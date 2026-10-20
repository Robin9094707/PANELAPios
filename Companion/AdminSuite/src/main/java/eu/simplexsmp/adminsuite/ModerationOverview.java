package eu.simplexsmp.adminsuite;

import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.*;
import java.sql.*;
import java.util.Date;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/** Source-specific read-through view. No import/migration and no direct writes to LiteBans SQL. */
final class ModerationOverview {
    record Subject(UUID id,String name,String ip,long banUntil,String banReason,String banBy,long muteUntil,String muteReason,String muteBy) {}
    record Line(String title,String detail,Material icon) {}
    private final SimplexAdminSuite plugin;
    private final Semaphore queries=new Semaphore(4);
    private final Map<UUID,Long> requests=new HashMap<>();
    ModerationOverview(SimplexAdminSuite p){plugin=p;}
    private boolean allowed(CommandSender s,boolean mute){return plugin.has(s,"simplexadminsuite.admin")&&plugin.has(s,mute?"simplexadminsuite.mutes":"simplexadminsuite.bans")&&(!(s instanceof Player p)||plugin.adminSessionUsable(p));}
    private boolean menuAllowed(Player p){return allowed(p,false)||allowed(p,true);}
    private AdminUpdateMenu.Button b(String title,String detail,Material icon,Runnable action){return new AdminUpdateMenu.Button(title,detail,icon,action);}
    boolean command(CommandSender sender,String command,String[] args){
        boolean mute=command.equals("checkmute");
        if(!allowed(sender,mute)){plugin.menuNotice(sender,"§cKeine Berechtigung für diese Abfrage.");return true;}
        if(args.length!=1){if(sender instanceof Player p)select(p);else sender.sendMessage("/"+command+" <Name oder UUID>");return true;}
        Subject target=plugin.moderationSubject(args[0]);
        if(target==null){plugin.menuNotice(sender,"§cKein bekanntes Profil. Exakten gespeicherten Namen oder UUID verwenden.");return true;}
        inspect(sender,target,mute?Set.of(true):Set.of(false),lines->{
            if(!allowed(sender,mute))return;
            sender.sendMessage("§a"+(mute?"Mute":"Ban")+"-Prüfung: §e"+target.name);
            for(Line line:lines)sender.sendMessage("§e"+line.title+" §7"+line.detail.replace('\n',' '));
        });return true;
    }
    void select(Player p){
        if(!menuAllowed(p))return;
        List<AdminUpdateMenu.Button> items=new ArrayList<>();
        if(allowed(p,false))items.add(b("Bannarten & Gruppen","Account, Ultra, leise, Ghost • aktive Gruppen und Entbannung",Material.IRON_BARS,()->plugin.moderationControl.players(p,"",false)));
        items.add(b("Spieler suchen","Exakter Name oder UUID • online und offline",Material.NAME_TAG,()->plugin.adminUpdate.prompt(p,"Spielername / UUID","",q->{
            Subject s=plugin.moderationSubject(q);if(s==null)plugin.menuNotice(p,"§cKein gespeichertes Profil gefunden.");else open(p,s);
        })));
        for(Player target:Bukkit.getOnlinePlayers())items.add(b(target.getName(),"Ban- und Mute-Status aller verfügbaren Quellen",Material.PLAYER_HEAD,()->open(p,plugin.moderationSubject(target.getUniqueId().toString()))));
        items.add(b("Minecraft-Banliste","Aktive Profil-, Namens- und IP-Sperren",Material.IRON_BARS,()->nativeList(p)));
        items.add(b("Interne aktive Strafen","Gespeicherte Simplex-Bans und -Mutes",Material.BOOK,()->internalList(p)));
        if(allowed(p,false))items.add(b("LiteBans-Banliste","Aktive Bans aller Scopes • API-Abfrage",Material.SHIELD,()->liteList(p,false,0)));
        if(allowed(p,true))items.add(b("LiteBans-Muteliste","Aktive Mutes aller Scopes • API-Abfrage",Material.BOOK,()->liteList(p,true,0)));
        items.add(b("Zurück","Moderation",Material.OAK_DOOR,()->plugin.unifiedMenu.category(p,"Moderation")));
        plugin.adminUpdate.show(p,"Bans & Mutes",items,0);
    }
    void open(Player p,Subject s){
        if(s==null||!menuAllowed(p))return;
        Set<Boolean> kinds=new HashSet<>();if(allowed(p,false))kinds.add(false);if(allowed(p,true))kinds.add(true);
        long revision=plugin.adminAccess.revision(p.getUniqueId());
        long request=requests.merge(p.getUniqueId(),1L,Long::sum);
        p.sendMessage("§7Strafstatus wird geladen …");
        inspect(p,s,kinds,lines->{
            if(!p.isOnline()||!menuAllowed(p)||revision!=plugin.adminAccess.revision(p.getUniqueId())||requests.getOrDefault(p.getUniqueId(),0L)!=request)return;
            List<AdminUpdateMenu.Button> items=new ArrayList<>();
            for(Line line:lines)items.add(b(line.title,line.detail,line.icon,()->detailPage(p,line.title,line.detail,List.of(b("Zurück","Spielerstatus",Material.ARROW,()->open(p,s))))));
            for(String source:List.of("LiteBans","Simplex intern","Minecraft")){
                if(source.equals("LiteBans")&&lite()==null)continue;
                items.add(b(source+" verwalten","Quelle gezielt auswählen • Änderungen erst nach Bestätigung",Material.ANVIL,()->manage(p,s,source)));
            }
            if(allowed(p,false))items.add(b("Bannart auswählen","Diesen Spieler klassisch, ultra, leise oder als Ghost bannen",Material.IRON_BARS,()->plugin.moderationControl.banTarget(p,s.id)));
            if(plugin.has(p,"simplexadminsuite.restrictions"))items.add(b("Einschränkungen","Vorlage für diesen Spieler anwenden oder aufheben",Material.WRITABLE_BOOK,()->plugin.moderationControl.restrictionTarget(p,s.id)));
            items.add(b("Bisheriges Strafmenü","Konfigurierter Anbieter, Kick und weitere Strafaktionen",Material.SHIELD,()->plugin.openUnifiedPunish(p,s.id)));
            items.add(b("Aktualisieren","Status erneut abfragen",Material.CLOCK,()->open(p,plugin.moderationSubject(s.id.toString()))));
            items.add(b("Zurück","Spielerauswahl",Material.OAK_DOOR,()->select(p)));
            plugin.adminUpdate.show(p,"Strafen: "+s.name,items,0);
        });
    }
    private void inspect(CommandSender sender,Subject s,Set<Boolean> kinds,Consumer<List<Line>> done){
        List<Line> base=new ArrayList<>();long now=System.currentTimeMillis();
        for(boolean mute:kinds){
            long until=mute?s.muteUntil:s.banUntil;
            base.add(new Line("Simplex • "+(mute?"Mute":"Ban")+": "+(until>now?"AKTIV":"frei"),until>now?"Grund: "+safe(mute?s.muteReason:s.banReason)+"\nVon: "+safe(mute?s.muteBy:s.banBy)+"\nBis: "+expiry(until):"Keine aktive interne Strafe",until>now?Material.RED_DYE:Material.LIME_DYE));
        }
        if(kinds.contains(false))nativeStatus(s,base);
        if(kinds.contains(true))base.add(new Line("Minecraft • Mute","Vanilla/Paper besitzt keine eigene Mute-Liste.",Material.PAPER));
        Plugin lite=lite();if(lite==null){base.add(new Line("LiteBans • nicht verfügbar","Nur lokal installiertes LiteBans kann über seine API abgefragt werden.",Material.GRAY_DYE));done.accept(base);return;}
        if(!queries.tryAcquire()){sender.sendMessage("§eEs laufen bereits mehrere Abfragen. Bitte gleich erneut versuchen.");return;}
        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            try{
                Class<?> dbClass=Class.forName("litebans.api.Database",true,lite.getClass().getClassLoader());
                Class<?> entryClass=Class.forName("litebans.api.Entry",true,lite.getClass().getClassLoader());
                Object db=dbClass.getMethod("get").invoke(null);
                for(boolean mute:kinds){
                    String type=mute?"Mute":"Ban";
                    Object active=dbClass.getMethod(mute?"getMute":"getBan",UUID.class,String.class,String.class).invoke(db,s.id,emptyNull(s.ip),"__ALL__");
                    if(active==null)base.add(new Line("LiteBans • "+type+": frei","Kein aktiver Treffer in allen Server-Scopes (UUID + letzte bekannte IP).",Material.LIME_DYE));
                    else base.add(new Line("LiteBans • "+type+": AKTIV","ID: "+get(entryClass,active,"getId")+"\nGrund: "+safe(get(entryClass,active,"getReason"))+"\nVon: "+safe(get(entryClass,active,"getExecutorName"))+"\nScope: "+get(entryClass,active,"getServerScope")+"\nRestzeit: "+entryClass.getMethod("getRemainingDurationString",long.class).invoke(active,System.currentTimeMillis()),Material.RED_DYE));
                    history(dbClass,db,s,mute,base);
                }
            }catch(ReflectiveOperationException|RuntimeException e){
                base.add(new Line("LiteBans • Abfrage fehlgeschlagen","Status unbekannt. /litebans:checkban bzw. /litebans:checkmute verwenden.",Material.BARRIER));
                plugin.getLogger().warning("LiteBans-Lesezugriff: "+e.getClass().getSimpleName()+" / "+(e.getCause()==null?e.getMessage():e.getCause().getClass().getSimpleName()));
            }finally{queries.release();}
            if(plugin.isEnabled())plugin.runAdminTask(()->done.accept(base));
        });
    }
    private void history(Class<?> clazz,Object db,Subject s,boolean mute,List<Line> lines){
        String sql="SELECT id,reason,time,until,active FROM {"+(mute?"mutes":"bans")+"} WHERE uuid=? ORDER BY id DESC LIMIT 10";
        try(PreparedStatement statement=(PreparedStatement)clazz.getMethod("prepareStatement",String.class).invoke(db,sql)){
            statement.setString(1,s.id.toString());statement.setQueryTimeout(5);
            try(ResultSet rows=statement.executeQuery()){
                StringBuilder text=new StringBuilder();while(rows.next())text.append('#').append(rows.getLong("id")).append(" • ").append(rows.getBoolean("active")&&(rows.getLong("until")<=0||rows.getLong("until")>System.currentTimeMillis())?"aktiv":"beendet").append(" • ").append(safe(rows.getString("reason"))).append('\n');
                lines.add(new Line("LiteBans • "+(mute?"Mute":"Ban")+"-Verlauf (10)",text.length()==0?"Keine UUID-Einträge":text.toString(),Material.BOOK));
            }
        }catch(Exception e){lines.add(new Line("LiteBans • Verlauf nicht verfügbar","Aktivstatus oben bleibt separat. API/SQL-Version prüfen.",Material.GRAY_DYE));}
    }
    private static Object get(Class<?> type,Object entry,String method)throws ReflectiveOperationException{return type.getMethod(method).invoke(entry);}
    private Plugin lite(){Plugin p=Bukkit.getPluginManager().getPlugin("LiteBans");return p!=null&&p.isEnabled()?p:null;}
    private static String emptyNull(String s){return s==null||s.isBlank()?null:s;}
    private static String safe(Object value){if(value==null)return "—";String s=ChatColor.stripColor(String.valueOf(value)).replaceAll("[\\r\\n]"," ");return s.length()>1000?s.substring(0,1000)+"…":s;}
    private static String expiry(long until){return until<=0||until==Long.MAX_VALUE?"permanent":java.time.Instant.ofEpochMilli(until).toString();}
    @SuppressWarnings({"deprecation","unchecked"})
    private void nativeStatus(Subject s,List<Line> lines){
        org.bukkit.profile.PlayerProfile profile=Bukkit.createPlayerProfile(s.id,s.name);
        BanEntry<?> profileBan=((org.bukkit.ban.ProfileBanList)Bukkit.getBanList(BanList.Type.PROFILE)).getBanEntry(profile);
        BanEntry<?> nameBan=Bukkit.getBanList(BanList.Type.NAME).getBanEntry(s.name==null?s.id.toString():s.name);
        BanEntry<?> ipBan=s.ip.isBlank()?null:Bukkit.getBanList(BanList.Type.IP).getBanEntry(s.ip);
        appendNative(lines,"Profil",profileBan);if(nameBan!=null&&profileBan==null)appendNative(lines,"Name",nameBan);if(s.ip.isBlank())lines.add(new Line("Minecraft • IP: unbekannt","Keine gespeicherte IP für dieses Profil. IP-Banliste separat öffnen.",Material.GRAY_DYE));else appendNative(lines,"IP",ipBan);
    }
    private void appendNative(List<Line> lines,String type,BanEntry<?> entry){
        boolean active=entry!=null&&(entry.getExpiration()==null||entry.getExpiration().after(new Date()));
        lines.add(new Line("Minecraft • "+type+": "+(active?"GESPERRT":"frei"),active?"Grund: "+safe(entry.getReason())+"\nVon: "+safe(entry.getSource())+"\nBis: "+(entry.getExpiration()==null?"permanent":entry.getExpiration().toInstant()):"Keine aktive Sperre",active?Material.RED_DYE:Material.LIME_DYE));
    }
    @SuppressWarnings({"deprecation","rawtypes"})
    private void nativeList(Player p){
        if(!allowed(p,false))return;
        List<AdminUpdateMenu.Button> items=new ArrayList<>();
        for(BanList.Type type:List.of(BanList.Type.PROFILE,BanList.Type.IP)){
            BanList list=Bukkit.getBanList(type);
            for(Object raw:list.getBanEntries()){
                BanEntry entry=(BanEntry)raw;
                if(entry.getExpiration()!=null&&!entry.getExpiration().after(new Date()))continue;
                Object target=entry.getBanTarget();
                String label=target instanceof org.bukkit.profile.PlayerProfile prof?(prof.getName()==null?String.valueOf(prof.getUniqueId()):prof.getName()):String.valueOf(target);
                String detail="Quelle: Minecraft • "+(type==BanList.Type.IP?"IP-Sperre":"Profilsperre")+"\nZiel: "+label
                    +(target instanceof org.bukkit.profile.PlayerProfile prof?"\nUUID: "+prof.getUniqueId():"")
                    +"\nGrund: "+safe(entry.getReason())+"\nVon: "+safe(entry.getSource())
                    +"\nErstellt: "+entry.getCreated()+"\nBis: "+(entry.getExpiration()==null?"permanent":entry.getExpiration())
                    +"\nAufhebung betrifft diese Minecraft-Sperre. Weitere Anbieter und Identitätssperren bleiben separat.";
                Date created=entry.getCreated();String reason=entry.getReason();
                items.add(b((type==BanList.Type.IP?"IP: ":"Profil: ")+label,"Grund: "+safe(entry.getReason())+" • Details öffnen",Material.IRON_BARS,()->{
                    if(!allowed(p,false))return;
                    List<AdminUpdateMenu.Button> actions=new ArrayList<>();
                    if(plugin.has(p,"simplexadminsuite.action.unban"))actions.add(b("Diesen Eintrag entbannen","Nur diese Minecraft-Sperre aufheben",Material.LIME_DYE,()->confirm(p,"Minecraft-Sperre aufheben?",detail,()->{
                        if(!allowed(p,false)||!plugin.has(p,"simplexadminsuite.action.unban"))return;
                        BanEntry current=list.getBanEntry(target);
                        if(current==null){p.sendMessage("§eSperre ist bereits aufgehoben.");nativeList(p);return;}
                        if(!Objects.equals(created,current.getCreated())||!Objects.equals(reason,current.getReason())){plugin.menuNotice(p,"§eSperre wurde zwischenzeitlich verändert. Bitte neu prüfen.");nativeList(p);return;}
                        try{list.pardon(target);boolean removed=list.getBanEntry(target)==null;
                            plugin.suiteAudit(p.getName(),"Minecraft-Unban",type+" "+label+" removed="+removed);
                            plugin.menuNotice(p,removed?"§aMinecraft-Sperre erfolgreich aufgehoben.":"§cSperre besteht weiterhin.");
                        }catch(RuntimeException error){plugin.menuNotice(p,"§cEntbannen fehlgeschlagen: "+error.getClass().getSimpleName());}
                        nativeList(p);
                    })));
                    actions.add(b("Zurück","Minecraft-Banliste",Material.ARROW,()->nativeList(p)));
                    detailPage(p,"Minecraft: "+label,detail,actions);
                }));
            }
        }
        if(items.isEmpty())items.add(AdminUpdateMenu.info("Keine aktiven Minecraft-Sperren","",Material.LIME_DYE));
        items.add(b("Aktualisieren","Banliste erneut lesen",Material.CLOCK,()->nativeList(p)));
        items.add(b("Zurück","Strafverwaltung",Material.OAK_DOOR,()->select(p)));plugin.adminUpdate.show(p,"Minecraft-Banliste",items,0);
    }
    private void internalList(Player p){
        if(!menuAllowed(p))return;
        List<AdminUpdateMenu.Button> items=new ArrayList<>();
        for(Subject s:plugin.moderationSubjects())if(allowed(p,false)&&s.banUntil>System.currentTimeMillis()||allowed(p,true)&&s.muteUntil>System.currentTimeMillis())
            items.add(b(s.name,"Aktive interne Strafe • öffnen",Material.PLAYER_HEAD,()->open(p,s)));
        items.add(b("Zurück","Strafverwaltung",Material.OAK_DOOR,()->select(p)));plugin.adminUpdate.show(p,"Interne Strafen",items,0);
    }
    private void manage(Player p,Subject s,String source){
        if(!menuAllowed(p))return;List<AdminUpdateMenu.Button> items=new ArrayList<>();
        for(String action:List.of("ban","tempban","unban","mute","tempmute","unmute")){
            boolean mute=action.contains("mute");if(source.equals("Minecraft")&&mute||!allowed(p,mute)||!plugin.has(p,"simplexadminsuite.action."+action))continue;
            items.add(b(action+" • "+s.name,"Anbieter: "+source,action.startsWith("un")?Material.LIME_DYE:Material.RED_DYE,()->{
                if(action.startsWith("un")){confirm(p,action+" bestätigen",source+" • "+s.name,()->apply(p,s,source,action,"","Removed by staff"));return;}
                plugin.adminUpdate.prompt(p,"Grund (für Spieler auf Englisch)","Violation of server rules",reason->{
                    if(reason.isBlank()||reason.length()>240){plugin.menuError(p,"§cGrund: 1–240 Zeichen.");return;}
                    if(action.startsWith("temp"))plugin.adminUpdate.prompt(p,"Dauer, z. B. 1h oder 7d","1d",duration->{
                        if(SimplexAdminSuite.DurationValue.parse(duration)==null){plugin.menuNotice(p,"§cUngültige Dauer.");return;}
                        confirm(p,action+" bestätigen",source+" • "+s.name+" • "+duration+" • "+reason,()->apply(p,s,source,action,duration,reason));
                    });else confirm(p,action+" bestätigen",source+" • "+s.name+" • "+reason,()->apply(p,s,source,action,"",reason));
                });
            }));
        }
        items.add(b("Zurück","Strafstatus",Material.OAK_DOOR,()->open(p,s)));plugin.adminUpdate.show(p,source+" verwalten",items,0);
    }
    private void confirm(Player p,String title,String detail,Runnable action){
        java.util.concurrent.atomic.AtomicBoolean used=new java.util.concurrent.atomic.AtomicBoolean();
        detailPage(p,title,detail,List.of(b("Bestätigen","Ausgewählte Aktion ausführen",Material.LIME_DYE,()->{if(used.compareAndSet(false,true))action.run();}),b("Abbrechen","Ohne Änderung",Material.BARRIER,()->select(p))));
    }
    private void detailPage(Player p,String title,String detail,List<AdminUpdateMenu.Button> actions){
        if(!menuAllowed(p))return;
        if(plugin.isBedrockClient(p)){
            long revision=plugin.adminAccess.revision(p.getUniqueId());
            SimplexAdminSuite.SimpleForm.Builder form=SimplexAdminSuite.SimpleForm.builder().title(title).content("§f"+detail);
            for(AdminUpdateMenu.Button action:actions)form.button("§a"+action.title(),r->plugin.runAdminTask(()->{if(menuAllowed(p)&&revision==plugin.adminAccess.revision(p.getUniqueId()))action.action().run();}));
            if(!plugin.sendBedrockForm(p,form))plugin.menuNotice(p,"§cDas Bedrock-Detailmenü konnte nicht geöffnet werden.");
        }else{
            List<AdminUpdateMenu.Button> rows=new ArrayList<>();
            for(String line:detail.split("\n"))rows.add(AdminUpdateMenu.info(line.contains(":")?line.substring(0,line.indexOf(':')):"Information",line,Material.PAPER));
            rows.addAll(actions);plugin.adminUpdate.show(p,title,rows,0);
        }
    }

    private record LiteRow(long id,String uuid,String reason,String executor,long until,String scope,long created,String ip,boolean ipban) {}
    private void liteList(Player p,boolean mute,int page){
        if(!allowed(p,mute))return;
        Plugin lite=lite();if(lite==null){p.sendMessage("§eLiteBans ist nicht lokal aktiv.");return;}
        if(!queries.tryAcquire()){p.sendMessage("§eBitte kurz warten und erneut versuchen.");return;}
        long revision=plugin.adminAccess.revision(p.getUniqueId());
        long request=requests.merge(p.getUniqueId(),1L,Long::sum);
        p.sendMessage("§7LiteBans-Liste wird geladen …");
        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            List<LiteRow> rows=new ArrayList<>();String error=null;
            try{
                Class<?> type=Class.forName("litebans.api.Database",true,lite.getClass().getClassLoader());Object db=type.getMethod("get").invoke(null);
                String sql="SELECT id,uuid,reason,banned_by_name,until,server_scope,time,ip,ipban FROM {"+(mute?"mutes":"bans")+"} WHERE active=? AND (until<=0 OR until>?) ORDER BY id DESC LIMIT 21 OFFSET "+(Math.max(0,page)*20);
                try(PreparedStatement statement=(PreparedStatement)type.getMethod("prepareStatement",String.class).invoke(db,sql)){
                    statement.setBoolean(1,true);statement.setLong(2,System.currentTimeMillis());statement.setQueryTimeout(5);
                    try(ResultSet result=statement.executeQuery()){while(result.next())rows.add(new LiteRow(result.getLong("id"),result.getString("uuid"),result.getString("reason"),result.getString("banned_by_name"),result.getLong("until"),result.getString("server_scope"),result.getLong("time"),result.getString("ip"),result.getBoolean("ipban")));}
                }
            }catch(Exception e){error=e.getClass().getSimpleName();}finally{queries.release();}
            String failure=error;
            if(plugin.isEnabled())plugin.runAdminTask(()->{
                if(!allowed(p,mute)||!p.isOnline()||revision!=plugin.adminAccess.revision(p.getUniqueId())||requests.getOrDefault(p.getUniqueId(),0L)!=request)return;
                if(failure!=null){plugin.menuNotice(p,"§cLiteBans-Liste konnte nicht geladen werden ("+failure+"). Status unbekannt.");return;}
                List<AdminUpdateMenu.Button> items=new ArrayList<>();
                for(LiteRow row:rows.subList(0,Math.min(20,rows.size()))){
                    Subject subject=row.uuid==null?null:plugin.moderationSubject(row.uuid);
                    String name=subject==null||subject.name==null?"UUID/IP-Sperre":subject.name;
                    String detail="ID: #"+row.id+"\nUUID: "+safe(row.uuid)+"\nGrund: "+safe(row.reason)+"\nVon: "+safe(row.executor)+"\nErstellt: "+new Date(row.created)+"\nIP-Sperre: "+(row.ipban?"Ja • "+safe(row.ip):"Nein")+"\nBis: "+expiry(row.until)+"\nScope: "+safe(row.scope);
                    items.add(b("#"+row.id+" • "+name,detail,Material.IRON_BARS,()->{
                        List<AdminUpdateMenu.Button> actions=new ArrayList<>();
                        if(subject!=null)actions.add(b("Spielerstatus öffnen",detail,Material.PLAYER_HEAD,()->open(p,subject)));
                        String action=mute?"unmute":"unban";
                        if(plugin.has(p,"simplexadminsuite.action."+action))actions.add(b("Diesen Eintrag aufheben","Nur LiteBans-ID #"+row.id,Material.LIME_DYE,()->confirm(p,"Eintrag aufheben?","LiteBans #"+row.id+" • "+detail,()->{
                            if(!allowed(p,mute)||!plugin.has(p,"simplexadminsuite.action."+action)||lite()==null)return;
                            boolean ok=Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"litebans:"+action+" "+row.id+" --sender="+p.getName().replace(' ','_')+" --sender-uuid="+p.getUniqueId());
                            plugin.suiteAudit(p.getName(),"LiteBans-Aufhebung",action+" id="+row.id+" dispatched="+ok);
                            p.sendMessage(ok?"§aAufhebung an LiteBans übergeben. Liste erneut öffnen, um den Status zu prüfen.":"§cLiteBans konnte die Aktion nicht ausführen.");select(p);
                        })));
                        actions.add(b("Zurück","LiteBans-Liste",Material.OAK_DOOR,()->liteList(p,mute,page)));
                        detailPage(p,"LiteBans #"+row.id,detail,actions);
                    }));
                }
                if(rows.isEmpty())items.add(AdminUpdateMenu.info("Keine aktiven Einträge","Für diese Seite liegen keine aktiven Strafen vor",Material.LIME_DYE));
                items.add(b("Aktualisieren","Diese Seite neu lesen",Material.CLOCK,()->liteList(p,mute,page)));
                if(page>0)items.add(b("Vorherige Seite","",Material.ARROW,()->liteList(p,mute,page-1)));
                if(rows.size()>20)items.add(b("Nächste Seite","",Material.ARROW,()->liteList(p,mute,page+1)));
                items.add(b("Zurück","Strafverwaltung",Material.OAK_DOOR,()->select(p)));
                plugin.adminUpdate.show(p,"LiteBans "+(mute?"Mutes":"Bans")+" • "+(page+1),items,0);
            });
        });
    }

    @SuppressWarnings({"deprecation","rawtypes","unchecked"})
    private void apply(Player p,Subject s,String source,String action,String duration,String reason){
        if(!allowed(p,action.contains("mute"))||!plugin.has(p,"simplexadminsuite.action."+action))return;
        boolean success=false;
        if(source.equals("LiteBans")){
            if(lite()==null){plugin.menuError(p,"§cLiteBans ist nicht verfügbar.");return;}
            success=Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"litebans:"+action+" "+s.id+(duration.isBlank()?"":" "+duration)+" --sender="+p.getName().replace(' ','_')+" --sender-uuid="+p.getUniqueId()+(action.startsWith("un")?"":" -- "+reason.replaceAll("[\\r\\n]"," ")));
        }else if(source.equals("Simplex intern"))success=plugin.moderationInternal(p,s.id,action,duration,reason);
        else if(source.equals("Minecraft")){
            org.bukkit.ban.ProfileBanList list=(org.bukkit.ban.ProfileBanList)Bukkit.getBanList(BanList.Type.PROFILE);org.bukkit.profile.PlayerProfile target=Bukkit.createPlayerProfile(s.id,s.name);
            if(action.equals("unban")){list.pardon(target);Bukkit.getBanList(BanList.Type.NAME).pardon(s.name==null?s.id.toString():s.name);}
            else{
                Date until=duration.isBlank()?null:new Date(Math.addExact(System.currentTimeMillis(),SimplexAdminSuite.DurationValue.parse(duration).toMillis()));
                list.addBan(target,reason,until,p.getName());Player online=Bukkit.getPlayer(s.id);if(online!=null)online.kickPlayer("You are banned from this server.\nReason: "+reason);
            }
            success=true;
        }
        plugin.suiteAudit(p.getName(),"Strafverwaltung",source+" "+action+" "+s.id+" duration="+duration+" reason="+reason+" dispatched="+success);
        plugin.menuNotice(p,success?"§aAktion an "+source+" übergeben. Status anschließend aktualisieren.":"§cAktion konnte nicht ausgeführt werden.");
        select(p);
    }
}
