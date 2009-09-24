/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
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

package com.sun.grizzly.utils;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.sun.grizzly.Buffer;
import com.sun.grizzly.Readable;

/**
 * Bridge between {@link com.sun.grizzly.Readable} and {@link InputStream}
 *
 * @author Alexey Stashok
 */
public class ReadableInputStream extends InputStream {
    private Buffer buffer;
    private Readable readable;
    private long timeout = 30000;
    
    public Readable getReadable() {
        return readable;
    }

    public void setReadable(Readable readable) {
        this.readable = readable;
    }

    public Buffer getBuffer() {
        return buffer;
    }

    public void setBuffer(Buffer buffer) {
        this.buffer = buffer;
    }

    public long getTimeout(TimeUnit unit) {
        return unit.convert(timeout, TimeUnit.MILLISECONDS);
    }

    public void setTimeout(long timeout, TimeUnit unit) {
        this.timeout = TimeUnit.MILLISECONDS.convert(timeout, unit);
    }

    @Override
    public int read() throws IOException {
        if (!buffer.hasRemaining()) {
            doBlockingRead();
        }

        if (!buffer.hasRemaining()) {
            throw new EOFException();
        }

        return buffer.get() & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (!buffer.hasRemaining()) {
            doBlockingRead();
        }

        if (!buffer.hasRemaining()) {
            throw new EOFException();
        }

        int bytesToCopy = Math.min(len, buffer.remaining());
        buffer.get(b, off, bytesToCopy);
        return bytesToCopy;
    }

    @Override
    public void close() throws IOException {
        readable.close();
    }


    protected void doBlockingRead() throws IOException {
        if (!buffer.hasRemaining()) {
            buffer.clear();
        }

        Future future = readable.read(buffer);
        try {
            future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new IOException("Interrupted exception");
        } catch (TimeoutException e) {
            throw new EOFException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            } else {
                throw new IOException(cause.getClass().getName() +
                        ": " + cause.getMessage());
            }
        } finally {
            buffer.flip();
        }
    }
}
