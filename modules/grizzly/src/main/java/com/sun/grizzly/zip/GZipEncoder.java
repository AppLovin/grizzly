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

package com.sun.grizzly.zip;

import com.sun.grizzly.AbstractTransformer;
import com.sun.grizzly.AbstractTransformer.LastResultAwareState;
import com.sun.grizzly.Buffer;
import com.sun.grizzly.TransformationException;
import com.sun.grizzly.TransformationResult;
import com.sun.grizzly.TransportFactory;
import com.sun.grizzly.attributes.AttributeStorage;
import com.sun.grizzly.memory.BufferUtils;
import com.sun.grizzly.memory.MemoryManager;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * This class implements a {@link Transformer} which encodes plain data to
 * the GZIP format.
 *
 * @author Alexey Stashok
 */
public class GZipEncoder extends AbstractTransformer<Buffer, Buffer> {
    private static final int GZIP_MAGIC = 0x8b1f;

    /*
     * Trailer size in bytes.
     */
    private static final int TRAILER_SIZE = 8;

    private final int bufferSize;

    private static final Buffer header;

    static {
        header = TransportFactory.getInstance().getDefaultMemoryManager().allocate(10);
        header.put((byte) GZIP_MAGIC);                // Magic number (short)
        header.put((byte) (GZIP_MAGIC >> 8));                // Magic number (short)
        header.put((byte) Deflater.DEFLATED);         // Compression method (CM)
        header.put((byte) 0);                         // Flags (FLG)
        header.put((byte) 0);                         // Modification time MTIME (int)
        header.put((byte) 0);                         // Modification time MTIME (int)
        header.put((byte) 0);                         // Modification time MTIME (int)
        header.put((byte) 0);                         // Modification time MTIME (int)
        header.put((byte) 0);                         // Extra flags (XFLG)
        header.put((byte) 0);                         // Operating system (OS)

        header.flip();
    }

    public GZipEncoder() {
        this(512);
    }

    public GZipEncoder(int bufferSize) {
        this.bufferSize = bufferSize;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return "gzip-encoder";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasInputRemaining(AttributeStorage storage, Buffer input) {
        return input.hasRemaining();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected LastResultAwareState createStateObject() {
        return new GZipOutputState();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected TransformationResult<Buffer, Buffer> transformImpl(
            AttributeStorage storage, Buffer input) throws TransformationException {

        final MemoryManager memoryManager = obtainMemoryManager(storage);
        final GZipOutputState state = (GZipOutputState) obtainStateObject(storage);

        Buffer headerToWrite = null;
        if (!state.isInitialized()) {
            headerToWrite = initializeOutput(state);
        }

        Buffer encodedBuffer = null;
        if (input != null && input.hasRemaining()) {
            encodedBuffer = encodeBuffer(input, state, memoryManager);
        }

        if (headerToWrite == null && encodedBuffer == null) {
            return TransformationResult.createIncompletedResult(null);
        }

        encodedBuffer = BufferUtils.appendBuffers(memoryManager,
                headerToWrite, encodedBuffer);

        return TransformationResult.createCompletedResult(encodedBuffer, null);
    }

    /**
     * Finishes to compress data to the output stream without closing
     * the underlying stream. Use this method when applying multiple filters
     * in succession to the same output stream.
     *
     * @return {@link Buffer} with the last GZIP data to be sent.
     */
    public Buffer finish(AttributeStorage storage) {
        final MemoryManager memoryManager = obtainMemoryManager(storage);
        final GZipOutputState state = (GZipOutputState) obtainStateObject(storage);

        Buffer resultBuffer = null;

        if (state.isInitialized()) {
            final Deflater deflater = state.getDeflater();
            if (!deflater.finished()) {
                deflater.finish();

                while (!deflater.finished()) {
                    resultBuffer = BufferUtils.appendBuffers(memoryManager,
                            resultBuffer,
                            deflate(deflater, memoryManager));
                }

                // Put GZIP member trailer
                final Buffer trailer = memoryManager.allocate(TRAILER_SIZE);
                final CRC32 crc32 = state.getCrc32();
                putUInt(trailer, (int) crc32.getValue());
                putUInt(trailer, deflater.getTotalIn());
                trailer.flip();

                resultBuffer = BufferUtils.appendBuffers(memoryManager,
                        resultBuffer, trailer);
            }

            state.setInitialized(false);
        }

        return resultBuffer;
    }
    
    private Buffer initializeOutput(final GZipOutputState state) {
        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        final CRC32 crc32 = new CRC32();
        crc32.reset();
        state.setDeflater(deflater);
        state.setCrc32(crc32);
        state.setInitialized(true);
        final Buffer headerToWrite = header.duplicate();
        headerToWrite.allowBufferDispose(false);
        return headerToWrite;
    }

    private Buffer encodeBuffer(Buffer buffer,
            GZipOutputState state, MemoryManager memoryManager) {
        final CRC32 crc32 = state.getCrc32();
        final Deflater deflater = state.getDeflater();

	if (deflater.finished()) {
	    throw new IllegalStateException("write beyond end of stream");
	}

        // Deflate no more than stride bytes at a time.  This avoids
        // excess copying in deflateBytes (see Deflater.c)
        int stride = bufferSize;
        Buffer resultBuffer = null;

        final ByteBuffer[] buffers = buffer.toByteBufferArray();

        for (ByteBuffer byteBuffer : buffers) {
            final int len = byteBuffer.remaining();
            if (len > 0) {
                final byte[] buf = byteBuffer.array();
                final int off = byteBuffer.arrayOffset() + byteBuffer.position();

                for (int i = 0; i < len; i += stride) {
                    deflater.setInput(buf, off + i, Math.min(stride, len - i));
                    while (!deflater.needsInput()) {
                        final Buffer deflated = deflate(deflater, memoryManager);
                        if (deflated != null) {
                            resultBuffer = BufferUtils.appendBuffers(
                                    memoryManager, resultBuffer, deflated);
                        }
                    }
                }

                crc32.update(buf, off, len);
            }
        }

        return resultBuffer;
    }

    /**
     * Writes next block of compressed data to the output stream.
     * @throws IOException if an I/O error has occurred
     */
    protected Buffer deflate(Deflater deflater, MemoryManager memoryManager) {
        final Buffer buffer = memoryManager.allocate(bufferSize);
        final ByteBuffer byteBuffer = buffer.toByteBuffer();
        final byte[] array = byteBuffer.array();
        final int offset = byteBuffer.arrayOffset();

	int len = deflater.deflate(array, offset, bufferSize);
        if (len <= 0) {
            buffer.dispose();
            return null;
        }

        buffer.position(len);
        buffer.trim();

        return buffer;
    }
    
    /*
     * Writes integer in Intel byte order to a byte array, starting at a
     * given offset.
     */
    private static void putUInt(Buffer buffer, int value) {
        putUShort(buffer, value & 0xffff);
        putUShort(buffer, (value >> 16) & 0xffff);
    }

    /*
     * Writes short integer in Intel byte order to a byte array, starting
     * at a given offset
     */
    private static void putUShort(Buffer buffer, int value) {
        buffer.put((byte) (value & 0xff));
        buffer.put((byte) ((value >> 8) & 0xff));
    }

    protected static final class GZipOutputState extends LastResultAwareState {
        private boolean isInitialized;

        /**
         * CRC-32 of uncompressed data.
         */
        private CRC32 crc32;

        /**
         * Compressor for this stream.
         */
        private Deflater deflater;

        public boolean isInitialized() {
            return isInitialized;
        }

        public void setInitialized(boolean isInitialized) {
            this.isInitialized = isInitialized;
        }

        public Deflater getDeflater() {
            return deflater;
        }

        public void setDeflater(Deflater deflater) {
            this.deflater = deflater;
        }

        public CRC32 getCrc32() {
            return crc32;
        }

        public void setCrc32(CRC32 crc32) {
            this.crc32 = crc32;
        }
    }
}
