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

package org.glassfish.grizzly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/**
 * {@link ProcessorSelector} implementation, which acts like wrapper for chain
 * of {@link ProcessorSelector}s.
 * So, when {@link ProcessorSelector#select(IOEvent, Connection)} operation is
 * called - it delegates selecting to the first {@link ProcessorSelector} from
 * chain. If first {@link ProcessorSelector} returns not <tt>null</tt>
 * {@link Processor} - {@link ChainProcessorSelector} returns it as result,
 * otherwise next {@link ProcessorSelector} will be taken from chain... etc
 * 
 * @author Alexey Stashok
 */
public class ChainProcessorSelector implements ProcessorSelector,
        List<ProcessorSelector> {

    private List<ProcessorSelector> selectorChain;

    public ChainProcessorSelector() {
        this(new ArrayList<ProcessorSelector>());
    }

    public ChainProcessorSelector(ProcessorSelector... selectorChain) {
        this(new ArrayList(Arrays.asList(selectorChain)));
    }

    public ChainProcessorSelector(List<ProcessorSelector> selectorChain) {
        this.selectorChain = selectorChain;
    }

    /**
     * {@inheritDoc}
     */
    public Processor select(IOEvent ioEvent,
            Connection connection) {
        for(ProcessorSelector processorSelector : selectorChain) {
            Processor processor = processorSelector.select(ioEvent, connection);
            if (processor != null) {
                return processor;
            }
        }

        return null;
    }

    /**
     * {@inheritDoc}
     */
    public int size() {
        return selectorChain.size();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isEmpty() {
        return selectorChain.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    public boolean contains(Object o) {
        return selectorChain.contains(o);
    }

    /**
     * {@inheritDoc}
     */
    public Iterator<ProcessorSelector> iterator() {
        return selectorChain.iterator();
    }

    /**
     * {@inheritDoc}
     */
    public Object[] toArray() {
        return selectorChain.toArray();
    }

    /**
     * {@inheritDoc}
     */
    public <T> T[] toArray(T[] a) {
        return selectorChain.toArray(a);
    }

    /**
     * {@inheritDoc}
     */
    public boolean add(ProcessorSelector o) {
        return selectorChain.add(o);
    }

    /**
     * {@inheritDoc}
     */
    public boolean remove(Object o) {
        return selectorChain.remove(o);
    }

    /**
     * {@inheritDoc}
     */
    public boolean containsAll(Collection<?> c) {
        return selectorChain.containsAll(c);
    }

    /**
     * {@inheritDoc}
     */
    public boolean addAll(Collection<? extends ProcessorSelector> c) {
        return selectorChain.addAll(c);
    }

    /**
     * {@inheritDoc}
     */
    public boolean addAll(int index,
            Collection<? extends ProcessorSelector> c) {
        return selectorChain.addAll(index, c);
    }

    /**
     * {@inheritDoc}
     */
    public boolean removeAll(Collection<?> c) {
        return selectorChain.removeAll(c);
    }

    /**
     * {@inheritDoc}
     */
    public boolean retainAll(Collection<?> c) {
        return selectorChain.retainAll(c);
    }

    /**
     * {@inheritDoc}
     */
    public void clear() {
        selectorChain.clear();
    }

    /**
     * {@inheritDoc}
     */
    public ProcessorSelector get(int index) {
        return selectorChain.get(index);
    }

    /**
     * {@inheritDoc}
     */
    public ProcessorSelector set(int index,
            ProcessorSelector element) {
        return selectorChain.set(index, element);
    }

    /**
     * {@inheritDoc}
     */
    public void add(int index, ProcessorSelector element) {
        selectorChain.add(index, element);
    }

    /**
     * {@inheritDoc}
     */
    public ProcessorSelector remove(int index) {
        return selectorChain.remove(index);
    }

    /**
     * {@inheritDoc}
     */
    public int indexOf(Object o) {
        return selectorChain.indexOf(o);
    }

    /**
     * {@inheritDoc}
     */
    public int lastIndexOf(Object o) {
        return selectorChain.lastIndexOf(o);
    }

    /**
     * {@inheritDoc}
     */
    public ListIterator<ProcessorSelector> listIterator() {
        return selectorChain.listIterator();
    }

    /**
     * {@inheritDoc}
     */
    public ListIterator<ProcessorSelector> listIterator(int index) {
        return selectorChain.listIterator(index);
    }

    /**
     * {@inheritDoc}
     */
    public List<ProcessorSelector> subList(int fromIndex, int toIndex) {
        return selectorChain.subList(fromIndex, toIndex);
    }
}
