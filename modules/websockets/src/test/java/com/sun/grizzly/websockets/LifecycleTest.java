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

import com.sun.grizzly.http.SelectorThread;
import com.sun.grizzly.tcp.Request;
import com.sun.grizzly.tcp.StaticResourcesAdapter;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Test
public class LifecycleTest {
    protected static final String BASE_URL = "ws://localhost:" + WebSocketsTest.PORT;
    private static final String ADDRESS = BASE_URL + "/echo";
    private CountDownLatch closeLatch;
    private CountDownLatch connectedLatch;

    public void detectClosed() throws Exception {
        final String name = "/detect";
        final WebSocketApplication serverApp = createServerApp(name);

        final SelectorThread thread =
                WebSocketsTest.createSelectorThread(WebSocketsTest.PORT, new StaticResourcesAdapter());

        try {
            Assert.assertEquals(serverApp.getWebSockets().size(), 0, "There should be no clients connected");

            BadWebSocketClient client = new BadWebSocketClient(BASE_URL + name);

            connectedLatch.await(WebSocketEngine.DEFAULT_TIMEOUT, TimeUnit.SECONDS);
            Assert.assertEquals(serverApp.getWebSockets().size(), 1, "There should be 1 client connected");

            client.close();
            Assert.assertTrue(client.waitForClosed());
            closeLatch.await(WebSocketEngine.DEFAULT_TIMEOUT, TimeUnit.SECONDS);

            Assert.assertEquals(serverApp.getWebSockets().size(), 0, "There should be 0 clients connected");
        } finally {
            thread.stopEndpoint();
            WebSocketEngine.getEngine().unregister(serverApp);
        }
    }

    @Test(enabled = false)
    public void dirtyClose() throws Exception {
        final String name = "/dirty";
        final SelectorThread thread =
                WebSocketsTest.createSelectorThread(WebSocketsTest.PORT, new StaticResourcesAdapter());

        final WebSocketApplication app = createServerApp(name);


        try {
            Assert.assertEquals(app.getWebSockets().size(), 0, "There should be no clients connected");

            BadWebSocketClient client = new BadWebSocketClient(BASE_URL + name);

            connectedLatch.await(WebSocketEngine.DEFAULT_TIMEOUT, TimeUnit.SECONDS);
            Assert.assertEquals(app.getWebSockets().size(), 1, "There should be 1 client connected");

            System.out.println(new Date() + ": killing connection");
            client.killConnection();
            Assert.assertTrue(closeLatch.await(60, TimeUnit.SECONDS),
                    "Should get the close event");
            Assert.assertEquals(app.getWebSockets().size(), 0, "There should be 0 clients connected");
        } finally {
            thread.stopEndpoint();
            WebSocketEngine.getEngine().unregister(app);
        }

    }

    private WebSocketApplication createServerApp(final String name) {
        closeLatch = new CountDownLatch(1);
        connectedLatch = new CountDownLatch(1);
        final WebSocketApplication serverApp = new WebSocketApplication() {
            @Override
            public void onConnect(WebSocket socket) {
                super.onConnect(socket);
                connectedLatch.countDown();
            }

            @Override
            public boolean isApplicationRequest(Request request) {
                return request.requestURI().equals(name);
            }

            @Override
            public void onClose(WebSocket socket) {
                super.onClose(socket);
                closeLatch.countDown();
            }
        };
        WebSocketEngine.getEngine().register(serverApp);
        return serverApp;
    }

    public void multipleClientClosing() throws Exception {
        final CountDownLatch close = new CountDownLatch(1);
        final EchoWebSocketApplication app = new EchoWebSocketApplication() {
            @Override
            public void onClose(WebSocket socket) {
                super.onClose(socket);
                close.countDown();
            }
        };
        WebSocketEngine.getEngine().register(app);

        final SelectorThread thread =
                WebSocketsTest.createSelectorThread(WebSocketsTest.PORT, new StaticResourcesAdapter());

        try {
            cleanDisconnect(app);
            dirtyDisconnect(app);
        } finally {
            thread.stopEndpoint();
            WebSocketEngine.getEngine().unregister(app);
        }

    }

    private void dirtyDisconnect(EchoWebSocketApplication app) throws Exception {

        Assert.assertEquals(app.getWebSockets().size(), 0, "There should be 0 clients connected");

        BadWebSocketClient badClient = newClient();
        BadWebSocketClient client2 = newClient();

        badClient.killConnection();

        checkSend(client2);
        boolean connected = client2.isConnected();
        client2.close();
        Assert.assertTrue(connected);

        Thread.sleep(3000);
        Assert.assertEquals(app.getWebSockets().size(), 0, "There should be 0 clients connected");
    }

    private void cleanDisconnect(WebSocketApplication app) throws Exception {
        Assert.assertEquals(app.getWebSockets().size(), 0, "There should be 0 clients connected");

        BadWebSocketClient client = newClient();
        BadWebSocketClient client2 = newClient();

        client.close();
        client.waitForClosed();
        checkSend(client2);
        Assert.assertTrue(client2.isConnected());

        client2.close();
        client2.waitForClosed();
        Assert.assertFalse(client2.isConnected());

        Thread.sleep(3000);
        Assert.assertEquals(app.getWebSockets().size(), 0, "There should be 0 clients connected");
    }

    private BadWebSocketClient newClient() throws Exception {
        BadWebSocketClient client = new BadWebSocketClient(ADDRESS);
        checkSend(client);
        return client;
    }

    private void checkSend(BadWebSocketClient client) throws InterruptedException {
        client.send("message");
        Assert.assertTrue(client.waitForMessage(), "Message should come back");
    }

    private static class BadWebSocketClient extends ClientWebSocket {
        private CountDownLatch messages;
        private final CountDownLatch closed = new CountDownLatch(1);

        public BadWebSocketClient(String addr, WebSocketListener... listeners) throws IOException {
            super(addr, listeners);
        }

        void killConnection() throws IOException {
            ((ClientNetworkHandler) getNetworkHandler()).shutdown();
        }

        @Override
        public void send(String data) {
            messages = new CountDownLatch(1);
            super.send(data);
        }

        @Override
        public void onClose(DataFrame frame) {
            super.onClose(frame);
            closed.countDown();
        }

        @Override
        public void onMessage(String frame) {
            super.onMessage(frame);
            messages.countDown();
        }

        public boolean waitForMessage() throws InterruptedException {
            return messages.await(WebSocketEngine.DEFAULT_TIMEOUT, TimeUnit.SECONDS);
        }

        public boolean waitForClosed() throws InterruptedException {
            return closed.await(WebSocketEngine.DEFAULT_TIMEOUT, TimeUnit.SECONDS);
        }

    }
}
