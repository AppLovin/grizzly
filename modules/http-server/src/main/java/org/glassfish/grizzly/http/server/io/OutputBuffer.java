/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2012 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http.server.io;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.FileTransfer;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.Writer.Reentrant;
import org.glassfish.grizzly.asyncqueue.MessageCloner;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.HttpServerFilter;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.server.util.MimeType;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.CompositeBuffer;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.utils.Charsets;
import org.glassfish.grizzly.utils.Exceptions;
import org.glassfish.grizzly.utils.Futures;

/**
 * Abstraction exposing both byte and character methods to write content
 * to the HTTP messaging system in Grizzly.
 */
public class OutputBuffer {
    
    private static final Logger LOGGER = Grizzly.logger(OutputBuffer.class);

    private static final int MAX_COPY_BUFFER_SIZE = 1024 * 8;

    private static final int DEFAULT_BUFFER_SIZE = 1024 * 8;
    
    /**
     * Flag indicating whether or not async operations are being used on the
     * input streams.
     */
    private final static boolean IS_BLOCKING =
            Boolean.getBoolean(OutputBuffer.class.getName() + ".isBlocking");

    private HttpResponsePacket response;

    private FilterChainContext ctx;

    private CompositeBuffer compositeBuffer;

    private Buffer currentBuffer;

    // Buffer, which is used for write(byte[] ...) scenarios to try to avoid
    // byte arrays copying
    private final TemporaryHeapBuffer temporaryWriteBuffer =
            new TemporaryHeapBuffer();
    // The cloner, which will be responsible for cloning temporaryWriteBuffer,
    // if it's not possible to write its content in this thread
    private final ByteArrayCloner cloner = new ByteArrayCloner();

    /**
     * This char[] array will be used when a user calls {@link #write(String)}  or
     * {@link #write(String, int, int)}.  In these two cases, {@link String#getChars(int, int, char[], int)}
     * will be used to copy the characters from the String into this array which
     * will then be wrapped and passed for encoding.  This is done as the copy+wrap
     * is cheaper than just wrapping the String.
     */
    private char[] stringCopyBuffer;
    
    private final List<LifeCycleListener> lifeCycleListeners =
            new ArrayList<LifeCycleListener>(2);
    
    private boolean committed;

    private boolean finished;

    private boolean closed;

    private CharsetEncoder encoder;

    private final Map<String, CharsetEncoder> encoders =
            new HashMap<String, CharsetEncoder>();
    
    private final CharBuffer charBuf = CharBuffer.allocate(1);

    private MemoryManager memoryManager;

    private WriteHandler handler;

    private final AtomicReference<Throwable> asyncError = new AtomicReference<Throwable>();

    private InternalWriteHandler asyncWriteHandler;

    private boolean fileTransferRequested;

    private int bufferSize = DEFAULT_BUFFER_SIZE;
    
    private boolean sendfileEnabled;
    
    private Response serverResponse;
    
    private final CompletionHandler<WriteResult> onAsyncErrorCompletionHandler =
            new OnErrorCompletionHandler();

    private final CompletionHandler<WriteResult> onWritePossibleCompletionHandler =
            new OnWritePossibleCompletionHandler();

    private boolean isNonBlockingWriteGuaranteed;
    private boolean isLastWriteNonBlocking;
    
    
    // ---------------------------------------------------------- Public Methods


    public void initialize(final Response response,
                           final FilterChainContext ctx) {

        this.serverResponse = response;
        this.response = response.getResponse();
        sendfileEnabled = response.isSendFileEnabled();
        this.ctx = ctx;
        memoryManager = ctx.getMemoryManager();
    }

    public void prepareCharacterEncoder() {
        getEncoder();
    }

    public int getBufferSize() {
        return bufferSize;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public void registerLifeCycleListener(final LifeCycleListener listener) {
        lifeCycleListeners.add(listener);
    }
    
    @SuppressWarnings({"UnusedDeclaration"})
    public boolean removeLifeCycleListener(final LifeCycleListener listener) {
        return lifeCycleListeners.remove(listener);
    }
    
    public void setBufferSize(final int bufferSize) {
        if (!committed && currentBuffer == null) {
            this.bufferSize = bufferSize;  
        }
    }
    
    /**
     * Reset current response.
     *
     * @throws IllegalStateException if the response has already been committed
     */
    public void reset() {

        if (committed)
            throw new IllegalStateException(/*FIXME:Put an error message*/);

        compositeBuffer = null;
        
        if (currentBuffer != null) {
            currentBuffer.clear();
        }

    }


    /**
     * @return <code>true</code> if this <tt>OutputBuffer</tt> is closed, otherwise
     *  returns <code>false</code>.
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public boolean isClosed() {
        return closed;
    }
    
    /**
     * Get the number of bytes buffered on OutputBuffer and ready to be sent.
     * 
     * @return the number of bytes buffered on OutputBuffer and ready to be sent.
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public int getBufferedDataSize() {
        int size = 0;
        if (compositeBuffer != null) {
            size += compositeBuffer.remaining();
        }
        
        if (currentBuffer != null) {
            size += currentBuffer.position();
        }
        
        return size;
    }
    
    
    /**
     * Recycle the output buffer. This should be called when closing the
     * connection.
     */
    public void recycle() {

        response = null;

        if (compositeBuffer != null) {
            compositeBuffer.dispose();
            compositeBuffer = null;
        }
        
        if (currentBuffer != null) {
            currentBuffer.dispose();
            currentBuffer = null;
        }

        temporaryWriteBuffer.recycle();

        charBuf.position(0);

        fileTransferRequested = false;
        encoder = null;
        ctx = null;
        memoryManager = null;
        resetHandler();
        isNonBlockingWriteGuaranteed = false;
        isLastWriteNonBlocking = false;
        asyncError.set(null);
        asyncWriteHandler = null;

        committed = false;
        finished = false;
        closed = false;

        lifeCycleListeners.clear();
    }


    public void endRequest()
        throws IOException {

        handleAsyncErrors();

        if (finished) {
            return;
        }

        final InternalWriteHandler asyncWriteQueueHandlerLocal = asyncWriteHandler;
        if (asyncWriteQueueHandlerLocal != null) {
            asyncWriteHandler = null;
            asyncWriteQueueHandlerLocal.done = true;
        }

        if (!closed) {
            close();
        }
        
        if (ctx != null) {
            ctx.notifyDownstream(HttpServerFilter.RESPONSE_COMPLETE_EVENT);
        }

        finished = true;

    }


    /**
     * Acknowledge a HTTP <code>Expect</code> header.  The response status
     * code and reason phrase should be set before invoking this method.
     *
     * @throws IOException if an error occurs writing the acknowledgment.
     */
    public void acknowledge() throws IOException {

        ctx.write(response, IS_BLOCKING);
        
    }


    // ---------------------------------------------------- Writer-Based Methods


    public void write(char cbuf[], int off, int len) throws IOException {

        handleAsyncErrors();

        if (closed || len == 0) {
            return;
        }

        updateNonBlockingStatus();
        flushCharsToBuf(CharBuffer.wrap(cbuf, off, len));

    }

    public void writeChar(int c) throws IOException {

        handleAsyncErrors();

        if (closed) {
            return;
        }

        updateNonBlockingStatus();
        
        charBuf.position(0);
        charBuf.put(0, (char) c);
        flushCharsToBuf(charBuf);
    }


    public void write(final char cbuf[]) throws IOException {
        write(cbuf, 0, cbuf.length);
    }


    public void write(final String str) throws IOException {
        write(str, 0, str.length());
    }


    public void write(final String str, final int off, final int len) throws IOException {

        handleAsyncErrors();

        if (closed || len == 0) {
            return;
        }

        updateNonBlockingStatus();
        
        int offLocal = off;
        int lenLocal = len;

        while (lenLocal > 0) {
            if (lenLocal > MAX_COPY_BUFFER_SIZE) {
                copyStringCharsToInternalBuffer(str, offLocal, MAX_COPY_BUFFER_SIZE);
                flushCharsToBuf(CharBuffer.wrap(stringCopyBuffer, 0, MAX_COPY_BUFFER_SIZE));
                offLocal += MAX_COPY_BUFFER_SIZE;
                lenLocal -= MAX_COPY_BUFFER_SIZE;
            } else {
                copyStringCharsToInternalBuffer(str, offLocal, lenLocal);
                flushCharsToBuf(CharBuffer.wrap(stringCopyBuffer, 0, lenLocal));
                offLocal += lenLocal;
                lenLocal = 0;
            }
        }
    }


    // ---------------------------------------------- OutputStream-Based Methods

    public void writeByte(final int b) throws IOException {

        handleAsyncErrors();
        if (closed) {
            return;
        }

        updateNonBlockingStatus();
        
        checkCurrentBuffer();
        
        if (currentBuffer.hasRemaining()) {
            currentBuffer.put((byte) b);
        } else {
            //flush();
            finishCurrentBuffer();
            checkCurrentBuffer();
            currentBuffer.put((byte) b);
        }

    }


    public void write(final byte b[]) throws IOException {
        write(b, 0, b.length);
    }

    /**
     * <p>
     * Calls <code>write(file, 0, file.length())</code>.
     * </p>
     * 
     * @param file the {@link File} to transfer.
     * @param handler {@link CompletionHandler} that will be notified
     *                of the transfer progress/completion or failure.
     *             
     * @throws IOException if an error occurs during the transfer
     * @throws IllegalArgumentException if <code>file</code> is null
     * 
     * @see #sendfile(java.io.File, long, long, org.glassfish.grizzly.CompletionHandler)
     * 
     * @since 2.2   
     */
    public void sendfile(final File file, final CompletionHandler<WriteResult> handler) {
        if (file == null) {
            throw new IllegalArgumentException("Argument 'file' cannot be null");
        }
        sendfile(file, 0, file.length(), handler);
    }

    /**
     * <p>
     * Will use {@link java.nio.channels.FileChannel#transferTo(long, long, java.nio.channels.WritableByteChannel)}
     * to send file to the remote endpoint.  Note that all headers necessary
     * for the file transfer must be set prior to invoking this method as this will
     * case the HTTP header to be flushed to the client prior to sending the file
     * content. This should also be the last call to write any content to the remote
     * endpoint.
     * </p>
     * 
     * <p>
     * It's required that the response be suspended when using this functionality.
     * It will be assumed that if the response wasn't suspended when this method
     * is called, that it's desired that this method manages the suspend/resume cycle.
     * </p>
     *
     * @param file the {@link File} to transfer.
     * @param offset the starting offset within the File
     * @param length the total number of bytes to transfer
     * @param handler {@link CompletionHandler} that will be notified
     *                of the transfer progress/completion or failure.
     *               
     * @throws IOException              if an error occurs during the transfer
     * @throws IOException              if an I/O error occurs
     * @throws IllegalArgumentException if the response has already been committed
     *                                  at the time this method was invoked.
     * @throws IllegalStateException    if a file transfer request has already
     *                                  been made or if send file support isn't
     *                                  available.
     * @throws IllegalStateException    if the response was in a suspended state
     *                                  when this method was invoked, but no
     *                                  {@link CompletionHandler} was provided.
     * @since 2.2
     */
    public void sendfile(final File file, 
                         final long offset, 
                         final long length, 
                         final CompletionHandler<WriteResult> handler) {
        if (!sendfileEnabled) {
            throw new IllegalStateException("sendfile support isn't available.");
        }
        if (fileTransferRequested) {
            throw new IllegalStateException("Only one file transfer allowed per request");
        }
        if (committed) {
            throw new IllegalStateException("Unable to transfer file using sendfile.  Response has already been committed.");
        }



        // additional precondition validation performed by FileTransfer
        // constructor
        final FileTransfer f = new FileTransfer(file, offset, length);

        // lock further sendfile requests out
        fileTransferRequested = true;

        // clear the internal buffers; sendfile content is exclusive
        if (currentBuffer != null) {
            currentBuffer.clear();
        } if (compositeBuffer != null) {
            compositeBuffer.clear();
        }
        
        response.setContentLengthLong(f.remaining());
        if (response.getContentType() == null) {
            response.setContentType(MimeType.getByFilename(file.getName()));
        }
        // set Content-Encoding to identity to prevent compression
        response.setHeader(Header.ContentEncoding, "identity");

        try {
            flush(); // commit the headers, then send the file
        } catch (IOException e) {
            if (handler != null) {
                handler.failed(e);
            } else {
                if (LOGGER.isLoggable(Level.SEVERE)) {
                    LOGGER.log(Level.SEVERE,
                            String.format("Failed to transfer file %s.  Cause: %s.",
                                    file.getAbsolutePath(),
                                    e.getMessage()),
                            e);
                }
            }
            
            return;
        }

        // check the suspend status at the time this method was invoked
        // and take action based on this value
        final boolean suspendedAtStart = serverResponse.isSuspended();
        final CompletionHandler<WriteResult> ch;
        if (suspendedAtStart && handler != null) {
            // provided CompletionHandler assumed to manage suspend/resume
            ch = handler;
        } else if (!suspendedAtStart && handler != null) {
            // provided CompletionHandler assumed to not managed suspend/resume
            ch = suspendAndCreateHandler(handler);
        } else {
            // create internal CompletionHandler that will take the
            // appropriate action depending on the current suspend status
            ch = createInternalCompletionHandler(file, suspendedAtStart);
        }
        
        ctx.write(f, ch);
    }

    public void write(final byte b[], final int off, final int len) throws IOException {

        handleAsyncErrors();
        if (closed || len == 0) {
            return;
        }
        
        updateNonBlockingStatus();
        
        // Copy the content of the b[] to the currentBuffer, if it's possible
        if (bufferSize >= len &&
                (currentBuffer == null || currentBuffer.remaining() >= len)) {
            checkCurrentBuffer();

            assert currentBuffer != null;
            currentBuffer.put(b, off, len);
        } else {  // If b[] is too big - try to send it to wire right away.
            
            // wrap byte[] with a thread local buffer
            temporaryWriteBuffer.reset(b, off, len);
            
            // if there is data in the currentBuffer - complete it
            finishCurrentBuffer();
            
            // mark headers as committed
            doCommit();
            if (compositeBuffer != null) { // if we write a composite buffer
                compositeBuffer.append(temporaryWriteBuffer);
                
                flushBuffer(compositeBuffer, false, cloner);
                compositeBuffer = null;
            } else { // we write just mutableHeapBuffer content
                flushBuffer(temporaryWriteBuffer, false, cloner);
            }
            
            blockAfterWriteIfNeeded();
        }
    }


    // --------------------------------------------------- Common Output Methods


    public void close() throws IOException {

        handleAsyncErrors();
        if (closed) {
            return;
        }
        closed = true;

        // commit the response (mark it as committed)
        final boolean isJustCommitted = doCommit();
        // Try to commit the content chunk together with headers (if there were not committed before)
        if (!flushInternalBuffers(true) && (isJustCommitted || response.isChunked())) {
            // If there is no ready content chunk to commit,
            // but headers were not committed yet, or this is chunked encoding
            // and we need to send trailer
            forceCommitHeaders(true);
        }
        
        blockAfterWriteIfNeeded();
    }




    /**
     * Flush the response.
     *
     * @throws java.io.IOException an underlying I/O error occurred
     */
    public void flush() throws IOException {
        handleAsyncErrors();

        final boolean isJustCommitted = doCommit();
        if (!flushInternalBuffers(false) && isJustCommitted) {
            forceCommitHeaders(false);
        }

        blockAfterWriteIfNeeded();      
    }


    /**
     * <p>
     * Writes the contents of the specified {@link ByteBuffer} to the client.
     * </p>
     *
     * Note, that passed {@link ByteBuffer} will be directly used by underlying
     * connection, so it could be reused only if it has been flushed.
     *
     * @param byteBuffer the {@link ByteBuffer} to write
     * @throws IOException if an error occurs during the write
     */
    @SuppressWarnings({"unchecked", "UnusedDeclaration"})
    public void writeByteBuffer(final ByteBuffer byteBuffer) throws IOException {
        final Buffer w = Buffers.wrap(memoryManager, byteBuffer);
        w.allowBufferDispose(false);
        writeBuffer(w);
    }


    /**
     * <p>
     * Writes the contents of the specified {@link Buffer} to the client.
     * </p>
     *
     * Note, that passed {@link Buffer} will be directly used by underlying
     * connection, so it could be reused only if it has been flushed.
     * 
     * @param buffer the {@link Buffer} to write
     * @throws IOException if an error occurs during the write
     */
    public void writeBuffer(final Buffer buffer) throws IOException {
        handleAsyncErrors();
        
        updateNonBlockingStatus();
        
        finishCurrentBuffer();
        checkCompositeBuffer();
        compositeBuffer.append(buffer);
        
        if (compositeBuffer.remaining() > bufferSize) {
            flush();
        }
    }


    // -------------------------------------------------- General Public Methods


    /**
     * @see org.glassfish.grizzly.asyncqueue.AsyncQueueWriter#canWrite(org.glassfish.grizzly.Connection, int)
     */
    public boolean canWrite() {
        if (isNonBlockingWriteGuaranteed) {
            return true;
        }
        
        final Connection c = ctx.getConnection();
        if (c.canWrite()) {
            isNonBlockingWriteGuaranteed = true;
            return true;
        }
        
        return false;
    }


    public void notifyCanWrite(final WriteHandler handler) {
        if (this.handler != null) {
            throw new IllegalStateException("Illegal attempt to set a new handler before the existing handler has been notified.");
        }

        final Throwable asyncException;
        if ((asyncException = asyncError.get()) != null) {
            handler.onError(Exceptions.makeIOException(asyncException));
            return;
        }

//        final int maxBytes = getMaxAsyncWriteQueueSize();
//        if (maxBytes > 0 && length > maxBytes) {
//            throw new IllegalArgumentException("Illegal request to write "
//                                                  + length
//                                                  + " bytes.  Max allowable write is "
//                                                  + maxBytes + '.');
//        }
        
        final Connection c = ctx.getConnection();
        
//        final int totalLength = length + getBufferedDataSize();
        
        this.handler = handler;

        if (isNonBlockingWriteGuaranteed || canWrite()) {
            final Reentrant reentrant = Reentrant.getWriteReentrant();
            if (!reentrant.isMaxReentrantsReached()) {
                notifyWritePossible();
            } else {
                notifyWritePossibleAsync(c);
            }
            
            return;
        }
        
        if (asyncWriteHandler == null) {
            asyncWriteHandler = new InternalWriteHandler();
        }

        try {
            // If exception occurs here - it's from WriteHandler, so it must
            // have been processed by WriteHandler.onError().
            c.notifyWritePossible(asyncWriteHandler);
        } catch (Exception ignored) {
        }
    }

//    private int getMaxAsyncWriteQueueSize() {
//        return ctx.getConnection().getMaxAsyncWriteQueueSize();
//    }
//
    /**
     * Notify WriteHandler asynchronously
     */
    @SuppressWarnings("unchecked")
    private void notifyWritePossibleAsync(final Connection c) {
        c.write(Buffers.EMPTY_BUFFER,
                onWritePossibleCompletionHandler);
    }

    /**
     * Notify WriteHandler
     */
    private void notifyWritePossible() {
        final Reentrant reentrant = Reentrant.getWriteReentrant();
        final WriteHandler localHandler = handler;
        
        if (localHandler != null) {
            try {
                resetHandler();
                reentrant.inc();
                
                isNonBlockingWriteGuaranteed = true;
                
                localHandler.onWritePossible();
            } catch (Exception ioe) {
                localHandler.onError(ioe);
            } finally {
                reentrant.dec();
            }
        }
    }

    // --------------------------------------------------------- Private Methods


    private void handleAsyncErrors() throws IOException {
        final Throwable t = asyncError.get();
        if (t != null) {
            throw Exceptions.makeIOException(t);
        }
    }

    private void blockAfterWriteIfNeeded()
            throws IOException {
        
        if (isNonBlockingWriteGuaranteed || isLastWriteNonBlocking) {
            return;
        }
        
        final Connection connection = ctx.getConnection();
        
        if (connection.canWrite()) {
            return;
        }
        
        final FutureImpl<Boolean> future = Futures.createSafeFuture();
        
        connection.notifyWritePossible(new WriteHandler() {

            @Override
            public void onWritePossible() throws Exception {
                future.result(Boolean.TRUE);
            }

            @Override
            public void onError(Throwable t) {
                future.failure(Exceptions.makeIOException(t));
            }
        });
        
        try {
            final long writeTimeout =
                    connection.getBlockingWriteTimeout(TimeUnit.MILLISECONDS);
            if (writeTimeout >= 0) {
                future.get(writeTimeout, TimeUnit.MILLISECONDS);
            } else {
                future.get();
            }
        } catch (ExecutionException e) {
            throw Exceptions.makeIOException(e.getCause());
        } catch (Exception e) {
            throw Exceptions.makeIOException(e);
        }
    }
    
    private boolean flushInternalBuffers(final boolean isLast)
            throws IOException {
        if (!response.isChunkingAllowed()
                && response.getContentLength() == -1) {
            if (!isLast) {
                return false;
            } else {
                response.setContentLength(getBufferedDataSize());
            }
        }
        final Buffer bufferToFlush;
        final boolean isFlushComposite = compositeBuffer != null && compositeBuffer.hasRemaining();

        if (isFlushComposite) {
            finishCurrentBuffer();
            bufferToFlush = compositeBuffer;
            compositeBuffer = null;
        } else if (currentBuffer != null && currentBuffer.position() > 0) {
            currentBuffer.trim();
            bufferToFlush = currentBuffer;
            currentBuffer = null;
        } else {
            bufferToFlush = null;
        }

        if (bufferToFlush != null) {
            flushBuffer(bufferToFlush, isLast, null);

            return true;
        }
        
        return false;
    }

    private void flushBuffer(final Buffer bufferToFlush,
            final boolean isLast, final MessageCloner<Buffer> messageCloner)
            throws IOException {
        
        final HttpContent.Builder builder = response.httpContentBuilder();

        builder.content(bufferToFlush).last(isLast);
        ctx.write(null,
                builder.build(),
                onAsyncErrorCompletionHandler,
                null,
                messageCloner,
                IS_BLOCKING);
    }

    private void checkCurrentBuffer() {
        if (currentBuffer == null) {
            currentBuffer = memoryManager.allocate(bufferSize);
            currentBuffer.allowBufferDispose(true);
        }
    }

    private void finishCurrentBuffer() {
        if (currentBuffer != null && currentBuffer.position() > 0) {
            currentBuffer.trim();
            checkCompositeBuffer();
            compositeBuffer.append(currentBuffer);
            currentBuffer = null;
        }
    }

    private CharsetEncoder getEncoder() {

        if (encoder == null) {
            String encoding = response.getCharacterEncoding();
            if (encoding == null) {
                encoding = org.glassfish.grizzly.http.util.Constants.DEFAULT_HTTP_CHARACTER_ENCODING;
            }
            
            encoder = encoders.get(encoding);
            if (encoder == null) {
                final Charset cs = Charsets.lookupCharset(encoding);
                encoder = cs.newEncoder();
                encoder.onMalformedInput(CodingErrorAction.REPLACE);
                encoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
                
                encoders.put(encoding, encoder);
            } else {
                encoder.reset();
            }
        }

        return encoder;

    }

    
    private boolean doCommit() throws IOException {

        if (!committed) {
            notifyCommit();
            committed = true;
            return true;
        }

        return false;
    }

    private void forceCommitHeaders(final boolean isLast) throws IOException {
        if (isLast) {
            if (response != null) {
                final HttpContent.Builder builder = response.httpContentBuilder();
                builder.last(true);
                ctx.write(builder.build(), IS_BLOCKING);
            }
        } else {
            ctx.write(response, IS_BLOCKING);
        }
    }

    private void checkCompositeBuffer() {
        if (compositeBuffer == null) {
            final CompositeBuffer buffer = CompositeBuffer.newBuffer(memoryManager);
            buffer.allowBufferDispose(true);
            buffer.allowInternalBuffersDispose(true);
            compositeBuffer = buffer;
        }
    }

    private void flushCharsToBuf(final CharBuffer charBuf) throws IOException {

        handleAsyncErrors();
        
        // flush the buffer - need to take care of encoding at this point
        final CharsetEncoder enc = getEncoder();
        checkCurrentBuffer();
        ByteBuffer currentByteBuffer = currentBuffer.toByteBuffer();
        int bufferPos = currentBuffer.position();
        int byteBufferPos = currentByteBuffer.position();

        CoderResult res = enc.encode(charBuf,
                                     currentByteBuffer,
                                     true);
        if (currentBuffer.isComposite()) {
            final ByteBuffer duplicate = currentByteBuffer.duplicate();
            duplicate.flip();
            currentBuffer.put(duplicate);  // TODO: COPY
        }
        currentBuffer.position(bufferPos + (currentByteBuffer.position() - byteBufferPos));
        currentByteBuffer.position(byteBufferPos);
        while (res == CoderResult.OVERFLOW) {
            finishCurrentBuffer();
            checkCurrentBuffer();
            currentByteBuffer = currentBuffer.toByteBuffer();
            bufferPos = currentBuffer.position();
            byteBufferPos = currentByteBuffer.position();
            
            res = enc.encode(charBuf, currentByteBuffer, true);
            if (currentBuffer.isComposite()) {
                final ByteBuffer duplicate = currentByteBuffer.duplicate();
                duplicate.flip();
                currentBuffer.put(duplicate);  // TODO: COPY
            }
            currentBuffer.position(bufferPos + (currentByteBuffer.position() - byteBufferPos));
            currentByteBuffer.position(byteBufferPos);
        }

        if (res != CoderResult.UNDERFLOW) {
            throw new IOException("Encoding error");
        }
        
        if (compositeBuffer != null) { // this actually checks wheather current buffer was overloaded during encoding so we need to flush
            doCommit();
            flushInternalBuffers(false);
            
            blockAfterWriteIfNeeded();
        }        
    }

    private void notifyCommit() throws IOException {
        for (int i = 0, len = lifeCycleListeners.size(); i < len; i++) {
            lifeCycleListeners.get(i).onCommit();
        }
    }

    private void resetHandler() {
        this.handler = null;
    }

    private void updateNonBlockingStatus() {
        isLastWriteNonBlocking = isNonBlockingWriteGuaranteed;
        isNonBlockingWriteGuaranteed = false;
    }
    
    private void copyStringCharsToInternalBuffer(final String string,
                                                 final int offset,
                                                 final int len) {
        if (stringCopyBuffer == null || len > stringCopyBuffer.length) {
            stringCopyBuffer = new char[getCopyBufferAllocationLength(len)];
        }
        string.getChars(offset, offset + len, stringCopyBuffer, 0);
    }


    private static int getCopyBufferAllocationLength(final int minimumLength) {
        if (minimumLength >= MAX_COPY_BUFFER_SIZE) {
            return MAX_COPY_BUFFER_SIZE;
        }

        int returnLen = MAX_COPY_BUFFER_SIZE;
        for (int i = 5; i > 0; i--) {
            final int computedLen = MAX_COPY_BUFFER_SIZE >> i;
            if (minimumLength <= computedLen) {
                returnLen = computedLen;
                break;
            }
        }
        return returnLen;
    }
    
    private CompletionHandler<WriteResult> createInternalCompletionHandler(
            final File file, final boolean suspendedAtStart) {

        CompletionHandler<WriteResult> ch;
        if (!suspendedAtStart) {
            serverResponse.suspend();
        }
        ch = new CompletionHandler<WriteResult>() {
            @Override
            public void cancelled() {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING,
                            "Transfer of file {0} was cancelled.",
                            file.getAbsolutePath());
                }
                serverResponse.resume();
            }

            @Override
            public void failed(Throwable throwable) {
                if (LOGGER.isLoggable(Level.SEVERE)) {
                    LOGGER.log(Level.SEVERE,
                            String.format("Failed to transfer file %s.  Cause: %s.",
                                    file.getAbsolutePath(),
                                    throwable.getMessage()),
                            throwable);
                }
                serverResponse.resume();
            }

            @Override
            public void completed(WriteResult result) {
                serverResponse.resume();
            }

            @Override
            public void updated(WriteResult result) {
                // no-op
            }
        };
        return ch;

    }

    private CompletionHandler<WriteResult> suspendAndCreateHandler(final CompletionHandler<WriteResult> handler) {
        serverResponse.suspend();
        return new CompletionHandler<WriteResult>() {

            @Override
            public void cancelled() {
                handler.cancelled();
                serverResponse.resume();
            }

            @Override
            public void failed(Throwable throwable) {
                handler.failed(throwable);
                serverResponse.resume();
            }

            @Override
            public void completed(WriteResult result) {
                handler.completed(result);
                serverResponse.resume();
            }

            @Override
            public void updated(WriteResult result) {
                handler.updated(result);
            }
        };
    }
    
    /**
     * The {@link MessageCloner}, responsible for cloning Buffer content, if it
     * wasn't possible to write it in the current Thread (it was added to async
     * write queue).
     * We do this, because {@link #write(byte[], int, int)} method is not aware
     * of async write queues, and content of the passed byte[] might be changed
     * by user application once in gets control back.
     */
    private final class ByteArrayCloner implements MessageCloner<Buffer> {
        @Override
        public Buffer clone(final Connection connection,
                final Buffer originalMessage) {
            
            // Buffer was disposed somewhere on the way to async write queue -
            // just return the original message
            if (temporaryWriteBuffer.isDisposed()) {
                return originalMessage;
            }
            
            if (originalMessage.isComposite()) {
                final CompositeBuffer compositeBuffer = (CompositeBuffer) originalMessage;
                compositeBuffer.shrink();

                if (!temporaryWriteBuffer.isDisposed()) {
                    if (compositeBuffer.remaining() == temporaryWriteBuffer.remaining()) {
                        compositeBuffer.allowInternalBuffersDispose(false);
                        compositeBuffer.tryDispose();
                        return temporaryWriteBuffer.cloneContent();
                    } else {
                        compositeBuffer.replace(temporaryWriteBuffer,
                                temporaryWriteBuffer.cloneContent());
                    }
                }
                
                return originalMessage;
            }
                
            return temporaryWriteBuffer.cloneContent();
        }
    }
    
    public static interface LifeCycleListener {
        public void onCommit() throws IOException;
    }
    
    private class OnErrorCompletionHandler
            extends EmptyCompletionHandler<WriteResult> {

        @Override
        public void failed(final Throwable throwable) {
            asyncError.compareAndSet(null, throwable);

            final WriteHandler localHandler = handler;
            resetHandler();
            
            if (localHandler != null) {
                localHandler.onError(throwable);
            }
        }
    }

    private final class OnWritePossibleCompletionHandler
            extends OnErrorCompletionHandler {

        @Override
        public void completed(final WriteResult result) {
            notifyWritePossible();
        }
    }    
    
    private final class InternalWriteHandler implements WriteHandler {
        private volatile boolean done;
        
        @Override
        public void onWritePossible() throws Exception {
            if (!done) {
                try {
                    final Reentrant reentrant = Reentrant.getWriteReentrant();
                    if (!reentrant.isMaxReentrantsReached()) {
                        notifyWritePossible();
                    } else {
                        notifyWritePossibleAsync(ctx.getConnection());
                    }
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public void onError(Throwable t) {
            if (!done) {
                final WriteHandler localHandler = handler;

                if (localHandler != null) {
                    try {
                        resetHandler();
                        localHandler.onError(t);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }
}
