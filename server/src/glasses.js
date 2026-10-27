import crypto from "node:crypto";

function fail(status, message) {
  return Object.assign(new Error(message), { status });
}

const MAX_PENDING = 3;
const text = (v, max) => (typeof v === "string" ? v.trim().slice(0, max) : "");

/**
 * Meta glasses linked to player accounts. A linked pair makes its account premium once an admin
 * approves it, or straight away if an admin enrolled that pair in advance (event glasses, say).
 *
 * Why a person decides: the Meta Wearables Device Access Toolkit gives the app an opaque device
 * identifier, the model, the firmware and the name the owner gave the glasses, but no serial number
 * and nothing signed by Meta. The app reports those, so the server can't prove a request came from
 * real glasses. What it can do: keep one pair to one account, and show the admin the evidence.
 *
 * Identifiers are stored only as salted hashes; admins see a short fingerprint of each.
 */
export function createGlasses({ store, accounts, now = Date.now }) {
  const state = store.read("glasses.json", { links: [], enrolled: [] });
  const save = () => store.write("glasses.json", state);
  const hash = (identifier) => crypto.createHash("sha256").update(`ghostsignal-glasses:${identifier}`).digest("hex");
  const fingerprint = (h) => `${h.slice(0, 4)}-${h.slice(4, 8)}`.toUpperCase();

  function view(link) {
    return {
      id: link.id,
      status: link.status,
      deviceType: link.deviceType,
      name: link.name,
      firmware: link.firmware,
      fingerprint: fingerprint(link.idHash),
      requestedAtMs: link.requestedAtMs,
      decidedAtMs: link.decidedAtMs ?? null,
      decidedBy: link.decidedBy ?? null,
    };
  }

  function evidence(body) {
    const identifier = typeof body.identifier === "string" ? body.identifier.trim() : "";
    if (identifier.length < 4 || identifier.length > 256) throw fail(400, "connect your glasses through the Meta AI app first");
    if (body.mock === true) throw fail(400, "simulated glasses can't be linked");
    return {
      idHash: hash(identifier),
      deviceType: text(body.deviceType, 40) || "UNKNOWN",
      name: text(body.name, 80),
      firmware: text(body.firmware, 80),
    };
  }

  /** Whose account these glasses unlock premium for, if anyone's. */
  const approvedOwner = (idHash) => state.links.find((l) => l.idHash === idHash && l.status === "approved")?.accountId ?? null;

  return {
    /** Premium from glasses: this account has an approved pair. */
    premiumFor: (accountId) => state.links.some((l) => l.accountId === accountId && l.status === "approved"),

    /** A player links the glasses connected to their phone. */
    request(account, body) {
      const e = evidence(body);
      const owner = approvedOwner(e.idHash);
      if (owner && owner !== account.id) throw fail(409, "these glasses are linked to another account. Ask an admin to move them.");
      let link = state.links.find((l) => l.accountId === account.id && l.idHash === e.idHash);
      if (link?.status === "approved") return view(link);
      if (link?.status === "rejected") throw fail(403, "an admin didn't approve this pair. Contact DataRail if that's a mistake.");
      if (!link && state.links.filter((l) => l.accountId === account.id && l.status === "pending").length >= MAX_PENDING) {
        throw fail(429, "you already have glasses waiting for approval");
      }
      if (!link) {
        link = { id: `g_${crypto.randomBytes(6).toString("hex")}`, accountId: account.id, idHash: e.idHash };
        state.links.push(link);
      }
      Object.assign(link, e, { requestedAtMs: now(), status: "pending", decidedAtMs: null, decidedBy: null });
      // A pair an admin enrolled is already checked: it links at once.
      if (state.enrolled.some((x) => x.idHash === e.idHash)) Object.assign(link, { status: "approved", decidedAtMs: now(), decidedBy: "enrolled" });
      save();
      return view(link);
    },

    mine: (accountId) => state.links.filter((l) => l.accountId === accountId).map(view),

    unlink(accountId, id) {
      const before = state.links.length;
      state.links = state.links.filter((l) => !(l.id === id && l.accountId === accountId));
      if (state.links.length === before) throw fail(404, "no such glasses on your account");
      save();
    },

    /** Drops every link of a deleted account. */
    forget(accountId) {
      state.links = state.links.filter((l) => l.accountId !== accountId);
      save();
    },

    // ---------------------------------------------------------------- admin

    /** Every link, newest first, with the account's email and anything an admin should notice. */
    list() {
      return [...state.links]
        .sort((a, b) => b.requestedAtMs - a.requestedAtMs)
        .map((l) => {
          const others = state.links.filter((o) => o.idHash === l.idHash && o.accountId !== l.accountId);
          return {
            ...view(l),
            accountId: l.accountId,
            email: accounts.get(l.accountId)?.email ?? "(deleted account)",
            enrolled: state.enrolled.some((x) => x.idHash === l.idHash),
            alsoRequestedBy: others.map((o) => accounts.get(o.accountId)?.email ?? "(deleted account)"),
          };
        });
    },

    decide(id, status, who) {
      if (!["approved", "rejected", "revoked", "pending"].includes(status)) throw fail(400, "status is approved, rejected, revoked or pending");
      const link = state.links.find((l) => l.id === id);
      if (!link) throw fail(404, "no such glasses link");
      if (status === "approved") {
        const owner = approvedOwner(link.idHash);
        if (owner && owner !== link.accountId) throw fail(409, "this pair is approved on another account; revoke that one first");
      }
      Object.assign(link, { status, decidedAtMs: now(), decidedBy: who });
      save();
      return view(link);
    },

    enrolled: () => state.enrolled.map((x) => ({ id: x.id, fingerprint: fingerprint(x.idHash), deviceType: x.deviceType, name: x.name, note: x.note, atMs: x.atMs, by: x.by })),

    /** An admin vouches for a pair in advance, from the app with that pair connected. */
    enroll(body, who) {
      const e = evidence(body);
      let x = state.enrolled.find((y) => y.idHash === e.idHash);
      if (!x) {
        x = { id: `e_${crypto.randomBytes(6).toString("hex")}`, idHash: e.idHash };
        state.enrolled.push(x);
      }
      Object.assign(x, { deviceType: e.deviceType, name: e.name, note: text(body.note, 120), atMs: now(), by: who });
      // Anyone already waiting on this pair is approved, unless it's approved elsewhere.
      if (!approvedOwner(e.idHash)) {
        const waiting = state.links.find((l) => l.idHash === e.idHash && l.status === "pending");
        if (waiting) Object.assign(waiting, { status: "approved", decidedAtMs: now(), decidedBy: "enrolled" });
      }
      save();
      return { id: x.id, fingerprint: fingerprint(x.idHash) };
    },

    unenroll(id) {
      const before = state.enrolled.length;
      state.enrolled = state.enrolled.filter((x) => x.id !== id);
      if (state.enrolled.length === before) throw fail(404, "no such enrolled pair");
      save();
    },
  };
}
