/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
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
 */

package com.sun.grizzly.websockets;

import com.sun.grizzly.util.LogMessages;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientWebSocketApplication extends WebSocketApplication {
    static final Logger logger = Logger.getLogger(WebSocketEngine.WEBSOCKET);
    private final Selector selector;
    private final ExecutorService executorService = Executors.newFixedThreadPool(1);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Queue<ClientNetworkHandler> handlers = new ConcurrentLinkedQueue<ClientNetworkHandler>();
    private final Thread selectorThread;

    protected volatile long selectTimeout = 1000;
    private final String address;

    public ClientWebSocketApplication(final String addr) throws IOException {
        address = addr;
        selector = SelectorProvider.provider().openSelector();
        selectorThread = new Thread(new Runnable() {
            public void run() {
                select();
            }
        });
        selectorThread.setDaemon(true);
    }

    public String getAddress() {
        return address;
    }

    @Override
    public WebSocket createSocket(NetworkHandler handler, WebSocketListener... listeners) throws IOException {
        return new ClientWebSocket(handler, listeners);
    }

    public WebSocket connect(long timeout, TimeUnit unit, final WebSocketListener... listeners) throws IOException {
        synchronized (selectorThread) {
            if (!selectorThread.isAlive()) {
                selectorThread.start();
            }
        }
        final FutureTask<WebSocket> command = new WebSocketConnectTask(this, address, listeners);
        executorService.execute(command);
        try {
            return command.get(timeout, unit);
        } catch (InterruptedException e) {
            throw new IOException(e.getMessage());
        } catch (ExecutionException e) {
            throw new IOException(e.getMessage());
        } catch (TimeoutException e) {
            throw new IOException(e.getMessage());
        }
    }

    public WebSocket connect(final WebSocketListener... listeners) throws IOException {
        return connect(WebSocketEngine.DEFAULT_TIMEOUT, TimeUnit.SECONDS, listeners);
    }

    public void stop() throws IOException {
        if (running.getAndSet(false)) {
            for (WebSocket socket : getWebSockets()) {
                socket.close();
            }
        }
    }

    public void register(ClientNetworkHandler handler) {
        handlers.add(handler);
        selector.wakeup();
    }

    private void select() {
        while (running.get()) {
            try {
                while (!handlers.isEmpty()) {
                    final ClientNetworkHandler handler = handlers.poll();
                    final SocketChannel socketChannel = handler.getChannel();
                    if (socketChannel.isConnected()) { // Channel was immediately connected
                        socketChannel.register(getSelector(), SelectionKey.OP_READ, handler);
                        handler.doConnect(false);
                    } else { // Channel we be connected in NIO fashion
                        socketChannel.register(getSelector(), SelectionKey.OP_CONNECT, handler);
                    }
                }

                final int count = selector.select(selectTimeout);
                if (count != 0) {
                    Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();
                    while (selectedKeys.hasNext()) {
                        SelectionKey key = selectedKeys.next();
                        selectedKeys.remove();
                        final ClientNetworkHandler handler = (ClientNetworkHandler) key.attachment();
                        try {
                            handler.process(key);
                        } catch (IOException e) {
                            handler.shutdown();
                        }
                    }
                }
            } catch (IOException e) {
                if (logger.isLoggable(Level.WARNING)) {
                    logger.log(Level.WARNING,
                            LogMessages.WARNING_GRIZZLY_WS_SELECT_ERROR(e.getMessage()),
                            e);
                }
            }
        }
    }

    public void execute(Runnable runnable) {
        executorService.execute(runnable);
    }

    public Selector getSelector() {
        return selector;
    }

    protected long getSelectTimeout() {
        return selectTimeout;
    }

    protected void setSelectTimeout(long selectTimeout) {
        this.selectTimeout = selectTimeout;
    }
}
