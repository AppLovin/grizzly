/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
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
package org.glassfish.grizzly.spdy.frames;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.memory.CompositeBuffer;
import org.glassfish.grizzly.memory.MemoryManager;

import static org.glassfish.grizzly.spdy.Constants.SPDY_VERSION;

public class SynReplyFrame extends SpdyFrame {

    private static final ThreadCache.CachedTypeIndex<SynReplyFrame> CACHE_IDX =
                       ThreadCache.obtainIndex(SynReplyFrame.class, 8);

    public static final int TYPE = 2;
    public static final byte FLAG_FIN = 0x01;

    private static final Marshaller MARSHALLER = new SynReplyFrameMarshaller();

    private int streamId;
    private Buffer compressedHeaders;
    private boolean dispose;


    // ------------------------------------------------------------ Constructors


    private SynReplyFrame() {
        super();
    }


    // ---------------------------------------------------------- Public Methods


    public static SynReplyFrame create() {
        SynReplyFrame frame = ThreadCache.takeFromCache(CACHE_IDX);
        if (frame == null) {
            frame = new SynReplyFrame();
        }
        return frame;
    }

    static SynReplyFrame create(final SpdyHeader header) {
        SynReplyFrame frame = create();
        frame.initialize(header);
        return frame;
    }

    public int getStreamId() {
        return streamId;
    }

    public void setStreamId(int streamId) {
        if (header == null) {
            this.streamId = streamId;
        }
    }

    public Buffer getCompressedHeaders() {
        if (compressedHeaders == null) {
            compressedHeaders = (header.buffer.isComposite())
                    ? header.buffer.slice()
                    : header.buffer;
            dispose = header.buffer == compressedHeaders;
        }
        return compressedHeaders;
    }

    public void setCompressedHeaders(Buffer compressedHeaders) {
        if (header == null) {
            this.compressedHeaders = compressedHeaders;
        }
    }


    // -------------------------------------------------- Methods from Cacheable


    @Override
    public void recycle() {
        streamId = 0;
        if (dispose) {
            compressedHeaders.dispose();
        }
        compressedHeaders = null;
        super.recycle();
    }


    // -------------------------------------------------- Methods from SpdyFrame


    @Override
    public Marshaller getMarshaller() {
        return MARSHALLER;
    }


    // ------------------------------------------------------- Protected Methods


    @Override
    protected void initialize(SpdyHeader header) {
        super.initialize(header);
        streamId = header.buffer.getInt() & 0x7fffffff;
    }


    // ---------------------------------------------------------- Nested Classes


    private static final class SynReplyFrameMarshaller implements Marshaller {

        @Override
        public Buffer marshall(final SpdyFrame frame, final MemoryManager memoryManager) {
            final SynReplyFrame synReplyFrame = (SynReplyFrame) frame;
            final Buffer frameBuffer = allocateHeapBuffer(memoryManager, 12);

            frameBuffer.putInt(0x80000000 | (SPDY_VERSION << 16) | TYPE);  // C | SPDY_VERSION | SYN_REPLY

            frameBuffer.putInt((synReplyFrame.flags << 24) | (synReplyFrame.compressedHeaders.remaining() + 4)); // FLAGS | LENGTH
            frameBuffer.putInt(synReplyFrame.streamId & 0x7FFFFFFF); // STREAM_ID
            frameBuffer.trim();
            CompositeBuffer cb = CompositeBuffer.newBuffer(memoryManager, frameBuffer);
            cb.append(synReplyFrame.compressedHeaders);
            cb.allowBufferDispose(true);
            cb.allowInternalBuffersDispose(true);
            return cb;
        }

    } // END SynReplyFrameMarshaller

}
