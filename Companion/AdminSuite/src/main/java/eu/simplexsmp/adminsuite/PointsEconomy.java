package eu.simplexsmp.adminsuite;
import eu.simplexsmp.adminsuite.api.SimplexPointsApi;
import java.util.UUID;
import org.bukkit.Bukkit;

final class PointsEconomy implements SimplexPointsApi {
    private final SimplexAdminSuite plugin;
    final EconomyStore store;
    PointsEconomy(SimplexAdminSuite p)throws Exception {
        plugin=p;store=new EconomyStore(p.getDataFolder().toPath().resolve("community-economy.sqlite"));
        for(var profile:p.expansionProfiles())profile.pointsEconomy=this;
        for(var profile:p.expansionProfiles())profile.mirrorPoints(store.migrate(profile.uniqueId(),profile.simplexPoints()));
    }
    void attach(SimplexAdminSuite.PlayerProfile p){
        if(p.pointsEconomy==this)return;
        try{p.mirrorPoints(store.migrate(p.uniqueId(),p.simplexPoints()));p.pointsEconomy=this;}catch(Exception e){throw new IllegalStateException("Punktekonto nicht verfügbar",e);}
    }
    private void main(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Simplex Points mutations require the server main thread");}
    private void known(UUID id){var p=plugin.expansionProfile(id);if(p==null)throw new IllegalArgumentException("Unknown player profile");attach(p);}
    private String reason(String r){if(r==null||r.isBlank()||r.length()>240)throw new IllegalArgumentException("Reason required (1–240 characters)");return r;}
    private void amount(long a){if(a<=0||a>EconomyStore.MAX)throw new IllegalArgumentException("Amount must be positive and within balance limit");}
    public long balance(UUID id){var p=plugin.expansionProfile(id);if(p==null)return 0;try{return store.balance(id);}catch(Exception e){throw new IllegalStateException("Points read failed",e);}}
    void mirror(UUID id){
        var p=plugin.expansionProfile(id);if(p==null)return;
        try{p.mirrorPoints(store.balance(id));}catch(java.sql.SQLException e){throw new IllegalStateException("Points projection failed",e);}
        // The ledger has already committed. A display/legacy YAML error must never turn a successful
        // debit into a reported failure and encourage a caller to retry the financial mutation.
        try{plugin.saveExpansionProfilesAsync(java.util.List.of(p));plugin.discordPointsChanged();plugin.refreshPlayerBoard(Bukkit.getPlayer(id));}
        catch(RuntimeException e){plugin.getLogger().warning("Punkte gebucht; Anzeige/Profil wird später aktualisiert: "+id);}
    }
    public boolean withdraw(UUID id,long a,String r){main();amount(a);known(id);reason(r);if(balance(id)<a)return false;try{store.change(id,-a,r,false);mirror(id);return true;}catch(java.sql.SQLException e){throw new IllegalStateException("Points debit failed",e);}}
    public void deposit(UUID id,long a,String r){main();amount(a);known(id);try{store.change(id,a,reason(r),false);mirror(id);}catch(java.sql.SQLException e){throw new IllegalStateException("Points credit failed",e);}}
    public void setBalance(UUID id,long a,String r){main();known(id);if(a<0||a>EconomyStore.MAX)throw new IllegalArgumentException("Invalid balance");try{store.change(id,a,reason(r),true);mirror(id);}catch(java.sql.SQLException e){throw new IllegalStateException("Points update failed",e);}}
    public boolean transfer(UUID a,UUID b,long n,String r){main();amount(n);known(a);known(b);try{boolean ok=store.transfer(a,b,n,reason(r));if(ok){mirror(a);mirror(b);}return ok;}catch(java.sql.SQLException e){throw new IllegalStateException("Points transfer failed",e);}}
}
