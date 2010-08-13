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

package com.sun.grizzly.samples.echo;

import com.sun.grizzly.filterchain.BaseFilter;
import java.io.IOException;
import com.sun.grizzly.filterchain.FilterChain;
import com.sun.grizzly.filterchain.FilterChainContext;
import com.sun.grizzly.filterchain.NextAction;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of {@link FilterChain} filter, which asynchronously replies
 * with the request message.
 *
 * @author Alexey Stashok
 */
public class EchoFilterAsync extends BaseFilter {

    // Create Scheduled thread pool
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5, new ThreadFactory() {

        @Override
        public Thread newThread(Runnable r) {
            final Thread thread = new Thread(r);
            thread.setDaemon(true);
            return thread;
        }
    });
    
    /**
     * Handle just read operation, when some message has come and ready to be
     * processed.
     *
     * @param ctx Context of {@link FilterChainContext} processing
     * @return the next action
     * @throws java.io.IOException
     */
    @Override
    public NextAction handleRead(final FilterChainContext ctx)
            throws IOException {

        final Object message = ctx.getMessage();

        if (message != null) {
            // If message is not null - it's first time the filter is getting called
            // and we need to init async thread, which will reply

            
            // Peer address is used for non-connected UDP Connection :)
            final Object peerAddress = ctx.getAddress();

            // suspend the current execution
            ctx.suspend();
            
            // schedule async work
            scheduler.schedule(new Runnable() {

                @Override
                public void run() {
                    try {
                        // write the response
                        ctx.write(peerAddress, message, null);
                    } catch (IOException ignored) {
                    }

                    // set the message null, to let our filter to distinguish resumed context
                    ctx.setMessage(null);

                    // resume the context
                    ctx.resume();
                }
            }, 5, TimeUnit.SECONDS);

            // return suspend status
            return ctx.getSuspendAction();
        }

        // If message is null - it means async thread completed the execution
        // and resumed the context

        return ctx.getStopAction();
    }

}
