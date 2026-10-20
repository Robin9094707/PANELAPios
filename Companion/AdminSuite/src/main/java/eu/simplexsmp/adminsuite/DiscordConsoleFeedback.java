package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

/** Captures only this command sender's feedback, never the global log stream. */
final class DiscordConsoleFeedback {
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    static String execute(SimplexAdminSuite plugin, String command, Consumer<String> live) {
        if (ACTIVE.incrementAndGet() > 3) { ACTIVE.decrementAndGet(); return "Zu viele laufende Konsolenanfragen. Bitte kurz warten."; }
        int seconds = Math.max(1,Math.min(15,plugin.getConfig().getInt("discord-bot.admin.console.feedback-seconds",5)));
        Capture capture = new Capture(System.nanoTime() + seconds * 1_000_000_000L);
        CommandSender sender;
        try {
            // Paper's native sender retains Minecraft command context/permissions (including vanilla commands).
            sender = (CommandSender) Bukkit.class.getMethod("createCommandSender", Consumer.class).invoke(null, (Consumer<Object>) capture::accept);
        } catch (ReflectiveOperationException unavailable) {
            ACTIVE.decrementAndGet();
            return "Dieser Server bietet Papers createCommandSender-API nicht an. Der Befehl wurde nicht ausgeführt.";
        }
        boolean accepted;
        try { accepted = Bukkit.dispatchCommand(sender,command); }
        catch (RuntimeException e) { ACTIVE.decrementAndGet(); return "Befehlsausführung fehlgeschlagen: " + e.getClass().getSimpleName(); }
        String status = accepted ? "Befehl an den Server übergeben." : "Der Server hat den Befehl nicht angenommen.";
        if (live == null) { ACTIVE.decrementAndGet(); return status + "\n" + capture.render(true); }
        // Fixed bounded task count; edits are at most one per second and contain the latest complete output.
        for (int i=1;i<=seconds;i++) {
            boolean last=i==seconds;
            Bukkit.getScheduler().runTaskLater(plugin,()->{
                try { live.accept(status + "\n" + capture.render(last)); }
                finally { if(last)ACTIVE.decrementAndGet(); }
            },i*20L);
        }
        return status + "\n" + capture.render(false);
    }
    static final class Capture {
        private final long deadline;
        private final Deque<String> lines = new ArrayDeque<>();
        private int length;
        private boolean truncated;
        Capture(long deadline){this.deadline=deadline;}
        synchronized void accept(Object component) {
            if(System.nanoTime()>deadline)return;
            String value = plain(component).replaceAll("§[0-9A-FK-ORa-fk-or]", "").replace("```", "'''");
            for(String line:value.split("\\R")) {
                if(line.length()>500) { line=line.substring(0,500)+"…";truncated=true; }
                lines.addLast(line);length+=line.length()+1;
                while(lines.size()>30||length>3000){length-=lines.removeFirst().length()+1;truncated=true;}
            }
        }
        synchronized String render(boolean finished) {
            String text=lines.isEmpty()?"(Der Befehl hat bisher keine direkte Antwort gesendet.)":String.join("\n",lines);
            return "```text\n"+text+"\n```"+(truncated?"\nÄltere / lange Ausgabe gekürzt.":"")
                    +(finished?"\nFeedback abgeschlossen. Globale Serverlogs werden nicht mitgelesen.":"\nLive-Feedback läuft …");
        }
        private static String plain(Object component) {
            if(component==null)return "";if(component instanceof String s)return s;
            try {
                Class<?> type=Class.forName("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
                Object serializer=type.getMethod("plainText").invoke(null);
                Method method=type.getMethod("serialize",Class.forName("net.kyori.adventure.text.Component"));
                return String.valueOf(method.invoke(serializer,component));
            } catch(ReflectiveOperationException error){return String.valueOf(component);}
        }
    }
}
