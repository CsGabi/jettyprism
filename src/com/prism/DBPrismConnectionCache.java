/**
 ****************************************************************************
 * Copyright (C) Marcelo F. Ochoa. All rights reserved.                      *
 * ------------------------------------------------------------------------- *
 * This software is published under the terms of the Apache Software License *
 * version 1.1, a copy of which has been included  with this distribution in *
 * the LICENSE file.                                                         *
 */
package com.prism;

//import com.prism.utils.DBPrismConnectionCache;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.Hashtable;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import oracle.jdbc.OracleDriver;
import org.apache.log4j.Logger;

import spinat.jettyprism.Configuration;

/**
 * This interface defines a contract for the ResourceManager DBPrism engine
 * calls these methods to get and release connections for an specific Database
 * Resource Manager. By default there are three Resource Manager implementation:
 * - com.utils.DBPrismConnectionCache which works with JDBC 1.x specification -
 * com.oracle.JTADBPrismConnectionCacheImpl which works with JDBC 2.0 drivers -
 * com.utils.JndiDBPrismConnectionCacheImpl which works with JDBC 2.0 drivers
 * the last two drivers must be implement JDBC 2.0 Standard Extension API
 *
 * @author Marcelo F. Ochoa<BR>
 * <BR>
 * Modified: 17/Jan/2004 by <a href="mailto:pyropunk@usa.net">Alexander
 * Graesser</a> (LXG)<BR>
 * Changes : <UL><LI>JavDoc cleanup</LI></UL>
 */
public class DBPrismConnectionCache implements java.lang.Runnable {

    private static Logger log = Logger.getLogger(DBPrismConnectionCache.class);

    private static int clients = 0;

    private static int totalCount = 0;

    private static int connectionTimeOut;

    private static int minConnections;

    private static int maxConnections;

    //  private static boolean txEnableProcessing;
    private static boolean bContinue = true;

    private static Hashtable FreeList;

    private static Hashtable BusyList;

    private static java.lang.Thread txThread = null;

    private int counter = -1;

    private java.lang.String TxId;

    private DBConnection connection;

    private static DBPrismConnectionCache instance = null;

    /**
     * Singleton
     *
     * @param props Configuration
     * @return DBPrismConnectionCache
     * @throws Exception
     */
    public static synchronized DBPrismConnectionCache getInstance(Configuration props) throws Exception {
        if (instance == null) {
            if (log.isInfoEnabled()) {
                log.info(".getInstance - Initializing Transaction manager...");
            }
            instance = new DBPrismConnectionCache(props);
        }
        clients++;
        return instance;
    }

    /**
     * A private constructor since this is a Singleton
     *
     * @param props Configuration
     * @throws Exception
     */
    private DBPrismConnectionCache(Configuration props) throws Exception {
        init(props);
    }

    /**
     * replace deprecated method for jdk 1.2 Stop the tread by set to false
     * boolean var of while loop in method Run
     */
    public void stopManager() {
        bContinue = false;
    }

    /**
     * A public constructor nothing to do here.
     */
    public DBPrismConnectionCache() {
        // LXG: call to super is generated anyway but put it here for clarity.
        super();
    }

    /**
     * this method inherits from Runnable interface. Check every 1 second for
     * unused connection (in FreeList or BusyList) if the connection is unused
     * for {Manager.timeout} seconds - close it (BusyList) - or remove
     * (FreeList)
     */
    public void run() {
        Enumeration en;
        DBPrismConnectionCache rtmp;
        try {
            //System.out.println("run: Thread started...");
            while (bContinue) {
                if (totalCount >= minConnections) {
                    // Verify if there are unused connections to DB and close it
                    synchronized (DBPrismConnectionCache.class) {
                        en = FreeList.elements();
                        while (en.hasMoreElements()) {
                            rtmp = (DBPrismConnectionCache) en.nextElement();
                            //System.out.println("run: Free "+rtmp.TxId+"
                            // count="+rtmp.counter);
                            rtmp.counter--; // Reduce counter for checking
                            // unused connection
                            if (rtmp.counter < 0) {
                                //System.out.println("run: Remove Connection
                                // from FreeList "+rtmp.TxId);
                                freeJdbcResource(rtmp);
                            }
                        }
                    }
                }
                // Verify if there are unused connection in Busy List or
                // Transaction incompleted
                synchronized (DBPrismConnectionCache.class) {
                    en = BusyList.elements();
                    while (en.hasMoreElements()) {
                        rtmp = (DBPrismConnectionCache) en.nextElement();
                        //System.out.println("run: Busy "+rtmp.TxId+"
                        // count="+rtmp.counter);
                        rtmp.counter--; // Reduce counter for checking age of
                        // Transaction
                        if (rtmp.counter < 0) {
                            //System.out.println("run: Remove Connection from
                            // BusyList "+rtmp.TxId);
                            freeJdbcResource(rtmp);
                        }
                    }
                }
                //System.out.println("run: Wait 1 second...");
                // LXG: changed to static access
                Thread.sleep(1000); // Wait 1 second
            }
            //System.out.println("run: Thread finished...");
        } catch (Exception e) {
            log.warn(".run Can't start the TX Thread or Thread interrupted.", e);
        }
    }

    /**
     * Initialize BusyList (Busy Connection) Initialize FreeList (Free
     * Connection) Set DBPrismConnectionCache parameters. Start a Thread wich
     * control age of connections and transacctions
     *
     * @param props
     * @throws Exception
     */
    public void init(Configuration props) throws Exception {
        synchronized (DBPrismConnectionCache.class) {
            FreeList = new Hashtable();
            BusyList = new Hashtable();
        }

        try {
            minConnections = (new Integer(props.getProperty("minconnections", "0", "Manager"))).intValue();
        } catch (Exception e) {
            minConnections = 0;
        }
        try {
            maxConnections = (new Integer(props.getProperty("maxconnections", "20", "Manager"))).intValue();
        } catch (Exception e) {
            maxConnections = 20;
        }
        try {
            connectionTimeOut = (new Integer(props.getProperty("timeout", "600", "Manager"))).intValue();
        } catch (Exception e) {
            connectionTimeOut = 600;
        }

        OracleDriver d = new OracleDriver();
        DriverManager.registerDriver(d);

        // Create and Start the Thread which control age expiration and
        // transaction
        txThread = new Thread(this);
        txThread.start();
    }

    /**
     * Create a connection to an particular DBConnection this method is a Client
     * in Abstract Factory pattern get a correct Factory from ConnInfo object a
     * call to createDBConnection
     *
     * @param cc_tmp ConnInfo
     * @param usr String
     * @param pass String
     * @throws SQLException
     */
    public DBPrismConnectionCache(ConnInfo cc_tmp, String usr, String pass) throws SQLException {
        if (totalCount >= maxConnections) {
            log.warn(".DBPrismConnectionCache - No more connections available");
            throw new SQLException("No more connections available");
        }
        DBFactory factory = cc_tmp.getFactory();
        connection = factory.createDBConnection(cc_tmp); // Allocated
        // DBConnection
        TxId = connection.toString(); // Store connection id as TxId
        counter = connectionTimeOut; // How much wait for unused connection
        connection.connInfo = cc_tmp; // Set default values for ConnInfo object
        connection.connInfo.status = ConnInfo.CONN_DIR;
        if (log.isDebugEnabled()) {
            log.debug("connectString: " + cc_tmp.connectString);
            log.debug("user: " + usr);
            //log.debug("pass: " + pass );
        }
        connection.sqlconn = DriverManager.getConnection(cc_tmp.connectString, usr, pass);
        connection.sqlconn.setAutoCommit(false);
        if (log.isDebugEnabled()) {
            log.debug("Connect without auto commit " + TxId);
        }
    }

    /**
     * Implements method get from interface DBPrismResource. Get one Connection
     * from BusyList or FreeList
     *
     * @param req HttpServletRequest
     * @param usr String
     * @param pass String
     * @return DBConnection
     * @throws SQLException
     */
    public DBConnection get(String alias, String usr, String pass) throws SQLException {
        if (log.isDebugEnabled()) {
            log.debug(".get entered.");
        }
        DBPrismConnectionCache txtmp;
        txtmp = getConnection(alias, usr, pass);
        if (log.isDebugEnabled()) {
            log.debug(".get Return normal connection");
        }
        return txtmp.connection;
    }

    /**
     * Implements method release from interface DBPrismResource Shutdown
     * DBPrismConnectionCache
     *
     * @throws SQLException
     */
    public void release() throws SQLException {
        Enumeration e;
        if (log.isDebugEnabled()) {
            log.debug(".release client: " + clients);
        }
        stopManager();
        // wait for the thread run finished
        synchronized (DBPrismConnectionCache.class) {
            e = FreeList.elements();
            while (e.hasMoreElements()) {
                DBPrismConnectionCache ctmp = (DBPrismConnectionCache) e.nextElement();
                if (ctmp.connection.sqlconn != null && !ctmp.connection.sqlconn.isClosed()) {
                    ctmp.connection.sqlconn.rollback();
                    ctmp.connection.sqlconn.close();
                }
                if (log.isDebugEnabled()) {
                    log.debug("FreeList all released");
                }
            }
            FreeList.clear();
            e = BusyList.elements();
            while (e.hasMoreElements()) {
                DBPrismConnectionCache ctmp = (DBPrismConnectionCache) e.nextElement();
                if (ctmp.connection.sqlconn != null && !ctmp.connection.sqlconn.isClosed()) {
                    ctmp.connection.sqlconn.rollback();
                    ctmp.connection.sqlconn.close();
                }
                if (log.isDebugEnabled()) {
                    log.debug("BusyList all released");
                }
            }
            BusyList.clear();
        }
        clients--;
    }

    /**
     * Implements method release from interface DBPrismResource Release one
     * Connection
     *
     * @param req HttpServletRequest
     * @param pconnection DBConnection
     * @throws SQLException
     * @throws NullPointerException
     */
    public void release(DBConnection pconnection) throws SQLException, NullPointerException {
        DBPrismConnectionCache rtmp;
        if (log.isDebugEnabled()) {
            log.debug(".release - releasing normal connection from DBPrismConnectionCache");
        }
        synchronized (DBPrismConnectionCache.class) {
            rtmp = (DBPrismConnectionCache) BusyList.get(pconnection.toString());
        }
        freeJdbcResource(rtmp);
    }

    /**
     * Gets one connection, create it, if there isn't
     *
     * @param req HttpServletRequest
     * @param usr String
     * @param pass String
     * @param timeOut int
     * @return DBPrismConnectionCache
     * @throws SQLException
     */
    public DBPrismConnectionCache getConnection(String alias, String usr, String pass)/*, int timeOut*/ throws SQLException {
        DBPrismConnectionCache rtmp;
        ConnInfo cc = new ConnInfo(alias);

        /*
         * Added by Robert E. Parrott (Robert.E.Parrott@Dartmouth.EDU): new
         * connection choice code
         */
        //find connection from FreeList with same uname/password/connAlias
        synchronized (DBPrismConnectionCache.class) {
            rtmp = findMyConnection(usr, pass, cc.connAlias);
            if (rtmp == null) {
                // if null, create new connection
                rtmp = new DBPrismConnectionCache(cc, usr, pass);
                rtmp.connection.connInfo.usr = usr;
                rtmp.connection.connInfo.pass = pass;
                rtmp.connection.connInfo.connectString = cc.connectString;
                totalCount++; // Increase number of connection opened to DB
            } else { //got a matching free connection; test old connection
                //remove old connection from list
                FreeList.remove(rtmp.TxId);
                try {
                    // Detect lost or closed connection
                    PreparedStatement st = rtmp.connection.sqlconn.prepareStatement("select * from dual");
                    st.execute();
                    st.close();
                } catch (SQLException ex) {
                    // if there are errors try to make a new connection
                    // First decrease the number cf connection because this
                    // connection was lost
                    totalCount--;
                    rtmp = new DBPrismConnectionCache(cc, usr, pass);
                    rtmp.connection.connInfo.usr = usr;
                    rtmp.connection.connInfo.pass = pass;
                    rtmp.connection.connInfo.connectString = cc.connectString;
                    totalCount++; // Increase number of connection opened to DB
                }
            }
        }
        /*if (timeOut > 0) {
            // Reclaim TX connection
            rtmp.counter = timeOut;
            rtmp.connection.connInfo.status = ConnInfo.CONN_TX;
            HttpSession ss = req.getSession(true);
            rtmp.TxId = ss.getId();
            if (log.isDebugEnabled())
                log.debug(".getConnection TxEnable TxId:" + rtmp.TxId);
        } else */
        {
            // Reclaim Direct connection
            rtmp.counter = connectionTimeOut;
            rtmp.connection.connInfo.status = ConnInfo.CONN_DIR;
            if (log.isDebugEnabled()) {
                log.debug(".getConnection DirectConn TxId:" + rtmp.TxId);
            }
        }
        synchronized (DBPrismConnectionCache.class) {
            BusyList.put(rtmp.TxId, rtmp);
        }
        return rtmp;
    }

    /**
     * Release one Connection Phisically closes the jdbc connection when the
     * resource is from FreeList Logically closes the jdbc connection when the
     * resource is from BusyList (rollback changes and move to FreeList)
     *
     * @param res DBPrismConnectionCache
     * @throws SQLException
     * @throws NullPointerException
     */
    public void freeJdbcResource(DBPrismConnectionCache res) throws SQLException, NullPointerException {
        //System.out.println("freeJdbcResource totalCount="+totalCount);
        if (res == null) { // if res is null the connection already was released
            // sanity checks.
            log.warn(".freeJdbcResource - Warning: attempt to release a null connection... ");
            return;
        }
        if (res.connection.connInfo.status == ConnInfo.CONN_FREE) {
            // Time expired, end of run methods
            // Free connections to DB
            synchronized (DBPrismConnectionCache.class) {
                FreeList.remove(res.TxId);
                totalCount--;
            }
            // Discard uncommited changes
            try {
                res.connection.sqlconn.rollback();
            } catch (SQLException sqe) {
                // ignore errors because this connection is discarded
                log.warn(".freeJdbcResource (rollback)", sqe);
            } finally {
                res.connection.sqlconn.close();
                res.connection.sqlconn = null;
            }
            if (log.isDebugEnabled()) {
                log.debug(".freeJdbcResource (close)... " + res.TxId + " count= " + totalCount);
            }
        } else {
            // Remove from BusyList and put in FreeList
            if (log.isDebugEnabled()) {
                log.debug(".freeJdbcResource (reset counter)... " + res.TxId);
            }
            // Discard uncommited changes
            try {
                res.connection.sqlconn.rollback();
            } catch (SQLException sqe) {
                // ignore errors because this connection is discarded
                log.warn(".freeJdbcResource (rollback)", sqe);
            }
            synchronized (DBPrismConnectionCache.class) {
                BusyList.remove(res.TxId);
                // Discard uncommited changes
                res.TxId = res.connection.toString();
                res.counter = connectionTimeOut;
                res.connection.connInfo.status = ConnInfo.CONN_FREE;
                res.connection.sqlconn.setAutoCommit(false);
                FreeList.put(res.TxId, res);
            }
        }
    }

    /*
     * ADDED by Robert E. Parrott (Robert.E.Parrott@Dartmouth.EDU) to hopefully
     * include smarter connection choice
     */
    private DBPrismConnectionCache findMyConnection(String usr, String pass, String connAlias) {
        for (Enumeration e = FreeList.elements(); e.hasMoreElements();) {
            DBPrismConnectionCache rtmp = (DBPrismConnectionCache) e.nextElement();
            if (rtmp.connection.connInfo.usr.equals(usr) && rtmp.connection.connInfo.pass.equals(pass) && rtmp.connection.connInfo.connAlias.equals(connAlias)) {
                return rtmp;
            }
        }
        return null;
    }
}
