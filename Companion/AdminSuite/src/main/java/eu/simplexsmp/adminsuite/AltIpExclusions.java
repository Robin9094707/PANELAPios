package eu.simplexsmp.adminsuite;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Reversible evidence exclusions. Raw observations stay available until explicitly purged. */
final class AltIpExclusions {
    static final String DEFAULT_IP="82.165.45.159";
    private final Path file;
    private final Properties state=new Properties();
    private volatile Set<String> excluded=Set.of();
    AltIpExclusions(Path file)throws IOException {
        this.file=file;
        if(Files.exists(file))try(Reader in=Files.newBufferedReader(file)){state.load(in);}
        else {state.setProperty("excluded",DEFAULT_IP);state.setProperty("revision","1");AtomicState.save(file,state,"Alt IP exclusions v1");}
        TreeSet<String> ips=new TreeSet<>();for(String ip:state.getProperty("excluded","").split(","))if(!ip.isBlank())ips.add(normalize(ip));excluded=Set.copyOf(ips);
    }
    static String normalize(String value){
        if(value==null||!(VpnIpCheckService.isIpLiteral(value.strip())||value.indexOf(':')>=0&&value.strip().matches("[0-9A-Fa-f:.]+")))throw new IllegalArgumentException("Eine genaue IPv4- oder IPv6-Adresse ist erforderlich; keine Hostnamen oder CIDR-Netze.");
        try {return InetAddress.getByName(value.strip()).getHostAddress();}catch(UnknownHostException error){throw new IllegalArgumentException("Ungültige IP-Adresse");}
    }
    static String canonical(String value){try{return normalize(value);}catch(IllegalArgumentException invalid){return value==null?"":value;}}
    boolean contains(String value){return excluded.contains(canonical(value));}
    Set<String> values(){return new TreeSet<>(excluded);}
    String revision(){return state.getProperty("revision","1");}
    boolean needsRepair(){return !revision().equals(state.getProperty("repaired",""));}
    synchronized void change(String raw,boolean ignore)throws IOException {
        String ip=normalize(raw);TreeSet<String> next=new TreeSet<>(excluded);
        if(ignore)next.add(ip);else next.remove(ip);
        if(next.equals(excluded))return;
        Properties copy=new Properties();copy.putAll(state);copy.setProperty("excluded",String.join(",",next));copy.setProperty("revision",UUID.randomUUID().toString());
        AtomicState.save(file,copy,"Alt IP exclusions v1");state.clear();state.putAll(copy);excluded=Set.copyOf(next);
    }
    synchronized void repaired(String report)throws IOException {Properties next=new Properties();next.putAll(state);next.setProperty("repaired",revision());next.setProperty("last-report",report);next.setProperty("repaired-at",Long.toString(System.currentTimeMillis()));AtomicState.save(file,next,"Alt IP exclusions v1");state.clear();state.putAll(next);}
    String report(){return state.getProperty("last-report","");}
    Map<String,Long> evidence(Map<String,Long> raw){Map<String,Long> result=new HashMap<>();synchronized(raw){raw.forEach((ip,at)->{if(!contains(ip))result.merge(canonical(ip),at,Math::max);});}return result;}
    static Path backup(Path data)throws IOException {
        Path root=data.toAbsolutePath().normalize();Path output=root.resolve("alt-detection/ip-repair-backups/"+System.currentTimeMillis()+"-"+UUID.randomUUID());
        List<Path> files=new ArrayList<>();
        for(String name:List.of("alt-detection/identity-bans.yml","alt-detection/blocked-identifiers.yml","alt-detection/ultra-bans.yml","alt-detection/ip-exclusions.properties","suite-policies.properties","admin-6/alt-right-policies.yml")){Path p=root.resolve(name);if(Files.isRegularFile(p))files.add(p);}
        Path players=root.resolve("players");if(Files.isDirectory(players))try(var dirs=Files.list(players)){for(Path dir:dirs.toList()){Path p=dir.resolve("profil.yml");if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))files.add(p);}}
        for(Path source:files){Path target=output.resolve(root.relativize(source));Files.createDirectories(target.getParent());Files.copy(source,target);}
        Files.createDirectories(output);return output;
    }
}
