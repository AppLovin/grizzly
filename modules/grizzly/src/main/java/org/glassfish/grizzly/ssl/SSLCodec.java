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

package org.glassfish.grizzly.ssl;

import java.io.IOException;
import java.util.concurrent.Future;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Codec;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Transformer;

/**
 * SSL Codec, which contains SSL encoder and decoder {@link Transformer}s.
 * 
 * @author Alexey Stashok
 */
public class SSLCodec implements Codec<Buffer, Buffer> {
    private SSLHandshaker sslHandshaker;

    private SSLContext sslContext;

    private SSLEngineConfigurator serverSSLEngineConfig;
    private SSLEngineConfigurator clientSSLEngineConfig;
    
    private Transformer<Buffer, Buffer> decoder;
    private Transformer<Buffer, Buffer> encoder;

    public SSLCodec(SSLContextConfigurator config) {
        this(config.createSSLContext());
    }

    public SSLCodec(SSLContext sslContext) {
        this.sslContext = sslContext;

        decoder = new SSLDecoderTransformer();
        encoder = new SSLEncoderTransformer();

        serverSSLEngineConfig = new SSLEngineConfigurator(sslContext);
        clientSSLEngineConfig = new SSLEngineConfigurator(sslContext, true,
                false, false);
        
        sslHandshaker = new DefaultSSLHandshaker();
    }

    /**
     * {@inheritDoc}
     */
    public Transformer<Buffer, Buffer> getDecoder() {
        return decoder;
    }

    /**
     * {@inheritDoc}
     */
    public Transformer<Buffer, Buffer> getEncoder() {
        return encoder;
    }

    public SSLEngineConfigurator getClientSSLEngineConfig() {
        return clientSSLEngineConfig;
    }

    public void setClientSSLEngineConfig(
            SSLEngineConfigurator clientSSLEngineConfig) {
        this.clientSSLEngineConfig = clientSSLEngineConfig;
    }

    public SSLEngineConfigurator getServerSSLEngineConfig() {
        return serverSSLEngineConfig;
    }

    public void setServerSSLEngineConfig(
            SSLEngineConfigurator serverSSLEngineConfig) {
        this.serverSSLEngineConfig = serverSSLEngineConfig;
    }

    public SSLHandshaker getSslHandshaker() {
        return sslHandshaker;
    }

    public void setSslHandshaker(SSLHandshaker sslHandshaker) {
        this.sslHandshaker = sslHandshaker;
    }

    public Future<SSLEngine> handshake(Connection connection)
            throws IOException {
        return handshake(connection, clientSSLEngineConfig);
    }

    public Future<SSLEngine> handshake(Connection connection,
            SSLEngineConfigurator configurator) throws IOException {
        return sslHandshaker.handshake(connection, configurator);
    }
}
