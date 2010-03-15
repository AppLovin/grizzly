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

import com.sun.grizzly.Buffer;
import com.sun.grizzly.memory.BufferUtils;

/**
 * Object represents HTTP message content: complete or part.
 * The <tt>HttpContent</tt> object could be used both with
 * fixed-size and chunked HTTP messages.
 * To get the HTTP message header - call {@link HttpContent#getHttpHeader()}.
 *
 * To build <tt>HttpContent</tt> message, use {@link Builder} object, which
 * could be get following way: {@link HttpContent#builder(com.sun.grizzly.http.HttpHeader)}.
 *
 * @see HttpPacket
 * @see HttpHeader
 *
 * @author Alexey Stashok
 */
public class HttpContent implements HttpPacket, com.sun.grizzly.Appendable<HttpContent> {

    /**
     * Returns {@link HttpContent} builder.
     * 
     * @param httpHeader related HTTP message header
     * @return {@link Builder}.
     */
    public static Builder builder(HttpHeader httpHeader) {
        return new Builder(httpHeader);
    }

    protected boolean isLast;
    
    protected Buffer content = BufferUtils.EMPTY_BUFFER;

    protected HttpHeader httpHeader;

    protected HttpContent() {
        this(null);
    }

    protected HttpContent(HttpHeader httpHeader) {
        this.httpHeader = httpHeader;
    }

    /**
     * Get the HTTP message content {@link Buffer}.
     *
     * @return {@link Buffer}.
     */
    public final Buffer getContent() {
        return content;
    }

    protected final void setContent(Buffer content) {
        this.content = content;
    }

    /**
     * Get the HTTP message header, associated with this content.
     *
     * @return {@link HttpHeader}.
     */
    public final HttpHeader getHttpHeader() {
        return httpHeader;
    }

    /**
     * Return <tt>true</tt>, if the current content chunk is last,
     * or <tt>false</tt>, if there are content chunks to follow.
     * 
     * @return <tt>true</tt>, if the current content chunk is last,
     * or <tt>false</tt>, if there are content chunks to follow.
     */
    public boolean isLast() {
        return isLast;
    }

    public void setLast(boolean isLast) {
        this.isLast = isLast;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean isHeader() {
        return false;
    }

    @Override
    public HttpContent append(HttpContent element) {
        if (isLast) {
            throw new IllegalStateException("Can not append to a last chunk");
        }

        final Buffer content2 = element.getContent();
        if (content2 != null && content2.hasRemaining()) {
            content = BufferUtils.appendBuffers(null, content, content2);
        }

        if (element.isLast()) {
            element.setContent(content);
            return element;
        }        

        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void recycle() {
        isLast = false;
        content = BufferUtils.EMPTY_BUFFER;
        httpHeader = null;
    }

    /**
     * <tt>HttpContent</tt> message builder.
     */
    public static class Builder<T extends Builder> {

        protected HttpContent packet;

        protected Builder(HttpHeader httpHeader) {
            packet = create(httpHeader);
        }

        protected HttpContent create(HttpHeader httpHeader) {
            return new HttpContent(httpHeader);
        }

        /**
         * Set whether this <tt>HttpContent</tt> chunk is the last.
         *
         * @param isLast is this <tt>HttpContent</tt> chunk last.
         * @return <tt>Builder</tt>
         */
        public final T last(boolean isLast) {
            packet.setLast(isLast);
            return (T) this;
        }
        
        /**
         * Set the <tt>HttpContent</tt> chunk content {@link Buffer}.
         *
         * @param content the <tt>HttpContent</tt> chunk content {@link Buffer}.
         * @return <tt>Builder</tt>
         */
        public final T content(Buffer content) {
            packet.setContent(content);
            return (T) this;
        }

        /**
         * Build the <tt>HttpContent</tt> message.
         *
         * @return <tt>HttpContent</tt>
         */
        public HttpContent build() {
            return packet;
        }
    }
}
