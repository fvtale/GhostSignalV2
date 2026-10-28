/**
 * Meta Model API (https://api.meta.ai/v1): one key covers Muse Spark chat — the character
 * replies — and Muse Voice Transcribe — the runner's speech. Chat is OpenAI-shaped;
 * transcription is a multipart POST of a mono integer-PCM WAV at 16 or 24 kHz (the recorded
 * clip is the turn: PUSH_TO_TALK). Docs: https://dev.meta.ai/docs/api-reference
 */
export const DEFAULT_CHAT_MODEL = "muse-spark-1.3";
export const DEFAULT_STT_MODEL = "muse-voice-transcribe-1.0";

const fail = (status, message) => Object.assign(new Error(message), { status });

export function createMeta({
  apiKey = process.env.MODEL_API_KEY || process.env.META_API_KEY || "",
  baseUrl = process.env.META_API_BASE || "https://api.meta.ai/v1",
  fetchImpl = globalThis.fetch,
  log = console,
} = {}) {
  const base = String(baseUrl).replace(/\/+$/, "");

  async function call(pathName, init) {
    let res;
    try {
      res = await fetchImpl(`${base}${pathName}`, {
        ...init,
        headers: { authorization: `Bearer ${apiKey}`, ...(init.headers ?? {}) },
        signal: AbortSignal.timeout(20_000),
      });
    } catch (e) {
      throw fail(502, `Meta Model API unreachable: ${e.message}`);
    }
    if (!res.ok) {
      const detail = await res.text().catch(() => "");
      log.warn?.(`meta ${pathName} ${res.status}: ${detail.slice(0, 200)}`);
      throw fail(res.status === 429 ? 429 : 502, `Meta Model API returned ${res.status}`);
    }
    return res;
  }

  return {
    enabled: Boolean(apiKey),

    /** One character reply: system persona + the per-request game state, as a chat completion. */
    async chat({ model, system, user, effort = "low" }) {
      const res = await call("/chat/completions", {
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
      const data = await res.json();
      return data.choices?.[0]?.message?.content ?? "";
    },

    /** Transcribes a mono PCM16 WAV clip (16/24 kHz). Returns the transcript text. */
    async transcribe(wav, { model = DEFAULT_STT_MODEL, keywords = [] } = {}) {
      const form = new FormData();
      const request = { model, audioEncoding: "WAV", mode: "PUSH_TO_TALK", ...(keywords.length ? { keywords } : {}) };
      form.append("request", new Blob([JSON.stringify(request)], { type: "application/json" }));
      form.append("audio", new Blob([wav], { type: "audio/wav" }), "speech.wav");
      const res = await call("/asr/transcribe", { method: "POST", headers: { accept: "application/json" }, body: form });
      const data = await res.json();
      return typeof data.transcript === "string" ? data.transcript : "";
    },
  };
}
