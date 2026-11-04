import eu.simplexsmp.sentinelbridge.SimplexSentinelBridge;
import org.geysermc.geyser.api.connection.GeyserConnection;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Loads the release against the genuine Geyser runtime API and real BedrockClientData bean. */
public final class RealApiBridgeHarness {
 public interface Extra { Object getClientData(); int getClientRenderDistance(); }
 public static void main(String[] args)throws Exception {
  Class<?> type=Class.forName("org.geysermc.geyser.session.auth.BedrockClientData");Object data=type.getConstructor().newInstance();
  String json="{\"MemoryTier\":4,\"MaxViewDistance\":76,\"GraphicsMode\":1,\"ProfileHash\":\"fixture-profile\"}";
  type.getMethod("setOriginalString",String.class).invoke(data,"header."+Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8))+".signature");
  Field id=type.getDeclaredField("deviceId");id.setAccessible(true);id.set(data,"a4f365bb1e04459bbe4cb3cbf9d546e0");
  GeyserConnection connection=(GeyserConnection)Proxy.newProxyInstance(RealApiBridgeHarness.class.getClassLoader(),new Class[]{GeyserConnection.class,Extra.class},(proxy,method,values)->{
   return switch(method.getName()) {
    case "getClientData" -> data; case "xuid" -> "2533274700000000"; case "name","bedrockUsername","javaUsername" -> "Fixture";
    case "javaUuid" -> UUID.fromString("11111111-2222-3333-4444-555555555555");case "version" -> "26.51";
    case "protocolVersion" -> 2193;case "joinAddress" -> "simplexsmp.eu";case "joinPort" -> 19132;case "ping" -> 37;
    case "getClientRenderDistance" -> 24;
    case "inputMode" -> method.getReturnType().getEnumConstants()[1];
    case "toString" -> "fixture";case "hashCode" -> 1;case "equals" -> proxy==values[0];
    default -> method.getReturnType().equals(boolean.class)?false:method.getReturnType().equals(int.class)?0:null;
   };
  });
  var bridge=new SimplexSentinelBridge();Field config=SimplexSentinelBridge.class.getDeclaredField("config");config.setAccessible(true);config.set(bridge,new Properties());
  Field capabilities=SimplexSentinelBridge.class.getDeclaredField("capabilityReported");capabilities.setAccessible(true);capabilities.set(bridge,true);
  Method collect=SimplexSentinelBridge.class.getDeclaredMethod("collect",GeyserConnection.class,boolean.class);collect.setAccessible(true);
  var result=(Properties)collect.invoke(bridge,connection,true);
  for(var pair:Map.of("bedrockProtocolVersion","2193","joinAddress","simplexsmp.eu","joinPort","19132","memoryTier","4","maxViewDistance","76","requestedViewDistance","24","collectionSource","geyser-clientdata-jwt").entrySet()) if(!pair.getValue().equals(result.getProperty(pair.getKey()))) throw new AssertionError(pair.getKey());
  if(result.containsKey("originalString")||result.containsKey("clientJwt")||!result.containsKey("geyserVersion"))throw new AssertionError("version/token handling");
  System.out.println("PASS: release collector runs with real Geyser 2.12.1 API and ClientData.");
 }
}
