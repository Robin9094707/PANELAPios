/* Simplex Panel Web 10.5.0 – Werbe-Zentrale: Werbe-Orte (Discord-Kanäle, Reddit, Planet Minecraft, Facebook-Gruppen …)
   mit Link, passend formatiertem Werbetext und Intervall. Gepostet wird nie automatisch: Ist ein Ort dran, erinnert
   Bark; „Jetzt werben“ kopiert den Text und öffnet den Ort, danach startet der Timer leicht über dem Intervall neu. */
(function () {
  "use strict";
  var S = window.Simplex, h = S.h, icon = S.icon, P = S.pages = S.pages || {};

  // ============================================================ Discord markdown → DOM (no innerHTML)
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
    [/^@(everyone|here)\b/, function (m) { return h("span", { class: "dm-mention muted-ping", text: "@" + m[1] }); }]
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


  // ============================================================ other formats → Discord markdown (preview only)
  function bbToDiscord(s) {
    s = String(s || "");
    s = s.replace(/\[code\]\n?([\s\S]*?)\n?\[\/code\]/gi, "```\n$1\n```").replace(/\[url=([^\]]+)\]([\s\S]*?)\[\/url\]/gi, "[$2]($1)").replace(/\[url\]([\s\S]*?)\[\/url\]/gi, "$1").replace(/\[img\]([\s\S]*?)\[\/img\]/gi, "$1");
    s = s.replace(/\[size="?(\d+)(px|pt|%)?"?\]([\s\S]*?)\[\/size\]/gi, function (m, n, unit, inner) {
      n = Number(n); unit = (unit || "").toLowerCase();
      var lvl = unit === "%" ? (n >= 180 ? 1 : n >= 140 ? 2 : n >= 115 ? 3 : n <= 85 ? 9 : 0) : !unit && n <= 7 ? (n >= 6 ? 1 : n === 5 ? 2 : n === 4 ? 3 : n <= 2 ? 9 : 0) : (n >= 24 ? 1 : n >= 20 ? 2 : n >= 17 ? 3 : n <= 11 ? 9 : 0);
      if (lvl === 9) return "-# " + inner;
      return lvl && inner.indexOf("\n") < 0 ? "###".slice(0, lvl) + " " + inner.replace(/\[\/?b\]/gi, "") : inner;
    });
    s = s.replace(/\[quote(?:=[^\]]*)?\]\n?([\s\S]*?)\n?\[\/quote\]/gi, function (m, q) { return q.split("\n").map(function (l) { return "> " + l; }).join("\n"); });
    s = s.replace(/\[list(=[^\]]*)?\]\n?/gi, "").replace(/\n?\[\/list\]/gi, "").replace(/\[\*\]\s*/g, "- ");
    s = s.replace(/\[b\]([\s\S]*?)\[\/b\]/gi, "**$1**").replace(/\[i\]([\s\S]*?)\[\/i\]/gi, "*$1*").replace(/\[u\]([\s\S]*?)\[\/u\]/gi, "__$1__").replace(/\[(s|strike)\]([\s\S]*?)\[\/\1\]/gi, "~~$2~~").replace(/\[spoiler(?:=[^\]]*)?\]([\s\S]*?)\[\/spoiler\]/gi, "||$1||");
    return s.replace(/\[hr\]/gi, "").replace(/\[\/?(center|left|right|justify|color|font|indent|align|h[1-6])(=[^\]]*)?\]/gi, "");
  }
  function toDiscord(text, format) {
    var s = String(text || "");
    if (format === "discord") return s;
    if (format === "bbcode") return bbToDiscord(s);
    if (format === "markdown") return s.replace(/>!(.+?)!</g, "||$1||").replace(/\^\(([^)\n]*)\)/g, "-# $1").replace(/ {2,}\n/g, "\n");
    if (format === "whatsapp") return s.replace(/(^|[^\w*])\*(?=\S)([^*\n]*?\S)\*(?![\w*])/g, "$1**$2**").replace(/(^|[^\w_])_(?=\S)([^_\n]*?\S)_(?![\w_])/g, "$1*$2*").replace(/(^|[^\w~])~(?=\S)([^~\n]*?\S)~(?![\w~])/g, "$1~~$2~~");
    return null; // plain / unicode: shown as typed
  }
  function fill(text, values) {
    return String(text || "").split("{online}").join((values && values.online) || "7").split("{max}").join((values && values.max) || "60").split("{date}").join(new Date().toLocaleDateString("de-DE", { day: "2-digit", month: "2-digit", year: "numeric" }));
  }
  function body(text, format) {
    var md = toDiscord(fill(text), format);
    return md === null ? h("div", { class: "ah-plain", text: fill(text) }) : render(md);
  }

  // ============================================================ platform previews
  var PLATFORM_ICON = { discord: "chat", reddit: "users", planetminecraft: "globe", facebook: "users", telegram: "send", whatsapp: "chat", x: "send", serverlist: "list", other: "link" };
  function preview(text, format, platform, placeName) {
    var now = new Date().toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit" });
    if (platform === "discord") {
      return h("div", { class: "dc-window" }, h("div", { class: "dc-channel" }, h("span", { class: "dc-hash", text: "#" }), h("b", { text: placeName || "werbung" })),
        h("div", { class: "dc-msg" }, h("div", { class: "dc-avatar fallback", text: "S" }), h("div", { class: "grow", style: { minWidth: 0 } }, h("div", { class: "dc-head" }, h("b", { text: "Du" }), h("small", { text: "Heute um " + now })), body(text, format))));
    }
    var skins = { reddit: ["ah-skin reddit", "r/" + (placeName || "subreddit"), "u/du · gerade eben"], planetminecraft: ["ah-skin pmc", "Planet Minecraft", "Serverbeschreibung"], facebook: ["ah-skin fb", placeName || "Facebook-Gruppe", "Du · Gerade eben · 🌍"],
      telegram: ["ah-skin tg", placeName || "Telegram", now], whatsapp: ["ah-skin wa", placeName || "WhatsApp", now], x: ["ah-skin x", "@du", "Jetzt"], serverlist: ["ah-skin pmc", placeName || "Serverliste", "Beschreibung"], other: ["ah-skin", placeName || "Website", ""] };
    var sk = skins[platform] || skins.other;
    return h("div", { class: sk[0] }, h("div", { class: "ah-skin-head" }, h("b", { text: sk[1] }), h("small", { text: sk[2] })), h("div", { class: "ah-skin-body" + (format === "markdown" || format === "bbcode" || format === "discord" ? " md" : "") }, body(text, format)));
  }

  // ============================================================ helpers
  function api(b) { return S.api("ad-hub", b ? { body: b } : undefined); }
  function human(min) {
    min = Number(min) || 0;
    if (min >= 1440 && min % 1440 === 0) return min / 1440 + (min === 1440 ? " Tag" : " Tage");
    if (min >= 60 && min % 60 === 0) return min / 60 + " Std";
    if (min > 60) return (min / 60).toLocaleString("de-DE", { maximumFractionDigits: 1 }) + " Std";
    return min + " Min";
  }
  function until(ms) {
    var d = Number(ms) - Date.now(); if (!ms) return "–"; if (d <= 0) return "jetzt";
    if (d < 3600e3) return "in " + Math.max(1, Math.round(d / 60e3)) + " Min"; if (d < 86400e3) return "in " + (d / 3600e3).toLocaleString("de-DE", { maximumFractionDigits: 1 }) + " Std";
    return S.date(ms);
  }
  var INTERVALS = [[15, "15 Min"], [30, "30 Min"], [60, "1 Std"], [120, "2 Std"], [180, "3 Std"], [240, "4 Std"], [360, "6 Std"], [480, "8 Std"], [720, "12 Std"], [1440, "24 Std"], [2880, "2 Tage"], [4320, "3 Tage"], [10080, "7 Tage"], [20160, "14 Tage"], [43200, "30 Tage"]];
  var SNOOZE = [[15, "15 Min"], [30, "30 Min"], [60, "1 Std"], [180, "3 Std"], [360, "6 Std"], [1440, "1 Tag"]];
  var STATE = { due: ["Fällig", "danger"], waiting: ["Wartet", "ok"], snoozed: ["Später", "info"], paused: ["Pausiert", "warn"] };
  var KIND = { posted: ["Geworben", "ok"], snoozed: ["Später", "info"], created: ["Angelegt", "accent"], edited: ["Geändert", "accent"], paused: ["Pausiert", "warn"], resumed: ["Fortgesetzt", "ok"], undo: ["Rückgängig", "warn"], info: ["Info", ""], deleted: ["Gelöscht", ""], "due-now": ["Fällig", "info"], pause: ["Pausiert", "warn"], resume: ["Fortgesetzt", "ok"] };
  function discordChannel(link) { var m = /^https:\/\/(?:(?:www|ptb|canary)\.)?discord(?:app)?\.com\/channels\/(\d{15,25}|@me)\/(\d{15,25})/.exec(String(link || "").trim()); return m ? { guild: m[1], channel: m[2] } : null; }
  function guess(link) {
    var l = String(link || "").toLowerCase();
    if (/^https?:\/\/([a-z]+\.)?discord(app)?\.(com|gg)\//.test(l)) return "discord";
    if (l.indexOf("reddit.com/") >= 0 || l.indexOf("redd.it/") >= 0) return "reddit";
    if (l.indexOf("planetminecraft.com") >= 0) return "planetminecraft";
    if (l.indexOf("facebook.com/") >= 0 || l.indexOf("fb.com/") >= 0) return "facebook";
    if (l.indexOf("t.me/") >= 0) return "telegram";
    if (l.indexOf("whatsapp.com/") >= 0 || l.indexOf("wa.me/") >= 0) return "whatsapp";
    if (l.indexOf("x.com/") >= 0 || l.indexOf("twitter.com/") >= 0) return "x";
    if (/minecraft-server|minecraftservers|topg\.|minecraft-mp|serverlist/.test(l)) return "serverlist";
    return "other";
  }
  function platformOf(data, id) { return (data.platforms || []).filter(function (p) { return p.id === id; })[0] || { id: id, label: id, format: "plain", limit: 0 }; }
  function textOf(data, id) { return (data.texts || []).filter(function (t) { return t.id === id; })[0]; }
  function chars(s) { return Array.from(String(s || "")).length; }

  /**
   * Copy & open from one click: the browser only allows clipboard and pop-ups during the click, so the tab opens at
   * once and the clipboard receives a promise that resolves with the server's text.
   */
  async function postNow(place, data, done, force) {
    var tab = null; try { tab = window.open("about:blank", "_blank"); } catch (e) { tab = null; }
    var request = api({ operation: "place.post", id: place.id, force: !!force });
    var copied = false;
    try {
      if (window.ClipboardItem && navigator.clipboard && navigator.clipboard.write) {
        await navigator.clipboard.write([new ClipboardItem({ "text/plain": request.then(function (r) { return new Blob([r.text || ""], { type: "text/plain" }); }) })]);
        copied = true;
      }
    } catch (e) { copied = false; }
    var r;
    try { r = await request; }
    catch (e) {
      if (tab) try { tab.close(); } catch (x) { /* ignore */ }
      if (e.status === 409 && !force) { if (await S.confirm("Noch nicht fällig", e.message + "\n\nWenn du zu früh postest, kann der Server dich wegen Spam entfernen.", "Trotzdem werben", true)) return postNow(place, data, done, true); return; }
      throw e;
    }
    if (!copied) { try { await navigator.clipboard.writeText(r.text || ""); copied = true; } catch (e) { copied = false; } }
    if (tab) { try { tab.opener = null; tab.location.href = r.link; } catch (e) { window.open(r.link, "_blank", "noopener"); } } else window.open(r.link, "_blank", "noopener");
    if (copied) S.toast("Werbetext kopiert – jetzt einfügen", "ok", r.notice);
    else { S.toast("Bitte Text manuell kopieren", "err"); showText(r.name, r.text); }
    done(r.overview);
  }
  function showText(name, text) {
    var area = h("textarea", { class: "input ad-area", rows: 14, readonly: true }); area.value = text || "";
    var dlg = S.modal(name, h("div", { class: "stack" }, S.notice("Kopieren hat nicht geklappt (Browser-Rechte). Text markieren und kopieren:", "warn"), area), [S.btn("Schließen", { onClick: function () { dlg.close(); } })], { wide: true, icon: "copy" });
    setTimeout(function () { area.focus(); area.select(); }, 50);
  }

  // ============================================================ formatting toolbar (per format)
  function toolbar(area, getFormat, onChange) {
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
    function link() { var url = window.prompt("Link-Adresse (https://…)", "https://"); if (!url || !/^https?:\/\//.test(url)) return; var f = getFormat(); if (f === "bbcode") wrap("[url=" + url + "]", "[/url]", "Linktext"); else if (f === "discord" || f === "markdown") wrap("[", "](" + url + ")", "Linktext"); else insert(url); }
    var TOOLS = {
      discord: [["B", "Fett", "**", "**", "b"], ["I", "Kursiv", "*", "*", "i"], ["U", "Unterstrichen", "__", "__", "u"], ["S", "Durchgestrichen", "~~", "~~", "s"], ["||", "Spoiler", "||", "||"], ["</>", "Code", "`", "`", "mono"], ["H1", "Überschrift", "# "], ["H2", "Überschrift 2", "## "], ["H3", "Überschrift 3", "### "], ["-#", "Kleiner Text", "-# "], ["❝", "Zitat", "> "], ["•", "Liste", "- "]],
      markdown: [["B", "Fett", "**", "**", "b"], ["I", "Kursiv", "*", "*", "i"], ["S", "Durchgestrichen", "~~", "~~", "s"], [">!", "Spoiler", ">!", "!<"], ["</>", "Code", "`", "`", "mono"], ["H1", "Überschrift", "# "], ["H2", "Überschrift 2", "## "], ["❝", "Zitat", "> "], ["•", "Liste", "- "]],
      bbcode: [["B", "Fett", "[b]", "[/b]", "b"], ["I", "Kursiv", "[i]", "[/i]", "i"], ["U", "Unterstrichen", "[u]", "[/u]", "u"], ["S", "Durchgestrichen", "[s]", "[/s]", "s"], ["H1", "Groß", "[size=6][b]", "[/b][/size]"], ["H2", "Mittel", "[size=5][b]", "[/b][/size]"], ["small", "Klein", "[size=2]", "[/size]"], ["⎯", "Zentriert", "[center]", "[/center]"], ["🎨", "Farbe", "[color=#22d3ee]", "[/color]"], ["❝", "Zitat", "[quote]", "[/quote]"], ["•", "Liste", "[list]\n[*]", "\n[/list]"], ["{ }", "Code", "[code]", "[/code]", "mono"], ["🖼", "Bild", "[img]", "[/img]"]],
      whatsapp: [["B", "Fett", "*", "*", "b"], ["I", "Kursiv", "_", "_", "i"], ["S", "Durchgestrichen", "~", "~", "s"], ["</>", "Code", "`", "`", "mono"], ["❝", "Zitat", "> "], ["•", "Liste", "- "]],
      unicode: [], plain: []
    };
    var bar = h("div", { class: "ad-toolbar", role: "toolbar", "aria-label": "Formatierung" });
    var emojis = ["🌍", "🖥️", "📱", "🎮", "👤", "💎", "🛡️", "♾️", "⚡", "🤝", "🔗", "📡", "⭐", "🔥", "✅", "🎉", "⚔️", "🏰", "💬", "📢"];
    var emojiRow = h("div", { class: "ad-emojis hidden" }, emojis.map(function (em) { return b(em, "Emoji einfügen", function () { insert(em); }, "emoji"); }));
    function paint() {
      S.clear(bar);
      (TOOLS[getFormat()] || []).forEach(function (t) { bar.appendChild(b(t[0], t[1] + (t[3] !== undefined ? " (" + t[2] + "…" + t[3] + ")" : " (" + t[2] + ")"), function () { if (t[3] !== undefined) wrap(t[2], t[3], t[1].toLowerCase()); else prefix(t[2]); }, t[4])); });
      if (getFormat() === "unicode") bar.appendChild(h("small", { class: "muted", style: { padding: "6px" }, text: "Tipp: Fett/Kursiv als Discord-Text schreiben und „Umwandeln → Facebook“ nutzen – dann entstehen 𝗳𝗲𝘁𝘁𝗲 Unicode-Buchstaben." }));
      bar.appendChild(h("span", { class: "ad-sep" }));
      bar.appendChild(b("🔗", "Link einfügen", link));
      bar.appendChild(b("😀", "Emojis", function () { emojiRow.classList.toggle("hidden"); }));
      bar.appendChild(b("{online}", "Platzhalter: Spieler online", function () { insert("{online}"); }, "ph"));
      bar.appendChild(b("{max}", "Platzhalter: max. Spieler", function () { insert("{max}"); }, "ph"));
    }
    paint();
    return { el: h("div", { class: "stack", style: { gap: "6px" } }, bar, emojiRow), repaint: paint };
  }

  // ============================================================ text editor
  function textEditor(data, text, done, preset) {
    var t = text || preset || { name: "", content: "", format: "discord" };
    var format = t.format || "discord", platform = { discord: "discord", markdown: "reddit", bbcode: "planetminecraft", whatsapp: "whatsapp", unicode: "facebook", plain: "other" }[format];
    var name = S.input({ value: t.name, maxlength: 60, placeholder: "z. B. Simplex SMP – Reddit" });
    var area = h("textarea", { class: "input ad-area", rows: 16, spellcheck: "false", maxlength: data.limits.content, placeholder: "Werbetext …" }); area.value = t.content || "";
    var fmt = h("select", { class: "input" }, (data.formats || []).map(function (f) { return h("option", { value: f.id, text: f.label, selected: f.id === format }); }));
    var counter = h("span", { class: "badge" }), prev = h("div", { class: "ad-preview" }), timer = null, bar;
    var limits = {}; (data.platforms || []).forEach(function (p) { if (!limits[p.format] || p.limit && p.limit < limits[p.format]) limits[p.format] = p.limit; });
    function update() {
      var n = chars(area.value), lim = platformOf(data, platform).limit;
      counter.textContent = n + (lim ? " / " + lim : "") + " Zeichen"; counter.className = "badge " + (lim && n > lim ? "danger" : lim && n > lim * 0.9 ? "warn" : "");
      clearTimeout(timer); timer = setTimeout(function () { S.clear(prev).appendChild(preview(area.value, format, platform, "werbung")); }, S.perf && S.perf.lite ? 350 : 120);
    }
    fmt.addEventListener("change", function () { format = fmt.value; platform = { discord: "discord", markdown: "reddit", bbcode: "planetminecraft", whatsapp: "whatsapp", unicode: "facebook", plain: "other" }[format]; bar.repaint(); update(); });
    bar = toolbar(area, function () { return format; }, update);
    area.addEventListener("input", update);
    var convertTo = h("select", { class: "input" }, h("option", { value: "", text: "In Format umwandeln …" }), (data.formats || []).map(function (f) { return h("option", { value: f.id, text: f.label }); }));
    convertTo.addEventListener("change", async function () {
      var to = convertTo.value; convertTo.value = ""; if (!to || to === format) return;
      try { var r = await api({ operation: "text.convert", from: format, to: to, content: area.value }); area.value = r.content; format = to; fmt.value = to; platform = { discord: "discord", markdown: "reddit", bbcode: "planetminecraft", whatsapp: "whatsapp", unicode: "facebook", plain: "other" }[to]; bar.repaint(); update(); S.toast("Umgewandelt", "ok", "Prüfen und speichern – oder „Abbrechen“, dann bleibt alles wie es war."); }
      catch (e) { S.toast(e.message, "err"); }
    });
    var editor = h("div", { class: "ad-editor" },
      h("div", { class: "stack", style: { gap: "10px" } }, S.field("Name", name), S.field("Format", fmt), bar.el, area,
        h("div", { class: "row", style: { flexWrap: "wrap" } }, counter, h("span", { class: "grow" }), convertTo, S.btn("Kopieren", { icon: "copy", size: "sm", onClick: function () { return S.copy(area.value, "Werbetext"); } }))),
      h("div", { class: "stack", style: { gap: "8px" } }, h("div", { class: "ad-label", text: "Vorschau" }), prev, h("small", { class: "muted", text: "Platzhalter {online}, {max} und {date} werden beim Kopieren mit echten Werten gefüllt." })));
    update();
    var dlg = S.modal(text ? "Werbetext bearbeiten" : "Neuer Werbetext", editor, [
      S.btn("Abbrechen", { onClick: function () { dlg.close(); } }),
      S.btn("Speichern", { kind: "primary", icon: "check", onClick: async function () {
        var r = await api({ operation: "text.save", id: text ? text.id : "", name: name.value.trim(), content: area.value, format: format });
        S.toast(r.notice || "Gespeichert"); dlg.close(); done(r.overview, r.id);
      } })], { wide: true, icon: "edit" });
  }

  // ============================================================ place editor
  function placeEditor(data, place, done) {
    var p = place || { name: "", link: "", platform: "", textId: "", intervalMinutes: 360, note: "", notifyAll: false };
    var link = S.input({ value: p.link, placeholder: "Kanal-/Gruppen-/Seiten-Link einfügen (https://…)", inputmode: "url" });
    var name = S.input({ value: p.name, maxlength: 80, placeholder: "z. B. #werbung-6h · MCAdverts" });
    var platform = p.platform || "";
    var plat = h("select", { class: "input" }, h("option", { value: "", text: "Automatisch aus dem Link" }), (data.platforms || []).map(function (x) { return h("option", { value: x.id, text: x.label, selected: x.id === platform }); }));
    var detect = h("small", { class: "muted" }), prevBox = h("div", { class: "ad-preview" });
    var textSel = h("select", { class: "input" });
    var interval = Number(p.intervalMinutes) || 360;
    var intSel = h("select", { class: "input" }, INTERVALS.map(function (o) { return h("option", { value: o[0], text: o[1], selected: o[0] === interval }); }), h("option", { value: "custom", text: "Eigenes …", selected: !INTERVALS.some(function (o) { return o[0] === interval; }) }));
    var custom = S.input({ type: "number", min: 5, max: 43200, value: interval, style: { maxWidth: "140px" } });
    var customWrap = h("div", { class: "row" + (INTERVALS.some(function (o) { return o[0] === interval; }) ? " hidden" : "") }, custom, h("small", { class: "muted", text: "Minuten" }));
    var last = h("select", { class: "input" }, [[0, "Jetzt fällig (noch nicht gepostet)"], [30, "Vor 30 Min gepostet"], [60, "Vor 1 Std gepostet"], [180, "Vor 3 Std gepostet"], [360, "Vor 6 Std gepostet"], [720, "Vor 12 Std gepostet"], [1440, "Vor 1 Tag gepostet"]].map(function (o) { return h("option", { value: o[0], text: o[1] }); }));
    var notifyAll = !!p.notifyAll, note = S.input({ value: p.note, maxlength: 300, placeholder: "Notiz, z. B. Regeln: nur 1 Post, kein @everyone" });
    function effPlatform() { return platform || guess(link.value); }
    function fillTexts() {
      var pf = platformOf(data, effPlatform()), cur = textSel.value || p.textId;
      var list = (data.texts || []).slice().sort(function (a, b) { return (b.format === pf.format) - (a.format === pf.format); });
      S.clear(textSel);
      list.forEach(function (t) { textSel.appendChild(h("option", { value: t.id, text: t.name + (t.format === pf.format ? " ✓" : " · " + t.formatLabel), selected: t.id === cur })); });
      if (!cur && list[0]) textSel.value = list[0].id;
    }
    function update() {
      var pf = platformOf(data, effPlatform()), dc = discordChannel(link.value);
      detect.textContent = !link.value ? pf.hint || "" : pf.id === "discord" ? (dc ? "✓ Discord-Kanal erkannt – öffnet direkt diesen Kanal" : "Tipp: Kanal-Link (discord.com/channels/…) öffnet direkt den Kanal; ein Einladungslink öffnet nur den Server.") : "Erkannt: " + pf.label + (pf.limit ? " · max. " + S.num(pf.limit) + " Zeichen" : "");
      var t = textOf(data, textSel.value);
      S.clear(prevBox);
      if (t) {
        prevBox.appendChild(preview(t.content, t.format, pf.id, name.value || "werbung"));
        if (t.format !== pf.format) prevBox.appendChild(S.notice("Dieser Text ist „" + t.formatLabel + "“ – für " + pf.label + " passt „" + ((data.formats || []).filter(function (f) { return f.id === pf.format; })[0] || {}).label + "“ besser. Im Text-Editor kannst du ihn mit einem Tipp umwandeln.", "info"));
        if (pf.limit && t.length > pf.limit) prevBox.appendChild(S.notice("Text ist zu lang für " + pf.label + " (" + t.length + " / " + pf.limit + ").", "warn"));
      }
    }
    link.addEventListener("input", function () { fillTexts(); update(); });
    plat.addEventListener("change", function () { platform = plat.value; fillTexts(); update(); });
    textSel.addEventListener("change", update); name.addEventListener("input", function () { clearTimeout(name._t); name._t = setTimeout(update, 300); });
    intSel.addEventListener("change", function () { customWrap.classList.toggle("hidden", intSel.value !== "custom"); });
    fillTexts(); update();
    var form = h("div", { class: "ad-editor" },
      h("div", { class: "stack", style: { gap: "10px" } },
        S.field("Link", link), detect,
        h("div", { class: "grid two" }, S.field("Plattform", plat), S.field("Name", name)),
        h("div", { class: "row", style: { alignItems: "flex-end", gap: "8px" } }, h("div", { class: "grow" }, S.field("Werbetext", textSel)), S.btn("", { icon: "plus", size: "icon", title: "Neuer Werbetext", onClick: function () { textEditor(data, null, function (ov, id) { data = ov; fillTexts(); if (id) textSel.value = id; update(); }, { name: "", content: "", format: platformOf(data, effPlatform()).format }); } })),
        h("div", { class: "grid two" }, h("div", {}, S.field("Wie oft darf ich dort posten?", intSel), customWrap), place ? null : S.field("Zuletzt gepostet", last)),
        h("label", { class: "check" }, S.sw(notifyAll, function (v) { notifyAll = v; }), h("span", { text: "Alle mit Zugriff erinnern (sonst nur mich)" })),
        S.field("Notiz", note),
        h("small", { class: "muted", text: "Nach dem Werben startet der Timer neu – immer etwas über dem Intervall (Sicherheitsaufschlag " + String(data.settings.safetyPercent).replace(".", ",") + " %: 6 Std → etwa " + (6 * (1 + data.settings.safetyPercent / 100)).toLocaleString("de-DE", { maximumFractionDigits: 2 }) + " Std)." })),
      h("div", { class: "stack", style: { gap: "8px" } }, h("div", { class: "ad-label", text: "So sieht es aus" }), prevBox));
    var dlg = S.modal(place ? "Werbe-Ort bearbeiten" : "Werbe-Ort hinzufügen", form, [
      S.btn("Abbrechen", { onClick: function () { dlg.close(); } }),
      S.btn("Speichern", { kind: "primary", icon: "check", onClick: async function () {
        var minutes = intSel.value === "custom" ? Number(custom.value) : Number(intSel.value);
        var r = await api({ operation: "place.save", id: place ? place.id : "", link: link.value.trim(), name: name.value.trim(), platform: platform, textId: textSel.value, intervalMinutes: minutes, lastPostedMinutesAgo: place ? 0 : Number(last.value), notifyAll: notifyAll, note: note.value });
        S.toast(r.notice || "Gespeichert"); dlg.close(); done(r.overview);
      } })], { wide: true, icon: "send" });
  }

  // ============================================================ page
  P["ad-hub"] = async function (param, query, alive) {
    if (!S.can("data.discord-ads")) { S.mount(S.lockedPage("Werbe-Zentrale gesperrt", "data.discord-ads")); return; }
    var data = await api(); if (!alive()) return;
    var filter = "all", search = query.get("q") || "";
    var finder = S.searchBox("Werbe-Orte, Texte & Verlauf suchen …", function (q) { search = q; paint(); }, search);
    function match(text) { return !search || (S.workspaceMatches ? S.workspaceMatches(search, text) : String(text).toLowerCase().indexOf(search.toLowerCase()) >= 0); }
    function rerender(next) { if (next) data = next; if (alive()) paint(); }
    async function act(b) { var r = await api(b); if (r.notice) S.toast(r.notice); rerender(r.overview); }
    function snoozeMenu(pl) {
      var box = h("div", { class: "ah-snooze" }, SNOOZE.map(function (o) { return S.btn(o[1], { size: "sm", onClick: async function () { dlg.close(); await act({ operation: "place.snooze", id: pl.id, minutes: o[0] }); } }); }));
      var dlg = S.modal("Später erinnern: " + pl.name, h("div", { class: "stack" }, h("p", { class: "muted", style: { margin: 0 }, text: "Die nächste Bark-Erinnerung kommt nach dieser Zeit." }), box), [], { icon: "clock" });
    }
    function placeRow(pl, big) {
      var st = STATE[pl.state] || [pl.state, ""], hist = h("div", { class: "ad-history hidden" });
      (pl.history || []).forEach(function (e) { var k = KIND[e.kind] || [e.kind, ""]; hist.appendChild(h("div", { class: "ad-hrow" }, h("small", { class: "muted mono", text: S.date(e.time) }), S.badge(k[0], k[1]), h("span", { text: e.text }))); });
      if (!(pl.history || []).length) hist.appendChild(h("small", { class: "muted", text: "Noch kein Verlauf." }));
      var due = pl.state === "due";
      var main = S.btn(due ? "Jetzt werben" : "Werben", { kind: due ? "primary" : "", size: big ? "" : "sm", icon: "copy", title: "Text kopieren & " + pl.platformLabel + " öffnen",
        disabled: !data.canPost, onClick: function () { return postNow(pl, data, rerender); } });
      var row = h("div", { class: "item ah-place" + (pl.enabled ? "" : " paused") + (due ? " due" : "") },
        h("div", { class: "ah-pico " + pl.platform }, icon(PLATFORM_ICON[pl.platform] || "link")),
        h("div", { class: "meta" },
          h("b", { text: pl.name }),
          h("small", { text: pl.platformLabel + " · alle " + pl.intervalText + " · „" + (pl.textName || "kein Text") + "“" + (pl.lastPostedAt ? " · zuletzt " + S.ago(pl.lastPostedAt) : " · noch nie") }),
          pl.enabled ? h("small", { text: due ? "Fällig seit " + S.ago(pl.dueAt).replace("vor ", "") + (pl.remindCount ? " · " + pl.remindCount + "× erinnert" : "") : (pl.state === "snoozed" ? "Später erinnern: " : "Nächste Erinnerung ") + until(pl.dueAt) + " (" + S.date(pl.dueAt) + ")" }) : h("small", { text: "Pausiert – keine Erinnerungen" }),
          pl.note ? h("p", { class: "muted", style: { margin: "2px 0 0" }, text: pl.note }) : null),
        h("div", { class: "side ad-side" }, h("div", { class: "row", style: { gap: "6px" } }, S.badge(st[0], st[1]), main),
          h("div", { class: "row", style: { gap: "4px" } },
            data.canPost && pl.enabled ? S.btn("", { icon: "clock", size: "sm icon", title: "Später erinnern", onClick: function () { snoozeMenu(pl); } }) : null,
            h("a", { class: "btn sm icon", href: pl.link, target: "_blank", rel: "noopener noreferrer", title: "Nur öffnen" }, icon("external")),
            S.btn("", { icon: "list", size: "sm icon", title: "Verlauf", onClick: function () { hist.classList.toggle("hidden"); } }),
            data.canPost && pl.undoable ? S.btn("", { icon: "refresh", size: "sm icon", title: "Letztes „Geworben“ zurücknehmen", onClick: function () { return act({ operation: "place.undo", id: pl.id }); } }) : null,
            data.canManage ? S.btn("", { icon: "edit", size: "sm icon", title: "Bearbeiten", onClick: function () { placeEditor(data, pl, rerender); } }) : null,
            data.canManage ? (pl.enabled ? S.btn("", { icon: "x", size: "sm icon", title: "Pausieren", onClick: function () { return act({ operation: "place.pause", id: pl.id }); } }) : S.btn("", { icon: "check", size: "sm icon", title: "Fortsetzen", onClick: function () { return act({ operation: "place.resume", id: pl.id }); } })) : null,
            data.canManage ? S.btn("", { icon: "trash", size: "sm icon", title: "Löschen", onClick: async function () { if (await S.confirm("Werbe-Ort löschen?", "„" + pl.name + "“ wird entfernt. Der Werbetext bleibt erhalten.", "Löschen", true)) return act({ operation: "place.delete", id: pl.id }); } }) : null)));
      return h("div", {}, row, hist);
    }
    function placesCard() {
      var list = data.places.filter(function (pl) { return (filter === "all" || pl.platform === filter) && match(pl.name + " " + pl.link + " " + pl.platform + " " + pl.note); });
      var due = list.filter(function (pl) { return pl.state === "due"; }), rest = list.filter(function (pl) { return pl.state !== "due"; });
      var used = {}; data.places.forEach(function (pl) { used[pl.platform] = (used[pl.platform] || 0) + 1; });
      var chips = [["all", "Alle (" + data.places.length + ")"]].concat((data.platforms || []).filter(function (x) { return used[x.id]; }).map(function (x) { return [x.id, x.label + " (" + used[x.id] + ")"]; }));
      var body = h("div", { class: "stack", style: { gap: "12px" } }, chips.length > 2 ? h("div", { class: "ad-scroll" }, S.segmented(chips, filter, function (v) { filter = v; paint(); })) : null);
      if (!data.places.length) body.appendChild(S.empty("send", "Noch keine Werbe-Orte", "Füge einen Discord-Kanal-Link, ein Subreddit, deine Planet-Minecraft-Seite oder eine Facebook-Gruppe hinzu – mit Text und Intervall.", data.canManage ? S.btn("Werbe-Ort hinzufügen", { kind: "primary", icon: "plus", onClick: function () { placeEditor(data, null, rerender); } }) : null));
      if (due.length) { var dl = h("div", { class: "list ad-list" }); due.forEach(function (pl) { dl.appendChild(placeRow(pl, true)); }); body.appendChild(h("div", { class: "ah-due" }, h("div", { class: "ad-label", text: "Jetzt fällig (" + due.length + ")" }), dl)); }
      if (rest.length) { var rl = h("div", { class: "list ad-list" }); rest.forEach(function (pl) { rl.appendChild(placeRow(pl)); }); body.appendChild(h("div", {}, due.length ? h("div", { class: "ad-label", text: "Wartet" }) : null, rl)); }
      return S.card("Werbe-Orte", { icon: "send", sub: "Kanäle, Gruppen und Seiten mit Link, Text und Intervall. „Jetzt werben“ kopiert den Text und öffnet den Ort.",
        actions: data.canManage ? S.btn("Hinzufügen", { kind: "primary", size: "sm", icon: "plus", onClick: function () { placeEditor(data, null, rerender); } }) : null }, body);
    }
    function textsCard() {
      var list = h("div", { class: "list" });
      data.texts.filter(function (t) { return match(t.name + " " + t.content); }).forEach(function (t) {
        list.appendChild(h("div", { class: "item" }, h("div", { class: "meta" }, h("b", { text: t.name }), h("small", { text: t.formatLabel + " · " + t.length + " Zeichen" + (t.usedBy.length ? " · " + t.usedBy.length + "× verwendet" : " · nicht verwendet") + (t.preset ? " · Vorlage" : " · " + S.ago(t.updatedAt)) })),
          h("div", { class: "side" },
            S.btn("", { icon: "eye", size: "sm icon", title: "Vorschau", onClick: function () { var pf = { discord: "discord", markdown: "reddit", bbcode: "planetminecraft", whatsapp: "whatsapp", unicode: "facebook", plain: "other" }[t.format]; S.modal(t.name, h("div", { class: "stack" }, preview(t.content, t.format, pf, "werbung"), h("div", { class: "row" }, S.btn("Text kopieren", { icon: "copy", size: "sm", onClick: function () { return S.copy(t.content, "Werbetext"); } }))), [], { wide: true, icon: "eye" }); } }),
            S.btn("", { icon: "copy", size: "sm icon", title: "Text kopieren", onClick: function () { return S.copy(t.content, "Werbetext"); } }),
            data.canManage ? S.btn("", { icon: "edit", size: "sm icon", title: "Bearbeiten", onClick: function () { textEditor(data, t, rerender); } }) : null,
            data.canManage ? S.btn("", { icon: "trash", size: "sm icon", title: t.usedBy.length ? "Wird verwendet" : "Löschen", disabled: t.usedBy.length > 0, onClick: async function () { if (await S.confirm("Werbetext löschen?", "„" + t.name + "“ wird entfernt.", "Löschen", true)) return act({ operation: "text.delete", id: t.id }); } }) : null)));
      });
      return S.card("Werbetexte", { icon: "edit", sub: "Discord, Reddit, BBCode (Planet Minecraft), WhatsApp/Telegram, Facebook (Unicode) oder reiner Text – mit Vorschau und Umwandeln.",
        actions: data.canManage ? S.btn("Neuer Text", { size: "sm", icon: "plus", onClick: function () { textEditor(data, null, rerender); } }) : null }, list);
    }
    function settingsCard() {
      var st = data.settings, dis = !data.canManage;
      function save(patch) { return act(Object.assign({ operation: "settings.save" }, patch)); }
      function sel(value, options, key) { var s = h("select", { class: "input", disabled: dis }, options.map(function (o) { return h("option", { value: o[0], selected: Number(value) === o[0], text: o[1] }); })); s.addEventListener("change", function () { var p = {}; if (key === "safetyTenth") p.safetyPercent = Number(s.value) / 10; else p[key] = Number(s.value); save(p); }); return s; }
      return S.card("Erinnerungen", { icon: "bell" },
        h("div", { class: "stack", style: { gap: "10px" } },
          h("div", { class: "right-row" }, h("span", { class: "grow", text: "Bark-Erinnerungen senden" }), S.sw(st.remind, function (v) { save({ remind: v }); }, dis)),
          S.field("Noch nicht geworben? Erneut erinnern", sel(st.repeatMinutes, [[0, "Nie"], [15, "Nach 15 Min"], [30, "Nach 30 Min"], [60, "Nach 1 Std"], [120, "Nach 2 Std"], [240, "Nach 4 Std"]], "repeatMinutes")),
          S.field("Höchstens so oft wiederholen", sel(st.maxRepeats, [[1, "1×"], [2, "2×"], [3, "3×"], [5, "5×"], [10, "10×"]], "maxRepeats")),
          S.field("Sicherheitsaufschlag auf das Intervall", sel(Math.round(st.safetyPercent * 10), [[0, "Keiner (mind. 1 Min)"], [10, "1 %"], [15, "1,5 % (6 Std → ~6,1 Std)"], [20, "2 %"], [30, "3 %"], [50, "5 %"]], "safetyTenth")),
          h("small", { class: "muted", text: "Bark: In den Bark-Einstellungen heißt das Ereignis „Werbe-Zentrale: Werbung fällig“. Tippen auf die Mitteilung öffnet die Simplex-App, kopiert den Text und öffnet den Kanal. Im Browser: Benachrichtigungen → „Werbe-Zentrale“." })));
    }
    function logCard() {
      var list = h("div", { class: "list" });
      (data.log || []).filter(function (e) { return match(e.text); }).slice(0, 40).forEach(function (e) {
        var k = KIND[e.kind] || [e.kind, ""];
        list.appendChild(h("div", { class: "item" }, S.badge(k[0], k[1]), h("div", { class: "meta" }, h("small", { text: S.date(e.time) }), h("p", { style: { marginTop: 0 }, text: e.text }))));
      });
      if (!(data.log || []).length) list.appendChild(S.empty("clock", "Noch nichts passiert"));
      return S.card("Verlauf", { icon: "clock" }, list);
    }
    function paint() {
      var st = data.stats;
      S.title("Werbe-Zentrale", "Studio", data.canManage ? S.btn("Werbe-Ort", { kind: "primary", icon: "plus", title: "Werbe-Ort hinzufügen", onClick: function () { placeEditor(data, null, rerender); } }) : null);
      function kpi(label, value, ic, tone, hint) { return h("section", { class: "card kpi" }, h("div", { class: "ico " + (tone || "") }, icon(ic)), h("div", { class: "label", text: label }), h("div", { class: "value", text: String(value) }), hint ? h("div", { class: "hint", text: hint }) : null); }
      var mig = data.migration;
      S.mount(h("div", { class: "stack" },
        mig ? h("div", { class: "notice ok" }, icon("check"), h("div", { class: "grow" }, h("b", { text: "Discord-Werbeplaner übernommen. " }), mig.places + " Kanäle und " + mig.texts + " Texte sind jetzt Werbe-Orte mit Bark-Erinnerung. " + (mig.accounts ? "Der gespeicherte Discord-Token wurde gelöscht – ändere am besten das Passwort des Zweitaccounts, dann ist er auch bei Discord ungültig." : "")),
          S.btn("OK", { size: "sm", onClick: function () { return act({ operation: "migration.ack" }); } })) : null,
        data.canPost ? null : S.notice("Nur ansehen – zum Werben ist manage- oder full-Zugriff nötig.", "info", "lock"),
        data.problem ? S.notice("Erinnerungen melden einen Fehler: " + data.problem + " – Serverprotokoll prüfen.", "warn") : null,
        h("div", { class: "grid kpis" }, kpi("Jetzt fällig", st.due, "bell", st.due ? "r" : "g"), kpi("Aktive Orte", st.active, "send", "g"), kpi("Geworben (24 h)", st.posts24h, "check", ""), kpi("Geworben (7 T)", st.posts7d, "chart", "v"),
          kpi("Nächste Erinnerung", st.nextDueAt ? until(st.nextDueAt) : "–", "clock", "v", st.nextDueAt ? S.date(st.nextDueAt) : null)),
        finder,
        placesCard(),
        h("div", { class: "grid two" }, textsCard(), h("div", { class: "stack" }, settingsCard(), logCard())),
        h("small", { class: "muted", text: "Es wird nie automatisch gepostet. Halte dich an die Regeln der jeweiligen Server und Gruppen – zu häufiges Posten kann zum Ausschluss führen." })));
      if (query && query.get && query.get("post") && !paint.done) {
        paint.done = true; var target = data.places.filter(function (x) { return x.id === query.get("post"); })[0];
        if (target) { var dlg = S.modal("Werbung fällig: " + target.name, h("div", { class: "stack" }, preview(textOf(data, target.textId) ? textOf(data, target.textId).content : "", target.format, target.platform, target.name)), [S.btn("Später", { onClick: function () { dlg.close(); snoozeMenu(target); } }), S.btn("Kopieren & öffnen", { kind: "primary", icon: "copy", onClick: function () { dlg.close(); return postNow(target, data, rerender); } })], { wide: true, icon: "bell" }); }
      }
    }
    paint();
    S.every(30000, async function () { if (!alive() || document.querySelector(".modal-bg")) return; try { var d = await api(); if (alive() && !document.querySelector(".modal-bg")) rerender(d); } catch (e) { /* keep last view */ } });
  };
})();
