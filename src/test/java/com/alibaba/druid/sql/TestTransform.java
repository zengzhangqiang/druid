package com.alibaba.druid.sql;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import junit.framework.Assert;
import oracle.jdbc.OracleStatement;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.dialect.oracle.parser.OracleStatementParser;
import com.alibaba.druid.sql.dialect.oracle.visitor.OracleSchemaStatVisitor;
import com.alibaba.fastjson.JSON;

public class TestTransform extends OracleTest {

    private String          jdbcUrl;
    private String          user;
    private String          password;
    private DruidDataSource dataSource;

    private ExecutorService executor = Executors.newFixedThreadPool(10);

    public void setUp() throws Exception {
        jdbcUrl = "jdbc:oracle:thin:@10.20.149.18:1521:emdb";
        user = "wardon";
        password = "wardon";

        dataSource = new DruidDataSource();
        dataSource.setInitialSize(1);
        dataSource.setUrl(jdbcUrl);
        dataSource.setUsername(user);
        dataSource.setPassword(password);
        dataSource.setMaxActive(50);
        dataSource.setPoolPreparedStatements(true);
        dataSource.setMaxOpenPreparedStatements(100);
    }

    public void f_test_createIndex() throws Exception {
        String sql = "CREATE INDEX i_db_day_sql_fulltext on db_day_sql_fulltext (sql_id)";
        Connection conn = dataSource.getConnection();

        Statement stmt = conn.createStatement();

        stmt.execute(sql);

        conn.close();
    }

    private int updateCount = 0;

    public void updateRecord(String sqlId, String result) throws Exception {
        Connection conn = dataSource.getConnection();

        PreparedStatement stmt = conn.prepareStatement("UPDATE db_day_sql_fulltext SET SQL_PARSE_RESULT = ? WHERE sql_id = ?");

        stmt.setString(1, result);
        stmt.setString(2, sqlId);

        int updateCount = stmt.executeUpdate();
        if (updateCount < 1) {
            throw new Exception();
        }

        stmt.close();

        conn.close();

        System.out.println((this.updateCount++) + " : " + sqlId);
    }

    public void f_test_transform() throws Exception {

        Connection conn = dataSource.getConnection();

        int count = 0;
        {
            String sql = "SELECT COUNT(*) FROM db_day_sql_fulltext WHERE SQL_PARSE_RESULT IS NULL";
            Statement stmt = conn.createStatement();

            ResultSet rs = stmt.executeQuery(sql);
            rs.next();
            count = rs.getInt(1);
            rs.close();
            stmt.close();
        }
        System.out.println("COUNT : " + count);

        String sql = "SELECT SNAP_DATE, DBNAME, SQL_ID, PIECE, SQL_TEXT" + //
                     "      , COMMAND_TYPE, LAST_SNAP_DATE, DB_PK, SQL_PARSE_RESULT " + //
                     "  FROM db_day_sql_fulltext " + //
                     " WHERE SQL_PARSE_RESULT IS NULL" + //
                     "  ORDER BY sql_id, piece";

        Statement stmt = conn.createStatement();

        OracleStatement oracleStmt = stmt.unwrap(OracleStatement.class);
        oracleStmt.setRowPrefetch(100);

        ResultSet rs = stmt.executeQuery(sql);
        Record r = null;

        int i = 0;
        while (rs.next()) {
            r = new Record();

            i++;

            Date d1 = rs.getDate(1);
            String s2 = rs.getString(2);
            String sqlId = rs.getString(3);

            r.setSnapshotDate(d1);
            r.setDbName(s2);
            r.setSqlId(sqlId);
            r.setPiece(rs.getInt(4));
            r.setSqlText(rs.getString(5));

            r.setCommandType(rs.getInt(6));
            r.setLastSnapshotDate(rs.getDate(7));
            r.setDbPk(rs.getLong(8));

            System.out.println(i + "(" + r.getDbName() + "/" + r.getSqlId() + "/" + r.getCommandType() + ") : " + r.getSqlText());
            try {
                schemaStatInternal(r);
            } catch (Throwable e) {
                // e.printStackTrace();
            }
        }
        rs.close();
        stmt.close();

        conn.close();
    }

    public void schemaStat(final Record r) throws Exception {
        executor.submit(new Runnable() {

            public void run() {
                try {
                    schemaStatInternal(r);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void schemaStatInternal(Record r) throws Exception {
        String sql = r.getSqlText();

        OracleStatementParser parser = new OracleStatementParser(sql);
        List<SQLStatement> statementList = parser.parseStatementList();
        SQLStatement statemen = statementList.get(0);

        Assert.assertEquals(1, statementList.size());
        // print(statementList);

        OracleSchemaStatVisitor visitor = new OracleSchemaStatVisitor();
        statemen.accept(visitor);

        StringBuffer buf = new StringBuffer();
        buf.append("Tables : " + visitor.getTables().toString());
        buf.append("\nColumns : " + visitor.getColumns().toString());
        buf.append("\nCoditions : " + visitor.getConditions().toString());
        buf.append("\nrelationships " + visitor.getRelationships().toString());

        System.out.println(buf.toString());

        System.out.println();
        System.out.println();
        System.out.println();

        updateRecord(r.getSqlId(), buf.toString());
    }

    public void clearResult() throws Exception {
        Connection conn = dataSource.getConnection();

        Statement stmt = conn.createStatement();

        stmt.execute("DELETE FROM db_day_sql_fulltext");
        stmt.close();
        conn.close();
    }

    public void test_migrate() throws Exception {
        Connection conn = dataSource.getConnection();

        int count = 0;
        {
            String sql = "SELECT COUNT(*) FROM db_day_sqltext";
            Statement stmt = conn.createStatement();

            ResultSet rs = stmt.executeQuery(sql);
            rs.next();
            count = rs.getInt(1);
            rs.close();
            stmt.close();
        }
        System.out.println("COUNT : " + count);

        clearResult();

        String sql = "SELECT SNAP_DATE, DBNAME, SQL_ID, PIECE, SQL_TEXT" + //
                     "      , COMMAND_TYPE, LAST_SNAP_DATE, DB_PK, SQL_PARSE_RESULT " + //
                     "  FROM db_day_sqltext " + //
                     // "  WHERE db_pk = 40 and snap_date = trunc(sysdate) " + //
                     "  ORDER BY db_pk, sql_id, piece";

        Statement stmt = conn.createStatement();
        OracleStatement oracleStmt = stmt.unwrap(OracleStatement.class);
        oracleStmt.setRowPrefetch(1000);
        ResultSet rs = stmt.executeQuery(sql);

        List<Record> list = new ArrayList<Record>();
        Record r = null;

        String lastSqlId = null;
        int i = 0;
        int j = 0;
        while (rs.next()) {
            j++;
            Date d1 = rs.getDate(1);
            String s2 = rs.getString(2);
            String sqlId = rs.getString(3);

            if (lastSqlId == null || !lastSqlId.equals(sqlId)) {
                if (r != null) {
                    System.out.println((i++) + "/" + j + " : " + r.getSqlId());
                    System.out.println();

                    try {
                        setInfo(r);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    list.add(r);
                    if (list.size() > 100) {
                        insert(list);
                        list.clear();
                    }
                }

                r = new Record();
                r.setSnapshotDate(d1);
                r.setDbName(s2);
                r.setSqlId(sqlId);
                r.setPiece(rs.getInt(4));
                r.setSqlText(rs.getString(5));

                r.setCommandType(rs.getInt(6));
                r.setLastSnapshotDate(rs.getDate(7));
                r.setDbPk(rs.getLong(8));
            } else {
                String part = rs.getString(5);
                
                if (part == null) {
                    continue;
                }
                
                int commentIndex = part.indexOf("--");
                if (commentIndex != -1) {
                    part = part.substring(0, commentIndex);
                }
                if (part != null) {
                    r.appendSqlText(part);
                }

            }
            lastSqlId = sqlId;
        }

        insert(list);
        rs.close();
        stmt.close();

        conn.close();
    }

    public void setInfo(Record r) {
        OracleStatementParser parser = new OracleStatementParser(r.getSqlText());
        List<SQLStatement> statementList = parser.parseStatementList();
        SQLStatement statemen = statementList.get(0);

        Assert.assertEquals(1, statementList.size());
        // print(statementList);

        OracleSchemaStatVisitor visitor = new OracleSchemaStatVisitor();
        statemen.accept(visitor);

        StringBuffer buf = new StringBuffer();
        buf.append("Tables : " + visitor.getTables().toString());
        buf.append("\nColumns : " + visitor.getColumns().toString());
        buf.append("\nCoditions : " + visitor.getConditions().toString());
        buf.append("\nrelationships " + visitor.getRelationships().toString());

        r.setResult(buf.toString());

    }

    public void insert(List<Record> list) throws Exception {
        if (list.size() == 0) {
            return;
        }

        String sql = "INSERT INTO db_day_sql_fulltext " + //
                     "(SNAP_DATE, DBNAME, SQL_ID, PIECE, SQL_TEXT" + //
                     ", COMMAND_TYPE, LAST_SNAP_DATE, DB_PK, SQL_PARSE_RESULT)" + //
                     " VALUES (?, ?, ?, ?, ?,   ?, ?, ?, ?)";
        Connection conn = dataSource.getConnection();

        PreparedStatement stmt = conn.prepareStatement(sql);

        for (Record r : list) {
            stmt.setDate(1, r.getSnapshotDate());
            stmt.setString(2, r.getDbName());
            stmt.setString(3, r.getSqlId());
            stmt.setInt(4, r.getPiece());
            stmt.setString(5, r.getSqlText());

            stmt.setInt(6, r.getCommandType());
            stmt.setDate(7, r.getLastSnapshotDate());
            stmt.setLong(8, r.getDbPk());
            stmt.setString(9, r.getResult());

            stmt.addBatch();
        }
        stmt.executeBatch();

        stmt.close();

        conn.close();
    }

    public static class Record {

        private Date         snapshotDate;
        private String       dbName;
        private String       sqlId;
        private StringBuffer sqlText = new StringBuffer();
        private Integer      piece;
        private Integer      commandType;
        private Date         lastSnapshotDate;
        private Long         dbPk;
        private String       result;

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public Date getSnapshotDate() {
            return snapshotDate;
        }

        public void setSnapshotDate(Date snapshotDate) {
            this.snapshotDate = snapshotDate;
        }

        public String getDbName() {
            return dbName;
        }

        public void setDbName(String dbName) {
            this.dbName = dbName;
        }

        public String getSqlId() {
            return sqlId;
        }

        public void setSqlId(String sqlId) {
            this.sqlId = sqlId;
        }

        public String getSqlText() {
            return sqlText.toString();
        }

        public void setSqlText(String sqlText) {
            this.sqlText = new StringBuffer(sqlText);
        }

        public void appendSqlText(String sqlText) {
            this.sqlText.append(sqlText);
        }

        public Integer getPiece() {
            return piece;
        }

        public void setPiece(Integer piece) {
            this.piece = piece;
        }

        public Integer getCommandType() {
            return commandType;
        }

        public void setCommandType(Integer commandType) {
            this.commandType = commandType;
        }

        public Date getLastSnapshotDate() {
            return lastSnapshotDate;
        }

        public void setLastSnapshotDate(Date lastSnapshotDate) {
            this.lastSnapshotDate = lastSnapshotDate;
        }

        public Long getDbPk() {
            return dbPk;
        }

        public void setDbPk(Long dbPk) {
            this.dbPk = dbPk;
        }

        public String toString() {
            return JSON.toJSONString(this);
        }
    }
}