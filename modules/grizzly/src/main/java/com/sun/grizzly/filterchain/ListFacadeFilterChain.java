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

package com.sun.grizzly.filterchain;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/**
 * {@link FilterChain} facade, which implements all the {@link List} related
 * methods.
 *
 * @see FilterChain
 * 
 * @author Alexey Stashok
 */
public abstract class ListFacadeFilterChain extends AbstractFilterChain {
    
    /**
     * The list of Filters this chain will invoke.
     */
    protected final List<Filter> filters;

    public ListFacadeFilterChain(final List<Filter> filtersImpl) {
        this.filters = filtersImpl;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public boolean add(Filter filter) {
        return filters.add(filter);
    }
        
    /**
     * {@inheritDoc}
     */
    @Override
    public void add(int index, Filter filter){
        filters.add(index, filter);
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public boolean addAll(Collection<? extends Filter> c) {
        return filters.addAll(c);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean addAll(int index, Collection<? extends Filter> c) {
        return filters.addAll(index, c);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Filter set(final int index, final Filter filter) {
        return filters.set(index, filter);
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public Filter get(int index) {
        return filters.get(index);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int indexOf(final Object object) {
        return filters.indexOf(object);
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public int lastIndexOf(Object filter) {
        return filters.lastIndexOf(filter);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean contains(Object filter) {
        return filters.contains(filter);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsAll(Collection<?> c) {
        return filters.containsAll(c);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object[] toArray() {
        return filters.toArray();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T[] toArray(T[] a) {
        return filters.toArray(a);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean retainAll(Collection<?> c) {
        return filters.retainAll(c);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean remove(Object object) {
        return filters.remove((Filter) object);
    }
           
    /**
     * {@inheritDoc}
     */
    @Override
    public Filter remove(int index) {
        return filters.remove(index);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeAll(Collection<?> c) {
        return filters.removeAll(c);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEmpty() {
        return filters == null || filters.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        return filters.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        filters.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterator<Filter> iterator() {
        return filters.iterator();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ListIterator<Filter> listIterator() {
        return filters.listIterator();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ListIterator<Filter> listIterator(int index) {
        return filters.listIterator(index);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Filter> subList(int fromIndex, int toIndex) {
        return filters.subList(fromIndex, toIndex);
    }
}
