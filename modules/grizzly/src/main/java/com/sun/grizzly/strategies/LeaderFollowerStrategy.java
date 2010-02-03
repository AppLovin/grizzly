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
package com.sun.grizzly.strategies;

import java.io.IOException;
import java.util.concurrent.Executor;
import com.sun.grizzly.Connection;
import com.sun.grizzly.IOEvent;
import com.sun.grizzly.ProcessorRunnable;
import com.sun.grizzly.Strategy;
import com.sun.grizzly.nio.NIOConnection;
import com.sun.grizzly.nio.SelectorRunner;
import com.sun.grizzly.utils.CurrentThreadExecutor;
import com.sun.grizzly.utils.WorkerThreadExecutor;
import java.util.concurrent.ExecutorService;

/**
 * {@link Strategy}, which executes {@link Processor}s in a current threads, and
 * resumes selector thread logic in separate thread.
 *
 * @author Alexey Stashok
 */
public class LeaderFollowerStrategy implements Strategy<Boolean> {
    private final Executor sameThreadProcessorExecutor;
    private final Executor workerThreadProcessorExecutor;

    public LeaderFollowerStrategy(final ExecutorService workerThreadPool) {
        this.workerThreadProcessorExecutor =
                new WorkerThreadExecutor(workerThreadPool);
        this.sameThreadProcessorExecutor = new CurrentThreadExecutor();
    }

    protected LeaderFollowerStrategy(Executor sameThreadProcessorExecutor,
            Executor workerThreadProcessorExecutor) {
        this.sameThreadProcessorExecutor = sameThreadProcessorExecutor;
        this.workerThreadProcessorExecutor = workerThreadProcessorExecutor;
    }

   /**
    * {@inheritDoc}
    */
    @Override
    public Boolean prepare(Connection connection, IOEvent ioEvent) {
        return true;
    }

   /**
    * {@inheritDoc}
    */
    @Override
    public void executeProcessor(Boolean strategyContext,
            ProcessorRunnable processorRunnable) throws IOException {

        if (strategyContext != null && strategyContext) {
            NIOConnection nioConnection =
                    (NIOConnection) processorRunnable.getConnection();
            SelectorRunner runner = nioConnection.getSelectorRunner();
            runner.postpone();
            nioConnection.getTransport().getThreadPool().execute(runner);
        }

        Executor executor = getProcessorExecutor(strategyContext);

        executor.execute(processorRunnable);
    }

   /**
    * {@inheritDoc}
    */
    @Override
    public boolean isTerminateThread(Boolean strategyContext) {
        return strategyContext;
    }

    public Executor getProcessorExecutor(Boolean strategyContext) {
        if (strategyContext != null && strategyContext) {
            return sameThreadProcessorExecutor;
        }

        return workerThreadProcessorExecutor;
    }
}
