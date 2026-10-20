package eu.simplexsmp.adminsuite;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;

/** Native Floodgate forms or protected inventory pages, with owner and generation checks. */
final class CommunityMenus implements Listener {
    record Row(String title,String detail,ItemStack icon,String path,Runnable action) { }
    private static final class Holder implements InventoryHolder {
        final UUID owner;final long generation;final Map<Integer,Runnable> actions=new HashMap<>();Inventory inv;
        Holder(UUID o,long g){owner=o;generation=g;}
        public Inventory getInventory(){return inv;}
    }
    private final SimplexAdminSuite plugin;
    private final Map<UUID,Long> generations=new HashMap<>();
    CommunityMenus(SimplexAdminSuite p){plugin=p;}
    static Row row(String title,String detail,Material material,Runnable action){return new Row(title,detail,new ItemStack(material),MenuStyle.icon(title),action);}
    boolean show(Player p,String title,String body,List<Row> all,int requested,boolean single) {
        if(!p.isOnline())return false;
        int page=Math.max(0,Math.min(requested,Math.max(0,(all.size()-1)/21)));
        List<Row> rows=new ArrayList<>(all.subList(page*21,Math.min(all.size(),page*21+21)));
        if(!single){if(page>0)rows.add(row(plugin.playerText(p,"Vorherige Seite","Previous page"),"",Material.ARROW,()->show(p,title,body,all,page-1,false)));
        if((page+1)*21<all.size())rows.add(row(plugin.playerText(p,"Nächste Seite","Next page"),"",Material.ARROW,()->show(p,title,body,all,page+1,false)));
        rows.add(row(plugin.playerText(p,"Schließen","Close"),"",Material.BARRIER,p::closeInventory));}
        long gen=generations.merge(p.getUniqueId(),1L,Long::sum);
        if(plugin.isBedrockClient(p)) {
            long nativeGen=plugin.nativeFormGeneration(p.getUniqueId())+1;
            var form=SimplexAdminSuite.SimpleForm.builder().playerFacing().title(title).content(body);
            for(Row r:rows)form.button(r.title+(r.detail.isBlank()?"":"\n"+r.detail),r.path,ignored->plugin.runAdminTask(()->{
                if(p.isOnline()&&gen==generations.getOrDefault(p.getUniqueId(),0L)&&nativeGen==plugin.nativeFormGeneration(p.getUniqueId())){generations.put(p.getUniqueId(),gen+1);r.action.run();}
            }));
            boolean sent=plugin.sendBedrockForm(p,form);
            if(!sent)p.sendMessage(plugin.prefix()+plugin.playerText(p,"Das Bedrock-Menü konnte nicht geöffnet werden.","Could not open the Bedrock menu."));
            return sent;
        }
        Holder h=new Holder(p.getUniqueId(),gen);h.inv=LiveMenus.create(h,54,MenuStyle.inventoryTitle(plugin.brandText(title)));
        for(int n=0;n<54;n++)h.inv.setItem(n,plugin.item(Material.GRAY_STAINED_GLASS_PANE," "));
        h.inv.setItem(4,plugin.item(Material.BOOK,title,wrap(body)));
        for(int n=0;n<rows.size();n++) {
            Row r=rows.get(n);int slot=10+n+n/7*2;ItemStack icon=r.icon.clone();var meta=icon.getItemMeta();
            if(meta!=null){meta.setDisplayName(MenuStyle.javaText(r.title));meta.setLore(Arrays.stream(wrap(r.detail)).map(MenuStyle::javaText).toList());icon.setItemMeta(meta);}
            h.inv.setItem(slot,icon);h.actions.put(slot,r.action);
        }
        LiveMenus.show(p,h.inv);return true;
    }
    private static String[] wrap(String text){List<String> out=new ArrayList<>();for(String line:text.split("\n",-1)){StringBuilder b=new StringBuilder();for(String word:line.split(" ")){if(b.length()+word.length()>42){out.add(b.toString());b.setLength(0);}if(!b.isEmpty())b.append(' ');b.append(word);}out.add(b.toString());}return out.toArray(String[]::new);}
    @EventHandler public void click(InventoryClickEvent e){if(!(LiveMenus.holder(e.getInventory()) instanceof Holder h))return;e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p)||!p.getUniqueId().equals(h.owner)||h.generation!=generations.getOrDefault(h.owner,0L))return;Runnable r=h.actions.get(e.getRawSlot());if(r!=null){generations.put(h.owner,h.generation+1);plugin.runAdminTask(()->{if(!p.isOnline()||LiveMenus.holder(p.getOpenInventory().getTopInventory())!=h)return;r.run();if(LiveMenus.holder(p.getOpenInventory().getTopInventory())==h&&generations.getOrDefault(h.owner,0L)==h.generation+1)generations.put(h.owner,h.generation);});}}
    @EventHandler public void drag(InventoryDragEvent e){if(LiveMenus.holder(e.getInventory()) instanceof Holder)e.setCancelled(true);}
    @EventHandler public void quit(PlayerQuitEvent e){generations.remove(e.getPlayer().getUniqueId());}
}
