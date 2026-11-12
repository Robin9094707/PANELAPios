/* Simplex Panel Web 10.14.0 – Einbehaltungsablage, Automationen und Smart-Neustart. */
(function (S) {
  "use strict";
  var h = S.h, P = S.pages = S.pages || {};
  function fail(e) { S.toast(e.message, "err"); }

  // ------------------------------------------------------------------ Einbehaltungsablage
  var STATUS = { returned: "zurückgegeben an ", given: "gegeben an ", deleted: "gelöscht" };
  P.locker = async function (param, query, alive) {
    if (!S.has("item-locker-v1") || !S.can("data.inventory")) { S.mount(S.lockedPage("Einbehaltungsablage gesperrt", "data.inventory")); return; }
    var icons = {}, area = h("div", { class: "stack" });
    S.title("Einbehaltungsablage", "Einbehaltene Items mit Herkunft und Grund · im Spiel /sas ablage");
    S.mount(area);
    async function act(row, body, done) {
      try { await S.api("locker", { body: Object.assign({ id: row.id, confirmed: true }, body) }); S.toast(done); await load(); }
      catch (e) { fail(e); }
    }
    function details(row, manage) {
      var dlg, note = h("textarea", { class: "input", rows: 2, text: row.note || "" });
      var actions = manage ? [
        row.fromId ? S.btn("An " + row.fromName + " zurückgeben", { kind: "primary", icon: "refresh", onClick: function () { dlg.close(); return act(row, { operation: "give", target: row.fromId }, "Zurückgegeben."); } }) : null,
        S.btn("An Spieler geben …", { icon: "users", onClick: async function () {
          var target = await (S.pickPlayer || S.pickOnlinePlayer)("Item weitergeben", ""); if (!target) return;
          dlg.close(); await act(row, { operation: "give", target: target.id }, "An " + target.name + " gegeben.");
        } }),
        S.btn("Notiz speichern", { onClick: function () { return act(row, { operation: "note", note: note.value }, "Notiz gespeichert."); } }),
        S.btn("Löschen", { kind: "danger", icon: "trash", onClick: async function () {
          if (!await S.confirm("Item endgültig löschen?", "Der Eintrag bleibt 30 Tage im Verlauf sichtbar.", "Löschen", true)) return;
          dlg.close(); await act(row, { operation: "delete" }, "Gelöscht.");
        } })].filter(Boolean) : [];
      dlg = S.modal(row.amount + "× " + row.name, h("div", { class: "stack" },
        h("div", { class: "row" }, S.mcSlot(row, icons[row.material], { tag: "div" }), h("code", { class: "small", text: row.material })),
        S.kv([["Herkunft", row.fromName], ["Grund", row.reason || "–"], ["Eingelagert von", row.by], ["Zeitpunkt", S.date(row.at) + " · " + S.ago(row.at)]]),
        manage ? S.field("Teamnotiz", note) : (row.note ? h("p", { text: row.note }) : null)), actions, { icon: "box" });
    }
    async function load() {
      var data = await S.api("locker"); if (!alive()) return;
      var all = (data.items || []).concat(data.history || []);
      await S.mcIcons(Array.from(new Set(all.map(function (r) { return r.material; }))), icons); if (!alive()) return;
      var held = data.items || [], cells = [];
      for (var i = 0; i < Math.max(27, Math.ceil(held.length / 9) * 9); i++) {
        (function (row) {
          cells.push(row ? S.mcSlot(row, icons[row.material], { title: row.amount + "× " + row.name + " · von " + row.fromName, onClick: function () { details(row, data.manage); } })
            : S.mcSlot(null, null, { tag: "div", empty: true }));
        })(held[i]);
      }
      S.clear(area);
      area.append(h("section", { class: "mc-panel" }, h("h3", { text: "Einbehaltungsablage · " + held.length }), h("div", { class: "mc-grid" }, cells)));
      if (!held.length) area.append(S.notice("Die Ablage ist leer. Im Live-Inventar eines Spielers ein Item auswählen und „In Ablage“ wählen."));
      if ((data.history || []).length) area.append(S.card("Verlauf", { icon: "clock", cls: "pad-list" }, h("div", { class: "list" }, data.history.slice(0, 40).map(function (row) {
        return h("div", { class: "item" }, S.mcSlot(row, icons[row.material], { tag: "div" }), h("div", { class: "meta" }, h("b", { text: row.amount + "× " + row.name }),
          h("div", { class: "small muted", text: "Von " + row.fromName + " · " + (STATUS[row.status] || "") + (row.status === "deleted" ? "" : row.toName) + " · " + row.resolvedBy + " · " + S.ago(row.resolvedAt) })));
      }))));
    }
    await load();
    if (alive()) S.every(15000, load);
  };

  // ------------------------------------------------------------------ Automationen
  var TRIGGERS = [["game-day", "Ingame-Tag"], ["daily", "Täglich um"], ["interval", "Alle X Minuten"], ["players-above", "Ab X Spielern online"], ["players-below", "Bis X Spieler online"], ["first-join", "Erstbeitritt"]];
  var ACTIONS = [["broadcast", "Nachricht im Chat"], ["notify", "Team benachrichtigen"], ["event", "Event-Status setzen"], ["lockdown", "Lockdown-Stufe"], ["command", "Konsolenbefehl"], ["restart", "Neustart mit Countdown"]];
  function label(list, key) { var hit = list.find(function (x) { return x[0] === key; }); return hit ? hit[1] : key; }
  function triggerText(t) {
    switch (t.type) {
      case "game-day": return "Welt " + t.world + " · ab Tag " + t.day + (t.everyDays > 0 ? " · alle " + t.everyDays + " Tage" : " · einmalig");
      case "daily": return "Täglich um " + t.time + " Uhr";
      case "interval": return "Alle " + t.minutes + " Minuten";
      case "players-above": return "Sobald mindestens " + t.count + " Spieler online sind";
      case "players-below": return "Sobald höchstens " + t.count + " Spieler online sind";
      case "first-join": return "Wenn ein neuer Spieler zum ersten Mal beitritt";
      default: return t.type;
    }
  }
  function select(values, value) { return h("select", { class: "input" }, values.map(function (v) { return h("option", { value: v[0], selected: v[0] === value, text: v[1] }); })); }

  function editor(rule, data, save) {
    rule = JSON.parse(JSON.stringify(rule || { name: "", enabled: true, cooldownMinutes: 30, trigger: { type: "daily", time: "20:00" }, actions: [{ type: "broadcast", value: "", en: "" }] }));
    var name = S.input({ value: rule.name, placeholder: "Name" }), enabled = h("input", { type: "checkbox", checked: rule.enabled !== false });
    var type = select(TRIGGERS, rule.trigger.type), triggerFields = h("div", { class: "grid two" }), actionList = h("div", { class: "stack" });
    var fields = {};
    function num(key, value, min, max) { fields[key] = S.input({ type: "number", value: value, min: min, max: max }); return fields[key]; }
    function drawTrigger() {
      S.clear(triggerFields); fields = {};
      var t = rule.trigger;
      if (type.value === "game-day") {
        fields.world = select((data.worlds || []).map(function (w) { return [w.name, w.name + " · jetzt Tag " + w.day]; }), t.world);
        S.add(triggerFields, [S.field("Welt", fields.world), S.field("Ab Ingame-Tag", num("day", t.day || 1, 0, 100000000)), S.field("Alle X Tage (0 = einmalig)", num("everyDays", t.everyDays || 0, 0, 10000))]);
      } else if (type.value === "daily") { fields.time = S.input({ type: "time", value: t.time || "20:00" }); triggerFields.append(S.field("Uhrzeit (Europe/Berlin)", fields.time)); }
      else if (type.value === "interval") triggerFields.append(S.field("Minuten (mind. 15)", num("minutes", t.minutes || 60, 15, 10080)));
      else if (type.value.indexOf("players-") === 0) S.add(triggerFields, [S.field("Spielerzahl", num("count", t.count || 10, 0, 1000)), S.field("Pause danach (Minuten)", num("cooldown", rule.cooldownMinutes || 30, 1, 1440))]);
      else triggerFields.append(h("p", { class: "small muted", text: "{player} wird durch den Namen des neuen Spielers ersetzt." }));
    }
    function drawActions() {
      S.clear(actionList);
      rule.actions.forEach(function (action, index) {
        var kind = select(ACTIONS.filter(function (a) { return data.commands || ["command", "restart"].indexOf(a[0]) < 0 || a[0] === action.type; }), action.type);
        var value = action.type === "lockdown" ? select([["off", "Aus"], ["known", "Nur bekannte Spieler"], ["one-connection", "Eine Verbindung je IP"], ["closed", "Geschlossen"]], action.value || "off")
          : S.input({ value: action.value || "", placeholder: action.type === "broadcast" ? "Deutsch" : action.type === "restart" ? "Countdown 10–300 Sekunden" : action.type === "command" ? "Befehl ohne /" : "Text" });
        var en = action.type === "broadcast" ? S.input({ value: action.en || "", placeholder: "Englisch (optional)" }) : null;
        kind.addEventListener("change", function () { action.type = kind.value; action.value = kind.value === "lockdown" ? "off" : ""; drawActions(); });
        value.addEventListener("input", function () { action.value = value.value; }); value.addEventListener("change", function () { action.value = value.value; });
        if (en) en.addEventListener("input", function () { action.en = en.value; });
        actionList.append(h("div", { class: "automation-action" }, h("span", { class: "badge", text: "Dann " + (index + 1) }), kind, value, en,
          rule.actions.length > 1 ? S.btn("", { icon: "trash", size: "sm icon", title: "Aktion entfernen", onClick: function () { rule.actions.splice(index, 1); drawActions(); } }) : null));
      });
      if (rule.actions.length < 8) actionList.append(S.btn("Aktion hinzufügen", { size: "sm", icon: "plus", onClick: function () { rule.actions.push({ type: "broadcast", value: "", en: "" }); drawActions(); } }));
    }
    type.addEventListener("change", function () { rule.trigger = { type: type.value }; drawTrigger(); });
    drawTrigger(); drawActions();
    var dlg = S.modal(rule.id ? "Automation bearbeiten" : "Neue Automation", h("div", { class: "stack" },
      S.field("Name", name), h("label", { class: "check" }, enabled, "Aktiv"),
      h("h3", { text: "Wenn …" }), S.field("Auslöser", type), triggerFields,
      h("h3", { text: "… dann" }), actionList,
      h("p", { class: "small muted", text: data.commands ? "Befehle werden geprüft; sas, op, stop und reload sind gesperrt." : "Befehle und Neustarts benötigen das Befehlsrecht des Event-Planers." })),
      [S.btn("Abbrechen", { onClick: function () { dlg.close(); } }), S.btn("Speichern", { kind: "primary", onClick: async function () {
        var t = { type: type.value };
        if (fields.world) t.world = fields.world.value;
        ["day", "everyDays", "minutes", "count"].forEach(function (k) { if (fields[k]) t[k] = Number(fields[k].value); });
        if (fields.time) t.time = fields.time.value;
        var body = { name: name.value.trim(), enabled: enabled.checked, cooldownMinutes: fields.cooldown ? Number(fields.cooldown.value) : rule.cooldownMinutes || 30, trigger: t, actions: rule.actions };
        try { await save(rule.id || "", body); dlg.close(); } catch (e) { fail(e); }
      } })], { icon: "sparkles", wide: true });
  }

  P.automations = async function (param, query, alive) {
    if (!S.has("automations-v1") || !S.can("data.server-events")) { S.mount(S.lockedPage("Automationen gesperrt", "data.server-events")); return; }
    var data = null, area = h("div", { class: "stack" });
    async function post(body) { data = await S.api("automations", { body: Object.assign({ confirmed: true }, body) }); S.toast("Gespeichert."); draw(); }
    function save(id, rule) { return post({ operation: "save", id: id, rule: rule }); }
    var templates = [
      { name: "Abendgruß um 20 Uhr", trigger: { type: "daily", time: "20:00" }, actions: [{ type: "broadcast", value: "Schönen Abend auf Simplex!", en: "Have a nice evening on Simplex!" }] },
      { name: "Alle 7 Ingame-Tage Markttag", trigger: { type: "game-day", day: 1, everyDays: 7 }, actions: [{ type: "event", value: "Markttag" }, { type: "broadcast", value: "Heute ist Markttag!", en: "Today is market day!" }] },
      { name: "Danke bei vollem Server", cooldownMinutes: 120, trigger: { type: "players-above", count: 15 }, actions: [{ type: "broadcast", value: "Danke, dass ihr so zahlreich da seid!", en: "Thanks for playing!" }] },
      { name: "Willkommen für neue Spieler", trigger: { type: "first-join" }, actions: [{ type: "broadcast", value: "Willkommen {player} auf Simplex!", en: "Welcome {player} to Simplex!" }] }];
    function draw() {
      S.clear(area);
      var rows = (data.items || []).map(function (rule) {
        return h("div", { class: "item automation" + (rule.enabled ? "" : " paused") },
          h("span", { class: "automation-icon", "aria-hidden": "true" }, S.icon(rule.trigger.type === "daily" ? "clock" : rule.trigger.type === "first-join" ? "user" : rule.trigger.type.indexOf("players") === 0 ? "users" : "star")),
          h("div", { class: "meta" }, h("b", { text: rule.name + (rule.enabled ? "" : " · pausiert") }),
            h("div", { class: "small muted", text: triggerText(rule.trigger) }),
            h("div", { class: "small", text: (rule.actions || []).map(function (a) { return label(ACTIONS, a.type) + (a.value ? ": " + a.value : ""); }).join(" → ") }),
            h("div", { class: "small muted", text: (rule.runs ? rule.runs + "× ausgeführt" : "Noch nicht ausgeführt") + (rule.lastRun ? " · zuletzt " + S.ago(rule.lastRun) : "") + (rule.lastResult && rule.lastResult !== "OK" ? " · " + rule.lastResult : "") })),
          data.manage ? h("div", { class: "row wrap" },
            S.sw(rule.enabled, function () { return post({ operation: "toggle", id: rule.id }).catch(fail); }),
            S.btn("Bearbeiten", { size: "sm", onClick: function () { editor(rule, data, save); } }),
            S.btn("Jetzt ausführen", { size: "sm", onClick: async function () { if (await S.confirm("Jetzt ausführen?", "Alle Aktionen von „" + rule.name + "“ werden sofort ausgeführt.", "Ausführen")) await post({ operation: "run", id: rule.id }).catch(fail); } }),
            S.btn("", { size: "sm icon", icon: "trash", title: "Löschen", onClick: async function () { if (await S.confirm("Automation löschen?", "„" + rule.name + "“ wird entfernt.", "Löschen", true)) await post({ operation: "delete", id: rule.id }).catch(fail); } })) : null);
      });
      area.append(S.card("Deine Automationen", { icon: "sparkles", cls: "pad-list", sub: "Wenn etwas passiert, erledigt die Suite den Rest – nach Ingame-Tag, Uhrzeit, Intervall, Spielerzahl oder Erstbeitritt." },
        h("div", { class: "list" }, rows.length ? rows : S.empty("sparkles", "Noch keine Automationen", "Lege eine an oder starte mit einer Vorlage."))));
      if (data.manage) area.append(S.card("Vorlagen", { icon: "star" }, h("div", { class: "row wrap" }, templates.map(function (t) {
        return S.btn(t.name, { onClick: function () { var copy = JSON.parse(JSON.stringify(t)); if (copy.trigger.type === "game-day") copy.trigger.world = (data.worlds[0] || {}).name; editor(copy, data, save); } });
      }))));
    }
    S.title("Automationen", "Wenn … dann … für deinen Server", null);
    S.mount(area);
    data = await S.api("automations"); if (!alive()) return;
    if (data.manage) S.title("Automationen", "Wenn … dann … für deinen Server", S.btn("Neue Automation", { kind: "primary", icon: "plus", onClick: function () { editor(null, data, save); } }));
    draw();
  };

  // ------------------------------------------------------------------ Smart-Neustart
  P["smart-restart"] = async function (param, query, alive) {
    if (!S.has("smart-restart-v1") || !S.can("data.operations")) { S.mount(S.lockedPage("Smart-Neustart gesperrt", "data.operations")); return; }
    var data = await S.api("smart-restart"); if (!alive()) return;
    var st = data.settings, status = h("div", { class: "stack" });
    var enabled = h("input", { type: "checkbox", checked: st.enabled }), abort = h("input", { type: "checkbox", checked: st.abortOnJoin });
    function num(value, min, max, step) { return S.input({ type: "number", value: value, min: min, max: max, step: step || 1 }); }
    var interval = num(st.intervalHours, 1, 168), players = num(st.maxPlayers, 0, 500), stable = num(st.stableMinutes, 0, 60), countdown = num(st.countdownSeconds, 10, 300, 10), force = num(st.forceAfterHours, 0, 336);
    function drawStatus() {
      S.clear(status);
      status.append(h("div", { class: "row wrap" }, h("span", { class: "badge " + (data.settings.enabled ? "ok" : ""), text: data.phase }), h("span", { class: "muted", text: data.online + " Spieler online" })),
        S.kv([["Frühestens", data.dueAt > Date.now() ? S.date(data.dueAt) : "jetzt, sobald die Bedingung passt"], ["Letzter Smart-Neustart", data.lastRestart ? S.ago(data.lastRestart) : "noch keiner"],
          ["Server läuft seit", S.ago(data.serverStart)], ["Countdown aktiv", data.countdown ? "ja" : "nein"]]),
        (data.history || []).length ? h("div", { class: "list" }, data.history.slice(0, 10).map(function (row) { return h("div", { class: "item" }, h("div", { class: "meta" }, h("b", { text: row.text }), h("div", { class: "small muted", text: S.ago(row.at) }))); })) : null);
    }
    drawStatus();
    S.title("Smart-Neustart", "Alle X Stunden, aber nur bei wenigen Spielern");
    S.mount(h("div", { class: "grid two" }, S.card("Status", { icon: "activity" }, status),
      S.card("Regeln", { icon: "clock", sub: "Countdown auf Deutsch und Englisch. Nach einem Serverstart mindestens 30 Minuten Ruhe; nie zwei automatische Neustarts direkt hintereinander." },
        h("div", { class: "stack" }, h("label", { class: "check" }, enabled, "Smart-Neustart aktiv"),
          h("div", { class: "grid two" }, S.field("Höchstens alle (Stunden)", interval), S.field("Nur bei höchstens … Spielern", players),
            S.field("Bedingung stabil (Minuten)", stable), S.field("Countdown (Sekunden)", countdown), S.field("Spätestens erzwingen nach (Stunden, 0 = nie)", force)),
          h("label", { class: "check" }, abort, "Abbrechen, wenn während des Countdowns Spieler dazukommen"),
          data.manage ? S.btn("Speichern", { kind: "primary", onClick: async function () {
            try {
              data = await S.api("smart-restart", { body: { confirmed: true, enabled: enabled.checked, abortOnJoin: abort.checked, intervalHours: Number(interval.value), maxPlayers: Number(players.value),
                stableMinutes: Number(stable.value), countdownSeconds: Number(countdown.value), forceAfterHours: Number(force.value) } });
              S.toast("Smart-Neustart gespeichert."); drawStatus();
            } catch (e) { fail(e); }
          } }) : S.notice("Ändern benötigt das Recht action.restart.apply.")))));
    S.every(15000, async function () { try { data = await S.api("smart-restart", { quiet: true }); if (alive()) drawStatus(); } catch (e) { /* keep last state */ } });
  };

  // ------------------------------------------------------------------ 10.15.0 Schnellzentrale (same tiles as the app)
  var QUICK = [["tool", "Werkzeug öffnen", "grid"], ["restart", "Neustart mit Countdown", "refresh"], ["restart-cancel", "Neustart abbrechen", "x"],
    ["smart-restart", "Smart-Neustart an/aus", "clock"], ["maintenance-on", "Wartung starten", "settings"], ["maintenance-off", "Wartung beenden", "check"],
    ["chat-on", "Chat einschalten", "chat"], ["chat-off", "Chat ausschalten", "chat"], ["slowmode", "Chat-Slowmode", "clock"], ["announce", "Ankündigung senden", "send"],
    ["save-worlds", "Welten speichern", "download"], ["automation", "Automation ausführen", "star"]];
  var QUICK_VALUE = { restart: "Countdown in Sekunden (5–300)", slowmode: "Sekunden zwischen Nachrichten (0–120)", announce: "Nachricht", "maintenance-on": "Grund (optional)" };
  var QUICK_COLOR = { restart: "E5484D", "maintenance-on": "E5484D", "chat-off": "E5484D", "restart-cancel": "30A46C", "maintenance-off": "30A46C", "chat-on": "30A46C", announce: "8E4EC6", automation: "8E4EC6", "smart-restart": "F76B15", slowmode: "F76B15" };
  var QUICK_SYMBOL = { tool: "square.grid.2x2.fill", restart: "arrow.clockwise.circle.fill", "restart-cancel": "xmark.circle.fill", "smart-restart": "clock.arrow.2.circlepath", "maintenance-on": "wrench.and.screwdriver.fill", "maintenance-off": "checkmark.seal.fill", "chat-on": "bubble.left.and.bubble.right.fill", "chat-off": "bubble.left.and.exclamationmark.bubble.right.fill", slowmode: "tortoise.fill", announce: "megaphone.fill", "save-worlds": "externaldrive.fill", automation: "wand.and.stars" };
  function quickIcon(kind) { var hit = QUICK.find(function (q) { return q[0] === kind; }); return hit ? hit[2] : "star"; }
  async function runQuick(tile) {
    function act(path, values) { return S.runAction(path, values, { title: tile.title }); }
    switch (tile.kind) {
      case "tool": S.go("#/" + (tile.value || "dashboard")); return;
      case "restart": return act("server.restart", { action: "schedule", seconds: String(Math.max(5, Math.min(300, Number(tile.value) || 60))) });
      case "restart-cancel": return act("server.restart", { action: "cancel" });
      case "maintenance-on": return act("server.maintenance", { mode: "on", reason: tile.value || "" });
      case "maintenance-off": return act("server.maintenance", { mode: "off" });
      case "chat-on": return act("server.chat", { mode: "on" });
      case "chat-off": return act("server.chat", { mode: "off" });
      case "slowmode": return act("server.slowmode", { seconds: String(Math.max(0, Math.min(120, Number(tile.value) || 5))) });
      case "announce": if (!tile.value) { S.toast("Für diese Kachel ist keine Nachricht hinterlegt.", "err"); return; } return act("server.announce", { message: tile.value });
      case "save-worlds": return act("server.save", {});
      case "smart-restart": {
        var current = await S.api("smart-restart");
        if (!await S.confirm(tile.title, "Smart-Neustart " + (current.settings.enabled ? "ausschalten?" : "einschalten?"), "Umschalten")) return;
        await S.api("smart-restart", { body: { confirmed: true, enabled: !current.settings.enabled } }); S.toast("Smart-Neustart " + (current.settings.enabled ? "aus" : "an")); return;
      }
      case "automation":
        if (!await S.confirm(tile.title, "Automation jetzt ausführen?", "Ausführen")) return;
        await S.api("automations", { body: { confirmed: true, operation: "run", id: tile.value } }); S.toast("Automation ausgeführt"); return;
    }
  }
  function quickEditor(tiles, onSave) {
    tiles = JSON.parse(JSON.stringify(tiles || []));
    var list = h("div", { class: "list" }), dlg;
    function draw() {
      S.clear(list);
      if (!tiles.length) list.appendChild(S.empty("star", "Noch keine Kacheln", "Füge Neustart, Wartung, Ankündigung oder Werkzeuge hinzu."));
      tiles.forEach(function (tile, index) {
        var title = S.input({ value: tile.title, maxlength: 40, "aria-label": "Titel" }), value = S.input({ value: tile.value || "", placeholder: QUICK_VALUE[tile.kind] || "", "aria-label": "Wert" });
        var kind = h("select", { class: "input" }, QUICK.map(function (q) { return h("option", { value: q[0], selected: q[0] === tile.kind, text: q[1] }); }));
        var color = h("input", { type: "color", value: "#" + (tile.color || "0091FF"), "aria-label": "Farbe", style: { width: "44px", padding: 0 } });
        title.addEventListener("input", function () { tile.title = title.value; });
        value.addEventListener("input", function () { tile.value = value.value; });
        color.addEventListener("input", function () { tile.color = color.value.slice(1).toUpperCase(); });
        kind.addEventListener("change", function () { tile.kind = kind.value; tile.title = QUICK.find(function (q) { return q[0] === kind.value; })[1]; tile.symbol = QUICK_SYMBOL[kind.value] || ""; tile.color = QUICK_COLOR[kind.value] || "0091FF"; tile.value = kind.value === "restart" ? "60" : ""; draw(); });
        list.appendChild(h("div", { class: "item quick-edit" }, h("span", { class: "quick-dot", style: { background: "#" + (tile.color || "0091FF") } }, S.icon(quickIcon(tile.kind))),
          h("div", { class: "grid two grow" }, kind, title, QUICK_VALUE[tile.kind] || tile.kind === "tool" || tile.kind === "automation" ? value : h("span"), color),
          h("div", { class: "row" },
            S.btn("", { size: "sm icon", icon: "chevLeft", title: "Nach vorne", disabled: index === 0, onClick: function () { tiles.splice(index - 1, 0, tiles.splice(index, 1)[0]); draw(); } }),
            S.btn("", { size: "sm icon", icon: "chevRight", title: "Nach hinten", disabled: index === tiles.length - 1, onClick: function () { tiles.splice(index + 1, 0, tiles.splice(index, 1)[0]); draw(); } }),
            S.btn("", { size: "sm icon", icon: "trash", title: "Entfernen", onClick: function () { tiles.splice(index, 1); draw(); } }))));
      });
    }
    draw();
    dlg = S.modal("Schnellzentrale bearbeiten", h("div", { class: "stack" }, h("p", { class: "small muted", text: "Bei „Werkzeug öffnen“ als Wert die Seite eintragen (z. B. players, automations, smart-restart). Bei „Automation ausführen“ die Automations-ID. Die Kacheln erscheinen auch in der App." }), list,
      h("div", { class: "row wrap" }, S.btn("Kachel hinzufügen", { icon: "plus", disabled: tiles.length >= 24, onClick: function () { tiles.push({ id: (crypto.randomUUID ? crypto.randomUUID() : String(Date.now())), kind: "restart", title: "Neustart mit Countdown", symbol: QUICK_SYMBOL.restart, color: QUICK_COLOR.restart, value: "60" }); draw(); } }),
        !tiles.length ? S.btn("Empfohlene übernehmen", { onClick: function () { tiles = ["restart", "restart-cancel", "smart-restart", "maintenance-on", "maintenance-off", "chat-off", "chat-on", "save-worlds"].map(function (k, i) { return { id: "tile-" + Date.now() + "-" + i, kind: k, title: QUICK.find(function (q) { return q[0] === k; })[1], symbol: QUICK_SYMBOL[k], color: QUICK_COLOR[k] || "0091FF", value: k === "restart" ? "60" : "" }; }); draw(); } }) : null)),
      [S.btn("Abbrechen", { onClick: function () { dlg.close(); } }), S.btn("Speichern", { kind: "primary", onClick: async function () {
        try { await onSave(tiles.map(function (t) { return { id: t.id, kind: t.kind, title: (t.title || "").trim().slice(0, 40) || "Kachel", symbol: t.symbol || QUICK_SYMBOL[t.kind] || "", color: t.color || "", value: t.value || "" }; })); dlg.close(); }
        catch (e) { fail(e); }
      } })], { icon: "star", wide: true });
  }
  var baseDashboard = P.dashboard;
  P.dashboard = async function (param, query, alive) {
    await baseDashboard(param, query, alive); if (!alive() || !S.has("quick-center-v1")) return;
    var prefs = {};
    try { prefs = await S.api("shortcut-prefs", { quiet: true }); } catch (e) { return; }
    if (!alive()) return;
    var card;
    function build() {
      var tiles = Array.isArray(prefs.actions) ? prefs.actions : [];
      card = S.card("Schnellzentrale", { icon: "star", sub: tiles.length ? "Antippen führt die Aktion nach Bestätigung aus" : "Neustart, Wartung, Ankündigung & Werkzeuge als Kachel" },
        h("div", { class: "quick-grid" }, tiles.map(function (tile) {
          return h("button", { type: "button", class: "quick-tile", style: "--tile:#" + (/^[0-9a-fA-F]{6}$/.test(tile.color || "") ? tile.color : "0091FF"), onClick: function () { runQuick(tile).catch(fail); } }, S.icon(quickIcon(tile.kind)), h("span", { text: tile.title }));
        }), h("button", { type: "button", class: "quick-tile add", onClick: function () {
          quickEditor(tiles, async function (next) { prefs = await S.api("shortcut-prefs", { body: { actions: next } }); S.toast("Schnellzentrale gespeichert"); var old = card; build(); old.replaceWith(card); });
        } }, S.icon("plus"), h("span", { text: tiles.length ? "Bearbeiten" : "Einrichten" }))));
      card.classList.add("quick-center");
    }
    build();
    function add() { if (alive() && S.content && !S.content.querySelector(".quick-center")) { var anchor = S.content.querySelector(".workspace-shortcuts"); if (anchor) anchor.after(card); else S.content.prepend(card); } }
    add(); var observer = new MutationObserver(add); observer.observe(S.content, { childList: true });
    var previousLeave = S.onLeave; S.onLeave = function () { observer.disconnect(); if (previousLeave) previousLeave(); };
  };

  // ------------------------------------------------------------------ 10.17.0 Chat-Ruhe
  var SILENCE_PRESETS = [[30, "30 Minuten"], [180, "3 Stunden"], [1440, "24 Stunden"], [0, "Dauerhaft"]];
  function silenceUntil(row) {
    if (!row.active) return "Chat sichtbar";
    if (!row.until) return "dauerhaft – bis der Spieler ihn einschaltet";
    var left = Math.max(1, Math.round((row.until - Date.now()) / 60000)), hours = Math.floor(left / 60), mins = left % 60;
    return "noch " + (hours ? hours + " Std." + (mins ? " " + mins + " Min." : "") : mins + " Min.") + " · bis " + S.date(row.until);
  }
  function silenceBy(row) {
    if (!row.by) return "";
    if (row.by === "self") return "vom Spieler selbst";
    if (row.by === "expired") return "automatisch abgelaufen";
    return "vom Team (" + String(row.by).replace(/^staff:/, "").replace(/^Simplex Panel\//, "") + ")";
  }
  P["chat-silence"] = async function (param, query, alive) {
    if (!S.has("chat-silence-v1") || !S.can("data.chat-silence")) { S.mount(S.lockedPage("Chat-Ruhe gesperrt", "data.chat-silence")); return; }
    var data, search = "", filter = "active", area = h("div", { class: "stack" }), list = h("div", { class: "list" }), kpis = h("div", { class: "grid kpis" });
    function kpi(label, value, ic, hint) { return h("section", { class: "card kpi" }, h("div", { class: "ico" }, S.icon(ic)), h("div", { class: "label", text: label }), h("div", { class: "value", text: String(value) }), hint ? h("div", { class: "hint small muted", text: hint }) : null); }
    async function send(body, done) {
      try { data = await S.api("chat-silence", { body: Object.assign({ confirmed: true }, body) }); S.toast(data.notice || done); paint(); }
      catch (e) { fail(e); }
    }
    function editor(target, row) {
      var minutes = 180, whispers = row ? !!row.whispers : false, mentions = row ? !!row.mentions : false, notify = true, dlg;
      var presets = S.segmented(SILENCE_PRESETS.map(function (p) { return [String(p[0]), p[1]]; }), String(minutes), function (v) { minutes = Number(v); });
      var body = h("div", { class: "stack" },
        h("p", { class: "muted", text: "Für Spieler, die nicht wissen, wie es geht. " + target.name + " sieht danach keine Chatnachrichten und keine Chat-Töne mehr – Erwähnungen mit vollem Namen und Flüsternachrichten kommen an, solange sie nicht ebenfalls stummgeschaltet sind." }),
        S.field("Dauer", presets),
        h("label", { class: "check" }, S.sw(whispers, function (v) { whispers = v; }), h("span", { text: "Flüsternachrichten ebenfalls stummschalten" })),
        h("label", { class: "check" }, S.sw(mentions, function (v) { mentions = v; }), h("span", { text: "Erwähnungen ebenfalls stummschalten" })),
        h("label", { class: "check" }, S.sw(notify, function (v) { notify = v; }), h("span", { text: "Spieler im Spiel informieren (Deutsch/Englisch nach Sprache)" })));
      dlg = S.modal("Chat-Ruhe für " + target.name, body, [
        S.btn("Abbrechen", { onClick: function () { dlg.close(); } }),
        S.btn("Stummschalten", { kind: "primary", icon: "bellOff", onClick: async function () { dlg.close(); await send({ operation: "mute", player: target.id, minutes: minutes, whispers: whispers, mentions: mentions, notify: notify }, "Gespeichert."); } })], { icon: "bellOff" });
    }
    function options(row) {
      var whispers = !!row.whispers, mentions = !!row.mentions, dlg;
      dlg = S.modal("Optionen für " + row.name, h("div", { class: "stack" },
        h("p", { class: "muted", text: "Gilt nur während einer Chat-Ruhe." }),
        h("label", { class: "check" }, S.sw(whispers, function (v) { whispers = v; }), h("span", { text: "Flüsternachrichten stummschalten" })),
        h("label", { class: "check" }, S.sw(mentions, function (v) { mentions = v; }), h("span", { text: "Erwähnungen stummschalten" }))), [
        S.btn("Abbrechen", { onClick: function () { dlg.close(); } }),
        S.btn("Speichern", { kind: "primary", onClick: async function () { dlg.close(); await send({ operation: "options", player: row.player, whispers: whispers, mentions: mentions }, "Gespeichert."); } })], { icon: "settings" });
    }
    function paint() {
      if (!alive()) return;
      S.clear(kpis); S.clear(list);
      var rows = (data.items || []).filter(function (r) { return filter === "all" || (filter === "active" ? r.active : !r.active); })
        .filter(function (r) { return !search || String(r.name).toLowerCase().indexOf(search.toLowerCase()) >= 0; });
      kpis.append(kpi("Chat stummgeschaltet", data.active, "bellOff", "Spieler mit aktiver Chat-Ruhe"),
        kpi("Davon online", (data.items || []).filter(function (r) { return r.active && r.online; }).length, "users"),
        kpi("Spieler online", data.online, "activity"),
        kpi("Plugin-Filter", data.packetFilter === "idle" ? "bereit" : data.packetFilter, "shieldCheck", "Kanäle mit aktivem Nachrichtenfilter"));
      rows.forEach(function (r) {
        var badges = h("div", { class: "row wrap", style: { gap: "6px" } },
          S.badge(r.active ? "Chat stumm" : "Chat sichtbar", r.active ? "warn" : "ok", r.active ? "bellOff" : "bell"),
          r.online ? S.badge(r.bedrock ? "Online · Bedrock" : "Online · Java", "info") : S.badge("Offline", ""),
          r.whispers ? S.badge("Flüstern stumm", "") : null, r.mentions ? S.badge("Erwähnungen stumm", "") : null);
        var actions = data.manage ? h("div", { class: "row wrap", style: { gap: "6px" } },
          r.active ? S.btn("Aufheben", { kind: "primary", icon: "bell", size: "sm", onClick: async function () {
            if (!await S.confirm("Chat für " + r.name + " wieder einschalten?", "Der Spieler sieht danach wieder alle Chatnachrichten und wird im Spiel informiert.", "Einschalten")) return;
            await send({ operation: "unmute", player: r.player }, "Aufgehoben.");
          } }) : S.btn("Stummschalten", { icon: "bellOff", size: "sm", onClick: function () { editor({ id: r.player, name: r.name }, r); } }),
          S.btn("Optionen", { size: "sm", icon: "settings", onClick: function () { options(r); } })) : null;
        list.append(h("div", { class: "item" }, S.avatar(r.name), h("div", { class: "meta grow" }, h("b", { text: r.name }),
          h("div", { class: "small muted", text: silenceUntil(r) + (silenceBy(r) ? " · " + silenceBy(r) : "") + (r.since ? " · " + S.ago(r.since) : "") }), badges), actions));
      });
      if (!rows.length) list.append(S.empty("bell", filter === "active" ? "Niemand hat den Chat stummgeschaltet" : "Keine Einträge", "Spieler nutzen im Spiel /chat – oder du hilfst ihnen hier."));
    }
    S.title("Chat-Ruhe", "Spieler, die den Chat für sich stummgeschaltet haben · im Spiel /chat",
      S.can("chat-silence.manage") ? S.btn("Spieler stummschalten", { kind: "primary", icon: "bellOff", onClick: async function () {
        var target = await (S.pickPlayer || S.pickOnlinePlayer)("Chat-Ruhe setzen", ""); if (!target) return;
        editor(target, (data.items || []).find(function (r) { return r.player === target.id; }));
      } }) : null);
    area.append(kpis,
      S.card("Spieler", { icon: "bellOff", cls: "pad-list", sub: "Aktive Chat-Ruhen und gespeicherte Optionen. Spieler verwalten das selbst mit /chat (Java: Dialog oder Menü, Bedrock: natives Formular)." },
        h("div", { class: "stack" }, h("div", { class: "row wrap" }, S.searchBox("Spieler suchen …", function (q) { search = q; paint(); }, search),
          S.segmented([["active", "Aktiv"], ["inactive", "Nur Optionen"], ["all", "Alle"]], filter, function (v) { filter = v; paint(); })), list)),
      S.card("So funktioniert es", { icon: "info" }, h("div", { class: "stack small" },
        h("p", { text: "Während der Chat-Ruhe erhält der Spieler keine Chatnachrichten anderer Spieler und Plugins und keine Chat-Töne. Action-Bar, Titel und Antworten auf eigene Befehle bleiben sichtbar." }),
        h("p", { text: "Erwähnungen mit vollem Namen kommen an (der Spieler kann sie per Klick bzw. /chat erwähnungen aus stummschalten). Schreibt der Spieler selbst in den Chat, erscheint ein Hinweis mit dem Weg zurück." }),
        h("p", { text: "Wer einen stummgeschalteten Spieler erwähnt – auch mit mindestens sechs Buchstaben des Namens –, erhält einen Hinweis. Flüsternachrichten werden abgelehnt, wenn der Spieler sie stummgeschaltet hat." }))));
    S.mount(area);
    data = await S.api("chat-silence"); if (!alive()) return;
    paint();
    S.every(15000, async function () { try { data = await S.api("chat-silence", { quiet: true }); paint(); } catch (e) { /* keep last state */ } });
  };
})(window.Simplex);
