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
package org.glassfish.grizzly.websockets;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.websockets.draft06.ClosingFrame;
import org.glassfish.grizzly.websockets.frametypes.PongFrameType;

@SuppressWarnings({"StringContatenationInLoop"})
public class DefaultWebSocket implements WebSocket {
    private static final Logger logger = Grizzly.logger(DefaultWebSocket.class);
    private final Collection<WebSocketListener> listeners = new ConcurrentLinkedQueue<WebSocketListener>();
    protected final ProtocolHandler protocolHandler;

    enum State {
        NEW, CONNECTED, CLOSING, CLOSED
    }

    EnumSet<State> connected = EnumSet.<State>range(State.CONNECTED, State.CLOSING);
    private final AtomicReference<State> state = new AtomicReference<State>(State.NEW);

    public DefaultWebSocket(ProtocolHandler protocolHandler, WebSocketListener... listeners) {
        this.protocolHandler = protocolHandler;
        for (WebSocketListener listener : listeners) {
            add(listener);
        }
        protocolHandler.setWebSocket(this);
    }

    public Collection<WebSocketListener> getListeners() {
        return listeners;
    }

    public final boolean add(WebSocketListener listener) {
        return listeners.add(listener);
    }

    public final boolean remove(WebSocketListener listener) {
        return listeners.remove(listener);
    }

    public boolean isConnected() {
        return connected.contains(state.get());
    }

    public void setClosed() {
        state.set(State.CLOSED);
    }

    public void onClose(DataFrame frame) {
        synchronized (listeners) {
            final Iterator<WebSocketListener> it = listeners.iterator();
            while (it.hasNext()) {
                final WebSocketListener listener = it.next();
                it.remove();
                listener.onClose(this, frame);
            }
        }
        if (state.compareAndSet(State.CONNECTED, State.CLOSING)) {
            final ClosingFrame closing = (ClosingFrame) frame;
            protocolHandler.close(closing.getCode(), closing.getTextPayload());
        } else {
            state.set(State.CLOSED);
            protocolHandler.doClose();
        }
    }

    public void onConnect() {
        state.set(State.CONNECTED);
        
        for (WebSocketListener listener : listeners) {
            listener.onConnect(this);
        }
    }

    @Override
    public void onFragment(boolean last, byte[] fragment) {
        for (WebSocketListener listener : listeners) {
            listener.onFragment(this, fragment, last);
        }
    }

    @Override
    public void onFragment(boolean last, String fragment) {
        for (WebSocketListener listener : listeners) {
            listener.onFragment(this, fragment, last);
        }
    }

    @Override
    public void onMessage(byte[] data) {
        for (WebSocketListener listener : listeners) {
            listener.onMessage(this, data);
        }
    }

    @Override
    public void onMessage(String text) {
        for (WebSocketListener listener : listeners) {
            listener.onMessage(this, text);
        }
    }

    public void onPing(DataFrame frame) {
        send(new DataFrame(new PongFrameType(), frame.getBytes()));
        for (WebSocketListener listener : listeners) {
            listener.onPing(this, frame.getBytes());
        }
    }

    @Override
    public void onPong(DataFrame frame) {
        for (WebSocketListener listener : listeners) {
            listener.onPong(this, frame.getBytes());
        }
    }

    public void close() {
        close(NORMAL_CLOSURE, null);
    }

    public void close(int code) {
        close(code, null);
    }

    public void close(int code, String reason) {
        if (state.compareAndSet(State.CONNECTED, State.CLOSING)) {
            protocolHandler.close(code, reason);
        }
    }

    public GrizzlyFuture<DataFrame> send(byte[] data) {
        if (isConnected()) {
            return protocolHandler.send(data);
        } else {
            throw new RuntimeException("Socket is not connected.");
        }
    }

    public GrizzlyFuture<DataFrame> send(String data) {
        if (isConnected()) {
            return protocolHandler.send(data);
        } else {
            throw new RuntimeException("Socket is not connected.");
        }
    }

    private GrizzlyFuture<DataFrame> send(DataFrame frame) {
        if (isConnected()) {
            return protocolHandler.send(frame);
        } else {
            throw new RuntimeException("Socket is not connected.");
        }
    }

    public GrizzlyFuture<DataFrame> stream(boolean last, String fragment) {
        if (isConnected()) {
            return protocolHandler.stream(last, fragment);
        } else {
            throw new RuntimeException("Socket is not connected.");
        }
    }

    public GrizzlyFuture<DataFrame> stream(boolean last, byte[] bytes, int off, int len) {
        if (isConnected()) {
            return protocolHandler.stream(last, bytes, off, len);
        } else {
            throw new RuntimeException("Socket is not connected.");
        }
    }

}
