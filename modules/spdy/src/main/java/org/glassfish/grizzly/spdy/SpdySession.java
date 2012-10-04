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
package org.glassfish.grizzly.spdy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.glassfish.grizzly.IOEvent;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;

/**
 *
 * @author oleksiys
 */
final class SpdySession {
    private final boolean isServer;
    private Inflater inflater;
    private Deflater deflater;

    private int lastPeerStreamId;
    private int lastLocalStreamId;
    
    private FilterChain upstreamChain;
    private FilterChain downstreamChain;
    
    private Map<Integer, SpdyStream> streamsMap =
            new ConcurrentHashMap<Integer, SpdyStream>();
    
    public SpdySession() {
        this(true);
    }
    
    public SpdySession(final boolean isServer) {
        this.isServer = isServer;
    }
    
    public SpdyStream getStream(final int streamId) {
        return streamsMap.get(streamId);
    }
    
    public Inflater getInflater() {
        if (inflater == null) {
            inflater = new Inflater();
        }
        
        return inflater;
    }
    
    public Deflater getDeflater() {
        if (deflater == null) {
            deflater = new Deflater();
        }
        
        return deflater;
    }

    public boolean isServer() {
        return isServer;
    }

    SpdyStream acceptStream(final FilterChainContext context,
            final SpdyRequest spdyRequest,
            final int streamId, final int associatedToStreamId, 
            final int priority, final int slot) {
        
        final FilterChainContext upstreamContext =
                getUpstreamChain(context).obtainFilterChainContext(
                context.getConnection());
        final FilterChainContext downstreamContext =
                getDownstreamChain(context).obtainFilterChainContext(
                context.getConnection());
        
        upstreamContext.getInternalContext().setEvent(IOEvent.READ);
        upstreamContext.setMessage(HttpContent.builder(spdyRequest).build());
        upstreamContext.setAddressHolder(context.getAddressHolder());
        
        final SpdyStream spdyStream = new SpdyStream(spdyRequest,
                upstreamContext, downstreamContext, streamId, associatedToStreamId,
                priority, slot);
        
        streamsMap.put(streamId, spdyStream);
        lastPeerStreamId = streamId;
        
        return spdyStream;
    }
    
    private FilterChain getUpstreamChain(final FilterChainContext context) {
        if (upstreamChain == null) {
            upstreamChain = (FilterChain) context.getFilterChain().subList(
                    context.getFilterIdx(), context.getEndIdx());
        }
        
        return upstreamChain;
    }
    
    private FilterChain getDownstreamChain(final FilterChainContext context) {
        if (downstreamChain == null) {
            downstreamChain = (FilterChain) context.getFilterChain().subList(
                    context.getStartIdx(), context.getFilterIdx());
        }
        
        return downstreamChain;
    }
}
