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

import org.glassfish.grizzly.attributes.AttributeHolder;
import org.glassfish.grizzly.util.AttributeStorage;

/**
 * <tt>Transformer</tt> interface, which knows how to transform the original
 * data to some custom representation.
 * A <tt>Transformer</tt> implementation could be stateful or stateless. However
 * it's very easy to write stateful <tt>Transformer</tt>, which actaully doesn't
 * save any state internally, but uses {@link AttributeStorage} as an external
 * state storage. Please note, that {@link AttributeStorage} is being passed
 * as the parameter to all <tt>Transformer</tt> methods. This way it's
 * possible to reuse single instance of a stateful <tt>Transformer</tt> to
 * process lots of concurrent transformations.
 *
 * @author Alexey Stashok
 */
public interface Transformer<K, L> {
    /**
     * Transforms an input data to some custom representation.
     * Input and output are not passed implicitly, which means that
     * <tt>Transformer</tt> is able to retrieve input and output from its
     * internal state or from external storage ({@link AttributeStorage}).
     * 
     * @param storage the external state storage, where <tt>Transformer</tt> could
     *        get/put a state.
     * @return the result {@link TransformationResult}
     * 
     * @throws org.glassfish.grizzly.TransformationException
     */
    public TransformationResult<L> transform(AttributeStorage storage)
            throws TransformationException;

    /**
     * Transforms an input data to some custom representation.
     *
     * @param storage the external state storage, where <tt>Transformer</tt>
     *        could retrieve or store its state.
     * @return the result {@link TransformationResult}
     *
     * @throws org.glassfish.grizzly.TransformationException
     */
    public TransformationResult<L> transform(AttributeStorage storage,
            K input, L output) throws TransformationException;

    /**
     * Gets the input data, which <tt>Transformer</tt> will work with.
     * Very often <tt>Transformer</tt>s are used together with I/O operations.
     * When <tt>Transformer</tt> is used with read operation, this method may
     * return a {@link Buffer}, where "reader" will read the data to. But note,
     * the returned Buffer should always represent READY data.In other words,
     * if there is no data to be transformed - the Buffer should have 0
     * remaining bytes.
     * 
     * @param storage the external state storage, where <tt>Transformer</tt>
     *        could retrieve or store its state.
     * @return the <tt>Transformer</tt> input.
     */
    public K getInput(AttributeStorage storage);

    /**
     * Sets the input data, which <tt>Transformer</tt> will work with.
     * Very often <tt>Transformer</tt>s are used together with I/O operations.
     * When <tt>Transformer</tt> is used with read operation, the input
     * {@link Buffer} will be used by "reader" to read the data to. But note,
     * the input Buffer should always represent READY data.In other words,
     * if there is no data to be transformed - the Buffer should have 0
     * remaining bytes.
     *
     * @param input Input data
     * @param storage the external state storage, where <tt>Transformer</tt>
     *        could retrieve or store its state.
     */
    public void setInput(AttributeStorage storage, K input);

    /**
     * Gets the output, which <tt>Transformer</tt> will use for transformed data.
     * Although output could be used to get a result of transformation, it is
     * recommended to return transformation result directly from transform
     * method within {@link TransformationResult}.
     *
     * @param storage the external state storage, where <tt>Transformer</tt>
     *        could retrieve or store its state.
     * @return the <tt>Transformer</tt> output.
     */
    public L getOutput(AttributeStorage storage);

    /**
     * Gets the output, which <tt>Transformer</tt> will use for transformed data.
     * Although output could be used to get a result of transformation, it is
     * recommended to return transformation result directly from transform
     * method within {@link TransformationResult}.
     *
     * @param output Output
     * @param storage the external state storage, where <tt>Transformer</tt>
     *        could retrieve or store its state.
     */
    public void setOutput(AttributeStorage storage, L output);

    /**
     * Gets the last returned <tt>Transformer</tt> result.
     * Last result could be either retrieved from internal state, or external
     * storage, which is passed as the parameter.
     * 
     * @param storage the external state storage, where <tt>Transformer</tt>
     *        could retrieve or store its state.
     * @return the last returned <tt>Transformer</tt> result.
     */
    public TransformationResult<L> getLastResult(AttributeStorage storage);

    /**
     * Gets the property storage, using which it's possible to read or
     * update <tt>Transformer</tt> properties.
     *
     * @param storage the external state storage, where <tt>Transformer</tt>
     *        could retrieve or store its state.
     * @return the property storage.
     */
    public AttributeHolder getProperties(AttributeStorage storage);

    /**
     * Method could be called by framework to let <tt>Transformer</tt> know,
     * that transformation will be postponed and probably continued in separate
     * <tt>Thread</tt>, so all resources, which are associated with the current
     * <tt>Thread</tt> should be detached and stored internally or in the
     * external storage.
     *
     * @param storage the external state storage, where <tt>Transformer</tt>
     *        could retrieve or store its state.
     */
    public void hibernate(AttributeStorage storage);

    /**
     * The <tt>Transformer</tt> has done its work and can release all
     * associated resource.
     *
     * @param storage the external state storage, where <tt>Transformer</tt>
     *        could retrieve or store its state.
     */
    public void release(AttributeStorage storage);
}
