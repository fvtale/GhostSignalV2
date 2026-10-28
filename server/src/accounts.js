import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { promisify } from "node:util";

const scrypt = promisify(crypto.scrypt);
const DAY = 24 * 60 * 60 * 1000;
/** Browsers sign in for a month, phones for half a year; both renew while in use. */
const TTL = { web: 30 * DAY, app: 180 * DAY };
const KEY_LEN = 64;
const COST = { N: 1 << 15, r: 8, p: 1, maxmem: 64 * 1024 * 1024 };

function fail(status, message) {
  return Object.assign(new Error(message), { status });
}

export function normalEmail(email) {
  return typeof email === "string" ? email.trim().toLowerCase() : "";
}

const validEmail = (e) => e.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(e);
const sha256 = (s) => crypto.createHash("sha256").update(s).digest("hex");

async function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const key = await scrypt(password, salt, KEY_LEN, COST);
  return `scrypt$${COST.N}$${COST.r}$${COST.p}$${salt.toString("base64")}$${key.toString("base64")}`;
}

async function checkPassword(password, stored) {
  const [scheme, N, r, p, salt, key] = String(stored).split("$");
  if (scheme !== "scrypt") return false;
  const want = Buffer.from(key, "base64");
  const got = await scrypt(password, Buffer.from(salt, "base64"), want.length, { N: Number(N), r: Number(r), p: Number(p), maxmem: COST.maxmem });
  return crypto.timingSafeEqual(got, want);
}

/**
 * Player and admin accounts, kept in two JSON files under DATA_DIR: accounts.json and
 * sessions.json. Passwords are scrypt hashes; session tokens are random and only their SHA-256
 * is stored, so a copy of the data folder can't be used to sign in.
 *
 * Everyone who signs up is a player. Emails listed in ADMIN_EMAILS are admins, and admins can make
 * other accounts admins from the admin page. Premium is a flag with a source: a payment (set by a
 * payment provider's webhook) or a grant (set by an admin).
 */
export function createAccounts({ dir = process.env.DATA_DIR || "data", adminEmails = [], now = Date.now } = {}) {
  fs.mkdirSync(dir, { recursive: true });
  const accountsFile = path.join(dir, "accounts.json");
  const sessionsFile = path.join(dir, "sessions.json");
  const admins = new Set(adminEmails.map(normalEmail).filter(Boolean));
  const read = (file, fallback) => {
    try {
      return JSON.parse(fs.readFileSync(file, "utf8"));
    } catch {
      return fallback;
    }
  };
  const write = (file, value) => {
    const tmp = `${file}.${process.pid}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(value, null, 2), { mode: 0o600 });
    fs.renameSync(tmp, file);
  };
  let accounts = read(accountsFile, { accounts: [] }).accounts;
  let sessions = read(sessionsFile, { sessions: {} }).sessions;
  const saveAccounts = () => write(accountsFile, { accounts });
  const saveSessions = () => write(sessionsFile, { sessions });
  // A real hash to compare against when the email is unknown, so a wrong email takes as long as a
  // wrong password and the form can't be used to find out who has an account.
  const decoy = hashPassword(crypto.randomBytes(12).toString("hex"));

  const byId = (id) => accounts.find((a) => a.id === id) ?? null;
  const byEmail = (email) => accounts.find((a) => a.email === normalEmail(email)) ?? null;

  function isAdmin(a) {
    return a.role === "admin" || admins.has(a.email);
  }

  /** Premium from a payment or an admin's grant. */
  function paidOrGranted(a) {
    const p = a.premium;
    return Boolean(p?.active && (!p.until || p.until > now()));
  }

  /** Other ways to premium (linked glasses), plugged in by the server. */
  let extraPremium = () => false;

  function premiumActive(a) {
    return paidOrGranted(a) || extraPremium(a.id);
  }

  function view(a) {
    return {
      id: a.id,
      email: a.email,
      role: isAdmin(a) ? "admin" : "player",
      adminFromConfig: admins.has(a.email),
      disabled: Boolean(a.disabled),
      createdAtMs: a.createdAtMs,
      lastSeenMs: a.lastSeenMs ?? null,
      premium: {
        active: premiumActive(a),
        // "payment" or "grant" when that's active; otherwise "glasses" for an approved pair.
        source: paidOrGranted(a) ? a.premium.source : extraPremium(a.id) ? "glasses" : a.premium?.source ?? null,
        provider: a.premium?.provider ?? null,
        sinceMs: a.premium?.sinceMs ?? null,
        untilMs: a.premium?.until ?? null,
        manageUrl: a.premium?.manageUrl ?? null,
      },
      completions: a.completions ?? {},
    };
  }

  function openSession(a, client) {
    const kind = client === "app" ? "app" : "web";
    const token = `gsu_${crypto.randomBytes(32).toString("base64url")}`;
    const t = now();
    for (const [h, s] of Object.entries(sessions)) if (s.expiresAtMs <= t) delete sessions[h];
    sessions[sha256(token)] = { accountId: a.id, kind, createdAtMs: t, expiresAtMs: t + TTL[kind] };
    a.lastSeenMs = t;
    saveSessions();
    saveAccounts();
    return { token, expiresAtMs: t + TTL[kind], account: view(a) };
  }

  function dropSessions(accountId, keepHash = null) {
    for (const [h, s] of Object.entries(sessions)) if (s.accountId === accountId && h !== keepHash) delete sessions[h];
    saveSessions();
  }

  return {
    view,
    isAdmin,
    premiumActive,
    setExtraPremium(fn) {
      extraPremium = fn;
    },
    get: byId,
    findByEmail: byEmail,
    findByCustomer: (provider, customerId) =>
      customerId ? accounts.find((a) => a.premium?.provider === provider && a.premium?.customerId === customerId) ?? null : null,

    async signUp({ email, password, client }) {
      const e = normalEmail(email);
      if (!validEmail(e)) throw fail(400, "enter a valid email address");
      if (typeof password !== "string" || password.length < 10 || password.length > 200) {
        throw fail(400, "use a password of at least 10 characters");
      }
      // Hash first (the slow, async step), then check-and-insert with no await in between, so
      // two sign-ups for the same email racing each other can't both slip past the check.
      const passwordHash = await hashPassword(password);
      if (byEmail(e)) throw fail(409, "there's already an account with that email; sign in instead");
      const a = {
        id: `u_${crypto.randomBytes(9).toString("hex")}`,
        email: e,
        passwordHash,
        role: "player",
        createdAtMs: now(),
        premium: null,
        completions: {},
      };
      accounts.push(a);
      saveAccounts();
      return openSession(a, client);
    },

    /** The account for an email and password, without signing in. Throws 401 or 403. */
    async verify({ email, password }) {
      const a = byEmail(email);
      const ok = typeof password === "string" && password.length <= 200 && (await checkPassword(password, a?.passwordHash ?? (await decoy)));
      if (!a || !ok) throw fail(401, "that email and password don't match");
      if (a.disabled) throw fail(403, "this account is disabled");
      return a;
    },

    async logIn({ email, password, client }) {
      return openSession(await this.verify({ email, password }), client);
    },

    /** The signed-in account for a session token, or null. Renews a session that's in use. */
    session(token) {
      if (typeof token !== "string" || !token.startsWith("gsu_")) return null;
      const h = sha256(token);
      const s = sessions[h];
      const t = now();
      if (!s || s.expiresAtMs <= t) return null;
      const a = byId(s.accountId);
      if (!a || a.disabled) return null;
      const ttl = TTL[s.kind] ?? TTL.web;
      if (s.expiresAtMs - t < ttl / 2) {
        s.expiresAtMs = t + ttl;
        a.lastSeenMs = t;
        saveSessions();
        saveAccounts();
      }
      return { account: a, hash: h };
    },

    logOut(token) {
      if (typeof token !== "string") return;
      delete sessions[sha256(token)];
      saveSessions();
    },

    async changePassword(token, current, next) {
      const s = this.session(token);
      if (!s) throw fail(401, "sign in again");
      if (!(typeof current === "string" && (await checkPassword(current, s.account.passwordHash)))) throw fail(403, "your current password is wrong");
      if (typeof next !== "string" || next.length < 10 || next.length > 200) throw fail(400, "use a password of at least 10 characters");
      s.account.passwordHash = await hashPassword(next);
      saveAccounts();
      dropSessions(s.account.id, s.hash); // sign out everywhere else
    },

    async remove(token, password) {
      const s = this.session(token);
      if (!s) throw fail(401, "sign in again");
      if (!(typeof password === "string" && (await checkPassword(password, s.account.passwordHash)))) throw fail(403, "that password is wrong");
      accounts = accounts.filter((a) => a.id !== s.account.id);
      saveAccounts();
      dropSessions(s.account.id);
    },

    /** Finished runs only ever go up, so two phones on one account can't undo each other. */
    recordProgress(account, missionId, completions) {
      if (typeof missionId !== "string" || !/^[\w-]{1,80}$/.test(missionId)) throw fail(400, "invalid mission");
      if (!Number.isInteger(completions) || completions < 0 || completions > 100000) throw fail(400, "invalid completions");
      account.completions = account.completions ?? {};
      account.completions[missionId] = Math.max(account.completions[missionId] ?? 0, completions);
      saveAccounts();
      return account.completions[missionId];
    },

    // ---------------------------------------------------------------- admin

    list(query = "") {
      const q = normalEmail(query);
      return accounts.filter((a) => !q || a.email.includes(q)).map(view);
    },

    counts() {
      return {
        total: accounts.length,
        premium: accounts.filter(premiumActive).length,
        admins: accounts.filter(isAdmin).length,
      };
    },

    /** Admin changes: role, disabled, or a premium grant. */
    update(id, change) {
      const a = byId(id);
      if (!a) throw fail(404, "no such account");
      if (change.role !== undefined) {
        if (!["admin", "player"].includes(change.role)) throw fail(400, "role is admin or player");
        if (change.role === "player" && admins.has(a.email)) throw fail(409, "this email is an admin through ADMIN_EMAILS");
        a.role = change.role;
      }
      if (change.disabled !== undefined) {
        a.disabled = change.disabled === true;
        if (a.disabled) dropSessions(a.id);
      }
      if (change.premium !== undefined) {
        if (change.premium === true) a.premium = { ...(a.premium ?? {}), active: true, source: "grant", provider: null, sinceMs: now(), until: null };
        else if (change.premium === false) a.premium = a.premium ? { ...a.premium, active: false } : null;
        else throw fail(400, "premium is true or false");
      }
      saveAccounts();
      return view(a);
    },

    /** What a payment provider says about an account's premium. */
    setPayment(a, payment) {
      const was = a.premium ?? {};
      a.premium = {
        ...was,
        active: payment.active,
        source: "payment",
        provider: payment.provider,
        customerId: payment.customerId ?? was.customerId ?? null,
        subscriptionId: payment.subscriptionId ?? was.subscriptionId ?? null,
        manageUrl: payment.manageUrl ?? was.manageUrl ?? null,
        sinceMs: payment.active && !was.active ? now() : was.sinceMs ?? now(),
        until: payment.until ?? null,
      };
      saveAccounts();
      return view(a);
    },
  };
}
