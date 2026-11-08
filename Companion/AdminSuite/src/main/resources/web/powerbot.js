(function (S) {
  "use strict";
  var h = S.h, P = S.pages;
  var statusNames = { active: "Aktiv", off: "Aus", bypass: "KI pausiert · Chat frei", unconfigured: "API-Schlüssel fehlt · lokale Regeln aktiv" };
  var kindNames = { mute: "AutoMod-Mute", conflict: "Konflikt", help: "Hilferuf", security: "Cheat-Hinweis", outage: "KI pausiert",
    recovery: "KI wieder erreichbar", settings: "Einstellungen geändert", abuse: "Chatnachricht blockiert", advertising: "Werbung blockiert",
    "keyword-warning": "Stichwortprüfung · Warnung", "keyword-block": "Stichwortprüfung · blockiert",
    warning: "Warnhinweis", reviewed: "Nachträgliche Prüfung", deescalation: "KI hat deeskaliert", "ai-action": "KI-Aktion ausgeführt", "server-event": "Wichtiges Serverereignis" };
  var labels = { enabled: "PowerBot", moderation: "KI-Moderation", whispers: "Flüsternachrichten prüfen", advertising: "Eigenwerbung blockieren",
    "auto-mute": "AutoMod-Mutes", "linked-mutes": "Bestätigte Alt-Konten einschließen", "public-chat": "Öffentliche Chat-KI",
    proactive: "Proaktiv deeskalieren", discord: "Discord-Relay", "notify-staff": "Staff benachrichtigen",
    "security-hints": "Cheat-Hinweise sammeln", "staff-device-context": "Geräteinfos für Staff-KI", "critical-alerts": "Kritische Meldungen" };

  P.powerbot = async function (id, query, alive) {
    if (!S.has("powerbot-v1") || !S.can("data.powerbot")) { S.mount(S.lockedPage("PowerBot gesperrt", "data.powerbot")); return; }
    if (id === "keywords") { await keywordRules(alive); return; }
    var filter = query.get("filter") || "important", version = 0, busy = false;
    var options = h("details", {}), head = h("div", {}), rows = h("div", { class: "stack" });
    S.title("PowerBot-Fallzentrale", "Moderation");
    S.mount(h("div", { class: "stack" },
      S.card("Chatmoderation und Teamübergabe", { icon: "shieldCheck" }, head),
      options,
      S.segmented([["important", "Wichtig"], ["open", "Offen"], ["mutes", "Mutes"], ["all", "Alles"]], filter,
        function (value) { filter = value; load().catch(fail); }),
      rows));
    function fail(error) { if (alive()) S.toast(error.message, "err"); }
    async function change(operation, fields, title, detail) {
      if (busy) return;
      busy = true;
      try {
        if (title && !await S.confirm(title, detail, "Bestätigen")) return;
        await S.api("powerbot", { body: Object.assign({ operation: operation, confirmed: true }, fields) });
        if (!alive()) return;
        S.toast("PowerBot aktualisiert.", "ok");
        if (operation === "settings") { options.open = false; options.replaceChildren(); }
        await load();
      } catch (e) { fail(e); } finally { busy = false; }
    }
    function settings(data) {
      if (!data.canSettings) { options.replaceChildren(); return; }
      if (options.open && options.childNodes.length) return;
      var switches = {};
      var inputs = Object.entries(labels).map(function (field) {
        var input = switches[field[0]] = h("input", { type: "checkbox", checked: !!data.flags[field[0]] });
        return h("label", { class: "row" }, input, field[1]);
      });
      var notes = h("textarea", { class: "input", rows: 4, maxLength: 500, placeholder: "Optionale Moderationsrichtlinien", "aria-label": "Moderationsrichtlinien" });
      notes.value = data.guidelines || "";
      options.replaceChildren(h("summary", { class: "btn", text: "PowerBot einstellen" }), S.card(null, { icon: "settings" },
        h("div", { class: "grid two" }, inputs), notes,
        S.btn("Einstellungen speichern", { kind: "primary", onClick: function () {
          var flags = {};
          Object.keys(switches).forEach(function (key) { flags[key] = switches[key].checked; });
          return change("settings", { flags: flags, guidelines: notes.value }, "Einstellungen übernehmen?", "Die Regeln gelten sofort für den Server.");
        } })));
    }
    async function load() {
      var request = ++version;
      var data = await S.api("powerbot", { query: { filter: filter } });
      if (!alive() || request !== version) return;
      head.replaceChildren(h("div", { class: "row wrap" },
        S.badge(statusNames[data.status] || data.status, data.status === "active" ? "ok" : "warn"),
        S.badge(data.open + " offene Fälle", "accent"),
        data.canSettings && S.has("powerbot-keywords-v1") ?
          h("a", { class: "btn sm", href: "#/powerbot/keywords", text: "Stichwortregeln" }) : null));
      settings(data);
      rows.replaceChildren();
      if (!(data.items || []).length) rows.appendChild(S.card("Keine Einträge", { icon: "check" }));
      (data.items || []).forEach(function (row) {
        var buttons = [], id = row.id;
        if (data.manage && row.case) {
          if (!row.resolved && !row.assignee) buttons.push(S.btn("Übernehmen", { size: "sm", onClick: function () { return change("claim", { id: id }); } }));
          if (!row.resolved) buttons.push(S.btn("Abschließen", { size: "sm", onClick: function () { return change("resolve", { id: id }, "Fall abschließen?", "Der Eintrag bleibt im Protokoll erhalten."); } }));
          if (row.active) buttons.push(S.btn("Mute aufheben", { size: "sm", onClick: function () { return change("revoke", { id: id }, "AutoMod-Mute aufheben?", "Gilt für die Konten dieses Falls."); } }));
        }
        if (/^[0-9a-f-]{36}$/i.test(row.player || "") && S.can("data.profile")) buttons.push(h("a", { class: "btn sm", href: "#/player/" + encodeURIComponent(row.player), text: "Spieler öffnen" }));
        var subtitle = [row.name, row.source, S.date(row.at)].filter(Boolean).join(" · ");
        rows.appendChild(S.card(kindNames[row.kind] || row.kind, { icon: row.active ? "lock" : "shieldCheck", sub: subtitle },
          h("p", { class: "small", text: row.detail }), h("div", { class: "row wrap" },
            row.active ? S.badge("Aktiv bis " + S.date(row.until), "warn") : null,
            row.score ? S.badge(row.score + " bestätigte Versuche", "info") : null,
            row.revokedAt ? S.badge("Widerrufen", "info") : null,
            row.resolved ? S.badge("Abgeschlossen", "ok") : null,
            row.assignee ? S.badge("Übernommen von " + row.assignee, "accent") : null),
          buttons.length ? h("div", { class: "row wrap" }, buttons) : null));
      });
    }
    await load();
  };

  async function keywordRules(alive) {
    if (!S.has("powerbot-keywords-v1") || !S.full() || !S.can("powerbot.settings") || !S.can("powerbot.manage")) {
      S.mount(S.lockedPage("Stichwortregeln gesperrt", "powerbot.settings")); return;
    }
    var data = await S.api("powerbot/keywords");
    if (!alive()) return;
    var rules = data.items || [], selected = "", busy = false;
    var heading = h("b", { text: "Stichwort hinzufügen" });
    var search = h("input", { type: "search", class: "input", placeholder: "Stichwort oder Regel suchen", "aria-label": "Stichwort oder Regel suchen", onInput: draw });
    var keyword = h("input", { class: "input", maxLength: 64, required: true, placeholder: "Zum Beispiel SMP", "aria-label": "Stichwort oder Phrase" });
    var instructions = h("textarea", { class: "input", maxLength: 500, rows: 5, placeholder: "Was soll PowerBot prüfen?", "aria-label": "Eigene Prüfregel" });
    var list = h("div", { class: "list" });
    var count = h("small", { class: "muted" });
    var save = S.btn("Speichern", { kind: "primary", onClick: submit });
    var reset = S.btn("Neues Stichwort", { onClick: function () { if (!busy) edit(null); } });
    S.title("Stichwortregeln", "PowerBot", h("a", { class: "btn ghost sm", href: "#/powerbot", text: "Zur Fallzentrale" }));
    S.mount(h("div", { class: "stack" },
      S.card("Stichwörter", { icon: "search", sub: "Ganze Wörter und Phrasen lösen eine KI-Prüfung aus." }, search, count, list),
      S.card(null, { icon: "settings" }, h("div", { class: "stack" }, heading,
        h("label", { class: "stack" }, "Stichwort oder Phrase", keyword),
        h("label", { class: "stack" }, "Eigene Prüfregel", instructions),
        h("small", { class: "muted", text: "Beschreibe, wann PowerBot durchlassen, warnen oder blockieren soll. Leer verwendet die allgemeinen Moderationsregeln." }),
        h("div", { class: "row wrap" }, save, reset)))));
    draw();

    function edit(row) {
      selected = row ? row.id : "";
      keyword.value = row ? row.keyword : "";
      instructions.value = row ? row.instructions : "";
      heading.textContent = row ? "Stichwort bearbeiten" : "Stichwort hinzufügen";
      keyword.focus();
    }
    function draw() {
      var query = search.value.trim().toLowerCase();
      var visible = rules.filter(function (row) {
        return !query || (row.keyword + " " + row.instructions).toLowerCase().includes(query);
      });
      count.textContent = visible.length + " von " + rules.length + " Stichwortregeln";
      list.replaceChildren();
      if (!visible.length) list.appendChild(S.notice(query ? "Keine Treffer." : "Noch keine Stichwörter."));
      visible.forEach(function (row) {
        list.appendChild(h("div", { class: "item row wrap" },
          h("div", { class: "meta grow" }, h("b", { text: row.keyword }), h("small", { text: row.instructions || "Allgemeine Moderationsregeln" })),
          S.btn("Bearbeiten", { size: "sm", onClick: function () { if (!busy) edit(row); } }),
          S.btn("Löschen", { size: "sm", onClick: async function () {
            if (busy || !await S.confirm("Stichwort löschen?", "Die Regel für „" + row.keyword + "“ wird entfernt.", "Löschen", true)) return;
            await change({ operation: "delete", id: row.id }, selected === row.id);
          } })));
      });
    }
    function submit() {
      if (!keyword.reportValidity() || !keyword.value.trim()) return;
      return change({ operation: "save", id: selected, keyword: keyword.value, instructions: instructions.value }, true);
    }
    async function change(body, clearDraft) {
      if (busy) return;
      busy = true;
      save.disabled = true;
      try {
        var result = await S.api("powerbot/keywords", { body: Object.assign({ confirmed: true }, body) });
        if (!alive()) return;
        rules = result.items || [];
        if (clearDraft) edit(null);
        draw();
        S.toast("Stichwortregeln aktualisiert.", "ok");
      } catch (e) { if (alive()) S.toast(e.message, "err"); }
      finally { busy = false; save.disabled = false; }
    }
  }

  S.powerBotDigest = async function (alive) {
    if (!S.has("powerbot-v1") || !S.can("data.powerbot")) return null;
    var key = "simplex.powerbot.last." + S.health.serverId + "." + (S.account && S.account.username || S.health.keyLabel || "user");
    var previous;
    try { previous = Number(localStorage.getItem(key)); localStorage.setItem(key, String(Date.now())); } catch (e) { return null; }
    if (!previous || Date.now() - previous < 10800000) return null;
    try {
      var data = await S.api("powerbot", { quiet: true, query: { after: previous } });
      if (!alive() || !data.recent) return null;
      var card = S.card("Seit deinem letzten Besuch", { icon: "shieldCheck", sub: data.recent + " wichtige PowerBot-Einträge · " + data.mutes + " Mutes" },
        h("a", { class: "btn sm primary", href: "#/powerbot", text: "Fallzentrale öffnen" }),
        S.btn("Ausblenden", { size: "sm", onClick: function () { card.remove(); } }));
      return card;
    } catch (e) { try { localStorage.setItem(key, String(previous)); } catch (ignored) {} return null; }
  };

  P.snapshots = async function (id, query, alive) {
    if (!S.can("data.snapshots") || !S.can("data.snapshot-content")) { S.mount(S.lockedPage("Snapshot gesperrt", "data.snapshot-content")); return; }
    S.title("Inventar-Snapshots", "Spieler", h("a", { class: "btn ghost sm", href: "#/player/" + encodeURIComponent(id), text: "Zum Profil" }));
    var file = query.get("file");
    if (!file) {
      var history = await S.api("data/snapshots", { query: { player: id } });
      if (!alive()) return;
      S.mount(S.card("Gespeicherte Inventare", { icon: "box" }, (history.items || []).length ? (history.items || []).map(function (row) {
        return h("a", { class: "item link", href: "#/snapshots/" + encodeURIComponent(id) + "?file=" + encodeURIComponent(row.id) },
          h("div", { class: "meta" }, h("b", { text: row.name }), h("small", { text: S.date(row.modified) })));
      }) : S.notice("Keine Snapshots gespeichert.")));
      return;
    }
    var data = await S.api("data/snapshot-content", { query: { player: id, file: file } });
    if (!alive()) return;
    var items = data.items || [], keys = Array.from(new Set(items.map(function (row) { return row.type.toLowerCase(); })));
    var textures = {};
    if (S.has("visual-inventory-v1") && keys.length && keys.length <= 70) {
      try { textures = (await S.api("invsee/icons", { query: { items: keys.join(",") }, quiet: true })).icons || {}; } catch (e) {}
    }
    if (!alive()) return;
    var parts = [["inventory", "Inventar", 41], ["armor", "Rüstung", 4], ["enderchest", "Endertruhe", 27]];
    S.mount(h("div", { class: "stack" },
      S.card("Snapshot", { icon: "box", sub: file }, S.kv([["Erstellt von", data.actor], ["XP-Level", data.level], ["Spielmodus", data.gamemode]])),
      parts.map(function (part) {
        var bySlot = new Map(items.filter(function (row) { return row.container === part[0]; }).map(function (row) { return [row.slot, row]; }));
        return S.card(part[1], {}, h("div", { class: "inventory-grid" }, Array.from({ length: part[2] }, function (_, slot) {
          var row = bySlot.get(slot), texture = row && textures[row.type.toLowerCase()];
          return h("div", { class: "inventory-slot", title: row ? row.name + " × " + row.amount : "Leer" },
            texture ? h("img", { src: "data:image/png;base64," + texture, alt: row.name }) : row ? h("span", { text: row.type.slice(0, 2) }) : null,
            row && row.amount > 1 ? h("small", { text: String(row.amount) }) : null);
        })));
      })));
  };
})(window.Simplex);
