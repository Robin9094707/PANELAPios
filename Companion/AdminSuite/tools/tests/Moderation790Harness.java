package eu.simplexsmp.adminsuite;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Production-code checks for persistence, command safety, component styles and Discord command topology. */
public final class Moderation790Harness {
    static int checks;
    static void check(boolean v,String reason){checks++;if(!v)throw new AssertionError(reason);}
    static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,Class<?> type,String name,Object value)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static Object call(Object o,String name)throws Exception{Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(o);}
    @SuppressWarnings("unchecked") static <T> Map<String,T> map(Object o,String name)throws Exception{return (Map<String,T>)get(o,name);}
    static SimplexAdminSuite plugin(Path dir)throws Exception {
        Class<?> unsafeClass=Class.forName("sun.misc.Unsafe");Field f=unsafeClass.getDeclaredField("theUnsafe");f.setAccessible(true);Object unsafe=f.get(null);
        SimplexAdminSuite p=(SimplexAdminSuite)unsafeClass.getMethod("allocateInstance",Class.class).invoke(unsafe,SimplexAdminSuite.class);
        set(p,JavaPlugin.class,"dataFolder",dir.toFile());set(p,JavaPlugin.class,"newConfig",new YamlConfiguration());set(p,JavaPlugin.class,"logger",java.util.logging.Logger.getLogger("Moderation790Harness"));
        set(p,JavaPlugin.class,"description",new org.bukkit.plugin.PluginDescriptionFile("SimplexAdminSuite","8.4.0",SimplexAdminSuite.class.getName()));
        return p;
    }
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("sas790-");
        try{
            check(ModerationControl.modelKey("  iPhone　17  ").equals("iphone 17"),"Unicode/space model normalization");
            check(ModerationControl.modelKey("iPhone\u202E 17").equals("iphone 17"),"model bidi controls");
            check(ModerationControl.spawnCommand("/spawn")&&ModerationControl.spawnCommand("/SPAWN"),"spawn allowed");
            for(String command:List.of("/spawn hacker","/minecraft:spawn","/spawn\n/op x","/sas","/execute run spawn"))check(!ModerationControl.spawnCommand(command),"ghost command bypass: "+command);
            check(ModerationControl.chatTextCommand("/essentials:msg x &cred"),"namespaced messaging detection");
            check(!ModerationControl.chatTextCommand("/sas suiteconfig set x <red>"),"admin config not stripped as chat");
            check(ModerationControl.flags("text-peaceful").containsAll(Set.of("text","peaceful")),"combined preset");
            check(ModerationControl.normalizeMode("ghost").equals("ghost")&&ModerationControl.normalizeMode("legacy").equals("ultra"),"old mode safe migration");
            check(ModerationControl.protectedKey("discord-bot.token")&&ModerationControl.protectedKey("openai.api-key")&&ModerationControl.protectedKey("alt-detection.bridge.shared-secret"),"secret paths protected");
            check(ModerationControl.protectedKey("discord-status.webhook-url"),"webhook tokens protected");
            check(!ModerationControl.protectedKey("discord-bot.profile.description"),"public profile editable");
            Component original=Component.text("outer",NamedTextColor.RED).decorate(TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/op x")).append(Component.text("inner",NamedTextColor.BLUE).decorate(TextDecoration.ITALIC));
            Component stripped=ModerationControl.cleanComponent(original,true,true);
            check(stripped.color()==null&&stripped.clickEvent()==null&&stripped.decoration(TextDecoration.BOLD)==TextDecoration.State.NOT_SET,"body style and clicks removed");
            check(stripped.children().get(0).color()==null&&stripped.children().get(0).decoration(TextDecoration.ITALIC)==TextDecoration.State.NOT_SET,"nested styles removed");
            Component colorsOnly=ModerationControl.cleanComponent(original,true,false);
            check(colorsOnly.color()==null&&colorsOnly.decoration(TextDecoration.BOLD)==TextDecoration.State.TRUE,"independent colors toggle");
            String formatted="&cHello <gradient:red:blue>world</gradient><click:run_command:'/op x'>!</click><newline>spoof";
            check(SimplexAdminSuite.sanitizePlayerChatFormatting(formatted,true,true,false).equals("Hello world!spoof"),"legacy/mini formatting + newline spoof stripped");
            SimplexAdminSuite p=plugin(dir);ModerationControl first=new ModerationControl(p);
            UUID player=UUID.randomUUID();first.observeModel(player,"iPhone 17");
            Set<String> blocked=(Set<String>)get(first,"blockedModels");blocked.add("iphone 17");
            check(first.modelBlocked(player),"fresh model block");first.clearModel(player);check(!first.modelBlocked(player),"stale login model cannot block");
            Class<?> ruleClass=Class.forName("eu.simplexsmp.adminsuite.ModerationControl$Rule");Constructor<?> ruleCtor=ruleClass.getDeclaredConstructors()[0];ruleCtor.setAccessible(true);
            UUID source=UUID.randomUUID(),alt=UUID.randomUUID();Object rule=ruleCtor.newInstance(source,"text-peaceful","Fixture reason","Console",Long.MAX_VALUE,true);
            ((Set<UUID>)get(rule,"accounts")).add(alt);((Set<String>)get(rule,"identities")).add("DEV|device-hash");
            ((Map<UUID,Object>)get(first,"rules")).put(source,rule);call(first,"save");
            ModerationControl restored=new ModerationControl(p);
            check(((Set<String>)get(restored,"blockedModels")).contains("iphone 17"),"model block persisted");
            Object restoredRule=((Map<UUID,Object>)get(restored,"rules")).get(source);
            check(restoredRule!=null&&get(restoredRule,"preset").equals("text-peaceful")&&get(restoredRule,"until").equals(Long.MAX_VALUE),"permanent combined rule persisted");
            check(((Set<UUID>)get(restoredRule,"accounts")).contains(alt)&&((Set<String>)get(restoredRule,"identities")).contains("DEV|device-hash"),"existing/future alt anchors persisted");
            // Load a schema-2 ultra group that has no new mode field, then persist schema 3 without losing its identity/member.
            Path folder=dir.resolve("alt-detection");Files.createDirectories(folder);Path bans=folder.resolve("ultra-bans.yml");
            YamlConfiguration legacy=new YamlConfiguration();legacy.set("schema",2);legacy.set("count",1);String b="groups.0";
            legacy.set(b+".id","UB-OLD");legacy.set(b+".source-uuid",source.toString());legacy.set(b+".source-name","Fixture");legacy.set(b+".actor","Console");legacy.set(b+".reason","Fixture reason");legacy.set(b+".active",true);legacy.set(b+".until",Long.MAX_VALUE);
            legacy.set(b+".members-count",1);legacy.set(b+".members.0.uuid",source.toString());legacy.set(b+".members.0.name","Fixture");legacy.set(b+".members.0.trusted-expansion",true);
            legacy.set(b+".identities-count",1);legacy.set(b+".identities.0.type","DEV");legacy.set(b+".identities.0.value","device-hash");legacy.set(b+".identities.0.sources",List.of(source.toString()));legacy.save(bans.toFile());
            Class<?> altClass=Class.forName("eu.simplexsmp.adminsuite.SimplexAdminSuite$AdvancedAltDetection");Constructor<?> altCtor=altClass.getDeclaredConstructor(SimplexAdminSuite.class);altCtor.setAccessible(true);Object manager=altCtor.newInstance(p);
            call(manager,"loadUltraBans");Object group=map(manager,"ultraGroups").get("ub-old");
            check(get(group,"mode").equals("ultra"),"existing ban stays normal ultra");
            set(group,group.getClass(),"mode","ghost");call(manager,"saveUltraBans");call(manager,"loadUltraBans");group=map(manager,"ultraGroups").get("ub-old");
            check(get(group,"mode").equals("ghost")&&((Map<?,?>)get(group,"members")).size()==1&&((Map<?,?>)get(group,"identities")).size()==1,"mode migration preserves member/identity data");
            Class<?> profileClass=Class.forName("eu.simplexsmp.adminsuite.SimplexAdminSuite$PlayerProfile");Constructor<?> profileCtor=profileClass.getDeclaredConstructor(UUID.class,String.class);profileCtor.setAccessible(true);Object changed=profileCtor.newInstance(source,"Fixture");
            set(changed,profileClass,"lastIpHash","new-ip-hash");Method absorb=altClass.getDeclaredMethod("absorbProfile",group.getClass(),profileClass,boolean.class,String.class);absorb.setAccessible(true);absorb.invoke(manager,group,changed,true,"new IP");
            check(((Map<?,?>)get(group,"identities")).containsKey("IP|new-ip-hash"),"known account on a new IP extends group");
            set(group,group.getClass(),"active",false);Method match=altClass.getDeclaredMethod("matchAgainstGroup",group.getClass(),profileClass);match.setAccessible(true);
            check(match.invoke(manager,group,changed)==null,"deactivated group stops banning all members");
            DiscordAdminController controller=new DiscordAdminController(null,null);List<Object> commands=controller.guildCommands();
            String topology=DiscordBotManager.Json.stringify(commands);check(topology.contains("banmode")&&topology.contains("restrict")&&topology.contains("chatstyle"),"native Discord routes exposed");
            check(!controller.mutation(new DiscordAdminController.Request("moderation.status",Map.of()))&&controller.requiresConfirmation(new DiscordAdminController.Request("moderation.banmode",Map.of("mode","ghost"))),"Discord read-only/confirmation boundary");
            check(DiscordAdminController.allowedSuiteCommand(List.of("banmode","Fixture","none"))&&!DiscordAdminController.allowedSuiteCommand(List.of("password","secret")),"Discord rescue allowlist/credential isolation");
            System.out.println("PASS: "+checks+" moderation checks against production classes; no live server simulation.");
        }finally{try(var paths=Files.walk(dir)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
