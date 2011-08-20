/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.grizzly.http.ajp;

import com.sun.grizzly.http.SelectorThread;
import com.sun.grizzly.tcp.Adapter;
import com.sun.grizzly.tcp.StaticResourcesAdapter;
import com.sun.grizzly.util.Utils;
import com.sun.grizzly.util.buf.ByteChunk;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;

/**
 * Test simple Ajp communication usecases.
 *
 * @author Alexey Stashok
 * @author Justin Lee
 */
public class BasicAjpTest {
    private static final int PORT = 8009;

    private SelectorThread selectorThread;

    @Before
    public void before() throws Exception {
        configureHttpServer();
    }

    @After
    public void after() throws Exception {
        if (selectorThread != null) {
            selectorThread.stopEndpoint();
        }
    }

    @Test
    public void testRequest() throws IOException {
        ByteChunk chunk = new ByteChunk();
        ByteBuffer request = read("/request.txt");
        final ByteBuffer response = send("localhost", PORT, request);
        List<AjpResponse> responses = AjpMessageUtils.parseResponse(response);

        final Iterator<AjpResponse> iterator = responses.iterator();
        AjpResponse next = iterator.next();
        Assert.assertEquals(200, next.getResponseCode());
        Assert.assertEquals("OK", next.getResponseMessage());

        next = iterator.next();
        Assert.assertArrayEquals(readFile("src/test/resources/ajpindex.html"), next.getBody());

        Assert.assertEquals(AjpConstants.JK_AJP13_END_RESPONSE, iterator.next().getType());
    }

    private byte[] readFile(String name) throws IOException {
        final FileInputStream stream = new FileInputStream(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] read = new byte[4096];
        int count = 0;
        while((count = stream.read(read)) != -1) {
            out.write(read, 0, count);
        }
        stream.close();
        return out.toByteArray();
    }

    private ByteBuffer read(String file) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream(file)));
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try {
            while (reader.ready()) {
                String[] line = reader.readLine().split(" ");
                int index = 0;
                while (index < 19 && index < line.length) {
                    if (!"".equals(line[index])) {
                        stream.write(Integer.parseInt(line[index], 16));
                    }
                    index++;
                }
            }
        } finally {
            reader.close();
        }

        return ByteBuffer.wrap(stream.toByteArray());
    }

    public void testPingPong() throws Exception {
        final ByteBuffer request = ByteBuffer.allocate(12);
        request.put((byte) 0x12);
        request.put((byte) 0x34);
        request.putShort((short) 1);
        request.put(AjpConstants.JK_AJP13_CPING_REQUEST);
        request.flip();

        final ByteBuffer response = send("localhost", PORT, request);
        Assert.assertEquals((byte) 'A', response.get());
        Assert.assertEquals((byte) 'B', response.get());
        Assert.assertEquals((short) 1, response.getShort());
        Assert.assertEquals(AjpConstants.JK_AJP13_CPONG_REPLY, response.get());
    }

    @SuppressWarnings({"unchecked"})
    private ByteBuffer send(String host, int port, ByteBuffer request) throws IOException {
        ByteChunk response = new ByteChunk();
        Socket socket = new Socket(host, port);
        try {
            byte[] data = new byte[request.limit() - request.position()];
            request.get(data);
            socket.getOutputStream().write(data);
            final InputStream stream = socket.getInputStream();
            byte[] bytes = new byte[8192];
            int read;
            while ((read = stream.read(bytes)) != -1) {
                response.append(bytes, 0, read);
            }
        } finally {
            socket.close();
        }

        return response.getLength() == 0 ? ByteBuffer.allocate(0) : response.toByteBuffer();
    }

    private void configureHttpServer() throws Exception {
        final Adapter adapter = new StaticResourcesAdapter("src/test/resources");
        selectorThread = new AjpSelectorThread();
        selectorThread.setSsBackLog(8192);
        selectorThread.setCoreThreads(2);
        selectorThread.setMaxThreads(2);
        selectorThread.setPort(PORT);
        selectorThread.setDisplayConfiguration(Utils.VERBOSE_TESTS);
        selectorThread.setAdapter(adapter);
        selectorThread.setTcpNoDelay(true);

        selectorThread.listen();
    }

    public static void main(String[] args) throws Exception {
        BasicAjpTest test = new BasicAjpTest();
        test.configureHttpServer();
    }
}
