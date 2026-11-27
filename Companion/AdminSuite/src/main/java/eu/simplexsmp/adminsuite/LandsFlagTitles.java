package eu.simplexsmp.adminsuite;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 10.24.0: German titles for Lands' role, natural and player flags (fallback when Lands sends no display name). */
final class LandsFlagTitles {
    private static final Map<String, String> TITLES = new HashMap<>();

    static {
        String[][] rows = {
            {"BLOCK_BREAK", "Abbauen"}, {"BLOCK_PLACE", "Bauen"}, {"ATTACK_PLAYER", "Spieler angreifen (PvP)"}, {"ATTACK_ANIMAL", "Tiere angreifen"},
            {"ATTACK_MONSTER", "Monster angreifen"}, {"BLOCK_IGNITE", "Feuer legen"}, {"INTERACT_GENERAL", "Allgemein benutzen"},
            {"INTERACT_MECHANISM", "Knöpfe, Hebel & Mechanik"}, {"INTERACT_CONTAINER", "Kisten & Behälter"}, {"INTERACT_DOOR", "Türen"},
            {"INTERACT_TRAPDOOR", "Falltüren"}, {"INTERACT_VILLAGER", "Mit Dorfbewohnern handeln"}, {"FLY", "Fliegen"}, {"ELYTRA", "Elytra"},
            {"SPAWN_TELEPORT", "Zum Land-Spawn teleportieren"}, {"LAND_ENTER", "Land betreten"}, {"VEHICLE_USE", "Fahrzeuge benutzen"},
            {"ITEM_PICKUP", "Items aufheben"}, {"ENDER_PEARL", "Enderperlen"}, {"TRAMPLE_FARMLAND", "Ackerland zertrampeln"}, {"HARVEST", "Ernten"},
            {"PLANT", "Pflanzen"}, {"SHEAR", "Scheren"}, {"WIND_BURST", "Windstoß"}, {"PLAYER_TRUST", "Spieler hinzufügen"},
            {"PLAYER_UNTRUST", "Spieler entfernen"}, {"PLAYER_SETROLE", "Rollen vergeben"}, {"LAND_CLAIM", "Chunks claimen"},
            {"LAND_CLAIM_BORDER", "Am Rand claimen"}, {"SPAWN_SET", "Spawn setzen"}, {"SETTING_EDIT_LAND", "Land-Einstellungen ändern"},
            {"SETTING_EDIT_ROLE", "Rollen bearbeiten"}, {"SETTING_EDIT_TAXES", "Steuern ändern"}, {"SETTING_EDIT_VARIOUS", "Sonstige Einstellungen"},
            {"BALANCE_WITHDRAW", "Geld abheben"}, {"BALANCE_DEPOSIT", "Geld einzahlen"}, {"AREA_ASSIGN", "Gebiete vergeben"}, {"PLAYER_BAN", "Spieler bannen"},
            {"WAR_MANAGE", "Kriege verwalten"}, {"NO_DAMAGE", "Kein Schaden"}, {"NATION_EDIT", "Nation bearbeiten"}, {"LEVEL_UP", "Land aufsteigen lassen"},
            {"ENTITY_GRIEFING", "Mob-Griefing"}, {"TNT_GRIEFING", "TNT-Schäden"}, {"PISTON_GRIEFING", "Kolben über die Grenze"},
            {"MONSTER_SPAWN", "Monster spawnen"}, {"PHANTOM_SPAWN", "Phantome spawnen"}, {"ANIMAL_SPAWN", "Tiere spawnen"},
            {"WATERFLOW_ALLOW", "Wasser fließt"}, {"LAVAFLOW_ALLOW", "Lava fließt"}, {"TITLE_HIDE", "Titel beim Betreten ausblenden"},
            {"REQUEST_ACCEPT", "Anfragen annehmen"}, {"FIRE_SPREAD", "Feuer breitet sich aus"}, {"LEAF_DECAY", "Laub zerfällt"},
            {"PLANT_GROWTH", "Pflanzen wachsen"}, {"SNOW_MELT", "Schnee schmilzt"}, {"WITHER_ATTACK_ANIMAL", "Wither greift Tiere an"},
            {"BLOCK_SPREADING", "Blöcke breiten sich aus"}, {"COPPER_GOLEM", "Kupfergolem"}, {"EXPIRATION_SHIELD", "Ablauf-Schutz"},
            {"PEACEFUL", "Friedlicher Modus"}, {"ENTER_MESSAGES", "Betreten-Nachrichten"}, {"RECEIVE_INVITES", "Einladungen empfangen"},
            {"SHOW_INBOX", "Posteingang anzeigen"}
        };
        for (String[] row : rows) TITLES.put(row[0], row[1]);
    }

    private LandsFlagTitles() { }

    static String title(String name) {
        if (name == null || name.isBlank()) return "";
        String key = name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        String known = TITLES.get(key);
        if (known != null) return known;
        String words = key.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
