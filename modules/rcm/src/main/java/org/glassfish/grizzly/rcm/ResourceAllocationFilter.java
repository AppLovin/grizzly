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

package org.glassfish.grizzly.rcm;


import java.io.IOException;
import java.util.Collection;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Level;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Context;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.ProcessorRunnable;
import org.glassfish.grizzly.filterchain.FilterAdapter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.StopAction;
import org.glassfish.grizzly.filterchain.TerminateAction;
import org.glassfish.grizzly.streams.StreamReader;
import org.glassfish.grizzly.threadpool.DefaultThreadPool;

/**
 * This ProtocolFilter is an implementation of a Resource Consumption Management
 * (RCM) system. RCM system are allowing you to enable virtualization of system
 * resources per web application, similar to Solaris 10 Zone or the outcome of
 * the upcoming JSR 284.
 *
 * This ProtocolFiler uses a {@link ProtocolParser} to determine which
 * token to use to enable virtualization. As an example, configuring this class
 * to use the <code>ContextRootAlgorithm</code> will allow virtualization
 * and isolation of http request. As an example, if you define:
 *
 * -Dcom.sun.grizzly.rcm.policyMetric="/myApplication|0.9"
 *
 * This ProtocolFilter will allocate 90% of the current threads count to
 * application myApplication, and the remaining 10% to any other context-root
 * (or application). See com.sun.grizzly.rcm.RCM for an example.
 *
 * @author Jeanfrancois Arcand
 */
public class ResourceAllocationFilter extends FilterAdapter {

    protected final static String RESERVE = "reserve";
    protected final static String CEILING = "ceiling";

    protected final static String ALLOCATION_MODE =
            "org.glassfish.grizzly.rcm.policyMethod";

    protected final static String RULE_TOKENS =
            "org.glassfish.grizzly.rcm.policyMetric";

    private final static String DELAY_VALUE = "org.glassfish.grizzly.rcm.delay";

    protected final static String QUERY_STRING="?";
    protected final static String PATH_STRING="/";

    /**
     * The {@link ExecutorService} configured based on the
     * <code>threadRatio</code>. This {@link ExecutorService} is only used
     * by privileged application.
     */
    protected final static ConcurrentHashMap<String, ExecutorService> threadPools
            = new ConcurrentHashMap<String, ExecutorService>();


    /**
     * The list of privileged token used to decide if a request can be
     * serviced by the privileged {@link ExecutorService}.
     */
    protected final static ConcurrentHashMap<String,Double>
            privilegedTokens = new ConcurrentHashMap<String,Double>();


    /**
     * The thread ratio used when an application isn't listed as a privileged
     * application.
     */
    protected static double leftRatio = 1;


    /**
     * The allocation mode used: celling or Reserve. With Ceiling policy,
     * the strategy is to wait until all apps queus are showing some slack.
     * With Reserve policiy, if 100% reservation is made by other apps,
     * cancel the request processing.
     */
    protected static String allocationPolicy = RESERVE;


    /**
     * The time this thread will sleep when a rule is delayed.
     */
    private static long delayValue = 5 * 1000;

    static {
        try{
            if (System.getProperty(RULE_TOKENS) != null){
                StringTokenizer privList =
                        new StringTokenizer(System.getProperty(RULE_TOKENS),",");
                StringTokenizer privElement;
                String tokens;
                double countRatio = 0;
                double tokenValue;
                while (privList.hasMoreElements()){
                    privElement = new StringTokenizer(privList.nextToken());

                    while (privElement.hasMoreElements()){
                        tokens = privElement.nextToken();
                        int index = tokens.indexOf("|");
                        tokenValue = Double.valueOf(tokens.substring(index+1));
                        privilegedTokens.put
                                (tokens.substring(0, index),tokenValue);
                        countRatio += tokenValue;
                    }
                }
                if ( countRatio > 1 ) {
                    Grizzly.logger.info("Thread ratio too high. The total must be lower or equal to 1");
                }  else {
                    leftRatio = 1 - countRatio;
                }
            }
        } catch (Exception ex){
            Grizzly.logger.log(Level.SEVERE,"Unable to set the ratio",ex);
        }

        if (System.getProperty(ALLOCATION_MODE) != null){
            allocationPolicy = System.getProperty(ALLOCATION_MODE);
            if ( !allocationPolicy.equals(RESERVE) &&
                    !allocationPolicy.equals(CEILING) ){
                Grizzly.logger.info("Invalid allocation policy");
                allocationPolicy = RESERVE;
            }
        }
    }


    public ResourceAllocationFilter() {

    }

    @Override
    public NextAction handleRead(FilterChainContext ctx, NextAction nextAction) throws IOException {
        StreamReader reader = ctx.getStreamReader();

        StringBuilder sb = new StringBuilder(256);
        
        if (!parse(reader, 0, sb)) {
            return new StopAction();
        }

        String token = getContextRoot(sb.toString());
        int delayCount = 0;
        while (leftRatio == 0 && privilegedTokens.get(token) == null) {
            if ( allocationPolicy.equals(RESERVE) ){
                delay(ctx);
                delayCount++;
            } else if ( allocationPolicy.equals(CEILING) ) {

                // If true, then we need to wait for free space. If false, then
                // we can go ahead and let the task execute with its default
                // thread pool
                if (isThreadPoolInUse()){
                    delay(ctx);
                    delayCount++;
                } else {
                    break;
                }
            }
            if (delayCount > 5) {
                ctx.getConnection().close();
                return new StopAction();
            }
        }

        // Lazy instanciation
        ExecutorService threadPool = threadPools.get(token);
        if (threadPool == null) {
            threadPool = filterRequest(token,
                    ctx.getConnection().getTransport().getWorkerThreadPool());
            threadPools.put(token, threadPool);
        }


        ctx.setProcessorExecutor(threadPool);
        ctx.setCurrentFilterIdx(ctx.getCurrentFilterIdx() + 1);
        threadPool.execute(new ProcessorRunnable(ctx));
        return new TerminateAction();
    }


    /**
     * Delay the request processing.
     */
    private void delay(Context ctx){
        // This is sub optimal. Grizzly ARP should be used instead
        // tp park the request.
        try{
            Thread.sleep(delayValue);
        } catch (InterruptedException ex) {
            Grizzly.logger.log(Level.SEVERE,"Delay exception",ex);
        }
    }


    /**
     * Filter the request and decide which thread pool to use.
     */
    public ExecutorService filterRequest(String token, ExecutorService p) {
        ExecutorService es = null;
        if (p instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor threadPool = (ThreadPoolExecutor) p;
            int maxThreads = threadPool.getMaximumPoolSize();

            // Set the number of dispatcher thread to 1.
            threadPool.setCorePoolSize(1);

            Double threadRatio = privilegedTokens.get(token);
            boolean defaultThreadPool = false;
            if (threadRatio == null) {
                es = threadPools.get("*");
                if (es != null){
                    return es;
                }

                threadRatio = (leftRatio == 0 ? 0.5 : leftRatio);
                defaultThreadPool = true;
            }

            int privilegedCount = (threadRatio == 1 ? maxThreads :
                (int) (maxThreads * threadRatio) + 1);

            es = newThreadPool(privilegedCount, p);
            if (defaultThreadPool){
                threadPools.put("*", es);
            }
        }

        return es;
    }


    /**
     * Creates a new {@link ExecutorService}
     */
    protected ExecutorService newThreadPool(int threadCount, ExecutorService p) {
        if (threadCount == 0){
            return null;
        }
        DefaultThreadPool threadPool = new DefaultThreadPool();
        threadPool.setCorePoolSize(1);
        threadPool.setMaximumPoolSize(threadCount);
        threadPool.setName("RCM_" + threadCount);
        threadPool.start();
        return threadPool;
    }


    /**
     * Check to see if the privileged thread pool are in-use right now.
     */
    protected boolean isThreadPoolInUse(){
        Collection<ExecutorService> collection = threadPools.values();
        for (ExecutorService threadPool: collection){
            if (threadPool instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor pool = (ThreadPoolExecutor) threadPool;
                if (pool.getQueue().size() > 0) {
                    return true;
                }
            }
        }
        return false;
    }


    /***
     * Get the context-root from the {@link StreamReader}
     */
    protected String getContextRoot(String token){
        // Remove query string.
        int index = token.indexOf(QUERY_STRING);
        if ( index != -1){
            token = token.substring(0,index);
        }

        boolean slash = token.endsWith(PATH_STRING);
        if ( slash ){
            token = token.substring(0,token.length() -1);
        }
        return token;
    }

    protected boolean parse(StreamReader reader, int state, StringBuilder sb) throws IOException {

        if (findSpace(reader, state, sb) == 2) {
            return true;
        }

        int size = reader.availableDataSize();
        Buffer currentBuffer = reader.getBuffer();

        if (size > currentBuffer.remaining()) {
            reader.finishBuffer();
            boolean ret = parse(reader, state, sb);
            reader.prependBuffer(currentBuffer);
            return ret;
        }

        return false;
    }

    private int findSpace(StreamReader reader, int state, StringBuilder sb) {
        Buffer currentBuffer = reader.getBuffer();

        int pos = currentBuffer.position();
        int lim = currentBuffer.limit();

        for(int i=pos; i<lim; i++) {
            char c = (char) currentBuffer.get(i);

            if (c == ' ') {
                state++;
                if (state == 2) {
                    return state;
                }
            } else if (state == 1) {
                sb.append(c);
            }
        }

        return state;
    }
}
