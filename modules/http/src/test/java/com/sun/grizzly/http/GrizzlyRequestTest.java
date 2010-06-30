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

package com.sun.grizzly.http;

import com.sun.grizzly.SSLConfig;
import com.sun.grizzly.http.utils.SelectorThreadUtils;
import com.sun.grizzly.ssl.SSLSelectorThread;
import com.sun.grizzly.tcp.http11.GrizzlyAdapter;
import com.sun.grizzly.tcp.http11.GrizzlyRequest;
import com.sun.grizzly.tcp.http11.GrizzlyResponse;
import com.sun.grizzly.util.ExtendedThreadPool;
import com.sun.grizzly.util.LoggerUtils;
import com.sun.grizzly.util.Utils;
import com.sun.grizzly.util.net.jsse.JSSEImplementation;
import junit.framework.TestCase;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GrizzlyRequestTest extends TestCase {

    private SSLConfig sslConfig;
    private SSLSelectorThread st;
    private static final int PORT = 7333;
    private Logger logger = LoggerUtils.getLogger();
    private String trustStoreFile;
    private String keyStoreFile;

    @Override
    public void setUp() throws URISyntaxException {
        sslConfig = new SSLConfig();
        ClassLoader cl = getClass().getClassLoader();
        // override system properties
        URL cacertsUrl = cl.getResource("ssltest-cacerts.jks");
        if (cacertsUrl != null) {
            trustStoreFile = new File(cacertsUrl.toURI()).getAbsolutePath();
            sslConfig.setTrustStoreFile(trustStoreFile);
            sslConfig.setTrustStorePass("changeit");
            logger.log(Level.INFO, "SSL certs path: " + trustStoreFile);
        }

        // override system properties
        URL keystoreUrl = cl.getResource("ssltest-keystore.jks");
        if (keystoreUrl != null) {
            keyStoreFile = new File(keystoreUrl.toURI()).getAbsolutePath();
            sslConfig.setKeyStoreFile(keyStoreFile);
            sslConfig.setKeyStorePass("changeit");

        }
        logger.log(Level.INFO, "SSL keystore path: " + keyStoreFile);

        SSLConfig.DEFAULT_CONFIG = sslConfig;

        System.setProperty("javax.net.ssl.trustStore", trustStoreFile);
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
        System.setProperty("javax.net.ssl.keyStore", keyStoreFile);
        System.setProperty("javax.net.ssl.keyStorePassword", "changeit");

    }


    // ------------------------------------------------------------ Test Methods


    public void testGetUserPrincipalSSL() throws Exception {
        createSelectorThread();
        try {
            HostnameVerifier hv = new HostnameVerifier() {
                public boolean verify(String urlHostName, SSLSession session) {
                    return true;
                }
            };
            HttpsURLConnection.setDefaultHostnameVerifier(hv);

            HttpsURLConnection connection = null;
            InputStream in = null;
            try {
                URL url = new URL("https://localhost:" + PORT);
                connection =
                        (HttpsURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setDoOutput(true);
                in = connection.getInputStream();
                BufferedReader r = new BufferedReader(new InputStreamReader(in));
                boolean found = false;
                for (String l = r.readLine(); l != null; l = r.readLine()) {
                    if (l.length() > 0 && l.contains("Principal not null: true")) {
                        found = true;
                    }
                }
                assertEquals(200, connection.getResponseCode());
                assertTrue("GrizzlyRequest.getUserPrincipal() returned null.", found);
            } finally {
                if (in != null) {
                    in.close();
                }

                if (connection != null) {
                    connection.disconnect();
                }
            }
        } finally {
            SelectorThreadUtils.stopSelectorThread(st);
        }
    }

    // --------------------------------------------------------- Private Methods


    public void createSelectorThread() throws Exception {
        st = new SSLSelectorThread();
        st.setPort(PORT);
        st.setAdapter(new MyAdapter());
        st.setDisplayConfiguration(Utils.VERBOSE_TESTS);
        st.setFileCacheIsEnabled(false);
        st.setLargeFileCacheEnabled(false);
        st.setBufferResponse(false);
        st.setKeepAliveTimeoutInSeconds(2000);

        st.initThreadPool();
        ((ExtendedThreadPool) st.getThreadPool()).setMaximumPoolSize(50);

        st.setBufferSize(32768);
        st.setMaxKeepAliveRequests(8196);
        st.setWebAppRootPath("/dev/null");
        st.setSSLConfig(sslConfig);
        try {
            st.setSSLImplementation(new JSSEImplementation());
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
          st.enableMonitoring();
        st.setNeedClientAuth(true);
        st.listen();


    }


    // ---------------------------------------------------------- Nested Classes


    private static final class MyAdapter extends GrizzlyAdapter {

        @Override
        public void service(GrizzlyRequest request, GrizzlyResponse response) {

            response.setContentType("text/html");
            response.setCharacterEncoding("ISO-8859-1");
            response.setLocale(Locale.US);
            Writer w = null;
            try {
                w = response.getWriter();
                w.write("<html><head></head><body>");
                w.write("Principal not null: ");
                w.write(Boolean.toString(request.getUserPrincipal() != null));
                w.write("</body></html>");
            } catch (Exception e) {
                 LoggerUtils.getLogger().log(Level.SEVERE,
                                                    e.toString(),
                                                    e);
            } finally {
                if (w != null) {
                    try {
                        w.close();
                    } catch (IOException ioe) {
                        LoggerUtils.getLogger().log(Level.SEVERE,
                                                    ioe.toString(),
                                                    ioe);
                    }
                }
            }
        }
    }

}
