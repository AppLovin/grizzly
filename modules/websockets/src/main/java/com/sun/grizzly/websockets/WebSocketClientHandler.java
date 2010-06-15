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

package com.sun.grizzly.websockets;

import com.sun.grizzly.Connection;
import java.io.IOException;

/**
 * Abstract client-side {@link WebSocketHandler}, which will handle
 * client {@link WebSocket}s events.
 *
 * @see WebSocketHandler
 * @see WebSocketApplication
 *
 * @author Alexey Stashok
 */
public abstract class WebSocketClientHandler implements WebSocketHandler {
    public abstract void onConnect(WebSocket socket) throws IOException;

    /**
     * Method is called, when inital {@link WebSocket} handshake process was completed,
     * but <tt>WebSocketClientHandler</tt> may perform additional validation.
     *
     * @param socket {@link WebSocket}
     * @param serverMeta {@link ServerWebSocketMeta}.
     * 
     * @throws HandshakeException error, occurred during the handshake.
     */
    protected void handshake(WebSocket socket, ServerWebSocketMeta serverMeta)
            throws HandshakeException {
    }

    /**
     * Method is called before the {@link WebSocketEngine} will create a client-side
     * {@link WebSocket} object, so the handler may return any customized
     * subtype of {@link WebSocket}.
     *
     * @param connection underlying Grizzly {@link Connection}.
     * @param meta client-side {@link WebSocketMeta}.
     *
     * @return customized {@link WebSocket}, or <tt>null</tt>, if handler wants
     * to delegate {@link WebSocket} creation to {@link WebSocketEngine}.
     */
    protected WebSocket createWebSocket(Connection connection,
            ClientWebSocketMeta meta) {
        return null;
    }
}
