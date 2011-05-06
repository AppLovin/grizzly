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

import com.sun.grizzly.tcp.http11.OutputFilter;
import com.sun.grizzly.tcp.http11.filters.GzipOutputFilter;
import com.sun.grizzly.tcp.http11.filters.LzmaOutputFilter;
import com.sun.grizzly.util.buf.MessageBytes;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provider, which is responsible for collecting compression filters.
 * 
 * @author Alexey Stashok
 */
public class CompressionFiltersProvider {

    private final static CompressionFiltersProvider instance =
            new CompressionFiltersProvider();
    // Compression output filters map (sorted)
    private final TreeMap<Key, OutputFilter> outputFilters;
    // Id counter for filter map keys
    private final AtomicInteger counter = new AtomicInteger();

    private CompressionFiltersProvider() {
        outputFilters = new TreeMap(new Comparator<Key>() {

            public int compare(Key key1, Key key2) {
                return key1.id - key2.id;
            }
        });

        final GzipOutputFilter gzipOutputFilter = new GzipOutputFilter();
        outputFilters.put(new Key(counter.getAndIncrement(), "gzip"), gzipOutputFilter);
        outputFilters.put(new Key(counter.getAndIncrement(), "deflate"), gzipOutputFilter);
        
        registerOutputFilter(new LzmaOutputFilter());
    }

    /**
     * Get <tt>CompressionFiltersProvider</tt> instance.
     * 
     * @return <tt>CompressionFiltersProvider</tt> instance.
     */
    public static CompressionFiltersProvider provider() {
        return instance;
    }

    /**
     * Register compression {@link OutputFilter}.
     * 
     * @param outputFilter {@link OutputFilter}.
     */
    public void registerOutputFilter(OutputFilter outputFilter) {
        outputFilters.put(
                new Key(counter.getAndIncrement(),
                outputFilter.getEncodingName().toString()),
                outputFilter);
    }

    /**
     * Get collection of registered compression {@link OutputFilter}s.
     * 
     * @return collection of registered compression {@link OutputFilter}s.
     */
    public Collection<OutputFilter> getOutputFilters() {
        return outputFilters.values();
    }

    /**
     * Returns <tt>true</tt>, if there is registered {@link OutputFilter}, which
     * support passed encoding, or <tt>false</tt>, if encoding is not supported
     * by any registered filter.
     *
     * @param encoding
     *
     * @return <tt>true</tt>, if there is registered {@link OutputFilter}, which
     * support passed encoding, or <tt>false</tt>, if encoding is not supported
     * by any registered filter.
     */
    public boolean supportsOutput(String encoding) {
        return getOutputFilter(encoding) != null;
    }

    /**
     * Returns <tt>true</tt>, if there is registered {@link OutputFilter}, which
     * support passed encoding, or <tt>false</tt>, if encoding is not supported
     * by any registered filter.
     *
     * @param encoding
     *
     * @return <tt>true</tt>, if there is registered {@link OutputFilter}, which
     * support passed encoding, or <tt>false</tt>, if encoding is not supported
     * by any registered filter.
     */
    public boolean supportsOutput(MessageBytes encoding) {
        return getOutputFilter(encoding) != null;
    }

    /**
     * Get {@link OutputFilter}, which supports passed encoding,
     * or <tt>null</tt>, if encoding is not supported by any registered filter.
     *
     * @param encoding
     *
     * @return {@link OutputFilter}, which supports passed encoding,
     * or <tt>null</tt>, if encoding is not supported by any registered filter.
     */
    public OutputFilter getOutputFilter(String encoding) {
        for (Entry<Key, OutputFilter> entry : outputFilters.entrySet()) {
            if (encoding.indexOf(entry.getKey().encoding) != -1) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * Get {@link OutputFilter}, which supports passed encoding,
     * or <tt>null</tt>, if encoding is not supported by any registered filter.
     *
     * @param encoding
     *
     * @return {@link OutputFilter}, which supports passed encoding,
     * or <tt>null</tt>, if encoding is not supported by any registered filter.
     */
    public OutputFilter getOutputFilter(MessageBytes encoding) {
        for (Entry<Key, OutputFilter> entry : outputFilters.entrySet()) {
            if (encoding.indexOf(entry.getKey().encoding) != -1) {
                return entry.getValue();
            }
        }

        return null;
    }

    // Key element for compression filters map
    private static class Key {

        private final int id;
        private final String encoding;

        public Key(int id, String encoding) {
            this.id = id;
            this.encoding = encoding;
        }

        @Override
        public int hashCode() {
            return id;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final Key other = (Key) obj;
            if (this.id != other.id) {
                return false;
            }
            return true;
        }
    }
}
