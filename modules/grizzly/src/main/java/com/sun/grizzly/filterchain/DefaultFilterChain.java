
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

package com.sun.grizzly.filterchain;

import com.sun.grizzly.Buffer;
import com.sun.grizzly.CompletionHandler;
import com.sun.grizzly.Context;
import com.sun.grizzly.GrizzlyFuture;
import java.io.IOException;
import com.sun.grizzly.ProcessorResult;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.sun.grizzly.Appendable;
import com.sun.grizzly.Appender;
import com.sun.grizzly.Connection;
import com.sun.grizzly.Grizzly;
import com.sun.grizzly.IOEvent;
import com.sun.grizzly.ProcessorExecutor;
import com.sun.grizzly.ReadResult;
import com.sun.grizzly.WriteResult;
import com.sun.grizzly.asyncqueue.AsyncQueueEnabledTransport;
import com.sun.grizzly.asyncqueue.AsyncQueueWriter;
import com.sun.grizzly.attributes.Attribute;
import com.sun.grizzly.impl.FutureImpl;
import com.sun.grizzly.impl.ReadyFutureImpl;
import com.sun.grizzly.impl.SafeFutureImpl;
import com.sun.grizzly.impl.UnsafeFutureImpl;
import com.sun.grizzly.memory.BufferUtils;
import com.sun.grizzly.utils.Pair;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.ExecutionException;

/**
 * Default {@link FilterChain} implementation
 *
 * @see FilterChain
 * @see Filter
 * 
 * @author Alexey Stashok
 */
public final class DefaultFilterChain extends ListFacadeFilterChain {

    public enum FILTER_STATE_TYPE {INCOMPLETE, REMAINDER};
    
    protected final static Attribute<FiltersState> FILTERS_STATE_ATTR =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute("DefaultFilterChain-StoredData");

    protected final static Attribute<Queue<Pair>> STANDALONE_READ_LISTENERS_ATTR =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute("DefaultFilterChain-ReadListener");

    /**
     * NONE,
     * SERVER_ACCEPT,
     * ACCEPTED,
     * CONNECTED,
     * READ,
     * WRITE,
     * CLOSED
     *
     * Filter executors array
     */
    private static final FilterExecutor[] filterExecutors = {
        null, null,
        new FilterExecutorUp() {
        @Override
            public NextAction execute(Filter filter, FilterChainContext context)
            throws IOException {
                return filter.handleAccept(context);
            }
        },
        new FilterExecutorUp() {
        @Override
            public NextAction execute(Filter filter, FilterChainContext context)
            throws IOException {
                return filter.handleConnect(context);
            }
        },
        new FilterExecutorUp() {
        @Override
            public NextAction execute(Filter filter, FilterChainContext context)
            throws IOException {
                return filter.handleRead(context);
            }
        },
        new FilterExecutorDown() {
        @Override
            public NextAction execute(Filter filter, FilterChainContext context)
            throws IOException {
                return filter.handleWrite(context);
            }
        },
        new FilterExecutorUp() {
        @Override
            public NextAction execute(Filter filter, FilterChainContext context)
            throws IOException {
                return filter.handleClose(context);
            }
        },
    };


    /**
     * Logger
     */
    private Logger logger = Grizzly.logger(DefaultFilterChain.class);

    public DefaultFilterChain() {
        this(new ArrayList<Filter>());
    }
    
    public DefaultFilterChain(Collection<Filter> initialFilters) {
        super(new ArrayList<Filter>(initialFilters));
    }

    @Override
    public ProcessorResult process(Context context) throws IOException {
        final IOEvent ioEvent = context.getIoEvent();
        if (ioEvent == IOEvent.WRITE && !((FilterChainContext) context).isUserWrite()) {
            final Connection connection = context.getConnection();
            final AsyncQueueEnabledTransport transport =
                    (AsyncQueueEnabledTransport) connection.getTransport();
            final AsyncQueueWriter writer = transport.getAsyncQueueIO().getWriter();

            writer.processAsync(connection);

            return ProcessorResult.createCompletedLeave();
        }

        return execute((FilterChainContext) context);
    }

    @Override
    public GrizzlyFuture read(Connection connection,
            CompletionHandler completionHandler) throws IOException {
        final FilterChainContext context = (FilterChainContext) Context.create(
                this, connection, IOEvent.READ, null, null);

        return ReadyFutureImpl.create(read(context));
    }

    @Override
    public ReadResult read(FilterChainContext context) throws IOException {
        final Connection connection = context.getConnection();
        if (!connection.isBlocking()) {
            throw new IllegalStateException("FilterChain doesn't support standalone non blocking read. Please use Filter instead.");
        } else {
            final UnsafeFutureImpl future = UnsafeFutureImpl.create();
            context.setCompletionFuture(future);

            final int ioEventIndex = context.getIoEvent().ordinal();
            final FilterExecutor executor = filterExecutors[ioEventIndex];

            do {
                checkRemainder(context, executor, 0, context.getEndIdx());
                executeChainPart(context, executor, context.getFilterIdx(), context.getEndIdx());
            } while (!future.isDone());

            try {
                final FilterChainContext retContext = (FilterChainContext) future.get();
                ReadResult rr = ReadResult.create(connection);
                rr.setMessage(retContext.getMessage());
                rr.setSrcAddress(retContext.getAddress());

                future.recycle(false);

                return rr;
            } catch (ExecutionException e) {
                Throwable t = e.getCause();
                if (t instanceof IOException) {
                    throw (IOException) t;
                }

                throw new IOException(t);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }
    }

    @Override
    public GrizzlyFuture write(Connection connection,
            Object dstAddress, Object message,
            CompletionHandler completionHandler)
            throws IOException {
        final FutureImpl<WriteResult> future = SafeFutureImpl.create();
        final FilterChainContext context = (FilterChainContext) Context.create(
                this, connection, IOEvent.WRITE, future, completionHandler);
        context.setAddress(dstAddress);
        context.setMessage(message);
        context.setUserWrite(true);
        ProcessorExecutor.resume(context);

        return future;
    }


    /**
     * Execute this FilterChain.
     * @param ctx {@link FilterChainContext} processing context
     * @throws java.lang.Exception
     */
    @Override
    public ProcessorResult execute(FilterChainContext ctx) {
        final int ioEventIndex = ctx.getIoEvent().ordinal();
        final FilterExecutor executor = filterExecutors[ioEventIndex];

        if (isEmpty()) ProcessorResult.createCompleted();

        if (ctx.getFilterIdx() == FilterChainContext.NO_FILTER_INDEX) {
            final int startIdx = executor.defaultStartIdx(ctx);
            ctx.setFilterIdx(startIdx);
            ctx.setStartIdx(startIdx);
            ctx.setEndIdx(executor.defaultEndIdx(ctx));
        }

        final int end = ctx.getEndIdx();

        try {
            do {
                if (!executeChainPart(ctx, executor, ctx.getFilterIdx(), end)) {
                    return ProcessorResult.createTerminate();
                }
            } while (checkRemainder(ctx, executor, ctx.getStartIdx(), end));
        } catch (IOException e) {
            try {
                logger.log(Level.FINE, "Exception during FilterChain execution", e);
                throwChain(ctx, executor, e);
                ctx.getConnection().close().markForRecycle(true);
            } catch (IOException ioe) {
                logger.log(Level.FINE, "Exception during reporting the failuer", ioe);
            }
        } catch (Exception e) {
            try {
                logger.log(Level.WARNING, "Exception during FilterChain execution", e);
                throwChain(ctx, executor, e);
                ctx.getConnection().close().markForRecycle(true);
            } catch (IOException ioe) {
                logger.log(Level.FINE, "Exception during reporting the failuer", ioe);
            }
        }

        return ProcessorResult.createCompleted();
    }
    
    /**
     * Sequentially lets each {@link Filter} in chain to process {@link IOEvent}.
     * 
     * @param ctx {@link FilterChainContext} processing context
     * @param executor {@link FilterExecutor}, which will call appropriate
     *          filter operation to process {@link IOEvent}.
     * @return <tt>false</tt> to terminate exectution, or <tt>true</tt> for
     *         normal exection process
     */
    protected final boolean executeChainPart(FilterChainContext ctx,
            FilterExecutor executor, int start, int end) throws IOException {
        final IOEvent ioEvent = ctx.getIoEvent();
        
        final Connection connection = ctx.getConnection();
        
        FiltersState filtersState = FILTERS_STATE_ATTR.get(connection);
        
        int i = start;

        while (i != end) {
            // current Filter to be executed
            final Filter currentFilter = get(i);

            // Check if there is any data stored for the current Filter
            final FilterStateElement filterState;
            if (filtersState != null && 
                    (filterState = filtersState.clearState(ioEvent, i)) != null) {
                Object storedMessage = filterState.getState();
                final Object currentMessage = ctx.getMessage();
                if (currentMessage != null) {
                    final Appender appender = filterState.getAppender();
                    if (appender != null) {
                        storedMessage =
                                appender.append(storedMessage, currentMessage);
                    } else {
                        storedMessage = ((Appendable) storedMessage).append(currentMessage);
                    }
                }
                
                ctx.setMessage(storedMessage);
            }

            // Save initial inputMessage
            final Object inputMessage = ctx.getMessage();
            
            // execute the task
            final NextAction nextNextAction = executeFilter(executor,
                    currentFilter, ctx);

            final int nextNextActionType = nextNextAction.type();
            
            if (nextNextActionType == SuspendAction.TYPE) { // on suspend - return immediatelly
                return false;
            }

            if (nextNextActionType == InvokeAction.TYPE) { // if we need to execute next filter
                // Take the remainder, if any?
                final Object remainder = ((InvokeAction) nextNextAction).getRemainder();
                if (remainder != null) {
                    boolean isStoreRemainder = true;
                    
                    if (remainder == inputMessage && remainder instanceof Buffer) {
                        isStoreRemainder = !((Buffer) remainder).shrink();
                    }

                    if (isStoreRemainder) {
                        if (filtersState == null) {
                            filtersState = new FiltersState(size());
                            FILTERS_STATE_ATTR.set(connection, filtersState);
                        }

                        filtersState.setState(ioEvent, i,
                                new FilterStateElement(FILTER_STATE_TYPE.REMAINDER,
                                remainder, null));
                    }
                }
            } else {
                // If the next action is StopAction and there is some data to store for the processed Filter - store it
                Object messageToStore;
                if (nextNextActionType == StopAction.TYPE &&
                        (messageToStore =
                        ((StopAction) nextNextAction).getRemainder()) != null) {
                    if (filtersState == null) {
                        filtersState = new FiltersState(size());
                        FILTERS_STATE_ATTR.set(connection, filtersState);
                    }

                    final Appender appender = ((StopAction) nextNextAction).getAppender();
                    filtersState.setState(ioEvent, i,
                            new FilterStateElement(FILTER_STATE_TYPE.INCOMPLETE,
                            messageToStore, appender));
                    return true;
                }
                
                return true;
            }

            i = executor.getNextFilter(ctx);
            ctx.setFilterIdx(i);
        }

        if (ioEvent == IOEvent.READ && i == end) {
            notifyCompleted(ctx, ctx);
        }

        return true;
    }

    /**
     * Execute the {@link Filter}, using specific {@link FilterExecutor} and
     * {@link FilterChainContext}.
     * 
     * @param executor
     * @param currentFilter
     * @param ctx
     *
     * @return {@link NextAction}.
     * 
     * @throws IOException
     */
    protected NextAction executeFilter(final FilterExecutor executor,
            final Filter currentFilter, final FilterChainContext ctx)
            throws IOException {

        NextAction nextNextAction;
        do {
            if (logger.isLoggable(Level.FINEST)) {
                logger.fine("Execute filter. filter=" + currentFilter +
                        " context=" + ctx);
            }
            // execute the task
            nextNextAction = executor.execute(currentFilter, ctx);

            if (logger.isLoggable(Level.FINEST)) {
                logger.fine("after execute filter. filter=" + currentFilter +
                        " context=" + ctx + " nextAction=" + nextNextAction);
            }
        } while (nextNextAction.type() == RerunFilterAction.TYPE);

        return nextNextAction;
    }

    /**
     * Sequentially lets each executed {@link Filter} to post process
     * {@link IOEvent}. The {@link Filter}s will be called in opposite order
     * they were called on processing phase.
     *
     * @param ctx {@link FilterChainContext} processing context
     * @param executor {@link FilterExecutor}, which will call appropriate
     *          filter operation to post process {@link IOEvent}.
     * @return <tt>false</tt> to terminate exectution, or <tt>true</tt> for
     *         normal exection process
     */
    protected boolean checkRemainder(FilterChainContext ctx,
            FilterExecutor executor, int start, int end) {

        final Connection connection = ctx.getConnection();

        // Take the last added remaining data info
        final FiltersState filtersState = FILTERS_STATE_ATTR.get(connection);

        if (filtersState == null) return false;
        final int add = (end - start > 0) ? 1 : -1;
        final IOEvent ioEvent = ctx.getIoEvent();

        for(int i = end - add; i != start - add; i -= add) {
            final FilterStateElement element = filtersState.getState(ioEvent, i);
            if (element != null &&
                    element.getType() == FILTER_STATE_TYPE.REMAINDER) {
                ctx.setFilterIdx(i);
                ctx.setMessage(null);
                return true;
            }
        }

        return false;
    }

    /**
     * Notify the filters about error.
     * @param ctx {@link FilterChainContext}
     * @return position of the last executed {@link Filter}
     */
    protected void throwChain(FilterChainContext ctx, FilterExecutor executor,
            Throwable exception) {

        notifyFailure(ctx, exception);
        
        final int endIdx = ctx.getStartIdx();

        if (ctx.getFilterIdx() == endIdx) return;

        int i;
        while(true) {
            i = executor.getPreviousFilter(ctx);
            ctx.setFilterIdx(i);
            get(i).exceptionOccurred(ctx, exception);

            if (i == endIdx) return;
        }
    }

    @Override
    public DefaultFilterChain subList(int fromIndex, int toIndex) {
        return new DefaultFilterChain(filters.subList(fromIndex, toIndex));
    }

    private void notifyCompleted(FilterChainContext context, Object result) {
        final CompletionHandler completionHandler = context.getCompletionHandler();
        final FutureImpl future = context.getCompletionFuture();

        if (completionHandler != null) {
            completionHandler.completed(result);
        }

        if (future != null) {
            future.result(result);
        }
    }

    private void notifyFailure(FilterChainContext context, Throwable e) {

        final CompletionHandler completionHandler = context.getCompletionHandler();
        final FutureImpl future = context.getCompletionFuture();

        if (completionHandler != null) {
            completionHandler.failed(e);
        }

        if (future != null) {
            future.failure(e);
        }
    }
    
    /**
     * Executes appropriate {@link Filter} processing method to process occured
     * {@link IOEvent}.
     */
    public interface FilterExecutor {
        public NextAction execute(Filter filter, FilterChainContext context)
                throws IOException;

        public int defaultStartIdx(FilterChainContext context);

        public int defaultEndIdx(FilterChainContext context);
        
        public int getNextFilter(FilterChainContext context);

        public int getPreviousFilter(FilterChainContext context);

        public boolean hasNextFilter(FilterChainContext context, int idx);

        public boolean hasPreviousFilter(FilterChainContext context, int idx);
    }

    /**
     * Executes appropriate {@link Filter} processing method to process occured
     * {@link IOEvent}.
     */
    public static abstract class FilterExecutorUp implements FilterExecutor {

        @Override
        public final int defaultStartIdx(FilterChainContext context) {
            if (context.getFilterIdx() != FilterChainContext.NO_FILTER_INDEX) {
                return context.getFilterIdx();
            }
            
            context.setFilterIdx(0);
            return 0;
        }

        @Override
        public final int defaultEndIdx(FilterChainContext context) {
            return context.getFilterChain().size();
        }

        @Override
        public final int getNextFilter(FilterChainContext context) {
            return context.getFilterIdx() + 1;
        }

        @Override
        public final int getPreviousFilter(FilterChainContext context) {
            return context.getFilterIdx() - 1;
        }

        @Override
        public final boolean hasNextFilter(FilterChainContext context, int idx) {
            return idx < context.getFilterChain().size() - 1;
        }

        @Override
        public final boolean hasPreviousFilter(FilterChainContext context, int idx) {
            return idx > 0;
        }
    }
    
    /**
     * Executes appropriate {@link Filter} processing method to process occured
     * {@link IOEvent}.
     */
    public static abstract class FilterExecutorDown implements FilterExecutor {
        @Override
        public final int defaultStartIdx(FilterChainContext context) {
            if (context.getFilterIdx() != FilterChainContext.NO_FILTER_INDEX) {
                return context.getFilterIdx();
            }

            final int idx = context.getFilterChain().size() - 1;
            context.setFilterIdx(idx);
            return idx;
        }

        @Override
        public final int defaultEndIdx(FilterChainContext context) {
            return -1;
        }

        @Override
        public final int getNextFilter(FilterChainContext context) {
            return context.getFilterIdx() - 1;
        }

        @Override
        public final int getPreviousFilter(FilterChainContext context) {
            return context.getFilterIdx() + 1;
        }

        @Override
        public final boolean hasNextFilter(FilterChainContext context, int idx) {
            return idx > 0;
        }

        @Override
        public final boolean hasPreviousFilter(FilterChainContext context, int idx) {
            return idx < context.getFilterChain().size() - 1;
        }
    }

    public static final class FiltersState {
        private final FilterStateElement[][] state;

        public FiltersState(int filtersNum) {
            this.state = new FilterStateElement[IOEvent.values().length][filtersNum];
        }

        public FilterStateElement getState(final IOEvent event,
                final int filterIndex) {
            return state[event.ordinal()][filterIndex];
        }

        public void setState(final IOEvent event, final int filterIndex,
                final FilterStateElement stateElement) {
            state[event.ordinal()][filterIndex] = stateElement;
        }

        public FilterStateElement clearState(final IOEvent event,
                final int filterIndex) {
            
            final int eventIdx = event.ordinal();
            final FilterStateElement oldState = state[eventIdx][filterIndex];
            state[eventIdx][filterIndex] = null;
            return oldState;
        }

        public int indexOf(final IOEvent event,
                final FILTER_STATE_TYPE type) {
            return indexOf(event, type, 0);
        }

        public int indexOf(final IOEvent event,
                final FILTER_STATE_TYPE type, final int start) {
            final int eventIdx = event.ordinal();
            final int length = state[eventIdx].length;

            for (int i = start; i < length; i++) {
                final FilterStateElement filterState;
                if ((filterState = state[eventIdx][i]) != null &&
                        filterState.getType() == type) {
                    return i;
                }
            }

            return -1;
        }

        public int lastIndexOf(final IOEvent event,
                final FILTER_STATE_TYPE type, final int end) {
            final int eventIdx = event.ordinal();

            for (int i = end - 1; i >= 0; i--) {
                final FilterStateElement filterState;
                if ((filterState = state[eventIdx][i]) != null &&
                        filterState.getType() == type) {
                    return i;
                }
            }

            return -1;
        }

        public int lastIndexOf(final IOEvent event,
                final FILTER_STATE_TYPE type) {
            return lastIndexOf(event, type, state[event.ordinal()].length);
        }
    }

    public static final class FilterStateElement {
        public static final FilterStateElement create(
                final FILTER_STATE_TYPE type,
                final Object remainder) {
            if (remainder instanceof Buffer) {
                return create(type, (Buffer) remainder,
                        BufferUtils.BUFFER_APPENDER);
            } else {
                return create(type, (Appendable) remainder);
            }
        }

        public static final FilterStateElement create(
                final FILTER_STATE_TYPE type, final Appendable state) {
            return new FilterStateElement(type, state);
        }

        public static final <E> FilterStateElement create(
                final FILTER_STATE_TYPE type,
                final E state, final Appender<E> appender) {
            return new FilterStateElement(type, state, appender);
        }

        private final FILTER_STATE_TYPE type;
        private final Object state;
        private final Appender appender;

        private FilterStateElement(FILTER_STATE_TYPE type, Appendable state) {
            this.type = type;
            this.state = state;
            appender = null;
        }

        private <E> FilterStateElement(FILTER_STATE_TYPE type, E state, Appender<E> appender) {
            this.type = type;
            this.state = state;
            this.appender = appender;
        }

        private FILTER_STATE_TYPE getType() {
            return type;
        }

        public Object getState() {
            return state;
        }

        public Appender getAppender() {
            return appender;
        }
    }
}
