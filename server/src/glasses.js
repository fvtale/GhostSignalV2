import crypto from "node:crypto";

// Linking Meta glasses to player accounts. An approved link makes its
// account premium; admins can also enrol a pair in advance (event glasses),
// in which case linking is instant.
//
// Why a human approves: the Meta Wearables Device Access Toolkit gives the
// app an opaque device identifier, model, firmware, and owner-chosen name,
// but no serial number and nothing signed by Meta. The server therefore
// cannot prove a request came from real glasses. What it can do is keep
// one pair bound to one account, and show an admin the evidence.
//
// Identifiers are never stored raw. Only a SHA-256 of the identifier with
// a fixed app-specific prefix is kept. That prefix is a pepper, not a
// per-pair salt: the hash is deliberately deterministic so the same pair
// always shows the same short fingerprint. Treat it as a pseudonymous id,
// never as a secret.

const MAX_PENDING_PER_ACCOUNT = 3;
const IDENTIFIER_PREFIX = "ghostsignal-glasses:";

function linkError(status, message) {
  return Object.assign(new Error(message), { status });
}

const trimmed = (value, max) => (typeof value === "string" ? value.trim().slice(0, max) : "");

export function createGlasses({ store, accounts, now = Date.now }) {
  const state = store.read("glasses.json", { links: [], enrolled: [] });
  const persist = () => store.write("glasses.json", state);

  const hashIdentifier = (identifier) =>
    crypto.createHash("sha256").update(`${IDENTIFIER_PREFIX}${identifier}`).digest("hex");
  const fingerprintOf = (idHash) => `${idHash.slice(0, 4)}-${idHash.slice(4, 8)}`.toUpperCase();

  function describe(link) {
    return {
      id: link.id,
      status: link.status,
      deviceType: link.deviceType,
      name: link.name,
      firmware: link.firmware,
      fingerprint: fingerprintOf(link.idHash),
      requestedAtMs: link.requestedAtMs,
      decidedAtMs: link.decidedAtMs ?? null,
      decidedBy: link.decidedBy ?? null,
    };
  }

  /** Validates a link/enrol submission and reduces it to stored evidence. */
  function evidenceFrom(body) {
    const identifier = typeof body.identifier === "string" ? body.identifier.trim() : "";
    if (identifier.length < 4 || identifier.length > 256) {
      throw linkError(400, "connect your glasses through the Meta AI app first");
    }
    if (body.mock === true) throw linkError(400, "simulated glasses can't be linked");
    return {
      idHash: hashIdentifier(identifier),
      deviceType: trimmed(body.deviceType, 40) || "UNKNOWN",
      name: trimmed(body.name, 80),
      firmware: trimmed(body.firmware, 80),
    };
  }

  const approvedOwnerOf = (idHash) =>
    state.links.find((link) => link.idHash === idHash && link.status === "approved")?.accountId ?? null;

  const isEnrolled = (idHash) => state.enrolled.some((entry) => entry.idHash === idHash);

  return {
    /** True when this account holds at least one approved pair. */
    premiumFor: (accountId) =>
      state.links.some((link) => link.accountId === accountId && link.status === "approved"),

    /** A player asks to link the glasses connected to their phone. */
    request(account, body) {
      const evidence = evidenceFrom(body);
      const existingOwner = approvedOwnerOf(evidence.idHash);
      if (existingOwner && existingOwner !== account.id) {
        throw linkError(409, "these glasses are linked to another account. Ask an admin to move them.");
      }

      let link = state.links.find(
        (entry) => entry.accountId === account.id && entry.idHash === evidence.idHash,
      );
      if (link?.status === "approved") return describe(link);
      if (link?.status === "rejected") {
        throw linkError(403, "an admin didn't approve this pair. Contact DataRail if that's a mistake.");
      }

      const pendingCount = state.links.filter(
        (entry) => entry.accountId === account.id && entry.status === "pending",
      ).length;
      if (!link && pendingCount >= MAX_PENDING_PER_ACCOUNT) {
        throw linkError(429, "you already have glasses waiting for approval");
      }

      if (!link) {
        link = {
          id: `g_${crypto.randomBytes(6).toString("hex")}`,
          accountId: account.id,
          idHash: evidence.idHash,
        };
        state.links.push(link);
      }
      Object.assign(link, evidence, {
        requestedAtMs: now(),
        status: "pending",
        decidedAtMs: null,
        decidedBy: null,
      });
      // Pre-enrolled pairs were already vouched for: link immediately.
      if (isEnrolled(evidence.idHash)) {
        Object.assign(link, { status: "approved", decidedAtMs: now(), decidedBy: "enrolled" });
      }
      persist();
      return describe(link);
    },

    mine: (accountId) =>
      state.links.filter((link) => link.accountId === accountId).map(describe),

    unlink(accountId, id) {
      const before = state.links.length;
      state.links = state.links.filter(
        (link) => !(link.id === id && link.accountId === accountId),
      );
      if (state.links.length === before) throw linkError(404, "no such glasses on your account");
      persist();
    },

    /** Removes every link belonging to a deleted account. */
    forget(accountId) {
      state.links = state.links.filter((link) => link.accountId !== accountId);
      persist();
    },

    // -- admin -----------------------------------------------------------------

    /** All links, newest first, with owner email and conflict flags. */
    list() {
      const newestFirst = [...state.links].sort((a, b) => b.requestedAtMs - a.requestedAtMs);
      return newestFirst.map((link) => {
        const rivals = state.links.filter(
          (other) => other.idHash === link.idHash && other.accountId !== link.accountId,
        );
        const emailOf = (accountId) => accounts.get(accountId)?.email ?? "(deleted account)";
        return {
          ...describe(link),
          accountId: link.accountId,
          email: emailOf(link.accountId),
          enrolled: isEnrolled(link.idHash),
          alsoRequestedBy: rivals.map((rival) => emailOf(rival.accountId)),
        };
      });
    },

    decide(id, status, who) {
      const allowed = ["approved", "rejected", "revoked", "pending"];
      if (!allowed.includes(status)) {
        throw linkError(400, "status is approved, rejected, revoked or pending");
      }
      const link = state.links.find((entry) => entry.id === id);
      if (!link) throw linkError(404, "no such glasses link");
      if (status === "approved") {
        const currentOwner = approvedOwnerOf(link.idHash);
        if (currentOwner && currentOwner !== link.accountId) {
          throw linkError(409, "this pair is approved on another account; revoke that one first");
        }
      }
      Object.assign(link, { status, decidedAtMs: now(), decidedBy: who });
      persist();
      return describe(link);
    },

    enrolled: () => state.enrolled.map((entry) => ({
      id: entry.id,
      fingerprint: fingerprintOf(entry.idHash),
      deviceType: entry.deviceType,
      name: entry.name,
      note: entry.note,
      atMs: entry.atMs,
      by: entry.by,
    })),

    /** An admin vouches for a pair in advance, from a phone wearing it. */
    enroll(body, who) {
      const evidence = evidenceFrom(body);
      let entry = state.enrolled.find((candidate) => candidate.idHash === evidence.idHash);
      if (!entry) {
        entry = { id: `e_${crypto.randomBytes(6).toString("hex")}`, idHash: evidence.idHash };
        state.enrolled.push(entry);
      }
      Object.assign(entry, {
        deviceType: evidence.deviceType,
        name: evidence.name,
        note: trimmed(body.note, 120),
        atMs: now(),
        by: who,
      });
      // Approve anyone already waiting on this pair, unless it is approved
      // on a different account (that conflict stays for a human).
      if (!approvedOwnerOf(evidence.idHash)) {
        const waiting = state.links.find(
          (link) => link.idHash === evidence.idHash && link.status === "pending",
        );
        if (waiting) {
          Object.assign(waiting, { status: "approved", decidedAtMs: now(), decidedBy: "enrolled" });
        }
      }
      persist();
      return { id: entry.id, fingerprint: fingerprintOf(entry.idHash) };
    },

    unenroll(id) {
      const before = state.enrolled.length;
      state.enrolled = state.enrolled.filter((entry) => entry.id !== id);
      if (state.enrolled.length === before) throw linkError(404, "no such enrolled pair");
      persist();
    },
  };
}
