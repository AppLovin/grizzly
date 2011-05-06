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

import com.sun.grizzly.SSLConfig;
import com.sun.grizzly.http.utils.SelectorThreadUtils;
import com.sun.grizzly.ssl.SSLSelectorThread;
import com.sun.grizzly.tcp.Adapter;
import com.sun.grizzly.tcp.CompletionHandler;
import com.sun.grizzly.tcp.Request;
import com.sun.grizzly.tcp.Response;
import com.sun.grizzly.tcp.StaticResourcesAdapter;
import com.sun.grizzly.tcp.http11.GrizzlyAdapter;
import com.sun.grizzly.tcp.http11.GrizzlyRequest;
import com.sun.grizzly.tcp.http11.GrizzlyResponse;
import com.sun.grizzly.util.WorkerThreadImpl;
import com.sun.grizzly.util.buf.ByteChunk;
import com.sun.grizzly.util.net.jsse.JSSEImplementation;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.AssertJUnit.*;

/**
 * Units test that exercise the {@link Response#suspend}, {@link Response#resume}
 * and {@link Response.cancel} API.
 * 
 * @author Jeanfrancois Arcand
 * @author gustav trede
 */
@Test
public class SuspendTest {
    private static Logger logger = Logger.getLogger("grizzly.test");

    public static final int PORT = 18890;
    private ScheduledThreadPoolExecutor pe;
    private SelectorThread st;
    private final String testString = "blabla test.";
    private final byte[] testData = testString.getBytes();


    @DataProvider(name = "isSslEnabled")
    public Object[][] createData1() {
        return new Object[][]{
                    {Boolean.FALSE},
                    {Boolean.TRUE}
                };
    }

    @BeforeMethod
    public void before(Object[] parameters) throws Exception {
        boolean isSslEnabled = parameters[0] != null && ((Boolean) parameters[0]);
        
        pe = new ScheduledThreadPoolExecutor(1);
        createSelectorThread(isSslEnabled);
    }

    @AfterMethod
    public void tearDown() throws Exception {
        pe.shutdown();
        SelectorThreadUtils.stopSelectorThread(st);
    }

    private void createSelectorThread(boolean isSslEnabled) throws Exception {
        if (isSslEnabled) {
            SSLConfig sslConfig = configureSSL();
            SSLSelectorThread sslSelectorThread = new SSLSelectorThread();
            sslSelectorThread.setSSLConfig(sslConfig);
            try {
                sslSelectorThread.setSSLImplementation(new JSSEImplementation());
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
            st = sslSelectorThread;
        } else {
            st = new SelectorThread();
        }

        st.setPort(PORT);
        st.setDisplayConfiguration(true);
    }

    private SSLConfig configureSSL() throws Exception {
        SSLConfig sslConfig = new SSLConfig();
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
        SSLConfig.DEFAULT_CONFIG = sslConfig;

        System.setProperty("javax.net.ssl.trustStore", trustStoreFile);
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
        System.setProperty("javax.net.ssl.keyStore", keyStoreFile);
        System.setProperty("javax.net.ssl.keyStorePassword", "changeit");

        return sslConfig;
    }

    private void setAdapterAndListen(Adapter adapter) throws Exception {
        st.setAdapter(adapter);
        st.listen();
        st.enableMonitoring();
    }

    // See https://grizzly.dev.java.net/issues/show_bug.cgi?id=592
    @Test(dataProvider="isSslEnabled", enabled=false)
    public void __testSuspendDoubleCancelInvokation(boolean isSslEnabled) throws Exception {
        System.err.println("Test: testSuspendDoubleCancelInvokation");
        final CountDownLatch latch = new CountDownLatch(1);
        setAdapterAndListen(new TestStaticResourcesAdapter() {

            @Override
            public void dologic(final Request req, final Response res) throws Throwable {
                res.suspend(60 * 1000, this, new TestCompletionHandler<StaticResourcesAdapter>() {

                    @Override
                    public void cancelled(StaticResourcesAdapter attachment) {
                        System.err.println("cancelled");
                        latch.countDown();
                    }
                });

                cancelLater(res);

                if (!latch.await(5, TimeUnit.SECONDS)) {
                    fail("was canceled too late");
                }
                try {
                    res.cancel();
                    fail("should not reach here");
                } catch (IllegalStateException t) {
                    res.getChannel().write(ByteBuffer.wrap(testData));
                }
            }
        });
        sendRequest(isSslEnabled, false);
    }

    @Test(dataProvider="isSslEnabled", enabled=true)
    public void testSuspendResumeSameTransaction(final boolean isSslEnabled) throws Exception {
        System.err.println("Test: testSuspendResumeSameTransaction isSslEnabled=" + isSslEnabled);
        setAdapterAndListen(new TestStaticResourcesAdapter() {

            @Override
            public void service(final Request req, final Response res) {
                try {
                    res.suspend();
                    res.resume();
                    write(res, testData);
                    res.finish();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });
        sendRequest(isSslEnabled);
    }

    @Test(dataProvider="isSslEnabled", enabled=true)
    public void testSuspendResumeNoArgs(final boolean isSslEnabled) throws Exception {
        System.err.println("Test: testSuspendResumeNoArgs isSslEnabled=" + isSslEnabled);
        setAdapterAndListen(new TestStaticResourcesAdapter() {

            @Override
            public void service(final Request req, final Response res) {
                try {
                    res.suspend();
                    writeToSuspendedClient(res);
                    res.resume();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });
        sendRequest(isSslEnabled);
    }

    @Test(dataProvider="isSslEnabled", enabled=true)
    public void testSuspendNoArgs(final boolean isSslEnabled) throws Exception {
        System.err.println("Test: testSuspendNoArgs isSslEnabled=" + isSslEnabled);
        setAdapterAndListen(new TestStaticResourcesAdapter() {

            @Override
            public void service(final Request req, final Response res) {
                res.suspend();
                pe.schedule(new Runnable() {

                    public void run() {
                        writeToSuspendedClient(res);
                        res.resume();
                    }
                }, 2, TimeUnit.SECONDS);
            }
        });
        sendRequest(isSslEnabled);
    }

    @Test(dataProvider="isSslEnabled", enabled=true)
    public void testSuspendResumedCompletionHandler(final boolean isSslEnabled) throws Exception {
        System.err.println("Test: testSuspendResumedCompletionHandler isSslEnabled=" + isSslEnabled);
        setAdapterAndListen(new TestStaticResourcesAdapter() {

            @Override
            public void dologic(final Request req, final Response res) throws Throwable {
                res.suspend(60 * 1000, this, new TestCompletionHandler<StaticResourcesAdapter>() {

                    @Override
                    public void resumed(StaticResourcesAdapter attachment) {
                        writeToSuspendedClient(res);
                    }
                });
                resumeLater(res);
            }
        });
        sendRequest(isSslEnabled);
    }

    @Test(dataProvider="isSslEnabled", enabled=true)
    public void testSuspendCancelledCompletionHandler(final boolean isSslEnabled) throws Exception {
        System.err.println("Test: testSuspendCancelledCompletionHandler isSslEnabled=" + isSslEnabled);
        setAdapterAndListen(new TestStaticResourcesAdapter() {

            @Override
            public void dologic(final Request req, final Response res) throws Throwable {
                res.suspend(60 * 1000, this, new TestCompletionHandler<StaticResourcesAdapter>() {

                    @Override
                    public void cancelled(StaticResourcesAdapter attachment) {
                        writeToSuspendedClient(res);
                        try {
                            res.flush();
                        } catch (IOException ex) {
                            ex.printStackTrace();
                        }
                    }
                });
                this.cancelLater(res);
            }
        });
        sendRequest(isSslEnabled);
    }

    @Test(dataProvider="isSslEnabled", enabled=true)
    public void testSuspendSuspendedExceptionCompletionHandler(final boolean isSslEnabled) throws Exception {
        System.err.println("Test: testSuspendSuspendedExceptionCompletionHandler isSslEnabled=" + isSslEnabled);
        setAdapterAndListen(new TestStaticResourcesAdapter() {

            @Override
            public void dologic(final Request req, final Response res) throws Throwable {
                res.suspend(60 * 1000, this, new TestCompletionHandler<StaticResourcesAdapter>() {

                    private AtomicBoolean first = new AtomicBoolean(true);

                    @Override
                    public void resumed(StaticResourcesAdapter attachment) {
                        if (!first.compareAndSet(true, false)) {
                            fail("recursive resume");
                        }
                        System.err.println("Resumed.");
                        try {
                            res.resume();
                            fail("should not reach here");
                        } catch (IllegalStateException ise) {
                            writeToSuspendedClient(res);
                        }
                    }
                });
                resumeLater(res);
            }
        });
        sendRequest(isSslEnabled, true);
    }

    @Test(dataProvider="isSslEnabled", enabled=true)
    public void testSuspendTimeoutCompletionHandler(final boolean isSslEnabled) throws Exception {
        System.err.println("Test: testSuspendTimeoutCompletionHandler isSslEnabled=" + isSslEnabled);
        setAdapterAndListen(new TestStaticResourcesAdapter() {

            @Override
            public void dologic(final Request req, final Response res) throws Throwable {
                res.suspend(5 * 1000, this, new TestCompletionHandler<StaticResourcesAdapter>() {

                    @Override
                    public void cancelled(StaticResourcesAdapter attachment) {
                        try {
                            System.err.println("Time out");
                            write(res, testData);
                        } catch (Throwable ex) {
                            ex.printStackTrace();
                        }
                    }
                });
            }
        });
        sendRequest(isSslEnabled);
    }

    @Test(dataProvider="isSslEnabled", enabled=true)
    public void testSuspendDoubleSuspendInvokation(final boolean isSslEnabled) throws Exception {
        System.err.println("Test: testSuspendDoubleSuspendInvokation isSslEnabled=" + isSslEnabled);
        setAdapterAndListen(new TestStaticResourcesAdapter() {

            @Override
            public void dologic(final Request req, final Response res) throws Throwable {
                res.suspend(60 * 1000, this, new TestCompletionHandler<StaticResourcesAdapter>() {

                    @Override
                    public void resumed(StaticResourcesAdapter attachment) {
                        System.err.println("resumed");
                    }
                });

                pe.schedule(new Runnable() {

                    public void run() {
                        try {
                            res.suspend();
                            fail("should not reach here");
                        } catch (IllegalStateException t) {
                            System.err.println("catched suspended suspend");
                            writeToSuspendedClient(res);
                            try {
                                res.resume();
                            } catch (Throwable at) {
                                at.printStackTrace();
                                fail(at.getMessage());
                            }
                        }
                    }
                }, 2, TimeUnit.SECONDS);
            }
        });
        sendRequest(isSslEnabled);
    }

    @Test(dataProvider="isSslEnabled", enabled=true)
    public void testSuspendDoubleResumeInvokation(final boolean isSslEnabled) throws Exception {
        System.err.println("Test: testSuspendDoubleResumeInvokation isSslEnabled=" + isSslEnabled);
        setAdapterAndListen(new TestStaticResourcesAdapter() {

            @Override
            public void dologic(final Request req, final Response res) throws Throwable {
                res.suspend(60 * 1000, this, new TestCompletionHandler<StaticResourcesAdapter>() {

                    @Override
                    public void resumed(StaticResourcesAdapter attachment) {
                        try {
                            System.err.println("trying to resume");
                            res.resume();
                            fail("should no get here");
                        } catch (IllegalStateException ex) {
                            writeToSuspendedClient(res);
                        }
                    }
                });
                resumeLater(res);
            }
        });
        sendRequest(isSslEnabled);
    }

    @Test(dataProvider="isSslEnabled", enabled=true)
    public void testSuspendResumedCompletionHandlerGrizzlyAdapter(boolean isSslEnabled) throws Exception {
        System.err.println("Test: testSuspendResumedCompletionHandlerGrizzlyAdapter isSslEnabled=" + isSslEnabled);
        setAdapterAndListen(new GrizzlyAdapter() {

            @Override
            public void service(final GrizzlyRequest req, final GrizzlyResponse res) {
                try {
                    res.suspend(60 * 1000, this, new TestCompletionHandler<GrizzlyAdapter>() {

                        @Override
                        public void resumed(GrizzlyAdapter attachment) {
                            if (res.isSuspended()) {
                                System.err.println("Resumed");
                                try {
                                    res.getWriter().write(testString);
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            } else {
                                fail("resumed without being suspended");
                            }
                        }
                    });

                    resumeLater(res);
                } catch (Throwable t) {
                    t.printStackTrace();
                    fail(t.getMessage());
                }
            }
        });
        sendRequest(isSslEnabled);
    }

    @Test(dataProvider="isSslEnabled", enabled=true)
    public void testSuspendTimeoutCompletionHandlerGrizzlyAdapter(boolean isSslEnabled) throws Exception {
        System.err.println("Test: testSuspendTimeoutCompletionHandlerGrizzlyAdapter isSslEnabled=" + isSslEnabled);
        setAdapterAndListen(new GrizzlyAdapter() {

            @Override
            public void service(final GrizzlyRequest req, final GrizzlyResponse res) {
                try {
                    final long t1 = System.currentTimeMillis();
                    res.suspend(10 * 1000, "foo", new TestCompletionHandler<String>() {

                        @Override
                        public void cancelled(String attachment) {
                            try {
                                System.err.println("Cancelling TOOK: " + (System.currentTimeMillis() - t1));
                                res.getWriter().write(testString);
                            } catch (Throwable ex) {
                                ex.printStackTrace();
                            }
                        }
                    });
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        });
        sendRequest(isSslEnabled);
    }

    @Test(dataProvider="isSslEnabled", enabled=true)
    public void testFastSuspendResumeGrizzlyAdapter(boolean isSslEnabled) throws Exception {
        System.err.println("Test: testFastSuspendResumeGrizzlyAdapter isSslEnabled=" + isSslEnabled);
        setAdapterAndListen(new GrizzlyAdapter() {

            @Override
            public void service(final GrizzlyRequest req, final GrizzlyResponse res) {
                try {
                    final long t1 = System.currentTimeMillis();
                    res.suspend(10 * 1000, "foo", new TestCompletionHandler<String>() {

                        @Override
                        public void resumed(String attachment) {
                            try {
                                System.err.println("Resumed TOOK: " + (System.currentTimeMillis() - t1));
                                res.getWriter().write(testString);
                                res.finishResponse();
                                // res.flushBuffer();
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        }
                    });
                } catch (Throwable t) {
                    t.printStackTrace();
                }

                new WorkerThreadImpl(new Runnable() {

                    public void run() {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ex) {
                            ex.printStackTrace();
                            fail(ex.getMessage());
                        }

                        if (!res.isCommitted()) {
                            System.err.println("Resuming");
                            res.resume();
                        } else {
                            fail("response is commited so we dont resume");
                        }
                    }
                }).start();
            }
        });
        sendRequest(isSslEnabled);
    }

    @Test(dataProvider="isSslEnabled", enabled=true)
    public void testSuspendResumeOneTransaction(final boolean isSslEnabled) throws Exception {
        System.err.println("Test: testSuspendResumeOneTransaction isSslEnabled=" + isSslEnabled);
        setAdapterAndListen(new TestStaticResourcesAdapter() {

            @Override
            public void service(final Request req, final Response res) {
                try {
                    res.suspend();
                    write(res, testData);
                    res.flush();
                    res.resume();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });
        sendRequest(isSslEnabled);
    }

    private void resumeLater(final GrizzlyResponse res) {
        pe.schedule(new Runnable() {

            public void run() {
                if (res.isSuspended()) {
                    try {
                        System.err.println("Now Resuming");
                        res.resume();
                    } catch (Throwable ex) {
                        ex.printStackTrace();
                        fail("resume failed: " + ex.getMessage());
                    }
                } else {
                    fail("not suspended, so we dont resume");
                }
            }
        }, 2, TimeUnit.SECONDS);
    }

    private void write(Response response, byte[] data) throws IOException {
        ByteChunk bc = new ByteChunk();
        bc.setBytes(data, 0, data.length);

        response.setContentType("custom/response");
        response.getOutputBuffer().doWrite(bc, response);
        response.flush();
    }

    private void sendRequest(boolean isSslEnabled) throws Exception {
        sendRequest(isSslEnabled, true);
    }

    private void sendRequest(boolean isSslEnabled, boolean assertResponse)
            throws Exception {
        Socket s;

        if (isSslEnabled) {
            s = SSLConfig.DEFAULT_CONFIG.createSSLContext().getSocketFactory().createSocket("localhost", PORT);
        } else {
            s = new Socket("localhost", PORT);
        }

        try {
            s.setSoTimeout(30 * 1000);
            OutputStream os = s.getOutputStream();

            System.err.println(("GET / HTTP/1.1\n"));
            os.write(("GET / HTTP/1.1\n").getBytes());
            os.write(("Host: localhost:" + PORT + "\n").getBytes());
            os.write("\n".getBytes());
            os.flush();

            InputStream is = new DataInputStream(s.getInputStream());
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String line = null;
            System.err.println("================== reading the response");
            boolean gotCorrectResponse = false;
            while ((line = br.readLine()) != null) {
                System.err.println("-> " + line + " --> " + line.startsWith(testString));
                if (line.startsWith(testString)) {
                    gotCorrectResponse = true;
                    break;
                }
            }
            if (assertResponse) {
                assertTrue(gotCorrectResponse);
            }
        } finally {
        }
    }

    private class TestStaticResourcesAdapter extends StaticResourcesAdapter {
        @Override
        public void service(Request req, Response res) {
            try {
                if (res.isSuspended()) {
                    super.service(req, res);
                } else {
                    dologic(req, res);
                }
            } catch (junit.framework.AssertionFailedError ae) {
            } catch (Throwable t) {
                t.printStackTrace();
                fail(t.getMessage());
            }
        }

        public void dologic(final Request req, final Response res) throws Throwable {
        }

        @Override
        public void afterService(final Request req, final Response res) {
            if (res.isSuspended()) {
                try {
                    super.afterService(req, res);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }

        protected void writeToSuspendedClient(Response resp) {
            if (resp.isSuspended()) {
                try {
                    write(resp, testData);
                } catch (Throwable ex) {
                    ex.printStackTrace();
                }
            } else {
                fail("Not Suspended.");
            }
        }

        protected void cancelLater(final Response res) {
            pe.schedule(new Runnable() {

                public void run() {
                    if (res.isSuspended()) {
                        try {
                            System.err.println("Now cancel");
                            res.cancel();
                        } catch (Throwable ex) {
                            ex.printStackTrace();
                            fail("cancel failed: " + ex.getMessage());
                        }
                    } else {
                        fail("not suspended, so we dont cancel");
                    }
                }
            }, 2, TimeUnit.SECONDS);
        }

        protected void resumeLater(final Response res) {
            pe.schedule(new Runnable() {

                public void run() {
                    if (res.isSuspended()) {
                        try {
                            System.err.println("Now Resuming");
                            res.resume();
                        } catch (Throwable ex) {
                            ex.printStackTrace();
                            fail("resume failed: " + ex.getMessage());
                        }
                    } else {
                        fail("not suspended, so we dont resume");
                    }
                }
            }, 2, TimeUnit.SECONDS);
        }
    }

    private class TestCompletionHandler<StaticResourcesAdapter> implements CompletionHandler<StaticResourcesAdapter> {

        public void resumed(StaticResourcesAdapter attachment) {
            fail("Unexpected resume");
        }

        public void cancelled(StaticResourcesAdapter attachment) {
            fail("Unexpected Cancel");
        }
    }
}
