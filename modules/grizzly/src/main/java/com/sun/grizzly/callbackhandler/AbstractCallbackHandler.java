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

package com.sun.grizzly.callbackhandler;

import com.sun.grizzly.Context;
import com.sun.grizzly.Grizzly;
import com.sun.grizzly.IOEvent;
import com.sun.grizzly.utils.IOEventMask;
import com.sun.grizzly.Processor;
import com.sun.grizzly.ProcessorResult;
import com.sun.grizzly.AbstractProcessor;
import com.sun.grizzly.utils.ArrayIOEventMask;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base {@link CallbackHandler} implementation, which delegates
 * {@link Processor#process(Context)} call to appropriate {@link CallbackHandler}
 * method.
 *
 * @see CallbackHandler
 * 
 * @author Alexey Stashok
 */
public abstract class AbstractCallbackHandler extends AbstractProcessor
        implements CallbackHandler {

    private final Logger logger = Grizzly.logger(AbstractCallbackHandler.class);
    
    // By default interested in all client connection events
    protected IOEventMask interestedIoEventsMask = new ArrayIOEventMask(
            IOEventMask.CLIENT_EVENTS_MASK);


    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isInterested(IOEvent ioEvent) {
        return interestedIoEventsMask.isInterested(ioEvent);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setInterested(IOEvent ioEvent, boolean isInterested) {
        interestedIoEventsMask.setInterested(ioEvent, isInterested);
    }

    /**
     * Delegate {@link IOEvent} processing to appropriate
     * {@link CallbackHandler} method.
     */
    @Override
    public ProcessorResult process(Context context)
            throws IOException {
        switch (context.getIoEvent()) {
            case READ :
                onRead(context);
                break;
            case WRITE : 
                onWrite(context);
                break;
            case ACCEPTED :
                onAccept(context);
                break;
            case CONNECTED: 
                onConnect(context);
                break;
            case CLOSED: 
                onClose(context);
                break;
            default:
                logger.log(Level.WARNING, 
                        "Unexpected SelectionKey operation: " + 
                        context.getIoEvent());
        }

        return null;
    }
}
