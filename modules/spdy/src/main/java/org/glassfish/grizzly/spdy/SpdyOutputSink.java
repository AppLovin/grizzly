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
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.asyncqueue.MessageCloner;
import org.glassfish.grizzly.asyncqueue.AsyncQueueRecord;
import org.glassfish.grizzly.asyncqueue.TaskQueue;
import org.glassfish.grizzly.asyncqueue.WritableMessage;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpPacket;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.spdy.SpdyStream.Termination;
import org.glassfish.grizzly.spdy.SpdyStream.TerminationType;
import org.glassfish.grizzly.spdy.frames.DataFrame;
import org.glassfish.grizzly.spdy.frames.SpdyFrame;
import org.glassfish.grizzly.spdy.frames.SynReplyFrame;
import org.glassfish.grizzly.spdy.frames.SynStreamFrame;
import org.glassfish.grizzly.spdy.frames.WindowUpdateFrame;


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
    
    // current output queue size
//    private final AtomicInteger outputQueueSize = new AtomicInteger();
    final TaskQueue<OutputQueueRecord> outputQueue =
            TaskQueue.createTaskQueue(new TaskQueue.MutableMaxQueueSize() {

        @Override
        public int getMaxQueueSize() {
            return spdyStream.getPeerWindowSize();
        }
    });
    
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
    public void onPeerWindowUpdate(final int delta) {
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
            AggregatingMessageCloner messageCloner =
                    outputQueueRecord.messageCloner;
            boolean isLast = outputQueueRecord.isLast;
            
            // check if output record's buffer is fitting into window size
            // if not - split it into 2 parts: part to send, part to keep in the queue
            final int idx = checkOutputWindow(outputQueueRecord.buffer);
            
            final Buffer dataChunkToStore = splitOutputBufferIfNeeded(
                    outputQueueRecord.buffer, idx);
            final Buffer dataChunkToSend = outputQueueRecord.buffer;

            outputQueueRecord = null;
            
            // if there is a chunk to store
            if (dataChunkToStore != null && dataChunkToStore.hasRemaining()) {
                // Create output record for the chunk to be stored
                outputQueueRecord =
                        new OutputQueueRecord(dataChunkToStore,
                        completionHandler, messageCloner, isLast);
                outputQueueRecord.incCompletionCounter();
                
                // reset isLast for the current chunk
                isLast = false;
            }

            // if there is a chunk to sent
            if (dataChunkToSend != null &&
                    (dataChunkToSend.hasRemaining() || isLast)) {
                final int dataChunkToSendSize = dataChunkToSend.remaining();
                
                // update unconfirmed bytes counter
                unconfirmedBytes.addAndGet(dataChunkToSendSize);
                outputQueue.releaseSpace(dataChunkToSendSize);

                DataFrame dataFrame = DataFrame.builder().data(dataChunkToSend).
                        last(isLast).streamId(spdyStream.getStreamId()).build();

                // send a spdydata frame
                writeDownStream(dataFrame, completionHandler,
                        messageCloner, isLast);
                
                // pass peer-window-size as max, even though these values are independent.
                // later we may want to decouple outputQueue's max-size and peer-window-size
                outputQueue.doNotify();
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
     * @param messageCloner the {@link MessageCloner}, which will be able to
     *          clone the message in case it can't be completely written in the
     *          current thread.
     * @throws IOException 
     */
    synchronized <E> void writeDownStream(final HttpPacket httpPacket,
            CompletionHandler<WriteResult> completionHandler,
            MessageCloner<WritableMessage> messageCloner)
            throws IOException {

        // if the last frame (fin flag == 1) has been queued already - throw an IOException
        if (isTerminated()) {
            throw new IOException(terminationFlag.getDescription());
        }
        
        final HttpHeader httpHeader = httpPacket.getHttpHeader();
        final HttpContent httpContent = HttpContent.isContent(httpPacket) ? (HttpContent) httpPacket : null;
        
        int framesAvailFlag = 0;
        SpdyFrame headerFrame = null;
        
        // If HTTP header hasn't been commited - commit it
        if (!httpHeader.isCommitted()) {
            // do we expect any HTTP payload?
            final boolean isNoContent = !httpHeader.isExpectContent() ||
                    (httpContent != null && httpContent.isLast() &&
                    !httpContent.getContent().hasRemaining());
            
            // encode HTTP packet header
            if (!httpHeader.isRequest()) {
                Buffer compressedHeaders;
                synchronized (spdySession) { // TODO This sync point should be revisited for a more optimal solution.
                    compressedHeaders = SpdyEncoderUtils.encodeSynReplyHeaders(spdySession,
                                                                               (HttpResponsePacket) httpHeader);
                }
                headerFrame = SynReplyFrame.builder().streamId(spdyStream.getStreamId()).
                        last(isNoContent).compressedHeaders(compressedHeaders).build();
            } else {
                Buffer compressedHeaders;
                synchronized (spdySession) {
                    compressedHeaders = SpdyEncoderUtils.encodeSynStreamHeaders(spdySession,
                                                                               (HttpRequestPacket) httpHeader);
                }
                headerFrame = SynStreamFrame.builder().streamId(spdyStream.getStreamId()).
                        associatedStreamId(spdyStream.getAssociatedToStreamId()).
                        last(isNoContent).compressedHeaders(compressedHeaders).build();
            }

            httpHeader.setCommitted(true);
            framesAvailFlag = 1;
            
            if (isNoContent) {
                // if we don't expect any HTTP payload, mark this frame as
                // last and return
                writeDownStream(headerFrame, completionHandler,
                        messageCloner, isNoContent);
                return;
            }
        }
        
        SpdyFrame dataFrame = null;
        OutputQueueRecord outputQueueRecord = null;
        boolean isLast = false;
        
        // if there is a payload to send now
        if (httpContent != null) {
            AggregatingCompletionHandler aggrCompletionHandler = null;
            AggregatingMessageCloner aggrMessageCloner = null;
            
            isLast = httpContent.isLast();
            Buffer data = httpContent.getContent();
            final int dataSize = data.remaining();
            
            // Check if output queue is not empty - add new element
            if (outputQueue.reserveSpace(dataSize) > dataSize) {
                // if the queue is not empty - the headers should have been sent
                assert headerFrame == null;

                aggrCompletionHandler = AggregatingCompletionHandler.create(completionHandler);
                aggrMessageCloner = AggregatingMessageCloner.create(messageCloner);

                if (aggrMessageCloner != null) {
                    data = (Buffer) aggrMessageCloner.clone(
                            spdySession.getConnection(), data);
                }
                
                outputQueueRecord = new OutputQueueRecord(
                        data, aggrCompletionHandler, aggrMessageCloner, isLast);
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
            
            
            // check if output record's buffer is fitting into window size
            // if not - split it into 2 parts: part to send, part to keep in the queue
            final int fitWindowIdx = checkOutputWindow(data);

            // if there is a chunk to store
            if (fitWindowIdx != -1) {
                aggrCompletionHandler = AggregatingCompletionHandler.create(
                        completionHandler, aggrCompletionHandler);
                aggrMessageCloner = AggregatingMessageCloner.create(
                        messageCloner, aggrMessageCloner);
                
                if (aggrMessageCloner != null) {
                    data = (Buffer) aggrMessageCloner.clone(
                            spdySession.getConnection(), data);
                }
                
                final Buffer dataChunkToStore = splitOutputBufferIfNeeded(
                        data, fitWindowIdx);
                
                // Create output record for the chunk to be stored
                outputQueueRecord = new OutputQueueRecord(dataChunkToStore,
                                                            aggrCompletionHandler,
                                                            aggrMessageCloner,
                                                            isLast);
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
            
            completionHandler = aggrCompletionHandler == null ?
                    completionHandler :
                    aggrCompletionHandler;
            messageCloner = aggrMessageCloner == null ?
                    messageCloner :
                    aggrMessageCloner;
        }

        switch (framesAvailFlag) {
            case 1: {
                writeDownStream(headerFrame, completionHandler,
                        messageCloner, false);
                break;
            }
            case 2: {
                writeDownStream(dataFrame, completionHandler,
                        messageCloner, isLast);
                break;
            }
                
            case 3: {
                writeDownStream(asList(headerFrame, dataFrame),
                        completionHandler, messageCloner, isLast);
                break;
            }
                
            default: break; // we can't write even single byte from the buffer
        }
        
        if (outputQueueRecord == null) {
            return;
        }
        
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
                final AggregatingMessageCloner aggrMessageCloner =
                        outputQueueRecord.messageCloner;
                
                isLast = outputQueueRecord.isLast;
                
                final int fitWindowIdx = checkOutputWindow(outputQueueRecord.buffer);
                
                final Buffer dataChunkToStore = splitOutputBufferIfNeeded(
                        outputQueueRecord.buffer, fitWindowIdx);
                final Buffer dataChunkToSend = outputQueueRecord.buffer;
                
                outputQueueRecord = null;

                // if there is a chunk to store
                if (dataChunkToStore != null && dataChunkToStore.hasRemaining()) {
                    // Create output record for the chunk to be stored
                    outputQueueRecord = new OutputQueueRecord(dataChunkToStore,
                                                                aggrCompletionHandler,
                                                                aggrMessageCloner,
                                                                isLast);
                    outputQueueRecord.incCompletionCounter();
                    
                    // reset isLast for the current chunk
                    isLast = false;
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
                            aggrMessageCloner, isLast);
                }
            } else {
                break; // will be (or already) written asynchronously
            }
        } while (outputQueueRecord != null);
    }
    
    /**
     * The method is responsible for checking the current output window size.
     * The returned integer value is an index in the passed buffer. The buffer's
     * [position; index] part fits to output window and might be sent now,
     * the remainder (index; limit) has to be stored in queue and sent later.
     * The <tt>-1</tt> returned value means entire buffer could be sent now.
     * 
     * @param data the output queue data.
     */
    private int checkOutputWindow(final Buffer data) {
        final int size = data.remaining();
        
        // take a snapshot of the current output window state
        final int unconfirmedBytesNow = unconfirmedBytes.get();
        final int windowSizeLimit = spdyStream.getPeerWindowSize();

        // Check if data chunk is overflowing the output window
        if (unconfirmedBytesNow + size > windowSizeLimit) { // Window overflowed
            final int dataSizeAllowedToSend = windowSizeLimit - unconfirmedBytesNow;

            // Split data chunk into 2 chunks - one to be sent now and one to be stored in the output queue
            return data.position() + dataSizeAllowedToSend;
        }

        return -1;
    }

    private Buffer splitOutputBufferIfNeeded(final Buffer buffer, final int idx) {
        if (idx == -1) {
            return null;
        }

        return buffer.split(idx);
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
            final MessageCloner messageCloner,
            final boolean isLast) {
        writeDownStream0(frame, completionHandler, messageCloner);
        
        if (isLast) {
            terminate();
        }
    }
    
    private void writeDownStream0(final SpdyFrame frame,
            final CompletionHandler<WriteResult> completionHandler,
            final MessageCloner messageCloner) {
        spdySession.getDownstreamChain().write(spdySession.getConnection(),
                null, frame, completionHandler, messageCloner);
    }
    
    private void writeDownStream0(final SpdyFrame frame,
            final CompletionHandler<WriteResult> completionHandler) {
        spdySession.getDownstreamChain().write(spdySession.getConnection(),
                null, frame, completionHandler, (MessageCloner) null);
    }
    
    private void writeDownStream(final List<SpdyFrame> frames,
            final CompletionHandler<WriteResult> completionHandler,
            final MessageCloner messageCloner,
            final boolean isLast) {
        writeDownStream0(frames, completionHandler, messageCloner);
        
        if (isLast) {
            terminate();
        }
    }

    private void writeDownStream0(final List<SpdyFrame> frames,
            final CompletionHandler<WriteResult> completionHandler,
            final MessageCloner messageCloner) {
        spdySession.getDownstreamChain().write(spdySession.getConnection(),
                null, frames, completionHandler, messageCloner);
    }

    private List<SpdyFrame> asList(final SpdyFrame frame1, final SpdyFrame frame2) {
        if (tmpOutputList == null) {
            tmpOutputList = new ArrayList<SpdyFrame>(2);
        }
        
        tmpOutputList.add(frame1);
        tmpOutputList.add(frame2);
        
        return tmpOutputList;
    }
    
    synchronized void close() {
        if (!isLastFrameQueued && !isTerminated()) {
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

    void terminate() {
        terminate(new Termination(TerminationType.FIN,
                    "The output stream has been terminated"));
    }
    
    synchronized void terminate(final Termination terminationFlag) {
        if (!isTerminated()) {
            this.terminationFlag = terminationFlag;
            outputQueue.onClose();
            // NOTIFY STREAM
            spdyStream.onOutputClosed();
        }
    }
    
    boolean isTerminated() {
        return terminationFlag != null;
    }
    
    private void writeEmptyFin() {
        if (!isTerminated()) {
            // SEND LAST
            DataFrame dataFrame =
                    DataFrame.builder().streamId(spdyStream.getStreamId()).
                            data(Buffers.EMPTY_BUFFER).last(true).build();
            writeDownStream0(dataFrame, null);

            terminate();
        }
    }

    private boolean isWantToWrite(final int unconfirmedBytesNow,
            final int windowSizeLimit) {
        return unconfirmedBytesNow < (windowSizeLimit * 3 / 4);
    }

    private static class OutputQueueRecord extends AsyncQueueRecord<WriteResult>{
        private final Buffer buffer;
        private final AggregatingCompletionHandler aggrCompletionHandler;
        private final AggregatingMessageCloner messageCloner;
        
        private final boolean isLast;
        
        public OutputQueueRecord(final Buffer buffer,
                final AggregatingCompletionHandler completionHandler,
                final AggregatingMessageCloner messageCloner,
                final boolean isLast) {
            super(null, null, null, null);
            
            this.buffer = buffer;
            this.aggrCompletionHandler = completionHandler;
            this.messageCloner = messageCloner;
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

    private static class AggregatingMessageCloner implements MessageCloner<WritableMessage> {
        
        private static AggregatingMessageCloner create(
                final MessageCloner<WritableMessage> parentMessageCloner) {
            return parentMessageCloner == null
                    ? null
                    : new AggregatingMessageCloner(parentMessageCloner);
        }

        private static AggregatingMessageCloner create(
                final MessageCloner<WritableMessage> parentMessageCloner,
                final AggregatingMessageCloner aggrMessageCloner) {
            return aggrMessageCloner != null
                    ? aggrMessageCloner
                    : create(parentMessageCloner);
        }

        private final MessageCloner<WritableMessage> parentMessageCloner;
        
        private boolean isCloned;
        
        public AggregatingMessageCloner(
                final MessageCloner<WritableMessage> parentMessageCloner) {
            this.parentMessageCloner = parentMessageCloner;
        }

        @Override
        public WritableMessage clone(Connection connection,
                WritableMessage originalMessage) {
            if (isCloned) {
                return originalMessage;
            }
            
            isCloned = true;
            return parentMessageCloner.clone(connection, originalMessage);
        }
    }
}
