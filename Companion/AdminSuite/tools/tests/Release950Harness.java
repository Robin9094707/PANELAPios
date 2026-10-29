package eu.simplexsmp.adminsuite;
import java.nio.file.*;
import java.util.*;

/** 9.5.0: client brand parsing, ticket attachments, discarded deliveries, delete cleanup and Bark levels per priority. */
public final class Release950Harness {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        // Self-reported brands with versions and loaders are recognised; unknown parts stay unusual.
        check(ClientBrands.known("lunarclient:v2.23.11-2644,fabric").equals("Lunar Client · Fabric"),"Lunar Client with version and loader");
        check(ClientBrands.known("lunarclient:v2.24.0-2700").equals("Lunar Client"),"Lunar Client version only");
        check(ClientBrands.known("fabric,lunarclient:v2.1").startsWith("Lunar Client"),"client name before loader");
        check(ClientBrands.known("LabyMod 4").equals("LabyMod")&&ClientBrands.known("Feather Fabric").equals("Feather Client"),"LabyMod/Feather variants");
        check(ClientBrands.known("fml,forge").equals("Forge"),"Forge pair collapses");
        check(ClientBrands.known("fabric,Robins PC").isEmpty()&&ClientBrands.known("lunarclient:v1,meteor").isEmpty(),"unknown parts keep the brand unusual");
        check(ClientBrands.known("§clunarclient").isEmpty(),"formatting codes rejected");
        // Bark interruption levels per ticket priority.
        check(TicketManager.barkType("low").equals("ticket-low")&&TicketManager.barkLevel("low").equals("passive"),"low tickets are silent");
        check(TicketManager.barkType("normal").equals("ticket")&&TicketManager.barkLevel("normal").equals("active"),"normal tickets keep the 9.4.0 type");
        check(TicketManager.barkType("high").equals("ticket-high")&&TicketManager.barkLevel("high").equals("timeSensitive"),"high tickets are time-sensitive");
        check(TicketManager.barkType("urgent").equals("ticket-urgent")&&TicketManager.barkLevel("urgent").equals("critical"),"urgent tickets are critical");
        check(SuiteEventCenter.TYPES.containsAll(Set.of("ticket-low","ticket-high"))&&BarkPolicy.rules("ticket-low:passive:minuet:0;ticket-high:timeSensitive:bell:0").size()==2,"new types accepted in Bark rules");
        Path folder=Files.createTempDirectory("suite950-");
        try{
            TicketStore store=new TicketStore(folder);UUID alex=new UUID(0,9);
            var t=store.create(alex,"Alex","","minecraft","general","normal","de","","Hallo","Alex","");
            var reply=store.reply(t.id,"Robin","app","staff","app","Bitte Screenshot");
            Map<String,Object> meta=store.storeAttachment(t.id,"bild 1.png","image/png",new byte[]{(byte)0x89,'P','N','G',1,2,3});
            check(Boolean.TRUE.equals(meta.get("image"))&&String.valueOf(meta.get("id")).matches(TicketStore.ATTACHMENT_ID)&&Files.size(store.attachmentFile(t.id,String.valueOf(meta.get("id"))))==7,"attachment stored with metadata");
            store.attach(t.id,reply.id(),List.of(meta));check(store.get(t.id).last().attachments().size()==1,"attachment linked to message");
            check(store.discard(t.id,0)==1&&store.pending(alex).isEmpty()&&store.get(t.id).last().discarded()&&store.get(t.id).last().delivered(),"pending reply discarded");
            check(store.discard(t.id,0)==0,"discard is idempotent");
            try{store.storeAttachment(t.id,"big.bin","application/octet-stream",new byte[TicketStore.MAX_ATTACHMENT+1]);throw new AssertionError("oversize accepted");}catch(IllegalArgumentException expected){checks++;}
            try{store.attachmentFile(t.id,"../config.yml");throw new AssertionError("path traversal accepted");}catch(IllegalArgumentException expected){checks++;}
            store.stop();
            TicketStore again=new TicketStore(folder);var last=again.get(t.id).last();
            check(last.discarded()&&last.attachments().size()==1&&"bild_1.png".equals(last.attachments().get(0).get("name"))==false&&last.attachments().get(0).get("name").equals("bild 1.png"),"attachments and discard survive a restart");
            // 9.4.0 ticket files (no attachments/discarded keys) still load.
            Files.writeString(folder.resolve("ticket-50.json"),"{\"id\":50,\"ownerName\":\"Old\",\"category\":\"general\",\"priority\":\"high\",\"status\":\"open\",\"language\":\"de\",\"subject\":\"Alt\",\"created\":1,\"updated\":1,\"items\":[{\"id\":1,\"author\":\"Old\",\"role\":\"player\",\"source\":\"minecraft\",\"text\":\"x\",\"at\":1,\"delivered\":true}]}");
            again.stop();TicketStore third=new TicketStore(folder);
            check(third.get(50)!=null&&third.get(50).last().attachments().isEmpty()&&!third.get(50).last().discarded(),"9.4.0 ticket files stay compatible");
            check(third.delete(t.id),"delete");third.stop();Thread.sleep(200);
            check(!Files.exists(folder.resolve("files").resolve(Long.toString(t.id))),"attachments removed with the ticket");
        }finally{try(var paths=Files.walk(folder)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        System.out.println("Release950Harness: "+checks+" production checks passed");
    }
}
