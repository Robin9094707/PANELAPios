package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.entity.Player;

/** Per-player bounded JSONL history; immutable snapshots, ordered asynchronous writes. */
final class ClientProbeDiagnostics implements AutoCloseable {
    record Row(String id,String name,String key,String mode,String fallback,String response,String result,String reason,String stage) { }
    record Entry(long at,String scan,String connection,String player,String brand,String locale,String phase,List<String> lines,List<Row> rows,String detail) { Entry{lines=List.copyOf(lines);rows=List.copyOf(rows);} }
    private final SimplexAdminSuite plugin;
    private final Path directory;
    private final Map<UUID,ArrayDeque<Entry>> histories=new HashMap<>();
    private final Map<UUID,Long> generations=new HashMap<>();
    private final Set<UUID> dirty=new LinkedHashSet<>();
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Simplex-client-diagnostics");t.setDaemon(true);return t;});
    private boolean flushing,closed;
    ClientProbeDiagnostics(SimplexAdminSuite p){plugin=p;directory=p.getDataFolder().toPath().resolve("client-probe-diagnostics");}
    synchronized List<Entry> entries(UUID id){return List.copyOf(history(id));}
    private int maximum(){return Math.max(32,Math.min(2048,plugin.getConfig().getInt("client-security.probes.diagnostics.max-entries-per-player",256)));}
    private ArrayDeque<Entry> history(UUID id){return histories.computeIfAbsent(id,key->{ArrayDeque<Entry> entries=new ArrayDeque<>();Path file=directory.resolve(key+".jsonl");if(Files.exists(file))try(BufferedReader reader=Files.newBufferedReader(file)){String line;while((line=reader.readLine())!=null){try{Entry e=decode(line);entries.addLast(e);while(entries.size()>maximum())entries.removeFirst();}catch(RuntimeException invalid){plugin.getLogger().warning("Invalid client diagnostic entry for "+id+"; remaining history kept.");}}}catch(IOException error){plugin.getLogger().warning("Client diagnostics read failed: "+error.getMessage());}return entries;});}
    synchronized long generation(UUID id){return generations.getOrDefault(id,0L);}
    synchronized boolean recordCurrent(UUID id,long generation,Entry entry){if(closed||generation!=generation(id))return false;record(id,entry);return true;}
    synchronized void record(UUID id,Entry entry){if(closed)return;ArrayDeque<Entry> rows=history(id);rows.addLast(entry);while(rows.size()>maximum())rows.removeFirst();dirty.add(id);if(!flushing){flushing=true;writer.execute(this::flush);}/* Raw replies remain in files; console shows final summaries only. */}
    private void flush(){while(true){UUID id;List<Entry> snapshot;synchronized(this){if(dirty.isEmpty()){flushing=false;return;}id=dirty.iterator().next();dirty.remove(id);snapshot=List.copyOf(history(id));}try{write(id,snapshot);}catch(IOException error){plugin.getLogger().warning("Client diagnostic write failed for "+id+": "+error.getMessage());}}}
    private void write(UUID id,List<Entry> rows)throws IOException{Files.createDirectories(directory);Path file=directory.resolve(id+".jsonl");if(rows.isEmpty()){Files.deleteIfExists(file);return;}Path temp=Files.createTempFile(directory,"diagnostics-",".tmp");try{try(BufferedWriter output=Files.newBufferedWriter(temp)){for(Entry row:rows){output.write(encode(row));output.newLine();}}try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}}
    void clear(UUID id)throws IOException{Future<?> done;synchronized(this){if(closed)throw new IOException("Diagnostic writer stopped");generations.put(id,generation(id)+1);histories.put(id,new ArrayDeque<>());dirty.remove(id);done=writer.submit(()->{try{write(id,List.of());}catch(IOException error){throw new UncheckedIOException(error);}});}try{done.get(5,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("Interrupted diagnostic clear",error);}catch(ExecutionException|TimeoutException error){throw new IOException("Diagnostic clear failed",error);}}
    void dump(UUID id){List<Entry> entries=entries(id);plugin.getLogger().info("[ClientProbe] retained history "+id+" • entries="+entries.size());for(Entry entry:entries)print(id,entry);}
    private void print(UUID id,Entry e){plugin.getLogger().info("[ClientProbe] player="+escaped(e.player)+" uuid="+id+" scan="+e.scan+" phase="+e.phase+" brand="+escaped(e.brand)+" locale="+escaped(e.locale)+" detail="+escaped(e.detail));if(!e.lines.isEmpty())plugin.getLogger().info("[ClientProbe] raw-lines="+escapedList(e.lines));for(Row r:e.rows)plugin.getLogger().info("[ClientProbe] "+r.id+" result="+r.result+" stage="+r.stage+" reason="+r.reason+" key="+escaped(r.key)+" fallback="+escaped(r.fallback)+" response="+escaped(r.response));}
    static String escaped(String value){return DiscordBotManager.Json.stringify(value==null?"":value).replace("§","\\u00a7").replace("&","\\u0026");}
    static String escapedList(List<String> values){return "["+String.join(", ",values.stream().map(ClientProbeDiagnostics::escaped).toList())+"]";}
    static List<String> safeLines(String[] lines){if(lines==null)return List.of();List<String> result=new ArrayList<>();for(int i=0;i<Math.min(lines.length,16);i++){String s=lines[i]==null?"":lines[i];result.add(s.length()>4096?s.substring(0,4096)+" [OVERSIZE TRUNCATED]":s);}return List.copyOf(result);}
    static String encode(Entry e){List<Object> rows=new ArrayList<>();for(Row r:e.rows)rows.add(Map.of("id",r.id,"name",r.name,"key",r.key,"mode",r.mode,"fallback",r.fallback,"response",r.response,"result",r.result,"reason",r.reason,"stage",r.stage));Map<String,Object> value=new LinkedHashMap<>();value.put("at",e.at);value.put("scan",e.scan);value.put("connection",e.connection);value.put("player",e.player);value.put("brand",e.brand);value.put("locale",e.locale);value.put("phase",e.phase);value.put("lines",e.lines);value.put("rows",rows);value.put("detail",e.detail);return DiscordBotManager.Json.stringify(value);}
    static Entry decode(String json){Object parsed=DiscordBotManager.Json.parse(json);if(!(parsed instanceof Map<?,?> m))throw new IllegalArgumentException("Invalid diagnostic");List<String> lines=new ArrayList<>();if(m.get("lines") instanceof List<?> list)for(Object item:list)lines.add(Objects.toString(item,""));List<Row> rows=new ArrayList<>();if(m.get("rows") instanceof List<?> list)for(Object item:list)if(item instanceof Map<?,?> r)rows.add(new Row(text(r,"id"),text(r,"name"),text(r,"key"),text(r,"mode"),text(r,"fallback"),text(r,"response"),text(r,"result"),text(r,"reason"),text(r,"stage")));return new Entry(((Number)m.get("at")).longValue(),text(m,"scan"),text(m,"connection"),text(m,"player"),text(m,"brand"),text(m,"locale"),text(m,"phase"),List.copyOf(lines),List.copyOf(rows),text(m,"detail"));}
    private static String text(Map<?,?> m,String key){return Objects.toString(m.get(key),"");}
    static String brand(Player p){try{String brand="";try{if(p.getConnection()!=null)brand=Objects.toString(p.getConnection().getClientBrandName(),"");}catch(RuntimeException|LinkageError ignored){}if(brand.isEmpty())brand=Objects.toString(p.getClientBrandName(),"");return brand.length()>256?brand.substring(0,256)+" [OVERSIZE BRAND]":brand;}catch(RuntimeException unsupported){return "";}}
    @Override public void close(){synchronized(this){closed=true;writer.shutdown();}try{if(!writer.awaitTermination(5,TimeUnit.SECONDS))plugin.getLogger().warning("Diagnostic writes still finishing at shutdown.");}catch(InterruptedException error){Thread.currentThread().interrupt();}}
}
