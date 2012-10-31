/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2012 Oracle and/or its affiliates. All rights reserved.
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
package org.glassfish.grizzly.websockets;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.util.DataChunk;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.grizzly.http.util.MimeHeaders;

/**
 * @author Justin Lee
 */
public abstract class HandShake {
    private boolean secure;
    private String origin;
    private String serverHostName;
    private int port = 80;
    private String resourcePath;
    private String location;
    //private final Map<String, String[]> queryParams = new TreeMap<String, String[]>();
    private List<String> subProtocol = new ArrayList<String>();
    private List<Extension> extensions = new ArrayList<Extension>(); // client extensions

    public HandShake(URI url) {
        resourcePath = url.getPath();
        if ("".equals(resourcePath)) {
            resourcePath = "/";
        }
        if (url.getQuery() != null) {
            resourcePath += "?" + url.getQuery();
        }
        serverHostName = url.getHost();
        secure = "wss://".equals(url.getScheme());
        port = url.getPort();
        origin = appendPort(new StringBuilder(url.getHost())).toString();
        buildLocation();
    }

    public HandShake(HttpRequestPacket request) {
        MimeHeaders mimeHeaders = request.getHeaders();
        checkForHeader(request, "Upgrade", "WebSocket");
        checkForHeader(request, "Connection", "Upgrade");
        origin = readHeader(mimeHeaders, WebSocketEngine.SEC_WS_ORIGIN_HEADER);
        if (origin == null) {
            origin = readHeader(mimeHeaders, WebSocketEngine.ORIGIN_HEADER);
        }
        determineHostAndPort(mimeHeaders);
        subProtocol = split(mimeHeaders.getHeader(WebSocketEngine.SEC_WS_PROTOCOL_HEADER));
        if (serverHostName == null) {
            throw new HandshakeException("Missing required headers for WebSocket negotiation");
        }
        resourcePath = request.getRequestURI();
        final String queryString = request.getQueryString();
        if (queryString != null) {
            if (!queryString.isEmpty()) {
                resourcePath += "?" + queryString;
            }
//            Parameters queryParameters = new Parameters();
//            queryParameters.processParameters(queryString);
//            final Set<String> names = queryParameters.getParameterNames();
//            for (String name : names) {
//                queryParams.put(name, queryParameters.getParameterValues(name));
//            }
        }
        buildLocation();
    }

    protected final void buildLocation() {
        StringBuilder builder = new StringBuilder((isSecure() ? "wss" : "ws") + "://" + serverHostName);
        appendPort(builder);
        if (resourcePath == null || !resourcePath.startsWith("/") && !"".equals(resourcePath)) {
            builder.append("/");
        }
        builder.append(resourcePath);
        location = builder.toString();
    }



    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setResourcePath(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    public String getResourcePath() {
        return resourcePath;
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public String getServerHostName() {
        return serverHostName;
    }

    public void setServerHostName(String serverHostName) {
        this.serverHostName = serverHostName;
    }

    public List<String> getSubProtocol() {
        return subProtocol;
    }

    public void setSubProtocol(List<String> subProtocol) {
        this.subProtocol = subProtocol;
    }

    private void sanitize(List<String> strings) {
        if (strings != null) {
            for (int i = 0; i < strings.size(); i++) {
                strings.set(i, strings.get(i) == null ? null : strings.get(i).trim());
            }
        }
    }

    public List<Extension> getExtensions() {
        return extensions;
    }

    public void setExtensions(List<Extension> extensions) {
        this.extensions = extensions;
    }

    protected final String joinExtensions(List<Extension> extensions) {
        StringBuilder sb = new StringBuilder();
        for (Extension e : extensions) {
            if (sb.length() != 0) {
                sb.append(", ");
            }
            sb.append(e.toString());
        }
        return sb.toString();
    }

    protected String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String s : values) {
            if (builder.length() != 0) {
                builder.append(", ");
            }
            builder.append(s);
        }
        return builder.toString();
    }

    private void checkForHeader(HttpHeader headers, String header, String validValue) {
        validate(header, validValue, headers.getHeader(header));
    }

    private void validate(String header, String validValue, String value) {
        boolean found = false;
        if(value.contains(",")) {
            for(String part: value.split(",")) {
                found |= part.trim().equalsIgnoreCase(validValue);
            }
        } else {
            found = value.equalsIgnoreCase(validValue);
        }
        if(!found) {
            throw new HandshakeException(String.format("Invalid %s header returned: '%s'", header, value));
        }
    }

    /**
     * Reads the header value using UTF-8 encoding
     */
    public final String readHeader(MimeHeaders headers, final String name) {
        final DataChunk value = headers.getValue(name);
        return value == null ? null : value.toString();
    }

    private void determineHostAndPort(MimeHeaders headers) {
        String header;
        header = readHeader(headers, "host");
        final int i = header == null ? -1 : header.indexOf(":");
        if (i == -1) {
            setServerHostName(header);
            setPort(80);
        } else {
            setServerHostName(header.substring(0, i));
            setPort(Integer.valueOf(header.substring(i + 1)));
        }
    }

    public HttpContent composeHeaders() {
        String host = getServerHostName();
        if (port != -1 && port != 80 && port != 443) {
            host += ":" + getPort();
        }
        final HttpRequestPacket.Builder builder = HttpRequestPacket.builder()
            .method("GET")
            .uri(getResourcePath())
            .protocol(Protocol.HTTP_1_1)
            .header("Host", host)
            .header("Connection", "Upgrade")
            .upgrade("WebSocket");
        if (!getSubProtocol().isEmpty()) {
            builder.header(WebSocketEngine.SEC_WS_PROTOCOL_HEADER, join(getSubProtocol()));
        }
        return HttpContent.builder(builder.build())
            .build();
    }

    public void validateServerResponse(HttpResponsePacket headers) {
        if (WebSocketEngine.RESPONSE_CODE_VALUE != headers.getStatus()) {
            throw new HandshakeException(String.format("Response code was not %s: %s",
                WebSocketEngine.RESPONSE_CODE_VALUE, headers.getStatus()));
        }
        checkForHeader(headers, WebSocketEngine.UPGRADE, WebSocketEngine.WEBSOCKET);
        checkForHeader(headers, WebSocketEngine.CONNECTION, WebSocketEngine.UPGRADE);
        if (!getSubProtocol().isEmpty()) {
            checkForHeader(headers, WebSocketEngine.SEC_WS_PROTOCOL_HEADER, WebSocketEngine.SEC_WS_PROTOCOL_HEADER);
        }
    }

    public void respond(FilterChainContext ctx, WebSocketApplication application, HttpResponsePacket response) {
        response.setProtocol(Protocol.HTTP_1_1);
        response.setStatus(HttpStatus.SWITCHING_PROTOCOLS_101);
        response.setHeader("Upgrade", "websocket");
        response.setHeader("Connection", "Upgrade");
        setHeaders(response);
        if (!getSubProtocol().isEmpty()) {
            response.setHeader(WebSocketEngine.SEC_WS_PROTOCOL_HEADER,
                join(application.getSupportedProtocols(getSubProtocol())));
        }
        if (!application.getSupportedExtensions().isEmpty() && !getExtensions().isEmpty()) {
            List<Extension> intersection =
                    intersection(getExtensions(),
                                 application.getSupportedExtensions());
            if (!intersection.isEmpty()) {
                application.onExtensionNegotiation(intersection);
                response.setHeader(WebSocketEngine.SEC_WS_EXTENSIONS_HEADER,
                                   joinExtensions(intersection));
            }
        }

        ctx.write(HttpContent.builder(response).build());
    }

    protected abstract void setHeaders(HttpResponsePacket response);

    protected final List<String> split(final String header) {
        if (header == null) {
            return Collections.emptyList();
        } else {
            final List<String> list = Arrays.asList(header.split(","));
            sanitize(list);
            return list;
        }
    }

    protected List<Extension> intersection(List<Extension> requested, List<Extension> supported) {
        List<Extension> intersection = new ArrayList<Extension>(supported.size());
        for (Extension e : requested) {
            for (Extension s : supported) {
                if (e.getName().equals(s.getName())) {
                    intersection.add(e);
                    break;
                }
            }
        }
        return intersection;
    }

    protected final List<Extension> parseExtensionsHeader(final String headerValue) {
        List<Extension> resolved = new ArrayList<Extension>();
        String[] parts = headerValue.split(",");
        for (String part : parts) {
            int idx = part.indexOf(';');
            if (idx < 0) {
                resolved.add(new Extension(part.trim()));
            } else {
                String name = part.substring(0, idx);
                Extension e = new Extension(name.trim());
                resolved.add(e);
                parseParameters(part.substring(idx + 1).trim(), e.getParameters());
            }
        }
        return resolved;
    }

    protected final void parseParameters(String parameterString,
                                         List<Extension.Parameter> parameters) {
        String[] parts = parameterString.split(";");
        for (String part: parts) {
            int idx = part.indexOf('=');
            if (idx < 0) {
                parameters.add(new Extension.Parameter(part.trim(), null));
            } else {
                parameters.add(
                        new Extension.Parameter(part.substring(0, idx).trim(),
                                                part.substring(idx + 1).trim()));
            }
        }
    }

    public void initiate(FilterChainContext ctx) throws IOException {
        ctx.write(composeHeaders());
    }

    private StringBuilder appendPort(StringBuilder builder) {
        if (isSecure()) {
            if (port != 443 && port != -1) {
                builder.append(':').append(port);
            }
        } else {
            if (port != 80 && port != -1) {
                builder.append(':').append(port);
            }
        }
        return builder;
    }
}
