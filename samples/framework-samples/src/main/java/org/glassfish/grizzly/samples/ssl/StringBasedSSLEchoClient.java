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

package org.glassfish.grizzly.samples.ssl;

import com.sun.grizzly.Connection;
import com.sun.grizzly.EmptyCompletionHandler;
import com.sun.grizzly.filterchain.FilterChainContext;
import com.sun.grizzly.filterchain.NextAction;
import java.io.IOException;
import java.net.URL;
import com.sun.grizzly.TransportFactory;
import com.sun.grizzly.filterchain.BaseFilter;
import com.sun.grizzly.filterchain.Filter;
import com.sun.grizzly.filterchain.FilterChain;
import com.sun.grizzly.filterchain.FilterChainBuilder;
import com.sun.grizzly.filterchain.TransportFilter;
import com.sun.grizzly.nio.transport.TCPNIOTransport;
import com.sun.grizzly.ssl.SSLContextConfigurator;
import com.sun.grizzly.ssl.SSLEngineConfigurator;
import com.sun.grizzly.ssl.SSLFilter;
import com.sun.grizzly.utils.StringFilter;
import javax.net.ssl.SSLEngine;

/**
 * The simple {@link FilterChain} based SSL client, which sends a message to
 * the echo server and waits for response. In this sample we add
 * a {@link StringFilter} to a {@link FilterChain}, so there is no need to do
 * Buffer <-> String transformation explicitly.
 *
 * @see StringFilter
 * @see SSLFilter
 * @see SSLContextConfigurator
 * @see SSLEngineConfigurator
 *
 * @author Alexey Stashok
 */
public class StringBasedSSLEchoClient {
    private static final String MESSAGE = "Hello World!";

    public static void main(String[] args) throws IOException {
        // Create a FilterChain using FilterChainBuilder
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        // Add TransportFilter, which is responsible
        // for reading and writing data to the connection
        filterChainBuilder.add(new TransportFilter());

        // Initialize and add SSLFilter
        final SSLEngineConfigurator serverConfig = initializeSSL();
        final SSLEngineConfigurator clientConfig = serverConfig.clone().setClientMode(true);

        final SSLFilter sslFilter = new SSLFilter(serverConfig, clientConfig);
        filterChainBuilder.add(sslFilter);

        // Add StringFilter, which will be responsible for Buffer <-> String transformation
        filterChainBuilder.add(new StringFilter());

        // Add Filter, which will send a greeting message and check the result
        filterChainBuilder.add(new SendMessageFilter(sslFilter));

        // Create TCP transport
        TCPNIOTransport transport = TransportFactory.getInstance().createTCPTransport();
        transport.setProcessor(filterChainBuilder.build());

        try {
            // start the transport
            transport.start();

            // perform async. connect to the server
            transport.connect(SSLEchoServer.HOST, SSLEchoServer.PORT);

            System.out.println("Press any key to stop the client...");
            System.in.read();
        } finally {
            System.out.println("Stopping transport...");
            // stop the transport
            transport.stop();

            // release TransportManager resources like ThreadPool
            TransportFactory.getInstance().close();
            System.out.println("Stopped transport...");
        }
    }

    /**
     * The {@link Filter}, responsible for handling client {@link Connection}
     * events.
     */
    private static class SendMessageFilter extends BaseFilter {
        
        private final SSLFilter sslFilter;

        public SendMessageFilter(SSLFilter sslFilter) {
            this.sslFilter = sslFilter;
        }

        /**
         * Handle newly connected {@link Connection}, perform SSL handshake and
         * send greeting message to a server.
         *
         * @param ctx {@link FilterChain} context
         * @return nextAction
         * @throws IOException
         */
        @Override
        public NextAction handleConnect(FilterChainContext ctx)
                throws IOException {
            final Connection connection = ctx.getConnection();

            // Execute async SSL handshake
            sslFilter.handshake(connection,
                    new EmptyCompletionHandler<SSLEngine>() {

                /**
                 * Once SSL handshake will be completed - send greeting message
                 */
                @Override
                public void completed(SSLEngine result) {
                    try {
                        // Here we send String directly
                        connection.write(MESSAGE);
                    } catch (IOException e) {
                        try {
                            connection.close();
                        } catch (IOException ex) {
                        }
                    }
                }
            });

            return ctx.getInvokeAction();
        }

        /**
         * Handle server response and check, whether it has expected data
         * @param ctx {@link FilterChain} context
         * @return nextAction
         * @throws IOException
         */
        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {

            // The received message is String
            final String message = (String) ctx.getMessage();

            // Check the message
            if (MESSAGE.equals(message)) {
                System.out.println("Got echo message: \"" + message + "\"");
            } else {
                System.out.println("Got unexpected echo message: \"" +
                        message + "\"");
            }

            return ctx.getStopAction();
        }

    }

    /**
     * Initialize server side SSL configuration.
     * 
     * @return server side {@link SSLEngineConfigurator}.
     */
    private static SSLEngineConfigurator initializeSSL() {
        // Initialize SSLContext configuration
        SSLContextConfigurator sslContextConfig = new SSLContextConfigurator();

        // Set key store
        ClassLoader cl = StringBasedSSLEchoClient.class.getClassLoader();
        URL cacertsUrl = cl.getResource("ssltest-cacerts.jks");
        if (cacertsUrl != null) {
            sslContextConfig.setTrustStoreFile(cacertsUrl.getFile());
            sslContextConfig.setTrustStorePass("changeit");
        }

        // Set trust store
        URL keystoreUrl = cl.getResource("ssltest-keystore.jks");
        if (keystoreUrl != null) {
            sslContextConfig.setKeyStoreFile(keystoreUrl.getFile());
            sslContextConfig.setKeyStorePass("changeit");
        }


        // Create SSLEngine configurator
        return new SSLEngineConfigurator(sslContextConfig.createSSLContext(),
                false, false, false);
    }
}
