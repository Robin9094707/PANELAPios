package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Durable support tickets: one atomic JSON file per ticket, written off the main thread in order. */
final class TicketStore {
    static final List<String> CATEGORIES=List.of("general","technical","report","discord","other");
    static final List<String> PRIORITIES=List.of("low","normal","high","urgent");
    static final List<String> STATUSES=List.of("open","answered","closed");
    static final int MAX_TICKETS=5000,MAX_MESSAGES=300,MAX_TEXT=1500;

    /** Attachments are metadata only ({id,name,type,size,image}); bytes live under files/<ticket>/<id>. */
    record Message(long id,String author,String authorId,String role,String source,String text,long at,boolean delivered,boolean discarded,List<Map<String,Object>> attachments) {
        Message(long id,String author,String authorId,String role,String source,String text,long at,boolean delivered){this(id,author,authorId,role,source,text,at,delivered,false,List.of());}
        Message with(boolean delivered,boolean discarded,List<Map<String,Object>> attachments){return new Message(id,author,authorId,role,source,text,at,delivered,discarded,List.copyOf(attachments));}
        Map<String,Object> json(){Map<String,Object> row=SimplexAdminSuite.appMap("id",id,"author",author,"authorId",authorId,"role",role,"source",source,"text",text,"at",at,"delivered",delivered);if(discarded)row.put("discarded",true);if(!attachments.isEmpty())row.put("attachments",new ArrayList<>(attachments));return row;}
    }
    static final class Ticket {
        long id,created,updated,closedAt,sequence;
        UUID owner;String ownerName="",discordUser="",source="minecraft",category="general",priority="normal",status="open",language="en",subject="",claimedBy="",closedBy="",channel="",channelMode="";
        final List<Message> messages=new ArrayList<>();
        boolean active(){return !status.equals("closed");}
        int undelivered(){int n=0;for(Message m:messages)if(!m.delivered)n++;return n;}
        Message last(){return messages.isEmpty()?null:messages.get(messages.size()-1);}
        Map<String,Object> summary(){
            Message last=last();
            return SimplexAdminSuite.appMap("id",id,"owner",owner==null?"":owner.toString(),"ownerName",ownerName,"discordUser",discordUser,"source",source,"category",category,"priority",priority,"status",status,"language",language,"subject",subject,
                "claimedBy",claimedBy,"created",created,"updated",updated,"closedAt",closedAt,"channel",channel,"messages",messages.size(),"pending",undelivered(),
                "lastAuthor",last==null?"":last.author,"lastRole",last==null?"":last.role,"preview",last==null?"":clip(last.text,140));
        }
        Map<String,Object> json(){
            Map<String,Object> row=summary();row.put("closedBy",closedBy);row.put("channelMode",channelMode);row.put("sequence",sequence);
            List<Object> rows=new ArrayList<>();for(Message m:messages)rows.add(m.json());row.put("items",rows);return row;
        }
    }
    private final Path folder;
    private final Map<Long,Ticket> tickets=new LinkedHashMap<>();
    private final Properties state=new Properties();
    private long sequence;
    private volatile Set<UUID> waiting=Set.of();
    private final ExecutorService writer;
    private volatile String lastError="";

    TicketStore(Path folder)throws IOException{
        this.folder=folder;Files.createDirectories(folder);
        writer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Simplex-Tickets");t.setDaemon(true);return t;});
        Path statePath=folder.resolve("state.properties");
        if(Files.exists(statePath))try(Reader in=Files.newBufferedReader(statePath)){state.load(in);}
        sequence=Long.parseLong(state.getProperty("sequence","0"));
        try(DirectoryStream<Path> files=Files.newDirectoryStream(folder,"ticket-*.json")){
            List<Ticket> loaded=new ArrayList<>();
            for(Path file:files){
                try{loaded.add(parse(DiscordBotManager.Json.parse(Files.readString(file))));}
                catch(Exception damaged){Files.move(file,file.resolveSibling(file.getFileName()+".damaged"),StandardCopyOption.REPLACE_EXISTING);}
            }
            loaded.sort(Comparator.comparingLong(t->t.id));
            for(Ticket t:loaded){tickets.put(t.id,t);sequence=Math.max(sequence,t.id);}
            refreshWaiting();
        }
    }
    String lastError(){return lastError;}
    void stop(){writer.shutdown();try{writer.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}

    @SuppressWarnings("unchecked") private static Ticket parse(Object raw){
        if(!(raw instanceof Map<?,?> m))throw new IllegalArgumentException("ticket");
        Ticket t=new Ticket();t.id=num(m.get("id"));t.created=num(m.get("created"));t.updated=num(m.get("updated"));t.closedAt=num(m.get("closedAt"));t.sequence=num(m.get("sequence"));
        String owner=str(m.get("owner"));t.owner=owner.isBlank()?null:UUID.fromString(owner);
        t.ownerName=str(m.get("ownerName"));t.discordUser=str(m.get("discordUser"));t.source=str(m.get("source"));t.category=valid(CATEGORIES,str(m.get("category")),"general");
        t.priority=valid(PRIORITIES,str(m.get("priority")),"normal");t.status=valid(STATUSES,str(m.get("status")),"open");t.language=str(m.get("language")).equals("de")?"de":"en";
        t.subject=str(m.get("subject"));t.claimedBy=str(m.get("claimedBy"));t.closedBy=str(m.get("closedBy"));t.channel=str(m.get("channel"));t.channelMode=str(m.get("channelMode"));
        if(m.get("items") instanceof List<?> list)for(Object item:list)if(item instanceof Map<?,?> r)
            t.messages.add(new Message(num(r.get("id")),str(r.get("author")),str(r.get("authorId")),str(r.get("role")),str(r.get("source")),str(r.get("text")),num(r.get("at")),!Boolean.FALSE.equals(r.get("delivered")),Boolean.TRUE.equals(r.get("discarded")),attachments(r.get("attachments"))));
        for(Message msg:t.messages)t.sequence=Math.max(t.sequence,msg.id);
        if(t.id<=0)throw new IllegalArgumentException("ticket id");return t;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> attachments(Object raw){
        if(!(raw instanceof List<?> list))return List.of();List<Map<String,Object>> out=new ArrayList<>();
        for(Object item:list)if(item instanceof Map<?,?> m&&str(m.get("id")).matches(ATTACHMENT_ID))out.add(new LinkedHashMap<>((Map<String,Object>)m));
        return List.copyOf(out);
    }
    static final String ATTACHMENT_ID="[a-f0-9]{32}";
    private static long num(Object v){return v instanceof Number n?n.longValue():0L;}
    private static String str(Object v){return v==null?"":String.valueOf(v);}
    private static String valid(List<String> values,String value,String fallback){return values.contains(value)?value:fallback;}
    static String clip(String text,int max){String s=text==null?"":text;return s.length()<=max?s:s.substring(0,Math.max(0,max-1))+"…";}
    static String clean(String text,int max){
        String s=text==null?"":text.replaceAll("[\\p{Cntrl}&&[^\\n]]","").replace('§',' ').strip();
        if(s.length()>max)s=s.substring(0,max);return s;
    }

    /** Owners with undelivered replies; lets the per-second delivery check skip everyone else cheaply. */
    private void refreshWaiting(){Set<UUID> next=new HashSet<>();for(Ticket t:tickets.values())if(t.owner!=null&&t.undelivered()>0)next.add(t.owner);waiting=next;}
    private void persist(Ticket t){
        refreshWaiting();
        String body=DiscordBotManager.Json.stringify(t.json());long id=t.id;String seq=Long.toString(sequence);
        try{writer.execute(()->{try{
            Path temp=Files.createTempFile(folder,"ticket-"+id,".tmp");
            try{Files.writeString(temp,body);try{Files.move(temp,folder.resolve("ticket-"+id+".json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,folder.resolve("ticket-"+id+".json"),StandardCopyOption.REPLACE_EXISTING);}}
            finally{Files.deleteIfExists(temp);}
            synchronized(state){state.setProperty("sequence",seq);AtomicState.save(folder.resolve("state.properties"),state,"Simplex ticket counters");}
            lastError="";
        }catch(IOException e){lastError="Ticket "+id+" konnte nicht gespeichert werden: "+e.getClass().getSimpleName();}});}
        catch(RejectedExecutionException stopped){lastError="Ticketspeicher gestoppt";}
    }
    private void remove(long id){try{writer.execute(()->{try{Files.deleteIfExists(folder.resolve("ticket-"+id+".json"));}catch(IOException e){lastError="Ticket "+id+" konnte nicht entfernt werden";}});}catch(RejectedExecutionException ignored){}}

    synchronized Ticket create(UUID owner,String ownerName,String discordUser,String source,String category,String priority,String language,String subject,String text,String author,String authorId){
        String body=clean(text,MAX_TEXT),title=clean(subject,100);
        if(body.isBlank())throw new IllegalArgumentException("Nachricht fehlt");
        if(!CATEGORIES.contains(category)||!PRIORITIES.contains(priority))throw new IllegalArgumentException("Kategorie oder Priorität ungültig");
        if(title.isBlank())title=clip(body.replace('\n',' '),60);
        // Keep the store bounded: oldest closed tickets go first, open tickets are never dropped.
        if(tickets.size()>=MAX_TICKETS){Long oldest=tickets.values().stream().filter(t->!t.active()).map(t->t.id).findFirst().orElse(null);if(oldest==null)throw new IllegalStateException("Zu viele offene Tickets");tickets.remove(oldest);remove(oldest);}
        Ticket t=new Ticket();t.id=++sequence;t.created=t.updated=System.currentTimeMillis();t.owner=owner;t.ownerName=clean(ownerName,64);t.discordUser=discordUser==null?"":discordUser;
        t.source=source;t.category=category;t.priority=priority;t.language="de".equals(language)?"de":"en";t.subject=title;
        t.messages.add(new Message(++t.sequence,clean(author,64),authorId==null?"":authorId,"player",source,body,t.created,true));
        tickets.put(t.id,t);persist(t);return t;
    }
    synchronized Ticket get(long id){return tickets.get(id);}
    synchronized Ticket byChannel(String channel){if(channel==null||channel.isBlank())return null;for(Ticket t:tickets.values())if(channel.equals(t.channel))return t;return null;}
    synchronized List<Ticket> all(){return new ArrayList<>(tickets.values());}
    synchronized List<Ticket> owned(UUID owner,String discord){List<Ticket> out=new ArrayList<>();for(Ticket t:tickets.values())if(owner!=null&&owner.equals(t.owner)||discord!=null&&!discord.isBlank()&&discord.equals(t.discordUser))out.add(t);Collections.reverse(out);return out;}
    synchronized int activeCount(UUID owner,String discord){int n=0;for(Ticket t:owned(owner,discord))if(t.active())n++;return n;}
    synchronized long lastCreated(UUID owner,String discord){long last=0;for(Ticket t:owned(owner,discord))last=Math.max(last,t.created);return last;}

    /** Staff answers wait for Minecraft delivery when the ticket belongs to a Minecraft profile. */
    synchronized Message reply(long id,String author,String authorId,String role,String source,String text){
        Ticket t=require(id);String body=clean(text,MAX_TEXT);if(body.isBlank())throw new IllegalArgumentException("Nachricht fehlt");
        boolean staff=role.equals("staff");
        if(!t.active()){if(staff){t.status="answered";t.closedAt=0;t.closedBy="";}else throw new IllegalArgumentException("Ticket ist geschlossen");}
        else t.status=staff?"answered":"open";
        Message m=new Message(++t.sequence,clean(author,64),authorId==null?"":authorId,role,source,body,System.currentTimeMillis(),!staff||t.owner==null);
        t.messages.add(m);while(t.messages.size()>MAX_MESSAGES)t.messages.remove(1);
        t.updated=m.at;persist(t);return m;
    }
    synchronized Message system(long id,String text,boolean deliverToPlayer){
        Ticket t=require(id);Message m=new Message(++t.sequence,"System","","system","suite",clean(text,MAX_TEXT),System.currentTimeMillis(),!deliverToPlayer||t.owner==null);
        t.messages.add(m);while(t.messages.size()>MAX_MESSAGES)t.messages.remove(1);t.updated=m.at;persist(t);return m;
    }
    synchronized Ticket close(long id,String actor){Ticket t=require(id);if(!t.active())throw new IllegalArgumentException("Ticket ist bereits geschlossen");t.status="closed";t.closedAt=t.updated=System.currentTimeMillis();t.closedBy=clean(actor,64);persist(t);return t;}
    synchronized Ticket reopen(long id){Ticket t=require(id);if(t.active())throw new IllegalArgumentException("Ticket ist bereits offen");t.status="open";t.closedAt=0;t.closedBy="";t.updated=System.currentTimeMillis();persist(t);return t;}
    synchronized Ticket claim(long id,String actor){Ticket t=require(id);t.claimedBy=clean(actor,64);t.updated=System.currentTimeMillis();persist(t);return t;}
    synchronized Ticket priority(long id,String priority){if(!PRIORITIES.contains(priority))throw new IllegalArgumentException("Priorität ungültig");Ticket t=require(id);t.priority=priority;t.updated=System.currentTimeMillis();persist(t);return t;}
    synchronized Ticket channel(long id,String channel,String mode){Ticket t=require(id);t.channel=channel==null?"":channel;t.channelMode=mode==null?"":mode;persist(t);return t;}
    synchronized Ticket linkDiscord(long id,String discord){Ticket t=require(id);t.discordUser=discord==null?"":discord;persist(t);return t;}
    synchronized boolean delete(long id){Ticket t=tickets.remove(id);if(t==null)return false;remove(id);refreshWaiting();
        Path files=folder.resolve("files").resolve(Long.toString(id));try{writer.execute(()->{try(var paths=Files.walk(files)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}catch(IOException ignored){}});}catch(RejectedExecutionException ignored){}
        return true;}
    /** Staff can drop replies that should no longer reach the player; message 0 discards all pending replies. */
    synchronized int discard(long ticket,long message){
        Ticket t=require(ticket);int count=0;
        for(int i=0;i<t.messages.size();i++){Message m=t.messages.get(i);if(!m.delivered&&(message==0||m.id==message)){t.messages.set(i,m.with(true,true,m.attachments));count++;}}
        if(count>0)persist(t);return count;
    }
    synchronized Message attach(long ticket,long message,List<Map<String,Object>> items){
        Ticket t=require(ticket);
        for(int i=0;i<t.messages.size();i++){Message m=t.messages.get(i);if(m.id==message){List<Map<String,Object>> all=new ArrayList<>(m.attachments);all.addAll(items);Message next=m.with(m.delivered,m.discarded,all.subList(0,Math.min(all.size(),10)));t.messages.set(i,next);persist(t);return next;}}
        throw new IllegalArgumentException("Nachricht nicht gefunden");
    }
    Path attachmentFile(long ticket,String id){if(!id.matches(ATTACHMENT_ID))throw new IllegalArgumentException("Ungültiger Anhang");return folder.resolve("files").resolve(Long.toString(ticket)).resolve(id);}
    /** Writes bytes synchronously (caller is a worker thread) and returns the stored metadata. */
    Map<String,Object> storeAttachment(long ticket,String name,String type,byte[] data)throws IOException{
        if(data.length==0||data.length>MAX_ATTACHMENT)throw new IllegalArgumentException("Datei leer oder größer als 8 MiB");
        synchronized(this){require(ticket);}
        String id=UUID.randomUUID().toString().replace("-","");Path file=attachmentFile(ticket,id);Files.createDirectories(file.getParent());
        Path temp=Files.createTempFile(file.getParent(),id,".tmp");try{Files.write(temp,data);Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
        String clean=clean(name,80).replaceAll("[\\\\/:*?\"<>|]","_");if(clean.isBlank())clean="datei";
        String mime=type==null||!type.matches("[a-z]+/[a-z0-9.+-]{1,60}")?"application/octet-stream":type;
        return SimplexAdminSuite.appMap("id",id,"name",clean,"type",mime,"size",(long)data.length,"image",mime.startsWith("image/"));
    }
    static final int MAX_ATTACHMENT=8*1024*1024;
    synchronized void channelDeleted(String channel){for(Ticket t:tickets.values())if(channel.equals(t.channel)){t.channel="";persist(t);}}

    record Pending(long ticket,String subject,Message message) { }
    List<Pending> pending(UUID owner){
        if(!waiting.contains(owner))return List.of();
        synchronized(this){
        List<Pending> out=new ArrayList<>();
        for(Ticket t:tickets.values())if(owner.equals(t.owner))for(Message m:t.messages)if(!m.delivered)out.add(new Pending(t.id,t.subject,m));
        return out;
        }
    }
    synchronized void delivered(long ticket,long message){
        Ticket t=tickets.get(ticket);if(t==null)return;
        for(int i=0;i<t.messages.size();i++){Message m=t.messages.get(i);if(m.id==message&&!m.delivered){t.messages.set(i,m.with(true,m.discarded,m.attachments));persist(t);return;}}
    }

    synchronized Object list(String filter,String query,int offset){
        String q=query==null?"":query.toLowerCase(Locale.ROOT);List<Ticket> rows=new ArrayList<>(tickets.values());
        rows.removeIf(t->switch(filter==null?"active":filter){case "open"->!t.status.equals("open");case "answered"->!t.status.equals("answered");case "closed"->t.active();case "all"->false;case "urgent"->!t.active()||!t.priority.equals("urgent");default->!t.active();});
        if(!q.isBlank())rows.removeIf(t->!(("#"+t.id+" "+t.subject+" "+t.ownerName+" "+t.category).toLowerCase(Locale.ROOT).contains(q)));
        // Active tickets: most urgent first, then the most recently updated.
        rows.sort(Comparator.comparingInt((Ticket t)->t.active()?PRIORITIES.indexOf(t.priority)+1:0).reversed().thenComparing(Comparator.comparingLong((Ticket t)->t.updated).reversed()));
        int start=Math.min(Math.max(0,offset),rows.size()),end=Math.min(start+50,rows.size());
        List<Object> page=new ArrayList<>();for(Ticket t:rows.subList(start,end))page.add(t.summary());
        return SimplexAdminSuite.appMap("items",page,"total",rows.size(),"next",end<rows.size()?end:-1,"counts",counts());
    }
    synchronized Map<String,Object> counts(){
        int open=0,answered=0,closed=0,urgent=0,waiting=0;
        for(Ticket t:tickets.values()){switch(t.status){case "open"->open++;case "answered"->answered++;default->closed++;}if(t.active()&&t.priority.equals("urgent"))urgent++;if(t.undelivered()>0)waiting++;}
        return SimplexAdminSuite.appMap("open",open,"answered",answered,"closed",closed,"urgent",urgent,"waitingDelivery",waiting);
    }
    private Ticket require(long id){Ticket t=tickets.get(id);if(t==null)throw new IllegalArgumentException("Ticket #"+id+" nicht gefunden");return t;}

    /** Persistent per-player hint timestamps, bounded to the newest 5000 entries. */
    long hinted(UUID id){synchronized(state){return Long.parseLong(state.getProperty("hint."+id,"0"));}}
    void hint(UUID id,long at){synchronized(state){state.setProperty("hint."+id,Long.toString(at));
        List<String> keys=state.stringPropertyNames().stream().filter(k->k.startsWith("hint.")).sorted(Comparator.comparingLong(k->Long.parseLong(state.getProperty(k,"0")))).toList();
        for(int i=0;i<keys.size()-5000;i++)state.remove(keys.get(i));}
        try{writer.execute(()->{synchronized(state){try{AtomicState.save(folder.resolve("state.properties"),state,"Simplex ticket counters");}catch(IOException e){lastError="Ticketzustand konnte nicht gespeichert werden";}}});}catch(RejectedExecutionException ignored){}
    }
    String stateValue(String key,String fallback){synchronized(state){return state.getProperty(key,fallback);}}
    void putState(String key,String value){synchronized(state){if(value==null)state.remove(key);else state.setProperty(key,value);}try{writer.execute(()->{synchronized(state){try{AtomicState.save(folder.resolve("state.properties"),state,"Simplex ticket counters");}catch(IOException e){lastError="Ticketzustand konnte nicht gespeichert werden";}}});}catch(RejectedExecutionException ignored){}}
}
