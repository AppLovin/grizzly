/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.comet;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.utils.Utils;

public class DefaultCometHandler implements CometHandler<String>, Comparable<CometHandler> {
    private Response response;
    volatile AtomicBoolean onInitializeCalled = new AtomicBoolean(false);
    volatile AtomicBoolean onInterruptCalled = new AtomicBoolean(false);
    volatile AtomicBoolean onEventCalled = new AtomicBoolean(false);
    volatile AtomicBoolean onTerminateCalled = new AtomicBoolean(false);
    private CometContext<String> cometContext;

    public DefaultCometHandler(CometContext<String> cometContext, Response response) {
        this.cometContext = cometContext;
        this.response = response;
    }

    @Override
    public Response getResponse() {
        return response;
    }

    public void setResponse(final Response response) {
        this.response = response;
    }

    @Override
    public CometContext<String> getCometContext() {
        return cometContext;
    }

    public void setCometContext(final CometContext<String> cometContext) {
        this.cometContext = cometContext;
    }

    public void attach(String attachment) {
    }

    public void onEvent(CometEvent event) throws IOException {
        Utils.dumpOut("     -> onEvent Handler:" + hashCode());
        onEventCalled.set(true);
    }

    public void onInitialize(CometEvent event) throws IOException {
        Utils.dumpOut("     -> onInitialize Handler:" + hashCode());
        response.addHeader(BasicCometTest.onInitialize,
            event.attachment() == null ? BasicCometTest.onInitialize : event.attachment().toString());
        onInitializeCalled.set(true);
    }

    public void onTerminate(CometEvent event) throws IOException {
        Utils.dumpOut("    -> onTerminate Handler:" + hashCode());
        onTerminateCalled.set(true);
        response.getWriter().write(BasicCometTest.onTerminate);
    }

    public void onInterrupt(CometEvent event) throws IOException {
        Utils.dumpOut("    -> onInterrupt Handler:" + hashCode());
        onInterruptCalled.set(true);
        response.getWriter().write(BasicCometTest.onInterrupt);
    }

    @Override
    public int compareTo(CometHandler o) {
        return hashCode() - o.hashCode();
    }
}
