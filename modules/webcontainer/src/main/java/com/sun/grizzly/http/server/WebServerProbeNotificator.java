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

package com.sun.grizzly.http.server;

import com.sun.grizzly.Connection;

/**
 * Utility class, which has notificator methods for different
 * {@link WebServerMonitoringProbe} events.
 *
 * @author Alexey Stashok
 */
final class WebServerProbeNotificator {
    /**
     * Notify registered {@link HttpMonitoringProbe}s about the "request received" event.
     *
     * @param filter {@link WebServerFilter}, the event belongs to.
     * @param connection {@link Connection}, the event belongs to.
     * @param request received {@link GrizzlyRequest}.
     */
    static void notifyRequestReceived(final WebServerFilter filter,
            final Connection connection, final GrizzlyRequest request) {

        final WebServerMonitoringProbe[] probes = filter.monitoringProbes.getArray();
        if (probes != null) {
            for (WebServerMonitoringProbe probe : probes) {
                probe.onRequestReceiveEvent(filter, connection, request);
            }
        }
    }

    /**
     * Notify registered {@link HttpMonitoringProbe}s about the "request completed" event.
     *
     * @param filter {@link WebServerFilter}, the event belongs to.
     * @param connection {@link Connection}, the event belongs to.
     * @param response {@link GrizzlyResponse}.
     */
    static void notifyRequestCompleted(final WebServerFilter filter,
            final Connection connection, final GrizzlyResponse response) {

        final WebServerMonitoringProbe[] probes = filter.monitoringProbes.getArray();
        if (probes != null) {
            for (WebServerMonitoringProbe probe : probes) {
                probe.onRequestCompleteEvent(filter, connection, response);
            }
        }
    }

    /**
     * Notify registered {@link HttpMonitoringProbe}s about the "request suspended" event.
     *
     * @param filter {@link WebServerFilter}, the event belongs to.
     * @param connection {@link Connection}, the event belongs to.
     * @param request {@link GrizzlyRequest}.
     */
    static void notifyRequestSuspended(final WebServerFilter filter,
            final Connection connection, final GrizzlyRequest request) {

        final WebServerMonitoringProbe[] probes = filter.monitoringProbes.getArray();
        if (probes != null) {
            for (WebServerMonitoringProbe probe : probes) {
                probe.onRequestSuspendEvent(filter, connection, request);
            }
        }
    }

    /**
     * Notify registered {@link HttpMonitoringProbe}s about the "request resumed" event.
     *
     * @param filter {@link WebServerFilter}, the event belongs to.
     * @param connection {@link Connection}, the event belongs to.
     * @param request {@link GrizzlyRequest}.
     */
    static void notifyRequestResumed(final WebServerFilter filter,
            final Connection connection, final GrizzlyRequest request) {

        final WebServerMonitoringProbe[] probes = filter.monitoringProbes.getArray();
        if (probes != null) {
            for (WebServerMonitoringProbe probe : probes) {
                probe.onRequestResumeEvent(filter, connection, request);
            }
        }
    }

    /**
     * Notify registered {@link HttpMonitoringProbe}s about the "request timeout after suspend" event.
     *
     * @param filter {@link WebServerFilter}, the event belongs to.
     * @param connection {@link Connection}, the event belongs to.
     * @param request  {@link GrizzlyRequest}.
     */
    static void notifyRequestTimeout(final WebServerFilter filter,
            final Connection connection, final GrizzlyRequest request) {

        final WebServerMonitoringProbe[] probes = filter.monitoringProbes.getArray();
        if (probes != null) {
            for (WebServerMonitoringProbe probe : probes) {
                probe.onRequestTimeoutEvent(filter, connection, request);
            }
        }
    }

    /**
     * Notify registered {@link HttpMonitoringProbe}s about the "request cancelled after suspend" event.
     *
     * @param filter {@link WebServerFilter}, the event belongs to.
     * @param connection {@link Connection}, the event belongs to.
     * @param request  {@link GrizzlyRequest}.
     */
    static void notifyRequestCancelled(final WebServerFilter filter,
            final Connection connection, final GrizzlyRequest request) {

        final WebServerMonitoringProbe[] probes = filter.monitoringProbes.getArray();
        if (probes != null) {
            for (WebServerMonitoringProbe probe : probes) {
                probe.onRequestCancelEvent(filter, connection, request);
            }
        }
    }
}
