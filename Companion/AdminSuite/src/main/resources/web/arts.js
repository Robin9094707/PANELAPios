(function (S) {
  "use strict";
  var h = S.h, P = S.pages = S.pages || {};
  var modes = [["off", "Aus"], ["known", "Nur bekannte Spieler"], ["one-connection", "Bekannt · eine Verbindung je IP/Alt-Signal"], ["closed", "Nur Team mit Ausnahme"]];
  var slots = [["Ausrüstung & Nebenhand", 36, 5], ["Inventar", 9, 27], ["Hotbar", 0, 9], ["Endertruhe", 41, 27]];
  function select(values, value) {
    return h("select", { class: "input" }, values.map(function (item) {
      return h("option", { value: item[0], selected: item[0] === value, text: item[1] });
    }));
  }
  function fail(e) { S.toast(e.message, "err"); }

  /** 10.14.0: Minecraft-style inventory with swap, give-to-player and Einbehaltungsablage. */
  S.mcSlot = function (item, texture, opts) {
    opts = opts || {};
    return h(opts.tag || "button", { type: "button", class: "mc-slot" + (opts.picked ? " picked" : "") + (opts.empty ? " empty" : ""),
      title: opts.title || "", "aria-label": opts.title || "", "aria-pressed": opts.picked ? "true" : null, onClick: opts.onClick },
      texture ? h("img", { src: "data:image/png;base64," + texture, alt: "" }) : item ? h("span", { class: "mc-abbr", text: (item.name || "?").slice(0, 2).toUpperCase() }) : opts.placeholder ? h("span", { class: "mc-ph", text: opts.placeholder }) : null,
      item && item.amount > 1 ? h("small", { text: String(item.amount) }) : null,
      item && item.enchanted ? h("i", { class: "mc-glint", "aria-hidden": "true" }) : null,
      item && item.maxDurability > 0 && item.durability < item.maxDurability ? h("b", { class: "mc-dur", "aria-hidden": "true" },
        h("span", { style: { width: Math.round(100 * item.durability / item.maxDurability) + "%", background: "hsl(" + Math.round(120 * item.durability / item.maxDurability) + ",90%,50%)" } })) : null);
  };
  /** 10.15.0: item details with enchantments, durability, lore and shulker contents. */
  S.mcDetails = function (item, icons) {
    var contents = {};
    (item.contents || []).forEach(function (c) { contents[c.slot] = c; });
    var grid = [];
    for (var i = 0; i < 27; i++) grid.push(S.mcSlot(contents[i], contents[i] && icons[contents[i].material], { tag: "div", empty: !contents[i] }));
    S.modal(item.name, h("div", { class: "stack" },
      h("div", { class: "row" }, S.mcSlot(item, icons[item.material], { tag: "div" }), h("div", {}, h("b", { text: item.name }), h("div", { class: "small muted", text: item.amount + " / " + (item.max || 64) + " · " + item.material }))),
      item.maxDurability ? S.kv([["Haltbarkeit", item.durability + " / " + item.maxDurability]]) : null,
      (item.enchantments || []).length ? h("div", { class: "row wrap" }, item.enchantments.map(function (e) { return h("span", { class: "badge accent", text: e }); })) : null,
      (item.lore || []).length ? h("div", { class: "small muted" }, item.lore.map(function (l) { return h("div", { text: l }); })) : null,
      item.contents ? h("section", { class: "mc-panel" }, h("h3", { text: "Inhalt · " + item.contents.length + " Plätze belegt" }), h("div", { class: "mc-grid" }, grid)) : null), null, { icon: "box" });
  };
  S.pickPlayer = function (title, exclude) {
    return new Promise(function (resolve) {
      var done = false, dlg, list = h("div", { class: "list" });
      var search = S.input({ placeholder: "Spieler suchen – online und offline", "aria-label": "Spieler suchen" }), timer;
      async function load() {
        var data = await S.api("data/players", { query: { limit: 50, q: search.value.trim() }, quiet: true });
        S.clear(list);
        var rows = (data.items || []).filter(function (r) { return r.id !== exclude; }).sort(function (a, b) { return (b.online ? 1 : 0) - (a.online ? 1 : 0); });
        if (!rows.length) list.appendChild(S.empty("users", "Niemand gefunden", "Teil des Namens genügt."));
        rows.slice(0, 40).forEach(function (row) {
          list.appendChild(h("div", { class: "item link", onClick: function () { done = true; dlg.close(); resolve(row); } }, S.avatar(row.name, "sm"),
            h("div", { class: "meta" }, h("b", { text: row.name })), h("span", { class: "badge " + (row.online ? "ok" : ""), text: row.online ? "Online" : "Offline" }), S.icon("chevRight", "chev")));
        });
      }
      search.addEventListener("input", function () { clearTimeout(timer); timer = setTimeout(load, 250); });
      dlg = S.modal(title, h("div", { class: "stack" }, search, list), null, { icon: "users", onClose: function () { if (!done) resolve(null); } });
      load();
    });
  };
  S.mcIcons = async function (materials, cache) {
    var needed = materials.filter(function (m) { return m && !(m in cache); });
    for (var i = 0; i < needed.length; i += 60) {
      try { var data = await S.api("invsee/icons", { query: { items: needed.slice(i, i + 60).join(",") }, quiet: true, timeout: 10000 }); Object.assign(cache, data.icons || {}); } catch (e) { /* textures are optional */ }
      needed.slice(i, i + 60).forEach(function (m) { if (!(m in cache)) cache[m] = ""; });
    }
    return cache;
  };
  S.pickOnlinePlayer = function (title, exclude) {
    return new Promise(function (resolve) {
      var done = false, dlg, list = h("div", { class: "list" });
      var search = S.input({ placeholder: "Spieler suchen (online)", "aria-label": "Spieler suchen" }), timer;
      async function load() {
        var data = await S.api("data/players", { query: { filter: "online", limit: 100, q: search.value.trim() }, quiet: true });
        S.clear(list);
        var rows = (data.items || []).filter(function (r) { return r.online && r.id !== exclude; });
        if (!rows.length) list.appendChild(S.empty("users", "Niemand gefunden", "Nur Spieler, die gerade online sind."));
        rows.slice(0, 30).forEach(function (row) {
          list.appendChild(h("div", { class: "item link", onClick: function () { done = true; dlg.close(); resolve(row); } }, S.avatar(row.name, "sm"), h("div", { class: "meta" }, h("b", { text: row.name })), S.icon("chevRight", "chev")));
        });
      }
      search.addEventListener("input", function () { clearTimeout(timer); timer = setTimeout(load, 250); });
      dlg = S.modal(title, h("div", { class: "stack" }, search, list), null, { icon: "users", onClose: function () { if (!done) resolve(null); } });
      load();
    });
  };

  /** 10.15.0: invsee for any player (online or offline) with a second player side by side. */
  P.inventory = async function (id, query, alive) {
    if (!S.has("visual-inventory-v1") || !S.can("data.inventory")) {
      S.mount(S.lockedPage("Inventar gesperrt", "data.inventory")); return;
    }
    var v2 = S.has("invsee-move-v1");
    var sides = [{ player: id, name: "", revision: "", items: {}, editable: false, transfer: false, offline: false, error: "" }];
    var icons = {}, textureVersion = null, pick = null, amount = 0, busy = false, pack = false;
    var status = h("div", { class: "muted small", role: "status" }), grid = h("div", { class: "mc-wrap" }), bar = h("div", { class: "mc-actionbar" }), packArea = h("div", { class: "stack" });
    function heading() {
      var second = S.btn(sides.length > 1 ? "Zweiten Spieler schließen" : "Zweiten Spieler öffnen", { icon: "users", onClick: async function () {
        if (sides.length > 1) { sides.pop(); pick = null; heading(); draw(); return; }
        var row = await S.pickPlayer("Zweiten Spieler wählen", id); if (!row || !alive()) return;
        sides.push({ player: row.id, name: row.name, revision: "", items: {}, editable: false, transfer: false, offline: !row.online, error: "" });
        heading(); await update();
      } });
      S.title("Invsee", "Spieler", h("div", { class: "row wrap" }, v2 ? second : null,
        h("a", { class: "btn ghost sm", href: "#/player/" + encodeURIComponent(id), text: "Zum Profil" }),
        S.has("item-locker-v1") ? h("a", { class: "btn ghost sm", href: "#/locker", text: "Einbehaltungsablage" }) : null));
    }
    heading();
    S.mount(h("div", { class: "stack" }, status, grid, bar, packArea));
    status.textContent = "Item anklicken, dann Zielplatz anklicken – auch beim zweiten Spieler. Rechtsklick zeigt Details.";
    async function send(body, done) {
      if (busy) return;
      busy = true;
      try { var r = await S.api("invsee/move", { body: Object.assign({ confirmed: true }, body) }); S.toast(done || (r.moved + "× → " + r.target)); }
      catch (e) { fail(e); }
      finally { pick = null; amount = 0; busy = false; sides.forEach(function (s) { s.revision = ""; }); await update(); }
    }
    function move(from, toSide, toSlot) {
      var a = sides[from.side], b = sides[toSide], source = a.items[from.slot]; if (!source) return;
      var target = toSlot >= 0 ? b.items[toSlot] : null;
      var same = target && target.material === source.material && target.name === source.name;
      var swap = !!target && !same;
      if (swap && amount > 0 && amount < source.amount) { S.toast("Teilmengen nur auf freie oder gleiche Plätze.", "err"); return; }
      var to = { player: b.player, slot: toSlot };
      if (toSlot >= 0) to.revision = b.revision;
      var body = { from: { player: a.player, slot: from.slot, revision: a.revision }, to: to, mode: swap ? "swap" : "move" };
      if (amount > 0 && amount < source.amount) body.amount = amount;
      return send(body);
    }
    function slot(sideIndex, index, placeholder) {
      var side = sides[sideIndex], item = side.items[index], label = item ? item.name + " × " + item.amount : "Leer";
      var el = S.mcSlot(item, item && icons[item.material], { picked: pick && pick.side === sideIndex && pick.slot === index, placeholder: placeholder, title: side.name + " · Slot " + index + " · " + label, onClick: function () {
        if (busy) return;
        if (pick) {
          if (pick.side === sideIndex && pick.slot === index) { pick = null; draw(); return; }
          if (!sides[pick.side].editable || !side.editable) { S.toast("Für diesen Spieler fehlt das Bearbeitungsrecht.", "err"); return; }
          move(pick, sideIndex, index); return;
        }
        if (item) { pick = { side: sideIndex, slot: index }; amount = 0; draw(); }
      } });
      el.addEventListener("contextmenu", function (e) { if (item) { e.preventDefault(); S.mcDetails(item, icons); } });
      return el;
    }
    function range(sideIndex, a, b) { var out = []; for (var i = a; i < b; i++) out.push(slot(sideIndex, i)); return out; }
    function panel(sideIndex) {
      var side = sides[sideIndex];
      return h("div", { class: "stack" },
        h("div", { class: "row wrap" }, h("b", { text: side.name || "Spieler" }), h("span", { class: "badge " + (side.offline ? "" : "ok"), text: side.offline ? "Offline · Spielerdatei" : "Online · live" }),
          side.error ? h("span", { class: "small", style: { color: "var(--danger, #ef4444)" }, text: side.error }) : null),
        h("section", { class: "mc-panel" }, h("h3", { text: "Inventar" }),
          h("div", { class: "mc-top" }, h("div", { class: "mc-armor" }, slot(sideIndex, 39, "⛑"), slot(sideIndex, 38, "👕"), slot(sideIndex, 37, "👖"), slot(sideIndex, 36, "👢")),
            h("div", { class: "mc-figure", "aria-hidden": "true" }, S.avatar(side.name || "?", "lg")), h("div", { class: "mc-offhand" }, slot(sideIndex, 40, "🛡"))),
          h("div", { class: "mc-grid" }, range(sideIndex, 9, 36)), h("div", { class: "mc-grid hotbar" }, range(sideIndex, 0, 9))),
        h("section", { class: "mc-panel" }, h("h3", { text: "Endertruhe" }), h("div", { class: "mc-grid" }, range(sideIndex, 41, 68))));
    }
    function draw() {
      S.clear(grid); S.clear(bar);
      sides.forEach(function (side, index) { grid.appendChild(panel(index)); });
      var item = pick && sides[pick.side] && sides[pick.side].items[pick.slot];
      if (!item) return;
      var side = sides[pick.side], other = pick.side === 0 ? 1 : 0;
      var qty = item.amount > 1 ? S.input({ type: "number", min: 1, max: item.amount, value: amount || item.amount, style: { width: "84px" }, "aria-label": "Menge" }) : null;
      if (qty) qty.addEventListener("input", function () { amount = Math.max(1, Math.min(item.amount, Number(qty.value) || item.amount)); });
      bar.append(h("div", { class: "row wrap" }, S.mcSlot(item, icons[item.material], { tag: "div" }),
        h("div", { class: "grow" }, h("b", { text: item.name }), h("div", { class: "small muted", text: side.name + " · Slot " + pick.slot + (side.offline ? " · offline" : "") + " · Zielplatz anklicken" })),
        qty ? h("label", { class: "row", style: { gap: "6px" } }, h("span", { class: "small muted", text: "Menge" }), qty) : null,
        S.btn("Details", { size: "sm", icon: "info", onClick: function () { S.mcDetails(item, icons); } }),
        sides.length > 1 ? S.btn("Zu " + sides[other].name, { size: "sm", kind: "primary", onClick: function () { move(pick, other, -1); } }) : null,
        side.transfer ? S.btn("In Ablage", { size: "sm", icon: "box", onClick: async function () {
          var reason = await S.prompt("In Ablage legen", "Grund", "", "Einlagern"); if (reason === null) return;
          var body = { from: { player: side.player, slot: pick.slot, revision: side.revision }, to: { player: "locker" }, reason: reason };
          if (amount > 0 && amount < item.amount) body.amount = amount;
          send(body, "In der Einbehaltungsablage gesichert.");
        } }) : null,
        S.btn("", { icon: "x", size: "sm icon", title: "Auswahl aufheben", onClick: function () { pick = null; draw(); } })));
    }
    function packControls(data) {
      S.clear(packArea);
      if (!pack && !data.status) return;
      var input = h("input", { type: "file", class: "input", "aria-label": "Java-Ressourcenpaket" });
      packArea.appendChild(S.card("Ressourcenpaket", { sub: (data.custom ? "Eigenes ZIP + " : "") + (data.status || "") },
        pack ? h("div", { class: "row wrap" }, input, S.btn("ZIP importieren", { onClick: async function () {
          var file = input.files[0]; if (!file) return;
          if (file.size > 32 * 1024 * 1024) { S.toast("Maximal 32 MiB.", "err"); return; }
          try { await S.api("invsee/pack", { body: file, type: "application/zip" }); textureVersion = null; await update(); } catch (e) { fail(e); }
        } }), data.custom ? S.btn("Vanilla verwenden", { onClick: async function () {
          if (!await S.confirm("Eigenes Paket entfernen", "Die Vorschau lädt wieder die Vanilla-Texturen.", "Entfernen", true)) return;
          try { await S.api("invsee/pack", { query: { mode: "reset" }, body: { confirmed: true } }); textureVersion = null; await update(); } catch (e) { fail(e); }
        } }) : null) : null));
    }
    var updating = false;
    async function update() {
      if (!alive() || busy || updating) return;
      updating = true;
      var changed = false, textures = textureVersion;
      try {
        for (var i = 0; i < sides.length; i++) {
          var side = sides[i];
          try {
            var params = { player: side.player, revision: side.revision, offline: v2 ? "auto" : String(query.get("offline") === "true") };
            if (v2) params.v2 = 1;
            var data = await S.api("invsee", { query: params, quiet: true, noRetry: true, timeout: 8000 });
            if (!alive() || sides[i] !== side) return;
            if (side.editable !== data.editable || side.transfer !== !!data.transfer) changed = true;
            side.editable = data.editable; side.transfer = !!data.transfer; side.offline = !!data.offline; side.error = "";
            if (data.name) side.name = data.name;
            if (i === 0) pack = data.pack;
            if (!data.unchanged) {
              side.items = {}; (data.slots || []).forEach(function (item) { side.items[item.slot] = item; });
              side.revision = data.revision; changed = true;
              if (pick && pick.side === i) pick = null;
            }
            textures = data.textures;
          } catch (e) { side.error = e.message; side.items = {}; side.revision = ""; side.editable = false; changed = true; }
        }
        var needed = [];
        sides.forEach(function (side) { Object.values(side.items).forEach(function (item) { needed.push(item.material); (item.contents || []).forEach(function (c) { needed.push(c.material); }); }); });
        needed = Array.from(new Set(needed));
        if (textureVersion !== textures || needed.some(function (m) { return !(m in icons); })) {
          if (textureVersion !== textures) icons = {};
          await S.mcIcons(needed, icons);
          var info = await S.api("invsee/icons", { query: { items: "" }, quiet: true }).catch(function () { return {}; });
          if (!alive()) return;
          textureVersion = textures; packControls(info); changed = true;
        }
        if (changed) draw();
      } finally { updating = false; }
    }
    await update();
    if (alive()) S.every(3000, update);
  };

  P["player-devices"] = async function (id, query, alive) {
    if (!S.can("data.devices")) { S.mount(S.lockedPage("Geräte gesperrt", "data.devices")); return; }
    var data = await S.api("data/devices", { query: { player: id } });
    if (!alive()) return;
    S.title("Spielergeräte", "Geräteverlauf");
    var rows = (data.items || []).sort(function (a, b) { return b.lastSeen - a.lastSeen; });
    S.mount(h("div", { class: "stack" }, rows.length ? rows.map(function (item) {
      return S.card(item.name || item.platform, { sub: S.date(item.lastSeen) },
        S.kv([["Plattform", item.platform], ["System", item.os], ["Version", item.version], ["Eingabe", item.input], ["Sprache", item.language]]));
    }) : S.empty("devices", "Keine Geräte erfasst", "")));
  };

  P["server-events"] = async function (id, query, alive) {
    S.title("Event-Modus", "Server");
    var root = h("div", { class: "stack" });
    var draft = null;
    async function act(values, description, dangerous) {
      if (!await S.confirm("Event-Modus", description, "Anwenden", dangerous)) return;
      try { await S.api("server-events", { body: Object.assign(values, { confirmed: true }) }); draft = null; await load(); }
      catch (e) { fail(e); }
    }
    async function load() {
      var data = await S.api("server-events");
      if (!alive()) return;
      S.clear(root);
      var name = h("input", { class: "input", value: data.active || "", maxlength: 80, placeholder: "Event-Name", "aria-label": "Aktives Event" });
      var mode = select(modes, data.lockdown);
      root.appendChild(S.card("Serverstatus", { sub: data.active || "Kein aktives Event" },
        data.manage ? h("div", { class: "row wrap" }, name, S.btn("Event anzeigen", { kind: "primary", onClick: function () { return act({ operation: "status", name: name.value }, "Event-Status ändern?"); } }),
          data.active ? S.btn("Event beenden", { onClick: function () { return act({ operation: "status", name: "" }, "Aktives Event beenden?"); } }) : null) : null));
      root.appendChild(S.card("Lockdown", { sub: (modes.find(function (m) { return m[0] === data.lockdown; }) || modes[0])[1] },
        data.manage ? h("div", { class: "row wrap" }, mode, S.btn("Stufe anwenden", { onClick: function () { return act({ operation: "lockdown", mode: mode.value }, "Beitritte nach dieser Stufe filtern?", mode.value !== "off"); } }),
          data.lockdown !== "off" ? S.btn("Lockdown deaktivieren", { onClick: function () { return act({ operation: "lockdown", mode: "off" }, "Beitrittsschutz deaktivieren?"); } }) : null) : null));
      root.appendChild(S.card("Geplante Spieltage", { sub: "Weltzeit · 24.000 Ticks pro Tag · einmalige Ausführung" },
        (data.items || []).map(function (item) { return h("div", { class: "item row wrap" },
          h("div", { class: "grow" }, h("b", { text: item.name }), h("small", { class: "muted", text: " · " + item.world + " · Tag " + item.day + (item.activate ? " · Event starten" : " · Alarm") }),
            (item.commands || []).length ? h("div", { class: "mono small", text: item.commands.join(" · ") }) : null),
          data.manage ? S.btn("Bearbeiten", { onClick: function () { draft = item; return load(); } }) : null,
          data.manage ? S.btn("Löschen", { onClick: function () { return act({ operation: "delete", id: item.id }, "Diesen Termin löschen?", true); } }) : null); })));
      if (!data.manage || !data.worlds.length) return;
      var world = select(data.worlds.map(function (w) { return [w.name, w.name + " · aktuell Tag " + w.day]; }), draft && draft.world);
      var title = h("input", { class: "input", maxlength: 80, value: draft ? draft.name : "", "aria-label": "Titel" });
      var day = h("input", { class: "input", type: "number", min: 0, value: draft ? draft.day : data.worlds[0].day + 1, "aria-label": "Ingame-Tag" });
      world.addEventListener("change", function () { day.value = data.worlds.find(function (w) { return w.name === world.value; }).day + 1; });
      var activate = h("input", { type: "checkbox", checked: draft && draft.activate });
      var commandList = h("datalist", { id: "event-command-list" }, (data.known || []).map(function (entry) { return h("option", { value: entry }); }));
      var command = h("input", { class: "input", list: "event-command-list", "aria-label": "Befehl auswählen", placeholder: "Befehlsname und Argumente" });
      var commands = h("textarea", { class: "input", rows: 3, value: draft ? draft.commands.join("\n") : "", "aria-label": "Konsolenbefehle", placeholder: "Ein Befehl pro Zeile" });
      root.appendChild(S.card(draft ? "Termin bearbeiten" : "Spieltag planen", {},
        h("div", { class: "stack" }, S.field("Welt", world), S.field("Ingame-Tag", day), S.field("Titel", title),
          h("label", { class: "row" }, activate, "Event im Serverstatus starten"),
          data.commands ? h("div", { class: "stack" }, commandList, h("div", { class: "row wrap" }, command,
            S.btn("Befehl hinzufügen", { onClick: function () { if (command.value.trim()) commands.value += (commands.value ? "\n" : "") + command.value.trim(); command.value = ""; } })),
            S.field("Konsolenbefehle", commands, "Optional · werden beim Erreichen dieses Tages einmal als Konsole ausgeführt.")) : null,
          S.btn("Termin speichern", { kind: "primary", onClick: function () { return act({ operation: "schedule", id: draft ? draft.id : "", name: title.value,
            world: world.value, day: Number(day.value), activate: activate.checked, commands: data.commands ? commands.value.split("\n").map(function (line) { return line.trim(); }).filter(Boolean) : [] },
            "Termin für " + world.value + ", Tag " + day.value + " speichern?"); } }),
          draft ? S.btn("Abbrechen", { onClick: function () { draft = null; return load(); } }) : null)));
    }
    S.mount(root);
    await load();
  };
})(window.Simplex);
