package eu.simplexsmp.adminsuite;

import java.util.*;

/** Fail closed on public/unknown explicit viewers before posting client metadata. */
final class PrivateLogChannel {
    private PrivateLogChannel(){ }
    static boolean allowed(Map<?,?> channel,String guild,Set<String> adminRoles,Set<String> adminUsers){try{
        if(!guild.equals(Objects.toString(channel.get("guild_id"),""))||!(channel.get("type") instanceof Number type)||!Set.of(0,5).contains(type.intValue())||!(channel.get("permission_overwrites") instanceof List<?> entries))return false;
        boolean privateChannel=false;
        for(Object item:entries){if(!(item instanceof Map<?,?> entry)||!(entry.get("type") instanceof Number kind))return false;String id=Objects.toString(entry.get("id"),"");long allow=Long.parseUnsignedLong(Objects.toString(entry.get("allow"),"")),deny=Long.parseUnsignedLong(Objects.toString(entry.get("deny"),""));
            if(kind.intValue()==0&&id.equals(guild)&&(deny&1024)!=0&&(allow&1024)==0)privateChannel=true;
            if((allow&1024)!=0&&!(kind.intValue()==0?adminRoles:adminUsers).contains(id))return false;
        }return privateChannel;
    }catch(RuntimeException invalid){return false;}}
}
