/**
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER. *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved. *
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
package com.sun.grizzly.http.servlet.deployer;

import com.sun.grizzly.arp.AsyncHandler;
import com.sun.grizzly.arp.DefaultAsyncHandler;
import com.sun.grizzly.comet.CometAsyncFilter;
import com.sun.grizzly.http.SelectorThread;
import com.sun.grizzly.http.embed.GrizzlyWebServer;
import com.sun.grizzly.http.embed.GrizzlyWebServer.PROTOCOL;
import com.sun.grizzly.http.servlet.deployer.comparator.WarFileComparator;
import com.sun.grizzly.http.servlet.deployer.conf.ConfigurationParser;
import com.sun.grizzly.http.servlet.deployer.conf.DeployerConfiguration;
import com.sun.grizzly.http.webxml.WebappLoader;
import com.sun.grizzly.http.webxml.schema.*;
import com.sun.grizzly.util.ExpandJar;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * We have 4 cases : 
 * 
 * #1 - war
 * #2 - web.xml
 * #3 - folder that contains at least one war
 * #4 - folder of a deployed war (will use the /WEB-INF/web.xml)
 * 
 * if #3 find war, it will deployed it, if not, will try #4 if it found nothing, #2
 *
 * @author Sebastien Dionne
 * @author Hubert Iwaniuk
 */
public class GrizzlyWebServerDeployer {

    private static Logger logger = Logger.getLogger("GrizzlyWebServerDeployerLogger");

    private static final String ROOT = "/";

    private static final String WEB_XML = "web.xml";
    public static final String WEB_XML_PATH = "WEB-INF" + File.separator + WEB_XML;

    private GrizzlyWebServer ws = null;

    private String webxmlPath;

    /**
     * @param args Command line parameters.
     */
    public static void main(String[] args) {
        new GrizzlyWebServerDeployer().launch(init(args));
    }

    public static DeployerConfiguration init(String args[]) {
        DeployerConfiguration cfg = ConfigurationParser.parseOptions(args, GrizzlyWebServerDeployer.class.getCanonicalName());
        if (logger.isLoggable(Level.INFO)) {
            logger.log(Level.INFO, cfg.toString());
        }
        return cfg;
    }

    public void launch(DeployerConfiguration conf) {
        try {
            ws = new GrizzlyWebServer(conf.port);
            URLClassLoader serverLibLoader = createServerLibClassLoader(conf.libraryPath);
            configureApplications(conf, serverLibLoader);
            configureServer(conf);
            // don't start the server is true: useful for unittest
            if (!conf.waitToStart) {
                ws.start();
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error while launching deployer.", e);
        }
    }

    private void configureApplications(DeployerConfiguration conf, URLClassLoader serverLibLoader) throws Exception {
        String locations = conf.locations;
        if (locations != null) {
            if (logger.isLoggable(Level.FINEST)) {
                logger.log(Level.FINEST, "Application(s) Found = " + locations);
            }
            deployApplications(conf, serverLibLoader);
        }
    }

    public void deployApplications(final DeployerConfiguration conf, URLClassLoader serverLibLoader) throws Exception {
        if (conf.locations != null && conf.locations.length() > 0) {
            final WebApp webDefault = getDefaultSupportWebApp(conf.webdefault);
            for (String loc : conf.locations.split(File.pathSeparator)) {
                deployApplication(conf, loc, serverLibLoader, webDefault);
            }
        }
    }

    private void deployApplication(final DeployerConfiguration conf, String location, URLClassLoader serverLibLoader, WebApp webDefault) throws Exception {
        if (location.endsWith(".war")) {// #1
            deployWar(location, conf.forcedContext, serverLibLoader, webDefault);
        } else if (location.endsWith(".xml")) {// #2
            // use the forcedContext if set
            deployServlet(location, conf.forcedContext, serverLibLoader, webDefault);
        } else {

            // #3-#4
            //obtain the list of potential war to deploy
            Collection<File> files = getFiles(location, conf.forceWarDeployment);

            if (files != null) {
                for (File file : files) {

                    if (file.getName().endsWith(".war")) {
                        deployWar(file.getPath(), null, serverLibLoader, webDefault);
                    } else {
                        /*
                        * we could have these cases
                        *
                        * folder contains multiple expanded war or servlet
                        *
                        * classes/
                        * jmaki-comet/
                        * jmaki-comet2.war
                        * web.xml
                        *
                        * In this case, we have 1 web.xml (servlet), 1 expanded war and 1 war file
                        *
                        * The 3 of them will be loaded.
                        */

                        // #4 : this folder in a expanded war
                        if (isWebXmlInWebInf(location)) {
                            deployExpandedWar(String.format("%s%s", location, File.separator), serverLibLoader, webDefault);
                        } else {

                            // #2 : this folder contains a servlet
                            File webxmlFile2 = new File(
                                    String.format("%s%s%s", location, File.separator, WEB_XML));

                            if (webxmlFile2.exists()) {
                                // this one..see #2
                                deployServlet(webxmlFile2.getPath(), serverLibLoader, webDefault);
                            } else {

                                // this folder contains multiple war or webapps
                                File webapp = new File(
                                        String.format(
                                                "%s%s%s", file.getPath(), File.separator, WEB_XML_PATH));

                                if (webapp.exists()) {
                                    deployExpandedWar(
                                            String.format("%s%s", file.getPath(), File.separator), serverLibLoader, webDefault);
                                } else {
                                    // not a webapp with web.xml, maybe a php application
                                    deployCustom(String.format("%s%s", file.getPath(), File.separator), serverLibLoader, webDefault);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean isWebXmlInWebInf(final String location) {
        return new File(String.format("%s%s%s", location, File.separator, WEB_XML_PATH)).exists();
    }

    /**
     * Deploy WAR file.
     *
     * TODO make {@link GrizzlyWebServer} a parameter here for easier embadability.
     *
     * @param location Location of WAR file.
     * @param context Context to deploy to.
     * @param serverLibLoader Server wide {@link ClassLoader}. Optional.
     * @param defaultWebApp webdefault application, get's merged with application to deploy. Optional.
     * @throws Exception Duh. TODO refactor Exception handling.
     */
    public void deployWar(
            String location, String context, URLClassLoader serverLibLoader, WebApp defaultWebApp) throws Exception {

        final Map.Entry<String, URLClassLoader> loaderEntry =
                explodeAndCreateWebAppClassLoader(location, serverLibLoader);
        webxmlPath = loaderEntry.getKey();
        String ctx = context;
        if (ctx == null) {
            ctx = getContext(webxmlPath);
        }
        deploy(webxmlPath, ctx, webxmlPath + WEB_XML_PATH, loaderEntry.getValue(), defaultWebApp);
    }

    private URLClassLoader createServerLibClassLoader(String libraryPath) throws IOException {
        // Must be a better way because that sucks!
        String separator = (System.getProperty("os.name").toLowerCase().startsWith("win") ? ROOT : "//");

        List<URL> urls = new ArrayList<URL>();

        if (libraryPath != null) {

            // look if we have multiple folder
            String[] libPaths = libraryPath.split(File.pathSeparator);

            if (libPaths != null && libPaths.length > 0) {

                for (String libPath : libPaths) {
                    File libFolder = new File(libPath);

                    if (libFolder.exists() && libFolder.isDirectory()) {
                        for (File file : libFolder.listFiles(new ExtensionFileNameFilter(Arrays.asList(".jar")))) {
                            urls.add(new URL(
                                    String.format(
                                            "jar:file:%s%s!/", separator,
                                            file.getCanonicalPath().replace('\\', '/'))));
                        }
                    }
                }
            }
        }

        if (logger.isLoggable(Level.FINEST)) {
            logger.log(Level.FINEST, String.format("Server library path contains=%s", urls));
        }

        return new URLClassLoader(urls.toArray(new URL[urls.size()]));
    }

    private void deployServlet(String location, URLClassLoader serverLibLoader, WebApp defaultWebApp) throws Exception {
        deployServlet(location, null, serverLibLoader, defaultWebApp);
    }

    private void deployServlet(String location, String context, URLClassLoader serverLibLoader, WebApp defaultWebApp)
            throws Exception {
        String ctx = context;
        if (ctx == null) {
            ctx = getContext(ROOT);
        }
        final Map.Entry<String, URLClassLoader> loaderEntry = explodeAndCreateWebAppClassLoader(null, serverLibLoader);
        webxmlPath = loaderEntry.getKey();
        deploy(null, ctx, location, loaderEntry.getValue(), defaultWebApp);
    }

    private void deployExpandedWar(String location, URLClassLoader serverLibLoader, WebApp defaultWebApp) throws Exception {
        final Map.Entry<String, URLClassLoader> loaderEntry = explodeAndCreateWebAppClassLoader(location, serverLibLoader);
        webxmlPath = loaderEntry.getKey();
        deploy(webxmlPath, getContext(webxmlPath), webxmlPath + WEB_XML_PATH, loaderEntry.getValue(), defaultWebApp);
    }

    private static Collection<File> getFiles(String location, boolean forceWarDeployment) {
        Map<String, File> result = null;
        File folder = getFileNotTrailingSlash(location);
        if (folder.exists() && folder.isDirectory()) {

            // we only want folders that contains WEB-INF or war files
            File files[] = folder.listFiles(new DeployableFilter());

            // do we have something to deploy
            if (files != null && files.length != 0) {

                // sort list.  We want expanded folder first followed by war file.
                Arrays.sort(files, new WarFileComparator());


                // filter the list.
                result = new HashMap<String, File>(files.length);
                for (File file : files) {

                    // add folders
                    final String filename = file.getName();
                    if (file.isDirectory()) {
                        result.put(filename, file);
                    } else if (filename.endsWith(".war") && !forceWarDeployment) {
                        String name = filename.substring(0, filename.length() - ".war".length());
                        if (result.containsKey(name)) {
                            logger.log(Level.INFO, "War file skipped");
                        } else {
                            result.put(name, file);
                        }
                    } else if (filename.endsWith(".war") && forceWarDeployment) {
                        String name = filename.substring(0, filename.length() - ".war".length());
                        // we must remove the folder from the list if found
                        if (result.containsKey(name)) {
                            result.remove(name);
                        }
                        result.put(name, file);
                    } else {
                        result.put(filename, file);
                    }
                }
            }
        }
        return result == null ? null : result.values();
    }

    private static File getFileNotTrailingSlash(String name) {
        return new File(removeTrailingPathSeparator(name));
    }

    /**
     * Return the context that will be used to deploy the application
     *
     * @param path : file path where the application is
     * @return the context
     */
    public static String getContext(String path) {
        String result;

        if (path == null || path.trim().length() == 0) {
            result = ROOT;
        } else {
            result = removeTrailingPathSeparator(fixPath(path));
            int lastIndex = result.lastIndexOf(ROOT);

            if (lastIndex > 0) {
                result = ROOT + result.substring(lastIndex + 1);
            } else if (lastIndex == -1) {
                // need to add the ROOT
                result = ROOT + result;
            }
        }
        return result;
    }

    private static String fixPath(String path) {
        return path
                .replaceAll("[/\\\\]+", '\\' + ROOT)
                .replaceAll("\\\\", '\\' + ROOT);
    }

    private static String removeTrailingPathSeparator(String path) {
        String result = path;
        if (result.endsWith(ROOT) && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String getRootFolder(String location, String context) {
        String result;
        if (location == null || context == null) {
            result = location;
        } else {
            result = fixPath(location);
            int index = result.lastIndexOf(context);
            if (index > -1) {
                result = result.substring(0, index);
            }
        }
        return result;
    }

    protected void deployCustom(String location, URLClassLoader serverLibLoader, WebApp defaultSupportWebApp) throws Exception {
        final Map.Entry<String, URLClassLoader> loaderEntry = explodeAndCreateWebAppClassLoader(location, serverLibLoader);
        webxmlPath = loaderEntry.getKey();

        String context = getContext(webxmlPath);
        String root = getRootFolder(location, context);

        deploy(root, context, root + context, loaderEntry.getValue(), defaultSupportWebApp);
    }

    public void deploy(
            String rootFolder, String context, String path, URLClassLoader webAppCL, WebApp superApp) throws Exception {

        String root = rootFolder;
        if (rootFolder != null) {
            root = fixPath(rootFolder);
        }

        if (logger.isLoggable(Level.INFO)) {
            logger.log(Level.INFO, "Will deploy application path=" + path);
        }

        if (path != null) {
            WebApp webApp;
            if (path.toLowerCase().endsWith(".xml")) {
                webApp = extractWebXmlInfo(path);
            } else {
                webApp = new WebApp(); // empty web app - we might be dealing here with PHP
            }
            try {
                final WebAppAdapter webAppAdapter =
                        new WebAppAdapter(ws, root, context, webApp, webAppCL, superApp);
                ws.addGrizzlyAdapter(webAppAdapter, new String[]{context});
            } catch (Exception e) {
                logger.log(Level.INFO, "Not a valid WebApp, will be ignored : path=" + path);
                logger.log(Level.INFO, "Error follows.", e);
            }
        }
    }

    /**
     * Make available the content of a War file to the current Thread Context
     * ClassLoader.  This function as to be executed before the start() because
     * the new classpath won't take effect.
     *
     * TODO This potentially can be replaced by {@link com.sun.grizzly.util.ClassLoaderUtil#createClassloader(java.io.File, ClassLoader)}
     * @param appliPath
     * @param serverLibLoader
     * @return the exploded war file location and web app CL.
     * @throws java.io.IOException
     */
    public static Map.Entry<String, URLClassLoader> explodeAndCreateWebAppClassLoader(
            String appliPath, URLClassLoader serverLibLoader) throws IOException {

        if (appliPath != null && appliPath.endsWith(File.pathSeparator)) {
            appliPath += File.pathSeparator;
        }

        // Must be a better way because that sucks!
        String separator = (System.getProperty("os.name").toLowerCase().startsWith("win") ? ROOT : "//");

        List<URL> classpathList = new ArrayList<URL>();

        String path = null;
        URL appRoot = null;
        URL classesURL = null;
        if (appliPath != null && (appliPath.endsWith(".war") || appliPath.endsWith(".jar"))) {
            File file = new File(appliPath);
            appRoot = new URL("jar:file:" + file.getCanonicalPath() + "!/");
            classesURL = new URL("jar:file:" + file.getCanonicalPath() + "!/WEB-INF/classes/");
            path = ExpandJar.expand(appRoot);
        } else if (appliPath != null) {
            path = appliPath;
            classesURL = new URL("file:///" + path + "WEB-INF/classes/");
            appRoot = new URL("file:///" + path);
        }

        if (appliPath != null) {
            File libFiles = new File(new File(path).getAbsolutePath() + File.separator + "WEB-INF" + File.separator + "lib");

            if (libFiles.exists() && libFiles.isDirectory()) {
                for (File file : libFiles.listFiles()) {
                    classpathList.add(
                            new URL(String.format(
                                    "jar:file:%s%s!/", separator,
                                    file.toString().replace('\\', '/'))));
                }
            }
        }

        if (appliPath != null) {
            classpathList.add(appRoot);
            classpathList.add(classesURL);
        }

        if (logger.isLoggable(Level.FINEST)) {
            logger.log(Level.FINEST, String.format("Classpath contains=%s", classpathList));
        }

        //be sure to that the path ends by File.separator
        if (path != null && !path.endsWith(File.separator)) {
            path += File.separator;
        }

        // Linking with serverLibCL
        return new AbstractMap.SimpleImmutableEntry<String, URLClassLoader>(
                path, new URLClassLoader(classpathList.toArray(new URL[classpathList.size()]), serverLibLoader));
    }

    private static WebApp extractWebXmlInfo(String webxml) throws Exception {
        return webxml == null ? null : WebappLoader.load(webxml);
    }

    private void configureServer(DeployerConfiguration conf) {
        // comet
        if (conf.cometEnabled) {
            SelectorThread st = ws.getSelectorThread();

            AsyncHandler asyncHandler = new DefaultAsyncHandler();
            asyncHandler.addAsyncFilter(new CometAsyncFilter());
            st.setAsyncHandler(asyncHandler);

            st.setEnableAsyncExecution(true);
        }

        if (conf.ajpEnabled) {
            ws.enableProtocol(PROTOCOL.AJP);
        }
    }

    public void stop() {

        if (ws != null) {
            ws.stop();
        }

    }

    public void start() throws IOException {

        if (ws != null) {
            ws.start();
        }

    }

    /**
     * @param webdefault location of webdefault (directory or file).
     * @return {@link WebApp} with merged webdefaults if many.
     * @throws IllegalArgumentException If provided webdefault doesn't exist or is empty directory.
     * @throws Exception If failed to parse web.xml.
     */
    private static WebApp getDefaultSupportWebApp(String webdefault) throws Exception {
        WebApp result = new WebApp();
        // TODO fields of WebApp are not getting initialized when constructed

        if (webdefault != null) {

            File webdefaultFile = getFileNotTrailingSlash(webdefault);

            if (webdefaultFile.exists()) {
                if (webdefaultFile.isDirectory()) {
                    File xmlFiles[] = webdefaultFile.listFiles(new ExtensionFileNameFilter(Arrays.asList(".xml")));
                    if (xmlFiles != null && xmlFiles.length != 0) {
                        for (File xmlFile : xmlFiles) {
                            result = extractWebAppAndMerge(result, xmlFile.getPath());
                        }

                    } else {
                        // no .xml files in webdefault directory.
                        throw new IllegalArgumentException("Webdefault is empty directory.");

                    }
                } else {
                    // exists && is file
                    result = extractWebAppAndMerge(result, webdefaultFile.getPath());

                }
            } else {
                // provided parameter but location is not existent
                throw new IllegalArgumentException("Webdefault location is not existent.");

            }
        }
        return result;
    }

    private static WebApp extractWebAppAndMerge(WebApp mergeTo, String webxmlLocation) throws Exception {
        WebApp webApp = extractWebXmlInfo(webxmlLocation);
        if (webApp == null) {
            throw new Exception("Invalid webdefault: " + webxmlLocation);
        }
        return mergeTo.mergeWith(webApp);
    }

    private static class DeployableFilter implements FilenameFilter {
        public boolean accept(File dir, String name) {
            boolean result;
            if (name.endsWith(".war")) {
                result = true;
            } else {

                // check if it's a expanded folder
                // and it doesn't need to contains a web.xml
                // a php application could be deployed
                File file = new File(dir + File.separator + name);

                result = (file.exists() && file.isDirectory());
            }
            return result;
        }
    }

    private static class ExtensionFileNameFilter implements FilenameFilter {
        private List<String> extensions;

        public ExtensionFileNameFilter(List<String> extensions) {
            this.extensions = Collections.unmodifiableList(extensions);
        }

        public boolean accept(File dir, String name) {
            boolean result = false;
            for (String extension : extensions) {
                if (name.endsWith(extension)) {
                    result = true;
                    break;
                }
            }
            return result;
        }
    }
}
