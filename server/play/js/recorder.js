// The Record challenge: a message for the next runner, kept in this
// browser only (IndexedDB), mirroring how the app keeps it on the phone.

const DB_NAME = "ghostsignal";
const STORE_NAME = "recordings";

function saveRecording(key, blob) {
  return new Promise((resolve, reject) => {
    const openRequest = indexedDB.open(DB_NAME, 1);
    openRequest.onupgradeneeded = () => {
      openRequest.result.createObjectStore(STORE_NAME);
    };
    openRequest.onerror = () => reject(openRequest.error);
    openRequest.onsuccess = () => {
      const transaction = openRequest.result.transaction(STORE_NAME, "readwrite");
      transaction.objectStore(STORE_NAME).put(blob, key);
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    };
  });
}

export class Recorder {
  constructor() {
    this.mediaRecorder = null;
    this.stopTimer = null;
  }

  /** Records up to [maxSeconds]; onDone receives a stored key or null. */
  async start(signalId, maxSeconds, onDone) {
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch {
      onDone(null);
      return;
    }

    const chunks = [];
    const recorder = new MediaRecorder(stream);
    this.mediaRecorder = recorder;

    recorder.ondataavailable = (event) => {
      if (event.data.size) chunks.push(event.data);
    };
    recorder.onstop = async () => {
      stream.getTracks().forEach((track) => track.stop());
      clearTimeout(this.stopTimer);
      this.mediaRecorder = null;
      if (!chunks.length) {
        onDone(null);
        return;
      }
      const key = `${signalId}-${Date.now()}`;
      try {
        await saveRecording(key, new Blob(chunks, { type: recorder.mimeType || "audio/webm" }));
        onDone(`idb:${key}`);
      } catch {
        onDone(null);
      }
    };

    recorder.start();
    this.stopTimer = setTimeout(() => this.stop(), maxSeconds * 1000);
  }

  stop() {
    if (this.mediaRecorder && this.mediaRecorder.state !== "inactive") {
      this.mediaRecorder.stop();
    }
  }
}
