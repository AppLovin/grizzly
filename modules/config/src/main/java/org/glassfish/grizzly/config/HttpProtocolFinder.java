/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2006-2010 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.config;

import java.io.IOException;
import java.util.logging.Logger;

import org.glassfish.grizzly.Context;
import org.glassfish.grizzly.http.WebFilter;
import org.glassfish.grizzly.config.dom.Protocol;
import org.glassfish.grizzly.config.dom.ProtocolFinder;
import org.glassfish.grizzly.config.dom.Ssl;

/**
 *
 * @author Alexey Stashok
 */
public class HttpProtocolFinder //extends com.glassfish.grizzly.http.portunif.HttpProtocolFinder
        implements ConfigAwareElement<ProtocolFinder> {

    private static final Logger logger = WebFilter.logger();

    private final Object sync = new Object();
    
    private volatile boolean isSecured;
    private volatile Ssl ssl;
    private volatile SSLConfigHolder sslConfigHolder;

    private final static int sslReadTimeout = 5000;

    public void configure(ProtocolFinder configuration) {
        Protocol protocol = configuration.findProtocol();
        isSecured = Boolean.parseBoolean(protocol.getSecurityEnabled());

        if (isSecured) {
            ssl = protocol.getSsl();
            if (!SSLConfigHolder.isAllowLazyInit(ssl)) {
                sslConfigHolder = SSLConfigHolder.configureSSL(ssl);
            }
        }
    }

//    @Override
    public String find(Context context/*, PUProtocolRequest protocolRequest*/)
            throws IOException {

        return null;
/*
        if (isSecured) {
            if (sslConfigHolder == null) {
                synchronized(sync) {
                    if (sslConfigHolder == null) {
                        sslConfigHolder = SSLConfigHolder.configureSSL(ssl);
                    }
                }
            }

            SelectionKey key = context.getSelectionKey();
            SelectableChannel channel = key.channel();


            final SSLEngine sslEngine = sslConfigHolder.createSSLEngine();

            final boolean isloglevelfine = logger.isLoggable(Level.FINE);
            if (isloglevelfine) {
                logger.log(Level.FINE, "sslEngine: " + sslEngine);
            }

            ByteBuffer inputBB = protocolRequest.getSecuredInputByteBuffer();
            ByteBuffer outputBB = protocolRequest.getSecuredOutputByteBuffer();
            ByteBuffer byteBuffer = protocolRequest.getByteBuffer();
            int securedBBSize = sslEngine.getSession().getPacketBufferSize();
            if (inputBB == null || (inputBB != null && securedBBSize > inputBB.capacity())) {
                inputBB = ByteBuffer.allocate(securedBBSize * 2);
                protocolRequest.setSecuredInputByteBuffer(inputBB);
            }

            if (outputBB == null || (outputBB != null && securedBBSize > outputBB.capacity())) {
                outputBB = ByteBuffer.allocate(securedBBSize * 2);
                protocolRequest.setSecuredOutputByteBuffer(outputBB);
            }

            int applicationBBSize = sslEngine.getSession().getApplicationBufferSize();
            if (byteBuffer == null || applicationBBSize > byteBuffer.capacity()) {
                ByteBuffer newBB = ByteBuffer.allocate(securedBBSize);
                byteBuffer.flip();
                newBB.put(byteBuffer);
                byteBuffer = newBB;
                protocolRequest.setByteBuffer(byteBuffer);
            }

            inputBB.clear();
            outputBB.position(0);
            outputBB.limit(0);

            inputBB.put((ByteBuffer) byteBuffer.flip());
            byteBuffer.clear();

            final WorkerThread workerThread = (WorkerThread) Thread.currentThread();

            boolean isHandshakeDone = false;
            HandshakeStatus handshakeStatus = HandshakeStatus.NEED_UNWRAP;
            try {
                byteBuffer = SSLUtils.doHandshake(channel, byteBuffer,
                        inputBB, outputBB, sslEngine, handshakeStatus,
                        sslReadTimeout, inputBB.position() > 0);
                if (isloglevelfine) {
                    logger.log(Level.FINE, "handshake is done");
                }

                protocolRequest.setSSLEngine(sslEngine);
                workerThread.setSSLEngine(sslEngine);
                workerThread.setInputBB(inputBB);
                workerThread.setOutputBB(outputBB);
                
                final Object attachment = workerThread.updateAttachment(Mode.SSL_ENGINE);
                key.attach(attachment);

                // set "no available data" for secured output buffer
                outputBB.limit(outputBB.position());
                isHandshakeDone = true;
            } catch (EOFException ex) {
                if (isloglevelfine) {
                    logger.log(Level.FINE, "handshake failed", ex);
                }
                // DO nothing, as the client closed the connection
            } catch (Exception ex) {
                // An exception means the handshake failed.
                if (isloglevelfine) {
                    logger.log(Level.FINE, "handshake failed", ex);
                }

                inputBB.flip();
                byteBuffer.put(inputBB);
            }

            if (isloglevelfine) {
                logger.log(Level.FINE, "after handshake. isComplete: " +
                        isHandshakeDone);
            }

            if (isHandshakeDone) {
                int byteRead = -1;
                if (isloglevelfine) {
                    logger.log(Level.FINE, "secured bytebuffer: " + inputBB);
                }

                final long startTime = System.currentTimeMillis();

                String protocol = null;
                
                while((protocol = super.find(context, protocolRequest)) == null &&
                        System.currentTimeMillis() - startTime < sslReadTimeout) {
                    byteRead = SSLUtils.doRead(channel, inputBB, sslEngine,
                            sslReadTimeout).bytesRead;
                    if (byteRead == -1) {
                        logger.log(Level.FINE, "EOF");
                        throw new EOFException();
                    }
                    
                    byteBuffer = SSLUtils.unwrapAll(byteBuffer, inputBB, sslEngine);
                    protocolRequest.setByteBuffer(byteBuffer);
                    workerThread.setByteBuffer(byteBuffer);
                }

                context.setAttribute(SSLReadFilter.SSL_PREREAD_DATA, Boolean.TRUE);

                if (isloglevelfine) {
                    logger.log(Level.FINE, "protocol: " + protocol);
                }

                return protocol;
            }

            return null;
        } else {
            return super.find(context, protocolRequest);
        }
*/
    }
}
