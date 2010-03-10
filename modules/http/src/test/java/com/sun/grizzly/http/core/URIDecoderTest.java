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

package com.sun.grizzly.http.core;

import com.sun.grizzly.Buffer;
import com.sun.grizzly.TransportFactory;
import com.sun.grizzly.memory.MemoryManager;
import com.sun.grizzly.memory.MemoryUtils;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import junit.framework.TestCase;

/**
 * Parse URL decoder
 * 
 * @author Alexey Stashok
 */
public class URIDecoderTest extends TestCase {
    private static final Charset UTF8_CHARSET = Charset.forName("UTF-8");
    
    public void testURLNoChangeBB() throws Exception {
        testDecoder("http://localhost:8080/helloworld");
    }

    public void testURLSpaceBB() throws Exception {
        testDecoder("http://localhost:8080/hello world");
    }

    public void testURLUTFBB() throws Exception {
        String s = "http://localhost:8080/\u043F\u0440\u0438\u0432\u0435\u0442 \u043C\u0438\u0440";

        testDecoder(s);
    }

    private void testDecoder(String inputURI) throws Exception {
        
        MemoryManager mm = TransportFactory.getInstance().getDefaultMemoryManager();
        String encodedURI = URLEncoder.encode(inputURI, UTF8_CHARSET.name());

        Buffer b = MemoryUtils.wrap(mm, encodedURI);

        BufferChunk bufferChunk = BufferChunk.newInstance();
        bufferChunk.setBuffer(b);

        URLDecoder.decode(bufferChunk);

        String decodedURI = bufferChunk.toString(UTF8_CHARSET);

        assertEquals(inputURI, decodedURI);
    }
}
