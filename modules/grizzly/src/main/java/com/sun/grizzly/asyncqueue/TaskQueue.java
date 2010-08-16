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
package com.sun.grizzly.asyncqueue;

import com.sun.grizzly.utils.LinkedTransferQueue;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class represents common implementation of asynchronous processing queue.
 *
 * @author Alexey Stashok
 */
public abstract class TaskQueue<E> {

    /**
     * The queue of tasks, which will be processed asynchronously
     */
    protected final Queue<E> queue;


    protected final Queue<QueueMonitor> monitorQueue;


    // ------------------------------------------------------------ Constructors


    protected TaskQueue(Queue<E> queue) {
        this.queue = queue;
        monitorQueue = new ConcurrentLinkedQueue<QueueMonitor>();
    }


    // ---------------------------------------------------------- Public Methods


    public static <E> TaskQueue<E> createSafeTaskQueue() {
        return new SafeTaskQueue<E>();
    }
    
    public static <E> TaskQueue<E> createUnSafeTaskQueue() {
        return new UnSafeTaskQueue<E>();
    }


    /**
     * Reserves memory space in the queue.
     *
     * @return the new memory (in bytes) consumed by the queue.
     */
    public abstract int reserveSpace(int amount);

    /**
     * Releases memory space in the queue.
     *
     * @return the new memory (in bytes) consumed by the queue.
     */
    public abstract int releaseSpace(int amount, boolean notify);

    /**
     * Returns the number of queued bytes.
     * 
     * @return the number of queued bytes.
     */
    public abstract int spaceInBytes();

    /**
     * Get the current processing task
     * @return the current processing task
     */
    public abstract E getCurrentElement();

    /**
     * Get the wrapped current processing task, to perform atomic operations.
     * @return the wrapped current processing task, to perform atomic operations.
     */
    public abstract AtomicReference<E> getCurrentElementAtomic();
    
    /**
     * Get the queue of tasks, which will be processed asynchronously
     * @return the queue of tasks, which will be processed asynchronously
     */
    public Queue<E> getQueue() {
        return queue;
    }


    public boolean addQueueMonitor(final QueueMonitor monitor) {
        if (monitor.shouldNotify()) {
            monitor.onNotify();
            return false;
        } else {
            monitorQueue.offer(monitor);
            return true;
        }
    }


    public void removeQueueMonitor(final QueueMonitor monitor) {
        monitorQueue.remove(monitor);
    }


    // ------------------------------------------------------- Protected Methods


    protected void doNotify() {

        if (!monitorQueue.isEmpty()) {
            for (final Iterator<QueueMonitor> i = monitorQueue.iterator(); i.hasNext(); ) {
                final QueueMonitor m = i.next();
                if (m.shouldNotify()) {
                    i.remove();
                    m.onNotify();
                }
            }
        }

    }


    //----------------------------------------------------------- Nested Classes


    /**
     * Thread safe <tt>AsyncQueue</tt> implementation.
     * @param <E> queue element type.
     */
    public final static class SafeTaskQueue<E> extends TaskQueue<E> {
        final AtomicReference<E> currentElement;
        final AtomicInteger spaceInBytes = new AtomicInteger();

        protected SafeTaskQueue() {
            super(new LinkedTransferQueue<E>());
            currentElement = new AtomicReference<E>();
        }

        @Override
        public E getCurrentElement() {
            return currentElement.get();
        }

        @Override
        public AtomicReference<E> getCurrentElementAtomic() {
            return currentElement;
        }

        @Override
        public int reserveSpace(int amount) {
            return spaceInBytes.addAndGet(amount);
        }

        @Override
        public int releaseSpace(int amount, boolean notify) {
            final int space = spaceInBytes.addAndGet(-amount);
            if (notify) {
                doNotify();
            }
            return space;
        }

        @Override
        public int spaceInBytes() {
            return spaceInBytes.get();
        }

    } // END SafeTaskQueue


    /**
     * Non thread safe <tt>AsyncQueue</tt> implementation.
     * @param <E> queue element type.
     */
    public final static class UnSafeTaskQueue<E> extends TaskQueue<E> {
        private E currentElement;

        private int spaceInBytes;

        /**
         * Locker object, which could be used by a queue processors
         */
        protected final ReentrantLock queuedActionLock;

        protected UnSafeTaskQueue() {
            super(new LinkedList<E>());
            queuedActionLock = new ReentrantLock();
        }

        @Override
        public E getCurrentElement() {
            return currentElement;
        }

        @Override
        public AtomicReference<E> getCurrentElementAtomic() {
            throw new UnsupportedOperationException("Is not supported for unsafe queue");
        }

        /**
         * Get the locker object, which could be used by a queue processors
         * @return the locker object, which could be used by a queue processors
         */
        public ReentrantLock getQueuedActionLock() {
            return queuedActionLock;
        }

        @Override
        public int reserveSpace(int amount) {
            spaceInBytes += amount;
            return spaceInBytes;
        }

        @Override
        public int releaseSpace(int amount, boolean notify) {
            spaceInBytes -= amount;
            if (notify) {
                doNotify();
            }
            return spaceInBytes;
        }

        @Override
        public int spaceInBytes() {
            return spaceInBytes;
        }

    } // END UnsafeTaskQueue


    /**
     * Notification mechanism which is currently invoked when
     * {@link TaskQueue#releaseSpace(int, boolean)} is called.
     */
    public interface QueueMonitor {

        /**
         * Action(s) to perform when the current queue space meets the conditions
         * mandated by {@link #shouldNotify()}.
         */
        void onNotify();

        /**
         * @return <code>true</code> if {@link #onNotify()} should be invoked on
         *  this <code>QueueMonitor</code> at the point in time <code>shouldNotify</code>
         *  was called, otherwise returns <code>false</code>
         */
        boolean shouldNotify();


    } // END QueueMonitor
}