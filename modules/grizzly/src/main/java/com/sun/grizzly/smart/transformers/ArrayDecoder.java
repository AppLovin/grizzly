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

package com.sun.grizzly.smart.transformers;

import java.lang.reflect.Array;
import com.sun.grizzly.Buffer;
import com.sun.grizzly.TransformationException;
import com.sun.grizzly.TransformationResult;
import com.sun.grizzly.TransformationResult.Status;
import com.sun.grizzly.attributes.Attribute;
import com.sun.grizzly.attributes.AttributeStorage;

/**
 *
 * @author oleksiys
 */
public class ArrayDecoder extends SequenceDecoder<Object> {
    protected Attribute<Integer> currentElementIdxAttribute;

    
    public ArrayDecoder() {
        String prefix = ArrayDecoder.class.getName();
        currentElementIdxAttribute = attributeBuilder.createAttribute(
                prefix + ".currentElementIdx");
    }

    @Override
    public TransformationResult<Object> transform(AttributeStorage storage,
            Buffer input, Object output) throws TransformationException {
        
        if (input == null) {
            throw new TransformationException("Input should not be null");
        }

        Object sequence = getSequence(storage);
        if (sequence == null) {
            int size = checkSize(storage);
            sequence = createSequence(storage, size);
        }

        // Optimize for transforming array of bytes
        if (componentType.isPrimitive() && componentType.equals(byte.class)) {
            int currentElementIdx = getValue(storage,
                    currentElementIdxAttribute, 0);

            byte[] byteArray = (byte[]) sequence;
            if (input.remaining() < byteArray.length) {
                saveState(storage, byteArray, currentElementIdx,
                        incompletedResult);
                return incompletedResult;
            }

            input.get(byteArray);
            TransformationResult<Object> result =
                    new TransformationResult<Object>(Status.COMPLETED, sequence);
            saveState(storage, byteArray, byteArray.length, result);
            return result;
        }

        return super.transform(storage, input, output);
    }

    private void saveState(AttributeStorage storage, Object array,
            int currentElementIdx, TransformationResult<Object> lastResult) {
        currentElementIdxAttribute.set(storage, currentElementIdx);
        super.saveState(storage, array, lastResult);
    }

    @Override
    public void release(AttributeStorage storage) {
        currentElementIdxAttribute.remove(storage);
        super.release(storage);
    }

    @Override
    protected Object createSequence(AttributeStorage storage, int size) {
        return Array.newInstance(getComponentType(), size);
    }

    @Override
    protected void set(AttributeStorage storage, Object sequence,
            Object component) {
        int currentElementIdx = getValue(storage, currentElementIdxAttribute, 0);
        Array.set(sequence, currentElementIdx, component);
    }

    @Override
    protected boolean next(AttributeStorage storage, Object sequence) {
        int currentElementIdx = getValue(storage, currentElementIdxAttribute, -1);
        if (currentElementIdx < Array.getLength(sequence)) {
            currentElementIdxAttribute.set(storage, ++currentElementIdx);
            return true;
        }
        
        return false;
    }

    @Override
    protected int size(AttributeStorage storage, Object sequence) {
        return Array.getLength(sequence);
    }
}
