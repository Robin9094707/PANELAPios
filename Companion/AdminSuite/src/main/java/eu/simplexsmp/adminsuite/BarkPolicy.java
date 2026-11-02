package eu.simplexsmp.adminsuite;
import java.time.*;import java.util.*;
/** Pure policy evaluation, including overnight quiet windows and IANA timezone/DST handling. */
final class BarkPolicy {
 static boolean quiet(Properties p,String prefix,Instant now){String mode=p.getProperty(prefix+"quietMode","off");if(mode.equals("off"))return false;ZoneId zone=ZoneId.of(p.getProperty(prefix+"timezone","Europe/Berlin"));LocalTime time=now.atZone(zone).toLocalTime(),start=LocalTime.parse(p.getProperty(prefix+"quietStart","22:00")),end=LocalTime.parse(p.getProperty(prefix+"quietEnd","07:00"));if(start.equals(end))return true;return start.isBefore(end)?!time.isBefore(start)&&time.isBefore(end):!time.isBefore(start)||time.isBefore(end);}
 static boolean allowed(Properties p,String prefix,String type,String level,Instant now){if(!quiet(p,prefix,now))return true;return switch(p.getProperty(prefix+"quietMode","off")){case "critical"->level.equals("critical");case "selected"->level.equals("critical")&&Arrays.asList(p.getProperty(prefix+"quietKinds","").split(",")).contains(type);default->false;};}
 /** True while the subscriber's working hours (9.7.0) are active: weekdays + window in the configured zone. */
 static boolean working(Properties p,String prefix,Instant now){if(!Boolean.parseBoolean(p.getProperty(prefix+"workEnabled","false")))return false;ZoneId zone=zone(p,prefix);ZonedDateTime at=now.atZone(zone);LocalTime time=at.toLocalTime(),start=LocalTime.parse(p.getProperty(prefix+"workStart","08:00")),end=LocalTime.parse(p.getProperty(prefix+"workEnd","17:00"));
  boolean inWindow=start.equals(end)||(start.isBefore(end)?!time.isBefore(start)&&time.isBefore(end):!time.isBefore(start)||time.isBefore(end));if(!inWindow)return false;
  // Overnight shifts count for the day they started.
  int day=(!start.isBefore(end)&&time.isBefore(end)?at.minusDays(1):at).getDayOfWeek().getValue();return days(p.getProperty(prefix+"workDays","1,2,3,4,5")).contains(day);}
 static Set<Integer> days(String raw){Set<Integer> out=new TreeSet<>();for(String part:raw.split(",")){String v=part.trim();if(v.isEmpty())continue;int d=Integer.parseInt(v);if(d<1||d>7)throw new IllegalArgumentException("Wochentag 1–7");out.add(d);}return out;}
 private static ZoneId zone(Properties p,String prefix){return ZoneId.of(p.getProperty(prefix+"timezone","Europe/Berlin"));}
 /** Final Bark level after sleep and working hours, or null when the notice is held back.
  *  Working hours never block normal notices; they only turn critical alarms into time-sensitive ones.
  *  Emergency restarts may pierce sleep and/or working hours when the subscriber allowed it. */
 static String decide(Properties p,String prefix,String type,String level,Instant now){
  if(type.equals("emergency-restart")){
   if(!Boolean.parseBoolean(p.getProperty(prefix+"emergencyEnabled","true")))return null;
   String override=p.getProperty(prefix+"emergencyOverride","both");
   if(quiet(p,prefix,now)&&!override.equals("sleep")&&!override.equals("both"))return null;
   if(working(p,prefix,now)&&!override.equals("work")&&!override.equals("both"))return "timeSensitive";
   return "critical";
  }
  if(!allowed(p,prefix,type,level,now))return null;
  if(level.equals("critical")&&working(p,prefix,now))return "timeSensitive";
  return level;
 }
 static Map<String,String[]> rules(String input){Map<String,String[]> result=new LinkedHashMap<>();for(String row:input.split(";")){if(row.isBlank())continue;String[] a=row.split(":",-1);if(a.length!=4||!SuiteEventCenter.TYPES.contains(a[0])||!(SuiteEventCenter.LEVELS.contains(a[1])||a[1].equals("off"))||!a[2].matches("[A-Za-z0-9_.-]{1,64}"))throw new IllegalArgumentException("Ungültige Bark-Ereignisregel");int repeat=Integer.parseInt(a[3]);if(repeat!=0&&(repeat<60||repeat>86400))throw new IllegalArgumentException("Wiederholung: 0 oder 60–86400 Sekunden");result.put(a[0],a);}return result;}
}
