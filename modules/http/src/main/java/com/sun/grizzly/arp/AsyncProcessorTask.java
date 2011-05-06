/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2007-2010 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.grizzly.arp;

import com.sun.grizzly.http.ProcessorTask;
import com.sun.grizzly.http.SelectorThread;
import com.sun.grizzly.http.TaskBase;

import java.io.IOException;
import java.util.logging.Level;

/**
 * A {@link Task} that wraps the execution of an asynchronous execution
 * of a {@link ProcessorTask}. Internaly, this class invoke the associated
 * {@link AsyncExecutor} method to execute the {@link ProcessorTask}
 * lifecycle operations.
 *
 * @author Jeanfrancois Arcand
 */
public class AsyncProcessorTask extends TaskBase implements AsyncTask {

    /**
     * The {@link AsyncExecutor} which drive the execution of the 
     * {@link ProcessorTask}
     */
    private AsyncExecutor asyncExecutor;

    
    /**
     * The current execution stage.
     */
    private int stage = AsyncTask.PRE_EXECUTE;

    
    /**
     * Execute the {@link AsyncExecutor} based on the <code>stage</code>
     * of the {@link ProcessorTask} execution.
     */
    public void doTask() throws IOException {
        boolean continueExecution = true;
        while (continueExecution) {
            try {
                switch (stage) {
                    case AsyncTask.PRE_EXECUTE:
                        continueExecution = asyncExecutor.preExecute();
                        if (!continueExecution) { 
                            asyncExecutor.getAsyncHandler().returnTask(this);
                            return;
                        } else {
                            stage = AsyncTask.INTERRUPTED;
                        }
                        break;
                    case AsyncTask.INTERRUPTED:
                        stage = AsyncTask.POST_EXECUTE;
                        continueExecution = asyncExecutor.interrupt();
                        break;
                    case AsyncTask.EXECUTE:
                        continueExecution = asyncExecutor.execute();
                        stage = AsyncTask.POST_EXECUTE;
                        break;
                    case AsyncTask.POST_EXECUTE:
                        continueExecution = asyncExecutor.postExecute();
                        if (continueExecution) {
                            if (asyncExecutor.getProcessorTask().hasNextRequest()) {
                                asyncExecutor.reset();
                                asyncExecutor.getProcessorTask().prepareForNextRequest();

                                stage = AsyncTask.PRE_EXECUTE;
                            } else {
                                stage = AsyncTask.FINISH;
                            }
                        }
                        break;
//                        asyncExecutor.getAsyncHandler().returnTask(this);
                    case AsyncTask.FINISH:
                        asyncExecutor.finishExecute();
                        asyncExecutor.getAsyncHandler().returnTask(this);
                        return;
                }
            } catch (Throwable t) {
                t.printStackTrace();
                SelectorThread.logger().log(Level.SEVERE, t.getMessage(), t);
                if (stage <= AsyncTask.INTERRUPTED) {
                    // We must close the connection.
                    stage = AsyncTask.POST_EXECUTE;
                } else {
                    stage = AsyncTask.PRE_EXECUTE;
                    throw new RuntimeException(t);
                }
            }
        }
    }

    
    /**
     * Return the <code>stage</code> of the current execution.
     */
    public int getStage() {
        return stage;
    }

    
    /**
     * Reset the object.
     */
    @Override
    public void recycle() {
        stage = AsyncTask.PRE_EXECUTE;
        if (asyncExecutor instanceof DefaultAsyncExecutor){
            ((DefaultAsyncExecutor)asyncExecutor).reset();
        }
    }

    
    /**
     * Set the {@link AsyncExecutor} used to delegate the execution of a {@link ProcessorTask}.
     */
    public void setAsyncExecutor(AsyncExecutor asyncExecutor) {
        this.asyncExecutor = asyncExecutor;
    }

    
    /**
     * Get the {@link AsyncExecutor}.
     */
    public AsyncExecutor getAsyncExecutor() {
        return asyncExecutor;
    }

    
    /**
     * Set the current execution stage.
     */
    public void setStage(int stage) {
        this.stage = stage;
    }
    
    
    /**
     * Set the {@link ProcessorTask} used to execute the request processing.
     * @param task a {@link ProcessorTask} 
     * @deprecated - Use {@link AsyncExecutor#setProcessorTask}
     */    
    public void setProcessorTask(ProcessorTask task){
        // Do nothing
    }
    
    
    /**
     * The {@link ProcessorTask} used to execute the request processing.
     * @return {@link ProcessorTask} used to execute the request processing.
     * @deprecated - Use {@link AsyncExecutor#getProcessorTask}
     */        
    public ProcessorTask getProcessorTask(){
        return asyncExecutor == null ? null : asyncExecutor.getProcessorTask();
        
    }

}
