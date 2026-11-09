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
          var target = await S.pickOnlinePlayer("Item weitergeben", ""); if (!target) return;
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
})(window.Simplex);
