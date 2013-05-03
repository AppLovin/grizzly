/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012-2013 Oracle and/or its affiliates. All rights reserved.
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
package org.glassfish.grizzly.spdy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.WritableMessage;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.asyncqueue.AsyncQueueRecord;
import org.glassfish.grizzly.asyncqueue.LifeCycleHandler;
import org.glassfish.grizzly.asyncqueue.TaskQueue;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpPacket;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.spdy.SpdyStream.Termination;
import org.glassfish.grizzly.spdy.frames.DataFrame;
import org.glassfish.grizzly.spdy.frames.SpdyFrame;
import org.glassfish.grizzly.spdy.frames.SynReplyFrame;
import org.glassfish.grizzly.spdy.frames.SynStreamFrame;
import org.glassfish.grizzly.spdy.frames.WindowUpdateFrame;

import static org.glassfish.grizzly.spdy.Constants.*;

/**
 * Class represents an output sink associated with specific {@link SpdyStream}. 
 * 
 * The implementation is aligned with SPDY/3 requirements with regards to message
 * flow control.
 * 
 * @author Alexey Stashok
 */
final class SpdyOutputSink {
    private static final int EMPTY_QUEUE_RECORD_SIZE = 1;
    
    private static final OutputQueueRecord TERMINATING_QUEUE_RECORD =
            new OutputQueueRecord(null, null, null, true);
    
    // async output queue
    final TaskQueue<OutputQueueRecord> outputQueue =
            TaskQueue.createTaskQueue();
    
    // number of sent bytes, which are still unconfirmed by server via (WINDOW_UPDATE) message
    private final AtomicInteger unconfirmedBytes = new AtomicInteger();

    // true, if last output frame has been queued
    private volatile boolean isLastFrameQueued;
    // not null if last output frame has been sent or forcibly terminated
    private Termination terminationFlag;
    
    // associated spdy session
    private final SpdySession spdySession;
    // associated spdy stream
    private final SpdyStream spdyStream;

    private List<SpdyFrame> tmpOutputList;
    
    SpdyOutputSink(final SpdyStream spdyStream) {
        this.spdyStream = spdyStream;
        spdySession = spdyStream.getSpdySession();
    }

    /**
     * The method is called by Spdy Filter once WINDOW_UPDATE message comes from the peer.
     * 
     * @param delta the delta.
     */
    public void onPeerWindowUpdate(final int delta) throws SpdyStreamException {
        // update the unconfirmed bytes counter
        final int unconfirmedBytesNow = unconfirmedBytes.addAndGet(-delta);
        
        // get the current peer's window size limit
        final int windowSizeLimit = spdyStream.getPeerWindowSize();
        
        // try to write until window limit allows
        while (isWantToWrite(unconfirmedBytesNow, windowSizeLimit) &&
                !outputQueue.isEmpty()) {
            
            // pick up the first output record in the queue
            OutputQueueRecord outputQueueRecord = outputQueue.poll();

            // if there is nothing to write - return
            if (outputQueueRecord == null) {
                return;
            }
            
            // if it's terminating record - processFin
            if (outputQueueRecord == TERMINATING_QUEUE_RECORD) {
                outputQueue.releaseSpace(EMPTY_QUEUE_RECORD_SIZE);
                writeEmptyFin();
                return;
            }
            
            AggregatingCompletionHandler completionHandler =
                    outputQueueRecord.aggrCompletionHandler;
            AggregatingLifeCycleHandler lifeCycleHandler =
                    outputQueueRecord.lifeCycleHandler;
            boolean isLast = outputQueueRecord.isLast;
            final OutputResource resource = outputQueueRecord.resource;
            
            // check if output record's buffer is fitting into window size
            // if not - split it into 2 parts: part to send, part to keep in the queue
            final int bytesToSend = checkOutputWindow(resource.remaining());
            final Buffer dataChunkToSend = resource.read(bytesToSend);
            final boolean hasRemaining = resource.hasRemaining();
            
            
            // if there is a chunk to store
            if (hasRemaining) {
                // Create output record for the chunk to be stored
                outputQueueRecord.reset(resource, completionHandler,
                        lifeCycleHandler, isLast);
                outputQueueRecord.incCompletionCounter();
                
                // reset isLast for the current chunk
                isLast = false;
            } else {
                outputQueueRecord.complete();
                outputQueueRecord = null;
            }

            // if there is a chunk to sent
            if (dataChunkToSend != null &&
                    (dataChunkToSend.hasRemaining() || isLast)) {
                final int dataChunkToSendSize = dataChunkToSend.remaining();
                
                DataFrame dataFrame = DataFrame.builder().data(dataChunkToSend).
                        last(isLast).streamId(spdyStream.getStreamId()).build();

                // send a spdydata frame
                writeDownStream(dataFrame, completionHandler,
                        lifeCycleHandler, isLast);
                
                
                // update unconfirmed bytes counter
                unconfirmedBytes.addAndGet(dataChunkToSendSize);
                outputQueue.releaseSpace(dataChunkToSendSize);
                
                // pass peer-window-size as max, even though these values are independent.
                // later we may want to decouple outputQueue's max-size and peer-window-size
                outputQueue.onSizeDecreased(windowSizeLimit);
            }
            
            if (outputQueueRecord != null) {
                // if there is a chunk to be stored - store it and return
                outputQueue.setCurrentElement(outputQueueRecord);
                break;
            }
        }
    }
    
    /**
     * Send an {@link HttpPacket} to the {@link SpdyStream}.
     * 
     * @param httpPacket {@link HttpPacket} to send
     * @throws IOException 
     */
    public synchronized void writeDownStream(final HttpPacket httpPacket)
            throws IOException {
        writeDownStream(httpPacket, null);
    }
    
    /**
     * Send an {@link HttpPacket} to the {@link SpdyStream}.
     * 
     * The writeDownStream(...) methods have to be synchronized with shutdown().
     * 
     * @param httpPacket {@link HttpPacket} to send
     * @param completionHandler the {@link CompletionHandler},
     *          which will be notified about write progress.
     * @throws IOException 
     */
    public synchronized void writeDownStream(final HttpPacket httpPacket,
            final CompletionHandler<WriteResult> completionHandler)
            throws IOException {
        writeDownStream(httpPacket, completionHandler, null);
    }

    /**
     * Send an {@link HttpPacket} to the {@link SpdyStream}.
     * 
     * The writeDownStream(...) methods have to be synchronized with shutdown().
     * 
     * @param httpPacket {@link HttpPacket} to send
     * @param completionHandler the {@link CompletionHandler},
     *          which will be notified about write progress.
     * @param lifeCycleHandler the {@link LifeCycleHandler},
     *          which will be notified about write progress.
     * @throws IOException 
     */
    synchronized void writeDownStream(final HttpPacket httpPacket,
            CompletionHandler<WriteResult> completionHandler,
            LifeCycleHandler lifeCycleHandler)
            throws IOException {
        
        // if the last frame (fin flag == 1) has been queued already - throw an IOException
        if (isTerminated()) {
            throw new IOException(terminationFlag.getDescription());
        }
        
        final HttpHeader httpHeader = spdyStream.getOutputHttpHeader();
        final HttpContent httpContent = HttpContent.isContent(httpPacket) ? (HttpContent) httpPacket : null;
        
        int framesAvailFlag = 0;
        SpdyFrame headerFrame = null;
        OutputQueueRecord outputQueueRecord = null;
        
        boolean isDeflaterLocked = false;
        
        try { // try-finally block to release deflater lock if needed

            // If HTTP header hasn't been commited - commit it
            if (!httpHeader.isCommitted()) {
                // do we expect any HTTP payload?
                final boolean isNoContent = !httpHeader.isExpectContent() ||
                        (httpContent != null && httpContent.isLast() &&
                        !httpContent.getContent().hasRemaining());

                // encode HTTP packet header
                if (!httpHeader.isRequest()) {
                    final Buffer compressedSynStreamHeaders;
                    if (!spdyStream.isUnidirectional()) {
                        final Buffer compressedHeaders =
                                SpdyEncoderUtils.encodeSynReplyHeadersAndLock(
                                spdySession, (HttpResponsePacket) httpHeader);
                        headerFrame = SynReplyFrame.builder().streamId(spdyStream.getStreamId()).
                                last(isNoContent).compressedHeaders(compressedHeaders).build();
                    } else {
                        compressedSynStreamHeaders =
                                SpdyEncoderUtils.encodeUnidirectionalSynStreamHeadersAndLock(
                                spdyStream, (SpdyResponse) httpHeader);

                        headerFrame = SynStreamFrame.builder().streamId(spdyStream.getStreamId()).
                                associatedStreamId(spdyStream.getAssociatedToStreamId()).
                                unidirectional(true).
                                last(isNoContent).compressedHeaders(compressedSynStreamHeaders).build();
                    }
                } else {
                    final Buffer compressedHeaders =
                            SpdyEncoderUtils.encodeSynStreamHeadersAndLock(spdyStream,
                            (HttpRequestPacket) httpHeader);
                    headerFrame = SynStreamFrame.builder().streamId(spdyStream.getStreamId()).
                            associatedStreamId(spdyStream.getAssociatedToStreamId()).
                            unidirectional(spdyStream.isUnidirectional()).
                            last(isNoContent).compressedHeaders(compressedHeaders).build();
                }

                isDeflaterLocked = true;
                httpHeader.setCommitted(true);
                framesAvailFlag = 1;

                if (isNoContent || httpContent == null) {
                    // if we don't expect any HTTP payload, mark this frame as
                    // last and return
                    writeDownStream(headerFrame, completionHandler,
                            lifeCycleHandler, isNoContent);
                    return;
                }
            }

            // if there is nothing to write - return
            if (httpContent == null) {
                return;
            }
            
            SpdyFrame dataFrame = null;
            boolean isLast = httpContent.isLast();
            Buffer data = httpContent.getContent();
            final int dataSize = data.remaining();

            if (isLast && dataSize == 0) {
                close();
                return;
            }

            AggregatingCompletionHandler aggrCompletionHandler = null;
            AggregatingLifeCycleHandler aggrLifeCycleHandler = null;


            boolean isDataCloned = false;

            // Check if output queue is not empty - add new element
            if (outputQueue.reserveSpace(dataSize) > dataSize) {
                // if the queue is not empty - the headers should have been sent
                assert headerFrame == null;
            
                aggrCompletionHandler = AggregatingCompletionHandler.create(completionHandler);
                aggrLifeCycleHandler = AggregatingLifeCycleHandler.create(lifeCycleHandler);

                if (aggrLifeCycleHandler != null) {
                    data = (Buffer) aggrLifeCycleHandler.onThreadContextSwitch(
                            spdySession.getConnection(), data);
                    isDataCloned = true;
                }

                outputQueueRecord = new OutputQueueRecord(
                        OutputResource.factory(spdyStream)
                            .createBufferOutputResource(data),
                        aggrCompletionHandler, aggrLifeCycleHandler, isLast);
                // Should be called before flushing headerFrame, so
                // AggregatingCompletionHanlder will not pass completed event to the parent
                outputQueueRecord.incCompletionCounter();

                outputQueue.offer(outputQueueRecord);

                // check if our element wasn't forgotten (async)
                if (outputQueue.size() != dataSize ||
                        !outputQueue.remove(outputQueueRecord)) {
                    // if not - return
                    return;
                }

                outputQueueRecord.decCompletionCounter();
                outputQueueRecord = null;
            }

            // our element is first in the output queue

            final int remaining = data.remaining();

            // check if output record's buffer is fitting into window size
            // if not - split it into 2 parts: part to send, part to keep in the queue
            final int fitWindowLen = checkOutputWindow(remaining);
            // if there is a chunk to store
            if (fitWindowLen < remaining) {
                aggrCompletionHandler = AggregatingCompletionHandler.create(
                        completionHandler, aggrCompletionHandler);
                aggrLifeCycleHandler = AggregatingLifeCycleHandler.create(
                        lifeCycleHandler, aggrLifeCycleHandler);

                if (aggrLifeCycleHandler != null) {
                    data = (Buffer) aggrLifeCycleHandler.onThreadContextSwitch(
                            spdySession.getConnection(), data);
                    isDataCloned = true;
                }
                
                final Buffer dataChunkToStore = splitOutputBufferIfNeeded(
                        data, fitWindowLen);
                // Create output record for the chunk to be stored
                outputQueueRecord = new OutputQueueRecord(
                        OutputResource.factory(spdyStream)
                            .createBufferOutputResource(dataChunkToStore),
                        aggrCompletionHandler, aggrLifeCycleHandler, isLast);
                outputQueueRecord.incCompletionCounter();
                // reset completion handler and isLast for the current chunk
                isLast = false;
            }

            // if there is a chunk to send
            if (data != null &&
                    (data.hasRemaining() || isLast)) {
                spdyStream.onDataFrameSend();

                final int dataChunkToSendSize = data.remaining();

                // update unconfirmed bytes counter
                unconfirmedBytes.addAndGet(dataChunkToSendSize);
                outputQueue.releaseSpace(dataChunkToSendSize);

                // encode spdydata frame
                dataFrame = DataFrame.builder()
                        .streamId(spdyStream.getStreamId())
                        .data(data).last(isLast)
                        .build();
                framesAvailFlag |= 2;
            }

            completionHandler = aggrCompletionHandler == null
                    ? completionHandler
                    : aggrCompletionHandler;
            lifeCycleHandler = aggrLifeCycleHandler == null
                    ? lifeCycleHandler
                    : aggrLifeCycleHandler;

            switch (framesAvailFlag) {
                case 0: break;  // buffer is full, can't write any byte
                    
                case 2: {
                    writeDownStream(dataFrame, completionHandler,
                            lifeCycleHandler, isLast);
                    break;
                }

                case 3: {
                    writeDownStream(asList(headerFrame, dataFrame),
                            completionHandler, lifeCycleHandler, isLast);
                    break;
                }

                default: throw new IllegalStateException("Unexpected write mode");
            }
            
        } finally {
            if (isDeflaterLocked) {
                spdySession.getDeflaterLock().unlock();
            }
        }
        
        if (outputQueueRecord == null) {
            return;
        }
        addOutputQueueRecord(outputQueueRecord);
    }
    
    /**
     * Send an {@link OutputResource} to the {@link SpdyStream}.
     * Unlike {@link #writeDownStream(org.glassfish.grizzly.http.HttpPacket)}, here
     * we assume the resource is going to be send on non-commited header and
     * it will be the only resource sent over this {@link SpdyStream} (isLast flag will be set).
     * 
     * The writeDownStream(...) methods have to be synchronized with shutdown().
     * 
     * @param resource {@link OutputResource} to send
     * @throws IOException 
     */
    synchronized void writeDownStream(final OutputResource resource)
            throws IOException {

        // if the last frame (fin flag == 1) has been queued already - throw an IOException
        if (isTerminated()) {
            throw new IOException(terminationFlag.getDescription());
        }

        final HttpHeader httpHeader = spdyStream.getOutputHttpHeader();
        
        if (httpHeader.isCommitted()) {
            throw new IllegalStateException("Headers have been already commited");
        }
        
        SpdyFrame headerFrame;
        OutputQueueRecord outputQueueRecord = null;
        
        boolean isDeflaterLocked = false;
        
        try { // try-finally block to release deflater lock if needed
            
            // We assume HTTP header hasn't been commited
            
            // do we expect any HTTP payload?
            final boolean isNoContent =
                    !httpHeader.isExpectContent() ||
                    resource == null || !resource.hasRemaining();

            // encode HTTP packet header
            if (!httpHeader.isRequest()) {
                final Buffer compressedSynStreamHeaders;
                if (!spdyStream.isUnidirectional()) {
                    final Buffer compressedHeaders =
                            SpdyEncoderUtils.encodeSynReplyHeadersAndLock(
                            spdySession, (HttpResponsePacket) httpHeader);
                    headerFrame = SynReplyFrame.builder().streamId(spdyStream.getStreamId()).
                            last(isNoContent).compressedHeaders(compressedHeaders).build();
                } else {
                    compressedSynStreamHeaders =
                            SpdyEncoderUtils.encodeUnidirectionalSynStreamHeadersAndLock(
                            spdyStream, (SpdyResponse) httpHeader);

                    headerFrame = SynStreamFrame.builder().streamId(spdyStream.getStreamId()).
                            associatedStreamId(spdyStream.getAssociatedToStreamId()).
                            unidirectional(true).
                            last(isNoContent).compressedHeaders(compressedSynStreamHeaders).build();                }
            } else {
                final Buffer compressedSynStreamHeaders =
                        SpdyEncoderUtils.encodeSynStreamHeadersAndLock(
                        spdyStream, (HttpRequestPacket) httpHeader);

                headerFrame = SynStreamFrame.builder().streamId(spdyStream.getStreamId()).
                        associatedStreamId(spdyStream.getAssociatedToStreamId()).
                        last(isNoContent).compressedHeaders(compressedSynStreamHeaders).build();
            }

            isDeflaterLocked = true;
            httpHeader.setCommitted(true);

            if (isNoContent) {
                // if we don't expect any HTTP payload, mark this frame as
                // last and return
                writeDownStream(headerFrame, null, null, isNoContent);
                return;
            }

            final int dataSize = resource.remaining();

            if (dataSize == 0) {
                close();
                return;
            }

            // our element is first in the output queue
            outputQueue.reserveSpace(dataSize);

            boolean isLast = true;

            // check if output record's buffer is fitting into window size
            // if not - split it into 2 parts: part to send, part to keep in the queue
            final int fitWindowLen = checkOutputWindow(dataSize);

            // if there is a chunk to store
            if (fitWindowLen < dataSize) {
                // Create output record for the chunk to be stored
                outputQueueRecord = new OutputQueueRecord(resource,
                        null, null, true);
                isLast = false;
            }

            final Buffer bufferToSend = resource.read(fitWindowLen);
            
            spdyStream.onDataFrameSend();

            final int dataChunkToSendSize = bufferToSend.remaining();

            // update unconfirmed bytes counter
            unconfirmedBytes.addAndGet(dataChunkToSendSize);
            outputQueue.releaseSpace(dataChunkToSendSize);

            // encode spdydata frame
            final SpdyFrame dataFrame = DataFrame.builder()
                    .streamId(spdyStream.getStreamId())
                    .data(bufferToSend).last(isLast)
                    .build();

            writeDownStream(asList(headerFrame, dataFrame), null, null, isLast);
        
        } finally {
            if (isDeflaterLocked) {
                spdySession.getDeflaterLock().unlock();
            }
        }
        
        if (outputQueueRecord == null) {
            return;
        }
        
        addOutputQueueRecord(outputQueueRecord);
    }
    
    /**
     * The method is responsible for checking the current output window size.
     * The returned integer value is the size of the data, which could be
     * sent now.
     * 
     * @param data the output queue data.
     */
    private int checkOutputWindow(final int size) {
        // take a snapshot of the current output window state
        final int unconfirmedBytesNow = unconfirmedBytes.get();
        final int windowSizeLimit = spdyStream.getPeerWindowSize();

        // Check if data chunk is overflowing the output window
        if (unconfirmedBytesNow + size > windowSizeLimit) { // Window overflowed
            final int dataSizeAllowedToSend = windowSizeLimit - unconfirmedBytesNow;

            // Split data chunk into 2 chunks - one to be sent now and one to be stored in the output queue
            return dataSizeAllowedToSend;
        }

        return size;
    }
    
    private Buffer splitOutputBufferIfNeeded(final Buffer buffer,
            final int length) {
        if (length == buffer.remaining()) {
            return null;
        }

        return buffer.split(buffer.position() + length);
    }
    
    void writeWindowUpdate(final int currentUnackedBytes) {
        WindowUpdateFrame frame =
                WindowUpdateFrame.builder().streamId(spdyStream.getStreamId()).
                        delta(currentUnackedBytes).build();
        writeDownStream0(frame, null);
    }
    
    private void writeDownStream(final SpdyFrame frame,
            final CompletionHandler<WriteResult> completionHandler,
            final boolean isLast) {
        writeDownStream(frame, completionHandler, null, isLast);
    }

    private void writeDownStream(final SpdyFrame frame,
            final CompletionHandler<WriteResult> completionHandler,
            final LifeCycleHandler lifeCycleHandler,
            final boolean isLast) {
        writeDownStream0(frame, completionHandler, lifeCycleHandler);
        
        if (isLast) {
            terminate(OUT_FIN_TERMINATION);
        }
    }
    
    private void writeDownStream0(final SpdyFrame frame,
            final CompletionHandler<WriteResult> completionHandler,
            final LifeCycleHandler lifeCycleHandler) {
        spdySession.getDownstreamChain().write(spdySession.getConnection(),
                null, frame, completionHandler, lifeCycleHandler);
    }
    
    private void writeDownStream0(final SpdyFrame frame,
            final CompletionHandler<WriteResult> completionHandler) {
        spdySession.getDownstreamChain().write(spdySession.getConnection(),
                null, frame, completionHandler, null);
    }

    private void writeDownStream(final List<SpdyFrame> frames,
            final CompletionHandler<WriteResult> completionHandler,
            final LifeCycleHandler lifeCycleHandler,
            final boolean isLast) {
        writeDownStream0(frames, completionHandler, lifeCycleHandler);
        
        if (isLast) {
            terminate(OUT_FIN_TERMINATION);
        }
    }

    private void writeDownStream0(final List<SpdyFrame> frames,
            final CompletionHandler<WriteResult> completionHandler,
            final LifeCycleHandler lifeCycleHandler) {
        spdySession.getDownstreamChain().write(spdySession.getConnection(),
                null, frames, completionHandler, lifeCycleHandler);
    }

    private List<SpdyFrame> asList(final SpdyFrame frame1, final SpdyFrame frame2) {
        if (tmpOutputList == null) {
            tmpOutputList = new ArrayList<SpdyFrame>(4);
        }
        
        tmpOutputList.add(frame1);
        tmpOutputList.add(frame2);
        
        return tmpOutputList;
    }
    
    /**
     * Closes the output sink by adding last DataFrame with the FIN flag to a queue.
     * If the output sink is already closed - method does nothing.
     */
    synchronized void close() {
        if (!isClosed()) {
            isLastFrameQueued = true;
            
            if (outputQueue.isEmpty()) {
                writeEmptyFin();
                return;
            }
            
            outputQueue.reserveSpace(EMPTY_QUEUE_RECORD_SIZE);
            outputQueue.offer(TERMINATING_QUEUE_RECORD);
            
            if (outputQueue.size() == EMPTY_QUEUE_RECORD_SIZE &&
                    outputQueue.remove(TERMINATING_QUEUE_RECORD)) {
                writeEmptyFin();
            }
        }
    }

    /**
     * Unlike {@link #close()} this method forces the output sink termination
     * by setting termination flag and canceling all the pending writes.
     */
    synchronized void terminate(final Termination terminationFlag) {
        if (!isTerminated()) {
            this.terminationFlag = terminationFlag;
            outputQueue.onClose();
            // NOTIFY STREAM
            spdyStream.onOutputClosed();
        }
    }
    
    boolean isClosed() {
        return isLastFrameQueued || isTerminated();
    }
    
    private boolean isTerminated() {
        return terminationFlag != null;
    }
    
    private void writeEmptyFin() {
        if (!isTerminated()) {
            // SEND LAST
            DataFrame dataFrame =
                    DataFrame.builder().streamId(spdyStream.getStreamId()).
                            data(Buffers.EMPTY_BUFFER).last(true).build();
            writeDownStream0(dataFrame, null);

            terminate(OUT_FIN_TERMINATION);
        }
    }

    private boolean isWantToWrite(final int unconfirmedBytesNow,
            final int windowSizeLimit) {
        return unconfirmedBytesNow < (windowSizeLimit * 3 / 4);
    }

    private void addOutputQueueRecord(OutputQueueRecord outputQueueRecord)
            throws SpdyStreamException {

        do { // Make sure current outputQueueRecord is not forgotten
            
            // set the outputQueueRecord as the current
            outputQueue.setCurrentElement(outputQueueRecord);

            // update the unconfirmed bytes counter
            final int unconfirmedBytesNow = unconfirmedBytes.get();

            // get the current peer's window size limit
            final int windowSizeLimit = spdyStream.getPeerWindowSize();
            

            // check if situation hasn't changed and we can't send the data chunk now
            if (isWantToWrite(unconfirmedBytesNow, windowSizeLimit) &&
                    outputQueue.compareAndSetCurrentElement(outputQueueRecord, null)) {
                
                // if we can send the output record now - do that
                
                final AggregatingCompletionHandler aggrCompletionHandler =
                        outputQueueRecord.aggrCompletionHandler;
                final AggregatingLifeCycleHandler aggrLifeCycleHandler =
                        outputQueueRecord.lifeCycleHandler;

                boolean isLast = outputQueueRecord.isLast;
                
                final OutputResource currentResource = outputQueueRecord.resource;
                
                final int fitWindowLen = checkOutputWindow(currentResource.remaining());
                final Buffer dataChunkToSend = currentResource.read(fitWindowLen);
                
                
                // if there is a chunk to store
                if (currentResource.hasRemaining()) {
                    // Create output record for the chunk to be stored
                    outputQueueRecord.reset(currentResource,
                            aggrCompletionHandler, aggrLifeCycleHandler,
                            isLast);
                    outputQueueRecord.incCompletionCounter();
                    
                    // reset isLast for the current chunk
                    isLast = false;
                } else {
                    outputQueueRecord.complete();
                    outputQueueRecord = null;
                }

                // if there is a chunk to send
                if (dataChunkToSend != null &&
                        (dataChunkToSend.hasRemaining() || isLast)) {
                    final int dataChunkToSendSize = dataChunkToSend.remaining();
                    
                    // update unconfirmed bytes counter
                    unconfirmedBytes.addAndGet(dataChunkToSendSize);
                    outputQueue.releaseSpace(dataChunkToSendSize);

                    // encode spdydata frame
                    DataFrame frame =
                            DataFrame.builder().streamId(spdyStream.getStreamId()).
                            data(dataChunkToSend).last(isLast).build();
                    writeDownStream(frame, aggrCompletionHandler,
                            aggrLifeCycleHandler, isLast);
                }
            } else {
                break; // will be (or already) written asynchronously
            }
        } while (outputQueueRecord != null);
    }

    private static class OutputQueueRecord extends AsyncQueueRecord<WriteResult>{
        private OutputResource resource;
        private AggregatingCompletionHandler aggrCompletionHandler;
        private AggregatingLifeCycleHandler lifeCycleHandler;
        
        private boolean isLast;
        
        public OutputQueueRecord(final OutputResource resource,
                final AggregatingCompletionHandler completionHandler,
                final AggregatingLifeCycleHandler lifeCycleHandler,
                final boolean isLast) {
            super(null, null, null, null);
            
            this.resource = resource;
            this.aggrCompletionHandler = completionHandler;
            this.lifeCycleHandler = lifeCycleHandler;
            this.isLast = isLast;
        }
        
        private void incCompletionCounter() {
            if (aggrCompletionHandler != null) {
                aggrCompletionHandler.counter++;
            }
        }
        
        private void decCompletionCounter() {
            if (aggrCompletionHandler != null) {
                aggrCompletionHandler.counter--;
            }
        }

        private void reset(final OutputResource resource,
                final AggregatingCompletionHandler completionHandler,
                final AggregatingLifeCycleHandler lifeCycleHandler,
                final boolean last) {
            this.resource = resource;
            this.aggrCompletionHandler = completionHandler;
            this.lifeCycleHandler = lifeCycleHandler;
            this.isLast = last;
        }
        
        public void complete() {
            if (resource != null) {
                resource.release();
                resource = null;
            }
        }

        @Override
        public void notifyFailure(final Throwable e) {
            final CompletionHandler chLocal = aggrCompletionHandler;
            aggrCompletionHandler = null;
                try {
                if (chLocal != null) {
                    chLocal.failed(e);
                }
            } finally {
                complete();
            }
        }
        
        @Override
        public void recycle() {
        }
    }    

    private static class AggregatingCompletionHandler
            implements CompletionHandler<WriteResult> {

        private static AggregatingCompletionHandler create(
                final CompletionHandler<WriteResult> parentCompletionHandler) {
            return parentCompletionHandler == null
                    ? null
                    : new AggregatingCompletionHandler(parentCompletionHandler);
        }

        private static AggregatingCompletionHandler create(
                final CompletionHandler<WriteResult> parentCompletionHandler,
                final AggregatingCompletionHandler aggrCompletionHandler) {
            return aggrCompletionHandler != null
                    ? aggrCompletionHandler
                    : create(parentCompletionHandler);
        }
        
        private final CompletionHandler<WriteResult> parentCompletionHandler;
        
        private boolean isDone;
        private int counter;
        private long writtenSize;
        
        public AggregatingCompletionHandler(
                final CompletionHandler<WriteResult> parentCompletionHandler) {
            this.parentCompletionHandler = parentCompletionHandler;
        }

        @Override
        public void cancelled() {
            if (!isDone) {
                isDone = true;
                parentCompletionHandler.cancelled();
            }
        }

        @Override
        public void failed(Throwable throwable) {
            if (!isDone) {
                isDone = true;
                parentCompletionHandler.failed(throwable);
            }
        }

        @Override
        public void completed(final WriteResult result) {
            if (isDone) {
                return;
            }
            
            if (--counter == 0) {
                isDone = true;
                final long initialWrittenSize = result.getWrittenSize();
                writtenSize += initialWrittenSize;
                
                try {
                    result.setWrittenSize(writtenSize);
                    parentCompletionHandler.completed(result);
                } finally {
                    result.setWrittenSize(initialWrittenSize);
                }
            } else {
                updated(result);
                writtenSize += result.getWrittenSize();
            }
        }

        @Override
        public void updated(final WriteResult result) {
            final long initialWrittenSize = result.getWrittenSize();
            
            try {
                result.setWrittenSize(writtenSize + initialWrittenSize);
                parentCompletionHandler.updated(result);
            } finally {
                result.setWrittenSize(initialWrittenSize);
            }
        }
    }

    private static class AggregatingLifeCycleHandler implements LifeCycleHandler {
        
        private static AggregatingLifeCycleHandler create(
                final LifeCycleHandler parentLifyCycleHandler) {
            return parentLifyCycleHandler == null
                    ? null
                    : new AggregatingLifeCycleHandler(parentLifyCycleHandler);
        }

        private static AggregatingLifeCycleHandler create(
                final LifeCycleHandler parentLifyCycleHandler,
                final AggregatingLifeCycleHandler aggrLifeCycleHandler) {
            return aggrLifeCycleHandler != null
                    ? aggrLifeCycleHandler
                    : create(parentLifyCycleHandler);
        }

        private final LifeCycleHandler parentLifyCycleHandler;
        
        private boolean isThreadSwitched;
        private boolean isBeforeWriteCalled;
        
        public AggregatingLifeCycleHandler(
                final LifeCycleHandler parentLifyCycleHandler) {
            this.parentLifyCycleHandler = parentLifyCycleHandler;
        }

        @Override
        public WritableMessage onThreadContextSwitch(final Connection connection,
                final WritableMessage message) {
            if (isThreadSwitched) {
                return message;
            }
            
            isThreadSwitched = true;
            return parentLifyCycleHandler.onThreadContextSwitch(connection, message);
        }

        @Override
        public void onBeforeWrite(final Connection connection,
                final WritableMessage message) {
            
            if (isBeforeWriteCalled) {
                return;
            }
            
            isBeforeWriteCalled = true;
            parentLifyCycleHandler.onBeforeWrite(connection, message);
        }
    }
}
