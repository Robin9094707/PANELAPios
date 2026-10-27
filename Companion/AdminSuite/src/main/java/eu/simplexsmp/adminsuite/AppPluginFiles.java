package eu.simplexsmp.adminsuite;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;

/** Disk-only plugin inspection and confirmed staging. Never loads a JAR or touches plugin data. */
final class AppPluginFiles {
    static final long MAX = 128L * 1024 * 1024;
    private final Path plugins, server;
    AppPluginFiles(Path plugins) throws IOException {
        this.plugins = plugins.toAbsolutePath().normalize(); this.server = this.plugins.getParent();
        directory(this.plugins);
    }
    private static void directory(Path path) throws IOException {
        Path part = path.getRoot();
        for (Path name : path) { part = part.resolve(name); if (Files.isSymbolicLink(part)) throw new IOException("Symbolische Links sind für Plugin-Updates nicht erlaubt: " + name); }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Plugin-Verzeichnis fehlt");
    }
    static void filename(String name) {
        if (name == null || name.length() > 240 || name.startsWith(".") || name.contains("/") || name.contains("\\") || name.chars().anyMatch(c -> c < 32) || !(name.endsWith(".jar") || name.endsWith(".jar.disabled")))
            throw new IllegalArgumentException("Ein einzelner Plugin-JAR-Dateiname ist erforderlich");
    }
    private Path file(Path folder, String name) throws IOException {
        filename(name); directory(folder); Path path = folder.resolve(name);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX) throw new IOException("Plugin fehlt, ist ein Link oder größer als 128 MiB: " + name);
        return path;
    }
    Path updateDirectory(boolean create) throws Exception {
        Path config = server.resolve("bukkit.yml");
        if (Files.isSymbolicLink(config) || !Files.isRegularFile(config) || Files.size(config) > 8 * 1024 * 1024) throw new IOException("bukkit.yml fehlt oder ist ungültig");
        YamlConfiguration yaml = new YamlConfiguration(); yaml.load(config.toFile());
        String value = yaml.getString("settings.update-folder", "update");
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\") || value.chars().anyMatch(c -> c < 32)) throw new IOException("Nicht unterstützter Update-Ordner in bukkit.yml");
        Path folder = plugins;
        for (String part : value.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) throw new IOException("Update-Ordner muss innerhalb des Plugin-Ordners liegen");
            folder = folder.resolve(part);
            if (Files.isSymbolicLink(folder)) throw new IOException("Update-Ordner darf kein symbolischer Link sein");
            if (create && !Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) { try { Files.createDirectory(folder); } catch (FileAlreadyExistsException ignored) { } }
            if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) directory(folder);
        }
        return folder;
    }
    String publicPath(Path folder) { return "/plugins/" + plugins.relativize(folder).toString().replace(File.separatorChar, '/'); }
    Map<String,Object> list(boolean pending) throws Exception {
        Path folder = pending ? updateDirectory(false) : plugins;
        List<Object> items = new ArrayList<>(); int budget=4*1024*1024;
        if (Files.exists(folder)) {
            directory(folder);
            try (var files = Files.list(folder)) {
                for (Path path : files.sorted().limit(1024).toList()) {
                    String name = path.getFileName().toString();
                    if (!name.endsWith(".jar") && !name.endsWith(".jar.disabled")) continue;
                    Map<String,Object> row = new LinkedHashMap<>(); row.put("name", name);
                    try { Map<String,Object> info=inspect(folder, name, false);int length=String.valueOf(info.get("yaml")).getBytes(StandardCharsets.UTF_8).length;if(length>budget)throw new IOException("Metadaten bitte einzeln öffnen");budget-=length;row.putAll(info); } catch (Exception e) { row.put("error", e.getMessage()); }
                    items.add(row);
                }
            }
        }
        return Map.of("items", items, "updateDirectory", publicPath(updateDirectory(false)));
    }
    Map<String,Object> inspect(String name, boolean pending) throws Exception { return inspect(pending ? updateDirectory(false) : plugins, name, true); }
    private Map<String,Object> inspect(Path folder, String name, boolean hashes) throws Exception {
        Path path = file(folder, name);
        var before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Map<String,Object> result = descriptor(path);
        result.put("filename", name); result.put("size", before.size()); result.put("modified_at", before.lastModifiedTime().toInstant().toString());
        if (hashes) result.putAll(fingerprints(path));
        var after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size()!=after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !Objects.equals(before.fileKey(), after.fileKey())) throw new IOException("Plugin wurde beim Lesen geändert. Neu prüfen.");
        return result;
    }
    static Map<String,Object> descriptor(Path path) throws Exception {
        try (JarFile jar = new JarFile(path.toFile())) {
            String descriptor = jar.getJarEntry("paper-plugin.yml") != null ? "paper-plugin.yml" : "plugin.yml";
            JarEntry entry = jar.getJarEntry(descriptor);
            if (entry == null || entry.getSize() > 65_536) throw new IOException("Plugin-Metadaten fehlen oder sind zu groß");
            long duplicates = jar.stream().filter(e -> e.getName().equals(descriptor)).count();
            if (duplicates != 1) throw new IOException("Mehrdeutige Plugin-Metadaten");
            String text;
            try (InputStream input = jar.getInputStream(entry)) {
                byte[] data = input.readNBytes(65_537); if (data.length > 65_536) throw new IOException("Plugin-Metadaten zu groß");
                text = new String(data, StandardCharsets.UTF_8);
            }
            YamlConfiguration yaml = new YamlConfiguration(); yaml.loadFromString(text);
            String name = yaml.getString("name", ""), version = yaml.getString("version", ""), main = yaml.getString("main", "");
            if (name.isBlank() || version.isBlank() || name.length()>256 || version.length()>256 || !main.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")) throw new IOException("Plugin-Identität unvollständig");
            Map<String,Object> result = new LinkedHashMap<>(); result.put("name", name); result.put("version", version); result.put("main", main); result.put("yaml", text); result.put("descriptorFile", descriptor);
            return result;
        }
    }
    static Map<String,Object> fingerprints(Path file) throws Exception {
        MessageDigest sha256=MessageDigest.getInstance("SHA-256"), sha1=MessageDigest.getInstance("SHA-1");
        int count=0; byte[] buffer=new byte[65536];
        try(InputStream in=Files.newInputStream(file)){int n;while((n=in.read(buffer))!=-1){sha256.update(buffer,0,n);sha1.update(buffer,0,n);for(int i=0;i<n;i++)if(!whitespace(buffer[i]&255))count++;}}
        int h=count^1,k=0,shift=0,m=0x5bd1e995;
        try(InputStream in=new BufferedInputStream(Files.newInputStream(file))){int b;while((b=in.read())!=-1){if(whitespace(b))continue;k|=b<<shift;shift+=8;if(shift==32){k*=m;k^=k>>>24;k*=m;h=(h*m)^k;k=0;shift=0;}}}
        if(shift>0){h^=k;h*=m;}h^=h>>>13;h*=m;h^=h>>>15;
        return Map.of("sha256",HexFormat.of().formatHex(sha256.digest()),"sha1",HexFormat.of().formatHex(sha1.digest()),"curse",Integer.toUnsignedLong(h));
    }
    private static boolean whitespace(int b){return b==9||b==10||b==13||b==32;}
    static String hash(Path file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}return HexFormat.of().formatHex(digest.digest());
    }
    Map<String,Object> prepare(Map<String,Object> values) throws Exception {
        String name=text(values,"filename"), expected=text(values,"expected"), sha=text(values,"sha256"); filename(name);
        if(!name.endsWith(".jar") || !expected.matches("[a-f0-9]{64}") || !sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Aktive JAR und SHA-256-Prüfsummen erforderlich");
        Map<String,Object> old=inspect(plugins,name,false);old.put("sha256",hash(file(plugins,name)));
        if(!expected.equals(old.get("sha256")))throw new IOException("Installierte JAR wurde geändert. Neu auswählen.");
        if(!text(values,"name").equals(old.get("name")) || !text(values,"main").equals(old.get("main")))throw new IOException("Update gehört nicht zum ausgewählten Plugin");
        if(text(values,"version").equals(old.get("version")) || text(values,"version").isBlank())throw new IOException("Gleiche oder fehlende Update-Version");
        String previous=String.valueOf(old.get("version")), next=text(values,"version");
        if(previous.matches("[0-9]+(\\.[0-9]+){0,4}") && next.matches("[0-9]+(\\.[0-9]+){0,4}")) {
            String[] a=previous.split("\\."),b=next.split("\\.");int comparison=0;
            for(int i=0;i<Math.max(a.length,b.length)&&comparison==0;i++)comparison=new java.math.BigInteger(i<a.length?a[i]:"0").compareTo(new java.math.BigInteger(i<b.length?b[i]:"0"));
            if(comparison>=0)throw new IOException("Update-Version ist nicht neuer als die installierte Version");
        }
        return old;
    }
    private static String text(Map<String,Object> values,String key){return String.valueOf(values.getOrDefault(key,""));}
    Map<String,Object> stage(Map<String,Object> values, InputStream input) throws Exception { return stage(values,input,()->{}); }
    synchronized Map<String,Object> stage(Map<String,Object> values, InputStream input, Runnable authorize) throws Exception {
        authorize.run();prepare(values); Path folder=updateDirectory(true); String installed=text(values,"filename");
        // Keep the installed filename so Paper replaces that exact JAR and no duplicate version remains.
        Path destination=folder.resolve(installed); String wanted=text(values,"sha256");
        Map<String,Object> existing=status(installed,wanted);if(Boolean.TRUE.equals(existing.get("queued")))return existing;
        checkPending(folder,installed,text(values,"name"));
        Path temporary=Files.createTempFile(folder,"simplex-update-",".pending");
        try {
            UpdateJarVerifier.copyLimited(input,temporary,MAX);
            if(!hash(temporary).equals(wanted))throw new IOException("Upload-Prüfsumme stimmt nicht");
            Map<String,Object> next=descriptor(temporary);
            for(String field:List.of("name","main","version"))if(!Objects.equals(next.get(field),values.get(field)))throw new IOException("Hochgeladene Plugin-Identität stimmt nicht: "+field);
            verifyClass(temporary,text(values,"main"));
            prepare(values);if(!folder.equals(updateDirectory(false)))throw new IOException("Update-Ordner wurde geändert");
            checkPending(folder,installed,text(values,"name"));
            authorize.run();
            // Atomic, exclusive publication: an existing destination is never overwritten.
            try { Files.createLink(destination,temporary); }
            catch(UnsupportedOperationException e){Files.move(temporary,destination);}
            Map<String,Object> result=status(installed,wanted);
            if(!Boolean.TRUE.equals(result.get("queued")))throw new IOException("Update konnte nach dem Vormerken nicht bestätigt werden");
            return result;
        }finally{Files.deleteIfExists(temporary);}
    }
    private static void verifyClass(Path path,String main)throws Exception{
        try(JarFile jar=new JarFile(path.toFile())){JarEntry entry=jar.getJarEntry(main.replace('.','/')+".class");if(entry==null)throw new IOException("Plugin-Hauptklasse fehlt");
            try(DataInputStream input=new DataInputStream(jar.getInputStream(entry))){if(input.readInt()!=0xCAFEBABE)throw new IOException("Ungültige Hauptklasse");int minor=input.readUnsignedShort(),major=input.readUnsignedShort();if(minor==65535||major>Runtime.version().feature()+44)throw new IOException("Update benötigt eine neuere Java-Version oder Preview-Funktionen");}}
    }
    private void checkPending(Path folder,String filename,String identity)throws Exception{
        try(var paths=Files.list(folder)){for(Path path:paths.filter(p->p.getFileName().toString().endsWith(".jar")).toList()){
            String name=path.getFileName().toString();file(folder,name);
            if(name.equalsIgnoreCase(filename))throw new IOException("Update-Dateiname bereits belegt. Vorgemerktes Update zuerst prüfen.");
            if(identity.equals(descriptor(path).get("name")))throw new IOException("Für dieses Plugin ist bereits ein anderes Update vorgemerkt");
        }}
    }
    Map<String,Object> status(String filename,String expected)throws Exception{
        filename(filename);if(!expected.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("SHA-256 erforderlich");Path folder=updateDirectory(false);Path file=folder.resolve(filename);
        if(Files.exists(folder))directory(folder);
        boolean queued=Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)<=MAX&&hash(file).equals(expected);
        return Map.of("queued",queued,"updateDirectory",publicPath(folder),"filename",filename,"sha256",expected,"restartRequired",true);
    }
}
