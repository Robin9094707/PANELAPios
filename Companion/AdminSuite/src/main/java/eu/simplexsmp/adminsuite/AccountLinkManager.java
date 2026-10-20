package eu.simplexsmp.adminsuite;

import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.*;
import java.util.*;
import java.util.concurrent.*;

final class AccountLinkManager implements Listener {
    private final SimplexAdminSuite plugin;
    final AccountLinks store;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Simplex-Account-Links");t.setDaemon(true);return t;});
    private static final class Confirm implements InventoryHolder {
        final UUID target;final String expected;final boolean admin;
        Confirm(UUID target,String expected,boolean admin){this.target=target;this.expected=expected;this.admin=admin;}
        public Inventory getInventory(){return null;}
    }
    AccountLinkManager(SimplexAdminSuite p)throws java.io.IOException{plugin=p;store=new AccountLinks(p.getDataFolder().toPath().resolve("discord-links.properties"));}
    void stop(){io.shutdown();try{io.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    void submit(Runnable r){io.execute(r);}
    private boolean admin(Player p){return plugin.adminMenuAllowed(p,"discord.links")&&plugin.has(p,"simplexadminsuite.discord.links");}
    private String text(Player p,String german,String english){return plugin.has(p,"simplexadminsuite.admin")||plugin.community.german(p)?german:english;}
    boolean command(CommandSender sender,String[] args){
        if(!(sender instanceof Player p)){sender.sendMessage("Verknüpfungen: /sas links");return true;}
        p.sendMessage(text(p,"§eVerknüpfe nur dein eigenes Discord-Konto mit deinem eigenen Minecraft-Konto. Gib Codes niemals weiter und verwende keine Codes fremder Personen. Das verknüpfte Konto kann Inventar, Punkte und Standort sehen.","§eLink only your own Discord account to your own Minecraft account. Never share codes or use codes from other people. The linked account can view your inventory, points and location."));
        if(args.length==0||args[0].equalsIgnoreCase("status")){
            AccountLinks.Link l=store.byMinecraft(p.getUniqueId());p.sendMessage(l==null?text(p,"§e/link in Discord nutzen, dann hier /link <Code> eingeben.","§eUse /link in Discord, then enter the code here: /link <code>."):text(p,"§aDiscord-Konto: "+l.discord()+". Entknüpfen mit /link unlink.","§aLinked Discord account: "+l.discord()+". Use /link unlink to disconnect."));return true;
        }
        if(Set.of("unlink","entlinken","entknüpfen").contains(args[0].toLowerCase(Locale.ROOT))){
            AccountLinks.Link l=store.byMinecraft(p.getUniqueId());if(l==null)p.sendMessage(text(p,"§eDein Konto ist nicht verknüpft.","§eYour account is not linked."));else confirm(p,l,false);return true;
        }
        if(!args[0].matches("[0-9]{6}")){p.sendMessage("§e/link <6-digit code> | /link status | /link unlink");return true;}
        String code=args[0];UUID uuid=p.getUniqueId();String name=p.getName();
        submit(()->{try{AccountLinks.Link l=store.consume(uuid,name,code);plugin.runAdminTask(()->{
            if(p.isOnline())p.sendMessage(text(p,"§aMinecraft und Discord sind jetzt verknüpft. Discord → Minecraft ist freigeschaltet.","§aYour Minecraft and Discord accounts are now linked. Discord → Minecraft chat is enabled."));
            plugin.suiteAudit(name,"Discord verknüpft",l.discord());plugin.discordRelay().syncLinkedNickname(l,name);
        });}catch(Exception e){plugin.runAdminTask(()->{if(p.isOnline())p.sendMessage(plugin.playerUiText(p,"§c"+(e instanceof java.io.IOException?"Could not save your link. Please contact staff.":e.getMessage())));});}});return true;
    }
    void openAdmin(Player p){
        if(!admin(p))return;
        List<AdminUpdateMenu.Button> rows=new ArrayList<>();
        rows.add(new AdminUpdateMenu.Button("Manuell verknüpfen","Minecraft-Profil und Discord-ID auswählen",Material.TRIPWIRE_HOOK,()->selectPlayer(p)));
        for(AccountLinks.Link l:store.all())rows.add(new AdminUpdateMenu.Button(l.name(),"Discord: "+l.discord()+" • Klicken zum Entknüpfen",Material.PLAYER_HEAD,()->confirm(p,l,true)));
        rows.add(new AdminUpdateMenu.Button("Zurück","Admin-Zentrale",Material.OAK_DOOR,()->plugin.openMainFromModule(p)));
        plugin.adminUpdate.show(p,"Discord-Verknüpfungen",rows,0);
    }
    private void selectPlayer(Player p) {
        if(!admin(p))return;
        List<AdminUpdateMenu.Button> rows=new ArrayList<>();
        for(var profile:plugin.expansionProfiles()) {
            if(store.byMinecraft(profile.uniqueId())!=null)continue;
            rows.add(new AdminUpdateMenu.Button(profile.displayName(),"Noch nicht verknüpft",Material.PLAYER_HEAD,
                ()->plugin.adminUpdate.prompt(p,"Discord-Benutzer-ID","",id->manualLink(p,profile,id.trim()))));
        }
        rows.add(new AdminUpdateMenu.Button("Zurück","Verknüpfungen",Material.OAK_DOOR,()->openAdmin(p)));
        plugin.adminUpdate.show(p,"Minecraft-Spieler auswählen",rows,0);
    }
    private void manualLink(Player p,SimplexAdminSuite.PlayerProfile profile,String id) {
        if(!admin(p))return;
        submit(()->{try {
            plugin.discordRelay().validateLinkTarget(id);
            var l=store.link(profile.uniqueId(),id,profile.displayName());
            plugin.runAdminTask(()->{plugin.suiteAudit(p.getName(),"Discord manuell verknüpft",l.name()+" / "+l.discord());plugin.discordRelay().syncLinkedNickname(l,l.name());plugin.menuNotice(p,"§aVerknüpfung gespeichert.");openAdmin(p);});
        } catch(Exception e){plugin.runAdminTask(()->plugin.menuError(p,"§cVerknüpfung fehlgeschlagen: "+e.getMessage()));}});
    }
    private void confirm(Player p,AccountLinks.Link l,boolean administrative){
        if(administrative&&!admin(p))return;
        boolean german=administrative||plugin.has(p,"simplexadminsuite.admin")||plugin.community.german(p);
        String title=german?"Discord entknüpfen?":"Unlink Discord account?";
        String warning=german?"Discord-Chat-Zugriff wird gesperrt. Danach kann ein neuer Account verknüpft werden.":"Discord → Minecraft chat will be disabled. You can link another account afterwards.";
        Runnable yes=()->unlink(p,l,administrative);
        if(plugin.isBedrockClient(p)){
            boolean sent=plugin.sendBedrockForm(p,SimplexAdminSuite.SimpleForm.builder().title(title).content(warning)
                .button(german?"Entknüpfen":"Unlink",r->plugin.runAdminTask(yes))
                .button(german?"Abbrechen":"Cancel",r->{}));
            if(!sent)p.sendMessage(text(p,"§cBestätigung konnte nicht geöffnet werden. Bitte erneut versuchen.","§cCould not open confirmation. Please try again."));return;
        }
        Inventory inv=LiveMenus.create(new Confirm(l.minecraft(),l.discord(),administrative),27,title);
        inv.setItem(11,item(Material.LIME_DYE,german?"§cEntknüpfen":"§cUnlink",warning));
        inv.setItem(15,item(Material.BARRIER,german?"§aAbbrechen":"§aCancel",l.name()));LiveMenus.show(p,inv);
    }
    private static ItemStack item(Material material,String title,String detail){ItemStack i=new ItemStack(material);var m=i.getItemMeta();m.setDisplayName(MenuStyle.javaText(title));m.setLore(List.of(MenuStyle.javaText(detail)));i.setItemMeta(m);return i;}
    private void unlink(Player p,AccountLinks.Link l,boolean administrative){
        if(administrative?!admin(p):!p.getUniqueId().equals(l.minecraft()))return;
        submit(()->{try{boolean changed=store.unlink(l.minecraft(),l.discord());plugin.runAdminTask(()->{plugin.suiteAudit(p.getName(),"Discord entknüpft",l.name()+" / "+l.discord());if(p.isOnline())p.sendMessage(changed?(administrative?"§aVerknüpfung entfernt.":text(p,"§aDein Discord-Konto wurde entknüpft.","§aYour Discord account has been unlinked.")):text(p,"§eVerknüpfung bereits geändert; kein Konto entfernt.","§eLink changed already; no account was removed."));if(administrative&&p.isOnline())openAdmin(p);});}catch(Exception e){plugin.runAdminTask(()->p.sendMessage(text(p,"§cEntknüpfung konnte nicht gespeichert werden. Bitte das Team informieren.","§cCould not save unlink. Please contact staff.")));}});
    }
    @EventHandler public void click(InventoryClickEvent e){if(!(LiveMenus.holder(e.getInventory()) instanceof Confirm c))return;e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p))return;
        if(e.getRawSlot()==11){p.closeInventory();AccountLinks.Link l=store.byMinecraft(c.target);if(l!=null&&l.discord().equals(c.expected))unlink(p,l,c.admin);}else if(e.getRawSlot()==15)p.closeInventory();}
    @EventHandler public void drag(InventoryDragEvent e){if(LiveMenus.holder(e.getInventory()) instanceof Confirm)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e){Player p=e.getPlayer();var l=store.byMinecraft(p.getUniqueId());if(l!=null){String name=p.getName();submit(()->{try{var updated=store.rename(l.minecraft(),l.discord(),name);if(updated!=null)plugin.discordRelay().syncLinkedNickname(updated,name);}catch(java.io.IOException error){plugin.getLogger().warning("Discord-Kontoname konnte nicht aktualisiert werden.");}});}}
}
