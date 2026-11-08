(function () {
  "use strict";
  var S = window.Simplex, h = S.h;
  S.pages = S.pages || {};
  S.pages["ai-workspace"] = async function (param, query, alive) {
    var state = await S.api("ai-workspace");
    if (!alive()) return;
    S.title("Simplex KI", "Serverdaten suchen und Aktionsentwürfe prüfen");
    if (!state.allowed) {
      S.mount(S.notice("Dieses Konto benötigt das Recht KI-Adminmodus.", "warn", "lock"));
      return;
    }
    var answer = h("div", { class: "stack" }), queue = h("div", { class: "stack" }), result = h("div", { class: "stack" });
    var question = h("textarea", { class: "input", rows: 4, maxlength: 2200, placeholder: "Was soll ich am Server prüfen?" });
    var send = S.btn("KI fragen", { kind: "primary", icon: "sparkles", disabled: !state.available, onClick: function () { return ask(question.value); } });
    var working = false;
    async function ask(text) {
      if (!text.trim() || working || !state.available) return;
      working = true; send.disabled = true;
      S.clear(answer); answer.appendChild(S.notice("Passende Quellen werden gesucht …"));
      try {
        var response = await S.api("ai-workspace/ask", { body: { question: text }, timeout: 110000 });
        if (!alive()) return;
        question.value = "";
        S.clear(answer);
        answer.appendChild(S.card("Antwort", { icon: "sparkles" }, h("div", { class: "stack" },
          h("pre", { class: "small", text: response.answer }),
          (response.sources || []).length ? h("small", { class: "muted", text: "Durchsuchte Quellen · " + response.sources.join(" · ") }) : null,
          h("div", { class: "row wrap" }, (response.players || []).map(function (player) {
            return h("a", { class: "btn sm", href: "#/player/" + encodeURIComponent(player.id), text: player.name });
          })),
          h("div", { class: "row wrap" }, (response.suggestions || []).filter(Boolean).map(function (suggestion) {
            return S.btn(suggestion, { size: "sm", onClick: function () { return ask(suggestion); } });
          })))));
        await refresh();
      } catch (e) {
        if (alive()) { S.clear(answer); answer.appendChild(S.notice(e.message, "warn", "alert")); }
      } finally { working = false; if (alive()) send.disabled = !state.available; }
    }
    async function refresh() {
      var next = await S.api("ai-workspace");
      if (!alive()) return;
      state = next; drawQueue();
    }
    function drawQueue() {
      S.clear(queue);
      (state.pending || []).forEach(function (plan) {
        var selected = new Set(), summary = h("div");
        var approve = S.btn("Auswahl prüfen und freigeben", { kind: "danger", disabled: true, onClick: async function () {
          var chosen = plan.actions.filter(function (action) { return selected.has(action.id); });
          if (!chosen.length || !(await S.confirm("KI-Aktionen ausführen?", chosen.map(function (action) { return action.summary; }).join("\n"), "Verbindlich freigeben", true))) return;
          var out = await S.api("ai-workspace/approve", { body: { id: plan.id, actions: chosen.map(function (action) { return action.id; }) } });
          if (!alive()) return;
          S.clear(result);
          result.appendChild(S.card("Ausführung", { icon: "check" }, (out.results || []).map(function (row) {
            return S.notice(row.summary + " · " + row.message, row.ok ? "ok" : "warn");
          })));
          await refresh();
        } });
        var items = plan.actions.map(function (action) {
          if (!state.owner) return h("p", { text: action.summary });
          return h("label", { class: "check" }, h("input", { type: "checkbox", onChange: function (event) {
            if (event.target.checked) selected.add(action.id); else selected.delete(action.id);
            approve.disabled = selected.size === 0;
          } }), h("span", { text: action.summary }));
        });
        queue.appendChild(S.card("KI-Aktionsentwurf", { icon: "list", sub: "Von " + plan.requester },
          h("div", { class: "stack" }, items, summary, h("div", { class: "row wrap" },
            state.owner ? approve : null,
            S.btn("KI-Zusammenfassung", { size: "sm", disabled: !state.available, onClick: async function () {
              var r = await S.api("ai-workspace/summarize", { body: { id: plan.id }, timeout: 90000 });
              summary.textContent = r.summary;
            } }),
            S.btn("Verwerfen", { size: "sm", onClick: async function () {
              await S.api("ai-workspace/cancel", { body: { id: plan.id } }); await refresh();
            } })))));
      });
    }
    function settings() {
      if (!state.owner) return null;
      var enabled = h("input", { type: "checkbox", checked: state.enabled });
      var limit = h("input", { class: "input", type: "number", min: 0, max: 500, value: state.dailyLimit });
      var limits = h("textarea", { class: "input", rows: 3, value: (state.limits || []).join("\n"), placeholder: "app:admin=10" });
      var delegates = h("textarea", { class: "input", rows: 3, value: (state.discordDelegates || []).join("\n"), placeholder: "Discord-ID=App-Benutzer" });
      function lines(input) { return input.value.split(/\r?\n/).map(function (v) { return v.trim(); }).filter(Boolean); }
      return S.card("KI-Zugang und Limits", { icon: "settings", sub: "Der Hauptadmin hat kein Tageslimit." }, h("div", { class: "stack" },
        h("label", { class: "check" }, enabled, "KI-Adminmodus"),
        S.field("Standardlimit je Tag", limit), S.field("Kontolimits", limits), S.field("Discord mit App-Konto verknüpfen", delegates),
        S.btn("Speichern", { kind: "primary", onClick: async function () {
          await S.api("ai-workspace/settings", { body: { enabled: enabled.checked, dailyLimit: Number(limit.value), limits: lines(limits), discordDelegates: lines(delegates) } });
          S.reload();
        } })));
    }
    S.mount(h("div", { class: "stack" },
      S.card("KI fragen", { icon: "sparkles" }, h("div", { class: "stack" }, state.available ? null : S.notice("KI derzeit nicht verfügbar. Offene Entwürfe bleiben prüfbar."), question, send)),
      answer, result, queue, S.btn("Offene Entwürfe aktualisieren", { size: "sm", icon: "refresh", onClick: refresh }), settings()));
    drawQueue();
  };
}());
