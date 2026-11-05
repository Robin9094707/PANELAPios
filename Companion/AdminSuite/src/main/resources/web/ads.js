/* Simplex Panel Web 10.4.0 – Discord-Werbeplaner: Account verknüpfen, Werbetexte mit Discord-Formatierung und
   Live-Vorschau, Kanäle je Discord-Server mit eigenem Timer planen. Gepostet wird ausschließlich serverseitig. */
(function () {
  "use strict";
  var S = window.Simplex, h = S.h, icon = S.icon, P = S.pages = S.pages || {};

  // ============================================================ Discord markdown → DOM (no innerHTML)
  var ESC = "\\*_~`|<>#[]()-.!:@+";
  function emojiImg(name, id, animated) { return h("img", { class: "dm-emoji", alt: ":" + name + ":", title: ":" + name + ":", src: "https://cdn.discordapp.com/emojis/" + id + "." + (animated ? "gif" : "webp") + "?size=48", referrerpolicy: "no-referrer" }); }
  function stamp(sec, style) {
    var d = new Date(Number(sec) * 1000), o;
    switch (style) {
      case "t": o = { hour: "2-digit", minute: "2-digit" }; break;
      case "T": o = { hour: "2-digit", minute: "2-digit", second: "2-digit" }; break;
      case "d": o = { day: "2-digit", month: "2-digit", year: "numeric" }; break;
      case "D": o = { day: "numeric", month: "long", year: "numeric" }; break;
      case "F": o = { weekday: "long", day: "numeric", month: "long", year: "numeric", hour: "2-digit", minute: "2-digit" }; break;
      case "R": return S.ago(d.getTime()).replace("gerade eben", "jetzt");
      default: o = { day: "numeric", month: "long", year: "numeric", hour: "2-digit", minute: "2-digit" };
    }
    return d.toLocaleString("de-DE", o);
  }
  var RULES = [
    [/^\\([^A-Za-z0-9\s])/, function (m) { return document.createTextNode(m[1]); }],
    [/^``([\s\S]+?)``/, function (m) { return h("code", { class: "dm-code", text: m[1] }); }],
    [/^`([^`\n]+?)`/, function (m) { return h("code", { class: "dm-code", text: m[1] }); }],
    [/^\*\*\*([\s\S]+?)\*\*\*(?!\*)/, function (m) { return h("b", {}, h("i", {}, inline(m[1]))); }],
    [/^\*\*([\s\S]+?)\*\*(?!\*)/, function (m) { return h("b", {}, inline(m[1])); }],
    [/^__([\s\S]+?)__(?!_)/, function (m) { return h("u", {}, inline(m[1])); }],
    [/^\*(?=\S)([\s\S]*?\S)\*(?!\*)/, function (m) { return h("i", {}, inline(m[1])); }],
    [/^_([\s\S]+?)_(?![A-Za-z0-9_])/, function (m) { return h("i", {}, inline(m[1])); }],
    [/^~~([\s\S]+?)~~/, function (m) { return h("s", {}, inline(m[1])); }],
    [/^\|\|([\s\S]+?)\|\|/, function (m) { var sp = h("span", { class: "dm-spoiler", title: "Spoiler – zum Aufdecken klicken" }, inline(m[1])); sp.addEventListener("click", function () { sp.classList.add("open"); }); return sp; }],
    [/^\[([^\]\n]+)\]\(<?(https?:\/\/[^)\s>]+)>?\)/, function (m) { return h("a", { class: "dm-link", href: m[2], target: "_blank", rel: "noopener noreferrer", title: m[2] }, inline(m[1])); }],
    [/^<(https?:\/\/[^>\s]+)>/, function (m) { return h("a", { class: "dm-link", href: m[1], target: "_blank", rel: "noopener noreferrer", text: m[1] }); }],
    [/^https?:\/\/[^\s<]+[^\s<.,:;"')\]!?]/, function (m) { return h("a", { class: "dm-link", href: m[0], target: "_blank", rel: "noopener noreferrer", text: m[0] }); }],
    [/^<(a?):(\w{2,32}):(\d{15,25})>/, function (m) { return emojiImg(m[2], m[3], m[1] === "a"); }],
    [/^<@!?(\d{15,25})>/, function () { return h("span", { class: "dm-mention", text: "@Benutzer" }); }],
    [/^<@&(\d{15,25})>/, function () { return h("span", { class: "dm-mention", text: "@Rolle" }); }],
    [/^<#(\d{15,25})>/, function () { return h("span", { class: "dm-mention", text: "#Kanal" }); }],
    [/^<t:(-?\d{1,13})(?::([tTdDfFR]))?>/, function (m) { return h("span", { class: "dm-time", text: stamp(m[1], m[2]) }); }],
    [/^@(everyone|here)\b/, function (m) { return h("span", { class: "dm-mention muted-ping", title: "Pings sind beim Posten immer deaktiviert", text: "@" + m[1] }); }]
  ];
  var STARTS = "\\`*_~|[<h@";
  /** Inline markdown of one line or span. */
  function inline(text) {
    var frag = document.createDocumentFragment(), src = String(text || ""), buf = "", i = 0;
    function flush() { if (buf) { frag.appendChild(document.createTextNode(buf)); buf = ""; } }
    while (i < src.length) {
      var ch = src[i], done = false;
      if (STARTS.indexOf(ch) >= 0) {
        var rest = src.slice(i);
        for (var r = 0; r < RULES.length; r++) {
          var m = RULES[r][0].exec(rest);
          if (m && m[0].length) { flush(); frag.appendChild(RULES[r][1](m)); i += m[0].length; done = true; break; }
        }
      }
      if (!done) { buf += ch; i++; }
    }
    flush();
    return frag;
  }
  /** Block markdown: code fences, headings, -# subtext, quotes (> and >>>), lists. */
  function render(text, into) {
    var box = into || h("div", { class: "dm" }), lines = String(text || "").replace(/\r\n/g, "\n").split("\n"), quote = null, rest = false;
    function target() { return quote || box; }
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (/^```/.test(line.replace(/^>\s?/, "").trimStart())) {
        var inQuote = /^>\s?/.test(line) && !rest, raw = inQuote ? line.replace(/^>\s?/, "") : line, code = [], first = raw.trimStart().slice(3);
        var closed = first.indexOf("```") >= 0;
        if (closed) code.push(first.slice(0, first.indexOf("```")));
        else {
          var lang = /^[A-Za-z0-9+#-]{1,20}$/.test(first.trim()) ? first.trim() : ""; if (!lang && first) code.push(first);
          for (i++; i < lines.length; i++) { var l = inQuote ? lines[i].replace(/^>\s?/, "") : lines[i]; if (l.indexOf("```") >= 0) { code.push(l.slice(0, l.indexOf("```"))); break; } code.push(l); }
        }
        if (!inQuote && !rest) quote = null;
        target().appendChild(h("pre", { class: "dm-pre" }, h("code", { text: code.join("\n").replace(/\n$/, "") })));
        continue;
      }
      if (!rest && /^>>> ?/.test(line)) { quote = h("div", { class: "dm-quote" }); box.appendChild(quote); rest = true; line = line.replace(/^>>> ?/, ""); }
      else if (!rest && /^> ?/.test(line) && !/^>>>/.test(line)) { if (!quote) { quote = h("div", { class: "dm-quote" }); box.appendChild(quote); } line = line.replace(/^> ?/, ""); }
      else if (!rest) quote = null;
      var t = target(), hm;
      if ((hm = /^(#{1,3}) (.+)$/.exec(line))) t.appendChild(h("div", { class: "dm-h" + hm[1].length }, inline(hm[2])));
      else if ((hm = /^-# (.+)$/.exec(line))) t.appendChild(h("div", { class: "dm-sub" }, inline(hm[1])));
      else if ((hm = /^(\s*)[-*] (.+)$/.exec(line))) t.appendChild(h("div", { class: "dm-li", style: { paddingLeft: (16 + Math.min(4, Math.floor(hm[1].length / 2)) * 18) + "px" } }, h("span", { class: "dm-bullet", text: hm[1].length >= 2 ? "◦" : "•" }), inline(hm[2])));
      else if ((hm = /^(\s*)(\d{1,3})\. (.+)$/.exec(line))) t.appendChild(h("div", { class: "dm-li", style: { paddingLeft: (16 + Math.min(4, Math.floor(hm[1].length / 2)) * 18) + "px" } }, h("span", { class: "dm-bullet", text: hm[2] + "." }), inline(hm[3])));
      else if (!line.trim()) t.appendChild(h("div", { class: "dm-gap" }));
      else t.appendChild(h("div", { class: "dm-line" }, inline(line)));
    }
    return box;
  }
  S.discordMarkdown = render;

  /** A message by the linked account, as Discord would show it. */
  function messagePreview(content, account, channel, values) {
    var text = String(content || "").split("{online}").join((values && values.online) || "7").split("{max}").join((values && values.max) || "60").split("{date}").join(new Date().toLocaleDateString("de-DE", { day: "2-digit", month: "2-digit", year: "numeric" }));
    var name = account ? account.name : "Dein Account", avatar = account && account.avatar;
    var fallback = h("div", { class: "dc-avatar fallback", text: String(name).slice(0, 1).toUpperCase() });
    var img = avatar ? h("img", { class: "dc-avatar", src: avatar, alt: "", referrerpolicy: "no-referrer", onError: function () { img.replaceWith(fallback); } }) : fallback;
    var now = new Date().toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit" });
    return h("div", { class: "dc-window" },
      h("div", { class: "dc-channel" }, h("span", { class: "dc-hash", text: "#" }), h("b", { text: channel || "werbung" })),
      h("div", { class: "dc-msg" }, img, h("div", { class: "grow", style: { minWidth: 0 } }, h("div", { class: "dc-head" }, h("b", { text: name }), h("small", { text: "Heute um " + now })), render(text))));
  }

  // ============================================================ helpers
  function api(body) { return S.api("discord-ads", body ? { body: body } : undefined); }
  function human(min) {
    min = Number(min) || 0;
    if (min >= 1440 && min % 1440 === 0) return min / 1440 + (min === 1440 ? " Tag" : " Tage");
    if (min >= 60 && min % 60 === 0) return min / 60 + " Std";
    if (min > 60) return (min / 60).toLocaleString("de-DE", { maximumFractionDigits: 1 }) + " Std";
    return min + " Min";
  }
  function hours(min) { return (Number(min) / 60).toLocaleString("de-DE", { maximumFractionDigits: 2 }) + " Std"; }
  function windowText(c) { return c.windowToMinutes > 120 ? hours(c.windowFromMinutes) + " – " + hours(c.windowToMinutes) : Math.round(c.windowFromMinutes * 10) / 10 + " – " + Math.round(c.windowToMinutes * 10) / 10 + " Min"; }
  function until(ms) {
    var d = Number(ms) - Date.now(); if (!ms) return "–"; if (d <= 0) return "jetzt";
    if (d < 3600e3) return "in " + Math.max(1, Math.round(d / 60e3)) + " Min"; if (d < 86400e3) return "in " + (d / 3600e3).toLocaleString("de-DE", { maximumFractionDigits: 1 }) + " Std";
    return S.date(ms);
  }
  var RESULT = { sent: ["Gesendet", "ok"], slowmode: ["Slowmode – neu geplant", "info"], ratelimit: ["Discord bremst", "info"], retry: ["Neuer Versuch", "warn"], deleted: ["Gelöscht vom Server", "danger"],
    permission: ["Keine Rechte", "danger"], invalid: ["Text abgelehnt", "danger"], gone: ["Kanal weg", "danger"], failed: ["Fehlgeschlagen", "danger"], account: ["Account-Problem", "danger"] };
  var ACCOUNT = { ok: ["Verbunden", "ok"], invalid: ["Token ungültig", "danger"], captcha: ["Captcha nötig", "warn"], verify: ["Bestätigung nötig", "warn"], error: ["Fehler", "warn"], locked: ["Neu verknüpfen", "danger"], missing: ["Abgemeldet", "danger"] };
  var INTERVALS = [[5, "5 Min"], [15, "15 Min"], [30, "30 Min"], [60, "1 Std"], [120, "2 Std"], [180, "3 Std"], [360, "6 Std"], [720, "12 Std"], [1440, "24 Std"], [2880, "48 Std"], [10080, "7 Tage"]];
  function guildIcon(url, name, cls) {
    var fb = h("div", { class: "ad-gicon fallback " + (cls || ""), text: S.initials(name) });
    if (!url) return fb;
    var img = h("img", { class: "ad-gicon " + (cls || ""), src: url, alt: "", referrerpolicy: "no-referrer", onError: function () { img.replaceWith(fb); } });
    return img;
  }

  // ============================================================ formatting toolbar
  function toolbar(area, onChange) {
    function wrap(a, b, placeholder) {
      var s = area.selectionStart, e = area.selectionEnd, v = area.value, sel = v.slice(s, e) || placeholder || "";
      area.value = v.slice(0, s) + a + sel + b + v.slice(e); area.focus(); area.setSelectionRange(s + a.length, s + a.length + sel.length); onChange();
    }
    function prefix(p) {
      var s = area.selectionStart, e = area.selectionEnd, v = area.value, start = v.lastIndexOf("\n", s - 1) + 1, end = v.indexOf("\n", e); if (end < 0) end = v.length;
      var block = v.slice(start, end).split("\n").map(function (l) { return l.indexOf(p) === 0 ? l.slice(p.length) : p + l; }).join("\n");
      area.value = v.slice(0, start) + block + v.slice(end); area.focus(); area.setSelectionRange(start, start + block.length); onChange();
    }
    function insert(t) { var s = area.selectionStart, v = area.value; area.value = v.slice(0, s) + t + v.slice(area.selectionEnd); area.focus(); area.setSelectionRange(s + t.length, s + t.length); onChange(); }
    function b(label, title, fn, cls) { return h("button", { type: "button", class: "ad-tool " + (cls || ""), title: title, "aria-label": title, text: label, onClick: function (e) { e.preventDefault(); fn(); } }); }
    var emojis = ["🌍", "🖥️", "📱", "🎮", "👤", "💎", "🛡️", "♾️", "⚡", "🤝", "🔗", "📡", "⭐", "🔥", "✅", "🎉", "⚔️", "🏰", "💬", "📢"];
    var emojiRow = h("div", { class: "ad-emojis hidden" }, emojis.map(function (em) { return b(em, "Emoji einfügen", function () { insert(em); }, "emoji"); }));
    return h("div", { class: "stack", style: { gap: "6px" } },
      h("div", { class: "ad-toolbar", role: "toolbar", "aria-label": "Formatierung" },
        b("B", "Fett (**text**)", function () { wrap("**", "**", "fett"); }, "b"),
        b("I", "Kursiv (*text*)", function () { wrap("*", "*", "kursiv"); }, "i"),
        b("U", "Unterstrichen (__text__)", function () { wrap("__", "__", "unterstrichen"); }, "u"),
        b("S", "Durchgestrichen (~~text~~)", function () { wrap("~~", "~~", "durchgestrichen"); }, "s"),
        b("||", "Spoiler (||text||)", function () { wrap("||", "||", "spoiler"); }),
        b("</>", "Code (`text`)", function () { wrap("`", "`", "code"); }, "mono"),
        b("{ }", "Codeblock", function () { wrap("```\n", "\n```", "code"); }, "mono"),
        h("span", { class: "ad-sep" }),
        b("H1", "Große Überschrift (# )", function () { prefix("# "); }),
        b("H2", "Überschrift (## )", function () { prefix("## "); }),
        b("H3", "Kleine Überschrift (### )", function () { prefix("### "); }),
        b("-#", "Kleiner grauer Text (-# )", function () { prefix("-# "); }),
        b("❝", "Zitat (> )", function () { prefix("> "); }),
        b("•", "Liste (- )", function () { prefix("- "); }),
        h("span", { class: "ad-sep" }),
        b("🔗", "Link mit Text [Text](https://…)", function () { var url = window.prompt("Link-Adresse (https://…)", "https://"); if (url && /^https?:\/\//.test(url)) wrap("[", "](" + url + ")", "Linktext"); }),
        b("😀", "Emojis", function () { emojiRow.classList.toggle("hidden"); }),
        b("{online}", "Platzhalter: Spieler online", function () { insert("{online}"); }, "ph"),
        b("{max}", "Platzhalter: max. Spieler", function () { insert("{max}"); }, "ph")),
      emojiRow);
  }

  // ============================================================ template editor
  function templateEditor(data, template, done) {
    var t = template || { name: "", content: "", suppressEmbeds: false };
    var name = S.input({ value: t.name, maxlength: 60, placeholder: "z. B. Simplex SMP – Discord" });
    var area = h("textarea", { class: "input ad-area", rows: 16, spellcheck: "false", maxlength: data.limits.content, placeholder: "Werbetext mit Discord-Formatierung …" });
    area.value = t.content;
    var suppress = !!t.suppressEmbeds;
    var counter = h("span", { class: "badge" }), preview = h("div", { class: "ad-preview" }), timer = null;
    var acc = (data.accounts || [])[0];
    function update() {
      var n = area.value.length;
      counter.textContent = n + " / " + data.limits.discord;
      counter.className = "badge " + (n > data.limits.discord ? "danger" : n > data.limits.discord * 0.9 ? "warn" : "");
      clearTimeout(timer); timer = setTimeout(function () { S.clear(preview).appendChild(messagePreview(area.value, acc, "werbung")); }, 120);
    }
    area.addEventListener("input", update);
    var tooLong = h("small", { class: "muted", text: "Über " + data.limits.discord + " Zeichen geht nur mit Discord Nitro – sonst lehnt Discord den Post ab und der Kanal wird pausiert." });
    var body = h("div", { class: "ad-editor" },
      h("div", { class: "stack", style: { gap: "10px" } },
        S.field("Name", name),
        toolbar(area, update),
        area,
        h("div", { class: "row" }, counter, h("span", { class: "grow" }), h("label", { class: "check" }, S.sw(suppress, function (v) { suppress = v; }), h("span", { text: "Link-Vorschau unterdrücken" }))),
        tooLong,
        h("div", { class: "row", style: { flexWrap: "wrap" } }, btnCopy(function () { return area.value; }))),
      h("div", { class: "stack", style: { gap: "8px" } }, h("div", { class: "ad-label", text: "Vorschau" }), preview,
        h("small", { class: "muted", text: "Pings (@everyone/@here) werden beim Posten immer unterdrückt. Eigene Server-Emojis erscheinen nur, wenn der Account sie verwenden darf." })));
    update();
    var dlg = S.modal(template ? "Werbetext bearbeiten" : "Neuer Werbetext", body, [
      S.btn("Abbrechen", { onClick: function () { dlg.close(); } }),
      S.btn("Speichern", { kind: "primary", icon: "check", onClick: async function () {
        var r = await api({ operation: "template.save", id: template ? template.id : "", name: name.value.trim(), content: area.value, suppressEmbeds: suppress });
        S.toast(r.notice || "Gespeichert"); dlg.close(); done(r.overview);
      } })], { wide: true, icon: "edit" });
  }
  function btnCopy(get) { return S.btn("Text kopieren", { icon: "copy", size: "sm", onClick: function () { return S.copy(get(), "Werbetext"); } }); }

  // ============================================================ link account
  function linkDialog(data, account, done) {
    var token = S.input({ type: "password", placeholder: "Token des Zweitaccounts einfügen", autocomplete: "off" });
    var accept = h("input", { type: "checkbox" });
    var body = h("div", { class: "stack", style: { gap: "12px" } },
      S.notice("Automatisches Posten mit einem normalen Discord-Account (Self-Bot) verstößt gegen die Discord-Nutzungsbedingungen. Discord kann den Account sperren – verwende unbedingt einen Zweitaccount, nicht deinen Haupt-Account.", "danger"),
      account ? S.notice("Token für „" + account.name + "“ erneuern. Er muss zum selben Discord-Account gehören; alle Kanäle bleiben erhalten.", "info") : null,
      h("details", { class: "ad-howto" }, h("summary", { text: "Wie bekomme ich den Token?" }),
        h("ol", {}, h("li", { text: "Mit dem Zweitaccount im Browser auf discord.com/app anmelden." }), h("li", { text: "Entwicklertools öffnen (F12) → Reiter „Netzwerk“ → irgendeinen Kanal anklicken." }),
          h("li", { text: "Eine Anfrage an „discord.com/api“ wählen → Anfrage-Header → Wert von „Authorization“ kopieren." }),
          h("li", { text: "Den Token niemals jemandem geben. Wer ihn hat, kann den Account vollständig benutzen. Passwort ändern macht ihn ungültig." }))),
      S.field("Discord-Token", token, "Wird verschlüsselt auf dem Minecraft-Server gespeichert und nie wieder angezeigt."),
      account ? null : h("label", { class: "check" }, accept, h("span", { text: "Ich habe verstanden, dass der Account gesperrt werden kann, und poste nur dort, wo Werbung ausdrücklich erlaubt ist." })));
    var dlg = S.modal(account ? "Token erneuern" : "Discord-Account verknüpfen", body, [
      S.btn("Abbrechen", { onClick: function () { dlg.close(); } }),
      S.btn(account ? "Prüfen & erneuern" : "Prüfen & verknüpfen", { kind: "primary", icon: "link", onClick: async function () {
        if (!account && !accept.checked) { S.toast("Bitte den Hinweis bestätigen", "err"); return; }
        var r = await api(account ? { operation: "relink", account: account.id, token: token.value } : { operation: "link", token: token.value, accept: true });
        token.value = "";
        S.toast((account ? "Token erneuert: " : "Verknüpft: ") + r.name, "ok", r.reattached ? r.reattached + " frühere Kanäle wieder zugeordnet (pausiert)." : null);
        dlg.close(); done(r.overview);
      } })], { icon: "link" });
  }

  function unlinkDialog(account, done) {
    var logout = h("input", { type: "checkbox", checked: true }), del = h("input", { type: "checkbox" });
    var body = h("div", { class: "stack", style: { gap: "12px" } },
      h("p", { class: "muted", style: { margin: 0 }, text: "„" + account.name + "“ wird vom Server entfernt. " + (account.campaigns ? account.campaigns + " geplante Kanäle werden pausiert und laufen weiter, sobald du denselben Account wieder verknüpfst." : "") }),
      h("label", { class: "check" }, logout, h("span", { text: "Auch bei Discord abmelden (Token wird ungültig)" })),
      account.campaigns ? h("label", { class: "check" }, del, h("span", { text: "Geplante Kanäle dieses Accounts löschen" })) : null);
    var dlg = S.modal("Account abmelden", body, [S.btn("Abbrechen", { onClick: function () { dlg.close(); } }),
      S.btn("Abmelden", { kind: "danger solid", icon: "logout", onClick: async function () {
        var r = await api({ operation: "unlink", account: account.id, logout: logout.checked, deleteCampaigns: del.checked });
        S.toast(r.notice || "Abgemeldet"); dlg.close(); done(r.overview);
      } })], { icon: "logout" });
  }

  // ============================================================ campaign wizard
  function campaignWizard(data, existing, done) {
    var state = { account: existing ? existing.account : ((data.accounts.filter(function (a) { return a.status === "ok"; })[0] || data.accounts[0] || {}).id),
      guild: existing ? { id: existing.guild, name: existing.guildName, icon: existing.guildIcon } : null, channel: existing ? { id: existing.channel, name: existing.channelName, slowmode: existing.slowmodeSeconds, hint: "ad" } : null,
      template: existing ? existing.template : (data.templates[0] || {}).id, interval: existing ? existing.intervalMinutes : 360, first: "soon", notify: existing ? existing.notify : true,
      pauseAfter: existing ? existing.pauseAfter : data.settings.pauseAfter, enabled: existing ? existing.enabled : true, confirmed: false };
    var step = existing ? 3 : 1, host = h("div", { class: "ad-wizard" }), foot = h("div", { class: "row", style: { width: "100%" } });
    var dlg = S.modal(existing ? "Planung bearbeiten" : "Kanal planen", host, [foot], { wide: true, icon: "send" });
    function account() { return data.accounts.filter(function (a) { return a.id === state.account; })[0]; }
    function steps() {
      return h("div", { class: "ad-steps" }, [["1", "Server"], ["2", "Kanal"], ["3", "Text & Timer"], ["4", "Bestätigen"]].map(function (s, i) {
        return h("span", { class: "ad-step" + (i + 1 === step ? " on" : i + 1 < step ? " done" : "") }, h("b", { text: s[0] }), h("span", { text: s[1] }));
      }));
    }
    function go(n) { step = n; draw(); }
    async function draw() {
      S.clear(host); S.clear(foot);
      if (!existing) host.appendChild(steps());
      if (step === 1) await drawGuilds();
      else if (step === 2) await drawChannels();
      else if (step === 3) drawSettings();
      else drawConfirm();
    }
    async function drawGuilds() {
      var accSel = null;
      if (data.accounts.length > 1) {
        accSel = h("select", { class: "input" }, data.accounts.map(function (a) { return h("option", { value: a.id, selected: a.id === state.account, text: a.name + (a.status !== "ok" ? " (" + (ACCOUNT[a.status] || [a.status])[0] + ")" : "") }); }));
        accSel.addEventListener("change", function () { state.account = accSel.value; state.guild = null; drawGuilds(); });
      }
      var list = h("div", { class: "ad-guilds" }, S.skeleton(3)), filter = "";
      S.clear(host); host.appendChild(steps()); if (accSel) host.appendChild(S.field("Account", accSel));
      var search = S.searchBox("Server suchen …", function (v) { filter = v.toLowerCase(); paint(); });
      host.appendChild(h("div", { class: "row" }, search, S.btn("", { icon: "refresh", title: "Neu laden", size: "sm icon", onClick: function () { return load(true); } })));
      host.appendChild(list);
      var guilds = [];
      function paint() {
        S.clear(list);
        var shown = guilds.filter(function (g) { return !filter || g.name.toLowerCase().indexOf(filter) >= 0; });
        if (!shown.length) { list.appendChild(S.empty("search", "Keine Server", "Mit diesem Account bist du auf keinem passenden Server.")); return; }
        shown.forEach(function (g) {
          list.appendChild(h("button", { type: "button", class: "ad-guild" + (state.guild && state.guild.id === g.id ? " on" : ""), onClick: function () { state.guild = g; state.channel = null; go(2); } },
            guildIcon(g.icon, g.name), h("div", { class: "grow" }, h("b", { text: g.name }), h("small", { text: (g.members ? S.num(g.members) + " Mitglieder" : "") + (g.campaigns ? " · " + g.campaigns + " geplant" : "") }))));
        });
      }
      async function load(refresh) {
        try { var r = await S.api("discord-ads/guilds", { query: { account: state.account, refresh: refresh ? "true" : "" } }); guilds = r.guilds; paint(); }
        catch (e) { S.clear(list).appendChild(S.notice(e.message, "danger")); }
      }
      foot.appendChild(h("span", { class: "grow" })); foot.appendChild(S.btn("Abbrechen", { onClick: function () { dlg.close(); } }));
      await load(false);
    }
    async function drawChannels() {
      var list = h("div", { class: "ad-channels" }, S.skeleton(4));
      host.appendChild(h("div", { class: "row" }, guildIcon(state.guild.icon, state.guild.name), h("b", { class: "grow", text: state.guild.name })));
      host.appendChild(list);
      foot.appendChild(S.btn("Zurück", { icon: "chevLeft", onClick: function () { go(1); } })); foot.appendChild(h("span", { class: "grow" }));
      try {
        var r = await S.api("discord-ads/channels", { query: { account: state.account, guild: state.guild.id } });
        S.clear(list);
        list.appendChild(S.notice("Geplant: " + r.used + " von " + r.limit + " Kanälen auf diesem Server." + (r.permissionsCertain ? "" : " Rollen konnten nicht gelesen werden – Schreibrechte sind geschätzt."), r.used >= r.limit ? "warn" : "info"));
        var cat = null;
        r.channels.forEach(function (c) {
          if (c.category !== cat) { cat = c.category; list.appendChild(h("div", { class: "ad-label", text: cat || "Ohne Kategorie" })); }
          var disabled = !c.canSend || !c.supported || (c.campaign && (!existing || c.campaign !== existing.id)) || (r.used >= r.limit && !c.campaign);
          var why = !c.supported ? "Forum – nicht unterstützt" : !c.canView ? "Nicht sichtbar" : !c.canSend ? "Keine Schreibrechte" : c.campaign ? "Bereits geplant" : r.used >= r.limit ? "Limit erreicht" : "";
          list.appendChild(h("button", { type: "button", class: "ad-channel" + (disabled ? " off" : ""), disabled: disabled, onClick: function () {
            state.channel = c; if (c.suggestedMinutes) state.interval = c.suggestedMinutes; else if (c.slowmode) state.interval = Math.max(state.interval, Math.ceil(c.slowmode / 60)); go(3);
          } },
            h("span", { class: "dc-hash", text: c.type === "announcement" ? "📢" : "#" }),
            h("div", { class: "grow" }, h("b", { text: c.name }), c.topic ? h("small", { text: c.topic }) : null),
            h("div", { class: "side" },
              c.slowmode ? S.badge("Slowmode " + human(Math.ceil(c.slowmode / 60)), "info", "clock") : null,
              c.suggestedMinutes ? S.badge("Vorschlag " + human(c.suggestedMinutes), "accent") : null,
              why ? S.badge(why, c.campaign ? "info" : "") : c.hint === "ad" ? S.badge("Werbekanal?", "ok") : c.hint === "warn" ? S.badge("Kein Werbekanal?", "danger", "alert") : null)));
        });
      } catch (e) { S.clear(list).appendChild(S.notice(e.message, "danger")); }
    }
    function drawSettings() {
      var acc = account();
      host.appendChild(h("div", { class: "row" }, guildIcon(state.guild.icon, state.guild.name), h("b", { text: state.guild.name }), h("span", { class: "muted", text: "›" }), h("b", { text: "#" + state.channel.name })));
      var tplSel = h("select", { class: "input" }, data.templates.map(function (t) { return h("option", { value: t.id, selected: t.id === state.template, text: t.name + " (" + t.length + " Zeichen)" }); }));
      var preview = h("div", { class: "ad-preview" });
      function paintPreview() { var t = data.templates.filter(function (x) { return x.id === state.template; })[0]; S.clear(preview).appendChild(messagePreview(t ? t.content : "", acc, state.channel.name)); }
      tplSel.addEventListener("change", function () { state.template = tplSel.value; paintPreview(); });
      var custom = S.input({ type: "number", min: 5, max: 10080, value: state.interval, style: { maxWidth: "120px" } });
      var info = h("small", { class: "muted" });
      function paintInfo() {
        var slow = Math.ceil((state.channel.slowmode || 0) / 60), eff = Math.max(state.interval, slow);
        info.textContent = "Gepostet wird immer etwas später als eingestellt (nie früher): bei " + human(eff) + " etwa " + (eff >= 120 ? hours(eff + Math.max(0.5, eff * 0.004)) + " – " + hours(eff + Math.min(60, Math.max(1.5, eff * 0.02))) : "+½ – 1½ Min") + "." + (slow > state.interval ? " Der Slowmode des Kanals (" + human(slow) + ") ist länger und wird eingehalten." : "");
      }
      var presets = S.segmented(INTERVALS.map(function (i) { return [String(i[0]), i[1]]; }), String(state.interval), function (v) { state.interval = Number(v); custom.value = v; paintInfo(); });
      custom.addEventListener("input", function () { var v = parseInt(custom.value, 10); if (v >= 5) { state.interval = v; paintInfo(); } });
      var firstSel = h("select", { class: "input" }, h("option", { value: "soon", text: "In 1–2 Minuten (wenn dein letzter Post dort lange genug her ist)" }), h("option", { value: "interval", text: "Erst nach Ablauf des Intervalls" }));
      var pause = h("select", { class: "input" }, [[0, "Nie automatisch pausieren"], [1, "Nach 1 Fehler"], [2, "Nach 2 Fehlern in Folge"], [3, "Nach 3 Fehlern in Folge"], [5, "Nach 5 Fehlern in Folge"], [10, "Nach 10 Fehlern in Folge"]].map(function (o) { return h("option", { value: o[0], selected: Number(state.pauseAfter) === o[0], text: o[1] }); }));
      pause.addEventListener("change", function () { state.pauseAfter = Number(pause.value); });
      firstSel.addEventListener("change", function () { state.first = firstSel.value; });
      host.appendChild(h("div", { class: "ad-editor" },
        h("div", { class: "stack", style: { gap: "12px" } },
          S.field("Werbetext", tplSel),
          S.field("Intervall für diesen Kanal", h("div", { class: "stack", style: { gap: "8px" } }, h("div", { class: "ad-scroll" }, presets), h("div", { class: "row" }, custom, h("span", { class: "muted small", text: "Minuten (eigener Wert)" })), info),
            state.channel.suggestedMinutes ? "Kanalname/Beschreibung deuten auf " + human(state.channel.suggestedMinutes) + " hin." : null),
          existing ? null : S.field("Erster Post", firstSel),
          h("label", { class: "check" }, S.sw(state.notify, function (v) { state.notify = v; }), h("span", { text: "Bark-Hinweis bei Fehlern" })),
          S.field("Bei Fehlern", pause),
          existing ? h("label", { class: "check" }, S.sw(state.enabled, function (v) { state.enabled = v; }), h("span", { text: "Aktiv" })) : null),
        h("div", { class: "stack", style: { gap: "8px" } }, h("div", { class: "ad-label", text: "Vorschau" }), preview)));
      paintPreview(); paintInfo();
      if (!existing) foot.appendChild(S.btn("Zurück", { icon: "chevLeft", onClick: function () { go(2); } }));
      foot.appendChild(h("span", { class: "grow" }));
      foot.appendChild(S.btn("Abbrechen", { onClick: function () { dlg.close(); } }));
      if (existing) foot.appendChild(S.btn("Speichern", { kind: "primary", icon: "check", onClick: save }));
      else foot.appendChild(S.btn("Weiter", { kind: "primary", icon: "chevRight", onClick: function () { if (!state.template) { S.toast("Bitte erst einen Werbetext anlegen", "err"); return; } go(4); } }));
    }
    function drawConfirm() {
      var c = state.channel, check = h("input", { type: "checkbox" });
      var warnName = c.hint === "warn";
      host.appendChild(h("div", { class: "ad-confirm" + (warnName ? " danger" : "") },
        h("div", { class: "row" }, guildIcon(state.guild.icon, state.guild.name, "big"), h("div", { class: "grow" }, h("small", { class: "muted", text: state.guild.name }), h("h3", { text: "#" + c.name }))),
        h("p", { text: "Ist das wirklich der richtige Kanal? Wenn Werbung hier nicht erlaubt ist – oder nur seltener als alle " + human(state.interval) + " –, kannst du vom Discord-Server ausgeschlossen oder gebannt werden." }),
        warnName ? S.notice("Der Kanalname wirkt nicht wie ein Werbekanal. Bitte besonders genau prüfen.", "danger") : null,
        c.topic ? h("div", { class: "ad-topic" }, h("b", { text: "Kanalbeschreibung" }), h("span", { text: c.topic })) : null,
        h("label", { class: "check" }, check, h("span", { text: "Ich habe die Regeln von „" + state.guild.name + "“ gelesen: In #" + c.name + " ist Werbung erlaubt, alle " + human(state.interval) + " ist in Ordnung." }))));
      foot.appendChild(S.btn("Zurück", { icon: "chevLeft", onClick: function () { go(3); } }));
      foot.appendChild(h("span", { class: "grow" }));
      foot.appendChild(S.btn("Planen", { kind: "primary", icon: "send", onClick: function () { if (!check.checked) { S.toast("Bitte bestätigen, dass Werbung hier erlaubt ist", "err"); return; } state.confirmed = true; return save(); } }));
    }
    async function save() {
      var body = { operation: "campaign.save", id: existing ? existing.id : "", account: state.account, guild: state.guild.id, channel: state.channel.id, template: state.template,
        intervalMinutes: state.interval, notify: state.notify, pauseAfter: state.pauseAfter, enabled: state.enabled, firstPost: state.first, confirm: state.confirmed };
      var r = await api(body); S.toast(r.notice || "Gespeichert"); dlg.close(); done(r.overview);
    }
    draw();
  }

  // ============================================================ page
  P["discord-ads"] = async function (param, query, alive) {
    if (!S.can("data.discord-ads")) { S.mount(S.lockedPage("Discord-Werbung gesperrt", "data.discord-ads")); return; }
    var data = await api(); if (!alive()) return;
    var tick = null;
    function rerender(next) { if (next) data = next; if (alive()) paint(); }
    async function act(body) { var r = await api(body); if (r.notice) S.toast(r.notice); rerender(r.overview); }
    function accountsCard() {
      var list = h("div", { class: "list" });
      if (!data.accounts.length) list.appendChild(S.empty("link", "Noch kein Discord-Account", "Verknüpfe einen Zweitaccount, mit dem die Werbung gepostet wird.", data.canManage ? S.btn("Account verknüpfen", { kind: "primary", icon: "plus", onClick: function () { linkDialog(data, null, rerender); } }) : null));
      data.accounts.forEach(function (a) {
        var st = ACCOUNT[a.status] || [a.status, "warn"];
        var fb = h("div", { class: "avatar", text: S.initials(a.name) });
        var img = h("img", { class: "avatar", src: a.avatar, alt: "", referrerpolicy: "no-referrer", onError: function () { img.replaceWith(fb); } });
        list.appendChild(h("div", { class: "item ad-acc" }, img,
          h("div", { class: "meta" }, h("b", { text: a.name }), h("small", { text: "@" + a.username + " · " + a.campaigns + " Kanäle · " + S.num(a.sent) + " Posts · geprüft " + S.ago(a.checkedAt) }), a.statusText ? h("p", { text: a.statusText }) : null),
          h("div", { class: "side" }, S.badge(st[0], st[1]),
            data.canManage ? S.btn("", { icon: "refresh", size: "sm icon", title: "Erneut prüfen", onClick: function () { return act({ operation: "check", account: a.id }); } }) : null,
            data.canManage ? S.btn("", { icon: "key", size: "sm icon", title: "Token erneuern", onClick: function () { linkDialog(data, a, rerender); } }) : null,
            data.canManage ? S.btn("", { icon: "logout", size: "sm icon", title: "Abmelden", onClick: function () { unlinkDialog(a, rerender); } }) : null)));
      });
      return S.card("Discord-Account", { icon: "user", sub: "Mit diesem Account wird gepostet – Token liegt nur verschlüsselt auf dem Server.",
        actions: data.canManage && data.accounts.length && data.accounts.length < data.limits.accounts ? S.btn("Weiteren verknüpfen", { size: "sm", icon: "plus", onClick: function () { linkDialog(data, null, rerender); } }) : null }, list);
    }
    function campaignRow(c) {
      var res = RESULT[c.lastResult], open = false, hist = h("div", { class: "ad-history hidden" });
      var stateBadge = c.state === "active" ? S.badge("Aktiv", "ok") : c.state === "account" ? S.badge("Account prüfen", "danger", "alert") : S.badge("Pausiert", "warn");
      var row = h("div", { class: "item ad-camp" + (c.enabled ? "" : " paused") },
        h("span", { class: "dc-hash big", text: "#" }),
        h("div", { class: "meta" },
          h("b", { text: c.channelName + (c.category ? "  ·  " + c.category : "") }),
          h("small", { text: "„" + c.templateName + "“ · alle " + human(c.intervalMinutes) + (c.effectiveMinutes > c.intervalMinutes ? " (Slowmode " + human(c.effectiveMinutes) + ")" : "") + " · Abstand " + windowText(c) }),
          c.enabled ? h("small", { text: "Nächster Post " + until(c.nextRunAt) + " (" + S.date(c.nextRunAt) + ")" + (c.lastSentAt ? " · zuletzt " + S.ago(c.lastSentAt) : "") }) : h("p", { text: c.pausedReason || "Pausiert" }),
          c.lastError && c.lastResult !== "sent" ? h("p", { class: "ad-err", text: c.lastError }) : null),
        h("div", { class: "side ad-side" }, stateBadge, res ? S.badge(res[0], res[1]) : null,
          h("small", { class: "muted", text: c.sentCount + " gesendet" + (c.failedCount ? " · " + c.failedCount + " Fehler" : "") }),
          h("div", { class: "row", style: { gap: "4px" } },
            S.btn("", { icon: "clock", size: "sm icon", title: "Verlauf", onClick: function () { open = !open; hist.classList.toggle("hidden", !open); } }),
            h("a", { class: "btn sm icon", href: c.link, target: "_blank", rel: "noopener noreferrer", title: "In Discord öffnen" }, icon("external")),
            data.canManage ? S.btn("", { icon: "edit", size: "sm icon", title: "Bearbeiten", onClick: function () { campaignWizard(data, c, rerender); } }) : null,
            data.canManage ? (c.enabled ? S.btn("", { icon: "x", size: "sm icon", title: "Pausieren", onClick: function () { return act({ operation: "campaign.pause", id: c.id }); } })
              : S.btn("", { icon: "check", size: "sm icon", title: "Fortsetzen", onClick: function () { return act({ operation: "campaign.resume", id: c.id }); } })) : null,
            data.canManage && c.enabled ? S.btn("", { icon: "send", size: "sm icon", title: c.earliestManual > Date.now() ? "Frühestens " + S.date(c.earliestManual) : "Jetzt senden", disabled: c.earliestManual > Date.now(),
              onClick: async function () { if (await S.confirm("Jetzt senden?", "Die Werbung wird in wenigen Sekunden in **#" + c.channelName + "** gepostet. Danach läuft der Timer neu.", "Senden")) return act({ operation: "campaign.send-now", id: c.id }); } }) : null,
            data.canManage ? S.btn("", { icon: "trash", size: "sm icon", title: "Löschen", onClick: async function () { if (await S.confirm("Planung löschen?", "#" + c.channelName + " auf " + c.guildName + " wird nicht mehr beworben. Bereits gesendete Posts bleiben in Discord.", "Löschen", true)) return act({ operation: "campaign.delete", id: c.id }); } }) : null)));
      (c.history || []).forEach(function (e) { var k = RESULT[e.kind] || { created: ["Geplant", "accent"], edited: ["Geändert", "accent"], paused: ["Pausiert", "warn"], resumed: ["Fortgesetzt", "ok"], postponed: ["Verschoben", "info"], info: ["Info", ""] }[e.kind] || [e.kind, ""];
        hist.appendChild(h("div", { class: "ad-hrow" }, h("small", { class: "muted mono", text: S.date(e.time) }), S.badge(k[0], k[1]), h("span", { text: e.text }))); });
      if (!(c.history || []).length) hist.appendChild(h("small", { class: "muted", text: "Noch kein Verlauf." }));
      return h("div", {}, row, hist);
    }
    function campaignsCard() {
      var groups = {}, order = [];
      data.campaigns.forEach(function (c) { if (!groups[c.guild]) { groups[c.guild] = []; order.push(c.guild); } groups[c.guild].push(c); });
      var body = h("div", { class: "stack", style: { gap: "12px" } });
      if (!data.campaigns.length) body.appendChild(S.empty("send", "Noch keine Kanäle geplant", data.accounts.length ? "Wähle einen Discord-Server und bis zu " + data.settings.maxPerGuild + " Werbekanäle mit eigenem Timer." : "Zuerst einen Discord-Account verknüpfen."));
      order.forEach(function (gid) {
        var g = groups[gid][0], list = h("div", { class: "list ad-list" });
        groups[gid].forEach(function (c) { list.appendChild(campaignRow(c)); });
        body.appendChild(h("div", { class: "ad-group" }, h("div", { class: "ad-ghead" }, guildIcon(g.guildIcon, g.guildName), h("b", { class: "grow", text: g.guildName }), h("small", { class: "muted", text: groups[gid].length + " / " + data.settings.maxPerGuild + " Kanäle · " + g.accountName })), list));
      });
      return S.card("Geplante Kanäle", { icon: "send", sub: "Jeder Kanal hat seinen eigenen Timer. Neustarts setzen nichts zurück.",
        actions: data.canManage && data.accounts.length ? S.btn("Kanal planen", { kind: "primary", size: "sm", icon: "plus", onClick: function () { campaignWizard(data, null, rerender); } }) : null }, body);
    }
    function templatesCard() {
      var list = h("div", { class: "list" });
      data.templates.forEach(function (t) {
        list.appendChild(h("div", { class: "item" }, h("div", { class: "meta" }, h("b", { text: t.name }), h("small", { text: t.length + " Zeichen" + (t.length > data.limits.discord ? " (nur mit Nitro)" : "") + (t.usedBy.length ? " · in #" + t.usedBy.join(", #") : " · nicht verwendet") + (t.preset ? " · Vorlage" : " · " + S.ago(t.updatedAt)) })),
          h("div", { class: "side" },
            S.btn("", { icon: "eye", size: "sm icon", title: "Vorschau", onClick: function () { S.modal(t.name, h("div", { class: "stack" }, messagePreview(t.content, data.accounts[0], "werbung"), h("div", { class: "row" }, btnCopy(function () { return t.content; }))), [], { wide: true, icon: "eye" }); } }),
            S.btn("", { icon: "copy", size: "sm icon", title: "Text kopieren", onClick: function () { return S.copy(t.content, "Werbetext"); } }),
            data.canManage ? S.btn("", { icon: "edit", size: "sm icon", title: "Bearbeiten", onClick: function () { templateEditor(data, t, rerender); } }) : null,
            data.canManage ? S.btn("", { icon: "trash", size: "sm icon", title: "Löschen", disabled: t.usedBy.length > 0, onClick: async function () { if (await S.confirm("Werbetext löschen?", "„" + t.name + "“ wird entfernt.", "Löschen", true)) return act({ operation: "template.delete", id: t.id }); } }) : null)));
      });
      return S.card("Werbetexte", { icon: "edit", sub: "Mit Discord-Formatierung und Vorschau. Jeder Kanal kann einen anderen Text verwenden.",
        actions: data.canManage ? S.btn("Neuer Werbetext", { size: "sm", icon: "plus", onClick: function () { templateEditor(data, null, rerender); } }) : null }, list);
    }
    function settingsCard() {
      var st = data.settings, dis = !data.canManage;
      function save(patch) { return act(Object.assign({ operation: "settings.save" }, patch)); }
      function sel(value, options, key) { var s = h("select", { class: "input", disabled: dis }, options.map(function (o) { return h("option", { value: o[0], selected: Number(value) === o[0], text: o[1] }); })); s.addEventListener("change", function () { var p = {}; p[key] = Number(s.value); save(p); }); return s; }
      return S.card("Einstellungen", { icon: "settings" },
        h("div", { class: "stack", style: { gap: "10px" } },
          h("div", { class: "right-row" }, h("span", { class: "grow", text: "Bark-Hinweise bei Fehlern und Pausen" }), S.sw(st.notify, function (v) { save({ notify: v }); }, dis)),
          h("div", { class: "right-row" }, h("span", { class: "grow", text: "Prüfen, ob der Post nach 3 Min noch da ist (Timer-Bots)" }), S.sw(st.verifyDelete, function (v) { save({ verifyDelete: v }); }, dis)),
          h("div", { class: "right-row" }, h("span", { class: "grow", text: "Vor dem Posten nach eigenem letzten Post im Kanal suchen" }), S.sw(st.checkOwnPosts, function (v) { save({ checkOwnPosts: v }); }, dis)),
          S.field("Standard für neue Kanäle: automatisch pausieren", sel(st.pauseAfter, [[0, "Nie"], [1, "Nach 1 Fehler"], [2, "Nach 2 Fehlern"], [3, "Nach 3 Fehlern"], [5, "Nach 5 Fehlern"]], "pauseAfter")),
          S.field("Mindestabstand zwischen zwei Posts des Accounts", sel(st.gapSeconds, [[30, "30 Sekunden"], [60, "1 Minute"], [120, "2 Minuten"], [300, "5 Minuten"], [600, "10 Minuten"]], "gapSeconds")),
          S.field("Höchstens Kanäle pro Discord-Server", sel(st.maxPerGuild, [[1, "1"], [2, "2"], [3, "3"], [4, "4"], [6, "6"], [8, "8"], [10, "10"]], "maxPerGuild")),
          h("small", { class: "muted", text: "Bark: In den Bark-Einstellungen erscheint „Discord-Werbung: Fehler & Pausen“ – ohne eigene Regel kommen diese Hinweise bei allen mit Leserecht an." })));
    }
    function logCard() {
      var list = h("div", { class: "list" });
      (data.log || []).slice(0, 30).forEach(function (e) {
        var k = { sent: ["Gesendet", "ok"], failed: ["Fehler", "danger"], created: ["Geplant", "accent"], edited: ["Geändert", "accent"], deleted: ["Gelöscht", ""], account: ["Account", "info"] }[e.kind] || [e.kind, ""];
        list.appendChild(h("div", { class: "item" }, S.badge(k[0], k[1]), h("div", { class: "meta" }, h("small", { text: S.date(e.time) }), h("p", { style: { marginTop: 0 }, text: e.text }))));
      });
      if (!(data.log || []).length) list.appendChild(S.empty("clock", "Noch nichts passiert"));
      return S.card("Verlauf", { icon: "clock" }, list);
    }
    function paint() {
      var st = data.stats;
      var newText = data.canManage ? S.btn("Werbetext", { icon: "plus", title: "Neuer Werbetext", onClick: function () { templateEditor(data, null, rerender); } }) : null;
      if (newText) newText.querySelector("span").classList.add("ad-title-label");
      S.title("Discord-Werbung", "Studio", h("div", { class: "row" }, newText,
        data.canManage && data.accounts.length ? S.btn("Planen", { kind: "primary", icon: "send", title: "Kanal planen", onClick: function () { campaignWizard(data, null, rerender); } }) : null));
      function kpi(label, value, ic, tone, hint) { return h("section", { class: "card kpi" }, h("div", { class: "ico " + (tone || "") }, icon(ic)), h("div", { class: "label", text: label }), h("div", { class: "value", text: String(value) }), hint ? h("div", { class: "hint", text: hint }) : null); }
      S.mount(h("div", { class: "stack" },
        data.canManage ? null : S.notice("Nur ansehen – zum Ändern ist das Recht „Discord-Werbung verwalten“ nötig.", "info", "lock"),
        data.problem ? S.notice("Planer meldet einen Fehler: " + data.problem + " – Serverprotokoll prüfen.", "warn") : null,
        h("div", { class: "grid kpis" }, kpi("Aktive Kanäle", st.active, "send", "g"), kpi("Pausiert", st.paused, "clock", "y"), kpi("Gesendet (24 h)", st.sent24h, "check", ""), kpi("Fehler (24 h)", st.failed24h, "alert", st.failed24h ? "r" : "v"),
          kpi("Nächster Post", st.nextRunAt ? until(st.nextRunAt) : "–", "clock", "v", st.nextRunAt ? S.date(st.nextRunAt) : null)),
        accountsCard(), campaignsCard(),
        h("div", { class: "grid two" }, templatesCard(), h("div", { class: "stack" }, settingsCard(), logCard())),
        h("small", { class: "muted", text: "Hinweis: Automatisches Posten mit einem Nutzer-Account verstößt gegen die Discord-Nutzungsbedingungen. Poste nur in Kanälen, in denen Werbung ausdrücklich erlaubt ist, und halte die Kanal-Timer ein." })));
    }
    paint();
    // Countdown and fresh state every 30 s while the page is open.
    tick = setInterval(async function () { if (!alive() || document.querySelector(".modal-bg")) { if (!alive()) clearInterval(tick); return; } try { var d = await api(); if (alive() && !document.querySelector(".modal-bg")) rerender(d); } catch (e) { /* keep last view */ } }, 30000);
  };
})();
