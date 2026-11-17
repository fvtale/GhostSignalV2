import crypto from "node:crypto";

// Premium is sold through a hosted payment link. An admin pastes a Stripe
// Payment Link or a Lemon Squeezy checkout link; each account's "Get
// premium" button opens it with the account identity attached, and the
// provider's signed webhook tells this server when that account has paid
// (or stopped paying). Links from anywhere else still work as plain links,
// and admins can always grant premium by hand.

/** Which provider a payment link belongs to, or null for a plain link. */
export function providerOf(link) {
  try {
    const host = new URL(link).hostname;
    if (host === "buy.stripe.com" || host.endsWith(".stripe.com")) return "stripe";
    if (host.endsWith(".lemonsqueezy.com")) return "lemonsqueezy";
  } catch {
    // Not a URL at all.
  }
  return null;
}

/** The payment link personalised for one account, so payment can be matched. */
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

function hexEqual(a, b) {
  const left = Buffer.from(String(a), "hex");
  const right = Buffer.from(String(b), "hex");
  return left.length === right.length && left.length > 0 && crypto.timingSafeEqual(left, right);
}

function hmacHex(secret, data) {
  return crypto.createHmac("sha256", secret).update(data).digest("hex");
}

/**
 * Verifies a Stripe webhook. The header is `t=<unix>,v1=<hex>` (possibly
 * several v1 values); the signed payload is "<t>.<raw body>", and the
 * timestamp must be within [toleranceS] seconds of now.
 */
export function verifyStripe(raw, header, secret, nowMs = Date.now(), toleranceS = 300) {
  if (!secret || typeof header !== "string") return false;
  const fields = header.split(",").map((part) => part.split("="));
  const timestamp = Number(fields.find(([key]) => key === "t")?.[1]);
  if (!Number.isFinite(timestamp)) return false;
  if (Math.abs(nowMs / 1000 - timestamp) > toleranceS) return false;
  const expected = hmacHex(secret, `${timestamp}.${raw.toString("utf8")}`);
  return fields.some(([key, value]) => key === "v1" && hexEqual(value, expected));
}

/** Verifies a Lemon Squeezy webhook: hex HMAC of the raw body. */
export function verifyLemonSqueezy(raw, header, secret) {
  if (!secret || typeof header !== "string") return false;
  return hexEqual(header, hmacHex(secret, raw));
}

/**
 * Translates a Stripe event into a premium change
 * ({ id, active, accountId?, email?, customerId?, subscriptionId?, until? }),
 * or null when the event does not affect premium.
 */
export function fromStripe(event) {
  const object = event?.data?.object ?? {};
  switch (event?.type) {
    case "checkout.session.completed":
    case "checkout.session.async_payment_succeeded": {
      const paid = object.payment_status === "paid" || object.payment_status === "no_payment_required";
      if (!paid) return null;
      return {
        id: event.id,
        active: true,
        accountId: object.client_reference_id ?? null,
        email: object.customer_details?.email ?? object.customer_email ?? null,
        customerId: typeof object.customer === "string" ? object.customer : null,
        subscriptionId: typeof object.subscription === "string" ? object.subscription : null,
      };
    }
    case "customer.subscription.updated":
    case "customer.subscription.deleted": {
      const stillLive = event.type === "customer.subscription.updated" &&
        ["active", "trialing", "past_due"].includes(object.status);
      return {
        id: event.id,
        active: stillLive,
        customerId: typeof object.customer === "string" ? object.customer : null,
        subscriptionId: object.id ?? null,
      };
    }
    default:
      return null;
  }
}

/**
 * The Lemon Squeezy equivalent. Event ids are synthesised from the body
 * hash (the payload carries none we can trust), and a cancelled
 * subscription stays premium until its paid period actually ends.
 */
export function fromLemonSqueezy(event, raw) {
  const eventName = event?.meta?.event_name;
  const attributes = event?.data?.attributes ?? {};
  const common = {
    id: crypto.createHash("sha256").update(raw).digest("hex"),
    accountId: event?.meta?.custom_data?.account_id ?? null,
    email: attributes.user_email ?? null,
    customerId: attributes.customer_id != null ? String(attributes.customer_id) : null,
  };

  if (eventName === "order_created") {
    return attributes.status === "paid" ? { ...common, active: true } : null;
  }
  if (eventName === "order_refunded") return { ...common, active: false };
  if (!eventName?.startsWith("subscription_") || eventName.startsWith("subscription_payment")) {
    return null;
  }

  const subscriptionId = event?.data?.id != null ? String(event.data.id) : null;
  // Shown back to the player as a link, so only ever an https address.
  const manageUrl = /^https:\/\//.test(attributes.urls?.customer_portal ?? "")
    ? attributes.urls.customer_portal
    : null;
  const status = attributes.status;

  if (["active", "on_trial", "past_due"].includes(status)) {
    return { ...common, active: true, subscriptionId, manageUrl };
  }
  if (status === "cancelled" && attributes.ends_at && Date.parse(attributes.ends_at) > Date.now()) {
    return { ...common, active: true, until: Date.parse(attributes.ends_at), subscriptionId, manageUrl };
  }
  return { ...common, active: false, subscriptionId, manageUrl };
}

/**
 * Webhook bookkeeping persisted through the store: event ids already
 * applied (providers retry deliveries), when each provider was last heard
 * from, and payments that matched no account for an admin to apply by hand.
 */
export function createLedger(store) {
  const state = store.read("billing.json", { seen: [], last: {}, unclaimed: [] });
  const persist = () => store.write("billing.json", state);

  return {
    seen: (id) => state.seen.includes(id),

    mark(provider, id) {
      state.seen = [...state.seen, id].slice(-1000);
      state.last[provider] = Date.now();
      persist();
    },

    last: () => ({ ...state.last }),

    unclaimed: () => [...state.unclaimed],

    addUnclaimed(provider, entry) {
      state.unclaimed.push({
        id: `p_${crypto.randomBytes(6).toString("hex")}`,
        provider,
        email: entry.email ?? null,
        customerId: entry.customerId ?? null,
        subscriptionId: entry.subscriptionId ?? null,
        manageUrl: entry.manageUrl ?? null,
        until: entry.until ?? null,
        atMs: Date.now(),
      });
      state.unclaimed = state.unclaimed.slice(-200);
      persist();
    },

    takeUnclaimed(id) {
      const index = state.unclaimed.findIndex((entry) => entry.id === id);
      if (index < 0) return null;
      const [taken] = state.unclaimed.splice(index, 1);
      persist();
      return taken;
    },
  };
}
