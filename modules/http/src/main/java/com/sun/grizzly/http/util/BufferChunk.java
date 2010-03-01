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

package com.sun.grizzly.http.util;

import com.sun.grizzly.Buffer;
import java.nio.charset.Charset;

/**
 * {@link Buffer} chunk representation.
 * Helps HTTP module to avoid redundant String creation.
 * 
 * @author Alexey Stashok
 */
public class BufferChunk {

    public static BufferChunk newInstance() {
        return new BufferChunk();
    }
    
    private Buffer buffer;

    private int start;
    private int end;

    private String cachedString;

    private BufferChunk() {
    }

    public Buffer getBuffer() {
        return buffer;
    }

    public void setBuffer(Buffer buffer) {
        this.buffer = buffer;
        cachedString = null;
    }
    
    public void setBuffer(Buffer buffer, int start, int end) {
        this.buffer = buffer;
        this.start = start;
        this.end = end;
        cachedString = null;
    }

    public int getStart() {
        return start;
    }

    public void setStart(int start) {
        this.start = start;
        cachedString = null;
    }
    
    public int getEnd() {
        return end;
    }

    public void setEnd(int end) {
        this.end = end;
        cachedString = null;
    }

    public void setString(String string) {
        cachedString = string;
    }

    public int size() {
        return end - start;
    }

    @Override
    public String toString() {
        return toString(Charset.defaultCharset());
    }
    
    public String toString(Charset charset) {
        if (cachedString != null) return cachedString;

        cachedString = buffer.toStringContent(charset, start, end);
        return cachedString;
    }

    /**
     * Returns true if the message bytes starts with the specified string.
     * @param c the character
     * @param starting The start position
     */
    public int indexOf(char c, int starting) {
        int ret = indexOf(buffer, start + starting, end, c);
        return (ret >= start) ? ret - start : -1;
    }

    private static int indexOf(Buffer buffer, int off, int end, char qq) {
        // Works only for UTF
        while (off < end) {
            byte b = buffer.get(off);
            if (b == qq) {
                return off;
            }
            off++;
        }
        return -1;
    }

    
    /**
     * Compares the message bytes to the specified String object.
     * @param s the String to compare
     * @return true if the comparison succeeded, false otherwise
     */
    public boolean equals(String s) {
        if (!hasString()) {
            if ((end - start) != s.length()) {
                return false;
            }

            for(int i = start; i < end; i++) {
                if (buffer.get(i) != s.charAt(i)) {
                    return false;
                }
            }

            return true;
        } else {
            return cachedString.equals(s);
        }
    }

    /**
     * Compares the message bytes to the specified String object.
     * @param s the String to compare
     * @return true if the comparison succeeded, false otherwise
     */
    public boolean equalsIgnoreCase(String s) {
        if (!hasString()) {
            if ((end - start) != s.length()) {
                return false;
            }
            
            for(int i = start; i < end; i++) {
                if (Ascii.toLower(buffer.get(i)) != Ascii.toLower(s.charAt(i - start))) {
                    return false;
                }
            }
            
            return true;
        } else {
            return cachedString.equalsIgnoreCase(s);
        }
    }


    /**
     * Returns true if the message bytes starts with the specified string.
     * @param s the string
     * @param pos The start position
     */
    public boolean startsWithIgnoreCase(String s, int pos) {
        if (!hasString()) {
            int len = s.length();
            if (len > end - start - pos) {
                return false;
            }
            
            int off = start + pos;
            for (int i = 0; i < len; i++) {
                if (Ascii.toLower(buffer.get(off++)) != Ascii.toLower(s.charAt(i))) {
                    return false;
                }
            }
            
            return true;
        } else {
            if (cachedString.length() < pos + s.length()) {
                return false;
            }

            for (int i = 0; i < s.length(); i++) {
                if (Ascii.toLower(s.charAt(i))
                        != Ascii.toLower(cachedString.charAt(pos + i))) {
                    return false;
                }
            }
            return true;
        }
    }
    
    public boolean hasBuffer() {
        return buffer != null;
    }

    public boolean hasString() {
        return cachedString != null;
    }
    
    public boolean isNull() {
        return !hasBuffer() && !hasString();
    }

    public void recycle() {
        start = -1;
        end = -1;
        buffer = null;
        cachedString = null;
    }
}
