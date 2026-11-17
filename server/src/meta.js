// Client for the Meta Model API (https://api.meta.ai/v1). One key powers
// both Muse Spark chat (character replies) and Muse Voice transcription
// (runner speech). Chat completions are OpenAI-shaped; transcription is a
// multipart POST of a mono PCM WAV (16 or 24 kHz) treated as one
// push-to-talk turn. Reference: https://dev.meta.ai/docs/api-reference

export const DEFAULT_CHAT_MODEL = "muse-spark-1.3";
export const DEFAULT_STT_MODEL = "muse-voice-transcribe-1.0";

const API_TIMEOUT_MS = 20_000;

function apiError(status, message) {
  return Object.assign(new Error(message), { status });
}

export function createMeta({
  apiKey = process.env.MODEL_API_KEY || process.env.META_API_KEY || "",
  baseUrl = process.env.META_API_BASE || "https://api.meta.ai/v1",
  fetchImpl = globalThis.fetch,
  log = console,
} = {}) {
  const root = String(baseUrl).replace(/\/+$/, "");
  const configured = Boolean(apiKey);

  async function request(pathname, init) {
    let response;
    try {
      response = await fetchImpl(`${root}${pathname}`, {
        ...init,
        headers: { authorization: `Bearer ${apiKey}`, ...(init.headers ?? {}) },
        signal: AbortSignal.timeout(API_TIMEOUT_MS),
      });
    } catch (cause) {
      throw apiError(502, `Meta Model API unreachable: ${cause.message}`);
    }
    if (!response.ok) {
      const detail = await response.text().catch(() => "");
      log.warn?.(`meta ${pathname} ${response.status}: ${detail.slice(0, 200)}`);
      // Rate limiting is the caller's signal to back off; everything else
      // upstream is presented as a bad gateway.
      throw apiError(response.status === 429 ? 429 : 502, `Meta Model API returned ${response.status}`);
    }
    return response;
  }

  return {
    enabled: configured,

    /** One character reply from a system persona plus a user turn. */
    async chat({ model, system, user, effort = "low" }) {
      const response = await request("/chat/completions", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          model,
          messages: [
            { role: "system", content: system },
            { role: "user", content: user },
          ],
          temperature: 0.7,
          max_completion_tokens: 400,
          reasoning_effort: effort,
        }),
      });
      const payload = await response.json();
      return payload.choices?.[0]?.message?.content ?? "";
    },

    /** Transcribes one mono PCM16 WAV clip; returns the transcript text. */
    async transcribe(wav, { model = DEFAULT_STT_MODEL, keywords = [] } = {}) {
      const requestJson = {
        model,
        audioEncoding: "WAV",
        mode: "PUSH_TO_TALK",
        ...(keywords.length ? { keywords } : {}),
      };
      const form = new FormData();
      form.append("request", new Blob([JSON.stringify(requestJson)], { type: "application/json" }));
      form.append("audio", new Blob([wav], { type: "audio/wav" }), "speech.wav");
      const response = await request("/asr/transcribe", {
        method: "POST",
        headers: { accept: "application/json" },
        body: form,
      });
      const payload = await response.json();
      return typeof payload.transcript === "string" ? payload.transcript : "";
    },
  };
}
