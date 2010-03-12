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

package com.sun.grizzly.samples.simpleauth;

import com.sun.grizzly.Connection;
import com.sun.grizzly.TransportFactory;
import com.sun.grizzly.filterchain.FilterChainBuilder;
import com.sun.grizzly.filterchain.TransportFilter;
import com.sun.grizzly.nio.transport.TCPNIOTransport;
import com.sun.grizzly.utils.StringFilter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Client implementation, which sends a message to a {@link Server} and checks
 * the response.
 * 
 * Client and server exachange String based messages:
 *
 * (1)
 * MultiLinePacket = command
 *                   *(parameter LF)
 *                   LF
 * parameter = TEXT (ASCII)
 *
 * Server filters are built in a following way:
 *
 * {@link TransportFilter} - reads/writes data from/to network
 * {@link StringFilter} - translates Buffer <-> String. StringFilter reads just single line at a time.
 * {@link MultiLineFilter} - translates String <-> MultiLinePacket (see 1)
 * {@link ClientAuthFilter} - checks, if client is authenticated. If not - initialize client authentication, and only then sends the message.
 * {@link ClientFilter} - client filter, which gets server echo and prints it out.
 *
 * @author Alexey Stashok
 */
public class Client {
    private static final Logger logger = Logger.getLogger(Client.class.getName());

    public static void main(String[] args) throws Exception {
        // Create a FilterChain using FilterChainBuilder
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        // Add TransportFilter, which is responsible
        // for reading and writing data to the connection
        filterChainBuilder.add(new TransportFilter());
        // StringFilter is responsible for parsing single string line
        filterChainBuilder.add(new StringFilter(Charset.forName("ASCII"), "\n"));
        // MultiStringFilter is responsible for gathering parsed lines in a single multi line packet
        filterChainBuilder.add(new MultiLineFilter(""));
        // AuthFilter is responsible for client authentication
        filterChainBuilder.add(new ClientAuthFilter());
        // Client filter, which prints out the server echo message
        filterChainBuilder.add(new ClientFilter());
        
        // Create TCP transport
        TCPNIOTransport transport = TransportFactory.getInstance().createTCPTransport();
        transport.setProcessor(filterChainBuilder.build());

        try {
            // start the transport
            transport.start();

            Future<Connection> connectFuture =
                    transport.connect(Server.HOST, Server.PORT);

            final Connection connection = connectFuture.get(10, TimeUnit.SECONDS);
            
            BufferedReader reader  = new BufferedReader(new InputStreamReader(System.in));

            while (true) {
                System.out.print("Type the message (Empty line for quit): ");
                String input = reader.readLine();

                if ("".equals(input)) {
                    break;
                }

                // Send echo message
                final MultiLinePacket request = MultiLinePacket.create("echo", input);
                logger.info("---------Client is sending the request:\n" + request);

                connection.write(request);
            }
            
        } finally {
            logger.info("Stopping transport...");
            // stop the transport
            transport.stop();

            // release TransportManager resources like ThreadPool
            TransportFactory.getInstance().close();
            logger.info("Stopped transport...");
        }
    }
}
