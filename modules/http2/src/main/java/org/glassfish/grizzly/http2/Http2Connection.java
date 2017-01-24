/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012-2017 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CloseType;
import org.glassfish.grizzly.Closeable;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Context;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.GenericCloseListener;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.IOEvent;
import org.glassfish.grizzly.IOEventLifeCycleListener;
import org.glassfish.grizzly.ProcessorExecutor;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http2.frames.DataFrame;
import org.glassfish.grizzly.http2.frames.PingFrame;
import org.glassfish.grizzly.http2.frames.PriorityFrame;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpContext;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpPacket;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.util.Header;

import org.glassfish.grizzly.http2.frames.ContinuationFrame;
import org.glassfish.grizzly.http2.frames.ErrorCode;
import org.glassfish.grizzly.http2.frames.GoAwayFrame;
import org.glassfish.grizzly.http2.frames.HeadersFrame;
import org.glassfish.grizzly.http2.frames.Http2Frame;
import org.glassfish.grizzly.http2.frames.PushPromiseFrame;
import org.glassfish.grizzly.http2.frames.RstStreamFrame;
import org.glassfish.grizzly.http2.frames.SettingsFrame;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.ssl.SSLBaseFilter;
import org.glassfish.grizzly.utils.DataStructures;
import org.glassfish.grizzly.utils.Holder;
import org.glassfish.grizzly.utils.NullaryFunction;

import static org.glassfish.grizzly.http2.frames.SettingsFrame.*;
import static org.glassfish.grizzly.http2.Constants.*;
import static org.glassfish.grizzly.http2.Http2BaseFilter.PRI_PAYLOAD;
import org.glassfish.grizzly.http2.frames.HeaderBlockFragment;
import org.glassfish.grizzly.http2.frames.WindowUpdateFrame;



/**
 * The HTTP2 connection abstraction.
 * 
 * @author Alexey Stashok
 */
public class Http2Connection {
    private static final Logger LOGGER = Grizzly.logger(Http2Connection.class);

    private final boolean isServer;
    private final Connection<?> connection;
    Http2State http2State;
    
    private HeadersDecoder headersDecoder;
    private HeadersEncoder headersEncoder;

    private final ReentrantLock deflaterLock = new ReentrantLock();
    
    private int lastPeerStreamId;
    private int lastLocalStreamId;

    private final ReentrantLock newClientStreamLock = new ReentrantLock();
    
    private volatile FilterChain http2StreamChain;
    private volatile FilterChain http2ConnectionChain;

    volatile Boolean cipherSuiteOkay;
    
    private final Map<Integer, Http2Stream> streamsMap =
            DataStructures.getConcurrentMap();
    
    // (Optimization) We may read several DataFrames belonging to the same
    // Http2Stream, so in order to not process every DataFrame separately -
    // we buffer them and only then passing for processing.
    final List<Http2Stream> streamsToFlushInput = new ArrayList<>();
    
    // The List object used to store header frames. Could be used by
    // Http2Connection streams, when they write headers
    protected final List<Http2Frame> tmpHeaderFramesList =
            new ArrayList<>(2);
    
    private final Object sessionLock = new Object();
    
    private CloseType closeFlag;
    
    private int peerStreamWindowSize = getDefaultStreamWindowSize();
    private volatile int localStreamWindowSize = getDefaultStreamWindowSize();
    
    private volatile int localConnectionWindowSize = getDefaultConnectionWindowSize();

    private volatile int maxHeaderListSize;
    
    private volatile int localMaxConcurrentStreams = getDefaultMaxConcurrentStreams();
    private int peerMaxConcurrentStreams = getDefaultMaxConcurrentStreams();

    private final StreamBuilder streamBuilder = new StreamBuilder();
    
    private final Http2ConnectionOutputSink outputSink;
    
    // true, if this connection is ready to accept frames or false if the first
    // HTTP/1.1 Upgrade is still in progress
    private volatile boolean isPrefaceReceived;
    private volatile boolean isPrefaceSent;
    
    public static Http2Connection get(final Connection connection) {
        final Http2State http2State = Http2State.get(connection);
        return http2State != null
                ? http2State.getHttp2Connection()
                : null;
    }
    
    static void bind(final Connection connection,
            final Http2Connection http2Connection) {
        Http2State.obtain(connection).setHttp2Connection(http2Connection);
    }
    
    private final Holder<?> addressHolder;

    final Http2BaseFilter handlerFilter;

    private final int localMaxFramePayloadSize;
    private int peerMaxFramePayloadSize = getSpecDefaultFramePayloadSize();
    
    private boolean isFirstInFrame = true;
    private volatile SSLBaseFilter sslFilter;
    
    private final AtomicInteger unackedReadBytes  = new AtomicInteger();
        
    public Http2Connection(final Connection<?> connection,
                       final boolean isServer,
                       final Http2BaseFilter handlerFilter) {
        this.connection = connection;
        final FilterChain chain = (FilterChain) connection.getProcessor();
        final int sslIdx = chain.indexOfType(SSLBaseFilter.class);
        if (sslIdx != -1) {
            sslFilter = (SSLBaseFilter) chain.get(sslIdx);
        }
        this.isServer = isServer;
        this.handlerFilter = handlerFilter;
        
        final int customMaxFramePayloadSz
                = handlerFilter.getLocalMaxFramePayloadSize() > 0
                ? handlerFilter.getLocalMaxFramePayloadSize()
                : -1;
        
        // apply custom max frame value only if it's in [getSpecMinFramePayloadSize(); getSpecMaxFramePayloadSize()] range
        localMaxFramePayloadSize =
                customMaxFramePayloadSz >= getSpecMinFramePayloadSize() &&
                customMaxFramePayloadSz <= getSpecMaxFramePayloadSize()
                ? customMaxFramePayloadSz
                : getSpecDefaultFramePayloadSize();

        maxHeaderListSize = handlerFilter.getMaxHeaderListSize();

        if (isServer) {
            lastLocalStreamId = 0;
            lastPeerStreamId = -1;
        } else {
            lastLocalStreamId = -1;
            lastPeerStreamId = 0;
        }
        
        addressHolder = Holder.lazyHolder(new NullaryFunction<Object>() {
            @Override
            public Object evaluate() {
                return connection.getPeerAddress();
            }
        });
        
        connection.addCloseListener(new ConnectionCloseListener());
        
        this.outputSink = newOutputSink();
    }

    protected Http2ConnectionOutputSink newOutputSink() {
        return new Http2ConnectionOutputSink(this);
    }

    public int getFrameHeaderSize() {
        return 9;
    }

    protected int getSpecDefaultFramePayloadSize() {
        return 16384; //2^14
    }

    protected int getSpecMinFramePayloadSize() {
        return 16384; //2^14
    }

    protected int getSpecMaxFramePayloadSize() {
        return 0xffffff; // 2^24-1 (16,777,215)
    }

    public int getDefaultConnectionWindowSize() {
        return 65535;
    }

    public int getDefaultStreamWindowSize() {
        return 65535;
    }

    public int getDefaultMaxConcurrentStreams() {
        return 100;
    }

    /**
     * @return the maximum size, in bytes, of header list.  If not explicitly configured, the default of
     *  <code>8192</code> is used.
     */
    public int getMaxHeaderListSize() {
        return maxHeaderListSize;
    }

    /**
     * Set the maximum size, in bytes, of the header list.
     *
     * @param maxHeaderListSize size, in bytes, of the header list.
     */
    public void setMaxHeaderListSize(int maxHeaderListSize) {
        this.maxHeaderListSize = maxHeaderListSize;
    }

    protected boolean isFrameReady(final Buffer buffer) {
        final int frameLen = getFrameSize(buffer);
        return frameLen > 0 && buffer.remaining() >= frameLen;
    }

    /**
     * Returns the total frame size (including header size), or <tt>-1</tt>
     * if the buffer doesn't contain enough bytes to read the size.
     *
     * @param buffer the buffer containing the frame data
     *
     * @return the total frame size (including header size), or <tt>-1</tt>
     * if the buffer doesn't contain enough bytes to read the size
     */
    protected int getFrameSize(final Buffer buffer) {
        return buffer.remaining() < 4 // even though we need just 3 bytes - we require 4 for simplicity
                ? -1
                : (buffer.getInt(buffer.position()) >>> 8) + getFrameHeaderSize();
    }

    public Http2Frame parseHttp2FrameHeader(final Buffer buffer)
            throws Http2ConnectionException {
        // we assume the passed buffer represents only this frame, no remainders allowed
        assert buffer.remaining() == getFrameSize(buffer);

        final int i1 = buffer.getInt();

        final int length = (i1 >>> 8) & 0xffffff;
        final int type =  i1 & 0xff;

        final int flags = buffer.get() & 0xff;
        final int streamId = buffer.getInt() & 0x7fffffff;

        switch (type) {
            case DataFrame.TYPE:
                return DataFrame.fromBuffer(length, flags, streamId, buffer)
                        .normalize(); // remove padding
            case HeadersFrame.TYPE:
                return HeadersFrame.fromBuffer(length, flags, streamId, buffer)
                        .normalize(); // remove padding
            case PriorityFrame.TYPE:
                return PriorityFrame.fromBuffer(streamId, buffer);
            case RstStreamFrame.TYPE:
                return RstStreamFrame.fromBuffer(flags, streamId, buffer);
            case SettingsFrame.TYPE:
                return SettingsFrame.fromBuffer(length, flags, buffer);
            case PushPromiseFrame.TYPE:
                return PushPromiseFrame.fromBuffer(length, flags, streamId, buffer);
            case PingFrame.TYPE:
                return PingFrame.fromBuffer(flags, buffer);
            case GoAwayFrame.TYPE:
                return GoAwayFrame.fromBuffer(length, buffer);
            case WindowUpdateFrame.TYPE:
                return WindowUpdateFrame.fromBuffer(flags, streamId, buffer);
            case ContinuationFrame.TYPE:
                return ContinuationFrame.fromBuffer(length, flags, streamId, buffer);
            default:
                throw new Http2ConnectionException(ErrorCode.PROTOCOL_ERROR,
                        "Unknown frame type: " + type);
        }
    }

    public void serializeHttp2FrameHeader(final Http2Frame frame,
                                          final Buffer buffer) {
        assert buffer.remaining() >= getFrameHeaderSize();

        buffer.putInt(
                ((frame.getLength() & 0xffffff) << 8) |
                        frame.getType());
        buffer.put((byte) frame.getFlags());
        buffer.putInt(frame.getStreamId());
    }
    
    protected Http2Stream newStream(final HttpRequestPacket request,
            final int streamId, final int refStreamId,
            final boolean exclusive, final int priority,
            final Http2StreamState initialState) {
        
        return new Http2Stream(this, request, streamId, refStreamId,
                               exclusive, priority, initialState);
    }

    protected Http2Stream newUpgradeStream(final HttpRequestPacket request,
            final int priority, final Http2StreamState initialState) {
        
        return new Http2Stream(this, request, priority, initialState);
    }

    protected void checkFrameSequenceSemantics(final Http2Frame frame)
            throws Http2ConnectionException {
        
        final int frameType = frame.getType();
        
        if (isFirstInFrame) {
            if (frameType != SettingsFrame.TYPE) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "First in frame should be a SettingsFrame (preface)", frame);
                }
                
                throw new Http2ConnectionException(ErrorCode.PROTOCOL_ERROR);
            }
            
            isPrefaceReceived = true;
            handlerFilter.onPrefaceReceived(this);
            
            // Preface received - change the HTTP2 connection state
            Http2State.get(connection).setOpen();
            
            isFirstInFrame = false;
        }
        
        // 1) make sure the header frame sequence comes without interleaving
        if (isParsingHeaders()) {
            if (frameType != ContinuationFrame.TYPE) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "ContinuationFrame is expected, but {0} came", frame);
                }

                throw new Http2ConnectionException(ErrorCode.PROTOCOL_ERROR);
            }
        } else if (frameType == ContinuationFrame.TYPE) {
            // isParsing == false, so no ContinuationFrame expected
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "ContinuationFrame is not expected");
            }

            throw new Http2ConnectionException(ErrorCode.PROTOCOL_ERROR);
        }
    }
    
    protected void onOversizedFrame(final Buffer buffer)
            throws Http2ConnectionException {
        
        final int oldPos = buffer.position();
        try {
            final Http2Frame oversizedFrame = parseHttp2FrameHeader(buffer);
            final int streamId = oversizedFrame.getStreamId();
            if (streamId > 0) {
                final Http2Stream stream = getStream(streamId);
                if (stream != null) {
                    // Known Http2Stream
                    stream.inputBuffer.close(FRAME_TOO_LARGE_TERMINATION);
                    sendRstFrame(ErrorCode.FRAME_SIZE_ERROR, streamId);
                } else {
                    sendRstFrame(ErrorCode.STREAM_CLOSED, streamId);
                }
            } else {
                throw new Http2ConnectionException(ErrorCode.FRAME_SIZE_ERROR);
            }
        } finally {
            buffer.position(oldPos);
        }
    }
    
    boolean isParsingHeaders() {
        return headersDecoder != null && headersDecoder.isProcessingHeaders();
    }
    
    /**
     * @return The max <tt>payload</tt> size to be accepted by this side
     */
    public final int getLocalMaxFramePayloadSize() {
        return localMaxFramePayloadSize;
    }

    /**
     * @return The max <tt>payload</tt> size to be accepted by the peer
     */
    public int getPeerMaxFramePayloadSize() {
        return peerMaxFramePayloadSize;
    }

    /**
     * Sets the max <tt>payload</tt> size to be accepted by the peer.
     * The method is called during the {@link SettingsFrame} processing.
     * 
     * @param peerMaxFramePayloadSize max payload size accepted by the peer.
     * @throws Http2ConnectionException if the peerMaxFramePayloadSize violates the limits
     */
    protected void setPeerMaxFramePayloadSize(final int peerMaxFramePayloadSize)
            throws Http2ConnectionException {
        if (peerMaxFramePayloadSize < getSpecMinFramePayloadSize() ||
                peerMaxFramePayloadSize > getSpecMaxFramePayloadSize()) {
            throw new Http2ConnectionException(ErrorCode.FRAME_SIZE_ERROR);
        }
        this.peerMaxFramePayloadSize = peerMaxFramePayloadSize;
    }
    
    
    boolean canWrite() {
        return outputSink.canWrite();
    }
    
    void notifyCanWrite(final WriteHandler writeHandler) {
        outputSink.notifyCanWrite(writeHandler);
    }
    
    public int getLocalStreamWindowSize() {
        return localStreamWindowSize;
    }

    public void setLocalStreamWindowSize(int localStreamWindowSize) {
        this.localStreamWindowSize = localStreamWindowSize;
    }
    
    public int getPeerStreamWindowSize() {
        return peerStreamWindowSize;
    }
    
    void setPeerStreamWindowSize(final int peerStreamWindowSize) {
        synchronized (sessionLock) {
            final int delta = peerStreamWindowSize - this.peerStreamWindowSize;
            
            this.peerStreamWindowSize = peerStreamWindowSize;
            
            for (Http2Stream stream : streamsMap.values()) {
                try {
                    stream.getOutputSink().onPeerWindowUpdate(delta);
                } catch (Http2StreamException e) {
                    if (LOGGER.isLoggable(Level.SEVERE)) {
                        LOGGER.log(Level.SEVERE, "Http2StreamException occurred on stream="
                                + stream + " during stream window update", e);
                    }

                    sendRstFrame(e.getErrorCode(), e.getStreamId());
                }
            }
        }
    }

    public int getLocalConnectionWindowSize() {
        return localConnectionWindowSize;
    }

    public void setLocalConnectionWindowSize(final int localConnectionWindowSize) {
        this.localConnectionWindowSize = localConnectionWindowSize;
    }
    
    public int getAvailablePeerConnectionWindowSize() {
        return outputSink.getAvailablePeerConnectionWindowSize();
    }
    
    /**
     * @return the maximum number of concurrent streams allowed for this session by our side.
     */
    public int getLocalMaxConcurrentStreams() {
        return localMaxConcurrentStreams;
    }

    /**
     * Sets the default maximum number of concurrent streams allowed for this session by our side.
     * @param localMaxConcurrentStreams max number of streams locally allowed
     */
    public void setLocalMaxConcurrentStreams(int localMaxConcurrentStreams) {
        this.localMaxConcurrentStreams = localMaxConcurrentStreams;
    }

    /**
     * @return the maximum number of concurrent streams allowed for this session by peer.
     */
    public int getPeerMaxConcurrentStreams() {
        return peerMaxConcurrentStreams;
    }

    /**
     * Sets the default maximum number of concurrent streams allowed for this session by peer.
     */
    void setPeerMaxConcurrentStreams(int peerMaxConcurrentStreams) {
        this.peerMaxConcurrentStreams = peerMaxConcurrentStreams;
    }

    
    public int getNextLocalStreamId() {
        lastLocalStreamId += 2;
        return lastLocalStreamId;
    }
    
    public StreamBuilder getStreamBuilder() {
        return streamBuilder;
    }
    
    public Connection getConnection() {
        return connection;
    }

    public MemoryManager getMemoryManager() {
        return connection.getMemoryManager();
    }
    
    public boolean isServer() {
        return isServer;
    }

    public boolean isLocallyInitiatedStream(final int streamId) {
        assert streamId > 0;
        
        return isServer() ^ ((streamId % 2) != 0);
        
//        Same as
//        return isServer() ?
//                (streamId % 2) == 0 :
//                (streamId % 2) == 1;        
    }
    
    Http2State getHttp2State() {
        return http2State;
    }
    
    boolean isHttp2InputEnabled() {
        return isPrefaceReceived;
    }

    boolean isHttp2OutputEnabled() {
        return isPrefaceSent;
    }

    public Http2Stream getStream(final int streamId) {
        return streamsMap.get(streamId);
    }
    
    protected Http2ConnectionOutputSink getOutputSink() {
        return outputSink;
    }
    
    /**
     * If the session is still open - closes it and sends GOAWAY frame to a peer,
     * otherwise if the session was already closed - does nothing.
     * 
     * @param errorCode GOAWAY status code.
     */
    public void goAway(final ErrorCode errorCode) {
        final Http2Frame goAwayFrame = setGoAwayLocally(errorCode);
        if (goAwayFrame != null) {
            outputSink.writeDownStream(goAwayFrame);
        }
    }

    GoAwayFrame setGoAwayLocally(final ErrorCode errorCode) {
        final int lastPeerStreamIdLocal = close();
        if (lastPeerStreamIdLocal == -1) {
            return null; // Http2Connection is already in go-away state
        }
        
        return GoAwayFrame.builder()
                .lastStreamId(lastPeerStreamIdLocal)
                .errorCode(errorCode)
                .build();
    }

    protected void sendWindowUpdate(final int streamId, final int delta) {
        outputSink.writeDownStream(
                WindowUpdateFrame.builder()
                        .streamId(streamId)
                        .windowSizeIncrement(delta)
                        .build());
    }
    
    boolean sendPreface() {
        if (!isPrefaceSent) {
            synchronized (sessionLock) {
                if (!isPrefaceSent) {
                    if (isServer) {
                        sendServerPreface();
                    } else {
                        sendClientPreface();
                    }

                    isPrefaceSent = true;
                    
                    if (!isServer) {
                        // If it's HTTP2 client, which uses HTTP/1.1 upgrade mechanism -
                        // it can have unacked user data sent from the server.
                        // So it's right time to ack this data and let the server send
                        // more data if needed.
                        ackConsumedData(getStream(0), 0);
                    }
                    return true;
                }
            }
        }
        
        return false;
    }
    
    protected void sendServerPreface() {
        final SettingsFrame settingsFrame = prepareSettings().build();
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "Tx: server preface (the settings frame)."
                    + "Connection={0}, settingsFrame={1}",
                    new Object[]{connection, settingsFrame});
        }

        // server preface
        //noinspection unchecked
        connection.write(settingsFrame.toBuffer(this), ((sslFilter != null) ? new EmptyCompletionHandler() {
            @Override
            public void completed(Object result) {
                sslFilter.setRenegotiationDisabled(true);
            }
        } : null));
    }
    
    protected void sendClientPreface() {
        // send preface (PRI * ....)
        final HttpRequestPacket request =
                HttpRequestPacket.builder()
                .method(Method.PRI)
                .uri("*")
                .protocol(Protocol.HTTP_2_0)
                .build();
        
        final Buffer priPayload =
                Buffers.wrap(connection.getMemoryManager(), PRI_PAYLOAD);
        
        final SettingsFrame settingsFrame = prepareSettings().build();        
        final Buffer settingsBuffer = settingsFrame.toBuffer(this);
        
        final Buffer payload = Buffers.appendBuffers(
                connection.getMemoryManager(), priPayload, settingsBuffer);
        
        final HttpContent content = HttpContent.builder(request)
                .content(payload)
                .build();
        
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "Tx: client preface including the settings "
                    + "frame. Connection={0}, settingsFrame={1}",
                    new Object[]{connection, settingsFrame});
        }
        
        connection.write(content);
    }
    
    protected void sendRstFrame(final ErrorCode errorCode, final int streamId) {
        outputSink.writeDownStream(
                RstStreamFrame.builder()
                        .errorCode(errorCode)
                        .streamId(streamId)
                        .build());
    }
    
    HeadersDecoder getHeadersDecoder() {
        if (headersDecoder == null) {
            headersDecoder = new HeadersDecoder(getMemoryManager(), getMaxHeaderListSize(), 4096);
        }
        
        return headersDecoder;
    }

    ReentrantLock getDeflaterLock() {
        return deflaterLock;
    }

    HeadersEncoder getHeadersEncoder() {
        if (headersEncoder == null) {
            headersEncoder = new HeadersEncoder(getMemoryManager(), 4096);
        }
        
        return headersEncoder;
    }

    /**
     * Encodes the {@link HttpHeader} and locks the compression lock.
     * 
     * @param ctx the current {@link FilterChainContext}
     * @param httpHeader the {@link HttpHeader} to encode
     * @param streamId the stream associated with this request
     * @param isLast is this the last frame?
     * @param toList the target {@link List}, to which the frames will be serialized
     * 
     * @return the HTTP2 header frames sequence
     * @throws IOException if an error occurs encoding the header
     */
    protected List<Http2Frame> encodeHttpHeaderAsHeaderFrames(
            final FilterChainContext ctx,
            final HttpHeader httpHeader,
            final int streamId,
            final boolean isLast,
            final List<Http2Frame> toList)
            throws IOException {
        
        final Buffer compressedHeaders = !httpHeader.isRequest()
                ? EncoderUtils.encodeResponseHeaders(
                        this, (HttpResponsePacket) httpHeader)
                : EncoderUtils.encodeRequestHeaders(
                        this, (HttpRequestPacket) httpHeader);
        
        final List<Http2Frame> headerFrames =
                bufferToHeaderFrames(streamId, compressedHeaders, isLast, toList);
        
        handlerFilter.onHttpHeadersEncoded(httpHeader, ctx);

        return headerFrames;
    }
    
    /**
     * Encodes the {@link HttpRequestPacket} as a {@link PushPromiseFrame}
     * and locks the compression lock.
     * 
     * @param ctx the current {@link FilterChainContext}
     * @param httpRequest the  {@link HttpRequestPacket} to encode.
     * @param streamId the stream associated with this request.
     * @param promisedStreamId the push promise stream ID.
     * @param toList the target {@link List}, to which the frames will be serialized
     * @return the HTTP2 push promise frames sequence
     * 
     * @throws IOException if an error occurs encoding the request
     */
    protected List<Http2Frame> encodeHttpRequestAsPushPromiseFrames(
            final FilterChainContext ctx,
            final HttpRequestPacket httpRequest,
            final int streamId,
            final int promisedStreamId,
            final List<Http2Frame> toList)
            throws IOException {
        
        final List<Http2Frame> headerFrames =
                bufferToPushPromiseFrames(
                        streamId,
                        promisedStreamId,
                        EncoderUtils.encodeRequestHeaders(this, httpRequest),
                        toList);
        
        handlerFilter.onHttpHeadersEncoded(httpRequest, ctx);

        return headerFrames;
    }
    
    /**
     * Encodes a compressed header buffer as a {@link HeadersFrame} and
     * a sequence of 0 or more {@link ContinuationFrame}s.
     * 
     * @param streamId the stream associated with the headers.
     * @param compressedHeaders a {@link Buffer} containing compressed headers
     * @param isEos will any additional data be sent after these headers?
     * @param toList the {@link List} to which {@link Http2Frame}s will be added
     * @return the result {@link List} with the frames
     */
    private List<Http2Frame> bufferToHeaderFrames(final int streamId,
            final Buffer compressedHeaders, final boolean isEos,
            final List<Http2Frame> toList) {
        final HeadersFrame.HeadersFrameBuilder builder =
                HeadersFrame.builder()
                        .streamId(streamId)
                        .endStream(isEos);
        
        return completeHeadersProviderFrameSerialization(builder,
                streamId, compressedHeaders, toList);
    }
    
    /**
     * Encodes a compressed header buffer as a {@link PushPromiseFrame} and
     * a sequence of 0 or more {@link ContinuationFrame}s.
     * 
     * @param streamId the stream associated with these headers
     * @param promisedStreamId the stream of the push promise
     * @param compressedHeaders the compressed headers to be sent
     * @param toList the {@link List} to which {@link Http2Frame}s will be added
     * @return the result {@link List} with the frames
     */
    private List<Http2Frame> bufferToPushPromiseFrames(final int streamId,
            final int promisedStreamId, final Buffer compressedHeaders,
            final List<Http2Frame> toList) {
        
        final PushPromiseFrame.PushPromiseFrameBuilder builder =
                PushPromiseFrame.builder()
                        .streamId(streamId)
                        .promisedStreamId(promisedStreamId);
        
        return completeHeadersProviderFrameSerialization(builder,
                streamId, compressedHeaders, toList);
    }
    
    /**
     * Completes the {@link HeaderBlockFragment} sequence serialization.
     * 
     * @param streamId the stream associated with this {@link HeaderBlockFragment}
     * @param compressedHeaders the {@link Buffer} containing the compressed headers
     * @param toList the {@link List} to which {@link Http2Frame}s will be added
     * @return the result {@link List} with the frames
     */
    private List<Http2Frame> completeHeadersProviderFrameSerialization(
            final HeaderBlockFragment.HeaderBlockFragmentBuilder builder,
            final int streamId,
            final Buffer compressedHeaders,
            List<Http2Frame> toList) {
        // we assume deflaterLock is acquired and held by this thread
        assert deflaterLock.isHeldByCurrentThread();
        
        if (toList == null) {
            toList = tmpHeaderFramesList;
        }
        
        if (compressedHeaders.remaining() <= peerMaxFramePayloadSize) {
            toList.add(
                    builder.endHeaders(true)
                    .compressedHeaders(compressedHeaders)
                    .build()
            );

            return toList;
        }
        
        Buffer remainder = compressedHeaders.split(
                compressedHeaders.position() + peerMaxFramePayloadSize);
        
        toList.add(
                builder.endHeaders(false)
                .compressedHeaders(compressedHeaders)
                .build());
        
        assert remainder != null;
        
        do {
            final Buffer buffer = remainder;
            
            remainder = buffer.remaining() <= peerMaxFramePayloadSize
                    ? null
                    : buffer.split(buffer.position() + peerMaxFramePayloadSize);

            toList.add(
                    ContinuationFrame.builder().streamId(streamId)
                    .endHeaders(remainder == null)
                    .compressedHeaders(buffer)
                    .build());
        } while (remainder != null);
        
        return toList;
    }
    
    /**
     * The {@link ReentrantLock}, which assures that requests assigned to newly
     * allocated stream IDs will be sent to the server in their order.
     * So that request associated with the stream ID '5' won't be sent before
     * the request associated with the stream ID '3' etc.
     * 
     * @return the {@link ReentrantLock}
     */
    public ReentrantLock getNewClientStreamLock() {
        return newClientStreamLock;
    }

    Http2Stream acceptStream(final HttpRequestPacket request,
            final int streamId, final int refStreamId,
            final boolean exclusive, final int priority,
            final Http2StreamState initState)
    throws Http2ConnectionException {
        
        final Http2Stream stream = newStream(request,
                streamId, refStreamId, exclusive, priority, initState);
        
        synchronized(sessionLock) {
            if (isClosed()) {
                return null; // if the session is closed is set - return null to ignore stream creation
            }
            
            if (streamsMap.size() >= getLocalMaxConcurrentStreams()) {
                // throw Session level exception because headers were not decompressed,
                // so compression context is lost
                throw new Http2ConnectionException(ErrorCode.REFUSED_STREAM);
            }
            
            streamsMap.put(streamId, stream);
            lastPeerStreamId = streamId;
        }
        
        return stream;
    }

    /**
     * Method is not thread-safe, it is expected that it will be called
     * within {@link #getNewClientStreamLock()} lock scope.
     * The caller code is responsible for obtaining and releasing the mentioned
     * {@link #getNewClientStreamLock()} lock.
     * @param request the request that initiated the stream
     * @param streamId the ID of this new stream
     * @param refStreamId the parent stream
     * @param priority the priority of this stream
     * @param initState the initial {@link Http2StreamState}
     *
     * @return a new {@link Http2Stream} for this request
     *
     * @throws org.glassfish.grizzly.http2.Http2StreamException if an error occurs opening the stream.
     */
    public Http2Stream openStream(final HttpRequestPacket request,
            final int streamId, final int refStreamId,
            final boolean exclusive,
            final int priority,
            final Http2StreamState initState)
            throws Http2StreamException {
        
        final Http2Stream stream = newStream(request,
                streamId, refStreamId, exclusive,
                priority, initState);
        
        synchronized(sessionLock) {
            if (isClosed()) {
                throw new Http2StreamException(streamId,
                        ErrorCode.REFUSED_STREAM, "Session is closed");
            }
            
            if (streamsMap.size() >= getLocalMaxConcurrentStreams()) {
                throw new Http2StreamException(streamId, ErrorCode.REFUSED_STREAM);
            }
            
            if (refStreamId > 0) {
                final Http2Stream mainStream = getStream(refStreamId);
                if (mainStream == null) {
                    throw new Http2StreamException(streamId, ErrorCode.REFUSED_STREAM,
                            "The parent stream does not exist");
                }
            }
            
            streamsMap.put(streamId, stream);
            lastLocalStreamId = streamId;
        }
        
        return stream;
    }

    /**
     * The method is called to create an {@link Http2Stream} initiated via
     * HTTP/1.1 Upgrade mechanism.
     * 
     * @param request the request that initiated the upgrade
     * @param priority the stream priority
     * @param fin is more content expected?
     *
     * @return a new {@link Http2Stream} for this request
     *
     * @throws org.glassfish.grizzly.http2.Http2StreamException if an error occurs opening the stream.
     */
    public Http2Stream acceptUpgradeStream(final HttpRequestPacket request,
            final int priority, final boolean fin)
            throws Http2StreamException {
        
        request.setExpectContent(!fin);
        final Http2Stream stream = newUpgradeStream(request, priority,
                Http2StreamState.IDLE);
        stream.onRcvHeaders(fin);
        
        synchronized (sessionLock) {
            if (isClosed()) {
                throw new Http2StreamException(Http2Stream.UPGRADE_STREAM_ID,
                        ErrorCode.REFUSED_STREAM, "Session is closed");
            }
            
            streamsMap.put(Http2Stream.UPGRADE_STREAM_ID, stream);
            lastLocalStreamId = Http2Stream.UPGRADE_STREAM_ID;
        }
        
        return stream;
    }

    /**
     * The method is called on the client side, when the server confirms
     * HTTP/1.1 -> HTTP/2.0 upgrade with '101' response.
     * 
     * @param request the request that initiated the upgrade
     * @param priority the priority of the stream
     *
     * @return a new {@link Http2Stream} for this request
     *
     * @throws org.glassfish.grizzly.http2.Http2StreamException if an error occurs opening the stream.
     */
    public Http2Stream openUpgradeStream(final HttpRequestPacket request,
            final int priority)
            throws Http2StreamException {
        
        // we already sent headers - so the initial state is OPEN
        final Http2Stream stream = newUpgradeStream(request, priority,
                Http2StreamState.OPEN);
        
        synchronized(sessionLock) {
            if (isClosed()) {
                throw new Http2StreamException(Http2Stream.UPGRADE_STREAM_ID,
                        ErrorCode.REFUSED_STREAM, "Session is closed");
            }
            
            streamsMap.put(Http2Stream.UPGRADE_STREAM_ID, stream);
            lastLocalStreamId = Http2Stream.UPGRADE_STREAM_ID;
        }
        
        return stream;
    }
    
    /**
     * Initializes HTTP2 communication (if not initialized before) by forming
     * HTTP2 connection and stream {@link FilterChain}s.
     * 
     * @param context the current {@link FilterChainContext}
     * @param isUpStream flag denoting the direction of the chain
     */
    boolean setupFilterChains(final FilterChainContext context,
            final boolean isUpStream) {

        //noinspection Duplicates
        if (http2ConnectionChain == null) {
            synchronized(this) {
                if (http2ConnectionChain == null) {
                    if (isUpStream) {
                        http2StreamChain = (FilterChain) context.getFilterChain().subList(
                                context.getFilterIdx(), context.getEndIdx());

                        http2ConnectionChain = (FilterChain) context.getFilterChain().subList(
                                context.getStartIdx(), context.getFilterIdx());
                    } else {
                        http2StreamChain = (FilterChain) context.getFilterChain().subList(
                                context.getFilterIdx(), context.getFilterChain().size());

                        http2ConnectionChain = (FilterChain) context.getFilterChain().subList(
                                context.getEndIdx() + 1, context.getFilterIdx());
                    }
                    
                    return true;
                }
            }
        }
        
        return false;
    }
    
    FilterChain getHttp2StreamChain() {
        return http2StreamChain;
    }
    
    FilterChain getHttp2ConnectionChain() {
        return http2ConnectionChain;
    }
    
    /**
     * Method is called, when the session closing is initiated locally.
     */
    private int close() {
        synchronized (sessionLock) {
            if (isClosed()) {
                return -1;
            }
            
            closeFlag = CloseType.LOCALLY;
            return lastPeerStreamId > 0 ? lastPeerStreamId : 0;
        }
    }
    
    /**
     * Method is called, when GOAWAY is initiated by peer
     */
    void setGoAwayByPeer(final int lastGoodStreamId) {
        synchronized (sessionLock) {
            // @TODO Notify pending SYNC_STREAMS if streams were aborted
            closeFlag = CloseType.REMOTELY;
        }
    }
    
    Object getSessionLock() {
        return sessionLock;
    }

    /**
     * Called from {@link Http2Stream} once stream is completely closed.
     */
    void deregisterStream(final Http2Stream htt2Stream) {
        streamsMap.remove(htt2Stream.getId());
        
        final boolean isCloseSession;
        synchronized (sessionLock) {
            // If we're in GOAWAY state and there are no streams left - close this session
            isCloseSession = isClosed() && streamsMap.isEmpty();
        }
        
        if (isCloseSession) {
            closeSession();
        }
    }

    /**
     * Close the session
     */
    private void closeSession() {
        connection.closeSilently();
        outputSink.close();
    }

    private boolean isClosed() {
        return closeFlag != null;
    }

    void sendMessageUpstreamWithParseNotify(final Http2Stream stream,
                                            final HttpContent httpContent) {
        final FilterChainContext upstreamContext =
                        http2StreamChain.obtainFilterChainContext(connection, stream);

        final HttpContext httpContext = httpContent.getHttpHeader()
                .getProcessingState().getHttpContext();
        httpContext.attach(upstreamContext);
        
        handlerFilter.onHttpContentParsed(httpContent, upstreamContext);
        final HttpHeader header = httpContent.getHttpHeader();
        if (httpContent.isLast()) {
            handlerFilter.onHttpPacketParsed(header, upstreamContext);
        }

        if (header.isSkipRemainder()) {
            return;
        }

        sendMessageUpstream(stream, httpContent, upstreamContext);
    }

    void sendMessageUpstream(final Http2Stream stream,
                             final HttpPacket message) {
        final FilterChainContext upstreamContext =
                http2StreamChain.obtainFilterChainContext(connection, stream);
        
        final HttpContext httpContext = message.getHttpHeader()
                .getProcessingState().getHttpContext();
        httpContext.attach(upstreamContext);
        
        sendMessageUpstream(stream, message, upstreamContext);
    }

    @SuppressWarnings("Duplicates")
    private void sendMessageUpstream(final Http2Stream stream,
                                     final HttpPacket message,
                                     final FilterChainContext upstreamContext) {

        upstreamContext.getInternalContext().setIoEvent(IOEvent.READ);
        upstreamContext.getInternalContext().addLifeCycleListener(
                new IOEventLifeCycleListener.Adapter() {
                    @Override
                    public void onReregister(final Context context) throws IOException {
                        stream.inputBuffer.onReadEventComplete();
                    }

                    @Override
                    public void onComplete(Context context, Object data) throws IOException {
                        stream.inputBuffer.onReadEventComplete();
                    }
                });

        upstreamContext.setMessage(message);
        upstreamContext.setAddressHolder(addressHolder);

        ProcessorExecutor.execute(upstreamContext.getInternalContext());
    }

    protected SettingsFrameBuilder prepareSettings() {
        return prepareSettings(null);
    }

    protected SettingsFrameBuilder prepareSettings(
            SettingsFrameBuilder builder) {
        
        if (builder == null) {
            builder = SettingsFrame.builder();
        }
        
        if (getLocalMaxConcurrentStreams() != getDefaultMaxConcurrentStreams()) {
            builder.setting(SETTINGS_MAX_CONCURRENT_STREAMS, getLocalMaxConcurrentStreams());
        }

        if (getLocalStreamWindowSize() != getDefaultStreamWindowSize()) {
            builder.setting(SETTINGS_INITIAL_WINDOW_SIZE, getLocalStreamWindowSize());
        }

        builder.setting(SETTINGS_MAX_HEADER_LIST_SIZE, getMaxHeaderListSize());
        
        return builder;
    }

    /**
     * Acknowledge that certain amount of data has been read.
     * Depending on the total amount of un-acknowledge data the HTTP2 connection
     * can decide to send a window_update message to the peer.
     * 
     * @param sz size, in bytes, of the data being acknowledged
     */
    void ackConsumedData(final int sz) {
        ackConsumedData(null, sz);
    }

    /**
     * Acknowledge that certain amount of data has been read.
     * Depending on the total amount of un-acknowledge data the HTTP2 connection
     * can decide to send a window_update message to the peer.
     * Unlike the {@link #ackConsumedData(int)}, this method also requests an
     * HTTP2 stream to acknowledge consumed data to the peer.
     * 
     * @param stream the stream that data is being ack'd on.
     * @param sz size, in bytes, of the data being acknowledged
     */
    void ackConsumedData(final Http2Stream stream, final int sz) {
        final int currentUnackedBytes
                = unackedReadBytes.addAndGet(sz);
        
        if (isPrefaceSent) {
            // ACK HTTP2 connection flow control
            final int windowSize = getLocalConnectionWindowSize();

            // if not forced - send update window message only in case currentUnackedBytes > windowSize / 2
            if (currentUnackedBytes > (windowSize / 3)
                    && unackedReadBytes.compareAndSet(currentUnackedBytes, 0)) {

                sendWindowUpdate(0, currentUnackedBytes);
            }
            
            if (stream != null) {
                // ACK HTTP2 stream flow control
                final int streamUnackedBytes
                        = Http2Stream.unackedReadBytesUpdater.addAndGet(stream, sz);
                final int streamWindowSize = stream.getLocalWindowSize();

                // send update window message only in case currentUnackedBytes > windowSize / 2
                if (streamUnackedBytes > 0
                        && (streamUnackedBytes > (streamWindowSize / 2))
                        && Http2Stream.unackedReadBytesUpdater.compareAndSet(stream, streamUnackedBytes, 0)) {

                    sendWindowUpdate(stream.getId(), streamUnackedBytes);
                }
            }
        }
    }

    public final class StreamBuilder {

        private StreamBuilder() {
        }
        
        public RegularStreamBuilder regular() {
            return new RegularStreamBuilder();
        }
        
        public PushStreamBuilder push() {
            return new PushStreamBuilder();
        }
    }
    
    public final class PushStreamBuilder extends HttpHeader.Builder<PushStreamBuilder> {

        private int associatedToStreamId;
        private int priority;
        private boolean isFin;
        private String uri;
        private String query;
        
        /**
         * Set the request URI.
         *
         * @param uri the request URI.
         * @return the current <code>Builder</code>
         */
        public PushStreamBuilder uri(final String uri) {
            this.uri = uri;
            return this;
        }

        /**
         * Set the <code>query</code> portion of the request URI.
         *
         * @param query the query String
         *
         * @return the current <code>Builder</code>
         */
        public PushStreamBuilder query(final String query) {
            this.query = query;
            return this;
        }

        /**
         * Set the <code>associatedToStreamId</code> parameter of a {@link Http2Stream}.
         *
         * @param associatedToStreamId the associatedToStreamId
         *
         * @return the current <code>Builder</code>
         */
        public PushStreamBuilder associatedToStreamId(final int associatedToStreamId) {
            this.associatedToStreamId = associatedToStreamId;
            return this;
        }

        /**
         * Set the <code>priority</code> parameter of a {@link Http2Stream}.
         *
         * @param priority the priority
         *
         * @return the current <code>Builder</code>
         */
        public PushStreamBuilder priority(final int priority) {
            this.priority = priority;
            return this;
        }
        
        /**
         * Sets the <code>fin</code> flag of a {@link Http2Stream}.
         * 
         * @param fin the new value of the <code>fin</code> flag.
         * 
         * @return the current <code>Builder</code>
         */
        public PushStreamBuilder fin(final boolean fin) {
            this.isFin = fin;
            return this;
        }
        
        /**
         * Build the <tt>HttpRequestPacket</tt> message.
         *
         * @return <tt>HttpRequestPacket</tt>
         * @throws org.glassfish.grizzly.http2.Http2StreamException if an error occurs opening the stream
         */
        @SuppressWarnings("unchecked")
        public final Http2Stream open() throws Http2StreamException {
            final HttpRequestPacket request = build();
            newClientStreamLock.lock();
            try {
                final Http2Stream stream = openStream(
                        request,
                        getNextLocalStreamId(),
                        associatedToStreamId, false, priority,
                        Http2StreamState.IDLE);
                
                
                connection.write(request.getResponse());
                
                return stream;
            } finally {
                newClientStreamLock.unlock();
            }
        }

        @Override
        public HttpRequestPacket build() {
            Http2Request request = (Http2Request) super.build();
            if (uri != null) {
                request.setRequestURI(uri);
            }
            if (query != null) {
                request.setQueryString(query);
            }
            
            request.setExpectContent(!isFin);
            
            return request;
        }

        @Override
        protected HttpHeader create() {
            Http2Request request = Http2Request.create();
            HttpResponsePacket packet = request.getResponse();
            packet.setSecure(true);
            return request;
        }
    }
    
    public final class RegularStreamBuilder extends HttpHeader.Builder<RegularStreamBuilder> {
        private int priority;
        private boolean isFin;
        private Method method;
        private String methodString;
        private String uri;
        private String query;
        private String host;
        

        /**
         * Set the HTTP request method.
         * @param method the HTTP request method..
         * @return the current <code>Builder</code>
         */
        public RegularStreamBuilder method(final Method method) {
            this.method = method;
            methodString = null;
            return this;
        }

        /**
         * Set the HTTP request method.
         * @param methodString the HTTP request method. Format is "GET|POST...".
         * @return the current <code>Builder</code>
         */
        public RegularStreamBuilder method(final String methodString) {
            this.methodString = methodString;
            method = null;
            return this;
        }

        /**
         * Set the request URI.
         *
         * @param uri the request URI.
         * @return the current <code>Builder</code>
         */
        public RegularStreamBuilder uri(final String uri) {
            this.uri = uri;
            return this;
        }

        /**
         * Set the <code>query</code> portion of the request URI.
         *
         * @param query the query String
         * @return the current <code>Builder</code>
         */
        public RegularStreamBuilder query(final String query) {
            this.query = query;
            return this;
        }

        /**
         * Set the value of the Host header.
         *
         * @param host the value of the Host header.
         * @return the current <code>Builder</code>
         */
        public RegularStreamBuilder host(final String host) {
            this.host = host;
            return this;
        }

        /**
         * Set the <code>priority</code> parameter of a {@link Http2Stream}.
         *
         * @param priority the priority
         * @return the current <code>Builder</code>
         */
        public RegularStreamBuilder priority(final int priority) {
            this.priority = priority;
            return this;
        }

        /**
         * Sets the <code>fin</code> flag of a {@link Http2Stream}.
         * 
         * @param fin the initial value of the <code>fin</code> flag.
         * 
         * @return the current <code>Builder</code>
         */
        public RegularStreamBuilder fin(final boolean fin) {
            this.isFin = fin;
            return this;
        }
        
        /**
         * Build the <tt>HttpRequestPacket</tt> message.
         *
         * @return <tt>HttpRequestPacket</tt>
         * @throws org.glassfish.grizzly.http2.Http2StreamException if an error occurs opening the stream.
         */
        @SuppressWarnings("unchecked")
        public final Http2Stream open() throws Http2StreamException {
            HttpRequestPacket request = build();
            newClientStreamLock.lock();
            try {
                final Http2Stream stream = openStream(
                        request,
                        getNextLocalStreamId(),
                        0, false, priority, Http2StreamState.IDLE);
                
                
                connection.write(request);
                
                return stream;
            } finally {
                newClientStreamLock.unlock();
            }
        }

        @Override
        public HttpRequestPacket build() {
            Http2Request request = (Http2Request) super.build();
            if (method != null) {
                request.setMethod(method);
            }
            if (methodString != null) {
                request.setMethod(methodString);
            }
            if (uri != null) {
                request.setRequestURI(uri);
            }
            if (query != null) {
                request.setQueryString(query);
            }
            if (host != null) {
                request.addHeader(Header.Host, host);
            }
            
            request.setExpectContent(!isFin);
            
            return request;
        }

        @Override
        protected HttpHeader create() {
            Http2Request request = Http2Request.create();
            request.setSecure(true);
            return request;
        }
    }
    
    private final class ConnectionCloseListener implements GenericCloseListener {

        @Override
        public void onClosed(final Closeable closeable, final CloseType type)
                throws IOException {
            
            final boolean isClosing;
            synchronized (sessionLock) {
                isClosing = !isClosed();
                if (isClosing) {
                    closeFlag = type;
                }
            }
            
            if (isClosing) {
                for (Http2Stream stream : streamsMap.values()) {
                    stream.closedRemotely();
                }
            }
        }
    }
}
