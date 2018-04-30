/**
 ****************************************************************************
 * Copyright (C) Marcelo F. Ochoa. All rights reserved. *
 * ------------------------------------------------------------------------- *
 * This software is published under the terms of the Apache Software License *
 * version 1.1, a copy of which has been included with this distribution in *
 * the LICENSE file. * Modified: 3/Nov/2003 by
 * <a href="mailto:jhking@airmail.net">Jason King</a> (JHK)<BR>
 * Changes : <UL><LI>Added logger reference</LI>
 * <LI>Converted System.out.printlns to log.debugs</LI>
 * <LI>Made Hashtable procedures protected, not private</LI>
 * <LI>Made Hashtable arguments protected, not private</LI>
 * </ul>
 * <BR>
 *
 */
package com.prism;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.log4j.Logger;

public class SPProc {

    private Logger log = Logger.getLogger(SPProc.class); //JHK

    protected HashMap<String, HashMap> procedures = new HashMap<>();
    protected HashMap<String, ArrayList> arguments = new HashMap<>();

    /**
     * create a new entry for this procedure definition every procedure
     * definition is knew by the overload index value
     */
    public void addProcedure(String index) {
        procedures.put(index, new HashMap());
        arguments.put(index, new ArrayList());
    }

    /**
     * add a new argument name for this procedure definition for every
     * overloaded procedure entry there are entrys for each argument name
     */
    public void add(String overloadIndex, String argumentName, String argumentType) {
        HashMap procedure = procedures.get(overloadIndex);
        ArrayList args = arguments.get(overloadIndex);
        procedure.put(argumentName, argumentType);
        args.add(argumentName);
        if (log.isDebugEnabled()) //JHK
        {
            log.debug("into : " + overloadIndex + " arg_name: " + argumentName + " inserted with type: " + argumentType);
        }
    }

    /**
     * return the type name for this argument name
     */
    public String get(String argumentName) {
        int i = 1;
        String type;
        if (log.isDebugEnabled()) //JHK
        {
            log.debug("finding " + argumentName);
        }
        do {
            String overloadIndex = (new Integer(i)).toString();
            if (log.isDebugEnabled()) //JHK
            {
                log.debug("trying " + overloadIndex);
            }
            HashMap procedure = procedures.get(overloadIndex);
            if (procedure == null) {
                return null;
            }
            type = (String) procedure.get(argumentName);
            if (log.isDebugEnabled()) //JHK
            {
                log.debug("found " + type);
            }
            i++;
        } while (type == null);
        return type;
    }

    /**
     * Create a concrete SPProc (8i and 7x). Find the Stored Procedure in the
     * table all_arguments to get public definitios If there are public Stored
     * Procedures add this definition to the Hashtable of the superclass, and
     * store all overloaded ocurrence of the same StoreProcedure
     *
     * @param conn ConnInfo
     * @param procname String
     * @param sqlconn Connection
     * @return SPProc
     * @throws SQLException
     */
    public SPProc(ConnInfo conn, String procname, Connection sqlconn) throws SQLException, ProcedureNotFoundException {
        if (log.isDebugEnabled()) {
            log.debug(".create overload for: '" + procname + "'");
        }
        final String owner;
        final String plpackage;
        final String plprocedure;
        try (CallableStatement css = sqlconn.prepareCall("BEGIN \n dbms_utility.name_resolve(?,1,?,?,?,?,?,?); \nEND;")) {
            css.setString(1, procname);
            css.registerOutParameter(2, Types.VARCHAR);
            css.registerOutParameter(3, Types.VARCHAR);
            css.registerOutParameter(4, Types.VARCHAR);
            css.registerOutParameter(5, Types.VARCHAR);
            css.registerOutParameter(6, Types.VARCHAR);
            css.registerOutParameter(7, Types.VARCHAR);
            css.execute();
            owner = css.getString(2);
            plpackage = css.getString(3);
            plprocedure = css.getString(4);
        }
        String columnNames = "argument_name, overload, data_type, type_owner, type_name, type_subname";
        if (plpackage == null) {
            throw new RuntimeException("procedure must be member of package");
        }
        try (
                PreparedStatement cs = sqlconn.prepareStatement("SELECT " + columnNames + " FROM all_arguments WHERE "
                        + " owner = ? AND package_name = ? AND object_name = ? " + " ORDER BY overload,sequence")) {
            if (log.isDebugEnabled()) {
                log.debug("Resolving package.procedure call");
                log.debug("Excuting: SELECT " + columnNames + " FROM all_arguments WHERE "
                        + " owner = ? AND package_name = ? AND object_name = ? " + " ORDER BY overload,sequence");
                log.debug("With arg 1: " + owner);
                log.debug("With arg 2: " + plpackage);
                log.debug("With arg 3: " + plprocedure);
            }
            cs.setString(1, owner);
            cs.setString(2, plpackage);
            cs.setString(3, plprocedure);
            boolean exists = false;
            try (ResultSet rs = cs.executeQuery()) {
                String old_overload = "something";
                while (rs.next()) {
                    exists = true;
                    String argument_name = rs.getString(1);
                    String overload = rs.getString(2);
                    if (overload == null) { // There is no overloading
                        overload = "1";
                    }
                    if (!old_overload.equals(overload)) {
                        this.addProcedure(overload);
                        old_overload = overload;
                    }
                    // if procedure has no argument, empty row is returned
                    if (argument_name == null) {
                        if (log.isDebugEnabled()) {
                            log.debug("            overload: " + overload + " no argument");
                        }
                        continue;
                    }
                    argument_name = argument_name.toLowerCase();
                    String data_type = rs.getString(3);
                    if ("PL/SQL TABLE".equals(data_type)) { // argument is ARRAY variable

                        String category = rs.getString(3).toUpperCase();
                        String type_owner = rs.getString(4);
                        String type_name = rs.getString(5);
                        String type_subname = rs.getString(6);
                        this.add(overload, argument_name, type_owner + "." + type_name + "." + type_subname, category);
                        if (log.isDebugEnabled()) {
                            log.debug("            overload: " + overload + " arg: " + argument_name + " data_type: " + data_type + " type_name: " + type_owner + "." + type_name + "." + type_subname);
                        }
                        rs.next();
                    } else { // argument is SCALAR variable
                        this.add(overload, argument_name, data_type, "SCALAR");
                        if (log.isDebugEnabled()) {
                            log.debug("            overload: " + overload + " arg: " + argument_name + " data_type: " + data_type + " category: SCALAR");
                        }
                    }
                }
                if (!exists) {
                    throw new ProcedureNotFoundException("could not find procedure: " + procname);
                }
            }
        }

    }

    /**
     * Customize get to handle pl/sql overloaded procedure names
     *
     * @return the type name for this argument name
     */
    public String get(String argumentName, int elementCnt) {
        int i = 1;
        String type;
        if (log.isDebugEnabled()) {
            log.debug("finding " + argumentName + " Elements: " + elementCnt);
        }
        do {
            String overloadIndex = (new Integer(i)).toString();
            if (log.isDebugEnabled()) {
                log.debug("trying " + overloadIndex);
            }
            HashMap procedure = procedures.get(overloadIndex);
            if (procedure == null) {
                return null;
            }
            type = (String) procedure.get(argumentName);
            /*					 
             *  multivalue elements map to a declared type e.g. owa_util.ident_arr,
             *  wsgl.typString240Table all of which contain (.).  Scalars like
             *  varchar2 and date don't contain (.).
             */
            if (type != null) {
                log.debug("Where's the period in " + type + " ( " + type.indexOf(".") + ") Elements(" + elementCnt + ")");
                if (elementCnt > 1 && (type.indexOf(".") == -1)) {
                    if (log.isDebugEnabled()) {
                        log.debug("scalar to multivalue rollback");
                        type = null;
                    }
                } // this was missing LXG
                if (log.isDebugEnabled()) {
                    log.debug("found " + type);
                }
            }
            i++;
        } while (type == null);
        return type;
    }

    /**
     * add a new argument name for this procedure definition for every
     * overloaded procedure entry there are entrys for each argument name
     * argumentCategory is "PL/SQL Table" for multivalued items Changed data
     * part of procedure to be an array instead of simple String
     */
    public void add(String overloadIndex, String argumentName, String argumentType, String argumentCategory) {
        HashMap procedure = procedures.get(overloadIndex);
        ArrayList args = arguments.get(overloadIndex);
        // String[] argsig = {argumentType, argumentCategory}; // LXG
        procedure.put(argumentName, argumentType);
        args.add(argumentName);
        if (log.isDebugEnabled()) //JHK
        {
            log.debug("into : " + overloadIndex + " arg_name: " + argumentName + " inserted with type: " + argumentType + " category: " + argumentCategory);
        }
    }

}
