/*
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License).  You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the license at
 * https://glassfish.dev.java.net/public/CDDLv1.0.html or
 * glassfish/bootstrap/legal/CDDLv1.0.txt.
 * See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at glassfish/bootstrap/legal/CDDLv1.0.txt.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * you own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Copyright 2006 Sun Microsystems, Inc. All rights reserved.
 */

package com.sun.grizzly.config;

import com.sun.grizzly.Context;
import com.sun.grizzly.portunif.PUProtocolRequest;
import com.sun.grizzly.portunif.ProtocolFinder;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 *
 * @author oleksiys
 */
public class XProtocolFinder implements ProtocolFinder {
    private final static String name = "X-protocol";
    private byte[] signature = name.getBytes();

    public String find(Context context, PUProtocolRequest protocolRequest)
            throws IOException {
        ByteBuffer buffer = protocolRequest.getByteBuffer();
        int position = buffer.position();
        int limit = buffer.limit();
        try {
            buffer.flip();
            System.out.println("Buffer: " + new String(buffer.array(), buffer.arrayOffset(), buffer.remaining()));
            if (buffer.remaining() >= signature.length) {
                for(int i=0; i<signature.length; i++) {
                    if (buffer.get(i) != signature[i]) {
                        return null;
                    }
                }

                return name;
            }
        } finally {
            buffer.limit(limit);
            buffer.position(position);
        }

        return null;
    }

}
