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

package org.glassfish.grizzly.memory;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import org.glassfish.grizzly.Buffer;

/**
 *
 * @author oleksiys
 */
public class ByteBufferWrapper implements Buffer<ByteBuffer> {
    MemoryManager<ByteBufferWrapper> memoryManager;
    
    ByteBuffer visible;

    public ByteBufferWrapper(MemoryManager<ByteBufferWrapper> memoryManager,
            ByteBuffer underlyingByteBuffer) {
        this.memoryManager = memoryManager;
        visible = underlyingByteBuffer;
    }
    
    ByteBufferWrapper() {
    }

    

    public ByteBuffer prepend(final ByteBuffer header) {
        checkDispose();
        return visible;
    }

    public void trim() {
        checkDispose() ;
        flip();
//        final int sizeNeeded = headerSize + visible.position() ;
//        backingStore = slab.trim( slabPosition, backingStore, sizeNeeded ) ;
//        backingStore.position( headerSize ) ;
//
//        visible = backingStore.slice() ;
//        visible.position( 0 ) ;
    }

    public void dispose() {
        checkDispose();
        memoryManager.release(this);
        memoryManager = null;
        visible = null;
    }

    public ByteBuffer underlying() {
        checkDispose();
        return visible;
    }

    public int capacity() {
        return visible.capacity();
    }

    public int position() {
        return visible.position();
    }

    public Buffer<ByteBuffer> position(int newPosition) {
        visible.position(newPosition);
        return this;
    }

    public int limit() {
        return visible.limit();
    }

    public Buffer<ByteBuffer> limit(int newLimit) {
        visible.limit(newLimit);
        return this;
    }

    public Buffer<ByteBuffer> mark() {
        visible.mark();
        return this;
    }

    public Buffer<ByteBuffer> reset() {
        visible.reset();
        return this;
    }

    public Buffer<ByteBuffer> clear() {
        visible.clear();
        return this;
    }

    public Buffer<ByteBuffer> flip() {
        visible.flip();
        return this;
    }

    public Buffer<ByteBuffer> rewind() {
        visible.rewind();
        return this;
    }

    public int remaining() {
        return visible.remaining();
    }

    public boolean hasRemaining() {
        return visible.hasRemaining();
    }

    public boolean isReadOnly() {
        return visible.isReadOnly();
    }

    public Buffer<ByteBuffer> slice() {
        ByteBuffer slice = visible.slice();
        return new ByteBufferWrapper(memoryManager, slice);
    }

    public Buffer<ByteBuffer> duplicate() {
        ByteBuffer duplicate = visible.duplicate();
        return new ByteBufferWrapper(memoryManager, duplicate);
    }

    public Buffer<ByteBuffer> asReadOnlyBuffer() {
        visible.asReadOnlyBuffer();
        return this;
    }

    public byte get() {
        return visible.get();
    }

    public byte get(int index) {
        return visible.get(index);
    }

    public Buffer<ByteBuffer> put(byte b) {
        visible.put(b);
        return this;
    }

    public Buffer<ByteBuffer> put(int index, byte b) {
        visible.put(index, b);
        return this;
    }

    public Buffer<ByteBuffer> get(byte[] dst) {
        visible.get(dst);
        return this;
    }

    public Buffer<ByteBuffer> get(byte[] dst, int offset, int length) {
        visible.get(dst, offset, length);
        return this;
    }

    public Buffer<ByteBuffer> put(Buffer src) {
        visible.put((ByteBuffer) src.underlying());
        return this;
    }

    public Buffer<ByteBuffer> put(byte[] src) {
        visible.put(src);
        return this;
    }

    public Buffer<ByteBuffer> put(byte[] src, int offset, int length) {
        visible.put(src, offset, length);
        return this;
    }

    public Buffer<ByteBuffer> compact() {
        visible.compact();
        return this;
    }

    public ByteOrder order() {
        return visible.order();
    }

    public Buffer<ByteBuffer> order(ByteOrder bo) {
        visible.order(bo);
        return this;
    }

    public char getChar() {
        return visible.getChar();
    }

    public char getChar(int index) {
        return visible.getChar(index);
    }

    public Buffer<ByteBuffer> putChar(char value) {
        visible.putChar(value);
        return this;
    }

    public Buffer<ByteBuffer> putChar(int index, char value) {
        visible.putChar(index, value);
        return this;
    }

    public short getShort() {
        return visible.getShort();
    }

    public short getShort(int index) {
        return visible.getShort(index);
    }

    public Buffer<ByteBuffer> putShort(short value) {
        visible.putShort(value);
        return this;
    }

    public Buffer<ByteBuffer> putShort(int index, short value) {
        visible.putShort(index, value);
        return this;
    }

    public int getInt() {
        return visible.getInt();
    }

    public int getInt(int index) {
        return visible.getInt(index);
    }

    public Buffer<ByteBuffer> putInt(int value) {
        visible.putInt(value);
        return this;
    }

    public Buffer<ByteBuffer> putInt(int index, int value) {
        visible.putInt(index, value);
        return this;
    }

    public long getLong() {
        return visible.getLong();
    }

    public long getLong(int index) {
        return visible.getLong(index);
    }

    public Buffer<ByteBuffer> putLong(long value) {
        visible.putLong(value);
        return this;
    }

    public Buffer<ByteBuffer> putLong(int index, long value) {
        visible.putLong(index, value);
        return this;
    }

    public float getFloat() {
        return visible.getFloat();
    }

    public float getFloat(int index) {
        return visible.getFloat(index);
    }

    public Buffer<ByteBuffer> putFloat(float value) {
        visible.putFloat(value);
        return this;
    }

    public Buffer<ByteBuffer> putFloat(int index, float value) {
        visible.putFloat(index, value);
        return this;
    }

    public double getDouble() {
        return visible.getDouble();
    }

    public double getDouble(int index) {
        return visible.getDouble(index);
    }

    public Buffer<ByteBuffer> putDouble(double value) {
        visible.putDouble(value);
        return this;
    }

    public Buffer<ByteBuffer> putDouble(int index, double value) {
        visible.putDouble(index, value);
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ByteBufferWrapper [");
        sb.append("visible=[").append(visible).append(']');
        sb.append(']');
        return sb.toString();
    }

    @Override
    public int hashCode() {
        return visible.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ByteBufferWrapper) {
            return visible.equals(((ByteBufferWrapper) obj).visible);
        }

        return false;
    }

    public int compareTo(Buffer<ByteBuffer> o) {
        return visible.compareTo(o.underlying());
    }

    private void checkDispose() {
        if (visible == null) {
            throw new IllegalStateException("BufferWrapper has already been disposed") ;
        }
    }

    public String contentAsString(Charset charset) {
        checkDispose();
        
        // Working with charset name to support JDK1.5
        String charsetName = charset.name();
        try {
            if (visible.hasArray()) {
                return new String(visible.array(),
                        visible.position() + visible.arrayOffset(),
                        visible.remaining(), charsetName);
            } else {
                int oldPosition = visible.position();
                byte[] tmpBuffer = new byte[visible.remaining()];
                visible.get(tmpBuffer);
                visible.position(oldPosition);
                return new String(tmpBuffer, charsetName);
            }
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("Unexpected exception", e);
        }
    }
}
