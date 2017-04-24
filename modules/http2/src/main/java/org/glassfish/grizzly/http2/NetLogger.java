/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2015-2017 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http2;


import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.http2.frames.DataFrame;
import org.glassfish.grizzly.http2.frames.GoAwayFrame;
import org.glassfish.grizzly.http2.frames.HeadersFrame;
import org.glassfish.grizzly.http2.frames.Http2Frame;
import org.glassfish.grizzly.http2.frames.PingFrame;
import org.glassfish.grizzly.http2.frames.PriorityFrame;
import org.glassfish.grizzly.http2.frames.PushPromiseFrame;
import org.glassfish.grizzly.http2.frames.RstStreamFrame;
import org.glassfish.grizzly.http2.frames.SettingsFrame;
import org.glassfish.grizzly.http2.frames.WindowUpdateFrame;

import java.util.logging.Level;
import java.util.logging.Logger;

final class NetLogger {

    private static final Logger LOGGER = Grizzly.logger(NetLogger.class);
    private static final Level LEVEL = Level.FINE;

    private static final String CLOSE_FMT           = "'{' \"session\":\"{0}\", \"event\":\"CLOSE\" '}'";
    private static final String DATA                = "DATA";
    private static final String DATA_FMT            = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"stream\":\"{2}\", \"fin\":\"{3}\", \"len\":\"{4}\" '}'";
    private static final String GOAWAY              = "GOAWAY";
    private static final String GOAWAY_FMT          = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"stream\":\"{2}\", \"last-stream\":\"{3}\", \"error-code\":\"{4}\", \"detail\":\"{5}\" '}'";
    private static final String HEADERS             = "HEADERS";
    private static final String HEADERS_FMT         = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"stream\":\"{2}\", \"parent-stream\":\"{3}\", \"prioritized\":\"{4}\", \"exclusive\":\"{5}\", \"weight\":\"{6}\", \"fin\":\"{7}\", \"len\":\"{8}\" '}'";
    private static final String PING                = "PING";
    private static final String PING_FMT            = "'{' session=\"{0}\", event=\"{1}\", is-ack=\"{2}\", opaque-data=\"{3}\" '}'";
    private static final String PRIORITY            = "PRIORITY";
    private static final String PRIORITY_FMT        = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"stream\":\"{2}\", \"parent-stream\":\"{3}\", \"exclusive\":\"{4}\", \"weight\":\"{5}\" '}'";
    private static final String PUSH_PROMISE        = "PUSH_PROMISE";
    private static final String PUSH_PROMISE_FMT    = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"stream\":\"{2}\", \"promised-stream\":\"{3}\", \"len\":\"{4}\" '}'";
    private static final String RST                 = "RST";
    private static final String RST_FMT             = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"stream\":\"{2}\", \"error-code\":\"{3}\" '}'";
    private static final String SETTINGS            = "SETTINGS";
    private static final String SETTINGS_FMT        = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"settings\":'{'{2}'}' '}'";
    private static final String WINDOW_UPDATE       = "WINDOW_UPDATE";
    private static final String WINDOW_UPDATE_FMT   = "'{' \"session\":\"{0}\", \"event\":\"{1}\", \"delta\":\"{2}\" '}'";

    private static final String NOT_AVAILABLE = "None Available";


    enum Context {
        TX("SEND_"),
        RX("RECV_");

        final String prefix;
        Context(final String prefix) {
            this.prefix = prefix;
        }

        String getPrefix() {
            return prefix;
        }
    }

    static void log(final Context ctx, final Http2Connection c, final Http2Frame frame) {
        switch (frame.getType()) {
            case DataFrame.TYPE:
                log(ctx, c, (DataFrame) frame);
                break;
            case GoAwayFrame.TYPE:
                log(ctx, c, (GoAwayFrame) frame);
                break;
            case HeadersFrame.TYPE:
                log(ctx, c, (HeadersFrame) frame);
                break;
            case PingFrame.TYPE:
                log(ctx, c, (PingFrame) frame);
                break;
            case PriorityFrame.TYPE:
                log(ctx, c, (PriorityFrame) frame);
                break;
            case PushPromiseFrame.TYPE:
                log(ctx, c, (PushPromiseFrame) frame);
                break;
            case RstStreamFrame.TYPE:
                log(ctx, c, (RstStreamFrame) frame);
                break;
            case SettingsFrame.TYPE:
                log(ctx, c, (SettingsFrame) frame);
                break;
            case WindowUpdateFrame.TYPE:
                log(ctx, c, (WindowUpdateFrame) frame);
                break;
            default:
                LOGGER.warning("Unable to log frame of type: " + frame.getClass().getName());

        }
    }

    static void log(final Context ctx, final Http2Connection c, final DataFrame frame) {
        validateParams(ctx, c, frame);
        if (LOGGER.isLoggable(LEVEL)) {
            LOGGER.log(LEVEL, DATA_FMT, new Object[]{
                    escape(c.getConnection().toString()),
                    ctx.getPrefix() + DATA,
                    frame.getStreamId(),
                    frame.isEndStream(),
                    frame.getData().remaining()});
        }
    }

    static void log(final Context ctx, final Http2Connection c, final GoAwayFrame frame) {
        validateParams(ctx, c, frame);
        if (LOGGER.isLoggable(LEVEL)) {
            final Buffer b = frame.getAdditionalDebugData();
            final String details = ((b != null) ? b.toStringContent() : NOT_AVAILABLE);
            LOGGER.log(LEVEL, GOAWAY_FMT, new Object[]{
                    escape(c.getConnection().toString()),
                    ctx.getPrefix() + GOAWAY,
                    frame.getStreamId(),
                    frame.getLastStreamId(),
                    frame.getErrorCode().getCode(),
                    escape(details)});
        }
    }

    static void log(final Context ctx, final Http2Connection c, final HeadersFrame frame) {
        validateParams(ctx, c, frame);
        if (LOGGER.isLoggable(LEVEL)) {
            LOGGER.log(LEVEL, HEADERS_FMT, new Object[]{
                    escape(c.getConnection().toString()),
                    ctx.getPrefix() + HEADERS,
                    frame.getStreamId(),
                    frame.getStreamDependency(),
                    frame.isPrioritized(),
                    frame.isExclusive(),
                    frame.getWeight(),
                    frame.isEndStream(),
                    frame.getCompressedHeaders().remaining()});
        }
    }

    static void log(final Context ctx, final Http2Connection c, final PingFrame frame) {
        validateParams(ctx, c, frame);
        if (LOGGER.isLoggable(LEVEL)) {
            LOGGER.log(LEVEL, PING_FMT, new Object[]{
                    escape(c.getConnection().toString()),
                    ctx.getPrefix() + PING,
                    frame.isAckSet(),
                    frame.getOpaqueData()});
        }
    }

    static void log(final Context ctx, final Http2Connection c, final PriorityFrame frame) {
        validateParams(ctx, c, frame);
        if (LOGGER.isLoggable(LEVEL)) {
            LOGGER.log(LEVEL, PRIORITY_FMT, new Object[]{
                    escape(c.getConnection().toString()),
                    ctx.getPrefix() + PRIORITY,
                    frame.getStreamId(),
                    frame.getStreamDependency(),
                    frame.isExclusive(),
                    frame.getWeight()});
        }
    }

    static void log(final Context ctx, final Http2Connection c, final PushPromiseFrame frame) {
        validateParams(ctx, c, frame);
        if (LOGGER.isLoggable(LEVEL)) {
            LOGGER.log(LEVEL, PUSH_PROMISE_FMT, new Object[]{
                    escape(c.getConnection().toString()),
                    ctx.getPrefix() + PUSH_PROMISE,
                    frame.getStreamId(),
                    frame.getPromisedStreamId(),
                    frame.getCompressedHeaders().remaining()});
        }
    }

    static void log(final Context ctx, final Http2Connection c, final RstStreamFrame frame) {
        validateParams(ctx, c, frame);
        if (LOGGER.isLoggable(LEVEL)) {
            LOGGER.log(LEVEL, RST_FMT, new Object[]{
                    escape(c.getConnection().toString()),
                    ctx.getPrefix() + RST,
                    frame.getStreamId(),
                    frame.getErrorCode().getCode()});
        }
    }

    static void log(final Context ctx, final Http2Connection c, final SettingsFrame frame) {
        validateParams(ctx, c, frame);
        if (LOGGER.isLoggable(LEVEL)) {
            final int numSettings = frame.getNumberOfSettings();
            final StringBuilder sb = new StringBuilder();
            if (numSettings > 0) {
                for (int i = 0; i < numSettings; i++) {
                    final SettingsFrame.Setting setting = frame.getSettingByIndex(i);
                    sb.append('"').append(frame.getSettingNameById(setting.getId())).append('"');
                    sb.append(": ");
                    sb.append('"').append(setting.getValue()).append('"');
                    if (i + 1 < numSettings) {
                        sb.append(", ");
                    }
                }
            }
            LOGGER.log(LEVEL, SETTINGS_FMT, new Object[]{
                    escape(c.getConnection().toString()),
                    ctx.getPrefix() + SETTINGS,
                    sb.toString()});
        }
    }

    static void log(final Context ctx, final Http2Connection c, final WindowUpdateFrame frame) {
        validateParams(ctx, c, frame);
        if (LOGGER.isLoggable(LEVEL)) {
            LOGGER.log(LEVEL, WINDOW_UPDATE_FMT, new Object[]{
                    escape(c.getConnection().toString()),
                    ctx.getPrefix() + WINDOW_UPDATE,
                    frame.getWindowSizeIncrement()});
        }
    }

    static void logClose(final Http2Connection c) {
        if (c == null) {
            throw new NullPointerException("Http2Connection cannot be null");
        }
        if (LOGGER.isLoggable(LEVEL)) {
            LOGGER.log(LEVEL, CLOSE_FMT, new Object[]{escape(c.getConnection().toString())});
        }
    }


    // --------------------------------------------------------- Private Methods


    private static void validateParams(final Context ctx,
                                       final Http2Connection c,
                                       final Http2Frame frame) {
        if (ctx == null) {
            throw new NullPointerException("Context cannot be null.");
        }
        if (c == null) {
            throw new NullPointerException("Http2Connection cannot be null.");
        }
        if (frame == null) {
            throw new NullPointerException("Http2Frame cannot be null.");
        }
    }

    private static String escape(final String s) {
        final StringBuilder sb = new StringBuilder(s.length() + 20);
        for (int i = 0, len = s.length(); i < len; i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '\'':
                    sb.append("\'");
                    break;
                case '"' :
                    sb.append("\"");
                    break;
                case '\\':
                    sb.append("\\");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

}
