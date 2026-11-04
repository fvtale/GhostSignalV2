import crypto from "node:crypto";

// Admin sign-in for the operator pages. Three credentials are accepted:
//   1. a GhostSignal account with the admin role (via ADMIN_EMAILS or an
//      existing admin's grant);
//   2. the operator token, which always works, so a fresh server and the
//      phone's route editor need nothing else configured;
//   3. a DataRail account flagged admin, when DATARAIL_API points at a
//      DataRail account service. The password goes straight to DataRail,
//      is never stored here, and that DataRail session is closed again
//      immediately after the identity check.
// Every path returns a short-lived session token for the browser tab. A
// session backed by a GhostSignal account dies the moment that account
// stops being an admin.

const SESSION_TTL_MS = 12 * 60 * 60 * 1000;
const DATARAIL_TIMEOUT_MS = 10_000;

function authError(status, message) {
  return Object.assign(new Error(message), { status });
}

/** Constant-time comparison that also hides the secret's length. */
function secretsMatch(candidate, expected) {
  const digest = (value) => crypto.createHash("sha256").update(String(value)).digest();
  return crypto.timingSafeEqual(digest(candidate), digest(expected));
}

export function createAdmin({
  operatorToken = "",
  datarailApi = "",
  fetchImpl = fetch,
  now = Date.now,
  accounts = null,
} = {}) {
  const liveSessions = new Map();
  const datarailRoot = datarailApi.replace(/\/+$/, "");

  function startSession(who) {
    for (const [token, session] of liveSessions) {
      if (session.expiresAtMs <= now()) liveSessions.delete(token);
    }
    const token = `gsa_${crypto.randomBytes(24).toString("base64url")}`;
    const expiresAtMs = now() + SESSION_TTL_MS;
    liveSessions.set(token, { who, expiresAtMs });
    return { session: token, expiresAtMs, who };
  }

  async function askDatarail(email, password) {
    let response;
    try {
      response = await fetchImpl(`${datarailRoot}/auth/login`, {
        method: "POST",
        headers: { "content-type": "application/json", accept: "application/json" },
        body: JSON.stringify({ email, password }),
        signal: AbortSignal.timeout(DATARAIL_TIMEOUT_MS),
      });
    } catch {
      throw authError(502, "couldn't reach DataRail");
    }
    if (response.status === 401) throw authError(401, "that email and password do not match");
    if (response.status === 429) throw authError(429, "too many attempts; wait and try again");
    if (!response.ok) throw authError(502, `DataRail answered ${response.status}`);

    const payload = await response.json().catch(() => ({}));
    // Close the DataRail session we just opened; only the answer matters.
    const cookie = (response.headers.get("set-cookie") ?? "").split(";")[0];
    if (cookie) {
      fetchImpl(`${datarailRoot}/auth/logout`, { method: "POST", headers: { cookie } }).catch(() => {});
    }
    return payload.principal ?? {};
  }

  return {
    /** True when DataRail sign-in is configured (the page hides it otherwise). */
    datarail: Boolean(datarailRoot),

    session(token) {
      const found = token ? liveSessions.get(token) : null;
      if (!found) return null;
      const backingAccount = found.who.accountId ? accounts?.get(found.who.accountId) : null;
      const stillAdmin = !found.who.accountId ||
        (backingAccount && !backingAccount.disabled && accounts.isAdmin(backingAccount));
      if (found.expiresAtMs <= now() || !stillAdmin) {
        liveSessions.delete(token);
        return null;
      }
      return found;
    },

    close(token) {
      liveSessions.delete(token);
    },

    async login(body) {
      if (typeof body.token === "string") {
        if (!operatorToken || !secretsMatch(body.token, operatorToken)) {
          throw authError(401, "that operator token is wrong");
        }
        return startSession({ display: "Operator token", via: "operator-token" });
      }

      const hasCredentials = typeof body.email === "string" &&
        typeof body.password === "string" && body.email.trim() !== "";
      if (!hasCredentials) {
        throw authError(400, "sign in with an email and password, or the operator token");
      }

      if (body.provider !== "datarail") {
        if (!accounts) throw authError(501, "GhostSignal accounts aren't set up on this server");
        const account = await accounts.verify(body);
        if (!accounts.isAdmin(account)) throw authError(403, "this account isn't an admin");
        return startSession({ display: account.email, via: "ghostsignal", accountId: account.id });
      }

      if (!datarailRoot) {
        throw authError(501, "DataRail sign-in isn't set up on this server (DATARAIL_API)");
      }
      const principal = await askDatarail(body.email.trim(), body.password);
      if (principal.is_admin !== true) throw authError(403, "this DataRail account isn't an admin");
      return startSession({ display: String(principal.display || body.email.trim()), via: "datarail" });
    },
  };
}
