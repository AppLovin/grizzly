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

package com.sun.grizzly;

import com.sun.grizzly.SelectionKeyOP.ConnectSelectionKeyOP;
import com.sun.grizzly.async.AsyncQueueReader;
import com.sun.grizzly.async.AsyncQueueWriter;
import com.sun.grizzly.async.TCPAsyncQueueWriter;
import com.sun.grizzly.async.TCPAsyncQueueReader;
import com.sun.grizzly.util.Cloner;
import com.sun.grizzly.util.Copyable;
import com.sun.grizzly.util.LinkedTransferQueue;
import com.sun.grizzly.util.SelectionKeyAttachment;
import com.sun.grizzly.util.State;
import com.sun.grizzly.util.StateHolder;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A SelectorHandler handles all java.nio.channels.Selector operations.
 * One or more instance of a Selector are handled by SelectorHandler.
 * The logic for processing of SelectionKey interest (OP_ACCEPT,OP_READ, etc.)
 * is usually defined using an instance of SelectorHandler.
 *
 * This class represents a TCP implementation of a SelectorHandler.
 * This class first bind a ServerSocketChannel to a TCP port and then start
 * waiting for NIO events.
 *
 * @author Jeanfrancois Arcand
 */
public class TCPSelectorHandler implements SelectorHandler, LinuxSpinningWorkaround {

    private int maxAcceptRetries = 5;

    /**
     * The ConnectorInstanceHandler used to return a new or pooled
     * ConnectorHandler
     */
    protected ConnectorInstanceHandler connectorInstanceHandler;


    /**
     * The list of {@link SelectionKeyOP} to register next time the
     * Selector.select is invoked.
     * can be combined read+write interest or Connect
     */
    protected final LinkedTransferQueue<SelectionKeyOP> opToRegister
            = new LinkedTransferQueue<SelectionKeyOP>();

    /**
     *  SelectionKeys to be registered with Read interest in the next Selector.select();
     */
    private final LinkedTransferQueue<SelectionKey> readOpToRegister
            = new LinkedTransferQueue<SelectionKey>();

    /**
     *  SelectionKeys to be registered with Write interest in the next Selector.select();
     */
    private final LinkedTransferQueue<SelectionKey> writeOpToRegister
            = new LinkedTransferQueue<SelectionKey>();

    /**
     * SelectionKeys to be registered with Read and Write interest in the next Selector.select();
     */
    private final LinkedTransferQueue<SelectionKey> readWriteOpToRegister
            = new LinkedTransferQueue<SelectionKey>();

    /**
     *  enqueued events from selectionkey attachment logic.
     */
    private List pendingIO = new ArrayList();


    /**
     * Max number of pendingIO tasks that will be executed per worker thread.
     */
    private int pendingIOlimitPerThread = 100;

    /**
     * True if selector thread should execute the pendingIO events.
     */
    private boolean finishIOUsingCurrentThread = true;


    /**
     * The socket tcpDelay.
     *
     * Default value for tcpNoDelay is disabled (set to true).
     */
    protected boolean tcpNoDelay = true;


    /**
     * The socket reuseAddress
     */
    protected boolean reuseAddress = true;


    /**
     * The socket linger.
     */
    protected int linger = -1;


    /**
     * The socket time out
     */
    protected int socketTimeout = -1;


    protected Logger logger;


    /**
     * The server socket time out
     */
    protected int serverTimeout = 0;


    /**
     * The inet address to use when binding.
     */
    protected InetAddress inet;


    /**
     * The default TCP port.
     */
    protected int port = 18888;


    /**
     * The ServerSocket instance.
     */
    protected ServerSocket serverSocket;


    /**
     * The ServerSocketChannel.
     */
    protected ServerSocketChannel serverSocketChannel;


    /**
     * The single Selector.
     */
    protected Selector selector;


    /**
     * The Selector time out.
     */
    protected long selectTimeout = 1000;


    /**
     * Server socket backlog.
     */
    protected int ssBackLog = 4096;


    /**
     * Is this used for client only or client/server operation.
     */
    protected Role role = Role.CLIENT_SERVER;


    /**
     * The SelectionKeyHandler associated with this SelectorHandler.
     */
    protected SelectionKeyHandler selectionKeyHandler;


    /**
     * The ProtocolChainInstanceHandler used by this instance. If not set, and instance
     * of the DefaultInstanceHandler will be created.
     */
    protected ProtocolChainInstanceHandler instanceHandler;


    /**
     * The {@link ExecutorService} used by this instance. If null -
     * {@link Controller}'s {@link ExecutorService} will be used
     */
    protected ExecutorService threadPool;


    /**
     * {@link AsyncQueueWriter}
     */
    protected AsyncQueueWriter asyncQueueWriter;


    /**
     * {@link AsyncQueueWriter}
     */
    protected AsyncQueueReader asyncQueueReader;


    /**
     * Attributes, associated with the {@link SelectorHandler} instance
     */
    protected Map<String, Object> attributes;

    /**
     * This {@link SelectorHandler} StateHolder, which is shared among
     * SelectorHandler and its clones
     */
    protected StateHolder<State> stateHolder = new StateHolder<State>(true);

    /**
     * Flag, which shows whether shutdown was called for this {@link SelectorHandler}
     */
    protected final AtomicBoolean isShutDown = new AtomicBoolean(false);

    private long lastSpinTimestamp;
    private int emptySpinCounter;
    private final WeakHashMap<Selector, Long> spinnedSelectorsHistory;
    private final Object spinSync;

    /**
     * The size to which to set the send buffer
     * If this value is not greater than 0, it is not used.
     */
    protected int sendBufferSize = -1;

    /**
     * The size to which to set the receive buffer
     * If this value is not greater than 0, it is not used.
     */
    protected int receiveBufferSize = -1;

    public TCPSelectorHandler(){
        this(Role.CLIENT_SERVER);
    }


    /**
     * Create a TCPSelectorHandler only used with ConnectorHandler.
     *
     * @param isClient true if this SelectorHandler is only used
     * to handle ConnectorHandler.
     */
    public TCPSelectorHandler(boolean isClient) {
        this(boolean2Role(isClient));
    }


    /**
     * Create a TCPSelectorHandler only used with ConnectorHandler.
     *
     * @param role the <tt>TCPSelectorHandler</tt> {@link Role}
     */
    public TCPSelectorHandler(Role role) {
        this.role = role;
        logger = Controller.logger();
        if (Controller.isLinux) {
            spinnedSelectorsHistory = new WeakHashMap<Selector, Long>();
            spinSync = new Object();
        } else {
            spinnedSelectorsHistory = null;
            spinSync = null;
        }
    }

    public void copyTo(Copyable copy) {
        TCPSelectorHandler copyHandler = (TCPSelectorHandler) copy;
        copyHandler.selector = selector;
        if (selectionKeyHandler != null) {
            copyHandler.setSelectionKeyHandler(Cloner.clone(selectionKeyHandler));
        }

        copyHandler.instanceHandler = instanceHandler;
        copyHandler.attributes = attributes;
        copyHandler.selectTimeout = selectTimeout;
        copyHandler.serverTimeout = serverTimeout;
        copyHandler.inet = inet;
        copyHandler.port = port;
        copyHandler.ssBackLog = ssBackLog;
        copyHandler.tcpNoDelay = tcpNoDelay;
        copyHandler.linger = linger;
        copyHandler.socketTimeout = socketTimeout;
        copyHandler.logger = logger;
        copyHandler.reuseAddress = reuseAddress;
        copyHandler.connectorInstanceHandler = connectorInstanceHandler;
        copyHandler.stateHolder = stateHolder;
        copyHandler.finishIOUsingCurrentThread = finishIOUsingCurrentThread;
        copyHandler.pendingIOlimitPerThread = pendingIOlimitPerThread;
    }


    /**
     * Return the set of SelectionKey registered on this Selector.
     */
    public Set<SelectionKey> keys(){
        if (selector != null){
            return selector.keys();
        } else {
            throw new IllegalStateException("Selector is not created!");
        }
    }


    /**
     * Is the Selector open.
     */
    public boolean isOpen(){
        if (selector != null){
            return selector.isOpen();
        } else {
            return false;
        }
    }


    /**
     * Before invoking {@link Selector#select}, make sure the {@link ServerSocketChannel}
     * has been created. If true, then register all {@link SelectionKey} to the {@link Selector}.
     * @param ctx {@link Context}
     */
    public void preSelect(Context ctx) throws IOException {
        if (asyncQueueReader == null) {
            asyncQueueReader = new TCPAsyncQueueReader(this);
        }

        if (asyncQueueWriter == null) {
            asyncQueueWriter = new TCPAsyncQueueWriter(this);
        }

        if (selector == null){
            initSelector(ctx);
        } else {
            processPendingOperations(ctx);
        }
    }

    /**
     * init the Selector
     * @param ctx
     * @throws java.io.IOException
     */
    private final void initSelector(Context ctx) throws IOException{
        try {
            isShutDown.set(false);

            connectorInstanceHandler = new ConnectorInstanceHandler.
                    ConcurrentQueueDelegateCIH(
                    getConnectorInstanceHandlerDelegate());

            // Create the socket listener
            selector = Selector.open();

            if (role != Role.CLIENT){
                serverSocketChannel = ServerSocketChannel.open();
                serverSocket = serverSocketChannel.socket();
                if( receiveBufferSize > 0 ) {
                    try {
                        serverSocket.setReceiveBufferSize( receiveBufferSize );
                    } catch( SocketException se ) {
                        if( logger.isLoggable( Level.FINE ) )
                            logger.log( Level.FINE, "setReceiveBufferSize exception ", se );
                    } catch( IllegalArgumentException iae ) {
                        if( logger.isLoggable( Level.FINE ) )
                            logger.log( Level.FINE, "setReceiveBufferSize exception ", iae );
                    }
                }
                serverSocket.setReuseAddress(reuseAddress);
                if ( inet == null){
                    serverSocket.bind(new InetSocketAddress(port),ssBackLog);
                } else {
                    serverSocket.bind(new InetSocketAddress(inet,port),ssBackLog);
                }

                serverSocketChannel.configureBlocking(false);
                serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

                serverSocket.setSoTimeout(serverTimeout);

                // Ephemeral support
                port = serverSocket.getLocalPort();
            }
            ctx.getController().notifyReady();
        } catch (SocketException ex){
            throw new BindException(ex.getMessage() + ": " + port + "=" + this);
        }
    }

    /**
     *
     * @param ctx
     * @throws java.io.IOException
     */
    protected void processPendingOperations(Context ctx) throws IOException {
        SelectionKeyOP operation;
        while((operation = opToRegister.poll()) != null) {
            int op = operation.getOp();
            if ((op & SelectionKey.OP_CONNECT) != 0) {
                onConnectOp(ctx, (SelectionKeyOP.ConnectSelectionKeyOP) operation);
            } else{
                SelectableChannel channel = operation.getChannel();
                if (channel.isOpen()){
                    selectionKeyHandler.register(channel,op);
                }
            }
        }

        SelectionKey key;
        while((key=readWriteOpToRegister.poll()) != null){
            if (Controller.isLinux) {
                key = checkIfSpinnedKey(key);
            }
            selectionKeyHandler.register(key, SelectionKey.OP_WRITE | SelectionKey.OP_READ);
        }

        while((key=writeOpToRegister.poll()) != null){
            if (Controller.isLinux) {
                key = checkIfSpinnedKey(key);
            }
            selectionKeyHandler.register(key, SelectionKey.OP_WRITE);
        }

        while((key=readOpToRegister.poll()) != null){
            if (Controller.isLinux) {
                key = checkIfSpinnedKey(key);
            }
            selectionKeyHandler.register(key, SelectionKey.OP_READ);
        }
    }

    private SelectionKey checkIfSpinnedKey(final SelectionKey key) {
        if (!key.isValid() && key.channel().isOpen() &&
                spinnedSelectorsHistory.containsKey(key.selector())) {
            final SelectionKey newKey = key.channel().keyFor(selector);
            newKey.attach(key.attachment());
            return newKey;
        }

        return key;
    }


    /**
     * Handle new OP_CONNECT ops.
     */
    protected void onConnectOp(Context ctx,
            SelectionKeyOP.ConnectSelectionKeyOP selectionKeyOp) throws IOException {
        SocketChannel socketChannel = (SocketChannel) selectionKeyOp.getChannel();
        SocketAddress remoteAddress = selectionKeyOp.getRemoteAddress();
        CallbackHandler callbackHandler = selectionKeyOp.getCallbackHandler();

        CallbackHandlerSelectionKeyAttachment attachment = new
                CallbackHandlerSelectionKeyAttachment(callbackHandler);

        SelectionKey key = socketChannel.register(selector, 0, attachment);
        attachment.associateKey(key);

        boolean isConnected;
        try {
            isConnected = socketChannel.connect(remoteAddress);
        } catch(Exception e) {
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "Exception occured when tried to connect socket", e);
            }

            // set isConnected to true to let callback handler to know about the problem happened
            isConnected = true;
        }

        // if channel was connected immediately or exception occured
        if (isConnected) {
            onConnectInterest(key, ctx);
        } else {
            key.interestOps(SelectionKey.OP_CONNECT);
        }
    }


    /**
     * Execute the Selector.select(...) operations.
     * @param ctx {@link Context}
     * @return {@link Set} of {@link SelectionKey}
     */
    public Set<SelectionKey> select(Context ctx) throws IOException{
        selector.select(selectTimeout);
        return selector.selectedKeys();
    }


    /**
     * Invoked after Selector.select().
     * @param ctx {@link Context}
     */
    public void postSelect(Context ctx) {
        selectionKeyHandler.expire(keys().iterator());

        if (pendingIO.size() > 0 ){
            final List tasks = pendingIO;
            // tests with upto 10K selectionkeys was faster with ArrayList then linkedlist
            // (did only test up to 10k)
            pendingIO = new ArrayList();
            int size = tasks.size();
            for (int x=0;x<size;){
                ctx.getController().executeUsingKernelExecutor(
                        doExecutePendiongIO(tasks,x,Math.min(x+=pendingIOlimitPerThread, size)));
            }
        }
    }

    /**
     * Perform the actual work.
     * @param tasks
     * @param start
     * @param end
     */
    private Runnable doExecutePendiongIO(final List tasks, final int start, final int end){
        Runnable r = new Runnable(){
            public void run() {
                for (int i=start;i<end;i++){
                    Object obj = tasks.get(i);
                    try{
                        if (obj instanceof SelectionKey){
                            selectionKeyHandler.close(((SelectionKey)obj));
                        }else{
                            ((Runnable)obj).run();
                        }
                    }catch(Throwable t){
                        logger.log(Level.FINEST, "doExecutePendiongIO failed.", t);
                    }
                }
            }
        };
        return r;
    }

    /**
     * {@inheritDoc}
     */
    public void addPendingIO(Runnable runnable){
        if (finishIOUsingCurrentThread){
            runnable.run();
        }else{
            pendingIO.add(runnable);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void addPendingKeyCancel(SelectionKey key){
        if (finishIOUsingCurrentThread){
            selectionKeyHandler.cancel(key);
        }else{
            pendingIO.add(key);
        }
    }


    /**
     * Register a SelectionKey to this Selector.<br>
     * Storing each interest type in different queues removes the need of wrapper (SelectionKeyOP)
     * while lowering thread contention due to the load is spread out on different queues.
     *
     * @param key
     * @param ops
     */
    public void register(SelectionKey key, int ops) {
        if (key == null) {
            throw new NullPointerException("SelectionKey parameter is null");
        }

        switch(ops){
            case SelectionKey.OP_READ: readOpToRegister.offer(key);  break;
            case SelectionKey.OP_WRITE: writeOpToRegister.offer(key); break;
            case SelectionKey.OP_WRITE | SelectionKey.OP_READ:
                readWriteOpToRegister.offer(key);  break;
            default:
                opToRegister.offer(new SelectionKeyOP(key,ops));
        }
        wakeUp();
    }

    public void register(SelectableChannel channel, int ops) {
        if (channel == null) {
            throw new NullPointerException("SelectableChannel parameter is null");
        }

        opToRegister.offer(new SelectionKeyOP(null,ops,channel));
        wakeUp();
    }

    /**
     * Workaround for NIO issue 6524172
     */
    private void wakeUp(){
        try{
            selector.wakeup();
        } catch (NullPointerException ne){
            // Swallow as this is a JDK issue.
        }
    }

    /**
     * Register a CallBackHandler to this Selector.
     *
     * @param remoteAddress remote address to connect
     * @param localAddress local address to bin
     * @param callbackHandler {@link CallbackHandler}
     * @throws java.io.IOException
     */
    protected void connect(SocketAddress remoteAddress, SocketAddress localAddress,
            CallbackHandler callbackHandler) throws IOException {
        SelectableChannel selectableChannel = getSelectableChannel( remoteAddress, localAddress );
        SelectionKeyOP.ConnectSelectionKeyOP keyOP = new ConnectSelectionKeyOP();
        keyOP.setOp(SelectionKey.OP_CONNECT);
        keyOP.setChannel(selectableChannel);
        keyOP.setRemoteAddress(remoteAddress);
        keyOP.setCallbackHandler(callbackHandler);
        opToRegister.offer(keyOP);
        wakeUp();
    }

    protected SelectableChannel getSelectableChannel( SocketAddress remoteAddress, SocketAddress localAddress ) throws IOException {
        SocketChannel newSocketChannel = SocketChannel.open();
        Socket newSocket = newSocketChannel.socket();
        if( receiveBufferSize > 0 ) {
            try {
                newSocket.setReceiveBufferSize( receiveBufferSize );
            } catch( SocketException se ) {
                if( logger.isLoggable( Level.FINE ) )
                    logger.log( Level.FINE, "setReceiveBufferSize exception ", se );
            } catch( IllegalArgumentException iae ) {
                if( logger.isLoggable( Level.FINE ) )
                    logger.log( Level.FINE, "setReceiveBufferSize exception ", iae );
            }
        }
        if( sendBufferSize > 0 ) {
            try {
                newSocket.setSendBufferSize( sendBufferSize );
            } catch( SocketException se ) {
                if( logger.isLoggable( Level.FINE ) )
                    logger.log( Level.FINE, "setSendBufferSize exception ", se );
            } catch( IllegalArgumentException iae ) {
                if( logger.isLoggable( Level.FINE ) )
                    logger.log( Level.FINE, "setSendBufferSize exception ", iae );
            }
        }
        newSocket.setReuseAddress( reuseAddress );
        if( localAddress != null )
            newSocket.bind( localAddress );
        newSocketChannel.configureBlocking( false );
        return newSocketChannel;
    }

    /**
     * {@inheritDoc}
     */
    public void pause() {
        stateHolder.setState(State.PAUSED);
    }

    /**
     * {@inheritDoc}
     */
    public void resume() {
        if (!State.PAUSED.equals(stateHolder.getState(false))) {
            throw new IllegalStateException("SelectorHandler is not in PAUSED state, but: " +
                    stateHolder.getState(false));
        }

        stateHolder.setState(State.STARTED);
    }

    /**
     * {@inheritDoc}
     */
    public StateHolder<State> getStateHolder() {
        return stateHolder;
    }

    /**
     * Shuntdown this instance by closing its Selector and associated channels.
     */
    public void shutdown() {
        // If shutdown was called for this SelectorHandler
        if (isShutDown.getAndSet(true)) return;

        stateHolder.setState(State.STOPPED);

        if (selector != null) {
            try {
                boolean isContinue = true;
                while(isContinue) {
                    try {
                        for(SelectionKey selectionKey : selector.keys()) {
                            selectionKeyHandler.close(selectionKey);
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

        try{
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
        } catch (Throwable ex){
            Controller.logger().log(Level.SEVERE,
                    "serverSocket.close",ex);
        }

        try{
            if (serverSocketChannel != null) {
                serverSocketChannel.close();
                serverSocketChannel = null;
            }
        } catch (Throwable ex){
            Controller.logger().log(Level.SEVERE,
                    "serverSocketChannel.close",ex);
        }

        try{
            if (selector != null)
                selector.close();
        } catch (Throwable ex){
            Controller.logger().log(Level.SEVERE,
                    "selector.close",ex);
        }

        if (asyncQueueReader != null) {
            asyncQueueReader.close();
            asyncQueueReader = null;
        }

        if (asyncQueueWriter != null) {
            asyncQueueWriter.close();
            asyncQueueWriter = null;
        }

        readOpToRegister.clear();
        writeOpToRegister.clear();
        opToRegister.clear();

        attributes = null;
    }

    /**
     * {@inheritDoc}
     */
    public SelectableChannel acceptWithoutRegistration(SelectionKey key) throws IOException {
        boolean isAccepted;
        int retryNum = 0;
        do {
            try {
                isAccepted = true;
                return ((ServerSocketChannel) key.channel()).accept();
            } catch (IOException ex) {
                if(!key.isValid()) throw ex;
                
                isAccepted = false;
                try {
                    // Let's try to recover here from too many open file
                    Thread.sleep(1000);
                } catch (InterruptedException ex1) {
                    throw new IOException(ex1.getMessage());
                }
                logger.log(Level.WARNING, "Exception accepting channel", ex);
            }
        } while (!isAccepted && retryNum++ < maxAcceptRetries);

        throw new IOException("Accept retries exceeded");
    }

    /**
     * Handle OP_ACCEPT.
     * @param ctx {@link Context}
     * @return always returns false
     */
    public boolean onAcceptInterest(SelectionKey key,
            Context ctx) throws IOException{
        SelectableChannel channel = acceptWithoutRegistration(key);

        if (channel != null) {
            configureChannel(channel);
            SelectionKey readKey =
                    channel.register(selector, SelectionKey.OP_READ);
            readKey.attach(System.currentTimeMillis());
        }
        return false;
    }

    /**
     * Handle OP_READ.
     * @param ctx {@link Context}
     * @param key {@link SelectionKey}
     * @return false if handled by a {@link CallbackHandler}, otherwise true
     */
    public boolean onReadInterest(final SelectionKey key,final Context ctx) throws IOException{
        // disable OP_READ on key before doing anything else
        key.interestOps(key.interestOps() & (~SelectionKey.OP_READ));

        if (asyncQueueReader.isReady(key)) {
            invokeAsyncQueueReader(pollContext(ctx, key, Context.OpType.OP_READ));
            return false;
        }
        Object attach = SelectionKeyAttachment.getAttachment(key);
        if (attach instanceof CallbackHandler){
            NIOContext context = pollContext(ctx, key, Context.OpType.OP_READ);
            invokeCallbackHandler((CallbackHandler) attach, context);
            return false;
        }
        return true;
    }


    /**
     * Handle OP_WRITE.
     *
     * @param key {@link SelectionKey}
     * @param ctx {@link Context}
     */
    public boolean onWriteInterest(final SelectionKey key,final Context ctx) throws IOException{
        // disable OP_WRITE on key before doing anything else
        key.interestOps(key.interestOps() & (~SelectionKey.OP_WRITE));

        if (asyncQueueWriter.isReady(key)) {
            invokeAsyncQueueWriter(pollContext(ctx, key, Context.OpType.OP_WRITE));
            return false;
        }
        Object attach = SelectionKeyAttachment.getAttachment(key);
        if (attach instanceof CallbackHandler){
            NIOContext context = pollContext(ctx, key, Context.OpType.OP_WRITE);
            invokeCallbackHandler((CallbackHandler) attach, context);
            return false;
        }
        return true;
    }


    /**
     * Handle OP_CONNECT.
     * @param key {@link SelectionKey}
     * @param ctx {@link Context}
     */
    public boolean onConnectInterest(final SelectionKey key, Context ctx)
    throws IOException{
        try {
            //logical replacement of 3 interest changes with 1 that is the complement to them.
            //if opaccept is known to not be of interest we could 0 the interest instead.
             key.interestOps(key.interestOps() & (SelectionKey.OP_ACCEPT) );
        } catch(CancelledKeyException e) {
            // Even if key was cancelled - we need to notify CallBackHandler
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "CancelledKeyException occured when tried to change key interests", e);
            }
        }

        Object attach = SelectionKeyAttachment.getAttachment(key);
        if (attach instanceof CallbackHandler){
            NIOContext context = pollContext(ctx, key, Context.OpType.OP_CONNECT);
            invokeCallbackHandler((CallbackHandler) attach, context);
        }
        return false;
    }


    /**
     * Invoke a CallbackHandler via a Context instance.
     * @param context {@link Context}
     * @throws java.io.IOException
     */
    protected void invokeCallbackHandler(CallbackHandler callbackHandler,
            NIOContext context) throws IOException {

        IOEvent<Context>ioEvent = new IOEvent.DefaultIOEvent<Context>(context);
        context.setIOEvent(ioEvent);

        // Added because of incompatibility with Grizzly 1.6.0
        context.setSelectorHandler(this);

        CallbackHandlerContextTask task =
                context.getCallbackHandlerContextTask(callbackHandler);
        boolean isRunInSeparateThread = true;

        if (callbackHandler instanceof CallbackHandlerDescriptor) {
            isRunInSeparateThread =
                    ((CallbackHandlerDescriptor) callbackHandler).
                    isRunInSeparateThread(context.getCurrentOpType());
        }
        context.execute(task, isRunInSeparateThread);
    }


    /**
     * Invoke a {@link AsyncQueueReader}
     * @param context {@link Context}
     * @throws java.io.IOException
     */
    protected void invokeAsyncQueueReader(NIOContext context) throws IOException {
        context.execute(context.getAsyncQueueReaderContextTask(asyncQueueReader));
    }


    /**
     * Invoke a {@link AsyncQueueWriter}
     * @param context {@link Context}
     * @throws java.io.IOException
     */
    protected void invokeAsyncQueueWriter(NIOContext context) throws IOException {
        context.execute(context.getAsyncQueueWriterContextTask(asyncQueueWriter));
    }


    /**
     * Return an instance of the default {@link ConnectorHandler},
     * which is the {@link TCPConnectorHandler}
     * @return {@link ConnectorHandler}
     */
    public ConnectorHandler acquireConnectorHandler(){
        if (selector == null || !selector.isOpen()){
            throw new IllegalStateException("SelectorHandler not yet started");
        }

        ConnectorHandler connectorHandler = connectorInstanceHandler.acquire();
        connectorHandler.setController(Controller.getHandlerController(this));
        return connectorHandler;
    }


    /**
     * Release a ConnectorHandler.
     */
    public void releaseConnectorHandler(ConnectorHandler connectorHandler){
        connectorInstanceHandler.release(connectorHandler);
    }


    /**
     * A token decribing the protocol supported by an implementation of this
     * interface
     */
    public Controller.Protocol protocol(){
        return Controller.Protocol.TCP;
    }
    // ------------------------------------------------------ Utils ----------//


    /**
     * {@inheritDoc}
     */
    public void configureChannel(SelectableChannel channel) throws IOException{
        Socket socket = ((SocketChannel) channel).socket();

        channel.configureBlocking(false);

        if (!channel.isOpen()){
            return;
        }

        try{
            if(socketTimeout >= 0 ) {
                socket.setSoTimeout(socketTimeout);
            }
        } catch (SocketException ex){
            if (logger.isLoggable(Level.FINE)){
                logger.log(Level.FINE,
                        "setSoTimeout exception ",ex);
            }
        }

        try{
            if(linger >= 0 ) {
                socket.setSoLinger( true, linger);
            }
        } catch (SocketException ex){
            if (logger.isLoggable(Level.FINE)){
                logger.log(Level.FINE,
                        "setSoLinger exception ",ex);
            }
        }

        try{
            socket.setTcpNoDelay(tcpNoDelay);
        } catch (SocketException ex){
            if (logger.isLoggable(Level.FINE)){
                logger.log(Level.FINE,
                        "setTcpNoDelay exception ",ex);
            }
        }

        if( receiveBufferSize > 0 ) {
            try {
                socket.setReceiveBufferSize( receiveBufferSize );
            } catch( SocketException se ) {
                if( logger.isLoggable( Level.FINE ) )
                    logger.log( Level.FINE, "setReceiveBufferSize exception ", se );
            } catch( IllegalArgumentException iae ) {
                if( logger.isLoggable( Level.FINE ) )
                    logger.log( Level.FINE, "setReceiveBufferSize exception ", iae );
            }
        }

        if( sendBufferSize > 0 ) {
            try {
                socket.setSendBufferSize( sendBufferSize );
            } catch( SocketException se ) {
                if( logger.isLoggable( Level.FINE ) )
                    logger.log( Level.FINE, "setSendBufferSize exception ", se );
            } catch( IllegalArgumentException iae ) {
                if( logger.isLoggable( Level.FINE ) )
                    logger.log( Level.FINE, "setSendBufferSize exception ", iae );
            }
        }

        try{
            socket.setReuseAddress(reuseAddress);
        } catch (SocketException ex){
            if (logger.isLoggable(Level.FINE)){
                logger.log(Level.FINE,
                        "setReuseAddress exception ",ex);
            }
        }
    }


    // ------------------------------------------------------ Properties -----//

    /**
     * Max number of pendingIO tasks that will be executed per worker thread.
     * @return
     */
    public int getPendingIOlimitPerThread() {
        return pendingIOlimitPerThread;
    }

    /**
     * Max number of pendingIO tasks that will be executed per worker thread.
     * @param pendingIOlimitPerThread
     */
    public void setPendingIOlimitPerThread(int pendingIOlimitPerThread) {
        this.pendingIOlimitPerThread = pendingIOlimitPerThread;
    }


    public final Selector getSelector() {
        return selector;
    }

    public final void setSelector(Selector selector) {
        this.selector = selector;
    }

    /**
     * {@inheritDoc}
     */
    public AsyncQueueReader getAsyncQueueReader() {
        return asyncQueueReader;
    }

    /**
     * {@inheritDoc}
     */
    public AsyncQueueWriter getAsyncQueueWriter() {
        return asyncQueueWriter;
    }

    public long getSelectTimeout() {
        return selectTimeout;
    }

    public void setSelectTimeout(long selectTimeout) {
        this.selectTimeout = selectTimeout;
    }

    public int getServerTimeout() {
        return serverTimeout;
    }

    public void setServerTimeout(int serverTimeout) {
        this.serverTimeout = serverTimeout;
    }

    public InetAddress getInet() {
        return inet;
    }

    public void setInet(InetAddress inet) {
        this.inet = inet;
    }

    /**
     * Gets this {@link SelectorHandler} current role.
     * <tt>TCPSelectorHandler</tt> could act as client, which corresponds to
     * {@link Role#CLIENT} or client-server, which corresponds
     * to the {@link Role#CLIENT_SERVER}
     *
     * @return the {@link Role}
     */
    public Role getRole() {
        return role;
    }

    /**
     * Sets this {@link SelectorHandler} current role.
     * <tt>TCPSelectorHandler</tt> could act as client, which corresponds to
     * {@link Role#CLIENT} or client-server, which corresponds
     * to the {@link Role#CLIENT_SERVER}
     *
     * @param role the {@link Role}
     */
    public void setRole(Role role) {
        this.role = role;
    }

    /**
     * Returns port number {@link SelectorHandler} is listening on
     * Similar to <code>getPort()</code>, but getting port number directly from
     * connection ({@link ServerSocket}, {@link DatagramSocket}).
     * So if default port number 0 was set during initialization, then <code>getPort()</code>
     * will return 0, but getPortLowLevel() will
     * return port number assigned by OS.
     *
     * @return port number or -1 if {@link SelectorHandler} was not initialized for accepting connections.
     * @deprecated Use {@link getPort}
     */
    public int getPortLowLevel() {
        if (serverSocket != null) {
            return serverSocket.getLocalPort();
        }

        return -1;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getSsBackLog() {
        return ssBackLog;
    }

    public void setSsBackLog(int ssBackLog) {
        this.ssBackLog = ssBackLog;
    }


    /**
     * Return the tcpNoDelay value used by the underlying accepted Sockets.
     *
     * Also see setTcpNoDelay(boolean tcpNoDelay)
     */
    public boolean isTcpNoDelay() {
        return tcpNoDelay;
    }


    /**
     * Enable (true) or disable (false) the underlying Socket's
     * tcpNoDelay.
     *
     * Default value for tcpNoDelay is enabled (set to true), as according to
     * the performance tests, it performs better for most cases.
     *
     * The Connector side should also set tcpNoDelay the same as it is set here
     * whenever possible.
     */
    public void setTcpNoDelay(boolean tcpNoDelay) {
        this.tcpNoDelay = tcpNoDelay;
    }

    public int getLinger() {
        return linger;
    }

    public void setLinger(int linger) {
        this.linger = linger;
    }

    public int getSocketTimeout() {
        return socketTimeout;
    }

    public void setSocketTimeout(int socketTimeout) {
        this.socketTimeout = socketTimeout;
    }

    public Logger getLogger() {
        return logger;
    }

    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    public boolean isReuseAddress() {
        return reuseAddress;
    }

    public void setReuseAddress(boolean reuseAddress) {
        this.reuseAddress = reuseAddress;
    }

    /**
     * {@inheritDoc}
     */
    public ExecutorService getThreadPool(){
        return threadPool;
    }


    /**
     * {@inheritDoc}
     */
    public void setThreadPool(ExecutorService threadPool){
        this.threadPool = threadPool;
    }


    /**
     * {@inheritDoc}
     */
    public Class<? extends SelectionKeyHandler> getPreferredSelectionKeyHandler() {
        return DefaultSelectionKeyHandler.class;
    }


    /**
     * Get the SelectionKeyHandler associated with this SelectorHandler.
     */
    public SelectionKeyHandler getSelectionKeyHandler() {
        return selectionKeyHandler;
    }


    /**
     * Set SelectionKeyHandler associated with this SelectorHandler.
     */
    public void setSelectionKeyHandler(SelectionKeyHandler selectionKeyHandler) {
        this.selectionKeyHandler = selectionKeyHandler;
        this.selectionKeyHandler.setSelectorHandler(this);
    }


    /**
     * Set the {@link ProtocolChainInstanceHandler} to use for
     * creating instance of {@link ProtocolChain}.
     */
    public void setProtocolChainInstanceHandler(ProtocolChainInstanceHandler
            instanceHandler){
        this.instanceHandler = instanceHandler;
    }


    /**
     * Return the {@link ProtocolChainInstanceHandler}
     */
    public ProtocolChainInstanceHandler getProtocolChainInstanceHandler(){
        return instanceHandler;
    }

    /**
     * {@inheritDoc}
     */
    public void closeChannel(SelectableChannel channel) {
        // channel could be either SocketChannel or ServerSocketChannel
        if (channel instanceof SocketChannel) {
            Socket socket = ((SocketChannel) channel).socket();

            try {
                if (!socket.isInputShutdown()) socket.shutdownInput();
            } catch (IOException e) {
                logger.log(Level.FINEST, "Unexpected exception during channel inputShutdown", e);
            }

            try {
                if (!socket.isOutputShutdown()) socket.shutdownOutput();
            } catch (IOException e) {
                logger.log(Level.FINEST, "Unexpected exception during channel outputShutdown", e);
            }

            try{
                socket.close();
            } catch (IOException e) {
                logger.log(Level.FINEST, "Unexpected exception during socket close", e);
            }
        }

        try{
            channel.close();
        } catch (IOException e){
            logger.log(Level.FINEST, "Unexpected exception during channel close", e);
        }

        if (asyncQueueReader != null) {
            asyncQueueReader.onClose(channel);
        }

        if (asyncQueueWriter != null) {
            asyncQueueWriter.onClose(channel);
        }
    }

    /**
     * Polls {@link Context} from pool and initializes it.
     *
     * @param serverContext {@link Controller} context
     * @param key {@link SelectionKey}
     * @return {@link Context}
     */
    protected NIOContext pollContext(final Context serverContext,
            final SelectionKey key, final Context.OpType opType) {
        Controller c = serverContext.getController();
        ProtocolChain protocolChain = instanceHandler != null ?
            instanceHandler.poll() :
            c.getProtocolChainInstanceHandler().poll();

        final NIOContext context = (NIOContext)c.pollContext();
        c.configureContext(key, opType, context, this);
        context.setProtocolChain(protocolChain);
        return context;
    }

    //--------------- ConnectorInstanceHandler -----------------------------
    /**
     * Return <Callable>factory<Callable> object, which knows how
     * to create {@link ConnectorInstanceHandler} corresponding to the protocol
     * @return <Callable>factory</code>
     */
    protected Callable<ConnectorHandler> getConnectorInstanceHandlerDelegate() {
        return new Callable<ConnectorHandler>() {
            public ConnectorHandler call() throws Exception {
                return new TCPConnectorHandler();
            }
        };
    }

    // ----------- AttributeHolder interface implementation ----------- //

    /**
     * Remove a key/value object.
     * Method is not thread safe
     *
     * @param key - name of an attribute
     * @return  attribute which has been removed
     */
    public Object removeAttribute(String key) {
        if (attributes == null) return null;

        return attributes.remove(key);
    }

    /**
     * Set a key/value object.
     * Method is not thread safe
     *
     * @param key - name of an attribute
     * @param value - value of named attribute
     */
    public void setAttribute(String key, Object value) {
        if (attributes == null) {
            attributes = new HashMap<String, Object>();
        }

        attributes.put(key, value);
    }

    /**
     * Return an object based on a key.
     * Method is not thread safe
     *
     * @param key - name of an attribute
     * @return - attribute value for the <tt>key</tt>, null if <tt>key</tt>
     *           does not exist in <tt>attributes</tt>
     */
    public Object getAttribute(String key) {
        if (attributes == null) return null;

        return attributes.get(key);
    }


    /**
     * Set a {@link Map} of attribute name/value pairs.
     * Old {@link AttributeHolder} values will not be available.
     * Later changes of this {@link Map} will lead to changes to the current
     * {@link AttributeHolder}.
     *
     * @param attributes - map of name/value pairs
     */
    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }


    /**
     * Return a {@link Map} of attribute name/value pairs.
     * Updates, performed on the returned {@link Map} will be reflected in
     * this {@link AttributeHolder}
     *
     * @return - {@link Map} of attribute name/value pairs
     */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /**
     * Returns the {@link Role}, depending on isClient value
     * @param isClient <tt>true>tt>, if this <tt>SelectorHandler</tt> works in
     *          the client mode, or <tt>false</tt> otherwise.
     * @return {@link Role}
     */
    protected static Role boolean2Role(boolean isClient) {
        if (isClient) return Role.CLIENT;

        return Role.CLIENT_SERVER;
    }

    /**
     * {@inheritDoc}
     */
    public void resetSpinCounter(){
        emptySpinCounter  = 0;
    }

    /**
     * {@inheritDoc}
     */
    public int getSpinRate(){
        if (emptySpinCounter++ == 0){
            lastSpinTimestamp = System.nanoTime();
        } else if (emptySpinCounter == 1000) {
            long deltatime = System.nanoTime() - lastSpinTimestamp;
            int contspinspersec = (int) (1000 * 1000000000L / deltatime);
            emptySpinCounter  = 0;
            return contspinspersec;
        }
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public void workaroundSelectorSpin() throws IOException {
        synchronized(spinSync) {
            spinnedSelectorsHistory.put(selector, System.currentTimeMillis());
            SelectorHandlerRunner.switchToNewSelector(this);
        }
    }


    /**
     * {@inheritDoc}
     */
    public SelectionKey keyFor(SelectableChannel channel) {
        if (Controller.isLinux) {
            synchronized(spinSync) {
                return channel.keyFor(selector);
            }
        } else {
            return channel.keyFor(selector);
        }
    }

    /**
     * Sets the <code>sendBufferSize</code> to the specified value
     *
     * @param size the size to which to set the send buffer. This value should be greater than 0.
     */
    public void setSendBufferSize( int size ) {
        this.sendBufferSize = size;
    }

    /**
     * Sets the <code>receiveBufferSize</code> to the specified value
     *
     * @param size the size to which to set the receive buffer. This value should be greater than 0.
     */
    public void setReceiveBufferSize( int size ) {
        this.receiveBufferSize = size;
    }
    
    /**
     * Return <tt>true</tt> by default, meaning the {@link WorkerThread} used to
     * execute the I/O operation will also complete the I/O operations. Setting to false
     * delegate the task to the kernel thread pool.
     *
     * @return the finishIOUsingCurrentThread
     */
    public boolean isFinishIOUsingCurrentThread() {
        return finishIOUsingCurrentThread;
    }

    /**
     * Set to false to use a the kernel thread pool to finish pending I/O operations,
     * like closing connection.
     *
     * @param finishIOUsingCurrentThread the finishIOUsingCurrentThread to set
     */
    public void setFinishIOUsingCurrentThread(boolean finishIOUsingCurrentThread) {
        this.finishIOUsingCurrentThread = finishIOUsingCurrentThread;
    }

    /**
     * Max number of accept() failures before abording.
     * @param maxAcceptRetries
     */
    public void setMaxAcceptRetries(int maxAcceptRetries){
        this.maxAcceptRetries = maxAcceptRetries;
    }
}
