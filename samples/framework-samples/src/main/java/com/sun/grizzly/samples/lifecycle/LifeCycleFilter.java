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

package com.sun.grizzly.samples.lifecycle;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.grizzly.Connection;
import com.sun.grizzly.Grizzly;
import com.sun.grizzly.attributes.Attribute;
import com.sun.grizzly.filterchain.BaseFilter;
import com.sun.grizzly.filterchain.FilterChainContext;
import com.sun.grizzly.filterchain.NextAction;

/**
 * Sample {@link Filter}, which tracks the connections lifecycle
 * The new connections could be either accepted if we have server, or connected,
 * if we establish client connection.
 *
 * @author Alexey Stashok
 */
public class LifeCycleFilter extends BaseFilter {
    private Attribute<Integer> connectionIdAttribute =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute("connection-id");

    private AtomicInteger totalConnectionNumber;
    private Map<Connection, Integer> activeConnectionsMap;

    public LifeCycleFilter() {
        totalConnectionNumber = new AtomicInteger();
        activeConnectionsMap = new ConcurrentHashMap<Connection, Integer>();
    }

    /**
     * Method is called, when new {@link Connection} was
     * accepted by a {@link Transport}
     *
     * @param ctx the filter chain context
     * @param nextAction next action to be executed
     *                   by the chain (could be modified)
     * @return the next action to be executed by chain
     * @throws java.io.IOException
     */
    @Override
    public NextAction handleAccept(FilterChainContext ctx, NextAction nextAction) throws IOException {
        newConnection(ctx.getConnection());

        return nextAction;
    }


    /**
     * Method is called, when new client {@link Connection} was
     * connected to some endpoint
     *
     * @param ctx the filter chain context
     * @param nextAction next action to be executed
     *                   by the chain (could be modified)
     * @return the next action to be executed by chain
     * @throws java.io.IOException
     */
    @Override
    public NextAction handleConnect(FilterChainContext ctx, NextAction nextAction) throws IOException {
        newConnection(ctx.getConnection());

        return nextAction;
    }

    /**
     * Method is called, when the {@link Connection} is getting closed
     *
     * @param ctx the filter chain context
     * @param nextAction next action to be executed
     *                   by the chain (could be modified)
     * @return the next action to be executed by chain
     * @throws java.io.IOException
     */
    @Override
    public NextAction handleClose(FilterChainContext ctx, NextAction nextAction) throws IOException {
        activeConnectionsMap.remove(ctx.getConnection());
        return super.handleClose(ctx, nextAction);
    }

    /**
     * Add connection to the {@link Map>
     *
     * @param connection new {@link Connection}
     */
    private void newConnection(Connection connection) {
        final Integer id = totalConnectionNumber.incrementAndGet();
        connectionIdAttribute.set(connection, id);
        activeConnectionsMap.put(connection, id);
    }

    /**
     * Returns the total number of connections ever
     * created by the {@link Transport}
     *
     * @return the total number of connections ever
     * created by the {@link Transport}
     */
    public int getTotalConnections() {
        return totalConnectionNumber.get();
    }

    /**
     * Returns the {@link Set} of currently active {@link Connection}s.
     *
     * @return the {@link Set} of currently active {@link Connection}s
     */
    public Set<Connection> getActiveConnections() {
        return activeConnectionsMap.keySet();
    }
}