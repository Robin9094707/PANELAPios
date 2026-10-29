package eu.simplexsmp.adminsuite;

import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static eu.simplexsmp.adminsuite.DiscordBotManager.mapOf;

/**
 * Discord side of the ticket system. Runs on the existing Admin Suite bot connection (no second token):
 * public panel → topic/urgency → form → private thread or private channel, two-way relay to Minecraft.
 */
final class TicketDiscord {
    static final long VIEW=1L<<10,SEND=1L<<11,EMBED=1L<<14,ATTACH=1L<<15,HISTORY=1L<<16,MANAGE_CHANNELS=1L<<4,MANAGE_ROLES=1L<<28,MANAGE_THREADS=1L<<34,CREATE_PRIVATE=1L<<36,SEND_THREADS=1L<<38,ADMIN=8L;
    private static final Map<Long,String> NAMES=new LinkedHashMap<>();
    static{NAMES.put(VIEW,"Kanal ansehen");NAMES.put(SEND,"Nachrichten senden");NAMES.put(EMBED,"Links einbetten");NAMES.put(ATTACH,"Dateien anhängen");NAMES.put(HISTORY,"Nachrichtenverlauf lesen");NAMES.put(MANAGE_CHANNELS,"Kanäle verwalten");NAMES.put(MANAGE_ROLES,"Berechtigungen verwalten");NAMES.put(MANAGE_THREADS,"Threads verwalten");NAMES.put(CREATE_PRIVATE,"Private Threads erstellen");NAMES.put(SEND_THREADS,"Nachrichten in Threads senden");}
    private final SimplexAdminSuite plugin;
    private final DiscordBotManager bot;
    private record Draft(String lang,String category,String priority,long expires) { }
    private final Map<String,Draft> drafts=new ConcurrentHashMap<>();
    private volatile String lastError="";

    TicketDiscord(SimplexAdminSuite plugin,DiscordBotManager bot){this.plugin=plugin;this.bot=bot;}
    private TicketManager manager(){return plugin.tickets;}
    String channelId(){return plugin.getConfig().getString("tickets.discord.channel-id","").trim();}
    String mode(){return plugin.getConfig().getString("tickets.discord.mode","thread").equals("channel")?"channel":"thread";}
    String categoryId(){return plugin.getConfig().getString("tickets.discord.category-id","").trim();}
    String logChannelId(){return plugin.getConfig().getString("tickets.discord.log-channel-id","").trim();}
    List<String> staffRoles(){List<String> roles=plugin.getConfig().getStringList("tickets.discord.staff-role-ids");if(roles.isEmpty())roles=plugin.getConfig().getStringList("discord-bot.admin.role-ids");return roles.stream().filter(r->r.matches("[0-9]{15,22}")).toList();}
    boolean active(){return manager()!=null&&manager().enabled()&&plugin.getConfig().getBoolean("tickets.discord.enabled",true)&&bot.enabled()&&bot.configured();}
    boolean ready(){return active()&&bot.ticketRunning()&&channelId().matches("[0-9]{15,22}");}
    String lastError(){return lastError;}

    Map<String,Object> command(){
        List<Object> priorities=new ArrayList<>();for(String p:TicketStore.PRIORITIES)priorities.add(mapOf("name",TicketManager.priority(p,true)+" / "+TicketManager.priority(p,false),"value",p));
        return mapOf("name","ticket","description","Support-Tickets einrichten und bearbeiten / support tickets","type",1,"options",List.of(
            mapOf("type",1,"name","setup","description","Ticketkanal festlegen und Panel posten","options",List.of(
                mapOf("type",7,"name","channel","description","Kanal für das Ticket-Panel (private Threads entstehen hier)","channel_types",List.of(0),"required",true),
                mapOf("type",3,"name","mode","description","Private Threads oder eigene Kanäle","required",false,"choices",List.of(mapOf("name","Private Threads (empfohlen)","value","thread"),mapOf("name","Eigene Ticketkanäle","value","channel"))),
                mapOf("type",7,"name","category","description","Kategorie für Ticketkanäle (Kanal-Modus)","channel_types",List.of(4),"required",false),
                mapOf("type",8,"name","role","description","Teamrolle, die Tickets sieht und benachrichtigt wird","required",false),
                mapOf("type",7,"name","log","description","Optionaler Protokollkanal für Ticket-Ereignisse","channel_types",List.of(0,5),"required",false))),
            mapOf("type",1,"name","panel","description","Ticket-Panel neu posten oder aktualisieren"),
            mapOf("type",1,"name","diagnose","description","Berechtigungen und Einrichtung prüfen"),
            mapOf("type",1,"name","list","description","Offene Tickets anzeigen (Team)"),
            mapOf("type",1,"name","close","description","Dieses Ticket schließen / close this ticket"),
            mapOf("type",1,"name","claim","description","Dieses Ticket übernehmen (Team)"),
            mapOf("type",1,"name","priority","description","Priorität dieses Tickets ändern (Team)","options",List.of(mapOf("type",3,"name","level","description","Neue Priorität","required",true,"choices",priorities)))));
    }

    // ------------------------------------------------------------------ interactions

    @SuppressWarnings("unchecked")
    boolean interaction(Map<String,Object> interaction,Map<String,Object> data,int type){
        String custom=str(data.get("custom_id")),name=str(data.get("name"));
        if(!(type==2&&name.equals("ticket"))&&!((type==3||type==5)&&custom.startsWith("sasticket:")))return false;
        String id=str(interaction.get("id")),token=str(interaction.get("token")),channel=str(interaction.get("channel_id"));
        Map<String,Object> member=interaction.get("member") instanceof Map?(Map<String,Object>)interaction.get("member"):Map.of();
        Map<String,Object> user=member.get("user") instanceof Map?(Map<String,Object>)member.get("user"):Map.of();
        String userId=str(user.get("id")),display=display(member,user);
        if(!bot.ticketGuild().equals(str(interaction.get("guild_id")))||userId.isBlank()){reply(id,token,"Tickets","Bitte im konfigurierten Discord-Server verwenden. / Please use this in the configured server.",0xED4245,true);return true;}
        if(manager()==null||!manager().enabled()){reply(id,token,"Tickets","Das Ticketsystem ist deaktiviert. / The ticket system is disabled.",0xED4245,true);return true;}
        boolean staff=bot.ticketStaff(member,userId,staffRoles()),admin=bot.ticketAdmin(member,userId);
        try{
            if(type==2)command(id,token,channel,userId,display,staff,admin,data);
            else if(type==3)component(id,token,channel,userId,display,staff,custom,data);
            else modal(id,token,userId,display,custom,data);
        }catch(IllegalArgumentException e){reply(id,token,"Tickets",e.getMessage(),0xED4245,true);}
        return true;
    }
    private void command(String id,String token,String channel,String userId,String display,boolean staff,boolean admin,Map<String,Object> data){
        String sub=sub(data.get("options"));
        switch(sub){
            case "setup"->{
                if(!admin)throw new IllegalArgumentException("Nur Discord-Admins können Tickets einrichten.");
                String target=opt(data.get("options"),"channel"),mode=opt(data.get("options"),"mode"),category=opt(data.get("options"),"category"),role=opt(data.get("options"),"role"),log=opt(data.get("options"),"log");
                defer(id,token);
                job(token,()->{
                    Map<String,Object> settings=new LinkedHashMap<>();settings.put("channelId",target);if(!mode.isBlank())settings.put("mode",mode);if(!category.isBlank())settings.put("categoryId",category);if(!role.isBlank())settings.put("staffRoleIds",List.of(role));if(!log.isBlank())settings.put("logChannelId",log);settings.put("discordEnabled",true);
                    plugin.runAdminTask(()->{try{manager().saveSettings(settings,"Discord/"+display);}catch(IllegalArgumentException e){lastError=e.getMessage();}});
                    String panel=postPanel(target);
                    edit(token,"Tickets eingerichtet",(panel.isBlank()?"⚠️ Panel konnte nicht gepostet werden.":"✅ Panel: https://discord.com/channels/"+bot.ticketGuild()+"/"+target+"/"+panel)+"\n\n"+format(diagnose(target,mode.isBlank()?mode():mode,category.isBlank()?categoryId():category,role.isBlank()?staffRoles():List.of(role),log.isBlank()?logChannelId():log)),0x57F287);
                });
            }
            case "panel"->{if(!admin)throw new IllegalArgumentException("Nur Discord-Admins.");if(!channelId().matches("[0-9]{15,22}"))throw new IllegalArgumentException("Zuerst /ticket setup ausführen.");defer(id,token);job(token,()->{String panel=postPanel(channelId());edit(token,"Ticket-Panel",panel.isBlank()?"Panel konnte nicht gepostet werden: "+lastError:"Panel ist aktuell.",panel.isBlank()?0xED4245:0x57F287);});}
            case "diagnose"->{if(!staff&&!admin)throw new IllegalArgumentException("Nur für das Team.");defer(id,token);job(token,()->edit(token,"Ticket-Diagnose",format(diagnose(channelId(),mode(),categoryId(),staffRoles(),logChannelId())),0x5865F2));}
            case "list"->{
                if(!staff&&!admin)throw new IllegalArgumentException("Nur für das Team.");
                StringBuilder out=new StringBuilder();
                for(TicketStore.Ticket t:manager().store.all())if(t.active()&&out.length()<3600)out.append(t.priority.equals("urgent")?"🚨 ":"• ").append("**#").append(t.id).append("** ").append(RelayText.discord(TicketStore.clip(t.subject,60),80)).append(" · ").append(RelayText.discord(t.ownerName,40)).append(" · ").append(TicketManager.status(t.status,true)).append(t.channel.isBlank()?"":" · <#"+t.channel+">").append('\n');
                reply(id,token,"Offene Tickets",out.length()==0?"Keine offenen Tickets.":out.toString(),0x5865F2,true);
            }
            default->{
                TicketStore.Ticket t=manager().store.byChannel(channel);
                if(t==null)throw new IllegalArgumentException("Dieser Befehl funktioniert nur in einem Ticket. / Use this inside a ticket.");
                boolean owner=userId.equals(t.discordUser)||linkedOwner(userId,t);
                if(sub.equals("close")){if(!staff&&!admin&&!owner)throw new IllegalArgumentException("Keine Berechtigung.");if(!t.active())throw new IllegalArgumentException("Ticket ist bereits geschlossen.");closeFromDiscord(id,token,t.id,display);}
                else if(sub.equals("claim")){if(!staff&&!admin)throw new IllegalArgumentException("Nur für das Team.");manager().claim(t.id,display,true);reply(id,token,"Ticket #"+t.id,"🙋 "+RelayText.discord(display,64)+" kümmert sich um dieses Ticket.",0x57F287,false);}
                else if(sub.equals("priority")){if(!staff&&!admin)throw new IllegalArgumentException("Nur für das Team.");String level=opt(data.get("options"),"level");manager().priority(t.id,level,display,true);reply(id,token,"Ticket #"+t.id,"⚑ Priorität: **"+TicketManager.priority(level,true)+"** / "+TicketManager.priority(level,false),color(level),false);}
            }
        }
    }
    @SuppressWarnings("unchecked")
    private void component(String id,String token,String channel,String userId,String display,boolean staff,String custom,Map<String,Object> data){
        String[] parts=custom.split(":");String action=parts.length>1?parts[1]:"",arg=parts.length>2?parts[2]:"";
        switch(action){
            case "new"->{
                boolean de=arg.equals("de");
                int max=Math.max(1,plugin.getConfig().getInt("tickets.max-open-per-player",3));
                if(manager().store.activeCount(linkedMinecraft(userId),userId)>=max)throw new IllegalArgumentException(de?"Du hast bereits "+max+" offene Tickets.":"You already have "+max+" open tickets.");
                drafts.entrySet().removeIf(e->e.getValue().expires<System.currentTimeMillis());
                drafts.put(userId,new Draft(de?"de":"en","general","normal",System.currentTimeMillis()+900_000L));
                List<Object> cats=new ArrayList<>(),prios=new ArrayList<>();
                for(String c:TicketStore.CATEGORIES)cats.add(mapOf("label",TicketManager.category(c,de),"value",c,"default",c.equals("general")));
                for(String p:TicketStore.PRIORITIES)prios.add(mapOf("label",TicketManager.priority(p,de),"value",p,"description",TicketStore.clip(TicketManager.priorityHint(p,de),100),"default",p.equals("normal")));
                Map<String,Object> payload=mapOf("flags",64,"allowed_mentions",mapOf("parse",List.of()),
                    "embeds",List.of(mapOf("title",de?"🎫 Neues Ticket":"🎫 New ticket","description",de?"1. Thema wählen\n2. Dringlichkeit ehrlich wählen – 🚨 Notfall nur bei akuten Problemen\n3. **Weiter** und Anliegen beschreiben":"1. Choose a topic\n2. Choose the urgency honestly – 🚨 urgent for emergencies only\n3. **Continue** and describe your request","color",0x8B5CF6)),
                    "components",List.of(
                        mapOf("type",1,"components",List.of(mapOf("type",3,"custom_id","sasticket:cat:"+arg,"placeholder",de?"Thema":"Topic","min_values",1,"max_values",1,"options",cats))),
                        mapOf("type",1,"components",List.of(mapOf("type",3,"custom_id","sasticket:prio:"+arg,"placeholder",de?"Dringlichkeit":"Urgency","min_values",1,"max_values",1,"options",prios))),
                        mapOf("type",1,"components",List.of(mapOf("type",2,"style",1,"label",de?"Weiter":"Continue","custom_id","sasticket:form:"+arg)))));
                bot.ticketRespond(id,token,4,payload);
            }
            case "cat","prio"->{
                Draft d=drafts.getOrDefault(userId,new Draft(arg.equals("de")?"de":"en","general","normal",0));
                String value=data.get("values") instanceof List<?> values&&!values.isEmpty()?str(values.get(0)):"";
                if(action.equals("cat")&&TicketStore.CATEGORIES.contains(value))d=new Draft(d.lang,value,d.priority,System.currentTimeMillis()+900_000L);
                if(action.equals("prio")&&TicketStore.PRIORITIES.contains(value))d=new Draft(d.lang,d.category,value,System.currentTimeMillis()+900_000L);
                drafts.put(userId,d);bot.ticketRespond(id,token,6,null);
            }
            case "form"->{
                Draft d=drafts.getOrDefault(userId,new Draft(arg.equals("de")?"de":"en","general","normal",0));boolean de=d.lang.equals("de");
                List<Object> rows=new ArrayList<>();
                rows.add(mapOf("type",1,"components",List.of(mapOf("type",4,"custom_id","subject","label",de?"Betreff":"Subject","style",1,"min_length",0,"max_length",100,"required",false,"placeholder",de?"Kurz zusammengefasst":"Short summary"))));
                rows.add(mapOf("type",1,"components",List.of(mapOf("type",4,"custom_id","message","label",de?"Dein Anliegen":"Your request","style",2,"min_length",5,"max_length",1500,"required",true,"placeholder",de?"Was ist passiert? Wo? Wer ist beteiligt?":"What happened? Where? Who is involved?"))));
                if(linkedMinecraft(userId)==null)rows.add(mapOf("type",1,"components",List.of(mapOf("type",4,"custom_id","minecraft","label",de?"Minecraft-Name (optional)":"Minecraft name (optional)","style",1,"min_length",0,"max_length",32,"required",false))));
                bot.ticketRespond(id,token,9,mapOf("custom_id","sasticket:modal:"+d.lang,"title",TicketStore.clip((de?"Ticket · ":"Ticket · ")+TicketManager.category(d.category,de)+" · "+TicketManager.priority(d.priority,de),45),"components",rows));
            }
            case "close","claim","reopen"->{
                long ticket=parse(arg);TicketStore.Ticket t=manager().store.get(ticket);if(t==null)throw new IllegalArgumentException("Ticket nicht gefunden.");
                boolean owner=userId.equals(t.discordUser)||linkedOwner(userId,t);
                if(action.equals("claim")){if(!staff)throw new IllegalArgumentException("Nur für das Team.");manager().claim(ticket,display,true);reply(id,token,"Ticket #"+ticket,"🙋 "+RelayText.discord(display,64)+" kümmert sich um dieses Ticket.",0x57F287,false);}
                else if(action.equals("close")){if(!staff&&!owner)throw new IllegalArgumentException("Keine Berechtigung.");if(!t.active())throw new IllegalArgumentException("Ticket ist bereits geschlossen.");closeFromDiscord(id,token,ticket,display);}
                else{if(!staff&&!owner)throw new IllegalArgumentException("Keine Berechtigung.");manager().reopen(ticket,display,true);
                    // Archived/locked threads must be reopened before the bot can post again.
                    bot.ticketRespond(id,token,6,null);reopenedAsync(ticket,display,true);}
            }
            default->throw new IllegalArgumentException("Unbekannte Ticketaktion.");
        }
    }
    private void closeFromDiscord(String id,String token,long ticket,String display){
        manager().close(ticket,display,true);
        bot.ticketRespond(id,token,4,mapOf("allowed_mentions",mapOf("parse",List.of()),"embeds",List.of(mapOf("description","🔒 Ticket geschlossen von / closed by **"+RelayText.discord(display,64)+"**","color",0x99AAB5)),
            "components",List.of(mapOf("type",1,"components",List.of(mapOf("type",2,"style",2,"label","Wieder öffnen / Reopen","custom_id","sasticket:reopen:"+ticket))))));
        bot.ticketJob(()->{try{Thread.sleep(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}archive(ticket,true);log(ticket,"🔒 Ticket #"+ticket+" geschlossen von "+RelayText.discord(display,64),0x99AAB5);});
    }
    private void modal(String id,String token,String userId,String display,String custom,Map<String,Object> data){
        boolean de=custom.endsWith(":de");Draft d=drafts.remove(userId);if(d==null)d=new Draft(de?"de":"en","general","normal",0);
        String subject=value(data.get("components"),"subject"),message=value(data.get("components"),"message"),minecraft=value(data.get("components"),"minecraft");
        if(message.isBlank())throw new IllegalArgumentException(de?"Bitte beschreibe dein Anliegen.":"Please describe your request.");
        String text=message+(minecraft.isBlank()?"":"\n\n"+(de?"Minecraft-Name (angegeben, nicht verifiziert): ":"Minecraft name (provided, not verified): ")+RelayText.plain(minecraft,32));
        defer(id,token);Draft draft=d;
        job(token,()->{
            try{
                AccountLinks.Link link=plugin.accountLinks==null?null:plugin.accountLinks.store.byDiscord(userId);
                TicketStore.Ticket t=manager().open(link==null?null:link.minecraft(),link==null?display:link.name(),userId,"discord",draft.category,draft.priority,draft.lang,subject,text,false);
                String channel=createChannel(t);
                edit(token,de?"✅ Ticket erstellt":"✅ Ticket created",channel.isBlank()?(de?"Ticket #"+t.id+" gespeichert. Der Ticketkanal konnte nicht erstellt werden – das Team wurde trotzdem benachrichtigt.":"Ticket #"+t.id+" saved. The ticket channel could not be created – staff has still been notified."):(de?"Ticket #"+t.id+": <#"+channel+">\nAntworten des Teams erscheinen dort"+(link!=null?" und im Spiel.":"."):"Ticket #"+t.id+": <#"+channel+">\nStaff replies appear there"+(link!=null?" and in game.":".")),0x57F287);
            }catch(IllegalArgumentException|IllegalStateException e){edit(token,"Tickets",e.getMessage(),0xED4245);}
        });
    }

    // ------------------------------------------------------------------ relay

    /** Messages in a ticket thread/channel: staff → player (delayed Minecraft delivery), owner → stored reply. */
    boolean message(Map<String,Object> message,Map<String,Object> member,Map<String,Object> author,String channel){
        if(manager()==null)return false;
        TicketStore.Ticket t=manager().store.byChannel(channel);if(t==null)return false;
        String content=str(message.get("content")).trim();if(content.startsWith("//"))return true;
        String userId=str(author.get("id")),name=display(member,author);String text=RelayText.fromDiscord(message,true);if(text.isBlank())return true;
        try{
            TicketStore.Message m=null;
            if(bot.ticketStaff(member,userId,staffRoles())&&!userId.equals(t.discordUser))m=manager().staffReply(t.id,name,userId,"discord",text,true);
            else if(userId.equals(t.discordUser)||linkedOwner(userId,t))m=manager().playerReply(t.id,name,userId,"discord",text,true);
            if(m!=null&&message.get("attachments") instanceof List<?> files&&!files.isEmpty()){long ticket=t.id,messageId=m.id();bot.ticketJob(()->download(ticket,messageId,files));}
        }catch(IllegalArgumentException ignored){}
        return true;
    }
    /** Copies Discord attachments (max 4, 8 MiB each) into the ticket so the app can show them after Discord's CDN links expire. */
    private void download(long ticket,long message,List<?> files){
        List<Map<String,Object>> stored=new ArrayList<>();
        for(Object raw:files){
            if(stored.size()>=4||!(raw instanceof Map<?,?> a))continue;
            String url=str(a.get("url")),name=str(a.get("filename")),type=str(a.get("content_type"));long size=a.get("size") instanceof Number n?n.longValue():0;
            if(size<=0||size>TicketStore.MAX_ATTACHMENT)continue;
            try{
                java.net.URI uri=java.net.URI.create(url);String host=uri.getHost()==null?"":uri.getHost();
                if(!"https".equals(uri.getScheme())||!(host.equals("cdn.discordapp.com")||host.equals("media.discordapp.net")))continue;
                byte[] data=bot.ticketDownload(uri,TicketStore.MAX_ATTACHMENT);
                stored.add(manager().store.storeAttachment(ticket,name,type.split(";")[0].trim().toLowerCase(Locale.ROOT),data));
            }catch(Exception e){lastError="Anhang "+name+": "+e.getClass().getSimpleName();}
        }
        if(!stored.isEmpty())try{manager().store.attach(ticket,message,stored);}catch(IllegalArgumentException ignored){}
    }
    /** Keeps line breaks and simple Markdown; neutralises mentions. */
    static String body(String text,int max){String value=text==null?"":text.replace("\r","").replace("@","@\u200b");return TicketStore.clip(value,max);}
    void openedAsync(long id){bot.ticketJob(()->{TicketStore.Ticket t=manager().store.get(id);if(t!=null&&t.channel.isBlank()&&ready())createChannel(t);});}
    void forwardAsync(long id,TicketStore.Message m){forwardAsync(id,m,false);}
    @SuppressWarnings("unchecked") void forwardAsync(long id,TicketStore.Message m,boolean mention){bot.ticketJob(()->{
        TicketStore.Ticket t=manager().store.get(id);if(t==null||!ready())return;
        String channel=t.channel.isBlank()?createChannel(t):t.channel;if(channel.isBlank())return;
        boolean staff=m.role().equals("staff");
        String label=staff?("🛡️ "+m.author()+" · Team"+(m.source().equals("app")?" (App)":" (Minecraft)")):("💬 "+m.author()+" · "+(m.source().equals("discord")?"Discord":"Minecraft"));
        Map<String,Object> embed=mapOf("author",mapOf("name",TicketStore.clip(label,250)),"description",body(m.text(),3900),"color",staff?0x5865F2:0x8B5CF6,"timestamp",java.time.Instant.ofEpochMilli(m.at()).toString());
        if(!staff&&t.owner!=null)((Map<String,Object>)embed.get("author")).put("icon_url","https://mc-heads.net/avatar/"+t.owner+"/64");
        if(staff&&t.owner!=null&&!m.delivered())embed.put("footer",mapOf("text","Zustellung in Minecraft nach "+manager().deliveryDelay()+" s Online-Zeit"));
        boolean ping=mention&&t.discordUser.matches("[0-9]{5,25}");
        Map<String,Object> payload=mapOf("embeds",List.of(embed),"allowed_mentions",ping?mapOf("parse",List.of(),"users",List.of(t.discordUser)):mapOf("parse",List.of()));
        if(ping)payload.put("content","<@"+t.discordUser+">");
        if(m.attachments().isEmpty()){post(channel,payload);return;}
        // Files go up as real Discord attachments; images are shown inline by Discord.
        List<Object[]> files=new ArrayList<>();List<Object> meta=new ArrayList<>();
        for(Map<String,Object> a:m.attachments()){
            try{byte[] data=java.nio.file.Files.readAllBytes(manager().store.attachmentFile(id,str(a.get("id"))));String file=str(a.get("name")).replaceAll("[^A-Za-z0-9._-]","_");meta.add(mapOf("id",files.size(),"filename",file));files.add(new Object[]{file,str(a.get("type")),data});}
            catch(Exception e){lastError="Anhang nicht lesbar: "+e.getClass().getSimpleName();}
        }
        payload.put("attachments",meta);
        if(Boolean.TRUE.equals(m.attachments().get(0).get("image"))&&!files.isEmpty())embed.put("image",mapOf("url","attachment://"+files.get(0)[0]));
        try{HttpResponse<String> r=bot.ticketMultipart("/channels/"+channel+"/messages",DiscordBotManager.Json.stringify(payload),files);if(r.statusCode()/100!=2)lastError="Anhang an Discord: HTTP "+r.statusCode()+" "+DiscordDiagnostics.detail(r.body(),"");}
        catch(Exception e){lastError="Anhang an Discord: "+e.getClass().getSimpleName();}
    });}
    /** Deleted in the app: remove the thread/channel; if Discord refuses, archive/lock it instead. */
    void deletedAsync(long id,String channel,String mode,String owner,String actor){bot.ticketJob(()->{
        if(!bot.enabled()||!bot.configured()||!channel.matches("[0-9]{15,22}"))return;
        try{
            HttpResponse<String> r=bot.ticketHttp("DELETE","/channels/"+channel,null);
            if(r.statusCode()/100!=2&&r.statusCode()!=404){
                if(mode.equals("channel")){if(owner.matches("[0-9]{5,25}"))bot.ticketHttp("PUT","/channels/"+channel+"/permissions/"+owner,DiscordBotManager.Json.stringify(mapOf("type",1,"allow",Long.toString(VIEW|HISTORY),"deny",Long.toString(SEND))));}
                else bot.ticketHttp("PATCH","/channels/"+channel,DiscordBotManager.Json.stringify(mapOf("archived",true,"locked",true)));
                lastError="Ticket #"+id+": Discord-Thread/-Kanal nur geschlossen (Löschen: HTTP "+r.statusCode()+", Rechte „Threads/Kanäle verwalten“ prüfen)";
            }
        }catch(Exception e){lastError="Ticket #"+id+" löschen: "+e.getClass().getSimpleName();}
        log(id,"🗑️ Ticket #"+id+" gelöscht von "+RelayText.discord(actor,64),0x99AAB5);
    });}
    void closedAsync(long id,String actor){bot.ticketJob(()->{
        TicketStore.Ticket t=manager().store.get(id);if(t==null||t.channel.isBlank()||!ready())return;
        post(t.channel,mapOf("allowed_mentions",mapOf("parse",List.of()),"embeds",List.of(mapOf("description","🔒 Ticket geschlossen von / closed by **"+RelayText.discord(actor,64)+"**","color",0x99AAB5)),
            "components",List.of(mapOf("type",1,"components",List.of(mapOf("type",2,"style",2,"label","Wieder öffnen / Reopen","custom_id","sasticket:reopen:"+id))))));
        archive(id,true);log(id,"🔒 Ticket #"+id+" geschlossen von "+RelayText.discord(actor,64),0x99AAB5);
    });}
    void reopenedAsync(long id,String actor){reopenedAsync(id,actor,true);}
    void reopenedAsync(long id,String actor,boolean announce){bot.ticketJob(()->{
        TicketStore.Ticket t=manager().store.get(id);if(t==null||t.channel.isBlank()||!ready())return;
        archive(id,false);if(announce)post(t.channel,mapOf("allowed_mentions",mapOf("parse",List.of()),"content","🔓 Wieder geöffnet von / reopened by **"+RelayText.discord(actor,64)+"**"));
        log(id,"🔓 Ticket #"+id+" wieder geöffnet von "+RelayText.discord(actor,64),0x57F287);
    });}
    void noticeAsync(long id,String text){bot.ticketJob(()->{TicketStore.Ticket t=manager().store.get(id);if(t==null||t.channel.isBlank()||!ready())return;post(t.channel,mapOf("allowed_mentions",mapOf("parse",List.of()),"content",TicketStore.clip(RelayText.plain(text,1900),1900)));});}

    /** Creates the private thread/channel once and posts the opening message. Runs on the bot feature worker. */
    @SuppressWarnings("unchecked")
    String createChannel(TicketStore.Ticket t){
        synchronized(this){
            TicketStore.Ticket current=manager().store.get(t.id);if(current!=null&&!current.channel.isBlank())return current.channel;
            if(!ready()){lastError="Discord-Tickets nicht eingerichtet oder Bot nicht verbunden";return "";}
            String name=("ticket-"+t.id+"-"+t.ownerName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-")).replaceAll("-+$","");if(name.length()>90)name=name.substring(0,90);
            String mode=mode(),channel;
            try{
                if(mode.equals("thread")){
                    HttpResponse<String> r=bot.ticketHttp("POST","/channels/"+channelId()+"/threads",DiscordBotManager.Json.stringify(mapOf("name",name,"type",12,"invitable",false,"auto_archive_duration",10080)));
                    channel=idOf(r);if(channel.isBlank())throw new IllegalStateException("Thread HTTP "+r.statusCode()+" "+DiscordDiagnostics.detail(r.body(),""));
                    if(t.discordUser.matches("[0-9]{5,25}"))bot.ticketHttp("PUT","/channels/"+channel+"/thread-members/"+t.discordUser,null);
                }else{
                    List<Object> overwrites=new ArrayList<>();
                    overwrites.add(mapOf("id",bot.ticketGuild(),"type",0,"allow","0","deny",Long.toString(VIEW)));
                    overwrites.add(mapOf("id",bot.ticketApplication(),"type",1,"allow",Long.toString(VIEW|SEND|EMBED|ATTACH|HISTORY),"deny","0"));
                    for(String role:staffRoles())overwrites.add(mapOf("id",role,"type",0,"allow",Long.toString(VIEW|SEND|EMBED|ATTACH|HISTORY),"deny","0"));
                    if(t.discordUser.matches("[0-9]{5,25}"))overwrites.add(mapOf("id",t.discordUser,"type",1,"allow",Long.toString(VIEW|SEND|ATTACH|HISTORY),"deny","0"));
                    Map<String,Object> body=mapOf("name",name,"type",0,"topic",TicketStore.clip("Ticket #"+t.id+" · "+RelayText.plain(t.subject,200),1000),"permission_overwrites",overwrites);
                    if(categoryId().matches("[0-9]{15,22}"))body.put("parent_id",categoryId());
                    HttpResponse<String> r=bot.ticketHttp("POST","/guilds/"+bot.ticketGuild()+"/channels",DiscordBotManager.Json.stringify(body));
                    channel=idOf(r);if(channel.isBlank())throw new IllegalStateException("Kanal HTTP "+r.statusCode()+" "+DiscordDiagnostics.detail(r.body(),""));
                }
            }catch(Exception e){lastError="Ticket #"+t.id+": "+e.getMessage();manager().store.system(t.id,"Discord-Kanal konnte nicht erstellt werden: "+TicketStore.clip(String.valueOf(e.getMessage()),300),false);return "";}
            manager().store.channel(t.id,channel,mode);
            List<String> roles=plugin.getConfig().getBoolean("tickets.discord.ping-staff",true)?staffRoles():List.of();
            StringBuilder mention=new StringBuilder();for(String role:roles)mention.append("<@&").append(role).append("> ");if(t.discordUser.matches("[0-9]{5,25}"))mention.append("<@").append(t.discordUser).append(">");
            TicketStore.Message first;synchronized(manager().store){first=t.messages.isEmpty()?null:t.messages.get(0);}
            List<Object> fields=new ArrayList<>();
            fields.add(mapOf("name","Spieler / Player","value",RelayText.discord(t.ownerName,80)+(t.owner==null?"":" (Minecraft)"),"inline",true));
            fields.add(mapOf("name","Thema / Topic","value",TicketManager.category(t.category,true),"inline",true));
            fields.add(mapOf("name","Priorität / Priority","value",(t.priority.equals("urgent")?"🚨 ":"")+TicketManager.priority(t.priority,true),"inline",true));
            fields.add(mapOf("name","Quelle / Source","value",t.source.equals("discord")?"Discord":t.source.equals("app")?"App":"Minecraft","inline",true));
            Map<String,Object> embed=mapOf("title",TicketStore.clip("🎫 Ticket #"+t.id+" · "+RelayText.plain(t.subject,200),250),"description",first==null?"":body(first.text(),3500),"color",color(t.priority),"fields",fields,
                "footer",mapOf("text","Antworten hier erreichen den Spieler auch im Spiel · Nachrichten mit // bleiben intern"),"timestamp",java.time.Instant.ofEpochMilli(t.created).toString());
            if(t.owner!=null)embed.put("thumbnail",mapOf("url","https://mc-heads.net/avatar/"+t.owner+"/128"));
            post(channel,mapOf("content",mention.toString().trim(),"allowed_mentions",mapOf("parse",List.of(),"roles",roles,"users",t.discordUser.matches("[0-9]{5,25}")?List.of(t.discordUser):List.of()),"embeds",List.of(embed),
                "components",List.of(mapOf("type",1,"components",List.of(mapOf("type",2,"style",2,"label","Übernehmen / Claim","custom_id","sasticket:claim:"+t.id),mapOf("type",2,"style",4,"label","Schließen / Close","custom_id","sasticket:close:"+t.id))))));
            log(t.id,(t.priority.equals("urgent")?"🚨 ":"🎫 ")+"Neues Ticket #"+t.id+" von "+RelayText.discord(t.ownerName,64)+": <#"+channel+">",color(t.priority));
            return channel;
        }
    }
    private void archive(long id,boolean closed){
        TicketStore.Ticket t=manager().store.get(id);if(t==null||t.channel.isBlank())return;
        try{
            if(t.channelMode.equals("channel")){if(t.discordUser.matches("[0-9]{5,25}"))bot.ticketHttp("PUT","/channels/"+t.channel+"/permissions/"+t.discordUser,DiscordBotManager.Json.stringify(mapOf("type",1,"allow",Long.toString(closed?VIEW|HISTORY:VIEW|SEND|ATTACH|HISTORY),"deny",Long.toString(closed?SEND:0))));}
            else{HttpResponse<String> r=bot.ticketHttp("PATCH","/channels/"+t.channel,DiscordBotManager.Json.stringify(mapOf("archived",closed,"locked",closed)));if(r.statusCode()/100!=2)lastError="Thread #"+id+" archivieren: HTTP "+r.statusCode()+" (Threads verwalten?)";}
        }catch(Exception e){lastError="Ticket #"+id+": "+e.getClass().getSimpleName();}
    }
    private void log(long id,String text,int color){String log=logChannelId();if(!log.matches("[0-9]{15,22}"))return;post(log,mapOf("allowed_mentions",mapOf("parse",List.of()),"embeds",List.of(mapOf("description",TicketStore.clip(text,3900),"color",color,"timestamp",java.time.Instant.now().toString()))));}
    private void post(String channel,Map<String,Object> body){
        try{HttpResponse<String> r=bot.ticketHttp("POST","/channels/"+channel+"/messages",DiscordBotManager.Json.stringify(body));
            if(r.statusCode()==404&&r.body().contains("10003"))manager().store.channelDeleted(channel);
            else if(r.statusCode()/100!=2)lastError="Nachricht in <#"+channel+">: HTTP "+r.statusCode()+" "+DiscordDiagnostics.detail(r.body(),"");
        }catch(Exception e){lastError="Discord nicht erreichbar: "+e.getClass().getSimpleName();}
    }

    /** Posts or edits the public panel in place; returns the message ID or "" on failure. */
    String postPanel(String channel){
        if(!channel.matches("[0-9]{15,22}")||!bot.enabled()||!bot.configured())return "";
        Map<String,Object> body=mapOf("allowed_mentions",mapOf("parse",List.of()),
            "embeds",List.of(mapOf("title","🎫 Support · Tickets","color",0x8B5CF6,"description",
                "**Deutsch**\nBrauchst du Hilfe vom Team? Klicke auf **Ticket erstellen**, wähle Thema und Dringlichkeit und beschreibe dein Anliegen. Du bekommst einen privaten Ticketkanal. Im Spiel geht das auch mit `/ticket`.\n\n"
                +"**English**\nNeed help from staff? Click **Create ticket**, choose topic and urgency and describe your request. You get a private ticket channel. In game you can also use `/ticket`.\n\n"
                +"🚨 Notfall / Urgent: nur bei akuten Problemen · emergencies only","footer",mapOf("text",plugin.brandName()+" · Simplex Admin Suite "+plugin.getDescription().getVersion()))),
            "components",List.of(mapOf("type",1,"components",List.of(
                mapOf("type",2,"style",1,"label","Ticket erstellen","emoji",mapOf("name","🇩🇪"),"custom_id","sasticket:new:de"),
                mapOf("type",2,"style",1,"label","Create ticket","emoji",mapOf("name","🇬🇧"),"custom_id","sasticket:new:en")))));
        try{
            String previousChannel=manager().store.stateValue("panel.channel",""),previous=manager().store.stateValue("panel.message","");
            if(previousChannel.equals(channel)&&previous.matches("[0-9]{15,22}")){
                HttpResponse<String> edited=bot.ticketHttp("PATCH","/channels/"+channel+"/messages/"+previous,DiscordBotManager.Json.stringify(body));
                if(edited.statusCode()/100==2)return previous;
            }
            HttpResponse<String> r=bot.ticketHttp("POST","/channels/"+channel+"/messages",DiscordBotManager.Json.stringify(body));String id=idOf(r);
            if(id.isBlank()){lastError="Panel: HTTP "+r.statusCode()+" "+DiscordDiagnostics.detail(r.body(),"");return "";}
            manager().store.putState("panel.channel",channel);manager().store.putState("panel.message",id);return id;
        }catch(Exception e){lastError="Panel: "+e.getClass().getSimpleName();return "";}
    }

    // ------------------------------------------------------------------ diagnostics

    record Check(String level,String title,String detail) { Map<String,Object> json(){return SimplexAdminSuite.appMap("level",level,"title",title,"detail",detail);} }
    /** Computes the bot's effective channel permissions exactly like Discord (guild roles, then @everyone/role/member overwrites). */
    List<Check> diagnose(String channel,String mode,String category,List<String> staff,String log){
        List<Check> out=new ArrayList<>();
        if(manager()==null||!manager().enabled())out.add(new Check("warn","Ticketsystem deaktiviert","In den Ticketeinstellungen aktivieren."));
        if(!bot.enabled()||!bot.configured()){out.add(new Check("error","Discord-Bot nicht eingerichtet","Bot aktivieren und Token/Server-ID der Admin Suite eintragen. Tickets funktionieren ohne Discord weiter im Spiel und in der App."));return out;}
        if(!bot.ticketRunning())out.add(new Check("warn","Bot nicht verbunden","Der Bot verbindet sich gerade oder hat keinen Zugang. Server-Log prüfen."));
        try{
            Map<String,Long> roles=new HashMap<>();HttpResponse<String> rr=bot.ticketHttp("GET","/guilds/"+bot.ticketGuild()+"/roles",null);
            if(DiscordBotManager.Json.parse(rr.body()) instanceof List<?> list)for(Object raw:list)if(raw instanceof Map<?,?> r)roles.put(str(r.get("id")),parseBits(r.get("permissions")));
            HttpResponse<String> me=bot.ticketHttp("GET","/guilds/"+bot.ticketGuild()+"/members/"+bot.ticketApplication(),null);
            Set<String> botRoles=new HashSet<>();if(DiscordBotManager.Json.parse(me.body()) instanceof Map<?,?> m&&m.get("roles") instanceof List<?> list)for(Object r:list)botRoles.add(str(r));
            long base=roles.getOrDefault(bot.ticketGuild(),0L);for(String r:botRoles)base|=roles.getOrDefault(r,0L);
            if((base&ADMIN)!=0)out.add(new Check("ok","Bot ist Administrator","Alle Kanalrechte vorhanden. Für mehr Sicherheit genügen die einzelnen Rechte."));
            if(!channel.matches("[0-9]{15,22}"))out.add(new Check("error","Kein Ticketkanal festgelegt","In Discord /ticket setup ausführen oder in der App einen Kanal wählen."));
            else{
                Map<?,?> c=channel(channel,out,"Ticketkanal");
                if(c!=null){
                    long perms=effective(base,c.get("permission_overwrites"),bot.ticketGuild(),botRoles,bot.ticketApplication());
                    long need=VIEW|SEND|EMBED|HISTORY;if(mode.equals("thread"))need|=CREATE_PRIVATE|SEND_THREADS|MANAGE_THREADS;
                    if(mode.equals("thread")&&((Number)c.get("type")).intValue()!=0)out.add(new Check("error","Kanaltyp passt nicht","Private Threads gehen nur in normalen Textkanälen."));
                    out.add(missing(perms,need,"Ticketkanal #"+str(c.get("name")),mode.equals("thread")?"Panel und private Ticket-Threads":"Panel"));
                    long everyone=effective(roles.getOrDefault(bot.ticketGuild(),0L),c.get("permission_overwrites"),bot.ticketGuild(),Set.of(),"");
                    if((everyone&VIEW)==0)out.add(new Check("warn","Spieler sehen den Ticketkanal nicht","@everyone hat hier kein „Kanal ansehen“. Falls gewollt (z. B. nur verifizierte Rolle), ignorieren."));
                    for(String role:staff){long rp=effective(roles.getOrDefault(bot.ticketGuild(),0L)|roles.getOrDefault(role,0L),c.get("permission_overwrites"),bot.ticketGuild(),Set.of(role),"");
                        if(!roles.containsKey(role))out.add(new Check("error","Teamrolle nicht gefunden",role+" existiert nicht mehr."));
                        else if(mode.equals("thread")&&(rp&MANAGE_THREADS)==0&&(rp&ADMIN)==0)out.add(new Check("warn","Teamrolle ohne „Threads verwalten“","<@&"+role+"> wird in jedem Ticket erwähnt und so hinzugefügt. Mit „Threads verwalten“ im Ticketkanal sieht das Team alle privaten Tickets dauerhaft."));
                        else out.add(new Check("ok","Teamrolle bereit","<@&"+role+">"));}
                }
                String panel=manager()==null?"":manager().store.stateValue("panel.message","");
                if(panel.isBlank()||!channel.equals(manager().store.stateValue("panel.channel","")))out.add(new Check("warn","Kein Ticket-Panel gepostet","Mit /ticket panel oder in der App „Panel posten“."));
                else{HttpResponse<String> pr=bot.ticketHttp("GET","/channels/"+channel+"/messages/"+panel,null);out.add(pr.statusCode()==200?new Check("ok","Ticket-Panel vorhanden","Spieler können Tickets per Knopf erstellen."):new Check("warn","Ticket-Panel fehlt","Nachricht wurde gelöscht oder ist nicht lesbar (HTTP "+pr.statusCode()+"). Neu posten."));}
            }
            if(mode.equals("channel")){
                if(!category.matches("[0-9]{15,22}"))out.add(new Check("warn","Keine Ticketkategorie","Ticketkanäle entstehen ohne Kategorie ganz oben. Kategorie wählen."));
                else{Map<?,?> cat=channel(category,out,"Ticketkategorie");if(cat!=null){long perms=effective(base,cat.get("permission_overwrites"),bot.ticketGuild(),botRoles,bot.ticketApplication());out.add(missing(perms,VIEW|SEND|EMBED|HISTORY|ATTACH|MANAGE_CHANNELS|MANAGE_ROLES,"Kategorie "+str(cat.get("name")),"Private Ticketkanäle erstellen und beim Schließen sperren"));}}
            }
            if(staff.isEmpty())out.add(new Check("warn","Keine Teamrolle","Ohne Teamrolle sehen nur Discord-Admins und der Bot die Tickets. Rolle in /ticket setup oder der App wählen."));
            if(log.matches("[0-9]{15,22}")){Map<?,?> l=channel(log,out,"Protokollkanal");if(l!=null)out.add(missing(effective(base,l.get("permission_overwrites"),bot.ticketGuild(),botRoles,bot.ticketApplication()),VIEW|SEND|EMBED,"Protokollkanal #"+str(l.get("name")),"Ticket-Ereignisse"));}
        }catch(Exception e){out.add(new Check("error","Discord nicht erreichbar",e.getClass().getSimpleName()));}
        if(!lastError.isBlank())out.add(new Check("warn","Letzter Ticketfehler",lastError));
        return out;
    }
    private Map<?,?> channel(String id,List<Check> out,String label)throws Exception{
        HttpResponse<String> r=bot.ticketHttp("GET","/channels/"+id,null);Object raw=DiscordBotManager.Json.parse(r.body());
        if(r.statusCode()==200&&raw instanceof Map<?,?> c&&bot.ticketGuild().equals(str(c.get("guild_id"))))return c;
        out.add(new Check("error",label+" nicht erreichbar",r.statusCode()==403?"Dem Bot fehlt „Kanal ansehen“ für "+id+".":r.statusCode()==404?"Kanal "+id+" existiert nicht mehr.":"HTTP "+r.statusCode()+" für "+id+"."));return null;
    }
    private static Check missing(long perms,long need,String where,String purpose){
        if((perms&ADMIN)!=0)return new Check("ok",where,"Alle Rechte (Administrator).");
        List<String> missing=new ArrayList<>();for(var e:NAMES.entrySet())if((need&e.getKey())!=0&&(perms&e.getKey())==0)missing.add(e.getValue());
        return missing.isEmpty()?new Check("ok",where,"Alle nötigen Rechte für: "+purpose):new Check("error",where+": Rechte fehlen","Dem Bot fehlen: "+String.join(", ",missing)+". Benötigt für: "+purpose+".");
    }
    static long effective(long base,Object overwrites,String guild,Set<String> roles,String member){
        if((base&ADMIN)!=0)return ~0L;long p=base,allow=0,deny=0;
        List<?> list=overwrites instanceof List<?> l?l:List.of();
        for(Object raw:list)if(raw instanceof Map<?,?> o&&guild.equals(str(o.get("id")))){p&=~parseBits(o.get("deny"));p|=parseBits(o.get("allow"));}
        for(Object raw:list)if(raw instanceof Map<?,?> o&&o.get("type") instanceof Number t&&t.intValue()==0&&roles.contains(str(o.get("id")))){deny|=parseBits(o.get("deny"));allow|=parseBits(o.get("allow"));}
        p&=~deny;p|=allow;
        for(Object raw:list)if(raw instanceof Map<?,?> o&&o.get("type") instanceof Number t&&t.intValue()==1&&!member.isBlank()&&member.equals(str(o.get("id")))){p&=~parseBits(o.get("deny"));p|=parseBits(o.get("allow"));}
        return p;
    }
    private static long parseBits(Object v){try{return Long.parseLong(str(v));}catch(NumberFormatException e){return 0L;}}
    static String format(List<Check> checks){StringBuilder out=new StringBuilder();for(Check c:checks)out.append(c.level.equals("ok")?"✅ ":c.level.equals("warn")?"⚠️ ":"❌ ").append("**").append(c.title).append("**\n").append(c.detail).append("\n");return TicketStore.clip(out.toString(),3900);}

    /** App settings payload: channels, categories, roles and live diagnostics. Runs on the app HTTP worker. */
    Object appSettings(Map<String,Object> settings){
        Map<String,Object> out=new LinkedHashMap<>(settings);List<Object> channels=new ArrayList<>(),categories=new ArrayList<>(),roleRows=new ArrayList<>();
        out.put("botReady",bot.enabled()&&bot.configured());out.put("botConnected",bot.ticketRunning());
        if(bot.enabled()&&bot.configured())try{
            HttpResponse<String> r=bot.ticketHttp("GET","/guilds/"+bot.ticketGuild()+"/channels",null);
            if(DiscordBotManager.Json.parse(r.body()) instanceof List<?> list)for(Object raw:list)if(raw instanceof Map<?,?> c){int type=c.get("type") instanceof Number n?n.intValue():-1;Map<String,Object> row=SimplexAdminSuite.appMap("id",str(c.get("id")),"name",str(c.get("name")),"category",str(c.get("parent_id")),"type",type,"position",c.get("position") instanceof Number n?n.intValue():0);if(type==0||type==5)channels.add(row);else if(type==4)categories.add(row);}
            HttpResponse<String> rr=bot.ticketHttp("GET","/guilds/"+bot.ticketGuild()+"/roles",null);
            if(DiscordBotManager.Json.parse(rr.body()) instanceof List<?> list)for(Object raw:list)if(raw instanceof Map<?,?> role&&!bot.ticketGuild().equals(str(role.get("id")))&&!Boolean.TRUE.equals(role.get("managed")))roleRows.add(SimplexAdminSuite.appMap("id",str(role.get("id")),"name",str(role.get("name")),"color",role.get("color") instanceof Number n?n.intValue():0,"position",role.get("position") instanceof Number n?n.intValue():0));
        }catch(Exception e){lastError="Kanalliste: "+e.getClass().getSimpleName();}
        out.put("channels",channels);out.put("categories",categories);out.put("roles",roleRows);
        List<Object> checks=new ArrayList<>();for(Check c:diagnose(channelId(),mode(),categoryId(),staffRoles(),logChannelId()))checks.add(c.json());out.put("diagnostics",checks);
        out.put("panelMessage",manager()==null?"":manager().store.stateValue("panel.message",""));out.put("panelChannel",manager()==null?"":manager().store.stateValue("panel.channel",""));out.put("guild",bot.ticketGuild());
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private UUID linkedMinecraft(String user){AccountLinks.Link l=plugin.accountLinks==null?null:plugin.accountLinks.store.byDiscord(user);return l==null?null:l.minecraft();}
    private boolean linkedOwner(String user,TicketStore.Ticket t){UUID id=linkedMinecraft(user);return id!=null&&id.equals(t.owner);}
    private void job(String token,Runnable action){if(!bot.ticketJob(action))edit(token,"Tickets","Discord-Warteschlange ist voll. Bitte gleich erneut versuchen.",0xFEE75C);}
    private void defer(String id,String token){bot.ticketRespond(id,token,5,mapOf("flags",64));}
    private void reply(String id,String token,String title,String text,int color,boolean ephemeral){Map<String,Object> data=mapOf("allowed_mentions",mapOf("parse",List.of()),"embeds",List.of(mapOf("title",title,"description",TicketStore.clip(text,3900),"color",color)));if(ephemeral)data.put("flags",64);bot.ticketRespond(id,token,4,data);}
    private void edit(String token,String title,String text,int color){bot.ticketEdit(token,mapOf("allowed_mentions",mapOf("parse",List.of()),"embeds",List.of(mapOf("title",title,"description",TicketStore.clip(text,3900),"color",color)),"components",List.of()));}
    private static int color(String priority){return switch(priority){case "urgent"->0xED4245;case "high"->0xF0883E;case "low"->0x57F287;default->0xFEE75C;};}
    private static long parse(String value){try{return Long.parseLong(value);}catch(NumberFormatException e){throw new IllegalArgumentException("Ungültige Ticket-ID.");}}
    private static String idOf(HttpResponse<String> r){if(r==null||r.statusCode()/100!=2)return "";try{return DiscordBotManager.Json.parse(r.body()) instanceof Map<?,?> m?str(m.get("id")):"";}catch(RuntimeException e){return "";}}
    private static String str(Object v){return v==null?"":String.valueOf(v);}
    private static String display(Map<String,Object> member,Map<String,Object> user){String n=str(member.get("nick"));if(n.isBlank())n=str(user.get("global_name"));if(n.isBlank())n=str(user.get("username"));return RelayText.plain(n,64);}
    private static String sub(Object options){return options instanceof List<?> l&&!l.isEmpty()&&l.get(0) instanceof Map<?,?> m?str(m.get("name")):"";}
    private static String opt(Object options,String key){if(!(options instanceof List<?> list))return "";for(Object raw:list)if(raw instanceof Map<?,?> m){if(key.equals(str(m.get("name")))&&m.get("value")!=null)return str(m.get("value"));String nested=opt(m.get("options"),key);if(!nested.isBlank())return nested;}return "";}
    private static String value(Object components,String key){if(!(components instanceof List<?> list))return "";for(Object raw:list)if(raw instanceof Map<?,?> m){if(key.equals(str(m.get("custom_id"))))return str(m.get("value")).strip();String nested=value(m.get("components"),key);if(!nested.isBlank())return nested;Object single=m.get("component");if(single!=null){nested=value(List.of(single),key);if(!nested.isBlank())return nested;}}return "";}
}
