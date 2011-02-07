/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2008-2011 Oracle and/or its affiliates. All rights reserved.
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
package org.glassfish.grizzly.servlet;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.ServletResponse;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.http.Cookie;
import org.glassfish.grizzly.http.server.AfterServiceListener;
import org.glassfish.grizzly.http.server.Constants;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Request.Note;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.server.util.ClassLoaderUtil;
import org.glassfish.grizzly.http.server.util.IntrospectionUtils;
import org.glassfish.grizzly.http.util.CharChunk;
import org.glassfish.grizzly.http.util.HttpRequestURIDecoder;
import org.glassfish.grizzly.localization.LogMessages;

/**
 * Adapter class that can initiate a {@link javax.servlet.FilterChain} and execute its
 * {@link Filter} and its {@link Servlet}
 * 
 * Configuring a {@link com.sun.grizzly.http.embed.GrizzlyWebServer} or
 * {@link com.sun.grizzly.http.SelectorThread} to use this
 * {@link GrizzlyAdapter} implementation add the ability of servicing {@link Servlet}
 * as well as static resources. 
 * 
 * This class can be used to programatically configure a Servlet, Filters, listeners,
 * init parameters, context-param, etc. a application usually defined using the web.xml.
 * See {@link #addInitParameter(String, String)} {@link #addContextParameter(String, String)}
 * {@link #setProperty(String, Object)}, {@link #addServletListener(String)}, etc.
 * 
 * As an example:
 * 
 * <pre><code>
 *      GrizzlyWebServer ws = new GrizzlyWebServer("/var/www");
try{
ServletHandler sa = new ServletHandler();
sa.setRootFolder("/Path/To/Exploded/War/File");
sa.setServlet(new MyServlet());

// Set the Servlet's Name
// Any ServletConfig.getXXX method can be configured using this call.
// The same apply for ServletContext.getXXX.
sa.setProperty("display-name","myServlet");
sa.addListener("foo.bar.myHttpSessionListener");
sa.addListener(MyOtherHttpSessionListener.class);
sa.addServletContextListener(new FooServletContextListener());
sa.addServletContextAttributeListener(new BarServletCtxAttListener());
sa.addContextParameter("databaseURI","jdbc://");
sa.addInitParameter("password","hello"); 
sa.setServletPath("/MyServletPath");
sa.setContextPath("/myApp");

ws.addGrizzlyAdapter(sa);

ws.start();
} catch (IOException ex){
// Something when wrong.
}
 * </code></pre>
 * 
 * @author Jeanfrancois Arcand
 */
public class ServletHandler extends HttpHandler {

    private static final Logger LOGGER = Grizzly.logger(ServletHandler.class);

    static final Note<HttpServletRequestImpl> SERVLET_REQUEST_NOTE =
            Request.createNote(HttpServletRequestImpl.class.getName());
    static final Note<HttpServletResponseImpl> SERVLET_RESPONSE_NOTE =
            Request.createNote(HttpServletResponseImpl.class.getName());

    static final ServletAfterServiceListener servletAfterServiceListener =
            new ServletAfterServiceListener();

    public static final String LOAD_ON_STARTUP = "load-on-startup";
    protected volatile Servlet servletInstance = null;
    private transient List<String> listeners = new ArrayList<String>();
    private String servletPath = "";
    private String contextPath = "";
    // Instanciate the Servlet when the start method is invoked.
    private boolean loadOnStartup = false;
    /**
     * The context parameters.
     */
    private Map<String, String> contextParameters = new HashMap<String, String>();
    /**
     * The servlet initialization parameters
     */
    private Map<String, String> servletInitParameters = new HashMap<String, String>();
    /**
     * Is the Servlet initialized.
     */
    private volatile boolean filterChainConfigured = false;
    private ReentrantLock filterChainReady = new ReentrantLock();
    /**
     * The {@link ServletContextImpl}
     */
    private final ServletContextImpl servletCtx;
    /**
     * The {@link ServletConfigImpl}
     */
    private ServletConfigImpl servletConfig;
    /**
     * Holder for our configured properties.
     */
    protected Map<String, Object> properties = new HashMap<String, Object>();
    /**
     * Initialize the {@link ServletContext}
     */
    protected boolean initialize = true;
    protected ClassLoader classLoader;
    private final static Object[] lock = new Object[0];
    /**
     * Filters.
     */
    private FilterConfigImpl[] filters = new FilterConfigImpl[8];
    public static final int INCREMENT = 8;
    /**
     * The int which gives the current number of filters.
     */
    private int n = 0;

    public ServletHandler() {
        this(new ServletContextImpl(),
                new HashMap<String, String>(), new HashMap<String, String>(),
                new ArrayList<String>());
    }

    /**
     * Create a ServletAdapter which support the specific Servlet
     *
     * @param servlet Instance to be used by this adapter.
     */
    public ServletHandler(Servlet servlet) {
        this();
        this.servletInstance = servlet;
    }

    /**
     * Convenience constructor.
     *
     * @param servletCtx {@link ServletContextImpl} to be used by new instance.
     * @param contextParameters Context parameters.
     * @param servletInitParameters servlet initialization parameters.
     * @param listeners Listeners.
     */
    protected ServletHandler(ServletContextImpl servletCtx,
            Map<String, String> contextParameters, Map<String, String> servletInitParameters,
            List<String> listeners) {
        this(servletCtx, contextParameters, servletInitParameters, listeners, true);
    }

    /**
     * Convenience constructor.
     *
     * @param servletCtx {@link ServletContextImpl} to be used by new instance.
     * @param contextParameters Context parameters.
     * @param servletInitParameters servlet initialization parameters.
     * @param listeners Listeners.
     * @param initialize false only when the {@link #newServletAdapter()} is invoked.
     */
    protected ServletHandler(ServletContextImpl servletCtx,
            Map<String, String> contextParameters, Map<String, String> servletInitParameters,
            List<String> listeners, boolean initialize) {
        this.servletCtx = servletCtx;
        servletConfig = new ServletConfigImpl(servletCtx, servletInitParameters);
        this.contextParameters = contextParameters;
        this.servletInitParameters = servletInitParameters;
        this.listeners = listeners;
        this.initialize = initialize;
    }

    /**
     * Convenience constructor.
     *
     * @param servletCtx {@link ServletContextImpl} to be used by new instance.
     * @param contextParameters Context parameters.
     * @param servletInitParameters servlet initialization parameters.
     * @param initialize false only when the {@link #newServletAdapter()} is invoked.
     */
    protected ServletHandler(ServletContextImpl servletCtx,
            Map<String, String> contextParameters, Map<String, String> servletInitParameters,
            boolean initialize) {
        this.servletCtx = servletCtx;
        servletConfig = new ServletConfigImpl(servletCtx, servletInitParameters);
        this.contextParameters = contextParameters;
        this.servletInitParameters = servletInitParameters;
        this.initialize = initialize;
    }

    public ServletHandler(Servlet servlet, ServletContextImpl servletContext) {
        servletInstance = servlet;
        servletCtx = servletContext;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        try {
            configureServletEnv();
            
            if (initialize) {
//                initWebDir();
                configureClassLoader(new File(servletCtx.getBasePath()).getCanonicalPath());
            }

            if (classLoader != null) {
                ClassLoader prevClassLoader = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(classLoader);
                try {
//                    setContextPath(contextPath);
                    if (loadOnStartup) {
                        loadServlet();
                    }
                } finally {
                    Thread.currentThread().setContextClassLoader(prevClassLoader);
                }
            } else {
//                setContextPath(contextPath);
                if (loadOnStartup) {
                    loadServlet();
                }
            }
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "start", t);
        }
    }

    /**
     * Create a {@link java.net.URLClassLoader} which has the capability of
     * loading classes jar under an exploded war application.
     *
     * @param applicationPath Application class path.
     * @throws java.io.IOException I/O error.
     */
    protected void configureClassLoader(String applicationPath) throws IOException {
        if (classLoader == null) {
            classLoader = ClassLoaderUtil.createURLClassLoader(applicationPath);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void service(Request request, Response response) {
        if (classLoader != null) {
            final ClassLoader prevClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(classLoader);
            try {
                doServletService(request, response);
            } finally {
                Thread.currentThread().setContextClassLoader(prevClassLoader);
            }
        } else {
            doServletService(request, response);
        }
    }

    protected void doServletService(final Request request, final Response response) {
        try {
//            Request req = request.getRequest();
//            Response res = response.getResponse();
            final String uri = request.getRequestURI();

            // The request is not for us.
            if (!uri.startsWith(contextPath)) {
                customizeErrorPage(response, "Resource Not Found", 404);
                return;
            }

            final HttpServletRequestImpl servletRequest = HttpServletRequestImpl.create();
            final HttpServletResponseImpl servletResponse = HttpServletResponseImpl.create();

            servletRequest.initialize(request);
            servletResponse.initialize(response);

            request.setNote(SERVLET_REQUEST_NOTE, servletRequest);
            request.setNote(SERVLET_RESPONSE_NOTE, servletResponse);

            request.addAfterServiceListener(servletAfterServiceListener);
            
            final Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie c : cookies) {
                    if (Constants.SESSION_COOKIE_NAME.equals(c.getName())) {
                        request.setRequestedSessionId(c.getValue());
                        request.setRequestedSessionCookie(true);
                        break;
                    }
                }
            }

            loadServlet();

            servletRequest.setContextImpl(servletCtx);
            servletRequest.setServletPath(servletPath);
            servletRequest.initSession();

            //TODO: Make this configurable.
            servletResponse.addHeader("server", "grizzly/" + Grizzly.getDotedVersion());
            FilterChainImpl filterChain = new FilterChainImpl(servletInstance, servletConfig);
            filterChain.invokeFilterChain(servletRequest, servletResponse);
        } catch (Throwable ex) {
            LOGGER.log(Level.SEVERE, "service exception:", ex);
            customizeErrorPage(response, "Internal Error", 500);
        }
    }

    /**
     * Customize the error page returned to the client.
     * @param response  the {@link Response}
     * @param message   the HTTP error message
     * @param errorCode the error code.
     */
    public void customizeErrorPage(Response response, String message, int errorCode) {
        response.setStatus(errorCode, message);
        response.setContentType("text/html");
        try {
            response.getWriter().write("<html><body><h1>" + message
                    + "</h1></body></html>");
            response.getWriter().flush();
        } catch (IOException ex) {
            // We are in a very bad shape. Ignore.
        }
    }

    /**
     * Load a {@link Servlet} instance.
     *
     * @throws javax.servlet.ServletException If failed to
     * {@link Servlet#init(javax.servlet.ServletConfig)}.
     */
    protected void loadServlet() throws ServletException {

        try {
            filterChainReady.lock();
            if (filterChainConfigured) {
                return;
            }

            if (servletInstance == null) {
                String servletClassName = System.getProperty("com.sun.grizzly.servletClass");
                if (servletClassName != null) {
                    servletInstance = (Servlet) ClassLoaderUtil.load(servletClassName);
                }

                if (servletInstance != null) {
                    LOGGER.log(Level.INFO, "Loading Servlet: {0}", servletInstance.getClass().getName());
                }
            }

            if (servletInstance != null) {
                servletInstance.init(servletConfig);
            }

            for (FilterConfigImpl f : filters) {
                if (f != null) {
                    f.getFilter().init(f);
                }
            }

            filterChainConfigured = true;
        } finally {
            filterChainReady.unlock();
        }
    }

    /**
     * Configure the {@link com.sun.grizzly.http.servlet.ServletContextImpl}
     * and {@link com.sun.grizzly.http.servlet.ServletConfigImpl}
     * 
     * @throws javax.servlet.ServletException Error while configuring
     * {@link Servlet}.
     */
    protected void configureServletEnv() throws ServletException {
        if (contextPath.length() > 0) {
            final CharChunk cc = new CharChunk();
            char[] ch = contextPath.toCharArray();
            cc.setChars(ch, 0, ch.length);
            HttpRequestURIDecoder.normalizeChars(cc);
            contextPath = cc.toString();
        }

        if ("/".equals(contextPath)) {
            contextPath = "";
        }

        if (initialize) {
            servletCtx.setInitParameter(contextParameters);
            servletCtx.setContextPath(contextPath);

            servletCtx.setBasePath(".");

            configureProperties(servletCtx);
            servletCtx.initListeners(listeners);
        }
        servletConfig.setInitParameters(servletInitParameters);
        configureProperties(servletConfig);
    }

    /**
     * Add a new servlet initialization parameter for this servlet.
     *
     * @param name Name of this initialization parameter to add
     * @param value Value of this initialization parameter to add
     */
    public void addInitParameter(String name, String value) {
        servletInitParameters.put(name, value);
    }

    /**
     * Remove a servlet initialization parameter for this servlet.
     *
     * @param name Name of this initialization parameter to remove
     */
    public void removeInitParameter(String name) {
        servletInitParameters.remove(name);
    }

    /**
     * get a servlet initialization parameter for this servlet.
     *
     * @param name Name of this initialization parameter to retrieve
     */
    public String getInitParameter(String name) {
        return servletInitParameters.get(name);
    }

    /**
     * if the servlet initialization parameter in present for this servlet.
     *
     * @param name Name of this initialization parameter 
     */
    public boolean containsInitParameter(String name) {
        return servletInitParameters.containsKey(name);
    }

    /**
     * Add a new servlet context parameter for this servlet.
     *
     * @param name Name of this initialization parameter to add
     * @param value Value of this initialization parameter to add
     */
    public void addContextParameter(String name, String value) {
        contextParameters.put(name, value);
    }

    /**
     * Add a {@link Filter} to the
     * {@link com.sun.grizzly.http.servlet.ServletAdapter.FilterChainImpl}
     *
     * @param filter an instance of Filter
     * @param filterName the Filter's name
     * @param initParameters the Filter init parameters.
     */
    public void addFilter(Filter filter, String filterName, Map initParameters) {
        FilterConfigImpl filterConfig = new FilterConfigImpl(servletCtx);
        filterConfig.setFilter(filter);
        filterConfig.setFilterName(filterName);
        filterConfig.setInitParameters(initParameters);
        addFilter(filterConfig);
    }

    /**
     * Return the {@link Servlet} instance used by this {@link ServletAdapter}
     * @return {@link Servlet} isntance.
     */
    public Servlet getServletInstance() {
        return servletInstance;
    }

    /**
     * Set the {@link Servlet} instance used by this {@link ServletAdapter}
     * @param servletInstance an instance of Servlet.
     */
    public void setServletInstance(Servlet servletInstance) {
        this.servletInstance = servletInstance;
    }

    /**
     *
     * Returns the part of this request's URL that calls
     * the servlet. This path starts with a "/" character
     * and includes either the servlet name or a path to
     * the servlet, but does not include any extra path
     * information or a query string. Same as the value of
     * the CGI variable SCRIPT_NAME.
     *
     * <p>This method will return an empty string ("") if the
     * servlet used to process this request was matched using
     * the "/*" pattern.
     *
     * @return		a <code>String</code> containing
     *			the name or path of the servlet being
     *			called, as specified in the request URL,
     *			decoded, or an empty string if the servlet
     *			used to process the request is matched
     *			using the "/*" pattern.
     *
     */
    public String getServletPath() {
        return servletPath;
    }

    /**
     * Programmatically set the servlet path of the Servlet.
     *
     * @param servletPath Path of {@link Servlet}.
     */
    public void setServletPath(String servletPath) {
        this.servletPath = servletPath;
        if (!servletPath.equals("") && !servletPath.startsWith("/")) {
            servletPath = "/" + servletPath;
        }
    }

    /**
     *
     * Returns the portion of the request URI that indicates the context
     * of the request. The context path always comes first in a request
     * URI. The path starts with a "/" character but does not end with a "/"
     * character. For servlets in the default (root) context, this method
     * returns "". The container does not decode this string.
     *
     * <p>It is possible that a servlet container may match a context by
     * more than one context path. In such cases this method will return the
     * actual context path used by the request and it may differ from the
     * path returned by the
     * {@link javax.servlet.ServletContext#getContextPath()} method.
     * The context path returned by
     * {@link javax.servlet.ServletContext#getContextPath()}
     * should be considered as the prime or preferred context path of the
     * application.
     *
     * @return		a <code>String</code> specifying the
     *			portion of the request URI that indicates the context
     *			of the request
     *
     * @see javax.servlet.ServletContext#getContextPath()
     */
    public String getContextPath() {
        return contextPath;
    }

    /**
     * Programmatically set the context path of the Servlet.
     *
     * @param contextPath Context path.
     */
    public void setContextPath(String contextPath) {
        this.contextPath = contextPath;
    }

    /**
     * Add Servlet listeners that implement {@link EventListener}
     *
     * @param listenerName name of a Servlet listener
     */
    public void addServletListener(String listenerName) {
        if (listenerName == null) {
            return;
        }
        listeners.add(listenerName);
    }

    /**
     * Remove Servlet listeners that implement {@link EventListener}
     *
     * @param listenerName name of a Servlet listener to remove
     */
    public boolean removeServletListener(String listenerName) {
        return listenerName != null && listeners.remove(listenerName);
    }

    /**
     * Use reflection to configure Object setter.
     *
     * @param object Populate this object with available properties.
     */
    private void configureProperties(Object object) {
        for (String s : properties.keySet()) {
            String value = properties.get(s).toString();
            IntrospectionUtils.setProperty(object, s, value);
        }
    }

    /**
     * Return a configured property. Property apply to
     * {@link com.sun.grizzly.http.servlet.ServletContextImpl}
     * and {@link com.sun.grizzly.http.servlet.ServletConfigImpl}
     *
     * @param name Name of property to get.
     * @return Value of property.
     */
    public Object getProperty(String name) {
        return properties.get(name);
    }

    /**
     * Set a configured property. Property apply to
     * {@link com.sun.grizzly.http.servlet.ServletContextImpl}
     * and {@link com.sun.grizzly.http.servlet.ServletConfigImpl}.
     * Use this method to map what's you usually
     * have in a web.xml like display-name, context-param, etc.
     * @param name Name of the property to set
     * @param value of the property.
     */
    public void setProperty(String name, Object value) {

        /**
         * Servlet 2.4 specs
         *
         *  If the value is a negative integer,
         *  or the element is not present, the container is free to load the
         *  servlet whenever it chooses. If the value is a positive integer
         *  or 0, the container must load and initialize the servlet as the
         *  application is deployed.
         */
        if (name.equalsIgnoreCase(LOAD_ON_STARTUP) && value != null) {
            if (value instanceof Boolean && ((Boolean) value) == true) {
                loadOnStartup = true;
            } else {
                try {
                    if ((new Integer(value.toString())) >= 0) {
                        loadOnStartup = true;
                    }
                } catch (Exception e) {
                }
            }

        }

        // Get rid of "-";
        int pos = name.indexOf("-");
        if (pos > 0) {
            String pre = name.substring(0, pos);
            String post = name.substring(pos + 1, pos + 2).toUpperCase() + name.substring(pos + 2);
            name = pre + post;
        }
        properties.put(name, value);

    }

    /** 
     * Remove a configured property. Property apply to
     * {@link com.sun.grizzly.http.servlet.ServletContextImpl}
     * and {@link com.sun.grizzly.http.servlet.ServletConfigImpl}
     *
     * @param name Property name to remove.
     */
    public void removeProperty(String name) {
        properties.remove(name);
    }

    /**
     * 
     * @return is the servlet will be loaded at startup
     */
    public boolean isLoadOnStartup() {
        return loadOnStartup;
    }

    /**
     * Destroy this Servlet and its associated
     * {@link javax.servlet.ServletContextListener}
     */
    @Override
    public void destroy() {
        if (classLoader != null) {
            ClassLoader prevClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(classLoader);
            try {
                super.destroy();
                servletCtx.destroyListeners();
                for (FilterConfigImpl filter : filters) {
                    if (filter != null) {
                        filter.recycle();
                    }
                }
                if (servletInstance != null) {
                    servletInstance.destroy();
                    servletInstance = null;
                }
                filters = null;
            } finally {
                Thread.currentThread().setContextClassLoader(prevClassLoader);
            }
        } else {
            super.destroy();
            servletCtx.destroyListeners();
        }
    }

    /**
     * Create a new {@link ServletAdapter} instance that will share the same 
     * {@link com.sun.grizzly.http.servlet.ServletContextImpl} and Servlet's
     * listener but with an empty map of init-parameters.
     *
     * @param servlet - The Servlet associated with the {@link ServletAdapter}
     * @return a new {@link ServletAdapter}
     */
    public ServletHandler newServletHandler(Servlet servlet) {
        ServletHandler sa = new ServletHandler(servletCtx, contextParameters,
                new HashMap<String, String>(), listeners,
                false);
        if (classLoader != null) {
            sa.setClassLoader(classLoader);
        }
        sa.setServletInstance(servlet);
        sa.setServletPath(servletPath);
        return sa;
    }

    protected ServletContextImpl getServletCtx() {
        return servletCtx;
    }

    protected List<String> getListeners() {
        return listeners;
    }

    protected Map<String, String> getContextParameters() {
        return contextParameters;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

//    private String getDefaultDocRootPath() {
//        final File[] basePaths = getDocRoots().getArray();
//        return (basePaths != null && basePaths.length > 0) ? basePaths[0].getPath() : null;
//    }
//
//    private File getDefaultDocRoot() {
//        final File[] basePaths = getDocRoots().getArray();
//        return (basePaths != null && basePaths.length > 0) ? basePaths[0] : null;
//    }

    /**
     * Add a filter to the set of filters that will be executed in this chain.
     *
     * @param filterConfig The FilterConfig for the servlet to be executed
     */
    protected void addFilter(FilterConfigImpl filterConfig) {
        synchronized (lock) {
            if (n == filters.length) {
                FilterConfigImpl[] newFilters =
                        new FilterConfigImpl[n + INCREMENT];
                System.arraycopy(filters, 0, newFilters, 0, n);
                filters = newFilters;
            }

            filters[n++] = filterConfig;
        }
    }

    // ---------------------------------------------------------- Nested Classes
    /**
     * Implementation of <code>javax.servlet.FilterChain</code> used to manage
     * the execution of a set of filters for a particular request.  When the
     * set of defined filters has all been executed, the next call to
     * <code>doFilter()</code> will execute the servlet's <code>service()</code>
     * method itself.
     *
     * @author Craig R. McClanahan
     */
    private final class FilterChainImpl implements FilterChain {

        /**
         * The servlet instance to be executed by this chain.
         */
        private final Servlet servlet;
        private final ServletConfigImpl configImpl;
        /**
         * The int which is used to maintain the current position
         * in the filter chain.
         */
        private int pos = 0;

        public FilterChainImpl(final Servlet servlet,
                final ServletConfigImpl configImpl) {

            this.servlet = servlet;
            this.configImpl = configImpl;
        }

        // ---------------------------------------------------- FilterChain Methods
        protected void invokeFilterChain(ServletRequest request, ServletResponse response)
                throws IOException, ServletException {

            ServletRequestEvent event =
                    new ServletRequestEvent(configImpl.getServletContext(), request);
            try {
                for (EventListener l : ((ServletContextImpl) configImpl.getServletContext()).getListeners()) {
                    try {
                        if (l instanceof ServletRequestListener) {
                            ((ServletRequestListener) l).requestInitialized(event);
                        }
                    } catch (Throwable t) {
                        if (LOGGER.isLoggable(Level.WARNING)) {
                            LOGGER.log(Level.WARNING,
                                    LogMessages.WARNING_GRIZZLY_HTTP_SERVLET_CONTAINER_OBJECT_INITIALIZED_ERROR("requestInitialized", "ServletRequestListener", l.getClass().getName()),
                                    t);
                        }
                    }
                }
                pos = 0;
                doFilter(request, response);
            } finally {
                for (EventListener l : ((ServletContextImpl) configImpl.getServletContext()).getListeners()) {
                    try {
                        if (l instanceof ServletRequestListener) {
                            ((ServletRequestListener) l).requestDestroyed(event);
                        }
                    } catch (Throwable t) {
                        if (LOGGER.isLoggable(Level.WARNING)) {
                            LOGGER.log(Level.WARNING,
                                    LogMessages.WARNING_GRIZZLY_HTTP_SERVLET_CONTAINER_OBJECT_DESTROYED_ERROR("requestDestroyed", "ServletRequestListener", l.getClass().getName()),
                                    t);
                        }
                    }
                }
            }

        }

        /**
         * Invoke the next filter in this chain, passing the specified request
         * and response.  If there are no more filters in this chain, invoke
         * the <code>service()</code> method of the servlet itself.
         *
         * @param request The servlet request we are processing
         * @param response The servlet response we are creating
         *
         * @exception java.io.IOException if an input/output error occurs
         * @exception javax.servlet.ServletException if a servlet exception occurs
         */
        @Override
        public void doFilter(ServletRequest request, ServletResponse response)
                throws IOException, ServletException {

            // Call the next filter if there is one
            if (pos < n) {

                FilterConfigImpl filterConfig;

                synchronized (lock) {
                    filterConfig = filters[pos++];
                }

                try {
                    Filter filter = filterConfig.getFilter();
                    filter.doFilter(request, response, this);
                } catch (IOException e) {
                    throw e;
                } catch (ServletException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw e;
                } catch (Throwable e) {
                    throw new ServletException("Throwable", e);
                }

                return;
            }

            try {
                if (servlet != null) {
                    servlet.service(request, response);
                }

            } catch (IOException e) {
                throw e;
            } catch (ServletException e) {
                throw e;
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new ServletException("Throwable", e);
            }

        }

        // -------------------------------------------------------- Package Methods
        protected FilterConfigImpl getFilter(int i) {
            return filters[i];
        }

        protected Servlet getServlet() {
            return servlet;
        }

        protected ServletConfigImpl getServletConfig() {
            return configImpl;
        }
    }

    /**
     * AfterServiceListener, which is responsible for recycle servlet request and response
     * objects.
     */
    static final class ServletAfterServiceListener implements AfterServiceListener {

        @Override
        public void onAfterService(final Request request) {
            final HttpServletRequestImpl servletRequest = request.getNote(SERVLET_REQUEST_NOTE);
            final HttpServletResponseImpl servletResponse = request.getNote(SERVLET_RESPONSE_NOTE);

            if (servletRequest != null) {
                servletRequest.recycle();
                servletResponse.recycle();
            }
        }
    }
}
