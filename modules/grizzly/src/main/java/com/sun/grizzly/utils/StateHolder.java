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

package com.sun.grizzly.utils;

import com.sun.grizzly.utils.conditions.DefaultConditionListener;
import com.sun.grizzly.utils.conditions.ConditionListener;
import com.sun.grizzly.Grizzly;
import com.sun.grizzly.utils.conditions.Condition;
import com.sun.grizzly.utils.conditions.EqualCondition;
import com.sun.grizzly.utils.conditions.NotEqualCondition;
import com.sun.grizzly.utils.conditions.OneTimeConditionListener;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class, which holds the state.
 * Provides API for state change notification, state read/write access locking.
 * 
 * @author Alexey Stashok
 */
public class StateHolder<E> {
    private static Logger _logger = Grizzly.logger;
    
    private volatile E state;
    
    private final ReentrantReadWriteLock readWriteLock;
    private final boolean isLockEnabled;
    
    private Collection<ConditionListener<E, ?>> conditionListeners;
    
    /**
     * Constructs <code>StateHolder</code>.
     * StateHolder will work in not-locking mode.
     */
    public StateHolder() {
        this(false);
    }
    
    /**
     * Constructs <code>StateHolder</code>.
     * @param isLockEnabled locking mode
     */
    public StateHolder(boolean isLockEnabled) {
        readWriteLock = new ReentrantReadWriteLock();
        conditionListeners = new LinkedTransferQueue<ConditionListener<E, ?>>();
        this.isLockEnabled = isLockEnabled;
    }

    /**
     * Constructs <code>StateHolder</code>.
     * @param isLockEnabled locking mode
     */
    public StateHolder(boolean isLockEnabled, E initialState) {
        state = initialState;
        readWriteLock = new ReentrantReadWriteLock();
        conditionListeners = new LinkedTransferQueue<ConditionListener<E, ?>>();
        this.isLockEnabled = isLockEnabled;
    }

    /**
     * Gets current state
     * Current StateHolder locking mode will be used
     * @return state
     */
    public E getState() {
        return getState(isLockEnabled);
    }
    
    /**
     * Gets current state
     * @param locked if true, get will be invoked in locking mode, false - non-locked
     * @return state
     */
    public E getState(boolean locked) {
        if (locked) {
            readWriteLock.readLock().lock();
        }
        
        E retState = state;
        
        if (locked) {
            readWriteLock.readLock().unlock();
        }
        return retState;
    }

    /**
     * Sets current state
     * Current StateHolder locking mode will be used
     * @param state
     */
    public void setState(E state) {
        setState(state, isLockEnabled);
    }
    
    /**
     * Sets current state
     * @param state
     * @param locked if true, set will be invoked in locking mode, false - non-locking
     */
    public void setState(E state, boolean locked) {
        if (locked) {
            readWriteLock.writeLock().lock();
        }
        
        this.state = state;
        
        // downgrading lock to read
        if (locked) {
            readWriteLock.readLock().lock();
            readWriteLock.writeLock().unlock();
        }
        
        notifyConditionListeners(state);

        if (locked) {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * Gets Read/Write locker, which is used by this <code>StateHolder</code>
     * @return locker
     */
    public ReentrantReadWriteLock getStateLocker() {
        return readWriteLock;
    }
    
    /**
     * Gets current locking mode
     * @return true, if mode is set to locking, false otherwise
     */
    public boolean isLockEnabled() {
        return isLockEnabled;
    }
    
    /**
     * Register listener, which will be notified, when state will be equal to passed
     * one. Once listener will be notified - it will be removed from this 
     * <code>StateHolder</code>'s listener set.
     * @param state State, listener is interested in
     * @param notificationObject Object, which will be notified. This <code>StateHolder</code>
     *          implementation works with Runnable, Callable, CountDownLatch, Object
     *          listeners
     * @return <code>ConditionListener</code>, if current state is not equal to required 
     *          and listener was registered, null if current state is equal to required.
     *          In both cases listener will be notified
     */
    public ConditionListener<E, Object> notifyWhenStateIsEqual(E state, Object notificationObject) {
        boolean isLockEnabledLocal = isLockEnabled;
        if (isLockEnabledLocal) {
            getStateLocker().writeLock().lock();
        }
        
        ConditionListener<E, Object> conditionListener = null;

        if (this.state.equals(state)) {
            DefaultConditionListener.notifyListenerObject(notificationObject);
        } else {
            conditionListener = new OneTimeConditionListener<E>();
            conditionListener.set(new EqualCondition<E>(state), notificationObject);
            conditionListeners.add(conditionListener);
        }
        
        if (isLockEnabledLocal) {
            getStateLocker().writeLock().unlock();
        }
        
        return conditionListener;
    }
    
    /**
     * Register listener, which will be notified, when state will become not equal 
     * to passed one. Once listener will be notified - it will be removed from
     * this <code>StateHolder</code>'s listener set.
     * @param state State, listener is interested in
     * @param notificationObject Object, which will be notified. This <code>StateHolder</code>
     *          implementation works with Runnable, Callable, CountDownLatch, Object
     *          listeners
     * @return <code>ConditionListener</code>, if current state is equal to required 
     *          and listener was registered, null if current state is not equal to required.
     *          In both cases listener will be notified
     */
    public ConditionListener<E, Object> notifyWhenStateIsNotEqual(E state, Object notificationObject) {
        boolean isLockEnabledLocal = isLockEnabled;
        if (isLockEnabledLocal) {
            getStateLocker().writeLock().lock();
        }
        
        ConditionListener<E, Object> conditionListener = null;

        if (!this.state.equals(state)) {
            DefaultConditionListener.notifyListenerObject(notificationObject);
        } else {
            conditionListener = new OneTimeConditionListener<E>();
            conditionListener.set(new NotEqualCondition<E>(state), notificationObject);
            conditionListeners.add(conditionListener);
        }
        
        if (isLockEnabledLocal) {
            getStateLocker().writeLock().unlock();
        }
        
        return conditionListener;
    }
    
    /**
     * Register listener, which will be notified, when state will match the condition.
     * Once listener will be notified - it will be removed from this 
     * <code>StateHolder</code>'s listener set.
     * @param condition Condition, the listener is interested in
     * @param notificationObject Object, which will be notified. This <code>StateHolder</code>
     *          implementation works with Runnable, Callable, CountDownLatch, Object
     *          listeners
     * @return <code>ConditionListener</code>, if current state doesn't match the condition
     *          and listener was registered, null if current state matches the condition.
     *          In both cases listener will be notified
     */
    public ConditionListener notifyWhenConditionMatchState(Condition<E> condition, Object notificationObject) {
        boolean isLockEnabledLocal = isLockEnabled;
        if (isLockEnabledLocal) {
            getStateLocker().writeLock().lock();
        }
        
        ConditionListener<E, Object> conditionListener = null;

        if (condition.check(state)) {
            DefaultConditionListener.notifyListenerObject(notificationObject);
        } else {
            conditionListener = new OneTimeConditionListener<E>();
            conditionListener.set(condition, notificationObject);
            conditionListeners.add(conditionListener);
        }
        
        if (isLockEnabledLocal) {
            getStateLocker().writeLock().unlock();
        }
        
        return conditionListener;
    }
    
    /**
     * Add new <code>ConditionListener</code> to listen this <code>StateHolder</code>'s
     * state changes.
     * 
     * @param conditionListener
     */
    public void addConditionListener(ConditionListener<E, ?> conditionListener) {
        boolean isLockEnabledLocal = isLockEnabled;
        if (isLockEnabledLocal) {
            getStateLocker().writeLock().lock();
        }
        
        if (conditionListener.checkConditionAndNotify(state)) {
            if (conditionListener.isKeepAlive()) {
                conditionListeners.add(conditionListener);
            }
        } else {
            conditionListeners.add(conditionListener);
        }
        
        if (isLockEnabledLocal) {
            getStateLocker().writeLock().unlock();
        }
    }
    
    public void removeConditionListener(ConditionListener<E, ?> conditionListener) {
        if (conditionListener == null) return;
        
        conditionListeners.remove(conditionListener);
    }
    
    protected void notifyConditionListeners(E state) {
        Iterator<ConditionListener<E, ?>> it = conditionListeners.iterator();
        while(it.hasNext()) {
            ConditionListener<E, ?> listener = it.next();
            try {
                if (listener.checkConditionAndNotify(state) &&
                        !listener.isKeepAlive()) {
                    it.remove();
                }
            } catch(Exception e) {
                _logger.log(Level.WARNING, "Error calling ConditionListener", e);
            }
        }
    }
}
