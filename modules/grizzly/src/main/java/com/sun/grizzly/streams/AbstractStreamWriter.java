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
package com.sun.grizzly.streams;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.LongBuffer;
import java.nio.FloatBuffer;
import java.nio.DoubleBuffer;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.sun.grizzly.Buffer;
import com.sun.grizzly.CompletionHandler;
import com.sun.grizzly.Connection;
import com.sun.grizzly.impl.ReadyFutureImpl;

/**
 * Write the primitive Java type to the current ByteBuffer.  If it doesn't
 * fit, call the BufferHandler, and write to the result, which becomes the
 * new current ByteBuffer.  Arrays will be written across multiple ByteBuffers
 * if necessary, but all primitives will be written to a single ByteBuffer.
 * 
 * @author Ken Cavanaugh
 */
public abstract class AbstractStreamWriter implements StreamWriter {
    protected static final Integer ZERO = new Integer(0);
    protected static final Future<Integer> ZERO_READY_FUTURE =
            new ReadyFutureImpl<Integer>(ZERO);
    
    private Connection connection;

    private boolean isBlocking;

    protected int bufferSize = 8192;
    
    protected Buffer buffer;
    private long timeoutMillis = 30000;
    
    private boolean isClosed = false;

    protected AbstractStreamWriter() {
        this(null);
    }

    /** Create a new ByteBufferWriter.  An instance maintains a current buffer
     * for use in writing.  Whenever the current buffer is insufficient to hold
     * the required data, the BufferHandler is called, and the result of the
     * handler is the new current buffer. The handler is responsible for
     * the disposition of the contents of the old buffer.
     */
    protected AbstractStreamWriter(final Connection connection) {
        setConnection(connection);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isBlocking() {
        return isBlocking;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBlocking(boolean isBlocking) {
        this.isBlocking = isBlocking;
    }

    protected Future<Integer> overflow() throws IOException {
        return overflow(null);
    }

    protected Future<Integer> overflow(
            CompletionHandler<Integer> completionHandler) throws IOException {
        // Why was this here: buffer.underlying().limit( buffer.underlying().position() ) ;
        Future future = null;

        if (buffer != null) {
            if (buffer.position() > 0) {
                future = flush0(buffer, completionHandler);
                if (!future.isDone()) {
                    buffer = newBuffer(bufferSize);
                } else {
                    checkBufferSize();
                }

                initBuffer();
            } else {
                checkBufferSize();

                future = ZERO_READY_FUTURE;
                if (completionHandler != null) {
                    completionHandler.completed(connection, ZERO);
                }
            }
        } else {
            buffer = newBuffer(bufferSize);
            initBuffer();
        }

        return future;
    }

    private void checkBufferSize() {
        if (buffer.capacity() != bufferSize) {
            buffer = reallocateBuffer(buffer, bufferSize);
        }
    }

    private void initBuffer() {
        buffer.clear();
    }

    /**
     * Cause the overflow handler to be called even if buffer is not full.
     */
    @Override
    public Future<Integer> flush() throws IOException {
        return flush(null);
    }

    /**
     * Cause the overflow handler to be called even if buffer is not full.
     */
    @Override
    public Future<Integer> flush(CompletionHandler<Integer> completionHandler)
            throws IOException {
        return overflow(completionHandler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isClosed() {
        return isClosed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        close(null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Integer> close(CompletionHandler<Integer> completionHandler)
            throws IOException {
        try {
            return close0(completionHandler);
        } finally {
            buffer = null;
            isClosed = true;
        }
    }

    /** Ensure that the requested amount of space is available
     */
    public void ensure(final int size) throws IOException {
        if (isClosed) {
            throw new IllegalStateException(
                    "ByteBufferWriter is closed");
        }

        if ((buffer == null) || (buffer.remaining() < size)) {
            overflow();
        }

        if (buffer.remaining() < size) {
            throw new RuntimeException("New allocated buffer is too small");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeBuffer(Buffer b) throws IOException {
        writeBuffer(b, null);
    }

    protected void writeBuffer(Buffer b, CompletionHandler completionHandler)
            throws IOException {
        if (buffer != null && buffer.position() > 0) {
            overflow();
        }

        if (b != null && b.hasRemaining()) {
            b.position(b.limit());
            flush0(b, completionHandler);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeStream(StreamReader streamReader) throws IOException {
        AbstractStreamReader readerImpl = (AbstractStreamReader) streamReader;
        Buffer readerBuffer;
        while ((readerBuffer = readerImpl.getBuffer()) != null) {
            readerImpl.finishBuffer();
            writeBuffer(readerBuffer,
                    new DisposeBufferCompletionHandler(readerBuffer));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeBoolean(final boolean data) throws IOException {
        ensure(1);
        final byte value = data ? (byte) 1 : (byte) 0;
        buffer.put(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeByte(final byte data) throws IOException {
        ensure(1);
        buffer.put(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeChar(final char data) throws IOException {
        ensure(2);
        buffer.putChar(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeShort(final short data) throws IOException {
        ensure(2);
        buffer.putShort(data);
    }

    @Override
    public void writeInt(final int data) throws IOException {
        ensure(4);
        buffer.putInt(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeLong(final long data) throws IOException {
        ensure(8);
        buffer.putLong(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeFloat(final float data) throws IOException {
        ensure(4);
        buffer.putFloat(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeDouble(final double data) throws IOException {
        ensure(8);
        buffer.putDouble(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeBooleanArray(final boolean[] data) throws IOException {
        ensure(1);
        int ctr = 0;
        while (ctr < data.length) {
            final int dataSizeToWrite = Math.min(data.length - ctr,
                    buffer.remaining());

            for (int ctr2 = ctr; ctr2 < ctr + dataSizeToWrite; ctr2++) {
                buffer.put((byte) (data[ctr2] ? 1 : 0));
            }
            ctr += dataSizeToWrite;

            if (ctr == data.length) {
                break;
            }

            overflow();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeByteArray(final byte[] data) throws IOException {
        writeByteArray(data, 0, data.length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeByteArray(byte[] data, int offset, int length) throws IOException {
        ensure(1);
        int ctr = 0;
        while (true) {
            final int dataSizeToWrite = Math.min(length - ctr,
                    buffer.remaining());
            buffer.put(data, offset + ctr, dataSizeToWrite);
            ctr += dataSizeToWrite;

            if (ctr == length) {
                break;
            }
            overflow();
        }
    }



    /**
     * {@inheritDoc}
     */
    @Override
    public void writeCharArray(final char[] data) throws IOException {
        ensure(2);
        int ctr = 0;
        while (true) {
            final ByteBuffer current = (ByteBuffer) buffer.underlying();
            final CharBuffer typedBuffer = current.asCharBuffer();
            final int dataSizeToWrite = Math.min(data.length - ctr,
                    typedBuffer.remaining());
            typedBuffer.put(data, ctr, dataSizeToWrite);
            buffer.position(typedBuffer.position() * 2 + current.position());
            ctr += dataSizeToWrite;
            if (ctr == data.length) {
                break;
            }
            overflow();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeShortArray(final short[] data) throws IOException {
        ensure(2);
        int ctr = 0;
        while (true) {
            final ByteBuffer current = (ByteBuffer) buffer.underlying();
            final ShortBuffer typedBuffer = current.asShortBuffer();
            final int dataSizeToWrite = Math.min(data.length - ctr,
                    typedBuffer.limit() - typedBuffer.position());
            typedBuffer.put(data, ctr, dataSizeToWrite);
            current.position(typedBuffer.position() * 2 + current.position());
            ctr += dataSizeToWrite;
            if (ctr == data.length) {
                break;
            }

            overflow();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeIntArray(final int[] data) throws IOException {
        ensure(4);
        int ctr = 0;
        while (true) {
            final ByteBuffer current = (ByteBuffer) buffer.underlying();
            final IntBuffer typedBuffer = current.asIntBuffer();
            final int dataSizeToWrite = Math.min(data.length - ctr,
                    typedBuffer.limit() - typedBuffer.position());
            typedBuffer.put(data, ctr, dataSizeToWrite);
            current.position(typedBuffer.position() * 4 + current.position());
            ctr += dataSizeToWrite;
            if (ctr == data.length) {
                break;
            }
            overflow();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeLongArray(final long[] data) throws IOException {
        ensure(8);
        int ctr = 0;
        while (true) {
            final ByteBuffer current = (ByteBuffer) buffer.underlying();
            final LongBuffer typedBuffer = current.asLongBuffer();
            final int dataSizeToWrite = Math.min(data.length - ctr,
                    typedBuffer.limit() - typedBuffer.position());
            typedBuffer.put(data, ctr, dataSizeToWrite);
            ctr += dataSizeToWrite;
            current.position(typedBuffer.position() * 8 + current.position());
            if (ctr == data.length) {
                break;
            }
            overflow();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeFloatArray(final float[] data) throws IOException {
        ensure(4);
        int ctr = 0;
        while (true) {
            final ByteBuffer current = (ByteBuffer) buffer.underlying();
            final FloatBuffer typedBuffer = current.asFloatBuffer();
            final int dataSizeToWrite = Math.min(data.length - ctr,
                    typedBuffer.limit() - typedBuffer.position());
            typedBuffer.put(data, ctr, dataSizeToWrite);
            current.position(typedBuffer.position() * 4 + current.position());
            ctr += dataSizeToWrite;

            if (ctr == data.length) {
                break;
            }

            overflow();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeDoubleArray(final double[] data) throws IOException {
        ensure(8);
        int ctr = 0;
        while (true) {
            final ByteBuffer current = (ByteBuffer) buffer.underlying();
            final DoubleBuffer typedBuffer = current.asDoubleBuffer();
            final int dataSizeToWrite = Math.min(data.length - ctr,
                    typedBuffer.limit() - typedBuffer.position());
            typedBuffer.put(data, ctr, dataSizeToWrite);
            current.position(typedBuffer.position() * 8 + current.position());
            ctr += dataSizeToWrite;
            if (ctr == data.length) {
                break;
            }

            overflow();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Connection getConnection() {
        return connection;
    }

    public void setConnection(Connection connection) {
        if (connection != null) {
            bufferSize = connection.getWriteBufferSize();
            isBlocking = connection.isBlocking();
        }
        
        this.connection = connection;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Buffer getBuffer() {
        return buffer;
    }

    protected Buffer newBuffer(int size) {
         return getConnection().getTransport().getMemoryManager().allocate(size);
    }

    private Buffer reallocateBuffer(Buffer oldBuffer, int size) {
        return getConnection().getTransport().getMemoryManager().reallocate(oldBuffer, size);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getBufferSize() {
        return bufferSize;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBufferSize(int size) {
        this.bufferSize = size;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getTimeout(TimeUnit timeunit) {
        return timeunit.convert(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setTimeout(long timeout, TimeUnit timeunit) {
        timeoutMillis = TimeUnit.MILLISECONDS.convert(timeout, timeunit);
    }

    protected abstract Future<Integer> flush0(Buffer buffer,
            CompletionHandler<Integer> completionHandler) throws IOException;

    protected abstract Future<Integer> close0(
            CompletionHandler<Integer> completionHandler) throws IOException;

    public static class DisposeBufferCompletionHandler
            implements CompletionHandler {

        private Buffer buffer;

        public DisposeBufferCompletionHandler(Buffer buffer) {
            this.buffer = buffer;
        }
        
        @Override
        public void cancelled(Connection connection) {
            disposeBuffer();
        }

        @Override
        public void failed(Connection connection, Throwable throwable) {
            disposeBuffer();
        }

        @Override
        public void completed(Connection connection, Object result) {
            disposeBuffer();
        }

        @Override
        public void updated(Connection connection, Object result) {
        }

        protected void disposeBuffer() {
            buffer.dispose();
        }
    }
}

