/**
 ****************************************************************************
 * Copyright (C) Marcelo F. Ochoa. All rights reserved. *
 * ------------------------------------------------------------------------- *
 * This software is published under the terms of the Apache Software License *
 * version 1.1, a copy of which has been included with this distribution in *
 * the LICENSE file. *
 */
package com.prism;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.DatatypeConverter;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.pool.OracleDataSource;
import org.apache.log4j.Logger;
import spinat.jettyprism.Configuration;
//import org.jconfig.ConfigurationManager;
//import org.jconfig.handler.InputStreamHandler;
//import org.jconfig.handler.URLHandler;
//import org.jconfig.handler.XMLFileHandler;

/**
 * This class is a Singleton that provides access to one or many connection DB
 * defined in a Property file. A client gets access to the single instance
 * through the static getInstance()<BR>
 * <BR>
 * Modified: 01/Apr/2005 by <a href="mailto:jhking@airmail.net">Jason King</a>
 * (JHK)<BR>
 * Changes : <UL><LI>More debug call-outs/LI></ul>
 *
 *
 * Modified: 18/Mar/2005 by <a href="mailto:jhking@airmail.net">Jason King</a>
 * (JHK)<BR>
 * Changes : <UL><LI>Made configuration name a static</LI></ul>
 *
 * Modified: 3/Nov/2003 by <a href="mailto:pyropunk@usa.net">Alexander
 * Graesser</a> (LXG)<BR>
 * Changes : <UL><LI>Added log4j logging</LI>
 * <LI>JavDoc cleanup</LI>
 * <LI>code cleanup</LI></UL>
 *
 */
public class DBPrism {

    private static Logger log = Logger.getLogger(DBPrism.class);
    public static java.lang.String NAME = "DBPrism";
    public static java.lang.String VERSION = "2.1.2.2-production";
    private static java.lang.String CONFIGURATION = "prism.xconf";  // JHK this string should only appear once in this file.
    public static java.lang.String PROPERTIES = "/" + CONFIGURATION;
    public static java.lang.String CONTENTTYPE = "text/html";
    public static java.lang.String UnauthorizedText;
    public static int maxUploadSize = 8192 * 1024;
    public static int BEHAVIOR = 0;
    public static DBPrismConnectionCacheProxy cache = null;
    public static DBProcedure proccache = null;
    private static boolean cachep = true;
    private Configuration properties = null;

    /**
     * private connection which hold the connection betwen makePage and getPage
     * steps
     */
    //private DBConnection connection = null;

    /**
     * Makes a page from Request If the request has not user/pass information
     * and the connection is with dymanic login throw NotAuthorizedException. If
     * it is not possible to establish the connection throw
     * NotAuthorizedException. If there are errors in the page generation,
     * according to the kind of errors the responsability is forwarded to the
     * wrappers If there aren't errors the connection is not free, this
     * connection will be free in getPage step
     *
     * @param req HttpServletRequest
     * @throws Exception
     */
    public Content makePage(HttpServletRequest req, ConnInfo cc_tmp) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(".makePage entered.");
        }
        DBConnection connection = null;
        String name;
        String password;
        try {
            int i;
            String str;
            try {
                log.debug("Auto " + req.getHeader("Authorization"));
                String s = req.getHeader("Authorization").substring(6);
                byte[] bytes = DatatypeConverter.parseBase64Binary(s);
                str = new String(bytes, cc_tmp.clientCharset);
                log.debug("ist: " + str);
            } catch (Exception e) {
                str = ":";
            }
            i = str.indexOf(':');
            if (i != -1) {
                name = str.substring(0, i);
                password = str.substring(i + 1);
            } else {
                name = "";
                password = "";
            }
            boolean dLogin = "".equals(cc_tmp.usr);
            if (!dLogin) {
                // if DAD username is not null, log to database using DAD username and password 
                connection =  this.createDBConnection(cc_tmp, cc_tmp.usr, cc_tmp.pass);
                if (log.isDebugEnabled()) {
                    log.debug("Using a " + connection.getClass().getName() + " class");  // JHK
                }        // Copy DAD username and password from DAD info
                if ("".equalsIgnoreCase(name)) {
                    name = cc_tmp.usr;
                }
                if ("".equalsIgnoreCase(password)) {
                    password = cc_tmp.pass;
                }
            } else if ("".equals(name) || "".equals(password)) {
                // if DAD username is null, and no user/pass is given into the B64 string 
                throw new NotAuthorizedException(cc_tmp.dynamicLoginRealm);
            } else {
                try { // DAD username is null, try to connect using B64 user/pass values
                    connection =this.createDBConnection(cc_tmp, name, password);
                    if (log.isDebugEnabled()) {
                        log.debug("Using a " + connection.getClass().getName() + " class");  // JHK
                    }
                } catch (SQLException e) {
                    if (e.getErrorCode() == 1017) {
                        //ORA-01017: invalid username/password; logon denied. 
                        throw new NotAuthorizedException(cc_tmp.dynamicLoginRealm);
                    }
                    log.error("connect failed", e);
                    throw e;
                }
            }
            connection.doCall(req, name, password);
            Content pg = connection.getGeneratedStream(req);
            if (log.isDebugEnabled()) {
                log.debug(".makePage doCall success on " + connection);
            }
            return pg;
        } catch (Exception e) {
            // try to free the connection
            log.error(".makePage exception: " + connection, e);
            // throw the exception as is
            throw e;
        } finally {
            recycle(req, connection);
        }
    }

    /**
     * Returns DB Prism version info
     *
     * @return String
     */
    public String getVersion() {
        return NAME + VERSION + " (C) Marcelo F. Ochoa (2000-2008)";
    }

    /**
     * Makes a download from Request If the request has no user/pass information
     * and the connection is with dynamic login throw NotAuthorizedException. If
     * it is not possible to establish the connection throw
     * NotAuthorizedException. If there is error in the page generation,
     * according to the kind of errors the responsibility is forwarded to the
     * wrappers This step free the connection, different from makePage which
     * free the connection in getPage step
     *
     * @param req
     * @param res
     * @throws Exception
     */
    public void downloadDocumentFromDB(HttpServletRequest req, HttpServletResponse res, ConnInfo cc_tmp ) throws Exception {
        String name;
        String password;
        DBConnection connection = null;
        try {
            int i;
            String str;
            try {

                String s = req.getHeader("Authorization").substring(6);
                byte[] bytes = DatatypeConverter.parseBase64Binary(s);
                str = new String(bytes, cc_tmp.clientCharset);
            } catch (Exception e) {
                str = ":";
            }
            i = str.indexOf(':');
            if (i != -1) {
                name = str.substring(0, i);
                password = str.substring(i + 1);
            } else {
                name = "";
                password = "";
            }
            boolean dLogin = "".equals(cc_tmp.usr);
            if (!dLogin) {
                // if DAD username is not null, log to database using DAD username and password 
                connection = cache.get(cc_tmp.connAlias, cc_tmp.usr, cc_tmp.pass);
                if (log.isDebugEnabled()) {
                    log.debug("Using a " + connection.getClass().getName() + " class");  // JHK
                }        // Copy DAD username and password from DAD info
                if ("".equalsIgnoreCase(name)) {
                    name = cc_tmp.usr;
                }
                if ("".equalsIgnoreCase(password)) {
                    password = cc_tmp.pass;
                }
            } else if ("".equals(name) || "".equals(password)) {
                // if DAD username is null, and no user/pass is given into the B64 string 
                throw new NotAuthorizedException(cc_tmp.dynamicLoginRealm);
            } else {
                try { // DAD username is null, try to connect using B64 user/pass values
                    connection = cache.get(cc_tmp.connAlias, name, password);
                } catch (SQLException e) {
                    throw new NotAuthorizedException(cc_tmp.dynamicLoginRealm);
                }
            }
            connection.doDownload(req, res, name, password);
            if (log.isDebugEnabled()) {
                log.debug("DBPrism: doDownload success on " + connection);
            }
        } finally {
            recycle(req, connection);
        }
    }


    /**
     * A public constructor to manage multiple connections
     */
    public DBPrism() {
        if (log.isDebugEnabled()) {
            log.debug("DBPrism()");
        }
    }

    /**
     * A public constructor to manage multiple connections
     *
     * @param req
     * @throws SQLException
     */
    private static void recycle(HttpServletRequest req, DBConnection connection ) throws SQLException {
        // try to free the connection
        if (connection != null) {
            connection.releasePage();
            cache.release(req, connection);
        }
    }

    /**
     * Initialize the singleton instance and the ResourceManger that is another
     * singleton
     *
     * @param filename
     * @throws IOException
     */
    public void init(String filename) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug(".init entered.");
        }
        String propFileName = filename;
        if (log.isDebugEnabled()) {
            log.debug(".init about to load properties: " + propFileName);
        }
        //  ConfigurationManager cm = ConfigurationManager.getInstance();
        try {
            // LXG: changed to static access
            properties = Configuration.loadFromPropertiesFile(filename);    //ConfigurationManager.getConfiguration("prism.xconf");
            System.out.println(properties.toString());
        } catch (Exception e) {
            log.error(".init Could not load properties file: " + propFileName, e);
            // LXG: use the correct file name when throwing the error
            throw new IOException("Can't load properties file '" + propFileName + "'\n Make sure properties file in CLASSPATH" + "\n or give 'properties' argument in Servlet Config");
        }
        // Set global DB Prism variables
        //LANG = properties.getProperty("lang");
        //COUNTRY = properties.getProperty("country");
        
        CONTENTTYPE = properties.getProperty("contenttype", "text/html");
        UnauthorizedText = properties.getProperty("UnauthorizedText", "You must be enter DB username and password to access at the system");
        BEHAVIOR = properties.getIntProperty("behavior", 0);
        maxUploadSize = properties.getIntProperty("maxUploadSize", 8388608);
        cachep = properties.getBooleanProperty("cacheprocedure", true);
        proccache = new DBProcedure(cachep);
        try {
            cache = DBPrismConnectionCacheProxy.getInstance(properties);
        } catch (Exception e) {
            log.error("Initialization of the DBPrismConnectionCacheProxy failed due to: " + e.getMessage(),e);
        }
        if (log.isDebugEnabled()) {
            log.debug(".init exited.");
        }
    }

    /**
     * Free all resources
     *
     * @throws Exception
     */
    public void release() throws Exception {
        if (log.isDebugEnabled()) {
            log.debug(".release entered.");
        }
        if (cache != null) {
            cache.release();
            cache = null;
        }
        if (log.isDebugEnabled()) {
            log.debug(".release DBPrism shutdown complete.");
        }
    }
    
     private HashMap<String, OracleDataSource> dss = new HashMap<>();

    DBConnection createDBConnection(ConnInfo ci, String user, String pw) throws SQLException {
        if (dss.containsKey(ci.connAlias)) {
            OracleConnection con = (OracleConnection) dss.get(ci.connAlias).getConnection(user, pw);
            con.setAutoCommit(false);
            return new DBConnection(this.properties, ci, con);
        }
        OracleDataSource ds = new OracleDataSource();
        ds.setURL(ci.connectString);
        dss.put(ci.connAlias,ds);
        return this.createDBConnection(ci, user, pw);
    }
}
