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
package com.sun.grizzly;

import com.sun.grizzly.attributes.Attribute;
import com.sun.grizzly.filterchain.Filter;
import com.sun.grizzly.filterchain.BaseFilter;
import com.sun.grizzly.filterchain.FilterChainBuilder;
import com.sun.grizzly.filterchain.FilterChainContext;
import com.sun.grizzly.filterchain.NextAction;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.sun.grizzly.filterchain.TransportFilter;
import com.sun.grizzly.impl.FutureImpl;
import com.sun.grizzly.impl.SafeFutureImpl;
import com.sun.grizzly.memory.MemoryManager;
import com.sun.grizzly.memory.MemoryUtils;
import com.sun.grizzly.nio.transport.TCPNIOTransport;
import com.sun.grizzly.ssl.SSLContextConfigurator;
import com.sun.grizzly.ssl.SSLEngineConfigurator;
import com.sun.grizzly.ssl.SSLFilter;
import com.sun.grizzly.ssl.SSLStreamReader;
import com.sun.grizzly.ssl.SSLStreamWriter;
import com.sun.grizzly.streams.StreamReader;
import com.sun.grizzly.streams.StreamWriter;
import com.sun.grizzly.utils.ChunkingFilter;
import com.sun.grizzly.utils.EchoFilter;
import com.sun.grizzly.utils.StringFilter;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLEngine;

/**
 * Set of SSL tests
 * 
 * @author Alexey Stashok
 */
public class SSLTest extends GrizzlyTestCase {
    private final static Logger logger = Grizzly.logger(SSLTest.class);
    
    public static final int PORT = 7779;

    public void testSimpleSyncSSL() throws Exception {
        doTestSSL(true, 1, 1, 0);
    }

    public void testSimpleAsyncSSL() throws Exception {
        doTestSSL(false, 1, 1, 0);
    }

    public void test5PacketsOn1ConnectionSyncSSL() throws Exception {
        doTestSSL(true, 1, 5, 0);
    }

    public void test5PacketsOn1ConnectionAsyncSSL() throws Exception {
        doTestSSL(false, 1, 5, 0);
    }

    public void test5PacketsOn5ConnectionsSyncSSL() throws Exception {
        doTestSSL(true, 5, 5, 0);
    }

    public void test5PacketsOn5ConnectionsAsyncSSL() throws Exception {
        doTestSSL(false, 5, 5, 0);
    }

    public void testSimpleSyncSSLChunkedBefore() throws Exception {
        doTestSSL(true, 1, 1, 1, new ChunkingFilter(1));
    }

    public void testSimpleAsyncSSLChunkedBefore() throws Exception {
        doTestSSL(false, 1, 1, 1, new ChunkingFilter(1));
    }

    public void testSimpleSyncSSLChunkedAfter() throws Exception {
        doTestSSL(true, 1, 1, 2, new ChunkingFilter(1));
    }

    public void testSimpleAsyncSSLChunkedAfter() throws Exception {
        doTestSSL(false, 1, 1, 2, new ChunkingFilter(1));
    }

    public void testPingPongFilterChainSync() throws Exception {
        doTestPingPongFilterChain(true, 5, 0);
    }

    public void testPingPongFilterChainAsync() throws Exception {
        doTestPingPongFilterChain(false, 5, 0);
    }

    public void testPingPongFilterChainSyncChunked() throws Exception {
        doTestPingPongFilterChain(true, 5, 1, new ChunkingFilter(1));
    }

    public void testPingPongFilterChainAsyncChunked() throws Exception {
        doTestPingPongFilterChain(false, 5, 1, new ChunkingFilter(1));
    }

    public void testSimplePendingSSLClientWrites() throws Exception {
        doTestPendingSSLClientWrites(1, 1);
    }

    public void test20on1PendingSSLClientWrites() throws Exception {
        doTestPendingSSLClientWrites(1, 20);
    }

    public void test20On5PendingSSLClientWrites() throws Exception {
        doTestPendingSSLClientWrites(5, 20);
    }

    protected void doTestPingPongFilterChain(boolean isBlocking,
            int turnAroundsNum, int filterIndex, Filter... filters)
            throws Exception {

        final Integer pingPongTurnArounds = turnAroundsNum;
        
        Connection connection = null;
        SSLContextConfigurator sslContextConfigurator = createSSLContextConfigurator();
        SSLEngineConfigurator clientSSLEngineConfigurator = null;
        SSLEngineConfigurator serverSSLEngineConfigurator = null;

        if (sslContextConfigurator.validateConfiguration(true)) {
            clientSSLEngineConfigurator =
                    new SSLEngineConfigurator(sslContextConfigurator.createSSLContext());
            serverSSLEngineConfigurator =
                    new SSLEngineConfigurator(sslContextConfigurator.createSSLContext(),
                    false, false, false);
        } else {
            fail("Failed to validate SSLContextConfiguration.");
        }
        final SSLFilter sslFilter = new SSLFilter(serverSSLEngineConfigurator,
                clientSSLEngineConfigurator);
        final SSLPingPongFilter pingPongFilter = new SSLPingPongFilter(
                sslFilter, pingPongTurnArounds);

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(sslFilter);
        filterChainBuilder.add(new StringFilter());
        filterChainBuilder.add(pingPongFilter);
        filterChainBuilder.addAll(filterIndex, filters);

        TCPNIOTransport transport =
                TransportFactory.getInstance().createTCPTransport();
        transport.setProcessor(filterChainBuilder.build());

        try {
            transport.bind(PORT);
            transport.start();

            transport.configureBlocking(isBlocking);

            Future<Connection> future = transport.connect("localhost", PORT);
            connection = future.get(10, TimeUnit.SECONDS);
            
            assertTrue(connection != null);

            try {
                assertEquals(pingPongTurnArounds,
                        pingPongFilter.getServerCompletedFeature().get(
                        10, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                logger.severe("Server timeout");
            }

            assertEquals(pingPongTurnArounds,
                    pingPongFilter.getClientCompletedFeature().get(
                    10, TimeUnit.SECONDS));
            
            connection.close();
            connection = null;
        } finally {
            if (connection != null) {
                connection.close();
            }

            transport.stop();
            TransportFactory.getInstance().close();
        }

    }
    
    public void doTestSSL(boolean isBlocking, int connectionsNum,
            int packetsNumber, int filterIndex, Filter... filters) throws Exception {
        Connection connection = null;
        SSLContextConfigurator sslContextConfigurator = createSSLContextConfigurator();
        SSLEngineConfigurator clientSSLEngineConfigurator = null;
        SSLEngineConfigurator serverSSLEngineConfigurator = null;

        if (sslContextConfigurator.validateConfiguration(true)) {
            clientSSLEngineConfigurator =
                    new SSLEngineConfigurator(sslContextConfigurator.createSSLContext());
            serverSSLEngineConfigurator =
                    new SSLEngineConfigurator(sslContextConfigurator.createSSLContext(),
                    false, false, false);
        } else {
            fail("Failed to validate SSLContextConfiguration.");
        }

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new SSLFilter(serverSSLEngineConfigurator,
                clientSSLEngineConfigurator));
        filterChainBuilder.add(new EchoFilter());
        filterChainBuilder.addAll(filterIndex, filters);

        TCPNIOTransport transport =
                TransportFactory.getInstance().createTCPTransport();
        transport.setProcessor(filterChainBuilder.build());

        SSLStreamReader reader = null;
        SSLStreamWriter writer = null;

        try {
            transport.bind(PORT);
            transport.start();

            transport.configureBlocking(isBlocking);

            for (int i = 0; i < connectionsNum; i++) {
                Future<Connection> future = transport.connect("localhost", PORT);
                connection = future.get(10, TimeUnit.SECONDS);
                assertTrue(connection != null);

                connection.configureStandalone(true);
                connection.setReadTimeout(10, TimeUnit.SECONDS);

                StreamReader connectionStreamReader =
                        StandaloneProcessor.INSTANCE.getStreamReader(connection);
                StreamWriter connectionStreamWriter =
                        StandaloneProcessor.INSTANCE.getStreamWriter(connection);
                
                reader = new SSLStreamReader(connectionStreamReader);
                writer = new SSLStreamWriter(connectionStreamWriter);

                final Future handshakeFuture = writer.handshake(reader,
                        clientSSLEngineConfigurator);

                handshakeFuture.get(10, TimeUnit.SECONDS);
                assertTrue(handshakeFuture.isDone());

                for (int j = 0; j < packetsNumber; j++) {
                    try {
                        byte[] sentMessage = ("Hello world! Connection#" + i + " Packet#" + j).getBytes();

                        // aquire read lock to not allow incoming data to be processed by Processor
                        writer.writeByteArray(sentMessage);
                        Future writeFuture = writer.flush();

                        writeFuture.get(10, TimeUnit.SECONDS);
                        assertTrue("Write timeout", writeFuture.isDone());

                        byte[] receivedMessage = new byte[sentMessage.length];

                        Future readFuture = reader.notifyAvailable(receivedMessage.length);
                        readFuture.get(10, TimeUnit.SECONDS);
                        assertTrue(readFuture.isDone());

                        reader.readByteArray(receivedMessage);

                        String sentString = new String(sentMessage);
                        String receivedString = new String(receivedMessage);
                        assertEquals(sentString, receivedString);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error occurred when testing connection#" + i + " packet#" + j);
                        throw e;
                    }
                }
                
                reader.close();
                reader = null;
                
                writer.close();
                writer = null;
                
                connection.close();
                connection = null;
            }
        } finally {
            if (reader != null) {
                reader.close();
            }

            if (writer != null) {
                writer.close();
            }
            if (connection != null) {
                connection.close();
            }

            transport.stop();
            TransportFactory.getInstance().close();
        }
    }

    public void doTestPendingSSLClientWrites(int connectionsNum,
            int packetsNumber) throws Exception {
        Connection connection = null;
        SSLContextConfigurator sslContextConfigurator = createSSLContextConfigurator();
        SSLEngineConfigurator clientSSLEngineConfigurator = null;
        SSLEngineConfigurator serverSSLEngineConfigurator = null;

        if (sslContextConfigurator.validateConfiguration(true)) {
            clientSSLEngineConfigurator =
                    new SSLEngineConfigurator(sslContextConfigurator.createSSLContext());
            serverSSLEngineConfigurator =
                    new SSLEngineConfigurator(sslContextConfigurator.createSSLContext(),
                    false, false, false);
        } else {
            fail("Failed to validate SSLContextConfiguration.");
        }

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new SSLFilter(serverSSLEngineConfigurator,
                clientSSLEngineConfigurator));
        filterChainBuilder.add(new EchoFilter());

        TCPNIOTransport transport =
                TransportFactory.getInstance().createTCPTransport();
        transport.setProcessor(filterChainBuilder.build());

        final MemoryManager mm = transport.getMemoryManager();

        try {
            transport.bind(PORT);
            transport.start();

            final String messagePattern = "Hello world! Packet#";
            for (int i = 0; i < connectionsNum; i++) {
                Future<Connection> future = transport.connect("localhost", PORT);
                connection = future.get(10, TimeUnit.SECONDS);
                assertTrue(connection != null);

                final FutureImpl<Integer> clientFuture = SafeFutureImpl.create();
                FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
                clientFilterChainBuilder.add(new TransportFilter());
                clientFilterChainBuilder.add(new SSLFilter(serverSSLEngineConfigurator,
                        clientSSLEngineConfigurator));

                final ClientTestFilter clientTestFilter = new ClientTestFilter(
                        clientFuture, messagePattern, packetsNumber);

                clientFilterChainBuilder.add(clientTestFilter);

                connection.setProcessor(clientFilterChainBuilder.build());

                int packetNum = 0;
                try {
                    for (int j = 0; j < packetsNumber; j++) {
                        packetNum = j;
                        Buffer buffer = MemoryUtils.wrap(mm, messagePattern + j);
                        connection.write(buffer);
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error occurred when testing connection#" + i + " packet#" + packetNum);
                    throw e;
                }

                try {
                    Integer bytesReceived = clientFuture.get(10, TimeUnit.SECONDS);
                    assertNotNull(bytesReceived);
                } catch (TimeoutException e) {
                    throw new TimeoutException("Received " + clientTestFilter.getBytesReceived() + " out of " + clientTestFilter.getPatternString().length());
                }

                connection.close();
                connection = null;
            }
        } finally {
            if (connection != null) {
                connection.close();
            }

            transport.stop();
            TransportFactory.getInstance().close();
        }
    }
    
    private SSLContextConfigurator createSSLContextConfigurator() {
        SSLContextConfigurator sslContextConfigurator =
                new SSLContextConfigurator();
        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL cacertsUrl = cl.getResource("ssltest-cacerts.jks");
        if (cacertsUrl != null) {
            sslContextConfigurator.setTrustStoreFile(cacertsUrl.getFile());
            sslContextConfigurator.setTrustStorePass("changeit");
        }

        // override system properties
        URL keystoreUrl = cl.getResource("ssltest-keystore.jks");
        if (keystoreUrl != null) {
            sslContextConfigurator.setKeyStoreFile(keystoreUrl.getFile());
            sslContextConfigurator.setKeyStorePass("changeit");
        }

        return sslContextConfigurator;
    }

    private class SSLPingPongFilter extends BaseFilter {
        private final Attribute<Integer> turnAroundAttr =
                Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute("TurnAroundAttr");

        private final int turnAroundNum;
        private final SSLFilter sslFilter;

        private final FutureImpl<Integer> serverCompletedFeature =
                SafeFutureImpl.<Integer>create();
        private final FutureImpl<Integer> clientCompletedFeature =
                SafeFutureImpl.<Integer>create();

        public SSLPingPongFilter(SSLFilter sslFilter, int turnaroundNum) {
            this.sslFilter = sslFilter;
            this.turnAroundNum = turnaroundNum;
        }

        @Override
        public NextAction handleConnect(final FilterChainContext ctx)
                throws IOException {

            final Connection connection = ctx.getConnection();
            
            try {
                sslFilter.handshake(connection, new EmptyCompletionHandler<SSLEngine>() {

                    @Override
                    public void completed(SSLEngine result) {
                        try {
                            connection.write("ping");
                            turnAroundAttr.set(connection, 1);
                        } catch (IOException e) {
                            clientCompletedFeature.failure(e);
                        }
                    }
                });
            } catch (Exception e) {
                clientCompletedFeature.failure(e);
            }
            return ctx.getInvokeAction();
        }


        @Override
        public NextAction handleRead(final FilterChainContext ctx)
                throws IOException {

            final Connection connection = ctx.getConnection();
            
            Integer currentTurnAround = turnAroundAttr.get(connection);
            if (currentTurnAround == null) {
                currentTurnAround = 1;
            } else {
                currentTurnAround++;
            }
            
            final String message = (String) ctx.getMessage();
            if (message.equals("ping")) {
                try {
                    connection.write("pong");
                    turnAroundAttr.set(connection, currentTurnAround);
                    if (currentTurnAround >= turnAroundNum) {
                        serverCompletedFeature.result(turnAroundNum);
                    }
                } catch (Exception e) {
                    serverCompletedFeature.failure(e);
                }
            } else if (message.equals("pong")) {
                try {
                    if (currentTurnAround > turnAroundNum) {
                        clientCompletedFeature.result(turnAroundNum);
                        return ctx.getStopAction();
                    }

                    connection.write("ping");
                    turnAroundAttr.set(connection, currentTurnAround);
                } catch (Exception e) {
                    clientCompletedFeature.failure(e);
                }
                
            }

            return ctx.getStopAction();
        }

        public Future<Integer> getClientCompletedFeature() {
            return clientCompletedFeature;
        }

        public Future<Integer> getServerCompletedFeature() {
            return serverCompletedFeature;
        }
    }

    private static class ClientTestFilter extends BaseFilter {

        private final FutureImpl<Integer> clientFuture;
        private final String messagePattern;
        private final int packetsNumber;

        private volatile int bytesReceived = 0;

        private final String patternString;

        private ClientTestFilter(FutureImpl<Integer> clientFuture, String messagePattern, int packetsNumber) {
            this.clientFuture = clientFuture;
            this.messagePattern = messagePattern;
            this.packetsNumber = packetsNumber;

            final StringBuilder sb = new StringBuilder(packetsNumber * (messagePattern.length() + 5));
            for (int i=0; i<packetsNumber; i++) {
                sb.append(messagePattern).append(i);
            }

            patternString = sb.toString();
        }

        @Override
        public NextAction handleRead(FilterChainContext ctx) throws IOException {
            final Buffer buffer = (Buffer) ctx.getMessage();

            final String rcvdStr = buffer.toStringContent();
            final String expectedChunk = patternString.substring(bytesReceived, bytesReceived + buffer.remaining());

            if (!expectedChunk.equals(rcvdStr)) {
                clientFuture.failure(new AssertionError("Content doesn't match. Expected: " + expectedChunk + " Got: " + rcvdStr));
            }

            bytesReceived += buffer.remaining();

            if (bytesReceived == patternString.length()) {
                clientFuture.result(bytesReceived);
            }
            return super.handleRead(ctx);
        }

        public int getBytesReceived() {
            return bytesReceived;
        }

        public String getPatternString() {
            return patternString;
        }
    }
}
