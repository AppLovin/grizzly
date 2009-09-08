/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2003-2008 Sun Microsystems, Inc. All rights reserved.
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
 */
package com.sun.grizzly;

import com.sun.grizzly.memory.DefaultMemoryManager;
import com.sun.grizzly.memory.MemoryManager;
import com.sun.grizzly.memory.MemoryUtils;
import com.sun.grizzly.utils.CompositeBuffer;
import junit.framework.TestCase;

/**
 * {@link CompositeBuffer} test set.
 * 
 * @author Alexey Stashok
 */
public class CompositeBufferTest extends TestCase {

    public void testSingleBuffer() {
        MemoryManager manager = new DefaultMemoryManager();

        Buffer buffer = manager.allocate(1 + 2 + 2 + 4 + 8 + 4 + 8);
        CompositeBuffer compositeBuffer = new CompositeBuffer(buffer);

        byte v1 = (byte) 127;
        char v2 = 'c';
        short v3 = (short) -1;
        int v4 = Integer.MIN_VALUE;
        long v5 = Long.MAX_VALUE;
        float v6 = 0.3f;
        double v7 = -0.5;

        compositeBuffer.put(v1);
        compositeBuffer.putChar(v2);
        compositeBuffer.putShort(v3);
        compositeBuffer.putInt(v4);
        compositeBuffer.putLong(v5);
        compositeBuffer.putFloat(v6);
        compositeBuffer.putDouble(v7);

        compositeBuffer.flip();

        assertEquals(v1, compositeBuffer.get());
        assertEquals(v2, compositeBuffer.getChar());
        assertEquals(v3, compositeBuffer.getShort());
        assertEquals(v4, compositeBuffer.getInt());
        assertEquals(v5, compositeBuffer.getLong());
        assertEquals(v6, compositeBuffer.getFloat());
        assertEquals(v7, compositeBuffer.getDouble());
    }

    public void testSingleBufferIndexedAccess() {
        MemoryManager manager = new DefaultMemoryManager();

        Buffer buffer = manager.allocate(1 + 2 + 2 + 4 + 8 + 4 + 8);
        CompositeBuffer compositeBuffer = new CompositeBuffer(buffer);

        byte v1 = (byte) 127;
        char v2 = 'c';
        short v3 = (short) -1;
        int v4 = Integer.MIN_VALUE;
        long v5 = Long.MAX_VALUE;
        float v6 = 0.3f;
        double v7 = -0.5;

        compositeBuffer.put(0, v1);
        compositeBuffer.putChar(1, v2);
        compositeBuffer.putShort(3, v3);
        compositeBuffer.putInt(5, v4);
        compositeBuffer.putLong(9, v5);
        compositeBuffer.putFloat(17, v6);
        compositeBuffer.putDouble(21, v7);

        assertEquals(v1, compositeBuffer.get(0));
        assertEquals(v2, compositeBuffer.getChar(1));
        assertEquals(v3, compositeBuffer.getShort(3));
        assertEquals(v4, compositeBuffer.getInt(5));
        assertEquals(v5, compositeBuffer.getLong(9));
        assertEquals(v6, compositeBuffer.getFloat(17));
        assertEquals(v7, compositeBuffer.getDouble(21));
    }

    public void testBytes() {
        doTest(new Byte[]{-1, 0, 127, -127}, 4, 1,
                new Put<Byte>() {

                    @Override
                    public void put(CompositeBuffer buffer, Byte value) {
                        buffer.put(value);
                    }
                },
                new Get<Byte>() {

                    @Override
                    public Byte get(CompositeBuffer buffer) {
                        return buffer.get();
                    }
                });
    }

    public void testBytesIndexed() {
        doTestIndexed(new Byte[]{-1, 0, 127, -127}, 4, 1,
                new Put<Byte>() {

                    @Override
                    public void putIndexed(CompositeBuffer buffer, int index, Byte value) {
                        buffer.put(index, value);
                    }
                },
                new Get<Byte>() {

                    @Override
                    public Byte getIndexed(CompositeBuffer buffer, int index) {
                        return buffer.get(index);
                    }
                }, 1);
    }

    public void testChars() {
        doTest(new Character[]{'a', 'b', 'c', 'd'}, 3, 3,
                new Put<Character>() {

                    @Override
                    public void put(CompositeBuffer buffer, Character value) {
                        buffer.putChar(value);
                    }
                },
                new Get<Character>() {

                    @Override
                    public Character get(CompositeBuffer buffer) {
                        return buffer.getChar();
                    }
                });
    }

    public void testCharsIndexed() {
        doTestIndexed(new Character[]{'a', 'b', 'c', 'd'}, 3, 3,
                new Put<Character>() {

                    @Override
                    public void putIndexed(CompositeBuffer buffer, int index, Character value) {
                        buffer.putChar(index, value);
                    }
                },
                new Get<Character>() {

                    @Override
                    public Character getIndexed(CompositeBuffer buffer, int index) {
                        return buffer.getChar(index);
                    }
                }, 2);
    }

    public void testShort() {
        doTest(new Short[]{Short.MIN_VALUE, -1}, 3, 3,
                new Put<Short>() {

                    @Override
                    public void put(CompositeBuffer buffer, Short value) {
                        buffer.putShort(value);
                    }
                },
                new Get<Short>() {

                    @Override
                    public Short get(CompositeBuffer buffer) {
                        return buffer.getShort();
                    }
                });
    }

    public void testShortIndexed() {
        doTestIndexed(new Short[]{Short.MIN_VALUE, -1}, 3, 3,
                new Put<Short>() {

                    @Override
                    public void putIndexed(CompositeBuffer buffer, int index, Short value) {
                        buffer.putShort(index, value);
                    }
                },
                new Get<Short>() {

                    @Override
                    public Short getIndexed(CompositeBuffer buffer, int index) {
                        return buffer.getShort(index);
                    }
                }, 2);
    }

    public void testInt() {
        doTest(new Integer[]{Integer.MIN_VALUE, -1, Integer.MAX_VALUE, 0}, 4, 5,
                new Put<Integer>() {

                    @Override
                    public void put(CompositeBuffer buffer, Integer value) {
                        buffer.putInt(value);
                    }
                },
                new Get<Integer>() {

                    @Override
                    public Integer get(CompositeBuffer buffer) {
                        return buffer.getInt();
                    }
                });
    }

    public void testIntIndexed() {
        doTestIndexed(new Integer[]{Integer.MIN_VALUE, -1, Integer.MAX_VALUE, 0}, 4, 5,
                new Put<Integer>() {

                    @Override
                    public void putIndexed(CompositeBuffer buffer, int index, Integer value) {
                        buffer.putInt(index, value);
                    }
                },
                new Get<Integer>() {

                    @Override
                    public Integer getIndexed(CompositeBuffer buffer, int index) {
                        return buffer.getInt(index);
                    }
                }, 4);
    }

    public void testLong() {
        doTest(new Long[]{Long.MIN_VALUE, -1L, Long.MAX_VALUE, 0L}, 4, 9,
                new Put<Long>() {

                    @Override
                    public void put(CompositeBuffer buffer, Long value) {
                        buffer.putLong(value);
                    }
                },
                new Get<Long>() {

                    @Override
                    public Long get(CompositeBuffer buffer) {
                        return buffer.getLong();
                    }
                });
    }

    public void testLongIndexed() {
        doTestIndexed(new Long[]{Long.MIN_VALUE, -1L, Long.MAX_VALUE, 0L}, 4, 9,
                new Put<Long>() {

                    @Override
                    public void putIndexed(CompositeBuffer buffer, int index, Long value) {
                        buffer.putLong(index, value);
                    }
                },
                new Get<Long>() {

                    @Override
                    public Long getIndexed(CompositeBuffer buffer, int index) {
                        return buffer.getLong(index);
                    }
                }, 8);
    }

    public void testFloat() {
        doTest(new Float[]{Float.MIN_VALUE, -1f, Float.MAX_VALUE, 0f}, 4, 5,
                new Put<Float>() {

                    @Override
                    public void put(CompositeBuffer buffer, Float value) {
                        buffer.putFloat(value);
                    }
                },
                new Get<Float>() {

                    @Override
                    public Float get(CompositeBuffer buffer) {
                        return buffer.getFloat();
                    }
                });
    }

    public void testFloatIndexed() {
        doTestIndexed(new Float[]{Float.MIN_VALUE, -1f, Float.MAX_VALUE, 0f}, 4, 5,
                new Put<Float>() {

                    @Override
                    public void putIndexed(CompositeBuffer buffer, int index, Float value) {
                        buffer.putFloat(index, value);
                    }
                },
                new Get<Float>() {

                    @Override
                    public Float getIndexed(CompositeBuffer buffer, int index) {
                        return buffer.getFloat(index);
                    }
                }, 4);
    }

    public void testDouble() {
        doTest(new Double[]{Double.MIN_VALUE, -1.0, Double.MAX_VALUE, 0.0}, 4, 9,
                new Put<Double>() {

                    @Override
                    public void put(CompositeBuffer buffer, Double value) {
                        buffer.putDouble(value);
                    }
                },
                new Get<Double>() {

                    @Override
                    public Double get(CompositeBuffer buffer) {
                        return buffer.getDouble();
                    }
                });
    }

    public void testDoubleIndexed() {
        doTestIndexed(new Double[]{Double.MIN_VALUE, -1.0, Double.MAX_VALUE, 0.0}, 4, 9,
                new Put<Double>() {

                    @Override
                    public void putIndexed(CompositeBuffer buffer, int index, Double value) {
                        buffer.putDouble(index, value);
                    }
                },
                new Get<Double>() {

                    @Override
                    public Double getIndexed(CompositeBuffer buffer, int index) {
                        return buffer.getDouble(index);
                    }
                }, 8);
    }

    public void testBuffers() {
        MemoryManager manager = new DefaultMemoryManager();

        Buffer sampleBuffer = MemoryUtils.wrap(manager, new byte[] {-1, 0, 1, 1, 2, 3, 4});

        Buffer b1 = manager.allocate(3);
        Buffer b2 = manager.allocate(4);

        CompositeBuffer compositeBuffer = new CompositeBuffer(b1, b2);
        compositeBuffer.put(sampleBuffer);
        compositeBuffer.flip();
        sampleBuffer.flip();

        while(sampleBuffer.hasRemaining()) {
            assertEquals(sampleBuffer.get(), compositeBuffer.get());
        }
    }

    private <E> void doTest(E[] testData, int buffersNum, int bufferSize,
            Put<E> put, Get<E> get) {

        MemoryManager manager = new DefaultMemoryManager();

        Buffer[] buffers = new Buffer[buffersNum];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = manager.allocate(bufferSize);
        }

        CompositeBuffer compositeBuffer = new CompositeBuffer(buffers);

        for (int i = 0; i < testData.length; i++) {
            put.put(compositeBuffer, testData[i]);
        }

        compositeBuffer.flip();

        for (int i = 0; i < testData.length; i++) {
            assertEquals(testData[i], get.get(compositeBuffer));
        }
    }

    private <E> void doTestIndexed(E[] testData, int buffersNum, int bufferSize,
            Put<E> put, Get<E> get, int eSizeInBytes) {

        MemoryManager manager = new DefaultMemoryManager();

        Buffer[] buffers = new Buffer[buffersNum];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = manager.allocate(bufferSize);
        }

        CompositeBuffer compositeBuffer = new CompositeBuffer(buffers);

        for (int i = 0; i < testData.length; i++) {
            put.putIndexed(compositeBuffer, i * eSizeInBytes, testData[i]);
        }

        for (int i = 0; i < testData.length; i++) {
            assertEquals(testData[i], get.getIndexed(compositeBuffer, i * eSizeInBytes));
        }
    }

    private static class Put<E> {

        public void put(CompositeBuffer buffer, E value) {
        }

        public void putIndexed(CompositeBuffer buffer, int index, E value) {
        }
    }

    private static class Get<E> {

        public E get(CompositeBuffer buffer) {
            return null;
        }

        public E getIndexed(CompositeBuffer buffer, int index) {
            return null;
        }
    }
}
