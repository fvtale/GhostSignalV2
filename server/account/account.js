/* GhostSignal account page. The session token stays in this browser's localStorage, so the page
   remembers you; every call sends it as X-Account-Token. */
(function () {
  "use strict";

  var KEY = "ghostsignal-account";
  var $ = function (id) { return document.getElementById(id); };
  var token = null;
  var mode = "in"; // or "up"
  var missionId = null;

  function remember(t) {
    token = t;
    try {
      if (t) localStorage.setItem(KEY, t);
      else localStorage.removeItem(KEY);
    } catch (e) { /* private window: signed in until the tab closes */ }
  }

  function remembered() {
    try { return localStorage.getItem(KEY); } catch (e) { return null; }
  }

  function api(path, opts) {
    opts = opts || {};
    var headers = { accept: "application/json" };
    if (token) headers["x-account-token"] = token;
    if (opts.body !== undefined) headers["content-type"] = "application/json";
    return fetch(path, { method: opts.method || "GET", headers: headers, body: opts.body === undefined ? undefined : JSON.stringify(opts.body) })
      .then(function (res) {
        if (res.status === 204) return null;
        return res.json().catch(function () { return {}; }).then(function (data) {
          if (res.ok) return data;
          if (res.status === 401 && token && !opts.signingIn) showSignin("You've been signed out. Sign in again.");
          var err = new Error(data.error || "HTTP " + res.status);
          err.status = res.status;
          throw err;
        });
      });
  }

  /** Where to go after signing in, when another page sent you here. Same-site paths only. */
  function returnTo() {
    var r = new URLSearchParams(location.search).get("return");
    return r && /^\/(?!\/)[\w\/.-]*$/.test(r) ? r : null;
  }

  function date(ms) {
    return new Date(ms).toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" });
  }

  // ------------------------------------------------------------------ signed out

  function pick(next) {
    mode = next;
    $("tabIn").setAttribute("aria-selected", String(mode === "in"));
    $("tabUp").setAttribute("aria-selected", String(mode === "up"));
    $("submit").textContent = mode === "in" ? "Sign in" : "Create account";
    $("signinTitle").textContent = mode === "in" ? "Sign in" : "Create your account";
    $("passwordHint").hidden = mode === "in";
    $("form").elements.password.autocomplete = mode === "in" ? "current-password" : "new-password";
    $("signinError").textContent = "";
  }

  function showSignin(message) {
    remember(null);
    $("app").hidden = true;
    $("who").hidden = true;
    $("signin").hidden = false;
    $("signinError").textContent = message || "";
  }

  // ------------------------------------------------------------------ signed in

  function render(me) {
    var a = me.account, p = a.premium, pay = me.payment;
    $("signin").hidden = true;
    $("app").hidden = false;
    $("who").hidden = false;
    $("whoName").textContent = a.email;
    $("emailAgain").textContent = a.email;

    $("planName").textContent = p.active ? "Premium" : "Free";
    $("planBadge").textContent = p.active ? "Premium" : "Free";
    $("planBadge").className = "badge " + (p.active ? "premium" : "base");
    var detail = "";
    if (p.active) {
      detail = p.source === "glasses" ? "Through your linked glasses." :
        (p.source === "grant" ? "Given to you by DataRail" : "Paid") + (p.sinceMs ? " since " + date(p.sinceMs) : "") +
        (p.untilMs ? ". Ends " + date(p.untilMs) + "." : ".");
    } else {
      detail = "The base game and every soundtrack you earn by finishing runs.";
    }
    $("planDetail").textContent = detail;

    var perks = $("perks");
    perks.textContent = "";
    var list = document.createElement("ul");
    list.className = "perks";
    var items = me.premiumTracks.length
      ? me.premiumTracks.map(function (t) { return (p.active ? "Playing: " : "Premium soundtrack: ") + t.title + (t.description ? " — " + t.description : ""); })
      : [p.active ? "Premium soundtracks appear in the app as soon as they're published." : "Premium soundtracks, as they're published."];
    items.forEach(function (text) {
      var li = document.createElement("li");
      li.textContent = text;
      list.appendChild(li);
    });
    perks.appendChild(list);

    var get = $("getPremium");
    get.hidden = !pay.url;
    if (pay.url) {
      get.href = pay.url;
      get.textContent = "Get premium" + (pay.priceLabel ? " · " + pay.priceLabel : "");
    }
    $("notOnSale").hidden = Boolean(p.active || pay.url);
    var manage = $("manageBilling");
    manage.hidden = !(p.active && p.source === "payment" && pay.manageUrl);
    if (!manage.hidden) manage.href = pay.manageUrl;

    var gl = $("glassesList");
    gl.textContent = "";
    var states = { approved: "linked, premium", pending: "waiting for an admin to approve", rejected: "not approved", revoked: "removed by an admin" };
    (me.glasses || []).forEach(function (g) {
      var li = document.createElement("li");
      li.textContent = g.deviceType.replace(/_/g, " ").toLowerCase() + (g.name ? " \u201c" + g.name + "\u201d" : "") + " (" + g.fingerprint + "): " + (states[g.status] || g.status);
      gl.appendChild(li);
    });
    if (!(me.glasses || []).length) {
      var none = document.createElement("li");
      none.textContent = "No glasses linked yet.";
      gl.appendChild(none);
    }

    var runs = missionId ? a.completions[missionId] || 0 : Object.keys(a.completions).reduce(function (n, k) { return n + a.completions[k]; }, 0);
    $("runs").textContent = "Finished runs: " + runs;
  }

  function load() {
    return api("/v1/account/me").then(render);
  }

  /** After the payment provider sends you back with ?paid=1, look for premium for a minute. */
  function awaitPayment() {
    if (!/[?&]paid=1\b/.test(location.search)) return;
    $("paidNotice").hidden = false;
    var tries = 0;
    var timer = setInterval(function () {
      tries++;
      api("/v1/account/me").then(function (me) {
        render(me);
        if (me.account.premium.active || tries >= 12) {
          clearInterval(timer);
          $("paidNotice").textContent = me.account.premium.active
            ? "Premium is on. Open GhostSignal on your phone to get the premium soundtracks."
            : "The payment hasn't come through yet. It can take a few minutes; refresh this page later.";
        }
      }).catch(function () { clearInterval(timer); });
    }, 5000);
    history.replaceState(null, "", location.pathname);
  }

  // ------------------------------------------------------------------ wiring

  $("tabIn").addEventListener("click", function () { pick("in"); });
  $("tabUp").addEventListener("click", function () { pick("up"); });

  $("form").addEventListener("submit", function (e) {
    e.preventDefault();
    var f = this.elements, button = $("submit");
    button.disabled = true;
    $("signinError").textContent = "";
    api(mode === "in" ? "/v1/account/login" : "/v1/account/signup", {
      method: "POST",
      signingIn: true,
      body: { email: f.email.value, password: f.password.value, client: "web" },
    })
      .then(function (data) {
        remember(data.token);
        f.password.value = "";
        if (returnTo()) return location.assign(returnTo());
        render(data);
        awaitPayment();
      })
      .catch(function (err) { $("signinError").textContent = err.message; })
      .then(function () { button.disabled = false; });
  });

  $("signOut").addEventListener("click", function () {
    api("/v1/account/logout", { method: "POST" }).catch(function () {}).then(function () { showSignin(""); });
  });

  $("passwordForm").addEventListener("submit", function (e) {
    e.preventDefault();
    var f = this.elements;
    $("passwordStatus").textContent = "";
    api("/v1/account/password", { method: "POST", body: { current: f.current.value, next: f.next.value } })
      .then(function () {
        f.current.value = f.next.value = "";
        $("passwordStatus").textContent = "Password changed. Other browsers and phones are signed out.";
      })
      .catch(function (err) { $("passwordStatus").textContent = err.message; });
  });

  $("deleteForm").addEventListener("submit", function (e) {
    e.preventDefault();
    var f = this.elements;
    if (!confirm("Delete your GhostSignal account? This can't be undone.")) return;
    api("/v1/account/delete", { method: "POST", body: { password: f.password.value } })
      .then(function () { showSignin("Your account has been deleted."); })
      .catch(function (err) { $("deleteError").textContent = err.message; });
  });

  fetch("/health").then(function (r) { return r.json(); }).catch(function () { return {}; }).then(function (h) {
    missionId = h.mission || null;
    if (/[?&]signup=1\b/.test(location.search)) pick("up");
    token = remembered();
    if (!token) return showSignin("");
    return load().then(awaitPayment).catch(function () { showSignin(""); });
  });
})();
