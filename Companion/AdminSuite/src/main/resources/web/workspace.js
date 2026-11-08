/* 10.6.0: searchable tools and recoverable history management; uses the existing panel components. */
(function () {
  "use strict";
  var S = window.Simplex, h = S.h, P = S.pages = S.pages || {};
  function matches(query, text) {
    function fold(v) { return String(v || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase(); }
    var textFolded = fold(text); return fold(query).trim().split(/\s+/).every(function (word) { return textFolded.indexOf(word) >= 0; });
  }
  S.workspaceMatches = matches;
  function historyLink(kind, label) { return h("a", { class: "btn ghost sm", href: "#/histories/" + (kind || ""), text: label || "Verlauf durchsuchen & bereinigen" }); }
  S.historyLink = historyLink;
  S.deleteHistory = async function (kind, ids, all) {
    var plan = await S.api("histories/prepare", { body: { kind: kind, ids: ids || [], all: !!all } });
    if (!await S.confirm(plan.count + " Einträge löschen?", "Die bestätigten Einträge werden für alle App- und Web-Nutzer entfernt. Zuerst wird eine wiederherstellbare Sicherung erstellt. Neue Einträge bleiben erhalten.", "Sichern & löschen", true)) return false;
    await S.api("histories/apply", { body: { token: plan.token } }); S.toast("Verlauf bereinigt – Sicherung erstellt", "ok"); return true;
  };

  P.histories = async function (kindID, query, alive) {
    var overview = await S.api("histories"); if (!alive()) return;
    var kind = (overview.kinds || []).filter(function (k) { return k.id === kindID; })[0];
    if (kindID && !kind) { S.mount(S.lockedPage("Verlauf nicht verfügbar", "Leserecht oder Modul fehlt")); return; }
    if (!kind) {
      S.title("Verläufe verwalten", "Suchen, auswählen, sichern und aufräumen");
      var kinds = h("div", { class: "list" }, (overview.kinds || []).map(function (k) {
        return h("a", { class: "item link", href: "#/histories/" + k.id }, S.icon("list"), h("span", { class: "grow", text: k.title }), S.badge(k.count));
      }));
      var backups = h("div", { class: "list" }, (overview.backups || []).map(function (backup) {
        return h("div", { class: "item" }, h("div", { class: "meta" }, h("b", { text: backup.title + " · " + backup.count + " Einträge" }), h("small", { text: S.date(backup.at) })),
          S.btn("Wiederherstellen", { size: "sm", icon: "refresh", onClick: async function () {
            if (!await S.confirm("Sicherung wiederherstellen?", "Gesicherte Einträge werden ergänzt. Bestehende Einträge werden nicht überschrieben. Bei zu wenig Platz wird nichts geändert.", "Wiederherstellen")) return;
            await S.api("histories/restore", { body: { backup: backup.id } }); S.toast("Sicherung wiederhergestellt", "ok"); S.reload();
          } }));
      }));
      S.mount(h("div", { class: "stack" }, S.notice("Vor jeder Löschung entsteht eine Sicherung im Serverordner history-backups. Nur der gewählte Verlauf wird bereinigt; Strafen, Reports, Werbe-Timer und Einstellungen bleiben erhalten.", "info"),
        S.card("Verläufe", { icon: "list" }, kinds), overview.canManage ? S.card("Sicherungen wiederherstellen", { icon: "refresh" }, backups) : S.notice("Zum Löschen und Wiederherstellen: full-Zugriff und das Recht „Verläufe verwalten“.", "info")));
      return;
    }
    var state = { q: query.get("q") || "", offset: 0, rows: [], selected: new Set(), busy: false, revision: 0 }, list = h("div", { class: "list" }), more = h("div", { class: "row wrap" }), count = h("span", { class: "muted small" });
    var remove = S.btn("Auswahl löschen", { kind: "danger", size: "sm", disabled: true, onClick: async function () { if (await S.deleteHistory(kind.id, Array.from(state.selected), false)) await load(true); } });
    var clear = S.btn("Verlauf vollständig leeren …", { kind: "danger", size: "sm", onClick: async function () { if (await S.deleteHistory(kind.id, [], true)) await load(true); } });
    async function load(reset) {
      var revision = ++state.revision;
      if (reset) { state.offset = 0; state.rows = []; state.selected.clear(); }
      state.busy = true; remove.disabled = true;
      try {
        var data = await S.api("histories/entries", { query: { kind: kind.id, q: state.q, offset: state.offset } });
        if (!alive() || revision !== state.revision) return;
        state.rows = state.rows.concat(data.items || []); state.offset = data.next;
        S.clear(list); count.textContent = data.total + " Einträge"; clear.disabled = !data.total || !!state.q;
        state.rows.forEach(function (row) {
          var id = String(row.id), checkbox = h("input", { type: "checkbox", checked: state.selected.has(id), "aria-label": "Eintrag auswählen", onChange: function () { if (checkbox.checked) state.selected.add(id); else state.selected.delete(id); remove.disabled = !state.selected.size; } });
          var title = row.action || row.title || row.text || row.name || row.author || id;
          var detail = [row.adminName, row.author, row.targetName, row.body, row.detail, row.note, row.source].filter(Boolean).join(" · ");
          list.appendChild(h("div", { class: "item" }, overview.canManage ? checkbox : null,
            h("div", { class: "meta" }, h("b", { text: title }), h("small", { text: detail }), h("small", { class: "muted", text: S.date(row.time || row.at) })),
            overview.canManage ? S.btn("", { kind: "danger", size: "sm icon", icon: "trash", title: "Eintrag löschen", onClick: async function () { if (await S.deleteHistory(kind.id, [id], false)) await load(true); } }) : null));
        });
        if (!state.rows.length) list.appendChild(S.empty("list", "Keine Einträge"));
        S.clear(more); if (data.next >= 0) more.appendChild(S.btn("Weitere Einträge laden", { onClick: function () { return load(false); } }));
      } finally { if (revision === state.revision) { state.busy = false; remove.disabled = !state.selected.size; } }
    }
    S.title(kind.title, "Verlauf verwalten", historyLink("", "Alle Verläufe & Sicherungen"));
    S.mount(h("div", { class: "stack" }, h("div", { class: "row wrap" }, S.searchBox("Im gesamten Verlauf suchen …", function (q) { state.q = q; load(true); }, state.q), count,
      overview.canManage ? remove : null, overview.canManage ? clear : null), S.card(null, { cls: "pad-0" }, list, more)));
    await load(true);
  };

  P.tools = async function (param, query, alive) {
    var q = query.get("q") || "", catalog = await S.api("schema"); if (!alive()) return;
    var body = h("div", { class: "stack" });
    function draw() {
      S.clear(body);
      (S.nav || []).forEach(function (group) {
        var items = group.items.filter(function (item) { return item.id !== "tools" && matches(q, item.label + " " + group.group + " " + item.id + (item.id === "ad-hub" ? " Werbung werben kopieren" : "")); });
        if (!items.length) return;
        body.appendChild(S.card(group.group, {}, h("div", { class: "grid two" }, items.map(function (item) {
          var denied = S.denied(item.id); return h("a", { class: "item link", href: "#/" + item.id, title: denied || item.label }, S.icon(item.icon), h("span", { text: item.label }), denied ? S.icon("lock") : null);
        }))));
      });
      if (q) {
        var actions = (catalog.actions || []).filter(function (a) { return a.allowed !== false && matches(q, a.description + " " + a.name + " " + a.group + " " + a.id); });
        if (actions.length) body.appendChild(S.card("Weitere Funktionen", {}, h("div", { class: "list" }, actions.map(function (a) { return h("a", { class: "item link", href: "#/function/" + encodeURIComponent(a.id), text: a.description || a.name }); }))));
      }
      if (!body.children.length) body.appendChild(S.empty("search", "Keine Treffer"));
    }
    S.title("Alle Werkzeuge & Einstellungen", "Nach Aufgabe gegliedert, überall erreichbar");
    S.mount(h("div", { class: "stack" }, S.searchBox("Werkzeuge, Einstellungen oder Funktionen …", function (v) { q = v; draw(); }, q), body)); draw();
  };

  var shortcutPages = {
    overview: "tools", assistant: "assistant", advertising: "ad-hub", players: "players", tickets: "tickets",
    diagnostics: "diagnostics", adminLog: "admin-log", histories: "histories", staffChat: "staff-chat",
    events: "events", statistics: "statistics", chat: "chat", worldTrim: "world-trim",
    discordStatus: "discord-status", scoreboard: "scoreboard", luckperms: "luckperms",
    lands: "lands", locks: "access-locks", commands: "commands", appSettings: "settings",
    functions: "functions", menu: "menu", bans: "moderation"
  };
  var initialShortcuts = ["overview", "advertising", "players", "tickets"];
  function shortcutItems() {
    var items = {};
    S.nav.forEach(function (group) { group.items.forEach(function (item) { items[item.id] = item; }); });
    return items;
  }
  S.shortcutPreferences = async function () {
    var prefs = await S.api("shortcut-prefs");
    var items = shortcutItems();
    var selected = Array.isArray(prefs.tools) ? prefs.tools.slice() : initialShortcuts.slice();
    var name = h("input", { class: "input", maxlength: 80, value: prefs.name || "", placeholder: "Servername" });
    var compact = h("input", { type: "checkbox", checked: prefs.compact !== false });
    var visible = h("input", { type: "checkbox", checked: prefs.hidden !== true });
    var order = h("div", { class: "stack" });
    function draw() {
      S.clear(order);
      selected.forEach(function (key, index) {
        var item = items[shortcutPages[key]]; if (!item || S.denied(item.id)) return;
        order.appendChild(h("div", { class: "row wrap" }, h("span", { class: "grow", text: item.label }),
          S.btn("Nach oben", { size: "sm", disabled: index === 0, onClick: function () { selected.splice(index - 1, 0, selected.splice(index, 1)[0]); draw(); } })));
      });
    }
    var options = Object.keys(shortcutPages).filter(function (key) { return items[shortcutPages[key]] && !S.denied(shortcutPages[key]); });
    var checks = options.map(function (key) {
      var item = items[shortcutPages[key]], box = h("input", { type: "checkbox", checked: selected.indexOf(key) >= 0 });
      box.addEventListener("change", function () { selected = selected.filter(function (v) { return v !== key; }); if (box.checked) selected.push(key); draw(); });
      return h("label", { class: "check" }, box, item.label);
    });
    draw();
    return S.card("Server-Schnellzugriffe", { icon: "grid", sub: "Diese Einstellungen verwendet auch die iOS-App mit demselben Suite-Konto." },
      h("div", { class: "stack" }, S.field("Servername", name), h("label", { class: "check" }, compact, "Kompakte Ansicht"),
        h("label", { class: "check" }, visible, "Server in der iOS-App anzeigen"),
        h("div", { class: "check-grid" }, checks), order,
        S.btn("Schnellzugriffe speichern", { kind: "primary", onClick: async function () {
          await S.api("shortcut-prefs", { body: { name: name.value, compact: compact.checked, hidden: !visible.checked, tools: selected } });
          S.toast("Schnellzugriffe gespeichert");
        } })));
  };

  P.assistant = async function (param, query, alive) {
    var status = await S.api("assistant/status"); if (!alive()) return;
    var owner = status.owner === true, result = null, busy = false, outcomes = [];
    var answer = h("div", { class: "stack", "aria-live": "polite" });
    var inbox = h("div", { class: "stack" }), settings = h("div", { class: "stack" });
    var input = h("textarea", { class: "input", maxlength: 2000, rows: 4, placeholder: "Was soll die KI für diesen Server prüfen?", "aria-label": "Deine Frage" });
    var send = S.btn("Fragen", { kind: "primary", icon: "send", disabled: !status.available, onClick: function () {
      var question = input.value.trim(); if (!question || busy) return;
      return run(async function () { await poll(await S.api("assistant/ask", { body: { question: question } })); });
    } });
    var usage = h("small", { class: "muted" });
    function updateUsage() { usage.textContent = owner ? "Hauptadmin · ohne Tageslimit" : "Heute genutzt: " + status.used + " / " + status.limit; }
    updateUsage();
    async function run(work) {
      if (busy || !alive()) return;
      busy = true; send.disabled = true;
      try { await work(); } catch (e) { if (alive()) S.toast(e.message, "err"); }
      finally { if (alive()) { busy = false; send.disabled = !status.available; } }
    }
    async function poll(job) {
      outcomes = [];
      S.clear(answer); answer.appendChild(S.notice("Antwort und Datenquellen werden geprüft …"));
      for (var i = 0; i < 64 && alive(); i++) {
        var row = await S.api("assistant/job", { query: { id: job.id }, quiet: true }); if (!alive()) return;
        if (row.status !== "running") {
          result = row; draw(); status = await S.api("assistant/status", { quiet: true }); updateUsage();
          if (owner) await loadInbox();
          return;
        }
        await new Promise(function (resolve) { setTimeout(resolve, 2000); });
      }
      if (alive()) throw new Error("Der Server braucht länger. Prüfe später die offenen Freigaben.");
    }
    async function change(operation) {
      if (!result) return;
      var id = result.id;
      if (operation === "approve" && !(await S.confirm("Diesen Aktionsplan verbindlich ausführen?", "Der Server führt die vollständig angezeigten Schritte nacheinander aus.", "Plan bestätigen", true))) return;
      if (!alive() || id !== result.id) return;
      return run(async function () {
        var response = await S.api("assistant/" + operation, { body: { id: id, confirmed: operation === "approve" } });
        outcomes = response.results || [];
        result = await S.api("assistant/job", { query: { id: id } }); draw();
        if (owner) await loadInbox();
      });
    }
    function draw() {
      if (!alive()) return;
      S.clear(answer);
      var r = result; if (!r) return;
      var detail = h("div", { class: "stack" });
      if (r.answer) detail.appendChild(h("div", { style: { whiteSpace: "pre-wrap" }, text: r.answer }));
      if (r.sources && r.sources.length) detail.appendChild(h("small", { class: "muted", text: "Datenquellen · " + r.sources.join(", ") }));
      if (r.summary) detail.appendChild(h("details", {}, h("summary", { text: "KI-Kurzfassung" }), h("p", { text: r.summary })));
      if (r.choices && r.choices.length && status.available && r.status === "ready") detail.appendChild(h("div", { class: "row wrap" }, r.choices.map(function (text, index) {
        return S.btn(text, { onClick: function () { return run(async function () { await poll(await S.api("assistant/choose", { body: { id: r.id, index: index } })); }); } });
      })));
      if (r.players) detail.appendChild(h("div", { class: "row wrap" }, r.players.map(function (p) { return h("a", { class: "btn sm", href: "#/player/" + encodeURIComponent(p.id), text: p.name }); })));
      (r.rejected || []).forEach(function (message) { detail.appendChild(S.notice(message, "warn")); });
      answer.appendChild(S.card("Antwort", { icon: "sparkles" }, detail));
      if ((r.actions || []).length) {
        answer.appendChild(S.card("Vorgeschlagene Aktionen", { icon: "list", sub: r.author || "" }, h("div", { class: "stack" },
          r.actions.map(function (action, index) { return h("div", { class: "item" }, h("div", { class: "meta" }, h("strong", { text: (index + 1) + ". " + action.title }), h("p", { style: { whiteSpace: "pre-wrap" }, text: action.preview }))); }),
          r.status === "ready" ? h("div", { class: "row wrap" }, owner ? S.btn("Plan bestätigen", { kind: "danger", onClick: function () { return change("approve"); } }) : h("span", { class: "muted", text: "Wartet auf Freigabe durch den Hauptadmin." }),
            S.btn("Plan verwerfen", { onClick: function () { return change("discard"); } })) : h("span", { class: "muted", text: r.status === "discarded" ? "Plan verworfen." : "Plan verarbeitet." }))));
      }
      if (outcomes.length) answer.appendChild(S.card("Ergebnis der Freigabe", {}, h("div", { class: "stack" }, outcomes.map(function (step) {
        return S.notice(step.title + " · " + step.description, step.ok ? "ok" : "warn");
      }))));
    }
    async function loadInbox() {
      var data = await S.api("assistant/inbox"); if (!alive()) return;
      S.clear(inbox);
      var items = data.items || [];
      inbox.appendChild(items.length ? h("div", { class: "row wrap" }, items.map(function (plan) {
        return S.btn(plan.author + " · " + plan.count + " Aktionen", { onClick: function () {
          return run(async function () { outcomes = []; result = await S.api("assistant/job", { query: { id: plan.id } }); draw(); });
        } });
      })) : S.notice("Keine offenen Pläne."));
    }
    async function loadLimits() {
      var data = await S.api("assistant/limits"); if (!alive()) return;
      S.clear(settings);
      var account = h("input", { class: "input", placeholder: "Suite-Benutzername", maxlength: 64 });
      var limit = h("input", { class: "input", type: "number", min: 0, max: 500, value: data.default });
      settings.appendChild(h("small", { class: "muted", text: "Standard: " + data.default + " pro Tag. 0 sperrt neue Anfragen." }));
      Object.keys(data.accounts || {}).sort().forEach(function (name) { settings.appendChild(S.btn(name + " · " + data.accounts[name], { size: "sm", onClick: function () { account.value = name; limit.value = data.accounts[name]; } })); });
      settings.appendChild(h("div", { class: "row wrap" }, S.field("Suite-Konto", account), S.field("Tageslimit", limit),
        S.btn("Limit speichern", { onClick: function () { return run(async function () {
          await S.api("assistant/limits", { body: { account: account.value, limit: limit.value } }); await loadLimits();
        }); } })));
    }
    S.title("KI-Assistent", "Gezielt nachsehen und Schreibaktionen zur Freigabe vorlegen");
    S.mount(h("div", { class: "stack" }, S.card("Serverassistent", { icon: "sparkles" }, h("div", { class: "stack" },
      status.available ? null : S.notice("KI ist deaktiviert oder der OpenAI-Schlüssel fehlt. Die manuellen Werkzeuge sind erreichbar.", "warn"),
      input, h("div", { class: "row wrap" }, send, usage),
      h("div", { class: "row wrap" }, ["Gab es letzte Nacht Vorfälle im Chat?", "Wann waren Admins zuletzt im Vanish?", "Welche Tickets sind offen?"].map(function (example) {
        return S.btn(example, { size: "sm", onClick: function () { input.value = example; input.focus(); } });
      })))), answer, owner ? S.card("Offene Freigaben", { icon: "list" }, inbox) : null, owner ? S.card("Tageslimits", { icon: "settings" }, settings) : null));
    if (owner) await Promise.all([loadInbox(), loadLimits()]);
  };

  var dashboard = P.dashboard;
  P.dashboard = async function (param, query, alive) {
    await dashboard(param, query, alive); if (!alive()) return;
    var prefs = {};
    if (S.has("shortcut-preferences-v1")) try { prefs = await S.api("shortcut-prefs", { quiet: true }); } catch (e) { /* use defaults */ }
    if (!alive()) return;
    var items = shortcutItems(), selected = Array.isArray(prefs.tools) ? prefs.tools : initialShortcuts;
    // Dashboard refreshes only replace its own mounted content; retain these shortcuts on every render.
    function add() {
      if (!alive() || !S.content || S.content.querySelector(".workspace-shortcuts")) return;
      var shortcuts = h("div", { class: "row wrap workspace-shortcuts" },
        prefs.name ? h("strong", { text: prefs.name }) : null,
        selected.map(function (key) {
          var item = items[shortcutPages[key]]; if (!item || S.denied(item.id)) return null;
          return h("a", { class: "btn" + (prefs.compact === false ? "" : " sm"), href: "#/" + item.id, text: item.label });
        }),
        S.has("shortcut-preferences-v1") ? h("a", { class: "btn ghost sm", href: "#/appearance", text: "Anpassen" }) : null);
      S.content.prepend(shortcuts);
    }
    add(); var observer = new MutationObserver(add); observer.observe(S.content, { childList: true });
    var previousLeave = S.onLeave; S.onLeave = function () { observer.disconnect(); if (previousLeave) previousLeave(); };
  };
  [["admin-log", "admin-log"], ["events", "events"], ["staff-chat", "staff-chat"], ["chat", "chat"], ["ad-hub", "advertising"]].forEach(function (pair) {
    var original = P[pair[0]]; if (!original) return;
    P[pair[0]] = async function (param, query, alive) { await original(param, query, alive); if (alive() && S.has("history-management-v1")) {
      function add() { if (alive() && !S.content.querySelector(".history-entry-link")) { var link = historyLink(pair[1]); link.classList.add("history-entry-link"); S.content.prepend(link); } }
      add(); var observer = new MutationObserver(add); observer.observe(S.content, { childList: true }); var previousLeave = S.onLeave; S.onLeave = function () { observer.disconnect(); if (previousLeave) previousLeave(); };
    } };
  });
}());
