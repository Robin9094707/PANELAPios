package eu.simplexsmp.adminsuite.api;
import java.util.UUID;
/** Available through Bukkit ServicesManager and SimplexAdminSuite#getPointsApi().
 * Mutations must run on the server main thread. Unknown profiles are rejected.
 * False withdraw/transfer means no balance changed. Amounts must be strictly positive.
 */
public interface SimplexPointsApi {
    long balance(UUID player);
    boolean withdraw(UUID player,long amount,String reason);
    void deposit(UUID player,long amount,String reason);
    void setBalance(UUID player,long amount,String reason);
    boolean transfer(UUID from,UUID to,long amount,String reason);
}
