/* Simplex Panel Web – administration: every Suite function, remote menus, settings, users and rights,
   devices and sessions, own security (password, 2FA, passkeys) and the web panel domain. */
(function () {
  "use strict";
  var S = window.Simplex, h = S.h, icon = S.icon, P = S.pages = S.pages || {};

  // ============================================================ Suite functions (schema driven)
  var schemaCache = null;
  S.schema = async function (force) { if (!schemaCache || force) schemaCache = (await S.api("schema")).actions || []; return schemaCache; };
  function allowed(a) { return a.allowed !== false && S.can("action." + a.id) && (a.scope !== "full" || S.full()); }

  function fieldInput(f, value) {
    var name = f.name, type = Number(f.type), el;
    if ((f.choices || []).length) {
      el = h("select", { class: "input", name: name }, f.required ? null : h("option", { value: "", text: "– nicht angeben –" }),
        f.choices.map(function (c) { return h("option", { value: c.value, text: c.name, selected: String(c.value) === String(value) }); }));
    } else if (type === 5) {
      el = h("select", { class: "input", name: name }, h("option", { value: "", text: "–" }), h("option", { value: "true", text: "Ja", selected: value === "true" }), h("option", { value: "false", text: "Nein", selected: value === "false" }));
    } else if (/password/i.test(name)) {
      el = S.input({ type: "password", name: name, value: value || "", autocomplete: "new-password" });
    } else if (/reason|details|note|description|text|message|question|request/i.test(name) && type === 3) {
      el = h("textarea", { class: "input", name: name, rows: 2, text: value || "" });
    } else {
      el = S.input({ type: type === 4 || type === 10 ? "number" : "text", name: name, value: value || "", step: type === 10 ? "any" : null, min: f.min_value, max: f.max_value });
      if (f.autocomplete || /player|spieler/i.test(name)) {
        var dl = h("datalist", { id: "dl-" + name + "-" + Math.random().toString(36).slice(2) });
        el.setAttribute("list", dl.id); var t;
        el.addEventListener("input", function () { clearTimeout(t); t = setTimeout(async function () { try { var c = await S.api("choices", { query: { path: f._path, field: name, q: el.value } }); S.clear(dl); (c || []).slice(0, 25).forEach(function (o) { dl.appendChild(h("option", { value: o.value, text: o.name })); }); } catch (e) { /* suggestions are optional */ } }, 250); });
        return h("div", {}, el, dl);
      }
    }
    return el;
  }
  function readForm(form) {
    var values = {};
    form.querySelectorAll("[name]").forEach(function (el) { var v = el.value; if (v !== "" && v != null) values[el.name] = v; });
    return values;
  }
  function replyView(r) {
    if (!r) return null;
    return h("div", { class: "notice " + (r.ok === false ? "danger" : "ok") }, icon(r.ok === false ? "alert" : "check"), h("div", { class: "grow" }, h("b", { text: r.title || "Ergebnis" }), r.description ? S.prose(r.description) : null,
      r.link ? h("div", { class: "row wrap", style: { marginTop: "8px" } }, h("code", { class: "mono ellipsis grow", text: r.link }), S.btn("Kopieren", { size: "sm", icon: "copy", onClick: function () { return S.copy(r.link, "Link"); } })) : null));
  }
  /** Opens a form for any Suite action: "Nur anzeigen" (read-only view) or "Prüfen & ausführen" (server-bound confirmation). */
  S.openAction = function (a, preset) {
    preset = preset || {};
    var ok = allowed(a);
    var form = h("form", { class: "stack" }, (a.options || []).map(function (f) {
      f._path = a.id; return S.field(f.description + (f.required ? " *" : ""), fieldInput(f, preset[f.name]));
    }));
    if (!(a.options || []).length) form.appendChild(h("p", { class: "muted small", text: "Diese Aktion hat keine Eingabefelder." }));
    var result = h("div");
    var body = h("div", { class: "stack" }, !ok ? S.notice("Keine Berechtigung für diese Aktion (" + "action." + a.id + "). Du kannst sie nur ansehen.", "warn", "lock") : null, form, result);
    var dlg = S.modal(a.description || a.name || a.id, body, [
      S.btn("Nur anzeigen", { icon: "eye", onClick: async function () { S.clear(result); try { result.appendChild(replyView(await S.api("view", { query: Object.assign({ path: a.id }, readForm(form)) }))); } catch (e) { result.appendChild(S.notice(e.message, e.status === 405 ? "warn" : "danger")); } } }),
      S.btn("Prüfen & ausführen", { kind: "primary", icon: "shieldCheck", disabled: !ok, title: ok ? "" : "Keine Berechtigung", onClick: async function () { S.clear(result); try { var r = await S.runAction(a.id, readForm(form), { silent: true }); if (r) result.appendChild(replyView(r)); } catch (e) { result.appendChild(S.notice(e.message, "danger")); } } })
    ], { wide: true, icon: "sparkles" });
    return dlg;
  };

  P.functions = async function (param, query, alive) {
    var actions = await S.schema(); if (!alive()) return;
    var wrap = h("div", { class: "stack" }), q = query.get("q") || "", onlyAllowed = false;
    function render() {
      S.clear(wrap);
      var groups = {};
      actions.filter(function (a) { return (!q || ((a.description || "") + " " + a.id + " " + (a.group || "")).toLowerCase().indexOf(q.toLowerCase()) >= 0) && (!onlyAllowed || allowed(a)); })
        .forEach(function (a) { (groups[a.group || "Weitere"] = groups[a.group || "Weitere"] || []).push(a); });
      var names = Object.keys(groups).sort();
      if (!names.length) { wrap.appendChild(S.card(null, {}, S.empty("search", "Keine Funktion gefunden"))); return; }
      names.forEach(function (g) {
        wrap.appendChild(h("div", { class: "section-title" }, h("h2", { text: g }), h("span", { class: "muted tiny", text: groups[g].length })));
        wrap.appendChild(h("div", { class: "grid cards" }, groups[g].map(function (a) {
          var ok = allowed(a);
          var c = h("section", { class: "card clickable" + (ok ? "" : " locked"), title: ok ? a.id : "Keine Berechtigung: action." + a.id, onClick: function () { S.openAction(a); }, tabindex: "0" },
            h("div", { class: "action-card" }, h("div", { class: "avatar device sm" }, icon(ok ? (a.scope === "full" ? "shieldCheck" : "chevRight") : "lock")), h("div", { class: "meta" }, h("b", { text: a.description || a.name }), h("small", { text: a.id }), ok ? null : S.lockNote("Keine Berechtigung"))));
          c.addEventListener("keydown", function (e) { if (e.key === "Enter") S.openAction(a); });
          return c;
        })));
      });
    }
    var toggle = h("label", { class: "check small" }, h("input", { type: "checkbox", onChange: function (e) { onlyAllowed = e.target.checked; render(); } }), "Nur freigegebene");
    S.title("Alle Funktionen", actions.length + " Suite-Funktionen");
    S.mount(h("div", { class: "stack" }, h("div", { class: "row wrap" }, S.searchBox("Funktion suchen, z. B. bannen, Wetter, Rang …", function (v) { q = v; render(); }, q), toggle),
      S.notice("Jede Änderung wird vom Server vorab geprüft und erst nach deiner Bestätigung ausgeführt. Gesperrte Funktionen sind ausgegraut.", "", "shieldCheck"), wrap));
    render();
    if (param) { var a = actions.find(function (x) { return x.id === param; }); if (a) S.openAction(a); }
  };
  P["function"] = P.functions;

  // ============================================================ Remote Suite menus
  P.menu = async function (param, query, alive) {
    async function load() {
      var m = await S.api("menu"); if (!alive()) return;
      var inputEl = m.input ? S.input({ placeholder: "Eingabe für das Menü" }) : null;
      async function choose(o, click) {
        try { var r = await S.runAction("menu.action", { session: m.session, revision: String(m.revision), option: String(o.id), click: click || "left", input: inputEl ? inputEl.value : "" }, { title: o.name, silent: true }); if (r) { if (r.description || r.title) S.toast(r.title || "Ausgeführt", r.ok === false ? "err" : "ok"); load(); } }
        catch (e) { S.toast(e.message, "err"); load(); }
      }
      S.title(m.title || "Suite-Menü", "Live aus dem Spiel", S.btn("", { kind: "ghost", size: "sm icon", icon: "refresh", title: "Neu laden", onClick: load }));
      S.mount(h("div", { class: "stack" }, m.notice ? S.notice(m.notice, "", "info") : null, (m.messages || []).length ? S.card("Meldungen", { icon: "chat" }, h("div", { class: "stack" }, m.messages.map(function (x) { return S.prose(String(x)); }))) : null,
        inputEl ? S.card("Eingabe", { icon: "edit" }, h("div", { class: "row" }, inputEl, S.btn("Senden", { kind: "primary", onClick: function () { return choose({ id: "input", name: "Eingabe" }); } }))) : null,
        h("div", { class: "menu-grid" }, (m.options || []).map(function (o) {
          var b = h("button", { type: "button", disabled: o.disabled }, h("b", { text: String(o.name || "").replace(/§[0-9a-fk-or]/gi, "") }), o.detail ? h("small", { text: String(o.detail).replace(/§[0-9a-fk-or]/gi, "") }) : null);
          b.addEventListener("click", function () { choose(o); }); b.addEventListener("contextmenu", function (e) { e.preventDefault(); choose(o, "right"); });
          return b;
        }))));
    }
    await load();
  };

  // ============================================================ Settings
  P.settings = async function (param, query, alive) {
    var tab = query.get("tab") || "app", body = h("div"), tabs = h("div", { class: "tabs" });
    [["app", "App-Einstellungen", "data.app-settings"], ["suite", "Alle Suite-Einstellungen", "data.settings"]].forEach(function (t) {
      var ok = S.can(t[2]) && (t[0] !== "suite" || S.full());
      var b = h("button", { type: "button", class: t[0] === tab ? "on" : "", text: t[1], disabled: !ok, title: ok ? "" : "Keine Berechtigung: " + t[2] });
      b.addEventListener("click", function () { Array.prototype.forEach.call(tabs.children, function (c) { c.classList.remove("on"); }); b.classList.add("on"); tab = t[0]; show(); });
      tabs.appendChild(b);
    });
    var canApp = S.can("action.app.settings"), canSuite = S.can("action.settings.update") && S.full();
    async function show() {
      S.clear(body).appendChild(S.skeleton(3));
      try {
        if (tab === "app") {
          var d = await S.api("data/app-settings"); if (!alive()) return; S.clear(body);
          var groups = {}; (d.items || []).forEach(function (s) { (groups[s.group] = groups[s.group] || []).push(s); });
          Object.keys(groups).forEach(function (g) {
            body.appendChild(S.card(g, { icon: "settings" }, h("div", { class: "list" }, groups[g].map(function (s) {
              var control;
              async function save(v) { return S.runAction("app.settings", { key: s.key, value: String(v) }, { skipConfirm: true }); }
              if (s.type === "bool") control = S.sw(!!s.value, async function (v, el) { try { await save(v); } catch (e) { el.checked = !v; S.toast(e.message, "err"); } }, !canApp);
              else {
                var i = S.input({ value: s.value, type: s.type === "int" ? "number" : "text", min: s.type === "int" ? s.min : null, max: s.type === "int" ? s.max : null, disabled: !canApp, style: { width: s.type === "int" ? "110px" : "min(320px, 60vw)" } });
                control = h("div", { class: "row" }, i, S.btn("", { size: "sm icon", icon: "check", title: "Speichern", disabled: !canApp, onClick: async function () { await save(i.value); } }));
              }
              return h("div", { class: "item" }, h("div", { class: "meta" }, h("b", { style: { whiteSpace: "normal" }, text: s.title }), s.help ? h("small", { style: { whiteSpace: "normal" }, text: s.help }) : null), control);
            }))));
          });
          if (!canApp) body.insertBefore(S.notice("Du kannst die Einstellungen ansehen, aber nicht ändern (action.app.settings).", "warn", "lock"), body.firstChild);
        } else {
          var q = "", list = h("div", { class: "list" });
          async function load() {
            var r = await S.api("data/settings", { query: { q: q } }); S.clear(list);
            (r.items || []).slice(0, 300).forEach(function (s) {
              list.appendChild(h("div", { class: "item" + (canSuite ? " link" : ""), onClick: canSuite ? async function () {
                var v = await S.prompt(s.name, s.id + (s.hint ? " – " + s.hint : ""), Array.isArray(s.value) ? s.value.join(",") : String(s.value));
                if (v === null) return; try { if (await S.runAction("settings.update", { key: s.id, value: v, expected: s.revision })) load(); } catch (e) { S.toast(e.message, "err"); }
              } : null }, h("div", { class: "meta" }, h("b", { text: s.name }), h("small", { class: "mono", text: s.id })), h("code", { class: "mono ellipsis", style: { maxWidth: "40%" }, text: Array.isArray(s.value) ? s.value.join(", ") : String(s.value) })));
            });
            if (!list.children.length) list.appendChild(S.empty("search", "Keine Einstellung gefunden"));
          }
          S.clear(body).appendChild(h("div", { class: "stack" }, S.searchBox("Schlüssel suchen, z. B. discord, afk …", function (v) { q = v; load(); }), S.card(null, { cls: "pad-0" }, list)));
          await load();
        }
      } catch (e) { S.clear(body).appendChild(S.errorView(e)); }
    }
    S.title("Einstellungen");
    S.mount(h("div", { class: "stack" }, tabs, body));
    if (!S.can("data.app-settings")) tab = "suite";
    await show();
  };

  // ============================================================ Users & rights
  P.users = async function (param, query, alive) {
    var d = await S.api("data/access"); if (!alive()) return;
    var canEdit = S.can("action.accounts.update") && S.full(), isOwner = S.account && (S.account.owner || S.account.kind === "key");
    function editor(row) {
      var fresh = !row, name = S.input({ value: row ? row.username : "", disabled: !fresh, autocapitalize: "none", placeholder: "Minecraft-Name" });
      var pass = S.input({ type: "password", autocomplete: "new-password", placeholder: fresh ? "Mindestens 12 Zeichen" : "Leer lassen = unverändert" });
      var enabled = row ? row.enabled !== false : true, rights = {}; (row ? row.permissions : ["data.overview", "data.players", "data.profile"]).forEach(function (p) { rights[p] = true; });
      var filter = "", permsBox = h("div", { class: "stack", style: { gap: "10px", maxHeight: "46vh", overflow: "auto" } });
      var catalog = d.catalog || [];
      var GROUPS = [["*", "Vollzugriff"], ["data.", "Lesen"], ["action.", "Aktionen"], ["ticket.", "Tickets"], ["", "Weitere"]];
      function renderPerms() {
        S.clear(permsBox);
        GROUPS.forEach(function (g) {
          var items = catalog.filter(function (c) { var id = c.id; var match = g[0] === "*" ? id === "*" : g[0] === "" ? !/^(data\.|action\.|ticket\.)/.test(id) && id !== "*" : id.indexOf(g[0]) === 0; return match && (!filter || (c.title + " " + id).toLowerCase().indexOf(filter) >= 0); });
          if (!items.length) return;
          var all = h("button", { type: "button", class: "btn ghost sm right", text: "Alle" });
          all.addEventListener("click", function () { var on = !items.every(function (c) { return rights[c.id]; }); items.forEach(function (c) { if (c.id !== "*" || isOwner) rights[c.id] = on; }); renderPerms(); });
          permsBox.appendChild(h("div", { class: "perm-group" }, h("header", {}, g[1], h("span", { class: "muted tiny", text: items.filter(function (c) { return rights[c.id]; }).length + "/" + items.length }), g[0] === "*" ? null : all),
            h("div", {}, items.map(function (c) {
              var cb = h("input", { type: "checkbox", checked: !!rights[c.id], disabled: c.id === "*" && !isOwner || row && row.owner });
              cb.addEventListener("change", function () { rights[c.id] = cb.checked; });
              return h("label", { class: "perm-row" }, cb, h("div", { class: "grow" }, h("div", { text: c.title }), h("small", { text: c.id })));
            }))));
        });
      }
      renderPerms();
      var sw = S.sw(enabled, function (v) { enabled = v; }, row && row.owner);
      var body = h("div", { class: "stack" }, row && row.owner ? S.notice("Hauptadmin-Zugang: hat immer Vollzugriff und kann nicht deaktiviert oder gelöscht werden.", "", "crown") : null,
        h("div", { class: "grid two" }, S.field("Benutzername", name), S.field(fresh ? "Passwort" : "Neues Passwort", pass)), S.strengthMeter(pass),
        h("div", { class: "row" }, h("b", { class: "grow", text: "Zugang aktiv" }), sw),
        h("div", { class: "row" }, h("b", { class: "grow", text: "Rechte" }), h("div", { class: "search", style: { width: "min(260px, 50%)" } }, icon("search"), S.input({ placeholder: "Filtern", onInput: function (e) { filter = e.target.value.toLowerCase(); renderPerms(); } }))), permsBox,
        S.notice("Speichern meldet alle Geräte dieses Benutzers ab, damit neue Rechte sofort gelten.", "", "info"));
      var actions = [];
      if (row && !row.owner) actions.push(S.btn("Löschen", { kind: "danger", icon: "trash", onClick: async function () { if (await S.runAction("accounts.update", { username: row.username, operation: "delete", enabled: "true", permissions: "", password: "" }, { danger: true, title: row.username + " löschen?" })) { dlg.close(); S.reload(); } } }));
      actions.push(S.btn("Speichern", { kind: "primary", icon: "check", onClick: async function () {
        if (fresh && pass.value.length < 12) { S.toast("Passwort: mindestens 12 Zeichen", "err"); return; }
        var r = await S.runAction("accounts.update", { username: name.value.trim().toLowerCase(), password: pass.value, enabled: String(enabled), permissions: Object.keys(rights).filter(function (k) { return rights[k]; }).join(","), operation: "save" });
        if (r) { dlg.close(); S.reload(); }
      } }));
      var dlg = S.modal(fresh ? "Neuer Benutzer" : row.username, body, actions, { wide: true, icon: "key", noFocus: !fresh });
    }
    async function setupLink(row) {
      var minutes = await S.prompt("Setup-Link für " + row.username, "Gültigkeit in Minuten (5–10080)", "60", "Erstellen", { type: "number", min: 5, max: 10080 });
      if (minutes === null) return;
      var r = await S.runAction("setup.create", { username: row.username, minutes: String(minutes), endpoint: location.host, operation: "create" }, { silent: true });
      if (!r) return;
      S.modal("Setup-Link für " + row.username, h("div", { class: "stack" }, S.notice("Einmalig gültig. Nur an " + row.username + " weitergeben – der Link meldet die App direkt an.", "warn", "alert"),
        h("div", { class: "qr", style: { width: "200px", height: "200px" } }, qrSvg(r.link)), h("code", { class: "mono", style: { overflowWrap: "anywhere" }, text: r.link }),
        S.notice("Im Browser meldet sich " + row.username + " mit Benutzername und Passwort unter " + location.origin + " an.", "", "info")), [S.btn("Link kopieren", { kind: "primary", icon: "copy", onClick: function () { return S.copy(r.link, "Setup-Link"); } })]);
      S.reload();
    }
    var rows = (d.items || []).map(function (u) {
      var menuBtn = S.btn("", { kind: "ghost", size: "sm icon", icon: "dots", title: "Mehr", disabled: !canEdit || (u.owner && !isOwner), onClick: function (e) {
        e.stopPropagation && e.stopPropagation();
        var pop = h("div", { class: "menu-pop" },
          h("button", { type: "button", onClick: function () { pop.remove(); editor(u); } }, icon("edit"), "Bearbeiten & Rechte"),
          S.account && S.account.owner && S.has("account-rename-v1") ? h("button", { type: "button", onClick: function () { pop.remove(); S.renameDialog(u.username, S.account.username === u.username); } }, icon("user"), "Benutzernamen ändern") : null,
          u.enabled && S.has("setup-links-v1") && !u.mfa ? h("button", { type: "button", onClick: function () { pop.remove(); setupLink(u).catch(function (x) { S.toast(x.message, "err"); }); } }, icon("link"), "Setup-Link für die App") : null,
          u.mfa ? h("button", { type: "button", onClick: async function () { pop.remove(); try { if (await S.runAction("accounts.update", { username: u.username, operation: "reset-mfa", enabled: "true", permissions: "", password: "" }, { title: "2FA von " + u.username + " zurücksetzen?" })) S.reload(); } catch (x) { S.toast(x.message, "err"); } } }, icon("shield"), "2FA zurücksetzen") : null,
          h("button", { type: "button", onClick: async function () { pop.remove(); try { if (await S.runAction("accounts.update", { username: u.username, operation: "logout-all", enabled: "true", permissions: "", password: "" }, { title: "Alle Geräte von " + u.username + " abmelden?" })) S.reload(); } catch (x) { S.toast(x.message, "err"); } } }, icon("logout"), "Alle Geräte abmelden"),
          h("button", { type: "button", onClick: function () { pop.remove(); S.go("#/sessions?user=" + encodeURIComponent(u.username)); } }, icon("devices"), "Geräte ansehen"));
        menuBtn.parentNode.appendChild(pop);
        setTimeout(function () { document.addEventListener("click", function off(ev) { if (!pop.contains(ev.target)) { pop.remove(); document.removeEventListener("click", off); } }); }, 0);
      } });
      return h("div", { class: "item" + (canEdit && (!u.owner || isOwner) ? " link" : ""), onClick: canEdit && (!u.owner || isOwner) ? function () { editor(u); } : null }, S.avatar(u.username),
        h("div", { class: "meta" }, h("b", { text: u.username }), h("small", { text: (u.permissions.indexOf("*") >= 0 ? "Vollzugriff" : u.permissions.length + " Rechte") + " · " + u.sessions + " Geräte · zuletzt " + S.ago(u.lastSeen) + (u.publicName ? " · öffentlich „" + u.publicName + "“" : "") }),
          h("div", { class: "row wrap", style: { gap: "6px", marginTop: "6px" } }, u.owner ? S.badge("Hauptadmin", "gold", "crown") : null, u.enabled ? null : S.badge("Deaktiviert", "danger"), u.mfa ? S.badge("2FA", "ok", "shieldCheck") : null,
            u.passkeys ? S.badge(u.passkeys + " Passkey" + (u.passkeys > 1 ? "s" : ""), "info", "fingerprint") : null, u.mustChange ? S.badge("Passwortwechsel offen", "warn") : null, u.setupExpires ? S.badge("Setup-Link offen", "accent", "link") : null)),
        h("div", { class: "side rel", onClick: function (e) { e.stopPropagation(); } }, menuBtn));
    });
    S.title("Benutzer & Rechte", (d.items || []).length + " Benutzer", S.btn("Neuer Benutzer", { kind: "primary", size: "sm", icon: "plus", disabled: !canEdit, onClick: function () { editor(null); } }));
    S.mount(h("div", { class: "stack" }, !canEdit ? S.notice("Nur ansehen – zum Bearbeiten wird action.accounts.update benötigt.", "warn", "lock") : null,
      S.card(null, { cls: "pad-0" }, h("div", { class: "list" }, rows.length ? rows : S.empty("users", "Noch keine Benutzer", "Lege Benutzer mit Benutzername und Passwort an. Sie können sich in der App oder hier im Browser anmelden.")))));
  };

  // ============================================================ Devices & sessions
  P.sessions = async function (param, query, alive) {
    var d = await S.api("sessions"); if (!alive()) return;
    var filterUser = query.get("user") || "", now = Date.now();
    function act(row, op, kind) {
      return async function () {
        var label = "";
        if (op === "rename") { label = await S.prompt("Gerät umbenennen", "Name", row.label || S.deviceName(row), "Speichern"); if (label === null) return; }
        else if (op === "logout" && !(await S.confirm(kind === "key" ? "API-Schlüssel widerrufen?" : "Gerät abmelden?", kind === "key" ? "Der Schlüssel „" + row.user + "“ wird dauerhaft ungültig." : "„" + S.deviceName(row) + "“ von " + row.user + " wird sofort abgemeldet.", kind === "key" ? "Widerrufen" : "Abmelden", true))) return;
        else if (op === "lock" && !(await S.confirm("Gerät vorübergehend sperren?", "Die API antwortet diesem Gerät, bis du es wieder freigibst, nur noch mit „gesperrt“. Die Anmeldung bleibt erhalten.", "Sperren", true))) return;
        try { var r = await S.api("sessions/update", { body: { id: row.id, operation: op, name: label, kind: kind || "session" } }); S.toast(r.title || "Gespeichert"); if (op === "logout" && row.current) { S.logout(); return; } S.reload(); } catch (e) { S.toast(e.message, "err"); }
      };
    }
    function row(r, kind) {
      var online = r.seen && now - r.seen < 5 * 60e3, own = kind !== "key" && S.account && r.user === S.account.username;
      var details = [r.kind === "web" ? "Browser" : kind === "key" ? "API-Schlüssel (" + r.scope + ")" : "App" + (r.app ? " " + r.app : ""), r.model && r.kind !== "web" ? r.model : null, r.os, r.browser && r.kind === "web" ? r.browser : null].filter(Boolean).join(" · ");
      var meta = [r.ip ? "IP " + r.ip : null, "zuletzt " + S.ago(r.seen), r.created ? "seit " + S.date(r.created) : null, r.via ? "via " + r.via : null].filter(Boolean).join(" · ");
      var buttons = h("div", { class: "row wrap", style: { gap: "6px" } },
        S.btn("", { size: "sm icon", kind: "ghost", icon: "edit", title: "Umbenennen", disabled: !(d.manage || own), onClick: act(r, "rename", kind) }),
        d.manage && !r.current ? S.btn("", { size: "sm icon", kind: "ghost", icon: r.locked ? "unlock" : "lock", title: r.locked ? "Freigeben" : "Vorübergehend sperren", onClick: act(r, r.locked ? "unlock" : "lock", kind) }) : null,
        (d.manage || own) && !(kind === "key" && r.current) ? S.btn("", { size: "sm icon", kind: "ghost", icon: "logout", title: kind === "key" ? "Widerrufen" : "Abmelden", onClick: act(r, "logout", kind) }) : null);
      return h("div", { class: "item" }, h("div", { class: "avatar device" }, icon(S.deviceIcon(Object.assign({}, r, { kind: kind === "key" ? "key" : r.kind })))),
        h("div", { class: "meta" }, h("b", { text: S.deviceName(r) }), h("small", { text: details }), h("small", { text: meta }),
          h("div", { class: "row wrap", style: { gap: "6px", marginTop: "6px" } }, r.current ? S.badge("Dieses Gerät", "accent") : null, online ? S.badge("Online", "ok") : null, r.locked ? S.badge("Gesperrt", "danger", "lock") : null, r.owner ? S.badge("Hauptadmin", "gold", "crown") : null)),
        h("div", { class: "side" }, buttons));
    }
    var items = (d.items || []).filter(function (r) { return !filterUser || r.user === filterUser; });
    var groups = {}; items.forEach(function (r) { (groups[r.user] = groups[r.user] || []).push(r); });
    var cards = Object.keys(groups).sort(function (a, b) { return (groups[b][0].owner ? 1 : 0) - (groups[a][0].owner ? 1 : 0) || a.localeCompare(b); }).map(function (u) {
      return S.card(u, { icon: groups[u][0].owner ? "crown" : "user", sub: groups[u].length + " Gerät" + (groups[u].length === 1 ? "" : "e"), cls: "pad-list" }, h("div", { class: "list" }, groups[u].map(function (r) { return row(r, "session"); })));
    });
    if (d.manage && (d.keys || []).length && !filterUser) cards.push(S.card("API-Schlüssel", { icon: "key", sub: "Klassische Zugänge ohne Benutzerkonto", cls: "pad-list" }, h("div", { class: "list" }, d.keys.map(function (r) { return row(r, "key"); }))));
    S.title("Geräte & Sitzungen", d.manage ? d.active + " gerade aktiv" : "Deine Geräte", filterUser ? h("a", { class: "btn ghost sm", href: "#/sessions" }, icon("x"), filterUser) : null);
    S.mount(h("div", { class: "stack" }, d.manage ? S.notice("Als Hauptadmin siehst du alle angemeldeten Geräte mit ungefährem Gerätenamen und IP. Gesperrte Geräte bleiben angemeldet, erhalten aber keine Daten, bis du sie freigibst.", "", "shieldCheck") : S.notice("Hier siehst du, wo du angemeldet bist. Unbekanntes Gerät? Sofort abmelden und das Passwort ändern.", "", "info"),
      cards.length ? cards : S.card(null, {}, S.empty("devices", "Keine aktiven Geräte"))));
  };

  // ============================================================ Account & security
  function qrSvg(text) {
    var m = window.SimplexQR.matrix(text), n = m.length, q = 4, d = "";
    for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) if (m[y][x]) d += "M" + (x + q) + " " + (y + q) + "h1v1h-1z";
    var svg = h("svg", { viewBox: "0 0 " + (n + 2 * q) + " " + (n + 2 * q), "shape-rendering": "crispEdges", role: "img", "aria-label": "QR-Code" });
    svg.appendChild(h("rect", { width: "100%", height: "100%", fill: "#fff" })); svg.appendChild(h("path", { d: d, fill: "#000" }));
    return svg;
  }
  function recoveryView(codes) {
    var text = "Simplex Panel – Wiederherstellungscodes für " + (S.account.username || "") + "\nJeder Code funktioniert genau einmal.\n\n" + codes.join("\n") + "\n";
    var url = URL.createObjectURL(new Blob([text], { type: "text/plain" }));
    return h("div", { class: "stack" }, S.notice("Diese Codes ersetzen den Authenticator, falls du ihn verlierst. Sie werden nur jetzt angezeigt.", "warn", "alert"),
      h("div", { class: "codes" }, codes.map(function (c) { return h("span", { text: c }); })),
      h("div", { class: "row wrap" }, S.btn("Kopieren", { icon: "copy", onClick: function () { return S.copy(codes.join("\n"), "Codes"); } }), h("a", { class: "btn", href: url, download: "simplex-wiederherstellungscodes.txt" }, icon("download"), "Als Datei")));
  }
  async function enable2fa() {
    var b = await S.api("account/totp/begin", { body: {} });
    var code = S.input({ inputmode: "numeric", autocomplete: "one-time-code", maxlength: 6, class: "input code", placeholder: "000000" });
    var body = h("div", { class: "stack" }, h("p", { class: "muted small", style: { margin: 0 }, text: "1. QR-Code mit einer Authenticator-App scannen (Apple Passwörter, Google Authenticator, Microsoft Authenticator, 1Password …)." }),
      h("div", { class: "qr" }, qrSvg(b.uri)),
      h("div", { class: "row wrap", style: { justifyContent: "center" } }, h("code", { class: "mono", text: b.secret.replace(/(.{4})/g, "$1 ").trim() }), S.btn("", { size: "sm icon", icon: "copy", title: "Schlüssel kopieren", onClick: function () { return S.copy(b.secret, "Schlüssel"); } }),
        /iPhone|iPad|Macintosh/.test(navigator.userAgent) ? h("a", { class: "btn sm", href: b.uri }, icon("key"), "In Passwörter öffnen") : null),
      S.field("2. Aktuellen 6-stelligen Code eingeben", code));
    var dlg = S.modal("Zwei-Faktor-Schutz einrichten", body, [S.btn("Aktivieren", { kind: "primary", icon: "shieldCheck", onClick: async function () {
      var r = await S.api("account/totp/enable", { body: { code: code.value.trim() } });
      dlg.close(); S.toast("2FA ist aktiv");
      S.modal("Wiederherstellungscodes", recoveryView(r.recovery || []), [S.btn("Gespeichert", { kind: "primary", onClick: function () { document.querySelector(".modal-bg").remove(); S.reload(); } })], { locked: true });
    } })], { icon: "shieldCheck" });
    code.addEventListener("keydown", function (e) { if (e.key === "Enter") dlg.el.querySelector(".btn.primary").click(); });
  }

  P.security = async function (param, query, alive) {
    var a = await S.api("account"); if (!alive()) return; S.account = Object.assign(S.account || {}, a);
    var returnToApp = query.get("return") === "app";
    var cards = [];
    if (returnToApp) cards.push(S.notice(h("span", {}, "Aus der App geöffnet. ", h("a", { href: "panel://suite-done", text: "Fertig – zurück zur App" })), "ok", "phone"));
    cards.push(S.card(null, {}, h("div", { class: "row wrap", style: { gap: "16px" } }, S.avatar(a.username || a.label, "lg"),
      h("div", { class: "grow" }, h("h2", { style: { margin: 0, fontSize: "20px" }, text: a.username || a.label }), h("div", { class: "row wrap", style: { gap: "6px", marginTop: "6px" } },
        a.owner ? S.badge("Hauptadmin", "gold", "crown") : null, (a.permissions || []).indexOf("*") >= 0 ? S.badge("Vollzugriff", "accent") : S.badge((a.permissions || []).length + " Rechte"), a.mfa ? S.badge("2FA aktiv", "ok", "shieldCheck") : S.badge("2FA aus", "warn", "shield"),
        (a.passkeys || []).length ? S.badge(a.passkeys.length + " Passkey" + (a.passkeys.length > 1 ? "s" : ""), "info", "fingerprint") : null)),
      S.btn("Abmelden", { icon: "logout", onClick: S.logout }))));
    // Password
    var cur = S.input({ type: "password", autocomplete: "current-password" }), nw = S.input({ type: "password", autocomplete: "new-password" }), nw2 = S.input({ type: "password", autocomplete: "new-password" });
    var pwd = S.card("Passwort ändern", { icon: "key", sub: "Alle anderen Geräte werden dabei abgemeldet." }, h("div", { class: "stack" }, S.field("Aktuelles Passwort", cur), h("div", { class: "grid two" }, h("div", { class: "stack", style: { gap: "6px" } }, S.field("Neues Passwort", nw), S.strengthMeter(nw)), S.field("Wiederholen", nw2)),
      h("div", {}, S.btn("Passwort ändern", { kind: "primary", icon: "check", onClick: async function () {
        if (nw.value.length < 12) throw new Error("Neues Passwort: mindestens 12 Zeichen");
        if (nw.value !== nw2.value) throw new Error("Die neuen Passwörter stimmen nicht überein");
        var r = await S.api("account/password", { body: { current: cur.value, next: nw.value } }); cur.value = nw.value = nw2.value = ""; S.toast(r.title, "ok", r.description);
      } }))));
    // 2FA
    var mfa = S.card("Zwei-Faktor-Schutz (2FA)", { icon: "shieldCheck", sub: a.mfa ? "Aktiv · " + a.recoveryLeft + " Wiederherstellungscodes übrig" : "Empfohlen – schützt auch bei gestohlenem Passwort." },
      a.mfa ? h("div", { class: "stack" }, a.recoveryLeft <= 3 ? S.notice("Nur noch " + a.recoveryLeft + " Wiederherstellungscodes. Bitte neue erzeugen.", "warn") : null, h("div", { class: "row wrap" },
        S.btn("Neue Wiederherstellungscodes", { icon: "refresh", onClick: async function () {
          var pw = await S.prompt("Wiederherstellungscodes erneuern", "Zur Bestätigung dein Passwort", "", "Erneuern", { type: "password", autocomplete: "current-password" }); if (pw === null) return;
          var r = await S.api("account/recovery", { body: { password: pw } }); S.modal("Neue Wiederherstellungscodes", recoveryView(r.recovery || []), [S.btn("Gespeichert", { kind: "primary", onClick: function () { document.querySelector(".modal-bg").remove(); S.reload(); } })], { locked: true });
        } }),
        S.btn("2FA deaktivieren", { kind: "danger", icon: "x", onClick: async function () {
          var pw = await S.prompt("2FA deaktivieren", "Zur Bestätigung dein Passwort", "", "Deaktivieren", { type: "password", autocomplete: "current-password" }); if (pw === null) return;
          await S.api("account/totp/disable", { body: { password: pw } }); S.toast("2FA deaktiviert"); S.reload();
        } })))
        : h("div", { class: "stack" }, h("p", { class: "muted small", style: { margin: 0 }, text: "Nach dem Passwort fragt das Panel – in der App und im Browser – nach einem 6-stelligen Code aus deiner Authenticator-App. Funktioniert sofort, auch ohne Domain." }),
          h("div", {}, S.btn("2FA einrichten", { kind: "primary", icon: "qr", onClick: enable2fa }))));
    // Passkeys
    var pkList = h("div", { class: "list" }, (a.passkeys || []).map(function (p) {
      return h("div", { class: "item" }, h("div", { class: "avatar device sm" }, icon("fingerprint")), h("div", { class: "meta" }, h("b", { text: p.name }), h("small", { text: "Hinzugefügt " + S.date(p.created) + " · zuletzt benutzt " + (p.used ? S.ago(p.used) : "noch nie") + (p.active ? "" : " · andere Domain (" + p.rp + ")") })),
        p.synced ? S.badge("Synchronisiert", "info") : null,
        S.btn("", { size: "sm icon", kind: "ghost", icon: "edit", title: "Umbenennen", onClick: async function () { var n = await S.prompt("Passkey umbenennen", "Name", p.name); if (n === null) return; await S.api("account/passkey/update", { body: { id: p.id, name: n, operation: "rename" } }); S.reload(); } }),
        S.btn("", { size: "sm icon", kind: "ghost", icon: "trash", title: "Entfernen", onClick: async function () { if (!(await S.confirm("Passkey entfernen?", "„" + p.name + "“ kann danach nicht mehr zur Anmeldung verwendet werden.", "Entfernen", true))) return; await S.api("account/passkey/update", { body: { id: p.id, operation: "delete" } }); S.reload(); } }));
    }));
    var addPasskey = async function () {
      var name = await S.prompt("Passkey hinzufügen", "Name für diesen Passkey", /iPhone/.test(navigator.userAgent) ? "iPhone" : /Mac/.test(navigator.userAgent) ? "Mac" : /Android/.test(navigator.userAgent) ? "Android" : "Dieses Gerät", "Weiter");
      if (name === null) return; var r = await S.passkey.register(name); S.toast(r.title || "Passkey gespeichert", "ok", r.description); S.reload();
    };
    var pkBody = h("div", { class: "stack" }, (a.passkeys || []).length ? pkList : null);
    if (!a.passkeysAvailable) pkBody.appendChild(S.notice(h("span", {}, "Passkeys benötigen eine eigene Domain mit gültigem HTTPS-Zertifikat (z. B. admin.example.eu). ", a.manager ? h("a", { href: "#/web", text: "Domain einrichten" }) : "Der Hauptadmin kann sie einrichten."), "", "info"));
    else if (!S.passkey.here()) pkBody.appendChild(S.notice(h("span", {}, "Passkeys funktionieren nur über ", h("a", { href: a.webUrl + "#/security", text: a.webUrl }), " – nicht über die IP-Adresse."), "warn", "link"));
    else pkBody.appendChild(h("div", {}, S.btn("Passkey hinzufügen", { kind: "primary", icon: "fingerprint", onClick: addPasskey })));
    var pk = S.card("Passkeys", { icon: "fingerprint", sub: "Anmeldung mit Face ID, Touch ID, Windows Hello oder Sicherheitsschlüssel – ganz ohne Passwort." }, pkBody);
    cards.push(h("div", { class: "grid two" }, h("div", { class: "stack" }, mfa, pk), h("div", { class: "stack" }, S.nameCard ? S.nameCard(a) : null, pwd)));
    cards.push(S.card("Notfall", { icon: "alert" }, S.prose(a.owner ? "Alles verloren? In der **Serverkonsole** `sas app owner-reset` eingeben. Du bekommst deinen Benutzernamen und ein Einmal-Passwort angezeigt; 2FA und Passkeys werden dabei entfernt und du legst sofort ein neues Passwort fest.\n\nWeitere Befehle (nur Konsole): `sas app recovery` zeigt alle Wiederherstellungsbefehle – z. B. `sas app user-reset <Name>`, `sas app owner-transfer <Name>`, `sas app rename <alt> <neu>` und `sas app unlock <Name>`." : "Zugang verloren? Der Hauptadmin kann deine Zwei-Faktor-Einrichtung zurücksetzen und dir ein neues Passwort geben – notfalls über die Serverkonsole mit `sas app user-reset <Name>`.")));
    S.title("Konto & Sicherheit", a.username || "");
    S.mount(h("div", { class: "stack" }, cards));
    if (query.get("add") === "passkey" && S.passkey.here() && a.passkeysAvailable) setTimeout(function () { addPasskey().catch(function (e) { S.toast(e.message, "err"); }); }, 300);
  };

  // ============================================================ Web panel & domain
  P.web = async function (param, query, alive) {
    var a = await S.api("account"); if (!alive()) return;
    if (!a.manager) { S.mount(S.lockedPage("Nur für den Hauptadmin", null, "Die Domain des Webpanels verwaltet der Hauptadmin.")); return; }
    var info = null; try { if (S.can("data.setup-info")) info = await S.api("data/setup-info"); } catch (e) { info = null; }
    var domain = S.input({ value: a.domain || "", placeholder: "admin.example.eu", autocapitalize: "none" });
    S.title("Webpanel & Domain");
    S.mount(h("div", { class: "stack" },
      h("div", { class: "grid two" },
        S.card("Erreichbarkeit", { icon: "globe" }, S.kv([["Diese Adresse", location.origin], ["Eigene Domain", a.webUrl || "noch nicht eingerichtet"], ["Port", info ? String(info.port) : location.port || "443"],
          ["Zertifikat (SHA-256)", info && info.fingerprint ? h("code", { class: "mono", style: { overflowWrap: "anywhere" }, text: info.fingerprint }) : null]])),
        S.card("Domain für Passkeys", { icon: "link", sub: "Optional. 2FA funktioniert auch ohne Domain." }, h("div", { class: "stack" }, S.field("Domain (ohne https://, optional mit :Port)", domain),
          h("div", { class: "row wrap" }, S.btn("Speichern", { kind: "primary", icon: "check", onClick: async function () { var r = await S.api("web/domain", { body: { domain: domain.value.trim() } }); S.toast(r.title, "ok", r.description); S.webConfig = null; S.reload(); } }),
            a.domain ? S.btn("Domain entfernen", { kind: "danger", icon: "trash", onClick: async function () { if (!(await S.confirm("Domain entfernen?", "Passkeys funktionieren danach nicht mehr, bis wieder eine Domain eingetragen ist.", "Entfernen", true))) return; await S.api("web/domain", { body: { domain: "" } }); S.reload(); } }) : null)))),
      S.card("So richtest du HTTPS mit Domain ein", { icon: "info" }, S.prose("Ohne Domain läuft das Webpanel direkt unter **https://Server-IP:Port** mit dem selbst erzeugten Zertifikat – der Browser zeigt dabei einmalig eine Warnung, die du bestätigen kannst (Fingerabdruck oben vergleichen).\n\nFür Passkeys und ein Zertifikat ohne Warnung:\n1. DNS-Eintrag (A/AAAA) für z. B. **admin.example.eu** auf die Server-IP setzen.\n2. Einen Reverse-Proxy mit Let's Encrypt davor betreiben (z. B. Caddy: `admin.example.eu { reverse_proxy 127.0.0.1:39093 }`) und in der config.yml `app-api.transport: proxy` sowie `app-api.bind: 127.0.0.1` setzen – oder den Port direkt freigeben.\n3. Domain hier eintragen. In der App unter Konto & Sicherheit die Adresse auf die Domain umstellen (Fingerabdruck leer lassen).")),
      S.notice("Gleicher Port wie die App-API: Webpanel und App nutzen dieselben Benutzer, Rechte, Sitzungen und Sperren.", "", "info")));
  };
})();
