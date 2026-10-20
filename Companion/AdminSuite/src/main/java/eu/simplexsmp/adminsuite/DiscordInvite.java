package eu.simplexsmp.adminsuite;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

final class DiscordInvite {
    private final SimplexAdminSuite plugin;
    DiscordInvite(SimplexAdminSuite p){plugin=p;}
    static String parseCode(String input){
        String code=input.trim().replaceFirst("(?i)^https?://(?:www\\.)?(?:discord\\.gg/|discord\\.com/invite/)","");
        if(!code.matches("[A-Za-z0-9_-]{2,64}"))throw new IllegalArgumentException("Gültigen Discord-Code oder discord.gg-Link eingeben.");
        return code;
    }
    String code(){return plugin.getConfig().getString("community.discord-invite-code","2gAsymnXfd");}
    boolean show(CommandSender sender){
        String code;
        try {code=parseCode(code());}catch(IllegalArgumentException e){sender.sendMessage(plugin.publicUiText(sender,"Discord invite is currently unavailable."));return true;}
        String url="https://discord.gg/"+code;
        if(sender instanceof Player p){
            p.sendMessage(Component.text("Discord » ",NamedTextColor.GREEN).append(Component.text(plugin.playerText(p,"Tritt unserer Community bei","Join our community"),NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(url)).hoverEvent(HoverEvent.showText(Component.text(url)))));
            if(plugin.isBedrockClient(p)){
                p.sendMessage(plugin.playerText(p,"§aDiscord-Einladungscode: §e","§aDiscord invite code: §e")+code+"\n§7"+url);
                plugin.sendBedrockForm(p,SimplexAdminSuite.SimpleForm.builder().playerFacing().title("Discord")
                    .content("Join our Discord community!\n\nInvite code: "+code+"\n\n"+url+"\n\nOpen Discord → Join a Server → enter the invite code.")
                    .button("Done",r->{}));
            }
        }else sender.sendMessage("Discord: "+url);
        return true;
    }
    boolean set(CommandSender s,String[] args){
        if(!plugin.has(s,"simplexadminsuite.settings") || s instanceof Player p && !plugin.adminSessionUsable(p)){s.sendMessage(s instanceof Player p?plugin.playerText(p,"§cKeine Einstellungsrechte.","§cMissing settings permission."):"Missing settings permission.");return true;}
        if(args.length!=1){s.sendMessage("/setdiscord <Code oder https://discord.gg/Code>");return true;}
        try{String value=parseCode(args[0]);plugin.getConfig().set("community.discord-invite-code",value);plugin.saveConfig();plugin.refreshDiscordInvites();plugin.suiteAudit(s.getName(),"Discord-Link","Code="+value);s.sendMessage((s instanceof Player p?plugin.playerText(p,"§aDiscord-Link gespeichert: ","§aDiscord invite saved: "):"Discord invite saved: ")+"https://discord.gg/"+value);}catch(IllegalArgumentException e){s.sendMessage("§c"+(s instanceof Player p?plugin.playerText(p,e.getMessage(),"Enter a valid Discord invite code or discord.gg link."):e.getMessage()));}
        return true;
    }
    void edit(Player p){plugin.adminUpdate.prompt(p,"Discord-Link oder Einladungscode",code(),v->{set(p,new String[]{v});plugin.unifiedMenu.category(p,"Community");});}
}
