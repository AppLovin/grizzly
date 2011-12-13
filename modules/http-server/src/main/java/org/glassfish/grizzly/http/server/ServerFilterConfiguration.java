/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2011 Oracle and/or its affiliates. All rights reserved.
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
package org.glassfish.grizzly.http.server;

/**
 * {@link HttpServerFilter} configuration.
 *
 * @author Alexey Stashok
 */
public class ServerFilterConfiguration {

    public static final String USE_SEND_FILE = "org.glassfish.grizzly.http.USE_SEND_FILE";

    private String httpServerName;
    private String httpServerVersion;
    private boolean sendFileEnabled;

    public ServerFilterConfiguration() {
        this("Grizzly", "2.1");
    }

    public ServerFilterConfiguration(final String serverName, final String serverVersion) {
        this.httpServerName = serverName;
        this.httpServerVersion = serverVersion;
        configureSendFileSupport();
    }

    /**
     * @return the server name used for headers and default error pages.
     */
    public String getHttpServerName() {
        return httpServerName;

    }

    /**
     * Sets the server name used for HTTP response headers and default generated error pages.  If not value is
     * explicitly set, this value defaults to <code>Grizzly</code>.
     *
     * @param httpServerName server name
     */
    public void setHttpServerName(String httpServerName) {
        this.httpServerName = httpServerName;
    }

    /**
     * @return the version of this server used for headers and default error pages.
     */
    public String getHttpServerVersion() {
        return httpServerVersion;
    }

    /**
     * Sets the version of the server info sent in HTTP response headers and the default generated error pages.  If not
     * value is explicitly set, this value defaults to the current version of the Grizzly runtime.
     *
     * @param httpServerVersion server version
     */
    public void setHttpServerVersion(String httpServerVersion) {
        this.httpServerVersion = httpServerVersion;
    }

    /**
     * <p>
     * Returns <code>true</code> if File resources may be be sent using
     * {@link java.nio.channels.FileChannel#transferTo(long, long, java.nio.channels.WritableByteChannel)}.
     * </p>
     * <p/>
     * <p>
     * By default, this property will be true, except in the following cases:
     * </p>
     * <p/>
     * <ul>
     * <li>JVM OS is HP-UX</li>
     * <li>JVM OS is Linux, and the Oracle JVM in use is 1.6.0_17 or older</li>
     * </ul>
     * <p/>
     * <p>
     * This logic can be overridden by explicitly setting the property via
     * {@link #setSendFileEnabled(boolean)} or by specifying the system property
     * {@value #USE_SEND_FILE} with a value of <code>true</code>
     * </p>
     * <p/>
     * <p>
     * Finally, if the connection between endpoints is secure, send file functionality
     * will be disabled regardless of configuration.
     * </p>
     *
     * @return <code>true</code> if resources will be sent using
     *         {@link java.nio.channels.FileChannel#transferTo(long, long, java.nio.channels.WritableByteChannel)}.
     * @since 2.2
     */
    public boolean isSendFileEnabled() {
        return sendFileEnabled;
    }

    /**
     * Configure whether or sendfile support will enabled which allows sending
     * {@link java.io.File} resources via {@link java.nio.channels.FileChannel#transferTo(long, long, java.nio.channels.WritableByteChannel)}.
     * If disabled, the more traditional byte[] copy will be used to send content.
     *
     * @param sendFileEnabled <code>true</code> to enable {@link java.nio.channels.FileChannel#transferTo(long, long, java.nio.channels.WritableByteChannel)}
     *                        support.
     * @since 2.2
     */
    public void setSendFileEnabled(boolean sendFileEnabled) {
        this.sendFileEnabled = sendFileEnabled;
    }

    // --------------------------------------------------------- Private Methods


    private void configureSendFileSupport() {

        if ((System.getProperty("os.name").equalsIgnoreCase("linux")
                && !linuxSendFileSupported())
                || System.getProperty("os.name").equalsIgnoreCase("HP-UX")) {
            sendFileEnabled = false;
        }

        // overrides the config from the previous block
        if (System.getProperty(USE_SEND_FILE) != null) {
            sendFileEnabled = Boolean.valueOf(System.getProperty(USE_SEND_FILE));
        }

    }


    private static boolean linuxSendFileSupported() {
        final String version = System.getProperty("java.version");
        if (version.startsWith("1.6")) {
            int idx = version.indexOf('_');
            if (idx == -1) {
                return false;
            }
            final int patchRev = Integer.parseInt(version.substring(idx + 1));
            return (patchRev >= 18);
        } else {
            return version.startsWith("1.7") || version.startsWith("1.8");
        }
    }

}
