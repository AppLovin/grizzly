/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
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
package org.glassfish.grizzly.spdy.frames;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.memory.MemoryManager;

import java.util.Arrays;

public class SettingsFrame extends SpdyFrame {

    private static final ThreadCache.CachedTypeIndex<SettingsFrame> CACHE_IDX =
                       ThreadCache.obtainIndex(SettingsFrame.class, 8);

    private static final String[] OPTION_TEXT = {
            "UPLOAD_BANDWIDTH",
            "DOWNLOAD_BANDWIDTH",
            "ROUND_TRIP_TIME",
            "MAX_CONCURRENT_STREAMS",
            "CURRENT_CWND",
            "DOWNLOAD_RETRANS_RATE",
            "INITIAL_WINDOW_SIZE",
            "CLIENT_CERTIFICATE_VECTOR_SIZE"
    };

    public static final int TYPE = 4;

    public static final byte FLAG_SETTINGS_CLEAR_SETTINGS = 0x01;

    public static final int MAX_DEFINED_SETTINGS = 8;
    /*
     * Values defined by SETTINGS* are the index in settingsSlots for their
     * respective values.
     *
     * When writing settings to the wire, the values are offset by +1.
     */
    public static final int SETTINGS_UPLOAD_BANDWIDTH = 0;
    public static final int SETTINGS_DOWNLOAD_BANDWIDTH = 1;
    public static final int SETTINGS_ROUND_TRIP_TIME = 2;
    public static final int SETTINGS_MAX_CONCURRENT_STREAMS = 3;
    public static final int SETTINGS_CURRENT_CWND = 4;
    public static final int SETTINGS_DOWNLOAD_RETRANS_RATE = 5;
    public static final int SETTINGS_INITIAL_WINDOW_SIZE = 6;
    public static final int SETTINGS_CLIENT_CERTIFICATE_VECTOR_SIZE = 7;

    private int numberOfSettings;

    private final int[] settingSlots = new int[8];
    private byte setSettings = 0;

    // ------------------------------------------------------------ Constructors


    private SettingsFrame() {
        Arrays.fill(settingSlots, -1);
    }


    // ---------------------------------------------------------- Public Methods


    static SettingsFrame create() {
        SettingsFrame frame = ThreadCache.takeFromCache(CACHE_IDX);
        if (frame == null) {
            frame = new SettingsFrame();
        }
        return frame;
    }

    static SettingsFrame create(final SpdyHeader header) {
        SettingsFrame frame = create();
        frame.initialize(header);
        return frame;
    }

    public static SettingsFrameBuilder builder() {
        return new SettingsFrameBuilder();
    }

    public int getNumberOfSettings() {
        return numberOfSettings;
    }

    public int getSetting(int slotId) {
        return settingSlots[slotId];
    }

    public byte getSetSettings() {
        return setSettings;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("SettingsFrame");
        sb.append("{numberOfSettings=").append(numberOfSettings);
        if (numberOfSettings > 0) {
            int numberOfSettingsLocal = numberOfSettings;
            sb.append(", {");
            for (int i = 0; i < MAX_DEFINED_SETTINGS && numberOfSettingsLocal > 0; i++) {
                if ((setSettings & (1 << i)) != 0) {
                    sb.append(' ');
                    sb.append(OPTION_TEXT[i]).append('=').append(settingSlots[i]);
                    numberOfSettingsLocal--;
                }
            }
        }
        sb.append(" }}");
        return sb.toString();
    }

    // -------------------------------------------------- Methods from Cacheable


    @Override
    public void recycle() {
        numberOfSettings = 0;
        setSettings = 0;
        Arrays.fill(settingSlots, -1);
        super.recycle();
        ThreadCache.putToCache(CACHE_IDX, this);
    }


    // -------------------------------------------------- Methods from SpdyFrame


    @Override
    public Buffer toBuffer(MemoryManager memoryManager) {
        return null;
    }


    // ------------------------------------------------------- Protected Methods

    @Override
    protected void initialize(SpdyHeader header) {
        super.initialize(header);
        numberOfSettings = header.buffer.getInt();
        if (numberOfSettings > 0) {
            final Buffer settings = header.buffer;
            int actualNumberOfSettings = 0;
            for (int i = 0; i < numberOfSettings; i++) {
                final int eHeader = settings.getInt();
                final int eId = (eHeader & 0xFFFFFF) - 1;
                setSettings |= 1 << eId;
                if (settingSlots[eId] == -1) {
                    actualNumberOfSettings++;
                    settingSlots[eId] = settings.getInt();
                }
            }
            numberOfSettings = actualNumberOfSettings; // adjusted for duplicates
        }

    }


    // ---------------------------------------------------------- Nested Classes


    public static class SettingsFrameBuilder extends SpdyFrameBuilder<SettingsFrameBuilder> {

        private SettingsFrame settingsFrame;


        // -------------------------------------------------------- Constructors


        protected SettingsFrameBuilder() {
            super(SettingsFrame.create());
            settingsFrame = (SettingsFrame) frame;
        }


        // ------------------------------------------------------ Public Methods


        public void setting(int slotId, int value) {
            if (settingsFrame.settingSlots[slotId] != -1) {
                settingsFrame.setSettings |= 1 << slotId;
                settingsFrame.settingSlots[slotId] = value;
                settingsFrame.numberOfSettings++;
            }
        }

        public SettingsFrame build() {
            return settingsFrame;
        }

        // --------------------------------------- Methods from SpdyFrameBuilder


        @Override
        protected SettingsFrameBuilder getThis() {
            return this;
        }

    } // END SettingsFrameBuilder

}
