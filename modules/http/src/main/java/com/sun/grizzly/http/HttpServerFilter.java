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
import com.sun.grizzly.http.core.HttpResponse;
import com.sun.grizzly.memory.MemoryManager;
import java.io.IOException;

/**
 *
 * @author oleksiys
 */
public class HttpServerFilter extends HttpFilter {
    protected static final int HEADER_PARSED_STATE = 2;
    
    private final Attribute<HttpRequestImpl> httpRequestInProcessAttr;

    public HttpServerFilter() {
        this(DEFAULT_MAX_HEADERS_SIZE);
    }

    public HttpServerFilter(int maxHeadersSize) {
        super(maxHeadersSize);
        
        this.httpRequestInProcessAttr =
                Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(
                "HttpServerFilter.httpRequest");
    }

    @Override
    public NextAction handleRead(FilterChainContext ctx) throws IOException {
        Buffer input = (Buffer) ctx.getMessage();
        final Connection connection = ctx.getConnection();
        
        HttpRequestImpl httpRequest = httpRequestInProcessAttr.get(connection);
        if (httpRequest == null) {
            final ParsingState parsingState =
                    new ParsingState(input.position(), maxHeadersSize);
            httpRequest = new HttpRequestImpl(parsingState);
            httpRequestInProcessAttr.set(connection, httpRequest);
        }

        return handleRead(ctx, httpRequest);
    }

    @Override
    final void onHttpPacketParsed(FilterChainContext ctx) {
        httpRequestInProcessAttr.remove(ctx.getConnection());
    }

    @Override
    final boolean decodeInitialLine(HttpPacketParsing httpPacket,
            ParsingState parsingState, Buffer input) {

        final HttpRequestImpl httpRequest = (HttpRequestImpl) httpPacket;

        final int reqLimit = parsingState.packetLimit;

        while(true) {
            int subState = parsingState.subState;

            switch(subState) {
                case 0 : { // parse the method name
                    final int spaceIdx =
                            findSpace(input, parsingState.offset, reqLimit);
                    if (spaceIdx == -1) {
                        parsingState.offset = input.limit();
                        return false;
                    }

                    httpRequest.getMethodBC().setBuffer(input,
                            parsingState.start, spaceIdx);

                    parsingState.start = -1;
                    parsingState.offset = spaceIdx;

                    parsingState.subState++;
                }

                case 1: { // skip spaces after the method name
                    final int nonSpaceIdx =
                            skipSpaces(input, parsingState.offset, reqLimit);
                    if (nonSpaceIdx == -1) {
                        parsingState.offset = input.limit();
                        return false;
                    }

                    parsingState.start = nonSpaceIdx;
                    parsingState.offset = nonSpaceIdx + 1;
                    parsingState.subState++;
                }

                case 2: { // parse the requestURI
                    if (!parseRequestURI(httpRequest, parsingState, input)) {
                        return false;
                    }
                }

                case 3: { // skip spaces after requestURI
                    final int nonSpaceIdx =
                            skipSpaces(input, parsingState.offset, reqLimit);
                    if (nonSpaceIdx == -1) {
                        parsingState.offset = input.limit();
                        return false;
                    }

                    parsingState.start = nonSpaceIdx;
                    parsingState.offset = nonSpaceIdx;
                    parsingState.subState++;
                }

                case 4: { // HTTP protocol
                    if (!findEOL(parsingState, input)) {
                        parsingState.offset = input.limit();
                        return false;
                    }

                    if (parsingState.checkpoint > parsingState.start) {
                        httpRequest.getProtocolBC().setBuffer(
                                input, parsingState.start,
                                parsingState.checkpoint);
                    } else {
                        httpRequest.setProtocol("");
                    }

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
        final HttpResponse httpResponse = (HttpResponse) httpPacket;
        output = put(memoryManager, output, httpResponse.getProtocolBC());
        output = put(memoryManager, output, Constants.SP);
        output = put(memoryManager, output, httpResponse.getStatusBC());
        output = put(memoryManager, output, Constants.SP);
        output = put(memoryManager, output, httpResponse.getReasonPhraseBC());

        return output;
    }

    private static boolean parseRequestURI(HttpRequestImpl httpRequest,
            ParsingState state, Buffer input) {
        
        final int limit = Math.min(input.limit(), state.packetLimit);

        int offset = state.offset;

        boolean found = false;

        while(offset < limit) {
            final byte b = input.get(offset);
            if (b == Constants.SP || b == Constants.HT) {
                found = true;
                break;
            } else if ((b == Constants.CR)
                       || (b == Constants.LF)) {
                // HTTP/0.9 style request
                found = true;
                break;
            } else if ((b == Constants.QUESTION)
                       && (state.checkpoint == -1)) {
                state.checkpoint = offset;
            }

            offset++;
        }

        if (found) {
            httpRequest.getRequestURIBC().setBuffer(input, state.start, offset);
            if (state.checkpoint != -1) {
                httpRequest.getQueryStringBC().setBuffer(input,
                        state.checkpoint + 1, offset);
            }

            state.start = -1;
            state.checkpoint = -1;
            state.subState++;
        }

        state.offset = offset;
        return found;
    }
}
