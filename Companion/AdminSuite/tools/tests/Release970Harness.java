package eu.simplexsmp.adminsuite;
import java.nio.file.*;
import java.time.*;
import java.util.*;
/** Focused 9.7.0 checks: one-time setup links, quiet/working hours, emergency override, rank colours, curated settings. */
public final class Release970Harness {
    static int checks;static void check(boolean b,String reason){checks++;if(!b)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("suite970-");AppApiSecurity security=new AppApiSecurity(dir.resolve("api"));var owner=security.issue("Owner iPhone","full");
        security.users.save("Freundin","example-long-password",Set.of("data.players"),true);
        long now=System.currentTimeMillis();String code=security.users.setupCode("freundin",60,now);
        check(code.startsWith("sass_")&&security.users.setupExpiry("freundin")>now,"setup code issued and listed");
        String[] first=security.users.redeem(code,now);check(first!=null&&first[0].equals("freundin")&&security.authenticate(first[1])!=null,"setup code creates a session");
        check(security.users.redeem(code,now)==null,"setup code is single use");
        String expired=security.users.setupCode("freundin",5,now);check(security.users.redeem(expired,now+6*60_000L)==null,"expired setup code rejected");
        String replaced=security.users.setupCode("freundin",60,now);String newer=security.users.setupCode("freundin",60,now);check(security.users.redeem(replaced,now)==null&&security.users.redeem(newer,now)!=null,"new link replaces previous one");
        boolean unknown=false;try{security.users.setupCode("niemand",60,now);}catch(IllegalArgumentException e){unknown=true;}check(unknown,"no link for unknown users");
        security=new AppApiSecurity(dir.resolve("api"));check(security.authenticate(owner.token())!=null&&security.authenticate(first[1])!=null,"existing key and redeemed session survive restart");

        Properties p=new Properties();String pre="o.";p.setProperty(pre+"timezone","Europe/Berlin");
        Instant workday=ZonedDateTime.of(2026,11,2,10,0,0,0,ZoneId.of("Europe/Berlin")).toInstant(); // Monday 10:00
        Instant night=ZonedDateTime.of(2026,11,3,2,0,0,0,ZoneId.of("Europe/Berlin")).toInstant();
        Instant sunday=ZonedDateTime.of(2026,11,8,10,0,0,0,ZoneId.of("Europe/Berlin")).toInstant();
        check("critical".equals(BarkPolicy.decide(p,pre,"ban","critical",workday)),"no windows: critical stays");
        p.setProperty(pre+"workEnabled","true");
        check("timeSensitive".equals(BarkPolicy.decide(p,pre,"ban","critical",workday)),"working hours downgrade critical");
        check("active".equals(BarkPolicy.decide(p,pre,"join","active",workday)),"working hours keep normal notices");
        check("critical".equals(BarkPolicy.decide(p,pre,"ban","critical",sunday)),"weekend is outside working hours");
        p.setProperty(pre+"quietMode","all");
        check(BarkPolicy.decide(p,pre,"join","active",night)==null,"sleep mutes notices");
        check("critical".equals(BarkPolicy.decide(p,pre,"emergency-restart","active",night)),"emergency default pierces sleep");
        check("critical".equals(BarkPolicy.decide(p,pre,"emergency-restart","active",workday)),"emergency default pierces work");
        p.setProperty(pre+"emergencyOverride","work");check(BarkPolicy.decide(p,pre,"emergency-restart","active",night)==null,"emergency respects sleep when only work allowed");
        p.setProperty(pre+"emergencyOverride","sleep");check("timeSensitive".equals(BarkPolicy.decide(p,pre,"emergency-restart","active",workday)),"emergency downgraded at work when only sleep allowed");
        p.setProperty(pre+"emergencyEnabled","false");check(BarkPolicy.decide(p,pre,"emergency-restart","active",workday)==null,"emergency notice can be disabled");
        boolean badDay=false;try{BarkPolicy.days("1,8");}catch(IllegalArgumentException e){badDay=true;}check(badDay,"weekday range validated");

        check(VisualIdentityManager.normalize("&#FF5555[Admin]").equals("&x&f&f&5&5&5&5[Admin]"),"hex prefix normalized");
        check(VisualIdentityManager.normalize("<red>[Mod]</red>").equals("&c[Mod]&r"),"MiniMessage colour tags normalized");
        check(VisualIdentityManager.lastColor("§7[§cAdmin§7] §c")==org.bukkit.ChatColor.RED,"name takes last rank colour");
        check(VisualIdentityManager.lastColor("§x§f§f§5§5§5§5[A] ")==org.bukkit.ChatColor.RED,"hex maps to nearest colour");
        check(VisualIdentityManager.lastColor("§c[A]§r ")==null,"reset clears the name colour");
        boolean blocked=false;try{AppSettings.find("app-api.enabled");}catch(IllegalArgumentException e){blocked=true;}check(blocked,"protected settings are not exposed");
        check(AppSettings.find("nameplates.color-names").type().equals("bool"),"curated setting available");
        System.out.println("PASS: "+checks+" focused 9.7.0 checks");
    }
}
