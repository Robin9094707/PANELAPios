package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Bounded structured JSON (a YAML subset) reader. No Bukkit calls and no file writes. */
final class PlayerStatistics {
    private PlayerStatistics(){}
    static Map<String,Long> read(File file)throws IOException{
        if(file==null||!file.isFile())throw new IOException("Keine Statistikdatei vorhanden");
        byte[] bytes;
        try(InputStream input=Files.newInputStream(file.toPath())){bytes=input.readNBytes(8_000_001);}
        if(bytes.length>8_000_000)throw new IOException("Statistikdatei größer als 8 MB");
        LoaderOptions options=new LoaderOptions();options.setCodePointLimit(8_000_000);options.setMaxAliasesForCollections(0);options.setNestingDepthLimit(32);options.setAllowDuplicateKeys(false);
        try{
            Object parsed=new Yaml(new SafeConstructor(options)).load(new String(bytes,StandardCharsets.UTF_8));
            if(!(parsed instanceof Map<?,?> root)||!(root.get("stats") instanceof Map<?,?> categories))throw new IOException("Ungültige Vanilla-Statistikstruktur");
            Map<String,Long> values=new TreeMap<>();
            for(var category:categories.entrySet())if(category.getValue() instanceof Map<?,?> entries){
                for(var entry:entries.entrySet())if(entry.getValue() instanceof Number n){
                    String key="minecraft:custom".equals(category.getKey())?String.valueOf(entry.getKey()):category.getKey()+" / "+entry.getKey();
                    values.put(key,n.longValue());
                }
            }
            if(!values.containsKey("minecraft:play_time")&&values.containsKey("minecraft:play_one_minute"))values.put("minecraft:play_time",values.get("minecraft:play_one_minute"));
            return Collections.unmodifiableMap(values);
        }catch(RuntimeException error){throw new IOException("Statistikdatei konnte nicht verarbeitet werden: "+error.getClass().getSimpleName(),error);}
    }
}
