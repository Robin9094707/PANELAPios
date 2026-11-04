/* Simplex Panel Web – core: DOM helpers, icons, API client, shared UI components.
   All text is inserted with textContent; there is no innerHTML for server data. */
(function () {
  "use strict";
  var S = window.Simplex = window.Simplex || {};

  // ------------------------------------------------------------ DOM
  function h(tag, props) {
    var el = tag === "svg" || tag === "path" || tag === "circle" || tag === "rect" || tag === "line" || tag === "polyline" || tag === "defs" || tag === "linearGradient" || tag === "stop" || tag === "text"
      ? document.createElementNS("http://www.w3.org/2000/svg", tag) : document.createElement(tag);
    if (props) for (var k in props) {
      var v = props[k];
      if (v == null || v === false) continue;
      if (k === "class") el.setAttribute("class", v);
      else if (k === "text") el.textContent = v;
      else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
      else if (k.slice(0, 2) === "on" && typeof v === "function") el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === "value") el.value = v;
      else if (k === "checked" || k === "disabled" || k === "selected" || k === "autofocus") el[k] = !!v;
      else el.setAttribute(k, v === true ? "" : v);
    }
    for (var i = 2; i < arguments.length; i++) add(el, arguments[i]);
    return el;
  }
  function add(el, child) {
    if (child == null || child === false) return;
    if (Array.isArray(child)) { child.forEach(function (c) { add(el, c); }); return; }
    el.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }
  function mount(el) { var c = clear(S.content || document.getElementById("app")); for (var i = 0; i < arguments.length; i++) add(c, arguments[i]); return c; }

  // ------------------------------------------------------------ Icons (24px stroke icons)
  var P = {
    home: "M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z",
    users: "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75",
    user: "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8",
    ticket: "M3 7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v2a2 2 0 0 0 0 6v2a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-2a2 2 0 0 0 0-6zM13 5v2M13 17v2M13 11v2",
    chat: "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z",
    team: "M14 9a2 2 0 0 1-2 2H6l-4 4V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2zM18 9h2a2 2 0 0 1 2 2v11l-4-4h-6a2 2 0 0 1-2-2v-1",
    shield: "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10",
    shieldCheck: "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10M9 12l2 2 4-4",
    gavel: "m14 13-7.5 7.5a2.12 2.12 0 0 1-3-3L11 10M16 16l6-6M8 8l6-6M9 7l8 8M21 11l-8-8",
    chart: "M3 3v18h18M7 16v-5M12 16V8M17 16v-8",
    globe: "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20M2 12h20M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10",
    puzzle: "M19.4 13.5a2.5 2.5 0 1 0 0-3H18V7a1 1 0 0 0-1-1h-3.5V4.6a2.5 2.5 0 1 0-3 0V6H7a1 1 0 0 0-1 1v3.5h1.4a2.5 2.5 0 1 1 0 3H6V17a1 1 0 0 0 1 1h3.5v-1.4a2.5 2.5 0 1 1 3 0V18H17a1 1 0 0 0 1-1v-3.5z",
    grid: "M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z",
    settings: "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1",
    key: "M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.78 7.78 5.5 5.5 0 0 1 7.78-7.78zm0 0L15.5 7.5m0 0 3 3L22 7l-3-3m-3.5 3.5L19 4",
    phone: "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2M11 18h2",
    laptop: "M4 5h16a1 1 0 0 1 1 1v10H3V6a1 1 0 0 1 1-1M1 19h22",
    devices: "M4 6h11a1 1 0 0 1 1 1v8H3V7a1 1 0 0 1 1-1M1 18h16M19 8h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1",
    lock: "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2M7 11V7a5 5 0 0 1 10 0v4",
    unlock: "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2M7 11V7a5 5 0 0 1 9.9-1",
    logout: "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9",
    plus: "M12 5v14M5 12h14",
    search: "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16M21 21l-4.35-4.35",
    bell: "M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9M13.73 21a2 2 0 0 1-3.46 0",
    list: "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01",
    server: "M4 3h16a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1M4 13h16a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1M7 7h.01M7 17h.01",
    refresh: "M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15",
    check: "M20 6 9 17l-5-5",
    x: "M18 6 6 18M6 6l12 12",
    chevRight: "m9 18 6-6-6-6",
    chevLeft: "m15 18-6-6 6-6",
    chevDown: "m6 9 6 6 6-6",
    menu: "M3 12h18M3 6h18M3 18h18",
    sun: "M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42",
    moon: "M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79",
    fingerprint: "M2 12C2 6.5 6.5 2 12 2a10 10 0 0 1 8 4M5 19.5C5.5 18 6 15 6 12c0-.7.12-1.37.34-2M17.29 21.02c.12-.6.43-2.3.5-3.02M12 10a2 2 0 0 0-2 2c0 1.02-.1 2.51-.26 4M8.65 22c.21-.66.45-1.32.57-2M14 13.12c0 2.38 0 6.38-1 8.88M2 16h.01M21.8 16c.2-2 .131-5.354 0-6M9 6.8a6 6 0 0 1 9 5.2c0 .47 0 1.17-.02 2",
    copy: "M9 9h11a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2zM5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1",
    trash: "M3 6h18M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M10 11v6M14 11v6M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2",
    edit: "M12 20h9M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4z",
    eye: "M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6",
    alert: "M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0M12 9v4M12 17h.01",
    info: "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20M12 16v-4M12 8h.01",
    clock: "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20M12 6v6l4 2",
    activity: "M22 12h-4l-3 9L9 3l-3 9H2",
    cpu: "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2M9 9h6v6H9zM9 1v3M15 1v3M9 20v3M15 20v3M20 9h3M20 14h3M1 9h3M1 14h3",
    memory: "M6 19v-3M10 19v-3M14 19v-3M18 19v-3M8 11V9M16 11V9M12 11V9M2 15h20M2 7a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v1.1a2 2 0 0 0 0 3.837V17a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-5.1a2 2 0 0 0 0-3.837z",
    link: "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
    external: "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14 21 3",
    send: "M22 2 11 13M22 2l-7 20-4-9-9-4z",
    clip: "m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48",
    download: "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3",
    crown: "m2 7 5 5 5-8 5 8 5-5-2 12H4zM4 19h16",
    terminal: "M4 17l6-6-6-6M12 19h8",
    sparkles: "M12 3l1.9 5.8L20 11l-6.1 2.2L12 19l-1.9-5.8L4 11l6.1-2.2zM5 3v4M3 5h4M19 17v4M17 19h4",
    map: "M1 6v16l7-4 8 4 7-4V2l-7 4-8-4zM8 2v16M16 6v16",
    filter: "M22 3H2l8 9.46V19l4 2v-8.54z",
    flag: "M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1zM4 22v-7",
    wifi: "M5 12.55a11 11 0 0 1 14.08 0M1.42 9a16 16 0 0 1 21.16 0M8.53 16.11a6 6 0 0 1 6.95 0M12 20h.01",
    qr: "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h3v3h-3zM20 14h1v1h-1zM14 20h1v1h-1zM17 17h4v4h-4z",
    image: "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2M8.5 10a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3M21 15l-5-5L5 21",
    mic: "M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3M19 10v2a7 7 0 0 1-14 0v-2M12 19v4M8 23h8",
    star: "m12 2 3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z",
    dots: "M12 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2M19 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2M5 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2",
    box: "M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16M3.27 6.96 12 12.01l8.73-5.05M12 22.08V12"
  };
  function icon(name, cls) {
    var svg = h("svg", { class: "i" + (cls ? " " + cls : ""), viewBox: "0 0 24 24", "aria-hidden": "true" });
    svg.appendChild(h("path", { d: P[name] || P.info }));
    return svg;
  }

  // ------------------------------------------------------------ Formatting
  var nf = new Intl.NumberFormat("de-DE");
  function num(v) { return nf.format(Number(v) || 0); }
  function bytes(v) { v = Number(v) || 0; var u = ["B", "KB", "MB", "GB", "TB"], i = 0; while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; } return (v >= 100 || i === 0 ? Math.round(v) : v.toFixed(1)).toString().replace(".", ",") + " " + u[i]; }
  function dur(ms) {
    ms = Math.max(0, Number(ms) || 0); var s = Math.floor(ms / 1000), d = Math.floor(s / 86400), hh = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60);
    if (d) return d + " T " + hh + " Std"; if (hh) return hh + " Std " + m + " Min"; if (m) return m + " Min"; return s + " Sek";
  }
  function date(ms) { if (!ms) return "–"; return new Date(Number(ms)).toLocaleString("de-DE", { day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit" }); }
  function ago(ms) {
    if (!ms) return "nie"; var d = Date.now() - Number(ms); if (d < 0) d = 0;
    if (d < 45e3) return "gerade eben"; if (d < 3600e3) return "vor " + Math.round(d / 60e3) + " Min"; if (d < 86400e3) return "vor " + Math.round(d / 3600e3) + " Std";
    if (d < 7 * 86400e3) return "vor " + Math.round(d / 86400e3) + " T"; return date(ms);
  }
  function initials(name) { name = String(name || "?").replace(/[^A-Za-z0-9ÄÖÜäöü]/g, " ").trim(); var p = name.split(/\s+/); return ((p[0] || "?")[0] + (p[1] ? p[1][0] : (p[0] || "")[1] || "")).toUpperCase(); }
  function hue(text) { var x = 0; text = String(text || ""); for (var i = 0; i < text.length; i++) x = (x * 31 + text.charCodeAt(i)) >>> 0; return x % 360; }
  function avatar(name, cls) {
    var a = h("div", { class: "avatar" + (cls ? " " + cls : ""), text: initials(name), "aria-hidden": "true" });
    var hh = hue(name); a.style.background = "linear-gradient(135deg, hsl(" + hh + " 80% 52%), hsl(" + ((hh + 50) % 360) + " 75% 45%))";
    return a;
  }
  /** Minimal, safe Discord-style markdown: **bold**, `code`, line breaks. */
  function prose(text, cls) {
    var el = h("div", { class: "prose" + (cls ? " " + cls : "") });
    String(text || "").split(/(\*\*[^*]+\*\*|`[^`]+`)/g).forEach(function (part) {
      if (/^\*\*[^*]+\*\*$/.test(part)) el.appendChild(h("b", { text: part.slice(2, -2) }));
      else if (/^`[^`]+`$/.test(part)) el.appendChild(h("code", { text: part.slice(1, -1) }));
      else if (part) el.appendChild(document.createTextNode(part.replace(/§[0-9a-fk-or]/gi, "")));
    });
    return el;
  }

  // ------------------------------------------------------------ API
  function ApiError(status, message, data) { this.status = status; this.message = message; this.data = data; }
  ApiError.prototype = Object.create(Error.prototype);
  async function api(path, options) {
    options = options || {};
    var headers = { "X-Simplex-Web": "1", "Accept": "application/json" }, body;
    if (options.body !== undefined && !(options.body instanceof Blob)) { headers["Content-Type"] = "application/json"; body = JSON.stringify(options.body); }
    else if (options.body instanceof Blob) { headers["Content-Type"] = options.type || options.body.type || "application/octet-stream"; body = options.body; }
    var url = "/v1/" + path.replace(/^\//, "");
    if (options.query) {
      var q = Object.keys(options.query).filter(function (k) { return options.query[k] !== undefined && options.query[k] !== null && options.query[k] !== ""; })
        .map(function (k) { return encodeURIComponent(k) + "=" + encodeURIComponent(options.query[k]); }).join("&");
      if (q) url += "?" + q;
    }
    var res;
    try { res = await fetch(url, { method: options.method || (body !== undefined ? "POST" : "GET"), headers: headers, body: body, credentials: "same-origin", cache: "no-store", redirect: "error" }); }
    catch (e) { throw new ApiError(0, "Server nicht erreichbar. Verbindung prüfen."); }
    if (options.raw) { if (!res.ok) throw new ApiError(res.status, "Datei konnte nicht geladen werden (" + res.status + ")."); return res; }
    var data = null; try { data = await res.json(); } catch (e) { data = null; }
    if (!res.ok) {
      var message = data && data.message ? data.message : "Fehler " + res.status;
      if (res.status === 401 && !options.quiet && S.onUnauthorized) S.onUnauthorized();
      if (res.status === 423 && S.onLocked) S.onLocked(message);
      throw new ApiError(res.status, message, data);
    }
    return data;
  }
  async function blobUrl(path, query) { var res = await api(path, { raw: true, query: query }); return URL.createObjectURL(await res.blob()); }

  // ------------------------------------------------------------ Permissions
  function can(perm) {
    var hl = S.health; if (!hl) return false;
    if (!hl.limited) return true;
    var p = hl.permissions || []; return p.indexOf("*") >= 0 || p.indexOf(perm) >= 0;
  }
  function has(feature) { return !!(S.health && (S.health.features || []).indexOf(feature) >= 0); }
  function full() { return !!(S.health && S.health.scope === "full"); }

  // ------------------------------------------------------------ Components
  function btn(label, opts) {
    opts = opts || {};
    var b = h("button", { class: "btn " + (opts.kind || "") + (opts.size ? " " + opts.size : "") + (opts.block ? " block" : ""), type: opts.submit ? "submit" : "button", disabled: opts.disabled, title: opts.title, "aria-label": opts.aria || (label ? null : opts.title) },
      opts.icon ? icon(opts.icon) : null, label ? h("span", { text: label }) : null);
    if (opts.onClick) b.addEventListener("click", async function (e) {
      if (b.disabled) return; e.preventDefault();
      var prev = Array.prototype.slice.call(b.childNodes); b.disabled = true; clear(b); b.appendChild(h("span", { class: "spin" })); if (label) b.appendChild(h("span", { text: label }));
      try { await opts.onClick(e); } catch (err) { toast(err.message || String(err), "err"); }
      finally { if (b.isConnected) { clear(b); prev.forEach(function (n) { b.appendChild(n); }); b.disabled = !!opts.disabled; } }
    });
    return b;
  }
  function badge(text, kind, ic) { return h("span", { class: "badge " + (kind || "") }, ic ? icon(ic) : null, text); }
  function card(title, opts) {
    opts = opts || {};
    var c = h("section", { class: "card" + (opts.cls ? " " + opts.cls : "") });
    if (title || opts.actions) c.appendChild(h("div", { class: "card-head" }, opts.icon ? icon(opts.icon) : null, h("div", { class: "grow" }, title ? h("h2", { text: title }) : null, opts.sub ? h("div", { class: "sub", text: opts.sub }) : null), opts.actions ? h("div", { class: "actions" }, opts.actions) : null));
    for (var i = 1; i < arguments.length; i++) if (i > 1) add(c, arguments[i]);
    return c;
  }
  function lockNote(text) { return h("div", { class: "lock-note" }, icon("lock"), h("span", { text: text })); }
  function lockedCard(title, perm) {
    return h("section", { class: "card locked", title: "Keine Berechtigung: " + perm }, h("div", { class: "card-head" }, icon("lock"), h("h2", { text: title })),
      h("p", { class: "muted small", style: { margin: 0 }, text: "Für diesen Bereich fehlt deinem Benutzer die Freigabe." }), lockNote(perm === "Vollzugriff" ? "Benötigt Vollzugriff" : "Benötigt " + perm));
  }
  function empty(ic, title, text, action) {
    return h("div", { class: "empty" }, h("div", { class: "big" }, icon(ic)), h("h3", { text: title }), text ? h("p", { text: text }) : null, action || null);
  }
  function notice(text, kind, ic) { return h("div", { class: "notice " + (kind || "") }, icon(ic || (kind === "warn" ? "alert" : kind === "danger" ? "alert" : kind === "ok" ? "check" : "info")), h("div", {}, text)); }
  function skeleton(lines) { var w = h("div", { class: "stack" }); for (var i = 0; i < (lines || 4); i++) w.appendChild(h("div", { class: "skeleton", style: { height: i === 0 ? "90px" : "56px" } })); return w; }
  function lockedPage(title, perm, text) {
    return h("div", { class: "locked-page" }, card(null, {}, empty("lock", title || "Keine Berechtigung", (text || "Dein Benutzer hat für diesen Bereich keine Freigabe.") + (perm ? " Benötigt: " + perm : ""), h("a", { class: "btn", href: "#/dashboard" }, icon("home"), "Zur Übersicht"))));
  }
  function errorView(err, retry) {
    if (err && err.status === 403) return lockedPage("Keine Berechtigung", null, err.message);
    return card(null, {}, empty("alert", "Konnte nicht geladen werden", err && err.message ? err.message : String(err), retry ? btn("Erneut versuchen", { icon: "refresh", onClick: retry }) : null));
  }
  function field(label, input, hint) { return h("label", { class: "field" }, h("span", { text: label }), input, hint ? h("small", { text: hint }) : null); }
  function input(props) { return h("input", Object.assign({ class: "input", autocomplete: "off", spellcheck: "false" }, props || {})); }
  function searchBox(placeholder, onInput, value) {
    var i = input({ type: "search", placeholder: placeholder, value: value || "" });
    var t; i.addEventListener("input", function () { clearTimeout(t); t = setTimeout(function () { onInput(i.value.trim()); }, 250); });
    return h("div", { class: "search grow" }, icon("search"), i);
  }
  function segmented(options, value, onChange) {
    var wrap = h("div", { class: "segmented", role: "tablist" });
    options.forEach(function (o) {
      var b = h("button", { class: o[0] === value ? "on" : "", type: "button", text: o[1], role: "tab" });
      b.addEventListener("click", function () { Array.prototype.forEach.call(wrap.children, function (c) { c.classList.remove("on"); }); b.classList.add("on"); onChange(o[0]); });
      wrap.appendChild(b);
    });
    return wrap;
  }
  function sw(checked, onChange, disabled) {
    var i = h("input", { type: "checkbox", checked: checked, disabled: disabled, role: "switch" });
    i.addEventListener("change", function () { onChange(i.checked, i); });
    return h("span", { class: "switch" }, i, h("i"));
  }
  function kv(pairs) { var d = h("dl", { class: "kv" }); pairs.forEach(function (p) { if (p && p[1] !== undefined && p[1] !== null && p[1] !== "") { d.appendChild(h("dt", { text: p[0] })); d.appendChild(h("dd", {}, p[1])); } }); return d; }
  function bar(value, max, warnAt) { var pct = max > 0 ? Math.min(100, Math.round(value / max * 100)) : 0; return h("div", { class: "bar" + (pct >= (warnAt || 85) ? " danger" : pct >= (warnAt || 85) - 15 ? " warn" : "") }, h("i", { style: { width: pct + "%" } })); }
  function sparkline(values) {
    var w = 200, hh = 36, svg = h("svg", { class: "spark", viewBox: "0 0 " + w + " " + hh, preserveAspectRatio: "none", "aria-hidden": "true" });
    if (!values || values.length < 2) return svg;
    var min = Math.min.apply(null, values), max = Math.max.apply(null, values); if (max === min) { max += 1; min -= 1; }
    var pts = values.map(function (v, i) { return [i / (values.length - 1) * w, hh - 3 - (v - min) / (max - min) * (hh - 6)]; });
    var line = "M" + pts.map(function (p) { return p[0].toFixed(1) + " " + p[1].toFixed(1); }).join(" L");
    var gid = "sp" + Math.random().toString(36).slice(2);
    var defs = h("defs"), grad = h("linearGradient", { id: gid, x1: "0", y1: "0", x2: "0", y2: "1" });
    grad.appendChild(h("stop", { offset: "0", "stop-color": "currentColor", "stop-opacity": "0.25" })); grad.appendChild(h("stop", { offset: "1", "stop-color": "currentColor", "stop-opacity": "0" }));
    defs.appendChild(grad); svg.appendChild(defs); svg.style.color = "var(--accent)";
    svg.appendChild(h("path", { d: line + " L" + w + " " + hh + " L0 " + hh + " Z", fill: "url(#" + gid + ")" }));
    svg.appendChild(h("path", { class: "l", d: line }));
    return svg;
  }

  // ------------------------------------------------------------ Overlays
  function toast(text, kind, detail) {
    var host = document.querySelector(".toasts") || document.body.appendChild(h("div", { class: "toasts", role: "status", "aria-live": "polite" }));
    var t = h("div", { class: "toast " + (kind || "ok") }, icon(kind === "err" ? "alert" : "check"), h("div", {}, h("b", { text: text }), detail ? h("small", { text: detail }) : null));
    host.appendChild(t); setTimeout(function () { t.style.opacity = "0"; t.style.transition = "opacity .3s"; setTimeout(function () { t.remove(); }, 300); }, kind === "err" ? 6500 : 3800);
  }
  function modal(title, body, actions, opts) {
    opts = opts || {};
    var bg = h("div", { class: "modal-bg", role: "dialog", "aria-modal": "true", "aria-label": title });
    var close = function () { bg.remove(); document.removeEventListener("keydown", esc); if (opts.onClose) opts.onClose(); };
    var esc = function (e) { if (e.key === "Escape" && !opts.locked) close(); };
    var m = h("div", { class: "modal" + (opts.wide ? " wide" : "") },
      h("header", {}, opts.icon ? icon(opts.icon) : null, h("h2", { class: "grow", text: title }), opts.locked ? null : btn("", { kind: "ghost", size: "sm icon", icon: "x", title: "Schließen", onClick: close })),
      h("div", { class: "body" }, body), actions && actions.length ? h("footer", {}, actions) : null);
    bg.appendChild(m); bg.addEventListener("mousedown", function (e) { if (e.target === bg && !opts.locked) close(); });
    document.addEventListener("keydown", esc); document.body.appendChild(bg);
    var f = m.querySelector("input, textarea, select"); if (f && !opts.noFocus) setTimeout(function () { f.focus(); }, 30);
    return { close: close, el: m };
  }
  function confirmDialog(title, text, okLabel, danger) {
    return new Promise(function (resolve) {
      var done = false, dlg;
      var finish = function (v) { if (done) return; done = true; dlg.close(); resolve(v); };
      dlg = modal(title, prose(text), [btn("Abbrechen", { onClick: function () { finish(false); } }), btn(okLabel || "Bestätigen", { kind: danger ? "danger solid" : "primary", onClick: function () { finish(true); } })], { onClose: function () { if (!done) { done = true; resolve(false); } } });
    });
  }
  function prompt(title, label, value, okLabel, opts) {
    return new Promise(function (resolve) {
      var done = false, i = input(Object.assign({ value: value || "" }, opts || {})), dlg;
      var ok = function () { if (done) return; done = true; dlg.close(); resolve(i.value); };
      i.addEventListener("keydown", function (e) { if (e.key === "Enter") { e.preventDefault(); ok(); } });
      dlg = modal(title, field(label, i), [btn("Abbrechen", { onClick: function () { done = true; dlg.close(); resolve(null); } }), btn(okLabel || "Speichern", { kind: "primary", onClick: ok })], { onClose: function () { if (!done) { done = true; resolve(null); } } });
    });
  }
  function lightbox(src) { var l = h("div", { class: "lightbox", onClick: function () { l.remove(); } }, h("img", { src: src, alt: "" })); document.body.appendChild(l); }
  async function copy(text, label) { try { await navigator.clipboard.writeText(text); toast((label || "Text") + " kopiert"); } catch (e) { toast("Kopieren nicht möglich", "err"); } }

  /** Two-step confirmation used for every Suite mutation: /v1/prepare → summary → /v1/execute. */
  async function runAction(path, values, opts) {
    opts = opts || {};
    var plan = await api("prepare", { body: { path: path, values: values } });
    if (!opts.skipConfirm) {
      var ok = await confirmDialog(opts.title || "Änderung bestätigen", plan.summary || "Aktion ausführen?", opts.okLabel || "Bestätigen & ausführen", opts.danger);
      if (!ok) return null;
    }
    var result = await api("execute", { body: { ticket: plan.ticket } });
    if (!opts.silent) toast(result && result.title ? result.title : "Ausgeführt", result && result.ok === false ? "err" : "ok", result && result.description ? String(result.description).replace(/\*\*/g, "").slice(0, 400) : null);
    return result;
  }

  Object.assign(S, { h: h, add: add, clear: clear, mount: mount, icon: icon, num: num, bytes: bytes, dur: dur, date: date, ago: ago, avatar: avatar, prose: prose,
    api: api, blobUrl: blobUrl, ApiError: ApiError, can: can, has: has, full: full, btn: btn, badge: badge, card: card, lockedCard: lockedCard, empty: empty, notice: notice,
    skeleton: skeleton, lockNote: lockNote, lockedPage: lockedPage, errorView: errorView, field: field, input: input, searchBox: searchBox, segmented: segmented, sw: sw, kv: kv, bar: bar,
    sparkline: sparkline, toast: toast, modal: modal, confirm: confirmDialog, prompt: prompt, lightbox: lightbox, copy: copy, runAction: runAction, initials: initials });
})();
