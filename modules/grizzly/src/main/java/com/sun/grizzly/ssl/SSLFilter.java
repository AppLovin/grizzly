/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2010 Sun Microsystems, Inc. All rights reserved.
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
package com.sun.grizzly.ssl;

import com.sun.grizzly.Buffer;
import com.sun.grizzly.CompletionHandler;
import com.sun.grizzly.filterchain.FilterChainContext;
import com.sun.grizzly.filterchain.NextAction;
import java.io.IOException;
import java.util.logging.Filter;
import java.util.logging.Logger;
import com.sun.grizzly.Connection;
import com.sun.grizzly.Grizzly;
import com.sun.grizzly.IOEvent;
import com.sun.grizzly.attributes.Attribute;
import com.sun.grizzly.filterchain.AbstractCodecFilter;
import com.sun.grizzly.memory.BufferUtils;
import com.sun.grizzly.memory.MemoryManager;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

/**
 * SSL {@link Filter} to operate with SSL encrypted data.
 *
 * @author Alexey Stashok
 */
public final class SSLFilter extends AbstractCodecFilter<Buffer, Buffer> {

    private static final byte CHANGE_CIPHER_SPECT_CONTENT_TYPE = 20;
    private static final byte ALERT_CONTENT_TYPE = 21;
    private static final byte HANDSHAKE_CONTENT_TYPE = 22;
    private static final byte APPLICATION_DATA_CONTENT_TYPE = 23;
    private static final int SSLV3_RECORD_HEADER_SIZE = 5; // SSLv3 record header
    private static final int SSL20_HELLO_VERSION = 0x0002;
    private static final int MIN_VERSION = 0x0300;
    private static final int MAX_MAJOR_VERSION = 0x03;

    private final Attribute<CompletionHandler> handshakeCompletionHandlerAttr;
    private Logger logger = Grizzly.logger(SSLFilter.class);
    private final SSLEngineConfigurator serverSSLEngineConfigurator;
    private final SSLEngineConfigurator clientSSLEngineConfigurator;

    public SSLFilter() {
        this(null, null);
    }

    /**
     * Build <tt>SSLFilter</tt> with the given {@link SSLEngineConfigurator}.
     *
     * @param serverSSLEngineConfigurator SSLEngine configurator for server side connections
     * @param clientSSLEngineConfigurator SSLEngine configurator for client side connections
     */
    public SSLFilter(SSLEngineConfigurator serverSSLEngineConfigurator,
            SSLEngineConfigurator clientSSLEngineConfigurator) {
        super(new SSLDecoderTransformer(), new SSLEncoderTransformer());

        if (serverSSLEngineConfigurator == null) {
            serverSSLEngineConfigurator = new SSLEngineConfigurator(
                    SSLContextConfigurator.DEFAULT_CONFIG.createSSLContext(),
                    false, false, false);
        }

        if (clientSSLEngineConfigurator == null) {
            clientSSLEngineConfigurator = new SSLEngineConfigurator(
                    SSLContextConfigurator.DEFAULT_CONFIG.createSSLContext(),
                    true, false, false);
        }

        this.serverSSLEngineConfigurator = serverSSLEngineConfigurator;
        this.clientSSLEngineConfigurator = clientSSLEngineConfigurator;
        handshakeCompletionHandlerAttr =
                Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(
                "SSLFilter-HandshakeCompletionHandlerAttr");
    }

    @Override
    public NextAction handleRead(final FilterChainContext ctx)
            throws IOException {
        final Connection connection = ctx.getConnection();
        SSLEngine sslEngine = SSLUtils.getSSLEngine(connection);

        if (sslEngine != null && !SSLUtils.isHandshaking(sslEngine)) {
            return super.handleRead(ctx);
        } else {
            if (sslEngine == null) {
                sslEngine = serverSSLEngineConfigurator.createSSLEngine();
                sslEngine.beginHandshake();
                SSLUtils.setSSLEngine(connection, sslEngine);
            }

            Buffer buffer = (Buffer) ctx.getMessage();

            buffer = doHandshakeStep(sslEngine, ctx);

            final boolean isHandshaking = SSLUtils.isHandshaking(sslEngine);
            if (!isHandshaking) {
                notifyHandshakeCompleted(connection, sslEngine);

                if (buffer.hasRemaining()) {
                    ctx.setMessage(buffer);
                    return super.handleRead(ctx);
                }
            }

            return ctx.getStopAction(buffer.hasRemaining() ? buffer : null);
        }
    }

    @Override
    public NextAction handleWrite(FilterChainContext ctx) throws IOException {
        final Connection connection = ctx.getConnection();
        SSLEngine sslEngine = SSLUtils.getSSLEngine(connection);
        if (sslEngine != null && !SSLUtils.isHandshaking(sslEngine)) {
            return super.handleWrite(ctx);
        } else {
            throw new IllegalStateException("Handshake is not completed!");
        }
    }

    public void handshake(final Connection connection,
            final CompletionHandler<SSLEngine> completionHandler)
            throws IOException {
        handshake(connection, completionHandler, null,
                clientSSLEngineConfigurator);
    }

    public void handshake(final Connection connection,
            final CompletionHandler<SSLEngine> completionHandler,
            final Object dstAddress)
            throws IOException {
        handshake(connection, completionHandler, dstAddress,
                clientSSLEngineConfigurator);
    }

    public void handshake(final Connection connection,
            final CompletionHandler<SSLEngine> completionHandler,
            final Object dstAddress,
            final SSLEngineConfigurator sslEngineConfigurator)
            throws IOException {

        SSLEngine sslEngine = SSLUtils.getSSLEngine(connection);

        if (sslEngine == null) {
            sslEngine = sslEngineConfigurator.createSSLEngine();
            sslEngine.beginHandshake();
            SSLUtils.setSSLEngine(connection, sslEngine);
        } else {
            sslEngineConfigurator.configure(sslEngine);
            sslEngine.beginHandshake();
        }

        if (completionHandler != null) {
            handshakeCompletionHandlerAttr.set(connection, completionHandler);
        }

        final FilterChainContext ctx = createContext(connection, IOEvent.WRITE,
                null, completionHandler);

        doHandshakeStep(sslEngine, ctx);
    }

    @Override
    public NextAction handleClose(FilterChainContext ctx) throws IOException {
        final Connection connection = ctx.getConnection();
        final CompletionHandler<SSLEngine> completionHandler =
                handshakeCompletionHandlerAttr.remove(connection);
        if (completionHandler != null) {
            completionHandler.failed(new java.io.EOFException());
        }
        
        return ctx.getInvokeAction();
    }

    protected Buffer doHandshakeStep(final SSLEngine sslEngine,
            FilterChainContext context) throws SSLException, IOException {

        final Connection connection = context.getConnection();
        final Object dstAddress = context.getAddress();
        Buffer inputBuffer = (Buffer) context.getMessage();

        final boolean isLoggingFinest = logger.isLoggable(Level.FINEST);

        final SSLSession sslSession = sslEngine.getSession();
        final int appBufferSize = sslSession.getApplicationBufferSize();

        HandshakeStatus handshakeStatus = sslEngine.getHandshakeStatus();

        final MemoryManager memoryManager =
                connection.getTransport().getMemoryManager();

        while (true) {

            if (isLoggingFinest) {
                logger.finest("Loop Engine: " + sslEngine
                        + " handshakeStatus=" + sslEngine.getHandshakeStatus());
            }

            switch (handshakeStatus) {
                case NEED_UNWRAP: {

                    if (isLoggingFinest) {
                        logger.finest("NEED_UNWRAP Engine: " + sslEngine);
                    }

                    if (inputBuffer == null || !inputBuffer.hasRemaining()) {
                        return inputBuffer;
                    }

                    final int expectedLength = getSSLPacketSize(inputBuffer);
                    if (expectedLength == -1 ||
                            inputBuffer.remaining() < expectedLength) {
                        return inputBuffer;
                    }

                    final SSLEngineResult sslEngineResult;

                    if (!inputBuffer.isComposite()) {
                        final ByteBuffer inputBB = inputBuffer.toByteBuffer();

                        final Buffer outputBuffer = memoryManager.allocate(
                                appBufferSize);

                        sslEngineResult = sslEngine.unwrap(inputBB,
                                outputBuffer.toByteBuffer());
                        outputBuffer.dispose();

                        if (inputBuffer.hasRemaining()) {
                            // shift remainder to the buffer position 0
                            inputBuffer.compact();
                            // trim
                            inputBuffer.trim();
                        }

                    } else {
                        final int pos = inputBuffer.position();
                        final ByteBuffer inputByteBuffer =
                                inputBuffer.toByteBuffer(pos,
                                pos + expectedLength);

                        final Buffer outputBuffer = memoryManager.allocate(
                                appBufferSize);

                        sslEngineResult = sslEngine.unwrap(inputByteBuffer,
                                outputBuffer.toByteBuffer());

                        inputBuffer.position(pos + sslEngineResult.bytesConsumed());

                        outputBuffer.dispose();
                    }

                    final Status status = sslEngineResult.getStatus();

                    if (status == Status.BUFFER_UNDERFLOW) {
                        return inputBuffer;
                    } else if (status == Status.BUFFER_OVERFLOW) {
                        throw new SSLException("Buffer overflow");
                    }

                    handshakeStatus = sslEngine.getHandshakeStatus();
                    break;
                }

                case NEED_WRAP: {
                    if (isLoggingFinest) {
                        logger.finest("NEED_WRAP Engine: " + sslEngine);
                    }

                    final Buffer buffer = memoryManager.allocate(
                            sslEngine.getSession().getPacketBufferSize());
                    buffer.allowBufferDispose(true);

                    try {
                        final SSLEngineResult result = sslEngine.wrap(
                                BufferUtils.EMPTY_BYTE_BUFFER, buffer.toByteBuffer());

                        buffer.trim();

                        context.write(dstAddress, buffer, null);

                        handshakeStatus = sslEngine.getHandshakeStatus();
                    } catch (SSLException e) {
                        buffer.dispose();
                        throw e;
                    } catch (IOException e) {
                        buffer.dispose();
                        throw e;
                    } catch (Exception e) {
                        e.printStackTrace();
                        buffer.dispose();
                        throw new IOException("Unexpected exception", e);
                    }

                    break;
                }

                case NEED_TASK: {
                    if (isLoggingFinest) {
                        logger.finest("NEED_TASK Engine: " + sslEngine);
                    }
                    SSLUtils.executeDelegatedTask(sslEngine);
                    handshakeStatus = sslEngine.getHandshakeStatus();
                    break;
                }

                case FINISHED:
                case NOT_HANDSHAKING: {
                    return inputBuffer;
                }
            }

            if (handshakeStatus == HandshakeStatus.FINISHED) {
                return inputBuffer;
            }
        }
    }

    private void notifyHandshakeCompleted(final Connection connection,
            final SSLEngine sslEngine) {

        final CompletionHandler<SSLEngine> completionHandler =
                handshakeCompletionHandlerAttr.get(connection);
        if (completionHandler != null) {
            completionHandler.completed(sslEngine);
        }
    }

    /**
     * {@inheritDoc}
     */
//    public SSLSupport createSSLSupport(Connection connection) {
//        return new SSLSupportImpl(connection,
//                sslEngineConfigurator, sslHandshaker);
//
//    }

    /*
     * Check if there is enough inbound data in the ByteBuffer
     * to make a inbound packet.  Look for both SSLv2 and SSLv3.
     *
     * @return -1 if there are not enough bytes to tell (small header),
     */
    protected static int getSSLPacketSize(Buffer buf) throws SSLException {

        /*
         * SSLv2 length field is in bytes 0/1
         * SSLv3/TLS length field is in bytes 3/4
         */
        if (buf.remaining() < 5) {
            return -1;
        }

        int pos = buf.position();
        byte byteZero = buf.get(pos);

        int len = 0;

        /*
         * If we have already verified previous packets, we can
         * ignore the verifications steps, and jump right to the
         * determination.  Otherwise, try one last hueristic to
         * see if it's SSL/TLS.
         */
        if (byteZero >= CHANGE_CIPHER_SPECT_CONTENT_TYPE
                && byteZero <= APPLICATION_DATA_CONTENT_TYPE) {
            /*
             * Last sanity check that it's not a wild record
             */
            final byte major = buf.get(pos + 1);
            final byte minor = buf.get(pos + 2);
            final int v = (major << 8) | minor;

            // Check if too old (currently not possible)
            // or if the major version does not match.
            // The actual version negotiation is in the handshaker classes
            if ((v < MIN_VERSION)
                    || (major > MAX_MAJOR_VERSION)) {
                throw new SSLException("Unsupported record version major="
                        + major + " minor=" + minor);
            }

            /*
             * One of the SSLv3/TLS message types.
             */
            len = ((buf.get(pos + 3) & 0xff) << 8)
                    + (buf.get(pos + 4) & 0xff) + SSLV3_RECORD_HEADER_SIZE;

        } else {
            /*
             * Must be SSLv2 or something unknown.
             * Check if it's short (2 bytes) or
             * long (3) header.
             *
             * Internals can warn about unsupported SSLv2
             */
            boolean isShort = ((byteZero & 0x80) != 0);

            if (isShort
                    && ((buf.get(pos + 2) == 1) || buf.get(pos + 2) == 4)) {

                final byte major = buf.get(pos + 3);
                final byte minor = buf.get(pos + 4);
                final int v = (major << 8) | minor;

                // Check if too old (currently not possible)
                // or if the major version does not match.
                // The actual version negotiation is in the handshaker classes
                if ((v < MIN_VERSION)
                        || (major > MAX_MAJOR_VERSION)) {

                    // if it's not SSLv2, we're out of here.
                    if (v != SSL20_HELLO_VERSION) {
                        throw new SSLException("Unsupported record version major="
                                + major + " minor=" + minor);
                    }
                }

                /*
                 * Client or Server Hello
                 */
                int mask = (isShort ? 0x7f : 0x3f);
                len = ((byteZero & mask) << 8)
                        + (buf.get(pos + 1) & 0xff) + (isShort ? 2 : 3);

            } else {
                // Gobblygook!
                throw new SSLException(
                        "Unrecognized SSL message, plaintext connection?");
            }
        }

        return len;
    }
}
