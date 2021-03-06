/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2008-2017 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.nio;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.grizzly.AbstractWriter;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.WritableMessage;
import org.glassfish.grizzly.WriteHandler;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.asyncqueue.AsyncQueueWriter;
import org.glassfish.grizzly.asyncqueue.AsyncWriteQueueRecord;
import org.glassfish.grizzly.asyncqueue.LifeCycleHandler;
import org.glassfish.grizzly.asyncqueue.RecordWriteResult;
import org.glassfish.grizzly.asyncqueue.TaskQueue;


/**
 * The {@link AsyncQueueWriter} implementation, based on the Java NIO
 * 
 *
 *
 *
 */
@SuppressWarnings("unchecked")
public abstract class AbstractNIOAsyncQueueWriter
        extends AbstractWriter<SocketAddress>
        implements AsyncQueueWriter<SocketAddress> {

    private final static Logger LOGGER = Grizzly.logger(AbstractNIOAsyncQueueWriter.class);

    protected final NIOTransport transport;

    protected volatile int maxPendingBytes = AUTO_SIZE;

    private volatile boolean isAllowDirectWrite = true;

    public AbstractNIOAsyncQueueWriter(NIOTransport transport) {
        this.transport = transport;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canWrite(final Connection<SocketAddress> connection) {
        final NIOConnection nioConnection = (NIOConnection) connection;
        final int connectionMaxPendingBytes = nioConnection.getMaxAsyncWriteQueueSize();
        
        if (connectionMaxPendingBytes < 0) {
            return true;
        }
        
        final TaskQueue<AsyncWriteQueueRecord> connectionQueue =
                (nioConnection).getAsyncWriteQueue();
        final int size = connectionQueue.size();
        
        return size == 0 || size < connectionMaxPendingBytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void notifyWritePossible(final Connection<SocketAddress> connection,
            final WriteHandler writeHandler) {
        final NIOConnection nioConnection = (NIOConnection) connection;
        final int connectionMaxPendingBytes = nioConnection.getMaxAsyncWriteQueueSize();
        
        nioConnection.getAsyncWriteQueue().notifyWhenOperable(writeHandler,
                connectionMaxPendingBytes);
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void setMaxPendingBytesPerConnection(final int maxPendingBytes) {
        this.maxPendingBytes = maxPendingBytes < AUTO_SIZE ? AUTO_SIZE : maxPendingBytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMaxPendingBytesPerConnection() {
        return maxPendingBytes;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isAllowDirectWrite() {
        return isAllowDirectWrite;
    }

    /**
     * {@inheritDoc}
     */
    public void setAllowDirectWrite(final boolean isAllowDirectWrite) {
        this.isAllowDirectWrite = isAllowDirectWrite;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void write(
            final Connection<SocketAddress> connection, final SocketAddress dstAddress,
            final WritableMessage message,
            final CompletionHandler<WriteResult<WritableMessage, SocketAddress>> completionHandler,
            final LifeCycleHandler lifeCycleHandler) {
        

        // create and initialize the write queue record
        final AsyncWriteQueueRecord queueRecord = createRecord(
                connection, message, completionHandler,
                dstAddress, lifeCycleHandler,
                !message.hasRemaining() || message.isExternal());
        
        if (connection == null) {
            queueRecord.notifyFailure(new IOException("Connection is null"));
            return;
        }

        if (!connection.isOpen()) {
            onWriteFailure(connection, queueRecord,
                    new IOException("Connection is closed",
                    connection.getCloseReason().getCause()));
            return;
        }
        
        final NIOConnection nioConnection = (NIOConnection) connection;
        
        // Get connection async write queue
        final TaskQueue<AsyncWriteQueueRecord> writeTaskQueue =
                nioConnection.getAsyncWriteQueue();

        // For empty buffer reserve 1 byte space
        final int bytesToReserve = (int) queueRecord.getBytesToReserve();

        final int pendingBytes = writeTaskQueue.reserveSpace(bytesToReserve);
        final boolean isCurrent = (pendingBytes == bytesToReserve);

        final boolean isLogFine = LOGGER.isLoggable(Level.FINEST);

        if (isLogFine) {
            doFineLog("AsyncQueueWriter.write connection={0}, record={1}, "
                    + "directWrite={2}, size={3}, isUncountable={4}, "
                    + "bytesToReserve={5}, pendingBytes={6}",
                    nioConnection, queueRecord, isCurrent, queueRecord.remaining(),
                    queueRecord.isUncountable(), bytesToReserve, pendingBytes);
        }

        final Reentrant reentrants = Reentrant.getWriteReentrant();
        
        try {
            if (!reentrants.inc()) {
                // Max number of reentrants is reached
                
                queueRecord.setMessage(
                        notifyThreadContextSwitch(nioConnection,
                        lifeCycleHandler, message));
                
                if (isCurrent) { //current but can't write because of maxReentrants limit
                    writeTaskQueue.setCurrentElement(queueRecord);
                    enqueOnReadyNotification(nioConnection);  // Enqueue async onReady() call
                } else {
                    writeTaskQueue.offer(queueRecord);
                }

                return;
            }
            
            if (isCurrent && isAllowDirectWrite) {
                queueRecord.notifyBeforeWrite();
                
                // If we can write directly - do it w/o creating queue record (simple)

                final RecordWriteResult writeResult = write0(nioConnection, queueRecord);
                final int bytesToRelease = (int) writeResult.bytesToReleaseAfterLastWrite();

                final boolean isFinished = queueRecord.isFinished();
                
                final int pendingBytesAfterRelease =
                        writeTaskQueue.releaseSpaceAndNotify(bytesToRelease);
                
                final boolean isQueueEmpty = (pendingBytesAfterRelease == 0);

                if (isLogFine) {
                    doFineLog("AsyncQueueWriter.write directWrite connection={0}, record={1}, "
                            + "isFinished={2}, remaining={3}, isUncountable={4}, "
                            + "bytesToRelease={5}, pendingBytesAfterRelease={6}",
                            nioConnection, queueRecord, isFinished, queueRecord.remaining(),
                            queueRecord.isUncountable(), bytesToRelease, pendingBytesAfterRelease);
                }
                
                if (isFinished) {
                    queueRecord.notifyCompleteAndRecycle();
                    if (!isQueueEmpty) {
                        enqueOnReadyNotification(nioConnection);  // Enqueue async onReady() call
                    }
                    return;
                }
            }
            
            queueRecord.setMessage(
                    notifyThreadContextSwitch(nioConnection, lifeCycleHandler, message));
            
            if (isLogFine) {
                doFineLog("AsyncQueueWriter.write queuing connection={0}, record={1}, "
                        + "size={2}, isUncountable={3}",
                        nioConnection, queueRecord, queueRecord.remaining(),
                        queueRecord.isUncountable());
            }
            
            if (isCurrent) { //current but not finished.                
                writeTaskQueue.setCurrentElement(queueRecord);
//              enableWriteInterest(connection); can't be used because of the possible clash with onReady method
                enqueOnReadyNotification(nioConnection);
            } else {
                writeTaskQueue.offer(queueRecord);
            }
        } catch (IOException e) {
            if (isLogFine) {
                LOGGER.log(Level.FINEST,
                        "AsyncQueueWriter.write exception. connection=" +
                        nioConnection + " record=" + queueRecord, e);
            }
            
            onWriteFailure(nioConnection, queueRecord, e);
        } finally {
            reentrants.dec();
        }        
    }
    
    /**
     * {@inheritDoc}
     * 
     * Note: NIO implementation relies on fact that onReady() must be called
     * from Selector thread.
     */
    @Override
    public AsyncResult onReady(final Connection connection) {
        
        final boolean isLogFine = LOGGER.isLoggable(Level.FINEST);
        final NIOConnection nioConnection = (NIOConnection) connection;
        if (!nioConnection.isOpen()) {
            return AsyncResult.COMPLETE;
        }
        
        final TaskQueue<AsyncWriteQueueRecord> writeTaskQueue =
                nioConnection.getAsyncWriteQueue();
        
        int bytesReleased = 0;
        boolean done = false;
        AsyncWriteQueueRecord queueRecord = null;
        try {
            while ((queueRecord = aggregate(writeTaskQueue)) != null) {

                queueRecord.notifyBeforeWrite();
                
                if (isLogFine) {
                    doFineLog("AsyncQueueWriter.processAsync beforeWrite "
                            + "connection={0} record={1}",
                            nioConnection, queueRecord);
                }                 

                final RecordWriteResult writeResult = write0(nioConnection, queueRecord);
                final int bytesToRelease = (int) writeResult.bytesToReleaseAfterLastWrite();

                final boolean isFinished = queueRecord.isFinished();

                bytesReleased += bytesToRelease;
                
                if (isLogFine) {
                    doFineLog("AsyncQueueWriter.processAsync written "
                            + "connection={0}, written={1}, done={2}, "
                            + "bytesToRelease={3}, bytesReleased={4}",
                            nioConnection, writeResult.lastWrittenBytes(), done,
                            bytesToRelease, bytesReleased);
                }  
                
//                if (isFinished && !context.isIOOptimizationsFlushed() &&
//                            bytesToRelease == writeTaskQueue.size()) {
//                    // Is here a chance that queue becomes empty?
//                    // If yes - we need to switch to manual io event processing
//                    // mode to *disable WRITE interest for SameThreadStrategy*,
//                    // so we don't have either neverending WRITE events processing
//                    // or stuck, when other thread tried to add data to the queue.
//                    context.flushIOOptimizations();
//                }


                final int remaining = writeTaskQueue.releaseSpaceAndNotify(bytesToRelease);
                done = (remaining == 0);

                if (isFinished) {
                    finishQueueRecord(nioConnection, queueRecord);
                    
                    if (done) {
                        return AsyncResult.COMPLETE;
                    }
                } else { // if there is still some data in current message
                    queueRecord.notifyIncomplete();
                    writeTaskQueue.setCurrentElement(queueRecord);
                    if (isLogFine) {
                        doFineLog("AsyncQueueWriter.processAsync onReadyToWrite "
                                + "connection={0} peekRecord={1}",
                                nioConnection, queueRecord);
                    }

                    // If connection is closed - this will fail,
                    // and onWriteFailure called properly
                    return AsyncResult.HAS_MORE;
                }
            }

            if (!done) {
                // Counter shows there should be some elements in queue,
                // but seems write() method still didn't add them to a queue
                // so we can release the thread for now
                return AsyncResult.EXPECTING_MORE;
            }
        } catch (IOException e) {
            if (isLogFine) {
                LOGGER.log(Level.FINEST, "AsyncQueueWriter.processAsync "
                        + "exception connection=" + nioConnection + " peekRecord=" +
                        queueRecord, e);
            }
            onWriteFailure(nioConnection, queueRecord, e);
        }
        
        return AsyncResult.COMPLETE;
    }

    private static void finishQueueRecord(final NIOConnection nioConnection,
            final AsyncWriteQueueRecord queueRecord) {
        final boolean isLogFine = LOGGER.isLoggable(Level.FINEST);
        
        if (isLogFine) {
            doFineLog("AsyncQueueWriter.processAsync finished "
                    + "connection={0} record={1}",
                    nioConnection, queueRecord);
        }

        if (queueRecord != null) {
            queueRecord.notifyCompleteAndRecycle();
        }
        
        if (isLogFine) {
            doFineLog("AsyncQueueWriter.processAsync finishQueueRecord "
                    + "connection={0} queueRecord={1}",
                    nioConnection, queueRecord);
        }
    }
        
    private static WritableMessage notifyThreadContextSwitch(
            final Connection connection,
            final LifeCycleHandler lifeCycleHandler,
            final WritableMessage message) {
        
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST,
                    "AsyncQueueWriter.write notifyThreadContextSwitch. connection={0} lifeCycleHandler={1} size={2}",
                    new Object[] {connection, lifeCycleHandler, message.remaining()});
        }
        
        return lifeCycleHandler == null ? message
                : lifeCycleHandler.onThreadContextSwitch(connection, message);
    }

    protected AsyncWriteQueueRecord createRecord(final Connection connection,
            final WritableMessage message,
            final CompletionHandler<WriteResult<WritableMessage, SocketAddress>> completionHandler,
            final SocketAddress dstAddress,
            final LifeCycleHandler lifeCycleHandler,
            final boolean isUncountable) {
        return AsyncWriteQueueRecord.create(connection, message,
                completionHandler, dstAddress, lifeCycleHandler, isUncountable);
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean isReady(final Connection connection) {
        final TaskQueue connectionQueue =
                ((NIOConnection) connection).getAsyncWriteQueue();

        return connectionQueue != null && !connectionQueue.isEmpty();
    }
       
    private static void doFineLog(final String msg, final Object... params) {
        LOGGER.log(Level.FINEST, msg, params);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onClose(final Connection connection) {
        final NIOConnection nioConnection =
                (NIOConnection) connection;
        final TaskQueue<AsyncWriteQueueRecord> writeQueue =
                nioConnection.getAsyncWriteQueue();
        writeQueue.onClose(nioConnection.getCloseReason().getCause());
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public final void close() {
    }
        
    protected static void onWriteFailure(final Connection connection,
            final AsyncWriteQueueRecord failedRecord, final Throwable e) {

        failedRecord.notifyFailure(e);
        connection.closeSilently();
    }
    
    protected abstract RecordWriteResult write0(NIOConnection connection,
            AsyncWriteQueueRecord queueRecord)
            throws IOException;

    /**
     * Aggregates records in a queue to be written as one chunk.
     */
    protected AsyncWriteQueueRecord aggregate(
            final TaskQueue<AsyncWriteQueueRecord> connectionQueue) {
        return connectionQueue.poll();
    }
    
    private static void enableWriteInterest(final NIOConnection connection)
            throws IOException {
        connection.registerKeyInterest(SelectionKey.OP_WRITE);
    }

    private void enqueOnReadyNotification(final NIOConnection connection) {
        connection.enqueOpWriteReady();
    }
}
