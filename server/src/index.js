import { createAccounts } from "./accounts.js";
import { createAdmin } from "./admin.js";
import { loadEnvFile } from "./env.js";
import { loadMission } from "./mission.js";
import { createMeta } from "./meta.js";
import { createNpc } from "./npc.js";
import { createServer } from "./server.js";
import { createStore } from "./store.js";
import { createStt } from "./stt.js";
import { createTts } from "./tts.js";

// Keys dropped in server/.env (see .env.example) count; real environment variables win.
const envFileKeys = loadEnvFile();
if (envFileKeys.length) console.log(`loaded ${envFileKeys.length} setting(s) from .env`);

const mission = loadMission();
const meta = createMeta();
const npc = createNpc({ meta });
const stt = createStt({ meta });
const tts = createTts();
const eventToken = process.env.EVENT_TOKEN ?? "";
const operatorToken = process.env.OPERATOR_TOKEN ?? "";

if (!eventToken) console.warn("EVENT_TOKEN is not set: anyone who finds this server can use the character agent and speech transcription.");
const datarailApi = process.env.DATARAIL_API ?? "";
if (!operatorToken && !datarailApi) console.warn("Neither OPERATOR_TOKEN nor DATARAIL_API is set: nobody can sign in to /admin, publish routes or send broadcasts.");
console.log(`mission ${mission.id} · characters ${npc.enabled ? `on ${npc.model}` : "OFF (no MODEL_API_KEY)"} · speech ${stt.enabled ? `on ${stt.model}` : "OFF (no MODEL_API_KEY)"} · voices on-device · admin sign-in ${["admin accounts", operatorToken && "operator token", datarailApi && "DataRail admins"].filter(Boolean).join(" + ") || "OFF"}`);

const list = (v) => (v ?? "").split(",").map((s) => s.trim()).filter(Boolean);
const store = createStore();
const accounts = createAccounts({ dir: store.dir, adminEmails: list(process.env.ADMIN_EMAILS) });
const admin = createAdmin({ operatorToken, datarailApi, accounts });
const billing = {
  defaults: {
    paymentLink: process.env.PAYMENT_LINK_URL ?? "",
    priceLabel: process.env.PREMIUM_PRICE_LABEL ?? "",
    manageBillingUrl: process.env.MANAGE_BILLING_URL ?? "",
  },
  secrets: { stripe: process.env.STRIPE_WEBHOOK_SECRET ?? "", lemonsqueezy: process.env.LEMONSQUEEZY_WEBHOOK_SECRET ?? "" },
  limits: {
    signup: Number(process.env.SIGNUPS_PER_MINUTE) || undefined,
    login: Number(process.env.LOGINS_PER_MINUTE) || undefined,
  },
};
console.log(`accounts: ${accounts.counts().total} · admins by email: ${list(process.env.ADMIN_EMAILS).length} · payment webhooks: ${["stripe", "lemonsqueezy"].filter((k) => billing.secrets[k]).join(", ") || "none"}`);

const port = Number(process.env.PORT || 8080);
// HOST=127.0.0.1 when a reverse proxy on the same machine is the only way in.
const host = process.env.HOST || undefined;
const assetsDir = process.env.ASSETS_DIR || undefined;
const trustProxy = /^(1|true|yes)$/i.test(process.env.TRUST_PROXY ?? "");
const server = createServer({ mission, npc, tts, stt, store, eventToken, operatorToken, accounts, admin, billing, assetsDir, trustProxy });
server.listen(port, host, () => {
  console.log(`GhostSignal backend listening on ${host ?? ""}:${port}${trustProxy ? " · client addresses from X-Forwarded-For" : ""}`);
});
