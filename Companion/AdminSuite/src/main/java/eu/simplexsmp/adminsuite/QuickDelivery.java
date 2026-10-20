package eu.simplexsmp.adminsuite;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;

/** Durable inbox. Delay counts continuous admitted online time, reset at every reconnect. */
final class QuickDelivery implements Listener {
    private final SimplexAdminSuite plugin;private final EconomyStore store;
    private final Map<UUID,Long> online=new HashMap<>(),nextOffer=new HashMap<>();
    private BukkitTask task;
    private final Map<UUID,Draft> drafts=new HashMap<>();
    private static final class Draft {
        final UUID owner;String titleDe="Nachricht vom Serverteam",titleEn="Message from staff",de="",en="",language;
        int priority;boolean menu=true;
        Draft(UUID owner,boolean german){this.owner=owner;language=german?"de":"en";}
    }
    private final java.util.function.LongSupplier clock;
    QuickDelivery(SimplexAdminSuite p){this(p,System::currentTimeMillis);}
    QuickDelivery(SimplexAdminSuite p,java.util.function.LongSupplier clock){plugin=p;store=p.pointsEconomy.store;this.clock=clock;}
    void start(){task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20,20);}
    void stop(){if(task!=null)task.cancel();online.clear();nextOffer.clear();drafts.clear();}
    private boolean admin(Player p){return plugin.adminSessionUsable(p)&&plugin.adminMenuAllowed(p,"delivery.admin")&&plugin.has(p,"simplexadminsuite.delivery.admin");}
    private String t(Player p,String de,String en){return plugin.playerText(p,de,en);}
    private void tick(){
        long now=clock.getAsLong();
        for(Player p:Bukkit.getOnlinePlayers()) {
            if(plugin.adminRuntime.blocked(p)){online.remove(p.getUniqueId());continue;}
            long since=online.computeIfAbsent(p.getUniqueId(),id->now);
            if(now-since<delaySeconds()*1000L||now<nextOffer.getOrDefault(p.getUniqueId(),0L))continue;
            nextOffer.put(p.getUniqueId(),now+5000);
            try {
                var notes=store.notes(p.getUniqueId(),true);if(notes.isEmpty())continue;
                var n=notes.get(0);
                // Never replace verification, another inventory, or a currently shown native form.
                if(n.menu()&&(p.getOpenInventory().getTopInventory().getType()!=org.bukkit.event.inventory.InventoryType.CRAFTING||plugin.nativeFormOpen(p.getUniqueId())))continue;
                if(show(p,n))nextOffer.put(p.getUniqueId(),now+60000);
            }catch(Exception e){plugin.getLogger().warning("Quick Delivery: "+e.getMessage());}
        }
    }
    @EventHandler public void quit(PlayerQuitEvent e){online.remove(e.getPlayer().getUniqueId());nextOffer.remove(e.getPlayer().getUniqueId());drafts.remove(e.getPlayer().getUniqueId());}
    /** Delivery language is the recipient's actual client locale, independent of UI preferences. */
    boolean germanRecipient(UUID id){Player live=Bukkit.getPlayer(id);String saved=plugin.profileLocale(id);var profile=plugin.expansionProfile(id);String locale=live==null||profile!=null&&profile.clientPlatformValue().equals("BEDROCK")&&!saved.isBlank()?saved:live.getLocale();return locale!=null&&locale.toLowerCase(Locale.ROOT).replace('-','_').startsWith("de");}
    String recipientLanguage(UUID id){return germanRecipient(id)?"Deutsch":"Englisch";}
    int delaySeconds(){return Math.max(1,Math.min(300,plugin.getConfig().getInt("quick-delivery.delay-seconds",30)));}
    String create(String actor,UUID owner,String title,String text,int priority,boolean menu)throws Exception {
        return createNote(actor,owner,title,title,text,text,priority,menu,germanRecipient(owner)?"de":"en");
    }
    String create(String actor,UUID owner,String td,String te,String de,String en,int priority,boolean menu)throws Exception {
        return createNote(actor,owner,td,te,de,en,priority,menu,"auto");
    }
    private String createNote(String actor,UUID owner,String td,String te,String de,String en,int priority,boolean menu,String language)throws Exception {
        if(plugin.expansionProfile(owner)==null)throw new IllegalArgumentException("Spielerprofil nicht gefunden");
        td=clean(td,100);te=clean(te,100);de=clean(de,2000);en=clean(en,2000);
        if(td.isBlank()||te.isBlank()||de.isBlank()||en.isBlank()||priority<0||priority>2)throw new IllegalArgumentException("Titel, Nachricht und Priorität (0–2) erforderlich");
        if(store.notes(owner,true).size()>=100)throw new IllegalArgumentException("Maximal 100 offene Nachrichten je Spieler");
        String id=UUID.randomUUID().toString();store.note(new EconomyStore.Note(id,owner,actor,td,te,de,en,priority,menu,clock.getAsLong(),0,0,language));
        nextOffer.remove(owner);plugin.suiteAudit(actor,"Quick Delivery erstellt",owner+" / "+id+" / language="+language+" / priority="+priority+" / menu="+menu);return id;
    }
    static String noteTitle(EconomyStore.Note n,boolean german){return n.language().equals("de")||n.language().equals("auto")&&german?n.titleDe():n.titleEn();}
    static String noteBody(EconomyStore.Note n,boolean german){return n.language().equals("de")||n.language().equals("auto")&&german?n.de():n.en();}
    List<String> complete(CommandSender sender,String[] args){
        if(args.length==0)return List.of();
        boolean staff=plugin.has(sender,"simplexadminsuite.delivery.admin");List<String> values=new ArrayList<>();
        if(args.length==1){values.addAll(List.of("inbox","ack"));if(staff){values.addAll(List.of("admin","create","send","status"));for(var profile:plugin.expansionProfiles())values.add(profile.displayName());}}
        else if(args[0].equalsIgnoreCase("ack")&&args.length==2&&sender instanceof Player p){try{for(var n:store.notes(p.getUniqueId(),true))if(n.sent()>0)values.add(n.id());}catch(Exception ignored){}}
        else if(staff&&Set.of("create","send","status").contains(args[0].toLowerCase(Locale.ROOT))){
            if(args.length==2)for(var profile:plugin.expansionProfiles())values.add(profile.displayName());
            else if(!args[0].equalsIgnoreCase("status")){
                if(args.length==3)values.addAll(List.of("chat","menu"));
                if(args.length==4)values.addAll(List.of("0","1","2"));
                UUID owner=args.length>1?plugin.discordAdminResolvePlayer(args[1]):null;
                if(args.length==5&&owner!=null)values.add(germanRecipient(owner)?"Hinweis":"Notice");
                if(args.length==6&&!Arrays.asList(args).contains("|"))values.add("|");
            }
        }
        String prefix=args.length==0?"":args[args.length-1].toLowerCase(Locale.ROOT);return values.stream().distinct().filter(v->v.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
    private static String clean(String s,int max){if(s==null||s.length()>max)throw new IllegalArgumentException("Text zu lang");return s.replaceAll("[\\p{Cntrl}&&[^\\n]]","").replace('§',' ').strip();}
    private boolean show(Player p,EconomyStore.Note n)throws Exception {
        boolean de=germanRecipient(p.getUniqueId());
        String title=noteTitle(n,de),text=noteBody(n,de);
        if(n.menu()){
            boolean shown=plugin.communityMenus.show(p,title,text,List.of(CommunityMenus.row(de?"Gelesen und verstanden":"Read and understood",de?"Nachricht bestätigen":"Acknowledge message",Material.LIME_DYE,()->{ack(p,n.id());if(!plugin.isBedrockClient(p))p.closeInventory();})),0,true);
            if(!shown)return false;
            if(!plugin.isBedrockClient(p))p.sendMessage(plugin.prefix()+"§e"+title+"\n§f"+text);
        }else{
            p.sendMessage(plugin.prefix()+"§e"+title+"\n§f"+text);
            p.sendMessage(net.kyori.adventure.text.Component.text(de?"[Gelesen und verstanden]":"[Read and understood]")
                .color(net.kyori.adventure.text.format.NamedTextColor.GREEN)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/delivery ack "+n.id())));
            p.sendMessage("§7"+(de?"Oder eingeben: ":"Or type: ")+"/delivery ack "+n.id());
        }
        store.sent(n.id(),p.getUniqueId());p.playSound(p.getLocation(),n.priority()==2?"minecraft:block.note_block.chime":"minecraft:entity.experience_orb.pickup",0.7F,1.1F);
        return true;
    }
    private void ack(Player p,String id){try{if(store.ack(id,p.getUniqueId())){plugin.suiteAudit(p.getName(),"Quick Delivery bestätigt",id);p.sendMessage(plugin.prefix()+t(p,"Nachricht bestätigt.","Message acknowledged."));nextOffer.put(p.getUniqueId(),clock.getAsLong()+3000);}}catch(Exception e){p.sendMessage(plugin.prefix()+t(p,"Bestätigung konnte nicht gespeichert werden.","Could not save acknowledgement."));}}
    boolean command(CommandSender sender,String[] args){
        if(args.length>0&&Set.of("create","send").contains(args[0].toLowerCase(Locale.ROOT))){
            if(!plugin.has(sender,"simplexadminsuite.delivery.admin")||sender instanceof Player p&&!admin(p))return true;
            if(sender instanceof Player p&&args.length==2){openComposeTarget(p,args[1]);return true;}
            if(args.length<5){sender.sendMessage("/delivery create <Spieler> <chat|menu> <0|1|2> <Titel> | <Nachricht> (optional zweisprachig: Titel DE | Titel EN | Text DE | Text EN)");return true;}
            UUID owner=plugin.discordAdminResolvePlayer(args[1]);String[] f=String.join(" ",Arrays.copyOfRange(args,4,args.length)).split("\\|",-1);
            try{
                if(owner==null||!Set.of("chat","menu").contains(args[2].toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Spieler oder Modus prüfen");
                boolean de=germanRecipient(owner);String title,text;
                if(f.length==1){title=de?"Nachricht vom Serverteam":"Message from staff";text=f[0];}
                else if(f.length==2){title=f[0];text=f[1];}
                else if(f.length==4){title=de?f[0]:f[1];text=de?f[2]:f[3];}
                else throw new IllegalArgumentException("Nur Titel | Nachricht angeben");
                String note=f.length==4?create(sender.getName(),owner,f[0],f[1],f[2],f[3],Integer.parseInt(args[3]),args[2].equalsIgnoreCase("menu")):create(sender.getName(),owner,title,text,Integer.parseInt(args[3]),args[2].equalsIgnoreCase("menu"));
                sender.sendMessage("Teamnachricht gespeichert: "+note);
            }catch(Exception e){sender.sendMessage("Nicht erstellt: "+e.getMessage());}return true;
        }
        if(args.length==2&&args[0].equalsIgnoreCase("status")){
            if(!plugin.has(sender,"simplexadminsuite.delivery.admin")||sender instanceof Player p&&!admin(p))return true;
            try{UUID id=plugin.discordAdminResolvePlayer(args[1]);if(id==null)throw new IllegalArgumentException("Spieler nicht gefunden");sender.sendMessage(discordStatus(id,1));}catch(Exception e){sender.sendMessage("Status nicht verfügbar: "+e.getMessage());}return true;
        }
        if(!(sender instanceof Player p)){sender.sendMessage("/delivery create <Spieler> <chat|menu> <0|1|2> <Titel> | <Nachricht>");return true;}
        if(args.length>0&&args[0].equalsIgnoreCase("admin")){adminHome(p);return true;}
        if(args.length==1&&!Set.of("inbox","ack").contains(args[0].toLowerCase(Locale.ROOT))&&plugin.has(p,"simplexadminsuite.delivery.admin")){openComposeTarget(p,args[0]);return true;}
        if(args.length==2&&args[0].equalsIgnoreCase("ack")){ack(p,args[1]);return true;}
        inbox(p);return true;
    }
    void inbox(Player p){
        try{List<CommunityMenus.Row> rows=new ArrayList<>();for(var n:store.notes(p.getUniqueId(),false))rows.add(CommunityMenus.row(noteTitle(n,germanRecipient(p.getUniqueId())),n.ack()>0?t(p,"Bestätigt: ","Acknowledged: ")+plugin.profileDate(n.ack()):t(p,"Noch nicht bestätigt","Not yet acknowledged"),Material.PAPER,()->{try{show(p,n);}catch(Exception e){p.sendMessage(t(p,"Nachricht nicht verfügbar.","Message unavailable."));}}));plugin.communityMenus.show(p,t(p,"Deine Teamnachrichten","Your staff messages"),t(p,"Nur Nachrichten an dein Konto","Only messages addressed to your account"),rows,0,false);}catch(Exception e){plugin.menuError(p,t(p,"Nachrichten nicht verfügbar.","Messages unavailable."));}
    }
    void adminHome(Player p){adminHome(p,"");}
    private void adminHome(Player p,String search){
        if(!admin(p))return;List<AdminUpdateMenu.Button> rows=new ArrayList<>();
        rows.add(new AdminUpdateMenu.Button("Spieler suchen","Auch bereits bekannte Offline-Spieler • Teilname genügt",Material.COMPASS,()->plugin.adminUpdate.prompt(p,"Spielername oder Teilname",search,value->adminHome(p,value.strip()))));
        for(var profile:plugin.expansionProfiles().stream().filter(profile->profile.displayName().toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))).sorted(Comparator.comparing(SimplexAdminSuite.PlayerProfile::displayName,String.CASE_INSENSITIVE_ORDER)).toList()){
            String pending="";try{pending=" • "+store.notes(profile.uniqueId(),true).size()+" offen";}catch(Exception ignored){}
            rows.add(new AdminUpdateMenu.Button(profile.displayName(),"Sprache: "+recipientLanguage(profile.uniqueId())+" • "+(Bukkit.getPlayer(profile.uniqueId())==null?"Offline":"Online")+pending,Material.PLAYER_HEAD,()->adminPlayer(p,profile.uniqueId())));
        }
        plugin.adminUpdate.show(p,"Teamnachrichten • Spieler",rows,0);
    }
    private void openComposeTarget(Player p,String query){
        if(!admin(p))return;var matches=plugin.expansionProfiles().stream().filter(profile->profile.displayName().equalsIgnoreCase(query)).toList();
        if(matches.size()!=1)matches=plugin.expansionProfiles().stream().filter(profile->profile.displayName().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        if(matches.size()==1)compose(p,matches.get(0).uniqueId());else adminHome(p,query);
    }
    void adminPlayer(Player p,UUID id){
        if(!admin(p))return;try{
            List<AdminUpdateMenu.Button> rows=new ArrayList<>();rows.add(new AdminUpdateMenu.Button("Neue Nachricht","Offline vormerken • Vorschau vor dem Speichern • Deutsch/Englisch",Material.WRITABLE_BOOK,()->compose(p,id)));
            rows.add(AdminUpdateMenu.info("Zustellung", "Nach "+delaySeconds()+" Sekunden freier Online-Zeit; offene Menüs werden abgewartet. Erinnerung bis zur Bestätigung.",Material.CLOCK));
            for(var n:store.notes(id,false))rows.add(new AdminUpdateMenu.Button(noteTitle(n,germanRecipient(id)),"Priorität "+n.priority()+" • "+status(n,true),Material.PAPER,()->{
                List<AdminUpdateMenu.Button> detail=new ArrayList<>();detail.add(AdminUpdateMenu.info(noteTitle(n,germanRecipient(id)),noteBody(n,germanRecipient(id)),Material.PAPER));
                if(n.ack()==0)detail.add(new AdminUpdateMenu.Button("Offene Nachricht löschen","Löschbestätigung öffnen",Material.BARRIER,()->{
                    if(!admin(p))return;plugin.adminUpdate.show(p,"Nachricht wirklich löschen?",List.of(
                        new AdminUpdateMenu.Button("Ja, löschen",noteTitle(n,true),Material.REDSTONE_BLOCK,()->{if(!admin(p))return;try{store.deleteNote(n.id());plugin.suiteAudit(p.getName(),"Quick Delivery gelöscht",n.id());adminPlayer(p,id);}catch(Exception e){plugin.menuError(p,"§cLöschen fehlgeschlagen.");}}),
                        new AdminUpdateMenu.Button("Abbrechen","Nachricht behalten",Material.OAK_DOOR,()->adminPlayer(p,id))),0);
                }));
                detail.add(new AdminUpdateMenu.Button("Zurück","Nachrichten des Spielers",Material.OAK_DOOR,()->adminPlayer(p,id)));
                plugin.adminUpdate.show(p,"Nachrichtenstatus",detail,0);
            }));rows.add(new AdminUpdateMenu.Button("Zurück","Spieler",Material.OAK_DOOR,()->adminHome(p)));plugin.adminUpdate.show(p,"Teamnachrichten • "+plugin.expansionProfile(id).displayName(),rows,0);
        }catch(Exception e){plugin.menuError(p,"§cNachrichten nicht verfügbar.");}
    }
    private void compose(Player p,UUID owner){
        if(!admin(p))return;Draft draft=drafts.get(p.getUniqueId());
        if(draft==null||!draft.owner.equals(owner)){draft=new Draft(owner,germanRecipient(owner));drafts.put(p.getUniqueId(),draft);}
        edit(p,draft);
    }
    private boolean current(Player p,Draft d){return admin(p)&&drafts.get(p.getUniqueId())==d;}
    private static String languageName(String value){return value.equals("auto")?"Deutsch und Englisch":value.equals("de")?"Deutsch":"Englisch";}
    private void edit(Player p,Draft d){
        if(!current(p,d))return;
        if(plugin.isBedrockClient(p)){
            var form=SimplexAdminSuite.CustomForm.builder().title("Teamnachricht • Entwurf")
                .dropdown("Nachrichtensprache",List.of("Deutsch","Englisch","Deutsch und Englisch"),d.language.equals("de")?0:d.language.equals("en")?1:2)
                .input("Titel (Deutsch)","Bei englischer Nachricht nicht benötigt",d.titleDe).input("Nachricht (Deutsch)","Bei englischer Nachricht nicht benötigt",d.de)
                .input("Titel (Englisch)","Bei deutscher Nachricht nicht benötigt",d.titleEn).input("Nachricht (Englisch)","Bei deutscher Nachricht nicht benötigt",d.en)
                .dropdown("Priorität",List.of("Normal","Wichtig","Dringend"),d.priority).toggle("Menü mit Lesebestätigung",d.menu)
                .validResultHandler(r->plugin.runAdminTask(()->{if(!current(p,d))return;d.language=List.of("de","en","auto").get(r.asDropdown(0));d.titleDe=r.asInput(1);d.de=r.asInput(2);d.titleEn=r.asInput(3);d.en=r.asInput(4);d.priority=r.asDropdown(5);d.menu=r.asToggle(6);preview(p,d);}));
            if(!plugin.sendBedrockForm(p,form))plugin.menuError(p,"§cFormular nicht verfügbar. Entwurf bleibt erhalten.");return;
        }
        List<AdminUpdateMenu.Button> rows=new ArrayList<>();
        rows.add(AdminUpdateMenu.info("Empfänger: "+plugin.expansionProfile(d.owner).displayName(),"Wird gespeichert und beim nächsten freien Online-Zeitpunkt zugestellt.",Material.PLAYER_HEAD));
        rows.add(new AdminUpdateMenu.Button("Sprache: "+languageName(d.language),"Anklicken zum Wechseln",Material.BOOK,()->{if(!current(p,d))return;d.language=d.language.equals("de")?"en":d.language.equals("en")?"auto":"de";edit(p,d);}));
        if(!d.language.equals("en")){rows.add(new AdminUpdateMenu.Button("Titel (Deutsch)",d.titleDe,Material.NAME_TAG,()->plugin.adminUpdate.prompt(p,"Titel (Deutsch)",d.titleDe,v->{if(current(p,d)){d.titleDe=v;edit(p,d);}})));rows.add(new AdminUpdateMenu.Button("Nachricht (Deutsch)",d.de.isBlank()?"Hier den Text eingeben":d.de,Material.WRITABLE_BOOK,()->plugin.adminUpdate.prompt(p,"Nachricht (Deutsch)",d.de,v->{if(current(p,d)){d.de=v;edit(p,d);}})));}
        if(!d.language.equals("de")){rows.add(new AdminUpdateMenu.Button("Titel (Englisch)",d.titleEn,Material.NAME_TAG,()->plugin.adminUpdate.prompt(p,"Titel (Englisch)",d.titleEn,v->{if(current(p,d)){d.titleEn=v;edit(p,d);}})));rows.add(new AdminUpdateMenu.Button("Nachricht (Englisch)",d.en.isBlank()?"Hier den Text eingeben":d.en,Material.WRITABLE_BOOK,()->plugin.adminUpdate.prompt(p,"Nachricht (Englisch)",d.en,v->{if(current(p,d)){d.en=v;edit(p,d);}})));}
        rows.add(new AdminUpdateMenu.Button("Priorität: "+List.of("Normal","Wichtig","Dringend").get(d.priority),"Anklicken zum Wechseln",Material.BELL,()->{if(current(p,d)){d.priority=(d.priority+1)%3;edit(p,d);}}));
        rows.add(new AdminUpdateMenu.Button(d.menu?"Anzeige: Menü mit Lesebestätigung":"Anzeige: Chat mit Lesebestätigung","Anklicken zum Wechseln",Material.PAPER,()->{if(current(p,d)){d.menu=!d.menu;edit(p,d);}}));
        rows.add(new AdminUpdateMenu.Button("Vorschau und Speichern","Empfänger und Inhalt zuerst prüfen",Material.LIME_DYE,()->preview(p,d)));
        rows.add(new AdminUpdateMenu.Button("Entwurf verwerfen","Es wurde noch nichts gesendet",Material.BARRIER,()->{if(current(p,d)){drafts.remove(p.getUniqueId());adminPlayer(p,d.owner);}}));
        plugin.adminUpdate.show(p,"Teamnachricht • Entwurf",rows,0);
    }
    private void preview(Player p,Draft d){
        if(!current(p,d))return;
        try{if(!d.language.equals("en")&&(clean(d.titleDe,100).isBlank()||clean(d.de,2000).isBlank())||!d.language.equals("de")&&(clean(d.titleEn,100).isBlank()||clean(d.en,2000).isBlank()))throw new IllegalArgumentException("Titel und Nachricht für die gewählte Sprache ausfüllen");}
        catch(IllegalArgumentException invalid){plugin.menuError(p,"§c"+invalid.getMessage());edit(p,d);return;}
        List<AdminUpdateMenu.Button> rows=new ArrayList<>();rows.add(AdminUpdateMenu.info("Empfänger: "+plugin.expansionProfile(d.owner).displayName(),languageName(d.language)+" • "+(d.menu?"Menü":"Chat")+" • "+List.of("Normal","Wichtig","Dringend").get(d.priority),Material.PLAYER_HEAD));
        if(!d.language.equals("en"))rows.add(new AdminUpdateMenu.Button("Vollständige Vorschau (Deutsch)",d.titleDe+" • "+d.de,Material.BOOK,()->{if(current(p,d))plugin.adminUpdate.detail(p,d.titleDe,d.de,()->preview(p,d));}));if(!d.language.equals("de"))rows.add(new AdminUpdateMenu.Button("Vollständige Vorschau (Englisch)",d.titleEn+" • "+d.en,Material.BOOK,()->{if(current(p,d))plugin.adminUpdate.detail(p,d.titleEn,d.en,()->preview(p,d));}));
        rows.add(new AdminUpdateMenu.Button("Jetzt verbindlich speichern","Offline: wartet bis zum nächsten Beitritt • Online: Zustellung nach freiem Menü",Material.LIME_DYE,()->{
            if(!current(p,d))return;try{String td=d.language.equals("en")?d.titleEn:d.titleDe,te=d.language.equals("de")?d.titleDe:d.titleEn,de=d.language.equals("en")?d.en:d.de,en=d.language.equals("de")?d.de:d.en;
                createNote(p.getName(),d.owner,td,te,de,en,d.priority,d.menu,d.language);drafts.remove(p.getUniqueId());plugin.menuNotice(p,"§aTeamnachricht gespeichert. Sie bleibt bis zur Lesebestätigung offen.");adminPlayer(p,d.owner);
            }catch(Exception failure){plugin.menuError(p,"§cNicht gespeichert: "+failure.getMessage());}
        }));
        rows.add(new AdminUpdateMenu.Button("Weiter bearbeiten","Entwurf bleibt erhalten",Material.WRITABLE_BOOK,()->edit(p,d)));
        rows.add(new AdminUpdateMenu.Button("Entwurf verwerfen","Nachricht wird nicht gespeichert",Material.BARRIER,()->{if(current(p,d)){drafts.remove(p.getUniqueId());adminPlayer(p,d.owner);}}));
        plugin.adminUpdate.show(p,"Teamnachricht • Vorschau",rows,0);
    }
    private String status(EconomyStore.Note n,boolean de){return n.ack()>0?(de?"Bestätigt ":"Acknowledged ")+plugin.profileDate(n.ack()):n.sent()>0?(de?"Zugestellt, unbestätigt ":"Delivered, unacknowledged ")+plugin.profileDate(n.sent()):(de?"Wartet auf freie Online-Zeit (":"Waiting for admitted online time (")+delaySeconds()+" s)";}
    String discordStatus(UUID id,int page)throws Exception {var notes=store.notes(id,false);int start=Math.min(notes.size(),Math.max(0,page-1)*10);StringBuilder s=new StringBuilder();for(var n:notes.subList(start,Math.min(notes.size(),start+10)))s.append(n.id()).append(" • ").append(ServerStatistics.label(noteTitle(n,true))).append(" • ").append(status(n,true)).append('\n');return s.isEmpty()?"Keine Nachrichten.":s.toString();}
}
