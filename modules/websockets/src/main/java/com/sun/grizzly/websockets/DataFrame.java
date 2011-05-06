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
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
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

package com.sun.grizzly.websockets;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.logging.Logger;

public class DataFrame {
    private static final Logger logger = Logger.getLogger(WebSocketEngine.WEBSOCKET);
    private String payload;
    private byte[] bytes;
    private FrameType type;

    public static DataFrame read(NetworkHandler handler) throws IOException {
        DataFrame frame = null;
        Iterator<FrameType> set = EnumSet.allOf(FrameType.class).iterator();
        while (frame == null && set.hasNext()) {
            FrameType frameType = set.next();
            if (frameType.accept(handler)) {
                frame = new DataFrame(frameType);
                frame.setBytes(frameType.unframe(handler));
            }
        }

        return frame;
    }

    public DataFrame(FrameType frameType) {
        type = frameType;
    }

    public DataFrame(String data) {
        type = FrameType.TEXT;
        payload = data;
        try {
            bytes = data.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public FrameType getType() {
        return type;
    }

    public void setType(FrameType type) {
        this.type = type;
    }

    public String getTextPayload() {
        if (payload == null && bytes != null) {
            try {
                payload = new String(bytes, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
        return payload;
    }

    public void setTextPayload(String payload) {
        type = FrameType.TEXT;
        this.payload = payload;
    }

    public void setBytes(byte[] bytes) {
        this.bytes = bytes;
    }

    public byte[] getBinaryPayload() {
        return bytes;
    }

    public byte[] frame() {
        return frame(type);
    }

    public byte[] frame(FrameType type) {
        return type.frame(bytes);
    }

    public void respond(WebSocket socket) throws IOException {
        getType().respond(socket, this);
    }

    @Override
    public String toString() {
        return new StringBuilder("DataFrame")
                .append("{")
                .append("payload='").append(getTextPayload()).append('\'')
                .append(", type=").append(type)
                .append(", bytes=").append(Arrays.toString(bytes))
                .append('}')
                .toString();
    }
}
