package eu.simplexsmp.adminsuite;

import java.sql.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Fixed metadata queries only. No table contents, user SQL, schema changes or stored passwords. */
final class DatabaseInspector {
    private final Semaphore slot = new Semaphore(1);
    static String text(Map<String,Object> input,String key,int maximum) {
        Object value=input.get(key);String text=value instanceof String?(String)value:"";
        if(text.isBlank()||text.length()>maximum||text.indexOf('\0')>=0)throw new IllegalArgumentException("Ungültiges Datenbankfeld: "+key);
        return text;
    }
    static Properties properties(Map<String,Object> input) {
        Properties properties=new Properties();
        properties.setProperty("user",text(input,"username",128));
        Object password=input.get("password");
        if(!(password instanceof String)||((String)password).length()>4096)throw new IllegalArgumentException("Ungültiges Passwortfeld");
        properties.setProperty("password",(String)password);
        properties.setProperty("connectTimeout","5000");properties.setProperty("socketTimeout","8000");
        properties.setProperty("allowLocalInfile","false");properties.setProperty("allowMultiQueries","false");
        properties.setProperty("sslMode",Boolean.TRUE.equals(input.get("allowPlaintext"))?"disable":"verify-full");
        return properties;
    }
    static String url(Map<String,Object> input) {
        String host=text(input,"host",253),database=text(input,"database",128);
        if(!host.matches("[A-Za-z0-9._-]+")||!database.matches("[A-Za-z0-9_$-]+"))throw new IllegalArgumentException("Host oder Datenbankname enthält nicht unterstützte Zeichen");
        int port;try{port=Integer.parseInt(String.valueOf(input.get("port")));}catch(Exception e){throw new IllegalArgumentException("Ungültiger Datenbankport");}
        if(port<1||port>65535)throw new IllegalArgumentException("Ungültiger Datenbankport");
        return "jdbc:mariadb://"+host+":"+port+"/"+database;
    }
    Map<String,Object> scan(Map<String,Object> input) {
        String url=url(input);Properties properties=properties(input);
        if(!slot.tryAcquire())throw new IllegalArgumentException("Ein Datenbankscan läuft bereits. Bitte kurz warten.");
        try {
            // Construct this driver's connection directly; do not register a global JDBC driver.
            try(Connection connection=new org.mariadb.jdbc.Driver().connect(url,properties)) {
                return inspect(connection,text(input,"database",128));
            }
        } catch(SQLException e) {
            String state=e.getSQLState()==null?"":e.getSQLState();
            String category=state.startsWith("28")?"authentication":state.startsWith("42")?"permission":"connection";
            String message=category.equals("authentication")?"Anmeldung abgelehnt. Passwort oder Datenbankbenutzer prüfen.":category.equals("permission")?"Zugriff auf diese Datenbank oder ihre Metadaten wurde verweigert.":"Keine bestätigte Datenbankverbindung. Host, Port, Quell-IP-Freigabe und TLS prüfen; das beweist kein falsches Passwort.";
            // Driver exception messages may contain connection details. Never return or log them.
            return SimplexAdminSuite.appMap("ok",false,"category",category,"message",message);
        } finally { properties.remove("password");slot.release(); }
    }
    static Map<String,Object> inspect(Connection connection,String database)throws SQLException {
        connection.setReadOnly(true);connection.setAutoCommit(false);
        try {
            // Server-enforced transaction, in addition to the JDBC read-only hint.
            try(Statement statement=connection.createStatement()){statement.setQueryTimeout(5);statement.execute("SET TRANSACTION READ ONLY");}
            Map<String,Object> summary=new LinkedHashMap<>();
            summary.put("ok",true);summary.put("database",database);summary.put("scannedAt",System.currentTimeMillis());
            summary.put("version",connection.getMetaData().getDatabaseProductVersion());summary.put("quotaBytes",null);
            String totals="SELECT COUNT(*) AS tables_count, COALESCE(SUM(DATA_LENGTH),0) AS data_bytes, COALESCE(SUM(INDEX_LENGTH),0) AS index_bytes, COALESCE(SUM(TABLE_ROWS),0) AS estimated_rows FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'";
            try(PreparedStatement query=connection.prepareStatement(totals)){
                query.setQueryTimeout(5);query.setString(1,database);
                try(ResultSet row=query.executeQuery()){if(row.next()){summary.put("tableCount",row.getLong("tables_count"));summary.put("dataBytes",row.getLong("data_bytes"));summary.put("indexBytes",row.getLong("index_bytes"));summary.put("estimatedRows",row.getLong("estimated_rows"));}}
            }
            List<Map<String,Object>> tables=new ArrayList<>();
            try(PreparedStatement query=connection.prepareStatement("SELECT TABLE_NAME, ENGINE, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH, TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY (COALESCE(DATA_LENGTH,0) + COALESCE(INDEX_LENGTH,0)) DESC, TABLE_NAME LIMIT 501")) {
                query.setQueryTimeout(5);query.setString(1,database);
                try(ResultSet row=query.executeQuery()){while(row.next()){if(tables.size()==500){summary.put("truncated",true);break;}tables.add(SimplexAdminSuite.appMap("name",row.getString("TABLE_NAME"),"engine",row.getString("ENGINE"),"estimatedRows",row.getObject("TABLE_ROWS"),"dataBytes",row.getObject("DATA_LENGTH"),"indexBytes",row.getObject("INDEX_LENGTH"),"collation",row.getString("TABLE_COLLATION")));}}
            }
            summary.put("tables",tables);summary.put("readOnly",true);return summary;
        } finally { connection.rollback(); }
    }
}
