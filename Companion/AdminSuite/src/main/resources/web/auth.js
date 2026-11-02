/* Simplex Panel Web – sign-in: password, new password after an emergency reset, TOTP / recovery code, passkeys,
   app hand-off (app → browser) and app sign-in through the browser (browser → app, for passkeys in the app). */
(function () {
  "use strict";
  var S = window.Simplex, h = S.h, icon = S.icon;

  // ------------------------------------------------------------ WebAuthn helpers
  function b64urlToBuf(s) { s = String(s).replace(/-/g, "+").replace(/_/g, "/"); while (s.length % 4) s += "="; var bin = atob(s), out = new Uint8Array(bin.length); for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i); return out.buffer; }
  function bufToB64url(buf) { var bytes = new Uint8Array(buf), bin = ""; for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]); return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""); }
  function passkeySupported() { return !!(window.PublicKeyCredential && navigator.credentials && window.isSecureContext); }
  function passkeyHere() { var c = S.webConfig || {}; return !!(c.passkeys && c.rpId && location.hostname.toLowerCase() === c.rpId && passkeySupported()); }
  function webauthnError(e) {
    if (e && e.name === "NotAllowedError") return "Passkey-Anfrage abgebrochen oder abgelaufen.";
    if (e && e.name === "InvalidStateError") return "Dieser Passkey ist auf diesem Gerät bereits registriert.";
    if (e && e.name === "SecurityError") return "Passkeys funktionieren nur über die eingetragene Domain mit gültigem HTTPS-Zertifikat.";
    return e && e.message ? e.message : "Passkey fehlgeschlagen.";
  }
  async function passkeyAssertion(extra) {
    var begin = await S.api("passkey/begin", { body: {}, quiet: true });
    var pk = begin.publicKey;
    var cred;
    try {
      cred = await navigator.credentials.get({ publicKey: { challenge: b64urlToBuf(pk.challenge), rpId: pk.rpId, timeout: pk.timeout, userVerification: pk.userVerification, allowCredentials: [] }, mediation: "optional" });
    } catch (e) { throw new Error(webauthnError(e)); }
    var r = cred.response;
    return S.api("passkey/finish", { quiet: true, body: Object.assign({ id: begin.id, credential: { id: bufToB64url(cred.rawId), type: cred.type, response: {
      clientDataJSON: bufToB64url(r.clientDataJSON), authenticatorData: bufToB64url(r.authenticatorData), signature: bufToB64url(r.signature), userHandle: r.userHandle ? bufToB64url(r.userHandle) : "" } } }, extra) });
  }
  async function registerPasskey(name) {
    var begin = await S.api("account/passkey/begin", { body: {} });
    var pk = begin.publicKey, cred;
    try {
      cred = await navigator.credentials.create({ publicKey: {
        challenge: b64urlToBuf(pk.challenge), rp: pk.rp, user: { id: b64urlToBuf(pk.user.id), name: pk.user.name, displayName: pk.user.displayName },
        pubKeyCredParams: pk.pubKeyCredParams, timeout: pk.timeout, attestation: "none", authenticatorSelection: pk.authenticatorSelection,
        excludeCredentials: (pk.excludeCredentials || []).map(function (c) { return { type: "public-key", id: b64urlToBuf(c.id) }; }) } });
    } catch (e) { throw new Error(webauthnError(e)); }
    return S.api("account/passkey/finish", { body: { id: begin.id, name: name || "", credential: { id: bufToB64url(cred.rawId), type: cred.type, response: {
      clientDataJSON: bufToB64url(cred.response.clientDataJSON), attestationObject: bufToB64url(cred.response.attestationObject) } } } });
  }
  S.passkey = { supported: passkeySupported, here: passkeyHere, register: registerPasskey };

  // ------------------------------------------------------------ Password strength
  function strength(pw) {
    var s = 0; if (pw.length >= 12) s++; if (pw.length >= 16) s++; if (/[a-z]/.test(pw) && /[A-Z]/.test(pw)) s++; if (/[0-9]/.test(pw) && /[^A-Za-z0-9]/.test(pw)) s++;
    return pw.length < 12 ? Math.min(s, 1) : Math.max(1, s);
  }
  function strengthMeter(inputEl) {
    var m = h("div", { class: "strength", "aria-hidden": "true" }, h("i"), h("i"), h("i"), h("i"));
    var label = h("small", { class: "muted tiny", text: "Mindestens 12 Zeichen" });
    inputEl.addEventListener("input", function () { var v = inputEl.value, s = v ? strength(v) : 0; m.className = "strength s" + s; label.textContent = !v ? "Mindestens 12 Zeichen" : v.length < 12 ? "Noch " + (12 - v.length) + " Zeichen" : ["", "Schwach", "Okay", "Gut", "Sehr gut"][s]; });
    return h("div", { class: "stack", style: { gap: "6px" } }, m, label);
  }
  S.strengthMeter = strengthMeter;

  // ------------------------------------------------------------ Screens
  function shell(title, lead, body, foot) {
    var name = (S.webConfig && S.webConfig.name) || "Simplex";
    var root = document.getElementById("app"); S.clear(root);
    root.appendChild(h("main", { class: "auth" }, h("div", { class: "auth-card" },
      h("img", { class: "auth-logo", src: "/assets/logo.png", alt: "", width: 72, height: 72 }),
      h("h1", { text: title }), h("p", { class: "lead", text: lead || name }), body,
      h("div", { class: "auth-foot" }, foot || ("Simplex Panel · Admin Suite " + ((S.webConfig && S.webConfig.version) || ""))))));
    var first = root.querySelector("input"); if (first) setTimeout(function () { first.focus(); }, 40);
  }
  function errorLine() { return h("div", { class: "notice danger hidden", role: "alert" }, icon("alert"), h("div")); }
  function showError(box, text) { box.classList.remove("hidden"); box.lastChild.textContent = text; }

  /** mode: "web" (normal), "app" (sign in for the app; returns an exchange code to panel://). */
  function login(mode, state) {
    var err = errorLine();
    var user = S.input({ name: "username", autocomplete: "username webauthn", autocapitalize: "none", placeholder: "Benutzername", required: true });
    var pass = S.input({ name: "password", type: "password", autocomplete: "current-password", placeholder: "Passwort", required: true });
    var remember = h("input", { type: "checkbox", checked: mode === "web" });
    var submit = S.btn("Anmelden", { kind: "primary", block: true, submit: true, icon: "lock" });
    var form = h("form", { novalidate: true }, err, S.field("Benutzername", user), S.field("Passwort", pass),
      mode === "web" ? h("label", { class: "check" }, remember, "Angemeldet bleiben (7 Tage)") : null, submit);
    form.addEventListener("submit", async function (e) {
      e.preventDefault(); if (!user.value.trim() || !pass.value) { showError(err, "Benutzername und Passwort eingeben."); return; }
      submit.disabled = true;
      try { var r = await S.api("login", { quiet: true, body: { username: user.value.trim(), password: pass.value, web: true, remember: remember.checked, app: mode === "app" } }); pass.value = ""; await step(r, mode, state); }
      catch (x) { showError(err, x.message); pass.select(); }
      finally { submit.disabled = false; }
    });
    var extra = [];
    if (passkeyHere()) {
      extra.push(h("div", { class: "divider", text: "oder" }));
      extra.push(S.btn("Mit Passkey anmelden", { icon: "fingerprint", block: true, onClick: async function () {
        try { var r = await passkeyAssertion({ web: true, remember: remember.checked, app: mode === "app" }); await step(r, mode, state); }
        catch (x) { showError(err, x.message); }
      } }));
    }
    var hint = null;
    if (!S.webConfig || !S.webConfig.passkeys) hint = h("p", { class: "muted tiny center", style: { margin: "14px 0 0" }, text: "Passkeys stehen zur Verfügung, sobald der Hauptadmin eine Domain mit HTTPS einrichtet." });
    shell(mode === "app" ? "In der App anmelden" : "Anmelden", mode === "app" ? "Nach der Anmeldung geht es automatisch zurück zur App." : (S.webConfig && S.webConfig.name) || "Admin Suite", h("div", {}, form, extra, hint));
  }

  async function step(r, mode, state) {
    if (r.passwordChange) return newPassword(r, mode, state);
    if (r.mfa) return secondFactor(r, mode, state);
    if (r.exchange) { finishApp(r.exchange, state); return; }
    if (r.ok || r.token) { S.toast("Willkommen, " + (r.username || "") + "!"); await S.boot(true); }
  }

  function newPassword(r, mode, state) {
    var err = errorLine();
    var a = S.input({ type: "password", autocomplete: "new-password", placeholder: "Neues Passwort" }), b = S.input({ type: "password", autocomplete: "new-password", placeholder: "Wiederholen" });
    var submit = S.btn("Passwort speichern & weiter", { kind: "primary", block: true, submit: true, icon: "check" });
    var form = h("form", {}, S.notice(r.message || "Bitte ein neues Passwort festlegen.", "warn", "key"), err, S.field("Neues Passwort", a), strengthMeter(a), S.field("Passwort wiederholen", b), submit);
    form.addEventListener("submit", async function (e) {
      e.preventDefault();
      if (a.value.length < 12) { showError(err, "Mindestens 12 Zeichen."); return; }
      if (a.value !== b.value) { showError(err, "Die Passwörter stimmen nicht überein."); return; }
      submit.disabled = true;
      try { var next = await S.api("login/verify", { quiet: true, body: { challenge: r.challenge, newPassword: a.value } }); await step(next, mode, state); }
      catch (x) { showError(err, x.message); if (x.status === 401) setTimeout(function () { login(mode, state); }, 1800); }
      finally { submit.disabled = false; }
    });
    shell("Neues Passwort", "Angemeldet als " + (r.username || ""), form);
  }

  function secondFactor(r, mode, state) {
    var err = errorLine(), recovery = false;
    var code = S.input({ inputmode: "numeric", autocomplete: "one-time-code", pattern: "[0-9]*", maxlength: 6, class: "input code", placeholder: "000000", "aria-label": "6-stelliger Code" });
    var label = h("span", { text: "Code aus deiner Authenticator-App" });
    var submit = S.btn("Bestätigen", { kind: "primary", block: true, submit: true, icon: "shieldCheck" });
    var toggle = h("button", { class: "btn ghost sm", type: "button", text: "Wiederherstellungscode verwenden" });
    toggle.addEventListener("click", function () {
      recovery = !recovery; code.value = ""; code.className = recovery ? "input mono" : "input code";
      code.maxLength = recovery ? 14 : 6; code.placeholder = recovery ? "XXXX-XXXX-XXXX" : "000000"; code.inputMode = recovery ? "text" : "numeric";
      label.textContent = recovery ? "Einer deiner Wiederherstellungscodes" : "Code aus deiner Authenticator-App"; toggle.textContent = recovery ? "Authenticator-Code verwenden" : "Wiederherstellungscode verwenden"; code.focus();
    });
    var form = h("form", {}, err, h("label", { class: "field" }, label, code), submit, h("div", { class: "row", style: { justifyContent: "center" } }, toggle));
    var sending = false;
    async function send() {
      if (sending) return; sending = true; submit.disabled = true;
      try { var next = await S.api("login/verify", { quiet: true, body: { challenge: r.challenge, code: code.value.trim() } }); await step(next, mode, state); }
      catch (x) { showError(err, x.message); code.select(); if (x.status === 401 && /abgelaufen|Fehlversuche/.test(x.message)) setTimeout(function () { login(mode, state); }, 1800); }
      finally { sending = false; submit.disabled = false; }
    }
    code.addEventListener("input", function () { if (!recovery) { code.value = code.value.replace(/\D/g, "").slice(0, 6); if (code.value.length === 6) send(); } });
    form.addEventListener("submit", function (e) { e.preventDefault(); send(); });
    var pk = (r.methods || []).indexOf("passkey") >= 0 && passkeyHere() ? [h("div", { class: "divider", text: "oder" }), S.btn("Passkey verwenden", { icon: "fingerprint", block: true, onClick: async function () {
      try { var next = await passkeyAssertion({ challenge: r.challenge, web: true, app: mode === "app" }); await step(next, mode, state); } catch (x) { showError(err, x.message); }
    } })] : null;
    shell("Zwei-Faktor-Bestätigung", "Angemeldet als " + (r.username || ""), h("div", {}, form, pk));
  }

  function finishApp(code, state) {
    var url = "panel://suite-auth?code=" + encodeURIComponent(code) + (state ? "&state=" + encodeURIComponent(state) : "");
    shell("Zurück zur App", "Anmeldung erfolgreich", h("div", { class: "stack" }, S.notice("Die App übernimmt die Anmeldung automatisch. Falls nicht, auf „Zur App“ tippen.", "ok"), h("a", { class: "btn primary block", href: url }, icon("phone"), "Zur App")));
    setTimeout(function () { location.href = url; }, 150);
  }

  /** #/handoff?code=…&next=passkey – opened by the app to manage passkeys in a real browser context. */
  async function handoff(params) {
    shell("Einen Moment …", "Sichere Übergabe aus der App", S.skeleton(2));
    try {
      await S.api("login/exchange", { quiet: true, body: { code: params.get("code") || "", web: true } });
      history.replaceState(null, "", "/#/security" + (params.get("next") === "passkey" ? "?add=passkey&return=app" : "?return=app"));
      await S.boot(true);
    } catch (x) {
      shell("Übergabe abgelaufen", "Bitte in der App erneut öffnen.", h("div", { class: "stack" }, S.notice(x.message, "danger"), h("a", { class: "btn block", href: "panel://suite-done" }, icon("phone"), "Zurück zur App")));
    }
  }

  S.auth = { login: login, handoff: handoff };
})();
