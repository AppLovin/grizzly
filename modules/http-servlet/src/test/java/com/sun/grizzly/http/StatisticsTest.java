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
package com.sun.grizzly.http;

import com.sun.grizzly.tcp.http11.GrizzlyAdapter;
import com.sun.grizzly.tcp.http11.GrizzlyRequest;
import com.sun.grizzly.tcp.http11.GrizzlyResponse;
import com.sun.grizzly.tcp.RequestGroupInfo;
import com.sun.grizzly.http.embed.Statistics;
import com.sun.grizzly.http.embed.GrizzlyWebServer;

import java.net.HttpURLConnection;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.OutputStream;

/**
 * Statistics gathering test.
 * <p/>
 * Issue: <a href="https://grizzly.dev.java.net/issues/show_bug.cgi?id=565">565</a>
 *
 * @author Hubert Iwaniuk
 */
public class StatisticsTest extends GrizzlyWebServerAbstractTest {

    public static final int PORT = 18890;

    public void testStatisticsGathering() throws IOException {
        try {
            startGrizzlyWebServer(PORT);

            addAdapter("/stats", new StatisticsAdapter(gws));
            addAdapter("/data", new ZerowingAdapter());
            RequestGroupInfo requestStatistics = gws.getStatistics().getRequestStatistics();

            HttpURLConnection conn;
            int count = 0;
            int calls = 0;
            for (int i = 0; i < 10; i++) {
                conn = getConnection("/data", PORT);
                readResponse(conn);
                count += 1024;
                calls += 1;
                assertEquals(calls, requestStatistics.getRequestCount());
                //assertEquals(count, requestStatistics.getBytesSent());

/*
                conn = getConnection("/stats", PORT);
                String s = readResponse(conn);
                System.out.println(s);
                count += s.getBytes().length + 1;
                calls += 1;
                assertEquals(calls, requestStatistics.getRequestCount());
                assertEquals(count, requestStatistics.getBytesSent());
*/
            }
            conn = getConnection("/stats", PORT);
            System.out.println(readResponse(conn));
            conn = getConnection("/stats", PORT);
            System.out.println(readResponse(conn));

        } finally {
            stopGrizzlyWebServer();
        }
    }

    
    class ZerowingAdapter extends GrizzlyAdapter {
        public void service(GrizzlyRequest request, GrizzlyResponse response) {
            try {
                System.out.println("Zerowing");
                OutputStream outputStream = response.getStream();
                outputStream.write(new byte[1024]);
                outputStream.flush();
            } catch (IOException e) {
                response.setStatus(501, e.getMessage());
                e.printStackTrace();
                fail("Zerowing error");
            }
        }
    }

    class StatisticsAdapter extends GrizzlyAdapter {
        final Statistics statistics;

        public StatisticsAdapter(GrizzlyWebServer webserver) {
            statistics = webserver.getStatistics();
            statistics.startGatheringStatistics();
        }

        public void service(GrizzlyRequest request, GrizzlyResponse response) {
            try {
                System.out.println("StatisticsAdapter");
                RequestGroupInfo requestStatistics = statistics.getRequestStatistics();
                PrintWriter out = response.getWriter();
                out.println(requestStatistics.getRequestCount() + ";" +
                        requestStatistics.getBytesReceived() + ";" +
                        requestStatistics.getBytesSent());
                out.flush();
            } catch (IOException e) {
                response.setStatus(501, e.getMessage());
                e.printStackTrace();
                fail("StatisticsAdapter error");
            }
        }
    }

}
