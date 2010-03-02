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
package com.sun.grizzly.http.core;

import com.sun.grizzly.Buffer;
import com.sun.grizzly.Connection;
import com.sun.grizzly.Grizzly;
import com.sun.grizzly.StandaloneProcessor;
import com.sun.grizzly.TransportFactory;
import com.sun.grizzly.filterchain.BaseFilter;
import com.sun.grizzly.filterchain.FilterChainBuilder;
import com.sun.grizzly.filterchain.FilterChainContext;
import com.sun.grizzly.filterchain.NextAction;
import com.sun.grizzly.filterchain.TransportFilter;
import com.sun.grizzly.http.HttpClientFilter;
import com.sun.grizzly.impl.FutureImpl;
import com.sun.grizzly.memory.MemoryManager;
import com.sun.grizzly.memory.MemoryUtils;
import com.sun.grizzly.nio.AbstractNIOConnection;
import com.sun.grizzly.nio.transport.TCPNIOConnection;
import com.sun.grizzly.nio.transport.TCPNIOTransport;
import com.sun.grizzly.streams.StreamReader;
import com.sun.grizzly.streams.StreamWriter;
import com.sun.grizzly.utils.ChunkingFilter;
import com.sun.grizzly.utils.Pair;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import junit.framework.TestCase;

/**
 * Testing HTTP request parsing
 * 
 * @author Alexey Stashok
 */
public class HttpResponseParseTest extends TestCase {
    private static final Logger logger = Grizzly.logger(HttpResponseParseTest.class);
    
    public static int PORT = 8001;

    public void testHeaderlessResponseLine() throws Exception {
        doHttpResponseTest("HTTP/1.0", 200, "OK", Collections.EMPTY_MAP, "\r\n");
    }

    public void testSimpleHeaders() throws Exception {
        Map<String, Pair<String, String>> headers =
                new HashMap<String, Pair<String, String>>();
        headers.put("Header1", new Pair("localhost", "localhost"));
        headers.put("Content-length", new Pair("2345", "2345"));
        doHttpResponseTest("HTTP/1.0", 200, "ALL RIGHT", headers, "\r\n");
    }

    public void testMultiLineHeaders() throws Exception {
        Map<String, Pair<String, String>> headers =
                new HashMap<String, Pair<String, String>>();
        headers.put("Header1", new Pair("localhost", "localhost"));
        headers.put("Multi-line", new Pair("first\r\n          second\r\n       third", "first seconds third"));
        headers.put("Content-length", new Pair("2345", "2345"));
        doHttpResponseTest("HTTP/1.0", 200, "DONE", headers, "\r\n");
    }
    

    public void testHeadersN() throws Exception {
        Map<String, Pair<String, String>> headers =
                new HashMap<String, Pair<String, String>>();
        headers.put("Header1", new Pair("localhost", "localhost"));
        headers.put("Multi-line", new Pair("first\n          second\n       third", "first seconds third"));
        headers.put("Content-length", new Pair("2345", "2345"));
        doHttpResponseTest("HTTP/1.0", 200, "DONE", headers, "\n");
    }

    public void testDecoderOK() {
        try {
            doTestDecoder("HTTP/1.0 404 Not found\n\n", 4096);
            assertTrue(true);
        } catch (IllegalStateException e) {
            logger.log(Level.SEVERE, "exception", e);
            assertTrue("Unexpected exception", false);
        }
    }

    public void testDecoderOverflowProtocol() {
        try {
            doTestDecoder("HTTP/1.0 404 Not found\n\n", 2);
            assertTrue("Overflow exception had to be thrown", false);
        } catch (IllegalStateException e) {
            assertTrue(true);
        }
    }

    public void testDecoderOverflowCode() {
        try {
            doTestDecoder("HTTP/1.0 404 Not found\n\n", 11);
            assertTrue("Overflow exception had to be thrown", false);
        } catch (IllegalStateException e) {
            assertTrue(true);
        }
    }

    public void testDecoderOverflowPhrase() {
        try {
            doTestDecoder("HTTP/1.0 404 Not found\n\n", 19);
            assertTrue("Overflow exception had to be thrown", false);
        } catch (IllegalStateException e) {
            assertTrue(true);
        }
    }

    public void testDecoderOverflowHeader() {
        try {
            doTestDecoder("HTTP/1.0 404 Not found\nHeader1: somevalue\n\n", 30);
            assertTrue("Overflow exception had to be thrown", false);
        } catch (IllegalStateException e) {
            assertTrue(true);
        }
    }

    private HttpPacket doTestDecoder(String response, int limit) {

        MemoryManager mm = TransportFactory.getInstance().getDefaultMemoryManager();
        Buffer input = MemoryUtils.wrap(mm, response);
        
        HttpClientFilter filter = new HttpClientFilter(limit);
        FilterChainContext ctx = FilterChainContext.create();
        ctx.setMessage(input);
        ctx.setConnection(new StandaloneConnection());

        try {
            filter.handleRead(ctx, null);
            return (HttpPacket) ctx.getMessage();
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    private void doHttpResponseTest(String protocol, int code,
            String phrase, Map<String, Pair<String, String>> headers, String eol)
            throws Exception {
        
        final FutureImpl<Boolean> parseResult = FutureImpl.create();

        Connection connection = null;
        StreamReader reader = null;
        StreamWriter writer = null;

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.singleton();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new ChunkingFilter(2));
        filterChainBuilder.add(new HttpClientFilter());
        filterChainBuilder.add(new HTTPResponseCheckFilter(parseResult,
                protocol, code, phrase, Collections.EMPTY_MAP));

        TCPNIOTransport transport = TransportFactory.getInstance().createTCPTransport();
        transport.setProcessor(filterChainBuilder.build());
        
        try {
            transport.bind(PORT);
            transport.start();

            Future<Connection> future = transport.connect("localhost", PORT);
            connection = (TCPNIOConnection) future.get(10, TimeUnit.SECONDS);
            assertTrue(connection != null);

            connection.configureStandalone(true);

            StringBuffer sb = new StringBuffer();

            sb.append(protocol + " " + Integer.toString(code) + " " + phrase + eol);

            for (Entry<String, Pair<String, String>> entry : headers.entrySet()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue().getFirst()).append(eol);
            }

            sb.append(eol);

            byte[] message = sb.toString().getBytes();
            
            writer = StandaloneProcessor.INSTANCE.getStreamWriter(connection);
            
            writer.writeByteArray(message);
            Future<Integer> writeFuture = writer.flush();

            assertTrue("Write timeout", writeFuture.isDone());
            assertEquals(message.length, (int) writeFuture.get());

            assertTrue(parseResult.get(10, TimeUnit.SECONDS));
        } finally {
            if (connection != null) {
                connection.close();
            }

            transport.stop();
            TransportFactory.getInstance().close();
        }
    }

    public class HTTPResponseCheckFilter extends BaseFilter {
        private final FutureImpl<Boolean> parseResult;
        private final String protocol;
        private final int code;
        private final String phrase;
        private final Map<String, Pair<String, String>> headers;

        public HTTPResponseCheckFilter(FutureImpl parseResult, String protocol,
                int code, String phrase,
                Map<String, Pair<String, String>> headers) {
            this.parseResult = parseResult;
            this.protocol = protocol;
            this.code = code;
            this.phrase = phrase;
            this.headers = headers;
        }

        @Override
        public NextAction handleRead(FilterChainContext ctx)
                throws IOException {
            HttpContent httpContent = (HttpContent) ctx.getMessage();
            HttpResponse httpResponse = (HttpResponse) httpContent.getHttpHeader();
            
            try {
                assertEquals(protocol, httpResponse.getProtocol());
                assertEquals(code, httpResponse.getStatus());
                assertEquals(phrase, httpResponse.getReasonPhrase());

                for(Entry<String, Pair<String, String>> entry : headers.entrySet()) {
                    assertEquals(entry.getValue().getSecond(),
                            httpResponse.getHeader(entry.getKey()));
                }

                parseResult.result(Boolean.TRUE);
            } catch (Throwable e) {
                parseResult.failure(e);
            }

            return ctx.getInvokeAction();
        }
    }

    protected static final class StandaloneConnection extends AbstractNIOConnection {
        public StandaloneConnection() {
            super(TransportFactory.getInstance().createTCPTransport());
        }

        @Override
        protected void preClose() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public SocketAddress getPeerAddress() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public SocketAddress getLocalAddress() {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    }

}
