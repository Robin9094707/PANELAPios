package eu.simplexsmp.adminsuite;

import java.util.*;

/** Pure, bounded heuristics. Observations never establish cheating or account identity. */
final class SecuritySignals {
    record Finding(String code, String detail) { }
    record ClickPattern(int samples, double cps, double variation, int period, boolean suspicious) { }
    static String safe(String value) {
        if (value == null) return "";
        String clean=value.replaceAll("[\\p{Cntrl}§]", " ").strip();
        return clean.substring(0, Math.min(clean.length(), 240));
    }
    static String namespace(String channel) {
        String value=safe(channel).toLowerCase(Locale.ROOT);
        int colon=value.indexOf(':');return colon<0?value:value.substring(0,colon);
    }
    static Set<String> loaders(Collection<String> channels) {
        Set<String> out=new TreeSet<>();
        for(String channel:channels) {
            String ns=namespace(channel);
            if(Set.of("fabric","fabric-networking-api-v1","fabric-registry-sync-v0").contains(ns))out.add("fabric");
            if(Set.of("forge","fml","fml2").contains(ns))out.add("forge");
            if(Set.of("neoforge","neoforge_network").contains(ns))out.add("neoforge");
            if(ns.equals("quilt"))out.add("quilt");
        }
        return out;
    }
    static List<Finding> fingerprint(String brand,Collection<String> channels) {
        String lower=safe(brand).toLowerCase(Locale.ROOT);Set<String> loaders=loaders(channels);
        List<Finding> result=new ArrayList<>();
        if(lower.equals("vanilla")&&!loaders.isEmpty())
            result.add(new Finding("BRAND_CHANNEL_MISMATCH","Brand=vanilla; loader channels="+String.join(", ",loaders)+". Proxy/brand customization is also possible."));
        if(loaders.size()>1)
            result.add(new Finding("MULTIPLE_LOADER_SIGNALS","Loader namespaces="+String.join(", ",loaders)+". Compatibility layers can produce this combination."));
        return List.copyOf(result);
    }
    static List<Finding> concealment(String brand,String previousBrand,Collection<String> channels,
            Collection<String> previousChannels,int removals,int changes,Collection<String> privacyNamespaces) {
        List<Finding> result=new ArrayList<>();
        if(!safe(previousBrand).isBlank()&&!safe(brand).isBlank()&&!safe(previousBrand).equalsIgnoreCase(safe(brand)))
            result.add(new Finding("LIVE_BRAND_CHANGE","Brand changed within this connection: "+safe(previousBrand)+" -> "+safe(brand)+". This is a hint, not proof of concealment."));
        Set<String> privacy=new HashSet<>();for(String ns:privacyNamespaces)privacy.add(namespace(ns));
        Set<String> visible=new TreeSet<>();for(String channel:channels)if(privacy.contains(namespace(channel)))visible.add(namespace(channel));
        if(!visible.isEmpty())result.add(new Finding("VISIBLE_PRIVACY_NAMESPACE","Observed configured privacy namespace(s): "+String.join(", ",visible)+". Names are unverified and spoofable."));
        if(changes>=12&&removals>=6&&brand!=null&&brand.equalsIgnoreCase("vanilla")&&!loaders(previousChannels).isEmpty())
            result.add(new Finding("CHANNEL_CHURN_WITH_LOADER_HISTORY","Repeated unregister/register activity after loader channels were observed in this connection; current brand=vanilla. Normal renegotiation is also possible."));
        // Missing channels, a missing brand and vanilla alone are deliberately never findings.
        return List.copyOf(result);
    }
    static ClickPattern clicks(List<Double> intervals,int minimum,double minimumCps,double maximumCv) {
        if(intervals.size()<minimum)return new ClickPattern(intervals.size(),0,0,0,false);
        double sum=0;for(double v:intervals)if(!Double.isFinite(v)||v<5||v>500)return new ClickPattern(intervals.size(),0,0,0,false);else sum+=v;
        double mean=sum/intervals.size(),variance=0;for(double v:intervals)variance+=(v-mean)*(v-mean);
        double cv=Math.sqrt(variance/intervals.size())/mean,cps=1000/mean;
        int period=0;
        for(int p=2;p<=8&&p*8<=intervals.size();p++){
            double error=0;for(int i=p;i<intervals.size();i++)error+=Math.abs(intervals.get(i)-intervals.get(i-p));
            if(error/(intervals.size()-p)<=1.25){period=p;break;}
        }
        return new ClickPattern(intervals.size(),cps,cv,period,cps>=minimumCps&&(cv<=maximumCv||period>0));
    }
    static double distanceToBox(double x,double y,double z,double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        double dx=Math.max(Math.max(minX-x,0),x-maxX),dy=Math.max(Math.max(minY-y,0),y-maxY),dz=Math.max(Math.max(minZ-z,0),z-maxZ);
        return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
    static boolean outsideReach(double distance,double reach,double tolerance) {
        return Double.isFinite(distance)&&Double.isFinite(reach)&&Double.isFinite(tolerance)&&reach>0&&reach<=32&&tolerance>=0&&distance>reach+tolerance;
    }
    static boolean healthy(double tps,int ping,double tickMillis) {
        return Double.isFinite(tps)&&Double.isFinite(tickMillis)&&tps>=18.5&&ping>=0&&ping<=200&&tickMillis>=20&&tickMillis<=85;
    }
}
