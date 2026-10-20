package eu.simplexsmp.adminsuite;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.world.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import java.nio.file.*;
import java.util.*;

/** Removal only: never assigns an identifier or records ownership. Main-thread Bukkit access. */
final class LegacyItemCleanup implements Listener {
    private final SimplexAdminSuite plugin;
    private static final Set<String> OLD_KEYS=Set.of("item_id","item_creator","item_creator_name","item_created","item_origin_group");
    private final ArrayDeque<Chunk> chunks=new ArrayDeque<>();
    private final Set<Chunk> queued=Collections.newSetFromMap(new IdentityHashMap<>());
    private final org.bukkit.scheduler.BukkitTask task;
    LegacyItemCleanup(SimplexAdminSuite plugin){
        this.plugin=plugin;
        if(plugin.getConfig().contains("item-tracking")){plugin.getConfig().set("item-tracking",null);plugin.saveConfig();}
        for(String name:List.of("item-ledger.sqlite","item-ledger.sqlite-wal","item-ledger.sqlite-shm","item-ledger.sqlite-journal"))try{Files.deleteIfExists(plugin.getDataFolder().toPath().resolve(name));}catch(Exception e){plugin.getLogger().warning("Could not remove retired item ledger "+name+": "+e.getMessage());}
        for(World w:Bukkit.getWorlds())for(Chunk c:w.getLoadedChunks())enqueue(c);
        for(Player p:Bukkit.getOnlinePlayers())player(p);
        task=Bukkit.getScheduler().runTaskTimer(plugin,()->{long until=System.nanoTime()+2_000_000;for(int n=0;n<2&&!chunks.isEmpty()&&System.nanoTime()<until;n++){Chunk c=chunks.remove();queued.remove(c);if(c.isLoaded())chunk(c);}},1,1);
    }
    void stop(){task.cancel();chunks.clear();queued.clear();}
    private void enqueue(Chunk c){if(queued.add(c))chunks.add(c);}
    private boolean clean(ItemStack item,int depth){
        if(item==null||item.getType().isAir()||!item.hasItemMeta()||depth>32)return false;
        ItemMeta meta=item.getItemMeta();boolean changed=false;
        var data=meta.getPersistentDataContainer();
        for(NamespacedKey k:new HashSet<>(data.getKeys()))if(k.getNamespace().equals("simplexadminsuite")&&OLD_KEYS.contains(k.getKey())){data.remove(k);changed=true;}
        if(meta instanceof BlockStateMeta block&&block.getBlockState() instanceof Container c){
            if(inventory(c.getInventory(),depth+1)){block.setBlockState(c);changed=true;}
        }
        if(meta instanceof BundleMeta bundle){List<ItemStack> items=new ArrayList<>(bundle.getItems());boolean nested=false;for(ItemStack it:items)nested|=clean(it,depth+1);if(nested){bundle.setItems(items);changed=true;}}
        if(changed)item.setItemMeta(meta);return changed;
    }
    private boolean inventory(Inventory inv,int depth){boolean changed=false;for(int i=0;i<inv.getSize();i++){ItemStack item=inv.getItem(i);if(clean(item,depth)){inv.setItem(i,item);changed=true;}}return changed;}
    private void player(Player p){inventory(p.getInventory(),0);inventory(p.getEnderChest(),0);ItemStack cursor=p.getItemOnCursor();if(clean(cursor,0))p.setItemOnCursor(cursor);}
    private void entity(Entity e){
        if(e instanceof Player p){player(p);return;}
        if(e instanceof Item drop){ItemStack item=drop.getItemStack();if(clean(item,0))drop.setItemStack(item);}
        if(e instanceof InventoryHolder h)inventory(h.getInventory(),0);
        if(e instanceof ItemFrame frame){ItemStack item=frame.getItem();if(clean(item,0))frame.setItem(item,false);}
        if(e instanceof LivingEntity living&&living.getEquipment()!=null){var eq=living.getEquipment();for(EquipmentSlot slot:EquipmentSlot.values()){try{ItemStack item=eq.getItem(slot);if(clean(item,0))eq.setItem(slot,item);}catch(IllegalArgumentException ignored){}}}
    }
    private void chunk(Chunk c){for(BlockState s:c.getTileEntities())if(s instanceof Container box)inventory(box.getInventory(),0);for(Entity e:c.getEntities())entity(e);}
    @EventHandler(priority=EventPriority.LOWEST) public void join(PlayerJoinEvent e){player(e.getPlayer());}
    @EventHandler(priority=EventPriority.LOWEST) public void quit(PlayerQuitEvent e){player(e.getPlayer());}
    @EventHandler(priority=EventPriority.LOWEST) public void load(ChunkLoadEvent e){enqueue(e.getChunk());}
    @EventHandler(priority=EventPriority.LOWEST) public void entities(EntitiesLoadEvent e){for(Entity entity:e.getEntities())entity(entity);}
    @EventHandler(priority=EventPriority.LOWEST) public void open(InventoryOpenEvent e){inventory(e.getInventory(),0);if(e.getPlayer() instanceof Player p)player(p);}
    @EventHandler(priority=EventPriority.LOWEST) public void click(InventoryClickEvent e){ItemStack current=e.getCurrentItem();if(clean(current,0))e.setCurrentItem(current);if(e.getWhoClicked() instanceof Player p)player(p);}
    @EventHandler(priority=EventPriority.LOWEST) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p)player(p);}
    @EventHandler(priority=EventPriority.LOWEST) public void move(InventoryMoveItemEvent e){ItemStack item=e.getItem();if(clean(item,0)){e.setCancelled(true);inventory(e.getSource(),0);}inventory(e.getDestination(),0);}
    @EventHandler(priority=EventPriority.LOWEST) public void pickup(EntityPickupItemEvent e){entity(e.getItem());entity(e.getEntity());}
    @EventHandler(priority=EventPriority.LOWEST) public void hopperPickup(InventoryPickupItemEvent e){entity(e.getItem());inventory(e.getInventory(),0);}
    @EventHandler(priority=EventPriority.LOWEST) public void spawn(ItemSpawnEvent e){entity(e.getEntity());}
    @EventHandler(priority=EventPriority.LOWEST) public void merge(ItemMergeEvent e){entity(e.getEntity());entity(e.getTarget());}
    @EventHandler(priority=EventPriority.LOWEST) public void interact(PlayerInteractEvent e){player(e.getPlayer());if(e.getClickedBlock()!=null&&e.getClickedBlock().getState() instanceof Container c)inventory(c.getInventory(),0);}
}
