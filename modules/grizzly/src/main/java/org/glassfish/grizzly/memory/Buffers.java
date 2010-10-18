/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2008-2010 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.memory;

import java.io.UnsupportedEncodingException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.grizzly.Appender;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.TransportFactory;

/**
 * Class has useful methods to simplify the work with {@link Buffer}s.
 *
 * @see Buffer
 *
 * @author Alexey Stashok
 */

public class Buffers {
    private static final Logger LOGGER = Grizzly.logger(Buffers.class);

    public static final Appender BUFFER_APPENDER = new Appender<Buffer>() {
        @Override
        public Buffer append(Buffer element1, Buffer element2) {
            if (element1.isComposite()) {
                ((CompositeBuffer) element1).append(element2);
                return element1;
            }

            final CompositeBuffer compositeBuffer =
                    CompositeBuffer.newBuffer(null,
                    element1);
            compositeBuffer.append(element2);
            return compositeBuffer;
        }

    };
    public static final ByteBuffer EMPTY_BYTE_BUFFER = ByteBuffer.allocate(0);
    public static final ByteBuffer[] EMPTY_BYTE_BUFFER_ARRAY = new ByteBuffer[0];

    public static final Buffer EMPTY_BUFFER;

    static {
        EMPTY_BUFFER = TransportFactory.getInstance().getDefaultMemoryManager().allocate(0).asReadOnlyBuffer();
        EMPTY_BUFFER.allowBufferDispose(false);
    }

    /**
     * Returns {@link Buffer}, which wraps the {@link String}.
     *
     * @param memoryManager {@link MemoryManager}, which should be
     *                       used for wrapping.
     * @param s {@link String}
     *
     * @return {@link Buffer} wrapper on top of passed {@link String}.
     */
    public static <E extends Buffer> E wrap(MemoryManager<E> memoryManager,
            String s) {
        return wrap(memoryManager, s, Charset.defaultCharset());
    }

    /**
     * Returns {@link Buffer}, which wraps the {@link String} with the specific
     * {@link Charset}.
     *
     * @param memoryManager {@link MemoryManager}, which should be
     *                       used for wrapping.
     * @param s {@link String}
     * @param charset {@link Charset}, which will be used, when converting
     * {@link String} to byte array.
     *
     * @return {@link Buffer} wrapper on top of passed {@link String}.
     */
    public static <E extends Buffer> E wrap(MemoryManager<E> memoryManager,
            String s, Charset charset) {
        try {
            byte[] byteRepresentation = s.getBytes(charset.name());
            return wrap(memoryManager, byteRepresentation);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns {@link Buffer}, which wraps the byte array.
     *
     * @param memoryManager {@link MemoryManager}, which should be
     *                       used for wrapping.
     * @param array byte array to wrap.
     *
     * @return {@link Buffer} wrapper on top of passed byte array.
     */
    public static <E extends Buffer> E wrap(MemoryManager<E> memoryManager,
            byte[] array) {
        return wrap(memoryManager, array, 0, array.length);
    }

    /**
     * Returns {@link Buffer}, which wraps the part of byte array with
     * specific offset and length.
     *
     * @param memoryManager {@link MemoryManager}, which should be
     *                       used for wrapping.
     * @param array byte array to wrap
     * @param offset byte buffer offset
     * @param length byte buffer length
     *
     * @return {@link Buffer} wrapper on top of passed byte array.
     */
    public static <E extends Buffer> E wrap(MemoryManager<E> memoryManager,
            byte[] array, int offset, int length) {
        if (memoryManager == null) {
            memoryManager = TransportFactory.getInstance().getDefaultMemoryManager();
        }

        if (memoryManager instanceof WrapperAware) {
            return ((WrapperAware<E>) memoryManager).wrap(array, offset, length);
        }

        E buffer = memoryManager.allocate(length);
        buffer.put(array, offset, length);
        buffer.flip();
        return buffer;
    }

    /**
     * Returns {@link Buffer}, which wraps the {@link ByteBuffer}.
     *
     * @param memoryManager {@link MemoryManager}, which should be
     *                       used for wrapping.
     * @param byteBuffer {@link ByteBuffer} to wrap
     *
     * @return {@link Buffer} wrapper on top of passed {@link ByteBuffer}.
     */
    public static <E extends Buffer> E wrap(MemoryManager<E> memoryManager,
            ByteBuffer byteBuffer) {
        if (memoryManager instanceof WrapperAware) {
            return ((WrapperAware<E>) memoryManager).wrap(byteBuffer);
        } else if (byteBuffer.hasArray()) {
            return wrap(memoryManager, byteBuffer.array(),
                    byteBuffer.arrayOffset() + byteBuffer.position(),
                    byteBuffer.remaining());
        }

        throw new IllegalStateException("Can not wrap ByteBuffer");
    }



    /**
     * Slice {@link ByteBuffer} of required size from big chunk.
     * Passed chunk position will be changed, after the slicing (chunk.position += size).
     *
     * @param chunk big {@link ByteBuffer} pool.
     * @param size required slice size.
     *
     * @return sliced {@link ByteBuffer} of required size.
     */
    public static ByteBuffer slice(ByteBuffer chunk, int size) {
        chunk.limit(chunk.position() + size);
        ByteBuffer view = chunk.slice();
        chunk.position(chunk.limit());
        chunk.limit(chunk.capacity());

        return view;
    }


    /**
     * Get the {@link ByteBuffer}'s slice basing on its passed position and limit.
     * Position and limit values of the passed {@link ByteBuffer} won't be changed.
     * The result {@link ByteBuffer} position will be equal to 0, and limit
     * equal to number of sliced bytes (limit - position).
     *
     * @param byteBuffer {@link ByteBuffer} to slice/
     * @param position the position in the passed byteBuffer, the slice will start from.
     * @param limit the limit in the passed byteBuffer, the slice will be ended.
     *
     * @return sliced {@link ByteBuffer} of required size.
     */
    public static ByteBuffer slice(final ByteBuffer byteBuffer,
            final int position, final int limit) {
        final int oldPos = byteBuffer.position();
        final int oldLimit = byteBuffer.limit();

        setPositionLimit(byteBuffer, position, limit);

        final ByteBuffer slice = byteBuffer.slice();

        setPositionLimit(byteBuffer, oldPos, oldLimit);

        return slice;
    }

    public static String toStringContent(ByteBuffer byteBuffer, Charset charset,
            int position, int limit) {

        if (charset == null) {
            charset = Charset.defaultCharset();
        }

        if (byteBuffer.hasArray()) {
            try {
                return new String(byteBuffer.array(),
                        position + byteBuffer.arrayOffset(),
                        limit - position, charset.name());
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
//            Uncomment, when StringDecoder will not create copy of byte[]
//            return new String(byteBuffer.array(),
//                    position + byteBuffer.arrayOffset(),
//                    limit - position, charset);
        } else {
            int oldPosition = byteBuffer.position();
            int oldLimit = byteBuffer.limit();
            setPositionLimit(byteBuffer, position, limit);

            byte[] tmpBuffer = new byte[limit - position];
            byteBuffer.get(tmpBuffer);

            setPositionLimit(byteBuffer, oldPosition, oldLimit);

            try {
                return new String(tmpBuffer, charset.name());
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }

//            Uncomment, when StringDecoder will not create copy of byte[]
//            return new String(tmpBuffer, charset);
        }
    }

    public static void setPositionLimit(final Buffer buffer,
            final int position, final int limit) {
        buffer.limit(limit);
        buffer.position(position);
    }

    public static void setPositionLimit(final ByteBuffer buffer,
            final int position, final int limit) {
        buffer.limit(limit);
        buffer.position(position);
    }

    public static void put(final ByteBuffer srcBuffer, final int srcOffset,
            final int length, final ByteBuffer dstBuffer) {

        if (dstBuffer.remaining() < length) {
            LOGGER.log(Level.WARNING, "BufferOverflow srcBuffer={0} srcOffset={1}"
                    + " length={2} dstBuffer={3}",
                    new Object[]{srcBuffer, srcOffset, length, dstBuffer});
            throw new BufferOverflowException();
        }

        if (srcBuffer.hasArray() && dstBuffer.hasArray()) {

            System.arraycopy(srcBuffer.array(),
                    srcBuffer.arrayOffset() + srcOffset,
                    dstBuffer.array(),
                    dstBuffer.arrayOffset() + dstBuffer.position(),
                    length);
            dstBuffer.position(dstBuffer.position() + length);
        } else {
            for(int i = srcOffset; i < srcOffset + length; i++) {
                dstBuffer.put(srcBuffer.get(i));
            }
        }
    }

    public static void put(Buffer src, int position, int length,
            Buffer dstBuffer) {

        if (dstBuffer.remaining() < length) {
            throw new BufferOverflowException();
        }

        if (!src.isComposite()) {
            final ByteBuffer srcByteBuffer = src.toByteBuffer();
            if (srcByteBuffer.hasArray()) {
                dstBuffer.put(srcByteBuffer.array(),
                        srcByteBuffer.arrayOffset() + position, length);
            } else {
                for(int i=0; i<length; i++) {
                    dstBuffer.put(srcByteBuffer.get(position + i));
                }
            }
        } else {
            final ByteBufferArray array = src.toByteBufferArray(position,
                    position + length);

            final ByteBuffer[] srcByteBuffers = array.getArray();
            for (int i = 0; i < array.size(); i++) {
                final ByteBuffer srcByteBuffer = srcByteBuffers[i];
                final int initialPosition = srcByteBuffer.position();
                final int srcByteBufferLen = srcByteBuffer.remaining();

                if (srcByteBuffer.hasArray()) {
                    dstBuffer.put(srcByteBuffer.array(),
                            srcByteBuffer.arrayOffset() + initialPosition,
                            srcByteBufferLen);
                } else {
                    for (int j = 0; j < srcByteBufferLen; i++) {
                        dstBuffer.put(srcByteBuffer.get(initialPosition + j));
                    }
                }
            }

            array.restore();
            array.recycle();
        }
    }

    public static void get(ByteBuffer srcBuffer,
            byte[] dstBytes, int dstOffset, int length) {

        if (srcBuffer.hasArray()) {
            if (length > srcBuffer.remaining()) {
                throw new BufferUnderflowException();
            }

            System.arraycopy(srcBuffer.array(),
                    srcBuffer.arrayOffset() + srcBuffer.position(),
                    dstBytes, dstOffset, length);
            srcBuffer.position(srcBuffer.position() + length);
        } else {
            srcBuffer.get(dstBytes, dstOffset, length);
        }
    }

    public static void put(byte[] srcBytes, int srcOffset, int length,
            ByteBuffer dstBuffer) {
        if (dstBuffer.hasArray()) {
            if (length > dstBuffer.remaining()) {
                throw new BufferOverflowException();
            }

            System.arraycopy(srcBytes, srcOffset, dstBuffer.array(),
                    dstBuffer.arrayOffset() + dstBuffer.position(), length);
            dstBuffer.position(dstBuffer.position() + length);
        } else {
            dstBuffer.put(srcBytes, srcOffset, length);
        }
    }

    public static Buffer appendBuffers(MemoryManager memoryManager,
            Buffer buffer1, Buffer buffer2) {

        if (buffer1 == null) {
            return buffer2;
        } else if (buffer2 == null) {
            return buffer1;
        }

        if (buffer1.isComposite()) {
            ((CompositeBuffer) buffer1).append(buffer2);
            return buffer1;
        } if (buffer2.isComposite()) {
            ((CompositeBuffer) buffer2).prepend(buffer1);
            return buffer2;
        } else {
            CompositeBuffer compositeBuffer =
                    CompositeBuffer.newBuffer(memoryManager);

            compositeBuffer.append(buffer1);
            compositeBuffer.append(buffer2);

            return compositeBuffer;
        }
    }

    /**
     * Fill the {@link Buffer} with the specific byte value. {@link Buffer}'s
     * position won't be changed.
     *
     * @param buffer {@link Buffer}
     * @param b value
     */
    public static void fill(Buffer buffer, byte b) {
        fill(buffer, buffer.position(), buffer.limit(), b);
    }

    /**
     * Fill the {@link Buffer}'s part [position, limit) with the specific byte value starting from the
     * {@link Buffer}'s position won't be changed.
     *
     * @param buffer {@link Buffer}
     * @param position {@link Buffer} position to start with (inclusive)
     * @param limit {@link Buffer} limit, where filling ends (exclusive)
     * @param b value
     */
    public static void fill(Buffer buffer, int position, int limit, byte b) {
        if (!buffer.isComposite()) {
            final ByteBuffer byteBuffer = buffer.toByteBuffer();
            fill(byteBuffer, position, limit, b);
        } else {
            final ByteBufferArray array = buffer.toByteBufferArray(position, limit);
            final ByteBuffer[] byteBuffers = array.getArray();
            final int size = array.size();

            for (int i = 0; i < size; i++) {
                final ByteBuffer byteBuffer = byteBuffers[i];
                fill(byteBuffer, b);
            }

            array.restore();
            array.recycle();
        }
    }

    /**
     * Fill the {@link ByteBuffer} with the specific byte value. {@link ByteBuffer}'s
     * position won't be changed.
     *
     * @param byteBuffer {@link ByteBuffer}
     * @param b value
     */
    public static void fill(ByteBuffer byteBuffer, byte b) {
        fill(byteBuffer, byteBuffer.position(), byteBuffer.limit(), b);
    }

    /**
     * Fill the {@link ByteBuffer}'s part [position, limit) with the specific byte value starting from the
     * {@link ByteBuffer}'s position won't be changed.
     *
     * @param byteBuffer {@link ByteBuffer}
     * @param position {@link ByteBuffer} position to start with (inclusive)
     * @param limit {@link Buffer} limit, where filling ends (exclusive)
     * @param b value
     */
    public static void fill(ByteBuffer byteBuffer, int position,
            int limit, byte b) {
        if (byteBuffer.hasArray()) {
            final int arrayOffset = byteBuffer.arrayOffset();
            Arrays.fill(byteBuffer.array(), arrayOffset + position,
                    arrayOffset + limit, b);
        } else {
            for (int i = position; i < limit; i++) {
                byteBuffer.put(i, b);
            }
        }
    }
}
