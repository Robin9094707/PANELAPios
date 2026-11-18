package eu.simplexsmp.adminsuite;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

import static eu.simplexsmp.adminsuite.SimplexAdminSuite.appMap;

/** Shared App/Web controls over the existing community records. Called on the server thread. */
final class CommunityTools {
    static final String FEATURE = "community-tools-v1";
    static final String READ = "data.community-tools";
    static final String INVITE = "community.invite";
    static final String DAYS = "community.days";
    static final String POINTS = "community.points";
    static final String DELIVERY = "community.delivery";
    static final String SHOP = "community.shop";
    static final Set<String> ACTIONS = Set.of(INVITE, DAYS, POINTS, DELIVERY, SHOP);

    private final SimplexAdminSuite plugin;

    CommunityTools(SimplexAdminSuite plugin) {
        this.plugin = plugin;
    }

    Object data(String player) throws SQLException {
        if (!player.isBlank()) {
            UUID owner = player(player);
            var notes = store().recentNotes(owner, 50).stream().map(note -> appMap(
                "id", note.id(), "titleDE", note.titleDe(), "titleEN", note.titleEn(),
                "actor", note.actor(), "priority", note.priority(), "created", note.created(),
                "sent", note.sent(), "ack", note.ack()
            )).toList();
            return appMap("player", owner.toString(), "name", plugin.discordAdminPlayerName(owner),
                "balance", Long.toString(plugin.getPointsApi().balance(owner)), "deliveries", notes);
        }
        var scoreboard = appMap(
            "main", appMap("name", plugin.getConfig().getString("scoreboard.main-world", "world"),
                "days", Long.toString(plugin.scoreboardMainDays()), "enabled", true),
            "second", appMap("name", plugin.getConfig().getString("scoreboard.second-world.name", "survival2"),
                "days", Long.toString(plugin.scoreboardSecondDays()),
                "enabled", plugin.getConfig().getBoolean("scoreboard.second-world.enabled", true))
        );
        var offers = plugin.pointsEconomy == null ? java.util.List.of() : store().offers().stream().map(offer -> appMap(
            "id", offer.id(), "titleDE", offer.de(), "titleEN", offer.en(), "price", Long.toString(offer.price()),
            "stock", Long.toString(offer.stock()), "revision", Long.toString(offer.revision()),
            "enabled", offer.enabled(), "itemPayment", offer.itemPayment(), "expires", offer.expires()
        )).toList();
        return appMap("invite", plugin.discordInvite.code(), "scoreboard", scoreboard,
            "economy", plugin.pointsEconomy != null, "offers", offers);
    }

    String preview(DiscordAdminController.Request request) throws Exception {
        return change(request, null);
    }

    Object apply(DiscordAdminController.Request request, String actor) throws Exception {
        return appMap("ok", true, "title", "Gespeichert", "description", change(request, actor));
    }

    private String change(DiscordAdminController.Request request, String actor) throws Exception {
        boolean execute = actor != null;
        return switch (request.path) {
            case INVITE -> {
                expect(plugin.discordInvite.code(), request.text("expected"));
                String code = DiscordInvite.parseCode(request.text("invite"));
                if (execute) plugin.discordInvite.update(actor, code);
                yield "Öffentlicher Discord-Link: https://discord.gg/" + code;
            }
            case DAYS -> {
                if (!Set.of("main", "second").contains(request.text("world"))) throw new IllegalArgumentException("Unbekannte Weltgruppe.");
                boolean second = request.text("world").equals("second");
                if (second && !plugin.getConfig().getBoolean("scoreboard.second-world.enabled", true)) throw changed();
                var scoreboard = plugin.scoreboardManager();
                if (scoreboard == null) throw new AppApiManager.ApiError(503, "Scoreboard nicht verfügbar.");
                long before = second ? scoreboard.secondDays() : scoreboard.mainDays();
                expect(Long.toString(before), request.text("expected"));
                long after = whole(request.text("days"), 0, Long.MAX_VALUE / 24_000);
                if (execute) scoreboard.setDays(second, after, actor);
                String name = plugin.getConfig().getString(second ? "scoreboard.second-world.name" : "scoreboard.main-world",
                    second ? "survival2" : "world");
                yield name + ": " + before + " → " + after + " Spieltage";
            }
            case POINTS -> {
                UUID owner = target(request.text("player"));
                store();
                var points = plugin.getPointsApi();
                long before = points.balance(owner);
                expect(Long.toString(before), request.text("expected"));
                long amount = whole(request.text("amount"), 0, EconomyStore.MAX);
                String operation = request.text("operation");
                long after = pointsAfter(before, amount, operation);
                String reason = requiredText(request.text("reason"), 240);
                if (execute) {
                    switch (operation) {
                        case "deposit" -> points.deposit(owner, amount, reason);
                        case "withdraw" -> {
                            if (!points.withdraw(owner, amount, reason)) throw changed();
                        }
                        case "set" -> points.setBalance(owner, amount, reason);
                        default -> throw new IllegalArgumentException("Unbekannte Punkteaktion.");
                    }
                    plugin.suiteAudit(actor, "Simplex Points", owner + " · " + before + " → " + after + " SP");
                }
                yield plugin.discordAdminPlayerName(owner) + ": " + before + " → " + after + " SP\nGrund: " + reason;
            }
            case DELIVERY -> {
                UUID owner = target(request.text("player"));
                if (plugin.deliveries == null) throw new AppApiManager.ApiError(503, "Quick Delivery nicht verfügbar.");
                String titleDE = requiredText(request.text("titleDE"), 100);
                String titleEN = requiredText(request.text("titleEN"), 100);
                String messageDE = requiredText(request.text("messageDE"), 2000);
                String messageEN = requiredText(request.text("messageEN"), 2000);
                int priority = (int) whole(request.text("priority"), 0, 2);
                boolean menu = request.text("menu").equals("true");
                if (execute) {
                    String id = plugin.deliveries.create(actor, owner, titleDE, titleEN, messageDE, messageEN, priority, menu);
                    yield "Nachricht für " + plugin.discordAdminPlayerName(owner) + " vorgemerkt (" + id + ").";
                }
                yield "Empfänger: " + plugin.discordAdminPlayerName(owner) + "\nPriorität: " + priority
                    + "\nAnzeige: " + (menu ? "Menü" : "Chat") + "\n\nDE · " + titleDE + "\n" + messageDE
                    + "\n\nEN · " + titleEN + "\n" + messageEN;
            }
            case SHOP -> {
                var store = store();
                var offer = store.offer(request.text("offer"));
                if (offer == null) throw new AppApiManager.ApiError(404, "Shop-Angebot nicht gefunden.");
                expect(Long.toString(offer.revision()), request.text("expected"));
                expect(Long.toString(offer.stock()), request.text("expectedStock"));
                String titleDE = requiredText(request.text("titleDE"), 100);
                String titleEN = requiredText(request.text("titleEN"), 100);
                long price = whole(request.text("price"), 1, offer.itemPayment() ? 2304 : EconomyStore.MAX);
                long stock = whole(request.text("stock"), -1, Long.MAX_VALUE);
                boolean enabled = request.text("enabled").equals("true");
                if (execute) {
                    if (!store.updateOfferDetails(offer, titleDE, titleEN, price, stock, enabled)) throw changed();
                    plugin.suiteAudit(actor, "Shop-Angebot bearbeitet", offer.id());
                }
                yield titleDE + " / " + titleEN + "\nPreis: " + offer.price() + " → " + price
                    + (offer.itemPayment() ? " Items" : " SP") + "\nBestand: " + stockLabel(offer.stock())
                    + " → " + stockLabel(stock) + "\n" + (enabled ? "Angebot aktiv" : "Angebot deaktiviert");
            }
            default -> throw new IllegalArgumentException("Unbekannte Community-Aktion.");
        };
    }

    private EconomyStore store() {
        if (plugin.pointsEconomy == null) throw new AppApiManager.ApiError(503, "Punkte und Shop nicht verfügbar.");
        return plugin.pointsEconomy.store;
    }

    private UUID player(String input) {
        // An exact current name or a UUID is required. Prefix matches could change the wrong account.
        String selector = input.trim();
        UUID id;
        try {
            id = UUID.fromString(selector);
        } catch (IllegalArgumentException ignored) {
            id = selector.isEmpty() ? null : plugin.discordAdminResolvePlayer(selector);
            if (id != null && !plugin.discordAdminPlayerName(id).equalsIgnoreCase(selector)) id = null;
        }
        if (id == null || plugin.expansionProfile(id) == null)
            throw new AppApiManager.ApiError(404, "Spielerprofil nicht gefunden. Exakten Namen oder UUID verwenden.");
        return id;
    }

    private UUID target(String input) {
        UUID id;
        try { id = UUID.fromString(input); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Die Aktion benötigt eine feste Spieler-UUID."); }
        return player(id.toString());
    }

    static long pointsAfter(long before, long amount, String operation) {
        return switch (operation) {
            case "deposit" -> {
                if (amount == 0 || before > EconomyStore.MAX - amount)
                    throw new IllegalArgumentException("Gutschrift muss positiv sein und ins Punktekonto passen.");
                yield before + amount;
            }
            case "withdraw" -> {
                if (amount == 0 || amount > before) throw new IllegalArgumentException("Zu wenig Punkte oder ungültiger Betrag.");
                yield before - amount;
            }
            case "set" -> amount;
            default -> throw new IllegalArgumentException("Unbekannte Punkteaktion.");
        };
    }

    private static long whole(String input, long minimum, long maximum) {
        try {
            long value = Long.parseLong(input.trim());
            if (value >= minimum && value <= maximum) return value;
        } catch (NumberFormatException ignored) {
            // The same message covers malformed or out-of-range values.
        }
        throw new IllegalArgumentException("Ganze Zahl von " + minimum + " bis " + maximum + " erforderlich.");
    }

    private static String requiredText(String input, int maximum) {
        String value = input.replace('§', ' ').strip();
        if (value.isBlank() || value.length() > maximum)
            throw new IllegalArgumentException("Text mit 1–" + maximum + " Zeichen erforderlich.");
        return value;
    }

    static void expect(String actual, String loaded) {
        if (!actual.equals(loaded)) throw changed();
    }

    private static AppApiManager.ApiError changed() {
        return new AppApiManager.ApiError(409, "Der Serverstand hat sich geändert. Bitte neu laden und erneut prüfen.");
    }

    private static String stockLabel(long stock) {
        return stock < 0 ? "unbegrenzt" : Long.toString(stock);
    }
}
