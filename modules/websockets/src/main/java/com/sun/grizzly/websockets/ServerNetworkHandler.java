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

package com.sun.grizzly.websockets;

import com.sun.grizzly.arp.AsyncProcessorTask;
import com.sun.grizzly.http.ProcessorTask;
import com.sun.grizzly.http.servlet.HttpServletRequestImpl;
import com.sun.grizzly.http.servlet.HttpServletResponseImpl;
import com.sun.grizzly.http.servlet.ServletContextImpl;
import com.sun.grizzly.tcp.Request;
import com.sun.grizzly.tcp.Response;
import com.sun.grizzly.tcp.http11.GrizzlyRequest;
import com.sun.grizzly.tcp.http11.GrizzlyResponse;
import com.sun.grizzly.tcp.http11.InternalInputBuffer;
import com.sun.grizzly.tcp.http11.InternalOutputBuffer;
import com.sun.grizzly.util.SelectedKeyAttachmentLogic;
import com.sun.grizzly.util.buf.ByteChunk;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class ServerNetworkHandler implements NetworkHandler {
    private final Request request;
    private final Response response;
    private final InternalInputBuffer inputBuffer;
    private final InternalOutputBuffer outputBuffer;
    private final ByteChunk chunk = new ByteChunk();
    private WebSocket socket;
    private WebSocketSelectionKeyAttachment attachment;
    private byte[] mask;
    private int maskIndex = 0;

    public ServerNetworkHandler(Request req, Response resp) {
        request = req;
        response = resp;
        inputBuffer = (InternalInputBuffer) req.getInputBuffer();
        outputBuffer = (InternalOutputBuffer) resp.getOutputBuffer();
    }

    public ServerNetworkHandler(final ProcessorTask task, final AsyncProcessorTask async, Request req, Response resp) {
        this(req, resp);
        attachment = new WebSocketSelectionKeyAttachment(this, task, async);
    }

    public WebSocket getWebSocket() {
        return socket;
    }

    public void setWebSocket(BaseWebSocket webSocket) {
        socket = webSocket;
    }

    protected void handshake(final boolean sslSupport) throws IOException, HandshakeException {
        final boolean secure = "https".equalsIgnoreCase(request.scheme().toString()) || sslSupport;

        final ServerHandShake server = new ServerHandShake(request, secure, chunk);
        server.respond(response);
        socket.onConnect();
        if (chunk.getLength() > 0) {
            readFrame();
        }
    }

    protected void readFrame() throws IOException {
        fill();
        while (socket.isConnected() && chunk.getLength() != 0) {
            final DataFrame dataFrame = new DataFrame();
            try {
                setMask(getUnmasked(WebSocketEngine.MASK_SIZE));
                dataFrame.unframe(this);
                dataFrame.respond(getWebSocket());
            } catch(FramingException fe) {
                socket.close();
            }
        }
    }

    private void setMask(byte[] mask) {
        maskIndex = 0;
        this.mask = mask;
    }

    protected void read() throws IOException {
        ByteChunk bytes = new ByteChunk(WebSocketEngine.INITIAL_BUFFER_SIZE);
        int count = WebSocketEngine.INITIAL_BUFFER_SIZE;
        while (count == WebSocketEngine.INITIAL_BUFFER_SIZE) {
            count = inputBuffer.doRead(bytes, request);
            chunk.append(bytes);
        }
    }

    public byte get() throws IOException {
        synchronized (chunk) {
            fill();
            return (byte) (chunk.substract() ^ mask[maskIndex++ % WebSocketEngine.MASK_SIZE]);
        }
    }

    public byte[] get(int count) throws IOException {
        synchronized (chunk) {
            byte[] bytes = new byte[count];
            int total = 0;
            while(total < count) {
                if(chunk.getLength() < count) {
                    read();
                }
                total += chunk.substract(bytes, total, count - total);
            }
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (bytes[i] ^ mask[maskIndex++ % WebSocketEngine.MASK_SIZE]);
            }
            return bytes;
        }
    }
    
    private byte[] getUnmasked(int count) throws IOException {
        synchronized (chunk) {
            byte[] bytes = new byte[count];
            fill();
            int total = 0;
            while(total < count) {
                if(chunk.getLength() < count) {
                    read();
                }
                total += chunk.substract(bytes, total, count - total);
            }
            return bytes;
        }
    }

    private void fill() throws IOException {
        if (chunk.getLength() == 0) {
            read();
        }
    }

    private void write(byte[] bytes) throws IOException {
        synchronized (outputBuffer) {
            ByteChunk buffer = new ByteChunk();
            buffer.setBytes(bytes, 0, bytes.length);
            outputBuffer.doWrite(buffer, response);
            outputBuffer.flush();
        }
    }

    public void send(DataFrame frame) throws IOException {
        write(frame.frame());
    }

    public void close(int code, String reason) throws IOException {
        send(new ClosingFrame(code, reason));
    }

    public void setWebSocket(WebSocket webSocket) {
        socket = webSocket;
    }

    public HttpServletRequest getRequest() throws IOException {
        GrizzlyRequest r = new GrizzlyRequest();
        r.setRequest(request);
        return new WSServletRequestImpl(r);
    }

    public HttpServletResponse getResponse() throws IOException {
        GrizzlyResponse r = new GrizzlyResponse();
        r.setResponse(response);
        return new HttpServletResponseImpl(r);
    }

    public SelectedKeyAttachmentLogic getAttachment() {
        return attachment;
    }

    private static class WSServletRequestImpl extends HttpServletRequestImpl {
        public WSServletRequestImpl(GrizzlyRequest r) throws IOException {
            super(r);
            setContextImpl(new ServletContextImpl());
        }
    }

}
