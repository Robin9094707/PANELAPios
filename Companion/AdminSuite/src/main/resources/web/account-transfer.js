/* Simplex Panel – preview and confirm a two-way player account transfer. */
(function () {
  "use strict";
  var S = window.Simplex, h = S.h, P = S.pages = S.pages || {};
  var integrations = [
    ["points", "Simplex Points"], ["luckPerms", "LuckPerms-Gruppen und Rechte"],
    ["homes", "Home-Dateien"], ["multiverse", "Multiverse-Inventories"]
  ];

  function pick(title, selected) {
    var field = S.input({ placeholder: "Name oder UUID", "aria-label": "Name oder UUID", autocomplete: "off" });
    var list = h("div", { class: "list", role: "status" });
    var dialog, revision = 0, timer;
    async function search() {
      var previous = ++revision;
      try {
        var result = await S.api("account-transfer/players", { query: { q: field.value.trim() }, quiet: true });
        if (previous !== revision || !list.isConnected) return;
        S.clear(list);
        var players = result.items || [];
        players.forEach(function (player) {
          list.appendChild(h("button", { type: "button", class: "item link", disabled: player.online, onClick: function () {
            dialog.close();
            selected(player);
          } }, S.avatar(player.name, "sm"), h("span", { class: "grow meta" },
            h("b", { text: player.name }), h("small", { text: player.online ? "Online · zuerst ausloggen" : player.id }))));
        });
        if (!players.length) list.appendChild(S.empty("search", "Kein Spieler gefunden"));
      } catch (error) {
        if (previous === revision && list.isConnected) { S.clear(list); list.appendChild(S.notice(error.message, "danger")); }
      }
    }
    field.addEventListener("input", function () {
      revision++;
      clearTimeout(timer);
      timer = setTimeout(search, 250);
    });
    dialog = S.modal(title, h("div", { class: "stack" }, field, list), [], { onClose: function () { clearTimeout(timer); revision++; } });
    search();
  }

  P["account-transfer"] = async function (param, query, alive) {
    var caps = await S.api("account-transfer");
    if (!alive()) return;
    S.title("Account-Transfer");
    var from = null, to = null, preview = null, outcome = null, busy = false, issue = "", expires = 0;
    var options = {};
    var content = h("div", { class: "stack" });
    integrations.forEach(function (option) { options[option[0]] = !!caps[option[0]]; });

    function choose(title, value, set) {
      return S.btn(value ? title + " · " + value.name : title + " wählen", { icon: "user", disabled: busy, onClick: function () {
        pick(title, function (player) {
          if (!alive()) return;
          set(player);
          issue = "";
          render();
        });
      } });
    }

    async function prepare() {
      if (busy || !from || !to || from.id === to.id) return;
      busy = true;
      issue = "";
      render();
      try {
        preview = await S.api("account-transfer/preview", {
          body: { old: from.id, new: to.id, options: options }, timeout: 40000
        });
        expires = Date.now() + preview.expiresIn * 1000;
      } catch (error) { issue = error.message; }
      finally { busy = false; if (alive()) render(); }
    }

    async function execute(input) {
      if (busy || input !== "TAUSCHEN" || expires <= Date.now()) return;
      busy = true;
      issue = "";
      render();
      try {
        outcome = await S.api("account-transfer/execute", {
          body: { ticket: preview.ticket, confirm: input }, timeout: 90000
        });
      } catch (error) { issue = error.message; preview = null; }
      finally { busy = false; if (alive()) render(); }
    }

    function select() {
      return [
        S.card("Welche Accounts möchtest du tauschen?", { icon: "users" }, h("div", { class: "stack" },
          h("p", { class: "muted", text: "Beide Spieler müssen offline sein. Der alte Account erhält das bisherige Inventar des neuen Accounts." }),
          h("div", { class: "row wrap" }, choose("Alter Account", from, function (player) { from = player; }),
            choose("Neuer Account", to, function (player) { to = player; })),
          from && to && from.id === to.id ? S.notice("Wähle zwei verschiedene Accounts.", "warn") : null)),
        S.card("Welche Daten sollen mit?", { icon: "box" }, h("div", { class: "stack" },
          h("b", { text: "Spielstand, Endertruhe und Fortschritt" }),
          integrations.filter(function (item) { return caps[item[0]]; }).map(function (item) {
            return h("label", { class: "check" }, h("input", { type: "checkbox", checked: options[item[0]], disabled: busy,
              onChange: function (event) { options[item[0]] = event.target.checked; } }), item[1]);
          }),
          caps.homesDetected && !caps.homes ? S.notice("Homes benötigen ein bestätigtes Dateiformat und ein gestopptes Homes-Plugin.") : null,
          caps.multiverseDetected && !caps.multiverse ? S.notice("Multiverse läuft separat. Sein Profil kann beim nächsten Login die Paper-Ausrüstung wieder überschreiben.", "warn") : null)),
        S.btn("Vorschau berechnen", { kind: "primary", icon: "eye", disabled: busy || !from || !to || from.id === to.id || !caps.ready,
          onClick: prepare })
      ];
    }

    function review() {
      var field = S.input({ placeholder: "TAUSCHEN", "aria-label": "TAUSCHEN eingeben", disabled: busy, autocomplete: "off" });
      var button = S.btn("Daten jetzt tauschen", { icon: "shieldCheck", kind: "danger solid", disabled: true,
        onClick: function () { return execute(field.value.trim()); } });
      var timer = h("small", { class: "muted" });
      function tick() {
        var seconds = Math.max(0, Math.floor((expires - Date.now()) / 1000));
        timer.textContent = seconds ? "Vorschau noch " + seconds + " s gültig" : "Vorschau abgelaufen";
        button.disabled = busy || field.value.trim() !== "TAUSCHEN" || !seconds;
      }
      field.addEventListener("input", tick);
      tick();
      clearInterval(content.countdown);
      content.countdown = setInterval(function () {
        if (!timer.isConnected) clearInterval(content.countdown);
        else tick();
      }, 1000);
      return [
        S.card("Wohin gehen die Daten?", { icon: "users" }, h("div", { class: "stack" },
          h("b", { text: preview.oldName + " erhält den Spielstand von " + preview.newName }),
          h("b", { text: preview.newName + " erhält den Spielstand von " + preview.oldName }))),
        S.card("Bestand vor dem Tausch", { icon: "box" }, h("div", { class: "list" },
          (preview.rows || []).map(function (row) {
            return h("div", { class: "item stackable" }, h("div", { class: "meta grow" },
              h("b", { text: row.area }), row.path ? h("small", { text: row.path }) : null,
              h("span", { text: preview.oldName + " · " + row.old }), h("span", { text: preview.newName + " · " + row.new }),
              row.oldItems ? h("details", {}, h("summary", { text: "Items vergleichen" }),
                itemList(preview.oldName, row.oldItems), itemList(preview.newName, row.newItems)) : null));
          }))),
        S.card("Tausch bestätigen", { icon: "shieldCheck" }, h("div", { class: "stack" },
          h("p", { class: "muted", text: "Sicherungen werden auf dem Server gespeichert. Während des Tauschs sind beide Accounts für Anmeldungen gesperrt." }),
          S.field("TAUSCHEN eingeben", field), timer,
          h("div", { class: "row wrap" }, button, S.btn("Auswahl bearbeiten", { disabled: busy, onClick: function () { preview = null; render(); } }))))
      ];
    }

    function itemList(name, items) {
      return h("div", {}, h("b", { text: name }), h("ul", {}, items.length
        ? items.map(function (item) { return h("li", { text: item }); }) : h("li", { text: "Leer" })));
    }

    function result() {
      return S.card(outcome.title, { icon: outcome.ok ? "check" : "alert" }, h("div", { class: "stack" },
        (outcome.completed || []).map(function (line) { return S.notice(line, "ok"); }),
        (outcome.errors || []).map(function (line) { return S.notice(line, "warn"); }),
        h("span", { text: "Sicherung " + outcome.backup }),
        S.btn("Weiteren Transfer vorbereiten", { onClick: function () { from = null; to = null; preview = null; outcome = null; render(); } })));
    }

    function render() {
      clearInterval(content.countdown);
      S.clear(content);
      if (issue) content.appendChild(S.notice(issue, "danger"));
      if (!caps.ready) content.appendChild(S.notice("Das Transfer-Journal muss auf dem Server geprüft werden.", "warn"));
      S.add(content, outcome ? result() : preview ? review() : select());
    }
    S.mount(content);
    render();
  };
})();
