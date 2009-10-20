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
package com.sun.grizzly.http;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.File;
import java.net.URL;
import java.net.URISyntaxException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import junit.framework.TestCase;
import com.sun.grizzly.TransportFactory;
import com.sun.grizzly.filterchain.TransportFilter;
import com.sun.grizzly.nio.transport.TCPNIOTransport;
import com.sun.grizzly.ssl.SSLContextConfigurator;
import com.sun.grizzly.ssl.SSLEngineConfigurator;
import com.sun.grizzly.ssl.SSLFilter;
import com.sun.grizzly.arp.AsyncExecutor;
import com.sun.grizzly.arp.AsyncFilter;
import com.sun.grizzly.arp.AsyncHandler;
import com.sun.grizzly.arp.AsyncWebFilter;
import com.sun.grizzly.arp.AsyncWebFilterConfig;
import com.sun.grizzly.arp.DefaultAsyncHandler;
import com.sun.grizzly.tcp.Adapter;
import com.sun.grizzly.tcp.OutputBuffer;
import com.sun.grizzly.tcp.Request;
import com.sun.grizzly.tcp.Response;
import com.sun.grizzly.util.buf.ByteChunk;

/**
 *
 * @author Danijel Bjelajac
 * @author Jeanfrancois Arcand
 */
public class ArpSSLTest extends TestCase {

    public static final int PORT = 18890;
    private static Logger logger = Logger.getLogger("grizzly.test");
    private AsyncWebFilter webFilter;

    public void testSimplePacket() throws Exception {
        TCPNIOTransport transport = TransportFactory.getInstance().createTCPTransport();

        AsyncWebFilterConfig webConfig = new AsyncWebFilterConfig();
        webConfig.setAdapter(new MyAdapter());

        FileCache fileCache = new FileCache(webConfig);
        fileCache.setLargeFileCacheEnabled(false);
        webConfig.setFileCache(fileCache);

        webConfig.setDisplayConfiguration(true);
        webConfig.setBufferResponse(false);
        webConfig.setKeepAliveTimeoutInSeconds(2000);

        webConfig.setRequestBufferSize(32768);
        webConfig.setMaxKeepAliveRequests(8196);

        webConfig.setWebAppRootPath("/dev/null");

        AsyncHandler handler = new DefaultAsyncHandler();
        handler.addAsyncFilter(new MyAsyncFilter());
        webConfig.setAsyncHandler(handler);

        webFilter = new AsyncWebFilter("async-filter", webConfig);

        transport.getFilterChain().add(new TransportFilter());
        transport.getFilterChain().add(new SSLFilter(new SSLEngineConfigurator(
                createSSLConfig().createSSLContext(), false, false, false)));

        transport.getFilterChain().add(webFilter);

        try {
            webFilter.enableMonitoring();
            webFilter.initialize();
            transport.bind(PORT);
            transport.start();
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        try {
            HostnameVerifier hv = new HostnameVerifier() {
                public boolean verify(String urlHostName, SSLSession session) {
                    return true;
                }
            };
            HttpsURLConnection.setDefaultHostnameVerifier(hv);
        
            String testString = "Hello. Client#";
            OutputStream os = null;
            DataInputStream is = null;
            HttpsURLConnection connection = null;

            try {
                URL url = new URL("https://localhost:" + PORT);
                connection = (HttpsURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                os = connection.getOutputStream();
                os.write(testString.getBytes());
                os.flush();
                assertEquals(200, connection.getResponseCode());
            } finally {
                if (os != null) {
                    os.close();
                }

                if (is != null) {
                    is.close();
                }

                if (connection != null) {
                    connection.disconnect();
                }
            }
        } finally {
            transport.stop();
            TransportFactory.getInstance().close();
        }
    }
    
    private class MyAsyncFilter implements AsyncFilter {
        public boolean doFilter(AsyncExecutor executor) {
            ProcessorTask processorTask = (ProcessorTask) executor.getProcessorTask();
            int contentLenght = processorTask.getRequest().getContentLength();
            ByteChunk byteChunk = new ByteChunk();
            byteChunk.setLimit(contentLenght);
            try {
                processorTask.getRequest().doRead(byteChunk);
            } catch (IOException e) {
                e.printStackTrace();
            }
            processorTask.invokeAdapter();
            return false;
        }
    }

    class MyAdapter implements Adapter {
        // Just in case this code is cut&pasted
        public synchronized void service(Request request, Response response) throws Exception {
            KeepAliveStats kas = webFilter.getKeepAliveStats();
            String s;
            if (kas == null) {
                s = "KAS: missing\n";
            } else {
                s = "KAS:  conns=" + kas.getCountConnections() + ", flushes=" + kas.getCountFlushes() + ", hits=" + kas.getCountHits() + ", refusals=" + kas.getCountRefusals() + ", timeouts=" + kas.getCountTimeouts() + ", maxConns=" + "\n";
            }
            byte[] b = s.getBytes("iso-8859-1");
            sendPlainText(response, b);
        }

        private void sendPlainText(Response response, byte[] b) throws IOException {
            response.setContentType("text/plain");
            response.setContentLength(b.length);
            ByteChunk chunk = new ByteChunk();
            chunk.append(b, 0, b.length);
            OutputBuffer buffer = response.getOutputBuffer();
            buffer.doWrite(chunk, response);
            response.finish();
        }

        public void afterService(Request request, Response response) throws Exception {
            request.recycle();
            response.recycle();
        }

        public void fireAdapterEvent(String string, Object object) {
        }
    }

    private SSLContextConfigurator createSSLConfig() throws URISyntaxException {
        SSLContextConfigurator sslConfig = new SSLContextConfigurator();
        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL cacertsUrl = cl.getResource("ssltest-cacerts.jks");
        String trustStoreFile = new File(cacertsUrl.toURI()).getAbsolutePath();
        if (cacertsUrl != null) {
            sslConfig.setTrustStoreFile(trustStoreFile);
            sslConfig.setTrustStorePass("changeit");
        }

        logger.log(Level.INFO, "SSL certs path: " + trustStoreFile);

        // override system properties
        URL keystoreUrl = cl.getResource("ssltest-keystore.jks");
        String keyStoreFile = new File(keystoreUrl.toURI()).getAbsolutePath();
        if (keystoreUrl != null) {
            sslConfig.setKeyStoreFile(keyStoreFile);
            sslConfig.setKeyStorePass("changeit");
        }

        logger.log(Level.INFO, "SSL keystore path: " + keyStoreFile);


        // public SSL configuration for HttpsURLConnection
        System.setProperty("javax.net.ssl.trustStore", trustStoreFile);
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
        System.setProperty("javax.net.ssl.keyStore", keyStoreFile);
        System.setProperty("javax.net.ssl.keyStorePassword", "changeit");


        return sslConfig;
    }
}
