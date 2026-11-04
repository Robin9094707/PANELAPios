package org.geysermc.geyser.api.extension;
import java.nio.file.Path;
public interface Extension {
    default Path dataFolder(){ return Path.of("."); }
    default ExtensionLogger logger(){ return new ExtensionLogger(){ public void info(String text){} public void warning(String text){} public void error(String text){} }; }
}
