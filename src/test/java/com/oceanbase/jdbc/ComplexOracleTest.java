/*
 *
 * OceanBase Client for Java
 *
 * Copyright (c) 2021 OceanBase.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * Copyright (c) 2009-2011, Marcus Eriksson
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this list
 * of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice, this
 * list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * Neither the name of the driver nor the names of its contributors may not be
 * used to endorse or promote products derived from this software without specific
 * prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS  AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 *
 */
package com.oceanbase.jdbc;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.sql.*;
import java.util.Arrays;

import com.oceanbase.jdbc.extend.datatype.ComplexDataType;
import com.oceanbase.jdbc.extend.datatype.StructImpl;
import org.junit.*;

/**
 * The test depends on the ob version .Version 2.2.52 has no problems, but 2.2.60 has errors !
 */
public class ComplexOracleTest extends BaseOracleTest {
    /**
     * Tables initialisation.
     *
     * @throws SQLException exception
     */
    public static String tablenameArray  = "testarray";
    public static String tablenameStruct = "teststruct";
    public static String array1          = "test_array";
    public static String array2          = "test_array2";
    public static String array3          = "test_array3";
    public static String struct1         = "test_struct1";
    public static String struct2         = "test_struct2";
    public static String struct3         = "test_struct3";
    public static String raw1            = "test_raw1";
    public static String refcursor1      = "test_ref";
    public static String struct4         = "test_struct4";

    //@BeforeClass()
    public static void initClass() throws SQLException {
        createTable(tablenameArray, "c1 int primary key,c2 interval day(6) to second(5)");
        createTable(tablenameStruct, " c1 int primary key,c2 interval year(4) to month");
        createTable(array1, "c1 int");
        createTable(array2, "c1 char(100)");
        createTable(array3, "c1 date");
        createTable(struct1, "c1 int");
        createTable(struct2, "c1 varchar(100)");
        createTable(struct3, "c1 date");
        createTable(raw1, "c1 int ,c2 raw(100)");
        createTable(refcursor1, "c1 varchar(20), c2 number");
        createTable("T_NESTED_ARRAY", "c1 number, c2 int");
        createTable(struct4, "c1 int, c2 varchar2(400)");
    }

    public void showArrayRes(Array array) throws Exception {
        ResultSet arrayRes = array.getResultSet();
        while (arrayRes.next()) {
            int index = arrayRes.getInt(1);
            Struct struct = (Struct) arrayRes.getObject(2);
            Object[] objArr = struct.getAttributes();
            for (Object obj : objArr) {
                System.out.printf("index j %d obj now is %s\n", index, obj);
            }
        }
    }

    public void executeSqls(String[] sqls, Connection conn) {
        PreparedStatement ps;
        for (String sql : sqls) {
            try {
                ps = conn.prepareStatement(sql);
                ps.execute();
                ps.close();
            } catch (Exception e) {
                System.out.println("sql:" + sql);
                // ignore, maybe table does not exist
                e.printStackTrace();
            }
        }
    }

    @Test
    public void basicArrayAndStructTest() throws SQLException {
        Assume.assumeTrue(sharedUsePrepare());
        Connection conn = null;
        try {
            conn = sharedConnection;
            PreparedStatement ps = null;
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE OR REPLACE TYPE my_obj as object (c1 int, c3 date)");
            stmt.execute("CREATE OR REPLACE TYPE obj_array IS TABLE OF my_obj");

            // test input
            String createPlSql = "CREATE OR REPLACE PROCEDURE my_proc_objarr(X IN obj_array) IS "
                                 + "BEGIN " + "  FOR idx IN 1..X.count LOOP " + "    INSERT INTO "
                                 + array1 + " VALUES(X(idx).c1);"
                                 + "  END LOOP; "
                                 //                                 + "  FOR idx IN 1..X.count LOOP" + "    INSERT INTO " + array2
                                 //                                 + "  VALUES(X(idx).c2);" + "  END LOOP; "
                                 + "    FOR idx IN 1..X.count LOOP" + "    INSERT INTO " + array3
                                 + " VALUES(X(idx).c3);" + "  END LOOP; " + "END;";
            stmt.execute(createPlSql);
            Integer[] intArray = new Integer[10];
            for (int i = 0; i < 10; ++i) {
                if (i % 2 == 0) {
                    intArray[i] = null;
                } else {
                    intArray[i] = i;
                }
            }

            java.sql.Timestamp[] dateArray = new Timestamp[10];
            for (int i = 0; i < 10; ++i) {
                if (i % 2 == 0) {
                    dateArray[i] = Timestamp.valueOf("2019-06-04 10:29:11.123456");
                } else {
                    dateArray[i] = null;
                }
            }
            Object[] structArray = new Object[10];
            for (int i = 0; i < 10; ++i) {
                structArray[i] = conn.createStruct("my_obj", new Object[] { intArray[i],
                        dateArray[i] });
            }
            Array array = conn.createArrayOf("my_obj", structArray);
            {
                System.out.println("=======prepareStatement input test =============");
                ps = conn.prepareStatement("call my_proc_objarr(?)");
                ps.setArray(1, array);
                ps.execute();
                ps.close();

                ps = conn.prepareStatement("select * from " + array1);
                ResultSet rs = ps.executeQuery();
                int i = 0;
                while (rs.next()) {
                    if (intArray[i] == null) {
                        Assert.assertEquals(0, rs.getInt("c1"));
                    } else {
                        Assert.assertEquals(intArray[i].intValue(), rs.getInt("c1"));
                    }
                    i++;
                }
                ps.close();
                ps = conn.prepareStatement("select * from " + array3);
                rs = ps.executeQuery();
                i = 0;
                while (rs.next()) {
                    if (rs.getDate("c1") == null) {
                        Assert.assertEquals(null, dateArray[i]);
                    } else {
                        Assert.assertEquals(new Date(dateArray[i].getTime()).toString(), rs
                            .getDate("c1").toString());
                    }
                    i++;
                }
                ps.close();
            }
            {
                System.out.println("=======callable Statement input test =============");
                ps = conn.prepareCall("call my_proc_objarr(?)");
                ps.setArray(1, array);
                ps.execute();
                ps.close();

                ps = conn.prepareStatement("select * from " + array1);
                ResultSet rs = ps.executeQuery();
                int i = 0;
                while (rs.next()) {
                    if (i == 10) {
                        i = 0;
                    }
                    if (intArray[i] == null) {
                        Assert.assertEquals(0, rs.getInt("c1"));
                    } else {
                        Assert.assertEquals(intArray[i].intValue(), rs.getInt("c1"));
                    }
                    i++;
                }
                ps.close();
                ps = conn.prepareStatement("select * from " + array3);
                rs = ps.executeQuery();
                i = 0;
                while (rs.next()) {
                    if (i == 10) {
                        i = 0;
                    }
                    if (rs.getDate("c1") == null) {
                        Assert.assertEquals(null, dateArray[i]);
                    } else {
                        Assert.assertEquals(new Date(dateArray[i].getTime()).toString(), rs
                            .getDate("c1").toString());
                    }
                    i++;
                }
                ps.close();
            }
            // test output
            createPlSql = "CREATE OR REPLACE PROCEDURE my_proc_obj_out(x OUT obj_array) IS"
                          + " i int :=1;" + " BEGIN" + " x.extend(10);" + "  for idx in 1..5 loop"
                          //                          + "    x(i).c1 := idx;" + "    x(i).c2 := idx;"
                          + "    x(i).c1 := idx;" + "    x(i).c3 := date '1970-01-01';"
                          + "    x(i + 1).c1 := null;"
                          //                          + "    x(i + 1).c2 := null;" + "    x(i + 1).c3 := null;"
                          + "    x(i + 1).c3 := null;" + "    i := i + 2;" + "  end loop;" + "END;";
            {
                stmt.execute(createPlSql);
                ps = conn.prepareStatement("call my_proc_obj_out(?)");
                ps.setArray(1, null);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Array resArray = rs.getArray(1);
                    showArrayRes(resArray);
                }
            }
            // callable test
            {
                System.out.println("=========callable stmt test==========");
                CallableStatement csmt = conn.prepareCall("{call my_proc_obj_out(?)}");
                csmt.registerOutParameter(1, Types.ARRAY, "obj_array");
                csmt.execute();
                Array resArray = csmt.getArray(1);
                Assert.assertNotNull(resArray.getArray());
                showArrayRes(resArray);
            }
        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }
    }

    @Test
    public void getStructTest1() throws SQLException {
        Assume.assumeTrue(sharedUsePrepare());
        Connection conn = null;
        try {
            conn = sharedPSConnection;
            PreparedStatement ps = null;
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE or replace  TYPE my_obj_1 force as object (c1 int, c3 int)");

            String createPlSql = "CREATE OR REPLACE PROCEDURE my_proc_obj_out_1(x OUT my_obj_1) IS "
                                 + " begin "
                                 + "    x:= my_obj_1(1,2); "
                                 + "    x.c1 := 1;"
                                 + "  x.c3 := 2;" + " END;";
            stmt.execute(createPlSql);
            System.out.println("=========callable stmt test==========");
            CallableStatement csmt = conn.prepareCall("call my_proc_obj_out_1(?)");
            csmt.registerOutParameter(1, Types.STRUCT, "MY_OBJ_1");
            csmt.execute();
            Struct struct = (Struct) csmt.getObject(1);
            Assert.assertEquals("MY_OBJ_1", struct.getSQLTypeName());
            Object[] objects = struct.getAttributes();
            Assert.assertEquals(1, ((BigDecimal) objects[0]).intValue());
            Assert.assertEquals(2, ((BigDecimal) objects[1]).intValue());
        } catch (Throwable e) {
            e.printStackTrace();
            fail();
        }
    }

    @Test
    public void setStructTest1() throws SQLException {
        Assume.assumeTrue(sharedUsePrepare());
        Connection conn = null;
        try {
            conn = sharedPSConnection;
            PreparedStatement ps = null;
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE or replace  TYPE my_obj_2 force as object (c1 int, c3 int)");
            Object[] objects = new Object[] { 1, 2 };
            Struct struct = conn.createStruct("my_obj_2", objects);
            String createPlSql = "CREATE OR REPLACE PROCEDURE my_proc_obj_out_2(x IN my_obj_2,y OUT my_obj_2) IS "
                                 + " begin "
                                 + "    y:= my_obj_2(1,2); "
                                 + "    y.c1 := x.c1;"
                                 + "  y.c3 := x.c3;" + " END;";
            stmt.execute(createPlSql);
            System.out.println("=========callable stmt test==========");
            CallableStatement csmt = conn.prepareCall("call my_proc_obj_out_2(?,?)");
            csmt.setObject(1, struct);
            csmt.registerOutParameter(2, Types.STRUCT, "MY_OBJ_2");
            csmt.execute();
            Struct structOutput = (Struct) csmt.getObject(2);
            Assert.assertEquals("MY_OBJ_2", structOutput.getSQLTypeName());
            Object[] attrs = structOutput.getAttributes();
            Assert.assertEquals(1, ((BigDecimal) attrs[0]).intValue());
            Assert.assertEquals(2, ((BigDecimal) attrs[1]).intValue());
        } catch (Throwable e) {
            e.printStackTrace();
            fail();
        }
    }

    /*
       Multi-level structure test case . Observer not support now.
     */
    @Ignore
    public void getStructTest2() throws SQLException {
        Assume.assumeTrue(sharedUsePrepare());
        Connection conn = null;
        try {
            conn = sharedConnection;
            PreparedStatement ps = null;
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE or replace  TYPE subtype1 force as object (c1 int, c2 int)");
            stmt.execute("CREATE or replace  TYPE type1 force as object (c1 int, c2 subtype1 )");
            String createPlSql = "CREATE OR REPLACE PROCEDURE type_proc(x OUT type1)  is "
                                 + "    st1 subtype1;" + " begin " + "    st1.c1 := 1;"
                                 + "  st1.c2 := 2;" + "    x.c1 := 1;" + "  x.c2 := st1;" + " END;";
            stmt.execute(createPlSql);
            System.out.println("=========callable stmt test==========");
            CallableStatement csmt = conn.prepareCall("call type_proc(?)");
            csmt.registerOutParameter(1, Types.STRUCT, "TYPE1");
            csmt.execute();
            Struct struct = (Struct) csmt.getObject(1);
        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }
    }

    @Test
    public void functionReturnValueTest() throws Exception {
        Assume.assumeTrue(sharedUsePrepare());
        Connection conn = sharedConnection;

        String sql1 = "drop table resFun";
        String sql2 = "create table resFun(var1 varchar2(50),var2 varchar2(50),var3 varchar2(50))";
        String sql3 = "create or replace type type1 is varray(3) of number";
        String sql4 = "create or replace function func19\n" + "return type1\n" + "is\n"
                      + "var type1;\n" + "begin\n" + "var :=type1(9,8,7);\n" + "return var;\n"
                      + "end;";
        String sql5 = "declare\n" + "var type1;\n" + "begin\n" + "var :=func19();\n"
                      + "for i in 1..var.count loop\n"
                      + "insert into resFun values (i,i,var(i));\n" + "end loop;\n" + "end;";
        String preCall = "begin\n" + "? :=func19();\n" + "end;";

        Statement stmt = conn.createStatement();
        try {
            stmt.execute(sql1);
        } catch (Exception e) {
        }

        stmt.execute(sql2);
        stmt.execute(sql3);
        stmt.execute(sql4);
        stmt.execute(sql5);
        stmt.close();
        CallableStatement cstmt = null;
        ResultSet resultSet = null;
        try {
            cstmt = conn.prepareCall(preCall);
            cstmt.registerOutParameter(1, Types.ARRAY, "TYPE1");
            cstmt.execute();

            Array array = cstmt.getArray(1);
            resultSet = array.getResultSet();
            Assert.assertTrue(resultSet.next());
            Assert.assertEquals(9, resultSet.getInt(2));
            Assert.assertTrue(resultSet.next());
            Assert.assertEquals(8, resultSet.getInt(2));
            Assert.assertTrue(resultSet.next());
            Assert.assertEquals(7, resultSet.getInt(2));
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void testNestedArray() {
        Assume.assumeTrue(sharedUsePrepare());
        try {
            Connection conn = sharedConnection;
            try {
                conn.createStatement().execute("drop table t_nested_array");
            } catch (SQLException e) {
                //            e.printStackTrace();
            }
            conn.createStatement().execute("create table t_nested_array (c1 number,c2 int)");
            String sql = "create or replace type t_array is table of number";
            PreparedStatement ps = conn.prepareStatement(sql);
            assertEquals(0, ps.executeUpdate());

            sql = "create or replace procedure test_insert (a in number,b in t_array) is begin "
                  + "for i in 1..b.count loop "
                  + "insert into t_nested_array values (a,b(i)); end loop; end";
            assertEquals(0, conn.prepareStatement(sql).executeUpdate());
            CallableStatement cs = conn.prepareCall("{call test_insert(?, ?)}");
            cs.setInt(1, 1);
            cs.setArray(2, conn.createArrayOf("number", new Integer[] { 5, 6 }));
            cs.execute();
            String createPlSql = "CREATE OR REPLACE PROCEDURE test_select(x OUT t_array) IS"
                                 + " i int :=1;" + " BEGIN" + " x.extend(10);"
                                 + "  for idx in 1..5 loop" + "    x(idx):= i;" + "    i := i + 2;"
                                 + "  end loop;" + "END;";
            conn.createStatement().execute(createPlSql);
            assertEquals(0, conn.prepareStatement(sql).executeUpdate());
            cs = conn.prepareCall("{call test_select( ?)}");
            cs.registerOutParameter(1, Types.ARRAY);
            cs.execute();
            Array array = cs.getArray(1);
            Object[] objects = (Object[]) array.getArray();
            int intarray[] = new int[] { 1, 3, 5, 7 };
            for (int i = 0; i < 4; i++) {
                Assert.assertEquals(intarray[i], ((BigDecimal) objects[i]).intValue());
            }
            assertNotNull(cs.getArray(1).getArray());
        } catch (Throwable e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void getArrayBugFix() {
        String c_type = "create or replace type ty is table of int;";
        String c_proc = "create or replace procedure proc(x out ty) is begin null; x := ty(); end;";
        Connection conn = null;
        String sql = "{call proc(?)}";
        Assume.assumeTrue(sharedUsePrepare());
        try {
            conn = sharedConnection;
            Statement stmt = conn.createStatement();
            stmt.execute(c_type);
            stmt.execute(c_proc);
            CallableStatement ps = conn.prepareCall(sql);
            ps.registerOutParameter(1, Types.ARRAY, "TY");
            ps.execute();
            Object arr[] = (Object[]) (ps.getArray(1).getArray());
            Assert.assertNotNull(arr);
            assertNotNull(ps.getArray(1).getArray());
            ps.close();
        } catch (Throwable e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void basicRawTest() throws SQLException {
        Connection conn = null;
        try {
            conn = sharedPSConnection;
            PreparedStatement ps = null;
            ps = conn.prepareStatement("insert into " + raw1 + " values(?,?)");
            ps.setInt(1, 11);
            ps.setBytes(2, new byte[] { 1, 2, 3 });
            ps.execute();

            ps = conn.prepareStatement("select c1 ,c2 from " + raw1 + " where c1 = 11");
            ResultSet rs = ps.executeQuery();
            //            Assert.assertEquals("[B", rs.getMetaData().getColumnClassName(2));
            Assert.assertEquals("RAW", rs.getMetaData().getColumnTypeName(2));
            Assert.assertEquals(-3, rs.getMetaData().getColumnType(2));
            Assert.assertEquals(2, rs.getMetaData().getColumnCount());
            Assert.assertEquals("C2", rs.getMetaData().getColumnLabel(2));
            Assert.assertEquals(100, rs.getMetaData().getColumnDisplaySize(2));
            Assert.assertEquals("TEST", rs.getMetaData().getCatalogName(2));
            Assert.assertEquals("C2", rs.getMetaData().getColumnName(2));
            Assert.assertEquals(0, rs.getMetaData().getScale(2));
            Assert.assertEquals("TEST_RAW1", rs.getMetaData().getTableName(2));
            //            assertFalse(rs.getMetaData().isCaseSensitive(2));
            assertTrue(rs.next());
            Assert.assertEquals(11, rs.getInt(1));
            Assert.assertTrue(Arrays.equals(new byte[] { 1, 2, 3 }, rs.getBytes(2)));
            Assert.assertEquals("010203", rs.getString(2));
            Assert.assertEquals(3, rs.getBinaryStream(2).available());
            rs.close();
            ps.close();
        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }
    }

    @Test
    public void testRefCursor() throws SQLException {
        try {
            Connection conn = sharedPSConnection;
            String sql = "insert into " + refcursor1 + " values(?, ?)";
            for (int i = 0; i < 10; i++) {
                PreparedStatement statement = conn.prepareStatement(sql);
                statement.setString(1, "test" + i);
                statement.setInt(2, i);
                statement.execute();
            }

            sql = "select * from " + refcursor1;
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.execute();
            ResultSet resultSet = ps.getResultSet();
            int count = 0;
            while (resultSet.next()) {
                Assert.assertEquals(2, resultSet.getMetaData().getColumnCount());
                Assert.assertEquals("test" + count, resultSet.getString(1));
                Assert.assertEquals(Integer.toString(count), resultSet.getString(2));
                count++;
            }
            Assert.assertEquals(10, count);

            Statement statement = conn.createStatement();
            String createPlSql = "CREATE OR REPLACE PROCEDURE test_cursor(a out int, p_cursor OUT sys_refcursor, b in out varchar2) "
                    + "is BEGIN "
                    + " open p_cursor for select * from "
                    + refcursor1 + ";" + " a := 66; b := '99';" + "end;";
            statement.execute(createPlSql);
            CallableStatement csmt = conn.prepareCall("call test_cursor(?, ?, ?)",
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            csmt.setFetchSize(10);
            csmt.registerOutParameter(1, Types.INTEGER);
            csmt.registerOutParameter(2, Types.REF);
            csmt.registerOutParameter(3, Types.VARCHAR);
            csmt.setString(3, "111");
            csmt.execute();

            resultSet = (ResultSet) csmt.getObject(2);
            resultSet.setFetchSize(2);
            count = 0;
            while (resultSet.next()) {
                Assert.assertEquals(2, resultSet.getMetaData().getColumnCount());
                Assert.assertEquals("test" + count, resultSet.getString(1));
                Assert.assertEquals(Integer.toString(count), resultSet.getString(2));
                count++;
            }
            Assert.assertEquals(10, count);

            resultSet.close();
            try {
                resultSet = (ResultSet) csmt.getObject(2);
            } catch (Exception e) {
                Assert.assertEquals(e.getMessage(), "cursor is not open");
            }
        } catch (Exception e) {
            fail();
        }
    }

    @Test
    public void testRefCursorResultSetClose() {
        try {
            Connection conn = sharedConnection;
            Statement stmt = conn.createStatement();
            try {
                stmt.execute("drop table test_ref_cursor");
            } catch (SQLException e) {
            }
            stmt.execute("create table test_ref_cursor (c1 int ,c2 varchar2(200))");
            for (int i = 0; i < 10; i++) {
                stmt.execute("insert into test_ref_cursor values (" + (i + 1) + ",'sfsfsfs')");
            }
            stmt.execute("create or replace procedure pro_cursor(ref_cursor out sys_refcursor) as\n"
                         + " begin\n"
                         + " open ref_cursor for SELECT * FROM test_ref_cursor;\n"
                         + " end;");
            CallableStatement cs = conn.prepareCall("call pro_cursor(?)");
            cs.registerOutParameter(1, Types.REF_CURSOR);
            cs.execute();
            ResultSet rs = (ResultSet) cs.getObject(1);
            rs.close();
            try {
                rs.next();
                Assert.fail();
            } catch (SQLException e) {
                assertTrue(e.getMessage().contains("Operation not permit on a closed resultSet"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void cursorGetValeByName() {
        Connection conn = null;
        CallableStatement cstmt = null;
        ResultSet cursor;
        try {
            conn = sharedConnection;
            {
                String[] sqls = {
                        "drop table t1 cascade constraints purge;",
                        "create table t1(c1 binary_double);",
                        "insert into t1 values(1);",
                        "insert into t1 values(2);",
                        "create or replace procedure get_jobs(tcur out sys_refcursor)\n" + "is\n"
                                + "BEGIN\n" + "  open tcur for SELECT * from t1;\n" + "END;", };
                for (int i = 0; i < sqls.length; i++) {
                    try {
                        conn.createStatement().execute(sqls[i]);
                    } catch (SQLException e) {
                        //
                    }
                }
            }
            PreparedStatement pstmt;
            String sql;
            sql = "select c1 from t1";
            pstmt = conn.prepareStatement(sql);
            cursor = pstmt.executeQuery();
            Assert.assertTrue(cursor.next());
            Assert.assertEquals("1.0", cursor.getString("c1"));
            Assert.assertTrue(cursor.next());
            Assert.assertEquals("2.0", cursor.getString("c1"));
            sql = "{call get_jobs(?)}";
            cstmt = conn.prepareCall(sql);
            cstmt.registerOutParameter(1, Types.REF);
            cstmt.execute();
            cursor = (ResultSet) cstmt.getObject(1);
            cursor.setFetchSize(2);
            Assert.assertTrue(cursor.next());
            Assert.assertEquals("1.0", cursor.getString("c1"));
            Assert.assertTrue(cursor.next());
            Assert.assertEquals("2.0", cursor.getString("c1"));
        } catch (Throwable te) {
            te.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void testStrcut() {
        Assume.assumeTrue(sharedUsePrepare());
        try {
            Connection conn = sharedConnection;
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE or replace  TYPE struct_test_1 force as object (c1 int, c3 varchar2(200))");
            Object[] objects = new Object[] { 1, new BigDecimal(200) }; // insert bigdecimal value to varchar struct
            Struct struct = conn.createStruct("struct_test_1", objects);
            String createPlSql = "CREATE OR REPLACE PROCEDURE my_proc_obj_out_test(x IN struct_test_1,y OUT struct_test_1) IS "
                                 + " begin "
                                 + "    y:= struct_test_1(1,null); "
                                 + "    insert into "
                                 + struct4
                                 + " values(x.c1, x.c3) ;"
                                 + "    y.c1 := x.c1;" + "  y.c3 := x.c3;" + " END;";
            stmt.execute(createPlSql);
            CallableStatement csmt = conn.prepareCall("call my_proc_obj_out_test(?,?)");
            csmt.setObject(1, struct);
            csmt.registerOutParameter(2, Types.STRUCT, "struct_test_1");
            csmt.execute();
            ResultSet rs = conn.createStatement().executeQuery("select count(*) from " + struct4);
            Assert.assertTrue(rs.next());
            Assert.assertEquals(1, rs.getInt(1));
        } catch (SQLException e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void testComplex() {
        Assume.assumeTrue(sharedUsePrepare());
        try {
            Connection conn = sharedConnection;
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE OR REPLACE TYPE my_obj_1 as object (c0 int, c1 int);");
            stmt.execute("CREATE OR REPLACE TYPE obj_array_1 IS TABLE OF my_obj_1;");
            stmt.execute("CREATE OR REPLACE PROCEDURE my_proc1_1(a in my_obj_1, b out obj_array_1) IS BEGIN null;end;");
            stmt.execute("CREATE OR REPLACE PROCEDURE my_proc2_1(a in my_obj_1, b out obj_array_1, c out my_obj_1) IS BEGIN null;end;");

            stmt.execute("CREATE OR REPLACE PROCEDURE my_proc3_1(a in my_obj_1, c out my_obj_1) IS BEGIN null;end;");

            System.out.println("###proc2###");
            CallableStatement csmt = conn.prepareCall("{call my_proc2_1(?,?,?)}");
            Struct InStruct = conn.createStruct("my_obj_1", new Object[] { 1, 2 });
            csmt.setObject(1, InStruct);
            csmt.registerOutParameter(2, Types.ARRAY, "OBJ_ARRAY_1");
            csmt.registerOutParameter(3, Types.STRUCT, "MY_OBJ_1");
            csmt.execute();
        } catch (SQLException e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void testComplexChar() {
        Assume.assumeTrue(sharedUsePrepare());
        try {
            Connection conn = sharedConnection;
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE OR REPLACE TYPE my_obj_complexchar as object (c0 char(2));");
            stmt.execute("CREATE OR REPLACE PROCEDURE my_proc1_complexchar(a in my_obj_complexchar) IS BEGIN null;end;");

            stmt.execute("CREATE OR REPLACE TYPE my_obj2_complexchar as object (c0 varchar(2));");
            stmt.execute("CREATE OR REPLACE PROCEDURE my_proc2_complexchar(a in my_obj2_complexchar) IS BEGIN null;end;");

            stmt.close();
            System.out.println("###proc2###");
            CallableStatement csmt = conn.prepareCall("{call my_proc2_complexchar(?)}");
            Struct InStruct = conn.createStruct("my_obj2_complexchar", new Object[] { "aa" });
            csmt.setObject(1, InStruct);
            csmt.execute();

            System.out.println("###proc1###");
            csmt = conn.prepareCall("{call my_proc1_complexchar(?)}");
            InStruct = conn.createStruct("my_obj_complexchar", new Object[] { "aa" });
            csmt.setObject(1, InStruct);
            csmt.execute();
        } catch (SQLException e) {
            e.printStackTrace();
            Assert.fail();
        }

    }

    @Test
    public void testComplexArrayWithTimestmap() {
        Assume.assumeTrue(sharedUsePrepare());
        try {
            Connection conn = sharedConnection;
            CallableStatement cs = null;
            try {
                cs = conn.prepareCall("drop table order_line");
                cs.execute();
            } catch (SQLException e) {
                //                e.printStackTrace();
            }
            cs = conn.prepareCall("create table order_line (ol_delivery_d   timestamp)");
            cs.execute();
            cs = conn
                .prepareCall("insert into order_line values(to_date('1998-12-1', 'yyyy-mm-dd'))");
            cs.execute();
            cs = conn.prepareCall("create or replace type time_array as table of timestamp");
            cs.execute();
            cs = conn.prepareCall("create or replace procedure Order1(\n"
                                  + "out_ol_delivery_d \t  out time_array\n" + ")is\n" + "begin\n"
                                  + "    SELECT ol_delivery_d\n"
                                  + "\tbulk collect into  out_ol_delivery_d\n"
                                  + "\tFROM order_line;\n" + "end Order1;");
            cs.execute();
            cs = conn.prepareCall("{call Order1(?)}");

            cs.registerOutParameter(1, Types.ARRAY, "TIME_ARRAY");
            cs.execute();

            Array resArray = cs.getArray(1);
            ResultSet arrayRes = resArray.getResultSet();
            Assert.assertTrue(arrayRes.next());
            int index = arrayRes.getInt(1);
            Timestamp ts = arrayRes.getTimestamp(2);
            Assert.assertEquals("1998-12-01 00:00:00.0", ts.toString());
        } catch (Throwable e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void testArrayWithClob() {
        Assume.assumeTrue(sharedUsePrepare());
        try {
            Statement stmt = sharedConnection.createStatement();
            try {
                stmt.execute("drop table order_line");
            } catch (SQLException e) {
                // e.printStackTrace();
            }
            stmt.execute("create table order_line (c1 clob)");
            stmt.execute("insert into order_line values('aaa')");
            stmt.execute("create or replace type clob_array as table of clob");

            CallableStatement cs = sharedConnection
                .prepareCall("create or replace procedure Order1(var out clob_array)"
                             + " is begin\n"
                             + " SELECT c1 bulk collect into var FROM order_line;\n"
                             + " end Order1;");
            cs.execute();
            cs = sharedConnection.prepareCall("{call Order1(?)}");
            cs.registerOutParameter(1, Types.ARRAY, "CLOB_ARRAY");
            cs.execute();
            ResultSet arrayRes = cs.getArray(1).getResultSet();
            Assert.assertTrue(arrayRes.next());
            assertEquals(1, arrayRes.getInt(1));
            Assert.assertEquals("aaa", arrayRes.getString(2));
        } catch (Throwable e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void testArrayWithBlob() {
        Assume.assumeTrue(sharedUsePrepare());
        try {
            Connection conn = sharedConnection;
            Statement stmt = conn.createStatement();
            try {
                stmt.execute("drop table test_blob");
            } catch (SQLException e) {
                // e.printStackTrace();
            }
            stmt.execute("create table test_blob (c1 blob)");
            stmt.execute("insert into test_blob values ('abc')");
            stmt.execute("create or replace type blob_array as table of blob");

            CallableStatement cs = conn
                .prepareCall("create or replace procedure pro_blob(var out blob_array)"
                             + " is begin\n" + " SELECT c1 bulk collect into var FROM test_blob;\n"
                             + " end;");
            cs.execute();
            cs = conn.prepareCall("{call pro_blob(?)}");
            cs.registerOutParameter(1, Types.ARRAY, "BLOB_ARRAY");
            cs.execute();
            ResultSet arrayRes = cs.getArray(1).getResultSet();
            Assert.assertTrue(arrayRes.next());
            assertEquals(1, arrayRes.getInt(1));

        } catch (Throwable e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void testCreateArrayOf(){
        try {
            Connection conn = sharedConnection;
            Statement stmt = conn.createStatement();
            stmt.execute("create or replace type a_type is table of varchar2(4000 byte)");
            stmt.execute("create or replace procedure my_proc_objarr (X IN a_type) is begin "
                    + "for idx in 1..x.count loop "
                    + "dbms_output.put_line(idx); "
                    + "END LOOP; end");
            CallableStatement cs = conn.prepareCall("{call my_proc_objarr(?)}");

            char[] varArray = new char[10];
            for (int i = 0; i < 10; ++i) {
                varArray[i] = 'A';
            }
            Object[] structArray = new Object[10];
            for (int i = 0; i < 10; ++i) {
                structArray[i] = conn.createStruct("a_type", new Object[] { varArray[i] });
            }
            Array array = conn.createArrayOf("a_type", structArray);

            cs.setArray(1, array);
            cs.execute();
        } catch (Throwable e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

    @Test
    public void testSdoGeometry() {
        String host = "11.158.31.35";
        int port = 46003;//proxy
        String username = "test@oracle";
        String password = "test";
        //host="100.83.231.1";
        //port=2828;

        String url = String.format("jdbc:oceanbase://%s:%d/%s?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true&useServerPrepStmts=true",
                host, port, "test");

        try {
            Connection conn = DriverManager.getConnection(url, username, password);
            PreparedStatement ps = null;
            Statement stmt = conn.createStatement();
            try {
                stmt.execute("drop table my_tt1");
            } catch (Exception ignore) {}
            stmt.execute("create table my_tt1(c1 number, c2 SDO_GEOMETRY)");
            stmt.execute("insert into my_tt1 values(1, SDO_GEOMETRY(2003, 4000, SDO_POINT_TYPE(1,1,1), SDO_ELEM_INFO_ARRAY(1,1003,3), SDO_ORDINATE_ARRAY(1,1, 5,7)))");
            String createPlSql = "select g.c2.sdo_point from my_tt1 g";
            //String createPlSql = "select c1 from my_tt1";
            System.out.println("=========callable stmt test==========");
            try (ResultSet rs = stmt.executeQuery(createPlSql)) {
                while (rs.next()) {
                    //System.out.println(rs.getNString(1));
                    System.out.println(rs.getObject(1));
                }
            } catch (SQLException e) {
                System.out.println(e.getMessage());
                e.printStackTrace();
            }
            stmt.execute("drop table my_tt1;");

        } catch (Exception e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    public void testGISPSRe() throws SQLException {
        Connection conn = setConnection("&useServerPrepStmts=true&useOraclePrepareExecute=true");
        Statement stmt = conn.createStatement();
        try {
            stmt.execute("drop table my_tt1");
        } catch (SQLException e) {
        }
        stmt.execute("CREATE TABLE my_tt1(c1 number, c2 SDO_GEOMETRY)");
        JDBC4ServerPreparedStatement ps = (JDBC4ServerPreparedStatement)conn.prepareStatement("insert into my_tt1 values(1, " +
                "SDO_GEOMETRY(2001, NULL, SDO_POINT_TYPE(0, 1, NULL), NULL, NULL)) returning c2 into ?");
        ps.registerReturnParameter(1, Types.STRUCT, "SDO_GEOMETRY");
        ps.execute();

        ResultSet rs = ps.getReturnResultSet();
        rs.next();
        System.out.println(rs.getString(1));
        Struct struct = (Struct) rs.getObject(1);
        System.out.println(struct.getSQLTypeName());
        Object[] objects = struct.getAttributes();
        Assert.assertEquals(5, objects.length);
    }

    @Test
    public void testGISGETForAone56696447() throws Exception {
        Connection conn = setConnection("&useServerPrepStmts=true&useOraclePrepareExecute=true&compatibleOjdbcVersion=6");
        Statement stmt = conn.createStatement();
        try {
            stmt.execute("drop table t_gis");
        } catch (SQLException e) {
        }
        stmt.execute("CREATE TABLE t_gis(c1 number, c2 SDO_GEOMETRY)");
        stmt.execute("insert into t_gis values(1, SDO_GEOMETRY(2003, null, SDO_POINT_TYPE(1,1,1), SDO_ELEM_INFO_ARRAY(1,1003,3), SDO_ORDINATE_ARRAY(1,1, 5,7)))");
        // stmt.execute("insert into t_gis values(1, SDO_GEOMETRY(2003, null, null, SDO_ELEM_INFO_ARRAY(1,1003,1), SDO_ORDINATE_ARRAY(10,105, 15,105, 20,110, 10,110, 10,105)))");
        PreparedStatement ps = conn.prepareStatement("select c2 from t_gis");
        ResultSet rs = ps.executeQuery();
        rs.next();
        assertNull(rs.getString(1));

        conn = setConnection("&useServerPrepStmts=true&useOraclePrepareExecute=true&compatibleOjdbcVersion=8");
        ps = conn.prepareStatement("select c2 from t_gis");
        rs = ps.executeQuery();
        rs.next();
        try {
            rs.getString(1);
            fail();
        } catch (SQLException e) {
            assertTrue(e instanceof SQLFeatureNotSupportedException);
        }
    }

    @Test
    public void testForAone56285525() throws SQLException {
        String synonyms = System.getProperty("synonyms");
        Connection conn = setConnection("zeroDateTimeBehavior=convertToNull&useServerPrepStmts=true&useOraclePrepareExecute=false");
        Statement stmt = conn.createStatement();
        try {
            stmt.execute("drop user " + synonyms + " cascade");
        } catch (Exception ignored) {

        }

        stmt.execute("create user " + synonyms + " identified by " + synonyms);
        stmt.execute("grant dba to " + synonyms);
        stmt.execute("create or replace type tb force is table of int");
        stmt.execute("create or replace procedure proc(arg out tb) is begin arg := tb(); end;");

        String url = System.getProperty("db_o_url");
        Connection conn2 = DriverManager.getConnection(url);
        Statement stmt2 = conn2.createStatement();
        stmt2.execute("create or replace synonym tb for test.tb");
        stmt2.execute("create or replace synonym proc for test.proc");

        CallableStatement call = conn2.prepareCall("call proc(?)");
        call.registerOutParameter(1, Types.ARRAY);
        try {
            call.execute();
        } catch (SQLException e) {
            fail();
        }
    }

    @Test
    public void testGisForAone57141394() throws SQLException {
        Connection conn = setConnection("&useServerPrepStmts=true");
        createTable("t_gis2", "c1 number, c2 SDO_GEOMETRY");

        try {
            PreparedStatement ps = conn.prepareStatement("insert into t_gis2 values(2, ?)");
            Object sdoPointType = conn.createStruct("SDO_POINT_TYPE", new Object[]{0, 1, null});
            Object sdoGeometry = conn.createStruct("SDO_GEOMETRY", new Object[]{2001, null, sdoPointType, null, null});
            ps.setObject(1, sdoGeometry);
            ps.execute();

            PreparedStatement ps1 = conn.prepareStatement("select t.c2.sdo_gtype, t.c2.sdo_point, t.c2.sdo_point.y from t_gis2 t where t.c1 = 2");
            ResultSet rs = ps1.executeQuery();
            assertTrue(rs.next());
            assertEquals(2001, rs.getInt(1));
            Struct struct = (Struct) rs.getObject(2);
            Object[] attributes = struct.getAttributes();
            assertEquals(new BigDecimal(0) , attributes[0]);
            assertEquals(1, rs.getInt(3));
        } catch (Exception e) {
            fail();
        }

    }

    @Test
    public void testGisMemberFunctionForAone57133075() throws SQLException {
        Connection conn = setConnection("&useServerPrepStmts=true&useOraclePrepareExecute=false");
        Statement stmt = conn.createStatement();
        createTable("t_gis7", "c1 number,c2 SDO_GEOMETRY");
        stmt.execute("insert into t_gis7 values(1, SDO_GEOMETRY(2001, NULL, SDO_POINT_TYPE(10, 50, NULL), NULL, NULL))");
        stmt.execute("insert into t_gis7 values(2, sdo_geometry(2003, null, null, SDO_ELEM_INFO_ARRAY(1,1003,3), SDO_ORDINATE_ARRAY(1,1,5,7)))");

        PreparedStatement ps = conn.prepareStatement("select t.c2.sdo_gtype, t.c2.sdo_srid, t.c2.sdo_point, t.c2.sdo_point.x, t.c2.sdo_point.y, t.c2.sdo_point.z from t_gis7 t where c1 = 1");
        ResultSet rs = ps.executeQuery();
        rs.next();
        assertEquals(2001, rs.getInt(1));
        assertNull(rs.getString(2));
        Object[] point = ((Struct) rs.getObject(3)).getAttributes();
        assertEquals(3, point.length);
        assertEquals(new BigDecimal(10) , rs.getBigDecimal(4));
        assertEquals(new BigDecimal(50) , rs.getBigDecimal(5));
        assertNull(rs.getBigDecimal(6));
    }

    @Test
    public void testForAone58098066() throws SQLException {
        String synonyms = System.getProperty("synonyms");
        Connection conn = sharedConnection;
        Statement stmt = conn.createStatement();

        try {
            stmt.execute("drop user " + synonyms + " cascade");
        } catch (SQLException e) {
            //throw new RuntimeException(e);
        }

        stmt.execute("create user " + synonyms + " identified by " + synonyms);
        stmt.execute("grant dba to " + synonyms + "; grant execute any type to " + synonyms);
        stmt.execute("create or replace type obj0 force as object(c0 int)");
        stmt.execute("create or replace public synonym syn_obj0 for obj0");
        stmt.execute("create or replace procedure p1(a out syn_obj0) is begin null;end;");
        CallableStatement cs =conn.prepareCall("call p1(?)");
        cs.registerOutParameter(1, Types.STRUCT);
        cs.execute();
        cs.close();

        String url = System.getProperty("DbUrl2") + synonyms;
        Connection conn2 = DriverManager.getConnection(url, synonyms+"@oracle", synonyms);
        Statement stmt2 = conn2.createStatement();
        stmt2.execute("create or replace procedure p1(a out syn_obj0) is begin null; end;");
        CallableStatement cs2 = conn2.prepareCall("call p1(?)");
        cs2.registerOutParameter(1, Types.STRUCT);
        try {
            cs2.execute();
            fail();
        } catch (SQLException e) {
            Assert.assertTrue(e.getMessage().contains("unknown complex data type."));
        }
    }

    @Test
    public void testForAone58098220() throws SQLException {
        Connection conn = sharedConnection;

        Statement stmt = conn.createStatement();
        stmt.execute("create or replace type obj0 force as object(c0 int)");
        stmt.execute("create or replace synonym syn_obj0 for obj0");

        stmt.execute("create or replace procedure p1(a syn_obj0) is begin null;end;");
        CallableStatement call =conn.prepareCall("call p1(?)");
        try {
            call.setObject(1, conn.createStruct("syn_obj0", new Object[]{1}));
            call.execute();
        } catch (SQLException e) {
            Assert.fail();
        }
    }

    @Test
    public void testNestedArrayForDima2024081200104103579() throws SQLException {
        Connection conn = sharedConnection;
        String observer_version = conn.getMetaData().getDatabaseProductVersion();
        Statement stmt = conn.createStatement();
        createTable("t_1", "id1 int, id2 int");
        stmt.execute("insert into t_1 values (1, 2), (3, 4), (5, 6)");
        try {
            stmt.execute("drop type obj0 force");
        } catch (SQLException e) {
            //
        }
        stmt.execute("create or replace type obj0 is table of number");
        conn.createStruct("obj0", new Object[] {1});

        try {
            stmt.execute("drop type obj0 force");
            stmt.execute("drop type array0 force");
        } catch (SQLException e) {
            //
        }
        stmt.execute("create or replace type obj0 as object (c1 int, c2 int)");
        stmt.execute("create or replace type array0 is varray(10) of obj0");

        String str;
        if (observer_version.compareTo("4.2") >= 0) {
            str = "obj0(id1,id2)";
        } else {
            str = "id1,id2";
        }

        String sql = "create or replace procedure test_array (b out array0) is begin "
                + "select "+ str +" bulk collect into b from t_1 ; end";
        stmt.execute(sql);
        CallableStatement cs = conn.prepareCall("{call test_array(?)}");
        cs.registerOutParameter(1, Types.ARRAY);
        cs.execute();
        Array array = cs.getArray(1);
        Object[] arrobj = (Object[]) array.getArray();
        assertEquals(3, arrobj.length);

        for (int i = 0; i < 3; i++) {
            StructImpl struct = (StructImpl) arrobj[i];
            Assert.assertEquals("OBJ0", struct.getSQLTypeName());
            ComplexDataType complexType = struct.getComplexType();

            ComplexDataType[] attrTypes = complexType.getAttrTypes();
            assertEquals(2, attrTypes.length);
            assertEquals("NUMBER", attrTypes[0].getTypeName());
            assertEquals("NUMBER", attrTypes[1].getTypeName());

            Object[] attrData = struct.getAttributes();
            assertEquals(2, attrData.length);
            assertEquals(i * 2 + 1 , ((Number) attrData[0]).intValue());
            assertEquals(i * 2 + 2, ((Number) attrData[1]).intValue());
        }

    }

    @Test
    public void testForDima2024062200102855809() {
        try {
            Connection conn = sharedConnection;
            Statement stmt = conn.createStatement();
            stmt.execute("create or replace type obj0 force as object (c0 int,c1 int)");
            stmt.execute("create or replace type tb is table of obj0");
            stmt.execute("create or replace procedure p1(a tb) is begin null;end;");
            Object[] structArray = new Object[10];
            for (int i = 0; i < 10; ++i) {
                structArray[i] = conn.createStruct("obj0", new Object[] { 1, 1});
            }
            conn.createStatement().execute("create or replace type obj0 force is object(c0 int)");
            conn.createStatement().execute("create or replace type tb force is table of obj0");
            conn.createStatement().execute("create or replace procedure p2(b out tb) is begin b:=tb(obj0(10));end;");
            CallableStatement call1 = conn.prepareCall("call p2(?)");
            call1.registerOutParameter(1,Types.ARRAY);
            call1.execute();

            Array array = call1.getArray(1);
            Object[] resultArray = (Object[]) array.getArray();
            assertEquals(1, resultArray.length);

            StructImpl struct = (StructImpl) resultArray[0];
            Assert.assertEquals("OBJ0", struct.getSQLTypeName());
            ComplexDataType complexType = struct.getComplexType();

            ComplexDataType[] attrTypes = complexType.getAttrTypes();
            assertEquals(1, attrTypes.length);
            assertEquals("NUMBER", attrTypes[0].getTypeName());

            Object[] attrData = struct.getAttributes();
            assertEquals(1, attrData.length);
            assertEquals(10, ((Number) attrData[0]).intValue());
        }catch (SQLException e){
            fail();
        }
    }

    @Test
    public void testForDima2024062200102855809_2() throws SQLException {
        Connection conn = sharedConnection;
        Statement stmt = conn.createStatement();
        stmt.execute("create or replace type obj0 force as object(c0 int)");
        stmt.execute("create or replace procedure p1(a obj0) is begin null;end;");
        CallableStatement  cs1 = conn.prepareCall("call p1(?)");
        cs1.setObject(1, conn.createStruct("obj0", new Object[]{1}));
        cs1.execute();
        cs1.close();

        try {
            stmt.execute("create or replace type obj0 force as object (c0 int, c1 int)");
            stmt.execute("create or replace procedure p1(a obj0) is begin null;end;");
            CallableStatement cs2 =conn.prepareCall("call p1(?)");
            cs2.setObject(1, conn.createStruct("obj0", new Object[]{1,2}));
            cs2.execute();
            cs2.close();
        } catch (SQLException e) {
            fail();
        }
    }
}
