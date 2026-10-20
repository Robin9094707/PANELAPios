package eu.simplexsmp.adminsuite;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Single durable transaction boundary for balances, stock, limits and receipts. No Bukkit calls. */
final class EconomyStore implements AutoCloseable {
    static final long MAX=Long.MAX_VALUE/4;
    record Offer(String id,String de,String en,byte[] item,long price,long stock,long limit,long expires,String icon,long revision,boolean enabled,byte[] currency) {
        Offer(String id,String de,String en,byte[] item,long price,long stock,long limit,long expires,String icon,long revision,boolean enabled){this(id,de,en,item,price,stock,limit,expires,icon,revision,enabled,new byte[0]);}
        boolean itemPayment(){return currency!=null&&currency.length>0;}
    }
    record Receipt(String id,UUID owner,String offer,byte[] item,long price,String state,long created,byte[] currency) {
        Receipt(String id,UUID owner,String offer,byte[] item,long price,String state,long created){this(id,owner,offer,item,price,state,created,new byte[0]);}
        boolean itemPayment(){return currency!=null&&currency.length>0;}
    }
    record Note(String id,UUID owner,String actor,String titleDe,String titleEn,String de,String en,int priority,boolean menu,long created,long sent,long ack,String language) {
        Note(String id,UUID owner,String actor,String titleDe,String titleEn,String de,String en,int priority,boolean menu,long created,long sent,long ack){this(id,owner,actor,titleDe,titleEn,de,en,priority,menu,created,sent,ack,"auto");}
    }
    private final Connection db;
    EconomyStore(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Class.forName("org.sqlite.JDBC");db=DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath());
        try(Statement s=db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS accounts(uuid TEXT PRIMARY KEY,balance INTEGER NOT NULL CHECK(balance>=0))");
            s.execute("CREATE TABLE IF NOT EXISTS offers(id TEXT PRIMARY KEY,de TEXT NOT NULL,en TEXT NOT NULL,item BLOB NOT NULL,price INTEGER NOT NULL CHECK(price>0),stock INTEGER NOT NULL CHECK(stock>=-1),lim INTEGER NOT NULL CHECK(lim>=0),expires INTEGER NOT NULL,icon TEXT NOT NULL,revision INTEGER NOT NULL,enabled INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS receipts(id TEXT PRIMARY KEY,uuid TEXT NOT NULL,offer TEXT NOT NULL,item BLOB NOT NULL,price INTEGER NOT NULL,state TEXT NOT NULL,created INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS receipt_owner ON receipts(uuid,state)");
            s.execute("CREATE TABLE IF NOT EXISTS ledger(id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL,delta INTEGER NOT NULL,reason TEXT NOT NULL,created INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS notes(id TEXT PRIMARY KEY,uuid TEXT NOT NULL,actor TEXT NOT NULL,title_de TEXT NOT NULL,title_en TEXT NOT NULL,de TEXT NOT NULL,en TEXT NOT NULL,priority INTEGER NOT NULL,menu INTEGER NOT NULL,created INTEGER NOT NULL,sent INTEGER NOT NULL DEFAULT 0,ack INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE INDEX IF NOT EXISTS note_owner ON notes(uuid,ack,priority)");
            addColumn("offers","currency","BLOB NOT NULL DEFAULT X''");
            addColumn("receipts","currency","BLOB NOT NULL DEFAULT X''");
            addColumn("notes","language","TEXT NOT NULL DEFAULT 'auto'");
            s.execute("PRAGMA user_version=2");
        }
    }
    private void addColumn(String table,String column,String definition)throws SQLException {
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("PRAGMA table_info("+table+")")){while(r.next())if(column.equals(r.getString("name")))return;}
        try(Statement s=db.createStatement()){s.execute("ALTER TABLE "+table+" ADD COLUMN "+column+" "+definition);}
    }
    private void exec(String sql,Object...values) throws SQLException {try(PreparedStatement s=db.prepareStatement(sql)){bind(s,values);s.executeUpdate();}}
    private static void bind(PreparedStatement s,Object...v)throws SQLException {for(int i=0;i<v.length;i++)s.setObject(i+1,v[i] instanceof UUID?v[i].toString():v[i]);}
    synchronized long migrate(UUID id,long legacy) throws SQLException {
        exec("INSERT OR IGNORE INTO accounts(uuid,balance) VALUES(?,?)",id,Math.min(MAX,Math.max(0,legacy)));return balance(id);
    }
    synchronized long balance(UUID id) throws SQLException {try(PreparedStatement s=db.prepareStatement("SELECT balance FROM accounts WHERE uuid=?")){bind(s,id);try(ResultSet r=s.executeQuery()){return r.next()?r.getLong(1):0;}}}
    synchronized long change(UUID id,long amount,String reason,boolean absolute) throws SQLException {
        db.setAutoCommit(false);try {
            exec("INSERT OR IGNORE INTO accounts VALUES(?,0)",id);
            long before=balance(id);long after=absolute?amount:Math.addExact(before,amount);
            if(after<0||after>MAX)throw new IllegalArgumentException("Insufficient funds or balance limit");
            exec("UPDATE accounts SET balance=? WHERE uuid=?",after,id);
            exec("INSERT INTO ledger(uuid,delta,reason,created) VALUES(?,?,?,?)",id,after-before,reason,System.currentTimeMillis());db.commit();return after;
        }catch(SQLException|RuntimeException e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
    }
    synchronized boolean transfer(UUID from,UUID to,long amount,String reason)throws SQLException {
        if(amount<=0||amount>MAX||from.equals(to))return false;
        db.setAutoCommit(false);try {
            long a=balance(from),b=balance(to);if(a<amount||b>MAX-amount){db.rollback();return false;}
            exec("INSERT OR IGNORE INTO accounts VALUES(?,0)",to);
            exec("UPDATE accounts SET balance=? WHERE uuid=?",a-amount,from);exec("UPDATE accounts SET balance=? WHERE uuid=?",b+amount,to);
            long now=System.currentTimeMillis();exec("INSERT INTO ledger(uuid,delta,reason,created) VALUES(?,?,?,?)",from,-amount,reason,now);exec("INSERT INTO ledger(uuid,delta,reason,created) VALUES(?,?,?,?)",to,amount,reason,now);
            db.commit();return true;
        }catch(SQLException|RuntimeException e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
    }
    private static Offer offer(ResultSet r)throws SQLException {return new Offer(r.getString("id"),r.getString("de"),r.getString("en"),r.getBytes("item"),r.getLong("price"),r.getLong("stock"),r.getLong("lim"),r.getLong("expires"),r.getString("icon"),r.getLong("revision"),r.getBoolean("enabled"),r.getBytes("currency"));}
    synchronized List<Offer> offers()throws SQLException {List<Offer> out=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT * FROM offers ORDER BY id")){while(r.next())out.add(offer(r));}return out;}
    synchronized Offer offer(String id)throws SQLException {try(PreparedStatement s=db.prepareStatement("SELECT * FROM offers WHERE id=?")){bind(s,id);try(ResultSet r=s.executeQuery()){return r.next()?offer(r):null;}}}
    synchronized void put(Offer o)throws SQLException {
        if(o.price<=0||o.price>MAX||o.stock< -1||o.limit<0||o.expires<0||o.item.length==0||o.itemPayment()&&o.price>2304)throw new IllegalArgumentException("Invalid offer");
        exec("INSERT INTO offers(id,de,en,item,price,stock,lim,expires,icon,revision,enabled,currency) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET de=excluded.de,en=excluded.en,item=excluded.item,price=excluded.price,stock=excluded.stock,lim=excluded.lim,expires=excluded.expires,icon=excluded.icon,revision=offers.revision+1,enabled=excluded.enabled,currency=excluded.currency",o.id,o.de,o.en,o.item,o.price,o.stock,o.limit,o.expires,o.icon,o.revision,o.enabled?1:0,o.currency==null?new byte[0]:o.currency);
    }
    synchronized void remove(String id)throws SQLException {exec("UPDATE offers SET enabled=0,revision=revision+1 WHERE id=?",id);}
    synchronized long bought(UUID who,String id)throws SQLException {try(PreparedStatement s=db.prepareStatement("SELECT COUNT(*) FROM receipts WHERE uuid=? AND offer=? AND state NOT IN ('REFUNDED','REFUND_PENDING','REFUNDING')")){bind(s,who,id);try(ResultSet r=s.executeQuery()){r.next();return r.getLong(1);}}}
    synchronized Receipt purchase(UUID who,String id,long expectedRevision,String request)throws SQLException {
        return purchase(who,id,expectedRevision,request,false);
    }
    synchronized Receipt purchaseItem(UUID who,String id,long expectedRevision,String request)throws SQLException {return purchase(who,id,expectedRevision,request,true);}
    private Receipt purchase(UUID who,String id,long expectedRevision,String request,boolean itemPayment)throws SQLException {
        db.setAutoCommit(false);try {
            Receipt old=receipt(request);if(old!=null){if(!old.owner.equals(who)||!old.offer.equals(id)||old.itemPayment()!=itemPayment)throw new IllegalArgumentException("Invalid purchase token");db.rollback();return old;}
            Offer o=offer(id);long now=System.currentTimeMillis();
            if(o==null||o.itemPayment()!=itemPayment||!o.enabled||o.revision!=expectedRevision||o.stock==0||o.expires>0&&now>=o.expires)throw new IllegalArgumentException("Offer changed, expired or sold out");
            if(o.limit>0&&bought(who,id)>=o.limit)throw new IllegalArgumentException("Purchase limit reached");
            long before=balance(who);if(!itemPayment&&before<o.price)throw new IllegalArgumentException("Not enough Simplex Points");
            if(!itemPayment)exec("UPDATE accounts SET balance=? WHERE uuid=?",before-o.price,who);
            if(o.stock>0)exec("UPDATE offers SET stock=stock-1 WHERE id=?",id);
            String state=itemPayment?"DELIVERING":"PENDING";
            exec("INSERT INTO receipts(id,uuid,offer,item,price,state,created,currency) VALUES(?,?,?,?,?,?,?,?)",request,who,id,o.item,o.price,state,now,o.currency);
            if(!itemPayment)exec("INSERT INTO ledger(uuid,delta,reason,created) VALUES(?,?,?,?)",who,-o.price,"shop:"+request,now);
            db.commit();return new Receipt(request,who,id,o.item,o.price,state,now,o.currency);
        }catch(SQLException|RuntimeException e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
    }
    private static Receipt receipt(ResultSet r)throws SQLException {return new Receipt(r.getString("id"),UUID.fromString(r.getString("uuid")),r.getString("offer"),r.getBytes("item"),r.getLong("price"),r.getString("state"),r.getLong("created"),r.getBytes("currency"));}
    synchronized Receipt receipt(String id)throws SQLException {try(PreparedStatement s=db.prepareStatement("SELECT * FROM receipts WHERE id=?")){bind(s,id);try(ResultSet r=s.executeQuery()){return r.next()?receipt(r):null;}}}
    synchronized List<Receipt> pending(UUID id)throws SQLException {List<Receipt> out=new ArrayList<>();try(PreparedStatement s=db.prepareStatement("SELECT * FROM receipts WHERE uuid=? AND state IN ('PENDING','REFUND_PENDING') ORDER BY created")){bind(s,id);try(ResultSet r=s.executeQuery()){while(r.next())out.add(receipt(r));}}return out;}
    synchronized List<Receipt> held()throws SQLException {List<Receipt> out=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT * FROM receipts WHERE state IN ('DELIVERING','REFUNDING') ORDER BY created")){while(r.next())out.add(receipt(r));}return out;}
    synchronized boolean transition(String id,String from,String to)throws SQLException {try(PreparedStatement s=db.prepareStatement("UPDATE receipts SET state=? WHERE id=? AND state=?")){bind(s,to,id,from);return s.executeUpdate()==1;}}
    synchronized boolean refund(String id)throws SQLException {
        db.setAutoCommit(false);try {
            Receipt r=receipt(id);if(r==null||!Set.of("PENDING","DELIVERING").contains(r.state)){db.rollback();return false;}
            long before=balance(r.owner);if(!r.itemPayment()&&before>MAX-r.price)throw new IllegalArgumentException("Balance limit reached");
            if(!r.itemPayment())exec("UPDATE accounts SET balance=? WHERE uuid=?",before+r.price,r.owner);
            exec("UPDATE offers SET stock=stock+1 WHERE id=? AND stock>=0",r.offer);
            exec("UPDATE receipts SET state=? WHERE id=?",r.itemPayment()?"REFUND_PENDING":"REFUNDED",id);
            if(!r.itemPayment())exec("INSERT INTO ledger(uuid,delta,reason,created) VALUES(?,?,?,?)",r.owner,r.price,"refund:"+id,System.currentTimeMillis());db.commit();return true;
        }catch(SQLException|RuntimeException e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
    }
    /** Cancel a confirmed unpaid item exchange without creating items or touching points. */
    synchronized boolean cancelUnpaid(String id)throws SQLException {
        db.setAutoCommit(false);try{Receipt r=receipt(id);if(r==null||!r.itemPayment()||!r.state.equals("DELIVERING")){db.rollback();return false;}
            exec("UPDATE receipts SET state='REFUNDED' WHERE id=?",id);exec("UPDATE offers SET stock=stock+1 WHERE id=? AND stock>=0",r.offer);db.commit();return true;
        }catch(SQLException|RuntimeException e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
    }
    synchronized void note(Note n)throws SQLException {
        exec("INSERT INTO notes(id,uuid,actor,title_de,title_en,de,en,priority,menu,created,sent,ack,language) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",n.id,n.owner,n.actor,n.titleDe,n.titleEn,n.de,n.en,n.priority,n.menu?1:0,n.created,n.sent,n.ack,n.language);
    }
    private static Note note(ResultSet r)throws SQLException {return new Note(r.getString("id"),UUID.fromString(r.getString("uuid")),r.getString("actor"),r.getString("title_de"),r.getString("title_en"),r.getString("de"),r.getString("en"),r.getInt("priority"),r.getBoolean("menu"),r.getLong("created"),r.getLong("sent"),r.getLong("ack"),r.getString("language"));}
    synchronized List<Note> notes(UUID id,boolean unread)throws SQLException {List<Note> out=new ArrayList<>();try(PreparedStatement s=db.prepareStatement("SELECT * FROM notes WHERE uuid=?"+(unread?" AND ack=0":"")+" ORDER BY priority DESC,created")){bind(s,id);try(ResultSet r=s.executeQuery()){while(r.next())out.add(note(r));}}return out;}
    synchronized void sent(String id,UUID owner)throws SQLException {exec("UPDATE notes SET sent=CASE WHEN sent=0 THEN ? ELSE sent END WHERE id=? AND uuid=?",System.currentTimeMillis(),id,owner);}
    synchronized boolean ack(String id,UUID owner)throws SQLException {try(PreparedStatement s=db.prepareStatement("UPDATE notes SET ack=? WHERE id=? AND uuid=? AND sent>0 AND ack=0")){bind(s,System.currentTimeMillis(),id,owner);return s.executeUpdate()==1;}}
    synchronized boolean deleteNote(String id)throws SQLException {try(PreparedStatement s=db.prepareStatement("DELETE FROM notes WHERE id=? AND ack=0")){bind(s,id);return s.executeUpdate()==1;}}
    @Override public synchronized void close()throws SQLException {db.close();}
}
