/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.grizzly.http.server.jmx;

import com.sun.grizzly.http.server.NetworlListener;
import com.sun.grizzly.monitoring.jmx.GrizzlyJmxManager;
import com.sun.grizzly.monitoring.jmx.JmxObject;
import org.glassfish.gmbal.Description;
import org.glassfish.gmbal.GmbalMBean;
import org.glassfish.gmbal.ManagedAttribute;
import org.glassfish.gmbal.ManagedObject;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JMX management object for {@link com.sun.grizzly.http.server.GrizzlyWebServer}.
 *
 * @since 2.0
 */
@ManagedObject
@Description("The GrizzlyWebServer.")
public class GrizzlyWebServer extends JmxObject {


    private final com.sun.grizzly.http.server.GrizzlyWebServer gws;

    private GrizzlyJmxManager mom;
    private final ConcurrentHashMap<String, NetworlListener> currentListeners =
            new ConcurrentHashMap<String, NetworlListener>();
    private final ConcurrentHashMap<String,JmxObject> listenersJmx =
            new ConcurrentHashMap<String,JmxObject>();
    


    // ------------------------------------------------------------ Constructors


    public GrizzlyWebServer(com.sun.grizzly.http.server.GrizzlyWebServer gws) {
        this.gws = gws;
    }


    // -------------------------------------------------- Methods from JmxObject


    /**
     * {@inheritDoc}
     */
    @Override
    public String getJmxName() {
        return "GrizzlyWebServer";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected synchronized void onRegister(GrizzlyJmxManager mom, GmbalMBean bean) {
        this.mom = mom;
        rebuildSubTree();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected synchronized void onUnregister(GrizzlyJmxManager mom) {
        this.mom = null;
    }


    // ---------------------------------------------------------- Public Methods


    /**
     * @see com.sun.grizzly.http.server.GrizzlyWebServer#isStarted()
     */
    @ManagedAttribute(id="started")
    @Description("Indicates whether or not this server instance has been started.")
    public boolean isStarted() {
        return gws.isStarted();
    }


    @ManagedAttribute(id="document-root")
    @Description("The document root of this server instance.")
    public String getDocumentRoot() {
        return gws.getServerConfiguration().getDocRoot();
    }


    // ------------------------------------------------------- Protected Methods


    protected void rebuildSubTree() {

        for (Iterator<NetworlListener> i = gws.getListeners(); i.hasNext(); ) {
            final NetworlListener l = i.next();
            final NetworlListener currentListener = currentListeners.get(l.getName());
            if (currentListener != l) {
                if (currentListener != null) {
                    final JmxObject listenerJmx = listenersJmx.get(l.getName());
                    mom.unregister(listenerJmx);

                    currentListeners.remove(l.getName());
                    listenersJmx.remove(l.getName());
                }

                final JmxObject mmJmx = l.createManagementObject();
                mom.register(this, mmJmx, "NetworlListener[" + l.getName() + ']');
                currentListeners.put(l.getName(), l);
                listenersJmx.put(l.getName(), mmJmx);
            }
        }
        
    }
}
