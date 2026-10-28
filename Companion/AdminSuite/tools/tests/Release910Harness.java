package eu.simplexsmp.adminsuite;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** UUID lookup and additive VPN defaults against real production classes. */
public final class Release910Harness {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        YamlConfiguration config=new YamlConfiguration();VpnSettings policy=new VpnSettings(config);
        check(policy.checksPlatform(false)&&policy.checksPlatform(true),"old configurations check both platforms");
        config.set("vpn-protection.target-platform","java");policy=new VpnSettings(config);
        check(policy.checksPlatform(false)&&!policy.checksPlatform(true),"Java-only skips Bedrock");
        config.set("vpn-protection.target-platform","bedrock");policy=new VpnSettings(config);
        check(!policy.checksPlatform(false)&&policy.checksPlatform(true),"Bedrock-only skips Java");
        config.set("vpn-protection.target-platform","bad-value");policy=new VpnSettings(config);
        check(policy.checksPlatform(false)&&policy.checksPlatform(true),"invalid target fails back to both");
        Path root=Files.createTempDirectory("suite-910-");Path outside=Files.createTempDirectory("suite-outside-");
        UUID uuid=UUID.fromString("00000000-0000-0000-0009-00000a1388a7");
        try {
            Path modern=Files.createDirectories(root.resolve("world/players/data")).resolve(uuid+".dat");Files.write(modern,new byte[]{10,0,0,0});
            Path legacy=Files.createDirectories(root.resolve("world/playerdata")).resolve(uuid+".dat");Files.write(legacy,new byte[]{10,0,0,0});
            Path dimension=Files.createDirectories(root.resolve("world/dimensions/minecraft/overworld"));
            var found=PlayerNbtLocator.find(root,List.of(dimension.toFile()),uuid);
            check(found.size()==2&&found.get(0).file().equals(modern.toRealPath()),"modern storage found from loaded dimension and preferred");
            check(found.get(0).id().equals("/world/players/data/"+uuid+".dat"),"panel path is exact UUID, no display-name substitution");
            Files.delete(modern);Files.delete(legacy);Files.write(modern.resolveSibling(uuid+".dat_old"),new byte[]{10,0,0,0});
            check(PlayerNbtLocator.find(root,List.of(dimension.toFile()),uuid).isEmpty(),"backup is never silently edited");
            Path custom=Files.createDirectories(root.resolve("survival/players/data")).resolve(uuid+".dat");Files.write(custom,new byte[]{10,0,0,0});
            Files.writeString(root.resolve("server.properties"),"level-name=survival\n");
            check(PlayerNbtLocator.find(root,List.of(),uuid).get(0).id().startsWith("/survival/"),"configured world found without scanning");
            Files.delete(custom);Files.write(outside.resolve(uuid+".dat"),new byte[]{10,0,0,0});
            Files.createSymbolicLink(custom,outside.resolve(uuid+".dat"));
            check(PlayerNbtLocator.find(root,List.of(),uuid).isEmpty(),"symlinked file rejected");
            Files.delete(custom);Files.delete(custom.getParent());Files.createSymbolicLink(custom.getParent(),outside);
            check(PlayerNbtLocator.find(root,List.of(),uuid).isEmpty(),"symlinked directory escaping server rejected");
            check(!VpnIpCheckService.isIpLiteral("old-ip-hash")&&VpnIpCheckService.isIpLiteral("192.168.1.5"),"raw IP validation never reinterprets legacy hashes");
            System.out.println("Release910Harness: "+checks+" checks passed");
        }finally{for(Path base:List.of(root,outside))try(var paths=Files.walk(base)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
