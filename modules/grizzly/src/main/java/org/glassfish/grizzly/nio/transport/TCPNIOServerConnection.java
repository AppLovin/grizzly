/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
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

package org.glassfish.grizzly.nio.transport;

import org.glassfish.grizzly.IOEvent;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Context;
import org.glassfish.grizzly.ProcessorResult;
import org.glassfish.grizzly.nio.NIOConnection;
import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.Future;
import java.util.logging.Level;
import org.glassfish.grizzly.AbstractProcessor;
import org.glassfish.grizzly.CompletionHandlerAdapter;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.nio.RegisterChannelResult;
import org.glassfish.grizzly.nio.SelectionKeyHandler;
import org.glassfish.grizzly.util.LinkedTransferQueue;

/**
 *
 * @author oleksiys
 */
public class TCPNIOServerConnection extends
        TCPNIOConnection {

    private LinkedTransferQueue<FutureImpl> acceptListeners =
            new LinkedTransferQueue<FutureImpl>();
    
    private RegisterAcceptedChannelCompletionHandler defaultCompletionHandler;
    
    public TCPNIOServerConnection(TCPNIOTransport transport, 
            ServerSocketChannel serverSocketChannel) {
        super(transport, serverSocketChannel);
        defaultCompletionHandler =
                new RegisterAcceptedChannelCompletionHandler();
        setProcessor(new AcceptorEventProcessor());
    }

    /**
     * Asynchronously accept a {@link Connection}
     *
     * @return {@link Future}
     * @throws java.io.IOException
     */
    public Future<Connection> accept() throws IOException {
        if (!isBlocking) {
            return acceptAsync();
        } else {
            Future<Connection> future = acceptAsync();
            try {
                future.get();
            } catch(Exception e) {
            }
            
            return future;
        }
    }

    protected Future<Connection> acceptAsync() throws IOException {
        if (!isOpen()) throw new IOException("Connection is closed");
        
        FutureImpl future = new FutureImpl();
        SocketChannel acceptedChannel = doAccept();
        if (acceptedChannel != null) {
            configure(acceptedChannel);
            register(acceptedChannel, future);
        } else {
            acceptListeners.offer(future);
        }

        return future;
    }

    /**
     * Check, if there are queued accept listeners. If yes - accept the
     * connection and notify listener, otherwise do nothing.
     * @return <tt>true</tt>, if connection was accepted,
     *         <tt>false</tt> otherwise.
     */
    protected boolean tryAccept() throws IOException {
        FutureImpl listener = acceptListeners.poll();
        if (listener == null) return false;

        SocketChannel acceptedChannel = doAccept();
        if (acceptedChannel == null) {
            acceptListeners.offer(listener);
            return false;
        }

        configure(acceptedChannel);
        register(acceptedChannel, listener);

        return true;
    }

    private SocketChannel doAccept() throws IOException {
        ServerSocketChannel serverChannel =
                (ServerSocketChannel) getChannel();
        SocketChannel acceptedChannel = serverChannel.accept();
        return acceptedChannel;
    }

    private void configure(SocketChannel acceptedChannel) throws IOException {
        TCPNIOTransport tcpNIOTransport = (TCPNIOTransport) transport;
        tcpNIOTransport.configureChannel(acceptedChannel);
    }

    private void register(SocketChannel acceptedChannel,
            FutureImpl listener) throws IOException {
        
        TCPNIOTransport tcpNIOTransport = (TCPNIOTransport) transport;
        NIOConnection connection =
                tcpNIOTransport.obtainNIOConnection(acceptedChannel);

        CompletionHandler handler = (listener == null) ?
            defaultCompletionHandler :
            new RegisterAcceptedChannelCompletionHandler(listener);

        connection.setProcessor(transport.getProcessor());
        connection.setProcessorSelector(transport.getProcessorSelector());
        
        tcpNIOTransport.getNioChannelDistributor().registerChannelAsync(
                acceptedChannel, SelectionKey.OP_READ, connection, handler);
    }

    @Override
    public void preClose() {
        for(Iterator<FutureImpl> it = acceptListeners.iterator();
                it.hasNext(); ) {
            FutureImpl future = it.next();
            it.remove();

            future.failure(new IOException("Connection is closed"));
        }
    }

    protected void throwUnsupportReadWrite() {
        throw new UnsupportedOperationException("TCPNIOServerConnection " +
                "doesn't support neither read nor write operations.");
    }

    /**
     * EventProcessor, which will be notified, once OP_ACCEPT will
     * be ready on ServerSockerChannel
     */
    protected class AcceptorEventProcessor extends AbstractProcessor {
        /**
         * Method will be called by framework, when async accept will be ready
         *
         * @param context processing context
         * @throws java.io.IOException
         */
        public ProcessorResult process(Context context)
                throws IOException {
            SocketChannel acceptedChannel = doAccept();
            if (acceptedChannel == null) {
                return null;
            }

            configure(acceptedChannel);
            register(acceptedChannel, null);
            return null;
        }

        public boolean isInterested(IOEvent ioEvent) {
            return true;
        }

        public void setInterested(IOEvent ioEvent, boolean isInterested) {
        }
    }
        
    protected class RegisterAcceptedChannelCompletionHandler
            extends CompletionHandlerAdapter<RegisterChannelResult> {

        private FutureImpl listener;

        public RegisterAcceptedChannelCompletionHandler() {
            this(null);
        }

        public RegisterAcceptedChannelCompletionHandler(
                FutureImpl listener) {
            this.listener = listener;
        }

        @Override
        public void completed(Connection c, RegisterChannelResult result) {
            try {
                TCPNIOTransport nioTransport = (TCPNIOTransport) transport;

                nioTransport.registerChannelCompletionHandler.completed(c,
                        result);

                SelectionKeyHandler selectionKeyHandler =
                        nioTransport.getSelectionKeyHandler();
                SelectionKey acceptedConnectionKey =
                        result.getSelectionKey();
                Connection connection = selectionKeyHandler.getConnectionForKey(
                        acceptedConnectionKey);

                if (listener != null) {
                    listener.setResult(connection);
                }

                transport.fireIOEvent(IOEvent.ACCEPTED, connection);
            } catch (Exception e) {
                Grizzly.logger.log(Level.FINE, "Exception happened, when " +
                        "trying to accept the connection", e);
            }
        }
    }
}
    

