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
package com.sun.grizzly.nio.transport;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.sun.grizzly.Buffer;
import com.sun.grizzly.CompletionHandler;
import com.sun.grizzly.Connection;
import com.sun.grizzly.WriteResult;
import com.sun.grizzly.impl.FutureImpl;
import com.sun.grizzly.impl.ReadyFutureImpl;
import com.sun.grizzly.nio.tmpselectors.TemporarySelectorWriter;
import com.sun.grizzly.streams.AbstractStreamWriter;
import com.sun.grizzly.streams.AddressableStreamWriter;

/**
 *
 * @author Alexey Stashok
 */
public class UDPNIOStreamWriter extends AbstractStreamWriter
        implements AddressableStreamWriter<SocketAddress> {

    private SocketAddress peerAddress;
    
    private int sentBytesCounter;

    public UDPNIOStreamWriter(UDPNIOConnection connection) {
        super(connection);
    }

    @Override
    public Future<Integer> flush(SocketAddress peerAddress,
            CompletionHandler<Integer> completionHandler) throws IOException {
        setPeerAddress(peerAddress);
        return flush(completionHandler);
    }

    @Override
    public Future<Integer> flush(CompletionHandler<Integer> completionHandler)
            throws IOException {
        return super.flush(new ResetCounterCompletionHandler(completionHandler));
    }
    
    @Override
    protected Future<Integer> flush0(Buffer current,
            CompletionHandler<Integer> completionHandler) throws IOException {
        current.flip();
        final UDPNIOConnection connection = (UDPNIOConnection) getConnection();
        final UDPNIOTransport transport =
                (UDPNIOTransport) connection.getTransport();

        if (isBlocking()) {
            TemporarySelectorWriter writer =
                    (TemporarySelectorWriter)
                    transport.getTemporarySelectorIO().getWriter();

            Future<WriteResult<Buffer, SocketAddress>> future =
                    writer.write(connection, peerAddress, current,
                    new CompletionHandlerAdapter(null, completionHandler),
                    null,
                    getTimeout(TimeUnit.MILLISECONDS),
                    TimeUnit.MILLISECONDS);

            try {
                return new ReadyFutureImpl<Integer>(future.get().getWrittenSize());
            } catch (Exception e) {
                throw new IOException(
                        "UDPNIOStreamWriter.flush0(): unexpected exception. " +
                        e.getMessage());
            }
        } else {
            FutureImpl<Integer> future = new FutureImpl<Integer>();

            transport.getAsyncQueueIO().getWriter().write(
                    connection, peerAddress, current,
                    new CompletionHandlerAdapter(future, completionHandler));

            return future;
        }
    }

    @Override
    protected Future<Integer> close0(
            final CompletionHandler<Integer> completionHandler)
            throws IOException {
        
        if (buffer != null && buffer.position() > 0) {
            final FutureImpl<Integer> future =
                    new FutureImpl<Integer>();

            try {
                overflow(new CompletionHandler<Integer>() {

                    @Override
                    public void cancelled(Connection connection) {
                        close(ZERO);
                    }

                    @Override
                    public void failed(Connection connection, Throwable throwable) {
                        close(ZERO);
                    }

                    @Override
                    public void completed(Connection connection, Integer result) {
                        close(result);
                    }

                    @Override
                    public void updated(Connection connection, Integer result) {
                    }

                    public void close(Integer result) {
                        try {
                            getConnection().close();
                        } catch (IOException e) {
                        } finally {
                            if (completionHandler != null) {
                                completionHandler.completed(null, result);
                            }
                            
                            future.setResult(result);
                        }
                    }
                });
            } catch (IOException e) {
            }

            return future;
        } else {
            if (completionHandler != null) {
                completionHandler.completed(null, ZERO);
            }

            return new ReadyFutureImpl(ZERO);
        }
    }

    @Override
    public SocketAddress getPeerAddress() {
        final UDPNIOConnection connection = (UDPNIOConnection) getConnection();
        if (connection.isConnected()) {
            return (SocketAddress) connection.getPeerAddress();
        } else {
            return peerAddress;
        }
    }

    @Override
    public void setPeerAddress(final SocketAddress peerAddress) {
        final UDPNIOConnection connection = (UDPNIOConnection) getConnection();
        if (!connection.isConnected()) {
            this.peerAddress = peerAddress;
        } else {
            throw new IllegalStateException(
                    "UDP connection is already connected!");
        }
    }

    private final class CompletionHandlerAdapter
            implements CompletionHandler<WriteResult<Buffer, SocketAddress>> {

        private final FutureImpl<Integer> future;
        private final CompletionHandler<Integer> completionHandler;

        public CompletionHandlerAdapter(FutureImpl<Integer> future,
                CompletionHandler<Integer> completionHandler) {
            this.future = future;
            this.completionHandler = completionHandler;
        }

        @Override
        public void cancelled(Connection connection) {
            if (completionHandler != null) {
                completionHandler.cancelled(connection);
            }

            if (future != null) {
                future.cancel(false);
            }
        }

        @Override
        public void failed(Connection connection, Throwable throwable) {
            if (completionHandler != null) {
                completionHandler.failed(connection, throwable);
            }

            if (future != null) {
                future.failure(throwable);
            }
        }

        @Override
        public void completed(Connection connection, WriteResult result) {
            sentBytesCounter += result.getWrittenSize();
            int totalSentBytes = sentBytesCounter;

            if (completionHandler != null) {
                completionHandler.completed(connection, totalSentBytes);
            }

            if (future != null) {
                future.setResult(totalSentBytes);
            }
        }

        @Override
        public void updated(Connection connection, WriteResult result) {
            if (completionHandler != null) {
                completionHandler.updated(connection, sentBytesCounter +
                        result.getWrittenSize());
            }
        }
    }

    private final class ResetCounterCompletionHandler
            implements CompletionHandler<Integer> {

        private final CompletionHandler<Integer> parentCompletionHandler;

        public ResetCounterCompletionHandler(CompletionHandler<Integer> parentCompletionHandler) {
            this.parentCompletionHandler = parentCompletionHandler;
        }

        @Override
        public void cancelled(Connection connection) {
            peerAddress = null;
            if (parentCompletionHandler != null) {
                parentCompletionHandler.cancelled(connection);
            }
        }

        @Override
        public void failed(Connection connection, Throwable throwable) {
            peerAddress = null;
            if (parentCompletionHandler != null) {
                parentCompletionHandler.failed(connection, throwable);
            }
        }

        @Override
        public void completed(Connection connection, Integer result) {
            sentBytesCounter = 0;
            peerAddress = null;
            if (parentCompletionHandler != null) {
                parentCompletionHandler.completed(connection, result);
            }
        }

        @Override
        public void updated(Connection connection, Integer result) {
            peerAddress = null;
            if (parentCompletionHandler != null) {
                parentCompletionHandler.updated(connection, result);
            }
        }

    }
}
