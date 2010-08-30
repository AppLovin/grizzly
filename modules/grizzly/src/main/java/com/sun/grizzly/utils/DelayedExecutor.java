/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2009-2010 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.grizzly.utils;

import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 
 * @author Alexey Stashok
 */
public class DelayedExecutor {
    public final static long UNSET_TIMEOUT = -1;
    
    private final ExecutorService threadPool;

    private final DelayedRunnable runnable = new DelayedRunnable();
    
    private final Collection<DelayQueue<?>> queues =
            new LinkedTransferQueue<DelayQueue<?>>();

    private final Object sync = new Object();

    private volatile boolean isStarted;

    private final long checkIntervalMillis;

    public DelayedExecutor(ExecutorService threadPool) {
        this(threadPool, 1000, TimeUnit.MILLISECONDS);
    }

    public DelayedExecutor(ExecutorService threadPool, long checkInterval, TimeUnit timeunit) {
        this.threadPool = threadPool;
        this.checkIntervalMillis = TimeUnit.MILLISECONDS.convert(checkInterval, timeunit);
    }

    public void start() {
        synchronized(sync) {
            if (!isStarted) {
                isStarted = true;
                threadPool.execute(runnable);
            }
        }
    }

    public void stop() {
        synchronized(sync) {
            if (isStarted) {
                isStarted = false;
                sync.notify();
            }
        }
    }

    public ExecutorService getThreadPool() {
        return threadPool;
    }

    public <E> DelayQueue<E> createDelayQueue(Worker<E> worker,
            Resolver<E> resolver) {
        
        final DelayQueue<E> queue = new DelayQueue(worker, resolver);

        queues.add(queue);

        return queue;
    }

    private class DelayedRunnable implements Runnable {

        @Override
        public void run() {
            while(isStarted) {
                final long currentTimeMillis = System.currentTimeMillis();
                
                for (final DelayQueue delayQueue : queues) {
                    if (delayQueue.queue.isEmpty()) continue;
                    
                    final Resolver resolver = delayQueue.resolver;

                    for (Iterator it = delayQueue.queue.iterator(); it.hasNext(); ) {
                        final Object element = it.next();
                        final Long timeoutMillis = resolver.getTimeoutMillis(element);
                        
                        if (timeoutMillis == null || timeoutMillis == UNSET_TIMEOUT) {
                            it.remove();
                        } else if (currentTimeMillis - timeoutMillis >= 0) {
                            it.remove();
                            try {
                                delayQueue.resolver.removeTimeout(element);
                                delayQueue.worker.doWork(element);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }

                synchronized(sync) {
                    if (!isStarted) return;
                    
                    try {
                        sync.wait(checkIntervalMillis);
                    } catch (InterruptedException e) {
                    }
                }
            }
        }

    }

    public final class DelayQueue<E> {
        final Queue<E> queue = new LinkedTransferQueue<E>();

        final Worker<E> worker;
        final Resolver<E> resolver;

        public DelayQueue(Worker<E> worker, Resolver<E> resolver) {
            this.worker = worker;
            this.resolver = resolver;
        }

        public void add(E elem, long delay, TimeUnit timeUnit) {
            if (delay >= 0) {
                resolver.setTimeoutMillis(elem, System.currentTimeMillis() +
                        TimeUnit.MILLISECONDS.convert(delay, timeUnit));
                queue.add(elem);
            }
        }

        public void remove(E elem) {
            resolver.removeTimeout(elem);
        }

        public void destroy() {
            queues.remove(this);
        }
    }

    public interface Worker<E> {
        public void doWork(E element);
    }

    public interface Resolver<E> {
        public boolean removeTimeout(E element);
        
        public Long getTimeoutMillis(E element);
        
        public void setTimeoutMillis(E element, long timeoutMillis);
    }
}
