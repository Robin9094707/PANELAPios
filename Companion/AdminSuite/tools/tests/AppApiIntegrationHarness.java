package eu.simplexsmp.adminsuite;

import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.net.ssl.*;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;

/** Actual TLS/auth/confirmation transport, real Paper interfaces, synthetic Bukkit services; no live Minecraft claim. */
public final class AppApiIntegrationHarness {
    static int checks;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static Object empty(Class<?> t){if(!t.isPrimitive())return t==List.class?List.of():t==Set.class?Set.of():t==Map.class?Map.of():null;if(t==boolean.class)return false;if(t==long.class)return 0L;if(t==double.class)return 0d;if(t==float.class)return 0f;return 0;}
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object data){return (Map<String,Object>)data;}
    static HttpClient client;
    static URI base;
    static HttpResponse<String> call(String path,String token,String body)throws Exception{
        var b=HttpRequest.newBuilder(base.resolve(path)).timeout(java.time.Duration.ofSeconds(20));if(token!=null)b.header("Authorization","Bearer "+token);if(body!=null)b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    static String json(Object value){return DiscordBotManager.Json.stringify(value);}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("sas-api-http-");Field serverField=Bukkit.class.getDeclaredField("server");serverField.setAccessible(true);Object old=serverField.get(null);AppApiManager api=null;
        try{
            BukkitTask task=proxy(BukkitTask.class,(p,m,a)->empty(m.getReturnType()));
            BukkitScheduler scheduler=proxy(BukkitScheduler.class,(p,m,a)->{if(a!=null)for(Object item:a){if(item instanceof Runnable work)work.run();else if(item instanceof Consumer<?> consumer)((Consumer<BukkitTask>)consumer).accept(task);}return m.getReturnType()==BukkitTask.class?task:empty(m.getReturnType());});
            Server server=proxy(Server.class,(p,m,a)->switch(m.getName()){case "getName","getVersion","getBukkitVersion","getMinecraftVersion"->"API Fixture";case "getScheduler"->scheduler;case "getOnlinePlayers"->List.of();case "isPrimaryThread"->true;case "getLogger"->java.util.logging.Logger.getLogger("AppApiHarness");default->empty(m.getReturnType());});serverField.set(null,server);
            SimplexAdminSuite plugin=Moderation790Harness.plugin(dir);Moderation790Harness.set(plugin,JavaPlugin.class,"isEnabled",true);Moderation790Harness.set(plugin,SimplexAdminSuite.class,"adminActor",new ThreadLocal<String>());Moderation790Harness.set(plugin,SimplexAdminSuite.class,"pendingInputs",new ConcurrentHashMap<>());Moderation790Harness.set(plugin,SimplexAdminSuite.class,"pendingActions",new ConcurrentHashMap<>());
            plugin.adminAccess=new AdminAccessManager(plugin);plugin.adminUpdate=new AdminUpdateMenu(plugin);
            Moderation790Harness.set(plugin,SimplexAdminSuite.class,"unifiedMenu",new UnifiedAdminMenu(plugin));
            int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
            plugin.getConfig().set("app-api.enabled",true);plugin.getConfig().set("app-api.bind","127.0.0.1");plugin.getConfig().set("app-api.port",port);plugin.getConfig().set("app-api.transport","tls");
            api=new AppApiManager(plugin);plugin.appApi=api;api.reload();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(!api.active()&&System.nanoTime()<deadline)Thread.sleep(50);check(api.active(),"TLS listener started");
            String pin=(String)Moderation790Harness.get(api,"fingerprint");check(pin.matches("[a-f0-9]{64}"),"self-generated certificate fingerprint");
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification","true");SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(null,new TrustManager[]{new X509TrustManager(){public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}public void checkClientTrusted(X509Certificate[] c,String a){}public void checkServerTrusted(X509Certificate[] c,String a)throws java.security.cert.CertificateException{try{String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(c[0].getEncoded()));if(!actual.equals(pin))throw new java.security.cert.CertificateException("Wrong certificate");}catch(GeneralSecurityException e){throw new java.security.cert.CertificateException(e);}}}},new SecureRandom());
            client=HttpClient.newBuilder().sslContext(ssl).connectTimeout(java.time.Duration.ofSeconds(5)).build();base=URI.create("https://127.0.0.1:"+port);
            var read=api.security.issue("Read","read");var full=api.security.issue("Full","full");
            check(call("/v1/health",null,null).statusCode()==401,"no key denied");check(call("/v1/health","invalid",null).statusCode()==401,"invalid key denied");
            var health=call("/v1/health",read.token(),null);check(health.statusCode()==200&&object(DiscordBotManager.Json.parse(health.body())).get("serverId").equals(api.security.serverId()),"authenticated stable server identity");
            check(call("/v1/menu",read.token(),null).statusCode()==403,"read key cannot enter mutable menus");
            String action=json(SimplexAdminSuite.appMap("path","menu.action","values",SimplexAdminSuite.appMap("session","synthetic","revision","1","option","0")));
            check(call("/v1/prepare",read.token(),action).statusCode()==403,"read key cannot prepare mutations");
            var schema=call("/v1/schema",full.token(),null);check(schema.statusCode()==200&&schema.body().contains("player.punish")&&schema.body().contains("menu.action")&&schema.body().contains("security.ultraunban"),"native command schema covers core services");
            var menu=object(DiscordBotManager.Json.parse(call("/v1/menu",full.token(),null).body()));check(menu.get("title").equals("Admin-Zentrale"),"existing menu rendered through delegated actor");
            List<?> options=(List<?>)menu.get("options");check(options.size()>=8,"real original navigation callback list");
            Map<String,Object> option=options.stream().map(AppApiIntegrationHarness::object).filter(o->o.get("name").equals("Spieler")).findFirst().orElseThrow();
            String prepared=json(SimplexAdminSuite.appMap("path","menu.action","values",SimplexAdminSuite.appMap("session",menu.get("session"),"revision",String.valueOf(menu.get("revision")),"option",option.get("id"))));
            var prepare=call("/v1/prepare",full.token(),prepared);check(prepare.statusCode()==200,"prepare creates a bound ticket");String ticket=String.valueOf(object(DiscordBotManager.Json.parse(prepare.body())).get("ticket"));String execution=json(Map.of("ticket",ticket));
            var done=call("/v1/execute",full.token(),execution);check(done.statusCode()==200&&done.body().contains("Spieler"),"same existing menu callback executed once");check(call("/v1/execute",full.token(),execution).statusCode()==400,"replayed ticket rejected");
            var stalePlan=call("/v1/prepare",full.token(),prepared);String stale=String.valueOf(object(DiscordBotManager.Json.parse(stalePlan.body())).get("ticket"));check(call("/v1/execute",full.token(),json(Map.of("ticket",stale))).statusCode()==400,"stale menu revision rejected");
            check(client.send(HttpRequest.newBuilder(base.resolve("/v1/health")).header("Authorization","Bearer "+full.token()).header("Origin","https://browser.example").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==403,"browser Origin rejected");
            api.security.revoke(full.key().id());check(call("/v1/menu",full.token(),null).statusCode()==401,"revocation immediately denies remote menu");
            api.stop();check(!api.active(),"listener stops without changing Suite data");
            System.out.println("PASS "+checks+" production HTTPS/menu integration checks with synthetic Bukkit services");
        }finally{if(api!=null)api.stop();serverField.set(null,old);try(var stream=Files.walk(dir)){for(Path p:stream.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
