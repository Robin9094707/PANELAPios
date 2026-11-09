/* Simplex Panel Web 10.3.0 – Studio: Welten-Trimmer, Discord-Status-Designer und Scoreboard-Designer mit
   Live-Vorschau, Spieler-Steckbrief und Schnellsuche (Strg/⌘ + K). Alle Änderungen prüft und speichert der Server. */
(function () {
  "use strict";
  var S = window.Simplex, h = S.h, icon = S.icon, P = S.pages = S.pages || {};

  // ============================================================ Minecraft colour codes → DOM
  var MC = { "0": "#000000", "1": "#0000AA", "2": "#00AA00", "3": "#00AAAA", "4": "#AA0000", "5": "#AA00AA", "6": "#FFAA00", "7": "#AAAAAA",
    "8": "#555555", "9": "#5555FF", a: "#55FF55", b: "#55FFFF", c: "#FF5555", d: "#FF55FF", e: "#FFFF55", f: "#FFFFFF" };
  /** Renders &-codes (incl. &x&R&R&G&G&B&B hex, &l &o &n &m &k &r) into styled spans. */
  function mcText(raw, base) {
    var out = h("span", { class: "mc" }), text = String(raw || "").replace(/§/g, "&"), st = { c: base || "#FFFFFF" }, buf = "";
    function flush() {
      if (!buf) return;
      var s = h("span", { text: buf, style: { color: st.c } });
      if (st.l) s.style.fontWeight = "700"; if (st.o) s.style.fontStyle = "italic";
      var deco = (st.n ? "underline " : "") + (st.m ? "line-through" : ""); if (deco) s.style.textDecoration = deco.trim();
      if (st.k) s.classList.add("mc-obf");
      out.appendChild(s); buf = "";
    }
    for (var i = 0; i < text.length; i++) {
      var ch = text[i];
      if (ch === "&" && i + 1 < text.length) {
        var code = text[i + 1].toLowerCase();
        if (code === "x" && /^(&[0-9a-fA-F]){6}$/.test(text.substr(i + 2, 12))) { flush(); var hex = ""; for (var j = 0; j < 6; j++) hex += text[i + 3 + j * 2]; st = { c: "#" + hex }; i += 13; continue; }
        if (MC[code]) { flush(); st = { c: MC[code] }; i++; continue; }
        if ("lonmk".indexOf(code) >= 0) { flush(); st[code] = true; i++; continue; }
        if (code === "r") { flush(); st = { c: base || "#FFFFFF" }; i++; continue; }
      }
      buf += ch;
    }
    flush();
    return out;
  }
  function stripMc(raw) { return String(raw || "").replace(/[&§]x(?:[&§][0-9a-fA-F]){6}/g, "").replace(/[&§][0-9a-fk-orA-FK-OR]/g, ""); }
  /** Hex gradient like the shipped scoreboard title. */
  function gradient(text, from, to, bold) {
    var a = parseInt(from.replace("#", ""), 16), b = parseInt(to.replace("#", ""), 16), chars = Array.from(text), n = Math.max(1, chars.length - 1), out = "";
    chars.forEach(function (c, i) {
      if (c === " ") { out += " "; return; }
      var t = i / n, r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t), g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t), bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
      var hex = [r, g, bl].map(function (v) { return ("0" + v.toString(16).toUpperCase()).slice(-2); }).join("");
      out += "&x" + hex.split("").map(function (d) { return "&" + d; }).join("") + (bold ? "&l" : "") + c;
    });
    return out;
  }
  S.mcText = mcText; S.mcGradient = gradient;

  // ============================================================ Discord markdown (subset) → DOM
  function dcInline(text) {
    var wrap = h("span"), src = String(text || ""), re = /(\*\*[^*]+\*\*|__[^_]+__|\*[^*\n]+\*|_[^_\n]+_|~~[^~]+~~|`[^`]+`|\[[^\]]+\]\([^)]+\))/g, last = 0, m;
    while ((m = re.exec(src))) {
      if (m.index > last) wrap.appendChild(document.createTextNode(src.slice(last, m.index)));
      var t = m[0], el;
      if (t.indexOf("**") === 0) el = h("b", {}, dcInline(t.slice(2, -2)));
      else if (t.indexOf("__") === 0) el = h("u", {}, dcInline(t.slice(2, -2)));
      else if (t.indexOf("~~") === 0) el = h("s", {}, dcInline(t.slice(2, -2)));
      else if (t[0] === "`") el = h("code", { text: t.slice(1, -1) });
      else if (t[0] === "[") el = h("span", { class: "dc-link", text: t.slice(1, t.indexOf("]")) });
      else el = h("i", {}, dcInline(t.slice(1, -1)));
      wrap.appendChild(el); last = m.index + t.length;
    }
    if (last < src.length) wrap.appendChild(document.createTextNode(src.slice(last)));
    return wrap;
  }
  function dcText(text) { var box = h("div", { class: "dc-md" }); String(text || "").split("\n").forEach(function (line, i) { if (i) box.appendChild(h("br")); box.appendChild(dcInline(line.replace(/\\([*_~`|])/g, "$1"))); }); return box; }
  /** External embed images are not loaded in the panel (strict CSP, no tracking); Discord shows them. */
  function extImage(cls, url) { var host = ""; try { host = new URL(url).host; } catch (e) { host = "Bild"; } return h("div", { class: cls + " dc-ext", title: url }, icon("image"), h("small", { text: host })); }
  function hexColor(n) { return "#" + ("000000" + (Number(n) || 0).toString(16)).slice(-6); }

  /** Discord-like message with one embed (dark theme), exactly from the server's payload. */
  function discordMessage(preview) {
    var msg = (preview && preview.message) || {}, embed = (msg.embeds || [])[0] || {}, bot = preview.botName || "Simplex";
    var fields = h("div", { class: "dc-fields" });
    (embed.fields || []).forEach(function (f) { fields.appendChild(h("div", { class: "dc-field" + (f.inline ? " inline" : "") }, h("div", { class: "dc-fname" }, dcInline(f.name)), h("div", { class: "dc-fvalue" }, dcText(f.value)))); });
    var stamp = embed.timestamp ? new Date(embed.timestamp).toLocaleString("de-DE", { hour: "2-digit", minute: "2-digit" }) : "";
    var card = h("div", { class: "dc-embed", style: { borderLeftColor: hexColor(embed.color) } },
      h("div", { class: "dc-embed-main" },
        embed.author ? h("div", { class: "dc-author", text: embed.author.name }) : null,
        embed.title ? h("div", { class: "dc-title" }, dcInline(embed.title)) : null,
        embed.description ? h("div", { class: "dc-desc" }, dcText(embed.description)) : null,
        (embed.fields || []).length ? fields : null,
        embed.image ? extImage("dc-image", embed.image.url) : null,
        embed.footer || stamp ? h("div", { class: "dc-footer", text: [embed.footer && embed.footer.text, stamp ? "Heute um " + stamp : ""].filter(Boolean).join(" • ") }) : null),
      embed.thumbnail ? extImage("dc-thumb", embed.thumbnail.url) : null);
    var fallback = h("div", { class: "dc-avatar fallback", text: String(bot).slice(0, 1).toUpperCase() });
    var avatar = preview.botAvatar ? h("img", { class: "dc-avatar", src: preview.botAvatar, alt: "", referrerpolicy: "no-referrer", onError: function () { avatar.replaceWith(fallback); } }) : fallback;
    return h("div", { class: "dc-window" },
      h("div", { class: "dc-channel" }, h("span", { class: "dc-hash", text: "#" }), h("b", { text: "server-status" }), h("span", { class: "dc-presence " + (preview.presenceStatus || "online") }), h("small", { text: (preview.activityType || "Schaut") + " " + (preview.activity || "") })),
      h("div", { class: "dc-msg" }, avatar, h("div", { class: "grow", style: { minWidth: 0 } },
        h("div", { class: "dc-head" }, h("b", { text: bot }), h("span", { class: "dc-bot", text: "APP" }), h("small", { text: stamp ? "Heute um " + stamp : "" })), card)));
  }

  // ============================================================ Discord-Status-Designer
  P["discord-status"] = async function (param, query, alive) {
    if (!S.can("data.discord-status")) { S.mount(S.lockedPage("Discord-Status gesperrt", "data.discord-status")); return; }
    var data = await S.api("studio/discord-status"); if (!alive()) return;
    var values = Object.assign({}, data.values), saved = JSON.stringify(data.values), state = "online", preset = null, timer = null, seq = 0;
    var previewBox = h("div", { class: "studio-preview" }, data.preview ? discordMessage(data.preview) : S.notice("Vorschau nicht verfügbar – Discord-Bot nicht geladen.", "warn"));
    var dirty = h("span", { class: "badge warn hidden", text: "Ungespeichert" });
    function changed() { dirty.classList.toggle("hidden", JSON.stringify(values) === saved); clearTimeout(timer); timer = setTimeout(refresh, 260); }
    async function refresh() {
      var mine = ++seq;
      try { var p = await S.api("studio/discord-status", { body: { operation: "preview", state: state, values: values }, quiet: true }); if (mine !== seq || !alive()) return; S.clear(previewBox).appendChild(discordMessage(p)); }
      catch (e) { if (mine === seq) { S.clear(previewBox).appendChild(S.notice(e.message, "danger")); } }
    }
    var inputs = {};
    function control(f) {
      var v = values[f.key], el;
      if (f.type === "bool") { el = S.sw(!!v, function (on) { values[f.key] = on; changed(); }, !data.canEdit); return h("div", { class: "right-row" }, h("span", { class: "grow", text: f.label }), el); }
      if (f.type === "color") {
        var picker = h("input", { type: "color", value: String(v || "#000000"), disabled: !data.canEdit, class: "color" });
        var hexIn = S.input({ value: String(v || ""), maxlength: 7, disabled: !data.canEdit, style: { maxWidth: "110px" } });
        picker.addEventListener("input", function () { hexIn.value = picker.value.toUpperCase(); values[f.key] = hexIn.value; changed(); });
        hexIn.addEventListener("input", function () { if (/^#[0-9a-fA-F]{6}$/.test(hexIn.value)) { picker.value = hexIn.value; values[f.key] = hexIn.value.toUpperCase(); changed(); } });
        inputs[f.key] = function (nv) { picker.value = nv; hexIn.value = nv; };
        return S.field(f.label, h("div", { class: "row" }, picker, hexIn));
      }
      if (f.type === "multiline") el = h("textarea", { class: "input", rows: 2, maxlength: f.max, disabled: !data.canEdit }, String(v == null ? "" : v));
      else el = S.input({ value: v == null ? "" : String(v), type: f.type === "number" || f.type === "int" ? "number" : "text", step: f.type === "number" ? "0.1" : "1", min: f.type === "number" || f.type === "int" ? f.min : null, max: f.type === "number" || f.type === "int" ? f.max : null, maxlength: f.type === "text" || f.type === "url" ? f.max : null, disabled: !data.canEdit, placeholder: f.type === "url" ? "https://…" : "" });
      if (f.type === "multiline") el.value = String(v == null ? "" : v);
      el.addEventListener("input", function () { values[f.key] = f.type === "int" ? parseInt(el.value || "0", 10) : f.type === "number" ? parseFloat(el.value || "0") : el.value; changed(); });
      inputs[f.key] = function (nv) { el.value = nv == null ? "" : String(nv); };
      return S.field(f.label, el);
    }
    var groups = {}, order = [];
    data.fields.forEach(function (f) { if (!groups[f.group]) { groups[f.group] = []; order.push(f.group); } groups[f.group].push(f); });
    var groupTab = order[0], editor = h("div", { class: "stack" });
    function renderGroup() { S.clear(editor); editor.appendChild(h("div", { class: "stack" }, groups[groupTab].map(control))); }
    renderGroup();
    function apply(p) {
      preset = p.id; values = Object.assign({}, data.defaults, p.values);
      Object.keys(inputs).forEach(function (k) { inputs[k](values[k]); }); renderGroup(); changed();
      S.toast("Vorlage „" + p.title + "“ übernommen", "ok", "Noch nicht gespeichert – erst prüfen, dann speichern.");
    }
    var presets = h("div", { class: "preset-row" }, data.presets.map(function (p) {
      var c = (p.values && p.values["healthy-color"]) || data.defaults["healthy-color"];
      return h("button", { type: "button", class: "preset", disabled: !data.canEdit, onClick: function () { apply(p); } }, h("span", { class: "swatch", style: { background: c } }), h("b", { text: p.title }), h("small", { text: p.explain }));
    }));
    var states = S.segmented(data.states.filter(function (s) { return s[0] !== "live"; }), state, function (v) { state = v; refresh(); });
    var save = data.canEdit ? S.btn("Speichern", { kind: "primary", icon: "check", onClick: async function () {
      var r = await S.api("studio/discord-status", { body: { operation: "save", values: values, preset: preset } });
      saved = JSON.stringify(values); data.values = Object.assign({}, values); dirty.classList.add("hidden"); S.toast(r.notice || "Gespeichert", "ok");
    } }) : null;
    var reset = data.canEdit ? S.btn("Verwerfen", { kind: "ghost", icon: "refresh", onClick: function () { values = JSON.parse(saved); Object.keys(inputs).forEach(function (k) { inputs[k](values[k]); }); renderGroup(); changed(); } }) : null;
    S.title("Discord-Status", "Studio", h("div", { class: "row" }, dirty, reset, save));
    var tabs = S.segmented(order.map(function (g) { return [g, g]; }), groupTab, function (g) { groupTab = g; renderGroup(); });
    S.mount(h("div", { class: "stack" },
      data.canEdit ? null : S.notice("Nur ansehen – zum Speichern ist das Recht „Discord-Status gestalten“ nötig.", "info", "lock"),
      S.card("Vorlagen", { icon: "palette", sub: "Standard = genau der bisherige Status. Vorlagen ändern erst beim Speichern etwas." }, presets),
      h("div", { class: "studio-grid" },
        S.card("Gestaltung", { icon: "edit", sub: "Platzhalter: {server} {players} {max} {tps} {seconds} {interval} {progress} · Discord-Markdown: **fett** _kursiv_ `code`" }, tabs, editor),
        S.card("Live-Vorschau", { icon: "eye", sub: "So erscheint die Nachricht im Statuskanal" + (data.preview && data.preview.channel ? "" : " (noch kein Statuskanal gesetzt)") }, states, previewBox))));
    refresh();
  };

  // ============================================================ Scoreboard-Designer
  function boardPreview(title, lines, sample, pointsEnabled) {
    function fill(t) { var out = String(t || ""); Object.keys(sample || {}).forEach(function (k) { out = out.split(k).join(sample[k]); }); return out; }
    var list = h("div", { class: "mc-lines" });
    (lines || []).slice(0, 15).forEach(function (l) { if (!pointsEnabled && String(l).indexOf("{points}") >= 0) return; list.appendChild(h("div", { class: "mc-line" }, mcText(fill(l)))); });
    return h("div", { class: "mc-scene" }, h("div", { class: "mc-board" }, h("div", { class: "mc-title" }, mcText(fill(title))), list));
  }
  P.scoreboard = async function (param, query, alive) {
    if (!S.can("data.scoreboard")) { S.mount(S.lockedPage("Scoreboard gesperrt", "data.scoreboard")); return; }
    var data = await S.api("studio/scoreboard"); if (!alive()) return;
    var title = data.title, lines = data.lines.slice(), preset = null, saved = JSON.stringify([data.title, data.lines]);
    var preview = h("div"), dirty = h("span", { class: "badge warn hidden", text: "Ungespeichert" });
    var titleIn = S.input({ value: title, maxlength: 600, disabled: !data.canEdit, class: "input mono" });
    var linesBox = h("div", { class: "stack", style: { gap: "6px" } });
    function draw() { S.clear(preview).appendChild(boardPreview(title, lines, data.sample, data.pointsEnabled)); dirty.classList.toggle("hidden", JSON.stringify([title, lines]) === saved); }
    titleIn.addEventListener("input", function () { title = titleIn.value; draw(); });
    var focused = null;
    function renderLines() {
      S.clear(linesBox);
      lines.forEach(function (l, i) {
        var inp = S.input({ value: l, maxlength: 180, disabled: !data.canEdit, class: "input mono" });
        inp.addEventListener("input", function () { lines[i] = inp.value; draw(); });
        inp.addEventListener("focus", function () { focused = inp; });
        linesBox.appendChild(h("div", { class: "row" }, h("span", { class: "muted tiny mono", style: { width: "18px" }, text: String(i + 1) }), inp,
          data.canEdit ? S.btn("", { kind: "ghost", size: "sm icon", icon: "chevDown", title: "Nach unten", disabled: i === lines.length - 1, onClick: function () { var t = lines[i]; lines[i] = lines[i + 1]; lines[i + 1] = t; renderLines(); draw(); } }) : null,
          data.canEdit ? S.btn("", { kind: "ghost", size: "sm icon", icon: "trash", title: "Zeile entfernen", disabled: lines.length <= 1, onClick: function () { lines.splice(i, 1); renderLines(); draw(); } }) : null));
      });
      if (data.canEdit && lines.length < data.maxLines) linesBox.appendChild(S.btn("Zeile hinzufügen", { size: "sm", icon: "plus", onClick: function () { lines.push("&7"); renderLines(); draw(); } }));
    }
    function insert(token) { var t = focused || titleIn; if (t.disabled) return; var s = t.selectionStart || t.value.length; t.value = t.value.slice(0, s) + token + t.value.slice(t.selectionEnd || s); t.dispatchEvent(new Event("input")); t.focus(); t.setSelectionRange(s + token.length, s + token.length); }
    var chips = h("div", { class: "row wrap" }, data.placeholders.map(function (p) { return h("button", { type: "button", class: "chip", title: p.label, disabled: !data.canEdit, onClick: function () { insert(p.key); } }, p.key); }));
    var colors = h("div", { class: "row wrap" }, Object.keys(MC).map(function (k) { return h("button", { type: "button", class: "chip color-chip", title: "&" + k, disabled: !data.canEdit, style: { background: MC[k] }, onClick: function () { insert("&" + k); } }); }),
      [["&l", "Fett"], ["&o", "Kursiv"], ["&n", "Unterstrichen"], ["&m", "Durchgestrichen"], ["&r", "Zurücksetzen"]].map(function (c) { return h("button", { type: "button", class: "chip", title: c[1], disabled: !data.canEdit, onClick: function () { insert(c[0]); } }, c[0]); }));
    var gText = S.input({ value: stripMc(title).trim() || "Simplex SMP", maxlength: 40 }), gFrom = h("input", { type: "color", value: "#00A8E8", class: "color" }), gTo = h("input", { type: "color", value: "#80BDF9", class: "color" }), gBold = h("input", { type: "checkbox", checked: true });
    var grad = h("div", { class: "row wrap" }, gText, gFrom, gTo, h("label", { class: "row small" }, gBold, "Fett"), S.btn("Als Titel", { size: "sm", icon: "sparkles", disabled: !data.canEdit, onClick: function () { titleIn.value = gradient(gText.value, gFrom.value, gTo.value, gBold.checked); titleIn.dispatchEvent(new Event("input")); } }));
    var presets = h("div", { class: "preset-row" }, data.presets.map(function (p) {
      return h("button", { type: "button", class: "preset", disabled: !data.canEdit, onClick: function () { preset = p.id; title = p.boardTitle; lines = p.lines.slice(); titleIn.value = title; renderLines(); draw(); S.toast("Vorlage „" + p.title + "“ übernommen", "ok", "Noch nicht gespeichert."); } },
        h("div", { class: "preset-mini" }, mcText(p.boardTitle)), h("b", { text: p.title }), h("small", { text: p.explain }));
    }));
    var save = data.canEdit ? S.btn("Speichern", { kind: "primary", icon: "check", onClick: async function () {
      var r = await S.api("studio/scoreboard", { body: { operation: "save", title: title, lines: lines, preset: preset } });
      lines = r.lines.slice(); title = r.title; saved = JSON.stringify([title, lines]); renderLines(); draw(); S.toast(r.notice || "Gespeichert", "ok");
    } }) : null;
    S.title("Scoreboard", "Studio", h("div", { class: "row" }, dirty, save));
    renderLines(); draw();
    S.mount(h("div", { class: "stack" },
      data.canEdit ? null : S.notice("Nur ansehen – zum Speichern ist das Recht „Scoreboard gestalten“ nötig.", "info", "lock"),
      data.enabled ? null : S.notice("Das Scoreboard ist in der Suite ausgeschaltet; Änderungen gelten, sobald es wieder an ist.", "warn"),
      S.card("Vorlagen", { icon: "palette", sub: "Standard = genau das bisherige Scoreboard." }, presets),
      h("div", { class: "studio-grid" },
        S.card("Gestaltung", { icon: "edit", sub: "Farbcodes mit &, Hex mit &x&R&R&G&G&B&B. Platzhalter antippen fügt sie ins zuletzt gewählte Feld ein." },
          S.field("Titel", titleIn), h("div", { class: "small muted", text: "Farbverlauf-Titel erzeugen" }), grad,
          h("div", { class: "small muted", text: "Zeilen (höchstens " + data.maxLines + ")" }), linesBox, h("div", { class: "small muted", text: "Platzhalter" }), chips, h("div", { class: "small muted", text: "Farben & Format" }), colors),
        S.card("Live-Vorschau", { icon: "eye", sub: "Mit echten Werten vom Server" + (data.sample && data.sample["{player}"] ? " (Beispiel: " + data.sample["{player}"] + ")" : "") }, preview))));
  };

  // ============================================================ Welten-Trimmer
  var PHASE = { IDLE: ["Bereit", "ok"], PREPARING: ["Sicherheitsprüfung", "info"], COUNTDOWN: ["Countdown", "warn"], RUNNING: ["Trimmt", "accent"], AWAITING_OVERRIDE: ["Entscheidung nötig", "danger"], RESTARTING: ["Neustart", "info"], RECOVERY: ["Prüfung nötig", "danger"] };
  var RESULT = { SUCCESS: ["Erfolgreich", "ok"], FAILED: ["Fehlgeschlagen", "danger"], CANCELLED: ["Abgebrochen", "warn"], INTERRUPTED: ["Unterbrochen", "warn"] };
  P["world-trim"] = async function (param, query, alive) {
    if (!S.can("data.world-trim")) { S.mount(S.lockedPage("Welten-Trimmer gesperrt", "data.world-trim")); return; }
    var st = await S.api("world-trim"); if (!alive()) return;
    var sel = {}; (st.selection || []).forEach(function (id) { sel[id] = true; });
    var form = { threshold: st.threshold, countdown: st.countdownChoice, snapshot: !!st.snapshotDefault };
    async function act(op, body) {
      var r = await S.api("world-trim", { body: Object.assign({ operation: op }, body || {}) });
      (r.messages || []).forEach(function (m) { S.toast(m, /abgebrochen|fehl|nicht|Unbekannt|fehlt/i.test(m) ? "err" : "ok"); });
      st = r; render();
    }
    function status() {
      var ph = PHASE[st.phase] || [st.phase, ""], busy = st.phase !== "IDLE";
      return S.card("Status", { icon: "activity", sub: st.status },
        h("div", { class: "row wrap", style: { gap: "10px" } }, S.badge(ph[0], ph[1]), st.enabled ? null : S.badge("In config.yml deaktiviert", "danger"), st.legacyPlugin ? S.badge("Alter SimplexTrimmer geladen", "danger") : null,
          S.badge("MCA Selector: " + (st.tool && st.tool.label || "–"), st.tool && st.tool.ready ? "ok" : "warn")),
        st.phase === "RUNNING" || st.phase === "COUNTDOWN" ? h("div", { class: "stack", style: { marginTop: "12px" } }, S.bar(st.phase === "RUNNING" ? st.progress : 0, 100, 101),
          S.kv([["Fortschritt", st.progress + " %"], ["Aktuell", st.target], ["Restzeit", st.eta], ["Countdown", st.phase === "COUNTDOWN" ? st.countdown + " s" : null], ["Gestartet von", st.actor]])) : null,
        st.phase === "AWAITING_OVERRIDE" ? S.notice(st.remainingLoadedChunks + " Chunks blieben nach allen Speicher-/Entlade-Versuchen geladen. Es wurden keine Regionsdateien verändert." + (st.canDecide ? "" : " Nur " + st.actor + " kann entscheiden."), "danger") : null,
        st.phase === "RECOVERY" ? S.notice("Der letzte Lauf wurde unterbrochen oder die Integritätsprüfung fand einen Fehler. Backup und Welt prüfen, dann freigeben.", "danger") : null,
        st.safety && st.safety.last ? h("div", { class: "small muted", style: { marginTop: "8px" }, text: "Letzte Sicherheitsprüfung: " + st.safety.last }) : null,
        h("div", { class: "row wrap", style: { marginTop: "12px" } },
          st.canRun && !busy ? S.btn("Welten neu erkennen", { icon: "refresh", disabled: st.scanning, onClick: function () { return act("scan"); } }) : null,
          st.canRun && !busy ? S.btn(st.tool && st.tool.ready ? "MCA Selector aktualisieren" : "MCA Selector laden", { icon: "download", disabled: st.downloading, onClick: function () { return act("download"); } }) : null,
          st.canRun && st.phase === "COUNTDOWN" ? S.btn("Countdown abbrechen", { kind: "danger", icon: "x", onClick: function () { return act("cancel"); } }) : null,
          st.canDecide ? S.btn("Sicher abbrechen", { icon: "x", onClick: function () { return act("abort"); } }) : null,
          st.canDecide ? S.btn("Trotzdem fortfahren", { kind: "danger solid", icon: "alert", onClick: async function () { if (await S.confirm("Notfall-Bypass", "MCA Selector startet trotz geladener Chunks. Nur fortfahren, wenn ein Backup existiert und kein Plugin die Welt gerade schreibt.", "Fortfahren", true)) return act("continue"); } }) : null,
          st.canRun && st.phase === "RECOVERY" ? S.btn("Geprüft – freigeben", { kind: "primary", icon: "check", onClick: async function () { if (await S.confirm("Recovery freigeben", "Bestätige, dass Backup und Serverstart geprüft wurden.", "Freigeben")) return act("recovery"); } }) : null));
    }
    function planner() {
      var total = 0, list = h("div", { class: "list" });
      (st.targets || []).forEach(function (t) {
        if (sel[t.id]) total += t.bytes;
        var box = h("input", { type: "checkbox", checked: !!sel[t.id], disabled: !st.canRun });
        box.addEventListener("change", function () { if (box.checked) sel[t.id] = true; else delete sel[t.id]; render(); });
        list.appendChild(h("label", { class: "item" }, box, h("div", { class: "meta" }, h("b", { text: t.label }), h("small", { text: [t.layout, t.poi ? "POI" : null, t.entities ? "Entities" : null].filter(Boolean).join(" · ") })), h("span", { class: "mono small", text: S.bytes(t.bytes) })));
      });
      if (!(st.targets || []).length) list.appendChild(S.empty("globe", "Keine Dimensionen erkannt", "Welten neu erkennen starten."));
      var thr = h("select", { class: "input", disabled: !st.canRun }, (st.thresholds || []).map(function (o) { return h("option", { value: o.ticks, selected: o.ticks === form.threshold, text: o.label + " (" + o.ticks + " Ticks)" }); }));
      thr.addEventListener("change", function () { form.threshold = Number(thr.value); });
      var cd = h("select", { class: "input", disabled: !st.canRun }, (st.countdowns || []).map(function (s) { return h("option", { value: s, selected: s === form.countdown, text: s ? s / 60 + " Minute" + (s > 60 ? "n" : "") : "Sofort" }); }));
      cd.addEventListener("change", function () { form.countdown = Number(cd.value); });
      var snapNeed = total + total / 10 + (st.minimumFreeBytes || 0), snapOk = st.freeBytes < 0 || st.freeBytes >= snapNeed;
      var snap = S.sw(form.snapshot && snapOk, function (on) { form.snapshot = on; }, !st.canRun || !snapOk);
      var backup = h("input", { type: "checkbox" }), word = S.input({ placeholder: "TRIMMEN eintippen", maxlength: 12, style: { maxWidth: "200px" } });
      var count = Object.keys(sel).length, can = st.canRun && st.phase === "IDLE" && st.enabled && count > 0;
      return S.card("Lauf planen", { icon: "scissors", sub: "Löscht Chunks, in denen Spieler kürzer als die Besuchsgrenze waren. Alle Spieler werden getrennt; danach startet der Server neu." },
        h("div", { class: "row wrap" }, st.canRun ? S.btn("Alle wählen", { size: "sm", onClick: function () { (st.targets || []).forEach(function (t) { sel[t.id] = true; }); render(); } }) : null, st.canRun ? S.btn("Keine", { size: "sm", kind: "ghost", onClick: function () { sel = {}; render(); } }) : null, h("span", { class: "grow" }), h("span", { class: "small muted", text: count + " gewählt · " + S.bytes(total) })),
        list,
        h("div", { class: "grid two" }, S.field("Besuchsgrenze", thr, "Chunks unter dieser Gesamt-Aufenthaltszeit werden gelöscht."), S.field("Countdown", cd, "Spieler werden vorher im Chat gewarnt.")),
        h("div", { class: "right-row" }, h("div", { class: "grow" }, h("b", { text: "Sicherheitskopie vor dem Trimmen" }), h("small", { class: "muted", text: snapOk ? "Kopiert die Regionsdateien (≈ " + S.bytes(total) + ") und stellt sie bei Fehler, Hänger oder defekter Datei automatisch wieder her. Frei: " + S.bytes(st.freeBytes) : "Zu wenig Speicher für die Kopie (benötigt ≈ " + S.bytes(snapNeed) + ", frei " + S.bytes(st.freeBytes) + ")." })), snap),
        st.canRun ? h("div", { class: "stack", style: { marginTop: "10px" } },
          h("label", { class: "row small" }, backup, "Ich habe ein vollständiges, aktuelles Backup der Welten."), h("div", { class: "row wrap" }, word,
          S.btn("Trimmen starten", { kind: "danger solid", icon: "scissors", disabled: !can, onClick: async function () {
            if (!backup.checked) throw new Error("Bitte das Backup bestätigen.");
            if (word.value.trim().toUpperCase() !== "TRIMMEN") throw new Error("Zur Sicherheit TRIMMEN eintippen.");
            return act("start", { targets: Object.keys(sel), threshold: form.threshold, countdown: form.countdown, snapshot: form.snapshot, backupConfirmed: true, confirm: "TRIMMEN" });
          } }))) : S.notice("Zum Starten ist das Recht „Welten-Trimmer ausführen“ nötig.", "info", "lock"));
    }
    function history() {
      var rows = st.history || [];
      return S.card("Verlauf", { icon: "list", sub: "Insgesamt freigegeben: " + S.bytes(st.totalSaved) }, rows.length ? h("div", { class: "list" }, rows.map(function (e) {
        var r = RESULT[e.status] || [e.status, ""];
        return h("div", { class: "item" }, h("div", { class: "meta" }, h("b", { text: S.date(e.finishedAt) + " · " + e.actor }), h("small", { text: (e.targets || []).join(", ") + (e.error ? " · " + e.error : "") })), h("span", { class: "mono small", text: e.saved ? "−" + S.bytes(e.saved) : "" }), S.badge(r[0], r[1]));
      })) : S.empty("list", "Noch keine Läufe"));
    }
    function render() { if (!alive()) return; S.mount(h("div", { class: "stack" }, status(), st.phase === "IDLE" ? planner() : null, history())); }
    S.title("Welten-Trimmer", "Server");
    render();
    S.every(4000, async function () { if (st.phase === "IDLE" && !st.scanning && !st.downloading) return; try { var n = await S.api("world-trim", { quiet: true }); if (!alive()) return; st = n; render(); } catch (e) { /* server restarting */ } });
  };

  // ============================================================ Player dossier (Steckbrief)
  var TONES = { ok: "ok", warn: "warn", danger: "danger", info: "info" };
  S.dossierCard = function (player) {
    var body = h("div", {}, S.skeleton(3)), filter = "", kind = "";
    var c = S.card("Steckbrief – alles über den Spieler", { icon: "id", sub: "Identität, Aktivität, Client, Bedrock-Gerät, Netzwerk, Moderation und Verlauf – je nach deinen Rechten" }, body);
    (async function () {
      try {
        var d = await S.api("player/dossier", { query: { player: player } });
        var search = S.searchBox("Im Steckbrief suchen …", function (v) { filter = v.toLowerCase(); draw(); });
        var grid = h("div", { class: "dossier" }), timeline = h("div", { class: "list" });
        var controls = d.whispersVisible ? S.segmented([["", "Alle"], ["public", "Öffentlich"], ["whisper", "Flüstern"]],
          "", function (value) { kind = value; draw(); }) : null;
        function draw() {
          S.clear(grid);
          d.sections.forEach(function (s) {
            var rows = (s.rows || []).filter(function (r) { return !filter || (r.label + " " + r.value).toLowerCase().indexOf(filter) >= 0; });
            var list = (s.list || []).filter(function (l) { return !filter || l.toLowerCase().indexOf(filter) >= 0; });
            if (!rows.length && !list.length) return;
            grid.appendChild(h("section", { class: "dossier-sec" }, h("div", { class: "dossier-head" }, icon(s.icon || "info"), h("b", { text: s.title })),
              h("dl", { class: "kv" }, rows.map(function (r) { return [h("dt", { text: r.label }), h("dd", { class: (r.mono ? "mono " : "") + (r.tone ? "tone-" + (TONES[r.tone] || "") : "") }, r.value, h("button", { type: "button", class: "copy-mini", title: "Kopieren", onClick: function () { S.copy(r.value, r.label); } }, icon("copy")))]; })),
              list.length ? h("details", { open: !!filter || list.length <= 6 }, h("summary", { text: list.length + " Einträge" }), h("ul", { class: "dossier-list" }, list.map(function (l) { return h("li", { text: l }); }))) : null));
          });
          if (!grid.children.length) grid.appendChild(S.empty("search", "Nichts gefunden"));
          S.clear(timeline);
          (d.chatTimeline || []).filter(function (row) {
            return (!kind || row.kind === kind) && (!filter || [row.from, row.to, row.text].join(" ").toLowerCase().includes(filter));
          }).forEach(function (row) {
            var whisper = row.kind === "whisper";
            timeline.appendChild(h("div", { class: "item" }, icon(whisper ? "lock" : "chat"),
              h("div", { class: "meta" },
                h("b", { text: row.from + (row.to ? " → " + row.to : "") }),
                h("p", { text: row.text }),
                h("small", { text: [whisper ? "Flüstern" : "Öffentlicher Chat",
                  row.at ? S.date(row.at) + " · " + S.ago(row.at) : ""].filter(Boolean).join(" · ") }))));
          });
          if (!timeline.children.length) timeline.appendChild(S.empty("chat", "Keine Nachrichten gefunden"));
        }
        draw();
        var tools = h("div", { class: "row wrap" }, search);
        S.clear(body).appendChild(h("div", { class: "stack" }, tools,
          d.chatVisible ? S.card("Chatverlauf", { icon: "chat", sub: "Neueste zuerst" }, h("div", { class: "stack" }, controls, timeline)) : null,
          grid));
        if (S.has("moderation-qol-v1") && S.can("ai.assistant.use")) {
          try {
            var ai = await S.api("assistant/status", { quiet: true });
            if (ai.available) tools.appendChild(h("a", { class: "btn ghost", href: "#/assistant?player=" + encodeURIComponent(d.player) },
              icon("sparkles"), h("span", { text: "KI zum Spieler fragen" })));
          } catch (e) { /* optional */ }
        }
      } catch (e) { S.clear(body).appendChild(S.notice(e.message, "danger")); }
    })();
    return c;
  };

  // ============================================================ Quick search (Ctrl/⌘ + K)
  function palette() {
    if (document.querySelector(".palette-bg")) return;
    var items = [];
    (S.nav || []).forEach(function (g) { g.items.forEach(function (it) { if (!S.denied || !S.denied(it.id)) items.push({ label: it.label, group: g.group, icon: it.icon, href: "#/" + it.id }); }); });
    var input = S.input({ placeholder: "Seite oder Spieler suchen …", class: "input palette-input" }), list = h("div", { class: "palette-list" }), active = 0, results = [], t;
    var bg = h("div", { class: "palette-bg", onMousedown: function (e) { if (e.target === bg) close(); } }, h("div", { class: "palette" }, h("div", { class: "row" }, icon("search"), input, h("kbd", { text: "Esc" })), list));
    function close() { bg.remove(); document.removeEventListener("keydown", keys, true); }
    function go(r) { close(); S.go(r.href); }
    function draw() {
      S.clear(list);
      results.forEach(function (r, i) { list.appendChild(h("button", { type: "button", class: "palette-item" + (i === active ? " on" : ""), onClick: function () { go(r); } }, icon(r.icon || "chevRight"), h("span", { class: "grow", text: r.label }), h("small", { text: r.group }))); });
      if (!results.length) list.appendChild(h("div", { class: "muted small", style: { padding: "12px" }, text: "Keine Treffer" }));
    }
    function search() {
      var q = input.value.trim().toLowerCase();
      results = items.filter(function (it) { return !q || (S.workspaceMatches ? S.workspaceMatches(q, it.label + " " + it.group) : (it.label + " " + it.group).toLowerCase().indexOf(q) >= 0); }).slice(0, 8);
      if (q) {
        results.push({ label: "Werkzeuge & Funktionen: " + q, group: "Überall suchen", icon: "grid", href: "#/tools?q=" + encodeURIComponent(q) });
        if (S.has("ad-hub-v1") && S.can("data.discord-ads")) results.push({ label: "Werbung durchsuchen: " + q, group: "Werbung", icon: "send", href: "#/ad-hub?q=" + encodeURIComponent(q) });
        if (S.has("history-management-v1")) results.push({ label: "Verläufe durchsuchen", group: "Protokolle & Chat", icon: "list", href: "#/histories" });
      }
      active = 0; draw();
      clearTimeout(t);
      if (q.length >= 2 && S.can("data.players")) t = setTimeout(async function () {
        try { var r = await S.api("data/players", { query: { q: q, limit: 6 }, quiet: true }); if (input.value.trim().toLowerCase() !== q) return;
          (r.items || []).forEach(function (p) { results.push({ label: p.name, group: p.online ? "Spieler · online" : "Spieler", icon: "user", href: "#/player/" + p.id }); }); draw(); } catch (e) { /* ignore */ }
      }, 180);
    }
    function keys(e) {
      if (e.key === "Escape") { e.preventDefault(); close(); }
      else if (e.key === "ArrowDown") { e.preventDefault(); active = Math.min(results.length - 1, active + 1); draw(); }
      else if (e.key === "ArrowUp") { e.preventDefault(); active = Math.max(0, active - 1); draw(); }
      else if (e.key === "Enter" && results[active]) { e.preventDefault(); go(results[active]); }
    }
    input.addEventListener("input", search);
    document.addEventListener("keydown", keys, true);
    document.body.appendChild(bg); search(); setTimeout(function () { input.focus(); }, 20);
  }
  S.palette = palette;
  document.addEventListener("keydown", function (e) { if ((e.ctrlKey || e.metaKey) && (e.key === "k" || e.key === "K") && S.health) { e.preventDefault(); palette(); } });
})();
