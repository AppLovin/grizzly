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

package com.sun.grizzly.samples.websockets;

import com.sun.grizzly.TransportFactory;
import com.sun.grizzly.filterchain.FilterChainBuilder;
import com.sun.grizzly.filterchain.TransportFilter;
import com.sun.grizzly.http.HttpServerFilter;
import com.sun.grizzly.nio.transport.TCPNIOTransport;
import com.sun.grizzly.websockets.WebSocketApplication;
import com.sun.grizzly.websockets.WebSocketEngine;
import com.sun.grizzly.websockets.WebSocketFilter;

/**
 * Standalone Java web-socket chat server implementation.
 * Server expects to get the path to webapp as command line parameter
 *
 * @author Alexey Stashok
 */
public class ChatWebSocketServer {
    // the port to listen on
    public static final int PORT = 8080;
    
    public static void main(String[] args) throws Exception {
        // Server expects to get the path to webapp as command line parameter
        if (args.length < 1) {
            System.out.println("Please provide a path to webapp in the command line");
            System.exit(0);
        }

        final String webappDir = args[0];

        // Initiate the server filterchain to work with websockets
        FilterChainBuilder serverFilterChainBuilder = FilterChainBuilder.stateless();
        // Transport filter
        serverFilterChainBuilder.add(new TransportFilter());
        // HTTP server side filter
        serverFilterChainBuilder.add(new HttpServerFilter());
        // WebSocket filter to intercept websocket messages
        serverFilterChainBuilder.add(new WebSocketFilter());
        // Simple Web server filter to process requests to a static resources
        serverFilterChainBuilder.add(new SimpleWebServerFilter(webappDir));

        // initialize transport
        TCPNIOTransport transport = TransportFactory.getInstance().createTCPTransport();
        transport.setProcessor(serverFilterChainBuilder.build());

        // initialize websocket chat application
        final WebSocketApplication chatApplication = new ChatApplication();

        // register the application
        WebSocketEngine.getEngine().registerApplication("/grizzly-websockets-chat/chat", chatApplication);

        try {
            // bind TCP listener
            transport.bind(PORT);
            // start the transport
            transport.start();


            System.out.println("Press any key to stop the server...");
            System.in.read();
        } finally {
            // stop the transport
            transport.stop();
            TransportFactory.getInstance().close();
        }
    }
}
