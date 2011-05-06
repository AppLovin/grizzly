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
 */

package com.sun.grizzly.websockets;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class WebSocketApplication implements WebSocketListener {
    private final Set<WebSocket> sockets = new HashSet<WebSocket>();

    private final Set<WebSocketListener> listeners = new HashSet<WebSocketListener>();

    private final ReentrantReadWriteLock socketsLock = new ReentrantReadWriteLock();

//    private final ReentrantReadWriteLock listenersLock = new ReentrantReadWriteLock();

    /**
     * Returns a set of {@link WebSocket}s, registered with the application.
     * The returned set is unmodifiable, the possible modifications may cause exceptions.
     *
     * @return a set of {@link WebSocket}s, registered with the application.
     */
    protected Set<WebSocket> getWebSockets() {
        socketsLock.readLock().lock();
        try {
            return Collections.unmodifiableSet(sockets);
        } finally {
            socketsLock.readLock().unlock();
        }
    }

    protected boolean add(WebSocket socket) {
        socketsLock.writeLock().lock();
        try {
            return sockets.add(socket);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            socketsLock.writeLock().unlock();
        }
    }

    public boolean remove(WebSocket socket) {
        socketsLock.writeLock().lock();
        try {
            return sockets.remove(socket);
        } finally {
            socketsLock.writeLock().unlock();
        }
    }

    public WebSocket createSocket(NetworkHandler handler, WebSocketListener... listeners) throws IOException {
        return new BaseServerWebSocket(handler, listeners);
    }

    public void onClose(WebSocket socket) throws IOException {
        remove(socket);
        socket.close();
    }

    public void onConnect(WebSocket socket) {
        add(socket);
    }

    public void onMessage(WebSocket socket, DataFrame data) throws IOException {
    }
}
