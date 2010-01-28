/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */

package com.sun.grizzly.nio;

import com.sun.grizzly.Strategy;
import com.sun.grizzly.Connection;
import com.sun.grizzly.Grizzly;
import com.sun.grizzly.IOEvent;
import com.sun.grizzly.Transport.State;
import com.sun.grizzly.utils.ExceptionHandler.Severity;
import com.sun.grizzly.utils.StateHolder;
import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.sun.grizzly.utils.LinkedTransferQueue;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Class is responsible for processing certain (single) {@link SelectorHandler}
 * 
 * @author Alexey Stashok
 */
public final class SelectorRunner implements Runnable {
    private final static Logger logger = Grizzly.logger(SelectorRunner.class);

    private final NIOTransport transport;
    private final StateHolder<State> stateHolder;
    
    private final Queue<SelectorHandlerTask> pendingTasks;
    private final Queue<SelectorHandlerTask> postponedTasks;

    private volatile Selector selector;
    private volatile Thread selectorRunnerThread;

    public SelectorRunner(NIOTransport transport) {
        this(transport, null);
    }

    public SelectorRunner(NIOTransport transport, Selector selector) {
        this.transport = transport;
        this.selector = selector;
        stateHolder = new StateHolder<State>(false, State.STOP);

        pendingTasks = new LinkedTransferQueue<SelectorHandlerTask>();
        postponedTasks = new LinkedList<SelectorHandlerTask>();
    }

    public NIOTransport getTransport() {
        return transport;
    }

    public Selector getSelector() {
        return selector;
    }

    public void setSelector(Selector selector) {
        this.selector = selector;
    }

    public Thread getRunnerThread() {
        return selectorRunnerThread;
    }

    public StateHolder<State> getStateHolder() {
        return stateHolder;
    }

    public State getState() {
        return stateHolder.getState();
    }
    
    public void postpone() {
        selectorRunnerThread = null;
        isResume = true;
    }

    public synchronized void start() {
        if (stateHolder.getState() != State.STOP) {
            logger.log(Level.WARNING,
                    "SelectorRunner is not in the stopped state!");
        }
        
        stateHolder.setState(State.STARTING);
        transport.getInternalThreadPool().execute(this);
    }
    
    public void startBlocking(int timeout) throws TimeoutException {
        start();
        final Future<State> future = stateHolder.notifyWhenStateIsEqual(
                State.START, null);
        try {
            future.get(5000, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
        } catch (InterruptedException e) {
        }
    }

    public synchronized void stop() {
        stateHolder.setState(State.STOPPING);
        
        wakeupSelector();

        pendingTasks.clear();
        postponedTasks.clear();
        
        if (selector != null) {
            try {
                boolean isContinue = true;
                while(isContinue) {
                    try {
                        for(SelectionKey selectionKey : selector.keys()) {
                            Connection connection =
                                    selectionKeyHandler.getConnectionForKey(
                                    selectionKey);
                            try {
                                connection.close();
                            } catch (IOException e) {
                            }
                        }

                        isContinue = false;
                    } catch (ConcurrentModificationException e) {
                        // ignore
                    }
                }
            } catch (ClosedSelectorException e) {
                // If Selector is already closed - OK
            }
        }
    }
        
    public void stopBlocking(int timeout) throws TimeoutException {
        stop();
        final Future<State> future = stateHolder.notifyWhenStateIsEqual(
                State.STOP, null);
        try {
            future.get(5000, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
        } catch (InterruptedException e) {
        }
    }
    
    public void wakeupSelector() {
        if (selector != null) {
            selector.wakeup();
        }
    }

    @Override
    public void run() {
        selectorRunnerThread = Thread.currentThread();
        if (!isResume) {
            selectorRunnerThread.setName(selectorRunnerThread.getName() +
                    " SelectorRunner");

            stateHolder.setState(State.START);
        }
        StateHolder<State> transportStateHolder = transport.getState();

        boolean isSkipping = false;
        
        try {
            while (!isSkipping && !isStop(false)) {
                if (transportStateHolder.getState(false) != State.PAUSE) {
                    isSkipping = !doSelect();
                } else {
                    try {
                        Future<State> future =
                                transportStateHolder.notifyWhenStateIsNotEqual(
                                State.PAUSE, null);
                        future.get(5000, TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                    }
                }
            }
        } finally {
            if (!isSkipping) {
                stateHolder.setState(State.STOP);
                selectorRunnerThread = null;
            }
        }
    }
    
    boolean isResume;

    SelectorHandler selectorHandler;
    SelectionKeyHandler selectionKeyHandler;
    Strategy strategy;
    
    int lastSelectedKeysCount;
    SelectionKey key = null;
    int keyEventProcessState;
    int keyReadyOps;

    Set<SelectionKey> readyKeys;
    Iterator<SelectionKey> iterator;

    /**
     * This method handle the processing of all Selector's interest op
     * (OP_ACCEPT,OP_READ,OP_WRITE,OP_CONNECT) by delegating to its Handler.
     * By default, all java.nio.channels.Selector operations are implemented
     * using SelectorHandler. All SelectionKey operations are implemented by
     * SelectionKeyHandler. Finally, ProtocolChain creation/re-use are implemented
     * by InstanceHandler.
     * @param selectorHandler - the <code>SelectorHandler</code>
     */
    protected boolean doSelect() {
        selectorHandler = transport.getSelectorHandler();
        selectionKeyHandler = transport.getSelectionKeyHandler();
        strategy = transport.getStrategy();
        
        try {

            if (isResume) {
                // If resume SelectorRunner - finish postponed keys
                isResume = false;
                if (keyReadyOps != 0) {
                    if (!iterateKeyEvents()) return false;
                }
                
                if (!iterateKeys()) return false;
            }

            lastSelectedKeysCount = 0;
            
            selectorHandler.preSelect(this);
            
            readyKeys = selectorHandler.select(this);

            if (stateHolder.getState(false) == State.STOPPING) return false;
            
            lastSelectedKeysCount = readyKeys.size();
            
            if (lastSelectedKeysCount != 0) {
                iterator = readyKeys.iterator();
                if (!iterateKeys()) return false;
            }

            selectorHandler.postSelect(this);
        } catch (ClosedSelectorException e) {
            if (isRunning(false)) {
                if (selectorHandler.onSelectorClosed(this)) {
                    return true;
                }
            }
            
            notifyConnectionException(key,
                    "Selector was unexpectedly closed", e,
                    Severity.TRANSPORT, Level.SEVERE, Level.FINE);
        } catch (Exception e) {
            notifyConnectionException(key,
                    "doSelect exception", e,
                    Severity.UNKNOWN, Level.SEVERE, Level.FINE);
        } catch (Throwable t) {
            logger.log(Level.SEVERE,"doSelect exception", t);
            transport.notifyException(Severity.FATAL, t);
        }

        return true;
    }

    private boolean iterateKeys() {
        while (iterator.hasNext()) {
            try {
                key = iterator.next();
                iterator.remove();
                if (key.isValid()) {
                    keyEventProcessState = 0;
                    keyReadyOps = key.readyOps();
                    if (!iterateKeyEvents()) return false;
                } else {
                    selectionKeyHandler.cancel(key);
                }
                
            } catch (IOException e) {
                keyEventProcessState = 0;
                keyReadyOps = 0;
                notifyConnectionException(key, "Unexpected IOException. Channel " + key.channel() + " will be closed.", e, Severity.CONNECTION, Level.WARNING, Level.FINE);
            } catch (CancelledKeyException e) {
                keyEventProcessState = 0;
                keyReadyOps = 0;
                notifyConnectionException(key, "Unexpected CancelledKeyException. Channel " + key.channel() + " will be closed.", e, Severity.CONNECTION, Level.FINE, Level.FINE);
            }
        }
        return true;
    }


    private boolean iterateKeyEvents()
            throws IOException {
        boolean isRunningInSameThread = true;
        while(keyReadyOps != 0 && isRunningInSameThread) {
            switch (keyEventProcessState) {
                // OP_READ
                case 0: {
                    int newReadyOps = keyReadyOps & (~SelectionKey.OP_READ);
                    if (newReadyOps != keyReadyOps) {
                        keyReadyOps = newReadyOps;
                        if (selectionKeyHandler.onReadInterest(key)) {
                            isRunningInSameThread = fire(key, IOEvent.READ);
                        }
                        keyEventProcessState = 2;
                        break;
                    }
                    
                    keyEventProcessState++;
                }

                // OP_ACCEPT
                case 1: {
                    int newReadyOps = keyReadyOps & (~SelectionKey.OP_ACCEPT);
                    if (newReadyOps != keyReadyOps) {
                        keyReadyOps = newReadyOps;
                        if (selectionKeyHandler.onAcceptInterest(key)) {
                            isRunningInSameThread = fire(key, IOEvent.SERVER_ACCEPT);
                        }
                        break;
                    }

                    keyEventProcessState++;
                }

                // OP_WRITE
                case 2: {
                    int newReadyOps = keyReadyOps & (~SelectionKey.OP_WRITE);
                    if (newReadyOps != keyReadyOps) {
                        keyReadyOps = newReadyOps;
                        if (selectionKeyHandler.onWriteInterest(key)) {
                            isRunningInSameThread = fire(key, IOEvent.WRITE);
                        }
                        break;
                    }

                    keyEventProcessState++;
                }

                // OP_CONNECT
                case 3: {
                    int newReadyOps = keyReadyOps & (~SelectionKey.OP_CONNECT);
                    if (newReadyOps != keyReadyOps) {
                        keyReadyOps = newReadyOps;
                        if (selectionKeyHandler.onConnectInterest(key)) {
                            isRunningInSameThread = fire(key, IOEvent.CONNECTED);
                        }
                        break;
                    }

                    keyEventProcessState++;
                    break;
                }

                // WRONG
                default:
                {
                    throw new IllegalStateException("SelectorRunner SelectionKey="
                            + key + " readyOps=" + keyReadyOps +
                            " state=" + keyEventProcessState);
                }
            }
        }
   
        return isRunningInSameThread;
    }

    private boolean fire(SelectionKey key, IOEvent ioEvent) throws IOException {
        Connection connection = selectionKeyHandler.getConnectionForKey(key);
        Object context = strategy.prepare(connection, ioEvent);
        transport.fireIOEvent(ioEvent, connection, context);
        return !strategy.isTerminateThread(context);
    }

    public Queue<SelectorHandlerTask> getPendingTasks() {
        return pendingTasks;
    }

    public Queue<SelectorHandlerTask> getPostponedTasks() {
        return postponedTasks;
    }

    private boolean isStop(boolean isBlockingCompare) {
        State state = stateHolder.getState(isBlockingCompare);

        if (state == State.STOP
                    || state == State.STOPPING) return true;
        
        state = transport.getState().getState(isBlockingCompare);
        
        return state == State.STOP
                    || state == State.STOPPING; 
    }
    
    private boolean isRunning(boolean isBlockingCompare) {
        return stateHolder.getState(isBlockingCompare) == State.START &&
                transport.getState().getState(isBlockingCompare) == State.START;
    }

    /**
     * Notify transport about the {@link Exception} happened, log it and cancel
     * the {@link SelectionKey}, if it is not null
     * 
     * @param key {@link SelectionKey}, which was processed, when the {@link Exception} occured
     * @param description error description
     * @param e {@link Exception} occured
     * @param severity {@link Severity} of occured problem
     * @param runLogLevel logger {@link Level} to use, if transport is in running state
     * @param stoppedLogLevel logger {@link Level} to use, if transport is not in running state
     */
    private void notifyConnectionException(SelectionKey key, String description, 
            Exception e, Severity severity, Level runLogLevel,
            Level stoppedLogLevel) {
        if (isRunning(false)) {
            logger.log(runLogLevel, description, e);

            if (key != null) {
                try {
                    transport.getSelectionKeyHandler().cancel(key);
                } catch (IOException cancelException) {
                    logger.log(Level.FINE, "IOException during cancelling key",
                            cancelException);
                }
            }

            transport.notifyException(severity, e);
        } else {
            logger.log(stoppedLogLevel, description, e);
        }
    }

    /**
     * Number of {@link SelectionKey}s, which were selected last time.
     * Operation is not thread-safe.
     *
     * @return number of {@link SelectionKey}s, which were selected last time.
     */
    public int getLastSelectedKeysCount() {
        return lastSelectedKeysCount;
    }

    protected final void switchToNewSelector() throws IOException {
        final Selector oldSelector = selector;
        final Selector newSelector = SelectorFactory.instance().create();

        final Set<SelectionKey> keys = oldSelector.keys();
        for (final SelectionKey selectionKey : keys) {
            try {
                selectionKey.channel().register(newSelector,
                        selectionKey.interestOps(), selectionKey.attachment());
            } catch (Exception e) {
                logger.log(Level.FINE, "Error switching channel to a new selector", e);
            }
        }

        selector = newSelector;

        try {
            oldSelector.close();
        } catch (Exception e) {
        }
    }
}
