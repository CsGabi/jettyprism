/**
 ****************************************************************************
 * Copyright (C) Marcelo F. Ochoa. All rights reserved.                      *
 * ------------------------------------------------------------------------- *
 * This software is published under the terms of the Apache Software License *
 * version 1.1, a copy of which has been included  with this distribution in *
 * the LICENSE file.                                                         *
 * Modified by Charly Schmid (charly.schmid@trivadis.com).                   *
 */

package com.prism.oracle;

import com.prism.CgiVars;
import com.prism.ConnInfo;
import com.prism.Content;
import com.prism.DBConnection;
import com.prism.DBPrism;
import com.prism.DownloadRequest;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.StringTokenizer;
import javax.servlet.http.HttpServletRequest;
import oracle.sql.CLOB;
import org.apache.log4j.Logger;

/**
 * This class implements a ConcreteProduct class of Abstract Factory patterm.
 * Define a product object (DBConnPLSQL represent a connection to this DB)
 * to be created by the corresponding concrete factory. Implements the AbstractProduct Interface (DBConnection).
 * Also, implements a ConcreteClass of TemplateMethod patterm.
 * Implements the primitive operations to carry out subclass-specific steps
 * of the algorithm (DBConnection::doCall & DBConnection::getGeneratedStream) Implements common stuff of Oracle PLSQL support
 * Only getGeneratedStream is implemented by the subclass (8i and 7x)<BR>
 * <BR>
 * Modified: 21/Mar/2005 by <a href="mailto:jhking@airmail.net">Jason King</a> (JHK)<BR>
 * Changes : <UL><LI>Uses SPProcPLSQL not SPProc</LI>
 *           </UL>

 * Modified: 3/Nov/2003 by <a href="mailto:pyropunk@usa.net">Alexander Graesser</a> (LXG)<BR>
 * Changes : <UL><LI>Added log4j logging</LI>
 *           <LI>JavDoc cleanup</LI>
 *           <LI>code cleanup</LI></UL>
 */
public class DBConnPLSQL extends DBConnection {
    Logger log = Logger.getLogger(DBConnPLSQL.class);

    protected java.lang.String toolkitVersion;

    protected java.lang.String nlsLanguage = null;

    protected java.lang.String nlsTerritory = null;

    protected java.lang.String excludeList;

    static final int MAX_PL_LINES = 127;


    public DBConnPLSQL() {
        // LXG: call to super is generated anyway but put it here for clarity.
        super();
    }

    /**
     * Create a concrete DBConnection (DBConnPLSQL). Find extra properties attributes of this connection and return a
     * concrete connection object.
     */
    public DBConnection create(ConnInfo cc) {
        DBConnPLSQL con = new DBConnPLSQL();
        con.connInfo = cc;
        con.toolkitVersion =
            properties.getProperty("toolkit", "4x", "DAD_" + cc.connAlias);
        con.excludeList =
            properties.getProperty("exclusion_list", "sys. owa dbms_ htp.",
                                                 "DAD_" + cc.connAlias);
        String nlsSetting =
            properties.getProperty("nls_lang", null, "DAD_" + cc.connAlias);
        if (nlsSetting != null) {
            try {
                String langSetting =
                    nlsSetting.substring(0, nlsSetting.indexOf("."));
                con.nlsLanguage =
                    langSetting.substring(0, langSetting.indexOf("_"));
                con.nlsTerritory =
                    langSetting.substring(langSetting.indexOf("_") + 1);
            } catch (IndexOutOfBoundsException e) {
                log
                .warn("Incorrect syntax on nls_lang parameter: " + nlsSetting);
            }
        }
        return con;
    }

    /**
     * return response form DB this is done by:
     * 
     * Fetch a block of data from the OWA and return it as a StringBuffer;
     * size of each piece of generated page is 128x256 bytes change this value according to max size of generated page and
     * HTBUF_LEN HTBUF_LEN = 256 (in htp public spec)
     */
    private String getDataBlock() throws SQLException {
        String s_GetPageSql
                = "declare nlns number;\n" + " buf_t varchar2(32767);\n"
                + " lines htp.htbuf_arr;\n" + "begin\n" + "  nlns := ?;\n"
                + "  OWA.GET_PAGE(lines, nlns);\n" + "  if (nlns < 1) then\n"
                + "   buf_t := null;\n" + "  else \n"
                + "   for i in 1..nlns loop\n" + "     buf_t:=buf_t||lines(i);\n"
                + "   end loop;\n" + "  end if;\n" + "  ? := buf_t; ? := nlns;\n"
                + "end;";

        try (CallableStatement cs = sqlconn.prepareCall(s_GetPageSql)) {
           
            cs.setInt(1, MAX_PL_LINES ); // 127*256 = 32768 
            cs.registerOutParameter(2, Types.VARCHAR);
            cs.registerOutParameter(3, Types.BIGINT);
            StringBuilder sb = new StringBuilder();
            while (true) {
                cs.execute();
                String s = cs.getString(2);
                int linecount = cs.getInt(3);
                if (linecount < 1) {
                    break;
                }
                sb.append(s);
                if (linecount < MAX_PL_LINES) {
                    break;
                }
            }
            return sb.toString();
        }
    }

    /**
     * Concrete operation of Template Method pattern.
     * Reset packages state if the connection is statefull
     * */
    public void resetPackages() throws SQLException {
        CallableStatement cs;
        if (log.isDebugEnabled()) {
            log.debug("Reset session");
        }
        cs = sqlconn.prepareCall("BEGIN dbms_session.reset_package; END;");
        cs.execute();
        cs.close();
        //don't wait for garbage collector
        if (log.isDebugEnabled())
            log
            .debug(".resetPackages - 'BEGIN dbms_session.reset_package; END;'");
    }

    /**
     * Concrete opeejration of Template Method pattern.
     * Call to the concrete Stored Procedure in the DB
     *
     * @throws java.sql.SQLException
     * @throws java.io.UnsupportedEncodingException */
    public void doIt(HttpServletRequest req,
                     String servletname) throws SQLException,
                                              UnsupportedEncodingException {
        if (log.isDebugEnabled()) {
            log.debug(".doIt entered.");
        }
      //  int i;
       
        StringTokenizer st = new StringTokenizer(excludeList, " ");
        if (log.isDebugEnabled()) {
            log.debug(".doIt - Servlet Name: '" + servletname + "'");
        }
        // Checks for package that violates exclusion_list parameter
        // Pakckages that start with any of these values are considered with high risk
        while (st.hasMoreElements()) {
            String pkgToExclude = (String)st.nextElement();
            if (servletname.toLowerCase()
                .startsWith(pkgToExclude.toLowerCase())) {
                throw new SQLException("Not Authorized");
            }
        }
        // parse all FORM input parameters and arrays set as PL/SQL arrays
        // Calling with constants - no prepared calls
        // Handling Case Insensitive args in PL/SQL and owa_image.point
        // Eg:
        // http://server:port/servlet/plsql/example.print?a=b
        // http://server:port/servlet/plsql/example.print?A=b
        // make the same call to the procedure example.print('b')
        // PLSQL runtime choose the correct procedure to call
        // Work with overload procedure and in/out parameters to.
        // Eg:
        // http://server:port/servlet/plsql/example.print?A=b
        // http://server:port/servlet/plsql/example.print?A=b&c=d
        // Build procedure call
        StringBuilder command = new StringBuilder(servletname + "(");
        // Main calling command
        StringBuilder decvar = new StringBuilder("DECLARE dummy integer; \n");
        //we will declare array variables here
        StringBuilder setvar = new StringBuilder("BEGIN \n");
        ArrayList<String> params = new ArrayList<>();
        ArrayList<Boolean> isClob = new ArrayList<>();
        //we will set array variables here
        int foundcount = 0;
        SPProcPLSQL plp =
            (SPProcPLSQL)DBPrism.proccache.get(connInfo, servletname, sqlconn);
        //JHK, to use overloaded get
        // Build procedure call parameter by parameter
        Enumeration real_args = req.getParameterNames();
        while (real_args.hasMoreElements()) {
            String name_args = (String)real_args.nextElement();
            String multi_vals[] = req.getParameterValues(name_args);
            if (log.isDebugEnabled()) {
                log.debug("argument: " + name_args + " elements: " + multi_vals.length);
            }
            final String argumentType;
            if (name_args.indexOf(".") > 0) {
                argumentType =
                        plp.get(name_args.substring(0, name_args.indexOf("."))
                                .toLowerCase(), multi_vals.length);
                //JHK
            } else {
                argumentType =
                        plp.get(name_args.toLowerCase(), multi_vals.length);
            }
            //JHK
            if (argumentType == null) {
                log
                .warn("Warning: argument " + name_args + " not in procedure description " +
                         servletname);
                throw new SQLException(servletname +
                                       ": MANY PROCEDURES MATCH NAME, BUT NONE MATCHES SIGNATURE (parameter name '" +
                                       name_args + "')");
            }
            if (log.isDebugEnabled()) {
                log.debug("Arg. name:" + name_args + " found type: " + argumentType);
            }
            if (argumentType.indexOf(".") > 0) {
                // ARRAY variable syntax: owner.type.subtype
                if (name_args.indexOf(".") > 0) {
                    // must be owa_image.point
                    if (name_args.toLowerCase().endsWith(".x")) {
                        // Use only name.x definition and ignore name.y
                        // handle owa_image.point data type
                        name_args =
                                name_args.substring(0, name_args.indexOf("."));
                        decvar.append("dbp$_").append(foundcount)
                                .append(" owa_image.point;\n");
                        String val_x = req.getParameter(name_args + ".x");
                        String val_y = req.getParameter(name_args + ".y");
                        // the owa_image.point data type is a array of varchar index by binary integer
                        // Position 1 is args.x value
                        // Position 2 is args.y value
                        String s =
                                new String(val_x.getBytes(connInfo.clientCharset));
                        s = replace2(s);
                        setvar.append("dbp$_").append(foundcount)
                                .append("(1):='").append(s).append("'; ");
                        if (log.isDebugEnabled()) {
                            log.debug("point " + name_args + ".x=" + val_x);
                        }
                        s = new String(val_y.getBytes(connInfo.clientCharset));
                        s = replace2(s);
                        setvar.append("dbp$_").append(foundcount)
                                .append("(2):='").append(s).append("'; ");
                        if (log.isDebugEnabled()) {
                            log.debug("point " + name_args + ".y=" + val_y);
                        }
                        command.append(name_args).append("=>dbp$_")
                                .append(foundcount).append(",");
                    } else {
                        // Skip .y definition
                        continue;
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log
                        .debug(name_args + " argumentType =" + argumentType);
                    }
                    for (int i = 0; i < multi_vals.length; i++) {
                        String s = multi_vals[i];
                        s = replace2(s);
                        setvar.append("dbp$_").append(foundcount).append("(")
                        .append((i + 1)).append("):='").append(s)
                        .append("'; ");
                    }
                    // end for make array variable
                    command.append(name_args).append("=>dbp$_")
                    .append(foundcount).append(",");
                    // Oracle 10g replace SYS by PUBLIC when object where installed on sys schema and granted to public.
                    // Remove PUBLIC and use short version (package.type) for the argument type.
                    decvar.append("dbp$_").append(foundcount).append(" ");
                    decvar.append((argumentType.startsWith("PUBLIC.") ?
                                                      argumentType.substring(7) :
                                                      argumentType));
                    decvar.append(";\n");
                }
            } else { // rav: no "." in type name
                // otherwise, must be scalar type or cast to scalar
                String s;
                if (name_args.indexOf(".") > 0) {
                    if (name_args.toLowerCase().endsWith(".x")) {
                        // Use only name.x definition and ignore name.y
                        s = req.getParameter(name_args);
                        name_args =
                                name_args.substring(0, name_args.indexOf("."));
                        if (log.isDebugEnabled()) {
                            log.debug("Casting from owa_image.point to varchar2");
                        }
                    } else {
                        // Skip .y definition
                        continue;
                    }
                } else if (multi_vals != null) {
                    s = multi_vals[0];
                    if (log.isDebugEnabled()) {
                        log.debug("Casting from owa_ident.arr to varchar2");
                    }
                } else {
                    s = req.getParameter(name_args);
                    if (log.isDebugEnabled()) {
                        log
                        .debug("single " + name_args + "=" + req.getParameter(name_args));
                    }
                }
                if ("CLOB".equalsIgnoreCase(argumentType)) {
                    params.add(s);
                    isClob.add(true);
                    command.append(name_args).append("=>?,");
                } else {
                    s = replace2(s);
                    int slen = s.length();
                    if (slen > 32767) {
                        throw new SQLException("Argument length of '" +
                                name_args +
                                "' is longer than 32767");
                    }
                    params.add(s);
                    isClob.add(false);
                    command.append(name_args).append("=>?,");
                }
            }
            // end if muti valued args
            foundcount++;
        }
        command =
            new StringBuilder(decvar.toString() + setvar.toString() + command
                                   .toString()
                                   .substring(0, command.length() - 1));
        if (foundcount == 0) {
            command.append("; END;");
        } else {
            command.append("); END;");
        }
        if (log.isInfoEnabled()) {
            log.info(".doIt command:\n" + command);
        }
        // Exec procedure in DB
        CallableStatement cs = null;
        ArrayList clobPassed = new ArrayList();    
        try {
            cs = sqlconn.prepareCall(command.toString());
            for(int i=0;i< params.size();i++) {
                if (isClob.get(i)) {
                     CLOB tmpClob =
                        CLOB.createTemporary(this.sqlconn, false, CLOB
                                                        .DURATION_SESSION);
                     clobPassed.add(tmpClob);
                    try (Writer iow = tmpClob.setCharacterStream(1L)) {
                        iow.write(params.get(i)); //.toCharArray());
                    } catch (IOException ioe) {
                        throw new SQLException("DBConnPLSQL: Failed to write temporary CLOB:\n" +
                                               ioe.getMessage());
                    }
                    cs.setClob(i + 1, tmpClob);
                } else {
                    cs.setString(i+1,params.get(i));
                }
            }
            cs.execute();
        } catch (SQLException e) {
            throw new SQLException("PLSQL Adapter - PLSQL Error\n" +
                                   e.getMessage() + MsgArgumentCallError(req));
        } finally {
            if (cs != null) {
                cs.close();
            }
            for (Object clobPassed1 : clobPassed) {
                CLOB tmpClob = (CLOB) clobPassed1;
                if (tmpClob != null) {
                    CLOB.freeTemporary(tmpClob);
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug(".doIt exited.");
        }
    }

    /** Concrete operation of Template Method pattern. Pass CGI Enviroment to the DB */
    public void setCGIVars(HttpServletRequest req, String name,
                           String pass) throws SQLException {
        CallableStatement cs = null;
        
        StringBuffer command = new StringBuffer("alter session set ");
        if (this.nlsLanguage != null) {
            command.append("NLS_LANGUAGE='").append(this.nlsLanguage).append("' NLS_TERRITORY='").append(this.nlsTerritory).append("' NLS_LENGTH_SEMANTICS=CHAR");
        } else {
            command.append("NLS_LENGTH_SEMANTICS=CHAR");
        }
        try {
          cs = sqlconn.prepareCall(command.toString());
          cs.execute();
          if (log.isDebugEnabled()) {
              log.debug(".setCGIVars - " + command + " for DAD: " +
                      this.connInfo.connAlias + " - Done.");
          }
        } catch (SQLException sqe) {
           log.warn("Warning, can't alter session: ", sqe);
        } finally {
           if (cs != null) {
               cs.close();
           }
           cs = null;
        }
        command =
            new StringBuffer("DECLARE var_val owa.vc_arr;\n");
        ArrayList<String> params = new ArrayList<>();
        command.append("  var_name owa.vc_arr;\n");
        command.append("  dummy_num_vals integer; \nBEGIN ");
        // Get dummy val, force to execute init code of the package
        // if not execute this call the global vars of packages of owa_init
        // and owa_cookie have null vals
        command.append("dummy_num_vals := owa.initialize;\n");
        String hostaddr = req.getRemoteAddr();
        if (log.isDebugEnabled()) {
            log.debug("hostaddr: " + hostaddr);
        }
        // fixme : I do not care about the IP address yet
        //          if working with localhost we might get an IP V6 address.
        if (hostaddr.contains(":")){
            hostaddr = "0.0.0.0";
        }
        StringTokenizer st = new StringTokenizer(hostaddr, ".");
        for (int i = 1; st.hasMoreElements(); i++) {
            command.append("   owa.ip_address("+i+"):=?;\n");
            params.add((String)st.nextElement());
        }
        // Set the owa.cgi_var_val and owa.cgi_var_name used by owa package
        // for example owa.get_service_path use the CGI var SCRIPT_NAME
        command.append("   owa.user_id:=?;\n");
        params.add(name);
        command.append("   owa.password:=?;\n");
        params.add(pass);
        command.append("   owa.hostname:=?;\n");
        
        params.add(req.getRemoteHost());
        if ("3x".equalsIgnoreCase(toolkitVersion)) {
            command.append("   htp.rows_in:=0; htp.rows_out:=0;\n");
        }
        CgiVars env = new CgiVars(req, this.connInfo, name, pass);
        for (int i = 0; i < env.size; i++) {
            command.append("   var_val("+(i + 1)+ "):=?;\n");
            command.append("   var_name("+(i + 1)+"):=?;\n");
            params.add(env.values[i]);
            params.add(env.names[i]);
            
        }
        command.append("   owa.init_cgi_env(").append(env.size);
        command.append(",var_name,var_val);\n ");
        if ("4x".equalsIgnoreCase(toolkitVersion)) {
            command.append("  htp.init;\n");
        }
        // get authorization mode
        command.append("END;");
        log.debug(command.toString());
        cs = sqlconn.prepareCall(command.toString());
        for(int i=0;i< params.size();i++) {
            cs.setString(i+1, params.get(i));
        }
        cs.execute();
        cs.close();
        //don't wait for garbage collector
    }

    /** Concrete operation of Template Method pattern. Return generated page as StringReader */
    public
    // LXG: remove exception UnsupportedEncodingException since it is not thrown
    // public StringReader getGeneratedStream() throws SQLException, UnsupportedEncodingException {
    Content getGeneratedStream(HttpServletRequest req) throws SQLException {
        DownloadRequest download = null;
        Content generatedContent = new Content();
       
        String s= getDataBlock();
       
        generatedContent.setPage(new StringReader(s));
        if (log.isDebugEnabled())
            log.debug("buff=" + s);
        try {
            download =
                this.connInfo.getFactory().createDownloadRequest(req,this);
            if (download!=null && download.isFileDownload())
              generatedContent.setInputStream(download.getStream(download.getDownloadInfo()));
        } catch (IOException e) {
            log.warn(".getGeneratedStream -  error getting the InputStrem in a inline download",e);
        }
        return generatedContent;
    }

    /**
     * Concrete operation of Template Method pattern. authMode specifies whether to enable custom authentication.
     * If specified, the application authenticates users in its own level
     * and not within the database level. This parameter can be set to one of the following values : none (Default)
     * global custom perPackage
     */
    public int doAuthorize(String authMode,
                           String ppackage) throws SQLException {
        int authStatus;
        if (authMode.equalsIgnoreCase("none")) {
            // Authorization none
            // always return true
            authStatus = 1;
        } else {
            CallableStatement cs = null;
            StringBuffer command =
                new StringBuffer("DECLARE\n" + "FUNCTION b2n(b BOOLEAN) RETURN NUMBER IS\n" +
                                                    "BEGIN\n" +
                                                    "IF (b) THEN\n" +
                                                    "  RETURN '1';\n" +
                                                    " END IF;\n" +
                                                    " RETURN '0';\n" +
                                                    " END;\n" + "BEGIN\n");
            if (authMode.equalsIgnoreCase("global"))
                // Authorization global
                // In global authentication all executes are validates by the owa_init.authorize function
                if (toolkitVersion.equalsIgnoreCase("3x"))
                    command.append("? := b2n(owa_init.authorize); END;");
                else
                    command
                    .append("? := b2n(owa_public.owa_custom.authorize); END;");
            else if (authMode.equalsIgnoreCase("custom"))
                if (toolkitVersion.equalsIgnoreCase("3x"))
                    throw new SQLException("DBConnPLSQL: custom authentication is not valid for toolkit 3x");
                else
                    // In custom authentication all executes are validates by the owa_custom.authorize function
                    // in the user schema or in owa_public schema
                    command.append("? := b2n(owa_custom.authorize); END;");
            else
            // Authorization per package
            if (ppackage.equals(""))
                // if anonymous procedure, anonymous function authorize
                command.append("? := b2n(authorize); END;");
            else
                // else call to function authorize on this package
                command.append("? := b2n(").append(ppackage)
                .append(".authorize); END;");
            //System.out.println("cm="+command);
            try {
                cs = sqlconn.prepareCall(command.toString());
                cs.registerOutParameter(1, Types.INTEGER);
                cs.execute();
                // Check if password is valid
                authStatus = cs.getInt(1);
                cs.close();
                //don't wait for garbage collector
            } catch (SQLException e) {
                if (cs != null)
                    cs.close();
                throw new SQLException("DBConnPLSQL: Can't execute authorize function for mode = " +
                                       authMode + "\n\n" + e.getMessage());
            }
        }
        return authStatus;
    }

    /**
     * Concrete operation of Template Method pattern. Return the realms sent back to the browser if the authorization fail
     * This realms is set by calling to owa_sec.set_protection_realm procedure
     */
    public String getRealm() throws SQLException {
        // if the autorization fail get the realm from the package owa_sec
        // The realm is set by the use owa_sec.set_protection_realm
        // A good place for call the owa_sec.set_protection_realm is in the function authorize
        // Ej
        // package body example is
        // function authorize return boolean is
        // begin
        //    owa_sec.set_protection_realm('Sample App.');
        //    if (owa_sec.get_user_id=="marcelo") then
        //        return true;
        //    else
        //        return false;
        //    end if;
        // end authorize;
        // end example;
        CallableStatement cs =
            sqlconn.prepareCall("BEGIN \n ? := owa.protection_realm; \nEND;");
        cs.registerOutParameter(1, Types.VARCHAR);
        cs.execute();
        // Get Protection realm
        String Realm = cs.getString(1);
        cs.close();
        //don't wait for garbage collector
        return Realm;
    }
}