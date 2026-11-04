package org.geysermc.geyser.api.connection;
public interface GeyserConnection {
    String xuid(); String name();
    default String javaUsername() { return null; }
    default String bedrockUsername() { return null; }
    default java.util.UUID javaUuid() { return null; }
    default boolean isLinked() { return false; }
}
