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
package com.sun.grizzly.http;

import com.sun.grizzly.Connection;
import com.sun.grizzly.Grizzly;
import com.sun.grizzly.ThreadCache;
import com.sun.grizzly.http.io.InputBuffer;
import com.sun.grizzly.http.io.RequestInputStream;
import com.sun.grizzly.http.io.RequestReader;
import com.sun.grizzly.http.util.BufferChunk;
import com.sun.grizzly.http.util.MimeHeaders;
import com.sun.grizzly.http.util.Parameters;
import com.sun.grizzly.http.util.RequestURIRef;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@link HttpHeader} object, which represents HTTP request message.
 *
 * @see HttpHeader
 * @see HttpResponse
 * 
 * @author Alexey Stashok
 */
public class HttpRequest extends HttpHeader {

    private static final Logger LOGGER = Grizzly.logger(HttpRequest.class);

    /**
     * Post data buffer.  TODO: Make this configurable
     */
    protected static int CACHED_POST_LEN = 8192;

    private static final ThreadCache.CachedTypeIndex<HttpRequest> CACHE_IDX =
            ThreadCache.obtainIndex(HttpRequest.class, 2);

    public static HttpRequest create() {
        final HttpRequest httpRequest =
                ThreadCache.takeFromCache(CACHE_IDX);
        if (httpRequest != null) {
            return httpRequest;
        }

        return new HttpRequest();
    }

    // ----------------------------------------------------- Instance Variables

    private Connection connection;

    private int serverPort = -1;
    private int remotePort = -1;
    private int localPort = -1;

    private RequestURIRef requestURIRef = new RequestURIRef();

    private Parameters parameters = new Parameters();

    private boolean parametersParsed;
    private boolean secure;
    private boolean secureParsed;
    private boolean usingReader;
    private boolean usingStream;

    private InputStream inputStream;
    private Reader reader;
    private InputBuffer inputBuffer = new InputBuffer();

    private String localHost;

    protected byte[] postData = null;

    private BufferChunk methodBC = BufferChunk.newInstance();
    private BufferChunk queryBC = BufferChunk.newInstance();
    private BufferChunk remoteAddressBC = BufferChunk.newInstance();
    private BufferChunk remoteHostBC = BufferChunk.newInstance();
    private BufferChunk localNameBC = BufferChunk.newInstance();
    private BufferChunk localAddressBC = BufferChunk.newInstance();
    private BufferChunk serverNameBC = BufferChunk.newInstance();

    /**
     * Returns {@link HttpRequest} builder.
     *
     * @return {@link Builder}.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    // ----------------------------------------------------------- Constructors


    protected HttpRequest() {
        methodBC.setString("GET");
    }


    // ---------------------------------------------------------- Public Methods


    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    public Connection getConnection() {
        return connection;
    }

    public InputBuffer getInputBuffer() {
        return inputBuffer;
    }


    // -------------------- Request data --------------------


    /**
     * Get the HTTP request method as {@link BufferChunk}
     * (avoiding creation of a String object). The result format is "GET|POST...".
     *
     * @return the HTTP request method as {@link BufferChunk}
     * (avoiding creation of a String object). The result format is "GET|POST...".
     */
    public BufferChunk getMethodBC() {
        return methodBC;
    }

    /**
     * Get the HTTP request method. The result format is "GET|POST...".
     *
     * @return the HTTP request method. The result format is "GET|POST...".
     */
    public String getMethod() {
        return methodBC.toString();
    }

    /**
     * Set the HTTP request method.
     * @param method the HTTP request method. Format is "GET|POST...".
     */
    public void setMethod(String method) {
        this.methodBC.setString(method);
    }

    /**
     * Returns the request URL of the HTTP request as {@link RequestURIRef}
     * (avoiding creation of a String object).
     * 
     * @return the request URL of the HTTP request as {@link RequestURIRef}
     * (avoiding creation of a String object).
     */
    public RequestURIRef getRequestURIRef() {
        return requestURIRef;
    }

    /**
     * Returns the request URL.
     *
     * @return the request URL.
     */
    public String getRequestURI() {
        return requestURIRef.getURI();
    }

    /**
     * Set the request URL.
     *
     * @param requestURI the request URL.
     */
    public void setRequestURI(String requestURI) {
        this.requestURIRef.setURI(requestURI);
    }

    /**
     * Returns the query string that is contained in the request URL after the
     * path. This method returns null if the URL does not have a query string.
     * The result is represented as {@link BufferChunk} (avoifing creation of a
     * String object).
     * 
     * @return the query string that is contained in the request URL after the
     * path. This method returns null if the URL does not have a query string.
     * The result is represented as {@link BufferChunk} (avoifing creation of a
     * String object).
     */
    public BufferChunk getQueryStringBC() {
        return queryBC;
    }

    /**
     * Returns the query string that is contained in the request URL after the
     * path. This method returns null if the URL does not have a query string.
     *
     * @return the query string that is contained in the request URL after the
     * path. This method returns null if the URL does not have a query string.
     */
    public String getQueryString() {
        return ((queryBC.isNull()) ? null : queryBC.toString());
    }

    /**
     * Set the <code>query</code> portion of the request URI.
     *
     * @param query the query String
     */
    public void setQueryString(String query) {
        queryBC.setString(query);
    }


    /**
     * TODO: Not currently used for anything
     * Return the buffer holding the server name, if
     * any. Use isNull() to check if there is no value
     * set.
     * This is the "virtual host", derived from the
     * Host: header.
     */
    public BufferChunk serverName() {
        return serverNameBC;
    }


    /**
     * @return Returns the integer value of the Internet Protocol (IP) port as
     *  specified in the <code>Host</code> request header.
     */
    public int getServerPort() {
        return serverPort;
    }

    /**
     * Sets the Internet Protocol (IP) port specified in the
     *  <code>Host</code> request header.
     *
     * @param serverPort the port as specified in the <code>Host</code>
     *  request header
     */
    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }

    /**
     * @return the {@link BufferChunk} representing the Internet Protocol (IP)
     *  address of the client or last proxy that sent the request.
     */
    public BufferChunk remoteAddr() {
        if (remoteAddressBC.isNull()) {
            remoteAddressBC
                  .setString(((InetSocketAddress) connection.getPeerAddress())
                        .getAddress().getHostAddress());
        }
        return remoteAddressBC;

    }


    /**
      * @return the Internet Protocol (IP) address of the client or last proxy
     *   that sent the request.
     */
    public String getRemoteAddress() {
        return remoteAddr().toString();
    }


    /**
     * @return a {@link BufferChunk} representing the fully qualified
     *  name of the client or the last proxy that sent the request. If the
     *  engine cannot or chooses not to resolve the hostname (to improve
     *  performance), this method returns the the IP address.
     */
    public BufferChunk remoteHost() {
         if ((remoteHostBC.isNull())) {
            String remoteHost = null;
            InetAddress inetAddr = ((InetSocketAddress) connection
                  .getPeerAddress()).getAddress();
            if (inetAddr != null) {
                remoteHost = inetAddr.getHostName();
            }

            if (remoteHost == null) {
                if (!remoteAddressBC.isNull()) {
                    remoteHost = remoteAddressBC.toString();
                } else { // all we can do is punt
                    remoteHostBC.recycle();
                }
            }
            remoteHostBC.setString(remoteHost);
        }
        return remoteHostBC;
    }


    /**
     * @return a <code>String</code> representing the fully qualified name of
     *  the client or the last proxy that sent the request. If the engine cannot
     *  or chooses not to resolve the hostname (to improve performance), this
     *  method returns the the IP address.
     */
    public String getRemoteHost() {

        return remoteHost().toString();
        
    }


    /**
     * @return a {@link BufferChunk} representing the host name of the
     *  Internet Protocol (IP) interface on which the request was received.
     */
    public BufferChunk localName() {

        if (localNameBC.isNull()) {
            InetAddress inetAddr = ((InetSocketAddress) connection
                  .getLocalAddress()).getAddress();
            localNameBC.setString(inetAddr.getHostName());
        }
        return localNameBC;
        
    }


    /**
     * @return a <code>String</code> representing the host name of the 
     *  Internet Protocol (IP) interface on which the request was received.
     */
    public String getLocalName() {

        return localName().toString();

    }


    /**
     * @return a {@link BufferChunk} representing the Internet Protocol (IP)
     *  address of the interface on which the request was received.
     */
    public BufferChunk localAddr() {
        if (localAddressBC.isNull()) {
            InetAddress inetAddr = ((InetSocketAddress) connection
                  .getLocalAddress()).getAddress();
            localAddressBC.setString(inetAddr.getHostAddress());
        }
        return localAddressBC;
    }


    /**
     * @return a <code>String</code> representing the Internet Protocol (IP)
     *  address of the interface on which the request was received.
     */
    public String getLocalAddress() {

        return localAddr().toString();

    }


    /**
     * @return the Internet Protocol (IP) source port of the client or last
     *  proxy that sent the request.
     */
    public int getRemotePort() {
        if (remotePort == -1) {
            remotePort = ((InetSocketAddress) connection.getPeerAddress()).getPort();
        }
        return remotePort;
    }


    /**
     * Sets the Internet Protocol (IP) source port of the client or last
     * proxy that sent the request.
     *
     * @param port the source port of the client
     */
    public void setRemotePort(int port) {
        this.remotePort = port;
    }


    /**
     * @return the Internet Protocol (IP) port number of the interface on which
     *  the request was received.
     */
    public int getLocalPort() {
        if (localPort == -1) {
            localPort = ((InetSocketAddress) connection.getLocalAddress()).getPort();
        }
        return localPort;
    }


    /**
     * Sets the Internet Protocol (IP) port number of the interface on which
     * the request was received.
     *
     * @param port the port on which the request was received
     */
    public void setLocalPort(int port) {
        this.localPort = port;
    }


    /**
     * TODO Docs
     * @return
     */
    public String getLocalHost() {
        return localHost;
    }


    /**
     * TODO Docs
     * @param host
     */
    public void setLocalHost(String host) {
        this.localHost = host;
    }


    /**
     * TODO Docs
     * @return
     */
    public boolean isSecure() {

        if (!secureParsed) {
            secureParsed = true;
            secure = "https".equals(getProtocol());
        }
        return secure;
        
    }


    /**
     * TODO Docs
     * @return
     */
    public InputStream getInputStream() throws IOException {
        if (usingReader) {
            throw new IllegalStateException();
        }

        if (inputStream == null) {
            inputStream = new RequestInputStream(inputBuffer);
        }
        return inputStream;
    }


    public Reader getReader() throws IOException {
        if (usingStream) {
            throw new IllegalStateException();
        }

        if (reader == null) {
            inputBuffer.processingChars();
            reader = new RequestReader(inputBuffer);
        }
        return reader;
    }


    /**
     * @return a {@link Parameters} instance representing any query parameters
     *  included with the request URI or POST data when the request content
     *  type is <code>application/x-www-form-urlencoded</code>.
     */
    public Parameters getParameters() {
        
        if (!parametersParsed) {
            String charEncodingLocal = getCharacterEncoding();

            // Delay updating requestParametersParsed to TRUE until
            // after getCharacterEncoding() has been called, because
            // getCharacterEncoding() may cause setCharacterEncoding() to be
            // called, and the latter will ignore the specified encoding if
            // requestParametersParsed is TRUE
            parametersParsed = true;
            charEncodingLocal = ((charEncodingLocal == null)
                            ? Constants.DEFAULT_CHARACTER_ENCODING
                            : charEncodingLocal);
            parameters.setQuery(queryBC);
            parameters.setEncoding(charEncodingLocal);
            parameters.setQueryStringEncoding(charEncodingLocal);
            parameters.setHeaders(getHeaders());

            // process the query string
            parameters.handleQueryParameters();

            // processing post if necessary
            if (!usingReader && !usingStream) {
                if ("POST".equalsIgnoreCase(getMethod())) {
                    String contentType = getContentType();
                    if (contentType != null) {
                        int semicolon = contentType.indexOf(';');
                        if (semicolon >= 0) {
                            contentType =
                                    contentType.substring(0, semicolon).trim();
                        } else {
                            contentType = contentType.trim();
                        }
                    }
                    if (("application/x-www-form-urlencoded".equals(contentType))) {
                        long len = getContentLength();
                        if (len > 0) {
                            try {
                                byte[] formData = getPostBody(len);
                                if (formData != null) {
                                    parameters.processParameters(formData,
                                                                 0,
                                                                 (int) len);
                                }
                            } catch (Throwable t) {
                                if (LOGGER.isLoggable(Level.FINE)) {
                                    LOGGER.log(Level.FINE,
                                               "Exception processing POST body.",
                                               t);
                                }
                            }
                        }
                    }
                }
            }
        }
        return parameters;

    }


    // -------------------- Recycling --------------------

    /**
     * {@inheritDoc}
     */
    @Override
    protected void reset() {
        requestURIRef.recycle();
        queryBC.recycle();
        methodBC.recycle();
        remoteAddressBC.recycle();
        remoteHostBC.recycle();
        localAddressBC.recycle();
        localNameBC.recycle();
        serverNameBC.recycle();
        parameters.recycle();
        inputBuffer.recycle();

        remotePort = -1;
        localPort = -1;
        serverPort = -1;

        connection = null;
        localHost = null;
        inputStream = null;
        reader = null;

        secure = false;
        parametersParsed = false;
        secureParsed = false;
        usingReader = false;
        usingStream = false;

        // XXX Do we need such defaults ?
        methodBC.setString("GET");
        protocolBC.setString("HTTP/1.0");

        super.reset();
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void recycle() {
        reset();
        ThreadCache.putToCache(CACHE_IDX, this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean isRequest() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("HttpRequest (\n   method=").append(getMethod())
                .append("\n   url=").append(getRequestURI())
                .append("\n   query=").append(getQueryString())
                .append("\n   protocol=").append(getProtocol())
                .append("\n   content-length=").append(getContentLength())
                .append("\n   headers=[");
        MimeHeaders headersLocal = getHeaders();
        for (Enumeration<String> e = headersLocal.names(); e.hasMoreElements(); ) {
            String n = e.nextElement();
            sb.append("\n      ").append(n).append('=').append(headersLocal.getHeader(n));
        }
        sb.append("]\n)");

        return sb.toString();
    }


    // --------------------------------------------------------- Private Methods


    /**
     * Gets the POST body of this request.
     *
     * @return The POST body of this request
     */
    private byte[] getPostBody(long expectedLen) throws IOException {

        int len = (int) expectedLen;
        byte[] formData;

        if (len < CACHED_POST_LEN) {
            if (postData == null)
                postData = new byte[CACHED_POST_LEN];
            formData = postData;
        } else {
            formData = new byte[len];
        }
        int actualLen = readPostBody(formData, len);
        if (actualLen == len) {
            return formData;
        }

        return null;
    }


    /**
     * Read post body in an array.
     */
    private int readPostBody(byte body[], int len)
    throws IOException {

        int offset = 0;
        do {
            int inputLen = getInputStream().read(body, offset, len - offset);
            if (inputLen <= 0) {
                return offset;
            }
            offset += inputLen;
        } while ((len - offset) > 0);
        return len;

    }


    // ---------------------------------------------------------- Nested Classes


    /**
     * <tt>HttpRequest</tt> message builder.
     */
    public static class Builder extends HttpHeader.Builder<Builder> {
        protected Builder() {
            packet = HttpRequest.create();
        }

        /**
         * Set the HTTP request method.
         * @param method the HTTP request method. Format is "GET|POST...".
         */
        public Builder method(String method) {
            ((HttpRequest) packet).setMethod(method);
            return this;
        }

        /**
         * Set the request URI.
         *
         * @param uri the request URI.
         */
        public Builder uri(String uri) {
            ((HttpRequest) packet).setRequestURI(uri);
            return this;
        }

        /**
         * Set the <code>query</code> portion of the request URI.
         *
         * @param query the query String
         *
         * @return the current <code>Builder</code>
         */
        public Builder query(String query) {
            ((HttpRequest) packet).setQueryString(query);
            return this;
        }

        /**
         * Build the <tt>HttpRequest</tt> message.
         *
         * @return <tt>HttpRequest</tt>
         */
        public final HttpRequest build() {
            return (HttpRequest) packet;
        }
    }
}
