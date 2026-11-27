/* Simplex Panel Web 10.24.0 – Länder-Zentrale: Lands-Dashboard, Land-Verwaltung (Mitglieder, Rollen & Rechte,
   Natur-Regeln, Gebiete, Bann, Besitzer, Bank, Löschen) und die Länder eines Spielers im Profil. */
(function (S) {
  "use strict";
  var h = S.h, P = S.pages = S.pages || {};
  var SORTS = [["chunks", "Größe"], ["members", "Mitglieder"], ["online", "Online"], ["balance", "Bank"], ["newest", "Neueste"], ["name", "Name"]];

  function kpi(label, value, ic, hint) {
    return h("section", { class: "card kpi" }, h("div", { class: "ico" }, S.icon(ic)), h("div", { class: "label", text: label }), h("div", { class: "value", text: String(value) }), hint ? h("div", { class: "hint", text: hint }) : null);
  }
  function money(v) { return S.num(Math.round(Number(v) || 0)); }
  function limit(v) { return v < 0 ? "∞" : S.num(v); }
  function playerLink(uuid, name) { return uuid && S.can("data.profile") ? h("a", { href: "#/player/" + uuid, text: name || "?" }) : h("b", { text: name || "?" }); }

  /** POST /v1/lands with a confirmation step for destructive operations. Returns the response or null. */
  async function act(body, opts) {
    opts = opts || {};
    if (opts.confirm) {
      var ok = await S.confirmDialog(opts.confirm[0], opts.confirm[1], opts.confirm[2] || "Bestätigen", !!opts.danger);
      if (!ok) return null;
      body.confirmed = true;
    }
    var r = await S.api("lands", { body: body });
    S.toast(r.message || "Gespeichert", "ok");
    return r;
  }

  /** Picks a player (search with suggestions) and resolves {id, name} or null. */
  function pickPlayer(title, okLabel) {
    return new Promise(function (resolve) {
      var chosen = null, dlg, done = false;
      var search = S.playerSearch ? S.playerSearch(function (row) { chosen = row; }, "Minecraft-Name eintippen") : h("div", {}, S.input({ placeholder: "Exakter Minecraft-Name" }));
      var field = search.querySelector("input");
      function finish(v) { if (done) return; done = true; dlg.close(); resolve(v); }
      dlg = S.modal(title, h("div", { class: "stack" }, search, h("p", { class: "muted small", text: "Ein Teil des Namens genügt – Vorschlag antippen." })), [
        S.btn("Abbrechen", { onClick: function () { finish(null); } }),
        S.btn(okLabel || "Weiter", { kind: "primary", icon: "plus", onClick: function () {
          var name = field.value.trim();
          if (!chosen && !name) { S.toast("Bitte einen Spieler wählen", "err"); return; }
          finish(chosen && chosen.name === name ? chosen : { id: name, name: name });
        } })], { icon: "users", onClose: function () { if (!done) { done = true; resolve(null); } } });
    });
  }

  /** Lets the admin choose one of the assignable roles; resolves the role id or "" (default role) or null. */
  function pickRole(roles, title, current) {
    return new Promise(function (resolve) {
      var assignable = (roles || []).filter(function (r) { return r.assignable; });
      var value = current || "", dlg, done = false;
      var list = h("div", { class: "list" });
      function render() {
        S.clear(list);
        [{ id: "", name: "Standardrolle von Lands" }].concat(assignable).forEach(function (r) {
          var radio = h("input", { type: "radio", name: "land-role", value: r.id, checked: value === r.id });
          radio.addEventListener("change", function () { value = r.id; });
          list.appendChild(h("label", { class: "item", style: { cursor: "pointer" } }, radio, h("div", { class: "meta grow" }, h("b", { text: r.name }),
            r.flags ? h("small", { text: r.flags.length + " Rechte · Rang " + r.priority }) : h("small", { text: "Lands vergibt seine normale Mitgliedsrolle" }))));
        });
      }
      render();
      function finish(v) { if (done) return; done = true; dlg.close(); resolve(v); }
      dlg = S.modal(title, list, [S.btn("Abbrechen", { onClick: function () { finish(null); } }), S.btn("Übernehmen", { kind: "primary", onClick: function () { finish(value); } })],
        { icon: "crown", onClose: function () { if (!done) { done = true; resolve(null); } } });
    });
  }

  // ================================================================== Dashboard
  P.lands = async function (param, query, alive) {
    var q = query.get("q") || "", sort = query.get("sort") || "chunks";
    var reqs = [S.api("lands", { query: { q: q, sort: sort } })];
    if (S.can("data.lands")) reqs.push(S.api("data/lands", { query: { q: "" }, quiet: true }).catch(function () { return null; }));
    var res = await Promise.all(reqs); if (!alive()) return;
    var d = res[0], legacy = res[1];
    S.title("Länder", d.installed ? "Lands " + (d.version || "") : "");
    if (!d.installed) { S.mount(S.card(null, {}, S.empty("map", "Lands ist nicht installiert", "Mit dem Lands-Plugin siehst und verwaltest du hier alle Länder, Mitglieder, Rollen, Rechte und Gebiete."))); return; }
    var st = d.stats || {};
    function go(nq, ns) { S.go("#/lands?q=" + encodeURIComponent(nq) + "&sort=" + encodeURIComponent(ns)); }
    var rows = (d.lands || []).map(function (l) {
      var badges = h("div", { class: "row wrap", style: { gap: "6px", marginTop: "4px" } },
        l.online ? S.badge(l.online + " online", "ok") : null, l.nation ? S.badge(l.nation, "info", "flag") : null,
        l.level ? S.badge(l.level, "accent", "star") : null, l.inWar ? S.badge("Im Krieg", "danger", "gavel") : null,
        l.type && l.type !== "LAND" ? S.badge(l.type === "ADMIN" ? "Admin-Land" : l.type, "warn") : null, l.areas ? S.badge(l.areas + " Gebiete", "", "layers") : null);
      return h("a", { class: "item link", href: "#/land/" + encodeURIComponent(l.id) }, S.avatar(l.name),
        h("div", { class: "meta grow" }, h("b", { text: l.display || l.name }), h("small", { text: "Besitzer " + (l.ownerName || "–") + " · " + S.num(l.members) + " Mitglieder · " + S.num(l.chunks) + (l.maxChunks > 0 ? " / " + S.num(l.maxChunks) : "") + " Chunks · Bank " + money(l.balance) }), badges),
        h("span", { class: "muted", text: "›" }));
    });
    var list = S.card("Alle Länder", { icon: "map", cls: "pad-list", sub: d.total + " gefunden" },
      h("div", { class: "stack" }, h("div", { class: "row wrap", style: { gap: "8px" } }, S.searchBox("Land, Besitzer oder Nation …", function (v) { go(v, sort); }, q), S.segmented(SORTS, sort, function (v) { go(q, v); })),
        h("div", { class: "list" }, rows.length ? rows : h("div", { class: "item muted small", text: "Keine Länder gefunden." }))));
    var cards = [d.error ? S.notice(d.error, "warn", "alert") : null,
      h("div", { class: "grid kpis" }, kpi("Länder", S.num(st.lands || 0), "map"), kpi("Spieler in Ländern", S.num(st.players || 0), "users", S.num(st.owners || 0) + " Besitzer"),
        kpi("Chunks", S.num(st.chunks || 0), "layers", S.num(st.areas || 0) + " Gebiete"), kpi("Gerade online", S.num(st.online || 0), "activity"),
        kpi("Landbanken", money(st.balance || 0), "star"), kpi("Nationen", S.num(st.nations || 0), "flag")),
      h("div", { class: "row wrap" }, S.can("data.profile") ? h("span", { class: "muted small", text: "Tipp: Die Länder eines Spielers siehst und verwaltest du direkt im Spielerprofil." }) : null),
      list];
    if (legacy && legacy.installed) cards.push(adminAccessCard(legacy));
    S.mount(h("div", { class: "stack" }, cards));
  };

  /** Admin access to all lands (LuckPerms nodes) – formerly the whole Lands page. */
  function adminAccessCard(d) {
    var canEdit = S.can("action.lands.access") && S.full() && d.luckperms;
    function toggle(m, node, on) {
      var what = node === "lands.bypass.*" ? "Bypass" : "Admin";
      return S.btn(what + (on ? " entziehen" : " vergeben"), { size: "sm", icon: on ? "x" : "plus", kind: on ? "danger" : "", disabled: !canEdit, title: canEdit ? "" : (d.luckperms ? "Benötigt action.lands.access" : "Benötigt LuckPerms"),
        onClick: async function () { var r = await S.runAction("lands.access", { player: m.uuid, node: node, operation: on ? "revoke" : "grant" }, { title: (on ? "Zugriff entziehen: " : "Zugriff vergeben: ") + m.name, danger: on }); if (r) setTimeout(S.reload, 900); } });
    }
    return S.card("Admin-Zugriff auf alle Länder", { icon: "shieldCheck", cls: "pad-list", sub: "lands.bypass.* = überall bauen/öffnen · lands.admin.* = Lands-Adminbefehle" }, h("div", { class: "list" }, (d.admins || []).length ? d.admins.map(function (m) {
      return h("div", { class: "item stackable" }, S.avatar(m.name, "sm"), h("div", { class: "meta" }, h("b", { text: m.name }), h("div", { class: "row wrap", style: { gap: "6px", marginTop: "4px" } },
        m.bypass ? S.badge("Bypass", "warn", "unlock") : S.badge("Kein Bypass"), m.admin ? S.badge("Lands-Admin", "accent", "crown") : null, m.online ? S.badge("Online", "ok") : null)),
        h("div", { class: "side wrap" }, toggle(m, "lands.bypass.*", m.bypass), toggle(m, "lands.admin.*", m.admin)));
    }) : h("div", { class: "item muted small", text: "Keine Teammitglieder erkannt." })), !d.luckperms ? S.notice("Zugriff vergeben/entziehen benötigt LuckPerms.", "warn", "lock") : null);
  }

  // ================================================================== Land detail
  P.land = async function (id, query, alive) {
    var tab = query.get("tab") || "members";
    var d = await S.api("lands/land", { query: { id: id } }); if (!alive()) return;
    var manage = !!d.manage, reload = function () { S.go("#/land/" + encodeURIComponent(d.id) + "?tab=" + tab); };
    S.title(d.display || d.name, "Land", h("a", { class: "btn ghost sm", href: "#/lands" }, S.icon("chevLeft"), h("span", { class: "hide-mobile", text: "Länder" })));
    var head = S.card(null, {}, h("div", { class: "row wrap", style: { gap: "16px" } }, S.avatar(d.name, "lg"),
      h("div", { class: "grow" }, h("h2", { style: { margin: 0, fontSize: "22px" }, text: d.display || d.name }),
        h("div", { class: "muted small" }, "Besitzer ", playerLink(d.owner, d.ownerName), d.created ? " · gegründet " + S.date(d.created) : ""),
        h("div", { class: "row wrap", style: { gap: "6px", marginTop: "8px" } }, d.level ? S.badge("Level " + d.level, "accent", "star") : null, d.nation ? S.badge("Nation " + d.nation, "info", "flag") : null,
          d.inWar ? S.badge("Im Krieg", "danger", "gavel") : null, d.category ? S.badge(d.category) : null, d.tag ? S.badge("[" + d.tag + "]") : null)),
      manage ? h("div", { class: "side wrap" }, S.btn("Spieler hinzufügen", { kind: "primary", icon: "plus", onClick: function () { return addMember(d, null, reload); } })) : null));
    var kpis = h("div", { class: "grid kpis" }, kpi("Mitglieder", S.num(d.members) + (d.maxMembers > 0 ? " / " + limit(d.maxMembers) : ""), "users"),
      kpi("Chunks", S.num(d.chunks) + (d.maxChunks > 0 ? " / " + limit(d.maxChunks) : ""), "layers"), kpi("Online im Land", S.num((d.onlinePlayers || []).length), "activity"),
      kpi("Landbank", money(d.balance), "star", d.upkeep ? "Unterhalt " + money(d.upkeep) : null), kpi("Gebiete", S.num(d.areas), "map"));
    var tabs = [["members", "Mitglieder"], ["roles", "Rollen & Rechte"], ["natural", "Natur-Regeln"], ["areas", "Gebiete"], ["settings", "Einstellungen"], ["more", "Beziehungen & Post"]];
    var body = h("div", { class: "stack" });
    function show(t) {
      tab = t; S.clear(body);
      if (t === "members") body.appendChild(membersCard(d, null, d.members, d.roles, manage, reload));
      if (t === "roles") body.appendChild(rolesCard(d, null, d.roles, d.flags, manage, reload));
      if (t === "natural") body.appendChild(naturalCard(d, null, d.natural, d.flags, manage, reload));
      if (t === "areas") body.appendChild(areasCard(d, manage, reload));
      if (t === "settings") body.appendChild(settingsCard(d, manage, reload));
      if (t === "more") body.appendChild(moreCard(d));
    }
    var seg = S.segmented(tabs, tab, show);
    S.mount(h("div", { class: "stack" }, head, kpis, h("div", { class: "row", style: { overflowX: "auto" } }, seg), body));
    show(tab);
  };

  async function addMember(d, area, done) {
    var who = await pickPlayer("Spieler zu " + (area ? area.name : d.name) + " hinzufügen", "Weiter"); if (!who) return;
    var roles = area ? area.roles : d.roles;
    var role = await pickRole(roles, "Rolle für " + who.name); if (role === null) return;
    await act({ operation: "trust", land: d.id || d.land, area: area ? area.id : "", player: who.id, role: role });
    done();
  }

  function membersCard(d, area, members, roles, manage, done) {
    var landId = area ? d.land : d.id;
    var rows = (members || []).map(function (m) {
      var actions = manage && !m.owner ? h("div", { class: "side wrap", style: { gap: "6px" } },
        S.btn("Rolle", { size: "sm", icon: "crown", onClick: async function () {
          var r = await pickRole(roles, "Rolle für " + m.name, m.roleId); if (r === null || r === "" || r === m.roleId) return;
          await act({ operation: "set-role", land: landId, area: area ? area.id : "", player: m.uuid, role: r }); done();
        } }),
        S.btn("Entfernen", { size: "sm", kind: "danger", icon: "x", onClick: async function () {
          if (await act({ operation: "untrust", land: landId, area: area ? area.id : "", player: m.uuid }, { confirm: ["Mitglied entfernen", m.name + " wird aus " + (area ? area.name : d.name) + " entfernt.", "Entfernen"], danger: true })) done();
        } }),
        S.btn("Bannen", { size: "sm", kind: "ghost", icon: "gavel", onClick: async function () {
          if (await act({ operation: "ban", land: landId, area: area ? area.id : "", player: m.uuid }, { confirm: ["Spieler bannen", m.name + " wird aus " + (area ? area.name : d.name) + " entfernt und gebannt.", "Bannen"], danger: true })) done();
        } })) : null;
      return h("div", { class: "item stackable" }, S.avatar(m.name, "sm"),
        h("div", { class: "meta grow" }, h("div", { class: "row wrap", style: { gap: "6px" } }, playerLink(m.uuid, m.name), m.owner ? S.badge("Besitzer", "accent", "crown") : null,
            m.role && !m.owner ? S.badge(m.role) : null, m.online ? S.badge("online", "ok") : null, !area && m.wholeLand === false ? S.badge("nur Gebiete", "warn") : null),
          h("small", { class: "muted", text: m.areas > 1 ? "in " + m.areas + " Gebieten vertraut" : "" })),
        actions);
    });
    var bans = (area ? area.bans : d.bans) || [];
    return h("div", { class: "stack" },
      S.card((area ? "Mitglieder im Gebiet" : "Mitglieder") + " (" + rows.length + ")", { icon: "users", cls: "pad-list", actions: manage ? S.btn("Hinzufügen", { size: "sm", kind: "primary", icon: "plus", onClick: function () { return addMember(area ? { id: d.land, name: d.landName } : d, area, done); } }) : null },
        h("div", { class: "list" }, rows.length ? rows : h("div", { class: "item muted small", text: "Noch keine Mitglieder." }))),
      S.card("Gebannt (" + bans.length + ")", { icon: "gavel", cls: "pad-list", sub: "Bekannte Spieler mit Bann in diesem " + (area ? "Gebiet" : "Land"),
        actions: manage ? S.btn("Spieler bannen", { size: "sm", icon: "gavel", onClick: async function () {
          var who = await pickPlayer("Spieler bannen", "Bannen"); if (!who) return;
          if (await act({ operation: "ban", land: landId, area: area ? area.id : "", player: who.id }, { confirm: ["Spieler bannen", who.name + " darf " + (area ? area.name : d.name) + " nicht mehr betreten.", "Bannen"], danger: true })) done();
        } }) : null },
        h("div", { class: "list" }, bans.length ? bans.map(function (b) {
          return h("div", { class: "item" }, S.avatar(b.name, "sm"), h("div", { class: "meta grow" }, playerLink(b.uuid, b.name)),
            manage ? S.btn("Entbannen", { size: "sm", icon: "unlock", onClick: async function () { await act({ operation: "unban", land: landId, area: area ? area.id : "", player: b.uuid }); done(); } }) : null);
        }) : h("div", { class: "item muted small", text: "Niemand gebannt." }))));
  }

  function rolesCard(d, area, roles, flags, manage, done) {
    var landId = area ? d.land : d.id, catalog = (flags && flags.role) || [];
    var wrap = h("div", { class: "stack" });
    (roles || []).forEach(function (r) {
      var on = {}; (r.flags || []).forEach(function (f) { on[String(f).toLowerCase()] = true; });
      var groups = {};
      catalog.forEach(function (f) { (groups[f.category] = groups[f.category] || []).push(f); });
      var grid = h("div", { class: "stack", hidden: true });
      Object.keys(groups).forEach(function (cat) {
        grid.appendChild(h("h3", { class: "small muted", style: { margin: "8px 0 0" }, text: cat === "MANAGEMENT" ? "Verwaltungsrechte" : "Aktionsrechte" }));
        grid.appendChild(h("div", { class: "list" }, groups[cat].map(function (f) {
          var checked = !!on[String(f.name).toLowerCase()];
          return h("label", { class: "item" }, h("div", { class: "meta grow" }, h("b", { text: f.title }), f.description ? h("small", { text: f.description }) : null),
            S.sw(checked, async function (value, input) {
              try { await act({ operation: "role-flag", land: landId, area: area ? area.id : "", role: r.id, flag: f.name, value: value }); on[String(f.name).toLowerCase()] = value; }
              catch (e) { input.checked = !value; S.toast(e.message, "err"); }
            }, !manage || r.owner));
        })));
      });
      var open = false;
      var toggle = S.btn((r.flags || []).length + " Rechte", { size: "sm", kind: "ghost", icon: "chevDown", onClick: function () { open = !open; grid.hidden = !open; } });
      var tools = manage && !r.owner ? h("div", { class: "side wrap", style: { gap: "6px" } },
        S.btn("", { size: "sm icon", kind: "ghost", icon: "chevUp", title: "Rang erhöhen", onClick: async function () { await act({ operation: "role-priority", land: landId, area: area ? area.id : "", role: r.id, direction: "up" }); done(); } }),
        S.btn("", { size: "sm icon", kind: "ghost", icon: "chevDown", title: "Rang senken", onClick: async function () { await act({ operation: "role-priority", land: landId, area: area ? area.id : "", role: r.id, direction: "down" }); done(); } }),
        r.visitor ? null : S.btn("Umbenennen", { size: "sm", icon: "edit", onClick: async function () {
          var name = await S.prompt("Rolle umbenennen", "Neuer Name", r.name, "Umbenennen", { maxlength: 32 }); if (!name || name === r.name) return;
          await act({ operation: "role-rename", land: landId, area: area ? area.id : "", role: r.id, name: name }); done();
        } }),
        r.deletable ? S.btn("Löschen", { size: "sm", kind: "danger", icon: "trash", onClick: async function () {
          if (await act({ operation: "role-delete", land: landId, area: area ? area.id : "", role: r.id }, { confirm: ["Rolle löschen", "„" + r.name + "“ wird gelöscht. " + r.members + " Mitglied(er) bekommen die Standardrolle.", "Löschen"], danger: true })) done();
        } }) : null) : null;
      wrap.appendChild(S.card(null, { cls: "pad-list" },
        h("div", { class: "item", style: { alignItems: "flex-start" } }, h("div", { class: "meta grow" },
          h("div", { class: "row wrap", style: { gap: "6px" } }, h("b", { text: r.name }), r.owner ? S.badge("Besitzer-Rolle", "accent", "crown") : null, r.visitor ? S.badge("Besucher", "info") : null,
            r.type && ["NORMAL", "OWNER", "VISITOR"].indexOf(r.type) < 0 ? S.badge(r.type === "ALLY" ? "Verbündete" : r.type === "NATION" ? "Nation" : r.type) : null),
          h("small", { class: "muted", text: "Rang " + r.priority + " · " + S.num(r.members) + " Mitglieder" + (r.owner ? " · Rechte des Besitzers sind fest" : "") })),
          h("div", { class: "side wrap", style: { gap: "6px" } }, toggle, tools)),
        grid));
    });
    if (!(roles || []).length) wrap.appendChild(S.card(null, {}, S.empty("crown", "Keine Rollen", "Lands hat für dieses " + (area ? "Gebiet" : "Land") + " keine eigenen Rollen gemeldet.")));
    return wrap;
  }

  function naturalCard(d, area, natural, flags, manage, done) {
    var landId = area ? d.land : d.id, on = {};
    (natural || []).forEach(function (n) { on[String(n).toLowerCase()] = true; });
    var catalog = (flags && flags.natural) || [];
    return S.card("Natur-Regeln " + (area ? "im Gebiet" : "im Land"), { icon: "globe", cls: "pad-list", sub: "Monster, Feuer, TNT, Wasser- und Lavafluss, Wachstum …" },
      h("div", { class: "list" }, catalog.length ? catalog.map(function (f) {
        return h("label", { class: "item" }, h("div", { class: "meta grow" }, h("b", { text: f.title }), f.description ? h("small", { text: f.description }) : null),
          S.sw(!!on[String(f.name).toLowerCase()], async function (value, input) {
            try { await act({ operation: "natural-flag", land: landId, area: area ? area.id : "", flag: f.name, value: value }); }
            catch (e) { input.checked = !value; S.toast(e.message, "err"); }
          }, !manage));
      }) : h("div", { class: "item muted small", text: "Lands meldet keine Natur-Regeln." })));
  }

  function areasCard(d, manage, done) {
    var areas = (d.areaList || []);
    return S.card("Gebiete (" + Math.max(0, areas.length - 1) + " Untergebiete)", { icon: "layers", cls: "pad-list", sub: "Neue Gebiete legen Spieler ingame an (/l wizard). Hier: Mitglieder, Rollen, Rechte und Regeln je Gebiet." },
      h("div", { class: "list" }, areas.map(function (a) {
        var b = a.bounds;
        return h("a", { class: "item link", href: "#/land-area/" + encodeURIComponent(d.id) + "/" + encodeURIComponent(a.id) },
          h("div", { class: "avatar sm" }, S.icon(a["default"] ? "home" : "layers")),
          h("div", { class: "meta grow" }, h("div", { class: "row wrap", style: { gap: "6px" } }, h("b", { text: a.name }), a["default"] ? S.badge("Hauptgebiet") : null, a.tenant ? S.badge("Mieter " + a.tenant, "info") : null, a.rental ? S.badge(a.rental === "SELL" ? "zu verkaufen" : "zu vermieten", "warn") : null),
            h("small", { text: [S.num(a.members) + " Mitglieder", a.world, b ? (b.maxX - b.minX + 1) + "×" + (b.maxZ - b.minZ + 1) + " Blöcke, Höhe " + b.minY + "–" + b.maxY : "", a.tax ? "Steuer " + a.tax : ""].filter(Boolean).join(" · ") })),
          h("span", { class: "muted", text: "›" }));
      })));
  }

  function settingsCard(d, manage, done) {
    function row(title, text, button) { return h("div", { class: "item stackable" }, h("div", { class: "meta grow" }, h("b", { text: title }), h("small", { text: text })), button); }
    var list = h("div", { class: "list" },
      row("Name", d.name, manage ? S.btn("Umbenennen", { size: "sm", icon: "edit", onClick: async function () {
        var name = await S.prompt("Land umbenennen", "Neuer Name (Lands prüft Länge und Zeichen)", d.name, "Umbenennen", { maxlength: 48 }); if (!name || name === d.name) return;
        await act({ operation: "rename", land: d.id, name: name }); done();
      } }) : null),
      row("Begrüßung beim Betreten", d.title || "– (Lands-Standard)", manage ? S.btn("Ändern", { size: "sm", icon: "edit", onClick: async function () {
        var text = await S.prompt("Begrüßungstext", "Text (leer = Lands-Standard)", d.title || "", "Speichern", { maxlength: 256 }); if (text === null) return;
        await act({ operation: "title", land: d.id, title: text }); done();
      } }) : null),
      row("Steuer im Land", String(d.tax || 0), manage ? S.btn("Ändern", { size: "sm", icon: "edit", onClick: async function () {
        var v = await S.prompt("Steuer", "Steuer pro Mitglied (Lands-Einheit)", String(d.tax || 0), "Speichern", { inputmode: "decimal" }); if (v === null) return;
        await act({ operation: "tax", land: d.id, tax: v }); done();
      } }) : null),
      row("Landbank", money(d.balance) + (d.upkeep ? " · Unterhalt " + money(d.upkeep) : ""), manage && d.balanceEditable ? h("div", { class: "side wrap", style: { gap: "6px" } },
        S.btn("Ein-/Auszahlen", { size: "sm", icon: "plus", onClick: async function () {
          var v = await S.prompt("Landbank ändern", "Betrag (negativ = abheben)", "0", "Buchen", { inputmode: "decimal" }); if (v === null || Number(v.replace(",", ".")) === 0) return;
          await act({ operation: "balance", land: d.id, mode: "add", amount: v }); done();
        } }),
        S.btn("Setzen", { size: "sm", icon: "edit", onClick: async function () {
          var v = await S.prompt("Kontostand setzen", "Neuer Kontostand", String(Math.round(d.balance || 0)), "Setzen", { inputmode: "decimal" }); if (v === null) return;
          await act({ operation: "balance", land: d.id, mode: "set", amount: v }, { confirm: ["Kontostand setzen", "Die Landbank von " + d.name + " wird auf " + v + " gesetzt.", "Setzen"] }); done();
        } })) : null),
      row("Spawn", d.spawn ? d.spawn.world + " · " + d.spawn.x + " / " + d.spawn.y + " / " + d.spawn.z : "nicht gesetzt", null),
      row("Besitzer", d.ownerName || "–", manage ? S.btn("Besitzer ändern", { size: "sm", icon: "crown", onClick: async function () {
        var who = await pickPlayer("Neuer Besitzer für " + d.name, "Weiter"); if (!who) return;
        if (await act({ operation: "set-owner", land: d.id, player: who.id }, { confirm: ["Besitzer ändern", who.name + " wird Besitzer von " + d.name + ". " + (d.ownerName || "Der bisherige Besitzer") + " bleibt Mitglied.", "Übertragen"] })) done();
      } }) : null));
    var danger = d.mayDelete ? S.card("Gefahrenzone", { icon: "alert" }, h("div", { class: "stack" },
      h("p", { class: "muted small", text: "Löscht das Land mit allen Claims, Gebieten und Rollen über Lands. Das lässt sich nicht rückgängig machen." }),
      S.btn("Land löschen …", { kind: "danger solid", icon: "trash", onClick: async function () {
        var name = await S.prompt("Land löschen", "Zur Bestätigung „" + d.name + "“ eingeben", "", "Endgültig löschen"); if (name === null) return;
        if (name.trim().toLowerCase() !== String(d.name).toLowerCase()) { S.toast("Name stimmt nicht – nichts gelöscht", "err"); return; }
        await S.api("lands", { body: { operation: "delete", land: d.id, confirmName: name.trim(), confirmed: true } });
        S.toast("Land wird gelöscht", "ok"); S.go("#/lands");
      } }))) : null;
    return h("div", { class: "stack" }, S.card("Einstellungen", { icon: "settings", cls: "pad-list" }, list), danger,
      !manage ? S.notice("Ändern benötigt das Recht lands.manage.", "warn", "lock") : null);
  }

  function moreCard(d) {
    var war = d.war;
    return h("div", { class: "grid two" },
      S.card("Beziehungen", { icon: "flag" }, S.kv([["Nation", d.nation || "–"], ["Verbündete", (d.allies || []).join(", ") || "–"], ["Feinde", (d.enemies || []).join(", ") || "–"],
        ["Online im Land", (d.onlinePlayers || []).join(", ") || "–"], war ? ["Kriege", war.wins + " gewonnen · " + war.defeats + " verloren · " + war.kills + " Kills"] : null])),
      S.card("Posteingang", { icon: "bell", cls: "pad-list", sub: "Neueste Land-Nachrichten" }, h("div", { class: "list" }, (d.inbox || []).length ? d.inbox.map(function (m) {
        return h("div", { class: "item" }, h("div", { class: "meta grow" }, h("p", { style: { margin: 0 }, text: m.text }), h("small", { class: "muted", text: m.time ? S.date(m.time) : "" })));
      }) : h("div", { class: "item muted small", text: "Keine Nachrichten." }))));
  }

  // ================================================================== Sub-area
  P["land-area"] = async function (param, query, alive) {
    var parts = String(param || "").split("/"), landId = parts[0], areaId = parts.slice(1).join("/");
    var tab = query.get("tab") || "members";
    var a = await S.api("lands/area", { query: { land: landId, area: areaId } }); if (!alive()) return;
    var manage = !!a.manage, reload = function () { S.go("#/land-area/" + encodeURIComponent(landId) + "/" + encodeURIComponent(areaId) + "?tab=" + tab); };
    S.title(a.name, a.landName, h("a", { class: "btn ghost sm", href: "#/land/" + encodeURIComponent(landId) + "?tab=areas" }, S.icon("chevLeft"), h("span", { class: "hide-mobile", text: a.landName })));
    var d = { land: landId, landName: a.landName, id: landId, name: a.landName };
    var body = h("div", { class: "stack" });
    function show(t) {
      tab = t; S.clear(body);
      if (t === "members") body.appendChild(membersCard(d, a, a.members, a.roles, manage, reload));
      if (t === "roles") body.appendChild(rolesCard(d, a, a.roles, a.flags, manage, reload));
      if (t === "natural") body.appendChild(naturalCard(d, a, a.natural, a.flags, manage, reload));
      if (t === "settings") body.appendChild(areaSettings());
    }
    function areaSettings() {
      return S.card("Gebiet", { icon: "settings", cls: "pad-list" }, h("div", { class: "list" },
        h("div", { class: "item stackable" }, h("div", { class: "meta grow" }, h("b", { text: "Name" }), h("small", { text: a.name })), manage && !a["default"] ? S.btn("Umbenennen", { size: "sm", icon: "edit", onClick: async function () {
          var name = await S.prompt("Gebiet umbenennen", "Neuer Name", a.name, "Umbenennen", { maxlength: 32 }); if (!name || name === a.name) return;
          await act({ operation: "rename", land: landId, area: a.id, name: name }); reload();
        } }) : null),
        h("div", { class: "item stackable" }, h("div", { class: "meta grow" }, h("b", { text: "Steuer" }), h("small", { text: String(a.tax || 0) })), manage ? S.btn("Ändern", { size: "sm", icon: "edit", onClick: async function () {
          var v = await S.prompt("Steuer im Gebiet", "Steuer", String(a.tax || 0), "Speichern", { inputmode: "decimal" }); if (v === null) return;
          await act({ operation: "tax", land: landId, area: a.id, tax: v }); reload();
        } }) : null),
        manage && !a["default"] ? h("div", { class: "item stackable" }, h("div", { class: "meta grow" }, h("b", { text: "Gebiet löschen" }), h("small", { text: "Entfernt nur dieses Untergebiet – das Land bleibt." })),
          S.btn("Löschen", { size: "sm", kind: "danger", icon: "trash", onClick: async function () {
            if (await act({ operation: "area-delete", land: landId, area: a.id }, { confirm: ["Gebiet löschen", "„" + a.name + "“ wird gelöscht. Mitglieder und Rollen des Gebiets gehen verloren.", "Löschen"], danger: true })) S.go("#/land/" + encodeURIComponent(landId) + "?tab=areas");
          } })) : null));
    }
    S.mount(h("div", { class: "stack" }, h("div", { class: "grid kpis" }, kpi("Mitglieder", S.num(a.members ? a.members.length : 0), "users"), kpi("Rollen", S.num((a.roles || []).length), "crown"), kpi("Natur-Regeln an", S.num((a.natural || []).length), "globe")),
      h("div", { class: "row", style: { overflowX: "auto" } }, S.segmented([["members", "Mitglieder"], ["roles", "Rollen & Rechte"], ["natural", "Natur-Regeln"], ["settings", "Gebiet"]], tab, show)), body));
    show(tab);
  };

  // ================================================================== Player profile card
  /** Lands of one player in the profile: owner, member (role, sub-areas), banned – with add/remove/role. */
  S.landsCard = function (playerId) {
    var box = h("div", { class: "stack" }, S.skeleton(1));
    var card = S.card("Länder", { icon: "map", sub: "Eigene Länder, Mitgliedschaften und Land-Banns" }, box);
    async function load() {
      var p;
      try { p = await S.api("lands/player", { query: { player: playerId }, quiet: true }); }
      catch (e) { S.clear(box).appendChild(S.notice(e.message, "warn", "alert")); return; }
      S.clear(box);
      if (!p.installed) { box.appendChild(S.notice("Lands ist nicht installiert.", "", "map")); return; }
      var manage = !!p.manage;
      function landRow(l, kind) {
        var badges = h("div", { class: "row wrap", style: { gap: "6px" } },
          kind === "owned" ? S.badge("Besitzer (Hauptland)", "accent", "crown") : kind === "banned" ? S.badge("Gebannt", "danger", "gavel") : S.badge(l.role || "Mitglied", "info"),
          kind === "member" && !l.wholeLand ? S.badge("nur Gebiete", "warn") : null,
          (l.subAreas || []).length ? S.badge("Gebiete: " + l.subAreas.join(", "), "", "layers") : null, l.online ? S.badge(l.online + " online", "ok") : null);
        var tools = null;
        if (manage && kind === "member") tools = h("div", { class: "side wrap", style: { gap: "6px" } },
          S.btn("Rolle", { size: "sm", icon: "crown", onClick: async function () {
            var detail = await S.api("lands/land", { query: { id: l.id } });
            var r = await pickRole(detail.roles, "Rolle in " + l.name, l.roleId); if (r === null || r === "" || r === l.roleId) return;
            await act({ operation: "set-role", land: l.id, player: playerId, role: r }); load();
          } }),
          S.btn("Entfernen", { size: "sm", kind: "danger", icon: "x", onClick: async function () {
            if (await act({ operation: "untrust", land: l.id, player: playerId }, { confirm: ["Aus Land entfernen", (p.name || "Der Spieler") + " wird aus " + l.name + " entfernt.", "Entfernen"], danger: true })) load();
          } }));
        if (manage && kind === "banned") tools = S.btn("Entbannen", { size: "sm", icon: "unlock", onClick: async function () { await act({ operation: "unban", land: l.id, player: playerId }); load(); } });
        return h("div", { class: "item stackable" }, S.avatar(l.name, "sm"),
          h("div", { class: "meta grow" }, h("a", { href: "#/land/" + encodeURIComponent(l.id), text: l.display || l.name }),
            h("small", { class: "muted", text: (kind === "owned" ? "" : "Besitzer " + (l.ownerName || "–") + " · ") + S.num(l.members) + " Mitglieder · " + S.num(l.chunks) + " Chunks" }), badges),
          tools);
      }
      var rows = [].concat((p.owned || []).map(function (l) { return landRow(l, "owned"); }), (p.member || []).map(function (l) { return landRow(l, "member"); }), (p.banned || []).map(function (l) { return landRow(l, "banned"); }));
      box.appendChild(h("div", { class: "row wrap", style: { gap: "8px" } },
        S.badge((p.owned || []).length + " eigene", "accent", "crown"), S.badge((p.member || []).length + " Mitgliedschaften", "info", "users"), (p.banned || []).length ? S.badge(p.banned.length + " Banns", "danger", "gavel") : null,
        p.editLand ? S.badge("bearbeitet gerade: " + p.editLand) : null));
      box.appendChild(h("div", { class: "list" }, rows.length ? rows : h("div", { class: "item muted small", text: "In keinem Land." })));
      if (manage) box.appendChild(h("div", { class: "row wrap", style: { gap: "8px" } },
        S.btn("Zu einem Land hinzufügen", { size: "sm", kind: "primary", icon: "plus", onClick: async function () {
          var all = await S.api("lands", { query: { sort: "name" } });
          var options = (all.lands || []).filter(function (l) { return (p.owned || []).concat(p.member || []).every(function (x) { return x.id !== l.id; }); });
          var choice = await chooseLand(options); if (!choice) return;
          var detail = await S.api("lands/land", { query: { id: choice.id } });
          var role = await pickRole(detail.roles, "Rolle in " + choice.name); if (role === null) return;
          await act({ operation: "trust", land: choice.id, player: playerId, role: role }); load();
        } }),
        (p.member || []).length > 1 ? S.btn("Aus allen Ländern entfernen", { size: "sm", kind: "danger", icon: "x", onClick: async function () {
          var first = p.member[0];
          if (await act({ operation: "remove-everywhere", land: first.id, player: playerId }, { confirm: ["Aus allen Ländern entfernen", (p.name || "Der Spieler") + " wird aus " + p.member.length + " Ländern entfernt. Eigene Länder bleiben unberührt.", "Alle entfernen"], danger: true })) load();
        } }) : null));
      if (p.error) box.appendChild(S.notice(p.error, "warn", "alert"));
    }
    load();
    return card;
  };

  function chooseLand(options) {
    return new Promise(function (resolve) {
      var done = false, dlg, list = h("div", { class: "list", style: { maxHeight: "50vh", overflowY: "auto" } });
      var search = S.input({ placeholder: "Land suchen …" });
      function finish(v) { if (done) return; done = true; dlg.close(); resolve(v); }
      function render() {
        var q = search.value.trim().toLowerCase(); S.clear(list);
        options.filter(function (l) { return !q || (l.name + " " + l.ownerName).toLowerCase().indexOf(q) >= 0; }).slice(0, 100).forEach(function (l) {
          list.appendChild(h("button", { type: "button", class: "item link", style: { width: "100%", textAlign: "left" }, onClick: function () { finish(l); } }, S.avatar(l.name, "sm"),
            h("div", { class: "meta grow" }, h("b", { text: l.display || l.name }), h("small", { text: "Besitzer " + (l.ownerName || "–") + " · " + S.num(l.members) + " Mitglieder" }))));
        });
        if (!list.children.length) list.appendChild(h("div", { class: "item muted small", text: "Kein passendes Land." }));
      }
      search.addEventListener("input", render); render();
      dlg = S.modal("Land wählen", h("div", { class: "stack" }, search, list), [S.btn("Abbrechen", { onClick: function () { finish(null); } })], { icon: "map", onClose: function () { if (!done) { done = true; resolve(null); } } });
    });
  }
})(window.Simplex);
