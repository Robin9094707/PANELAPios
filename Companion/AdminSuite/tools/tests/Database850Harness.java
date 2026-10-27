package eu.simplexsmp.adminsuite;

import java.sql.*;
import java.util.*;

public final class Database850Harness {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        check(ServiceLoader.load(org.mariadb.jdbc.plugin.AuthenticationPluginFactory.class).iterator().hasNext(),"packaged driver authentication services available");
        Map<String,Object> input=new LinkedHashMap<>(Map.of("host","127.0.0.1","port","3306","database","inspector_fixture","username","inspector","password","fixture-reader","allowPlaintext",true));
        check(DatabaseInspector.url(input).equals("jdbc:mariadb://127.0.0.1:3306/inspector_fixture"),"fixed database URL");
        check(DatabaseInspector.properties(input).getProperty("allowMultiQueries").equals("false"),"multiple statements disabled");
        input.put("allowPlaintext",false);check(DatabaseInspector.properties(input).getProperty("sslMode").equals("verify-full"),"TLS verified by default");input.put("allowPlaintext",true);
        for(String bad:List.of("localhost?sslMode=disable","localhost/other","localhost,user@host")) {
            input.put("host",bad);try{DatabaseInspector.url(input);throw new AssertionError("accepted URL injection");}catch(IllegalArgumentException expected){}
        }
        input.put("host","127.0.0.1");
        if(System.getenv("TEST_DATABASE") == null){System.out.println("Database scanner input checks passed (SQL integration runs in CI)");return;}
        try(Connection root=new org.mariadb.jdbc.Driver().connect("jdbc:mariadb://127.0.0.1:3306/inspector_fixture",new Properties(){{setProperty("user","root");setProperty("password","fixture-root");setProperty("sslMode","disable");}});Statement statement=root.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS sample (id INT PRIMARY KEY, value VARCHAR(100), INDEX(value))");
            statement.execute("INSERT IGNORE INTO sample VALUES(1,'unchanged')");
            statement.execute("CREATE USER IF NOT EXISTS 'inspector'@'%' IDENTIFIED BY 'fixture-reader'");
            statement.execute("GRANT SELECT ON inspector_fixture.* TO 'inspector'@'%'");
            DatabaseInspector inspector=new DatabaseInspector();
            Map<String,Object> result=inspector.scan(input);
            check(Boolean.TRUE.equals(result.get("ok")),"successful metadata scan: "+result);
            check(((Number)result.get("tableCount")).intValue()==1,"correct schema table count");
            check(((Number)result.get("dataBytes")).longValue()>0,"storage metadata returned");
            check(result.get("quotaBytes")==null,"never invent a quota");
            check(!result.toString().contains("unchanged")&&!result.toString().contains("fixture-reader"),"no row contents or passwords returned");
            try(ResultSet row=statement.executeQuery("SELECT COUNT(*),MAX(value) FROM sample")){check(row.next()&&row.getInt(1)==1&&row.getString(2).equals("unchanged"),"existing data unchanged");}
            input.put("password","wrong-secret");Map<String,Object> denied=inspector.scan(input);
            check(Boolean.FALSE.equals(denied.get("ok"))&&denied.get("category").equals("authentication"),"wrong password distinguished");
            check(!denied.toString().contains("wrong-secret"),"no password leaked");
            System.out.println("MariaDB read-only metadata integration passed");
        }
    }
}
