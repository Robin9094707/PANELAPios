package eu.simplexsmp.adminsuite;
import java.util.*;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
/** Public messages only. Bounded process memory, never saved to player/database history. */
final class AppChat {
    private final Deque<Map<String,Object>> rows=new ArrayDeque<>();private long sequence;
    synchronized void record(String uuid,String name,String text,String source){
        rows.addLast(SimplexAdminSuite.appMap("id",Long.toString(++sequence),"uuid",uuid,"name",name,"text",text,"source",source,"at",System.currentTimeMillis()));
        while(rows.size()>20)rows.removeFirst();
    }
    synchronized Object read(long after){return SimplexAdminSuite.appMap("items",rows.stream().filter(row->Long.parseLong((String)row.get("id"))>after).toList(),"cursor",Long.toString(sequence));}
    static String validateName(String name){if(!name.matches("[\\p{L}\\p{N} _.-]{1,32}"))throw new IllegalArgumentException("Anzeigename: 1–32 Buchstaben, Zahlen, Leerzeichen, Punkt, _ oder -");return name;}
    static String validateMessage(String text){if(text.isBlank()||text.length()>500||text.codePoints().anyMatch(c->Character.isISOControl(c)||c==0x00a7))throw new IllegalArgumentException("Nachricht: 1–500 Zeichen ohne Steuer- oder Formatcodes");return text;}
    Object send(SimplexAdminSuite plugin,String actor,String name,String message){
        String sender=validateName(name),text=validateMessage(message);String formatted=sender.equalsIgnoreCase("Server")?"[Server] "+text:"<"+sender+"> "+text;
        // Literal component, never a console command, tellraw JSON, MiniMessage or selector.
        Bukkit.broadcast(Component.text(formatted));record("",sender,text,"app");
        plugin.suiteAudit(actor,"App-Chat",sender+": "+text);
        if(plugin.discordRelay()!=null)plugin.discordRelay().relayMinecraftChat(sender,"App",text);
        return SimplexAdminSuite.appMap("ok",true);
    }
}
