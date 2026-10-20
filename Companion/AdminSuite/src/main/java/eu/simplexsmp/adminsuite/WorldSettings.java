package eu.simplexsmp.adminsuite;

import org.bukkit.*;
import org.bukkit.entity.Player;
import java.util.*;

/** Typed Bukkit access, discovered at runtime; no CraftBukkit/NMS version dependency. */
final class WorldSettings {
    private WorldSettings() {}
    static List<String> candidates(String requested) {
        String plain=requested.startsWith("minecraft:")?requested.substring(10):requested;
        String modern=switch(plain){
            case "doDaylightCycle" -> "advance_time";
            case "doWeatherCycle" -> "advance_weather";
            default -> plain.replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);
        };
        return new ArrayList<>(new LinkedHashSet<>(List.of(requested,plain,modern,"minecraft:"+modern)));
    }
    static Collection<GameRule<?>> registered() {
        List<GameRule<?>> rules=new ArrayList<>();
        try {
            Object registry=Registry.class.getField("GAME_RULE").get(null);
            if(registry instanceof Iterable<?> entries)for(Object entry:entries)if(entry instanceof GameRule<?> r)rules.add(r);
        }catch(ReflectiveOperationException|LinkageError ignored){}
        if(rules.isEmpty())try{
            Object values=GameRule.class.getMethod("values").invoke(null);
            if(values instanceof Object[] entries){for(Object entry:entries)if(entry instanceof GameRule<?> r)rules.add(r);}
            else if(values instanceof Collection<?> collection){for(Object item:collection)if(item instanceof GameRule<?> r)rules.add(r);}
        }catch(ReflectiveOperationException|LinkageError ignored){}
        return rules;
    }
    static String name(GameRule<?> rule){
        try{return String.valueOf(GameRule.class.getMethod("getKey").invoke(rule));}
        catch(ReflectiveOperationException ignored){try{return String.valueOf(GameRule.class.getMethod("getName").invoke(rule));}catch(ReflectiveOperationException error){return "";}}
    }
    static GameRule<?> resolve(String requested){
        List<String> names=candidates(requested);
        for(GameRule<?> rule:registered())if(names.contains(name(rule)))return rule;
        return null;
    }
    static List<String> names(){return registered().stream().map(WorldSettings::name).filter(s->!s.isBlank()).distinct().sorted().toList();}
    static String value(World world,String requested,String fallback){
        if(world==null)return fallback;
        GameRule<?> rule=resolve(requested);
        try{return rule==null?fallback:String.valueOf(world.getGameRuleValue(rule));}catch(RuntimeException error){return fallback;}
    }
    static Object parse(Class<?> type,String input){
        String value=input==null?"":input.trim();
        if(type==Boolean.class){if(!value.equalsIgnoreCase("true")&&!value.equalsIgnoreCase("false"))throw new IllegalArgumentException("true oder false erforderlich");return Boolean.valueOf(value);}
        if(type==Integer.class)return Integer.valueOf(value);
        throw new IllegalArgumentException("Nicht unterstützter Gamerule-Typ");
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static boolean set(World world,String requested,String input){
        if(world==null||Bukkit.getWorld(world.getUID())!=world)return false;
        GameRule rule=resolve(requested);if(rule==null)return false;
        try{Object parsed=parse(rule.getType(),input);return world.setGameRule(rule,parsed)&&Objects.equals(parsed,world.getGameRuleValue(rule));}
        catch(RuntimeException error){return false;}
    }
    static long beds(int eligible,int percent){return Math.max(1L,((long)Math.max(0,eligible)*Math.max(0,percent)+99L)/100L);}
    static String sleepSummary(World world){
        int eligible=0,sleeping=0;
        for(Player p:world.getPlayers())if(p.getGameMode()!=GameMode.SPECTATOR&&!p.isSleepingIgnored()){eligible++;if(p.isSleeping())sleeping++;}
        String raw=value(world,"playersSleepingPercentage","");
        int percent;try{percent=Integer.parseInt(raw);}catch(NumberFormatException e){return "Schlafquote nicht verfügbar";}
        if(percent>100)return "Nacht überspringen aus ("+percent+" %) • "+eligible+" Spieler";
        return "Betten: "+beds(eligible,percent)+" / "+eligible+" Spieler • schlafen: "+sleeping+" • "+percent+" %";
    }
}
