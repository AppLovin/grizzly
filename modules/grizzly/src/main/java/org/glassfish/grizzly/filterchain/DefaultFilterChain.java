/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2008-2013 Oracle and/or its affiliates. All rights reserved.
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
package org.glassfish.grizzly.filterchain;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.grizzly.Appendable;
import org.glassfish.grizzly.Appender;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Context;
import org.glassfish.grizzly.Event;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.ProcessorExecutor;
import org.glassfish.grizzly.ProcessorResult;
import org.glassfish.grizzly.ReadResult;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.asyncqueue.LifeCycleHandler;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.attributes.AttributeBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext.Operation;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.localization.LogMessages;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.utils.Exceptions;
import org.glassfish.grizzly.utils.Futures;
import org.glassfish.grizzly.utils.NullaryFunction;

/**
 * Default {@link FilterChain} implementation
 *
 * @see FilterChain
 * @see Filter
 * 
 * @author Alexey Stashok
 */
final class DefaultFilterChain extends ListFacadeFilterChain {

    public enum FILTER_STATE_TYPE {
        INCOMPLETE, UNPARSED
    }
    
    private static final Attribute<FiltersState> FILTERS_STATE_ATTR =
            AttributeBuilder.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(
            FilterChain.class.getName() + ".filters-state-attr",
            new NullaryFunction<FiltersState>() {
                @Override
                public FiltersState evaluate() {
                    return new FiltersState();
                }
            });
            
    /**
     * Logger
     */
    private static final Logger LOGGER = Grizzly.logger(DefaultFilterChain.class);

    public DefaultFilterChain(Collection<Filter> initialFilters) {
        super(new ArrayList<Filter>(initialFilters));
    }

    @Override
    public ProcessorResult process(final Context context) {
        if (isEmpty()) return ProcessorResult.createComplete();
        
        final InternalContextImpl internalContext = (InternalContextImpl) context;
        final FilterChainContext filterChainContext = internalContext.filterChainContext;

        if (filterChainContext.getOperation() == Operation.NONE) {
            final Event event = internalContext.getEvent();

            final Operation operation =
                    FilterChainContext.event2Operation(event);

            if (operation != null) {
                filterChainContext.setOperation(operation);
            } else {
                filterChainContext.setOperation(Operation.EVENT);
            }
        }

        return execute(filterChainContext);
    }
    
    /**
     * Execute this FilterChain.
     * @param initialContext {@link FilterChainContext} processing context
     * @throws java.lang.Exception
     */
    @Override
    public ProcessorResult execute(final FilterChainContext initialContext) {
        
        FilterChainContext ctx = initialContext;
        
        final FilterExecutor executor = ExecutorResolver.resolve(ctx);

        if (ctx.getFilterIdx() == FilterChainContext.NO_FILTER_INDEX) {
            executor.initIndexes(ctx);
        }

        final Connection connection = ctx.getConnection();
        final FiltersState filtersState = obtainFiltersState(connection);
        final int end = ctx.getEndIdx();

        try {
            
            boolean isRerunFilterChain;
            
            do {
                isRerunFilterChain = false;
                final FilterExecution execution = executeChainPart(ctx,
                        executor, ctx.getFilterIdx(), end, filtersState);
                switch (execution.type) {
                    case FilterExecution.TERMINATE_TYPE:
                        return ProcessorResult.createTerminate();
                    case FilterExecution.FORK_TYPE:
                        ctx = execution.getContext();
                        isRerunFilterChain = true;
                }
            } while (isRerunFilterChain ||
                    prepareRemainder(ctx, filtersState,
                    ctx.getStartIdx(), end));
        } catch (Throwable e) {
            LOGGER.log(e instanceof IOException ? Level.FINE : Level.WARNING,
                    LogMessages.WARNING_GRIZZLY_FILTERCHAIN_EXCEPTION(), e);
            throwChain(ctx, executor, e);
            ctx.getConnection().closeSilently();

            return ProcessorResult.createError(e);
        }

        return ctx == initialContext ?
                ProcessorResult.createComplete() :
                ProcessorResult.createComplete(ctx.internalContext);
    }

    /**
     * Sequentially lets each {@link Filter} in chain to process {@link org.glassfish.grizzly.IOEvent}.
     * 
     * @param ctx {@link FilterChainContext} processing context
     * @param executor {@link FilterExecutor}, which will call appropriate
     *          filter operation to process {@link org.glassfish.grizzly.IOEvent}.
     * @return TODO: Update
     */
    @SuppressWarnings("unchecked")
    protected final FilterExecution executeChainPart(final FilterChainContext ctx,
            final FilterExecutor executor,
            final int start,
            final int end,
            final FiltersState filtersState)
            throws Exception {

        int i = start;
        Filter currentFilter = null;
        
        int lastNextActionType = InvokeAction.TYPE;
        NextAction lastNextAction = null;
        
        while (i != end) {
            
            // current Filter to be executed
            currentFilter = get(i);
            
            if (ctx.predefinedThrowable != null) {
                final Throwable error = ctx.predefinedThrowable;
                ctx.predefinedThrowable = null;
                
                Exceptions.throwException(error);
            }
                        
            if (ctx.predefinedNextAction == null) {

                // Checks if there was a remainder message stored from the last filter execution
                checkStoredMessage(ctx, filtersState, currentFilter);

                // execute the task
                lastNextAction = executeFilter(executor, currentFilter, ctx);
            } else {
                lastNextAction = ctx.predefinedNextAction;
                ctx.predefinedNextAction = null;
            }
            
            lastNextActionType = lastNextAction.type();
            if (lastNextActionType != InvokeAction.TYPE) { // if we don't need to execute next filter
                break;
            }
            
            final InvokeAction invokeAction = (InvokeAction) lastNextAction;
            final Object chunk = invokeAction.getChunk();
            
            if (chunk != null) {
                // Store the remainder
                final FILTER_STATE_TYPE type = invokeAction.isIncomplete() ?
                                               FILTER_STATE_TYPE.INCOMPLETE :
                                               FILTER_STATE_TYPE.UNPARSED;
                storeMessage(ctx,
                        filtersState, type,
                        currentFilter,
                        chunk, invokeAction.getAppender());
            }

            i = executor.getNextFilter(ctx);
            ctx.setFilterIdx(i);
        }

        switch (lastNextActionType) {
            case InvokeAction.TYPE:
                notifyComplete(ctx);
                break;
            case StopAction.TYPE:
                assert currentFilter != null;
                
                // If the next action is StopAction and there is some data to store for the processed Filter - store it
                final StopAction stopAction = (StopAction) lastNextAction;
                storeMessage(ctx,
                             filtersState,
                             FILTER_STATE_TYPE.INCOMPLETE,
                             currentFilter,
                             stopAction.getIncompleteChunk(),
                             stopAction.getAppender());
                break;
            case ForkAction.TYPE:
                final ForkAction forkAction =
                        (ForkAction) lastNextAction;
                return FilterExecution.createFork(
                        forkAction.getContext());
            case SuspendAction.TYPE: // on suspend - return immediatelly
                return FilterExecution.createTerminate();
        }

        return FilterExecution.createContinue();
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
            throws Exception {

        NextAction nextNextAction;
        do {
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.log(Level.FINE, "Execute filter. filter={0} context={1}",
                        new Object[]{currentFilter, ctx});
            }
            // execute the task
            nextNextAction = executor.execute(currentFilter, ctx);

            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.log(Level.FINE, "after execute filter. filter={0} context={1} nextAction={2}",
                        new Object[]{currentFilter, ctx, nextNextAction});
            }
        } while (nextNextAction.type() == RerunFilterAction.TYPE);

        return nextNextAction;
    }
    
    /**
     * Locates a message remainder in the {@link FilterChain}, associated with the
     * {@link Connection} and prepares the {@link Context} for remainder processing.
     */
    protected boolean prepareRemainder(final FilterChainContext ctx,
            final FiltersState filtersState, final int start, final int end) {

        final int idx = indexOfRemainder(filtersState, ctx.getOperation(),
                start, end);
        
        if (idx != -1) {
            ctx.setFilterIdx(idx);
            ctx.setMessage(null);
            return true;
        }
        
        return false;
    }
    
    /**
     * Locates a message remainder in the {@link FilterChain}, associated with the
     * {@link Connection}.
     */
    private int indexOfRemainder(final FiltersState filtersState,
            final Operation operation, final int start, final int end) {

        final int add = (end - start > 0) ? 1 : -1;
        
        for (int i = end - add; i != start - add; i -= add) {
            final FilterState element =
                    filtersState.getState(operation, get(i));
            if (element != null
                    && element.getType() == FILTER_STATE_TYPE.UNPARSED) {
                return i;
            }
        }
        
        return -1;
    }
    
    @Override
    public void read(final Connection connection,
            final CompletionHandler<ReadResult> completionHandler) {
        final FilterChainContext context = obtainFilterChainContext(connection);
        context.setOperation(FilterChainContext.Operation.READ);
        context.getTransportContext().configureBlocking(true);
        
        try {
            final ReadResult readResult = read(context);
            Futures.notifyResult(null, completionHandler, readResult);
        } catch (IOException e) {
            Futures.notifyFailure(null, completionHandler, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public ReadResult read(final FilterChainContext context) throws IOException {
        final Connection connection = context.getConnection();
        if (!context.getTransportContext().isBlocking()) {
            throw new IllegalStateException("FilterChain doesn't support standalone non blocking read. Please use Filter instead.");
        } else {
            final FutureImpl<FilterChainContext> future =
                    Futures.createUnsafeFuture();
            context.operationCompletionHandler =
                    Futures.toCompletionHandler(future);

            final FilterExecutor executor = ExecutorResolver.resolve(context);
            final FiltersState filtersState = obtainFiltersState(connection);

            try {
                do {
                    if (!prepareRemainder(context, filtersState,
                            0, context.getEndIdx())) {
                        context.setFilterIdx(0);
                        context.setMessage(null);
                    }

                    executeChainPart(context, executor, context.getFilterIdx(),
                            context.getEndIdx(), filtersState);
                } while (!future.isDone());

                final FilterChainContext retContext = future.get();
                ReadResult rr = ReadResult.create(connection);
                rr.setMessage(retContext.getMessage());
                rr.setSrcAddressHolder(retContext.getAddressHolder());

                future.recycle(false);

                return rr;
            } catch (ExecutionException e) {
                throw Exceptions.makeIOException(e.getCause());
            } catch (Exception e) {
                throw Exceptions.makeIOException(e);
            }
        }
    }

    @Override
    public void write(final Connection connection,
            final Object dstAddress, final Object message,
            final CompletionHandler completionHandler,
            final LifeCycleHandler lifeCycleHandler) {

        final FilterChainContext context = obtainFilterChainContext(connection);
        context.transportFilterContext.completionHandler = completionHandler;
        context.transportFilterContext.lifeCycleHandler = lifeCycleHandler;
        context.setAddress(dstAddress);
        context.setMessage(message);
        context.setOperation(Operation.WRITE);
        ProcessorExecutor.execute(context.internalContext);
    }

    @Override
    public void flush(final Connection connection,
            final CompletionHandler<WriteResult> completionHandler) {
        final FilterChainContext context = obtainFilterChainContext(connection);
        context.setOperation(Operation.EVENT);
        context.setEvent(TransportFilter.createFlushEvent(completionHandler));
        ExecutorResolver.DOWNSTREAM_EXECUTOR_SAMPLE.initIndexes(context);

        ProcessorExecutor.execute(context.internalContext);
    }

    @Override
    public void fireEventDownstream(final Connection connection,
            final Event event,
            final CompletionHandler<FilterChainContext> completionHandler) {
        final FilterChainContext context = obtainFilterChainContext(connection);
        context.operationCompletionHandler = completionHandler;
        context.setOperation(Operation.EVENT);
        context.setEvent(event);
        ExecutorResolver.DOWNSTREAM_EXECUTOR_SAMPLE.initIndexes(context);

        ProcessorExecutor.execute(context.internalContext);
    }

    @Override
    public void fireEventUpstream(final Connection connection,
            final Event event,
            final CompletionHandler<FilterChainContext> completionHandler) {
        final FilterChainContext context = obtainFilterChainContext(connection);
        context.operationCompletionHandler = completionHandler;
        context.setOperation(Operation.EVENT);
        context.setEvent(event);
        ExecutorResolver.UPSTREAM_EXECUTOR_SAMPLE.initIndexes(context);

        ProcessorExecutor.execute(context.internalContext);
    }

    @Override
    public void fail(FilterChainContext context, Throwable failure) {
        throwChain(context, ExecutorResolver.resolve(context), failure);
    }

    /**
     * Notify the filters about error.
     * @param ctx {@link FilterChainContext}
     * @return position of the last executed {@link Filter}
     */
    private void throwChain(final FilterChainContext ctx,
            final FilterExecutor executor, final Throwable exception) {

        notifyFailure(ctx, exception);

        final int endIdx = ctx.getStartIdx();

        if (ctx.getFilterIdx() == endIdx) {
            return;
        }

        int i;
        while (true) {
            i = executor.getPreviousFilter(ctx);
            ctx.setFilterIdx(i);
            get(i).exceptionOccurred(ctx, exception);

            if (i == endIdx) {
                return;
            }
        }
    }

    @Override
    public DefaultFilterChain subList(int fromIndex, int toIndex) {
        return new DefaultFilterChain(filters.subList(fromIndex, toIndex));
    }

    private static FiltersState obtainFiltersState(final Connection connection) {
        return FILTERS_STATE_ATTR.get(connection);
    }

    /**
     * Checks if {@link Connection} has some stored data related to the processing
     * {@link Filter}. If yes - appends new context data to the stored one and
     * set the result as context message.
     * 
     * @param ctx {@link FilterChainContext}
     * @param filtersState {@link FiltersState} associated with the Connection
     * @param filter the current filter
     */
    @SuppressWarnings("unchecked")
    private void checkStoredMessage(final FilterChainContext ctx,
            final FiltersState filtersState, final Filter filter) {

        final Operation operation = ctx.getOperation();
        final FilterState filterState;

        // Check if there is any data stored for the current Filter
        if (filtersState != null && (filterState = filtersState.clearState(operation, filter)) != null) {
            Object storedMessage = filterState.getState();
            final Object currentMessage = ctx.getMessage();
            if (currentMessage != null) {
                final Appender appender = filterState.getAppender();
                if (appender != null) {
                    storedMessage = appender.append(storedMessage, currentMessage);
                } else {
                    storedMessage = ((Appendable) storedMessage).append(currentMessage);
                }
            }
            ctx.setMessage(storedMessage);
        }
    }

    /**
     * Stores the Filter associated remainder. This remainder will be reused next
     * time the same filter will be invoked on this Connection.
     * 
     * @param ctx
     * @param filtersState
     * @param type
     * @param filter
     * @param messageToStore
     * @param appender
     * @return
     */
    private <M> void storeMessage(final FilterChainContext ctx,
            final FiltersState filtersState, final FILTER_STATE_TYPE type,
            final Filter filter, final M messageToStore,
            final Appender<M> appender) {

        if (messageToStore != null) {
            final Operation operation = ctx.getOperation();

            filtersState.setState(operation, filter,
                    FilterState.create(type, messageToStore, appender));
        }
    }

    private void notifyComplete(final FilterChainContext context) {
        final CompletionHandler<FilterChainContext> completionHandler =
                context.operationCompletionHandler;
        if (completionHandler != null) {
            completionHandler.completed(context);
        }


        // If TransportFilter was invoked on the way - the following CompletionHandler and Future
        // will be null.
        final CompletionHandler<?> transportCompletionHandler =
                context.transportFilterContext.completionHandler;

        if (transportCompletionHandler != null) {
            transportCompletionHandler.completed(null);
        }
    }

    private void notifyFailure(final FilterChainContext context, final Throwable e) {

        final CompletionHandler completionHandler = context.operationCompletionHandler;

        if (completionHandler != null) {
            completionHandler.failed(e);
        }

        final CompletionHandler transportCompletionHandler = context.transportFilterContext.completionHandler;

        if (transportCompletionHandler != null) {
            transportCompletionHandler.failed(e);
        }
    }

    public static final class FiltersState {

        private final Map<Filter, FilterState>[] state;

        @SuppressWarnings("unchecked")
        public FiltersState() {
            this.state = new Map[Operation.values().length];
        }

        public FilterState getState(final Operation operation,
                final Filter filter) {
            final Map<Filter, FilterState> stateMap = state[operation.ordinal()];
            return stateMap != null && !stateMap.isEmpty() ?
                    stateMap.get(filter) : null;
        }

        public void setState(final Operation operation, final Filter filter,
                final FilterState stateElement) {
            Map<Filter, FilterState> stateMap =
                    state[operation.ordinal()];
            if (stateMap == null) {
                stateMap = new WeakHashMap<Filter, FilterState>();
                state[operation.ordinal()] = stateMap;
            }
            
            stateMap.put(filter, stateElement);
        }

        public FilterState clearState(final Operation operation,
                final Filter filter) {
            final Map<Filter, FilterState> stateMap = state[operation.ordinal()];
            return stateMap != null && !stateMap.isEmpty() ?
                    stateMap.remove(filter) : null;
        }
    }

    private static final class FilterState {

        public static FilterState create(
                final FILTER_STATE_TYPE type,
                final Object remainder) {
            if (remainder instanceof Buffer) {
                return create(type, (Buffer) remainder,
                        Buffers.getBufferAppender(true));
            } else {
                return create(type, (Appendable) remainder);
            }
        }

        public static FilterState create(
                final FILTER_STATE_TYPE type, final Appendable state) {
            return new FilterState(type, state);
        }

        public static <E> FilterState create(
                final FILTER_STATE_TYPE type,
                final E state, final Appender<E> appender) {
            return new FilterState(type, state, appender);
        }
        
        private final FILTER_STATE_TYPE type;
        private final Object state;
        private final Appender appender;

        private FilterState(FILTER_STATE_TYPE type, Appendable state) {
            this.type = type;
            this.state = state;
            appender = null;
        }

        private <E> FilterState(FILTER_STATE_TYPE type, E state, Appender<E> appender) {
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
    
    private static final class FilterExecution {
        private static final int CONTINUE_TYPE = 0;
        private static final int TERMINATE_TYPE = 1;
        private static final int FORK_TYPE = 2;
        
        private static final FilterExecution CONTINUE =
                new FilterExecution(CONTINUE_TYPE, null);
        
        private static final FilterExecution TERMINATE =
                new FilterExecution(TERMINATE_TYPE, null);
        
        private final int type;
        private final FilterChainContext context;
        
        public static FilterExecution createContinue() {
            return CONTINUE;
        }

        public static FilterExecution createTerminate() {
            return TERMINATE;
        }
        
        public static FilterExecution createFork(final FilterChainContext context) {
            return new FilterExecution(FORK_TYPE, context);
        }

        public FilterExecution(final int type, final FilterChainContext context) {
            this.type = type;
            this.context = context;
        }

        public int getType() {
            return type;
        }
        
        public FilterChainContext getContext() {
            return context;
        }
    }
}
