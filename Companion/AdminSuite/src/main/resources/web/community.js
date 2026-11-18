/* Simplex Panel Web – shared community controls. */
(function () {
  "use strict";
  var S = window.Simplex, h = S.h, P = S.pages = S.pages || {};

  P["community-tools"] = async function (param, query, alive) {
    var responses = await Promise.all([S.api("data/community-tools"), S.schema()]);
    if (!alive()) return;
    var data = responses[0], schema = responses[1], selected = query.get("player");
    var player = null, error = null;
    if (selected && data.economy) {
      try {
        player = await S.api("data/community-tools", { query: { player: selected }, quiet: true });
      } catch (failure) {
        error = failure;
      }
    }
    if (!alive()) return;

    function edit(id, label, values) {
      var action = schema.find(function (entry) { return entry.id === id && entry.allowed === true; });
      if (!action) return null;
      return S.btn(label, { icon: "edit", size: "sm", onClick: function () {
        S.openAction(action, values, function () { if (alive()) S.reload(); });
      } });
    }

    function row(title, detail, action) {
      return h("div", { class: "item stackable" }, h("div", { class: "grow meta" },
        h("b", { text: title }), h("small", { text: detail })), action);
    }

    function world(key) {
      var value = data.scoreboard[key];
      if (!value.enabled) return null;
      return row(value.name, value.days + " Spieltage", edit("community.days", "Spieltag ändern", {
        world: key, expected: value.days, days: value.days
      }));
    }

    function settings() {
      var invite = data.invite || "";
      return S.card("Server", { icon: "settings" }, h("div", { class: "list" },
        row("Discord-Einladung", invite ? "discord.gg/" + invite : "Kein Code gesetzt", edit("community.invite", "Einladung ändern", {
          expected: invite, invite: invite
        })), world("main"), world("second")));
    }

    function playerTools() {
      function openPlayer(id) {
        if (id && id.trim()) S.go("#/community-tools?player=" + encodeURIComponent(id.trim()));
      }
      var input = S.can("data.players")
        ? S.playerSearch(function (result) { openPlayer(result.id); }, "Minecraft-Name oder UUID")
        : h("div", {}, S.input({ placeholder: "Exakter Minecraft-Name oder UUID" }));
      var field = input.querySelector("input");
      field.value = player ? player.name : selected || "";
      field.setAttribute("aria-label", "Minecraft-Name oder UUID");
      var form = h("form", { class: "row wrap", onSubmit: function (event) {
        event.preventDefault(); openPlayer(field.value);
      } }, input, h("button", { type: "submit", class: "btn", text: "Spieler laden" }));
      var account = player && h("div", { class: "stack" },
        row(player.name, player.balance + " SP", edit("community.points", "Punkte buchen", {
          player: player.player, expected: player.balance
        })),
        edit("community.delivery", "Quick Delivery erstellen", { player: player.player }),
        h("div", { class: "list" }, (player.deliveries || []).map(function (note) {
          var state = note.ack ? "Bestätigt" : note.sent ? "Zugestellt" : "Wartet auf Zustellung";
          return row(note.titleDE, state + " · " + S.date(note.created));
        })));
      return S.card("Punktekonto & Quick Delivery", { icon: "users" },
        h("div", { class: "stack" }, form, error ? S.notice(error.message, "warn") : null, account));
    }

    function shop() {
      var offers = data.offers || [], list = h("div", { class: "list" });
      function render(text) {
        S.clear(list);
        var filtered = offers.filter(function (offer) {
          return S.workspaceMatches(text, offer.titleDE + " " + offer.titleEN);
        });
        if (!filtered.length) list.appendChild(S.empty("box", "Keine Angebote gefunden"));
        filtered.forEach(function (offer) {
          var stock = offer.stock === "-1" ? "unbegrenzt" : offer.stock;
          var detail = offer.price + (offer.itemPayment ? " Items" : " SP") + " · Bestand " + stock
            + (offer.enabled ? "" : " · Inaktiv");
          list.appendChild(row(offer.titleDE, detail, edit("community.shop", "Angebot bearbeiten", {
            offer: offer.id, expected: offer.revision, expectedStock: offer.stock, titleDE: offer.titleDE,
            titleEN: offer.titleEN, price: offer.price, stock: offer.stock, enabled: String(offer.enabled)
          })));
        });
      }
      render("");
      return S.card("Shop-Angebote", { icon: "box" },
        h("div", { class: "stack" }, offers.length > 5 ? S.searchBox("Angebote filtern", render) : null, list));
    }

    S.title("Community-Verwaltung");
    S.mount(h("div", { class: "stack" }, settings(), data.economy ? playerTools() : null,
      data.economy ? shop() : S.notice("Punkte, Shop und Quick Delivery sind auf diesem Server nicht verfügbar.")));
  };
})();
