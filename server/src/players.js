import {
  createLedger,
  fromLemonSqueezy,
  fromStripe,
  paymentUrl,
  providerOf,
  verifyLemonSqueezy,
  verifyStripe,
} from "./billing.js";

// Player-facing routes: accounts, glasses linking, run progress, payment
// webhooks, and the admin views over all of that. Players authenticate with
// an X-Account-Token header so the phone can send it alongside the event
// token it already presents as Authorization.
//
//   POST /v1/account/signup | /v1/account/login   { email, password, client }
//   GET  /v1/account/me                           account, plan, payment link
//   POST /v1/account/logout | /password | /delete | /progress
//   POST /v1/billing/stripe | /v1/billing/lemonsqueezy   signed webhooks
//   GET/PUT /v1/admin/accounts[/:id], /v1/admin/unclaimed/:id,
//   GET/PUT /v1/admin/settings, /v1/admin/glasses*       (admin only)

const MAX_WEBHOOK_BYTES = 512 * 1024;

function playerError(status, message) {
  return Object.assign(new Error(message), { status });
}

/** An https URL or ""; null when a value was supplied but is not usable. */
function httpsUrlOrBlank(value) {
  if (value === undefined || value === null || value === "") return "";
  if (typeof value !== "string" || value.length > 500) return null;
  try {
    return new URL(value).protocol === "https:" ? value : null;
  } catch {
    return null;
  }
}

export function createPlayers({
  accounts,
  glasses,
  store,
  defaults = {},
  secrets = {},
  limits = {},
  premiumTracks = () => [],
  log = console,
}) {
  // Attempt limits are per address per minute. Venue Wi-Fi puts a crowd
  // behind one address, so these are sized for an event, not a person.
  const attemptLimits = { signup: limits.signup ?? 30, login: limits.login ?? 60 };
  const ledger = createLedger(store);
  const attemptsByKind = { signup: new Map(), login: new Map(), glasses: new Map() };

  function currentSettings() {
    const saved = store.read("settings.json", {});
    return {
      paymentLink: saved.paymentLink ?? defaults.paymentLink ?? "",
      priceLabel: saved.priceLabel ?? defaults.priceLabel ?? "",
      manageBillingUrl: saved.manageBillingUrl ?? defaults.manageBillingUrl ?? "",
    };
  }

  function sessionFrom(req) {
    const token = req.headers["x-account-token"];
    return typeof token === "string" ? accounts.session(token) : null;
  }

  function planFor(account) {
    const settings = currentSettings();
    const premium = accounts.premiumActive(account);
    return {
      account: accounts.view(account),
      payment: {
        url: premium ? null : paymentUrl(settings.paymentLink, account),
        provider: providerOf(settings.paymentLink),
        priceLabel: settings.priceLabel,
        manageUrl: account.premium?.manageUrl || settings.manageBillingUrl || null,
      },
      premiumTracks: premiumTracks(),
      glasses: glasses.mine(account.id),
    };
  }

  /** Applies one verified payment event to accounts and the ledger. */
  function applyPaymentEvent(provider, event) {
    let account = event.accountId ? accounts.get(event.accountId) : null;
    if (!account) account = accounts.findByCustomer(provider, event.customerId);
    if (!account) {
      // Paid without an account id (a shared link). An email alone is not
      // proof of identity here, so an admin matches it up by hand.
      if (event.active) ledger.addUnclaimed(provider, event);
      return;
    }
    // A lapsed payment never revokes an admin's manual grant.
    if (!event.active && account.premium?.source !== "payment") return;
    accounts.setPayment(account, {
      active: event.active,
      provider,
      customerId: event.customerId,
      subscriptionId: event.subscriptionId,
      manageUrl: event.manageUrl,
      until: event.until,
    });
  }

  async function handleWebhook(provider, req, readBody) {
    const rawBody = await readBody(req, MAX_WEBHOOK_BYTES);
    const secret = secrets[provider];
    if (!secret) throw playerError(501, `${provider} webhooks aren't set up on this server`);

    const signatureValid = provider === "stripe"
      ? verifyStripe(rawBody, req.headers["stripe-signature"], secret)
      : verifyLemonSqueezy(rawBody, req.headers["x-signature"], secret);
    if (!signatureValid) throw playerError(400, "bad signature");

    let payload;
    try {
      payload = JSON.parse(rawBody.toString("utf8"));
    } catch {
      throw playerError(400, "invalid JSON");
    }

    const event = provider === "stripe" ? fromStripe(payload) : fromLemonSqueezy(payload, rawBody);
    if (!event) return { ok: true, ignored: true };
    if (ledger.seen(event.id)) return { ok: true, duplicate: true };

    applyPaymentEvent(provider, event);
    ledger.mark(provider, event.id);
    const subject = event.accountId ?? event.customerId ?? "an unmatched payment";
    log.info?.(`${provider} webhook: premium ${event.active ? "on" : "off"} for ${subject}`);
    return { ok: true };
  }

  /** True when [kind] attempts from [key] exceed [max] within a minute. */
  function isRateLimited(kind, key, max) {
    const buckets = attemptsByKind[kind];
    const now = Date.now();
    // Forget keys whose window has fully aged out; otherwise this map
    // grows with every address that ever tried.
    if (buckets.size >= 128) {
      for (const [k, stamps] of buckets) {
        if (!stamps.length || now - stamps[stamps.length - 1] >= 60_000) buckets.delete(k);
      }
    }
    const recent = (buckets.get(key) ?? []).filter((stamp) => now - stamp < 60_000);
    recent.push(now);
    buckets.set(key, recent);
    return recent.length > max;
  }

  return {
    settings: currentSettings,

    /** The signed-in player behind a request, if any. */
    accountFor: (req) => sessionFrom(req)?.account ?? null,

    isPremium(req) {
      const account = sessionFrom(req)?.account;
      return Boolean(account && accounts.premiumActive(account));
    },

    health() {
      const settings = currentSettings();
      return {
        paymentLink: Boolean(settings.paymentLink),
        stripe: Boolean(secrets.stripe),
        lemonsqueezy: Boolean(secrets.lemonsqueezy),
      };
    },

    /** Account and billing routes. Returns undefined for other paths. */
    async handle(req, url, ip, { readJson, readBody }) {
      const route = `${req.method} ${url.pathname}`;

      if (route === "POST /v1/billing/stripe") return [200, await handleWebhook("stripe", req, readBody)];
      if (route === "POST /v1/billing/lemonsqueezy") return [200, await handleWebhook("lemonsqueezy", req, readBody)];
      if (!url.pathname.startsWith("/v1/account/")) return undefined;

      if (route === "POST /v1/account/signup" || route === "POST /v1/account/login") {
        const isSignup = route.endsWith("signup");
        const kind = isSignup ? "signup" : "login";
        if (isRateLimited(kind, ip, attemptLimits[kind])) {
          throw playerError(429, "too many attempts; wait a minute");
        }
        const body = await readJson(req);
        const session = isSignup ? await accounts.signUp(body) : await accounts.logIn(body);
        return [isSignup ? 201 : 200, {
          token: session.token,
          expiresAtMs: session.expiresAtMs,
          ...planFor(accounts.get(session.account.id)),
        }];
      }

      const session = sessionFrom(req);
      if (!session) throw playerError(401, "sign in again");
      const token = req.headers["x-account-token"];

      switch (route) {
        case "GET /v1/account/me":
          return [200, planFor(session.account)];

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
          const accountId = session.account.id;
          await accounts.remove(token, body.password);
          glasses.forget(accountId);
          return [204, ""];
        }

        case "GET /v1/account/glasses":
          return [200, { glasses: glasses.mine(session.account.id) }];

        case "POST /v1/account/glasses": {
          if (isRateLimited("glasses", session.account.id, 6)) {
            throw playerError(429, "too many attempts; wait a minute");
          }
          const link = glasses.request(session.account, await readJson(req));
          return [link.status === "approved" ? 200 : 202, { link, ...planFor(session.account) }];
        }

        case "POST /v1/account/progress": {
          const body = await readJson(req);
          const completions = accounts.recordProgress(session.account, body.missionId, body.completions);
          return [200, { completions }];
        }

        default: {
          const unlinkMatch = url.pathname.match(/^\/v1\/account\/glasses\/(g_[0-9a-f]+)$/);
          if (unlinkMatch && req.method === "DELETE") {
            glasses.unlink(session.account.id, unlinkMatch[1]);
            return [204, ""];
          }
          throw playerError(404, "not found");
        }
      }
    },

    /** Admin routes over players/payments; caller is already verified admin. */
    async handleAdmin(req, url, who, { readJson }) {
      const route = `${req.method} ${url.pathname}`;

      if (route === "GET /v1/admin/accounts") {
        return [200, {
          accounts: accounts.list(url.searchParams.get("q") ?? ""),
          counts: accounts.counts(),
          unclaimed: ledger.unclaimed(),
        }];
      }

      const accountMatch = url.pathname.match(/^\/v1\/admin\/accounts\/(u_[0-9a-f]+)$/);
      if (accountMatch && req.method === "PUT") {
        const change = await readJson(req);
        const editingSelf = who?.accountId === accountMatch[1];
        if (editingSelf && (change.role === "player" || change.disabled === true)) {
          throw playerError(409, "you can't remove your own admin access");
        }
        return [200, accounts.update(accountMatch[1], change)];
      }

      const unclaimedMatch = url.pathname.match(/^\/v1\/admin\/unclaimed\/(p_[0-9a-f]+)$/);
      if (unclaimedMatch && req.method === "DELETE") {
        return ledger.takeUnclaimed(unclaimedMatch[1])
          ? [204, ""]
          : [404, { error: "no such payment" }];
      }
      if (unclaimedMatch && req.method === "POST") {
        const { accountId } = await readJson(req);
        const account = typeof accountId === "string" ? accounts.get(accountId) : null;
        if (!account) throw playerError(404, "no such account");
        const payment = ledger.takeUnclaimed(unclaimedMatch[1]);
        if (!payment) throw playerError(404, "no such payment");
        return [200, accounts.setPayment(account, {
          active: true,
          provider: payment.provider,
          customerId: payment.customerId,
          subscriptionId: payment.subscriptionId,
          manageUrl: payment.manageUrl,
          until: payment.until,
        })];
      }

      const decidedBy = who?.display ?? "Operator token";

      if (route === "GET /v1/admin/glasses") {
        return [200, { links: glasses.list(), enrolled: glasses.enrolled() }];
      }
      const linkMatch = url.pathname.match(/^\/v1\/admin\/glasses\/(g_[0-9a-f]+)$/);
      if (linkMatch && req.method === "PUT") {
        return [200, glasses.decide(linkMatch[1], (await readJson(req)).status, decidedBy)];
      }
      if (route === "POST /v1/admin/glasses/enroll") {
        return [201, glasses.enroll(await readJson(req), decidedBy)];
      }
      const enrolledMatch = url.pathname.match(/^\/v1\/admin\/glasses\/enrolled\/(e_[0-9a-f]+)$/);
      if (enrolledMatch && req.method === "DELETE") {
        glasses.unenroll(enrolledMatch[1]);
        return [204, ""];
      }

      if (route === "GET /v1/admin/settings") {
        const settings = currentSettings();
        const lastHeard = ledger.last();
        return [200, {
          settings,
          provider: providerOf(settings.paymentLink),
          webhooks: {
            stripe: { configured: Boolean(secrets.stripe), path: "/v1/billing/stripe", lastMs: lastHeard.stripe ?? null },
            lemonsqueezy: { configured: Boolean(secrets.lemonsqueezy), path: "/v1/billing/lemonsqueezy", lastMs: lastHeard.lemonsqueezy ?? null },
          },
        }];
      }
      if (route === "PUT /v1/admin/settings") {
        const body = await readJson(req);
        const paymentLink = httpsUrlOrBlank(body.paymentLink);
        const manageBillingUrl = httpsUrlOrBlank(body.manageBillingUrl);
        if (paymentLink === null) throw playerError(400, "the payment link must be an https:// address");
        if (manageBillingUrl === null) throw playerError(400, "the billing page must be an https:// address");
        const priceLabel = typeof body.priceLabel === "string" ? body.priceLabel.trim().slice(0, 40) : "";
        store.write("settings.json", { paymentLink, priceLabel, manageBillingUrl });
        return [200, { settings: currentSettings(), provider: providerOf(paymentLink) }];
      }

      return undefined;
    },
  };
}
