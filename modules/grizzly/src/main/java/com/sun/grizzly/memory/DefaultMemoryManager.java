/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
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

package com.sun.grizzly.memory;

import com.sun.grizzly.Cacheable;
import com.sun.grizzly.ThreadCache;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import com.sun.grizzly.threadpool.DefaultWorkerThread;
import java.util.Arrays;

/**
 * Default {@link MemoryManager}, used in Grizzly.
 * <tt>DefaultMemory</tt> has simple {@link Buffer} pooling implementation,
 * which makes released {@link Buffer}'s memory to be reused.
 *
 * @author Alexey Stashok
 */
public final class DefaultMemoryManager extends ByteBufferManager {
    private static final ThreadCache.CachedTypeIndex<TrimAwareWrapper> CACHE_IDX =
            ThreadCache.obtainIndex(TrimAwareWrapper.class, 2);

    /**
     * Construct {@link Future}.
     */
    private TrimAwareWrapper createTrimAwareBuffer(
            ByteBufferManager memoryManager,
            ByteBuffer underlyingByteBuffer) {

        final TrimAwareWrapper buffer = ThreadCache.takeFromCache(CACHE_IDX);
        if (buffer != null) {
            buffer.visible = underlyingByteBuffer;
            return buffer;
        }

        return new TrimAwareWrapper(memoryManager, underlyingByteBuffer);
    }

    public static final int DEFAULT_MAX_BUFFER_SIZE = 1024 * 128;
    
    /**
     * Max size of memory pool for one thread.
     */
    private volatile int maxThreadBufferSize = DEFAULT_MAX_BUFFER_SIZE;

    private boolean isMonitoring;

    /**
     * Real amount of bytes, which was allocated.
     */
    private final AtomicLong totalBytesAllocated = new AtomicLong();
    
    /**
     * Get the maximum size of memory pool for one thread.
     *
     * @return the maximum size of memory pool for one thread.
     */
    public int getMaxThreadBufferSize() {
        return maxThreadBufferSize;
    }

    /**
     * Set the maximum size of memory pool for one thread.
     *
     * @param maxThreadBufferSize the maximum size of memory pool for one thread.
     */
    public void setMaxThreadBufferSize(int maxThreadBufferSize) {
        this.maxThreadBufferSize = maxThreadBufferSize;
    }

    /**
     * Is monotoring enabled.
     * 
     * @return <tt>true</tt>, if monitoring is enabled, or <tt>false</tt>
     * otherwise.
     */
    public boolean isMonitoring() {
        return isMonitoring;
    }

    /**
     * Set monotoring mode.
     *
     * @param isMonitoring <tt>true</tt>, if monitoring is enabled, or
     * <tt>false</tt> otherwise.
     */
    public void setMonitoring(boolean isMonitoring) {
        this.isMonitoring = isMonitoring;
        ByteBufferWrapper.DEBUG_MODE = isMonitoring;
    }

    /**
     * Get real number of bytes allocated by this {@link MemoryManager}.
     * It doesn't count bytes, which were pooled and then reused.
     * 
     * @return real number of bytes allocated by this {@link MemoryManager}.
     */
    public long getTotalBytesAllocated() {
        return totalBytesAllocated.get();
    }

    /**
     * Allocates {@link ByteBuffer} of required size.
     * First of all <tt>DefaultMemoryManager</tt> tries to reuse thread local
     * memory pool. If it's not possible - it delegates allocation to 
     * {@link ByteBufferViewManager}.
     * 
     * @param size number of bytes to be allocated.
     * 
     * @return allocated {@link ByteBuffer}.
     */
    @Override
    public ByteBuffer allocateByteBuffer(int size) {
        if (size > maxThreadBufferSize) {
            // Don't use pool
            return incAllocated(super.allocateByteBuffer(size));
        }

        if (isDefaultWorkerThread()) {
            ThreadLocalPool threadLocalCache = getThreadLocalPool();

            if (threadLocalCache.hasRemaining()) {
                threadLocalCache = reallocatePoolBuffer();
                return allocateFromPool(threadLocalCache, size);
            }

            final ByteBuffer allocatedFromPool =
                    allocateFromPool(threadLocalCache, size);

            if (allocatedFromPool != null) {
                return allocatedFromPool;
            } else {
                threadLocalCache = reallocatePoolBuffer();
                return allocateFromPool(threadLocalCache, size);
            }

        } else {
            return incAllocated(super.allocateByteBuffer(size));
        }
    }

    private ThreadLocalPool reallocatePoolBuffer() {
        final ByteBuffer byteBuffer = incAllocated(
                super.allocateByteBuffer(maxThreadBufferSize));

        final ThreadLocalPool threadLocalCache = getThreadLocalPool();
        threadLocalCache.reset(byteBuffer);
        
        return threadLocalCache;
    }

    /**
     * Reallocate {@link ByteBuffer} to a required size.
     * First of all <tt>DefaultMemoryManager</tt> tries to reuse thread local
     * memory pool. If it's not possible - it delegates reallocation to
     * {@link ByteBufferViewManager}.
     *
     * @param oldBuffer old {@link ByteBuffer} we want to reallocate.
     * @param newSize {@link Buffer} required size.
     *
     * @return reallocated {@link ByteBuffer}.
     */
    @Override
    public ByteBuffer reallocateByteBuffer(ByteBuffer oldByteBuffer, int newSize) {
        if (oldByteBuffer.capacity() >= newSize) return (ByteBuffer) oldByteBuffer;

        if (isDefaultWorkerThread()) {
            final ThreadLocalPool memoryPool = getThreadLocalPool();
            final ByteBuffer newBuffer =
                    memoryPool.reallocate(oldByteBuffer, newSize);
            
            if (newBuffer != null) return newBuffer;
        }

        return incAllocated(super.reallocateByteBuffer(oldByteBuffer, newSize));
    }


    private ByteBuffer allocateFromPool(final ThreadLocalPool threadLocalCache,
            final int size) {
        if (threadLocalCache.remaining() >= size) {
            return threadLocalCache.allocate(size);
        }

        return null;
    }


    /**
     * Release {@link ByteBuffer}.
     * <tt>DefaultMemoryManager</tt> will checks if it's possible to return
     * the buffer to thread local pool. If not - let's garbage collector utilize
     * the memory.
     *
     * @param buffer {@link ByteBuffer} to be released.
     */
    @Override
    public void releaseByteBuffer(ByteBuffer byteBuffer) {
        if (isDefaultWorkerThread()) {
            ThreadLocalPool memoryPool = getThreadLocalPool();

            if (memoryPool.release((ByteBuffer) byteBuffer.clear())) return;

        }
        super.releaseByteBuffer(byteBuffer);
    }



    /**
     * Get the size of local thread memory pool.
     * 
     * @return the size of local thread memory pool.
     */
    public int getReadyThreadBufferSize() {
        if (isDefaultWorkerThread()) {
            final ThreadLocalPool threadLocalPool = getThreadLocalPool();
            return threadLocalPool.remaining();
        }

        return 0;
    }


    @Override
    public ByteBufferWrapper wrap(ByteBuffer byteBuffer) {
        return createTrimAwareBuffer(this, byteBuffer);
    }

    /**
     * Get thread associated buffer pool.
     * 
     * @return thread associated buffer pool.
     */
    private ThreadLocalPool getThreadLocalPool() {
        DefaultWorkerThread workerThread =
                (DefaultWorkerThread) Thread.currentThread();
        return workerThread.getMemoryPool();
    }

    /**
     * Counts total allocated memory size.
     */
    private ByteBuffer incAllocated(ByteBuffer allocated) {
        if (isMonitoring) {
            totalBytesAllocated.addAndGet(allocated.capacity());
        }

        return allocated;
    }

    private boolean isDefaultWorkerThread() {
        return Thread.currentThread() instanceof DefaultWorkerThread;
    }

    /**
     * Information about thread associated memory pool.
     */
    public static final class ThreadLocalPool {
        /**
         * Memory pool
         */
        private ByteBuffer pool;

        /**
         * {@link ByteBuffer} allocation history.
         */
        private Object[] allocationHistory;
        private int lastAllocatedIndex;

        public ThreadLocalPool() {
            allocationHistory = new Object[8];
        }

        public void reset(ByteBuffer pool) {
            Arrays.fill(allocationHistory, 0, lastAllocatedIndex, null);
            lastAllocatedIndex = 0;
            this.pool = pool;
        }

        public ByteBuffer allocate(int size) {
            final ByteBuffer allocated = BufferUtils.slice(pool, size);
            return addHistory(allocated);
        }

        public ByteBuffer reallocate(ByteBuffer oldByteBuffer, int newSize) {
            if (isLastAllocated(oldByteBuffer) &&
                    remaining() + oldByteBuffer.capacity() >= newSize) {
            
            lastAllocatedIndex--;

            pool.position(pool.position() - oldByteBuffer.capacity());
            final ByteBuffer newByteBuffer = BufferUtils.slice(pool, newSize);
            newByteBuffer.position(oldByteBuffer.position());

            return addHistory(newByteBuffer);
            }

            return null;
        }

        public boolean release(ByteBuffer underlyingBuffer) {
            if (isLastAllocated(underlyingBuffer)) {
                pool.position(pool.position() - underlyingBuffer.capacity());
                allocationHistory[--lastAllocatedIndex] = null;

                return true;
            } else if (tryReset(underlyingBuffer)) {
                return true;
            }

            return false;
        }

        public boolean tryReset(ByteBuffer byteBuffer) {
            if (wantReset(byteBuffer.remaining())) {
                reset(byteBuffer);

                return true;
            }

            return false;
        }

        private boolean wantReset(int size) {
            return !hasRemaining() ||
                    (lastAllocatedIndex == 0 && pool.remaining() < size);
        }

        public boolean isLastAllocated(ByteBuffer oldByteBuffer) {
            return lastAllocatedIndex > 0 &&
                    allocationHistory[lastAllocatedIndex - 1] == oldByteBuffer;
        }

        public ByteBuffer reduceLastAllocated(ByteBuffer byteBuffer) {
            final ByteBuffer oldLastAllocated = 
                    (ByteBuffer) allocationHistory[lastAllocatedIndex - 1];

            pool.position(pool.position() - (oldLastAllocated.capacity() -
                    byteBuffer.capacity()));
            allocationHistory[lastAllocatedIndex - 1] = byteBuffer;

            return oldLastAllocated;
        }

        public int remaining() {
            if (hasRemaining()) return 0;

            return pool.remaining();
        }

        public boolean hasRemaining() {
            return pool == null || !pool.hasRemaining();
        }

        private ByteBuffer addHistory(ByteBuffer allocated) {
            if (lastAllocatedIndex >= allocationHistory.length) {
                allocationHistory =
                        Arrays.copyOf(allocationHistory,
                        (allocationHistory.length * 3) / 2 + 1);
            }

            allocationHistory[lastAllocatedIndex++] = allocated;
            return allocated;
        }

        @Override
        public String toString() {
            return "(pool=" + pool +
                    " last-allocated-index=" + (lastAllocatedIndex - 1) +
                    " allocation-history=" + Arrays.toString(allocationHistory)
                    + ")";
        }
    }

    /**
     * {@link ByteBufferWrapper} implementation, which supports triming. In
     * other words it's possible to return unused {@link Buffer} space to
     * pool.
     */
    public final class TrimAwareWrapper extends ByteBufferWrapper
            implements Cacheable {

        private TrimAwareWrapper(ByteBufferManager memoryManager,
                ByteBuffer underlyingByteBuffer) {
            super(memoryManager, underlyingByteBuffer);
        }

        @Override
        public void trim() {
            final int sizeToReturn = visible.capacity() - visible.position();


            if (sizeToReturn > 0 && isDefaultWorkerThread()) {
                final ThreadLocalPool threadLocalCache = getThreadLocalPool();


                if (threadLocalCache.isLastAllocated(visible)) {
                    visible.flip();

                    visible = visible.slice();
                    threadLocalCache.reduceLastAllocated(visible);

                    return;
                } else if (threadLocalCache.wantReset(sizeToReturn)) {
                    visible.flip();

                    final ByteBuffer originalByteBuffer = visible;
                    visible = visible.slice();
                    originalByteBuffer.position(originalByteBuffer.limit());
                    originalByteBuffer.limit(originalByteBuffer.capacity());

                    threadLocalCache.tryReset(originalByteBuffer);
                    return;
                }
            }

            super.trim();
        }

        @Override
        public void recycle() {
            allowBufferDispose = false;
            disposeStackTrace = null;

            ThreadCache.putToCache(CACHE_IDX, this);
        }

        @Override
        public void dispose() {
            super.dispose();
            recycle();
        }
    }
}
