package eu.simplexsmp.adminsuite;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.lang.ref.WeakReference;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

/** One protected chest window across Suite screens. In-place updates preserve the client cursor. */
final class LiveMenus implements Listener {
    private static final class Screen implements InventoryHolder {
        InventoryHolder delegate;Inventory inventory;UUID owner;String title;long revision;
        Screen(InventoryHolder delegate,String title){this.delegate=delegate;this.title=title;}
        public Inventory getInventory(){return inventory;}
    }
    private static SimplexAdminSuite plugin;
    private static final Map<Inventory,WeakReference<Inventory>> aliases=Collections.synchronizedMap(new WeakHashMap<>());
    private static final class Dispatch {
        final Inventory window;final long revision;Inventory next;
        Dispatch(Inventory window){this.window=window;revision=((Screen)window.getHolder()).revision;}
    }
    private static final Map<UUID,Dispatch> dispatches=new HashMap<>();
    static void configure(SimplexAdminSuite value){plugin=value;aliases.clear();dispatches.clear();}
    static Inventory create(InventoryHolder holder,int size,String title){
        Screen screen=new Screen(holder,title);
        // All Suite navigation screens use six rows; live player inventories stay untouched.
        screen.inventory=Bukkit.createInventory(screen,54,MenuStyle.inventoryTitle(plugin==null?"Simplex":plugin.brandName()));return screen.inventory;
    }
    static InventoryHolder holder(Inventory inventory){if(inventory==null)return null;Inventory canonical=canonical(inventory);InventoryHolder h=canonical.getHolder();return h instanceof Screen s?s.delegate:h;}
    static String title(InventoryView view){if(view==null)return "";InventoryHolder h=view.getTopInventory().getHolder();return h instanceof Screen s?s.title:view.getTitle();}
    static boolean managed(Inventory inventory){return inventory!=null&&inventory.getHolder() instanceof Screen;}
    static Inventory canonical(Inventory inventory){WeakReference<Inventory> ref=aliases.get(inventory);Inventory real=ref==null?null:ref.get();return real==null?inventory:real;}
    /** A deferred render may proceed only while its exact screen is still shown. */
    static boolean current(Player player,Inventory expected,InventoryHolder context){
        if(!player.isOnline()||expected==null)return false;
        Inventory shown=player.getOpenInventory().getTopInventory();
        Dispatch queued=dispatches.get(player.getUniqueId());
        if(queued!=null&&queued.next==expected&&shown==queued.window)return true;
        return shown==canonical(expected)&&holder(shown)==context;
    }
    static void show(Player player,Inventory next){
        if(!player.isOnline())return;
        Dispatch dispatch=dispatches.get(player.getUniqueId());
        if(dispatch!=null){dispatch.next=next;return;}
        render(player,next);
    }
    private static void render(Player player,Inventory next){
        if(plugin!=null&&plugin.adminRuntime!=null&&!plugin.adminRuntime.allowsMenu(player,holder(next)))return;
        InventoryHolder raw=next.getHolder();if(!(raw instanceof Screen screen)){player.openInventory(next);return;}
        if(screen.owner!=null&&!screen.owner.equals(player.getUniqueId()))throw new IllegalArgumentException("Suite menu belongs to another player");
        screen.owner=player.getUniqueId();
        if(plugin!=null&&plugin.appApi!=null&&plugin.appApi.remote(player)){screen.owner=player.getUniqueId();screen.inventory=next;bind(screen.delegate,next);stamp(next,screen.title);MenuStyle.uniformInventory(next);player.openInventory(next);return;}
        Inventory current=player.getOpenInventory().getTopInventory();
        Screen existing=current.getHolder() instanceof Screen s?s:null;
        if(existing!=null&&player.getUniqueId().equals(existing.owner)&&current.getSize()==next.getSize()){
            InventoryHolder nextDelegate=screen.delegate;
            // Rebind module guards before publishing the new holder/actions. No close or open packet.
            bind(nextDelegate,current);screen.inventory=current;
            existing.delegate=nextDelegate;existing.title=screen.title;existing.revision++;stamp(next,screen.title);MenuStyle.uniformInventory(next);
            current.setContents(next.getContents());aliases.put(next,new WeakReference<>(current));return;
        }
        screen.inventory=next;aliases.remove(next);bind(screen.delegate,next);stamp(next,screen.title);MenuStyle.uniformInventory(next);player.openInventory(next);
    }
    private static void bind(InventoryHolder holder,Inventory inventory){
        if(holder==null)return;
        for(Field f:holder.getClass().getDeclaredFields())if(f.getType()==Inventory.class&&!Modifier.isStatic(f.getModifiers()))try{f.setAccessible(true);f.set(holder,inventory);}catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot bind Suite menu holder",e);}
    }
    private static void stamp(Inventory inventory,String title){
        if(plugin==null)return;
        for(int slot:new int[]{4,50,8,0}){ItemStack item=inventory.getItem(slot);if(item==null||item.getType()==Material.AIR||item.hasItemMeta()&&MenuStyle.plain(item.getItemMeta().getDisplayName()).isBlank()){
            inventory.setItem(slot,plugin.item(Material.BOOK,title));return;
        }}
    }
    static void beginClick(Player player,Inventory window){
        if(plugin!=null&&plugin.appApi!=null&&plugin.appApi.remote(player))return;
        if(!managed(window)||plugin==null||dispatches.containsKey(player.getUniqueId()))return;
        Dispatch dispatch=new Dispatch(window);dispatches.put(player.getUniqueId(),dispatch);
        // Keep the original holder for every listener of this event; publish after dispatch.
        Bukkit.getScheduler().runTask(plugin,()->{
            if(!dispatches.remove(player.getUniqueId(),dispatch))return;
            if(dispatch.next!=null&&player.isOnline()&&player.getOpenInventory().getTopInventory()==window
                    &&((Screen)window.getHolder()).revision==dispatch.revision)render(player,dispatch.next);
        });
    }
    @EventHandler(priority=EventPriority.LOWEST)public void click(InventoryClickEvent e){if(managed(e.getView().getTopInventory())){e.setCancelled(true);if(e.getWhoClicked() instanceof Player p)beginClick(p,e.getView().getTopInventory());}}
    @EventHandler(priority=EventPriority.LOWEST)public void drag(InventoryDragEvent e){if(managed(e.getView().getTopInventory()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR)public void close(InventoryCloseEvent e){dispatches.remove(e.getPlayer().getUniqueId());aliases.entrySet().removeIf(x->x.getKey()==e.getInventory()||x.getValue().get()==e.getInventory());}
}
