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

package com.sun.grizzly.http.server;

import com.sun.grizzly.Grizzly;
import com.sun.grizzly.filterchain.Filter;
import com.sun.grizzly.filterchain.FilterChain;
import com.sun.grizzly.filterchain.FilterChainContext;
import com.sun.grizzly.http.server.filecache.FileCache;
import com.sun.grizzly.http.server.io.OutputBuffer;
import com.sun.grizzly.http.server.util.MimeType;
import com.sun.grizzly.http.util.HttpStatus;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Static resources handler, which handles static resources requests made to a
 * {@link Adapter}.
 *
 * This class doesn't not decode the {@link Request} uri and just do
 * basic security check. If you need more protection, use the {@link Adapter}.
 *
 * @author Jeanfrancois Arcand
 * @author Alexey Stashok
 */
public class StaticResourcesHandler {
    private static Logger logger = Grizzly.logger(StaticResourcesHandler.class);
    
    protected volatile File docRoot;

    protected String resourcesContextPath = "";

    private volatile int fileCacheFilterIdx = -1;

    public StaticResourcesHandler() {
        this(".");
    }

    public StaticResourcesHandler(String rootFolder) {
        setDocRoot(rootFolder);
    }

    public StaticResourcesHandler(File rootFolder) {
        setDocRoot(rootFolder);
    }

    /**
     * Based on the {@link Request} URI, try to map the file from the
     * {@link StaticResourcesHandler#getDocRoot()}, and send it synchronously
     * using send file.
     * @param req the {@link Request}
     * @param res the {@link Response}
     * @throws Exception
     */
    public boolean handle(Request req, final Response res) throws Exception {
        String uri = req.getRequestURI();
        if (uri.indexOf("..") >= 0 || !uri.startsWith(resourcesContextPath)) {
            return false;
        }

        // We map only file that take the form of name.extension
        if (uri.indexOf(".") != -1) {
            uri = uri.substring(resourcesContextPath.length());
        }

        return handle(uri, req, res);
    }

    /**
     * Lookup a resource based on the request URI, and send it using send file.
     *
     * @param uri The request URI
     * @param req the {@link Request}
     * @param res the {@link Response}
     * @throws Exception
     */
    protected boolean handle(final String uri,
            final Request req,
            final Response res) throws Exception {
        
        FileInputStream fis = null;
        try {
            // local file
            File resource = new File(docRoot, uri);

            if (resource.isDirectory()) {
                final File f = new File(resource, "/index.html");
                if (!f.exists()) {
                    resource = null;
                } else {
                    resource = f;
                }
            }

            if (resource == null || !resource.exists()) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, "File not found  " + resource);
                }
                return false;
            }
            res.setStatus(HttpStatus.OK_200);
            String substr;
            int dot = uri.lastIndexOf(".");
            if (dot < 0) {
                substr = resource.toString();
                dot = substr.lastIndexOf(".");
            } else {
                substr = uri;
            }
            if (dot > 0) {
                String ext = substr.substring(dot + 1);
                String ct = MimeType.get(ext);
                if (ct != null) {
                    res.setContentType(ct);
                }
            } else {
                res.setContentType(MimeType.get("html"));
            }

            long length = resource.length();
            res.setContentLengthLong(length);

            addToFileCache(req, resource);
            // Send the header, and flush the bytes as we will now move to use
            // send file.
            res.flush();
            //res.sendHeaders();

            fis = new FileInputStream(resource);
            OutputBuffer outputBuffer = res.getOutputBuffer();

            byte b[] = new byte[8192];
            int rd;
            while ((rd = fis.read(b)) > 0) {
                //chunk.setBytes(b, 0, rd);
                outputBuffer.write(b, 0, rd);
            }

            return true;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    /**
     * Return the directory from where files will be serviced.
     * @return the directory from where file will be serviced.
     */
    public File getDocRoot() {
        return docRoot;
    }

    /**
     * Set the directory from where files will be serviced.
     * @param docRoot the directory from where files will be serviced.
     */
    public void setDocRoot(String docRoot) {
        if (docRoot != null) {
            setDocRoot(new File(docRoot));
        } else {
            setDocRoot((File) null);
        }
    }

    /**
     * Set the directory from where files will be serviced.
     * @param docRoot the directory from where files will be serviced.
     */
    public void setDocRoot(File docRoot) {
        this.docRoot = docRoot;
    }

    /**
     * Return the context path used for servicing resources. By default, "" is
     * used so request taking the form of http://host:port/index.html are serviced
     * directly. If set, the resource will be available under
     * http://host:port/context-path/index.html
     * @return the context path.
     */
    public String getResourcesContextPath() {
        return resourcesContextPath;
    }

    /**
     * Set the context path used for servicing resource. By default, "" is
     * used so request taking the form of http://host:port/index.html are serviced
     * directly. If set, the resource will be available under
     * http://host:port/context-path/index.html
     * @param resourcesContextPath the context path
     */
    public void setResourcesContextPath(String resourcesContextPath) {
        this.resourcesContextPath = resourcesContextPath;
    }

    public final boolean addToFileCache(Request req, File resource) {
        final FilterChainContext fcContext = req.getContext();
        final FileCacheFilter fileCacheFilter = lookupFileCache(fcContext);
        if (fileCacheFilter != null) {
            final FileCache fileCache = fileCacheFilter.getFileCache();
            fileCache.add(req.getRequest(), resource);
            return true;
        }

        return false;
    }

    private FileCacheFilter lookupFileCache(final FilterChainContext fcContext) {
        final FilterChain fc = fcContext.getFilterChain();
        final int lastFileCacheIdx = fileCacheFilterIdx;

        if (lastFileCacheIdx != -1) {
            final Filter filter = fc.get(lastFileCacheIdx);
            if (filter instanceof FileCacheFilter) {
                return (FileCacheFilter) filter;
            }
        }

        final int size = fc.size();
        for (int i = 0; i < size; i++) {
            final Filter filter = fc.get(i);

            if (filter instanceof FileCacheFilter) {
                fileCacheFilterIdx = i;
                return (FileCacheFilter) filter;
            }
        }

        fileCacheFilterIdx = -1;
        return null;
    }
}
