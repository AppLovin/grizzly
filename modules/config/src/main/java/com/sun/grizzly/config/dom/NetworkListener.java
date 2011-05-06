/*
 *   DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 *   Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 *   The contents of this file are subject to the terms of either the GNU
 *   General Public License Version 2 only ("GPL") or the Common Development
 *   and Distribution License("CDDL") (collectively, the "License").  You
 *   may not use this file except in compliance with the License. You can obtain
 *   a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 *   or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 *   language governing permissions and limitations under the License.
 *
 *   When distributing the software, include this License Header Notice in each
 *   file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 *   Sun designates this particular file as subject to the "Classpath" exception
 *   as provided by Sun in the GPL Version 2 section of the License file that
 *   accompanied this code.  If applicable, add the following below the License
 *   Header, with the fields enclosed by brackets [] replaced by your own
 *   identifying information: "Portions Copyrighted [year]
 *   [name of copyright owner]"
 *
 *   Contributor(s):
 *
 *   If you wish your version of this file to be governed by only the CDDL or
 *   only the GPL Version 2, indicate your decision by adding "[Contributor]
 *   elects to include this software in this distribution under the [CDDL or GPL
 *   Version 2] license."  If you don't indicate a single choice of license, a
 *   recipient has the option to distribute your version of this file under
 *   either the CDDL, the GPL Version 2 or to extend the choice of license to
 *   its licensees as provided above.  However, if you add GPL Version 2 code
 *   and therefore, elected the GPL Version 2 license, then the option applies
 *   only if the new code is made subject to such option by the copyright
 *   holder.
 *
 */
package com.sun.grizzly.config.dom;

import org.jvnet.hk2.component.Injectable;
import org.jvnet.hk2.config.Attribute;
import org.jvnet.hk2.config.ConfigBean;
import org.jvnet.hk2.config.ConfigBeanProxy;
import org.jvnet.hk2.config.Configured;
import org.jvnet.hk2.config.DuckTyped;

/**
 * Binds protocol to a specific endpoint to listen on
 */
@Configured
public interface NetworkListener extends ConfigBeanProxy, Injectable {
    /**
     * IP address to listen on
     */
    @Attribute(defaultValue = "0.0.0.0")
    String getAddress();

    void setAddress(String value);

    /**
     * If false, a configured listener, is disabled
     */
    @Attribute(defaultValue = "true")
    String getEnabled();

    void setEnabled(String enabled);

    /**
     * If true, a jk listener is enabled
     */
    @Attribute(defaultValue = "false")
    String getJkEnabled();

    void setJkEnabled(String enabled);

    /**
     * Network-listener name, which could be used as reference
     */
    @Attribute(required = true, key = true)
    String getName();

    void setName(String value);

    /**
     * Port to listen on
     */
    @Attribute(required = true)
    String getPort();

    void setPort(String value);

    @DuckTyped
    Protocol findProtocol();

    /**
     * Reference to a protocol
     */
    @Attribute(required = true)
    String getProtocol();

    void setProtocol(String value);

    @DuckTyped
    ThreadPool findThreadPool();

    /**
     * Reference to a thread-pool, defined earlier in the document.
     */
    @Attribute
    String getThreadPool();

    void setThreadPool(String value);

    @DuckTyped
    Transport findTransport();

    /**
     * Reference to a low-level transport
     */
    @Attribute(required = true)
    String getTransport();

    void setTransport(String value);

    class Duck {
        public static Protocol findProtocol(NetworkListener listener) {
            String name = listener.getProtocol();
            final NetworkConfig networkConfig = listener.getParent().getParent(NetworkConfig.class);
            for (final Protocol protocol : networkConfig.getProtocols().getProtocol()) {
                if (protocol.getName().equals(name)) {
                    return protocol;
                }
            }
            return null;
        }

        public static ThreadPool findThreadPool(NetworkListener listener) {
            final String name = listener.getThreadPool();
            for (final ThreadPool threadPool : ConfigBean.unwrap(listener).getHabitat().getAllByType(ThreadPool.class)) {
                if (threadPool.getName().equals(name)) {
                    return threadPool;
                }
            }
            return null;
        }

        public static Transport findTransport(NetworkListener listener) {
            final String name = listener.getTransport();
            final NetworkConfig networkConfig = listener.getParent().getParent(NetworkConfig.class);
            for (final Transport transport : networkConfig.getTransports().getTransport()) {
                if (transport.getName().equals(name)) {
                    return transport;
                }
            }
            return null;
        }
    }
}
