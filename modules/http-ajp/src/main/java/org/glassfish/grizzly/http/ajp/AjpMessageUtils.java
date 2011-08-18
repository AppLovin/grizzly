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
package org.glassfish.grizzly.http.ajp;

import org.glassfish.grizzly.memory.Buffers;
import java.io.CharConversionException;
import java.io.IOException;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.util.Ascii;
import org.glassfish.grizzly.http.util.BufferChunk;
import org.glassfish.grizzly.http.util.DataChunk;
import org.glassfish.grizzly.http.util.HexUtils;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.ssl.SSLSupport;
import static org.glassfish.grizzly.http.util.HttpCodecUtils.*;

/**
 * Utility method for Ajp message parsing and serialization.
 * 
 * @author Alexey Stashok
 */
final class AjpMessageUtils {

    static void decodeRequest(final Buffer requestContent,
            final AjpHttpRequest req, final boolean tomcatAuthentication)
            throws IOException {
        // FORWARD_REQUEST handler

        int offset = requestContent.position();

        // Translate the HTTP method code to a String.
        byte methodCode = requestContent.get(offset++);
        if (methodCode != AjpConstants.SC_M_JK_STORED) {
            String mName = AjpConstants.methodTransArray[(int) methodCode - 1];
            req.getMethodDC().setString(mName);
        }

        offset = getBytesToDataChunk(requestContent, offset, req.getProtocolDC());
        final int requestURILen = readShort(requestContent, offset);
        if (!isNullLength(requestURILen)) {
            req.getRequestURIRef().init(requestContent, offset + 2, offset + 2 + requestURILen);
        }
        // Don't forget to skip the terminating \0 (that's why "+ 1")
        offset += 2 + requestURILen + 1;

        offset = getBytesToDataChunk(requestContent, offset, req.remoteAddr());

        offset = getBytesToDataChunk(requestContent, offset, req.remoteHost());

        offset = getBytesToDataChunk(requestContent, offset, req.localName());

        req.setLocalPort(readShort(requestContent, offset));
        offset += 2;


        final boolean isSSL = requestContent.get(offset++) != 0;
        req.setSecure(isSSL);
        ((AjpHttpResponse) req.getResponse()).setSecure(isSSL);

        offset = decodeHeaders(requestContent, offset, req);

        offset = decodeAttributes(requestContent, offset, req,
                tomcatAuthentication);

        final DataChunk valueDC = req.getHeaders().getValue("host");
        parseHost(valueDC, req);
    }

    private static int decodeAttributes(final Buffer requestContent, int offset,
            final AjpHttpRequest req, final boolean tomcatAuthentication) {

        final DataChunk tmpDataChunk = req.tmpDataChunk;
        
        boolean moreAttr = true;

        while (moreAttr) {
            final byte attributeCode = requestContent.get(offset++);
            if (attributeCode == AjpConstants.SC_A_ARE_DONE) {
                return offset;
            }

            /* Special case ( XXX in future API make it separate type !)
             */
            if (attributeCode == AjpConstants.SC_A_SSL_KEY_SIZE) {
                // Bug 1326: it's an Integer.
                req.setAttribute(SSLSupport.KEY_SIZE_KEY,
                        readShort(requestContent, offset));
                offset += 2;
            }

            if (attributeCode == AjpConstants.SC_A_REQ_ATTRIBUTE) {
                // 2 strings ???...
                offset = setStringAttribute(req, requestContent, offset);
            }


            // 1 string attributes
            switch (attributeCode) {
                case AjpConstants.SC_A_CONTEXT:
                    offset = skipBytes(requestContent, offset);
                    // nothing
                    break;

                case AjpConstants.SC_A_SERVLET_PATH:
                    offset = skipBytes(requestContent, offset);
                    // nothing
                    break;

                case AjpConstants.SC_A_REMOTE_USER:
                    if (tomcatAuthentication) {
                        // ignore server
                        offset = skipBytes(requestContent, offset);
                    } else {
                        offset = getBytesToDataChunk(requestContent, offset,
                                req.remoteUser());
                    }
                    break;

                case AjpConstants.SC_A_AUTH_TYPE:
                    if (tomcatAuthentication) {
                        // ignore server
                        offset = skipBytes(requestContent, offset);
                    } else {
                        offset = getBytesToDataChunk(requestContent, offset,
                                req.authType());
                    }
                    break;

                case AjpConstants.SC_A_QUERY_STRING:
                    offset = getBytesToDataChunk(requestContent, offset,
                            req.getQueryStringDC());
                    break;

                case AjpConstants.SC_A_JVM_ROUTE:
                    offset = getBytesToDataChunk(requestContent, offset,
                            req.instanceId());
                    break;

                case AjpConstants.SC_A_SSL_CERT:
                    req.setSecure(true);
                    // SSL certificate extraction is costy, initialize on demand
                    offset = getBytesToDataChunk(requestContent, offset, req.sslCert());
                    break;

                case AjpConstants.SC_A_SSL_CIPHER:
                    req.setSecure(true);
                    offset = setStringAttributeValue(req,
                            SSLSupport.CIPHER_SUITE_KEY, requestContent, offset);
                    break;

                case AjpConstants.SC_A_SSL_SESSION:
                    req.setSecure(true);
                    offset = setStringAttributeValue(req,
                            SSLSupport.SESSION_ID_KEY, requestContent, offset);
                    break;

                case AjpConstants.SC_A_SECRET:
                    offset = getBytesToDataChunk(requestContent, offset, tmpDataChunk);

                    req.setSecret(tmpDataChunk.toString());
                    tmpDataChunk.recycle();

                    break;

                case AjpConstants.SC_A_STORED_METHOD:
                    offset = getBytesToDataChunk(requestContent, offset, req.getMethodDC());
                    break;

                default:
                    break; // ignore, we don't know about it - backward compat
            }
        }
        
        return offset;
    }

    private static int decodeHeaders(final Buffer requestContent, int offset,
            final AjpHttpRequest req) {
        // Decode headers
        final MimeHeaders headers = req.getHeaders();

        final int hCount = readShort(requestContent, offset);
        offset += 2;
        
        for (int i = 0; i < hCount; i++) {
            String hName = null;

            // Header names are encoded as either an integer code starting
            // with 0xA0, or as a normal string (in which case the first
            // two bytes are the length).
            int isc = readShort(requestContent, offset);
            int hId = isc & 0xFF;

            DataChunk valueDC = null;
            isc &= 0xFF00;
            if (0xA000 == isc) {
                offset += 2;
                hName = AjpConstants.headerTransArray[hId - 1];
                valueDC = headers.addValue(hName);
            } else {
                // reset hId -- if the header currently being read
                // happens to be 7 or 8 bytes long, the code below
                // will think it's the content-type header or the
                // content-length header - SC_REQ_CONTENT_TYPE=7,
                // SC_REQ_CONTENT_LENGTH=8 - leading to unexpected
                // behaviour.  see bug 5861 for more information.
                hId = -1;

                final int headerNameLen = readShort(requestContent, offset);
                offset += 2;
                valueDC = headers.addValue(requestContent,
                        offset, headerNameLen);
                // Don't forget to skip the terminating \0 (that's why "+ 1")
                offset += headerNameLen + 1;
            }

            offset = getBytesToDataChunk(requestContent, offset, valueDC);

            // Get the last added header name (the one we need)
            final DataChunk headerNameDC = headers.getName(headers.size() - 1);

            if (hId == AjpConstants.SC_REQ_CONTENT_LENGTH
                    || (hId == -1 && headerNameDC.equalsIgnoreCase("Content-Length"))) {
                // just read the content-length header, so set it
                final long cl = Ascii.parseLong(valueDC);
                if (cl < Integer.MAX_VALUE) {
                    req.setContentLength((int) cl);
                }
            } else if (hId == AjpConstants.SC_REQ_CONTENT_TYPE
                    || (hId == -1 && headerNameDC.equalsIgnoreCase("Content-Type"))) {
                // just read the content-type header, so set it
                req.setContentType(valueDC.toString());
            }
        }

        return offset;
    }

    /**
     * Parse host.
     */
    private static void parseHost(final DataChunk hostDC,
            final AjpHttpRequest request)
            throws CharConversionException {

        if (hostDC == null) {
            // HTTP/1.0
            // Default is what the socket tells us. Overriden if a host is
            // found/parsed
            request.setServerPort(request.getLocalPort());
            request.serverName().setString(request.getLocalName());
            return;
        }

        final BufferChunk valueBC = hostDC.getBufferChunk();
        final int valueS = valueBC.getStart();
        final int valueL = valueBC.getEnd() - valueS;
        int colonPos = -1;

        final Buffer valueB = valueBC.getBuffer();
        final boolean ipv6 = (valueB.get(valueS) == '[');
        boolean bracketClosed = false;
        for (int i = 0; i < valueL; i++) {
            final byte b = valueB.get(i + valueS);
            if (b == ']') {
                bracketClosed = true;
            } else if (b == ':') {
                if (!ipv6 || bracketClosed) {
                    colonPos = i;
                    break;
                }
            }
        }

        if (colonPos < 0) {
            if (!request.isSecure()) {
                // 80 - Default HTTTP port
                request.setServerPort(80);
            } else {
                // 443 - Default HTTPS port
                request.setServerPort(443);
            }
            request.serverName().setBuffer(valueB, valueS, valueS + valueL);
        } else {
            request.serverName().setBuffer(valueB, valueS, valueS + colonPos);

            int port = 0;
            int mult = 1;
            for (int i = valueL - 1; i > colonPos; i--) {
                int charValue = HexUtils.DEC[(int) valueB.get(i + valueS)];
                if (charValue == -1) {
                    // Invalid character
                    throw new CharConversionException("Invalid char in port: " + valueB.get(i + valueS));
                }
                port = port + (charValue * mult);
                mult = 10 * mult;
            }
            request.setServerPort(port);

        }

    }

    private static boolean isNullLength(final int length) {
        return length == 0xFFFF || length == -1;
    }

    private static int readShort(final Buffer buffer, final int offset) {
        return buffer.getShort(offset) & 0xFFFF;
    }

    static int getBytesToDataChunk(final Buffer buffer, final int offset,
            final DataChunk dataChunk) {

        final int bytesStart = offset + 2;
        final int length = readShort(buffer, offset);
        if (isNullLength(length)) {
            return bytesStart;
        }

        dataChunk.setBuffer(buffer, bytesStart, bytesStart + length);

        // Don't forget to skip the terminating \0 (that's why "+ 1")
        return bytesStart + length + 1;
    }

    private static int skipBytes(final Buffer buffer, final int offset) {

        final int bytesStart = offset + 2;
        final int length = readShort(buffer, offset);
        if (isNullLength(length)) {
            return bytesStart;
        }

        // Don't forget to skip the terminating \0 (that's why "+ 1")
        return bytesStart + length + 1;
    }

    private static int setStringAttribute(final AjpHttpRequest req,
            final Buffer buffer, int offset) {
        final DataChunk tmpDataChunk = req.tmpDataChunk;

        offset = getBytesToDataChunk(buffer, offset, tmpDataChunk);
        final String key = tmpDataChunk.toString();

        tmpDataChunk.recycle();

        offset = getBytesToDataChunk(buffer, offset, tmpDataChunk);        
        final String value = tmpDataChunk.toString();
        
        tmpDataChunk.recycle();

        req.setAttribute(key, value);
        
        return offset;
    }

    private static int setStringAttributeValue(final AjpHttpRequest req,
            final String key, final Buffer buffer, int offset) {

        final DataChunk tmpDataChunk = req.tmpDataChunk;
        
        offset = getBytesToDataChunk(buffer, offset, tmpDataChunk);
        final String value = tmpDataChunk.toString();
        
        tmpDataChunk.recycle();

        req.setAttribute(key, value);
        // Don't forget to skip the terminating \0 (that's why "+ 1")
        return offset;
    }

    public static Buffer encodeHeaders(MemoryManager mm,
            HttpResponsePacket httpResponsePacket) {
        Buffer encodedBuffer = mm.allocate(4096);
        int startPos = encodedBuffer.position();
        // Skip 4 bytes for the Ajp header
        encodedBuffer.position(startPos + 4);
        
        encodedBuffer.put(AjpConstants.JK_AJP13_SEND_HEADERS);
        encodedBuffer.putShort((short) httpResponsePacket.getStatus());
        encodedBuffer = putBytes(mm, encodedBuffer,
                httpResponsePacket.getReasonPhraseDC());

        if (httpResponsePacket.isAcknowledgement()) {
            // If it's acknoledgment packet - don't encode the headers
            // Serialize 0 num_headers
            encodedBuffer = putShort(mm, encodedBuffer, 0);
        } else {
            final MimeHeaders headers = httpResponsePacket.getHeaders();
            final String contentType = httpResponsePacket.getContentType();
            if (contentType != null) {
                headers.setValue("Content-Type").setString(contentType);
            }
            final String contentLanguage = httpResponsePacket.getContentLanguage();
            if (contentLanguage != null) {
                headers.setValue("Content-Language").setString(contentLanguage);
            }
            final long contentLength = httpResponsePacket.getContentLength();
            if (contentLength >= 0) {
                final Buffer contentLengthBuffer = getLongAsBuffer(mm, contentLength);
                headers.setValue("Content-Length").setBuffer(contentLengthBuffer,
                        contentLengthBuffer.position(), contentLengthBuffer.limit());
            }

            final int numHeaders = headers.size();

            encodedBuffer = putShort(mm, encodedBuffer, numHeaders);

            for (int i = 0; i < numHeaders; i++) {
                final DataChunk headerName = headers.getName(i);
                encodedBuffer = putBytes(mm, encodedBuffer, headerName);

                final DataChunk headerValue = headers.getValue(i);
                encodedBuffer = putBytes(mm, encodedBuffer, headerValue);
            }
        }

        // Add Ajp message header
        encodedBuffer.put(startPos, (byte) 'A');
        encodedBuffer.put(startPos + 1, (byte) 'B');
        encodedBuffer.putShort(startPos + 2,
                (short) (encodedBuffer.position() - startPos - 4));

        return encodedBuffer;
    }

    private static final int BODY_CHUNK_HEADER_SIZE = 7;
    private static final int MAX_BODY_CHUNK_CONTENT_SIZE =
            AjpConstants.MAX_READ_SIZE - BODY_CHUNK_HEADER_SIZE;
    public static Buffer appendContentAndTrim(final MemoryManager memoryManager,
            Buffer dstBuffer, Buffer httpContentBuffer) {
        Buffer resultBuffer = null;
        do {
            Buffer contentRemainder = null;
            if (httpContentBuffer.remaining() > MAX_BODY_CHUNK_CONTENT_SIZE) {
                contentRemainder = httpContentBuffer.split(
                        httpContentBuffer.position() + MAX_BODY_CHUNK_CONTENT_SIZE);
            }

            final Buffer encodedContentChunk = appendContentChunkAndTrim(
                    memoryManager,dstBuffer, httpContentBuffer);
            resultBuffer = Buffers.appendBuffers(memoryManager, resultBuffer,
                    encodedContentChunk);

            // dstBuffer use only once, when it comes from caller
            dstBuffer = null;
            httpContentBuffer = contentRemainder;
        } while (httpContentBuffer != null && httpContentBuffer.hasRemaining());

        return resultBuffer;
    }

    public static Buffer appendContentChunkAndTrim(final MemoryManager memoryManager,
            final Buffer dstBuffer, final Buffer httpContentBuffer) {

        final boolean useDstBufferForHeaders = dstBuffer != null &&
                dstBuffer.remaining() >= BODY_CHUNK_HEADER_SIZE;

        final Buffer headerBuffer;
        if (useDstBufferForHeaders) {
            headerBuffer = dstBuffer;
        } else {
            if (dstBuffer != null) {
                dstBuffer.trim();
            }
            headerBuffer = memoryManager.allocate(BODY_CHUNK_HEADER_SIZE);
        }

        headerBuffer.put((byte) 'A');
        headerBuffer.put((byte) 'B');
        headerBuffer.putShort((short) (3 + httpContentBuffer.remaining()));
        headerBuffer.put(AjpConstants.JK_AJP13_SEND_BODY_CHUNK);
        headerBuffer.putShort((short) httpContentBuffer.remaining());
        headerBuffer.trim();

        Buffer resultBuffer = Buffers.appendBuffers(memoryManager,
                headerBuffer, httpContentBuffer);

        if (!useDstBufferForHeaders && dstBuffer != null) {
            resultBuffer = Buffers.appendBuffers(memoryManager,
                    dstBuffer, resultBuffer);
        }

        if (resultBuffer.isComposite()) {
            // If during buffer appending - composite buffer was created -
            // allow buffer disposing
            resultBuffer.allowBufferDispose(true);
        }

        return resultBuffer;
    }

    private static Buffer putBytes(final MemoryManager memoryManager,
            Buffer dstBuffer, final DataChunk dataChunk) {
        if (dataChunk.isNull()) {
            return dstBuffer;
        }

        final int size = dataChunk.getLength();

        // Don't forget the terminating \0 (that's why "+ 1")
        if (dstBuffer.remaining() < size + 2 + 1) {
            dstBuffer = resizeBuffer(memoryManager, dstBuffer, size + 2 + 1);
        }

        dstBuffer.putShort((short) size);

        dstBuffer = put(memoryManager, dstBuffer, dataChunk);
        // Don't forget the terminating \0
        dstBuffer.put((byte) 0);

        return dstBuffer;
    }

    private static Buffer putShort(final MemoryManager memoryManager,
            Buffer dstBuffer, final int value) {
        if (dstBuffer.remaining() < 2) {
            dstBuffer = resizeBuffer(memoryManager, dstBuffer, 2);
        }
        dstBuffer.putShort((short) value);

        return dstBuffer;
    }
}
