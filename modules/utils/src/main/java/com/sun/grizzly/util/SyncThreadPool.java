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
package com.sun.grizzly.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ExecutorService} implementation, which function the similar way as
 * former Grizzly 1.x Pipeline based thread pools.
 * 
 * The <tt>SyncThreadPool</tt> is sychronized similar way as Grizzly 1.x Pipeline,
 * which makes thread pool more accurate when deciding to create or not
 * additional worker threads.
 * 
 * 
 * @author Alexey Stashok
 */
public class SyncThreadPool extends AbstractThreadPool {

    protected int threadsCounter;
    protected int currentPoolSize;
    protected int activeThreadsCount;
    private final Queue<Runnable> workQueue;
    protected int maxQueuedTasks = -1;
    private final AtomicLong completedTasksCount = new AtomicLong();
    private int largestThreadPoolSize;
    protected final Object statelock = new Object();
    protected boolean running = true;
    protected final Map<Worker, Long> workers = new HashMap<Worker, Long>();

    /**
     *
     */
    public SyncThreadPool() {
        this("Grizzly", DEFAULT_MIN_THREAD_COUNT, DEFAULT_MAX_THREAD_COUNT,
                DEFAULT_IDLE_THREAD_KEEPALIVE_TIMEOUT, TimeUnit.MILLISECONDS);
    }

    /**
     *
     * @param workerprefixname
     * @param corePoolsize
     * @param maxPoolSize
     * @param keepAliveTime
     * @param timeUnit {@link TimeUnit}
     */
    public SyncThreadPool(final String name, int corePoolsize,
            int maxPoolSize, long keepAliveTime, TimeUnit timeUnit) {
        this(name, corePoolsize, maxPoolSize, keepAliveTime, timeUnit, null);
    }

    /**
     *
     * @param corePoolsize
     * @param maxPoolSize
     * @param keepAliveTime
     * @param timeUnit  {@link TimeUnit}
     * @param threadFactory {@link ThreadFactory}
     */
    public SyncThreadPool(final String name, int corePoolsize,
            int maxPoolSize, long keepAliveTime, TimeUnit timeUnit,
            ThreadFactory threadFactory) {
        this(name, corePoolsize, maxPoolSize, keepAliveTime, timeUnit,
                threadFactory, new LinkedList<Runnable>(), -1);
    }

    /**
     *
     * @param corePoolsize
     * @param maxPoolSize
     * @param keepAliveTime
     * @param timeUnit {@link TimeUnit}
     * @param threadFactory {@link ThreadFactory}
     * @param workQueue {@link BlockingQueue}
     */
    public SyncThreadPool(final String name, int corePoolsize, int maxPoolSize,
            long keepAliveTime, TimeUnit timeUnit, ThreadFactory threadFactory,
            Queue<Runnable> workQueue, int maxQueuedTasks) {

        if (keepAliveTime < 0) {
            throw new IllegalArgumentException("keepAliveTime < 0");
        }
        if (timeUnit == null) {
            throw new IllegalArgumentException("timeUnit == null");
        }

        setPoolSizes(corePoolsize, maxPoolSize);

        this.keepAliveTime = TimeUnit.MILLISECONDS.convert(keepAliveTime, timeUnit);

        this.name = name;

        if (this.threadFactory == null) {
            this.threadFactory = new SyncWorkerThreadFactory();
        }


        this.workQueue = workQueue;
        this.maxQueuedTasks = maxQueuedTasks;
    }

    public void start() {
        synchronized(statelock) {
            while (currentPoolSize < corePoolSize) {
                startWorker(new SyncThreadWorker(true));
            }
        }
    }

    public void stop() {
        shutdownNow();
    }

    /**
     * {@inheritDoc}
     */
    public void execute(Runnable task) {

        if (task == null) {
            throw new IllegalArgumentException("Runnable task is null");
        }

        synchronized (statelock) {
            if (!running) {
                throw new RejectedExecutionException("ThreadPool is not running");
            }

            final int idleThreadsNumber = currentPoolSize - activeThreadsCount;

            if ((maxQueuedTasks < 0 || workQueue.size() < maxQueuedTasks) &&
                    workQueue.offer(task)) {
                onTaskQueued(task);
            } else {
                onTaskQueueOverflow();
                throw new RejectedExecutionException("ThreadPool is full");
            }

            final boolean isCore = (currentPoolSize < corePoolSize);
            
            if (isCore ||
                    (currentPoolSize < maxPoolSize && idleThreadsNumber == 0)) {
                startWorker(new SyncThreadWorker(isCore));
            } else if (idleThreadsNumber == 0) {
                onMaxNumberOfThreadsReached();
            } else {
                statelock.notify();
            }
        }
    }

    protected void startWorker(Worker wt) {
        // Must be executed in synchronized block
        
        wt.t = threadFactory.newThread(wt);
        workers.put(wt, System.currentTimeMillis());
        wt.t.start();
        currentPoolSize++;
        activeThreadsCount++;

        if (currentPoolSize > largestThreadPoolSize) {
            largestThreadPoolSize = currentPoolSize;
        }
    }

    /**
     * {@inheritDoc}
     */
    public List<Runnable> shutdownNow() {
        synchronized (statelock) {
            List<Runnable> drained = new ArrayList<Runnable>();
            if (running) {
                running = false;
                drained.addAll(workQueue);
                workQueue.clear();

                for (Runnable task : drained) {
                    onTaskDequeued(task);
                }

                poisonAll();
                //try to interrupt their current work so they can get their poison fast
                for (Worker w : workers.keySet()) {
                    w.t.interrupt();
                }
            }
            return drained;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void shutdown() {
        synchronized (statelock) {
            if (running) {
                running = false;
                poisonAll();
                statelock.notifyAll();
            }
        }
    }

    private void poisonAll() {
        int size = Math.max(maxPoolSize, currentPoolSize) * 4 / 3;
        while (size-- > 0) {
            workQueue.offer(poison);
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean isShutdown() {
        synchronized(statelock) {
            return !running;
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean isTerminated() {
        synchronized(statelock) {
           return !running && workers.isEmpty();
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * {@inheritDoc}
     */
    public int getActiveCount() {
        synchronized(statelock) {
            return activeThreadsCount;
        }
    }

    /**
     * {@inheritDoc}
     */
    public int getTaskCount() {
        synchronized(statelock) {
            return workQueue.size();
        }
    }

    /**
     * {@inheritDoc}
     */
    public long getCompletedTaskCount() {
        return completedTasksCount.get();
    }

    /**
     * {@inheritDoc}
     */
    public int getLargestPoolSize() {
        synchronized(statelock) {
            return largestThreadPoolSize;
        }
    }

    /**
     * {@inheritDoc}
     */
    public int getPoolSize() {
        synchronized(statelock) {
            return currentPoolSize;
        }
    }

    /**
     * {@inheritDoc}
     */
    public Queue<Runnable> getQueue() {
        return workQueue;
    }

    /**
     * {@inheritDoc}
     */
    public int getQueueSize() {
        synchronized(statelock) {
            return workQueue.size();
        }
    }

    /**
     * {@inheritDoc}
     */
    public int getMaxQueuedTasksCount() {
        synchronized(statelock) {
            return maxQueuedTasks;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void setMaxQueuedTasksCount(int maxQueuedTasks) {
        synchronized(statelock) {
            this.maxQueuedTasks = maxQueuedTasks;
        }
    }

    /**
     * {@inheritDoc}
     */
    protected void setPoolSizes(int corePoolSize, int maxPoolSize) {
        synchronized (statelock) {
            validateNewPoolSize(corePoolSize, maxPoolSize);

            this.corePoolSize = corePoolSize;
            this.maxPoolSize = maxPoolSize;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onWorkerExit(Worker worker) {
        super.onWorkerExit(worker);
        
        synchronized (statelock) {
            currentPoolSize--;
            activeThreadsCount--;
            workers.remove(worker);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        super.beforeExecute(t, r);
        ((WorkerThreadImpl) t).createByteBuffer(false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        ((WorkerThreadImpl) Thread.currentThread()).reset();
        completedTasksCount.incrementAndGet();
        super.afterExecute(r, t);
    }

    @Override
    protected String nextThreadId() {
        return Integer.toString(threadsCounter++);
    }


    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(512);
        builder.append("SyncThreadPool[");
        injectToStringAttributes(builder);
        builder.append(']');
        return builder.toString();
    }

    protected void injectToStringAttributes(StringBuilder sb) {
        sb.append("name=").append(name);
        sb.append(", min-threads=").append(getCorePoolSize());
        sb.append(", max-threads=").append(getMaximumPoolSize());
        sb.append(", max-queue-size=").append(getMaxQueuedTasksCount());
        sb.append(", is-shutdown=").append(isShutdown());
    }

    protected class SyncThreadWorker extends Worker {

        private final boolean core;

        public SyncThreadWorker(boolean core) {
            this.core = core;
        }

        protected Runnable getTask() throws InterruptedException {
            synchronized (statelock) {
                try {
                    activeThreadsCount--;

                    if (!core && currentPoolSize > maxPoolSize) {
                        // if maxpoolsize becomes lower during runtime we kill of the
                        return null;
                    }

                    Runnable r = workQueue.poll();

                    long localKeepAlive = keepAliveTime;

                    while (r == null) {
                        final long startTime = System.currentTimeMillis();
                        statelock.wait(localKeepAlive);
                        r = workQueue.poll();

                        localKeepAlive -= (System.currentTimeMillis() - startTime);
                        
                        // Less than 100 millis remainder will consider as keepalive timeout
                        if (!core &&
                                (r != null || localKeepAlive < 100)) {
                            break;
                        }
                    }

                    return r;

                } finally {
                    activeThreadsCount++;
                }
            }
        }
    }

    private class SyncWorkerThreadFactory implements ThreadFactory {

        public Thread newThread(Runnable r) {
            synchronized (statelock) {
                Thread thread = new WorkerThreadImpl(SyncThreadPool.this,
                        name + "-WorkerThread(" +
                        nextThreadId() + ")", r,
                        initialByteBufferSize);
                thread.setUncaughtExceptionHandler(SyncThreadPool.this);
                thread.setPriority(priority);
                return thread;
            }
        }
    }
}
