import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { promisify } from "node:util";

// Player and admin accounts, persisted as two JSON documents in the data
// folder (accounts.json, sessions.json). Passwords are stored as scrypt
// hashes; session tokens are random and only their SHA-256 is stored, so
// a leaked copy of the data folder cannot be replayed as a sign-in.
//
// Sign-up makes a player. Accounts whose email is in ADMIN_EMAILS are
// admins, and admins can promote others from the admin page. Premium has a
// source: "payment" (webhook-driven) or "grant" (an admin's gift), plus an
// optional external source plugged in by the server (linked glasses).

const scryptAsync = promisify(crypto.scrypt);

const DAY_MS = 24 * 60 * 60 * 1000;
const SESSION_TTL_MS = { web: 30 * DAY_MS, app: 180 * DAY_MS }; // Browsers: a month. Phones: half a year.
const DERIVED_KEY_BYTES = 64;
const SCRYPT_PARAMS = { N: 1 << 15, r: 8, p: 1, maxmem: 64 * 1024 * 1024 };
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MISSION_ID_PATTERN = /^[\w-]{1,80}$/;

function accountError(status, message) {
  return Object.assign(new Error(message), { status });
}

export function normalEmail(email) {
  return typeof email === "string" ? email.trim().toLowerCase() : "";
}

function isValidEmail(email) {
  return email.length <= 254 && EMAIL_PATTERN.test(email);
}

function sha256Hex(text) {
  return crypto.createHash("sha256").update(text).digest("hex");
}

async function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const derived = await scryptAsync(password, salt, DERIVED_KEY_BYTES, SCRYPT_PARAMS);
  const { N, r, p } = SCRYPT_PARAMS;
  return ["scrypt", N, r, p, salt.toString("base64"), derived.toString("base64")].join("$");
}

async function passwordMatches(password, storedHash) {
  const [scheme, n, r, p, saltB64, keyB64] = String(storedHash).split("$");
  if (scheme !== "scrypt") return false;
  const expected = Buffer.from(keyB64, "base64");
  const derived = await scryptAsync(password, Buffer.from(saltB64, "base64"), expected.length, {
    N: Number(n),
    r: Number(r),
    p: Number(p),
    maxmem: SCRYPT_PARAMS.maxmem,
  });
  return crypto.timingSafeEqual(derived, expected);
}

export function createAccounts({
  dir = process.env.DATA_DIR || "data",
  adminEmails = [],
  now = Date.now,
} = {}) {
  fs.mkdirSync(dir, { recursive: true });
  const accountsPath = path.join(dir, "accounts.json");
  const sessionsPath = path.join(dir, "sessions.json");
  const configAdmins = new Set(adminEmails.map(normalEmail).filter(Boolean));

  function readJson(file, fallback) {
    try {
      return JSON.parse(fs.readFileSync(file, "utf8"));
    } catch {
      return fallback;
    }
  }

  function writeJson(file, value) {
    const temp = `${file}.${process.pid}.tmp`;
    fs.writeFileSync(temp, JSON.stringify(value, null, 2), { mode: 0o600 });
    fs.renameSync(temp, file);
  }

  let accountRows = readJson(accountsPath, { accounts: [] }).accounts;
  let sessionRows = readJson(sessionsPath, { sessions: {} }).sessions;
  const persistAccounts = () => writeJson(accountsPath, { accounts: accountRows });
  const persistSessions = () => writeJson(sessionsPath, { sessions: sessionRows });

  // A genuine hash to check against for unknown emails, so "no such
  // account" takes as long as "wrong password" and sign-in cannot be used
  // to enumerate registered addresses.
  const decoyHashPromise = hashPassword(crypto.randomBytes(12).toString("hex"));

  const findById = (id) => accountRows.find((row) => row.id === id) ?? null;
  const findByEmail = (email) => accountRows.find((row) => row.email === normalEmail(email)) ?? null;

  function isAdmin(account) {
    return account.role === "admin" || configAdmins.has(account.email);
  }

  function paidOrGranted(account) {
    const premium = account.premium;
    return Boolean(premium?.active && (!premium.until || premium.until > now()));
  }

  // Extra premium sources (approved glasses), supplied by the server.
  let extraPremiumCheck = () => false;

  function premiumActive(account) {
    return paidOrGranted(account) || extraPremiumCheck(account.id);
  }

  function view(account) {
    const paid = paidOrGranted(account);
    return {
      id: account.id,
      email: account.email,
      role: isAdmin(account) ? "admin" : "player",
      adminFromConfig: configAdmins.has(account.email),
      disabled: Boolean(account.disabled),
      createdAtMs: account.createdAtMs,
      lastSeenMs: account.lastSeenMs ?? null,
      premium: {
        active: premiumActive(account),
        source: paid ? account.premium.source
          : extraPremiumCheck(account.id) ? "glasses"
          : account.premium?.source ?? null,
        provider: account.premium?.provider ?? null,
        sinceMs: account.premium?.sinceMs ?? null,
        untilMs: account.premium?.until ?? null,
        manageUrl: account.premium?.manageUrl ?? null,
      },
      completions: account.completions ?? {},
    };
  }

  function startSession(account, client) {
    const kind = client === "app" ? "app" : "web";
    const token = `gsu_${crypto.randomBytes(32).toString("base64url")}`;
    const startedAt = now();
    // Opportunistically drop expired sessions while we are writing anyway.
    for (const [hash, session] of Object.entries(sessionRows)) {
      if (session.expiresAtMs <= startedAt) delete sessionRows[hash];
    }
    sessionRows[sha256Hex(token)] = {
      accountId: account.id,
      kind,
      createdAtMs: startedAt,
      expiresAtMs: startedAt + SESSION_TTL_MS[kind],
    };
    account.lastSeenMs = startedAt;
    persistSessions();
    persistAccounts();
    return { token, expiresAtMs: startedAt + SESSION_TTL_MS[kind], account: view(account) };
  }

  function endSessions(accountId, keepHash = null) {
    for (const [hash, session] of Object.entries(sessionRows)) {
      if (session.accountId === accountId && hash !== keepHash) delete sessionRows[hash];
    }
    persistSessions();
  }

  const api = {
    view,
    isAdmin,
    premiumActive,

    setExtraPremium(fn) {
      extraPremiumCheck = fn;
    },

    get: findById,
    findByEmail,

    findByCustomer(provider, customerId) {
      if (!customerId) return null;
      return accountRows.find(
        (row) => row.premium?.provider === provider && row.premium?.customerId === customerId,
      ) ?? null;
    },

    async signUp({ email, password, client }) {
      const normalized = normalEmail(email);
      if (!isValidEmail(normalized)) throw accountError(400, "enter a valid email address");
      if (typeof password !== "string" || password.length < 10 || password.length > 200) {
        throw accountError(400, "use a password of at least 10 characters");
      }
      // Hash first (slow, awaited), then check-and-insert with no await in
      // between: two racing sign-ups for one email cannot both succeed.
      const passwordHash = await hashPassword(password);
      if (findByEmail(normalized)) {
        throw accountError(409, "there's already an account with that email; sign in instead");
      }
      const account = {
        id: `u_${crypto.randomBytes(9).toString("hex")}`,
        email: normalized,
        passwordHash,
        role: "player",
        createdAtMs: now(),
        premium: null,
        completions: {},
      };
      accountRows.push(account);
      persistAccounts();
      return startSession(account, client);
    },

    /** Checks credentials without opening a session. Throws 401/403. */
    async verify({ email, password }) {
      const account = findByEmail(email);
      const usable = typeof password === "string" && password.length <= 200;
      const stored = account?.passwordHash ?? (await decoyHashPromise);
      const matches = usable && (await passwordMatches(password, stored));
      if (!account || !matches) throw accountError(401, "that email and password don't match");
      if (account.disabled) throw accountError(403, "this account is disabled");
      return account;
    },

    async logIn(credentials) {
      const account = await api.verify(credentials);
      return startSession(account, credentials.client);
    },

    /** The account behind a session token, or null. Active sessions renew. */
    session(token) {
      if (typeof token !== "string" || !token.startsWith("gsu_")) return null;
      const hash = sha256Hex(token);
      const found = sessionRows[hash];
      const at = now();
      if (!found || found.expiresAtMs <= at) return null;
      const account = findById(found.accountId);
      if (!account || account.disabled) return null;
      const ttl = SESSION_TTL_MS[found.kind] ?? SESSION_TTL_MS.web;
      if (found.expiresAtMs - at < ttl / 2) {
        found.expiresAtMs = at + ttl;
        account.lastSeenMs = at;
        persistSessions();
        persistAccounts();
      }
      return { account, hash };
    },

    logOut(token) {
      if (typeof token !== "string") return;
      delete sessionRows[sha256Hex(token)];
      persistSessions();
    },

    async changePassword(token, current, next) {
      const session = api.session(token);
      if (!session) throw accountError(401, "sign in again");
      const currentOk = typeof current === "string" &&
        (await passwordMatches(current, session.account.passwordHash));
      if (!currentOk) throw accountError(403, "your current password is wrong");
      if (typeof next !== "string" || next.length < 10 || next.length > 200) {
        throw accountError(400, "use a password of at least 10 characters");
      }
      session.account.passwordHash = await hashPassword(next);
      persistAccounts();
      endSessions(session.account.id, session.hash); // Sign out everywhere else.
    },

    async remove(token, password) {
      const session = api.session(token);
      if (!session) throw accountError(401, "sign in again");
      const passwordOk = typeof password === "string" &&
        (await passwordMatches(password, session.account.passwordHash));
      if (!passwordOk) throw accountError(403, "that password is wrong");
      accountRows = accountRows.filter((row) => row.id !== session.account.id);
      persistAccounts();
      endSessions(session.account.id);
    },

    /** Completions only ever increase, so two phones cannot undo each other. */
    recordProgress(account, missionId, completions) {
      if (typeof missionId !== "string" || !MISSION_ID_PATTERN.test(missionId)) {
        throw accountError(400, "invalid mission");
      }
      if (!Number.isInteger(completions) || completions < 0 || completions > 100000) {
        throw accountError(400, "invalid completions");
      }
      account.completions = account.completions ?? {};
      account.completions[missionId] = Math.max(account.completions[missionId] ?? 0, completions);
      persistAccounts();
      return account.completions[missionId];
    },

    // -- admin ---------------------------------------------------------------

    list(query = "") {
      const needle = normalEmail(query);
      return accountRows
        .filter((row) => !needle || row.email.includes(needle))
        .map(view);
    },

    counts() {
      return {
        total: accountRows.length,
        premium: accountRows.filter(premiumActive).length,
        admins: accountRows.filter(isAdmin).length,
      };
    },

    /** Applies an admin's change: role, disabled flag, or a premium grant. */
    update(id, change) {
      const account = findById(id);
      if (!account) throw accountError(404, "no such account");
      if (change.role !== undefined) {
        if (!["admin", "player"].includes(change.role)) {
          throw accountError(400, "role is admin or player");
        }
        if (change.role === "player" && configAdmins.has(account.email)) {
          throw accountError(409, "this email is an admin through ADMIN_EMAILS");
        }
        account.role = change.role;
      }
      if (change.disabled !== undefined) {
        account.disabled = change.disabled === true;
        if (account.disabled) endSessions(account.id);
      }
      if (change.premium !== undefined) {
        if (change.premium === true) {
          account.premium = {
            ...(account.premium ?? {}),
            active: true, source: "grant", provider: null, sinceMs: now(), until: null,
          };
        } else if (change.premium === false) {
          account.premium = account.premium ? { ...account.premium, active: false } : null;
        } else {
          throw accountError(400, "premium is true or false");
        }
      }
      persistAccounts();
      return view(account);
    },

    /** Records what a payment provider reports about an account's premium. */
    setPayment(account, payment) {
      const previous = account.premium ?? {};
      account.premium = {
        ...previous,
        active: payment.active,
        source: "payment",
        provider: payment.provider,
        customerId: payment.customerId ?? previous.customerId ?? null,
        subscriptionId: payment.subscriptionId ?? previous.subscriptionId ?? null,
        manageUrl: payment.manageUrl ?? previous.manageUrl ?? null,
        sinceMs: payment.active && !previous.active ? now() : previous.sinceMs ?? now(),
        until: payment.until ?? null,
      };
      persistAccounts();
      return view(account);
    },
  };

  return api;
}
