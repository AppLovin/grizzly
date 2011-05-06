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

package com.sun.grizzly.config;

import com.sun.grizzly.tcp.StaticResourcesAdapter;
import org.jvnet.hk2.config.Dom;
import org.testng.Assert;
import org.testng.annotations.Test;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.logging.Logger;

@Test
public class HttpRedirectTest extends BaseGrizzlyConfigTest {


    // ------------------------------------------------------------ Test Methods


    public void legacyHttpToHttpsRedirect() {
        GrizzlyConfig grizzlyConfig = new GrizzlyConfig("legacy-http-https-redirect.xml");
        grizzlyConfig.setupNetwork();
        int count = 0;
        for (GrizzlyServiceListener listener : grizzlyConfig.getListeners()) {
            setRootFolder(listener, count++);
        }

        try {
            Socket s = new Socket("localhost", 48480);
            OutputStream out = s.getOutputStream();
            out.write("GET / HTTP/1.1\n".getBytes());
            out.write(("Host: localhost:" + 48480 + "\n").getBytes());
            out.write("\n".getBytes());
            out.flush();
            InputStream in = s.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            boolean found = false;
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                if (line.length() > 0 && line.toLowerCase().charAt(0) == 'l') {
                    Assert.assertEquals(line.toLowerCase(), "location: https://localhost:48480/");
                    found = true;
                    break;
                }
            }
            if (!found) {
                Assert.fail("Unable to find Location header in response - no redirect occurred.");
            }
        } catch (Exception e) {
            Assert.fail(e.toString(), e);
        } finally {
            grizzlyConfig.shutdownNetwork();
        }
    }


    public void legacyHttpsToHttpRedirect() {
        GrizzlyConfig grizzlyConfig = new GrizzlyConfig("legacy-https-http-redirect.xml");
        grizzlyConfig.setupNetwork();
        int count = 0;
        for (GrizzlyServiceListener listener : grizzlyConfig.getListeners()) {
            setRootFolder(listener, count++);
        }

        try {
            SocketFactory f = getSSLSocketFactory();
            Socket s = f.createSocket("localhost", 48480);
            s.setSoTimeout(50000);
            OutputStream out = s.getOutputStream();
            out.write("GET / HTTP/1.1\n".getBytes());
            out.write(("Host: localhost:" + 48480 + "\n").getBytes());
            out.write("\n".getBytes());
            out.flush();
            InputStream in = s.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            boolean found = false;
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                if (line.length() > 0 && line.toLowerCase().charAt(0) == 'l') {
                    Assert.assertEquals(line.toLowerCase(), "location: http://localhost:48480/");
                    found = true;
                    break;
                }
            }
            if (!found) {
                Assert.fail("Unable to find Location header in response - no redirect occurred.");
            }
        } catch (Exception e) {
            Assert.fail(e.toString(), e);
        } finally {
            grizzlyConfig.shutdownNetwork();
        }
    }


    public void httpToHttpsSamePortRedirect() {
        GrizzlyConfig grizzlyConfig = new GrizzlyConfig("http-https-redirect-same-port.xml");
        grizzlyConfig.setupNetwork();
        int count = 0;
        for (GrizzlyServiceListener listener : grizzlyConfig.getListeners()) {
            setRootFolder(listener, count++);
        }

        try {
            Socket s = new Socket("localhost", 48480);
            OutputStream out = s.getOutputStream();
            out.write("GET / HTTP/1.1\n".getBytes());
            out.write(("Host: localhost:" + 48480 + "\n").getBytes());
            out.write("\n".getBytes());
            out.flush();
            InputStream in = s.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            boolean found = false;
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                if (line.length() > 0 && line.toLowerCase().charAt(0) == 'l') {
                    Assert.assertEquals(line.toLowerCase(), "location: https://localhost:48480/");
                    found = true;
                    break;
                }
            }
            if (!found) {
                Assert.fail("Unable to find Location header in response - no redirect occurred.");
            }
        } catch (Exception e) {
            Assert.fail(e.toString(), e);
        } finally {
            grizzlyConfig.shutdownNetwork();
        }
    }


    public void httpsToHttpSamePortRedirect() {
        GrizzlyConfig grizzlyConfig = new GrizzlyConfig("https-http-redirect-same-port.xml");
        grizzlyConfig.setupNetwork();
        int count = 0;
        for (GrizzlyServiceListener listener : grizzlyConfig.getListeners()) {
            setRootFolder(listener, count++);
        }

        try {
            SocketFactory f = getSSLSocketFactory();
            Socket s = f.createSocket("localhost", 48480);
            s.setSoTimeout(50000);
            OutputStream out = s.getOutputStream();
            out.write("GET / HTTP/1.1\n".getBytes());
            out.write(("Host: localhost:" + 48480 + "\n").getBytes());
            out.write("\n".getBytes());
            out.flush();
            InputStream in = s.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            boolean found = false;
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                if (line.length() > 0 && line.toLowerCase().charAt(0) == 'l') {
                    Assert.assertEquals(line.toLowerCase(), "location: http://localhost:48480/");
                    found = true;
                    break;
                }
            }
            if (!found) {
                Assert.fail("Unable to find Location header in response - no redirect occurred.");
            }
        } catch (Exception e) {
            Assert.fail(e.toString(), e);
        } finally {
            grizzlyConfig.shutdownNetwork();
        }
    }


    public void httpToHttpsDifferentPortRedirect() {
        GrizzlyConfig grizzlyConfig = new GrizzlyConfig("http-https-redirect-different-port.xml");
        grizzlyConfig.setupNetwork();
        int count = 0;
        for (GrizzlyServiceListener listener : grizzlyConfig.getListeners()) {
            setRootFolder(listener, count++);
        }

        try {
            Socket s = new Socket("localhost", 48480);
            OutputStream out = s.getOutputStream();
            out.write("GET / HTTP/1.1\n".getBytes());
            out.write(("Host: localhost:" + 48480 + "\n").getBytes());
            out.write("\n".getBytes());
            out.flush();
            InputStream in = s.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            boolean found = false;
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                if (line.length() > 0 && line.toLowerCase().charAt(0) == 'l') {
                    Assert.assertEquals(line.toLowerCase(), "location: https://localhost:48481/");
                    found = true;
                    break;
                }
            }
            if (!found) {
                Assert.fail("Unable to find Location header in response - no redirect occurred.");
            }
        } catch (Exception e) {
            Assert.fail(e.toString(), e);
        } finally {
            grizzlyConfig.shutdownNetwork();
        }
    }


    public void httpsToHttpDifferentPortRedirect() {
        GrizzlyConfig grizzlyConfig = new GrizzlyConfig("https-http-redirect-different-port.xml");
        grizzlyConfig.setupNetwork();
        int count = 0;
        for (GrizzlyServiceListener listener : grizzlyConfig.getListeners()) {
            setRootFolder(listener, count++);
        }

        try {
            SocketFactory f = getSSLSocketFactory();
            Socket s = f.createSocket("localhost", 48480);
            s.setSoTimeout(50000);
            OutputStream out = s.getOutputStream();
            out.write("GET / HTTP/1.1\n".getBytes());
            out.write(("Host: localhost:" + 48480 + "\n").getBytes());
            out.write("\n".getBytes());
            out.flush();
            InputStream in = s.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            boolean found = false;
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                if (line.length() > 0 && line.toLowerCase().charAt(0) == 'l') {
                    Assert.assertEquals(line.toLowerCase(), "location: http://localhost:48481/");
                    found = true;
                    break;
                }
            }
            if (!found) {
                Assert.fail("Unable to find Location header in response - no redirect occurred.");
            }
        } catch (Exception e) {
            Assert.fail(e.toString(), e);
        } finally {
            grizzlyConfig.shutdownNetwork();
        }
    }

    // --------------------------------------------------------- Private Methods

    @Override
    protected void setRootFolder(final GrizzlyServiceListener listener, final int count) {

        final StaticResourcesAdapter adapter = (StaticResourcesAdapter) listener.getEmbeddedHttp().getAdapter();
        final String name = System.getProperty("java.io.tmpdir", "/tmp") + "/"
            + Dom.convertName(getClass().getSimpleName()) + count;
        File dir = new File(name);
        dir.mkdirs();
        
        adapter.addRootFolder(name);

    }


    public SSLSocketFactory getSSLSocketFactory() throws IOException {
        try {
            //---------------------------------
            // Create a trust manager that does not validate certificate chains
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    public void checkClientTrusted(
                        X509Certificate[] certs, String authType) {
                    }

                    public void checkServerTrusted(
                        X509Certificate[] certs, String authType) {
                    }
                }
            };
            // Install the all-trusting trust manager
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new SecureRandom());
            //---------------------------------
            return sc.getSocketFactory();
        } catch (Exception e) {
            e.printStackTrace();
            throw new IOException(e.getMessage());
        }
    }


}
