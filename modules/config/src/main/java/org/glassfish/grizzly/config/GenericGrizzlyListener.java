/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2007-2011 Oracle and/or its affiliates. All rights reserved.
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
package org.glassfish.grizzly.config;

import java.beans.PropertyChangeEvent;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.grizzly.IOStrategy;

import org.glassfish.grizzly.SocketBinder;
import org.glassfish.grizzly.config.dom.Http;
import org.glassfish.grizzly.config.dom.NetworkListener;
import org.glassfish.grizzly.config.dom.PortUnification;
import org.glassfish.grizzly.config.dom.Protocol;
import org.glassfish.grizzly.config.dom.ProtocolChain;
import org.glassfish.grizzly.config.dom.ProtocolChainInstanceHandler;
import org.glassfish.grizzly.config.dom.ProtocolFilter;
import org.glassfish.grizzly.config.dom.Ssl;
import org.glassfish.grizzly.config.dom.ThreadPool;
import org.glassfish.grizzly.config.dom.Transport;
import org.glassfish.grizzly.config.portunif.HttpRedirectFilter;
import org.glassfish.grizzly.filterchain.Filter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.ContentEncoding;
import org.glassfish.grizzly.http.EncodingFilter;
import org.glassfish.grizzly.http.GZipContentEncoding;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.KeepAlive;
import org.glassfish.grizzly.http.LZMAContentEncoding;
import org.glassfish.grizzly.http.server.AddOn;
import org.glassfish.grizzly.http.server.FileCacheFilter;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServerFilter;
import org.glassfish.grizzly.http.server.ServerFilterConfiguration;
import org.glassfish.grizzly.http.server.StaticHttpHandler;
import org.glassfish.grizzly.http.server.filecache.FileCache;
import org.glassfish.grizzly.http.util.DataChunk;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.nio.NIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.nio.transport.UDPNIOTransport;
import org.glassfish.grizzly.nio.transport.UDPNIOTransportBuilder;
import org.glassfish.grizzly.portunif.PUFilter;
import org.glassfish.grizzly.portunif.PUProtocol;
import org.glassfish.grizzly.portunif.ProtocolFinder;
import org.glassfish.grizzly.portunif.finders.SSLProtocolFinder;
import org.glassfish.grizzly.rcm.ResourceAllocationFilter;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.ssl.SSLFilter;
import org.glassfish.grizzly.strategies.WorkerThreadIOStrategy;
import org.glassfish.grizzly.threadpool.DefaultWorkerThread;
import org.glassfish.grizzly.threadpool.GrizzlyExecutorService;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.grizzly.utils.DelayedExecutor;
import org.glassfish.grizzly.utils.IdleTimeoutFilter;
import org.jvnet.hk2.component.Habitat;
import org.jvnet.hk2.config.ConfigBeanProxy;

/**
 * Generic {@link GrizzlyListener} implementation, which is not HTTP dependent, and can support any Transport
 * configuration, based on {@link FilterChain}.
 *
 * @author Alexey Stashok
 */
public class GenericGrizzlyListener implements GrizzlyListener {
    /**
     * The logger to use for logging messages.
     */
    static final Logger logger = Logger.getLogger(GrizzlyListener.class.getName());
    protected volatile String name;
    protected volatile InetAddress address;
    protected volatile int port;
    protected NIOTransport transport;
    protected FilterChain rootFilterChain;
    private volatile ExecutorService auxExecutorService;
    private volatile DelayedExecutor delayedExecutor;
    private volatile long transactionTimeoutMillis = -1;

    @Override
    public String getName() {
        return name;
    }

    protected final void setName(String name) {
        this.name = name;
    }

    @Override
    public InetAddress getAddress() {
        return address;
    }

    protected final void setAddress(InetAddress inetAddress) {
        address = inetAddress;
    }

    @Override
    public int getPort() {
        return port;
    }

    protected void setPort(int port) {
        this.port = port;
    }

    @Override
    public void start() throws IOException {
        delayedExecutor.start();
        ((SocketBinder) transport).bind(new InetSocketAddress(address, port));
        transport.start();
    }

    @Override
    public void stop() throws IOException {
        stopDelayedExecutor();
        transport.stop();
        transport = null;
        rootFilterChain = null;
    }

    @Override
    public void destroy() {
    }

    @Override
    public void processDynamicConfigurationChange(Habitat habitat,
        PropertyChangeEvent[] events) {
    }

    @Override
    public <T> T getAdapter(Class<T> adapterClass) {
        return null;
    }

    public <E> List<E> getFilters(Class<E> clazz) {
        return getFilters(clazz, rootFilterChain, new ArrayList<E>(2));
    }

    public org.glassfish.grizzly.Transport getTransport() {
        return transport;
    }
    
    @SuppressWarnings({"unchecked"})
    public static <E> List<E> getFilters(Class<E> clazz,
        FilterChain filterChain, List<E> filters) {
        for (final Filter filter : filterChain) {
            if (clazz.isAssignableFrom(filter.getClass())) {
                filters.add((E) filter);
            }
            if (PUFilter.class.isAssignableFrom(filter.getClass())) {
                final Set<PUProtocol> puProtocols = ((PUFilter) filter).getProtocols();
                for (PUProtocol puProtocol : puProtocols) {
                    getFilters(clazz, puProtocol.getFilterChain(), filters);
                }
            }
        }
        return filters;
    }

    /*
     * Configures the given grizzlyListener.
     *
     * @param networkListener The NetworkListener to configure
     */
    // TODO: Must get the information from domain.xml Config objects.
    // TODO: Pending Grizzly issue 54
    @Override
    public void configure(final Habitat habitat,
        final NetworkListener networkListener) throws IOException {
        setName(networkListener.getName());
        setAddress(InetAddress.getByName(networkListener.getAddress()));
        setPort(Integer.parseInt(networkListener.getPort()));
        configureDelayedExecutor(habitat);

        final FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        
        configureTransport(habitat, networkListener,
                networkListener.findTransport(), filterChainBuilder);

        configureProtocol(habitat, networkListener,
                networkListener.findProtocol(), filterChainBuilder);

        configureThreadPool(habitat, networkListener,
                networkListener.findThreadPool());

        rootFilterChain = filterChainBuilder.build();
        transport.setProcessor(rootFilterChain);
    }

    protected void configureTransport(final Habitat habitat,
            final NetworkListener networkListener,
            final Transport transportConfig,
            final FilterChainBuilder filterChainBuilder) {
        
        final String transportClassName = transportConfig.getClassname();
        if (TCPNIOTransport.class.getName().equals(transportClassName)) {
            transport = configureTCPTransport(habitat, networkListener, transportConfig);
        } else if (UDPNIOTransport.class.getName().equals(transportClassName)) {
            transport = configureUDPTransport(habitat, networkListener, transportConfig);
        } else {
            throw new GrizzlyConfigException("Unsupported transport type " + transportConfig.getName());
        }
        transport.setSelectorRunnersCount(Integer.parseInt(transportConfig.getAcceptorThreads()));
        transport.setReadBufferSize(Integer.parseInt(transportConfig.getBufferSizeBytes()));
        transport.getKernelThreadPoolConfig().setPoolName(networkListener.getName() + "-kernel");
        transport.setIOStrategy(loadIOStrategy(transportConfig.getIOStrategy()));
        
        filterChainBuilder.add(new TransportFilter());
    }

    protected NIOTransport configureTCPTransport(final Habitat habitat,
            final NetworkListener networkListener,
            final Transport transportConfig) {
        
        final TCPNIOTransport tcpTransport = TCPNIOTransportBuilder.newInstance().build();
        tcpTransport.setTcpNoDelay(Boolean.parseBoolean(transportConfig.getTcpNoDelay()));
        tcpTransport.setLinger(Integer.parseInt(transportConfig.getLinger()));
        tcpTransport.setServerConnectionBackLog(Integer.parseInt(transportConfig.getMaxConnectionsCount()));
        return tcpTransport;
    }

    protected NIOTransport configureUDPTransport(final Habitat habitat,
            final NetworkListener networkListener,
            final Transport transportConfig) {
        return UDPNIOTransportBuilder.newInstance().build();
    }

    protected void configureProtocol(final Habitat habitat,
            final NetworkListener networkListener,
            final Protocol protocol,
            final FilterChainBuilder filterChainBuilder) {
        
        if (Boolean.valueOf(protocol.getSecurityEnabled())) {
            configureSsl(habitat, networkListener, protocol.getSsl(),
                    filterChainBuilder);
        }
        configureSubProtocol(habitat, networkListener, protocol,
                filterChainBuilder);
    }

    protected void configureSubProtocol(final Habitat habitat,
            final NetworkListener networkListener,
            final Protocol protocol,
            final FilterChainBuilder filterChainBuilder) {
        
        if (protocol.getHttp() != null) {
            final Http http = protocol.getHttp();
            configureHttpProtocol(habitat, networkListener, http, filterChainBuilder);

        } else if (protocol.getPortUnification() != null) {
            // Port unification
            final PortUnification pu = protocol.getPortUnification();
            final String puFilterClassname = pu.getClassname();
            PUFilter puFilter = null;
            if (puFilterClassname != null) {
                try {
                    puFilter = Utils.newInstance(habitat,
                        PUFilter.class, puFilterClassname, puFilterClassname);
                    configureElement(habitat, networkListener, pu, puFilter);
                } catch (Exception e) {
                    logger.log(Level.WARNING,
                        "Can not initialize port unification filter: "
                            + puFilterClassname + " default filter will be used instead", e);
                }
            }
            if (puFilter == null) {
                puFilter = new PUFilter();
            }
            List<org.glassfish.grizzly.config.dom.ProtocolFinder> findersConfig = pu.getProtocolFinder();
            for (org.glassfish.grizzly.config.dom.ProtocolFinder finderConfig : findersConfig) {
                final String finderClassname = finderConfig.getClassname();
                try {
                    final ProtocolFinder protocolFinder = Utils.newInstance(habitat,
                        ProtocolFinder.class, finderClassname, finderClassname);
                    configureElement(habitat, networkListener, finderConfig, protocolFinder);
                    final Protocol subProtocol = finderConfig.findProtocol();
                    final FilterChainBuilder subProtocolFilterChainBuilder =
                        puFilter.getPUFilterChainBuilder();
                    // If subprotocol is secured - we need to wrap it under SSLProtocolFinder
                    if (Boolean.valueOf(subProtocol.getSecurityEnabled())) {
                        final PUFilter extraSslPUFilter = new PUFilter();
                        configureSsl(habitat, networkListener,
                                subProtocol.getSsl(), subProtocolFilterChainBuilder);
                        
                        subProtocolFilterChainBuilder.add(extraSslPUFilter);
                        final FilterChainBuilder extraSslPUFilterChainBuilder =
                            extraSslPUFilter.getPUFilterChainBuilder();
                        configureSubProtocol(habitat, networkListener,
                                subProtocol, extraSslPUFilterChainBuilder);
                        extraSslPUFilter.register(protocolFinder,
                                extraSslPUFilterChainBuilder.build());
                        
                        puFilter.register(new SSLProtocolFinder(
                            new SSLConfigurator(habitat, subProtocol.getSsl())),
                            subProtocolFilterChainBuilder.build());
                    } else {
                        configureSubProtocol(habitat, networkListener,
                                subProtocol, subProtocolFilterChainBuilder);
                        puFilter.register(protocolFinder, subProtocolFilterChainBuilder.build());
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Can not initialize sub protocol. Finder: "
                        + finderClassname, e);
                }
            }
            filterChainBuilder.add(puFilter);
        } else if (protocol.getHttpRedirect() != null) {
            filterChainBuilder.add(new org.glassfish.grizzly.http.HttpServerFilter());
            final HttpRedirectFilter filter = new HttpRedirectFilter();
            filter.configure(habitat, networkListener, protocol.getHttpRedirect());
            filterChainBuilder.add(filter);
        } else {
            ProtocolChainInstanceHandler pcihConfig = protocol.getProtocolChainInstanceHandler();
            if (pcihConfig == null) {
                logger.log(Level.WARNING, "Empty protocol declaration");
                return;
            }
            ProtocolChain filterChainConfig = pcihConfig.getProtocolChain();
            for (ProtocolFilter filterConfig : filterChainConfig.getProtocolFilter()) {
                final String filterClassname = filterConfig.getClassname();
                try {
                    final Filter filter = (Filter) Utils.newInstance(filterClassname);
                    configureElement(habitat, networkListener, filterConfig, filter);
                    filterChainBuilder.add(filter);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Can not initialize protocol filter: "
                        + filterClassname, e);
                    throw new IllegalStateException("Can not initialize protocol filter: "
                        + filterClassname);
                }
            }
        }
    }

    protected static void configureSsl(final Habitat habitat,
            final NetworkListener networkListener, final Ssl ssl,
            final FilterChainBuilder filterChainBuilder) {
        final SSLEngineConfigurator serverConfig = new SSLConfigurator(habitat, ssl);
        final SSLEngineConfigurator clientConfig = new SSLConfigurator(habitat, ssl);
        clientConfig.setClientMode(true);

        filterChainBuilder.add(new SSLFilter(serverConfig,
                                             clientConfig,
                                             isRenegotiateOnClientAuthWant(ssl)));
    }

    private static boolean isRenegotiateOnClientAuthWant(final Ssl ssl) {
        return ssl == null || Boolean.parseBoolean(ssl.getRenegotiateOnClientAuthWant());
    }

    @SuppressWarnings({"unchecked"})
    private static void configureElement(Habitat habitat,
            NetworkListener networkListener, ConfigBeanProxy configuration,
            Object instance) {
        
        if (instance instanceof ConfigAwareElement) {
            ((ConfigAwareElement) instance).configure(habitat, networkListener,
                    configuration);
        }
    }

    protected void configureThreadPool(final Habitat habitat,
            final NetworkListener networkListener,
            final ThreadPool threadPool) {
        try {
            transport.setWorkerThreadPool(GrizzlyExecutorService.createInstance(
                configureThreadPoolConfig(habitat, networkListener, threadPool)));
        } catch (NumberFormatException ex) {
            logger.log(Level.WARNING, " Invalid thread-pool attribute", ex);
        }
    }

    protected ThreadPoolConfig configureThreadPoolConfig(final Habitat habitat,
            final NetworkListener networkListener,
            final ThreadPool threadPool) {
//            Http http = listener.findHttpProtocol().getHttp();
//            int keepAlive = http == null ? 0 : Integer.parseInt(http.getTimeoutSeconds());
        final int maxQueueSize = threadPool.getMaxQueueSize() == null ? Integer.MAX_VALUE
            : Integer.parseInt(threadPool.getMaxQueueSize());
        final int minThreads = Integer.parseInt(threadPool.getMinThreadPoolSize());
        final int maxThreads = Integer.parseInt(threadPool.getMaxThreadPoolSize());
        final int timeout = Integer.parseInt(threadPool.getIdleThreadTimeoutSeconds());
        final ThreadPoolConfig poolConfig = ThreadPoolConfig.defaultConfig();
        poolConfig.setPoolName(networkListener.getName());
        poolConfig.setCorePoolSize(minThreads);
        poolConfig.setMaxPoolSize(maxThreads);
        poolConfig.setQueueLimit(maxQueueSize);
        poolConfig.setKeepAliveTime(timeout < 0 ? Long.MAX_VALUE : timeout, TimeUnit.SECONDS);
        if (transactionTimeoutMillis > 0 && !Utils.isDebugVM()) {
            poolConfig.setTransactionTimeout(delayedExecutor,
                    transactionTimeoutMillis, TimeUnit.MILLISECONDS);
        }
        
//            List<String> l = ManagementFactory.getRuntimeMXBean().getInputArguments();
//            boolean debugMode = false;
//            for (String s : l) {
//                if (s.trim().startsWith("-Xrunjdwp:")) {
//                    debugMode = true;
//                    break;
//                }
//            }
//            if (!debugMode && timeout > 0) {
//                // Idle Threads cannot be alive more than 15 minutes by default
//                httpListener.setTransactionTimeout(timeout * 1000);
//            } else {
//                // Disable the mechanism
//                httpListener.setTransactionTimeout(-1);
//            }
        return poolConfig;
    }

    protected void configureDelayedExecutor(Habitat habitat) {
        final AtomicInteger threadCounter = new AtomicInteger();
        auxExecutorService = Executors.newCachedThreadPool(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    final Thread newThread = new DefaultWorkerThread(
                        transport.getAttributeBuilder(),
                        getName() + '-' + threadCounter.getAndIncrement(),
                        null,
                        r);
                    newThread.setDaemon(true);
                    return newThread;
                }
            });
        delayedExecutor = new DelayedExecutor(auxExecutorService);
    }

    protected void stopDelayedExecutor() {
        final DelayedExecutor localDelayedExecutor = delayedExecutor;
        delayedExecutor = null;
        if (localDelayedExecutor != null) {
            localDelayedExecutor.stop();
        }
        final ExecutorService localThreadPool = auxExecutorService;
        auxExecutorService = null;
        if (localThreadPool != null) {
            localThreadPool.shutdownNow();
        }
    }

    @SuppressWarnings({"deprecation"})
    protected void configureHttpProtocol(final Habitat habitat,
            final NetworkListener networkListener,
            final Http http, final FilterChainBuilder filterChainBuilder) {
        transactionTimeoutMillis = Integer.parseInt(http.getRequestTimeoutSeconds()) * 1000;
        filterChainBuilder.add(new IdleTimeoutFilter(delayedExecutor, Integer.parseInt(http.getTimeoutSeconds()),
            TimeUnit.SECONDS));
        final org.glassfish.grizzly.http.HttpServerFilter httpServerFilter =
            new org.glassfish.grizzly.http.HttpServerFilter(
                Boolean.parseBoolean(http.getChunkingEnabled()),
                Integer.parseInt(http.getHeaderBufferLengthBytes()),
                http.getForcedResponseType(),
                configureKeepAlive(habitat, http),
                delayedExecutor);
        final Set<ContentEncoding> contentEncodings =
            configureContentEncodings(habitat, http);
        for (ContentEncoding contentEncoding : contentEncodings) {
            httpServerFilter.addContentEncoding(contentEncoding);
        }
        //httpServerFilter.addContentEncoding(new GZipContentEncoding());
        final boolean isRcmSupportEnabled = Boolean.parseBoolean(http.getRcmSupportEnabled());
        if (isRcmSupportEnabled) {
            filterChainBuilder.add(new ResourceAllocationFilter());
        }
//        httpServerFilter.getMonitoringConfig().addProbes(
//                serverConfig.getMonitoringConfig().getHttpConfig().getProbes());
        filterChainBuilder.add(httpServerFilter);
        final FileCache fileCache = configureHttpFileCache(habitat, http.getFileCache());
        fileCache.initialize(transport.getMemoryManager(), delayedExecutor);
        final FileCacheFilter fileCacheFilter = new FileCacheFilter(fileCache);
//        fileCache.getMonitoringConfig().addProbes(
//                serverConfig.getMonitoringConfig().getFileCacheConfig().getProbes());
        filterChainBuilder.add(fileCacheFilter);
        final HttpServerFilter webServerFilter = new HttpServerFilter(getHttpServerFilterConfiguration(http),
            delayedExecutor);

        final HttpHandler httpHandler = getHttpHandler(http);
        httpHandler.setAllowEncodedSlash(GrizzlyConfig.toBoolean(http.getEncodedSlashEnabled()));
        webServerFilter.setHttpHandler(httpHandler);
//        webServerFilter.getMonitoringConfig().addProbes(
//                serverConfig.getMonitoringConfig().getWebServerConfig().getProbes());
        filterChainBuilder.add(webServerFilter);

        configureCometSupport(habitat, networkListener, http, filterChainBuilder);

        configureWebSocketSupport(habitat, networkListener, http, filterChainBuilder);

        configureAjpSupport(habitat, networkListener, http, filterChainBuilder);
    }

    protected void configureCometSupport(final Habitat habitat,
            final NetworkListener networkListener,
            final Http http, final FilterChainBuilder filterChainBuilder) {

        if(GrizzlyConfig.toBoolean(http.getCometSupportEnabled())) {
            final AddOn cometAddOn = loadAddOn(habitat, "comet",
                    "org.glassfish.grizzly.comet.CometAddOn");
            if (cometAddOn != null) {
                configureElement(habitat, networkListener, http, cometAddOn);
                cometAddOn.setup(null, filterChainBuilder);
            }
        }
    }

    protected void configureWebSocketSupport(final Habitat habitat,
            final NetworkListener networkListener,
            final Http http, final FilterChainBuilder filterChainBuilder) {
        final boolean websocketsSupportEnabled = Boolean.parseBoolean(http.getWebsocketsSupportEnabled());
        if (websocketsSupportEnabled) {
            final AddOn websocketAddOn = loadAddOn(habitat, "websocket",
                    "org.glassfish.grizzly.websockets.WebSocketAddOn");
            if (websocketAddOn != null) {
                configureElement(habitat, networkListener, http, websocketAddOn);
                websocketAddOn.setup(null, filterChainBuilder);
            }
        }
    }

    protected void configureAjpSupport(final Habitat habitat,
            final NetworkListener networkListener,
            final Http http, final FilterChainBuilder filterChainBuilder) {

        final boolean jkSupportEnabled = http.getJkEnabled() != null ?
            Boolean.parseBoolean(http.getJkEnabled()) :
            Boolean.parseBoolean(networkListener.getJkEnabled());

        if (jkSupportEnabled) {
            final AddOn ajpAddOn = loadAddOn(habitat, "ajp",
                    "org.glassfish.grizzly.http.ajp.AjpAddOn");
            if (ajpAddOn != null) {
                configureElement(habitat, networkListener, http, ajpAddOn);
                ajpAddOn.setup(null, filterChainBuilder);
            }
        }
    }

    
    /**
     * Load {@link AddOn} with the specific service name and classname.
     */
    private AddOn loadAddOn(Habitat habitat, String name, String addOnClassName) {
        return Utils.newInstance(habitat, AddOn.class, name, addOnClassName);
    }

    /**
     * Load {@link Filter} with the specific service name and classname.
     */
    private Filter loadFilter(Habitat habitat, String name, String filterClassName) {
        return Utils.newInstance(habitat, Filter.class, name, filterClassName);
    }

    protected ServerFilterConfiguration getHttpServerFilterConfiguration(Http http) {
        return new ServerFilterConfiguration();
    }

    protected HttpHandler getHttpHandler(Http http) {
        return new StaticHttpHandler(".");
    }

    /**
     * Configure the Grizzly HTTP FileCache mechanism
     */
    protected FileCache configureHttpFileCache(Habitat habitat,
        org.glassfish.grizzly.config.dom.FileCache cache) {
        final FileCache fileCache = new FileCache();
        if (cache != null) {
            fileCache.setEnabled(GrizzlyConfig.toBoolean(cache.getEnabled()));
            fileCache.setSecondsMaxAge(Integer.parseInt(cache.getMaxAgeSeconds()));
            fileCache.setMaxCacheEntries(Integer.parseInt(cache.getMaxFilesCount()));
            fileCache.setMaxLargeFileCacheSize(Integer.parseInt(cache.getMaxCacheSizeBytes()));
        } else {
            fileCache.setEnabled(false);
        }
        return fileCache;
    }

    protected KeepAlive configureKeepAlive(Habitat habitat, Http http) {
        int timeoutInSeconds = 60;
        int maxConnections = 256;
        if (http != null) {
            try {
                timeoutInSeconds = Integer.parseInt(http.getTimeoutSeconds());
            } catch (NumberFormatException ex) {
//                String msg = _rb.getString("pewebcontainer.invalidKeepAliveTimeout");
                String msg = "pewebcontainer.invalidKeepAliveTimeout";
                msg = MessageFormat.format(msg, http.getTimeoutSeconds(), Integer.toString(timeoutInSeconds));
                logger.log(Level.WARNING, msg, ex);
            }
            try {
                maxConnections = Integer.parseInt(http.getMaxConnections());
            } catch (NumberFormatException ex) {
//                String msg = _rb.getString("pewebcontainer.invalidKeepAliveMaxConnections");
                String msg = "pewebcontainer.invalidKeepAliveMaxConnections";
                msg = MessageFormat.format(msg, http.getMaxConnections(), Integer.toString(maxConnections));
                logger.log(Level.WARNING, msg, ex);
            }
        }
        final KeepAlive keepAlive = new KeepAlive();
        keepAlive.setIdleTimeoutInSeconds(timeoutInSeconds);
        keepAlive.setMaxRequestsCount(maxConnections);
        return keepAlive;
    }

    protected Set<ContentEncoding> configureContentEncodings(
        Habitat habitat, Http http) {
        return configureCompressionEncodings(habitat, http);
    }

    protected Set<ContentEncoding> configureCompressionEncodings(Habitat habitat, Http http) {
        final String mode = http.getCompression();
        int compressionMinSize = Integer.parseInt(http.getCompressionMinSizeBytes());
        COMPRESSION_LEVEL compressionLevel;
        try {
            compressionLevel = getCompressionLevel(mode);
        } catch (IllegalArgumentException e) {
            try {
                // Try to parse compression as an int, which would give the
                // minimum compression size
                compressionLevel = COMPRESSION_LEVEL.ON;
                compressionMinSize = Integer.parseInt(mode);
            } catch (Exception ignore) {
                compressionLevel = COMPRESSION_LEVEL.OFF;
            }
        }
        final String compressableMimeTypesString = http.getCompressableMimeType();
        final String noCompressionUserAgentsString = http.getNoCompressionUserAgents();
        final String[] compressableMimeTypes = split(compressableMimeTypesString, ",");
        final String[] noCompressionUserAgents = split(noCompressionUserAgentsString, ",");
        final ContentEncoding gzipContentEncoding = new GZipContentEncoding(
            GZipContentEncoding.DEFAULT_IN_BUFFER_SIZE,
            GZipContentEncoding.DEFAULT_OUT_BUFFER_SIZE,
            new CompressionEncodingFilter(compressionLevel, compressionMinSize,
                compressableMimeTypes,
                noCompressionUserAgents,
                GZipContentEncoding.ALIASES));
        final ContentEncoding lzmaEncoding = new LZMAContentEncoding(new CompressionEncodingFilter(compressionLevel, compressionMinSize,
                compressableMimeTypes,
                noCompressionUserAgents,
                LZMAContentEncoding.ALIASES));
        final Set<ContentEncoding> set = new HashSet<ContentEncoding>(2);
        set.add(gzipContentEncoding);
        set.add(lzmaEncoding);
        return set;
    }

    @SuppressWarnings("unchecked")
    private static IOStrategy loadIOStrategy(final String classname) {
        Class<? extends IOStrategy> strategy;
        if (classname == null) {
            strategy = WorkerThreadIOStrategy.class;
        } else {
            try {
                strategy = Utils.loadClass(classname);
            } catch (Exception e) {
                strategy = WorkerThreadIOStrategy.class;
            }
        }
        
        try {
            final Method m = strategy.getMethod("getInstance");
            return (IOStrategy) m.invoke(null);
        } catch (Exception e) {
            throw new IllegalStateException("Can not initialize IOStrategy: " + strategy + ". Error: " + e);
        }
    }

    private String[] split(String s, String delim) {
        if (s == null) {
            return new String[0];
        }
        final List<String> compressableMimeTypeList = new ArrayList<String>(4);
        final StringTokenizer st = new StringTokenizer(s, delim);
        while (st.hasMoreTokens()) {
            compressableMimeTypeList.add(st.nextToken().trim());
        }
        final String[] splitStrings = new String[compressableMimeTypeList.size()];
        compressableMimeTypeList.toArray(splitStrings);
        return splitStrings;
    }

    public enum COMPRESSION_LEVEL {OFF, ON, FORCE}

    /**
     * Set compression level.
     */
    protected final COMPRESSION_LEVEL getCompressionLevel(String compression) {
        if ("on".equalsIgnoreCase(compression)) {
            return COMPRESSION_LEVEL.ON;
        } else if ("force".equalsIgnoreCase(compression)) {
            return COMPRESSION_LEVEL.FORCE;
        } else if ("off".equalsIgnoreCase(compression)) {
            return COMPRESSION_LEVEL.OFF;
        }
        throw new IllegalArgumentException();
    }

    public static class CompressionEncodingFilter implements EncodingFilter {
        private final COMPRESSION_LEVEL compressionLevel;
        private final int compressionMinSize;
        private final String[] compressableMimeTypes;
        private final String[] noCompressionUserAgents;
        private final String[] aliases;

        public CompressionEncodingFilter(COMPRESSION_LEVEL compressionLevel,
            int compressionMinSize,
            String[] compressableMimeTypes,
            String[] noCompressionUserAgents,
            String[] aliases) {
            this.compressionLevel = compressionLevel;
            this.compressionMinSize = compressionMinSize;
            this.compressableMimeTypes = compressableMimeTypes;
            this.noCompressionUserAgents = noCompressionUserAgents;
            this.aliases = aliases;
        }

        @Override
        public boolean applyEncoding(HttpHeader httpPacket) {
            switch (compressionLevel) {
                case OFF:
                    return false;
                default:
                    // Compress only since HTTP 1.1
                    if (org.glassfish.grizzly.http.Protocol.HTTP_1_1 != httpPacket.getProtocol()) {
                        return false;
                    }
                    final HttpResponsePacket responsePacket = (HttpResponsePacket) httpPacket;
                    final MimeHeaders requestHeaders = responsePacket.getRequest().getHeaders();
                    // Check if browser support gzip encoding
                    final DataChunk acceptEncodingDC =
                        requestHeaders.getValue("accept-encoding");
                    if (acceptEncodingDC == null || indexOf(aliases, acceptEncodingDC) == -1) {
                        return false;
                    }
                    final MimeHeaders responseHeaders = responsePacket.getHeaders();
                    // Check if content is not already gzipped
                    final DataChunk contentEncodingMB =
                        responseHeaders.getValue("Content-Encoding");
                    if (contentEncodingMB != null
                        && indexOf(aliases, contentEncodingMB) != -1) {
                        return false;
                    }
                    // If force mode, always compress (test purposes only)
                    if (compressionLevel == COMPRESSION_LEVEL.FORCE) {
                        return true;
                    }
                    // Check for incompatible Browser
                    if (noCompressionUserAgents.length > 0) {
                        final DataChunk userAgentValueDC =
                            requestHeaders.getValue("user-agent");
                        if (userAgentValueDC != null &&
                             indexOf(noCompressionUserAgents, userAgentValueDC) != -1) {
                            return false;
                        }
                    }
                    // Check if sufficient len to trig the compression
                    final long contentLength = responsePacket.getContentLength();
                    if (contentLength == -1
                        || contentLength > compressionMinSize) {
                        // Check for compatible MIME-TYPE
                        if (compressableMimeTypes.length > 0) {
                            return indexOfStartsWith(compressableMimeTypes,
                                responsePacket.getContentType()) != -1;
                        }
                    }
                    return true;
            }
        }

        @Override
        public boolean applyDecoding(HttpHeader httpPacket) {
            return false;
        }
    }

    private static int indexOf(String[] aliases, DataChunk dc) {
        for (int i = 0; i < aliases.length; i++) {
            final String alias = aliases[i];
            if (dc.indexOf(alias, 0) != -1) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfStartsWith(String[] aliases, String s) {
        for (int i = 0; i < aliases.length; i++) {
            final String alias = aliases[i];
            if (s.startsWith(alias)) {
                return i;
            }
        }
        return -1;
    }
}
