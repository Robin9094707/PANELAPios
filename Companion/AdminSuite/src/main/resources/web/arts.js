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
      item && item.amount > 1 ? h("small", { text: String(item.amount) }) : null);
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

  P.inventory = async function (id, query, alive) {
    if (!S.has("visual-inventory-v1") || !S.can("data.inventory")) {
      S.mount(S.lockedPage("Inventar gesperrt", "data.inventory")); return;
    }
    var offline = query.get("offline") === "true" && S.has("powerbot-v1");
    var last = "", textureVersion = null, icons = {}, contents = {}, edit = false, transfer = false, pack = false, selected = null, swapping = false, busy = false, name = "";
    var status = h("div", { class: "muted small", role: "status" }), grid = h("div", { class: "mc-wrap" }), bar = h("div", { class: "mc-actionbar" }), packArea = h("div", { class: "stack" });
    S.title(offline ? "Offline-Inventar" : "Live-Inventar", "Spieler", h("div", { class: "row wrap" },
      h("a", { class: "btn ghost sm", href: "#/player/" + encodeURIComponent(id), text: "Zum Profil" }),
      S.has("item-locker-v1") ? h("a", { class: "btn ghost sm", href: "#/locker", text: "Einbehaltungsablage" }) : null));
    S.mount(h("div", { class: "stack" }, status, grid, bar, packArea));
    async function apply(change, confirmText) {
      if (busy) return;
      busy = true;
      try {
        if (!confirmText || await S.confirm("Inventar ändern", confirmText, "Anwenden")) { await S.api(change.path, { body: change.body }); S.toast(change.done || "Inventar aktualisiert."); }
      } catch (e) { fail(e); }
      finally { selected = null; swapping = false; busy = false; await update(); }
    }
    async function move(destination) {
      var item = contents[selected], slot = selected; if (!item) return;
      var target = null;
      if (destination === "player") { target = await S.pickOnlinePlayer("An Spieler geben", id); if (!target || !alive()) return; }
      var amount = item.amount;
      if (item.amount > 1) {
        var value = await S.prompt("Menge", "Wie viele von " + item.amount + "?", String(item.amount), "Weiter", { type: "number", min: 1, max: item.amount });
        if (value === null || !alive()) return;
        amount = Math.max(1, Math.min(item.amount, Number(value) || item.amount));
      }
      var reason = await S.prompt("Grund", destination === "locker" ? "Warum wird das Item einbehalten?" : "Grund (optional)", "", destination === "locker" ? "Einlagern" : "Geben");
      if (reason === null || !alive()) return;
      await apply({ path: "invsee/transfer", done: destination === "locker" ? "In der Einbehaltungsablage gesichert." : "An " + target.name + " gegeben.",
        body: { player: id, revision: last, slot: slot, amount: amount, destination: destination, target: target ? target.id : "", reason: reason, confirmed: true } });
    }
    function slot(index, placeholder) {
      var item = contents[index], label = item ? item.name + " × " + item.amount : "Leer";
      return S.mcSlot(item, item && icons[item.material], { picked: selected === index, placeholder: placeholder, title: "Slot " + index + " · " + label, onClick: function () {
        if (busy) return;
        if (swapping && selected != null) {
          var from = selected; swapping = false;
          if (from === index || from >= 36 && from <= 40 || index >= 36 && index <= 40) { status.textContent = "Ausrüstung lässt sich nicht tauschen."; selected = null; draw(); return; }
          apply({ path: "invsee", body: { player: id, revision: last, offline: offline, from: from, to: index, confirmed: true } });
          return;
        }
        selected = selected === index || !item ? null : index;
        status.textContent = item ? "Slot " + index + " · " + label : "Leerer Slot";
        draw();
      } });
    }
    function range(a, b) { var out = []; for (var i = a; i < b; i++) out.push(slot(i)); return out; }
    function draw() {
      S.clear(grid); S.clear(bar);
      grid.append(
        h("section", { class: "mc-panel" }, h("h3", { text: name ? "Inventar von " + name : "Inventar" }),
          h("div", { class: "mc-top" }, h("div", { class: "mc-armor" }, slot(39, "⛑"), slot(38, "👕"), slot(37, "👖"), slot(36, "👢")),
            h("div", { class: "mc-figure", "aria-hidden": "true" }, S.icon("user")), h("div", { class: "mc-offhand" }, slot(40, "🛡"))),
          h("div", { class: "mc-grid" }, range(9, 36)), h("div", { class: "mc-grid hotbar" }, range(0, 9))),
        h("section", { class: "mc-panel" }, h("h3", { text: "Endertruhe" }), h("div", { class: "mc-grid" }, range(41, 68))));
      var item = selected != null && contents[selected];
      if (!item) return;
      bar.append(h("div", { class: "row wrap" }, S.mcSlot(item, icons[item.material], { tag: "div" }), h("div", { class: "grow" }, h("b", { text: item.name }), h("div", { class: "small muted", text: item.amount + "× · Slot " + selected })),
        swapping ? h("span", { class: "badge accent", text: "Zielslot wählen …" }) : null,
        edit && (selected < 36 || selected > 40) && !swapping ? S.btn("Tauschen", { icon: "refresh", onClick: function () { swapping = true; draw(); } }) : null,
        transfer && !offline && !swapping ? S.btn("An Spieler geben", { kind: "primary", icon: "users", onClick: function () { return move("player"); } }) : null,
        transfer && !offline && !swapping ? S.btn("In Ablage", { icon: "box", onClick: function () { return move("locker"); } }) : null,
        S.btn("", { icon: "x", size: "sm icon", title: "Auswahl aufheben", onClick: function () { selected = null; swapping = false; draw(); } })));
    }
    function packControls(data) {
      S.clear(packArea);
      if (!pack && !data.status) return;
      var input = h("input", { type: "file", class: "input", "aria-label": "Java-Ressourcenpaket" });
      var label = (data.custom ? "Eigenes ZIP + " : "") + (data.status || "");
      packArea.appendChild(S.card("Ressourcenpaket", { sub: label },
        pack ? h("div", { class: "row wrap" }, input, S.btn("ZIP importieren", { onClick: async function () {
          var file = input.files[0]; if (!file) return;
          if (file.size > 32 * 1024 * 1024) { S.toast("Maximal 32 MiB.", "err"); return; }
          try { await S.api("invsee/pack", { body: file, type: "application/zip" }); textureVersion = null; await update(); }
          catch (e) { fail(e); }
        } }), data.custom ? S.btn("Vanilla verwenden", { onClick: async function () {
          if (!await S.confirm("Eigenes Paket entfernen", "Die Vorschau lädt wieder die Vanilla-Texturen.", "Entfernen", true)) return;
          try { await S.api("invsee/pack", { query: { mode: "reset" }, body: { confirmed: true } }); textureVersion = null; await update(); }
          catch (e) { fail(e); }
        } }) : null) : null));
    }
    var updating = false;
    async function update() {
      if (!alive() || busy || updating) return;
      updating = true;
      try {
        var data = await S.api("invsee", { query: { player: id, revision: last, offline: offline }, quiet: true, noRetry: true, timeout: 7000 });
        if (!alive()) return;
        var changed = !data.unchanged, permissionsChanged = edit !== data.editable || pack !== data.pack || transfer !== !!data.transfer;
        if (data.name) name = data.name;
        if (changed && selected != null) { selected = null; swapping = false; status.textContent = "Inventar wurde vom Spieler geändert. Bitte neu auswählen."; }
        else if (changed) status.textContent = data.editable || data.transfer ? "Item anklicken, um es zu tauschen, weiterzugeben oder einzubehalten." : "Nur Ansicht";
        edit = data.editable; pack = data.pack; transfer = !!data.transfer;
        if (changed) {
          contents = {};
          (data.slots || []).forEach(function (item) { contents[item.slot] = item; });
          last = data.revision;
        }
        var needed = Array.from(new Set(Object.values(contents).map(function (item) { return item.material; })));
        if (textureVersion !== data.textures || needed.some(function (material) { return !(material in icons); })) {
          if (textureVersion !== data.textures) icons = {};
          var images = await S.api("invsee/icons", { query: { items: needed.slice(0, 70).join(",") }, quiet: true, timeout: 10000 });
          if (!alive()) return;
          Object.assign(icons, images.icons || {});
          needed.forEach(function (material) { if (!(material in icons)) icons[material] = ""; });
          textureVersion = data.textures;
          packControls(images);
          changed = true;
        }
        if (changed || permissionsChanged) draw();
      } catch (e) {
        if (!alive()) return;
        status.textContent = e.message;
        last = ""; contents = {}; selected = null; edit = false; transfer = false;
        draw();
      } finally { updating = false; }
    }
    await update();
    if (alive() && !offline) S.every(2000, update);
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
