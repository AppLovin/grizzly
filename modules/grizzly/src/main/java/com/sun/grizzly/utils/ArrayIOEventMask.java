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

package com.sun.grizzly.utils;

import com.sun.grizzly.IOEvent;

/**
 * Array based {@link IOEventMask} implementation
 * 
 * @author Alexey Stashok
 */
public class ArrayIOEventMask implements IOEventMask {

    private boolean[] arrayMask = new boolean[IOEvent.values().length];

    public ArrayIOEventMask() {
        this(false);
    }

    public ArrayIOEventMask(boolean enableAllInterests) {
        if (enableAllInterests) {
            for (int i = 0; i < arrayMask.length; i++) {
                arrayMask[i] = true;
            }
        }
    }

    /**
     * Costructs {@link IOEventMask}, with specific enabled interests
     *
     * @param ioEvents the array of {@link IOEvent}s, which are initially
     *        interested.
     */
    public ArrayIOEventMask(IOEvent... ioEvents) {
        for (IOEvent ioEvent : ioEvents) {
            setInterested(ioEvent, true);
        }
    }

    /**
     * Copy constructor
     * 
     * @param mask pattern {@link IOEventMask}
     */
    public ArrayIOEventMask(IOEventMask mask) {
        IOEvent[] ioEvents = IOEvent.values();
        for (IOEvent ioEvent : ioEvents) {
            setInterested(ioEvent, mask.isInterested(ioEvent));
        }
    }

    @Override
    public boolean isInterested(IOEvent ioEvent) {
        int position = ioEvent.ordinal();
        return arrayMask[position];
    }

    @Override
    public void setInterested(IOEvent ioEvent, boolean isInterested) {
        int position = ioEvent.ordinal();
        arrayMask[position] = isInterested;
    }

    @Override
    public IOEventMask or(IOEventMask mask) {
        IOEventMask newIOEventMask = new ArrayIOEventMask();
        IOEvent[] ioEvents = IOEvent.values();
        for (IOEvent ioEvent : ioEvents) {
            newIOEventMask.setInterested(ioEvent, isInterested(ioEvent) |
                    mask.isInterested(ioEvent));
        }

        return newIOEventMask;
    }

    @Override
    public IOEventMask and(IOEventMask mask) {
        IOEventMask newIOEventMask = new ArrayIOEventMask();
        IOEvent[] ioEvents = IOEvent.values();
        for (IOEvent ioEvent : ioEvents) {
            newIOEventMask.setInterested(ioEvent, isInterested(ioEvent) &
                    mask.isInterested(ioEvent));
        }

        return newIOEventMask;
    }

    @Override
    public IOEventMask xor(IOEventMask mask) {
        IOEventMask newIOEventMask = new ArrayIOEventMask();
        IOEvent[] ioEvents = IOEvent.values();
        for (IOEvent ioEvent : ioEvents) {
            newIOEventMask.setInterested(ioEvent, isInterested(ioEvent) ^
                    mask.isInterested(ioEvent));
        }

        return newIOEventMask;
    }

    @Override
    public IOEventMask inv() {
        return xor(IOEventMask.ALL_EVENTS_MASK);
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer("Interested in: ");
        boolean isAppended = false;
        for(int i=0; i<arrayMask.length; i++) {
            if (arrayMask[i]) {
                if (isAppended) {
                    sb.append(',');
                }
                sb.append(IOEvent.values()[i]);
                isAppended = true;
            }
        }

        return sb.toString();
    }
}
