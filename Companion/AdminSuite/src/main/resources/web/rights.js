/* Simplex Panel Web 10.0.0 – Rechte-Sperren, LuckPerms helpers (permission autocomplete, prefix/suffix editor with
   live Minecraft colour preview) and the one-time changelog per account. */
(function () {
  "use strict";
  var S = window.Simplex, h = S.h, icon = S.icon, P = S.pages = S.pages || {};

  // ============================================================ Minecraft colour preview
  var COLORS = { "0": "#000000", "1": "#0000AA", "2": "#00AA00", "3": "#00AAAA", "4": "#AA0000", "5": "#AA00AA", "6": "#FFAA00", "7": "#AAAAAA",
    "8": "#555555", "9": "#5555FF", a: "#55FF55", b: "#55FFFF", c: "#FF5555", d: "#FF55FF", e: "#FFFF55", f: "#FFFFFF" };
  var NAMED = { black: "0", dark_blue: "1", dark_green: "2", dark_aqua: "3", dark_red: "4", dark_purple: "5", gold: "6", gray: "7", grey: "7", dark_gray: "8", dark_grey: "8",
    blue: "9", green: "a", aqua: "b", red: "c", light_purple: "d", yellow: "e", white: "f" };
  var LABELS = [["0", "Schwarz"], ["1", "Dunkelblau"], ["2", "Dunkelgrün"], ["3", "Türkis"], ["4", "Dunkelrot"], ["5", "Lila"], ["6", "Gold"], ["7", "Grau"],
    ["8", "Dunkelgrau"], ["9", "Blau"], ["a", "Grün"], ["b", "Hellblau"], ["c", "Rot"], ["d", "Pink"], ["e", "Gelb"], ["f", "Weiß"]];
  function hexMix(a, b, t) { var x = parseInt(a.slice(1), 16), y = parseInt(b.slice(1), 16); function c(s) { return Math.round(((x >> s) & 255) * (1 - t) + ((y >> s) & 255) * t); } return "#" + ((1 << 24) + (c(16) << 16) + (c(8) << 8) + c(0)).toString(16).slice(1); }
  /** Splits legacy (&a, &l, &#RRGGBB, &x&…), {#RRGGBB} and simple MiniMessage (<red>, <bold>, <#hex>, <gradient:a:b>) into styled runs. */
  function runs(text) {
    var out = [], st = { color: null, b: false, i: false, u: false, s: false, k: false }, grad = null, i = 0, src = String(text || "");
    function push(ch) { out.push({ ch: ch, st: Object.assign({}, st), grad: grad }); }
    while (i < src.length) {
      var c = src[i], n = src[i + 1];
      if ((c === "&" || c === "§") && n) {
        var hx = /^[&§]#([0-9a-fA-F]{6})/.exec(src.slice(i));
        if (hx) { st = { color: "#" + hx[1], b: false, i: false, u: false, s: false, k: false }; i += 8; continue; }
        var xx = /^[&§]x((?:[&§][0-9a-fA-F]){6})/.exec(src.slice(i));
        if (xx) { st = { color: "#" + xx[1].replace(/[&§]/g, ""), b: false, i: false, u: false, s: false, k: false }; i += 14; continue; }
        var k = n.toLowerCase();
        if (COLORS[k]) { st = { color: COLORS[k], b: false, i: false, u: false, s: false, k: false }; i += 2; continue; }
        if ("lomnk".indexOf(k) >= 0) { st[{ l: "b", o: "i", n: "u", m: "s", k: "k" }[k]] = true; i += 2; continue; }
        if (k === "r") { st = { color: null, b: false, i: false, u: false, s: false, k: false }; grad = null; i += 2; continue; }
      }
      if (c === "{" && /^\{#[0-9a-fA-F]{6}\}/.test(src.slice(i))) { st.color = src.slice(i + 1, i + 8); i += 9; continue; }
      if (c === "<") {
        var tag = /^<\/?([a-z_]+|#[0-9a-fA-F]{6})(?::([^>]{1,60}))?>/.exec(src.slice(i));
        if (tag) {
          var name = tag[1].toLowerCase(), closing = src[i + 1] === "/";
          if (name[0] === "#") st.color = closing ? null : name;
          else if (NAMED[name]) st.color = closing ? null : COLORS[NAMED[name]];
          else if (name === "bold" || name === "b") st.b = !closing; else if (name === "italic" || name === "i" || name === "em") st.i = !closing;
          else if (name === "underlined" || name === "u") st.u = !closing; else if (name === "strikethrough" || name === "st") st.s = !closing;
          else if (name === "obfuscated" || name === "obf") st.k = !closing; else if (name === "reset") { st = { color: null, b: false, i: false, u: false, s: false, k: false }; grad = null; }
          else if (name === "gradient" || name === "rainbow") {
            if (closing) grad = null;
            else { var stops = (tag[2] || "").split(":").map(function (s) { return s[0] === "#" ? s : NAMED[s] ? COLORS[NAMED[s]] : null; }).filter(Boolean); grad = { rainbow: name === "rainbow", stops: stops.length >= 2 ? stops : ["#FF5555", "#5555FF"], start: out.length }; }
          } else { push(c); i++; continue; }
          i += tag[0].length; continue;
        }
      }
      push(c); i++;
    }
    // resolve gradients over the characters they cover
    var groups = {};
    out.forEach(function (r, idx) { if (r.grad) (groups[r.grad.start] = groups[r.grad.start] || { g: r.grad, idx: [] }).idx.push(idx); });
    Object.keys(groups).forEach(function (k) {
      var g = groups[k].g, list = groups[k].idx;
      list.forEach(function (idx, j) {
        var t = list.length > 1 ? j / (list.length - 1) : 0;
        if (g.rainbow) { out[idx].st.color = "hsl(" + Math.round(t * 330) + ",95%,62%)"; return; }
        var seg = Math.min(g.stops.length - 2, Math.floor(t * (g.stops.length - 1))), lt = t * (g.stops.length - 1) - seg;
        out[idx].st.color = hexMix(g.stops[seg], g.stops[seg + 1], lt);
      });
    });
    return out;
  }
  /** Renders text like Minecraft chat would (approximation; fonts differ). */
  S.mcText = function (text, fallback) {
    var wrap = h("span", { class: "mc-text" }), list = runs(text), cur = null, key = "";
    list.forEach(function (r) {
      var k = [r.st.color, r.st.b, r.st.i, r.st.u, r.st.s, r.st.k].join("|");
      if (!cur || k !== key) { cur = h("span"); var css = cur.style; if (r.st.color) css.color = r.st.color; else if (fallback) css.color = fallback;
        if (r.st.b) css.fontWeight = "800"; if (r.st.i) css.fontStyle = "italic"; css.textDecoration = [r.st.u ? "underline" : "", r.st.s ? "line-through" : ""].join(" ").trim() || "none";
        if (r.st.k) cur.className = "mc-obf"; wrap.appendChild(cur); key = k; }
      cur.textContent += r.ch;
    });
    return wrap;
  };
  S.mcPlain = function (text) { return runs(text).map(function (r) { return r.ch; }).join(""); };

  /** Prefix/suffix editor: colour palette, formats, hex colour, live preview "Präfix + Name + Suffix". */
  S.affixEditor = function (opts) {
    opts = opts || {};
    var inp = S.input({ value: opts.value || "", placeholder: opts.placeholder || "z. B. &c&l[Admin] &r", autocapitalize: "none", spellcheck: "false", maxlength: 64 });
    var preview = h("div", { class: "mc-preview" }), count = h("small", { class: "muted tiny" });
    function render() {
      S.clear(preview);
      var p = opts.kind === "suffix" ? (opts.other || "") : inp.value, sfx = opts.kind === "suffix" ? inp.value : (opts.other || "");
      preview.appendChild(S.mcText(p)); preview.appendChild(h("span", { class: "mc-name", text: opts.name || "Steve" })); preview.appendChild(S.mcText(sfx));
      preview.appendChild(h("span", { class: "mc-chat", text: ": Hallo zusammen!" }));
      count.textContent = inp.value.length + " / 64 Zeichen" + (/["\\]/.test(inp.value) ? " · Anführungszeichen und \\ sind nicht erlaubt" : "");
    }
    function insert(code) {
      var a = inp.selectionStart == null ? inp.value.length : inp.selectionStart, b = inp.selectionEnd == null ? a : inp.selectionEnd;
      inp.value = inp.value.slice(0, a) + code + inp.value.slice(b); inp.focus(); inp.setSelectionRange(a + code.length, a + code.length); render();
    }
    inp.addEventListener("input", render);
    var palette = h("div", { class: "mc-palette" }, LABELS.map(function (l) {
      return h("button", { type: "button", class: "mc-swatch", title: l[1] + " (&" + l[0] + ")", style: { background: COLORS[l[0]] }, onClick: function () { insert("&" + l[0]); } });
    }));
    var color = h("input", { type: "color", value: "#55ffff", title: "Eigene Farbe (&#RRGGBB)" });
    color.addEventListener("change", function () { insert("&" + color.value.toUpperCase()); });
    var formats = h("div", { class: "row wrap", style: { gap: "6px" } },
      [["&l", "Fett", "B"], ["&o", "Kursiv", "I"], ["&n", "Unterstrichen", "U"], ["&m", "Durchgestrichen", "S"], ["&k", "Verschleiert", "?"], ["&r", "Zurücksetzen", "↺"]].map(function (f) {
        return h("button", { type: "button", class: "btn sm", title: f[1] + " (" + f[0] + ")", onClick: function () { insert(f[0]); } }, h("b", { text: f[2] }));
      }), h("label", { class: "btn sm mc-color", title: "Eigene Hex-Farbe" }, color, "Hex"),
      h("button", { type: "button", class: "btn sm", title: "Verlauf (MiniMessage – nur wenn dein Chat-Plugin MiniMessage nutzt)", onClick: function () { insert("<gradient:#55FFFF:#AA00AA>Text</gradient>"); } }, "Verlauf"),
      h("button", { type: "button", class: "btn sm", title: "Leerzeichen am Ende trennt Rang und Name", onClick: function () { insert(" "); } }, "␣"));
    var templates = h("div", { class: "row wrap", style: { gap: "6px" } }, ["&c&l[Admin] &r", "&9[Mod] &r", "&a[Builder] &r", "&6&l✦ VIP &r", "&8[&bSupport&8] &r", "&7[Spieler] "].map(function (t) {
      return h("button", { type: "button", class: "chip mc-chip", onClick: function () { inp.value = t; render(); } }, S.mcText(t));
    }));
    render();
    return { el: h("div", { class: "stack" }, S.field(opts.label || "Text", inp), count, palette, formats, h("div", {}, h("small", { class: "muted", text: "Vorlagen" }), templates),
      h("div", {}, h("small", { class: "muted", text: "Vorschau im Chat" }), preview),
      h("p", { class: "muted tiny", text: "&-Farbcodes und &#RRGGBB funktionieren mit jedem Chat-Plugin, das LuckPerms-Präfixe anzeigt. Leer lassen entfernt den Eintrag." })),
      value: function () { return inp.value; } };
  };
  /** Dialog around the editor; resolves the new text or null. */
  S.affixDialog = function (title, opts) {
    return new Promise(function (resolve) {
      var ed = S.affixEditor(opts), done = false, dlg;
      dlg = S.modal(title, ed.el, [S.btn("Abbrechen", { onClick: function () { done = true; dlg.close(); resolve(null); } }),
        S.btn("Speichern", { kind: "primary", icon: "check", onClick: function () { if (/["\\]/.test(ed.value())) { S.toast("Anführungszeichen und \\ sind nicht erlaubt", "err"); return; } done = true; dlg.close(); resolve(ed.value()); } })],
        { wide: true, icon: "edit", onClose: function () { if (!done) resolve(null); } });
    });
  };

  // ============================================================ LuckPerms permission autocomplete
  var known = null, knownAt = 0;
  S.knownPermissions = async function () {
    if (known && Date.now() - knownAt < 120000) return known;
    if (!S.has("luckperms-v2")) return [];
    try { known = (await S.api("data/luckperms-permissions", { quiet: true })).items || []; knownAt = Date.now(); } catch (e) { known = []; }
    return known;
  };
  /** Text field with suggestions from every permission the server knows (plugins, LuckPerms, groups). */
  S.permInput = function (opts) {
    opts = opts || {};
    var inp = S.input({ placeholder: opts.placeholder || "Recht suchen, z. B. essentials.fly", autocapitalize: "none", spellcheck: "false", autocomplete: "off" });
    var list = h("div", { class: "suggest", hidden: true }), hint = h("small", { class: "muted tiny" }), active = -1, shown = [];
    function choose(item) { inp.value = item.node; list.hidden = true; hint.textContent = item.description ? item.description : (item.plugin ? "Plugin: " + item.plugin : ""); if (opts.onPick) opts.onPick(item); }
    async function update() {
      var q = inp.value.trim().toLowerCase(), all = await S.knownPermissions();
      if (!q) { list.hidden = true; hint.textContent = all.length ? all.length + " bekannte Rechte – einfach lostippen" : ""; return; }
      var starts = [], contains = [];
      all.forEach(function (p) { var n = p.node.toLowerCase(); if (n.indexOf(q) === 0) starts.push(p); else if (n.indexOf(q) > 0 || (p.description || "").toLowerCase().indexOf(q) >= 0) contains.push(p); });
      shown = starts.concat(contains).slice(0, 12); active = -1; S.clear(list);
      shown.forEach(function (p, i) {
        var row = h("button", { type: "button", class: "suggest-row" }, h("b", { class: "mono", text: p.node }), h("small", { text: [p.plugin, p.description].filter(Boolean).join(" · ") }));
        row.addEventListener("mousedown", function (e) { e.preventDefault(); choose(p); });
        list.appendChild(row);
      });
      list.hidden = !shown.length;
      hint.textContent = shown.length ? "" : "Unbekanntes Recht – kann trotzdem gesetzt werden.";
    }
    var t; inp.addEventListener("input", function () { clearTimeout(t); t = setTimeout(update, 80); });
    inp.addEventListener("focus", update);
    inp.addEventListener("blur", function () { setTimeout(function () { list.hidden = true; }, 120); });
    inp.addEventListener("keydown", function (e) {
      if (list.hidden || !shown.length) return;
      if (e.key === "ArrowDown" || e.key === "ArrowUp") { e.preventDefault(); active = (active + (e.key === "ArrowDown" ? 1 : -1) + shown.length) % shown.length; Array.prototype.forEach.call(list.children, function (c, i) { c.classList.toggle("on", i === active); }); }
      else if (e.key === "Enter" && active >= 0) { e.preventDefault(); choose(shown[active]); }
      else if (e.key === "Escape") list.hidden = true;
    });
    return { el: h("div", { class: "suggest-wrap" }, inp, list, hint), input: inp, value: function () { return inp.value.trim(); } };
  };

  // ============================================================ Page: Rechte-Sperren
  var SCOPE_TONE = { everywhere: "danger", own: "warn", "own-wild": "accent" };
  function lockBadge(l) { return S.badge(l.title + " · " + (l.scope === "everywhere" ? "überall" : l.scope === "own" ? "nur eigenes Land" : "eigenes Land + Wildnis") + " · " + l.remaining, SCOPE_TONE[l.scope] || "", "lock"); }
  async function locks(values, title, danger) { var r = await S.runAction("locks.update", values, { title: title || "Rechte-Sperre ändern?", danger: danger }); if (r) setTimeout(S.reload, 400); return r; }
  function durationSelect(list, value) {
    return h("select", { class: "input" }, (list || ["30m", "2h", "12h", "1d", "3d", "7d", "14d", "30d", ""]).map(function (d) {
      return h("option", { value: d, selected: d === (value || ""), text: d ? d.replace(/m$/, " Minuten").replace(/h$/, " Stunden").replace(/^1d$/, "1 Tag").replace(/d$/, " Tage") : "Dauerhaft" });
    }));
  }
  function explainCard(d) {
    return S.card("So funktionieren Rechte-Sperren", { icon: "info" }, h("div", { class: "stack" }, h("p", { class: "muted", text: d.help }),
      h("div", { class: "grid three" }, (d.scopes || []).map(function (s) { return h("div", { class: "scope-card " + (SCOPE_TONE[s.id] || "") }, h("b", { text: s.title }), h("p", { class: "small", text: s.explain })); })),
      !d.lands ? S.notice("Lands ist nicht installiert: Alles zählt als Wildnis. „Nur im eigenen Land“ sperrt dann überall, „eigenes Land & Wildnis“ erlaubt dann überall.", "warn", "map") : null));
  }
  /** 10.0.1: live player search with suggestions (online first), like the player list. */
  S.playerSearch = function (onPick, placeholder) {
    var inp = S.input({ placeholder: placeholder || "Spieler suchen – Name eintippen", autocapitalize: "none", spellcheck: "false", autocomplete: "off" });
    var list = h("div", { class: "suggest", hidden: true }), shown = [], active = -1, t, seq = 0;
    function pick(row) { list.hidden = true; inp.value = row.name; onPick(row); }
    async function update() {
      var q = inp.value.trim(), mine = ++seq;
      if (!q) { list.hidden = true; return; }
      var r; try { r = await S.api("data/players", { query: { q: q, filter: "all", limit: 8 }, quiet: true }); } catch (e) { return; }
      if (mine !== seq) return;
      shown = (r.items || []).slice(0, 8); active = -1; S.clear(list);
      shown.forEach(function (row) {
        var b = h("button", { type: "button", class: "suggest-row player" }, h("div", { class: "row", style: { gap: "10px" } }, S.avatar(row.name, "sm"), h("div", {}, h("b", { text: row.name }), h("small", { text: row.online ? "online" : "offline" + (row.lastSeen ? " · zuletzt " + S.ago(row.lastSeen) : "") }))));
        b.addEventListener("mousedown", function (e) { e.preventDefault(); pick(row); });
        list.appendChild(b);
      });
      if (!shown.length) list.appendChild(h("div", { class: "suggest-row muted small", text: "Kein Spieler gefunden" }));
      list.hidden = false;
    }
    inp.addEventListener("input", function () { clearTimeout(t); t = setTimeout(update, 180); });
    inp.addEventListener("focus", function () { if (inp.value.trim()) update(); });
    inp.addEventListener("blur", function () { setTimeout(function () { list.hidden = true; }, 150); });
    inp.addEventListener("keydown", function (e) {
      if (e.key === "ArrowDown" || e.key === "ArrowUp") { if (!shown.length) return; e.preventDefault(); active = (active + (e.key === "ArrowDown" ? 1 : -1) + shown.length) % shown.length; Array.prototype.forEach.call(list.children, function (c, i) { c.classList.toggle("on", i === active); }); }
      else if (e.key === "Enter") { e.preventDefault(); if (shown[active >= 0 ? active : 0]) pick(shown[active >= 0 ? active : 0]); else if (inp.value.trim()) onPick({ id: inp.value.trim(), name: inp.value.trim() }); }
      else if (e.key === "Escape") list.hidden = true;
    });
    return h("div", { class: "suggest-wrap search-live" }, h("div", { class: "search" }, icon("search"), inp), list);
  };
  /** Form field variant: a named input whose value becomes the picked player's exact name. */
  S.livePlayerInput = function (name, value, placeholder) {
    var wrap = S.playerSearch(function (row) { inp.value = row.name; }, placeholder || "Spieler – Teil des Namens genügt");
    var inp = wrap.querySelector("input"); inp.name = name; inp.value = value || "";
    return wrap;
  };
  function rightBadges(r) {
    var out = (r.locks || []).map(lockBadge);
    (r.inheritedLocks || []).forEach(function (l) { out.push(S.badge(l.title + " · geerbt von " + l.fromName, "accent", "link")); });
    (r.rights || []).filter(function (x) { return x.blocked; }).forEach(function (x) { out.push(S.badge(x.name + " · " + x.remaining, "danger", "lock")); });
    Object.keys(r.legacy || {}).forEach(function (k) { r.legacy[k].forEach(function (x) { out.push(S.badge(x.name + " · " + x.remaining, "warn", "lock")); }); });
    return out;
  }
  P["access-locks"] = async function (param, query, alive) {
    if (param) return lockPlayer(decodeURIComponent(param), alive);
    var q = query.get("q") || "";
    var d = await S.api("data/access-locks", { query: { q: q } }); if (!alive()) return;
    S.title("Rechte & Sperren", (d.items || []).length + " Spieler mit Sperren");
    var open = S.playerSearch(function (row) { S.go("#/access-locks/" + encodeURIComponent(row.id)); }, "Spieler suchen – online und offline");
    var rows = (d.items || []).map(function (r) {
      return h("div", { class: "item link stackable", onClick: function () { S.go("#/access-locks/" + r.uuid); } }, S.avatar(r.name, "sm"),
        h("div", { class: "meta" }, h("b", { text: r.name }), h("div", { class: "row wrap", style: { gap: "6px", marginTop: "4px" } }, rightBadges(r))),
        r.online ? h("span", { class: "dot on", title: "online" }) : null, icon("chevRight", "chev"));
    });
    var presets = S.card("Vorlagen", { icon: "sparkles", sub: "Mit einem Klick mehrere Sperren setzen" }, h("div", { class: "grid two" }, (d.presets || []).map(function (p) {
      return h("div", { class: "preset" }, h("b", { text: p.title }), h("p", { class: "small muted", text: p.explain }));
    })));
    S.mount(h("div", { class: "stack" }, S.card("Spieler auswählen", { icon: "user", sub: "Beim Tippen werden passende Spieler vorgeschlagen – dann Bereiche und Einzelrechte mit Dauer ändern" }, open),
      S.card("Aktive Sperren", { icon: "lock", cls: "pad-list" }, h("div", { class: "list" }, rows.length ? rows : S.empty("unlock", "Niemand ist gesperrt", "Öffne einen Spieler, um einzelne Rechte zu sperren."))),
      h("div", { class: "grid two" }, explainCard(d), presets)));
  };
  async function lockPlayer(id, alive) {
    var d = await S.api("data/access-locks", { query: { player: id } }); if (!alive()) return;
    var u = d.player, canEdit = S.can("action.locks.update");
    var current = {}; (u.locks || []).forEach(function (l) { current[l.flag] = l; });
    var inh = {}; (u.inheritedLocks || []).forEach(function (l) { inh[l.flag] = l; });
    S.title(u.name, "Rechte & Sperren", h("a", { class: "btn ghost sm", href: "#/access-locks" }, icon("chevLeft"), h("span", { class: "hide-mobile", text: "Übersicht" })));
    var picked = {}, scope = "default", dur = durationSelect(d.durations, "1d"), reason = S.input({ placeholder: "Grund – sieht der Spieler (optional)", maxlength: 200 });
    var flagsBox = h("div", { class: "lock-grid" });
    function renderFlags() {
      S.clear(flagsBox);
      (d.flags || []).forEach(function (f) {
        var on = current[f.id], cb = h("input", { type: "checkbox", checked: !!picked[f.id], disabled: !canEdit });
        cb.addEventListener("change", function () { picked[f.id] = cb.checked; });
        flagsBox.appendChild(h("label", { class: "lock-flag" + (on ? " locked" : "") }, cb, h("div", { class: "grow" },
          h("div", { class: "row", style: { gap: "8px" } }, h("b", { text: f.title }), on ? S.badge("gesperrt · " + on.remaining, SCOPE_TONE[on.scope] || "danger", "lock") : inh[f.id] ? S.badge("geerbt", "accent", "link") : (u.legacy || {})[f.id] ? S.badge("alte Sperre", "warn", "lock") : S.badge("erlaubt", "ok")),
          h("small", { text: f.explain }),
          !on && inh[f.id] ? h("small", { class: "inherit-note", text: "Geerbt von " + inh[f.id].fromName + " · " + inh[f.id].scopeTitle + " · " + inh[f.id].remaining }) : null,
          (u.legacy || {})[f.id] ? h("small", { class: "legacy-note", text: "Alte Einzelsperre: " + u.legacy[f.id].map(function (x) { return x.name + " (" + x.remaining + ")"; }).join(", ") + " – wird mit „Aufheben“ entfernt" }) : null,
          on ? h("small", { class: "muted", text: on.scopeTitle + (on.reason ? " · Grund: " + on.reason : "") + (on.by ? " · von " + on.by.replace(/ \(.*\)$/, "").replace(/^(Simplex Panel|Discord)\//, "") : "") }) : null),
          on || (u.legacy || {})[f.id] ? S.btn("Aufheben", { size: "sm", icon: "unlock", disabled: !canEdit, onClick: function (e) { e.preventDefault(); return locks({ player: u.uuid, operation: "remove", flags: f.id }, f.title + " für " + u.name + " wieder erlauben?"); } }) : null));
      });
    }
    renderFlags();
    var scopes = S.segmented([["default", "Empfohlen"]].concat((d.scopes || []).map(function (s) { return [s.id, s.title]; })), scope, function (v) { scope = v; scopeHint.textContent = v === "default" ? "Jeder Bereich nutzt seine empfohlene Einstellung (z. B. Truhen: eigenes Land & Wildnis erlaubt)." : (d.scopes.filter(function (s) { return s.id === v; })[0] || {}).explain; });
    var scopeHint = h("p", { class: "muted small", text: "Jeder Bereich nutzt seine empfohlene Einstellung (z. B. Truhen: eigenes Land & Wildnis erlaubt)." });
    var apply = S.btn("Ausgewählte sperren", { kind: "primary", icon: "lock", disabled: !canEdit, onClick: function () {
      var flags = Object.keys(picked).filter(function (k) { return picked[k]; });
      if (!flags.length) { S.toast("Mindestens einen Bereich auswählen", "err"); return; }
      return locks({ player: u.uuid, operation: "set", flags: flags.join(","), scope: scope, duration: dur.value, reason: reason.value.trim() }, "Rechte von " + u.name + " sperren?", true);
    } });
    // 10.0.1: every remaining classic right (chat, PvP, dimensions …) in the same view, each with a duration.
    var groups = {}; (u.rights || []).forEach(function (r) { (groups[r.group] = groups[r.group] || []).push(r); });
    var rightsCard = !u.profile ? S.card("Weitere Rechte", { icon: "key" }, S.notice("Einzelrechte gibt es, sobald der Spieler einmal online war. Bereiche funktionieren schon jetzt.", "", "info"))
      : S.card("Weitere Rechte", { icon: "key", sub: "Chat, Befehle, PvP, Welten, Freeze … – sperren mit Dauer oder dauerhaft" }, h("div", { class: "stack" }, Object.keys(groups).map(function (g) {
        return h("div", { class: "perm-group" }, h("header", {}, g, h("span", { class: "muted tiny", text: groups[g].filter(function (r) { return r.blocked; }).length + " gesperrt" })),
          h("div", {}, groups[g].map(function (r) {
            return h("div", { class: "right-row" + (r.blocked ? " blocked" : "") }, h("div", { class: "grow" }, h("b", { text: r.name }), h("small", { text: r.blocked ? "gesperrt · " + r.remaining : "erlaubt" })),
              r.blocked ? S.btn("Erlauben", { size: "sm", icon: "unlock", disabled: !canEdit, onClick: function () { return locks({ player: u.uuid, operation: "right-allow", right: r.id }, r.name + " für " + u.name + " wieder erlauben?"); } })
                : S.btn("Sperren", { size: "sm", icon: "lock", disabled: !canEdit, onClick: function () { return blockRight(r); } }));
          })));
      })));
    function blockRight(r) {
      var sel = durationSelect(d.durations, "1d"), done = false, dlg;
      dlg = S.modal(r.name + " sperren", h("div", { class: "stack" }, h("p", { class: "muted", text: "Für " + u.name + ". Läuft automatisch ab – oder dauerhaft bis zur Freigabe." }), S.field("Dauer", sel)),
        [S.btn("Abbrechen", { onClick: function () { dlg.close(); } }), S.btn("Weiter", { kind: "primary", icon: "lock", onClick: async function () { if (done) return; done = true; dlg.close(); await locks({ player: u.uuid, operation: "right-block", right: r.id, duration: sel.value }, r.name + " sperren?", true); } })], { icon: "lock" });
    }
    // 10.0.2: rank (LuckPerms groups) and alt accounts in the same view.
    var rankCard = null;
    if (S.has("luckperms-v1") && S.can("data.luckperms")) {
      rankCard = S.card("Rang & Gruppen", { icon: "key", sub: "LuckPerms-Gruppen dieses Spielers" }, S.skeleton(1));
      (async function () {
        try {
          var lpUser = await S.api("data/luckperms", { query: { player: u.uuid }, quiet: true }), all = await S.api("data/luckperms", { quiet: true });
          var canRank = S.can("action.luckperms.edit") && S.full(), have = {};
          (lpUser.groups || []).forEach(function (g) { have[g.group] = true; });
          var sel = h("select", { class: "input" }, (all.groups || []).filter(function (g) { return !have[g.name]; }).map(function (g) { return h("option", { value: g.name, text: (g.displayName || g.name) + " (Gewicht " + g.weight + ")" }); }));
          var gdur = durationSelect(["", "1d", "7d", "30d"], "");
          async function lpRun(values, title) { var r = await S.runAction("luckperms.edit", Object.assign({ player: u.uuid }, values), { title: title }); if (r) setTimeout(S.reload, 900); }
          var body = h("div", { class: "stack" },
            lpUser.prefix || lpUser.suffix ? h("div", { class: "mc-preview" }, S.mcText(lpUser.prefix || ""), h("span", { class: "mc-name", text: u.name }), S.mcText(lpUser.suffix || "")) : null,
            h("div", { class: "row wrap" }, (lpUser.groups || []).length ? lpUser.groups.map(function (g) {
              return h("span", { class: "chip" + (g.group === lpUser.primary ? " on" : "") }, g.group + (g.expiry ? " · bis " + S.date(g.expiry) : ""),
                h("button", { type: "button", title: "Entfernen", disabled: !canRank, onClick: function () { lpRun({ operation: "user-parent-remove", group: g.group }, u.name + " aus „" + g.group + "“ entfernen?"); } }, icon("x")));
            }) : h("span", { class: "muted small", text: "Keine Gruppen" })),
            sel.options.length ? h("div", { class: "grid two" }, S.field("Gruppe vergeben", sel), S.field("Dauer", gdur)) : null,
            h("div", { class: "row wrap" }, sel.options.length ? S.btn("Gruppe vergeben", { kind: "primary", size: "sm", icon: "plus", disabled: !canRank, onClick: function () { return lpRun({ operation: gdur.value ? "user-parent-temp" : "user-parent-add", group: sel.value, duration: gdur.value }, u.name + " zu „" + sel.value + "“ hinzufügen?"); } }) : null,
              h("a", { class: "btn sm", href: "#/luckperms/player/" + u.uuid }, icon("edit"), "Präfix, Befördern & eigene Rechte")));
          rankCard.replaceChild(body, rankCard.lastChild);
        } catch (e) { rankCard.replaceChild(S.notice(e.message, "warn", "alert"), rankCard.lastChild); }
      })();
    }
    var heirs = {}; (u.heirs || []).forEach(function (x) { heirs[x.uuid] = true; });
    var altCard = S.card("Alt-Accounts", { icon: "users", sub: "Sperren gelten automatisch auch für bestätigte Alt-Accounts – auch für künftig erkannte" }, h("div", { class: "stack" },
      h("div", { class: "row" }, h("b", { class: "grow", text: "Sperren auf Alt-Accounts übertragen" }), S.sw(u.inherit !== false, async function (on, el) {
        var r = await S.runAction("management.apply", { operation: "inherit", player: u.uuid, node: "", value: String(on) }, { title: on ? "Alt-Vererbung einschalten?" : "Alt-Vererbung ausschalten?" }).catch(function (e) { S.toast(e.message, "err"); return null; });
        if (r) setTimeout(S.reload, 500); else el.checked = !on;
      }, !(S.full() && S.can("action.management.apply")))),
      h("div", { class: "list" }, (u.alts || []).length ? u.alts.map(function (a) {
        return h("div", { class: "item link", onClick: function () { S.go("#/access-locks/" + a.uuid); } }, S.avatar(a.name, "sm"), h("div", { class: "meta" }, h("b", { text: a.name }), h("small", { text: (a.online ? "online · " : "") + (heirs[a.uuid] ? "erbt Sperren dieses Spielers" : a.inherit ? "erbt, sobald hier etwas gesperrt ist" : "Vererbung ausgeschaltet") })), icon("chevRight", "chev"));
      }) : h("div", { class: "item muted small", text: "Keine bestätigten Alt-Accounts." })),
      (u.inheritedLocks || []).length ? h("div", { class: "row wrap" }, u.inheritedLocks.map(function (l) { return S.badge(l.title + " · geerbt von " + l.fromName + " · " + l.remaining, "accent", "link"); })) : null));
    var presetDur = durationSelect(d.durations, "7d");
    var presetBox = h("div", { class: "grid two" }, (d.presets || []).map(function (p) {
      return h("div", { class: "preset" }, h("b", { text: p.title }), h("p", { class: "small muted", text: p.explain }),
        S.btn("Anwenden", { size: "sm", icon: "sparkles", disabled: !canEdit, onClick: function () { return locks({ player: u.uuid, operation: "preset", preset: p.id, duration: presetDur.value, reason: reason.value.trim() }, "Vorlage „" + p.title + "“ für " + u.name + "?", true); } }));
    }));
    var head = S.card(null, { cls: "hero" }, h("div", { class: "row wrap", style: { gap: "16px" } }, S.avatar(u.name, "lg"), h("div", { class: "grow" }, h("h2", { style: { margin: 0 }, text: u.name }),
      h("div", { class: "row wrap", style: { gap: "6px", marginTop: "6px" } }, u.online ? S.badge("Online", "ok") : S.badge("Offline"), (u.locks || []).length + (u.blockedRights || 0) + (u.inheritedLocks || []).length ? S.badge(((u.locks || []).length + (u.blockedRights || 0) + (u.inheritedLocks || []).length) + " Sperren aktiv", "danger", "lock") : S.badge("Alles erlaubt", "ok", "unlock"))),
      h("div", { class: "row lock-head-actions" }, S.can("data.profile") ? h("a", { class: "btn sm", href: "#/player/" + u.uuid }, icon("user"), "Profil") : null,
        S.has("luckperms-v1") && S.can("data.luckperms") ? h("a", { class: "btn sm", href: "#/luckperms/player/" + u.uuid }, icon("key"), "Rang") : null,
        (u.locks || []).length + (u.blockedRights || 0) ? S.btn("Alle aufheben", { size: "sm", kind: "danger", icon: "unlock", disabled: !canEdit, onClick: function () { return locks({ player: u.uuid, operation: "clear" }, "Alle Sperren von " + u.name + " aufheben – Bereiche und Einzelrechte?"); } }) : null)));
    S.mount(h("div", { class: "stack" }, head, !canEdit ? S.notice("Nur ansehen – zum Ändern wird action.locks.update benötigt.", "warn", "lock") : null,
      S.card("Bereiche mit Land-Option", { icon: "lock", sub: "Haken setzen, unten Gültigkeit und Dauer wählen, dann sperren" }, flagsBox),
      S.card("Gültigkeit & Dauer", { icon: "clock" }, h("div", { class: "stack lock-scope" }, S.field("Wo gilt die Sperre?", scopes), scopeHint, h("div", { class: "grid two" }, S.field("Dauer", dur), S.field("Grund", reason)), h("div", {}, apply))),
      rightsCard, rankCard, altCard,
      S.card("Vorlagen", { icon: "sparkles", sub: "Mehrere Sperren auf einmal" }, h("div", { class: "stack" }, S.field("Dauer für Vorlagen", presetDur), presetBox)),
      explainCard(d)));
  }

  // ============================================================ Changelog (once per account and version)
  var CL_ICONS = { lock: "lock", shield: "shieldCheck", map: "map", diamond: "sparkles", clock: "clock", crown: "key", key: "key", gavel: "flag" };
  S.showChangelog = async function (force) {
    if (!S.has("changelog-v1")) { if (force) S.toast("Benötigt Admin Suite 10.0.0", "err"); return; }
    var d; try { d = await S.api("changelog", { quiet: true }); } catch (e) { return; }
    if (!force && !d.unseen) return;
    var entries = d.entries || []; if (!entries.length) return;
    var body = changelogBody(entries);
    // Marked as seen as soon as it is shown: it appears exactly once per account and version, on any browser.
    S.api("changelog/seen", { body: { version: d.version }, quiet: true }).catch(function () {});
    var dlg = S.modal("Neu in Simplex " + d.version, body, [S.btn("Zu den Rechte-Sperren", { icon: "lock", onClick: function () { dlg.close(); S.go("#/access-locks"); } }), S.btn("Verstanden", { kind: "primary", icon: "check", onClick: function () { dlg.close(); } })],
      { wide: true, icon: "sparkles" });
  };
  function changelogBody(entries) {
    return h("div", { class: "stack changelog" }, entries.map(function (e, i) {
      return h("section", { class: "stack" }, h("div", { class: "row wrap", style: { gap: "8px" } }, h("h3", { style: { margin: 0 }, text: e.title }), S.badge("Suite " + e.version, i === 0 ? "accent" : ""), e.app ? S.badge("App " + e.app) : null, e.date ? h("span", { class: "muted small", text: e.date }) : null),
        e.intro ? h("p", { class: "muted", text: e.intro }) : null,
        h("div", { class: "cl-list" }, (e.items || []).map(function (it) { return h("div", { class: "cl-item" }, h("div", { class: "avatar device sm" }, icon(CL_ICONS[it.icon] || "sparkles")), h("div", {}, h("b", { text: it.title }), h("p", { class: "small", text: it.text }))); })));
    }));
  }
  P.changelog = async function (param, query, alive) {
    var d = await S.api("changelog"); if (!alive()) return;
    S.title("Neuigkeiten", "Admin Suite " + d.version);
    S.mount(h("div", { class: "stack" }, S.card(null, {}, changelogBody(d.entries || []))));
  };
})();
