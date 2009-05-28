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
package filter;

import com.sun.grizzly.http.servlet.ServletAdapter;
import com.sun.jersey.api.core.ClasspathResourceConfig;
import com.sun.jersey.spi.container.servlet.ServletContainer;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.Filter;
import javax.ws.rs.core.UriBuilder;
import com.sun.grizzly.Transport;
import com.sun.grizzly.TransportFactory;
import com.sun.grizzly.filterchain.TransportFilter;
import com.sun.grizzly.nio.transport.TCPNIOTransport;
import com.sun.grizzly.http.WebFilter;
import com.sun.grizzly.tcp.Adapter;

public class Main {

    public static final URI BASE_URI = UriBuilder.fromUri("http://localhost/").port(9998).build();

    protected static Transport startServer() throws IOException {
        final Map<String, String> initParams = new HashMap<String, String>();

        initParams.put("com.sun.jersey.config.property.packages",
                "filter");

        System.out.println("Starting grizzly...");
        Transport transport = create(BASE_URI, initParams);
        return transport;
    }

    public static void main(String[] args) throws IOException {
        Transport transport = startServer();
        System.out.println(String.format("Jersey app started with WADL available at " + "%sapplication.wadl\nHit enter to stop it...",
                BASE_URI));
        System.in.read();
        transport.stop();
        TransportFactory.getInstance().close();
    }

    private static Transport create(URI u,
            Map<String, String> initParams) throws IOException {
        return create(u, ServletContainer.class, initParams);
    }

    private static Transport create(URI u, Class<? extends Filter> c,
            Map<String, String> initParams) throws IOException {
        if (u == null) {
            throw new IllegalArgumentException("The URI must not be null");
        }

        ServletAdapter adapter = new ServletAdapter();
        if (initParams == null) {
            adapter.addInitParameter(ClasspathResourceConfig.PROPERTY_CLASSPATH,
                    System.getProperty("java.class.path").replace(File.pathSeparatorChar, ';'));
        } else {
            for (Map.Entry<String, String> e : initParams.entrySet()) {
                adapter.addInitParameter(e.getKey(), e.getValue());
            }
        }

        adapter.addFilter(getInstance(c), "filter", initParams);

        String path = u.getPath();
        if (path == null) {
            throw new IllegalArgumentException("The URI path, of the URI " + u +
                    ", must be non-null");
        } else if (path.length() == 0) {
            throw new IllegalArgumentException("The URI path, of the URI " + u +
                    ", must be present");
        } else if (path.charAt(0) != '/') {
            throw new IllegalArgumentException("The URI path, of the URI " + u +
                    ". must start with a '/'");
        }

        if (path.length() > 1) {
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            adapter.setContextPath(path);
        }

        return create(u, adapter);
    }

    private static Filter getInstance(Class<? extends Filter> c) {
        try {
            return c.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Transport create(URI u, Adapter adapter)
            throws IOException, IllegalArgumentException {
        if (u == null) {
            throw new IllegalArgumentException("The URI must not be null");
        }

        // TODO support https
        final String scheme = u.getScheme();
        if (!scheme.equalsIgnoreCase("http")) {
            throw new IllegalArgumentException("The URI scheme, of the URI " + u +
                    ", must be equal (ignoring case) to 'http'");
        }

        final int port = (u.getPort() == -1) ? 80 : u.getPort();

        TCPNIOTransport transport = TransportFactory.getInstance().createTCPTransport();
        WebFilter webFilter = new WebFilter("web-" + port);
        webFilter.getConfig().setAdapter(adapter);

        transport.getFilterChain().add(new TransportFilter());
        transport.getFilterChain().add(webFilter);
        webFilter.initialize();
        transport.bind(port);
        transport.start();
        return transport;
    }
}
