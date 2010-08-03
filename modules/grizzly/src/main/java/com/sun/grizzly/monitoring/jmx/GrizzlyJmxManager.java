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

package com.sun.grizzly.monitoring.jmx;

import com.sun.grizzly.utils.ServiceFinder;
import java.util.Iterator;
import org.glassfish.gmbal.GmbalMBean;
import org.glassfish.gmbal.ManagedObjectManager;
import org.glassfish.gmbal.ManagedObjectManagerFactory;

/**
 * Grizzly JMX manager
 * 
 * @author Alexey Stashok
 */
public abstract class GrizzlyJmxManager {
    private static final GrizzlyJmxManager manager;
    
    protected final ManagedObjectManager mom;

    static {
        ServiceFinder<GrizzlyJmxManager> serviceFinder =
                ServiceFinder.find(GrizzlyJmxManager.class);
        final Iterator<GrizzlyJmxManager> it = serviceFinder.iterator();

        if(it.hasNext()) {
            manager = it.next();
        } else {
            manager = new DefaultJmxManager();
        }
    }

    /**
     * Return the <tt>GrizzlyJmxManager</tt> instance.
     *
     * @return the <tt>GrizzlyJmxManager</tt> instance.
     */
    public static GrizzlyJmxManager instance() {
        return manager;
    }

    protected GrizzlyJmxManager(ManagedObjectManager mom) {
        this.mom = mom;
    }

    /**
     * Register Grizzly {@link JmxObject} at the root with the passed name.
     *
     * @param object {@link JmxObject} to register.
     * @param name
     * @return JMX {@link GmbalMBean} object.
     */
    public GmbalMBean registerAtRoot(JmxObject object, String name) {
        final GmbalMBean bean = mom.registerAtRoot(object, name);
        object.onRegister(this, bean);
        
        return bean;
    }

    /**
     * Register Grizzly {@link JmxObject} as child of the passed parent object
     * with the specific name.
     *
     * @param parent parent
     * @param object {@link JmxObject} to register.
     * @param name
     * @return JMX {@link GmbalMBean} object.
     */
    public GmbalMBean register(Object parent, JmxObject object, String name) {
        final GmbalMBean bean = mom.register(parent, object, name);
        object.onRegister(this, bean);

        return bean;
    }

    /**
     * Unregister Grizzly {@link JmxObject}.
     *
     * @param object {@link JmxObject} to unregister.
     */
    public void unregister(JmxObject object) {
        mom.unregister(object);
        object.onUnregister(this);
    }

    private static class DefaultJmxManager extends GrizzlyJmxManager {
        public DefaultJmxManager() {
            super(ManagedObjectManagerFactory.createStandalone("com.sun.grizzly"));
            mom.stripPackagePrefix();
            mom.createRoot();
        }
    }
}
