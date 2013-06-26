/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2013 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.impl;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.*;
import org.glassfish.grizzly.CompletionHandler;

/**
 * Safe {@link FutureImpl} implementation.
 *
 * (Based on the JDK {@link java.util.concurrent.FutureTask})
 *
 * @see Future
 * 
 * @author Alexey Stashok
 */
@SuppressWarnings("deprecation")
public class SafeFutureImpl<R> extends FutureTask<R> implements FutureImpl<R> {

    private static final Callable DUMMY_CALLABLE = new Callable() {
        private final Object result = new Object();
        @Override
        public Object call() throws Exception {
            return result;
        }
    };

    private final Object chSync = new Object();
    private volatile Set<CompletionHandler<R>> completionHandlers;

    /**
     * {@inheritDoc}
     */
    @Override
    public void addCompletionHandler(final CompletionHandler<R> completionHandler) {
        if (isDone()) {
            notifyCompletionHandler(completionHandler);
        } else {
            if (completionHandlers == null) {
                synchronized(chSync) {
                    if (completionHandlers == null) {
                        completionHandlers = Collections.newSetFromMap(
                                new ConcurrentHashMap<CompletionHandler<R>, Boolean>(2));
                    }
                }
            }
            
            completionHandlers.add(completionHandler);
            
            if (isDone() && completionHandlers.remove(completionHandler)) {
                notifyCompletionHandler(completionHandler);
            }
        }
    }
    
    /**
     * Construct {@link SafeFutureImpl}.
     */
    @SuppressWarnings("unchecked")
    public static <R> SafeFutureImpl<R> create() {
        return new SafeFutureImpl<R>();
    }
    
    /**
     * Creates <tt>SafeFutureImpl</tt> 
     */
    @SuppressWarnings("unchecked")
    public SafeFutureImpl() {
        super(DUMMY_CALLABLE);
    }
    
    /**
     * Set the result value and notify about operation completion.
     *
     * @param result the result value
     */
    @Override
    public void result(R result) {
        set(result);
    }

    /**
     * Notify about the failure, occurred during asynchronous operation execution.
     *
     * @param failure
     */
    @Override
    public void failure(Throwable failure) {
        setException(failure);
    }

    @Override
    public void markForRecycle(boolean recycleResult) {
    }

    @Override
    public void recycle(boolean recycleResult) {
    }

    @Override
    public void recycle() {
    }

    @Override
    public R getResult() {
        if (isDone()) {
            try {
                return get();
            } catch (Throwable ignored) {
            }
        }
        
        return null;
    }

    @Override
    protected final void done() {
        super.done();
        notifyCompletionHandlers();
        onComplete();
    }
    
    protected void onComplete() {
    }
    
    private void notifyCompletionHandlers() {
        if (completionHandlers == null) {
            return;
        }
        
        final boolean isCancelled = isCancelled();
        R result = null;
        Throwable error = null;
        
        if (!isCancelled) {
            try {
                result = get();
            } catch (ExecutionException e) {
                error = e.getCause();
            } catch (Exception e) {
                error = e;
            }
        }
        
        for (Iterator<CompletionHandler<R>> it = completionHandlers.iterator(); it.hasNext();) {
            final CompletionHandler<R> completionHandler = it.next();
            if (completionHandlers.remove(completionHandler)) {
                try {
                    if (isCancelled) {
                        completionHandler.cancelled();
                    } else if (error != null) {
                        completionHandler.failed(error);
                    } else {
                        completionHandler.completed(result);
                    }
                } catch (Exception e) {
                }
            }
        }
    }
    
    private void notifyCompletionHandler(final CompletionHandler<R> completionHandler) {
        if (isCancelled()) {
            completionHandler.cancelled();
        } else {
            try {
                final R result = get();
                
                try {
                    completionHandler.completed(result);
                } catch (Exception e) {
                }
            } catch (ExecutionException e) {
                completionHandler.failed(e.getCause());
            } catch (Exception e) {
                completionHandler.failed(e);
            }
        }
    }
}
