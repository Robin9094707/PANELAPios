package eu.simplexsmp.adminsuite;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

final class SimplexShop implements Listener {
    private final SimplexAdminSuite plugin;private final EconomyStore store;
    SimplexShop(SimplexAdminSuite p){plugin=p;store=p.pointsEconomy.store;}
    private String t(Player p,String de,String en){return plugin.playerText(p,de,en);}
    private boolean admin(Player p){return plugin.adminMenuAllowed(p,"shop.admin")&&plugin.has(p,"simplexadminsuite.shop.admin");}
    private boolean allowed(Player p){return p.isOnline()&&plugin.getConfig().getBoolean("shop.enabled",true)&&!plugin.adminRuntime.blocked(p)&&plugin.has(p,"simplexadminsuite.shop.use");}
    boolean command(CommandSender s,String[] args){
        if(!(s instanceof Player p)){s.sendMessage("/shop ist ein Minecraft-Menü. Shop ansehen: Discord /shop.");return true;}
        if(args.length>0&&args[0].equalsIgnoreCase("admin")){adminHome(p);return true;}
        if(!allowed(p)){p.sendMessage(plugin.prefix()+t(p,"Du darfst den Shop derzeit nicht verwenden.","You cannot use the shop right now."));return true;}
        open(p,0);return true;
    }
    void open(Player p,int page){
        if(!allowed(p))return;
        try {
            deliverPending(p);List<CommunityMenus.Row> rows=new ArrayList<>();long now=System.currentTimeMillis();
            for(var o:store.offers())if(o.enabled()&&(o.expires()==0||o.expires()>now)){
                String name=t(p,o.de(),o.en());String detail=cost(o)+" • "+t(p,"Bestand: ","Stock: ")+(o.stock()<0?t(p,"unendlich","unlimited"):o.stock());
                if(o.limit()>0)detail+=" • "+t(p,"Deine Käufe: ","Your purchases: ")+store.bought(p.getUniqueId(),o.id())+"/"+o.limit();
                if(o.expires()>0)detail+=" • "+t(p,"Ende: ","Ends: ")+plugin.profileDate(o.expires());
                rows.add(new CommunityMenus.Row(name,detail,ItemStack.deserializeBytes(o.item()),o.icon(),()->confirm(p,o)));
            }
            if(rows.isEmpty())rows.add(CommunityMenus.row(t(p,"Noch keine Angebote","No offers yet"),"",Material.PAPER,()->open(p,0)));
            if(plugin.has(p,"simplexadminsuite.shop.admin")&&plugin.adminSessionUsable(p))rows.add(CommunityMenus.row("Shop verwalten","Artikel, Bestand, Preise und Limits",Material.COMPARATOR,()->adminHome(p)));
            plugin.communityMenus.show(p,t(p,"Simplex-Shop","Simplex Shop"),t(p,"Dein Guthaben: ","Your balance: ")+plugin.getPointsApi().balance(p.getUniqueId())+" SP",rows,page,false);
        }catch(Exception e){error(p,e);}
    }
    private void confirm(Player p,EconomyStore.Offer o){
        String request=UUID.randomUUID().toString();
        plugin.communityMenus.show(p,t(p,"Kauf bestätigen","Confirm purchase"),t(p,o.de(),o.en())+"\n"+cost(o)+"\n"+t(p,"Ein Kauf liefert ein Paket wie angezeigt.","One purchase delivers one displayed bundle."),List.of(
            CommunityMenus.row(t(p,"Kaufen","Buy"),cost(o),Material.EMERALD,()->buy(p,o,request)),
            CommunityMenus.row(t(p,"Abbrechen","Cancel"),"",Material.BARRIER,()->open(p,0))),0,true);
    }
    private static String paymentName(byte[] bytes){ItemStack item=ItemStack.deserializeBytes(bytes);var meta=item.getItemMeta();return meta!=null&&meta.hasDisplayName()?MenuStyle.plain(meta.getDisplayName()):item.getType().name().toLowerCase(Locale.ROOT).replace('_',' ');}
    private static String cost(EconomyStore.Offer o){return o.itemPayment()?o.price()+" × "+paymentName(o.currency()):o.price()+" SP";}
    private static String cost(EconomyStore.Receipt r){return r.itemPayment()?r.price()+" × "+paymentName(r.currency()):r.price()+" SP";}
    private void buy(Player p,EconomyStore.Offer o,String request){
        if(!allowed(p))return;
        try {
            if(store.receipt(request)!=null){open(p,0);return;}
            ItemStack item=ItemStack.deserializeBytes(o.item());EconomyStore.Receipt receipt;
            if(o.itemPayment()){
                // The complete plan includes space freed by payment. Metadata must match exactly.
                ItemStack[] after=ItemExchange.plan(p.getInventory().getStorageContents(),ItemStack.deserializeBytes(o.currency()),Math.toIntExact(o.price()),item);
                receipt=store.purchaseItem(p.getUniqueId(),o.id(),o.revision(),request);
                // This durable claim precedes the one inventory mutation. Ambiguous crashes stay held.
                p.getInventory().setStorageContents(after);p.saveData();store.transition(receipt.id(),"DELIVERING","DELIVERED");
                purchaseMessage(p);
            }else{
                if(!fits(p,item))throw new IllegalArgumentException("Insufficient inventory space");
                receipt=store.purchase(p.getUniqueId(),o.id(),o.revision(),request);plugin.pointsEconomy.mirror(p.getUniqueId());deliver(p,receipt);
            }
            plugin.suiteAudit(p.getName(),"Shop-Kauf",o.id()+" / "+receipt.id()+" / "+cost(receipt));open(p,0);
        }catch(IllegalArgumentException e){p.sendMessage(plugin.prefix()+t(p,"Kauf nicht möglich: Angebot, Bestand, Limit, Zahlungsmittel oder Inventarplatz prüfen.","Purchase unavailable: check offer, stock, limit, payment items / points and inventory space."));open(p,0);}catch(Exception e){error(p,e);}
    }
    private void purchaseMessage(Player p){p.sendMessage(plugin.prefix()+t(p,"Kauf zugestellt. Vielen Dank!","Purchase delivered. Thank you!"));p.playSound(p.getLocation(),"minecraft:entity.experience_orb.pickup",0.6F,1.2F);}
    static boolean fits(ItemStack[] slots,ItemStack item){long space=0;for(ItemStack slot:slots){if(slot==null||slot.getType().isAir())space+=item.getMaxStackSize();else if(slot.isSimilar(item))space+=Math.max(0,Math.min(slot.getMaxStackSize(),item.getMaxStackSize())-slot.getAmount());}return space>=item.getAmount();}
    private boolean fits(Player p,ItemStack i){return fits(p.getInventory().getStorageContents(),i);}
    private void deliver(Player p,EconomyStore.Receipt r)throws Exception {
        boolean refund=r.state().equals("REFUND_PENDING");
        if(!p.getUniqueId().equals(r.owner())||!r.state().equals("PENDING")&&!refund)return;
        ItemStack item=ItemStack.deserializeBytes(refund?r.currency():r.item());if(refund)item.setAmount(Math.toIntExact(r.price()));if(!fits(p,item))return;
        if(!store.transition(r.id(),refund?"REFUND_PENDING":"PENDING",refund?"REFUNDING":"DELIVERING"))return;
        // Persist the player inventory before marking completion. Ambiguous crash receipts are held,
        // never automatically replayed; an admin can inspect and settle them without duplication.
        if(!p.getInventory().addItem(item).isEmpty())throw new IllegalStateException("Inventory changed during delivery");
        p.saveData();store.transition(r.id(),refund?"REFUNDING":"DELIVERING",refund?"REFUNDED":"DELIVERED");
        p.sendMessage(plugin.prefix()+t(p,refund?"Zahlungsitems erstattet.":"Kauf zugestellt. Vielen Dank!",refund?"Payment items refunded.":"Purchase delivered. Thank you!"));p.playSound(p.getLocation(),"minecraft:entity.experience_orb.pickup",0.6F,1.2F);
    }
    private void deliverPending(Player p)throws Exception {for(var r:store.pending(p.getUniqueId()))deliver(p,r);}
    @EventHandler(priority=EventPriority.MONITOR)public void join(PlayerJoinEvent e){Player p=e.getPlayer();Bukkit.getScheduler().runTaskLater(plugin,()->{if(!p.isOnline())return;plugin.pointsEconomy.attach(plugin.expansionProfile(p));try{deliverPending(p);}catch(Exception x){error(p,x);}},60);}
    void adminHome(Player p){
        if(!admin(p))return;try {
            List<AdminUpdateMenu.Button> rows=new ArrayList<>();
            rows.add(new AdminUpdateMenu.Button("Artikel aus Haupthand hinzufügen","Item inklusive Metadaten als Paket anbieten; kein Item wird entfernt",Material.CHEST,()->add(p)));
            for(var o:store.offers())if(o.enabled())rows.add(new AdminUpdateMenu.Button(o.de(),o.id()+" • "+cost(o)+" • Bestand "+o.stock(),Material.CHEST,()->edit(p,o.id())));
            for(var r:store.held())rows.add(new AdminUpdateMenu.Button("Zustellung prüfen: "+r.id().substring(0,8),r.owner()+" • "+cost(r)+" • "+r.offer()+" • "+r.state(),Material.HOPPER,()->settle(p,r)));
            rows.add(new AdminUpdateMenu.Button("Zurück","Community",Material.OAK_DOOR,()->plugin.unifiedMenu.open(p)));
            plugin.adminUpdate.show(p,"Shop-Verwaltung",rows,0);
        }catch(Exception e){error(p,e);}
    }
    private void add(Player p){
        if(!admin(p))return;ItemStack item=p.getInventory().getItemInMainHand().clone();if(item.getType().isAir()){plugin.menuError(p,"§cHalte ein Item oder Paket in der Haupthand.");return;}
        String id=UUID.randomUUID().toString();editFields(p,new EconomyStore.Offer(id,item.getType().name(),item.getType().name(),item.serializeAsBytes(),10,-1,0,0,"textures/items/emerald",1,true),true);
    }
    private void edit(Player p,String id){
        if(!admin(p))return;try{var o=store.offer(id);if(o==null)return;plugin.adminUpdate.show(p,"Artikel bearbeiten",List.of(
            new AdminUpdateMenu.Button("Einstellungen","Namen, Preis, Bestand, Limit, Laufzeit und Bedrock-Icon",Material.COMPARATOR,()->editFields(p,o,false)),
            new AdminUpdateMenu.Button("Item aus Haupthand übernehmen","Ersetzt das Paket inklusive aller Item-Metadaten",Material.CHEST,()->{if(!admin(p))return;ItemStack i=p.getInventory().getItemInMainHand().clone();if(i.getType().isAir())return;try{var fresh=store.offer(id);store.put(new EconomyStore.Offer(id,fresh.de(),fresh.en(),i.serializeAsBytes(),fresh.price(),fresh.stock(),fresh.limit(),fresh.expires(),fresh.icon(),fresh.revision(),true,fresh.currency()));adminHome(p);}catch(Exception e){error(p,e);}}),
            new AdminUpdateMenu.Button("Zahlungsitem aus Haupthand", "Aktuellen Stack als Preis übernehmen; Metadaten müssen beim Bezahlen übereinstimmen", Material.DIAMOND, ()->setPayment(p,id,true)),
            new AdminUpdateMenu.Button("Mit Simplex-Punkten bezahlen", "Zahlung auf SP umstellen; Preis danach unter Einstellungen bearbeiten", Material.EMERALD, ()->setPayment(p,id,false)),
            new AdminUpdateMenu.Button("Artikel deaktivieren","Bereits bezahlte Käufe werden weiter zugestellt",Material.BARRIER,()->{if(!admin(p))return;plugin.adminUpdate.show(p,"Deaktivieren bestätigen",List.of(new AdminUpdateMenu.Button("Ja, deaktivieren",o.de(),Material.RED_DYE,()->{if(!admin(p))return;try{store.remove(id);plugin.suiteAudit(p.getName(),"Shop deaktiviert",id);adminHome(p);}catch(Exception e){error(p,e);}}),new AdminUpdateMenu.Button("Abbrechen","",Material.LIME_DYE,()->adminHome(p))),0);}),
            new AdminUpdateMenu.Button("Zurück","Shop",Material.OAK_DOOR,()->adminHome(p))),0);
        }catch(Exception e){error(p,e);}
    }
    private void editFields(Player p,EconomyStore.Offer o,boolean creating){
        if(!admin(p))return;
        if(plugin.isBedrockClient(p)) {
            var form=SimplexAdminSuite.CustomForm.builder().title("Shop-Artikel bearbeiten")
                .input("Name Deutsch","",o.de()).input("Name Englisch","",o.en()).input(o.itemPayment()?"Preis: Anzahl passender Zahlungsitems":"Preis (SP)","",Long.toString(o.price()))
                .input("Bestand: -1 unendlich, 0 ausverkauft","",Long.toString(o.stock())).input("Kauflimit je Spieler: 0 unendlich","",Long.toString(o.limit()))
                .input("Laufzeit ab jetzt (Stunden): 0 dauerhaft, leer unverändert","","").input("Bedrock-Icon: textures/items/emerald","",o.icon())
                .validResultHandler(r->plugin.runAdminTask(()->saveFields(p,o,creating,r.asInput(0),r.asInput(1),r.asInput(2),r.asInput(3),r.asInput(4),r.asInput(5),r.asInput(6))));
            if(!plugin.sendBedrockForm(p,form))plugin.menuError(p,"§cFormular konnte nicht geöffnet werden.");return;
        }
        editDraft(p,o,creating,new ArrayList<>(List.of(o.de(),o.en(),Long.toString(o.price()),Long.toString(o.stock()),Long.toString(o.limit()),"unverändert",o.icon())));
    }
    private void editDraft(Player p,EconomyStore.Offer o,boolean creating,List<String> values){
        if(!admin(p))return;
        String[] labels={"Name Deutsch","Name Englisch",o.itemPayment()?"Preis: Anzahl Zahlungsitems":"Preis in SP","Bestand (-1 = unendlich)","Kauflimit je Spieler (0 = unendlich)","Laufzeit in Stunden (0 = dauerhaft)","Bedrock-Icon (textures/...)"};
        List<AdminUpdateMenu.Button> rows=new ArrayList<>();
        for(int n=0;n<labels.length;n++){final int i=n;rows.add(new AdminUpdateMenu.Button(labels[n],values.get(n),Material.PAPER,()->plugin.adminUpdate.prompt(p,labels[i],values.get(i),v->{if(!admin(p))return;values.set(i,v.trim());editDraft(p,o,creating,values);})) );}
        rows.add(new AdminUpdateMenu.Button("Speichern","Artikel mit diesen Einstellungen anbieten",Material.EMERALD,()->saveFields(p,o,creating,values.get(0),values.get(1),values.get(2),values.get(3),values.get(4),values.get(5),values.get(6))));
        rows.add(new AdminUpdateMenu.Button("Abbrechen","Änderungen verwerfen",Material.BARRIER,()->adminHome(p)));
        plugin.adminUpdate.show(p,"Shop-Artikel bearbeiten",rows,0);
    }

    private void saveFields(Player p,EconomyStore.Offer old,boolean creating,String de,String en,String price,String stock,String limit,String hours,String icon){
        if(!admin(p))return;try {
            if(de.isBlank()||en.isBlank()||de.length()>100||en.length()>100||!icon.matches("textures/[a-zA-Z0-9_/-]{1,160}"))throw new IllegalArgumentException("Namen/Texture-Pfad prüfen");
            var fresh=store.offer(old.id());if(!creating&&(fresh==null||fresh.revision()!=old.revision()))throw new IllegalArgumentException("Artikel wurde zwischenzeitlich bearbeitet");
            long expires=old.expires();if(!hours.isBlank()&&!hours.equalsIgnoreCase("unverändert")){long h=Long.parseLong(hours);if(h<0||h>8760)throw new IllegalArgumentException("Laufzeit 0–8760 Stunden");expires=h==0?0:Math.addExact(System.currentTimeMillis(),h*3600000);}
            store.put(new EconomyStore.Offer(old.id(),de,en,old.item(),Long.parseLong(price),Long.parseLong(stock),Long.parseLong(limit),expires,icon,old.revision(),true,old.currency()));
            plugin.suiteAudit(p.getName(),"Shop-Artikel gespeichert",old.id());plugin.menuNotice(p,"§aArtikel gespeichert.");adminHome(p);
        }catch(Exception e){plugin.menuError(p,"§cNicht gespeichert: "+e.getMessage());}
    }
    private void setPayment(Player p,String id,boolean itemPayment){
        if(!admin(p))return;try{var o=store.offer(id);if(o==null)return;byte[] currency=new byte[0];long price=o.price();
            if(itemPayment){ItemStack payment=p.getInventory().getItemInMainHand().clone();if(payment.getType().isAir())throw new IllegalArgumentException("Zahlungsitem in der Haupthand halten");price=payment.getAmount();payment.setAmount(1);currency=payment.serializeAsBytes();}
            store.put(new EconomyStore.Offer(o.id(),o.de(),o.en(),o.item(),price,o.stock(),o.limit(),o.expires(),o.icon(),o.revision(),o.enabled(),currency));
            plugin.suiteAudit(p.getName(),"Shop-Zahlungsart geändert",id+" / "+(itemPayment?"Item":"SP"));edit(p,id);
        }catch(Exception e){plugin.menuError(p,"§cNicht geändert: "+e.getMessage());}
    }
    private void settle(Player p,EconomyStore.Receipt r){
        if(!admin(p))return;List<AdminUpdateMenu.Button> rows=new ArrayList<>();boolean returning=r.state().equals("REFUNDING");
        rows.add(new AdminUpdateMenu.Button(returning?"Erstattung angekommen":"Als zugestellt bestätigen","Vorher gespeichertes Inventar und Protokoll prüfen: "+r.owner(),Material.LIME_DYE,()->settleConfirm(p,r,"delivered")));
        if(!returning){rows.add(new AdminUpdateMenu.Button(r.itemPayment()?"Zahlungsitems erstatten":"Punkte erstatten","Nur wenn bezahlt wurde und das gekaufte Paket nicht angekommen ist",Material.RED_DYE,()->settleConfirm(p,r,"refund")));
            if(r.itemPayment())rows.add(new AdminUpdateMenu.Button("Unbezahlt abbrechen","Nur wenn weder Zahlungsitems entfernt noch Kaufitems erhalten wurden",Material.BARRIER,()->settleConfirm(p,r,"unpaid")));}
        plugin.adminUpdate.show(p,"Zustellung prüfen",rows,0);
    }
    private void settleConfirm(Player p,EconomyStore.Receipt r,String action){
        if(!admin(p))return;plugin.adminUpdate.show(p,"Abschließend bestätigen",List.of(new AdminUpdateMenu.Button("Ja, bestätigen",r.id()+" • "+action+" • "+cost(r),Material.EMERALD,()->{if(!admin(p))return;try{
            boolean changed=switch(action){case "refund"->store.refund(r.id());case "unpaid"->store.cancelUnpaid(r.id());default->store.transition(r.id(),r.state(),r.state().equals("REFUNDING")?"REFUNDED":"DELIVERED");};
            if(changed){plugin.pointsEconomy.mirror(r.owner());plugin.suiteAudit(p.getName(),"Shop-Zustellung geklärt",r.id()+" action="+action);Player owner=Bukkit.getPlayer(r.owner());if(owner!=null)deliverPending(owner);}adminHome(p);
        }catch(Exception e){error(p,e);}}),new AdminUpdateMenu.Button("Abbrechen","",Material.BARRIER,()->adminHome(p))),0);
    }
    String discordList(int page)throws Exception {
        var offers=store.offers().stream().filter(o->o.enabled()&&(o.expires()==0||o.expires()>System.currentTimeMillis())).toList();int start=Math.min(offers.size(),(int)Math.min(Integer.MAX_VALUE,(long)Math.max(0,page-1)*10));
        StringBuilder s=new StringBuilder("**Simplex Shop · page "+Math.max(1,page)+"**\n");for(var o:offers.subList(start,Math.min(offers.size(),start+10)))s.append("• **").append(ServerStatistics.label(o.en())).append("** · ").append(cost(o)).append(" · stock: ").append(o.stock()<0?"unlimited":o.stock()).append(o.limit()>0?" · limit: "+o.limit():"").append(o.expires()>0?" · ends: "+plugin.profileDate(o.expires()):"").append('\n');return s.append("\nBuy safely in Minecraft using `/shop`.").toString();
    }
    private void error(Player p,Exception e){plugin.getLogger().warning("Shop: "+e.getClass().getSimpleName()+": "+e.getMessage());p.sendMessage(plugin.prefix()+t(p,"Shop vorübergehend nicht verfügbar. Bitte das Team informieren.","Shop temporarily unavailable. Please contact staff."));}
}
