// GhostSignal account page. The session token lives in this browser's
// localStorage (shared with the web player) and travels as X-Account-Token.
(() => {
  "use strict";

  const TOKEN_KEY = "ghostsignal-account";
  const byId = (id) => document.getElementById(id);

  let sessionToken = null;
  let authMode = "in"; // "in" = sign in, "up" = create account
  let currentMissionId = null;

  function rememberToken(token) {
    sessionToken = token;
    try {
      if (token) localStorage.setItem(TOKEN_KEY, token);
      else localStorage.removeItem(TOKEN_KEY);
    } catch {
      // Private window: signed in until the tab closes.
    }
  }

  function recalledToken() {
    try {
      return localStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  }

  async function api(path, { method = "GET", body, isSignIn = false } = {}) {
    const headers = { accept: "application/json" };
    if (sessionToken) headers["x-account-token"] = sessionToken;
    if (body !== undefined) headers["content-type"] = "application/json";

    const response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (response.status === 204) return null;
    const payload = await response.json().catch(() => ({}));
    if (response.ok) return payload;
    if (response.status === 401 && sessionToken && !isSignIn) {
      showSignIn("You've been signed out. Sign in again.");
    }
    const error = new Error(payload.error || `HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }

  /** Post-sign-in destination from ?return=, same-site paths only. */
  function returnTarget() {
    const target = new URLSearchParams(location.search).get("return");
    return target && /^\/(?!\/)[\w\/.-]*$/.test(target) ? target : null;
  }

  const formatDate = (ms) =>
    new Date(ms).toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" });

  // -- signed out ----------------------------------------------------------------

  function selectAuthMode(nextMode) {
    authMode = nextMode;
    const signingIn = authMode === "in";
    byId("tabIn").setAttribute("aria-selected", String(signingIn));
    byId("tabUp").setAttribute("aria-selected", String(!signingIn));
    byId("submit").textContent = signingIn ? "Sign in" : "Create account";
    byId("signinTitle").textContent = signingIn ? "Sign in" : "Create your account";
    byId("passwordHint").hidden = signingIn;
    byId("form").elements.password.autocomplete = signingIn ? "current-password" : "new-password";
    byId("signinError").textContent = "";
  }

  function showSignIn(message) {
    rememberToken(null);
    byId("app").hidden = true;
    byId("who").hidden = true;
    byId("signin").hidden = false;
    byId("signinError").textContent = message || "";
  }

  // -- signed in ---------------------------------------------------------------------

  function planSummary(account) {
    const premium = account.premium;
    if (!premium.active) {
      return "The base game and every soundtrack you earn by finishing runs.";
    }
    const origin = premium.source === "glasses"
      ? "Through your linked glasses."
      : premium.source === "grant" ? "Given to you by DataRail" : "Paid";
    const since = premium.sinceMs ? ` since ${formatDate(premium.sinceMs)}` : "";
    const until = premium.untilMs ? `. Ends ${formatDate(premium.untilMs)}.` : ".";
    return premium.source === "glasses" ? origin : `${origin}${since}${until}`;
  }

  function renderAccount(me) {
    const account = me.account;
    const premium = account.premium;
    const payment = me.payment;

    byId("signin").hidden = true;
    byId("app").hidden = false;
    byId("who").hidden = false;
    byId("whoName").textContent = account.email;
    byId("emailAgain").textContent = account.email;

    byId("planName").textContent = premium.active ? "Premium" : "Free";
    const badge = byId("planBadge");
    badge.textContent = premium.active ? "Premium" : "Free";
    badge.className = `badge ${premium.active ? "premium" : "base"}`;
    byId("planDetail").textContent = planSummary(account);

    const perkItems = me.premiumTracks.length
      ? me.premiumTracks.map((track) =>
          `${premium.active ? "Playing: " : "Premium soundtrack: "}${track.title}` +
          (track.description ? ` — ${track.description}` : ""))
      : [premium.active
          ? "Premium soundtracks appear in the app as soon as they're published."
          : "Premium soundtracks, as they're published."];
    const perkList = document.createElement("ul");
    perkList.className = "perks";
    for (const text of perkItems) {
      const item = document.createElement("li");
      item.textContent = text;
      perkList.append(item);
    }
    const perksBox = byId("perks");
    perksBox.textContent = "";
    perksBox.append(perkList);

    const getPremium = byId("getPremium");
    getPremium.hidden = !payment.url;
    if (payment.url) {
      getPremium.href = payment.url;
      getPremium.textContent = `Get premium${payment.priceLabel ? ` · ${payment.priceLabel}` : ""}`;
    }
    byId("notOnSale").hidden = Boolean(premium.active || payment.url);

    const manageBilling = byId("manageBilling");
    manageBilling.hidden = !(premium.active && premium.source === "payment" && payment.manageUrl);
    if (!manageBilling.hidden) manageBilling.href = payment.manageUrl;

    renderGlasses(me.glasses || []);
    renderRunCount(account);
  }

  function renderGlasses(glasses) {
    const statusText = {
      approved: "linked, premium",
      pending: "waiting for an admin to approve",
      rejected: "not approved",
      revoked: "removed by an admin",
    };
    const list = byId("glassesList");
    list.textContent = "";
    for (const pair of glasses) {
      const item = document.createElement("li");
      const model = pair.deviceType.replace(/_/g, " ").toLowerCase();
      const name = pair.name ? ` “${pair.name}”` : "";
      item.textContent = `${model}${name} (${pair.fingerprint}): ${statusText[pair.status] || pair.status}`;
      list.append(item);
    }
    if (!glasses.length) {
      const item = document.createElement("li");
      item.textContent = "No glasses linked yet.";
      list.append(item);
    }
  }

  function renderRunCount(account) {
    const completions = account.completions || {};
    const total = currentMissionId
      ? completions[currentMissionId] || 0
      : Object.values(completions).reduce((sum, n) => sum + n, 0);
    byId("runs").textContent = `Finished runs: ${total}`;
  }

  const loadAccount = () => api("/v1/account/me").then(renderAccount);

  /** After returning from payment with ?paid=1, poll for premium ~1 minute. */
  function watchForPayment() {
    if (!/[?&]paid=1\b/.test(location.search)) return;
    byId("paidNotice").hidden = false;
    let attempts = 0;
    const timer = setInterval(() => {
      attempts++;
      api("/v1/account/me")
        .then((me) => {
          renderAccount(me);
          if (me.account.premium.active || attempts >= 12) {
            clearInterval(timer);
            byId("paidNotice").textContent = me.account.premium.active
              ? "Premium is on. Open GhostSignal on your phone to get the premium soundtracks."
              : "The payment hasn't come through yet. It can take a few minutes; refresh this page later.";
          }
        })
        .catch(() => clearInterval(timer));
    }, 5000);
    history.replaceState(null, "", location.pathname);
  }

  // -- wiring -------------------------------------------------------------------------

  byId("tabIn").addEventListener("click", () => selectAuthMode("in"));
  byId("tabUp").addEventListener("click", () => selectAuthMode("up"));

  byId("form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.target;
    const submitButton = byId("submit");
    submitButton.disabled = true;
    byId("signinError").textContent = "";
    try {
      const data = await api(
        authMode === "in" ? "/v1/account/login" : "/v1/account/signup",
        {
          method: "POST",
          isSignIn: true,
          body: {
            email: form.elements.email.value,
            password: form.elements.password.value,
            client: "web",
          },
        },
      );
      rememberToken(data.token);
      form.elements.password.value = "";
      const target = returnTarget();
      if (target) {
        location.assign(target);
        return;
      }
      renderAccount(data);
      watchForPayment();
    } catch (error) {
      byId("signinError").textContent = error.message;
    } finally {
      submitButton.disabled = false;
    }
  });

  byId("signOut").addEventListener("click", async () => {
    try {
      await api("/v1/account/logout", { method: "POST" });
    } catch {
      // Signing out locally matters even if the server already forgot us.
    }
    showSignIn("");
  });

  byId("passwordForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.target;
    byId("passwordStatus").textContent = "";
    try {
      await api("/v1/account/password", {
        method: "POST",
        body: { current: form.elements.current.value, next: form.elements.next.value },
      });
      form.elements.current.value = "";
      form.elements.next.value = "";
      byId("passwordStatus").textContent =
        "Password changed. Other browsers and phones are signed out.";
    } catch (error) {
      byId("passwordStatus").textContent = error.message;
    }
  });

  byId("deleteForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.target;
    if (!confirm("Delete your GhostSignal account? This can't be undone.")) return;
    try {
      await api("/v1/account/delete", {
        method: "POST",
        body: { password: form.elements.password.value },
      });
      showSignIn("Your account has been deleted.");
    } catch (error) {
      byId("deleteError").textContent = error.message;
    }
  });

  // -- boot -----------------------------------------------------------------------------

  (async () => {
    try {
      const health = await (await fetch("/health")).json();
      currentMissionId = health.mission || null;
    } catch {
      currentMissionId = null;
    }
    if (/[?&]signup=1\b/.test(location.search)) selectAuthMode("up");
    sessionToken = recalledToken();
    if (!sessionToken) {
      showSignIn("");
      return;
    }
    try {
      await loadAccount();
      watchForPayment();
    } catch {
      showSignIn("");
    }
  })();
})();
