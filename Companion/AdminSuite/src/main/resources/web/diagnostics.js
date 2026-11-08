/* Simplex Panel Web 10.2.0 – Diagnosezentrale: Live-Werte, Disconnect-Analyse mit Vorfällen, Lag-Karte, Spark-Profile,
   Absturzberichte, Volldiagnose, Konsole und KI-Auswertung. Alles läuft auf dem Server; diese Seite liest und startet nur. */
(function () {
  "use strict";
  var S = window.Simplex, h = S.h, icon = S.icon, P = S.pages = S.pages || {};

  var TABS = [["live", "Live"], ["disconnects", "Disconnects"], ["lag", "Lag-Karte"], ["spark", "Spark"], ["crashes", "Abstürze"], ["full", "Volldiagnose"], ["console", "Konsole"]];
  var TONE = { ok: "ok", warn: "warn", crit: "danger", info: "info", critical: "danger", warning: "warn" };
  var VERDICT = { legit: ["Legitim", "ok"], problem: ["Fehler erkannt", "danger"], unclear: ["Unklar", "warn"] };
  var SEVERITY = { critical: ["Kritisch", "danger"], warning: ["Dringlich", "warn"], info: ["Hinweis", "info"] };
  var CRASH = { oom: "Speicher voll", watchdog: "Watchdog", jvm: "Java-VM", tick: "Tick-Fehler", killed: "Hart beendet" };

  function api(path, opts) { return S.api("diagnostics/" + path, opts); }
  function fmt(v, d) { return Number(v || 0).toLocaleString("de-DE", { maximumFractionDigits: d === undefined ? 1 : d, minimumFractionDigits: 0 }); }
  function pct(v) { return fmt(v, 1) + " %"; }
  function tile(label, value, opts) {
    opts = opts || {};
    return h("section", { class: "card kpi" + (opts.href ? " clickable" : ""), onClick: opts.href ? function () { S.go(opts.href); } : null },
      h("div", { class: "ico " + (opts.tone || "") }, icon(opts.icon || "activity")), h("div", { class: "label", text: label }),
      h("div", { class: "value" }, value, opts.unit ? h("small", { text: opts.unit }) : null), opts.hint ? h("div", { class: "hint", text: opts.hint }) : null, opts.extra || null);
  }
  function tabs(active, canSettings) {
    var list = TABS.slice(); if (canSettings) list.push(["settings", "Einstellungen"]);
    return S.segmented(list, active, function (id) { S.go("#/diagnostics/" + id); });
  }
  function findingRow(f) {
    return h("div", { class: "right-row" }, S.badge({ ok: "OK", warn: "Hinweis", crit: "Kritisch", info: "Info" }[f.level || f.status] || f.level, TONE[f.level || f.status] || ""),
      h("div", { class: "grow" }, h("b", { text: f.title }), f.value ? h("div", { class: "mono small", text: f.value }) : null, f.hint ? h("small", { class: "muted", text: f.hint }) : null));
  }
  function bars(rows, label, value, max) {
    var top = max || rows.reduce(function (m, r) { return Math.max(m, Number(r[value]) || 0); }, 0) || 1;
    return h("div", { class: "stack", style: { gap: "8px" } }, rows.map(function (r) {
      return h("div", {}, h("div", { class: "row" }, h("span", { class: "grow small", text: r[label] }), h("b", { class: "small mono", text: value === "share" ? pct(r[value]) : fmt(r[value], 0) })), S.bar(Number(r[value]) || 0, top, 101));
    }));
  }
  function copyTp(row) {
    return S.btn("TP kopieren", { size: "sm", icon: "copy", onClick: function () { return S.copy("/execute in " + (row.world === "world_nether" ? "minecraft:the_nether" : row.world === "world_the_end" ? "minecraft:the_end" : row.world === "world" ? "minecraft:overworld" : row.world) + " run tp @s " + row.x + " " + (row.y || 100) + " " + row.z, "Teleport-Befehl"); } });
  }

  /** Line chart (SVG): TPS and longest tick with the 50 ms budget line. */
  function chart(series) {
    var w = 640, hh = 160, pad = 26, svg = h("svg", { class: "diag-chart", viewBox: "0 0 " + w + " " + hh, preserveAspectRatio: "none", role: "img", "aria-label": "TPS und Tickzeit" });
    var n = (series.tps || []).length; if (n < 2) return h("p", { class: "muted small", text: "Noch zu wenige Messwerte – kurz warten." });
    var tps = series.tps.slice().reverse(), max = series.msptMax.slice().reverse(), avg = series.mspt.slice().reverse();
    var top = Math.max(100, Math.min(2000, Math.max.apply(null, max) * 1.1));
    function x(i) { return pad + i / (n - 1) * (w - pad * 2); }
    function yMs(v) { return hh - pad - Math.min(v, top) / top * (hh - pad * 2); }
    function yTps(v) { return hh - pad - Math.max(0, Math.min(20, v)) / 20 * (hh - pad * 2); }
    function path(arr, fy) { return "M" + arr.map(function (v, i) { return x(i).toFixed(1) + " " + fy(v).toFixed(1); }).join(" L"); }
    svg.appendChild(h("line", { x1: pad, x2: w - pad, y1: yMs(50), y2: yMs(50), class: "budget" }));
    svg.appendChild(h("text", { x: w - pad, y: yMs(50) - 4, class: "lbl", "text-anchor": "end" }, "50 ms"));
    svg.appendChild(h("path", { d: path(max, yMs), class: "max" }));
    svg.appendChild(h("path", { d: path(avg, yMs), class: "avg" }));
    svg.appendChild(h("path", { d: path(tps, yTps), class: "tps" }));
    var legend = h("div", { class: "row wrap small", style: { gap: "14px" } }, h("span", { class: "dot tps" }, "TPS (0–20)"), h("span", { class: "dot avg" }, "Ø Tickzeit"), h("span", { class: "dot max" }, "Längster Tick (bis " + fmt(top, 0) + " ms)"));
    return h("div", { class: "stack", style: { gap: "8px" } }, svg, legend);
  }

  /** AI box: shows a finished analysis or starts one and polls until it is done. */
  function aiBox(target, existing, available, canRun) {
    var box = h("div", { class: "stack", style: { gap: "10px" } });
    function render(job) {
      S.clear(box);
      if (job && job.status === "done") {
        box.appendChild(h("div", { class: "ai-text" }, String(job.text || "").split("\n").map(function (l) { return h("p", { text: l }); })));
        box.appendChild(h("small", { class: "muted", text: "KI-Auswertung · " + (job.model || "") + " · " + S.ago(job.doneAt) + " · " + (job.by || "") }));
      } else if (job && job.status === "running") box.appendChild(h("div", { class: "row" }, h("span", { class: "spin" }), h("span", { text: "KI analysiert die Daten …" })));
      else if (job && job.status === "failed") box.appendChild(S.notice("KI-Analyse fehlgeschlagen: " + (job.error || ""), "warn"));
      if (!available) box.appendChild(S.notice("Kein OpenAI-Schlüssel hinterlegt oder KI-Diagnose ausgeschaltet.", "", "info"));
      else if (canRun && !(job && job.status === "running")) box.appendChild(S.btn(job && job.status === "done" ? "Neu analysieren" : "Mit KI analysieren", { icon: "sparkles", kind: "primary", size: "sm", onClick: start }));
    }
    async function poll() {
      for (var i = 0; i < 60 && box.isConnected; i++) {
        await new Promise(function (r) { setTimeout(r, 2000); });
        var j = await api("ai", { query: { target: target }, quiet: true }).catch(function () { return null; });
        if (j && j.status !== "running") { render(j); return; }
      }
    }
    async function start() { var r = await api("analyze", { body: { target: target } }); render(r.job); if (r.job && r.job.status === "running") poll(); }
    render(existing);
    if (existing && existing.status === "running") poll();
    return box;
  }

  // ============================================================ router
  P.diagnostics = async function (param, query, alive) {
    var parts = String(param || "live").split("/"), tab = parts[0] || "live", id = parts.slice(1).join("/");
    var ov = await api("overview", { query: { range: tab === "live" ? 600 : 120 } }); if (!alive()) return;
    S.title("Diagnose", ov.tap ? "Konsole: " + ov.tap : "");
    var ctx = { ov: ov, alive: alive, canRun: ov.canRun, canManage: ov.canManage, ai: ov.ai && ov.ai.available };
    var head = h("div", { class: "stack" }, ov.alert ? alertBanner(ov.alert) : null, tabs(tab === "incident" ? "disconnects" : tab === "crash" ? "crashes" : tab, ov.canSettings));
    var body;
    switch (tab) {
      case "incident": body = await incident(id, ctx); break;
      case "disconnects": body = await disconnects(ctx, query); break;
      case "lag": body = await lag(ctx, query.get("refresh") === "1"); break;
      case "spark": body = id ? await sparkDetail(id, ctx) : await spark(ctx); break;
      case "crashes": case "crash": body = id ? await crashDetail(id, ctx) : await crashes(ctx); break;
      case "full": body = await full(ctx); break;
      case "console": body = await consoleView(ctx, query); break;
      case "settings": body = settings(ctx); break;
      default: body = live(ctx);
    }
    if (!alive()) return;
    S.mount(h("div", { class: "stack" }, head, body));
    if (tab === "live") S.every(5000, async function () { try { var next = await api("overview", { query: { range: 600 }, quiet: true }); if (!alive()) return; ctx.ov = next; var fresh = live(ctx); var old = document.querySelector(".diag-live"); if (old) old.replaceWith(fresh); } catch (e) { /* next tick */ } });
  };

  function alertBanner(a) {
    var sev = SEVERITY[a.severity] || ["Hinweis", "info"];
    return h("a", { class: "notice " + (a.severity === "critical" ? "danger" : "warn") + " clickable", href: "#/diagnostics/incident/" + a.id }, icon("alert"),
      h("div", { class: "grow" }, h("b", { text: sev[0] + ": " + a.title }), h("div", { class: "small", text: a.cause })), icon("chevRight"));
  }

  // ============================================================ live
  function live(ctx) {
    var ov = ctx.ov, l = ov.live || {}, tps = (l.tps || [20])[0], heap = l.heapMax ? l.heapUsed / l.heapMax * 100 : 0, d = ov.disconnects || {};
    var kpis = h("div", { class: "grid kpis" },
      tile("TPS", fmt(tps, 1), { icon: "activity", tone: tps >= 18.5 ? "g" : tps >= 15 ? "y" : "r", hint: "5 min " + fmt((l.tps || [])[1]) + " · 15 min " + fmt((l.tps || [])[2]) }),
      tile("Tickzeit", fmt(l.mspt, 1), { unit: "ms Ø", icon: "clock", tone: l.mspt < 30 ? "g" : l.mspt < 45 ? "y" : "r", hint: "Längster Tick (60 s): " + fmt(l.msptMax60, 0) + " ms", extra: S.bar(l.mspt, 50, 80) }),
      tile("Arbeitsspeicher", S.bytes(l.heapUsed), { unit: "/ " + S.bytes(l.heapMax), icon: "memory", tone: heap < 80 ? "v" : "r", hint: "GC " + fmt(l.gcMsPerMinute, 0) + " ms/min", extra: S.bar(l.heapUsed, l.heapMax) }),
      tile("CPU", pct(l.cpu), { icon: "cpu", tone: l.cpu < 70 ? "g" : "y", hint: "System " + pct(l.systemCpu) + " · " + l.threads + " Threads" }),
      tile("Spieler", S.num((l.java || 0) + (l.bedrock || 0)), { icon: "users", hint: (l.java || 0) + " Java · " + (l.bedrock || 0) + " Bedrock" }),
      tile("Welt", S.num(l.entities), { unit: "Entities", icon: "globe", tone: "v", hint: S.num(l.tiles) + " Block-Entities · " + S.num(l.chunks) + " Chunks · Sicht " + l.viewDistance + " / Sim " + l.simulationDistance, href: "#/diagnostics/lag" }),
      tile("Disconnects (1 h)", S.num(d.lastHour), { icon: "wifi", tone: d.involuntary >= 4 ? "r" : d.involuntary ? "y" : "g", hint: d.involuntary + " unfreiwillig · " + d.java + " Java / " + d.bedrock + " Bedrock", href: "#/diagnostics/disconnects" }),
      tile("Abstürze", S.num(ov.crashCount), { icon: "alert", tone: ov.crashCount ? "y" : "g", hint: ov.lastCrash ? ov.lastCrash.title + " · " + S.ago(ov.lastCrash.detectedAt) : "Keiner erkannt", href: "#/diagnostics/crashes" }));
    var actions = h("div", { class: "row wrap" },
      ctx.canRun ? S.btn("Volldiagnose starten", { kind: "primary", icon: "shieldCheck", onClick: function () { S.go("#/diagnostics/full?run=1"); } }) : null,
      S.btn("Lag-Karte", { icon: "map", onClick: function () { S.go("#/diagnostics/lag?refresh=1"); } }),
      ov.spark && ov.spark.available ? S.btn("Spark-Profil", { icon: "chart", onClick: function () { S.go("#/diagnostics/spark"); } }) : null,
      ov.lastFull ? S.badge("Letzte Volldiagnose: " + ov.lastFull.score + "/100 · " + S.ago(ov.lastFull.at), ov.lastFull.score >= 85 ? "ok" : ov.lastFull.score >= 60 ? "warn" : "danger") : null);
    var spikes = (ov.spikes || []).slice(0, 8);
    var cats = Object.keys(d.byCategory || {}).map(function (k) { return { label: (d.titles || {})[k] || k, value: d.byCategory[k] }; }).sort(function (a, b) { return b.value - a.value; });
    return h("div", { class: "stack diag-live" }, kpis, actions,
      S.card("Tickrate & Tickzeit (10 min)", { icon: "activity", sub: "Ab 50 ms pro Tick sinkt die TPS; Spitzen über 1 s spüren Spieler als Lag." }, chart(ov.series || {})),
      h("div", { class: "grid two" },
        S.card("Lag-Spitzen", { icon: "alert", sub: spikes.length ? "Ticks über der Schwelle" : "Keine Spitzen gemessen" }, spikes.length ? h("div", {}, spikes.map(function (s) { return h("div", { class: "right-row" }, S.badge(fmt(s.ms, 0) + " ms", s.ms >= 5000 ? "danger" : "warn"), h("div", { class: "grow small", text: S.ago(s.at) + " · " + s.players + " Spieler · " + S.num(s.entities) + " Entities · TPS " + fmt(s.tps) })); })) : S.empty("check", "Ruhig", "Keine Ticks über der Spitzen-Schwelle.")),
        S.card("Disconnects nach Grund (1 h)", { icon: "wifi" }, cats.length ? bars(cats, "label", "value") : S.empty("check", "Keine Trennungen", "In der letzten Stunde hat niemand die Verbindung verloren."))),
      (ov.incidents || []).length ? S.card("Letzte Vorfälle", { icon: "bell" }, incidentList(ov.incidents.slice(0, 6))) : null);
  }

  function incidentList(rows) {
    return h("div", {}, rows.map(function (i) {
      var v = VERDICT[i.verdict] || ["Unklar", "warn"], s = SEVERITY[i.severity] || ["Hinweis", "info"];
      return h("a", { class: "right-row clickable", href: "#/diagnostics/incident/" + i.id }, S.badge(v[0], v[1]), h("div", { class: "grow" }, h("b", { text: i.title }), h("small", { class: "muted", text: S.ago(i.firstAt) + " · " + s[0] + " · " + (i.cause || "") })),
        i.resolved ? S.badge("Erledigt", "ok", "check") : null, i.hasAi ? S.badge("KI", "accent", "sparkles") : null, icon("chevRight"));
    }));
  }

  // ============================================================ disconnects
  async function disconnects(ctx, query) {
    var state = { category: query.get("category") || "", platform: query.get("platform") || "", q: query.get("q") || "" };
    var d = await api("disconnects", { query: { category: state.category, platform: state.platform, q: state.q, limit: 200 } });
    function go(patch) { Object.assign(state, patch); var qs = new URLSearchParams(); Object.keys(state).forEach(function (k) { if (state[k]) qs.set(k, state[k]); }); S.go("#/diagnostics/disconnects" + (qs.toString() ? "?" + qs : "")); }
    var max = Math.max.apply(null, (d.hours || [0]).concat([1]));
    var hist = h("div", { class: "diag-hist" }, (d.hours || []).map(function (n, i) { var inv = d.involuntaryHours[i]; return h("div", { class: "col", title: (23 - i) + " h her: " + n + " (" + inv + " unfreiwillig)" }, h("i", { class: "all", style: { height: Math.round(n / max * 100) + "%" } }), h("i", { class: "inv", style: { height: Math.round(inv / max * 100) + "%" } })); }));
    var catSel = h("select", { class: "input" }, [["", "Alle Gründe"], ["involuntary", "Nur unfreiwillige"]].concat(Object.keys(d.titles || {}).map(function (k) { return [k, d.titles[k]]; })).map(function (o) { return h("option", { value: o[0], selected: o[0] === state.category, text: o[1] }); }));
    catSel.addEventListener("change", function () { go({ category: catSel.value }); });
    var filters = h("div", { class: "row wrap" }, S.searchBox("Spieler oder Meldung", function (v) { go({ q: v }); }, state.q), catSel,
      S.segmented([["", "Alle"], ["java", "Java"], ["bedrock", "Bedrock"]], state.platform, function (v) { go({ platform: v }); }));
    function packetTrace(trace) {
      if (!trace || !trace.source) return "–";
      return h("details", {}, h("summary", { text: trace.inbound + " rein / " + trace.outbound + " raus" }),
        h("p", { text: trace.exception || "Kein Netty-Fehler aufgezeichnet" }),
        h("p", { text: "Letztes KeepAlive IN / OUT: " + (trace.keepaliveInAgeMs < 0 ? "–" : trace.keepaliveInAgeMs + " ms") + " / " + (trace.keepaliveOutAgeMs < 0 ? "–" : trace.keepaliveOutAgeMs + " ms") }),
        (trace.recent || []).map(function (packet) { return h("div", { class: "mono tiny", text: packet.direction + " · " + packet.type + " · " + packet.ageMs + " ms" }); }));
    }
    var table = h("div", { class: "table-wrap" }, h("table", { class: "table" }, h("thead", {}, h("tr", {}, ["Zeit", "Spieler", "Grund", "Meldung", "Ping", "Sitzung", "Ort", "TPS / Tick", "Paketspur"].map(function (t) { return h("th", { text: t }); }))),
      h("tbody", {}, (d.items || []).map(function (r) {
        return h("tr", {}, h("td", { class: "small", text: S.ago(r.at) }), h("td", {}, h("a", { href: "#/player/" + r.uuid, text: r.name }), " ", S.badge(r.platform === "bedrock" ? "Bedrock" : "Java", r.platform === "bedrock" ? "info" : "")),
          h("td", {}, S.badge(r.categoryTitle, r.involuntary ? "danger" : "")), h("td", { class: "small mono", text: r.message || (r.kickCause ? r.kickCause : "–") }), h("td", { class: "small", text: r.ping >= 0 ? r.ping + " ms" : "–" }),
          h("td", { class: "small", text: r.sessionMs ? S.dur(r.sessionMs) : "–" }), h("td", { class: "small", text: r.world ? r.world + " " + r.blockX + " / " + r.blockZ : "–" }), h("td", { class: "small", text: fmt(r.tps) + " / " + fmt(r.msptMax, 0) + " ms" }), h("td", { class: "small" }, packetTrace(r.packetTrace)));
      }))));
    return h("div", { class: "stack" },
      S.card("Vorfälle", { icon: "bell", sub: "Mehrere Disconnects in kurzer Zeit oder viele Spieler gleichzeitig – mit Einschätzung legitim oder Fehler" }, (d.incidents || []).length ? incidentList(d.incidents) : S.empty("check", "Keine Vorfälle", "Bisher keine Disconnect-Welle erkannt.")),
      S.card("Letzte 24 Stunden", { icon: "chart", sub: "Grau = alle Trennungen, rot = unfreiwillige (Timeout, Abbruch, Paketfehler)" }, hist),
      S.card("Einzelne Trennungen", { icon: "wifi", sub: (d.items || []).length + " Einträge · Gründe aus Kick, Quit-Grund und Konsole" }, filters, (d.items || []).length ? table : S.empty("search", "Keine Treffer", "Filter anpassen.")));
  }

  async function incident(id, ctx) {
    var i = await api("incident", { query: { id: id } });
    var v = i.verdict || {}, verdict = VERDICT[v.verdict] || ["Unklar", "warn"], sev = SEVERITY[v.severity] || ["Hinweis", "info"];
    var actions = ctx.canManage ? h("div", { class: "row wrap" },
      i.resolved ? S.btn("Wieder öffnen", { size: "sm", icon: "refresh", onClick: async function () { await api("incident", { body: { id: i.id, operation: "reopen" } }); S.reload(); } })
        : S.btn("Als erledigt markieren", { size: "sm", icon: "check", kind: "primary", onClick: async function () { var note = await S.prompt("Vorfall erledigen", "Notiz (optional)", "", "Erledigt"); if (note === null) return; await api("incident", { body: { id: i.id, operation: "resolve", note: note } }); S.reload(); } }),
      S.btn("Löschen", { size: "sm", icon: "trash", kind: "danger", onClick: async function () { if (!(await S.confirm("Vorfall löschen?", "Die einzelnen Disconnect-Einträge bleiben erhalten.", "Löschen", true))) return; await api("incident", { body: { id: i.id, operation: "delete" } }); S.go("#/diagnostics/disconnects"); } })) : null;
    var st = i.state || {};
    return h("div", { class: "stack" },
      S.card(i.title, { icon: "bell", sub: S.date(i.firstAt) + (i.resolved ? " · erledigt" + (i.resolvedBy ? " von " + i.resolvedBy : "") + (i.note ? " – " + i.note : "") : ""), actions: actions },
        h("div", { class: "row wrap" }, S.badge(verdict[0], verdict[1]), S.badge(sev[0], sev[1]), v.dominantTitle ? S.badge(v.dominantTitle) : null),
        h("p", { text: v.cause }), (v.steps || []).length ? h("ol", {}, v.steps.map(function (s) { return h("li", { text: s }); })) : null,
        h("div", { class: "muted small" }, (v.evidence || []).map(function (e) { return h("div", { text: "• " + e }); })),
        S.kv([["TPS", fmt(st.tps)], ["Längster Tick 30 s", fmt(st.msptMax30s, 0) + " ms"], ["RAM", S.bytes(st.heapUsed) + " / " + S.bytes(st.heapMax)], ["GC", fmt(st.gcMsPerMinute, 0) + " ms/min"], ["CPU", pct(st.cpu)], ["Online danach", st.online], ["Watchdog", st.watchdog ? "ja" : "nein"]])),
      S.card("KI-Einschätzung", { icon: "sparkles", sub: "Kurze Auswertung der Daten oben und der letzten Konsolenzeilen (ohne IP-Adressen)" }, aiBox("incident:" + i.id, i.ai, ctx.ai, ctx.canRun)),
      (i.records || []).length ? S.card("Betroffene Spieler", { icon: "users" }, h("div", {}, i.records.map(function (r) {
        return h("div", { class: "right-row" }, S.badge(r.platform === "bedrock" ? "Bedrock" : "Java", r.platform === "bedrock" ? "info" : ""), h("div", { class: "grow" }, h("a", { href: "#/player/" + r.uuid }, h("b", { text: r.name })),
          h("small", { class: "muted", text: " " + r.categoryTitle + " · " + (r.message || "–") + " · Ping " + r.ping + " ms · " + (r.world || "") + " " + r.blockX + " / " + r.blockZ })), h("small", { text: new Date(r.at).toLocaleTimeString("de-DE") }));
      }))) : null,
      (i.logs || []).length ? S.card("Konsole rund um den Vorfall", { icon: "terminal" }, logList(i.logs)) : null);
  }

  function logList(lines) {
    return h("div", { class: "diag-log" }, lines.map(function (l) { return h("div", { class: "line " + String(l.level || "").toLowerCase() }, h("span", { class: "t", text: new Date(l.at).toLocaleTimeString("de-DE") }), h("span", { class: "lv", text: l.level }), h("span", { class: "src", text: l.source }), h("span", { class: "msg", text: l.text })); }));
  }

  // ============================================================ lag map
  async function lag(ctx, refresh) {
    var d = await api("lagmap", { query: { refresh: refresh ? "1" : "" } });
    function chunkTable(rows, label) {
      return rows.length ? h("div", { class: "table-wrap" }, h("table", { class: "table" }, h("thead", {}, h("tr", {}, ["Anzahl", "Welt", "Position", "Typen", "Nächster Spieler", ""].map(function (t) { return h("th", { text: t }); }))),
        h("tbody", {}, rows.map(function (r) { return h("tr", {}, h("td", {}, S.badge(S.num(r.count) + " " + label, r.count >= 300 ? "danger" : r.count >= 120 ? "warn" : "")), h("td", { text: r.world }), h("td", { class: "mono small", text: r.x + " / " + r.z + " (Chunk " + r.chunkX + ", " + r.chunkZ + ")" }),
          h("td", { class: "small", text: (r.types || []).map(function (t) { return t.count + "× " + t.type; }).join(", ") }), h("td", { class: "small", text: r.nearest || "–" }), h("td", {}, copyTp(r))); })))) : S.empty("check", "Keine Ansammlung", "");
    }
    return h("div", { class: "stack" },
      h("div", { class: "row wrap" }, S.btn("Neu messen", { icon: "refresh", onClick: function () { S.go("#/diagnostics/lag?refresh=1&t=" + Date.now()); } }), h("span", { class: "muted small", text: "Gemessen " + S.ago(d.at) + " in " + d.tookMs + " ms" + (d.partial ? " · Block-Entities nur teilweise (Zeitlimit)" : "") })),
      S.card("Befunde", { icon: "alert" }, h("div", {}, (d.tips || []).map(findingRow))),
      S.card("KI-Einschätzung", { icon: "sparkles" }, aiBox("lag:", null, ctx.ai, ctx.canRun)),
      S.card("Welten", { icon: "globe" }, h("div", { class: "table-wrap" }, h("table", { class: "table" }, h("thead", {}, h("tr", {}, ["Welt", "Spieler", "Entities", "Block-Entities", "davon tickend", "Chunks"].map(function (t) { return h("th", { text: t }); }))),
        h("tbody", {}, (d.worlds || []).map(function (w) { return h("tr", {}, h("td", { text: w.name }), h("td", { text: w.players }), h("td", { text: S.num(w.entities) }), h("td", { text: S.num(w.tileEntities) }), h("td", { text: S.num(w.tickableTiles) }), h("td", { text: S.num(w.chunks) })); }))))),
      S.card("Entity-Hotspots", { icon: "map", sub: "Chunks mit den meisten Entities (Mobs, Items, Rahmen …)" }, chunkTable(d.hotChunks || [], "Entities")),
      S.card("Block-Entity-Hotspots", { icon: "box", sub: "Trichter, Öfen, Truhen – große Anlagen kosten Tickzeit" }, chunkTable(d.tileChunks || [], "Blöcke")),
      h("div", { class: "grid two" },
        S.card("Entity-Typen", { icon: "chart" }, bars(d.entityTypes || [], "type", "count")),
        S.card("Chunk-Generierung (10 min)", { icon: "globe", sub: S.num((d.generation || {}).loads10m) + " Ladevorgänge · " + S.num((d.generation || {}).new10m) + " neue Chunks" }, ((d.generation || {}).players || []).length ? bars(d.generation.players, "name", "newChunks") : S.empty("check", "Keine neuen Chunks", "Niemand erkundet gerade neues Gebiet."))),
      S.card("Spieler nach Entities in der Nähe (48 Blöcke)", { icon: "users" }, h("div", {}, (d.players || []).map(function (p) { return h("div", { class: "right-row" }, h("b", { class: "grow", text: p.name }), h("small", { class: "muted", text: p.world + " " + p.x + " / " + p.z + " · Ping " + p.ping + " ms" }), S.badge(S.num(p.nearbyEntities) + " Entities", p.nearbyEntities > 300 ? "danger" : p.nearbyEntities > 120 ? "warn" : "")); }))));
  }

  // ============================================================ spark
  async function spark(ctx) {
    var d = await api("spark");
    var job = d.job || {};
    var secs = h("select", { class: "input" }, [30, 60, 120, 300, 600].map(function (s) { return h("option", { value: s, selected: s === 60, text: s < 120 ? s + " Sekunden" : s / 60 + " Minuten" }); }));
    var all = false;
    var control = !d.available ? S.notice("Spark ist auf diesem Server nicht verfügbar. Paper enthält Spark meist bereits; sonst das Spark-Plugin installieren.", "warn")
      : h("div", { class: "stack" }, job.status === "running" ? S.notice("Profil läuft seit " + S.ago(job.startedAt) + " (" + job.seconds + " s, gestartet von " + job.by + "). Danach wird es automatisch analysiert.", "", "clock") : null,
        job.status === "analyzing" || job.status === "uploading" ? S.notice("Profil wird hochgeladen und analysiert …", "", "clock") : null,
        ctx.canManage ? h("div", { class: "row wrap" }, S.field("Dauer", secs), h("label", { class: "row small" }, S.sw(false, function (on) { all = on; }), h("span", { text: "Alle Threads (sonst nur Server-Thread)" })),
          S.btn("Profil starten", { kind: "primary", icon: "activity", disabled: job.status === "running", onClick: async function () { var r = await api("spark", { body: { operation: "start", seconds: secs.value, allThreads: String(all) } }); S.toast(r.title, "ok", r.description); S.reload(); } }),
          job.status === "running" ? S.btn("Jetzt beenden", { icon: "x", onClick: async function () { await api("spark", { body: { operation: "stop" } }); S.reload(); } }) : null) : S.notice("Profile starten benötigt diagnostics.manage.", "", "lock"));
    var link = S.input({ placeholder: "https://spark.lucko.me/… oder Code" });
    var importer = ctx.canRun ? h("div", { class: "row wrap" }, h("div", { class: "grow" }, link), S.btn("Analysieren", { icon: "download", onClick: async function () { var r = await api("spark", { body: { operation: "import", code: link.value.trim() } }); S.toast(r.title, "ok", r.description); setTimeout(S.reload, 1500); } })) : null;
    var list = (d.items || []).length ? h("div", {}, d.items.map(function (p) {
      return h("a", { class: "right-row clickable", href: p.status === "done" ? "#/diagnostics/spark/" + p.code : "#/diagnostics/spark" }, S.badge({ done: "Fertig", failed: "Fehler", downloading: "Lädt", analyzing: "Analysiert" }[p.status] || p.status, p.status === "done" ? "ok" : p.status === "failed" ? "danger" : "info"),
        h("div", { class: "grow" }, h("b", { class: "mono", text: p.code }), h("small", { class: "muted", text: " " + S.ago(p.at) + " · " + (p.origin || "") + (p.bytes ? " · " + S.bytes(p.bytes) : "") + (p.error ? " · " + p.error : "") + (p.tpsAvg ? " · TPS Ø " + fmt(p.tpsAvg) + " · Spitze " + fmt(p.msptPeak, 0) + " ms" : "") })), icon("chevRight"));
    })) : S.empty("chart", "Noch keine Profile", "Profil starten oder einen Spark-Link einfügen.");
    if (d.items && d.items.some(function (p) { return p.status === "downloading" || p.status === "analyzing"; }) || job.status === "running" || job.status === "analyzing") S.every(4000, function () { S.reload(); });
    return h("div", { class: "stack" }, S.card("Profil aufnehmen", { icon: "activity", sub: "Spark misst, welche Plugins und Methoden die Tickzeit verbrauchen – serverseitig gesteuert" }, control),
      S.card("Vorhandenes Profil analysieren", { icon: "download", sub: "Das Rohprofil wird vom Server geladen und dort ausgewertet (auch große Profile)" }, importer || S.notice("Benötigt diagnostics.run.", "", "lock")),
      S.card("Profile", { icon: "list" }, list));
  }

  async function sparkDetail(code, ctx) {
    var p = await api("spark", { query: { code: code } }); var a = p.analysis || {}, w = a.windows || {}, m = a.metadata || {}, ps = m.platformStatistics || {}, sys = m.system || {};
    var kpis = h("div", { class: "grid kpis" },
      tile("TPS Ø", fmt(w.tpsAvg, 2), { icon: "activity", tone: w.tpsAvg >= 18.5 ? "g" : "y", hint: "Minimum " + fmt(w.tpsMin, 2) }),
      tile("Tick-Median Ø", fmt(w.msptMedianAvg), { unit: "ms", icon: "clock", tone: w.msptMedianAvg < 30 ? "g" : w.msptMedianAvg < 40 ? "y" : "r", hint: "Spitze " + fmt(w.msptPeak, 0) + " ms" }),
      tile("Leerlauf", pct(a.idleShare), { icon: "sparkles", tone: "v", hint: "Reserve des Server-Threads" }),
      tile("Entities Ø", S.num(w.entitiesAvg), { icon: "globe", hint: S.num(w.tileEntitiesAvg) + " Block-Entities · " + S.num(w.chunksAvg) + " Chunks" }),
      tile("Dauer", S.dur((m.durationSeconds || 0) * 1000), { icon: "clock", hint: (w.count || 0) + " Messfenster · " + fmt(w.playersAvg) + " Spieler Ø" }),
      tile("CPU (Prozess)", pct(w.cpuProcessAvg), { icon: "cpu", hint: (sys.cpuModel || "") + " · Java " + (sys.java || "") }));
    function frameTable(rows, cols) { return h("div", { class: "table-wrap" }, h("table", { class: "table" }, h("tbody", {}, rows.map(function (r) { return h("tr", {}, cols.map(function (c) { return h("td", { class: c === "frame" ? "mono small" : "small", text: c === "share" ? pct(r.share) : r[c] || "–" }); })); })))); }
    return h("div", { class: "stack" },
      h("div", { class: "row wrap" }, h("a", { class: "btn ghost sm", href: "#/diagnostics/spark" }, icon("chevLeft"), "Profile"), h("a", { class: "btn sm", href: p.url, target: "_blank", rel: "noopener noreferrer" }, icon("external"), "Im Spark-Viewer öffnen"),
        h("span", { class: "muted small", text: ((m.platform || {}).name || "") + " " + ((m.platform || {}).version || "") + " · Spark " + ((m.platform || {}).spark || "") + " · " + (m.engine || "") })),
      kpis,
      S.card("Befunde", { icon: "alert" }, h("div", {}, (a.findings || []).map(findingRow))),
      S.card("KI-Einschätzung", { icon: "sparkles", sub: "Es werden nur die Kennzahlen und Top-Methoden gesendet, nicht das Profil" }, aiBox("spark:" + code, p.ai, ctx.ai, ctx.canRun)),
      S.card("Tickverlauf", { icon: "chart" }, chart({ tps: (w.tps || []).slice().reverse(), mspt: (w.msptMedian || []).slice().reverse(), msptMax: (w.msptMax || []).slice().reverse() })),
      h("div", { class: "grid two" },
        S.card("Plugins nach Rechenzeit", { icon: "puzzle", sub: "Inklusive der Paper-Arbeit, die das Plugin auslöst · Rest " + pct(a.vanillaShare) + " Paper/Minecraft" }, bars(a.plugins || [], "name", "share")),
        S.card("Plugin-Stellen", { icon: "terminal", sub: "Welche Funktion eines Plugins die Zeit verursacht" }, frameTable((a.pluginFrames || []).slice(0, 15), ["plugin", "frame", "share"]))),
      S.card("Teuerste Methoden (Eigenzeit)", { icon: "list" }, frameTable((a.methods || []).slice(0, 25), ["frame", "source", "share"])),
      (ps.world && (ps.world.hotChunks || []).length) ? S.card("Entity-Hotspots zum Zeitpunkt des Profils", { icon: "map" }, h("div", {}, ps.world.hotChunks.map(function (c) { return h("div", { class: "right-row" }, S.badge(c.entities + " Entities", c.entities >= 150 ? "danger" : "warn"), h("div", { class: "grow small", text: c.world + " bei " + c.blockX + " / " + c.blockZ + " · " + (c.types || []).map(function (t) { return t.count + "× " + t.type; }).join(", ") })); }))) : null,
      S.card("Konfiguration", { icon: "settings" }, S.kv(Object.keys(m.config || {}).map(function (k) { return [k, String(m.config[k])]; }))));
  }

  // ============================================================ crashes
  async function crashes(ctx) {
    var d = await api("crashes");
    return S.card("Abstürze & unerwartete Neustarts", { icon: "alert", sub: "Erkannt über Herzschlag, Konsole, hs_err- und Crash-Report-Dateien. Normale Neustarts erscheinen hier nicht." },
      (d.items || []).length ? h("div", {}, d.items.map(function (c) {
        return h("a", { class: "right-row clickable", href: "#/diagnostics/crash/" + c.id }, S.badge(CRASH[c.kind] || c.kind, c.kind === "killed" ? "warn" : "danger"), h("div", { class: "grow" }, h("b", { text: c.title }), h("small", { class: "muted", text: " " + S.date(c.detectedAt) + " · Ausfall " + S.dur(c.downtimeSeconds * 1000) + " · Sicherheit " + c.confidence })), icon("chevRight"));
      })) : S.empty("check", "Keine Abstürze", "Seit Admin Suite 10.2.0 wurde kein Absturz erkannt."));
  }
  async function crashDetail(id, ctx) {
    var c = await api("crashes", { query: { id: id } }); var hb = c.heartbeat || {};
    return h("div", { class: "stack" },
      S.card(c.title, { icon: "alert", sub: S.date(c.detectedAt) + " · Sicherheit " + c.confidence }, h("p", { text: c.cause }),
        S.kv([["Letzter Herzschlag", S.date(c.lastBeat)], ["Ausfall", S.dur(c.downtimeSeconds * 1000)], ["TPS davor", hb.tps], ["RAM davor", hb.heapUsed ? S.bytes(hb.heapUsed) + " / " + S.bytes(hb.heapMax) : ""], ["Spieler davor", hb.players], ["Suite-Version", hb.version]]),
        (c.evidence || []).length ? h("div", { class: "diag-log" }, c.evidence.map(function (e) { return h("div", { class: "line error" }, h("span", { class: "msg", text: e })); })) : null),
      S.card("KI-Einschätzung", { icon: "sparkles" }, aiBox("crash:" + c.id, c.ai, ctx.ai, ctx.canRun)),
      c.hsErr ? S.card("hs_err (Auszug)", { icon: "terminal" }, h("pre", { class: "diag-pre", text: c.hsErr })) : null,
      c.crashReport ? S.card("Crash-Report (Auszug)", { icon: "terminal" }, h("pre", { class: "diag-pre", text: c.crashReport })) : null,
      S.card("Letzte Konsolenzeilen vor dem Absturz", { icon: "terminal", actions: S.btn("Kopieren", { size: "sm", icon: "copy", onClick: function () { return S.copy((c.log || []).join("\n"), "Log"); } }) }, h("pre", { class: "diag-pre", text: (c.log || []).join("\n") || "Keine Zeilen gesichert." })));
  }

  // ============================================================ full diagnosis
  async function full(ctx) {
    var d = await api("full"); var wrap = h("div", { class: "stack" });
    async function run() { d = await api("full", { body: {} }); render(); }
    function render() {
      S.clear(wrap);
      wrap.appendChild(h("div", { class: "row wrap" }, ctx.canRun ? S.btn(d && d.at ? "Erneut prüfen" : "Volldiagnose starten", { kind: "primary", icon: "shieldCheck", onClick: run }) : S.notice("Benötigt diagnostics.run.", "", "lock"),
        d && d.at ? h("span", { class: "muted small", text: "Zuletzt " + S.ago(d.at) }) : null));
      if (!d || !d.at) { wrap.appendChild(S.card(null, {}, S.empty("shieldCheck", "Noch keine Volldiagnose", "Prüft TPS, Tickzeiten, RAM/GC, CPU, Threads, Speicherplatz, Welt, Plugins, Konsolenfehler, Disconnects, Abstürze sowie DNS, Mojang und Internet vom Server aus."))); return; }
      var groups = { server: "Server", memory: "Speicher", system: "System", world: "Welt", network: "Netzwerk", plugins: "Plugins" };
      wrap.appendChild(S.card(null, { cls: "hero" }, h("div", { class: "row wrap", style: { gap: "16px" } }, h("div", { class: "diag-score " + (d.score >= 85 ? "ok" : d.score >= 60 ? "warn" : "danger"), text: String(d.score) }), h("div", { class: "grow" }, h("h2", { style: { margin: 0 }, text: d.summary }), h("div", { class: "muted small", text: d.critical + " kritisch · " + d.warnings + " Hinweise · " + d.checks.length + " Prüfungen" })))));
      Object.keys(groups).forEach(function (g) { var rows = d.checks.filter(function (c) { return c.group === g; }); if (rows.length) wrap.appendChild(S.card(groups[g], { icon: { server: "server", memory: "memory", system: "cpu", world: "globe", network: "wifi", plugins: "puzzle" }[g] }, h("div", {}, rows.map(findingRow)))); });
      wrap.appendChild(S.card("KI-Einschätzung", { icon: "sparkles" }, aiBox("full:", null, ctx.ai, ctx.canRun)));
    }
    render();
    if (location.hash.indexOf("run=1") >= 0 && ctx.canRun && !(d && d.at && Date.now() - d.at < 30000)) run().catch(function (e) { S.toast(e.message, "err"); });
    return wrap;
  }

  // ============================================================ console
  async function consoleView(ctx, query) {
    if (S.has("console-archive-v1")) return archiveView(ctx, query);
    var level = query.get("level") || "", q = query.get("q") || "";
    var d = await api("logs", { query: { level: level, q: q, limit: 300, minutes: 180 } });
    function go(l, s) { var qs = new URLSearchParams(); if (l) qs.set("level", l); if (s) qs.set("q", s); S.go("#/diagnostics/console" + (qs.toString() ? "?" + qs : "")); }
    return h("div", { class: "stack" }, S.card("Konsole (letzte 3 Stunden)", { icon: "terminal", sub: "IP-Adressen sind ausgeblendet · " + (d.tap || "") },
      h("div", { class: "row wrap" }, S.segmented([["", "Alles"], ["WARN", "Warnungen+"], ["ERROR", "Fehler"]], level, function (v) { go(v, q); }), S.searchBox("Suchen (z. B. lost connection, Plugin-Name)", function (v) { go(level, v); }, q)),
      (d.items || []).length ? logList(d.items) : S.empty("terminal", "Keine Zeilen", "Filter anpassen.")),
      S.card("KI-Einschätzung der Warnungen & Fehler (20 min)", { icon: "sparkles" }, aiBox("logs:", null, ctx.ai, ctx.canRun)));
  }

  async function archiveView(ctx, query) {
    var level = query.get("level") || "", term = query.get("q") || "", from = query.get("from") || "", to = query.get("to") || "";
    var data = await api("console-archive", { query: { level: level, q: term, from: from, to: to } });
    var rows = h("div", { class: "stack" }), more = null;
    function rowsFor(items) {
      return (items || []).slice().reverse().map(function (row) {
        return h("div", { class: "item row wrap" }, h("div", { class: "grow" },
          h("b", { text: S.date(row.at) + " · " + row.level + " · " + row.source }),
          h("pre", { class: "mono small console-text", text: row.text })),
          data.manage ? S.btn("Löschen", { kind: "ghost", size: "sm", onClick: function () { return change({ operation: "delete", id: row.id }, "Diese Konsolenzeile dauerhaft aus dem Archiv löschen?"); } }) : null);
      });
    }
    async function change(body, prompt) {
      if (!await S.confirm("Konsolenarchiv", prompt, "Bestätigen", true)) return;
      try { await api("console-archive", { body: Object.assign(body, { confirmed: true }) }); S.reload(); }
      catch (e) { S.toast(e.message, "err"); }
    }
    function route(changes) {
      var next = new URLSearchParams(query);
      Object.keys(changes).forEach(function (key) { if (changes[key]) next.set(key, changes[key]); else next.delete(key); });
      S.go("#/diagnostics/console" + (next.size ? "?" + next.toString() : ""));
    }
    function localInput(value, label) {
      var input = S.input({ type: "datetime-local", "aria-label": label });
      if (value) {
        var date = new Date(Number(value));
        if (Number.isFinite(date.getTime())) input.value = new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
      }
      return input;
    }
    var since = localInput(from, "Von"), until = localInput(to, "Bis");
    var days = S.input({ type: "number", value: data.days, min: 1, max: 365, "aria-label": "Aufbewahrung in Tagen" });
    S.add(rows, rowsFor(data.items));
    if (!(data.items || []).length) S.add(rows, S.empty("terminal", "Keine Treffer", "Das Archiv beginnt mit diesem Update."));
    if (data.next) more = S.btn("Ältere Zeilen laden", { onClick: async function () {
      more.disabled = true;
      try {
        var page = await api("console-archive", { query: { level: level, q: term, from: from, to: to, before: data.next } });
        data.next = page.next;
        S.add(rows, rowsFor(page.items));
        more.hidden = !data.next;
      } catch (e) { S.toast(e.message, "err"); }
      finally { more.disabled = false; }
    } });
    return h("div", { class: "stack" },
      S.card("Konsolenarchiv", { icon: "terminal", sub: data.days + " Tage · " + data.tap + " · IPs und erkannte Zugangsdaten maskiert" },
        h("div", { class: "row wrap" }, S.segmented([["", "Alles"], ["WARN", "Warnungen+"], ["ERROR", "Fehler"]], level, function (v) { route({ level: v }); }),
          S.searchBox("Stichwort oder Plugin-Name", function (v) { route({ q: v }); }, term)),
        h("div", { class: "row wrap" }, S.field("Von", since), S.field("Bis", until), S.btn("Zeitraum anwenden", { onClick: function () {
          route({ from: since.value ? new Date(since.value).getTime() : "", to: until.value ? new Date(until.value).getTime() : "" });
        } }), S.btn("Zeitraum zurücksetzen", { onClick: function () { route({ from: "", to: "" }); } }), S.btn("Aktualisieren", { onClick: S.reload })),
        data.dropped ? S.notice("Aufnahmepuffer überlastet. Fehlende Einträge in dieser Sitzung: " + data.dropped, "warn") : null,
        rows, more),
      data.manage ? S.card("Archiv verwalten", {}, h("div", { class: "row wrap" }, S.field("Aufbewahrung in Tagen", days),
        S.btn("Aufbewahrung speichern", { onClick: function () { return change({ operation: "retention", days: Number(days.value) }, "Zeilen außerhalb der neuen Aufbewahrung werden gelöscht."); } }),
        S.btn("Gesamtes Archiv leeren", { kind: "danger", onClick: function () { return change({ operation: "clear" }, "Alle Archivzeilen dauerhaft löschen? Die Aufzeichnung läuft weiter."); } }))) : null,
      S.card("KI-Einschätzung der Warnungen & Fehler (20 min)", { icon: "sparkles" }, aiBox("logs:", null, ctx.ai, ctx.canRun)));
  }

  // ============================================================ settings
  function settings(ctx) {
    var s = ctx.ov.settings || {}, inputs = {};
    function num(key, label, hint, min, max, step) { var i = S.input({ type: "number", value: s[key], min: min, max: max, step: step || 1 }); inputs[key] = i; return S.field(label, i, hint); }
    function flag(key, label) { var v = !!s[key]; inputs[key] = { get value() { return String(v); } }; return h("div", { class: "row" }, h("b", { class: "grow", text: label }), S.sw(v, function (on) { v = on; })); }
    return S.card("Diagnose-Einstellungen", { icon: "settings", sub: "Schwellenwerte für Vorfälle, Bark und KI" },
      h("div", { class: "grid two" }, num("burstWindow", "Fenster für mehrere Disconnects (s)", "5–120", 5, 120), num("burstMin", "Ab so vielen Spielern", "2–50", 2, 50), num("waveShare", "Welle ab Anteil der Spieler", "0,15–0,95", 0.15, 0.95, 0.05),
        num("waveWindow", "Fenster für Wellen (s)", "10–300", 10, 300), num("spikeMs", "Lag-Spitze ab (ms)", "250–20000", 250, 20000), num("flappingCount", "Instabil ab Trennungen in 10 min", "2–20", 2, 20), num("aiPerHour", "KI-Analysen pro Stunde", "0–120", 0, 120)),
      h("div", { class: "stack", style: { gap: "10px" } }, flag("enabled", "Diagnose aktiv"), flag("notifyLegit", "Auch legitime Wellen per Bark melden"), flag("aiEnabled", "KI-Analysen erlauben"), flag("aiAutoCritical", "Kritische Vorfälle automatisch mit KI prüfen"),
        flag("aiAutoCrash", "Abstürze automatisch mit KI prüfen"), flag("liveLog", "Konsole laufend für Absturzberichte sichern"), flag("sparkImportAll", "Auch Spark-Profile aus der Konsole analysieren")),
      h("div", { class: "row" }, S.btn("Speichern", { kind: "primary", icon: "check", onClick: async function () { var body = {}; Object.keys(inputs).forEach(function (k) { body[k] = String(inputs[k].value); }); var r = await api("settings", { body: body }); S.toast(r.title); S.reload(); } })),
      S.notice("Bark-Hinweise für Disconnect-Wellen, Abstürze und Server-Hänger aktiviert jedes Teammitglied selbst unter Benachrichtigungen (App: Sicherheitszentrale → Bark).", "", "bell"));
  }

  // ============================================================ player profile card
  /** Used by the player page: connection history of one player. */
  S.playerConnectionCard = function (id) {
    var c = S.card("Verbindung & Disconnects", { icon: "wifi", sub: "Letzte 7 Tage" }, S.skeleton(1));
    api("player", { query: { player: id }, quiet: true }).then(function (d) {
      var body = h("div", { class: "stack", style: { gap: "10px" } },
        h("div", { class: "row wrap" }, S.badge(d.day + " heute", d.day >= 5 ? "warn" : ""), S.badge(d.week + " in 7 Tagen"), S.badge(d.involuntary + " unfreiwillig", d.involuntary >= 3 ? "danger" : ""), d.flapping ? S.badge("Gerade instabil", "danger", "alert") : null,
          d.avgSessionMs ? S.badge("Ø Sitzung " + S.dur(d.avgSessionMs)) : null), h("p", { class: "muted small", style: { margin: 0 }, text: d.hint }),
        h("div", {}, (d.items || []).slice(0, 8).map(function (r) { return h("div", { class: "right-row" }, S.badge(r.categoryTitle, r.involuntary ? "danger" : ""), h("div", { class: "grow small", text: (r.message || r.kickCause || "–") + " · Ping " + r.ping + " ms" + (r.sessionMs ? " · " + S.dur(r.sessionMs) + " online" : "") }), h("small", { class: "muted", text: S.ago(r.at) })); })));
      var old = c.querySelector(".skeleton"); if (old) old.replaceWith(body); else c.appendChild(body);
    }).catch(function (e) { var old = c.querySelector(".skeleton"); if (old) old.replaceWith(h("p", { class: "muted small", text: e.message })); });
    return c;
  };
})();
