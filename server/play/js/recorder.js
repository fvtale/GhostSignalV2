/**
 * The Record challenge: a message for the next runner, kept in this browser only (IndexedDB),
 * like the app keeps it on the phone.
 */
export class Recorder {
  constructor() {
    this.media = null;
    this.stopTimer = null;
  }

  /** Records up to [maxSeconds]; [onDone] gets a stored key, or null if nothing was recorded. */
  async start(signalId, maxSeconds, onDone) {
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch {
      return onDone(null);
    }
    const chunks = [];
    const media = new MediaRecorder(stream);
    this.media = media;
    media.ondataavailable = (e) => e.data.size && chunks.push(e.data);
    media.onstop = async () => {
      stream.getTracks().forEach((t) => t.stop());
      clearTimeout(this.stopTimer);
      this.media = null;
      if (!chunks.length) return onDone(null);
      const key = `${signalId}-${Date.now()}`;
      try {
        await save(key, new Blob(chunks, { type: media.mimeType || "audio/webm" }));
        onDone(`idb:${key}`);
      } catch {
        onDone(null);
      }
    };
    media.start();
    this.stopTimer = setTimeout(() => this.stop(), maxSeconds * 1000);
  }

  stop() {
    if (this.media && this.media.state !== "inactive") this.media.stop();
  }
}

function save(key, blob) {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open("ghostsignal", 1);
    open.onupgradeneeded = () => open.result.createObjectStore("recordings");
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
      const tx = open.result.transaction("recordings", "readwrite");
      tx.objectStore("recordings").put(blob, key);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    };
  });
}
