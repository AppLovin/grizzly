/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2007-2010 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.grizzly.cometd;

import com.sun.enterprise.web.connector.grizzly.comet.CometEvent;
import com.sun.enterprise.web.connector.grizzly.comet.CometHandler;
import com.sun.enterprise.web.connector.grizzly.comet.DefaultNotificationHandler;
import com.sun.enterprise.web.connector.grizzly.comet.NotificationHandler;
import com.sun.grizzly.cometd.bayeux.DeliverResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

/**
 * Customized {@link NotificationHandler} that isolate notification to 
 * subscribed channel.
 *
 * @author Jeanfrancois Arcand
 */
public class CometdNotificationHandler extends DefaultNotificationHandler{

    /**
     * Notify only client subscribed to the active channel.
     */
    @Override
    protected void notify0(CometEvent cometEvent,Iterator<CometHandler> iteratorHandlers) 
            throws IOException{
        ArrayList<Throwable> exceptions = null;
        CometHandler handler = null;
        Object o = cometEvent.attachment();
        String activeChannel = "";
        Collection<String> channels = null;
        
        if (o instanceof DeliverResponse){
            activeChannel = ((DeliverResponse)o).getChannel();
        }
        while(iteratorHandlers.hasNext()){
            try{
                handler = iteratorHandlers.next();
                
                if (handler instanceof CometdHandler){
                    channels = ((CometdHandler)handler).getChannels();
                } else if (handler instanceof DataHandler){
                    channels = ((DataHandler)handler).getChannels();
                } 

                if (o instanceof String ||
                        (channels != null 
                        && (channels.contains(BayeuxCometHandler.BAYEUX_COMET_HANDLER) 
                        || (activeChannel != null &&
                            channels.contains(activeChannel))))) {
                     notify0(cometEvent,handler);
                }
            } catch (Throwable ex){
                if (exceptions == null){
                    exceptions = new ArrayList<Throwable>();
                }
                exceptions.add(ex);
            }
        }
        if (exceptions != null){
            StringBuffer errorMsg = new StringBuffer();
            for(Throwable t: exceptions){
                errorMsg.append(t.getMessage());
            }
            throw new IOException(errorMsg.toString());
        }
    }
}
