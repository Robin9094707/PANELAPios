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

  P.inventory = async function (id, query, alive) {
    if (!S.has("visual-inventory-v1") || !S.can("data.inventory")) {
      S.mount(S.lockedPage("Inventar gesperrt", "data.inventory")); return;
    }
    var offline = query.get("offline") === "true" && S.has("powerbot-v1");
    var last = "", textureVersion = null, icons = {}, contents = {}, edit = false, pack = false, selected = null, target = null, busy = false;
    var status = h("div", { class: "muted small", role: "status" }), grid = h("div", { class: "stack" }), packArea = h("div", { class: "stack" });
    var apply = S.btn("Anwenden", { kind: "primary", disabled: true, onClick: async function () {
      if (selected == null || target == null || busy) return;
      var change = { player: id, revision: last, offline: offline, from: selected, to: target, confirmed: true };
      busy = true;
      try {
        if (await S.confirm("Inventar ändern", "Diese Slots sofort auf dem Minecraft-Server tauschen?", "Anwenden")) {
          await S.api("invsee", { body: change });
          S.toast("Inventar aktualisiert.");
        }
      } catch (e) { fail(e); }
      finally { selected = target = null; busy = false; await update(); }
    } });
    S.title(offline ? "Offline-Inventar" : "Live-Inventar", "Spieler", h("a", { class: "btn ghost sm", href: "#/player/" + encodeURIComponent(id), text: "Zum Profil" }));
    S.mount(h("div", { class: "stack" }, S.card("Minecraft-Inventar", { icon: "grid", sub: offline ? "Gespeicherte Spielerdatei · Sicherung vor jeder Änderung" : "Live vom Server · aktualisiert alle 2 Sekunden" }, status, grid,
      h("div", { class: "row wrap" }, apply, S.btn("Auswahl zurücksetzen", { onClick: function () { selected = target = null; draw(); } }))),
      packArea));
    function draw() {
      S.clear(grid);
      slots.forEach(function (part) {
        var cells = [];
        for (var i = part[1]; i < part[1] + part[2]; i++) {
          (function (slot) {
            var item = contents[slot], name = item ? item.name + " × " + item.amount : "Leer", texture = item && icons[item.material];
            var changeable = edit && (slot < 36 || slot > 40);
            cells.push(h("button", { type: "button", class: "inventory-slot" + (selected === slot || target === slot ? " picked" : ""),
              title: "Slot " + slot + " · " + name, "aria-label": "Slot " + slot + " · " + name,
              "aria-pressed": selected === slot || target === slot,
              onClick: function () {
                status.textContent = "Slot " + slot + " · " + name;
                if (!changeable || busy) return;
                if (selected === slot) selected = target = null;
                else if (selected != null) target = slot;
                else if (item) selected = slot;
                draw();
              } },
              texture ? h("img", { src: "data:image/png;base64," + texture, alt: "" }) : item ? h("span", { text: item.name.slice(0, 2).toUpperCase() }) : null,
              item && item.amount > 1 ? h("small", { text: String(item.amount) }) : null));
          })(i);
        }
        grid.appendChild(h("section", { class: "inventory-group" }, h("h3", { text: part[0] }), h("div", { class: "inventory-grid" }, cells)));
      });
      apply.hidden = !edit;
      apply.disabled = selected == null || target == null;
    }
    function packControls(data) {
      S.clear(packArea);
      var input = h("input", { type: "file", class: "input", "aria-label": "Java-Ressourcenpaket" });
      var label = (data.custom ? "Eigenes ZIP + " : "") + data.status;
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
        var changed = !data.unchanged, permissionsChanged = edit !== data.editable || pack !== data.pack;
        if (changed && selected != null) { selected = target = null; status.textContent = "Inventar wurde vom Spieler geändert. Bitte neu auswählen."; }
        else if (changed) status.textContent = data.editable ? "Item antippen, Zielslot wählen und anwenden." : "Nur Ansicht";
        edit = data.editable; pack = data.pack;
        if (!edit) selected = target = null;
        if (changed) {
          contents = {};
          (data.slots || []).forEach(function (item) { contents[item.slot] = item; });
          last = data.revision;
        }
        var needed = Array.from(new Set(Object.values(contents).map(function (item) { return item.material; })));
        if (textureVersion !== data.textures || needed.some(function (material) { return !(material in icons); })) {
          var images = await S.api("invsee/icons", { query: { items: needed.join(",") }, quiet: true, timeout: 10000 });
          if (!alive()) return;
          icons = images.icons || {};
          needed.forEach(function (material) { if (!(material in icons)) icons[material] = ""; });
          textureVersion = data.textures;
          packControls(images);
          changed = true;
        }
        if (changed || permissionsChanged) draw();
      } catch (e) {
        if (!alive()) return;
        status.textContent = e.message;
        last = "";
        contents = {};
        selected = target = null;
        edit = false;
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
