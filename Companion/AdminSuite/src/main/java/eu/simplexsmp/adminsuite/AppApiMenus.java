package eu.simplexsmp.adminsuite;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.event.inventory.*;

/** Bounded remote rendering of existing menu callbacks. Full keys only; never impersonates an online player. */
final class AppApiMenus {
    private final SimplexAdminSuite plugin;
    private final AppApiSecurity security;
    private final Map<String,Session> sessions=new ConcurrentHashMap<>();
    AppApiMenus(SimplexAdminSuite p,AppApiSecurity security){plugin=p;this.security=security;}
    boolean remote(Object player){return sessions.values().stream().anyMatch(s->s.player==player&&valid(s));}
    private boolean valid(Session s){return plugin.appApi!=null&&plugin.appApi.active()&&s.key.scope().equals("full")&&security.keys().contains(s.key)&&System.currentTimeMillis()-s.touched<20*60_000L;}
    private Session session(Player p){return sessions.values().stream().filter(s->s.player==p&&valid(s)).findFirst().orElse(null);}
    Object current(AppApiSecurity.Key key){
        if(!key.scope().equals("full"))throw new IllegalArgumentException("Die vollständigen Suite-Menüs benötigen einen full-Schlüssel.");
        Session s=sessions.get(key.id());if(s==null||!valid(s)){
            if(s!=null)plugin.invalidateAdminSession(s.id);
            s=new Session(key);sessions.put(key.id(),s);Session opened=s;plugin.asAdmin(s.name,()->plugin.appOpenRemoteMenu(opened.player));
        }s.touched=System.currentTimeMillis();return s.snapshot();
    }
    Object perform(AppApiSecurity.Key key,Map<String,Object> values){
        Session s=sessions.get(key.id());if(s==null||!valid(s)||!s.id.toString().equals(String.valueOf(values.get("session")))||s.revision!=Long.parseLong(String.valueOf(values.get("revision"))))throw new IllegalArgumentException("Menü wurde aktualisiert oder ist abgelaufen. Neu laden und erneut auswählen.");
        s.touched=System.currentTimeMillis();String option=String.valueOf(values.getOrDefault("option",""));String input=String.valueOf(values.getOrDefault("input",""));
        Runnable work;
        if(option.equals("input")){Consumer<String> callback=s.input;if(callback==null)throw new IllegalArgumentException("Keine Texteingabe offen");work=()->{s.input=null;s.revision++;callback.accept(input);};}
        else if(option.equals("home"))work=()->plugin.appOpenRemoteMenu(s.player);
        else if(s.inventory!=null){int slot=Integer.parseInt(option);Inventory current=s.inventory;if(slot<0||slot>=current.getSize()||current.getItem(slot)==null)throw new IllegalArgumentException("Leerer Menüplatz");var meta=current.getItem(slot).getItemMeta();if(meta!=null&&meta.hasDisplayName()&&sensitive(meta.getDisplayName()))throw new IllegalArgumentException("Zugangsdaten bleiben lokal");InventoryView view=s.view();
            work=()->{s.revision++;Bukkit.getPluginManager().callEvent(new InventoryClickEvent(view,InventoryType.SlotType.CONTAINER,slot,ClickType.LEFT,InventoryAction.PICKUP_ALL));};
        }else{int index=Integer.parseInt(option);if(index<0||index>=s.buttons.size())throw new IllegalArgumentException("Unbekannter Menüpunkt");var button=s.buttons.get(index);if(button.informational()||sensitive(button.title()))throw new IllegalArgumentException("Reine Information");work=()->{s.revision++;button.action().run();};}
        s.messages.clear();try{plugin.asAdmin(s.name,work);}catch(RuntimeException e){s.messages.add("Nicht für die App verfügbar: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
        plugin.expansionAudit(s.name,null,"App-Suite-Menü",s.title+" · "+option);
        return s.snapshot();
    }
    boolean show(Player p,String title,List<AdminUpdateMenu.Button> buttons){Session s=session(p);if(s==null)return false;s.title=title;s.inventory=null;s.buttons=new ArrayList<>(buttons.subList(0,Math.min(1000,buttons.size())));s.input=null;s.revision++;return true;}
    private static boolean sensitive(String text){return text.toLowerCase(Locale.ROOT).matches(".*(passwort|password|token|api.?key|api.schlüssel|shared.secret|webhook.?url|zugangsschlüssel).*");}
    boolean prompt(Player p,String title,String previous,Consumer<String> callback){Session s=session(p);if(s==null)return false;if(sensitive(title)){s.messages.add("Zugangsdaten ausschließlich lokal im Spiel oder in der Serverkonsole ändern.");return true;}s.title=title;s.inventory=null;s.buttons=List.of();s.input=callback;s.previous=previous;s.revision++;return true;}
    void clear(){for(Session s:sessions.values())plugin.invalidateAdminSession(s.id);sessions.clear();}
    private final class Session implements InvocationHandler{
        final AppApiSecurity.Key key;final UUID id;final String name;final Player player;
        long revision=0,touched=System.currentTimeMillis();String title="Suite-Menü",previous="";List<AdminUpdateMenu.Button> buttons=List.of();Consumer<String> input;Inventory inventory;final List<String> messages=new ArrayList<>();
        Session(AppApiSecurity.Key key){this.key=key;id=UUID.nameUUIDFromBytes(("simplex-app-menu:"+key.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));name="Simplex Panel/"+key.label();player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},this);}
        Object snapshot(){
            List<Object> options=new ArrayList<>();if(inventory!=null){for(int slot=0;slot<inventory.getSize();slot++){ItemStack item=inventory.getItem(slot);if(item==null||item.getType().isAir())continue;var meta=item.getItemMeta();options.add(SimplexAdminSuite.appMap("id",Integer.toString(slot),"name",meta!=null&&meta.hasDisplayName()?meta.getDisplayName():item.getType().name(),"detail",sensitive(meta!=null&&meta.hasDisplayName()?meta.getDisplayName():"")?"Zugangsdaten werden lokal verwaltet.":meta!=null&&meta.hasLore()?String.join("\n",meta.getLore()):"","icon",item.getType().name(),"disabled",sensitive(meta!=null&&meta.hasDisplayName()?meta.getDisplayName():"")));}}
            else for(int i=0;i<buttons.size();i++){var b=buttons.get(i);options.add(SimplexAdminSuite.appMap("id",Integer.toString(i),"name",b.title(),"detail",sensitive(b.title())?"Zugangsdaten werden lokal verwaltet.":b.detail(),"icon",b.icon().name(),"disabled",b.informational()||sensitive(b.title())));}
            return SimplexAdminSuite.appMap("session",id.toString(),"revision",revision,"title",title,"options",options,"input",input!=null,"previous",previous,"messages",new ArrayList<>(messages),"notice","Jede Auswahl wird bestätigt. Aktionen mit einer eigenen Spielfigur benötigen weiterhin einen Spieler im Spiel.");
        }
        InventoryView view(){final Inventory shown=inventory;final String shownTitle=title;return (InventoryView)Proxy.newProxyInstance(InventoryView.class.getClassLoader(),new Class<?>[]{InventoryView.class},(proxy,m,args)->switch(m.getName()){
            case "getTopInventory","getInventory"->shown;case "getPlayer"->player;case "getTitle","getOriginalTitle"->shownTitle;case "getType"->shown.getType();case "countSlots"->shown.getSize();case "convertSlot"->args[0];case "getSlotType"->InventoryType.SlotType.CONTAINER;case "getItem"->shown.getItem((Integer)args[0]);case "toString"->shownTitle;default->empty(m.getReturnType());});}
        public Object invoke(Object proxy,Method method,Object[] args){
            String m=method.getName();if(m.equals("equals"))return proxy==args[0];if(m.equals("hashCode"))return id.hashCode();if(m.equals("toString"))return name;
            if(m.equals("getUniqueId"))return id;if(Set.of("getName","getDisplayName","getPlayerListName").contains(m))return name;
            if(m.equals("getServer"))return Bukkit.getServer();if(m.equals("isOnline")||m.equals("isValid")||m.equals("isOp")||m.equals("hasPermission")||m.equals("isPermissionSet"))return valid(this);
            if(m.equals("getLocale"))return "de_de";if(m.equals("locale"))return Locale.GERMANY;
            if(m.equals("sendMessage")){if(args!=null)for(Object value:args){if(value instanceof String text)add(text);else if(value instanceof net.kyori.adventure.text.Component component)add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component));else if(value instanceof String[] lines)for(String text:lines)add(text);}return null;}
            if(m.equals("openInventory")){if(args[0] instanceof Inventory inv)inventory=inv;else if(args[0] instanceof InventoryView v)inventory=v.getTopInventory();else throw new IllegalArgumentException("Menütyp nicht unterstützt");title=inventory.getType().name();title="Suite-Verwaltung";title=LiveMenus.title(view());buttons=List.of();input=null;revision++;return view();}
            if(m.equals("getOpenInventory"))return view();if(m.equals("closeInventory")){inventory=null;return null;}
            if(Set.of("playSound","stopSound","sendActionBar","sendTitle","resetTitle","updateInventory","sendExperienceChange","setPlayerListName").contains(m))return null;
            if(Set.of("getWorld","getLocation","getInventory","getEnderChest","teleport","teleportAsync","setGameMode","kickPlayer","kick","setHealth","setFlying","setAllowFlight","setInvulnerable").contains(m))throw new IllegalArgumentException("Diese Aktion benötigt eine eigene Spielfigur im Spiel. Spieleraktionen stattdessen über die Spielerakte ausführen.");
            return empty(method.getReturnType());
        }
        void add(String value){if(messages.size()<100)messages.add(value.length()>4000?value.substring(0,4000):value);}
    }
    private static Object empty(Class<?> type){if(!type.isPrimitive())return type==List.class?List.of():type==Set.class?Set.of():null;if(type==boolean.class)return false;if(type==long.class)return 0L;if(type==double.class)return 0d;if(type==float.class)return 0f;if(type==short.class)return (short)0;if(type==byte.class)return (byte)0;if(type==char.class)return '\0';return 0;}
}
