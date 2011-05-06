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

import com.sun.grizzly.http.servlet.ServletAdapter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.logging.Logger;

/**
 * Test {@link com.sun.grizzly.tcp.http11.GrizzlyAdapterChain} use of the {@link com.sun.grizzly.http.MapperTest}
 *
 * @author Jeanfrancois Arcand
 */
public class MapperTest extends GrizzlyWebServerAbstractTest {

    public static final int PORT = 18080;
    private static Logger logger = Logger.getLogger("grizzly.test");

    public void testOverlapingMapping() throws IOException {
        System.out.println("testOverlapingMapping");
        try {
            startGrizzlyWebServer(PORT);
            String[] aliases = new String[]{"/aaa/bbb", "/aaa/ccc"};
            for (String alias : aliases) {
                addAdapter(alias);
            }

            for (String alias : aliases) {
                HttpURLConnection conn = getConnection(alias, PORT);
                assertEquals(HttpServletResponse.SC_OK,
                        getResponseCodeFromAlias(conn));
                assertEquals(alias, readResponse(conn));
            }
        } finally {
            stopGrizzlyWebServer();
        }
   }
    
    public void testRootMapping() throws IOException {
        System.out.println("testRootMapping");
        try {
            startGrizzlyWebServer(PORT);
            String alias = "/";
            addAdapter(alias);
            HttpURLConnection conn = getConnection("/index.html", PORT);
            assertEquals(HttpServletResponse.SC_OK,
                    getResponseCodeFromAlias(conn));
            assertEquals(alias, readResponse(conn));
        } finally {
            stopGrizzlyWebServer();
        }
    }
    
    public void testWrongMapping() throws IOException {
        System.out.println("testWrongMapping");
        try {
            startGrizzlyWebServer(PORT);
            String alias = "/a/b/c";
            addAdapter(alias);
            HttpURLConnection conn = getConnection("/aaa.html", PORT);
            assertEquals(HttpServletResponse.SC_NOT_FOUND,
                    getResponseCodeFromAlias(conn));
        } finally {
            stopGrizzlyWebServer();
        }
    }

    public void testComplexMapping() throws IOException {
        System.out.println("testComplexMapping");
        try {
            startGrizzlyWebServer(PORT);
            String alias = "/a/b/c/*.html";
            addAdapter(alias);
            HttpURLConnection conn = getConnection("/a/b/c/index.html", PORT);
            assertEquals(HttpServletResponse.SC_OK,
                    getResponseCodeFromAlias(conn));
            assertEquals(alias, readResponse(conn));
        } finally {
            stopGrizzlyWebServer();
        }
    }
    
    public void testWildcardMapping() throws IOException {
        System.out.println("testWildcardMapping");
        try {
            startGrizzlyWebServer(PORT);
            String alias = "/*.html";
            addAdapter(alias);
            HttpURLConnection conn = getConnection("/index.html", PORT);
            assertEquals(HttpServletResponse.SC_OK,
                    getResponseCodeFromAlias(conn));
            assertEquals(alias, readResponse(conn));
        } finally {
            stopGrizzlyWebServer();
        }
    }
    
     public void __testWrongMappingRootContext() throws IOException {
        System.out.println("testWrongMappingRootContext");
        try {
            startGrizzlyWebServer(PORT);
            String alias = "/*.a";
            addAdapter(alias);
            HttpURLConnection conn = getConnection("/aaa.html", PORT);
            assertEquals(HttpServletResponse.SC_NOT_FOUND, getResponseCodeFromAlias(conn));
        } finally {
            stopGrizzlyWebServer();
        }
    }


    private ServletAdapter addAdapter(final String alias) {
        ServletAdapter adapter = new ServletAdapter(new HttpServlet() {

            @Override
            protected void doGet(
                    HttpServletRequest req, HttpServletResponse resp)
                    throws ServletException, IOException {
                logger.info("Servlet : " + alias + " received request " + req.getRequestURI());
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.getWriter().write(alias);
            }
        });
        gws.addGrizzlyAdapter(adapter, new String[]{alias});
        return adapter;
    }

}
