// GhostSignal admin console. Plain DOM plus the vendored Leaflet map; no
// build step. The tab's session token lives in sessionStorage and travels
// as a Bearer token on every call.
(() => {
  "use strict";

  const SESSION_KEY = "ghostsignal-admin";
  const byId = (id) => document.getElementById(id);

  let auth = null; // { session, who }
  let mission = null; // GET /v1/admin/mission
  let storedRoute = null; // Published venue route (placements + labels)
  let draft = {}; // signalId -> { lat, lon, radiusM, lockRadiusM, name, place }
  let catalog = null; // GET /v1/tracks/:missionId?all=1
  let map = null;
  let pins = {};
  let rings = {};
  let selectedSignalId = null;
  let editorState = null; // { id, isNew, published, stems: {slot: {...}} }

  // -- DOM + storage helpers ------------------------------------------------

  /** Tiny element builder: text/class attrs, on* handlers, plain attrs. */
  function createEl(tag, attrs, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs || {})) {
      if (value === null || value === undefined || value === false) continue;
      if (key === "text") node.textContent = value;
      else if (key === "class") node.className = value;
      else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
      else node.setAttribute(key, value === true ? "" : value);
    }
    for (const child of children) {
      if (child === null || child === undefined || child === false) continue;
      node.append(typeof child === "string" ? document.createTextNode(child) : child);
    }
    return node;
  }

  function saveSession(value) {
    try {
      if (value) sessionStorage.setItem(SESSION_KEY, JSON.stringify(value));
      else sessionStorage.removeItem(SESSION_KEY);
    } catch {
      // Private window: the session lasts until reload.
    }
  }

  function loadSession() {
    try {
      return JSON.parse(sessionStorage.getItem(SESSION_KEY) || "null");
    } catch {
      return null;
    }
  }

  async function api(path, { method = "GET", body, isLogin = false } = {}) {
    const headers = { accept: "application/json" };
    if (auth) headers.authorization = `Bearer ${auth.session}`;

    let payload = body;
    if (payload !== undefined && !(payload instanceof Blob)) {
      headers["content-type"] = "application/json";
      payload = JSON.stringify(payload);
    } else if (payload instanceof Blob) {
      headers["content-type"] = payload.type || "application/octet-stream";
    }

    const response = await fetch(path, { method, headers, body: payload });
    if (response.status === 204) return null;
    const data = await response.json().catch(() => ({}));
    if (response.ok) return data;
    if ((response.status === 401 || response.status === 403) && auth && !isLogin) {
      signOut("Your admin session ended. Sign in again.");
    }
    const error = new Error(data.error || `HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }

  const parseNumber = (value) => {
    const n = parseFloat(value);
    return Number.isFinite(n) ? n : NaN;
  };
  const round6 = (n) => Math.round(n * 1e6) / 1e6;
  const pad2 = (n) => String(n).padStart(2, "0");
  const slugify = (text) => text.toLowerCase().normalize("NFKD")
    .replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 40);
  const loopSecondsFor = (bpm, beatsPerBar, loopBars) => (loopBars * beatsPerBar * 60) / bpm;

  // -- sign in ------------------------------------------------------------------

  const SIGNIN_TABS = {
    account: ["tabAccount", "formAccount"],
    datarail: ["tabDatarail", "formDatarail"],
    token: ["tabToken", "formToken"],
  };

  function showSignIn(message) {
    byId("app").hidden = true;
    byId("who").hidden = true;
    byId("signin").hidden = false;
    byId("signinError").textContent = message || "";
  }

  function selectSignInTab(name) {
    for (const [key, [tabId, formId]] of Object.entries(SIGNIN_TABS)) {
      byId(tabId).setAttribute("aria-selected", String(key === name));
      byId(formId).hidden = key !== name;
    }
    byId("signinError").textContent = "";
  }

  async function submitSignIn(credentials, form) {
    const button = form.querySelector("button");
    button.disabled = true;
    byId("signinError").textContent = "";
    try {
      const data = await api("/v1/admin/login", {
        method: "POST", body: credentials, isLogin: true,
      });
      auth = { session: data.session, who: data.who };
      saveSession(auth);
      form.reset();
      await showApp();
    } catch (error) {
      byId("signinError").textContent = error.message;
    } finally {
      button.disabled = false;
    }
  }

  function signOut(message) {
    if (auth) {
      fetch("/v1/admin/logout", {
        method: "POST",
        headers: { authorization: `Bearer ${auth.session}` },
      }).catch(() => {});
    }
    auth = null;
    saveSession(null);
    showSignIn(message);
  }

  // -- signed-in shell --------------------------------------------------------------

  async function showApp() {
    mission = await api("/v1/admin/mission");
    const [routeResult, catalogResult] = await Promise.all([
      api(`/v1/routes/${mission.id}`).catch((error) => {
        if (error.status === 404) return null;
        throw error;
      }),
      api(`/v1/tracks/${mission.id}?all=1`),
    ]);
    storedRoute = routeResult || { placements: {}, labels: {} };
    catalog = catalogResult;

    byId("signin").hidden = true;
    byId("app").hidden = false;
    byId("who").hidden = false;
    const viaLabel = auth.who.via === "datarail"
      ? " · DataRail admin"
      : auth.who.via === "ghostsignal" ? " · admin" : "";
    byId("whoName").textContent = `${auth.who.display}${viaLabel}`;

    resetDraft();
    renderSignals();
    renderTracks();
    refreshGlassesBadge();
  }

  async function refreshGlassesBadge() {
    try {
      const data = await api("/v1/admin/glasses");
      const pending = data.links.filter((link) => link.status === "pending").length;
      byId("glassesCount").textContent = pending ? `(${pending})` : "";
    } catch {
      // The badge is a nicety; the Glasses tab still loads on demand.
    }
  }

  function switchView(name) {
    document.querySelectorAll("[data-view]").forEach((tab) =>
      tab.setAttribute("aria-selected", String(tab.dataset.view === name)));
    for (const viewName of ["signals", "tracks", "players", "glasses"]) {
      byId(`view-${viewName}`).hidden = viewName !== name;
    }
    if (name === "signals" && map) setTimeout(() => map.invalidateSize(), 0);
    if (name === "players") loadPlayers();
    if (name === "glasses") loadGlasses();
  }

  // -- signals ----------------------------------------------------------------------

  const authoredSignal = (signal) => ({
    lat: signal.location.lat,
    lon: signal.location.lon,
    radiusM: signal.radiusM,
    lockRadiusM: signal.lockRadiusM,
    name: signal.name,
    place: signal.place,
  });

  /** The live signal: authored values with the published route applied. */
  function liveSignal(signal) {
    const authored = authoredSignal(signal);
    const placement = (storedRoute.placements || {})[signal.id];
    const label = (storedRoute.labels || {})[signal.id] || {};
    return {
      lat: placement ? placement.location.lat : authored.lat,
      lon: placement ? placement.location.lon : authored.lon,
      radiusM: placement ? placement.radiusM : authored.radiusM,
      lockRadiusM: placement ? placement.lockRadiusM : authored.lockRadiusM,
      name: label.name || authored.name,
      place: label.place || authored.place,
    };
  }

  const liveName = (signal) => liveSignal(signal).name;

  function resetDraft() {
    draft = {};
    for (const signal of mission.signals) draft[signal.id] = liveSignal(signal);
  }

  const EDITABLE_FIELDS = ["name", "place", "lat", "lon", "radiusM", "lockRadiusM"];

  const fieldChanged = (signal, field) =>
    String(draft[signal.id][field]) !== String(liveSignal(signal)[field]);

  const signalMoved = (signal) => {
    const authored = authoredSignal(signal);
    const current = draft[signal.id];
    return current.lat !== authored.lat || current.lon !== authored.lon;
  };

  function draftProblems() {
    const problems = [];
    mission.signals.forEach((signal, index) => {
      const d = draft[signal.id];
      const label = `Signal ${index + 1}`;
      if (!(Math.abs(d.lat) <= 90 && Math.abs(d.lon) <= 180)) {
        problems.push(`${label} needs a valid position.`);
      }
      if (!(d.radiusM >= 5 && d.radiusM <= 500)) {
        problems.push(`${label}: near radius is 5 to 500 m.`);
      }
      if (!(d.lockRadiusM >= 5 && d.lockRadiusM <= d.radiusM)) {
        problems.push(`${label}: capture radius is 5 m up to its near radius.`);
      }
      if (d.lockRadiusM < 10) {
        problems.push(`${label}: a capture radius under 10 m is unreliable on phone GPS.`);
      }
    });
    return problems;
  }

  function refreshSignalDirty() {
    const anyDirty = mission.signals.some((signal) =>
      EDITABLE_FIELDS.some((field) => fieldChanged(signal, field)));
    const problems = draftProblems();
    byId("signalSave").hidden = !anyDirty;
    byId("signalSaveBtn").disabled =
      problems.some((problem) => !problem.includes("unreliable"));
    byId("signalDirty").textContent = problems.length ? problems[0] : "Unsaved changes";

    for (const signal of mission.signals) {
      const row = document.querySelector(`[data-signal="${signal.id}"]`);
      if (!row) continue;
      for (const field of EDITABLE_FIELDS) {
        row.querySelector(`[name="${field}"]`)
          .classList.toggle("changed", fieldChanged(signal, field));
      }
      const iconElement = pins[signal.id]?.getElement();
      if (iconElement) iconElement.firstChild.classList.toggle("moved", signalMoved(signal));
    }
  }

  const pinIconFor = (index) => L.divIcon({
    className: "",
    html: `<div class="pin">${index + 1}</div>`,
    iconSize: [28, 28],
    iconAnchor: [14, 14],
  });

  function selectSignal(signalId, panToPin) {
    selectedSignalId = signalId;
    document.querySelectorAll(".signal").forEach((row) =>
      row.classList.toggle("selected", row.dataset.signal === signalId));
    for (const [id, pin] of Object.entries(pins)) {
      const iconElement = pin.getElement();
      if (iconElement) iconElement.firstChild.classList.toggle("selected", id === signalId);
    }
    if (panToPin && map && pins[signalId]) map.panTo(pins[signalId].getLatLng());
  }

  function syncPinToDraft(signal) {
    const d = draft[signal.id];
    if (!(Math.abs(d.lat) <= 90 && Math.abs(d.lon) <= 180)) return;
    const position = [d.lat, d.lon];
    pins[signal.id].setLatLng(position);
    rings[signal.id].near.setLatLng(position).setRadius(Math.max(1, d.radiusM || 1));
    rings[signal.id].lock.setLatLng(position).setRadius(Math.max(1, d.lockRadiusM || 1));
  }

  function ensureMap() {
    if (map) return;
    map = L.map("map", { scrollWheelZoom: false });
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19,
      // OSM tile servers block requests without a Referer, and this page
      // sends no-referrer; tiles may send this origin (never the path).
      referrerPolicy: "strict-origin-when-cross-origin",
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    }).addTo(map);
  }

  function renderSignals() {
    const list = byId("signalList");
    list.textContent = "";
    ensureMap();

    for (const id of Object.keys(pins)) {
      pins[id].remove();
      rings[id].near.remove();
      rings[id].lock.remove();
    }
    pins = {};
    rings = {};

    mission.signals.forEach((signal, index) => {
      const current = draft[signal.id];
      rings[signal.id] = {
        near: L.circle([current.lat, current.lon], {
          radius: current.radiusM, color: "#42f5ff", weight: 1, dashArray: "4 6", fillOpacity: 0.04,
        }).addTo(map),
        lock: L.circle([current.lat, current.lon], {
          radius: current.lockRadiusM, color: "#42f5ff", weight: 1.5, fillOpacity: 0.1,
        }).addTo(map),
      };

      const pin = L.marker([current.lat, current.lon], {
        icon: pinIconFor(index), draggable: true, title: current.name, keyboard: false,
      }).addTo(map);
      pin.on("drag", (event) => {
        const point = event.target.getLatLng();
        rings[signal.id].near.setLatLng(point);
        rings[signal.id].lock.setLatLng(point);
      });
      pin.on("dragend", (event) => {
        const point = event.target.getLatLng();
        current.lat = round6(point.lat);
        current.lon = round6(point.lng);
        const row = document.querySelector(`[data-signal="${signal.id}"]`);
        row.querySelector('[name="lat"]').value = current.lat;
        row.querySelector('[name="lon"]').value = current.lon;
        selectSignal(signal.id, false);
        refreshSignalDirty();
      });
      pin.on("click", () => {
        selectSignal(signal.id, false);
        document.querySelector(`[data-signal="${signal.id}"] [name="name"]`).focus();
      });
      pins[signal.id] = pin;

      list.append(buildSignalRow(signal, index, pin));
    });

    const bounds = L.latLngBounds(
      mission.signals.map((signal) => [draft[signal.id].lat, draft[signal.id].lon]),
    );
    map.fitBounds(bounds.pad(0.25));
    setTimeout(() => map.invalidateSize(), 0);
    refreshSignalDirty();
    if (selectedSignalId) selectSignal(selectedSignalId, false);
  }

  function buildSignalRow(signal, index, pin) {
    const current = draft[signal.id];
    const authored = authoredSignal(signal);

    const makeField = (fieldName, labelText, inputType, extraAttrs) => {
      const input = createEl("input", {
        name: fieldName,
        value: current[fieldName],
        type: inputType || "text",
        ...(extraAttrs || {}),
      });
      input.addEventListener("input", () => {
        current[fieldName] = inputType === "number" ? parseNumber(input.value) : input.value;
        if (inputType === "number") syncPinToDraft(signal);
        if (fieldName === "name") pin.options.title = input.value;
        refreshSignalDirty();
      });
      input.addEventListener("focus", () => selectSignal(signal.id, true));
      return createEl("label", null, labelText, input);
    };

    return createEl("li", { class: "signal", "data-signal": signal.id },
      createEl("span", { class: "num" }, pad2(index + 1),
        createEl("small", { text: signal.stem })),
      makeField("name", "Name", "text", { maxlength: 60, placeholder: authored.name }),
      makeField("place", "Place (spoken in hints)", "text", { maxlength: 160, placeholder: authored.place }),
      makeField("lat", "Latitude", "number", { step: "0.000001" }),
      makeField("lon", "Longitude", "number", { step: "0.000001" }),
      makeField("radiusM", "Near m", "number", { step: "1", min: 5, max: 500 }),
      makeField("lockRadiusM", "Capture m", "number", { step: "1", min: 5, max: 500 }),
      createEl("button", {
        type: "button",
        class: "btn ghost small reset",
        text: "As written",
        title: "Back to the position and name in the mission file",
        onclick: () => {
          draft[signal.id] = authoredSignal(signal);
          renderSignals();
          selectSignal(signal.id, true);
        },
      }));
  }

  async function saveSignals() {
    const placements = {};
    const labels = {};
    for (const signal of mission.signals) {
      const authored = authoredSignal(signal);
      const current = draft[signal.id];
      const samePlace = current.lat === authored.lat && current.lon === authored.lon &&
        current.radiusM === authored.radiusM && current.lockRadiusM === authored.lockRadiusM;
      placements[signal.id] = samePlace ? null : {
        location: { lat: current.lat, lon: current.lon },
        radiusM: current.radiusM,
        lockRadiusM: current.lockRadiusM,
      };
      const name = current.name.trim() === authored.name ? "" : current.name.trim();
      const place = current.place.trim() === authored.place ? "" : current.place.trim();
      labels[signal.id] = name || place ? { name, place } : null;
    }

    byId("signalSaveBtn").disabled = true;
    try {
      await api(`/v1/routes/${mission.id}`, {
        method: "PUT",
        body: { missionId: mission.id, name: "Venue route", placements, labels },
      });
      storedRoute = await api(`/v1/routes/${mission.id}`);
      resetDraft();
      renderSignals();
      byId("signalStatus").textContent =
        "Saved. Phones pick it up the next time GhostSignal opens, or when the operator pulls from the server.";
    } catch (error) {
      byId("signalStatus").textContent = `Couldn't save: ${error.message}`;
    } finally {
      byId("signalSaveBtn").disabled = false;
      refreshSignalDirty();
    }
  }

  // -- soundtracks ---------------------------------------------------------------------

  function renderTracks() {
    const deployed = catalog.soundtracks;
    const baseTracks = deployed.filter((t) => t.published && !t.premium && t.unlockAfterCompletions === 0);
    const premiumTracks = deployed.filter((t) => t.published && t.premium);
    const earnedLabels = mission.bundledSoundtracks
      .map((t) => `${t.title} after ${t.unlockAfterCompletions} ${t.unlockAfterCompletions === 1 ? "run" : "runs"}`)
      .concat(deployed
        .filter((t) => t.published && t.unlockAfterCompletions > 0)
        .map((t) => `${t.title} after ${t.unlockAfterCompletions} ${t.unlockAfterCompletions === 1 ? "run" : "runs"}`));

    byId("trackSummary").textContent =
      `Base game: ${1 + baseTracks.length} soundtrack${baseTracks.length ? "s" : ""} ` +
      `(${[mission.music.title || "Original", ...baseTracks.map((t) => t.title)].join(", ")}). ` +
      (earnedLabels.length ? `Earned: ${earnedLabels.join(", ")}. ` : "") +
      (premiumTracks.length ? `Premium: ${premiumTracks.map((t) => t.title).join(", ")}.` : "");

    const list = byId("trackList");
    list.textContent = "";
    list.append(createEl("li", { class: "track" },
      createEl("div", { class: "t" },
        createEl("b", { text: mission.music.title || "Original" }),
        createEl("span", { text: `original · ${mission.music.bpm} BPM · ships with the app` })),
      createEl("span", { class: "badge base", text: "Base" }),
      createEl("span", { class: "badge live", text: "In the app" })));

    for (const bundled of mission.bundledSoundtracks) {
      list.append(createEl("li", { class: "track" },
        createEl("div", { class: "t" },
          createEl("b", { text: bundled.title }),
          createEl("span", { text: `${bundled.id} · ships with the app` })),
        createEl("span", { class: "badge bonus", text: `Bonus · after ${bundled.unlockAfterCompletions}` }),
        createEl("span", { class: "badge live", text: "In the app" })));
    }

    for (const track of deployed) {
      list.append(createEl("li", { class: "track" },
        createEl("div", { class: "t" },
          createEl("b", { text: track.title }),
          createEl("span", {
            text: `${track.id} · ${track.music.bpm} BPM · ${track.music.stems.length}/${mission.signals.length} stems · updated ${new Date(track.updatedAtMs).toLocaleString()}`,
          })),
        createEl("span", {
          class: track.premium ? "badge premium" : track.unlockAfterCompletions ? "badge bonus" : "badge base",
          text: track.premium ? "Premium" : track.unlockAfterCompletions ? `Bonus · after ${track.unlockAfterCompletions}` : "Base",
        }),
        createEl("span", {
          class: track.published ? "badge live" : "badge draft",
          text: track.published ? "Published" : "Draft",
        }),
        createEl("button", {
          type: "button", class: "btn ghost small", text: "Edit",
          onclick: () => openTrackEditor(track),
        }),
        createEl("button", {
          type: "button", class: "btn danger small", text: "Delete",
          onclick: async () => {
            if (!confirm(`Take “${track.title}” down? Phones drop it the next time they sync.`)) return;
            try {
              await api(`/v1/tracks/${mission.id}/${track.id}`, { method: "DELETE" });
              await reloadTracks();
              byId("trackStatus").textContent = `Deleted ${track.title}.`;
            } catch (error) {
              byId("trackStatus").textContent = `Couldn't delete: ${error.message}`;
            }
          },
        })));
    }
  }

  async function reloadTracks() {
    catalog = await api(`/v1/tracks/${mission.id}?all=1`);
    renderTracks();
  }

  const editorForm = () => byId("editor").elements;

  function editorLoopSeconds() {
    const form = editorForm();
    const seconds = loopSecondsFor(
      parseNumber(form.bpm.value),
      parseInt(form.beatsPerBar.value, 10),
      parseInt(form.loopBars.value, 10),
    );
    return Number.isFinite(seconds) && seconds > 0 ? seconds : NaN;
  }

  function openTrackEditor(track) {
    const form = editorForm();
    byId("editor").hidden = false;
    byId("editorError").textContent = "";
    byId("warnings").textContent = "";

    editorState = {
      id: track ? track.id : "",
      isNew: !track,
      published: track ? track.published : false,
      stems: {},
      idTouched: Boolean(track),
    };
    byId("editorTitle").textContent = track ? `Edit “${track.title}”` : "New soundtrack";
    form.title.value = track ? track.title : "";
    form.id.value = track ? track.id : "";
    form.id.readOnly = Boolean(track);
    form.description.value = track ? track.description || "" : "";

    const music = track ? track.music : mission.music;
    form.bpm.value = music.bpm;
    form.beatsPerBar.value = music.beatsPerBar || 4;
    form.loopBars.value = music.loopBars || 8;

    const unlock = track ? track.unlockAfterCompletions : 0;
    form.avail.value = track && track.premium ? "premium" : unlock > 0 ? "bonus" : "base";
    form.unlock.value = unlock > 0 ? unlock : 1;

    if (track) {
      for (const stem of track.music.stems) {
        editorState.stems[stem.id] = {
          blob: stem.file.replace(/^server:/, ""),
          info: { format: stem.format, durationS: stem.durationS, bytes: stem.bytes },
          name: stem.name,
          gainDb: stem.gainDb,
        };
      }
    }
    renderStemRows();
    byId("editor").scrollIntoView({ behavior: "smooth", block: "start" });
  }

  function describeStem(stemState) {
    if (!stemState) return { text: "No file yet", cls: "" };
    if (stemState.uploading) return { text: "Uploading…", cls: "" };
    if (stemState.error) return { text: stemState.error, cls: "bad" };
    const info = stemState.info;
    const summary = `${stemState.original ? `${stemState.original} · ` : ""}${info.durationS.toFixed(2)} s` +
      (info.sampleRate ? ` · ${Math.round(info.sampleRate / 100) / 10} kHz` : "") +
      (info.channels ? (info.channels === 1 ? " mono" : " stereo") : "") +
      ` · ${(info.format || "").toUpperCase()}`;
    const loop = editorLoopSeconds();
    if (!Number.isFinite(loop)) return { text: summary, cls: "" };
    const drift = info.durationS - loop;
    if (Math.abs(drift) <= 0.02) return { text: `${summary} · loops on the beat`, cls: "ok" };
    return {
      text: `${summary} · the loop is ${loop.toFixed(2)} s, so it'll be ${drift > 0 ? "cut short" : "padded with silence"}`,
      cls: "warn",
    };
  }

  function renderStemRows() {
    const list = byId("stemList");
    list.textContent = "";
    mission.signals.forEach((signal, index) => {
      const stemState = editorState.stems[signal.stem];
      const info = describeStem(stemState);

      const fileInput = createEl("input", {
        type: "file",
        accept: ".ogg,.opus,.wav,.flac,audio/ogg,audio/wav,audio/flac",
        "aria-label": `Audio for ${liveName(signal)}`,
      });
      fileInput.addEventListener("change", () => {
        if (fileInput.files[0]) uploadStem(signal, fileInput.files[0]);
      });

      const nameInput = createEl("input", {
        maxlength: 60,
        placeholder: liveName(signal),
        value: stemState && stemState.name !== liveName(signal) ? stemState.name || "" : "",
      });
      nameInput.addEventListener("input", () => {
        if (editorState.stems[signal.stem]) editorState.stems[signal.stem].name = nameInput.value;
      });

      const gainInput = createEl("input", {
        type: "number", min: -30, max: 12, step: 0.5,
        value: stemState ? stemState.gainDb || 0 : 0,
      });
      gainInput.addEventListener("input", () => {
        if (editorState.stems[signal.stem]) {
          editorState.stems[signal.stem].gainDb = parseNumber(gainInput.value) || 0;
        }
      });

      list.append(createEl("li", { class: "stem" },
        createEl("span", { class: "num" }, pad2(index + 1),
          createEl("small", { text: signal.stem })),
        createEl("div", { class: "file" },
          createEl("b", { text: liveName(signal) }),
          fileInput,
          createEl("span", { class: `info ${info.cls}`, text: info.text })),
        createEl("label", null, "Layer name", nameInput),
        createEl("label", null, "Gain dB", gainInput)));
    });
    refreshEditorButtons();
  }

  function refreshEditorButtons() {
    const form = editorForm();
    const loop = editorLoopSeconds();
    byId("loopLength").textContent = Number.isFinite(loop)
      ? `${form.loopBars.value} bars = ${loop.toFixed(2)} s per loop`
      : "";
    const allReady = mission.signals.every((signal) => {
      const stemState = editorState.stems[signal.stem];
      return stemState && stemState.blob && !stemState.uploading && !stemState.error;
    });
    const anyBusy = mission.signals.some((signal) => editorState.stems[signal.stem]?.uploading);
    byId("editorPublish").disabled = !allReady || anyBusy;
    byId("editorDraft").disabled = anyBusy;
    byId("editorPublish").textContent = editorState.published ? "Save and keep published" : "Publish";
    byId("editorDraft").textContent = editorState.published ? "Unpublish (back to draft)" : "Save as draft";
    byId("editorPublish").title = allReady
      ? ""
      : "Every signal needs a stem before the track can be published";
  }

  async function uploadStem(signal, file) {
    const previous = editorState.stems[signal.stem] || {};
    editorState.stems[signal.stem] = { ...previous, uploading: true, error: null };
    renderStemRows();
    try {
      const blob = await api(`/v1/blobs?name=${encodeURIComponent(file.name)}`, {
        method: "PUT", body: file,
      });
      editorState.stems[signal.stem] = {
        blob: blob.name,
        info: blob,
        original: file.name,
        name: previous.name || "",
        gainDb: previous.gainDb || 0,
      };
    } catch (error) {
      editorState.stems[signal.stem] = {
        ...previous, uploading: false, error: `${file.name}: ${error.message}`,
      };
    }
    renderStemRows();
  }

  async function saveTrack(publish) {
    const form = editorForm();
    const trackId = form.id.value.trim();
    const body = {
      title: form.title.value.trim(),
      description: form.description.value.trim(),
      unlockAfterCompletions: form.avail.value === "bonus" ? parseInt(form.unlock.value, 10) : 0,
      premium: form.avail.value === "premium",
      published: publish,
      music: {
        bpm: parseNumber(form.bpm.value),
        beatsPerBar: parseInt(form.beatsPerBar.value, 10),
        loopBars: parseInt(form.loopBars.value, 10),
        stems: mission.signals
          .filter((signal) => editorState.stems[signal.stem]?.blob)
          .map((signal) => {
            const stemState = editorState.stems[signal.stem];
            return {
              id: signal.stem,
              blob: stemState.blob,
              name: (stemState.name || "").trim() || liveName(signal),
              gainDb: stemState.gainDb || 0,
            };
          }),
      },
    };

    byId("editorError").textContent = "";
    byId("warnings").textContent = "";
    if (!/^[a-z0-9][a-z0-9-]{1,39}$/.test(trackId)) {
      byId("editorError").textContent = "The ID is 2 to 40 lowercase letters, digits and dashes.";
      return;
    }
    if (editorState.isNew && catalog.soundtracks.some((t) => t.id === trackId)) {
      byId("editorError").textContent = `There's already a soundtrack called ${trackId}.`;
      return;
    }

    byId("editorPublish").disabled = true;
    byId("editorDraft").disabled = true;
    try {
      const result = await api(`/v1/tracks/${mission.id}/${trackId}`, { method: "PUT", body });
      editorState.isNew = false;
      editorState.id = trackId;
      editorState.published = result.track.published;
      form.id.readOnly = true;
      for (const warning of result.warnings) {
        byId("warnings").append(createEl("li", { text: warning }));
      }
      byId("trackStatus").textContent = result.track.published
        ? `Published ${result.track.title}. Phones download it the next time GhostSignal opens.`
        : `Saved draft of ${result.track.title}.`;
      await reloadTracks();
      if (!result.warnings.length) byId("editor").hidden = true;
    } catch (error) {
      byId("editorError").textContent = error.message;
    } finally {
      refreshEditorButtons();
    }
  }

  // -- players ---------------------------------------------------------------------------

  let playersData = { accounts: [], unclaimed: [], counts: { total: 0, premium: 0, admins: 0 } };
  let playerSearchTimer = null;

  async function loadPlayers() {
    const query = byId("playerSearch").value.trim();
    try {
      const [accountsResult, settingsResult] = await Promise.all([
        api(`/v1/admin/accounts?q=${encodeURIComponent(query)}`),
        api("/v1/admin/settings"),
      ]);
      playersData = accountsResult;
      renderPlayers();
      renderPaymentSettings(settingsResult);
    } catch (error) {
      byId("playerStatus").textContent = `Couldn't load players: ${error.message}`;
    }
  }

  const formatDay = (ms) => (ms ? new Date(ms).toLocaleDateString() : "never");

  function renderPaymentSettings(settingsPayload) {
    const form = byId("payForm").elements;
    form.paymentLink.value = settingsPayload.settings.paymentLink || "";
    form.priceLabel.value = settingsPayload.settings.priceLabel || "";
    form.manageBillingUrl.value = settingsPayload.settings.manageBillingUrl || "";

    const hooks = byId("hooks");
    hooks.textContent = "";
    const provider = settingsPayload.provider;
    hooks.append(createEl("li", {
      text: settingsPayload.settings.paymentLink
        ? `Link: ${provider === "stripe" ? "Stripe Payment Link" : provider === "lemonsqueezy" ? "Lemon Squeezy checkout" : "other provider (grant premium by hand)"}`
        : "No payment link yet: players see “Premium isn't on sale yet”.",
    }));

    const webhookRows = [
      ["stripe", "Stripe", "STRIPE_WEBHOOK_SECRET", "checkout.session.completed, customer.subscription.updated, customer.subscription.deleted"],
      ["lemonsqueezy", "Lemon Squeezy", "LEMONSQUEEZY_WEBHOOK_SECRET", "order_created, order_refunded, subscription_created, subscription_updated, subscription_expired"],
    ];
    for (const [key, label, envName, events] of webhookRows) {
      const webhook = settingsPayload.webhooks[key];
      hooks.append(createEl("li", null,
        createEl("span", {
          class: webhook.configured ? "on" : "off",
          text: `${label} webhook ${webhook.configured ? "✓ " : `– not set (${envName}) `}`,
        }),
        "endpoint ",
        createEl("code", { text: `${location.origin}${webhook.path}` }),
        webhook.configured
          ? ` · last event ${webhook.lastMs ? new Date(webhook.lastMs).toLocaleString() : "none yet"}`
          : "",
        createEl("br"),
        createEl("span", { class: "small", text: `Events: ${events}` })));
    }
  }

  function renderPlayers() {
    const counts = playersData.counts;
    byId("playerCounts").textContent =
      `${counts.total} accounts · ${counts.premium} premium · ${counts.admins} admin${counts.admins === 1 ? "" : "s"}`;

    renderUnclaimed();
    renderAccountRows();
  }

  function renderUnclaimed() {
    const list = byId("unclaimedList");
    list.textContent = "";
    byId("unclaimedCard").hidden = !playersData.unclaimed.length;

    for (const payment of playersData.unclaimed) {
      const picker = createEl("select", { "aria-label": "Account to apply this payment to" },
        createEl("option", { value: "", text: "Choose an account…" }));
      for (const account of playersData.accounts) {
        picker.append(createEl("option", {
          value: account.id,
          text: account.email + (payment.email && account.email === payment.email ? " (same email)" : ""),
        }));
      }
      const emailMatch = playersData.accounts.find(
        (account) => payment.email && account.email === payment.email,
      );
      if (emailMatch) picker.value = emailMatch.id;

      list.append(createEl("li", { class: "track" },
        createEl("div", { class: "t" },
          createEl("b", { text: payment.email || "no email" }),
          createEl("span", {
            text: `${payment.provider} · ${new Date(payment.atMs).toLocaleString()}${payment.customerId ? ` · customer ${payment.customerId}` : ""}`,
          })),
        picker,
        createEl("button", {
          type: "button", class: "btn small", text: "Apply",
          onclick: async () => {
            if (!picker.value) return;
            try {
              await api(`/v1/admin/unclaimed/${payment.id}`, {
                method: "POST", body: { accountId: picker.value },
              });
              await loadPlayers();
              byId("playerStatus").textContent = "Premium applied.";
            } catch (error) {
              byId("playerStatus").textContent = error.message;
            }
          },
        }),
        createEl("button", {
          type: "button", class: "btn ghost small", text: "Dismiss",
          onclick: async () => {
            if (!confirm("Dismiss this payment? Nobody gets premium for it.")) return;
            await api(`/v1/admin/unclaimed/${payment.id}`, { method: "DELETE" });
            await loadPlayers();
          },
        })));
    }
  }

  function renderAccountRows() {
    const list = byId("playerList");
    list.textContent = "";
    if (!playersData.accounts.length) {
      list.append(createEl("li", { class: "muted", text: "No accounts yet." }));
    }

    for (const account of playersData.accounts) {
      const premium = account.premium;
      const finishedRuns = Object.values(account.completions || {})
        .reduce((sum, n) => sum + n, 0);
      const premiumNote = premium.active && premium.source
        ? ` · premium ${premium.source === "grant" ? "granted" : `paid via ${premium.provider}`}`
        : "";

      const applyChange = (change, doneMessage) => async () => {
        try {
          await api(`/v1/admin/accounts/${account.id}`, { method: "PUT", body: change });
          await loadPlayers();
          byId("playerStatus").textContent = doneMessage;
        } catch (error) {
          byId("playerStatus").textContent = error.message;
        }
      };

      list.append(createEl("li", { class: "track" },
        createEl("div", { class: "t" },
          createEl("b", { text: account.email }),
          createEl("span", {
            text: `joined ${formatDay(account.createdAtMs)} · last seen ${formatDay(account.lastSeenMs)} · ${finishedRuns} finished runs${premiumNote}`,
          })),
        account.role === "admin"
          ? createEl("span", {
              class: "badge admin",
              text: account.adminFromConfig ? "Admin · ADMIN_EMAILS" : "Admin",
            })
          : null,
        premium.active
          ? createEl("span", { class: "badge premium", text: "Premium" })
          : createEl("span", { class: "badge", text: "Free" }),
        account.disabled
          ? createEl("span", { class: "badge off", text: "Disabled" })
          : null,
        createEl("button", {
          type: "button", class: "btn ghost small",
          text: premium.active ? "Remove premium" : "Give premium",
          onclick: applyChange(
            { premium: !premium.active },
            premium.active ? `Premium removed from ${account.email}.` : `${account.email} has premium.`),
        }),
        account.adminFromConfig ? null : createEl("button", {
          type: "button", class: "btn ghost small",
          text: account.role === "admin" ? "Remove admin" : "Make admin",
          onclick: applyChange(
            { role: account.role === "admin" ? "player" : "admin" },
            `Updated ${account.email}.`),
        }),
        createEl("button", {
          type: "button",
          class: account.disabled ? "btn ghost small" : "btn danger small",
          text: account.disabled ? "Enable" : "Disable",
          onclick: applyChange(
            { disabled: !account.disabled },
            `${account.disabled ? "Enabled" : "Disabled"} ${account.email}.`),
        })));
    }
  }

  async function savePaymentSettings(event) {
    event.preventDefault();
    const form = byId("payForm").elements;
    byId("payStatus").textContent = "";
    try {
      await api("/v1/admin/settings", {
        method: "PUT",
        body: {
          paymentLink: form.paymentLink.value.trim(),
          priceLabel: form.priceLabel.value.trim(),
          manageBillingUrl: form.manageBillingUrl.value.trim(),
        },
      });
      renderPaymentSettings(await api("/v1/admin/settings"));
      byId("payStatus").textContent = "Saved. Players see it the next time they open their account.";
    } catch (error) {
      byId("payStatus").textContent = error.message;
    }
  }

  // -- glasses ------------------------------------------------------------------------------

  async function loadGlasses() {
    try {
      renderGlasses(await api("/v1/admin/glasses"));
    } catch (error) {
      byId("glassesStatus").textContent = `Couldn't load glasses: ${error.message}`;
    }
  }

  const prettyModel = (deviceType) => (deviceType || "UNKNOWN")
    .replace(/_/g, " ").toLowerCase()
    .replace(/\b\w/g, (ch) => ch.toUpperCase());

  function renderGlasses(data) {
    const pending = data.links.filter((link) => link.status === "pending");
    byId("glassesCount").textContent = pending.length ? `(${pending.length})` : "";

    const decide = (link, status, doneMessage) => async () => {
      try {
        await api(`/v1/admin/glasses/${link.id}`, { method: "PUT", body: { status } });
        await loadGlasses();
        byId("glassesStatus").textContent = doneMessage;
      } catch (error) {
        byId("glassesStatus").textContent = error.message;
      }
    };

    const linkRow = (link, actions) => {
      const timing = link.status === "pending"
        ? `asked ${new Date(link.requestedAtMs).toLocaleString()}`
        : `${link.status === "approved" ? "approved " : link.status === "rejected" ? "turned down " : "removed "}` +
          `${link.decidedAtMs ? new Date(link.decidedAtMs).toLocaleString() : ""}` +
          (link.decidedBy ? ` by ${link.decidedBy}` : "");
      return createEl("li", { class: "track" },
        createEl("div", { class: "t" },
          createEl("b", { text: link.email }),
          createEl("span", {
            text: `${prettyModel(link.deviceType)}${link.name ? ` “${link.name}”` : ""} · ${link.fingerprint}${link.firmware ? ` · firmware ${link.firmware}` : ""} · ${timing}`,
          }),
          link.alsoRequestedBy.length
            ? createEl("span", { class: "warnline", text: `Also asked for by ${link.alsoRequestedBy.join(", ")}` })
            : null,
          link.enrolled
            ? createEl("span", { class: "warnline", text: "This pair is enrolled as event glasses" })
            : null),
        actions);
    };

    const fillList = (elementId, rows, emptyText, buildRow) => {
      const list = byId(elementId);
      list.textContent = "";
      if (!rows.length) list.append(createEl("li", { class: "muted", text: emptyText }));
      for (const row of rows) list.append(buildRow(row));
    };

    fillList("glassesPending", pending, "Nothing waiting.", (link) =>
      linkRow(link, createEl("span", { class: "actions-inline" },
        createEl("button", {
          type: "button", class: "btn small", text: "Approve",
          onclick: decide(link, "approved", `${link.email} has premium.`),
        }),
        createEl("button", {
          type: "button", class: "btn ghost small", text: "Turn down",
          onclick: decide(link, "rejected", `Turned down ${link.email}'s glasses.`),
        }))));

    fillList("glassesLinked",
      data.links.filter((link) => link.status === "approved"),
      "No linked glasses yet.",
      (link) => linkRow(link, createEl("button", {
        type: "button", class: "btn danger small", text: "Revoke",
        onclick: () => {
          if (confirm(`Unlink these glasses from ${link.email}? Their premium from this pair ends.`)) {
            decide(link, "revoked", "Revoked.")();
          }
        },
      })));

    fillList("glassesClosed",
      data.links.filter((link) => link.status === "rejected" || link.status === "revoked"),
      "None.",
      (link) => linkRow(link, createEl("button", {
        type: "button", class: "btn ghost small", text: "Approve",
        onclick: decide(link, "approved", `${link.email} has premium.`),
      })));

    fillList("glassesEnrolled", data.enrolled, "No event glasses enrolled.", (entry) =>
      createEl("li", { class: "track" },
        createEl("div", { class: "t" },
          createEl("b", { text: `${prettyModel(entry.deviceType)}${entry.name ? ` “${entry.name}”` : ""}` }),
          createEl("span", {
            text: `${entry.fingerprint}${entry.note ? ` · ${entry.note}` : ""} · by ${entry.by}`,
          })),
        createEl("button", {
          type: "button", class: "btn ghost small", text: "Remove",
          onclick: async () => {
            try {
              await api(`/v1/admin/glasses/enrolled/${entry.id}`, { method: "DELETE" });
              await loadGlasses();
            } catch (error) {
              byId("glassesStatus").textContent = error.message;
            }
          },
        })));
  }

  // -- wiring --------------------------------------------------------------------------------

  for (const name of Object.keys(SIGNIN_TABS)) {
    byId(SIGNIN_TABS[name][0]).addEventListener("click", () => selectSignInTab(name));
  }
  byId("formAccount").addEventListener("submit", (event) => {
    event.preventDefault();
    submitSignIn(
      { email: event.target.elements.email.value, password: event.target.elements.password.value },
      event.target,
    );
  });
  byId("formDatarail").addEventListener("submit", (event) => {
    event.preventDefault();
    submitSignIn(
      {
        provider: "datarail",
        email: event.target.elements.email.value,
        password: event.target.elements.password.value,
      },
      event.target,
    );
  });
  byId("formToken").addEventListener("submit", (event) => {
    event.preventDefault();
    submitSignIn({ token: event.target.elements.token.value }, event.target);
  });
  byId("signOut").addEventListener("click", () => signOut(""));

  document.querySelectorAll("[data-view]").forEach((tab) =>
    tab.addEventListener("click", () => switchView(tab.dataset.view)));

  byId("signalSaveBtn").addEventListener("click", saveSignals);
  byId("signalDiscard").addEventListener("click", () => {
    resetDraft();
    renderSignals();
    byId("signalStatus").textContent = "";
  });

  byId("newTrack").addEventListener("click", () => openTrackEditor(null));
  byId("editorCancel").addEventListener("click", () => {
    byId("editor").hidden = true;
    editorState = null;
  });
  byId("editorDraft").addEventListener("click", () => saveTrack(false));
  byId("editorPublish").addEventListener("click", () => saveTrack(true));
  editorForm().title.addEventListener("input", () => {
    if (editorState && editorState.isNew && !editorState.idTouched) {
      editorForm().id.value = slugify(editorForm().title.value);
    }
  });
  editorForm().id.addEventListener("input", () => {
    if (editorState) editorState.idTouched = true;
  });
  for (const field of ["bpm", "beatsPerBar", "loopBars"]) {
    editorForm()[field].addEventListener("input", renderStemRows);
  }
  editorForm().unlock.addEventListener("focus", () => {
    editorForm().avail.value = "bonus";
  });

  byId("payForm").addEventListener("submit", savePaymentSettings);
  byId("playerSearch").addEventListener("input", () => {
    clearTimeout(playerSearchTimer);
    playerSearchTimer = setTimeout(loadPlayers, 250);
  });

  window.addEventListener("beforeunload", (event) => {
    if (!byId("signalSave").hidden) {
      event.preventDefault();
      event.returnValue = "";
    }
  });

  // -- boot -------------------------------------------------------------------------------------

  (async () => {
    let health = {};
    try {
      health = await (await fetch("/health")).json();
    } catch {
      health = {};
    }
    if (!health.admin?.datarail) {
      byId("tabDatarail").disabled = true;
      byId("tabDatarail").title = "DataRail sign-in isn't set up on this server";
      byId("datarailNote").textContent =
        "DataRail sign-in isn't set up on this server yet (DATARAIL_API).";
    }
    selectSignInTab("account");
    auth = loadSession();
    if (!auth) {
      showSignIn("");
      return;
    }
    try {
      await api("/v1/admin/me");
      await showApp();
    } catch {
      auth = null;
      saveSession(null);
      showSignIn("");
    }
  })();
})();
