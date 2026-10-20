package eu.simplexsmp.adminsuite;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Client-only overlays. Never changes a block, saves a chunk, or creates an item. */
final class XrayBaitManager implements Listener {
    private static final Set<Material> HOSTS=Set.of(Material.STONE,Material.DEEPSLATE,Material.ANDESITE,Material.DIORITE,Material.GRANITE,Material.TUFF);
    private static final BlockFace[] FACES={BlockFace.UP,BlockFace.DOWN,BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST};
    private record Pos(UUID world,int x,int y,int z) {
        static Pos of(Block b){return new Pos(b.getWorld().getUID(),b.getX(),b.getY(),b.getZ());}
        Location location(World w){return new Location(w,x,y,z);}
    }
    private static final class Session {
        final Set<Pos> approached=new HashSet<>();final Map<Pos,Double> initialDistance=new HashMap<>();int pulses;
        final UUID admin,target,world;final long deadline;final Map<Pos,BlockData> blocks;
        Session(Player a,Player t,int seconds,Map<Pos,BlockData> b){admin=a.getUniqueId();target=t.getUniqueId();world=t.getWorld().getUID();deadline=System.nanoTime()+seconds*1_000_000_000L;blocks=b;}
    }
    private final SimplexAdminSuite plugin;
    private final Map<UUID,Session> sessions=new HashMap<>();
    private BukkitTask timer;
    XrayBaitManager(SimplexAdminSuite p){plugin=p;}
    void start(){timer=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1L,5L);}
    private boolean allowed(Player p){return p!=null&&p.isOnline()&&plugin.adminSessionUsable(p)&&plugin.has(p,"simplexadminsuite.admin")&&plugin.has(p,"simplexadminsuite.xraybait");}
    static int duration(int n){if(n<5||n>60)throw new IllegalArgumentException("Dauer: 5 bis maximal 60 Sekunden.");return n;}
    static int amount(String mode){return switch(mode.toLowerCase(Locale.ROOT)){case "wenig","subtle","low"->4;case "viele","obvious","high"->20;default->throw new IllegalArgumentException("Modus: wenig oder viele.");};}
    boolean command(CommandSender s,String[] args){
        if(!(s instanceof Player p)){s.sendMessage("X-Ray-Köder benötigen einen Admin im Spiel in der Nähe des Zielspielers.");return true;}
        if(!allowed(p)){plugin.menuError(p,"§cKeine X-Ray-Köder-Berechtigung.");return true;}
        if(args.length==0){menu(p);return true;}
        if(args[0].equalsIgnoreCase("stop")){
            if(args.length<2){int count=0;for(Session session:List.copyOf(sessions.values()))if(session.admin.equals(p.getUniqueId())){stop(session,"manuell beendet");count++;}p.sendMessage("§a"+count+" eigene Tests beendet.");}
            else{Player target=Bukkit.getPlayerExact(args[1]);Session session=target==null?null:sessions.get(target.getUniqueId());if(session!=null){stop(session,"manuell durch "+p.getName()+" beendet");p.sendMessage("§aTest beendet.");}else p.sendMessage("§eKein aktiver Test für diesen Spieler.");}
            return true;
        }
        if(args[0].equalsIgnoreCase("list")){for(Session session:sessions.values())p.sendMessage("§e"+name(session.target)+" §7• "+Math.max(0,(session.deadline-System.nanoTime())/1_000_000_000L)+"s • Admin "+name(session.admin));return true;}
        int offset=args[0].equalsIgnoreCase("start")?1:0;
        if(args.length<=offset){p.sendMessage("/xraybait <Spieler> [5–60 Sekunden] [wenig|viele]");return true;}
        Player target=Bukkit.getPlayerExact(args[offset]);if(target==null){plugin.menuError(p,"§cSpieler ist nicht online.");return true;}
        try{int seconds=args.length>offset+1?duration(Integer.parseInt(args[offset+1])):30;String mode=args.length>offset+2?args[offset+2]:"wenig";create(p,target,seconds,amount(mode));}
        catch(IllegalArgumentException e){plugin.menuError(p,"§c"+(e instanceof NumberFormatException?"Dauer muss eine ganze Zahl sein.":e.getMessage()));}
        return true;
    }
    void menu(Player p){
        if(!allowed(p))return;
        List<AdminUpdateMenu.Button> items=new ArrayList<>();
        for(Player target:Bukkit.getOnlinePlayers())if(target!=p)items.add(button(target.getName(),"Test konfigurieren • Admin muss höchstens 48 Blöcke entfernt sein",Material.PLAYER_HEAD,()->configure(p,target,30,4)));
        for(Session s:List.copyOf(sessions.values()))items.add(button("Details: "+name(s.target),"Positionen, Restzeit und Entfernungen ansehen",Material.COMPASS,()->details(p,s)));
        for(Session s:List.copyOf(sessions.values()))items.add(button("Stop: "+name(s.target),"Aktiven Test sofort entfernen",Material.BARRIER,()->{if(allowed(p))stop(s,"manuell beendet");menu(p);}));
        items.add(button("Zurück","Beobachtung",Material.OAK_DOOR,()->plugin.unifiedMenu.category(p,"Beobachtung")));
        plugin.adminUpdate.show(p,"X-Ray-Köder",items,0);
    }
    private AdminUpdateMenu.Button button(String t,String d,Material m,Runnable a){return new AdminUpdateMenu.Button(t,d,m,a);}
    private void configure(Player p,Player target,int seconds,int count){
        if(!allowed(p))return;
        plugin.adminUpdate.show(p,"Köder: "+target.getName(),List.of(
            button("Dauer: "+seconds+" Sekunden","Klick: genaue Dauer zwischen 5 und 60 Sekunden",Material.CLOCK,()->plugin.adminUpdate.prompt(p,"Köder-Dauer (5–60)",String.valueOf(seconds),v->{try{configure(p,target,duration(Integer.parseInt(v)),count);}catch(IllegalArgumentException e){plugin.menuError(p,"§cDauer: ganze Zahl von 5 bis 60.");}})),
            button(count==4?"Menge: wenig (bis 4)":"Menge: viele (bis 20)","Klick: Menge wechseln • nur verdeckte natürliche Blöcke",Material.DIAMOND_ORE,()->configure(p,target,seconds,count==4?20:4)),
            button("Test starten","Client-Köder + private Admin-Markierung. Keine automatischen Strafen.",Material.LIME_DYE,()->{create(p,target,seconds,count);p.closeInventory();}),
            button("Zurück","Spielerauswahl",Material.OAK_DOOR,()->menu(p))),0);
    }
    private void create(Player admin,Player target,int seconds,int count){
        if(!allowed(admin))return;duration(seconds);
        if(!target.isOnline()||target==admin){plugin.menuError(admin,"§cAnderen Online-Spieler auswählen.");return;}
        World world=target.getWorld();Location center=target.getLocation();
        if(admin.getWorld()!=world||admin.getLocation().distanceSquared(center)>48*48){plugin.menuError(admin,"§cGehe zuerst in die Nähe des Spielers (maximal 48 Blöcke, gleiche Welt).");return;}
        if(world.getEnvironment()!=World.Environment.NORMAL||center.getBlockY()>48||!roof(world,center)){
            plugin.menuError(admin,"§cUngeeigneter Ort: Ziel muss unterirdisch in der Oberwelt sein (Y ≤ 48 und feste Decke).");return;
        }
        if(sessions.containsKey(target.getUniqueId())){admin.sendMessage("§eFür diesen Spieler läuft bereits ein Test. Erst stoppen.");return;}
        if(sessions.size()>=8){admin.sendMessage("§eMaximal 8 gleichzeitige Tests.");return;}
        Map<Pos,BlockData> blocks=new LinkedHashMap<>();ThreadLocalRandom rng=ThreadLocalRandom.current();
        for(int tries=0;tries<800&&blocks.size()<count;tries++){
            int dx=rng.nextInt(-12,13),dy=rng.nextInt(-5,4),dz=rng.nextInt(-12,13);
            if(dx*dx+dz*dz<25||dx*dx+dz*dz>144)continue;
            int x=center.getBlockX()+dx,y=center.getBlockY()+dy,z=center.getBlockZ()+dz;
            if(y<world.getMinHeight()+2||y>=world.getMaxHeight()-2||!loadedAround(world,x,z))continue;
            Block block=world.getBlockAt(x,y,z);
            if(!HOSTS.contains(block.getType())||!enclosed(block))continue;
            Pos pos=Pos.of(block);blocks.put(pos,(block.getType()==Material.DEEPSLATE||y<0?Material.DEEPSLATE_DIAMOND_ORE:Material.DIAMOND_ORE).createBlockData());
        }
        if(blocks.isEmpty()){admin.sendMessage("§eKeine geeigneten verdeckten Steinblöcke in bereits geladenen Chunks gefunden.");return;}
        Session session=new Session(admin,target,seconds,blocks);sessions.put(target.getUniqueId(),session);
        for(Map.Entry<Pos,BlockData> entry:blocks.entrySet()){
            target.sendBlockChange(entry.getKey().location(world),entry.getValue());
            admin.sendBlockChange(entry.getKey().location(world),entry.getValue());
            session.initialDistance.put(entry.getKey(),center.distance(entry.getKey().location(world).add(.5,.5,.5)));
            admin.sendMessage("§bKöder §7• "+coordinates(entry.getKey())+" §7("+world.getName()+")");
        }
        admin.sendMessage("§a"+blocks.size()+" Fake-Diamantblöcke für "+target.getName()+" • "+seconds+" Sekunden. /xraybait stop "+target.getName());
        plugin.suiteAudit(admin.getName(),"X-Ray-Köder gestartet",target.getUniqueId()+" blocks="+blocks.size()+" seconds="+seconds+" positions="+blocks.keySet());
    }
    private static boolean loadedAround(World world,int x,int z){return world.isChunkLoaded((x-1)>>4,(z-1)>>4)&&world.isChunkLoaded((x+1)>>4,(z+1)>>4)&&world.isChunkLoaded((x-1)>>4,(z+1)>>4)&&world.isChunkLoaded((x+1)>>4,(z-1)>>4);}
    private static boolean roof(World world,Location location){
        int solids=0;for(int dy=2;dy<=12&&location.getBlockY()+dy<world.getMaxHeight();dy++)if(world.getBlockAt(location.getBlockX(),location.getBlockY()+dy,location.getBlockZ()).getType().isOccluding())solids++;
        return solids>=4;
    }
    private static boolean enclosed(Block block){for(BlockFace face:FACES)if(!block.getRelative(face).getType().isOccluding())return false;return true;}
    private void tick(){
        long now=System.nanoTime();
        for(Session s:List.copyOf(sessions.values())){
            Player target=Bukkit.getPlayer(s.target),admin=Bukkit.getPlayer(s.admin);
            if(now>=s.deadline||target==null||!target.isOnline()||!allowed(admin)||!target.getWorld().getUID().equals(s.world)||admin.getWorld()!=target.getWorld()||admin.getLocation().distanceSquared(target.getLocation())>64*64){stop(s,"abgelaufen oder Sitzung beendet");continue;}
            World world=target.getWorld();
            for(Pos pos:List.copyOf(s.blocks.keySet())){
                if(!world.isChunkLoaded(pos.x>>4,pos.z>>4)){s.blocks.remove(pos);continue;}
                Block actual=world.getBlockAt(pos.x,pos.y,pos.z);
                if(!HOSTS.contains(actual.getType())||pos.location(world).distanceSquared(target.getLocation())>32*32){restore(target,pos);restore(admin,pos);s.blocks.remove(pos);continue;}
                target.sendBlockChange(pos.location(world),s.blocks.get(pos));
                admin.sendBlockChange(pos.location(world),s.blocks.get(pos));
                if(s.pulses%4==0){
                    // Receiver-specific packets: no world particle broadcast or visible entities.
                    for(double[] corner:new double[][]{{0,0,0},{1,0,0},{0,1,0},{0,0,1},{1,1,1},{1,1,0},{1,0,1},{0,1,1}})
                        admin.spawnParticle(Particle.END_ROD,pos.location(world).add(corner[0],corner[1],corner[2]),1,0,0,0,0);
                }
                double distance=target.getLocation().distance(pos.location(world).add(.5,.5,.5));
                if(distance<=3.5&&s.initialDistance.getOrDefault(pos,0d)-distance>=2&&s.approached.add(pos)){
                    admin.sendMessage("§e[X-Ray-Test] "+target.getName()+" nähert sich Köder "+coordinates(pos)+" ("+String.format(Locale.GERMANY,"%.1f",distance)+" m). Kein Cheat-Beweis.");
                    plugin.suiteAudit(name(s.admin),"X-Ray-Köder Annäherung",s.target+" "+pos+" distance="+distance);
                }
            }
            s.pulses++;
            if(s.blocks.isEmpty())stop(s,"keine Köder mehr aktiv");
        }
    }
    private boolean hit(Player p,Block block,String type){
        Session s=sessions.get(p.getUniqueId());Pos pos=Pos.of(block);
        if(s==null||!s.blocks.containsKey(pos))return false;
        if(System.nanoTime()>=s.deadline){stop(s,"abgelaufen");return false;}
        boolean concealed=loadedAround(block.getWorld(),block.getX(),block.getZ())&&enclosed(block);
        s.blocks.remove(pos);restore(p,pos);restore(Bukkit.getPlayer(s.admin),pos);
        String message="§e[X-Ray-Test] §f"+p.getName()+" §7"+type+" bei "+block.getX()+", "+block.getY()+", "+block.getZ()+". Aktuell "+(concealed?"verdeckt":"freigelegt")+". §eHinweis, kein Cheat-Beweis.";
        for(Player staff:Bukkit.getOnlinePlayers())if(allowed(staff))staff.sendMessage(message);
        plugin.suiteAudit(name(s.admin),"X-Ray-Köder Treffer",p.getUniqueId()+" "+type+" "+pos+" concealed="+concealed);
        Player admin=Bukkit.getPlayer(s.admin);if(allowed(admin))admin.playSound(admin.getLocation(),"block.note_block.pling",0.6f,1.5f);
        if(s.blocks.isEmpty())stop(s,"alle Köder geprüft");
        return true;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void damage(BlockDamageEvent e){if(hit(e.getPlayer(),e.getBlock(),"beginnt einen Köder abzubauen"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void broken(BlockBreakEvent e){if(hit(e.getPlayer(),e.getBlock(),"versucht einen Köder vollständig abzubauen"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void teleport(PlayerTeleportEvent e){clearPlayer(e.getPlayer().getUniqueId(),"Teleport");}
    @EventHandler public void quit(PlayerQuitEvent e){clearPlayer(e.getPlayer().getUniqueId(),"Verbindung beendet");}
    @EventHandler public void world(PlayerChangedWorldEvent e){clearPlayer(e.getPlayer().getUniqueId(),"Weltwechsel");}
    private void clearPlayer(UUID id,String reason){for(Session s:List.copyOf(sessions.values()))if(s.target.equals(id)||s.admin.equals(id))stop(s,reason);}
    private void restore(Player player,Pos pos){
        if(player==null||!player.isOnline()||!player.getWorld().getUID().equals(pos.world))return;
        World w=player.getWorld();if(!w.isChunkLoaded(pos.x>>4,pos.z>>4))return;
        player.sendBlockChange(pos.location(w),w.getBlockAt(pos.x,pos.y,pos.z).getBlockData());
    }
    private void stop(Session s,String reason){
        if(!sessions.remove(s.target,s))return;
        Player target=Bukkit.getPlayer(s.target);for(Pos pos:s.blocks.keySet()){restore(target,pos);restore(Bukkit.getPlayer(s.admin),pos);}
        Player admin=Bukkit.getPlayer(s.admin);if(allowed(admin))admin.sendMessage("§7X-Ray-Test für "+name(s.target)+": "+reason+". Fake-Blöcke entfernt.");
        plugin.suiteAudit(name(s.admin),"X-Ray-Köder beendet",s.target+" "+reason);
    }
    Set<UUID> observers(){Set<UUID> result=new HashSet<>();for(Session s:sessions.values())result.add(s.admin);return result;}
    String status(Player admin){
        if(!allowed(admin))return "";
        for(Session s:sessions.values())if(s.admin.equals(admin.getUniqueId())){
            Player target=Bukkit.getPlayer(s.target);if(target==null||!target.getWorld().getUID().equals(s.world))continue;
            Pos nearest=s.blocks.keySet().stream().min(Comparator.comparingDouble(p->p.location(target.getWorld()).distanceSquared(target.getLocation()))).orElse(null);
            if(nearest!=null)return "§bKöder "+coordinates(nearest)+" §7• "+name(s.target)+": "+String.format(Locale.GERMANY,"%.1f",nearest.location(target.getWorld()).add(.5,.5,.5).distance(target.getLocation()))+" m • "+Math.max(0,(s.deadline-System.nanoTime())/1_000_000_000L)+" s";
        }return "";
    }
    private static String coordinates(Pos p){return p.x+" / "+p.y+" / "+p.z;}
    private void details(Player p,Session s){
        if(!allowed(p))return;
        if(sessions.get(s.target)!=s){p.sendMessage("§eDieser Test ist bereits beendet.");menu(p);return;}
        List<AdminUpdateMenu.Button> rows=new ArrayList<>();
        Player target=Bukkit.getPlayer(s.target);World world=Bukkit.getWorld(s.world);
        for(Pos pos:s.blocks.keySet()){
            String info="Welt: "+(world==null?s.world:world.getName())+" • "+coordinates(pos);
            if(target!=null&&world==target.getWorld())info+=" • Zielabstand: "+String.format(Locale.GERMANY,"%.1f m",pos.location(world).add(.5,.5,.5).distance(target.getLocation()));
            String detail=info;
            rows.add(button(coordinates(pos),detail,Material.DIAMOND_ORE,()->p.sendMessage("§b"+detail)));
        }
        rows.add(button("Aktualisieren","Aktuelle Positionen und Abstände",Material.CLOCK,()->details(p,s)));
        rows.add(button("Test beenden","Alle Client-Köder entfernen",Material.BARRIER,()->{if(allowed(p))stop(s,"manuell beendet");menu(p);}));
        rows.add(button("Zurück","Köderübersicht",Material.OAK_DOOR,()->menu(p)));
        plugin.adminUpdate.show(p,"Köder: "+name(s.target),rows,0);
    }
    private String name(UUID id){Player p=Bukkit.getPlayer(id);return p==null?id.toString():p.getName();}
    void shutdown(){if(timer!=null)timer.cancel();for(Session s:List.copyOf(sessions.values()))stop(s,"Plugin wird beendet");}
}
