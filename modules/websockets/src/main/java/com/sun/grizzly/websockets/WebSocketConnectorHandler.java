/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2010 Sun Microsystems, Inc. All rights reserved.
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

package com.sun.grizzly.websockets;

import com.sun.grizzly.Connection;
import com.sun.grizzly.Grizzly;
import com.sun.grizzly.Processor;
import com.sun.grizzly.impl.FutureImpl;
import com.sun.grizzly.impl.SafeFutureImpl;
import com.sun.grizzly.nio.transport.TCPNIOConnectorHandler;
import com.sun.grizzly.nio.transport.TCPNIOTransport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client-side {@link WebSocket} connector handler, which is used to initiate
 * client {@link WebSocket} connection.
 * 
 * @author Alexey Stashok
 */
public class WebSocketConnectorHandler {
    private static final Logger logger = Grizzly.logger(WebSocketConnectorHandler.class);

    private final TCPNIOTransport transport;
    private final Processor processor;

    /**
     * Construct a <tt>WebSocketConnectorHandler</tt> basing on the specific TCP {@link Transport} object.
     *
     * @param transport {@link TCPNIOTransport}
     */
    public WebSocketConnectorHandler(TCPNIOTransport transport) {
        this(transport, null);
    }

    /**
     * Construct a <tt>WebSocketConnectorHandler</tt> basing on the specific TCP {@link Transport} object.
     * The underlying Grizzly {@link Connection} will use a {@link Processor}, different from one
     * used by transport {@link TCPNIOTransport}.
     *
     * @param transport {@link TCPNIOTransport}
     * @param processor custom NIO events {@link Processor}
     */
    public WebSocketConnectorHandler(TCPNIOTransport transport,
            Processor processor) {
        
        this.transport = transport;
        this.processor = processor;
    }

    /**
     * Creates, initializes and connects {@link WebSocket} to the specific application.
     *
     * @param uri WebSocket application URL.
     * @param handler {@link WebSocketClientHandler}, which will handle {@link WebSocket}'s events.
     * 
     * @return {@link Future} of the connect operation, which could be used to get
     * resulting {@link WebSocket}.
     *
     * @throws java.io.IOException
     */
    public Future<WebSocket> connect(final URI uri, final WebSocketClientHandler handler)
            throws IOException, HandshakeException {

        return connect(new ClientWebSocketMeta(uri), handler);
    }

    /**
     * Creates, initializes and connects {@link WebSocket} to the specific application.
     *
     * @param meta {@link ClientWebSocketMeta}.
     * @param handler {@link WebSocketClientHandler}, which will handle {@link WebSocket}'s events.
     * 
     * @return {@link Future} of the connect operation, which could be used to get
     * resulting {@link WebSocket}.
     *
     * @throws java.io.IOException
     */
    public Future<WebSocket> connect(final ClientWebSocketMeta meta,
            final WebSocketClientHandler handler)
            throws IOException, HandshakeException {

        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, "connect websocket meta=" + meta);
        }

        final FutureImpl<WebSocket> future = SafeFutureImpl.create();
        // create a connect handler to following TCP connection process.
        final WebSocketConnectHandler connectHandler =
                new WebSocketConnectHandler(future);

        final WebSocketEngine engine = WebSocketEngine.getEngine();

        // Use custom ConnectorHandler to associate a WebSocket client connection context
        final TCPNIOConnectorHandler connectorHandler =
                new TCPNIOConnectorHandler(transport) {

            @Override
            protected void preConfigure(Connection connection) {
                super.preConfigure(connection);
                engine.setClientConnectContext(connection, meta, handler, connectHandler);
            }
        };

        connectorHandler.setProcessor(processor);
        
        final URI uri = meta.getURI();
        final String host = uri.getHost();
        final int port = uri.getPort();

        // start connect
        connectorHandler.connect(new InetSocketAddress(host, port), connectHandler);

        return future;
    }
}
