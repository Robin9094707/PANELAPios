package eu.simplexsmp.adminsuite;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.Player;

/** Per-definition actions are explicit; failed/blocked responses never prove detection. */
final class ClientProbeRules {
    record Rule(String action,long duration,int reviewAfter,boolean staffAlert,boolean discordAlert){ }
    record ActionReport(String action,long banUntil,int strikes,String detail){static ActionReport none(){return new ActionReport("NONE",0,0,"Keine automatische Maßnahme.");}}
    static final String ROOT="client-security.probes.";
    static final Set<String> ACTIONS=Set.of("none","notify","kick","tempban");
    private final SimplexAdminSuite plugin;
    private final ClientProbeManager manager;
    ClientProbeRules(SimplexAdminSuite p,ClientProbeManager m){plugin=p;manager=m;}
    static long duration(String input){
        SimplexAdminSuite.DurationValue value=SimplexAdminSuite.DurationValue.parse(input);
        if(value==null)throw new IllegalArgumentException("Dauer: 1s bis 30d, z. B. 15m.");
        long ms=value.toMillis();if(ms<1000||ms>30L*86400000)throw new IllegalArgumentException("Dauer: 1s bis 30d.");return ms;
    }
    Rule rule(String id){String path=ROOT+"rules."+id+".";
        String legacy=plugin.getConfig().getBoolean(ROOT+"enforce-blocklist",false)&&plugin.getConfig().getStringList(ROOT+"blocked-clients").contains(id)?"kick":manager!=null&&manager.diagnosticOnly(id)?"none":"notify";
        if(id.equals("meteor-client"))legacy="kick";
        String action=plugin.getConfig().getString(path+"action",legacy);if(!ACTIONS.contains(action))action="notify";
        long duration=900000;try{duration=duration(plugin.getConfig().getString(path+"ban-duration","15m"));}catch(IllegalArgumentException ignored){if(action.equals("tempban"))action="notify";}
        return new Rule(action,duration,Math.max(0,Math.min(100,plugin.getConfig().getInt(path+"review-after",id.equals("meteor-client")&&action.equals("kick")?1:0))),plugin.getConfig().getBoolean(path+"alert-admins",!action.equals("none")||manager!=null&&manager.diagnosticOnly(id)),plugin.getConfig().getBoolean(path+"alert-discord",!action.equals("none")||manager!=null&&manager.diagnosticOnly(id)));
    }
    String configure(String actor,String id,String action,String duration,int reviewAfter){
        if(!manager.definitionIds().contains(id)||!id.matches("[a-z0-9_-]{1,64}"))return "Unbekannte Client-ID.";
        if(!ACTIONS.contains(action))return "Aktion: none, notify, kick, tempban.";
        try{duration(duration);if(reviewAfter<0||reviewAfter>100)return "review-after: 0 bis 100.";}
        catch(IllegalArgumentException invalid){return invalid.getMessage();}
        String path=ROOT+"rules."+id+".";plugin.getConfig().set(path+"action",action);plugin.getConfig().set(path+"ban-duration",duration);plugin.getConfig().set(path+"review-after",reviewAfter);plugin.saveConfig();plugin.expansionAudit(actor,null,"Client rule",id+" • "+action+" • "+duration+" • review="+reviewAfter);return "Regel gespeichert: "+id+" • "+action+" • "+duration+" • review="+reviewAfter;
    }
    void confirmed(Player target,String id,String actor,boolean repeated){if(!manager.enabled(id))return;Rule rule=rule(id);String name=manager.definitionName(id);
        if(rule.staffAlert)for(Player staff:Bukkit.getOnlinePlayers())if(plugin.has(staff,"simplexadminsuite.clientcheck.alerts")&&plugin.adminSessionUsable(staff)&&manager.alertsEnabled(staff.getUniqueId()))staff.sendMessage(plugin.prefix()+"§cDETECTED §8• §f"+target.getName()+" §8• §c"+name+plugin.playerText(staff,repeated?" §7(bestätigt)":" §7(einzelnes Signal)",repeated?" §7(confirmed)":" §7(single signal)"));
        // Discord is emitted once by manager.finish, after all results and actions are known.
    }
    ActionReport completed(Player target,Map<String,ClientProbeManager.Result> results,String actor){
        if(target==null)return ActionReport.none();
        List<String> found=results.entrySet().stream().filter(e->e.getValue()==ClientProbeManager.Result.DETECTED&&(manager==null||manager.enabled(e.getKey()))).map(Map.Entry::getKey).toList();
        boolean kick=false;long ban=0;Map<String,Integer> thresholds=new LinkedHashMap<>();List<String> disallowed=new ArrayList<>();
        for(String id:found){if(plugin.clientExempt(target.getUniqueId(),id)||plugin.emergency(target.getUniqueId()))continue;Rule r=rule(id);if(r.action.equals("kick")||r.action.equals("tempban")||r.reviewAfter>0){thresholds.put(id,r.reviewAfter);disallowed.add(manager.definitionName(id));}if(r.action.equals("kick"))kick=true;if(r.action.equals("tempban")){kick=true;ban=Math.max(ban,r.duration);}}
        boolean inconclusive=results.entrySet().stream().anyMatch(e->{Rule r=rule(e.getKey());return !plugin.emergency(target.getUniqueId())&&!plugin.clientExempt(target.getUniqueId(),e.getKey())&&(manager==null||manager.enabled(e.getKey()))&&(!r.action.equals("none")||r.reviewAfter>0)&&(e.getValue()==ClientProbeManager.Result.TIMEOUT||e.getValue()==ClientProbeManager.Result.PROTECTED);});
        int reviewUnknown=plugin.getConfig().getBoolean(ROOT+"review-inconclusive",false)&&inconclusive?Math.max(1,Math.min(100,plugin.getConfig().getInt(ROOT+"inconclusive-review-after",3))):0;
        if(thresholds.isEmpty()&&reviewUnknown==0)return ActionReport.none();
        long now=System.currentTimeMillis();try{
            ClientProbeAccess.State state=manager.access.record(target.getUniqueId(),now,target.getLocale(),disallowed,thresholds,ban,reviewUnknown,Math.max(1,Math.min(30,plugin.getConfig().getInt(ROOT+"strike-window-days",7)))*86400000L);
            String action=state.review()?"TEAM_REVIEW":ban>0?"TEMPBAN":kick?"KICK":"STRIKE";
            if(plugin.appEvents!=null&&(state.review()||ban>0))plugin.appEvents.record("ban",target.getUniqueId(),"Automatische Client-Prüfsperre",target.getName()+" · "+String.join(", ",disallowed)+" · "+action,true);
            plugin.expansionAudit(actor,target.getUniqueId(),"Client rule "+action,disallowed+" • until="+state.banUntil()+" • strikes="+state.strikes());
            if(kick||state.review()){manager.access.refreshInvite();plugin.suiteKick(target,ClientProbeAccess.message(state,now,manager.access.invite()));}
            return new ActionReport(action,state.banUntil(),state.strikes(),"Maßnahme: "+action+" • Fehlversuche: "+state.strikes()+" • Bann bis: "+(state.banUntil()>now?new Date(state.banUntil()):"—")+" • Freigabe: /sasadmin client approve player:"+target.getName());
        }catch(IOException saveError){plugin.getLogger().warning("Client action could not be persisted; no automatic restriction applied: "+saveError.getMessage());return new ActionReport("SAVE_FAILED",0,0,"Maßnahme konnte nicht gespeichert werden; keine automatische Sperre.");}
    }
    private String t(Player p,String de,String en){return plugin.playerText(p,de,en);}
    private String actionTitle(Player p,String action){return switch(action){case "none"->t(p,"Keine Aktion","No action");case "notify"->t(p,"Nur warnen","Notify only");case "kick"->t(p,"Trennen","Kick");default->t(p,"Zeitbann","Temporary ban");};}
    private boolean allowed(Player p){return plugin.has(p,"simplexadminsuite.clientsecurity.admin")&&plugin.adminSessionUsable(p);}
    void menu(Player p,String id){manager.menus.rule(p,id);}
}
