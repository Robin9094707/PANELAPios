/* Simplex Panel service worker (Admin Suite 9.9.0): browser push and an offline start screen.
   API responses (/v1/…) are never cached; only the static app shell is kept for a fast, offline-safe start. */
"use strict";
var VERSION = "{{VERSION}}";
var SHELL = "simplex-shell-" + VERSION;
var ASSETS = ["/", "/assets/app.css?v=" + VERSION, "/assets/core.js?v=" + VERSION, "/assets/auth.js?v=" + VERSION, "/assets/pages.js?v=" + VERSION,
  "/assets/admin.js?v=" + VERSION, "/assets/extras.js?v=" + VERSION, "/assets/rights.js?v=" + VERSION, "/assets/diagnostics.js?v=" + VERSION, "/assets/studio.js?v=" + VERSION, "/assets/adhub.js?v=" + VERSION, "/assets/app.js?v=" + VERSION, "/assets/qr.js?v=" + VERSION, "/assets/logo.png", "/assets/icon-192.png"];

self.addEventListener("install", function (event) {
  event.waitUntil(caches.open(SHELL).then(function (cache) { return cache.addAll(ASSETS).catch(function () { /* offline install is optional */ }); }).then(function () { return self.skipWaiting(); }));
});
self.addEventListener("activate", function (event) {
  event.waitUntil(caches.keys().then(function (keys) {
    return Promise.all(keys.filter(function (k) { return k.indexOf("simplex-shell-") === 0 && k !== SHELL; }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});
self.addEventListener("fetch", function (event) {
  var req = event.request, url = new URL(req.url);
  if (req.method !== "GET" || url.origin !== self.location.origin || url.pathname.indexOf("/v1/") === 0) return;
  if (req.mode === "navigate") {
    event.respondWith(fetch(req).catch(function () { return caches.match("/"); }));
    return;
  }
  if (url.pathname.indexOf("/assets/") === 0) {
    event.respondWith(caches.match(req).then(function (hit) { return hit || fetch(req); }));
  }
});

self.addEventListener("push", function (event) {
  var data = {};
  try { data = event.data ? event.data.json() : {}; } catch (e) { data = { title: "Simplex Panel", body: event.data ? event.data.text() : "" }; }
  var title = data.title || "Simplex Panel";
  var options = {
    body: data.body || "", tag: data.tag || undefined, renotify: !!data.tag, requireInteraction: !!data.critical,
    icon: "/assets/icon-192.png", badge: "/assets/badge-96.png", timestamp: data.time || Date.now(),
    data: { url: data.url || "/#/dashboard" }
  };
  event.waitUntil(self.registration.showNotification(title, options).then(function () {
    if (self.navigator && self.navigator.setAppBadge) return self.registration.getNotifications().then(function (list) { return self.navigator.setAppBadge(list.length); }).catch(function () {});
  }));
});
self.addEventListener("notificationclick", function (event) {
  event.notification.close();
  var target = new URL((event.notification.data && event.notification.data.url) || "/#/dashboard", self.location.origin).href;
  event.waitUntil(self.clients.matchAll({ type: "window", includeUncontrolled: true }).then(function (list) {
    for (var i = 0; i < list.length; i++) {
      var c = list[i];
      if (new URL(c.url).origin === self.location.origin) {
        if ("navigate" in c) return c.focus().then(function (w) { return w.navigate(target); }).catch(function () { return c.focus(); });
        return c.focus();
      }
    }
    return self.clients.openWindow(target);
  }));
});
self.addEventListener("pushsubscriptionchange", function (event) {
  // The page re-subscribes on its next start (subscriptions are tied to the signed-in account).
  event.waitUntil(Promise.resolve());
});
