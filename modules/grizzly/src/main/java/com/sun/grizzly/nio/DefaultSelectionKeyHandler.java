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

package com.sun.grizzly.nio;

import com.sun.grizzly.Grizzly;
import com.sun.grizzly.IOEvent;
import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author oleksiys
 */
public final class DefaultSelectionKeyHandler implements SelectionKeyHandler {
    
    private static Logger logger = Grizzly.logger(DefaultSelectionKeyHandler.class);

    private static final int[] ioEvent2SelectionKeyInterest = {0,
        SelectionKey.OP_ACCEPT, 0, SelectionKey.OP_CONNECT, SelectionKey.OP_READ,
        SelectionKey.OP_WRITE, 0};
    
    private final static IOEvent[][] ioEventMap;

    static {
        ioEventMap = new IOEvent[32][];
        for(int i=0; i<ioEventMap.length; i++) {
            int idx = 0;
            IOEvent[] tmpArray = new IOEvent[4];
            if ((i & SelectionKey.OP_READ) != 0) {
                tmpArray[idx++] = IOEvent.READ;
            }

            if ((i & SelectionKey.OP_WRITE) != 0) {
                tmpArray[idx++] = IOEvent.WRITE;
            }

            if ((i & SelectionKey.OP_CONNECT) != 0) {
                tmpArray[idx++] = IOEvent.CONNECTED;
            }

            if ((i & SelectionKey.OP_ACCEPT) != 0) {
                tmpArray[idx++] = IOEvent.SERVER_ACCEPT;
            }

            ioEventMap[i] = Arrays.copyOf(tmpArray, idx);
        }
    }

    @Override
    public void onKeyRegistered(SelectionKey key) {
        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, "KEY IS REGISTERED: " + key);
        }
    }
    
    @Override
    public void cancel(SelectionKey key) throws IOException {
        key.cancel();
    }

    @Override
    public int ioEvent2SelectionKeyInterest(IOEvent ioEvent) {
        return ioEvent2SelectionKeyInterest[ioEvent.ordinal()];
    }

    @Override
    public IOEvent[] getIOEvents(int interest) {
        return ioEventMap[interest];
    }

    @Override
    public IOEvent selectionKeyInterest2IoEvent(int selectionKeyInterest) {
        if ((selectionKeyInterest & SelectionKey.OP_READ) != 0) {
            return IOEvent.READ;
        } else if ((selectionKeyInterest & SelectionKey.OP_WRITE) != 0) {
            return IOEvent.WRITE;
        } else if ((selectionKeyInterest & SelectionKey.OP_ACCEPT) != 0) {
            return IOEvent.SERVER_ACCEPT;
        } else if ((selectionKeyInterest & SelectionKey.OP_CONNECT) != 0) {
            return IOEvent.CONNECTED;
        }

        return IOEvent.NONE;
    }

    @Override
    public boolean onProcessInterest(SelectionKey key, int interest)
            throws IOException {
        return true;
    }

    @Override
    public NIOConnection getConnectionForKey(SelectionKey selectionKey) {
        return (NIOConnection) selectionKey.attachment();
    }

    @Override
    public void setConnectionForKey(NIOConnection connection,
            SelectionKey selectionKey) {
        selectionKey.attach(connection);
    }
}
