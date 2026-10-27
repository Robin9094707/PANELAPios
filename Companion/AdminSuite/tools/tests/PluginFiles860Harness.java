package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Real JARs, disk publication and checksums. No stubbed file service. */
public final class PluginFiles860Harness {
    static int checks;
    static void check(boolean test,String why){checks++;if(!test)throw new AssertionError(why);}
    interface Work {void run()throws Exception;}
    static void denied(Work work,String why)throws Exception{try{work.run();throw new AssertionError("accepted: "+why);}catch(IOException|IllegalArgumentException expected){checks++;}}
    static byte[] jar(String name,String version,String main,boolean paper,int major)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(JarOutputStream out=new JarOutputStream(bytes)){
            out.putNextEntry(new JarEntry(paper?"paper-plugin.yml":"plugin.yml"));out.write(("name: "+name+"\nversion: '"+version+"'\nmain: "+main+"\napi-version: '26.2'\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();
            out.putNextEntry(new JarEntry(main.replace('.','/')+".class"));DataOutputStream data=new DataOutputStream(out);data.writeInt(0xCAFEBABE);data.writeShort(0);data.writeShort(major);data.write(new byte[]{1,2,3});out.closeEntry();
        }return bytes.toByteArray();
    }
    static Map<String,Object> plan(Path installed,byte[] next,String version)throws Exception{
        return new LinkedHashMap<>(Map.of("filename",installed.getFileName().toString(),"expected",AppPluginFiles.hash(installed),"sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(next)),"name","SimplexAdminSuite","main","eu.simplex.Main","version",version));
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("sas-files860-");
        try{
            Path plugins=Files.createDirectory(root.resolve("plugins"));Files.writeString(root.resolve("bukkit.yml"),"settings:\n  update-folder: incoming/updates\n");
            Path installed=plugins.resolve("SimplexAdminSuite-8.4.0.jar");byte[] old=jar("SimplexAdminSuite","8.4.0","eu.simplex.Main",false,61), next=jar("SimplexAdminSuite","8.6.0","eu.simplex.Main",false,61);Files.write(installed,old);
            Path data=Files.createDirectory(plugins.resolve("SimplexAdminSuite"));Files.writeString(data.resolve("existing.yml"),"existing-player-data");
            AppPluginFiles service=new AppPluginFiles(plugins);Map<String,Object> plan=plan(installed,next,"8.6.0");
            check(((List<?>)service.list(false).get("items")).size()==1,"installed inventory");check(service.inspect(installed.getFileName().toString(),false).get("version").equals("8.4.0"),"version from actual JAR");
            check(service.prepare(plan).get("name").equals("SimplexAdminSuite"),"preview checks installed identity");check(!Files.exists(plugins.resolve("incoming")),"preview has no side effects");
            var queued=service.stage(plan,new ByteArrayInputStream(next));check(Boolean.TRUE.equals(queued.get("queued")),"confirmed publication");
            Path destination=plugins.resolve("incoming/updates/").resolve(installed.getFileName());check(Arrays.equals(Files.readAllBytes(destination),next),"exact uploaded bytes in configured nested update directory");
            check(Arrays.equals(Files.readAllBytes(installed),old),"running 8.4.0 JAR untouched");check(Files.readString(data.resolve("existing.yml")).equals("existing-player-data"),"existing data untouched");
            check(Boolean.TRUE.equals(service.stage(plan,new ByteArrayInputStream(next)).get("queued")),"idempotent exact queued hash");
            check(!Files.list(destination.getParent()).anyMatch(p->p.toString().endsWith(".pending")),"temporary files cleaned");
            byte[] other=jar("SimplexAdminSuite","8.7.0","eu.simplex.Main",false,61);var conflict=plan(installed,other,"8.7.0");denied(()->service.stage(conflict,new ByteArrayInputStream(other)),"conflicting queued update");
            Files.delete(destination);
            denied(()->service.stage(plan,new ByteArrayInputStream(Arrays.copyOf(next,next.length-4))),"truncated upload");check(!Files.exists(destination),"no truncated JAR published");
            byte[] wrong=jar("OtherPlugin","8.6.0","eu.simplex.Main",false,61);var wrongPlan=plan(installed,wrong,"8.6.0");denied(()->service.stage(wrongPlan,new ByteArrayInputStream(wrong)),"wrong identity");
            byte[] newerJava=jar("SimplexAdminSuite","8.6.0","eu.simplex.Main",false,100);var javaPlan=plan(installed,newerJava,"8.6.0");denied(()->service.stage(javaPlan,new ByteArrayInputStream(newerJava)),"unsupported main-class Java");
            var stale=new LinkedHashMap<>(plan);stale.put("expected","0".repeat(64));denied(()->service.stage(stale,new ByteArrayInputStream(next)),"stale installed hash");
            var downgrade=new LinkedHashMap<>(plan);downgrade.put("version","8.3.0");denied(()->service.prepare(downgrade),"downgrade");
            denied(()->service.inspect("../outside.jar",false),"path traversal");
            Path outside=root.resolve("outside.jar");Files.write(outside,next);Files.createSymbolicLink(plugins.resolve("linked.jar"),outside);denied(()->service.inspect("linked.jar",false),"symlink JAR");
            Files.writeString(root.resolve("bukkit.yml"),"settings:\n  update-folder: ../outside\n");denied(()->service.stage(plan,new ByteArrayInputStream(next)),"update traversal");
            Files.writeString(root.resolve("bukkit.yml"),"settings:\n  update-folder: linked\n");Files.createSymbolicLink(plugins.resolve("linked"),root);denied(()->service.stage(plan,new ByteArrayInputStream(next)),"symlink update directory");
            Files.writeString(root.resolve("bukkit.yml"),"settings:\n  update-folder: update\n");byte[] paper=jar("SimplexAdminSuite","8.6.0","eu.simplex.Main",true,61);var paperPlan=plan(installed,paper,"8.6.0");check(Boolean.TRUE.equals(service.stage(paperPlan,new ByteArrayInputStream(paper)).get("queued")),"paper-plugin.yml supported");
            check(AppPluginFiles.fingerprints(installed).get("sha256").equals(plan.get("expected")),"SHA-256 stream fingerprint");
            Path whitespace=root.resolve("hash.bin");Files.write(whitespace,new byte[]{9,1,10,2,13,3,32,4,5});check(AppPluginFiles.fingerprints(whitespace).get("curse").equals(713005499L),"CurseForge Murmur2 reference vector");
            System.out.println("PASS "+checks+" real plugin file staging checks");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
