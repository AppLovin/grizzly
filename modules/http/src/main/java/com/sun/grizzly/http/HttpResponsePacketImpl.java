/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.grizzly.http;

import com.sun.grizzly.ThreadCache;
import com.sun.grizzly.http.HttpCodecFilter.ContentParsingState;

/**
 *
 * @author oleksiys
 */
class HttpResponsePacketImpl extends HttpResponsePacket implements HttpPacketParsing {
    private static final ThreadCache.CachedTypeIndex<HttpResponsePacketImpl> CACHE_IDX =
            ThreadCache.obtainIndex(HttpResponsePacketImpl.class, 2);

    public static HttpResponsePacketImpl create() {
        final HttpResponsePacketImpl httpResponseImpl =
                ThreadCache.takeFromCache(CACHE_IDX);
        if (httpResponseImpl != null) {
            return httpResponseImpl;
        }

        return new HttpResponsePacketImpl();
    }

    private boolean isHeaderParsed;
    private boolean isExpectContent;
    
    private final HttpCodecFilter.ParsingState headerParsingState;
    private final HttpCodecFilter.ContentParsingState contentParsingState;

    private HttpResponsePacketImpl() {
        this.headerParsingState = new HttpCodecFilter.ParsingState();
        this.contentParsingState = new HttpCodecFilter.ContentParsingState();
        isExpectContent = true;
    }

    public void initialize(int initialOffset, int maxHeaderSize) {
        headerParsingState.initialize(initialOffset, maxHeaderSize);
    }
    
    @Override
    public HttpCodecFilter.ParsingState getHeaderParsingState() {
        return headerParsingState;
    }

    @Override
    public ContentParsingState getContentParsingState() {
        return contentParsingState;
    }

    public ProcessingState getProcessingState() {
        return (((HttpRequestPacketImpl) getRequest()).getProcessingState());
    }

    @Override
    public boolean isHeaderParsed() {
        return isHeaderParsed;
    }

    @Override
    public void setHeaderParsed(boolean isHeaderParsed) {
        this.isHeaderParsed = isHeaderParsed;
    }

    @Override
    public boolean isExpectContent() {
        return isExpectContent;
    }

    @Override
    public void setExpectContent(boolean isExpectContent) {
        this.isExpectContent = isExpectContent;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void reset() {
        headerParsingState.recycle();
        contentParsingState.recycle();
        isHeaderParsed = false;
        isExpectContent = true;
        super.reset();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void recycle() {
        reset();
        ThreadCache.putToCache(CACHE_IDX, this);
    }
}
