/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2010 Sun Microsystems, Inc. All rights reserved.
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
 *
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.sun.grizzly.http.server.adapter;

import com.sun.grizzly.http.server.GrizzlyRequest;
import com.sun.grizzly.http.server.GrizzlyResponse;
import com.sun.grizzly.tcp.Request;
import com.sun.grizzly.tcp.Response;


/**
 * Adapter. This represents the extension point to any customized
 * tcp, like Servlet, static resource, etc.
 *
 * @author Remy Maucherat
 * @author Jeanfrancois Arcand
 */
public interface Adapter {


    /**
     * Invoke the service method. The service method is usually responsible for
     * manipulating the {@link com.sun.grizzly.tcp.Request} and preparing the
     * {@link com.sun.grizzly.tcp.Response}.
     *
     * @param req The {@link com.sun.grizzly.tcp.Request}
     * @param res The {@link com.sun.grizzly.tcp.Response}
     * @exception Exception if an error happens during handling of
     *   the request. Common errors are:
     *   <ul><li>IOException if an input/output error occurs and we are
     *   processing an included servlet (otherwise it is swallowed and
     *   handled by the top level error handler mechanism)
     *       <li>ServletException if a servlet throws an exception and
     *  we are processing an included servlet (otherwise it is swallowed
     *  and handled by the top level error handler mechanism)
     *       </li></ul>
     */
    void service(GrizzlyRequest req, GrizzlyResponse res)
    throws Exception;


    /**
     * Finish the response and dedide if the {@link Request}/{@link Response}
     * object must be recycled.
     *
     * @param req The {@link Request}
     * @param res The {@link Response}
     */
    void afterService(GrizzlyRequest req, GrizzlyResponse res)
    throws Exception;

    /**
     * Called when the {@link com.sun.grizzly.http.server.apapter.GrizzlyAdapter}'s container is started by invoking
     * {@link GrizzlyWebServer#start} or when {@linl SelectorThread.start}. By default,
     * it does nothing.
     */
    public void start();


    /**
     * Invoked when the {@link GrizzlyWebServer} or {@link SelectorThread}
     * is stopped or removed. By default, this method does nothing. Just override
     * the method if you need to clean some resource.
     */
    public void destroy();
}
