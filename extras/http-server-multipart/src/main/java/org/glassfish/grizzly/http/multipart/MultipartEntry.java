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

package org.glassfish.grizzly.http.multipart;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.io.NIOInputStream;
import org.glassfish.grizzly.http.server.io.NIOReader;
import org.glassfish.grizzly.http.util.ContentType;

/**
 * Abstraction represents single multipart entry, its functionality is pretty
 * similar to {@link Request}.
 * In order to read multipart entry data it's possible to use either {@link #getNIOInputStream()}
 * or {@link #getNIOReader()} depends on whether we want to operate with binary or
 * {@link String} data.
 * 
 * @since 2.0.1
 *
 * @author Alexey Stashok
 */
public class MultipartEntry {
    private final MultipartReadHandler multipartReadHandler;
    
    private static final String DEFAULT_CONTENT_TYPE =
            "text/plain; charset=US-ASCII";
    private static final String DEFAULT_CONTENT_ENCODING =
            "US-ASCII";

    private Request request;
    private NIOInputStream requestInputStream;
    
    private final MultipartEntryNIOInputStream inputStream;
    private final MultipartEntryNIOReader reader;

    private final Map<String, String> headers = new HashMap<String, String>();

    private String contentType = DEFAULT_CONTENT_TYPE;
    private String contentDisposition;

    private int availableBytes;

    // Previous (processed) line terminator bytes, which we're not sure about,
    // whether they are part of section boundary or multipart entry content
    private int reservedBytes;
    
    private boolean isFinished;

    private boolean isSkipping;
    
    /**
     * Using stream flag.
     */
    protected boolean usingInputStream = false;


    /**
     * Using writer flag.
     */
    protected boolean usingReader = false;

    MultipartEntry(final MultipartReadHandler multipartReadHandler) {
        this.multipartReadHandler = multipartReadHandler;
        inputStream = new MultipartEntryNIOInputStream(this);
        reader = new MultipartEntryNIOReader(this);
    }

    void initialize(final Request request) {
        this.request = request;
        this.requestInputStream = request.getInputStream(false);

    }

    public NIOInputStream getNIOInputStream() {
        if (usingReader)
            throw new IllegalStateException("MultipartEntry is in the character mode");

        if (!usingInputStream) {
            inputStream.initialize(requestInputStream);
        }
        
        usingInputStream = true;
        
        return inputStream;
    }

    public NIOReader getNIOReader() {
        if (usingInputStream)
            throw new IllegalStateException("MultipartEntry is in the binary mode");

        if (!usingReader) {
            reader.initialize(requestInputStream, getEncoding());
        }

        usingReader = true;

        return reader;
    }

    public String getContentType() {
        return contentType;
    }

    void setContentType(final String contentType) {
        this.contentType = contentType;
    }

    public String getContentDisposition() {
        return contentDisposition;
    }

    void setContentDisposition(final String contentDisposition) {
        this.contentDisposition = contentDisposition;
    }

    public Set<String> getHeaderNames() {
        return headers.keySet();
    }

    public String getHeader(final String name) {
        return headers.get(name);
    }

    void setHeader(final String name, final String value) {
        headers.put(name, value);
    }

    public void skip() throws IOException {
        isSkipping = true;
        requestInputStream.skip(availableBytes);
        availableBytes = 0;
    }

    protected String getEncoding() {
        String contentEncoding = null;
        contentEncoding = ContentType.getCharsetFromContentType(getContentType());
        return contentEncoding != null ? contentEncoding : DEFAULT_CONTENT_ENCODING;
    }

    void reset() {
        headers.clear();
        contentType = DEFAULT_CONTENT_TYPE;
        contentDisposition = null;
        availableBytes = 0;
        reservedBytes = 0;
        isFinished = false;
        isSkipping = false;
        usingInputStream = false;
        usingReader = false;
        inputStream.recycle();
        reader.recycle();
    }

    void onFinished() {
        isFinished = true;
        onDataCame();
    }

    void onDataCame() {
        if (isSkipping) {
            try {
                requestInputStream.skip(availableBytes);
                availableBytes = 0;
            } catch (IOException e) {
                throw new IllegalStateException("Unexpected exception", e);
            }

            return;
        }

        if (usingInputStream) {
            inputStream.onDataCame();
        } else if (usingReader) {
            reader.onDataCame();
        }
    }

    boolean isFinished() {
        return isFinished;
    }

    int availableBytes() {
        return availableBytes;
    }

    void addAvailableBytes(final int delta) {
        availableBytes += delta;
    }

    /**
     * Get the previous (processed) line terminator bytes, which we're not sure about,
     * whether they are part of section boundary or multipart entry content
     * 
     * @return the previous (processed) line terminator bytes, which we're not sure about,
     * whether they are part of section boundary or multipart entry content
     */
    int getReservedBytes() {
        return reservedBytes;
    }

    /**
     * Set the previous (processed) line terminator bytes, which we're not sure about,
     * whether they are part of section boundary or multipart entry content
     *
     * @param reservedBytes the previous (processed) line terminator bytes,
     * which we're not sure about, whether they are part of section boundary or
     * multipart entry content
     */
    void setReservedBytes(int reservedBytes) {
        this.reservedBytes = reservedBytes;
    }
}