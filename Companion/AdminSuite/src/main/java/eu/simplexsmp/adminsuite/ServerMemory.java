package eu.simplexsmp.adminsuite;
import java.nio.file.*;
import java.util.*;
/** Container memory includes native allocations/cache, unlike Runtime's heap. Never substitute host RAM or heap. */
final class ServerMemory {
    record Sample(long used,long limit,String source){boolean available(){return used>=0&&limit>0&&used<=limit*2;}}
    static Sample read(){return read(Path.of("/sys/fs/cgroup"));}
    static Sample read(Path root){try{
        Path v2=root;if(!Files.isReadable(v2.resolve("memory.current"))){for(String line:Files.readAllLines(Path.of("/proc/self/cgroup")))if(line.startsWith("0::")){Path sub=root.resolve(line.substring(3).replaceFirst("^/","")).normalize();if(sub.startsWith(root))v2=sub;}}
        if(Files.isReadable(v2.resolve("memory.current"))){String limit=Files.readString(v2.resolve("memory.max")).trim();if(!limit.equals("max"))return new Sample(Long.parseLong(Files.readString(v2.resolve("memory.current")).trim()),Long.parseLong(limit),"cgroup-v2");}
        Path v1=root.resolve("memory");if(Files.isReadable(v1.resolve("memory.usage_in_bytes"))){long limit=Long.parseLong(Files.readString(v1.resolve("memory.limit_in_bytes")).trim());if(limit<1L<<60)return new Sample(Long.parseLong(Files.readString(v1.resolve("memory.usage_in_bytes")).trim()),limit,"cgroup-v1");}
    }catch(Exception ignored){}return new Sample(-1,-1,"unavailable");}
    static final class Window{private long since=-1,last=-1;boolean sample(long now,Sample s,long threshold,long duration){if(!s.available()||s.used()<threshold||last>=0&&now-last>30_000){since=-1;}last=now;if(!s.available()||s.used()<threshold)return false;if(since<0)since=now;return now-since>=duration;}void reset(){since=-1;last=-1;}}
}
