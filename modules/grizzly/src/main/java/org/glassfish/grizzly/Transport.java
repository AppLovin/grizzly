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

package org.glassfish.grizzly;

import org.glassfish.grizzly.attributes.AttributeBuilder;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.util.ExceptionHandler;
import org.glassfish.grizzly.util.ObjectPool;
import org.glassfish.grizzly.util.StateHolder;
import java.io.IOException;
import java.util.concurrent.ExecutorService;

/**
 *
 * @author oleksiys
 */
public interface Transport extends ExceptionHandler {
    public enum State {STARTING, START, PAUSE, STOPPING, STOP};

    public String getName();

    public void setName(String name);

    public StateHolder<State> getState();

    public boolean isBlocking();

    public void configureBlocking(boolean isBlocking);

    public ObjectPool<Context> getDefaultContextPool();

    public void setDefaultContextPool(
            ObjectPool<Context> defaultContextPool);

    public Processor getProcessor();

    public void setProcessor(Processor processor);

    public ProcessorSelector getProcessorSelector();

    public void setProcessorSelector(ProcessorSelector selector);

    public MemoryManager getMemoryManager();

    public void setMemoryManager(MemoryManager memoryManager);

    public int getReadBufferSize();

    public void setReadBufferSize(int readBufferSize);

    public int getWriteBufferSize();

    public void setWriteBufferSize(int writeBufferSize);

    /**
     * Get a thread pool, which will process occurred I/O operations.
     * Custom user {@link Processor} will be executed by this thread pool.
     * 
     * @return {@link ExecutorService} worker thread pool.
     */
    public ExecutorService getWorkerThreadPool();

    /**
     * Set a thread pool, which will process occurred I/O operations.
     * Custom user {@link Processor} will be executed by this thread pool.
     *
     * @param workerThreadPool  {@link ExecutorService} worker thread pool.
     */
    public void setWorkerThreadPool(ExecutorService workerThreadPool);

    /**
     * Get a thread pool, which will process transport internal tasks like
     * NIO {@link Selector} polling etc.
     *
     * @return {@link ExecutorService} internal thread pool.
     */
    public ExecutorService getInternalThreadPool();

    /**
     * Set a thread pool, which will process transport internal tasks like
     * NIO {@link Selector} polling etc.
     *
     * @param internalThreadPool  {@link ExecutorService} internal thread pool.
     */
    public void setInternalThreadPool(ExecutorService internalThreadPool);

    public AttributeBuilder getAttributeBuilder();

    public void setAttributeBuilder(AttributeBuilder attributeBuilder);
    
    public void addExceptionHandler(ExceptionHandler handler);
    
    public void removeExceptionHandler(ExceptionHandler handler);

    public void notifyException(Severity severity, Throwable throwable);
    
    /**
     * Starts the transport
     * 
     * @throws java.io.IOException
     */
    public void start() throws IOException;
    
    /**
     * Stops the transport and closes all the connections
     * 
     * @throws java.io.IOException
     */
    public void stop() throws IOException;
    
    /**
     * Pauses the transport
     * 
     * @throws java.io.IOException
     */
    public void pause() throws IOException;
    
    /**
     * Resumes the transport after a pause
     * 
     * @throws java.io.IOException
     */
    public void resume() throws IOException;
    
    /**
     * Fires specific {@link IOEvent} on the {@link Connection}
     *
     * @param ioEvent I/O event
     * @param connection {@link Connection}, on which we fire the event.
     */
    public void fireIOEvent(IOEvent ioEvent, Connection connection)
            throws IOException;

    /**
     * Fires specific {@link IOEvent} on the {@link Connection}
     *
     * @param ioEvent I/O event
     * @param connection {@link Connection}, on which we fire the event.
     * @param strategyContext {@link Strategy} state
     */
    public void fireIOEvent(IOEvent ioEvent, Connection connection,
            Object strategyContext) throws IOException;

    /**
     * Returns <tt>true</tt>, if this <tt>Transport</tt> is in stopped state,
     *         <tt>false</tt> otherwise.
     * @return <tt>true</tt>, if this <tt>Transport</tt> is in stopped state,
     *         <tt>false</tt> otherwise.
     */
    public boolean isStopped();
}
