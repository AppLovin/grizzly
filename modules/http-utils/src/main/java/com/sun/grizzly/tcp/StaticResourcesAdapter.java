
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
 *
 * Portions Copyright Apache Software Foundation.
 */ 

package com.sun.grizzly.tcp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import com.sun.grizzly.util.buf.ByteChunk;
import com.sun.grizzly.util.buf.MessageBytes;
import com.sun.grizzly.util.http.MimeType;
import java.util.logging.Logger;

/**
 * Simple HTTP based Web Server. Part of this class is from Tomcat sandbox code
 * from Costin Manolache.
 *
 * @author Jeanfrancois Arcand
 */
public class StaticResourcesAdapter implements Adapter {
       
    private String rootFolder = ".";
    
    private File rootFolderF = null;
    
    private ConcurrentHashMap<String,File> cache
            = new ConcurrentHashMap<String,File>();
    
    protected Logger logger = Logger.getLogger("Grizzly");
       
    
    public StaticResourcesAdapter() {
        this(".");
    }

    public StaticResourcesAdapter(String rootFolder) {
        this.rootFolder = rootFolder;
    }
    
    
    public void service(Request req, final Response res) throws Exception {
        MessageBytes mb = req.requestURI();
        ByteChunk requestURI = mb.getByteChunk();
        String uri = req.requestURI().toString();
        if (uri.indexOf("..") >= 0) {
            res.setStatus(404);
            return;        
        }
        service(uri, req, res);
    }

    
    protected void service(String uri, Request req, final Response res)
        throws Exception {
        if (rootFolderF == null){            
            rootFolderF = new File(rootFolder);
            try {
                rootFolder = rootFolderF.getCanonicalPath();
            } catch (IOException e) {
            }
        }
     
        // local file
        File resource = cache.get(uri);
        if (resource == null){
            resource = new File(rootFolderF, uri);
            cache.put(uri,resource);
        }

        if (resource.isDirectory()) {
            resource = new File(resource, "index.html");
            cache.put(uri,resource);            
        }

        if (!resource.exists()) {
            logger.log(Level.INFO,"File not found  " + resource);
            res.setStatus(404);
            return;
        }        
        res.setStatus(200);

        int dot=uri.lastIndexOf(".");
        if( dot > 0 ) {
            String ext=uri.substring(dot+1);
            String ct= MimeType.get(ext);
            if( ct!=null) {
                res.setContentType(ct);
            }
        } else {
            res.setContentType(MimeType.get("html"));
        }

        res.setContentLength((int)resource.length());        
        res.sendHeaders();

        /* Workaround Linux NIO bug
         * 6427312: (fc) FileChannel.transferTo() throws IOException "system call interrupted"
         * 5103988: (fc) FileChannel.transferTo should return -1 for EAGAIN instead throws IOException
         * 6253145: (fc) FileChannel.transferTo on Linux fails when going beyond 2GB boundary
         * 6470086: (fc) FileChannel.transferTo(2147483647, 1, channel) cause "Value too large" exception 
         */
        FileInputStream fis = new FileInputStream(resource);
        byte b[] = new byte[8192];
        ByteChunk chunk = new ByteChunk();
        int rd = 0;
        while ((rd = fis.read(b)) > 0) {
            chunk.setBytes(b, 0, rd);
            res.doWrite(chunk);
        }

        try{
            req.action( ActionCode.ACTION_POST_REQUEST , null);
        }catch (Throwable t) {
            t.printStackTrace();
        }
        
        res.finish();
    }
    
    
    public void afterService(Request req, Response res) 
        throws Exception {
        // Recycle the wrapper request and response
        req.recycle();
        res.recycle();     
    }

    
    public void fireAdapterEvent(String string, Object object) {
    }

    
    public String getRootFolder() {
        return rootFolder;
    }

    public void setRootFolder(String newRoot) {
        this.rootFolder = newRoot;
    }    
}