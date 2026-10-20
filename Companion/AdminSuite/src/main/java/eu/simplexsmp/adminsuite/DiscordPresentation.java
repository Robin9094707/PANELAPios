package eu.simplexsmp.adminsuite;

/** Independent gates for low-priority application-profile writes and gateway presence changes. */
final class DiscordPresentation {
  private long nextProfile, lastPresence;
  private String lastPresencePayload = "";

  synchronized boolean profileDue(long now) {
    return now >= nextProfile;
  }

  synchronized void profileAttempt(long now, long intervalMillis) {
    nextProfile = now + Math.max(600_000L, intervalMillis);
  }

  synchronized void profileBackoff(long now, double retrySeconds) {
    if (!Double.isFinite(retrySeconds) || retrySeconds < 0) retrySeconds = 60;
    double milliseconds = Math.ceil(Math.max(1, retrySeconds) * 1000);
    long until = milliseconds >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + (long) milliseconds;
    nextProfile = Math.max(nextProfile, until);
  }

  synchronized boolean presence(long now, String payload) {
    if (payload.equals(lastPresencePayload) || lastPresence != 0 && now - lastPresence < 15_000)
      return false;
    lastPresence = now;
    lastPresencePayload = payload;
    return true;
  }

  synchronized void newApplication() {
    nextProfile = 0;
  }

  synchronized void reconnect() {
    lastPresencePayload = "";
  }

  static String profile(String base, ServerStatistics.Live live, boolean dynamic) {
    return profile(base,live,dynamic,java.util.List.of());
  }
  static String profile(String base, ServerStatistics.Live live, boolean dynamic,java.util.List<String> names) {
    base=base==null?"":base.strip();
    if(!dynamic||live==null)return cut(base,400);
    String stats=String.format(java.util.Locale.ROOT,"Players: %d/%d • Java: %d • Bedrock: %d\nTPS: %.1f • World days: %d",live.online(),live.max(),live.javaOnline(),live.bedrockOnline(),live.tps(),live.days());
    String header=cut(base,Math.max(0,Math.min(120,400-stats.length()-2)))+"\n\n"+stats;
    String full="\nOnline: "+String.join(", ",names);
    if(header.length()+full.length()<=400)return header+full;
    String compact=cut(base,Math.max(0,Math.min(65,400-stats.length()-2)))+"\n\n"+stats;
    StringBuilder list=new StringBuilder("\nOnline: ");int used=0;
    for(String name:names){String entry=(used==0?"":", ")+name;String rest=" … +"+(names.size()-used-1)+" more";if(compact.length()+list.length()+entry.length()+rest.length()>400)break;list.append(entry);used++;}
    if(used==0)return cut(compact,400);
    if(used<names.size())list.append(" … +").append(names.size()-used).append(" more");
    return cut(compact+list,400);
  }
  private static String cut(String s,int max){int end=Math.min(max,s.length());if(end>0&&end<s.length()&&Character.isHighSurrogate(s.charAt(end-1)))end--;return s.substring(0,end);}
}
