/* Simplex Panel Web 10.20.0 – Bann-Zentrale: alle Banns mit Haupt- und Alt-Konten, Freigaben, IP-Fehlbanns, Verlauf und LiteBans-Migration. */
(function (S) {
  "use strict";
  var h = S.h, P = S.pages = S.pages || {};
  function fail(e) { S.toast(e.message, "err"); }
  function until(row) { return row.permanent || !row.until ? "dauerhaft" : "bis " + S.date(row.until); }
  function roleBadge(role) { return role === "alt" ? S.badge("Alt-Konto", "warn", "users") : S.badge("Hauptbann", "danger", "gavel"); }
  function sourceLabel(src) {
    if (src === "litebans") return "aus LiteBans übernommen";
    if (String(src || "").indexOf("ultra") === 0) return "Alt-Erkennung (Ultrabann)";
    return "Simplex Admin Suite";
  }
  function kpi(label, value, ic, tone, hint) {
    return h("section", { class: "card kpi" }, h("div", { class: "ico " + (tone || "") }, S.icon(ic)), h("div", { class: "label", text: label }), h("div", { class: "value", text: String(value) }), hint ? h("div", { class: "hint", text: hint }) : null);
  }

  /** Ask how far a lift goes. Main ban: default "all"; alt account: the admin decides (only this one or all). */
  async function liftDialog(caseRow, member, done) {
    return new Promise(function (resolve) {
      var others = (caseRow.members || []).filter(function (m) { return !member || m.uuid !== member.uuid; });
      var scope = !member || member.role === "main" ? "all" : "single", note = S.input({ placeholder: "Notiz (optional, nur fürs Team)", maxlength: 200 }), dlg;
      var who = member ? member.name : caseRow.mainName;
      var options = h("div", { class: "stack" });
      function option(value, title, text) {
        var radio = h("input", { type: "radio", name: "lift-scope", value: value, checked: scope === value });
        radio.addEventListener("change", function () { scope = value; });
        return h("label", { class: "card", style: { display: "flex", gap: "12px", alignItems: "flex-start", padding: "12px", cursor: "pointer" } }, radio, h("div", {}, h("b", { text: title }), h("p", { class: "muted small", style: { margin: "4px 0 0" }, text: text })));
      }
      if (member) options.appendChild(option("single", "Nur " + member.name + " entsperren", member.role === "alt"
        ? "Das Hauptkonto " + (caseRow.mainName || "") + " und die übrigen Alt-Konten bleiben gesperrt. " + member.name + " wird von der Alt-Erkennung nicht erneut erfasst."
        : "Nur der Hauptbann wird aufgehoben; " + others.length + " verknüpfte Konto/Konten bleiben gesperrt."));
      options.appendChild(option("all", "Alle " + (caseRow.members || []).length + " Konten des Falls entsperren", "Hauptbann, alle Alt-Konten" + ((caseRow.ultraGroups || []).length ? ", Alt-Gruppe" : "") + (caseRow.ipBlocked ? ", IP-Sperre " + caseRow.ip : "") + " und zugehörige Identitätssperren werden aufgehoben."));
      if (others.length) options.appendChild(h("details", {}, h("summary", { class: "small", text: "Betroffene Konten anzeigen" }), h("div", { class: "row wrap", style: { gap: "6px", marginTop: "8px" } }, (caseRow.members || []).map(function (m) { return S.badge(m.name + (m.role === "alt" ? " · Alt" : " · Haupt"), m.role === "alt" ? "warn" : "danger"); }))));
      dlg = S.modal("Bann aufheben – " + who, h("div", { class: "stack" }, h("p", { class: "muted", text: "Fall " + caseRow.id + " · " + (caseRow.reason || "ohne Grund") }), options, S.field("Notiz", note)), [
        S.btn("Abbrechen", { onClick: function () { dlg.close(); resolve(false); } }),
        S.btn("Entsperren", { kind: "primary", icon: "unlock", onClick: async function () {
          try {
            var r = await S.api("ban-center", { body: { operation: "lift", "case": caseRow.id, player: member ? member.uuid : "", scope: scope, note: note.value, confirmed: true } });
            dlg.close(); S.toast(r.title, "ok", r.description); if (done) await done(); resolve(true);
          } catch (e) { fail(e); }
        } })], { icon: "unlock", onClose: function () { resolve(false); } });
    });
  }

  function memberRow(caseRow, m, manage, done) {
    return h("div", { class: "item" }, S.avatar(m.name, "sm"),
      h("div", { class: "meta grow" }, h("div", { class: "row wrap", style: { gap: "6px" } }, S.can("data.profile") ? h("a", { href: "#/player/" + m.uuid, text: m.name }) : h("b", { text: m.name }), roleBadge(m.role),
          m.online ? S.badge("online", "info") : null, m.ultra ? S.badge("Alt-Gruppe", "") : null),
        h("small", { text: [m.via, m.lastSeen ? "zuletzt " + S.ago(m.lastSeen) : ""].filter(Boolean).join(" · ") })),
      manage ? S.btn(m.role === "alt" ? "Entsperren …" : "Aufheben …", { size: "sm", icon: "unlock", onClick: function () { return liftDialog(caseRow, m, done); } }) : null);
  }

  function caseCard(c, manage, done) {
    var open = false, body = h("div", { class: "list", hidden: true }, (c.members || []).map(function (m) { return memberRow(c, m, manage, done); }));
    var toggle = S.btn((c.accounts || 0) + (c.accounts === 1 ? " Konto" : " Konten"), { size: "sm", kind: "ghost", icon: "chevDown", onClick: function () { open = !open; body.hidden = !open; } });
    var badges = h("div", { class: "row wrap", style: { gap: "6px" } }, S.badge(until(c), c.permanent ? "danger" : "warn", "clock"),
      c.alts ? S.badge(c.alts + (c.alts === 1 ? " Alt-Konto" : " Alt-Konten"), "warn", "users") : null,
      c.source === "litebans" ? S.badge("LiteBans", "info") : null, (c.ultraGroups || []).length ? S.badge("Alt-Gruppe " + c.ultraGroups.join(", "), "") : null,
      c.ipBlocked ? S.badge("IP gesperrt", "danger", "wifi") : null, c.ipExcluded ? S.badge("IP ausgeschlossen", "ok", "wifi") : null);
    return S.card(null, { cls: "pad-0" },
      h("div", { class: "item", style: { alignItems: "flex-start" } }, S.avatar(c.mainName || "?"),
        h("div", { class: "meta grow" }, h("div", { class: "row wrap", style: { gap: "8px" } }, c.main && S.can("data.profile") ? h("a", { href: "#/player/" + c.main }, h("b", { text: c.mainName || "?" })) : h("b", { text: c.mainName || "?" }), h("code", { class: "tiny muted", text: c.id })),
          h("p", { style: { margin: "4px 0" }, text: c.reason || "ohne Grund" }), badges,
          h("small", { class: "muted", text: [c.actor ? "von " + c.actor : "", c.created ? S.date(c.created) : "", sourceLabel(c.source)].filter(Boolean).join(" · ") })),
        h("div", { class: "side wrap", style: { gap: "6px" } }, toggle, manage ? S.btn("Alle aufheben", { size: "sm", kind: "primary", icon: "unlock", onClick: function () { return liftDialog(c, null, done); } }) : null)),
      body);
  }

  // ------------------------------------------------------------------ IP-Fehlbanns
  async function ipDialog(prefill, data, done) {
    var ip = S.input({ placeholder: "z. B. 82.165.45.159", value: prefill || "" }), out = h("div", { class: "stack" }), exclude = true, dlg;
    async function preview() {
      S.clear(out).appendChild(S.skeleton(1));
      try {
        var r = await S.api("ban-center", { body: { operation: "ip-preview", ip: ip.value } }); S.clear(out);
        out.append(S.kv([["Gesperrte Konten über diese IP", String((r.accounts || []).length)], ["Konten, die je über diese IP kamen", String(r.seenAccounts)], ["IP-Sperre aktiv", r.ipBlocked ? "Ja" : "Nein"], ["IP-Identitätssperren", String(r.identityBans)], ["Alt-Gruppen mit dieser IP", String(r.groupAnchors)], ["Bereits ausgeschlossen", r.excluded ? "Ja" : "Nein"]]),
          (r.accounts || []).length ? h("div", { class: "row wrap", style: { gap: "6px" } }, r.accounts.map(function (a) { return S.badge(a.name + (a.role === "alt" ? " · Alt" : ""), a.role === "alt" ? "warn" : "danger"); })) : null,
          r.seenAccounts > 5 ? S.notice("Über diese IP kamen " + r.seenAccounts + " verschiedene Konten – vermutlich eine geteilte Adresse (Proxy, Geyser, Schule, Mobilfunk). Ausschließen wird empfohlen.", "warn") : null);
      } catch (e) { S.clear(out).appendChild(S.notice(e.message, "danger")); }
    }
    dlg = S.modal("IP-Fehlbann aufheben", h("div", { class: "stack" },
      h("p", { class: "muted", text: "Wenn wegen einer gemeinsamen IP plötzlich alle gesperrt sind: hier alles aufheben, was an dieser IP hängt. Konten mit eigenem, nicht-IP-bezogenem Bann bleiben gesperrt." }),
      h("div", { class: "row wrap" }, h("div", { class: "grow" }, S.field("IP-Adresse", ip)), S.btn("Prüfen", { icon: "search", onClick: preview })), out,
      h("label", { class: "check" }, S.sw(exclude, function (v) { exclude = v; }, !S.full()), h("span", { text: "IP zusätzlich dauerhaft ausschließen (keine IP-Sperren, keine Alt-Erkennung über diese IP)" + (S.full() ? "" : " – nur mit Vollzugriff") }))), [
      S.btn("Abbrechen", { onClick: function () { dlg.close(); } }),
      S.btn("Alles über diese IP aufheben", { kind: "danger solid", icon: "unlock", onClick: async function () {
        if (!await S.confirm("Wirklich aufheben?", "Alle Banns, IP-Sperren und Gruppenanker, die nur an " + ip.value + " hängen, werden aufgehoben.", "Aufheben", true)) return;
        try { var r = await S.api("ban-center", { body: { operation: "ip-lift", ip: ip.value, exclude: S.full() && exclude ? "true" : "false", confirmed: true } }); dlg.close(); S.toast(r.title, "ok", r.description); await done(); }
        catch (e) { fail(e); }
      } })], { icon: "wifi", wide: true });
    if (prefill) preview();
  }

  // ------------------------------------------------------------------ LiteBans-Migration
  var DECISIONS = {
    account: ["Konten sperren", "Hauptkonto und ausgewählte Alt-Konten als Suite-Banns übernehmen."],
    "account+ip": ["Konten + IP sperren", "Zusätzlich die IP sperren – trifft jeden hinter dieser IP. Nur bei eindeutig privater IP."],
    main: ["Nur Hauptkonto", "Nur das Hauptkonto sperren, verknüpfte Konten frei lassen."],
    ultra: ["Alt-Erkennung (Ultrabann)", "Hauptkonto als Alt-Gruppe sperren; neue Alt-Konten mit gleichem Gerät werden erkannt."],
    mute: ["Mute übernehmen", "Als Suite-Mute übernehmen."],
    warn: ["Warnung übernehmen", "Als Warnung ins Spielerprofil."],
    skip: ["Nicht übernehmen", "Bleibt nur im Verlauf; der Spieler ist frei."]
  };
  var STATUS = { pending: ["offen", ""], done: ["übernommen", "ok"], failed: ["fehlgeschlagen", "danger"], skipped: ["nicht übernommen", "info"] };

  P["ban-migration"] = async function (param, query, alive) {
    if (!S.has("litebans-migration-v1") || !S.can("data.ban-center")) { S.mount(S.lockedPage("LiteBans-Migration gesperrt", "data.ban-center")); return; }
    var data, area = h("div", { class: "stack" }), filter = "open", search = "", pending = {};
    S.title("LiteBans-Migration", "LiteBans-Daten auswählen und ins Suite-Bannsystem übernehmen", h("a", { class: "btn ghost sm", href: "#/ban-center" }, S.icon("chevLeft"), h("span", { class: "hide-mobile", text: "Bann-Zentrale" })));
    S.mount(area);
    async function post(body, quiet) {
      try { var r = await S.api("ban-migration", { body: Object.assign({ confirmed: true }, body) }); if (!quiet) S.toast(r.title, "ok", r.description); await load(); }
      catch (e) { fail(e); }
    }
    function choose(c, value) { pending[c.key] = value; c.decision = value; paint(); }
    function paint() {
      if (!alive()) return; S.clear(area);
      var counts = data.counts || {}, canRun = data.migrate, snap = data.snapshot;
      var steps = h("div", { class: "grid kpis" },
        kpi("LiteBans", data.litebansEnabled ? "läuft noch" : data.litebansInstalled ? "abgeschaltet" : "nicht installiert", "shield", data.litebansEnabled ? "y" : "g", data.disabledBySuite ? "von der Suite deaktiviert" : ""),
        kpi("Aktive Fälle", counts.cases || 0, "gavel", "r", (counts.activeBans || 0) + " Bans · " + (counts.activeMutes || 0) + " Mutes"),
        kpi("Offen", counts.pending || 0, "clock", "y", (counts.failed || 0) + " fehlgeschlagen"),
        kpi("Übernommen", counts.done || 0, "check", "g", (counts.skipped || 0) + " bewusst nicht übernommen"),
        kpi("Ausgeschlossene IP", counts.excluded || 0, "wifi", "v", "Fälle an geteilter IP – Standard: nicht übernehmen"));
      area.appendChild(steps);
      if (data.error) area.appendChild(S.notice(data.error, "danger"));
      if (data.running) area.appendChild(S.notice("Migration läuft: " + data.progress.done + " von " + data.progress.total + " Fällen …", "info", "refresh"));
      else if (data.lastRun && data.lastRun.interrupted && data.lastRun.started) area.appendChild(S.notice("Die letzte Migration wurde unterbrochen. Einfach erneut starten – bereits übernommene Fälle bleiben erhalten.", "warn"));
      var tools = h("div", { class: "row wrap" },
        canRun ? S.btn(snap ? "Snapshot neu einlesen" : "LiteBans-Daten einlesen", { icon: "download", disabled: data.snapshotRunning, onClick: function () { return post({ operation: "snapshot" }); } }) : null,
        canRun && Object.keys(pending).length ? S.btn("Auswahl speichern (" + Object.keys(pending).length + ")", { kind: "primary", icon: "check", onClick: async function () { var list = Object.keys(pending).map(function (k) { return k + "=" + pending[k]; }).join(","); pending = {}; await post({ operation: "decide", decisions: list }); } }) : null,
        canRun && snap ? S.btn(data.lastRun && data.lastRun.interrupted && data.lastRun.started ? "Migration fortsetzen" : "Migration starten", { kind: "primary", icon: "send", disabled: data.running || Object.keys(pending).length > 0, title: Object.keys(pending).length ? "Zuerst die Auswahl speichern" : "", onClick: async function () {
          if (!await S.confirm("Migration starten?", (counts.pending || 0) + " offene und " + (counts.failed || 0) + " fehlgeschlagene Fälle werden nach deiner Auswahl übernommen. Bestehende Suite-Banns bleiben unverändert. Abbrechen ist jederzeit sicher – ein erneuter Start macht weiter.", "Starten")) return;
          await post({ operation: "run", retryFailed: "true" });
        } }) : null,
        canRun && data.litebansEnabled ? S.btn("LiteBans jetzt abschalten", { kind: "danger", icon: "x", onClick: async function () { if (await S.confirm("LiteBans abschalten?", "LiteBans wird bis zum nächsten Neustart deaktiviert; die Suite schaltet es bei jedem Start erneut ab.", "Abschalten", true)) await post({ operation: "disable" }); } }) : null,
        canRun && snap && data.litebansInstalled ? S.btn("LiteBans-JAR dauerhaft deaktivieren", { kind: "ghost", icon: "trash", onClick: async function () { if (await S.confirm("LiteBans-JAR umbenennen?", "Die JAR wird in „…jar.disabled-by-simplex“ umbenannt und lädt nach dem nächsten Neustart nicht mehr. Die Datenbank bleibt unverändert und kann jederzeit erneut eingelesen werden.", "Umbenennen", true)) await post({ operation: "rename-jar" }); } }) : null);
      area.appendChild(S.card("Ablauf", { icon: "list", sub: snap ? "Snapshot: " + snap.source + " · " + S.date(snap.takenAt) + " · " + snap.bans + " Bans, " + snap.mutes + " Mutes, " + snap.warnings + " Warnungen" : "Noch kein Snapshot." },
        h("ol", { class: "small", style: { margin: "0 0 12px", paddingLeft: "18px" } },
          h("li", { text: "LiteBans-Daten einlesen (wird beim Serverstart automatisch gemacht, danach wird LiteBans abgeschaltet)." }),
          h("li", { text: "Pro Fall auswählen, was übernommen wird. Fälle an einer ausgeschlossenen IP (z. B. 82.165.45.159) werden standardmäßig nicht übernommen, ihr IP-Teil nie." }),
          h("li", { text: "Migration starten. Bricht sie ab, einfach erneut starten – erledigte Fälle werden übersprungen." })),
        canRun ? tools : S.notice("Ansehen ist erlaubt. Für Einlesen und Übernehmen braucht dein Zugang Vollzugriff und das Recht „LiteBans-Migration durchführen“.", "info", "lock")));
      var rows = (data.cases || []).filter(function (c) {
        if (filter === "open" && (c.status === "done" || c.status === "skipped")) return false;
        if (filter === "excluded" && !c.excluded) return false;
        if (filter === "done" && c.status !== "done") return false;
        if (!search) return true;
        var s = search.toLowerCase();
        return String(c.reason).toLowerCase().indexOf(s) >= 0 || (c.accounts || []).some(function (a) { return String(a.name).toLowerCase().indexOf(s) >= 0 || String(a.ip).indexOf(s) >= 0; });
      });
      var list = h("div", { class: "stack" });
      rows.forEach(function (c) {
        var st = STATUS[c.status] || [c.status, ""];
        var select = h("select", { class: "input", disabled: !canRun || c.status === "done", style: { width: "100%" } }, (c.choices || []).map(function (v) {
          return h("option", { value: v, selected: v === c.decision, text: (DECISIONS[v] || [v])[0] + (v === c.recommended ? " (empfohlen)" : "") });
        }));
        select.addEventListener("change", function () { choose(c, select.value); });
        var accounts = h("div", { class: "list" }, (c.accounts || []).map(function (a) {
          var toggle = a.role === "alt" && canRun && c.status !== "done" ? S.sw(!!a.imported, function (v) { pending[c.key + "|" + a.uuid] = v ? "on" : "off"; a.imported = v; paint(); }) : null;
          return h("div", { class: "item" }, S.avatar(a.name, "sm"), h("div", { class: "meta grow" },
            h("div", { class: "row wrap", style: { gap: "6px" } }, h("b", { text: a.name }), roleBadge(a.role), a.ipExcluded ? S.badge("IP ausgeschlossen", "ok", "wifi") : null, !a.imported ? S.badge("wird nicht gesperrt", "") : null),
            h("small", { class: "muted", text: "LiteBans #" + a.id + " · IP " + (a.ip || "–") + (a.ipAccounts > 1 ? " · " + a.ipAccounts + " Konten über diese IP" : "") })),
            toggle ? h("label", { class: "check small" }, toggle, h("span", { text: "übernehmen" })) : null);
        }));
        list.appendChild(S.card(null, { cls: c.excluded ? "warn" : "" },
          h("div", { class: "row wrap", style: { gap: "8px", alignItems: "flex-start" } },
            h("div", { style: { flex: "1 1 260px", minWidth: "0" } }, h("div", { class: "row wrap", style: { gap: "6px" } }, h("b", { text: (c.type === "ban" ? "Bann" : c.type === "mute" ? "Mute" : "Warnung") + " #" + c.id }), S.badge(st[0], st[1]),
                c.excluded ? S.badge("geteilte/ausgeschlossene IP", "warn", "wifi") : null, c.type !== "warning" ? S.badge(until(c), c.permanent ? "danger" : "warn", "clock") : null),
              h("p", { style: { margin: "4px 0" }, text: c.reason || "ohne Grund" }),
              h("small", { class: "muted", text: "von " + (c.actor || "?") + " · " + S.date(c.time) + (c.message ? " · " + c.message : "") })),
            h("div", { class: "stack", style: { gap: "4px", flex: "1 1 220px", maxWidth: "360px" } }, select, h("small", { class: "muted", text: (DECISIONS[c.decision] || ["", ""])[1] }))),
          accounts));
      });
      if (!rows.length) list.appendChild(S.empty("check", filter === "open" ? "Keine offenen Fälle" : "Keine Fälle", snap ? "Alles erledigt oder anderer Filter." : "Zuerst LiteBans-Daten einlesen."));
      area.appendChild(S.card("Fälle", { icon: "gavel", cls: "pad-list" }, h("div", { class: "stack" },
        h("div", { class: "row wrap" }, S.searchBox("Name, Grund oder IP …", function (v) { search = v; paint(); }, search),
          S.segmented([["open", "Offen"], ["excluded", "Ausgeschlossene IP"], ["done", "Übernommen"], ["all", "Alle"]], filter, function (v) { filter = v; paint(); })), list)));
    }
    async function load() { try { data = await S.api("ban-migration", { quiet: true }); paint(); } catch (e) { if (alive()) S.clear(area).appendChild(S.errorView(e, load)); } }
    await load();
    S.every(4000, async function () { if (data && (data.running || data.snapshotRunning) && !Object.keys(pending).length) await load(); });
  };

  // ------------------------------------------------------------------ Bann-Zentrale
  P["ban-center"] = async function (param, query, alive) {
    if (!S.has("ban-center-v1") || !S.can("data.ban-center")) { S.mount(S.lockedPage("Bann-Zentrale gesperrt", "data.ban-center")); return; }
    var data, filter = "all", q = "", tab = "cases", area = h("div", { class: "stack" });
    S.title("Bann-Zentrale", "Alle Sperren der Suite – Hauptbanns, Alt-Konten, IP-Sperren und Verlauf",
      S.has("litebans-migration-v1") ? h("a", { class: "btn sm", href: "#/ban-migration" }, S.icon("download"), h("span", { text: "LiteBans-Migration" })) : null);
    S.mount(area);
    async function load() { try { data = await S.api("ban-center", { query: { filter: filter, q: q }, quiet: true }); paint(); } catch (e) { if (alive()) S.clear(area).appendChild(S.errorView(e, load)); } }
    function paint() {
      if (!alive()) return; S.clear(area);
      var k = data.kpis || {};
      area.appendChild(h("div", { class: "grid kpis" },
        kpi("Aktive Fälle", k.cases, "gavel", "r", "ein Fall = Hauptbann + zugehörige Konten"),
        kpi("Hauptbanns", k.main, "shield", "r"),
        kpi("Gesperrte Alt-Konten", k.alts, "users", "y"),
        kpi("Zeitlich begrenzt", k.temporary, "clock", "v", k.expiringSoon ? k.expiringSoon + " laufen in 24 h ab" : ""),
        kpi("IP-Sperren", k.ipBlocks, "wifi", "", (k.excludedIps || 0) + " IP(s) ausgeschlossen")));
      var m = data.migration;
      if (m && (m.pending > 0 || m.litebansEnabled)) area.appendChild(S.notice(h("span", {}, m.litebansEnabled ? "LiteBans läuft noch. " : "", m.pending > 0 ? m.pending + " LiteBans-Fälle warten auf deine Auswahl. " : "", h("a", { href: "#/ban-migration", text: "Zur Migration →" })), "warn", "download"));
      var tabs = S.segmented([["cases", "Fälle"], ["ips", "IP-Sperren & Ausschlüsse"], ["history", "Verlauf"]], tab, function (v) { tab = v; paint(); });
      area.appendChild(tabs);
      if (tab === "cases") {
        area.appendChild(h("div", { class: "row wrap" }, S.searchBox("Name, Grund, Fall-ID oder IP …", function (v) { q = v; load(); }, q),
          S.segmented([["all", "Alle"], ["alts", "Mit Alt-Konten"], ["temporary", "Zeitlich"], ["litebans", "Aus LiteBans"], ["ultra", "Alt-Gruppen"]], filter, function (v) { filter = v; load(); })));
        var items = data.items || [];
        area.appendChild(items.length ? h("div", { class: "stack" }, items.map(function (c) { return caseCard(c, data.manage, load); })) : S.empty("check", q || filter !== "all" ? "Keine passenden Banns" : "Niemand ist gesperrt", "Neue Banns: Spielerprofil → Aktionen oder im Spiel /ban."));
      } else if (tab === "ips") {
        area.appendChild(S.card("Gemeinsame IP fälschlich gesperrt?", { icon: "wifi", sub: "Alles aufheben, was an einer IP hängt – und die IP auf Wunsch dauerhaft ausschließen." },
          h("div", { class: "row wrap" }, data.manage ? S.btn("IP prüfen und aufheben …", { kind: "primary", icon: "unlock", onClick: function () { return ipDialog("", data, load); } }) : S.notice("Keine Berechtigung zum Aufheben.", "info", "lock"))));
        area.appendChild(S.card("Aktive IP-Sperren", { icon: "lock", cls: "pad-list" }, (data.ipBlocks || []).length ? h("div", { class: "list" }, data.ipBlocks.map(function (b) {
          return h("div", { class: "item" }, h("div", { class: "meta grow" }, h("b", { text: b.ip }), h("small", { text: "für " + (b.source || "?") + " · von " + (b.by || "?") + " · " + S.date(b.at) + " · " + b.accounts + " Konten kamen je über diese IP" })),
            data.manage ? S.btn("Aufheben", { size: "sm", icon: "unlock", onClick: async function () { if (!await S.confirm("IP-Sperre aufheben?", b.ip + " wird wieder freigegeben. Konto-Banns bleiben.", "Aufheben")) return; try { var r = await S.api("ban-center", { body: { operation: "ip-unblock", ip: b.ip, confirmed: true } }); S.toast(r.title, "ok", r.description); load(); } catch (e) { fail(e); } } }) : null,
            data.manage ? S.btn("Fehlbann …", { size: "sm", kind: "ghost", onClick: function () { return ipDialog(b.ip, data, load); } }) : null);
        })) : S.empty("wifi", "Keine IP-Sperren")));
        area.appendChild(S.card("Ausgeschlossene IPs", { icon: "check", sub: "Diese IPs zählen nie für IP-Sperren oder die Alt-Erkennung (z. B. Proxy/Geyser). Verwaltung: Alt-Accounts → IP-Regeln." },
          (data.excludedIps || []).length ? h("div", { class: "row wrap", style: { gap: "6px" } }, data.excludedIps.map(function (ip) { return S.badge(ip, "ok", "wifi"); })) : h("p", { class: "muted", text: "Keine." })));
      } else {
        var rows = data.history || [];
        area.appendChild(S.card("Verlauf", { icon: "list", cls: "pad-list", sub: "Aufgehobene, abgelaufene und aus LiteBans übernommene ältere Sperren (neueste zuerst)." }, rows.length ? h("div", { class: "list" }, rows.map(function (r) {
          var kind = r.type === "litebans" ? (r.table === "mute" ? "LiteBans-Mute" : r.table === "warning" ? "LiteBans-Warnung" : "LiteBans-Bann") : r.role === "alt" ? "Alt-Konto aufgehoben" : "Bann aufgehoben";
          return h("div", { class: "item" }, S.avatar(r.name || "?", "sm"), h("div", { class: "meta grow" },
            h("div", { class: "row wrap", style: { gap: "6px" } }, r.uuid && S.can("data.profile") ? h("a", { href: "#/player/" + r.uuid, text: r.name || "?" }) : h("b", { text: r.name || "?" }), S.badge(kind, r.type === "litebans" ? "info" : "")),
            h("p", { class: "small", style: { margin: "2px 0" }, text: r.reason || "" }),
            h("small", { class: "muted", text: [r.by ? "gesperrt von " + r.by : "", r.since ? S.date(r.since) : "", r.liftedBy ? "aufgehoben von " + r.liftedBy : "", r.note || "", r.at ? S.ago(r.at) : ""].filter(Boolean).join(" · ") })));
        })) : S.empty("list", "Noch kein Verlauf")));
      }
    }
    await load();
    S.every(20000, function () { if (tab === "cases") return load(); });
  };

  /** Ban status card in the player profile: main ban or alt account, the whole case and the lift options. */
  S.banStatusCard = function (playerId) {
    var box = h("div", { class: "stack" }, S.skeleton(1));
    var card = S.card("Sperrstatus", { icon: "gavel" }, box);
    async function load() {
      try {
        var st = await S.api("ban-center/player", { query: { player: playerId }, quiet: true }); S.clear(box);
        if (!st.banned) {
          box.append(h("div", { class: "row wrap", style: { gap: "6px" } }, S.badge("Nicht gesperrt", "ok", "check"), st.mute ? S.badge("stummgeschaltet " + until(st.mute), "warn", "bellOff") : null, st.ipExcluded ? S.badge("IP ausgeschlossen", "ok", "wifi") : null));
        } else {
          var c = st["case"] || {}, me = (c.members || []).filter(function (m) { return m.uuid === playerId; })[0] || { uuid: playerId, name: st.name, role: st.role };
          var ban = st.accountBan || {};
          box.append(h("div", { class: "row wrap", style: { gap: "6px" } }, roleBadge(st.role), S.badge(until(ban.until !== undefined ? ban : c), (ban.permanent || c.permanent) ? "danger" : "warn", "clock"), c.source === "litebans" ? S.badge("aus LiteBans", "info") : null),
            h("h3", { style: { margin: "4px 0 0" }, text: st.headline }),
            S.kv([["Grund", ban.reason || c.reason], ["Gesperrt von", ban.by || c.actor], ["Seit", ban.since ? S.date(ban.since) : c.created ? S.date(c.created) : null], ["Fall", c.id], ["Herkunft", ban.via || me.via], ["Hauptkonto", st.role === "alt" ? c.mainName : null], ["Konten im Fall", c.accounts ? c.accounts + " (davon " + c.alts + " Alt-Konten)" : null]]),
            (c.members || []).length > 1 ? h("div", { class: "list" }, c.members.map(function (m) { return memberRow(c, m, st.manage, load); })) : null,
            st.manage ? h("div", { class: "row wrap" },
              st.role === "alt" ? S.btn("Entsperren …", { kind: "primary", icon: "unlock", onClick: function () { return liftDialog(c, me, load); } })
                : S.btn((c.members || []).length > 1 ? "Bann + alle zugehörigen aufheben …" : "Bann aufheben …", { kind: "primary", icon: "unlock", onClick: function () { return liftDialog(c, me, load); } }),
              S.can("data.ban-center") ? h("a", { class: "btn ghost sm", href: "#/ban-center" }, "Bann-Zentrale") : null) : null);
        }
        if ((st.history || []).length) box.append(h("details", {}, h("summary", { class: "small", text: st.history.length + " frühere Sperre(n)" }), h("div", { class: "list" }, st.history.map(function (r) {
          return h("div", { class: "item" }, h("div", { class: "meta" }, h("b", { class: "small", text: (r.type === "litebans" ? "LiteBans · " : "") + (r.reason || "ohne Grund") }), h("small", { class: "muted", text: [r.since ? S.date(r.since) : "", r.by ? "von " + r.by : "", r.liftedBy ? "aufgehoben von " + r.liftedBy : "", r.note || ""].filter(Boolean).join(" · ") })));
        }))));
      } catch (e) { S.clear(box).appendChild(S.notice(e.message, "warn")); }
    }
    load();
    return card;
  };
})(window.Simplex);
