/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010 Oracle and/or its affiliates. All rights reserved.
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

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Cacheable;
import org.glassfish.grizzly.monitoring.jmx.AbstractJmxMonitoringConfig;
import org.glassfish.grizzly.monitoring.jmx.JmxObject;
import org.glassfish.grizzly.threadpool.DefaultWorkerThread;


/**
 * TODO Documentation
 *
 * @since 2.0
 */
public abstract class AbstractMemoryManager<E extends Buffer> implements MemoryManager<E>, ThreadLocalPoolProvider {


    /**
     * TODO: Document
     */
    public static final int DEFAULT_MAX_BUFFER_SIZE = 1024 * 128;

    /**
     * TODO: Document
     */
    public static final int DEFAULT_SMALL_BUFFER_SIZE = 32;


    /**
     * TODO: Document
     */
    protected final AbstractJmxMonitoringConfig<MemoryProbe> monitoringConfig =
            new AbstractJmxMonitoringConfig<MemoryProbe>(MemoryProbe.class) {

        @Override
        public JmxObject createManagementObject() {
            return createJmxManagementObject();
        }

    };

    protected final int maxBufferSize;

    protected final int smallBufferSize;


    // ------------------------------------------------------------ Constructors


    /**
     * TODO: Document
     */
    public AbstractMemoryManager() {

        this(DEFAULT_MAX_BUFFER_SIZE, DEFAULT_SMALL_BUFFER_SIZE);

    }

    /**
     * TODO: Document
     * @param maxBufferSize
     * @param smallBufferSize
     */
    public AbstractMemoryManager(final int maxBufferSize,
                                 final int smallBufferSize) {

        this.maxBufferSize = maxBufferSize;
        this.smallBufferSize = smallBufferSize;

    }


    // ---------------------------------------------------------- Public Methods


    /**
     * Get the size of local thread memory pool.
     *
     * @return the size of local thread memory pool.
     */
    public int getReadyThreadBufferSize() {
       ThreadLocalPool threadLocalPool = getThreadLocalPool();
        if (threadLocalPool != null) {
            return threadLocalPool.remaining();
        }

        return 0;
    }


    /**
     * TODO Documentation
     * @return
     */
    public int getMaxBufferSize() {
        return maxBufferSize;
    }


    /**
     * TODO Documentation
     * @return
     */
    public int getSmallBufferSize() {
        return smallBufferSize;
    }


    // ------------------------------------------------------- Protected Methods


    /**
     * TODO Documentation
     * @param threadLocalCache
     * @param size
     * @return
     */
    protected Object allocateFromPool(final ThreadLocalPool threadLocalCache,
                                      final int size) {
        if (threadLocalCache.remaining() >= size) {
            ProbeNotifier.notifyBufferAllocatedFromPool(monitoringConfig, size);

            return threadLocalCache.allocate(size);
        }

        return null;
    }


    /**
     * TODO Documentation
     * @return
     */
    protected abstract JmxObject createJmxManagementObject();


    /**
     * Get thread associated buffer pool.
     *
     * @return thread associated buffer pool.  This method may return
     *  <code>null</code> if the current thread doesn't have a buffer pool
     *  associated with it.
     */
    protected static ThreadLocalPool getThreadLocalPool() {
        final Thread t = Thread.currentThread();
        if (t instanceof DefaultWorkerThread) {
            return ((DefaultWorkerThread) t).getMemoryPool();
        } else {
            return null;
        }
    }


    /**
     * TODO: Documentation
     * @return
     */
    protected abstract SmallBuffer createSmallBuffer();


    // ---------------------------------------------------------- Nested Classes

    /**
     * TODO: Documentation
     */
    protected static interface SmallBuffer extends Cacheable { }

    /**
     * TODO: Documentation
     */
    protected static interface TrimAware extends Cacheable { }

}
