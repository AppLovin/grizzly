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

package com.sun.grizzly.nio.transport;

import java.util.concurrent.Future;
import com.sun.grizzly.IOEvent;
import com.sun.grizzly.nio.AbstractNIOTransport;
import com.sun.grizzly.Connection;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.sun.grizzly.Buffer;
import com.sun.grizzly.CompletionHandler;
import com.sun.grizzly.CompletionHandlerAdapter;
import com.sun.grizzly.Context;
import com.sun.grizzly.Grizzly;
import com.sun.grizzly.PostProcessor;
import com.sun.grizzly.Processor;
import com.sun.grizzly.ProcessorExecutor;
import com.sun.grizzly.ProcessorResult;
import com.sun.grizzly.ProcessorResult.Status;
import com.sun.grizzly.ProcessorRunnable;
import com.sun.grizzly.ProcessorSelector;
import com.sun.grizzly.ReadResult;
import com.sun.grizzly.SocketBinder;
import com.sun.grizzly.SocketConnectorHandler;
import com.sun.grizzly.WriteResult;
import com.sun.grizzly.asyncqueue.AsyncQueueEnabledTransport;
import com.sun.grizzly.asyncqueue.AsyncQueueIO;
import com.sun.grizzly.asyncqueue.AsyncQueueReader;
import com.sun.grizzly.asyncqueue.AsyncQueueWriter;
import com.sun.grizzly.filterchain.DefaultFilterChain;
import com.sun.grizzly.filterchain.Filter;
import com.sun.grizzly.filterchain.FilterChain;
import com.sun.grizzly.filterchain.FilterChainEnabledTransport;
import com.sun.grizzly.filterchain.FilterChainFactory;
import com.sun.grizzly.filterchain.PatternFilterChainFactory;
import com.sun.grizzly.filterchain.SingletonFilterChainFactory;
import com.sun.grizzly.nio.DefaultSelectionKeyHandler;
import com.sun.grizzly.nio.DefaultSelectorHandler;
import com.sun.grizzly.nio.NIOConnection;
import com.sun.grizzly.nio.RegisterChannelResult;
import com.sun.grizzly.nio.RoundRobinConnectionDistributor;
import com.sun.grizzly.nio.SelectorRunner;
import com.sun.grizzly.nio.tmpselectors.TemporarySelectorIO;
import com.sun.grizzly.nio.tmpselectors.TemporarySelectorPool;
import com.sun.grizzly.nio.tmpselectors.TemporarySelectorsEnabledTransport;
import com.sun.grizzly.strategies.WorkerThreadStrategy;
import com.sun.grizzly.threadpool.DefaultThreadPool;
import com.sun.grizzly.threadpool.ExtendedThreadPool;

/**
 * UDP NIO transport implementation
 * 
 * @author Alexey Stashok
 */
public class UDPNIOTransport extends AbstractNIOTransport
        implements SocketBinder, SocketConnectorHandler,
        AsyncQueueEnabledTransport, FilterChainEnabledTransport,
        TemporarySelectorsEnabledTransport  {
    
    private Logger logger = Grizzly.logger;

    private static final String DEFAULT_TRANSPORT_NAME = "UDPNIOTransport";
    /**
     * Default SelectorRunners count
     */
    private static final int DEFAULT_SELECTOR_RUNNERS_COUNT = 2;

    /**
     * The server socket time out
     */
    protected int serverSocketSoTimeout = 0;
    /**
     * The socket reuseAddress
     */
    protected boolean reuseAddress = true;
    /**
     * Default channel connection timeout
     */
    protected int connectionTimeout =
            UDPNIOConnectorHandler.DEFAULT_CONNECTION_TIMEOUT;

    /**
     * The Server connections.
     */
    protected final Collection<UDPNIOServerConnection> serverConnections;
    /**
     * FilterChainFactory implementation
     */
    protected FilterChainFactory filterChainFactory;
    /**
     * Transport AsyncQueueIO
     */
    protected AsyncQueueIO asyncQueueIO;
    /**
     * Server socket backlog.
     */
    protected TemporarySelectorIO temporarySelectorIO;
    private final Filter streamTransportFilter;
    private final Filter messageTransportFilter;
    protected final RegisterChannelCompletionHandler registerChannelCompletionHandler;
    private final EnableInterestPostProcessor enablingInterestPostProcessor;

    public UDPNIOTransport() {
        this(DEFAULT_TRANSPORT_NAME);
    }

    public UDPNIOTransport(String name) {
        super(name);

        readBufferSize = -1;
        writeBufferSize = -1;

        registerChannelCompletionHandler = new RegisterChannelCompletionHandler();
        enablingInterestPostProcessor = new EnableInterestPostProcessor();

        asyncQueueIO = new AsyncQueueIO(new UDPNIOAsyncQueueReader(this),
                new UDPNIOAsyncQueueWriter(this));

        temporarySelectorIO = new TemporarySelectorIO(
                new UDPNIOTemporarySelectorReader(this),
                new UDPNIOTemporarySelectorWriter(this));

        PatternFilterChainFactory patternFactory =
                new SingletonFilterChainFactory();
        FilterChain filterChain = new DefaultFilterChain(patternFactory);
        patternFactory.setFilterChainPattern(filterChain);

        filterChainFactory = patternFactory;

        streamTransportFilter = new UDPNIOStreamTransportFilter(this);
        messageTransportFilter = new UDPNIOMessageTransportFilter(this);
        serverConnections = new ConcurrentLinkedQueue<UDPNIOServerConnection>();
    }

    /**
     * {@inheritDoc}
     */
    public UDPNIOServerConnection bind(int port) throws IOException {
        return bind(new InetSocketAddress(port));
    }

    /**
     * {@inheritDoc}
     */
    public UDPNIOServerConnection bind(String host, int port)
            throws IOException {
        return bind(host, port, 50);
    }

    /**
     * {@inheritDoc}
     */
    public UDPNIOServerConnection bind(String host, int port, int backlog)
            throws IOException {
        return bind(new InetSocketAddress(host, port), backlog);
    }

    /**
     * {@inheritDoc}
     */
    public UDPNIOServerConnection bind(SocketAddress socketAddress)
            throws IOException {
        return bind(socketAddress, 4096);
    }

    /**
     * {@inheritDoc}
     */
    public UDPNIOServerConnection bind(SocketAddress socketAddress, int backlog)
            throws IOException {
        state.getStateLocker().writeLock().lock();

        try {
            DatagramChannel serverSocketChannel = DatagramChannel.open();
            final UDPNIOServerConnection serverConnection =
                    new UDPNIOServerConnection(this, serverSocketChannel);
            serverConnections.add(serverConnection);

            DatagramSocket socket = serverSocketChannel.socket();
            socket.setReuseAddress(reuseAddress);
            socket.setSoTimeout(serverSocketSoTimeout);
            socket.bind(socketAddress);

            serverSocketChannel.configureBlocking(false);

            if (!isStopped()) {
                serverConnection.register();
            }

            return serverConnection;
        } finally {
            state.getStateLocker().writeLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    public void unbind(Connection connection) throws IOException {
        state.getStateLocker().writeLock().lock();

        try {
            if (connection != null &&
                    serverConnections.remove((UDPNIOServerConnection) connection)) {
                connection.close();
            }
        } finally {
            state.getStateLocker().writeLock().unlock();
        }
    }

    @Override
    public void unbindAll() throws IOException {
        state.getStateLocker().writeLock().lock();

        try {
            stopServerConnections();
        } finally {
            state.getStateLocker().writeLock().unlock();
        }
    }

    /**
     * Creates non-connected UDP {@link Connection}.
     *
     * @return non-connected UDP {@link Connection}.
     * @throws java.io.IOException
     */
    public Future<Connection> connect() throws IOException {
        return connect(null, null, null);
    }

    public Future<Connection> connect(String host, int port)
            throws IOException {
        return connect(new InetSocketAddress(host, port));
    }

    public Future<Connection> connect(SocketAddress remoteAddress)
            throws IOException {
        return connect(remoteAddress, (SocketAddress) null);
    }

    public Future<Connection> connect(SocketAddress remoteAddress,
            SocketAddress localAddress) throws IOException {
        return connect(remoteAddress, localAddress, null);
    }

    @Override
    public Future<Connection> connect(SocketAddress remoteAddress,
            CompletionHandler<Connection> completionHandler)
            throws IOException {
        return connect(remoteAddress, null, completionHandler);

    }

    @Override
    public Future<Connection> connect(SocketAddress remoteAddress,
            SocketAddress localAddress,
            CompletionHandler<Connection> completionHandler)
            throws IOException {
        UDPNIOConnectorHandler connectorHandler = new UDPNIOConnectorHandler(this);
        return connectorHandler.connect(remoteAddress, localAddress,
                completionHandler);
    }

    @Override
    protected void closeConnection(Connection connection) throws IOException {
        SelectableChannel nioChannel = ((NIOConnection) connection).getChannel();

        // channel could be either SocketChannel or ServerSocketChannel
        if (nioChannel instanceof DatagramChannel) {
            DatagramSocket socket = ((DatagramChannel) nioChannel).socket();
            socket.close();
        }

        if (nioChannel != null) {
            try {
                nioChannel.close();
            } catch (IOException e) {
                Grizzly.logger.log(Level.FINE,
                        "TCPNIOTransport.closeChannel exception", e);
            }
        }

        if (asyncQueueIO != null) {
            AsyncQueueReader reader = asyncQueueIO.getReader();
            if (reader != null) {
                reader.onClose(connection);
            }

            AsyncQueueWriter writer = asyncQueueIO.getWriter();
            if (writer != null) {
                writer.onClose(connection);
            }

        }
    }

    @Override
    public void start() throws IOException {
        state.getStateLocker().writeLock().lock();
        try {
            State currentState = state.getState(false);
            if (currentState != State.STOP) {
                Grizzly.logger.log(Level.WARNING,
                        "Transport is not in STOP or BOUND state!");
            }

            state.setState(State.STARTING);

            if (selectorHandler == null) {
                selectorHandler = new DefaultSelectorHandler();
            }

            if (selectionKeyHandler == null) {
                selectionKeyHandler = new DefaultSelectionKeyHandler();
            }

            if (processor == null && processorSelector == null) {
                processor = getFilterChainFactory().create();
            }

            if (selectorRunnersCount <= 0) {
                selectorRunnersCount = DEFAULT_SELECTOR_RUNNERS_COUNT;
            }

            if (nioChannelDistributor == null) {
                nioChannelDistributor = new RoundRobinConnectionDistributor(this);
            }

            if (strategy == null) {
                strategy = new WorkerThreadStrategy(this);
            }

            if (internalThreadPool == null) {
                internalThreadPool = new DefaultThreadPool(
                        selectorRunnersCount * 2,
                        selectorRunnersCount * 4, 1, 5, TimeUnit.SECONDS);
            }

            if (workerThreadPool == null) {
                workerThreadPool = new DefaultThreadPool();
            }

            /* By default TemporarySelector pool size should be equal
            to the number of processing threads */
            int selectorPoolSize =
                    TemporarySelectorPool.DEFAULT_SELECTORS_COUNT;
            if (workerThreadPool instanceof ExtendedThreadPool) {
                selectorPoolSize =((ExtendedThreadPool) workerThreadPool).
                        getMaximumPoolSize();
            }
            temporarySelectorIO.setSelectorPool(
                    new TemporarySelectorPool(selectorPoolSize));

            startSelectorRunners();
            
            registerServerConnections();
        } finally {
            state.getStateLocker().writeLock().unlock();
        }
    }

    private void registerServerConnections() {
        for (UDPNIOServerConnection serverConnection : serverConnections) {
            try {
                serverConnection.register();
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "Exception occurred when starting server connection: " +
                        serverConnection, e);
            }
        }
    }

    @Override
    public void stop() throws IOException {
        state.getStateLocker().writeLock().lock();

        try {
            state.setState(State.STOP);
            stopSelectorRunners();

            if (internalThreadPool != null) {
                internalThreadPool.shutdown();
                internalThreadPool = null;
            }

            stopServerConnections();
        } finally {
            state.getStateLocker().writeLock().unlock();
        }
    }

    private void stopServerConnections() {
        for (Connection serverConnection : serverConnections) {
            try {
                serverConnection.close();
            } catch (Exception e) {
                logger.log(Level.FINE,
                        "Exception occurred when closing server connection: " +
                        serverConnection, e);
            }
        }

        serverConnections.clear();
    }

    @Override
    public void pause() throws IOException {
        state.getStateLocker().writeLock().lock();

        try {
            if (state.getState(false) != State.START) {
                Grizzly.logger.log(Level.WARNING,
                        "Transport is not in START state!");
            }
            state.setState(State.PAUSE);
        } finally {
            state.getStateLocker().writeLock().unlock();
        }
    }

    @Override
    public void resume() throws IOException {
        state.getStateLocker().writeLock().lock();

        try {
            if (state.getState(false) != State.PAUSE) {
                Grizzly.logger.log(Level.WARNING,
                        "Transport is not in PAUSE state!");
            }
            state.setState(State.START);
        } finally {
            state.getStateLocker().writeLock().unlock();
        }
    }
    
    public FilterChainFactory getFilterChainFactory() {
        return filterChainFactory;
    }

    public void setFilterChainFactory(FilterChainFactory factory) {
        filterChainFactory = factory;
    }

    public FilterChain getFilterChain() {
        final FilterChainFactory factory = getFilterChainFactory();
        if (factory instanceof PatternFilterChainFactory) {
            return ((PatternFilterChainFactory) factory).getFilterChainPattern();
        }

        throw new IllegalStateException(
                "Transport FilterChainFactory doesn't " +
                "support creating of FilterChain by a patterns. " +
                "It means you have to add/remove Filters using " +
                "FilterChainFactory API: " + factory.getClass().getName());
    }

    public Filter getStreamTransportFilter() {
        return streamTransportFilter;
    }

    @Override
    public Filter getMessageTransportFilter() {
        return messageTransportFilter;
    }

    protected NIOConnection obtainNIOConnection(DatagramChannel channel) {
        final UDPNIOConnection connection = new UDPNIOConnection(this, channel);
        connection.configureBlocking(isBlocking);
        return connection;
    }

    public AsyncQueueIO getAsyncQueueIO() {
        return asyncQueueIO;
    }

    public TemporarySelectorIO getTemporarySelectorIO() {
        return temporarySelectorIO;
    }

    public void setTemporarySelectorIO(TemporarySelectorIO temporarySelectorIO) {
        this.temporarySelectorIO = temporarySelectorIO;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public boolean isReuseAddress() {
        return reuseAddress;
    }

    public void setReuseAddress(boolean reuseAddress) {
        this.reuseAddress = reuseAddress;
    }

    public void fireIOEvent(final IOEvent ioEvent, final Connection connection,
            final Object strategyContext) throws IOException {

        try {
            // First of all try operations, which could run in standalone mode
            if (ioEvent == IOEvent.READ) {
                processReadIoEvent(ioEvent, (UDPNIOConnection) connection,
                        strategyContext);
            } else if (ioEvent == IOEvent.WRITE) {
                processWriteIoEvent(ioEvent, (UDPNIOConnection) connection,
                        strategyContext);
            } else {
                final Processor conProcessor = getConnectionProcessor(
                        connection, ioEvent);

                if (conProcessor != null) {
                    executeProcessor(ioEvent, connection, conProcessor,
                            null, null, strategyContext);
                } else {
                    ((NIOConnection) connection).disableIOEvent(ioEvent);
                }
            }
        } catch (IOException e) {
            logger.log(Level.FINE, "IOException occurred on fireIOEvent()." +
                    "connection=" + connection + " event=" + ioEvent);
            throw e;
        } catch (Exception e) {
            String text = new StringBuilder(256).
                    append("Unexpected exception occurred fireIOEvent().").
                    append("connection=").append(connection).
                    append(" event=").append(ioEvent).toString();

            logger.log(Level.WARNING, text, e);
            throw new IOException(e.getClass() + ": " + text);
        }

    }

    protected void executeProcessor(final IOEvent ioEvent,
            final Connection connection,
            final Processor processor, final ProcessorExecutor executor,
            final PostProcessor postProcessor, final Object strategyContext)
            throws IOException {

        final ProcessorRunnable processorRunnable = new ProcessorRunnable(
                ioEvent, connection, processor, postProcessor);

        strategy.executeProcessor(strategyContext, processorRunnable);
    }

    private void processReadIoEvent(final IOEvent ioEvent,
            final UDPNIOConnection connection, final Object strategyContext)
            throws IOException {

        final UDPNIOAsyncQueueReader asyncQueueReader =
                (UDPNIOAsyncQueueReader) getAsyncQueueIO().getReader();

        if (asyncQueueReader == null || !asyncQueueReader.isReady(connection)) {
            executeDefaultProcessor(ioEvent, connection, strategyContext);
        } else {
            connection.disableIOEvent(ioEvent);
            executeProcessor(ioEvent, connection, asyncQueueReader,
                    null, null, strategyContext);
        }
    }

    private void processWriteIoEvent(final IOEvent ioEvent,
            final UDPNIOConnection connection, final Object strategyContext)
            throws IOException {
        final AsyncQueueWriter asyncQueueWriter = getAsyncQueueIO().getWriter();

        if (asyncQueueWriter == null || !asyncQueueWriter.isReady(connection)) {
            executeDefaultProcessor(ioEvent, connection, strategyContext);
        } else {
            connection.disableIOEvent(ioEvent);
            executeProcessor(ioEvent, connection, asyncQueueWriter,
                    null, null, strategyContext);
        }
    }


    private void executeDefaultProcessor(final IOEvent ioEvent,
            final UDPNIOConnection connection, final Object strategyContext)
            throws IOException {

        connection.disableIOEvent(ioEvent);
        final Processor conProcessor = getConnectionProcessor(connection, ioEvent);
        if (conProcessor != null) {
            executeProcessor(ioEvent, connection, conProcessor, null,
                    enablingInterestPostProcessor, strategyContext);
        }
    }

    Processor getConnectionProcessor(final Connection connection,
            final IOEvent ioEvent) {
        Processor conProcessor = connection.getProcessor();
        final ProcessorSelector conProcessorSelector =
                connection.getProcessorSelector();

        if ((conProcessor == null || !conProcessor.isInterested(ioEvent)) &&
                conProcessorSelector != null) {
            conProcessor = conProcessorSelector.select(ioEvent, connection);
        }

        return conProcessor;
    }
    
    public int read(final Connection connection, final Buffer buffer)
            throws IOException {
        return read(connection, buffer, null);
    }

    public int read(final Connection connection, Buffer buffer,
            final ReadResult currentResult) throws IOException {

        int read = 0;

        boolean isAllocated = false;
        if (buffer == null && currentResult != null) {

            buffer = memoryManager.allocate(
                    connection.getReadBufferSize());
            isAllocated = true;
        }

        final int initialPos = buffer.position();
        SocketAddress srcAddress = null;
        
        if (buffer.hasRemaining()) {
            UDPNIOConnection udpConnection = (UDPNIOConnection) connection;
            srcAddress = ((DatagramChannel) udpConnection.getChannel()).receive(
                    (ByteBuffer) buffer.underlying());
            read = buffer.position() - initialPos;
        }

        if (isAllocated) {
            if (read > 0) {
                buffer.trim();
                buffer.position(buffer.limit());
            } else {
                buffer.dispose();
                buffer = null;
            }
        }

        if (currentResult != null && read >= 0) {
            currentResult.setMessage(buffer);
            currentResult.setReadSize(currentResult.getReadSize() + read);
            currentResult.setSrcAddress(srcAddress);
        }

        return read;
    }

    public int write(final Connection connection,
            final SocketAddress dstAddress, final Buffer buffer)
            throws IOException {
        return write(connection, dstAddress, buffer, null);
    }

    public int write(final Connection connection, final SocketAddress dstAddress,
            final Buffer buffer, final WriteResult currentResult)
            throws IOException {

        final UDPNIOConnection udpConnection = (UDPNIOConnection) connection;
        final int written;
        if (dstAddress != null) {
            written = ((DatagramChannel) udpConnection.getChannel()).send(
                    (ByteBuffer) buffer.underlying(), dstAddress);
        } else {
            written = ((DatagramChannel) udpConnection.getChannel()).write(
                    (ByteBuffer) buffer.underlying());
        }

        if (currentResult != null) {
            currentResult.setMessage(buffer);
            currentResult.setWrittenSize(currentResult.getWrittenSize() +
                    written);
            currentResult.setDstAddress(
                    connection.getPeerAddress());
        }

        return written;
    }

    public class EnableInterestPostProcessor
            implements PostProcessor {

        public void process(final ProcessorResult result,
                final Context context) throws IOException {
            if (result == null || result.getStatus() == Status.OK) {
                final IOEvent ioEvent = context.getIoEvent();
                ((NIOConnection) context.getConnection()).enableIOEvent(ioEvent);
            }
        }
    }

    protected class RegisterChannelCompletionHandler
            extends CompletionHandlerAdapter<RegisterChannelResult> {

        @Override
        public void completed(final Connection c,
                final RegisterChannelResult result) {
            try {
                final SelectionKey selectionKey = result.getSelectionKey();

                final UDPNIOConnection connection =
                        (UDPNIOConnection) getSelectionKeyHandler().
                        getConnectionForKey(selectionKey);

                if (connection != null) {
                    final SelectorRunner selectorRunner = result.getSelectorRunner();
                    connection.setSelectionKey(selectionKey);
                    connection.setSelectorRunner(selectorRunner);
                }
            } catch (Exception e) {
                Grizzly.logger.log(Level.FINE, "Exception happened, when " +
                        "trying to register the channel", e);
            }
        }
    }
}
