/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2010 Sun Microsystems, Inc. All rights reserved.
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

package com.sun.grizzly.http;

import com.sun.grizzly.http.core.HttpPacket;
import com.sun.grizzly.Buffer;
import com.sun.grizzly.Connection;
import com.sun.grizzly.Grizzly;
import com.sun.grizzly.attributes.Attribute;
import com.sun.grizzly.filterchain.FilterChainContext;
import com.sun.grizzly.filterchain.NextAction;
import com.sun.grizzly.http.core.HttpRequest;
import com.sun.grizzly.http.core.HttpResponse;
import com.sun.grizzly.memory.MemoryManager;
import java.io.IOException;

/**
 *
 * @author oleksiys
 */
public class HttpClientFilter extends HttpFilter {
    protected static final int HEADER_PARSED_STATE = 2;

    
    private final Attribute<HttpResponseImpl> httpResponseInProcessAttr;

    public HttpClientFilter() {
        this(DEFAULT_MAX_HEADERS_SIZE);
    }

    public HttpClientFilter(int maxHeadersSize) {
        super(maxHeadersSize);
        
        this.httpResponseInProcessAttr =
                Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(
                "HttpServerFilter.httpRequest");
    }

    @Override
    public NextAction handleRead(FilterChainContext ctx) throws IOException {
        Buffer input = (Buffer) ctx.getMessage();
        final Connection connection = ctx.getConnection();
        
        HttpResponseImpl httpResponse = httpResponseInProcessAttr.get(connection);
        if (httpResponse == null) {
            final ParsingState parsingState =
                    new ParsingState(input.position(), maxHeadersSize);
            httpResponse = new HttpResponseImpl(parsingState);
            httpResponseInProcessAttr.set(connection, httpResponse);
        }

        return handleRead(ctx, httpResponse);
    }

    @Override
    public NextAction handleWrite(FilterChainContext ctx) throws IOException {
        final HttpPacket input = (HttpPacket) ctx.getMessage();
        final Connection connection = ctx.getConnection();

        final Buffer output = encodeHttpPacket(
                connection.getTransport().getMemoryManager(), input);

        ctx.setMessage(output);
        return ctx.getInvokeAction();
    }
    
    @Override
    final void onHttpPacketParsed(FilterChainContext ctx) {
        httpResponseInProcessAttr.remove(ctx.getConnection());
    }

    @Override
    final boolean decodeInitialLine(HttpPacketParsing httpPacket,
            ParsingState parsingState, Buffer input) {

        final HttpResponse httpResponse = (HttpResponse) httpPacket;
        
        final int packetLimit = parsingState.packetLimit;

        while(true) {
            int subState = parsingState.subState;

            switch(subState) {
                case 0: { // HTTP protocol
                    final int spaceIdx =
                            findSpace(input, parsingState.offset, packetLimit);
                    if (spaceIdx == -1) {
                        parsingState.offset = input.limit();
                        return false;
                    }

                    httpResponse.getProtocolBC().setBuffer(input,
                            parsingState.start, spaceIdx);

                    parsingState.start = -1;
                    parsingState.offset = spaceIdx;

                    parsingState.subState++;
                }

                case 1: { // skip spaces after the HTTP protocol
                    final int nonSpaceIdx =
                            skipSpaces(input, parsingState.offset, packetLimit);
                    if (nonSpaceIdx == -1) {
                        parsingState.offset = input.limit();
                        return false;
                    }

                    parsingState.start = nonSpaceIdx;
                    parsingState.offset = nonSpaceIdx + 1;
                    parsingState.subState++;
                }

                case 2 : { // parse the status code
                    final int spaceIdx =
                            findSpace(input, parsingState.offset, packetLimit);
                    if (spaceIdx == -1) {
                        parsingState.offset = input.limit();
                        return false;
                    }

                    httpResponse.getStatusBC().setBuffer(input,
                            parsingState.start, spaceIdx);

                    parsingState.start = -1;
                    parsingState.offset = spaceIdx;

                    parsingState.subState++;
                }

                case 3: { // skip spaces after the status code
                    final int nonSpaceIdx =
                            skipSpaces(input, parsingState.offset, packetLimit);
                    if (nonSpaceIdx == -1) {
                        parsingState.offset = input.limit();
                        return false;
                    }

                    parsingState.start = nonSpaceIdx;
                    parsingState.offset = nonSpaceIdx;
                    parsingState.subState++;
                }

                case 4: { // HTTP response reason-phrase
                    if (!findEOL(parsingState, input)) {
                        parsingState.offset = input.limit();
                        return false;
                    }
                    
                    httpResponse.getReasonPhraseBC().setBuffer(
                            input, parsingState.start,
                            parsingState.checkpoint);

                    parsingState.subState = 0;
                    parsingState.start = -1;
                    parsingState.checkpoint = -1;

                    return true;
                }

                default: throw new IllegalStateException();
            }
        }
    }

    @Override
    Buffer encodeInitialLine(HttpPacket httpPacket, Buffer output, MemoryManager memoryManager) {
        final HttpRequest httpRequest = (HttpRequest) httpPacket;
        output = put(memoryManager, output, httpRequest.getMethodBC());
        output = put(memoryManager, output, Constants.SP);
        output = put(memoryManager, output, httpRequest.getRequestURIBC());
        output = put(memoryManager, output, Constants.SP);
        output = put(memoryManager, output, httpRequest.getProtocolBC());

        return output;
    }
}
