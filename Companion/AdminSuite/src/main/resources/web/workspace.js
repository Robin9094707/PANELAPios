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
    var threaded = S.has("admin-assistant-chat-v1"), fromStaff = query && query.get("from") === "staff";
    var chat = null, chats = [], pending = "", currentJob = "", draft = null, draftUrl = "", recorded = false;
    var stream = null, recorder = null, recorderTimer = null;
    var timeline = h("div", { class: "msgs", role: "log", "aria-label": "KI-Chatverlauf" });
    var chatList = h("select", { class: "input", "aria-label": "KI-Chat auswählen" });
    var chatControls = h("div", { class: "assistant-chat-controls row wrap" });
    var audioControls = h("div", { class: "assistant-audio row wrap" });
    var owner = status.owner === true, result = null, busy = false, outcomes = [], selected = new Set(), summary = "";
    var answer = h("div", { class: "stack", "aria-live": "polite" });
    var inbox = h("div", { class: "stack" }), settings = h("div", { class: "stack" });
    var input = h("textarea", { class: "input", maxlength: 2000, rows: threaded ? 1 : 4, placeholder: threaded ? "Nachricht an die KI …" : "Was soll die KI für diesen Server prüfen?", "aria-label": "Deine Frage" });
    var send = S.btn(threaded ? "" : "Fragen", { kind: "primary", icon: "send", title: "Nachricht senden", disabled: !status.available, onClick: function () {
      var question = input.value.trim(); if (!question || question.length > 2000 || busy || recorder) return;
      return run(async function () {
        if (threaded && !chat) await createChat();
        var job = await S.api("assistant/ask", { body: { question: question, chat: chat ? chat.id : "" } });
        if (!alive()) return;
        if (input.value.trim() === question) input.value = "";
        discardAudio();
        pending = question; renderTimeline();
        try { await poll(job); }
        finally { pending = ""; renderTimeline(); }
      });
    } });
    input.addEventListener("keydown", function (event) {
      if (threaded && event.key === "Enter" && !event.shiftKey && !event.isComposing) { event.preventDefault(); send.click(); }
    });
    input.addEventListener("input", function () {
      if (!threaded) return;
      input.style.height = "auto"; input.style.height = Math.min(144, input.scrollHeight + 2) + "px";
    });
    var usage = h("small", { class: "muted" });
    function updateUsage() { usage.textContent = owner ? "Hauptadmin · ohne Tageslimit" : "Heute genutzt: " + status.used + " / " + status.limit; }
    updateUsage();
    async function run(work) {
      if (busy || !alive()) return;
      busy = true; send.disabled = true;
      try { await work(); } catch (e) { if (alive()) S.toast(e.message, "err"); }
      finally { if (alive()) { busy = false; send.disabled = !status.available; if (threaded) drawAudio(); } }
    }
    function renderTimeline() {
      if (!threaded || !alive()) return;
      S.clear(timeline);
      var rows = chat && chat.items || [];
      function bubble(text, mine, at, job) {
        var stamp = at ? new Date(Number(at)).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : "";
        var extra = !mine && text && "speechSynthesis" in window ? S.btn("Vorlesen", { size: "sm", onClick: function () {
          if (window.speechSynthesis.speaking) { window.speechSynthesis.cancel(); return; }
          var utterance = new SpeechSynthesisUtterance(text);
          window.speechSynthesis.speak(utterance);
        } }) : null;
        var live = !mine && job && job !== currentJob && Date.now() - Number(at) < 20 * 60000;
        var details = live ? S.btn("Antwortdetails", { size: "sm", onClick: function () {
          return run(async function () {
            result = await S.api("assistant/job", { query: { id: job } }); outcomes = result.results || []; summary = "";
            draw(); answer.scrollIntoView({ block: "nearest" });
          });
        } }) : null;
        return h("div", { class: "msg" + (mine ? " mine" : "") }, h("div", { class: "bubble" },
          h("div", { class: "by" }, h("span", { text: mine ? "Du" : "Simplex KI" })),
          h("p", { text: text || "Keine Textantwort" }),
          h("div", { class: "row wrap" }, h("time", { class: "stamp", text: stamp }), extra, details)));
      }
      rows.forEach(function (turn) { timeline.append(bubble(turn.question, true, turn.at), bubble(turn.answer, false, turn.at, turn.job)); });
      if (pending) timeline.append(bubble(pending, true));
      if (!rows.length && !pending) timeline.append(S.empty("sparkles", "Dein privater KI-Chat", "Stelle eine Frage oder nimm eine Sprachnachricht auf."));
      timeline.scrollTop = timeline.scrollHeight;
    }
    function chatChange(operation, id, title, days) {
      return S.api("assistant/chats", { body: { operation: operation, id: id || "", title: title || "", days: days || 0 } });
    }
    async function loadChats() {
      var data = await S.api("assistant/chats"); if (!alive()) return;
      chats = data.items || [];
      S.clear(chatList); chatList.append(h("option", { value: "", text: "Chat auswählen …" }));
      chats.forEach(function (row) { chatList.append(h("option", { value: row.id, text: row.title })); });
      chatList.value = chat ? chat.id : "";
    }
    async function loadChat(id) {
      var row = await S.api("assistant/chats", { query: { id: id } }); if (!alive()) return;
      chat = row; chatList.value = row.id; renderTimeline();
    }
    function resetChat(keepInput) {
      chat = null; result = null; currentJob = ""; pending = ""; outcomes = []; summary = "";
      if (!keepInput) { input.value = ""; discardAudio(); }
      draw(); renderTimeline();
    }
    async function createChat() {
      var row = await chatChange("create"); if (!alive()) return;
      resetChat(true); await loadChat(row.id); await loadChats();
    }
    chatList.addEventListener("change", function () {
      var id = chatList.value;
      if (busy || recorder) { chatList.value = chat ? chat.id : ""; return; }
      run(async function () { resetChat(false); if (id) await loadChat(id); });
    });
    function disposeRecorder() {
      clearTimeout(recorderTimer); recorderTimer = null;
      if (stream) stream.getTracks().forEach(function (track) { track.stop(); });
      stream = null; recorder = null;
    }
    function discardAudio() {
      if (draftUrl) URL.revokeObjectURL(draftUrl);
      draftUrl = ""; draft = null; recorded = false;
      if (alive()) drawAudio();
    }
    async function record() {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia || !window.MediaRecorder) throw new Error("Mikrofonaufnahme braucht HTTPS und einen Browser mit Audioaufnahme.");
      var format = ["audio/webm;codecs=opus", "audio/mp4", "audio/webm"].find(function (type) { return MediaRecorder.isTypeSupported(type); });
      if (!format) throw new Error("Der Browser unterstützt kein passendes Audioformat.");
      var microphone = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (!alive()) { microphone.getTracks().forEach(function (track) { track.stop(); }); return; }
      stream = microphone;
      try {
        recorder = new MediaRecorder(stream, { mimeType: format, audioBitsPerSecond: 64000 });
        var active = recorder, chunks = [];
        active.ondataavailable = function (event) { if (event.data.size) chunks.push(event.data); };
        active.onerror = function () { disposeRecorder(); if (alive()) { S.toast("Aufnahme fehlgeschlagen.", "err"); drawAudio(); } };
        active.onstop = function () {
          disposeRecorder();
          if (!alive()) return;
          var blob = new Blob(chunks, { type: format.split(";")[0] });
          if (!blob.size || blob.size > 4 * 1024 * 1024) { S.toast("Aufnahme leer oder größer als 4 MiB.", "err"); drawAudio(); return; }
          draft = blob; draftUrl = URL.createObjectURL(blob); drawAudio();
        };
        active.start();
        recorderTimer = setTimeout(function () { if (active.state === "recording") active.stop(); }, 120000);
      } catch (error) { disposeRecorder(); throw error; }
    }
    function drawAudio() {
      S.clear(audioControls);
      if (recorder) {
        audioControls.append(h("span", { class: "muted", text: "Aufnahme läuft · max. 2 Minuten" }), S.btn("Stopp", { onClick: function () {
          if (recorder && recorder.state === "recording") recorder.stop();
        } }));
      } else if (draft) {
        audioControls.append(h("audio", { controls: true, preload: "metadata", src: draftUrl, "aria-label": "Aufnahme anhören" }),
          S.btn(recorded ? "Transkribiert" : "Transkribieren", { disabled: busy || recorded || !status.available, onClick: function () { return run(async function () {
            var reply = await S.api("assistant/transcribe", { body: draft, type: draft.type, timeout: 65000 });
            if (!alive()) return;
            var value = input.value.trim() ? input.value + "\n" + reply.text : reply.text;
            if (value.length > 2000) throw new Error("Kürze zuerst den Entwurf, damit der gesprochene Text hineinpasst.");
            input.value = value; recorded = true; input.focus();
            status = await S.api("assistant/status", { quiet: true }); if (alive()) updateUsage();
          }); } }),
          S.btn("Aufnahme verwerfen", { size: "sm", disabled: busy, onClick: discardAudio }),
          h("small", { class: "muted", text: "Die Aufnahme wird beim Transkribieren an OpenAI übermittelt." }));
      } else {
        audioControls.append(S.btn("Aufnehmen", { size: "sm", icon: "mic", disabled: busy || !status.available, onClick: function () { return run(record); } }));
      }
    }
    async function poll(job) {
      outcomes = []; summary = "";
      S.clear(answer); answer.appendChild(S.notice("Antwort und Datenquellen werden geprüft …"));
      for (var i = 0; i < 64 && alive(); i++) {
        var row = await S.api("assistant/job", { query: { id: job.id }, quiet: true }); if (!alive()) return;
        if (row.status !== "running") {
          result = row; currentJob = row.id;
          if (chat) { pending = ""; await loadChat(chat.id); await loadChats(); }
          draw(); status = await S.api("assistant/status", { quiet: true }); if (alive()) updateUsage();
          if (owner) await loadInbox();
          return;
        }
        await new Promise(function (resolve) { setTimeout(resolve, 2000); });
      }
      if (alive()) throw new Error("Der Server braucht länger. Prüfe später die offenen Freigaben.");
    }
    async function change(operation) {
      if (!result) return;
      var id = result.id, revision = result.revision, actions = Array.from(selected);
      if (operation === "approve" && !actions.length) return;
      if (operation === "approve" && !(await S.confirm("Diesen Aktionsplan verbindlich ausführen?", "Der Server führt die vollständig angezeigten Schritte nacheinander aus.", "Plan bestätigen", true))) return;
      if (!alive() || id !== result.id) return;
      return run(async function () {
        var response = await S.api("assistant/" + operation, { body: { id: id, revision: revision, actions: actions, confirmed: operation === "approve" } });
        outcomes = response.results || []; summary = "";
        result = await S.api("assistant/job", { query: { id: id } }); draw();
        if (owner) await loadInbox();
      });
    }
    function draw() {
      if (!alive()) return;
      S.clear(answer); selected.clear();
      var r = result; if (!r) return;
      var detail = h("div", { class: "stack" });
      var alreadyShown = chat && (chat.items || []).some(function (turn) { return turn.job === r.id; });
      if (r.answer && !alreadyShown) detail.appendChild(h("div", { style: { whiteSpace: "pre-wrap" }, text: r.answer }));
      if (r.sources && r.sources.length) detail.appendChild(h("small", { class: "muted", text: "Datenquellen · " + r.sources.join(", ") }));
      if (r.summary || summary) detail.appendChild(h("details", { open: !!summary }, h("summary", { text: "KI-Kurzfassung" }), h("p", { text: summary || r.summary })));
      if (r.choices && r.choices.length && status.available && r.status === "ready") detail.appendChild(h("div", { class: "row wrap" }, r.choices.map(function (text, index) {
        return S.btn(text, { onClick: function () { return run(async function () { await poll(await S.api("assistant/choose", { body: { id: r.id, index: index } })); }); } });
      })));
      if (r.players) detail.appendChild(h("div", { class: "row wrap" }, r.players.map(function (p) { return h("a", { class: "btn sm", href: "#/player/" + encodeURIComponent(p.id), text: p.name }); })));
      (r.rejected || []).forEach(function (message) { detail.appendChild(S.notice(message, "warn")); });
      if (detail.childNodes.length) answer.appendChild(S.card(alreadyShown ? "Antwortdetails" : "Antwort", { icon: "sparkles" }, detail));
      if ((r.actions || []).length) {
        var approve = S.btn("Auswahl bestätigen", { kind: "danger", disabled: true, onClick: function () { return change("approve"); } });
        var checks = [];
        var entries = r.actions.map(function (action, index) {
          var title = h("strong", { text: (index + 1) + ". " + action.title });
          if (owner && r.status === "ready") {
            var check = h("input", { type: "checkbox" });
            checks.push(check);
            check.addEventListener("change", function () {
              if (check.checked) selected.add(action.id); else selected.delete(action.id);
              approve.disabled = selected.size === 0;
            });
            title = h("label", { class: "check" }, check, title);
          }
          return h("div", { class: "item" }, h("div", { class: "meta" }, title, h("p", { style: { whiteSpace: "pre-wrap" }, text: action.preview })));
        });
        var all = S.btn("Alle auswählen", { onClick: function () {
          checks.forEach(function (box) { box.checked = true; });
          r.actions.forEach(function (action) { selected.add(action.id); });
          approve.disabled = false;
        } });
        answer.appendChild(S.card("Vorgeschlagene Aktionen", { icon: "list", sub: r.author || "" }, h("div", { class: "stack" }, entries,
          r.status === "ready" ? h("div", { class: "row wrap" }, owner ? [all, approve] : h("span", { class: "muted", text: "Wartet auf Freigabe durch den Hauptadmin." }),
            S.btn("Mit KI neu zusammenfassen", { disabled: !status.available, onClick: function () {
              return run(async function () {
                var job = await S.api("assistant/summarize", { body: { id: r.id } });
                for (var i = 0; i < 40 && alive(); i++) {
                  var row = await S.api("assistant/job", { query: { id: job.id }, quiet: true });
                  if (row.status !== "running") { summary = row.answer || ""; draw(); return; }
                  await new Promise(function (resolve) { setTimeout(resolve, 2000); });
                }
              });
            } }), S.btn("Plan verwerfen", { onClick: function () { return change("discard"); } })) : h("span", { class: "muted", text: r.status === "discarded" ? "Plan verworfen." : "Plan verarbeitet." }))));

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
          return run(async function () { outcomes = []; summary = ""; result = await S.api("assistant/job", { query: { id: plan.id } }); draw(); });
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
    var examples = h("div", { class: "row wrap" }, ["Gab es letzte Nacht Vorfälle im Chat?", "Wann waren Admins zuletzt im Vanish?", "Welche Tickets sind offen?"].map(function (example) {
      return S.btn(example, { size: "sm", onClick: function () { input.value = example; input.focus(); } });
    }));
    S.title("KI-Assistent", threaded ? "Privater Chat für diesen Server" : "Gezielt nachsehen und Schreibaktionen zur Freigabe vorlegen",
      fromStaff ? h("a", { class: "btn ghost", href: "#/staff-chat", text: "Zum Staff-Chat" }) : null);
    var availability = status.available ? null : S.notice("KI ist deaktiviert oder der OpenAI-Schlüssel fehlt.", "warn");
    var chatUI;
    if (threaded) {
      chatControls.append(chatList, S.btn("Neuer Chat", { icon: "plus", onClick: function () {
        if (recorder) return;
        return run(async function () { resetChat(false); await createChat(); });
      } }), S.btn("Chat einstellen", { icon: "settings", onClick: function () {
        if (!chat || recorder) return;
        return run(async function () {
          var name = await S.prompt("Chatname", "Name dieses privaten Chats", chat.title, "Weiter");
          if (name === null || !alive()) return;
          var days = await S.prompt("Automatisch löschen", "Tage seit letzter Aktivität · 0 (aus), 1, 7 oder 30", String(chat.days), "Speichern");
          if (days === null || !alive()) return;
          if (!/^(0|1|7|30)$/.test(days.trim())) throw new Error("Bitte 0, 1, 7 oder 30 eingeben.");
          var row = await chatChange("update", chat.id, name, Number(days));
          if (!alive()) return;
          if (row.expired) resetChat(false); else await loadChat(row.id);
          await loadChats();
        });
      } }), S.btn("Löschen", { icon: "trash", onClick: function () {
        if (!chat || recorder) return;
        return run(async function () {
          if (!(await S.confirm("Privaten Chat löschen?", "Verlauf und offene Vorschläge dieses Chats werden entfernt.", "Löschen", true)) || !alive()) return;
          await chatChange("delete", chat.id); if (!alive()) return;
          resetChat(false); await loadChats(); if (owner) await loadInbox();
        });
      } }));
      chatUI = S.card(null, { cls: "pad-0 chat-card" }, chatControls, availability,
        h("div", { class: "chat assistant-chat" }, timeline, audioControls,
          h("div", { class: "composer" }, input, send)), h("div", { class: "assistant-chat-footer stack" }, usage, examples));
      S.onLeave = function () { disposeRecorder(); discardAudio(); if ("speechSynthesis" in window) window.speechSynthesis.cancel(); };
      drawAudio();
    } else {
      chatUI = S.card("Serverassistent", { icon: "sparkles" }, h("div", { class: "stack" }, availability, input, h("div", { class: "row wrap" }, send, usage), examples));
    }
    S.mount(h("div", { class: "stack" }, chatUI, answer, owner ? S.card("Offene Freigaben", { icon: "list" }, inbox) : null, owner ? S.card("Tageslimits", { icon: "settings" }, settings) : null));
    if (threaded) { await loadChats(); if (alive() && chats.length) await loadChat(chats[0].id); else renderTimeline(); }
    if (owner && alive()) await Promise.all([loadInbox(), loadLimits()]);
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
