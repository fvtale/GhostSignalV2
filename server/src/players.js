import { createLedger, fromLemonSqueezy, fromStripe, paymentUrl, providerOf, verifyLemonSqueezy, verifyStripe } from "./billing.js";

const MAX_WEBHOOK = 512 * 1024;

function fail(status, message) {
  return Object.assign(new Error(message), { status });
}

const httpsOrBlank = (v) => {
  if (v === undefined || v === null || v === "") return "";
  if (typeof v !== "string" || v.length > 500) return null;
  try {
    return new URL(v).protocol === "https:" ? v : null;
  } catch {
    return null;
  }
};

/**
 * Accounts for players and admins, premium, and the payment link. Player requests carry their
 * session in an `X-Account-Token` header, so the phone can send it alongside the event token it
 * already sends as `Authorization`.
 *
 *   POST /v1/account/signup | /v1/account/login   { email, password, client: "web" | "app" }
 *   GET  /v1/account/me                           account, plan and payment link
 *   POST /v1/account/logout | /password | /delete | /progress
 *   POST /v1/billing/stripe | /v1/billing/lemonsqueezy   signed provider webhooks
 *   GET/PUT /v1/admin/accounts[/:id], /v1/admin/unclaimed/:id, GET/PUT /v1/admin/settings  (admin)
 */
export function createPlayers({ accounts, glasses, store, defaults = {}, secrets = {}, limits = {}, premiumTracks = () => [], log = console }) {
  // Per address per minute. A venue's Wi-Fi puts a whole crowd behind one address, so these are
  // set for an event, not for one person (SIGNUPS_PER_MINUTE, LOGINS_PER_MINUTE).
  const perMinute = { signup: limits.signup ?? 30, login: limits.login ?? 60 };
  const ledger = createLedger(store);
  const attempts = { signup: new Map(), login: new Map(), glasses: new Map() };

  function settings() {
    const saved = store.read("settings.json", {});
    return {
      paymentLink: saved.paymentLink ?? defaults.paymentLink ?? "",
      priceLabel: saved.priceLabel ?? defaults.priceLabel ?? "",
      manageBillingUrl: saved.manageBillingUrl ?? defaults.manageBillingUrl ?? "",
    };
  }

  function sessionOf(req) {
    const token = req.headers["x-account-token"];
    return typeof token === "string" ? accounts.session(token) : null;
  }

  function plan(account) {
    const s = settings();
    return {
      account: accounts.view(account),
      payment: {
        url: accounts.premiumActive(account) ? null : paymentUrl(s.paymentLink, account),
        provider: providerOf(s.paymentLink),
        priceLabel: s.priceLabel,
        manageUrl: account.premium?.manageUrl || s.manageBillingUrl || null,
      },
      premiumTracks: premiumTracks(),
      glasses: glasses.mine(account.id),
    };
  }

  function apply(provider, e) {
    let a = e.accountId ? accounts.get(e.accountId) : null;
    if (!a) a = accounts.findByCustomer(provider, e.customerId);
    if (!a) {
      // Paid without an account id (e.g. a link shared outside the account page). An email alone
      // isn't proof of who someone is here, so an admin matches it up by hand.
      if (e.active) ledger.addUnclaimed(provider, e);
      return;
    }
    if (!e.active && a.premium?.source !== "payment") return; // never undo an admin's grant
    accounts.setPayment(a, { active: e.active, provider, customerId: e.customerId, subscriptionId: e.subscriptionId, manageUrl: e.manageUrl, until: e.until });
  }

  async function webhook(provider, req, readBody) {
    const raw = await readBody(req, MAX_WEBHOOK);
    const secret = secrets[provider];
    if (!secret) throw fail(501, `${provider} webhooks aren't set up on this server`);
    const ok =
      provider === "stripe"
        ? verifyStripe(raw, req.headers["stripe-signature"], secret)
        : verifyLemonSqueezy(raw, req.headers["x-signature"], secret);
    if (!ok) throw fail(400, "bad signature");
    let evt;
    try {
      evt = JSON.parse(raw.toString("utf8"));
    } catch {
      throw fail(400, "invalid JSON");
    }
    const e = provider === "stripe" ? fromStripe(evt) : fromLemonSqueezy(evt, raw);
    if (!e) return { ok: true, ignored: true };
    if (ledger.seen(e.id)) return { ok: true, duplicate: true };
    apply(provider, e);
    ledger.mark(provider, e.id);
    log.info?.(`${provider} webhook: premium ${e.active ? "on" : "off"} for ${e.accountId ?? e.customerId ?? "an unmatched payment"}`);
    return { ok: true };
  }

  /** At most [max] sign-ups (or sign-ins) a minute from one address. */
  function limited(kind, ip, max) {
    const now = Date.now();
    const hits = (attempts[kind].get(ip) ?? []).filter((t) => now - t < 60_000);
    hits.push(now);
    attempts[kind].set(ip, hits);
    return hits.length > max;
  }

  return {
    settings,
    /** The signed-in player for a request, if any. */
    accountFor: (req) => sessionOf(req)?.account ?? null,
    isPremium(req) {
      const a = sessionOf(req)?.account;
      return Boolean(a && accounts.premiumActive(a));
    },
    health() {
      const s = settings();
      return { paymentLink: Boolean(s.paymentLink), stripe: Boolean(secrets.stripe), lemonsqueezy: Boolean(secrets.lemonsqueezy) };
    },

    /** Handles account and billing routes. Returns undefined when the route isn't one of them. */
    async handle(req, url, ip, { readJson, readBody }) {
      const route = `${req.method} ${url.pathname}`;
      if (route === "POST /v1/billing/stripe") return [200, await webhook("stripe", req, readBody)];
      if (route === "POST /v1/billing/lemonsqueezy") return [200, await webhook("lemonsqueezy", req, readBody)];
      if (!url.pathname.startsWith("/v1/account/")) return undefined;

      if (route === "POST /v1/account/signup" || route === "POST /v1/account/login") {
        const signup = route.endsWith("signup");
        if (limited(signup ? "signup" : "login", ip, perMinute[signup ? "signup" : "login"])) throw fail(429, "too many attempts; wait a minute");
        const body = await readJson(req);
        const s = signup ? await accounts.signUp(body) : await accounts.logIn(body);
        return [signup ? 201 : 200, { token: s.token, expiresAtMs: s.expiresAtMs, ...plan(accounts.get(s.account.id)) }];
      }

      const session = sessionOf(req);
      if (!session) throw fail(401, "sign in again");
      const token = req.headers["x-account-token"];
      switch (route) {
        case "GET /v1/account/me":
          return [200, plan(session.account)];
        case "POST /v1/account/logout":
          accounts.logOut(token);
          return [204, ""];
        case "POST /v1/account/password": {
          const body = await readJson(req);
          await accounts.changePassword(token, body.current, body.next);
          return [204, ""];
        }
        case "POST /v1/account/delete": {
          const body = await readJson(req);
          const id = session.account.id;
          await accounts.remove(token, body.password);
          glasses.forget(id);
          return [204, ""];
        }
        case "GET /v1/account/glasses":
          return [200, { glasses: glasses.mine(session.account.id) }];
        case "POST /v1/account/glasses": {
          if (limited("glasses", session.account.id, 6)) throw fail(429, "too many attempts; wait a minute");
          const link = glasses.request(session.account, await readJson(req));
          return [link.status === "approved" ? 200 : 202, { link, ...plan(session.account) }];
        }
        case "POST /v1/account/progress": {
          const body = await readJson(req);
          return [200, { completions: accounts.recordProgress(session.account, body.missionId, body.completions) }];
        }
        default: {
          const unlink = url.pathname.match(/^\/v1\/account\/glasses\/(g_[0-9a-f]+)$/);
          if (unlink && req.method === "DELETE") {
            glasses.unlink(session.account.id, unlink[1]);
            return [204, ""];
          }
          throw fail(404, "not found");
        }
      }
    },

    /** Admin routes for players and the payment link; called once the caller is known to be an admin. */
    async handleAdmin(req, url, who, { readJson }) {
      const route = `${req.method} ${url.pathname}`;
      if (route === "GET /v1/admin/accounts") {
        return [200, { accounts: accounts.list(url.searchParams.get("q") ?? ""), counts: accounts.counts(), unclaimed: ledger.unclaimed() }];
      }
      const acct = url.pathname.match(/^\/v1\/admin\/accounts\/(u_[0-9a-f]+)$/);
      if (acct && req.method === "PUT") {
        const change = await readJson(req);
        if (who?.accountId === acct[1] && (change.role === "player" || change.disabled === true)) {
          throw fail(409, "you can't remove your own admin access");
        }
        return [200, accounts.update(acct[1], change)];
      }
      const unclaimed = url.pathname.match(/^\/v1\/admin\/unclaimed\/(p_[0-9a-f]+)$/);
      if (unclaimed && req.method === "DELETE") {
        return ledger.takeUnclaimed(unclaimed[1]) ? [204, ""] : [404, { error: "no such payment" }];
      }
      if (unclaimed && req.method === "POST") {
        const { accountId } = await readJson(req);
        const a = typeof accountId === "string" ? accounts.get(accountId) : null;
        if (!a) throw fail(404, "no such account");
        const u = ledger.takeUnclaimed(unclaimed[1]);
        if (!u) throw fail(404, "no such payment");
        return [200, accounts.setPayment(a, { active: true, provider: u.provider, customerId: u.customerId, subscriptionId: u.subscriptionId, manageUrl: u.manageUrl, until: u.until })];
      }
      const by = who?.display ?? "Operator token";
      if (route === "GET /v1/admin/glasses") return [200, { links: glasses.list(), enrolled: glasses.enrolled() }];
      const gl = url.pathname.match(/^\/v1\/admin\/glasses\/(g_[0-9a-f]+)$/);
      if (gl && req.method === "PUT") return [200, glasses.decide(gl[1], (await readJson(req)).status, by)];
      if (route === "POST /v1/admin/glasses/enroll") return [201, glasses.enroll(await readJson(req), by)];
      const en = url.pathname.match(/^\/v1\/admin\/glasses\/enrolled\/(e_[0-9a-f]+)$/);
      if (en && req.method === "DELETE") {
        glasses.unenroll(en[1]);
        return [204, ""];
      }
      if (route === "GET /v1/admin/settings") {
        const last = ledger.last();
        return [200, {
          settings: settings(),
          provider: providerOf(settings().paymentLink),
          webhooks: {
            stripe: { configured: Boolean(secrets.stripe), path: "/v1/billing/stripe", lastMs: last.stripe ?? null },
            lemonsqueezy: { configured: Boolean(secrets.lemonsqueezy), path: "/v1/billing/lemonsqueezy", lastMs: last.lemonsqueezy ?? null },
          },
        }];
      }
      if (route === "PUT /v1/admin/settings") {
        const body = await readJson(req);
        const paymentLink = httpsOrBlank(body.paymentLink);
        const manageBillingUrl = httpsOrBlank(body.manageBillingUrl);
        if (paymentLink === null) throw fail(400, "the payment link must be an https:// address");
        if (manageBillingUrl === null) throw fail(400, "the billing page must be an https:// address");
        const priceLabel = typeof body.priceLabel === "string" ? body.priceLabel.trim().slice(0, 40) : "";
        store.write("settings.json", { paymentLink, priceLabel, manageBillingUrl });
        return [200, { settings: settings(), provider: providerOf(paymentLink) }];
      }
      return undefined;
    },
  };
}
