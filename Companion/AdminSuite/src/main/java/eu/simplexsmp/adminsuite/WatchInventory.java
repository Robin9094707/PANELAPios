package eu.simplexsmp.adminsuite;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
/** Public player menu; independent of staff-only AdminUpdateMenu permission guards. */
final class WatchInventory implements Listener,InventoryHolder{
    final UUID owner;final List<AdminUpdateMenu.Button> rows;WatchInventory(UUID owner,List<AdminUpdateMenu.Button> rows){this.owner=owner;this.rows=rows;}public Inventory getInventory(){return null;}
    static void show(SimplexAdminSuite plugin,Player p,List<AdminUpdateMenu.Button> rows,String title){
        // Up to 10 subscriptions per watcher; target pages may contain more. Keep all rows reachable via Next.
        showPage(plugin,p,rows,title,0);
    }
    static void showPage(SimplexAdminSuite plugin,Player p,List<AdminUpdateMenu.Button> rows,String title,int page){List<AdminUpdateMenu.Button> visible=new ArrayList<>(rows.subList(Math.min(page*45,rows.size()),Math.min(page*45+45,rows.size())));if(page>0)visible.add(new AdminUpdateMenu.Button("←", "",Material.ARROW,()->showPage(plugin,p,rows,title,page-1)));if((page+1)*45<rows.size())visible.add(new AdminUpdateMenu.Button("→","",Material.ARROW,()->showPage(plugin,p,rows,title,page+1)));WatchInventory holder=new WatchInventory(p.getUniqueId(),visible);Inventory inv=LiveMenus.create(holder,54,title);for(int i=0;i<visible.size();i++){var r=visible.get(i);ItemStack item=new ItemStack(r.icon());var m=item.getItemMeta();m.setDisplayName(MenuStyle.javaText(r.title()));m.setLore(Arrays.stream(r.detail().split("\n")).map(MenuStyle::javaText).toList());item.setItemMeta(m);inv.setItem(i,item);}LiveMenus.show(p,inv);}
    @EventHandler public void click(InventoryClickEvent e){if(!(LiveMenus.holder(e.getInventory()) instanceof WatchInventory h))return;e.setCancelled(true);if(e.getWhoClicked() instanceof Player p&&h.owner.equals(p.getUniqueId())&&e.getRawSlot()>=0&&e.getRawSlot()<h.rows.size()){p.closeInventory();h.rows.get(e.getRawSlot()).action().run();}}
    @EventHandler public void drag(InventoryDragEvent e){if(LiveMenus.holder(e.getInventory()) instanceof WatchInventory)e.setCancelled(true);}
}
