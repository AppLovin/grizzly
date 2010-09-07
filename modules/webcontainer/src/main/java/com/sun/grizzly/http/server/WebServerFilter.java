/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.grizzly.http.server;

import com.sun.grizzly.Buffer;
import com.sun.grizzly.Connection;
import com.sun.grizzly.EmptyCompletionHandler;
import com.sun.grizzly.Grizzly;
import com.sun.grizzly.WriteResult;
import com.sun.grizzly.attributes.Attribute;
import com.sun.grizzly.filterchain.BaseFilter;
import com.sun.grizzly.filterchain.FilterChainContext;
import com.sun.grizzly.filterchain.NextAction;
import com.sun.grizzly.http.HttpContent;
import com.sun.grizzly.http.HttpPacket;
import com.sun.grizzly.http.HttpRequestPacket;
import com.sun.grizzly.http.HttpResponsePacket;
import com.sun.grizzly.http.ProcessingState;
import com.sun.grizzly.http.server.io.ReadHandler;
import com.sun.grizzly.http.server.util.HtmlHelper;
import com.sun.grizzly.http.util.HttpStatus;
import com.sun.grizzly.memory.MemoryManager;
import com.sun.grizzly.memory.MemoryUtils;
import com.sun.grizzly.monitoring.jmx.AbstractJmxMonitoringConfig;
import com.sun.grizzly.monitoring.jmx.JmxMonitoringAware;
import com.sun.grizzly.monitoring.jmx.JmxMonitoringConfig;
import com.sun.grizzly.monitoring.jmx.JmxObject;
import com.sun.grizzly.utils.DelayedExecutor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * TODO:
 *   JMX
 *   Statistics
 */
public class WebServerFilter extends BaseFilter
        implements JmxMonitoringAware<WebServerProbe> {

    private static final FlushAndCloseHandler FLUSH_AND_CLOSE_HANDLER =
            new FlushAndCloseHandler();
    
    private final Attribute<WebServerContext> webServerContextAttr;

    private final DelayedExecutor delayedExecutor;
    private final DelayedExecutor.DelayQueue<GrizzlyResponse> suspendedResponseQueue;
    private final DelayedExecutor.DelayQueue<WebServerContext> keepAliveQueue;
    
    private final GrizzlyWebServer gws;
    private final GrizzlyListener listener;

    /**
     * Web server probes
     */
    protected final AbstractJmxMonitoringConfig<WebServerProbe> monitoringConfig =
            new AbstractJmxMonitoringConfig<WebServerProbe>(WebServerProbe.class) {

                @Override
                public JmxObject createManagementObject() {
                    return createJmxManagementObject();
                }

            };


    // ------------------------------------------------------------ Constructors


    public WebServerFilter(final GrizzlyWebServer webServer,
            final GrizzlyListener listener) {
        gws = webServer;
        this.listener = listener;
        delayedExecutor = webServer.getDelayedExecutor();
        suspendedResponseQueue = GrizzlyResponse.createDelayQueue(delayedExecutor);
        keepAliveQueue = delayedExecutor.createDelayQueue(new KeepAliveWorker(),
                new KeepAliveResolver());
        
        this.webServerContextAttr =
                Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(
                WebServerFilter.class.getName() + ".context");
    }


    // ----------------------------------------------------- Methods from Filter


    @Override
    public NextAction handleRead(FilterChainContext ctx)
          throws IOException {
        final Object message = ctx.getMessage();
        final Connection connection = ctx.getConnection();

        final WebServerContext wsContext = obtainWebServerContext(connection);

        if (message instanceof HttpPacket) {
            // Otherwise cast message to a HttpContent

            final HttpContent httpContent = (HttpContent) message;

            GrizzlyRequest grizzlyRequest = wsContext.associatedRequest;

            if (grizzlyRequest == null) {
                // It's a new HTTP request
                HttpRequestPacket request = (HttpRequestPacket) httpContent.getHttpHeader();
                HttpResponsePacket response = request.getResponse();
                grizzlyRequest = GrizzlyRequest.create();
                grizzlyRequest.initialize(request, httpContent, ctx, this);
                final GrizzlyResponse grizzlyResponse = GrizzlyResponse.create();
                final SuspendStatus suspendStatus = new SuspendStatus();

                grizzlyResponse.initialize(grizzlyRequest, response, ctx,
                        suspendedResponseQueue, suspendStatus);
                wsContext.associatedRequest = grizzlyRequest;

                WebServerProbeNotifier.notifyRequestReceive(this, connection,
                        grizzlyRequest);

                try {
                    ctx.setMessage(grizzlyResponse);
                    beforeService(connection, grizzlyRequest,
                            grizzlyResponse, wsContext);
                    
                    final GrizzlyAdapter adapter = gws.getAdapter();
                    if (adapter != null) {
                        adapter.doService(grizzlyRequest, grizzlyResponse);
                    }
                } catch (Throwable t) {
                    grizzlyRequest.getRequest().getProcessingState().setError(true);
                    
                    if (!response.isCommitted()) {
                        ByteBuffer b = HtmlHelper.getExceptionErrorPage("Internal Server Error", "Grizzly/2.0", t);
                        grizzlyResponse.reset();
                        grizzlyResponse.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                        grizzlyResponse.setContentType("text/html");
                        grizzlyResponse.setCharacterEncoding("UTF-8");
                        MemoryManager mm = ctx.getConnection().getTransport().getMemoryManager();
                        Buffer buf = MemoryUtils.wrap(mm, b);
                        grizzlyResponse.getOutputBuffer().writeBuffer(buf);
                    }
                } finally {
                    if (!suspendStatus.get()) {
                        afterService(connection, grizzlyRequest,
                                grizzlyResponse, wsContext);
                    } else {
                        if (grizzlyRequest.asyncInput()) {
                            return ctx.getSuspendingStopAction();
                        } else {
                            return ctx.getSuspendAction();
                        }
                    }
                }
            } else {
                // We're working with suspended HTTP request
                if (grizzlyRequest.asyncInput()) {
                    if (!grizzlyRequest.getInputBuffer().isFinished()) {

                        final Buffer content = httpContent.getContent();

                        if (!content.hasRemaining() || !grizzlyRequest.getInputBuffer().append(content)) {
                            if (!httpContent.isLast()) {
                                // need more data?
                                return ctx.getStopAction();
                            }

                        }
                        if (httpContent.isLast()) {
                            grizzlyRequest.getInputBuffer().finished();
                            // we have enough data? - terminate filter chain execution
                            final NextAction action = ctx.getSuspendAction();
                            ctx.recycle();
                            return action;
                        }
                    } 
                }
            }
        } else { // this code will be run, when we resume after suspend
            final GrizzlyResponse grizzlyResponse = (GrizzlyResponse) message;
            final GrizzlyRequest grizzlyRequest = grizzlyResponse.getRequest();
            afterService(connection, grizzlyRequest, grizzlyResponse, wsContext);
        }

        return ctx.getStopAction();
    }


    /**
     * Override the default implementation to notify the {@link ReadHandler},
     * if available, of any read error that has occurred during processing.
     * 
     * @param ctx event processing {@link FilterChainContext}
     * @param error error, which occurred during <tt>FilterChain</tt> execution
     */
    @Override
    public void exceptionOccurred(FilterChainContext ctx, Throwable error) {
        final Connection c = ctx.getConnection();
        final WebServerContext wsContext = webServerContextAttr.peek(c);

        if (wsContext != null) {
            final GrizzlyRequest grizzlyRequest =
                    webServerContextAttr.get(c).associatedRequest;

            if (grizzlyRequest != null) {
                ReadHandler handler = grizzlyRequest.getInputBuffer().getReadHandler();
                if (handler != null) {
                    handler.onError(error);
                }
            }
        }
    }


    // ---------------------------------------------------------- Public Methods


    /**
     * {@inheritDoc}
     */
    @Override
    public JmxMonitoringConfig<WebServerProbe> getMonitoringConfig() {
        return monitoringConfig;
    }


    // ------------------------------------------------------- Protected Methods


    protected JmxObject createJmxManagementObject() {
        return new com.sun.grizzly.http.server.jmx.WebServerFilter(this);
    }


    // --------------------------------------------------------- Private Methods

    private void beforeService(final Connection connection,
                              final GrizzlyRequest grizzlyRequest,
                              final GrizzlyResponse grizzlyResponse,
                              final WebServerContext wsContext)
    throws IOException {
        keepAliveQueue.remove(wsContext);
    }

    private void afterService(final Connection connection,
                              final GrizzlyRequest grizzlyRequest,
                              final GrizzlyResponse grizzlyResponse,
                              final WebServerContext wsContext)
    throws IOException {

        wsContext.associatedRequest = null;
        keepAliveQueue.add(wsContext,
                listener.getKeepAlive().getIdleTimeoutInSeconds(),
                TimeUnit.SECONDS);
        
        grizzlyResponse.finish();

        WebServerProbeNotifier.notifyRequestComplete(this, connection,
                grizzlyResponse);

        if (isKeepAlive(grizzlyRequest, grizzlyResponse, wsContext)) {
            final HttpRequestPacket request = grizzlyRequest.getRequest();
            final boolean isExpectContent = request.isExpectContent();

            if (isExpectContent) {
                request.setSkipRemainder(true);
            }

            grizzlyRequest.recycle(!isExpectContent);
            grizzlyResponse.recycle(!isExpectContent);
        } else {
            grizzlyRequest.getContext().flush(FLUSH_AND_CLOSE_HANDLER);
            grizzlyRequest.recycle();
            grizzlyResponse.recycle();
        }

    }

    /**
     * Checks if the HTTP connection should be kept alive
     */
    private static boolean isKeepAlive(final GrizzlyRequest grizzlyRequest,
            final GrizzlyResponse grizzlyResponse,
            final WebServerContext wsContext) {

        final ProcessingState ps = grizzlyRequest.getRequest().getProcessingState();
        return !ps.isError() && ps.isKeepAlive();
    }
    
    private WebServerContext obtainWebServerContext(final Connection connection) {
        WebServerContext context = webServerContextAttr.get(connection);
        if (context == null) {
            context = new WebServerContext(connection);
            webServerContextAttr.set(connection, context);
        }

        return context;
    }

    private static class WebServerContext {
        private final Connection connection;

        public WebServerContext(Connection connection) {
            this.connection = connection;
        }
        
        private volatile long keepAliveTimeoutMillis = DelayedExecutor.UNSET_TIMEOUT;
        private GrizzlyRequest associatedRequest;
    }

    private static class KeepAliveWorker implements
            DelayedExecutor.Worker<WebServerContext> {
        @Override
        public void doWork(WebServerContext element) {
            try {
                element.connection.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static class KeepAliveResolver implements
            DelayedExecutor.Resolver<WebServerContext> {

        @Override
        public boolean removeTimeout(WebServerContext element) {
            if (element.keepAliveTimeoutMillis != DelayedExecutor.UNSET_TIMEOUT) {
                element.keepAliveTimeoutMillis = DelayedExecutor.UNSET_TIMEOUT;
                return true;
            }

            return false;
        }

        @Override
        public Long getTimeoutMillis(WebServerContext element) {
            return element.keepAliveTimeoutMillis;
        }

        @Override
        public void setTimeoutMillis(WebServerContext element, long timeoutMillis) {
            element.keepAliveTimeoutMillis = timeoutMillis;
        }
    }

    private static class FlushAndCloseHandler extends EmptyCompletionHandler {
        @Override
        public void completed(Object result) {
            final WriteResult wr = (WriteResult) result;
            try {
                wr.getConnection().close().markForRecycle(false);
            } catch (IOException ignore) {
            } finally {
                wr.recycle();
            }
        }
    }
}
