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

import com.sun.grizzly.http.servlet.HttpServletRequestImpl;
import com.sun.grizzly.http.servlet.HttpServletResponseImpl;
import com.sun.grizzly.http.servlet.ServletContextImpl;
import com.sun.grizzly.tcp.Request;
import com.sun.grizzly.tcp.Response;
import com.sun.grizzly.tcp.http11.GrizzlyRequest;
import com.sun.grizzly.tcp.http11.GrizzlyResponse;
import com.sun.grizzly.tcp.http11.InternalInputBuffer;
import com.sun.grizzly.tcp.http11.InternalOutputBuffer;
import com.sun.grizzly.util.buf.ByteChunk;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class ServerNetworkHandler extends BaseNetworkHandler {
    private final Request request;
    private final Response response;
    private final InternalInputBuffer inputBuffer;
    private final InternalOutputBuffer outputBuffer;

    public ServerNetworkHandler(Request req, Response resp) {
        request = req;
        response = resp;
        inputBuffer = (InternalInputBuffer) req.getInputBuffer();
        outputBuffer = (InternalOutputBuffer) resp.getOutputBuffer();
    }

    @Override
    protected int read() {
        int read = 0;
        ByteChunk newChunk = new ByteChunk(WebSocketEngine.INITIAL_BUFFER_SIZE);
        try {
            ByteChunk bytes = new ByteChunk(WebSocketEngine.INITIAL_BUFFER_SIZE);
            if (chunk.getLength() > 0) {
                newChunk.append(chunk);
            }
            int count = WebSocketEngine.INITIAL_BUFFER_SIZE;
            while (count == WebSocketEngine.INITIAL_BUFFER_SIZE) {
                count = inputBuffer.doRead(bytes, request);
                newChunk.append(bytes);
                read += count;
            }
        } catch (IOException e) {
            throw new WebSocketException(e.getMessage(), e);
        }

        if(read == -1) {
            throw new WebSocketException("Read -1 bytes.  Connection closed?");
        }
        chunk.setBytes(newChunk.getBytes(), 0, newChunk.getEnd());
        return read;
    }

    public byte get() {
        synchronized (chunk) {
            fill();
            try {
                return (byte) chunk.substract();
            } catch (IOException e) {
                throw new WebSocketException(e.getMessage(), e);
            }
        }
    }

    public byte[] get(int count) {
        synchronized (chunk) {
            try {
                byte[] bytes = new byte[count];
                int total = 0;
                while (total < count) {
                    if (chunk.getLength() < count) {
                        read();
                    }
                    total += chunk.substract(bytes, total, count - total);
                }
                return bytes;
            } catch (IOException e) {
                throw new WebSocketException(e.getMessage(), e);
            }
        }
    }

    private void fill() {
        synchronized (chunk) {
            if (chunk.getLength() == 0) {
                read();
            }
        }
    }

    public void write(byte[] bytes) {
        synchronized (outputBuffer) {
            try {
                ByteChunk buffer = new ByteChunk();
                buffer.setBytes(bytes, 0, bytes.length);
                outputBuffer.doWrite(buffer, response);
                outputBuffer.flush();
            } catch (IOException e) {
                throw new WebSocketException(e.getMessage(), e);
            }
        }
    }

    public boolean ready() {
        synchronized (chunk) {
            return chunk.getLength() != 0;
        }
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

    private static class WSServletRequestImpl extends HttpServletRequestImpl {
        public WSServletRequestImpl(GrizzlyRequest r) throws IOException {
            super(r);
            setContextImpl(new ServletContextImpl());
        }
    }

}
