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

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.FilterAdapter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import java.io.IOException;
import java.util.logging.Filter;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.filterchain.StopAction;

/**
 * The {@link TCPNIOTransport}'s transport {@link Filter} implementation
 * 
 * @author Alexey Stashok
 */
public class TCPNIOTransportFilter extends FilterAdapter {

    public static final int DEFAULT_BUFFER_SIZE = 8192;
    private final TCPNIOTransport transport;

    TCPNIOTransportFilter(final TCPNIOTransport transport) {
        this.transport = transport;
    }

    @Override
    public NextAction handleRead(final FilterChainContext ctx,
            final NextAction nextAction) throws IOException {
        final TCPNIOConnection connection =
                (TCPNIOConnection) ctx.getConnection();


        final TCPNIOStreamReader reader =
                (TCPNIOStreamReader) connection.getStreamReader();
        final Buffer buffer = reader.read0();
        reader.appendBuffer(buffer);

        if (reader.availableDataSize() > 0) {
            ctx.setStreamReader(connection.getStreamReader());
            ctx.setStreamWriter(connection.getStreamWriter());
        } else {
            return new StopAction();
        }

        return nextAction;
    }

    @Override
    public NextAction handleWrite(final FilterChainContext ctx,
            final NextAction nextAction) throws IOException {
        final Object message = ctx.getMessage();
        if (message != null) {
            final Connection connection = ctx.getConnection();
            transport.write(connection, (Buffer) message);
        }

        return nextAction;
    }

    @Override
    public void exceptionOccurred(final FilterChainContext ctx,
            final Throwable error) {

        final Connection connection = ctx.getConnection();
        if (connection != null) {
            try {
                connection.close();
            } catch (IOException e) {
            }
        }
    }
}
