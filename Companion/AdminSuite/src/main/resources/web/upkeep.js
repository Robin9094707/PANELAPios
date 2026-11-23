/* Simplex Panel Web – 10.23.0: Paper-Server-Updater and Smart Anti-Lag. */
(function (S) {
  "use strict";
  var h = S.h, P = S.pages = S.pages || {};
  function fail(e) { S.toast(e && e.message ? e.message : String(e), "err"); }
  function kpi(label, value, ic, hint, kind) {
    return h("section", { class: "card kpi" + (kind ? " " + kind : "") }, h("div", { class: "ico" }, S.icon(ic)), h("div", { class: "label", text: label }),
      h("div", { class: "value", text: String(value) }), hint ? h("div", { class: "hint small muted", text: hint }) : null);
  }
  function mb(bytes) { return bytes ? (bytes / 1048576).toFixed(1).replace(".", ",") + " MB" : "–"; }
  function when(ms) { return ms ? new Date(ms).toLocaleString("de-DE", { dateStyle: "medium", timeStyle: "short" }) : "–"; }

  // ------------------------------------------------------------------ Paper-Server-Updater
  P["server-updater"] = async function (param, query, alive) {
    if (!S.has("server-updater-v1") || !S.can("data.server-updater")) { S.mount(S.lockedPage("Server-Updater gesperrt", "data.server-updater")); return; }
    var data, version = "", area = h("div", { class: "stack" }), kpis = h("div", { class: "grid kpis" }), jobBox = h("div"), list = h("div", { class: "list" }), versions = h("div"), history = h("div", { class: "list" }), polling = null;
    async function load(refresh) {
      data = await S.api("server-updater", { query: { version: version, refresh: refresh ? "true" : "false" }, quiet: !refresh });
      if (alive()) paint();
    }
    async function post(body, done) {
      try { var r = await S.api("server-updater", { body: Object.assign({ confirmed: true }, body) }); S.toast((r && r.notice) || done); return r; }
      catch (e) { fail(e); return null; }
    }
    function watchJob() {
      if (polling) return;
      polling = setInterval(async function () {
        if (!alive()) { clearInterval(polling); polling = null; return; }
        try {
          var r = await S.api("server-updater", { query: { job: "true" }, quiet: true });
          data.job = r.job; paintJob();
          if (!r.job.running) { clearInterval(polling); polling = null; await load(false); }
        } catch (e) { /* keep polling */ }
      }, 900);
    }
    function paintJob() {
      S.clear(jobBox);
      var j = data.job || {};
      if (!j.phase || j.phase === "idle") return;
      var steps = [["download", "Download"], ["verify", "Prüfen"], ["backup", "Sichern"], ["replace", "Ersetzen"], ["done", "Fertig"]];
      var index = steps.findIndex(function (s) { return s[0] === j.phase; });
      var track = h("div", { class: "steps" }, steps.map(function (s, i) {
        var state = j.phase === "failed" ? (i === 0 ? "bad" : "") : i < index ? "done" : i === index ? "on" : "";
        return h("div", { class: "step " + state }, h("i", { text: i < index || j.phase === "done" ? "✓" : String(i + 1) }), h("span", { text: s[1] }));
      }));
      var body = h("div", { class: "stack" }, track,
        j.running ? h("div", { class: "stack" }, S.bar(Math.round((j.progress || 0) * 100), 100, 101), h("div", { class: "small muted", text: (j.message || "") + (j.total ? " · " + mb(j.bytes) + " von " + mb(j.total) : "") })) : null,
        j.phase === "done" ? S.notice(j.message, "ok") : null,
        j.phase === "failed" ? S.notice(j.error || "Fehlgeschlagen", "danger") : null);
      if (j.phase === "failed" && j.guide && j.guide.length) {
        body.append(h("div", { class: "card-sub", text: "So aktualisierst du selbst – Schritt für Schritt:" }),
          h("ol", { class: "guide" }, j.guide.map(function (g) { return h("li", { text: g }); })));
      }
      if (j.phase === "done" && data.manage) body.append(h("div", { class: "row wrap" },
        S.btn("Jetzt mit Countdown neu starten", { kind: "primary", icon: "refresh", onClick: async function () {
          if (!await S.confirm("Server neu starten?", "Spieler erhalten einen 60-Sekunden-Countdown. Danach startet der Server mit Build " + j.build + ".", "Neustart planen")) return;
          await post({ operation: "restart", seconds: 60 }, "Neustart geplant.");
        } }), h("span", { class: "small muted", text: "Oder später beim nächsten regulären Neustart." })));
      jobBox.append(S.card(j.phase === "failed" ? "Update nicht möglich" : j.phase === "done" ? "Update installiert" : "Update läuft", { icon: j.phase === "failed" ? "alert" : "download" }, body));
    }
    function buildRow(b) {
      var commits = (b.commits || []);
      var details = h("details", { class: "changes" }, h("summary", { text: commits.length ? commits.length + (commits.length === 1 ? " Änderung" : " Änderungen") : "Keine Beschreibung" }),
        h("ul", {}, commits.map(function (c) { return h("li", {}, h("code", { text: String(c.sha || "").slice(0, 7) }), " ", h("span", { text: c.summary })); })));
      var badges = h("div", { class: "row wrap", style: { gap: "6px" } },
        b.installed ? S.badge("Installiert", "ok", "check") : b.newer ? S.badge("Neuer", "info", "sparkles") : S.badge("Älter", ""),
        b.channel && b.channel !== "STABLE" ? S.badge(b.channel === "BETA" ? "Beta" : b.channel === "ALPHA" ? "Alpha" : b.channel, "warn") : null);
      var action = b.installable ? S.btn(b.newer ? "Aktualisieren" : "Zurück auf diesen Build", { kind: b.newer ? "primary" : "", size: "sm", icon: "download", onClick: async function () {
        var text = "Build " + b.id + " wird von PaperMC geladen, mit der offiziellen Prüfsumme verglichen und die laufende " + (data.installed.jar || "server.jar") +
          " vorsichtig ersetzt. Eine Sicherung bleibt erhalten. Aktiv nach dem nächsten Neustart." + (b.newer ? "" : "\n\nAchtung: Dies ist ein älterer Build (Rollback).");
        if (!await S.confirm("Paper " + data.version + " Build " + b.id + " installieren?", text, "Installieren")) return;
        var r = await post({ operation: "install", version: data.version, build: b.id }, "Update gestartet.");
        if (r && r.job) { data.job = r.job; paintJob(); watchJob(); }
      } }) : null;
      return h("div", { class: "item" + (b.installed ? " current" : "") }, h("div", { class: "ico-tile" }, S.icon(b.installed ? "check" : "download")),
        h("div", { class: "meta grow" }, h("b", { text: "Build " + b.id }), h("div", { class: "small muted", text: when(b.time) + " · " + mb(b.size) }), badges, details), action);
    }
    function paint() {
      S.clear(kpis); S.clear(list); S.clear(versions); S.clear(history);
      var i = data.installed || {};
      var newest = (data.builds || [])[0];
      kpis.append(kpi("Installiert", (i.minecraft || "?") + " · Build " + (i.build >= 0 ? i.build : "?"), "server", i.brand || "Paper"),
        kpi("Neueste Version", newest ? "Build " + newest.id : "–", "sparkles", newest ? when(newest.time) : ""),
        kpi("Neuere Builds", data.newerBuilds || 0, "download", data.upToDate ? "Aktuell" : "Update verfügbar", data.upToDate ? "ok" : "warn"),
        kpi("Java", i.java + (data.javaMinimum ? " (mind. " + data.javaMinimum + ")" : ""), "cpu", data.javaCompatible === false ? "Java zu alt" : "passt"));
      if (data.error) list.append(S.notice(data.error, "warn"));
      if (i.jarProblem) list.append(S.notice(i.jarProblem + " Automatische Updates sind deshalb aus – die Anleitung erscheint nach einem Versuch, du kannst aber jederzeit manuell aktualisieren.", "warn"));
      (data.builds || []).forEach(function (b) { list.append(buildRow(b)); });
      if (!(data.builds || []).length && !data.error) list.append(S.empty("server", "Keine Builds gefunden", "Für diese Version liefert PaperMC keine stabilen Builds."));
      var opts = (data.versions || []).map(function (v) { return [v.id, v.id + (v.installed ? " · installiert" : v.newer ? " · neuer" : "")]; });
      if (opts.length > 1) versions.append(S.field("Minecraft-Version", S.segmented(opts.filter(function (o, idx) { return data.versions[idx].selectable || data.versions[idx].installed; }), data.version, function (v) { version = v; load(false); }),
        data.policy && data.policy.allowMinecraftUpgrade ? "Versionswechsel erlaubt. Plugins vorher auf Kompatibilität prüfen!" : "Standard: nur Builds deiner Minecraft-Version. Versionswechsel unten in den Einstellungen freischalten."));
      (data.history || []).forEach(function (r) {
        history.append(h("div", { class: "item" }, h("div", { class: "ico-tile" }, S.icon(r.ok ? "check" : "alert")), h("div", { class: "meta grow" },
          h("b", { text: r.from + " → " + r.to }), h("div", { class: "small muted", text: when(r.time) + " · " + (r.actor || "") + (r.error ? " · " + r.error : "") }))));
      });
      if (!(data.history || []).length) history.append(h("div", { class: "small muted", text: "Noch keine Updates über die Suite." }));
      paintJob();
      if (data.job && data.job.running) watchJob();
    }
    function settings() {
      var p = data.policy || {}, upgrade = !!p.allowMinecraftUpgrade, exp = !!p.includeExperimental, keep = p.keepBackups || 3, dlg;
      var keepInput = S.input({ type: "number", min: 1, max: 10, value: String(keep) });
      dlg = S.modal("Server-Updater einstellen", h("div", { class: "stack" },
        h("label", { class: "check" }, S.sw(upgrade, function (v) { upgrade = v; }), h("span", { text: "Minecraft-Versionswechsel erlauben (z. B. 26.2 → 26.3)" })),
        h("p", { class: "small muted", text: "Große Versionssprünge können Welten und Plugins betreffen. Vorher Backup und Plugin-Kompatibilität prüfen." }),
        h("label", { class: "check" }, S.sw(exp, function (v) { exp = v; }), h("span", { text: "Vorabversionen (Beta/Alpha) anzeigen" })),
        S.field("Gesicherte alte Server-JARs", keepInput)), [
        S.btn("Abbrechen", { onClick: function () { dlg.close(); } }),
        S.btn("Speichern", { kind: "primary", onClick: async function () { dlg.close(); await post({ operation: "settings", allowMinecraftUpgrade: upgrade, includeExperimental: exp, keepBackups: Number(keepInput.value) || 3 }, "Gespeichert."); await load(true); } })], { icon: "settings" });
    }
    S.title("Server-Updater", "Paper-Builds sehen, Änderungen lesen und sicher aktualisieren", h("div", { class: "row" },
      S.btn("Prüfen", { icon: "refresh", onClick: function () { load(true).catch(fail); } }),
      S.can("server-updater.manage") ? S.btn("Einstellungen", { icon: "settings", onClick: settings }) : null));
    area.append(kpis, jobBox, versions,
      S.card("Builds", { icon: "server", cls: "pad-list", sub: "Neueste zuerst. Änderungen stammen direkt von PaperMC. Installiert wird nur auf deinen Klick: Download → Prüfsumme → Sicherung → vorsichtiger Austausch der Server-JAR → aktiv nach Neustart." }, list),
      S.card("Verlauf", { icon: "clock", cls: "pad-list" }, history));
    S.mount(area);
    try { await load(false); } catch (e) { S.mount(S.errorView(e, function () { P["server-updater"](param, query, alive); })); }
  };

  // ------------------------------------------------------------------ Smart Anti-Lag
  var KIND = { mobs: ["Mob-Ansammlung", "users"], items: ["Item-Haufen", "box"], machine: ["Lag-Maschine", "cpu"] };
  var STATUS = { watch: ["Beobachtet", ""], high: ["Sehr hoch", "warn"], warned: ["Spieler informiert", "info"], limited: ["Spawnbremse aktiv", "warn"], throttled: ["KI gedrosselt", "danger"] };
  var MODES = [["off", "Aus"], ["observe", "Nur erkennen"], ["notify", "Erkennen + informieren"], ["smart", "Smart (empfohlen)"]];
  P["anti-lag"] = async function (param, query, alive) {
    if (!S.has("smart-anti-lag-v1") || !S.can("data.anti-lag")) { S.mount(S.lockedPage("Anti-Lag gesperrt", "data.anti-lag")); return; }
    var data, area = h("div", { class: "stack" }), kpis = h("div", { class: "grid kpis" }), spots = h("div", { class: "list" }), players = h("div", { class: "list" }), log = h("div", { class: "list" }), chart = h("div", { class: "chart-row" });
    async function send(body, done) {
      try { data = await S.api("anti-lag", { body: Object.assign({ confirmed: true }, body) }); S.toast(data.notice || done); paint(); }
      catch (e) { fail(e); }
    }
    async function pickStaff() {
      var staff = data.staffOnline || [];
      if (!staff.length) { S.toast("Kein Teammitglied mit Admin-Rechten online.", "err", "Melde dich im Spiel an, um dich teleportieren zu lassen."); return null; }
      if (staff.length === 1) return staff[0].name;
      return new Promise(function (resolve) {
        var dlg = S.modal("Wer soll teleportiert werden?", h("div", { class: "list" }, staff.map(function (s) {
          return h("button", { class: "item", type: "button", onclick: function () { dlg.close(); resolve(s.name); } }, S.avatar(s.name), h("div", { class: "meta grow" }, h("b", { text: s.name })));
        })), [S.btn("Abbrechen", { onClick: function () { dlg.close(); resolve(null); } })], { icon: "users" });
      });
    }
    function spotRow(s) {
      var k = KIND[s.kind] || KIND.mobs, st = STATUS[s.status] || STATUS.watch;
      var facts = [];
      if (s.living) facts.push(s.living + " Mobs" + (s.topCount > 20 ? " (davon " + s.topCount + " " + s.topType + ")" : ""));
      if (s.items) facts.push(s.items + " Items");
      if (s.redstonePerSecond) facts.push(s.redstonePerSecond + " Redstone/s");
      if (s.pistonsPerSecond) facts.push(s.pistonsPerSecond + " Kolben/s");
      if (s.hoppers >= 0) facts.push(s.hoppers + " Trichter");
      var causes = (s.causes || []).map(function (c) { return c.name + " (" + c.placed + " Teile" + (c.online ? ", online" : "") + ")"; });
      var who = causes.length ? "Gebaut/gezüchtet von: " + causes.join(", ") : s.nearby && s.nearby.length ? "In der Nähe: " + s.nearby.join(", ") : "Niemand in der Nähe";
      var actions = data.manage ? h("div", { class: "row wrap", style: { gap: "6px" } },
        S.btn("Teleport", { size: "sm", kind: "primary", icon: "map", onClick: async function () { var who = await pickStaff(); if (who) await send({ operation: "teleport", id: s.id, player: who }, "Teleport läuft."); } }),
        S.btn("Spieler informieren", { size: "sm", icon: "send", onClick: async function () { if (await S.confirm("Spieler informieren?", "Die verantwortlichen bzw. nahen Spieler erhalten einen freundlichen Hinweis im Chat.", "Senden")) await send({ operation: "notify", id: s.id }, "Gesendet."); } }),
        s.status === "limited" || s.status === "throttled" ? S.btn("Maßnahmen aufheben", { size: "sm", icon: "unlock", onClick: function () { send({ operation: "release", id: s.id }, "Aufgehoben."); } })
          : S.btn("Spawnbremse", { size: "sm", icon: "lock", onClick: async function () { if (await S.confirm("Spawnbremse setzen?", "In diesem Chunk entstehen keine neuen Mobs durch Zucht, Spawner oder natürliches Spawnen, bis du sie aufhebst oder der Hotspot verschwindet.", "Setzen")) await send({ operation: "limit", id: s.id }, "Gesetzt."); } }),
        S.btn("Trichter zählen", { size: "sm", icon: "search", onClick: function () { send({ operation: "inspect", id: s.id }, "Gezählt."); } }),
        s.items > 50 ? S.btn("Items entfernen", { size: "sm", kind: "danger", icon: "trash", onClick: async function () { if (await S.confirm("Herumliegende Items entfernen?", s.items + " Items im Chunk " + s.world + " " + s.x + " / " + s.z + " werden gelöscht. Das lässt sich nicht rückgängig machen.", "Entfernen", true)) await send({ operation: "clear-items", id: s.id }, "Entfernt."); } }) : null) : null;
      return h("div", { class: "item" }, h("div", { class: "ico-tile " + st[1] }, S.icon(k[1])),
        h("div", { class: "meta grow" }, h("b", { text: k[0] + " · " + s.world + " " + s.x + " / " + s.z }),
          h("div", { class: "small", text: facts.join(" · ") }),
          h("div", { class: "small muted", text: who + " · seit " + S.ago(s.since) }),
          h("div", { class: "row wrap", style: { gap: "6px" } }, S.badge(st[0], st[1]), S.badge("Last " + s.score, s.over ? "danger" : ""), s.throttledMobs ? S.badge(s.throttledMobs + " Mobs pausiert", "warn") : null)), actions);
    }
    function paint() {
      if (!alive()) return;
      S.clear(kpis); S.clear(spots); S.clear(players); S.clear(log); S.clear(chart);
      var hl = data.health || {};
      kpis.append(kpi("TPS", hl.tps, "activity", hl.stressed ? "Server unter Last" : "flüssig", hl.stressed ? "warn" : "ok"),
        kpi("Tickzeit", hl.mspt + " ms", "clock", "unter 50 ms = flüssig"),
        kpi("Hotspots", (data.hotspots || []).filter(function (s) { return s.over; }).length + " / " + (data.hotspots || []).length, "map", "kritisch / beobachtet"),
        kpi("Maßnahmen", data.limited + " · " + data.throttled, "shieldCheck", "Spawnbremsen · pausierte Mobs"),
        kpi("Entitäten", S.num ? S.num(hl.entities) : hl.entities, "users", (hl.chunks || 0) + " geladene Chunks"));
      var tps = (data.history || []).map(function (r) { return r.tps; }), ms = (data.history || []).map(function (r) { return r.mspt; });
      chart.append(h("div", { class: "card mini" }, h("div", { class: "small muted", text: "TPS (letzte 10 Minuten)" }), S.sparkline(tps)),
        h("div", { class: "card mini" }, h("div", { class: "small muted", text: "Tickzeit ms" }), S.sparkline(ms)));
      (data.hotspots || []).forEach(function (s) { spots.append(spotRow(s)); });
      if (!(data.hotspots || []).length) spots.append(S.empty("shieldCheck", "Keine Hotspots", "Alles im grünen Bereich – Anti-Lag beobachtet weiter."));
      (data.players || []).forEach(function (p, i) {
        players.append(h("div", { class: "item" }, h("div", { class: "rank", text: "#" + (i + 1) }), S.avatar(p.name), h("div", { class: "meta grow" }, h("b", { text: p.name }),
          h("div", { class: "small muted", text: p.hotspots + " Hotspot" + (p.hotspots === 1 ? "" : "s") + " · Last " + p.score + (p.online ? " · online in " + p.world + " " + p.x + " / " + p.z : " · offline") })),
          data.manage && p.online ? S.btn("Teleport", { size: "sm", icon: "map", onClick: async function () { var who = await pickStaff(); if (who) await send({ operation: "teleport-player", player: who, target: p.name }, "Teleport läuft."); } }) : null,
          h("a", { class: "btn sm ghost", href: "#/players/" + p.uuid }, S.icon("user"), "Profil")));
      });
      if (!(data.players || []).length) players.append(h("div", { class: "small muted", text: "Noch keine Verursacher erkannt." }));
      (data.actions || []).forEach(function (a) {
        log.append(h("div", { class: "item" }, h("div", { class: "ico-tile " + (a.kind === "ok" ? "ok" : a.kind === "warn" || a.kind === "throttle" ? "warn" : "") }, S.icon(a.kind === "ok" ? "check" : a.kind === "notice" ? "send" : "activity")),
          h("div", { class: "meta grow" }, h("b", { text: a.title }), h("div", { class: "small muted", text: S.ago(a.time) + " · " + a.text }))));
      });
      if (!(data.actions || []).length) log.append(h("div", { class: "small muted", text: "Noch keine Ereignisse." }));
    }
    function settings() {
      var l = data.limits || {}, mode = data.mode, dlg, fields = {}, notifyStaff = !!l.notifyStaff;
      function num(key, label, hint) { fields[key] = S.input({ type: "number", value: String(l[key]) }); return S.field(label, fields[key], hint); }
      dlg = S.modal("Smart Anti-Lag einstellen", h("div", { class: "stack" },
        S.field("Modus", S.segmented(MODES, mode, function (v) { mode = v; })),
        h("p", { class: "small muted", text: "Smart greift nur ein, wenn der Server wirklich leidet UND ein Hotspot weit über den Grenzen liegt: erst Hinweis an die Spieler, dann Spawnbremse, erst nach 2 Minuten anhaltender Last pausieren überzählige Mobs ihre KI. Nichts wird gelöscht." }),
        num("triggerMspt", "Last ab Tickzeit (ms)", "Normal: unter 50 ms"),
        num("mobsPerChunk", "Mobs je Chunk"), num("sameTypePerChunk", "Gleiche Mobart je Chunk"), num("itemsPerChunk", "Items je Chunk"),
        num("redstonePerSecond", "Redstone-Updates pro Sekunde je Chunk"), num("notifyRadius", "Hinweis-Umkreis (Blöcke)"),
        h("label", { class: "check" }, S.sw(notifyStaff, function (v) { notifyStaff = v; }), h("span", { text: "Teammitglieder im Spiel mit Teleport-Link informieren" }))), [
        S.btn("Abbrechen", { onClick: function () { dlg.close(); } }),
        S.btn("Speichern", { kind: "primary", onClick: async function () {
          var body = { operation: "settings", mode: mode, notifyStaff: notifyStaff };
          Object.keys(fields).forEach(function (k) { var v = Number(fields[k].value); if (isFinite(v) && v > 0) body[k] = v; });
          dlg.close(); await send(body, "Gespeichert.");
        } })], { icon: "settings" });
    }
    S.title("Smart Anti-Lag", "Hotspots, Lag-Maschinen und Verursacher – live", S.can("anti-lag.manage") ? S.btn("Einstellungen", { icon: "settings", onClick: settings }) : null);
    area.append(kpis, chart,
      S.card("Hotspots", { icon: "map", cls: "pad-list", sub: "Nach Last sortiert. „Gebaut von“ zählt platzierte Maschinenteile (Trichter, Kolben, Observer …) und Zucht im Umkreis. Deaktivieren entscheidest du selbst." }, spots),
      h("div", { class: "grid two" },
        S.card("Wer verursacht am meisten Last?", { icon: "users", cls: "pad-list" }, players),
        S.card("Protokoll", { icon: "list", cls: "pad-list" }, log)));
    S.mount(area);
    try { data = await S.api("anti-lag"); } catch (e) { S.mount(S.errorView(e, function () { P["anti-lag"](param, query, alive); })); return; }
    if (!alive()) return;
    paint();
    S.every(5000, async function () { data = await S.api("anti-lag", { quiet: true }); paint(); });
  };
})(window.Simplex);
