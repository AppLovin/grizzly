/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2010 Sun Microsystems, Inc. All rights reserved.
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
 */

package com.sun.grizzly.websockets;

import com.sun.grizzly.LogMessages;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WebSocketClientApplication extends WebSocketApplication {
    static final Logger logger = Logger.getLogger(WebSocketEngine.WEBSOCKET);
    private final Selector selector;
    private final ExecutorService executorService = Executors.newFixedThreadPool(1);
    private AtomicBoolean running = new AtomicBoolean(true);
    private Queue<ClientNetworkHandler> handlers = new ConcurrentLinkedQueue<ClientNetworkHandler>();

    public WebSocketClientApplication() throws IOException {
        selector = SelectorProvider.provider().openSelector();
        final Thread thread = new Thread(new Runnable() {
            public void run() {
                select();
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public WebSocket createSocket(NetworkHandler handler, WebSocketListener... listeners) throws IOException {
        return new ClientWebSocket(handler, listeners);
    }

    public Future<WebSocket> connect(final String address, final WebSocketListener... listeners) throws IOException {
        final FutureTask<WebSocket> command = new WebSocketConnectTask(this, address, listeners);
        executorService.execute(command);
        return command;
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
    }
    
    private void select() {
        while (running.get()) {
            try {
                while(!handlers.isEmpty()) {
                    final ClientNetworkHandler handler = handlers.poll();
                    final SocketChannel socketChannel = handler.getChannel();
                    final SelectionKey key = socketChannel.register(getSelector(), SelectionKey.OP_CONNECT);
                    key.attach(handler);
                }
                selector.select();

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
            } catch (IOException e) {
                if (logger.isLoggable(Level.WARNING)) {
                    logger.log(Level.WARNING,
                               LogMessages.WARNING_GRIZZLY_WS_SELECT_ERROR(e.getMessage()),
                               e);
                }
            }
            selector.wakeup();
        }
    }


    public Selector getSelector() {
        return selector;
    }

}