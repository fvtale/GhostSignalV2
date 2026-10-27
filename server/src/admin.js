import crypto from "node:crypto";

const SESSION_TTL_MS = 12 * 60 * 60 * 1000;

function fail(status, message) {
  return Object.assign(new Error(message), { status });
}

function sameSecret(a, b) {
  const h = (s) => crypto.createHash("sha256").update(String(s)).digest();
  return crypto.timingSafeEqual(h(a), h(b));
}

/**
 * Who may use the admin page. Three ways in:
 *  - a GhostSignal account with the admin role (ADMIN_EMAILS, or made an admin by another admin);
 *  - the operator token, which always works, so a fresh server and the phone's route editor need
 *    nothing else; and
 *  - a DataRail account flagged as an admin, when DATARAIL_API points at the DataRail account
 *    service (e.g. https://<host>/storage/api). The password goes to DataRail, never to disk here;
 *    the server only asks who the account is and closes that DataRail session straight away.
 * Every way gives the page a session token for the tab, sent back as a bearer token. A session
 * from a GhostSignal account ends as soon as that account stops being an admin.
 */
export function createAdmin({ operatorToken = "", datarailApi = "", fetchImpl = fetch, now = Date.now, accounts = null } = {}) {
  const sessions = new Map();
  const api = datarailApi.replace(/\/+$/, "");

  function open(who) {
    for (const [t, s] of sessions) if (s.expiresAtMs <= now()) sessions.delete(t);
    const session = `gsa_${crypto.randomBytes(24).toString("base64url")}`;
    const expiresAtMs = now() + SESSION_TTL_MS;
    sessions.set(session, { who, expiresAtMs });
    return { session, expiresAtMs, who };
  }

  async function datarailLogin(email, password) {
    let res;
    try {
      res = await fetchImpl(`${api}/auth/login`, {
        method: "POST",
        headers: { "content-type": "application/json", accept: "application/json" },
        body: JSON.stringify({ email, password }),
        signal: AbortSignal.timeout(10_000),
      });
    } catch {
      throw fail(502, "couldn't reach DataRail");
    }
    if (res.status === 401) throw fail(401, "that email and password do not match");
    if (res.status === 429) throw fail(429, "too many attempts; wait and try again");
    if (!res.ok) throw fail(502, `DataRail answered ${res.status}`);
    const data = await res.json().catch(() => ({}));
    const cookie = (res.headers.get("set-cookie") ?? "").split(";")[0];
    if (cookie) fetchImpl(`${api}/auth/logout`, { method: "POST", headers: { cookie } }).catch(() => {});
    return data.principal ?? {};
  }

  return {
    /** Whether DataRail sign-in is configured; the page hides the form when it isn't. */
    datarail: Boolean(api),

    session(token) {
      const s = token ? sessions.get(token) : null;
      if (!s) return null;
      const account = s.who.accountId ? accounts?.get(s.who.accountId) : null;
      const stillAdmin = !s.who.accountId || (account && !account.disabled && accounts.isAdmin(account));
      if (s.expiresAtMs <= now() || !stillAdmin) {
        sessions.delete(token);
        return null;
      }
      return s;
    },

    close(token) {
      sessions.delete(token);
    },

    async login(body) {
      if (typeof body.token === "string") {
        if (!operatorToken || !sameSecret(body.token, operatorToken)) throw fail(401, "that operator token is wrong");
        return open({ display: "Operator token", via: "operator-token" });
      }
      if (typeof body.email !== "string" || typeof body.password !== "string" || !body.email.trim()) {
        throw fail(400, "sign in with an email and password, or the operator token");
      }
      if (body.provider !== "datarail") {
        if (!accounts) throw fail(501, "GhostSignal accounts aren't set up on this server");
        const account = await accounts.verify(body);
        if (!accounts.isAdmin(account)) throw fail(403, "this account isn't an admin");
        return open({ display: account.email, via: "ghostsignal", accountId: account.id });
      }
      if (!api) throw fail(501, "DataRail sign-in isn't set up on this server (DATARAIL_API)");
      const principal = await datarailLogin(body.email.trim(), body.password);
      if (principal.is_admin !== true) throw fail(403, "this DataRail account isn't an admin");
      return open({ display: String(principal.display || body.email.trim()), via: "datarail" });
    },
  };
}
