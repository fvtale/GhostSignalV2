import crypto from "node:crypto";

/**
 * Premium through a payment link. The admin pastes a Stripe Payment Link or a Lemon Squeezy
 * checkout link; each account's "Get premium" button opens it with the account's id and email
 * attached, and the provider's webhook tells the server when that account has paid (or stopped
 * paying). Any other link still works as a plain link, and an admin grants premium by hand.
 */

export function providerOf(link) {
  try {
    const host = new URL(link).hostname;
    if (host === "buy.stripe.com" || host.endsWith(".stripe.com")) return "stripe";
    if (host.endsWith(".lemonsqueezy.com")) return "lemonsqueezy";
  } catch {
    // not a URL
  }
  return null;
}

/** The payment link for one account, with whatever the provider needs to match the payment to it. */
export function paymentUrl(link, account) {
  if (!link) return null;
  const url = new URL(link);
  const provider = providerOf(link);
  if (provider === "stripe") {
    url.searchParams.set("client_reference_id", account.id);
    url.searchParams.set("prefilled_email", account.email);
  } else if (provider === "lemonsqueezy") {
    url.searchParams.set("checkout[custom][account_id]", account.id);
    url.searchParams.set("checkout[email]", account.email);
  }
  return url.toString();
}

function sameHex(a, b) {
  const x = Buffer.from(String(a), "hex");
  const y = Buffer.from(String(b), "hex");
  return x.length === y.length && x.length > 0 && crypto.timingSafeEqual(x, y);
}

const hmac = (secret, data) => crypto.createHmac("sha256", secret).update(data).digest("hex");

/** Stripe-Signature: t=<unix>,v1=<hex>[,v1=...]; signed payload is "<t>.<body>". */
export function verifyStripe(raw, header, secret, nowMs = Date.now(), toleranceS = 300) {
  if (!secret || typeof header !== "string") return false;
  const parts = header.split(",").map((p) => p.split("="));
  const t = Number(parts.find(([k]) => k === "t")?.[1]);
  if (!Number.isFinite(t) || Math.abs(nowMs / 1000 - t) > toleranceS) return false;
  const want = hmac(secret, `${t}.${raw.toString("utf8")}`);
  return parts.some(([k, v]) => k === "v1" && sameHex(v, want));
}

/** X-Signature: hex HMAC of the raw body. */
export function verifyLemonSqueezy(raw, header, secret) {
  if (!secret || typeof header !== "string") return false;
  return sameHex(header, hmac(secret, raw));
}

/**
 * What a Stripe event means for premium, or null when it doesn't matter:
 * { id, active, accountId?, email?, customerId?, subscriptionId?, until? }
 */
export function fromStripe(evt) {
  const o = evt?.data?.object ?? {};
  switch (evt?.type) {
    case "checkout.session.completed":
    case "checkout.session.async_payment_succeeded":
      if (o.payment_status !== "paid" && o.payment_status !== "no_payment_required") return null;
      return {
        id: evt.id,
        active: true,
        accountId: o.client_reference_id ?? null,
        email: o.customer_details?.email ?? o.customer_email ?? null,
        customerId: typeof o.customer === "string" ? o.customer : null,
        subscriptionId: typeof o.subscription === "string" ? o.subscription : null,
      };
    case "customer.subscription.updated":
    case "customer.subscription.deleted": {
      const live = evt.type === "customer.subscription.updated" && ["active", "trialing", "past_due"].includes(o.status);
      return { id: evt.id, active: live, customerId: typeof o.customer === "string" ? o.customer : null, subscriptionId: o.id ?? null };
    }
    default:
      return null;
  }
}

/** The same for a Lemon Squeezy event. A cancelled subscription stays premium until it ends. */
export function fromLemonSqueezy(evt, raw) {
  const name = evt?.meta?.event_name;
  const a = evt?.data?.attributes ?? {};
  const base = {
    id: crypto.createHash("sha256").update(raw).digest("hex"),
    accountId: evt?.meta?.custom_data?.account_id ?? null,
    email: a.user_email ?? null,
    customerId: a.customer_id != null ? String(a.customer_id) : null,
  };
  if (name === "order_created") return a.status === "paid" ? { ...base, active: true } : null;
  if (name === "order_refunded") return { ...base, active: false };
  if (!name?.startsWith("subscription_") || name.startsWith("subscription_payment")) return null;
  const subscriptionId = evt?.data?.id != null ? String(evt.data.id) : null;
  // Shown to the player as a link, so only ever an https address.
  const manageUrl = /^https:\/\//.test(a.urls?.customer_portal ?? "") ? a.urls.customer_portal : null;
  const status = a.status;
  if (["active", "on_trial", "past_due"].includes(status)) return { ...base, active: true, subscriptionId, manageUrl };
  if (status === "cancelled" && a.ends_at && Date.parse(a.ends_at) > Date.now()) {
    return { ...base, active: true, until: Date.parse(a.ends_at), subscriptionId, manageUrl };
  }
  return { ...base, active: false, subscriptionId, manageUrl };
}

/**
 * Webhook bookkeeping kept with the accounts: events already applied (providers retry), when each
 * provider was last heard from, and payments that couldn't be matched to an account, for an admin
 * to apply by hand.
 */
export function createLedger(store) {
  const state = store.read("billing.json", { seen: [], last: {}, unclaimed: [] });
  const save = () => store.write("billing.json", state);
  return {
    seen: (id) => state.seen.includes(id),
    mark(provider, id) {
      state.seen = [...state.seen, id].slice(-1000);
      state.last[provider] = Date.now();
      save();
    },
    last: () => ({ ...state.last }),
    unclaimed: () => [...state.unclaimed],
    addUnclaimed(provider, e) {
      state.unclaimed.push({
        id: `p_${crypto.randomBytes(6).toString("hex")}`,
        provider,
        email: e.email ?? null,
        customerId: e.customerId ?? null,
        subscriptionId: e.subscriptionId ?? null,
        manageUrl: e.manageUrl ?? null,
        until: e.until ?? null,
        atMs: Date.now(),
      });
      state.unclaimed = state.unclaimed.slice(-200);
      save();
    },
    takeUnclaimed(id) {
      const i = state.unclaimed.findIndex((u) => u.id === id);
      if (i < 0) return null;
      const [u] = state.unclaimed.splice(i, 1);
      save();
      return u;
    },
  };
}
