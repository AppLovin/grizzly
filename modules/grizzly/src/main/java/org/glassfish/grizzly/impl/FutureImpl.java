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

package org.glassfish.grizzly.impl;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *
 * @author oleksiys
 */
public class FutureImpl<R> implements Future<R> {

    private boolean isCancelled;
    private Throwable failure;
    
    protected R result;
    
    protected CountDownLatch latch = new CountDownLatch(1);

    public R getResult() {
        return result;
    }

    public void setResult(R result) {
        this.result = result;
        notifyHaveResult();
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        isCancelled = true;
        notifyHaveResult();
        return true;
    }

    public boolean isCancelled() {
        return isCancelled;
    }

    public boolean isDone() {
        return latch.getCount() <= 0;
    }

    public R get() throws InterruptedException, ExecutionException {
        latch.await();
        if (isCancelled) {
            throw new CancellationException();
        } else if (failure != null) {
            throw new ExecutionException(failure);
        }

        return result;
    }

    public R get(long timeout, TimeUnit unit) throws
            InterruptedException, ExecutionException, TimeoutException {
        latch.await(timeout, unit);
        if (isCancelled) {
            throw new CancellationException();
        } else if (failure != null) {
            throw new ExecutionException(failure);
        } else if (result != null) {
            return result;
        } else {
            throw new TimeoutException();
        }
    }

    public void failure(Throwable failure) {
        this.failure = failure;
        notifyHaveResult();
    }

    protected void notifyHaveResult() {
        latch.countDown();
    }
}
