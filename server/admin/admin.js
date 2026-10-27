/* GhostSignal admin page. Plain DOM and the vendored Leaflet map, no build step.
   The session token is kept in sessionStorage for this tab only and sent as a bearer token. */
(function () {
  "use strict";

  var KEY = "ghostsignal-admin";
  var $ = function (id) { return document.getElementById(id); };

  var auth = null;          // { session, who }
  var mission = null;       // GET /v1/admin/mission
  var route = null;         // stored venue route: placements and labels
  var draft = {};           // signal id -> { lat, lon, radiusM, lockRadiusM, name, place }
  var catalog = null;       // GET /v1/tracks/:mission?all=1
  var map = null, pins = {}, rings = {}, selected = null;
  var editing = null;       // { id, isNew, published, stems: { slot: { blob, info, name, gainDb } } }

  // ------------------------------------------------------------------ small helpers

  function el(tag, attrs) {
    var node = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      var v = attrs[k];
      if (v === null || v === undefined || v === false) return;
      if (k === "text") node.textContent = v;
      else if (k === "class") node.className = v;
      else if (k.slice(0, 2) === "on") node.addEventListener(k.slice(2), v);
      else node.setAttribute(k, v === true ? "" : v);
    });
    for (var i = 2; i < arguments.length; i++) {
      var c = arguments[i];
      if (c === null || c === undefined || c === false) continue;
      node.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    }
    return node;
  }

  function store(value) {
    try {
      if (value) sessionStorage.setItem(KEY, JSON.stringify(value));
      else sessionStorage.removeItem(KEY);
    } catch (e) { /* private window: the session lasts until reload */ }
  }

  function stored() {
    try { return JSON.parse(sessionStorage.getItem(KEY) || "null"); } catch (e) { return null; }
  }

  function api(path, opts) {
    opts = opts || {};
    var headers = { accept: "application/json" };
    if (auth) headers.authorization = "Bearer " + auth.session;
    var body = opts.body;
    if (body !== undefined && !(body instanceof Blob)) {
      headers["content-type"] = "application/json";
      body = JSON.stringify(body);
    } else if (body instanceof Blob) {
      headers["content-type"] = body.type || "application/octet-stream";
    }
    return fetch(path, { method: opts.method || "GET", headers: headers, body: body }).then(function (res) {
      if (res.status === 204) return null;
      return res.json().catch(function () { return {}; }).then(function (data) {
        if (res.ok) return data;
        if ((res.status === 401 || res.status === 403) && auth && !opts.login) {
          signOut("Your admin session ended. Sign in again.");
        }
        var err = new Error(data.error || "HTTP " + res.status);
        err.status = res.status;
        throw err;
      });
    });
  }

  function num(v) { var n = parseFloat(v); return isFinite(n) ? n : NaN; }
  function round6(n) { return Math.round(n * 1e6) / 1e6; }
  function pad2(n) { return String(n).padStart(2, "0"); }
  function slug(s) { return s.toLowerCase().normalize("NFKD").replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 40); }
  function loopSeconds(bpm, beats, bars) { return bars * beats * 60 / bpm; }

  // ------------------------------------------------------------------ sign in

  function showSignin(message) {
    $("app").hidden = true;
    $("who").hidden = true;
    $("signin").hidden = false;
    $("signinError").textContent = message || "";
  }

  var SIGNIN = { account: ["tabAccount", "formAccount"], datarail: ["tabDatarail", "formDatarail"], token: ["tabToken", "formToken"] };

  function pickTab(name) {
    Object.keys(SIGNIN).forEach(function (k) {
      $(SIGNIN[k][0]).setAttribute("aria-selected", String(k === name));
      $(SIGNIN[k][1]).hidden = k !== name;
    });
    $("signinError").textContent = "";
  }

  function signIn(body, form) {
    var button = form.querySelector("button");
    button.disabled = true;
    $("signinError").textContent = "";
    api("/v1/admin/login", { method: "POST", body: body, login: true })
      .then(function (data) {
        auth = { session: data.session, who: data.who };
        store(auth);
        form.reset();
        return showApp();
      })
      .catch(function (e) { $("signinError").textContent = e.message; })
      .then(function () { button.disabled = false; });
  }

  function signOut(message) {
    if (auth) fetch("/v1/admin/logout", { method: "POST", headers: { authorization: "Bearer " + auth.session } }).catch(function () {});
    auth = null;
    store(null);
    showSignin(message);
  }

  // ------------------------------------------------------------------ signed in

  function showApp() {
    return api("/v1/admin/mission").then(function (m) {
      mission = m;
      return Promise.all([
        api("/v1/routes/" + mission.id).catch(function (e) { if (e.status === 404) return null; throw e; }),
        api("/v1/tracks/" + mission.id + "?all=1"),
      ]);
    }).then(function (got) {
      route = got[0] || { placements: {}, labels: {} };
      catalog = got[1];
      $("signin").hidden = true;
      $("app").hidden = false;
      $("who").hidden = false;
      $("whoName").textContent = auth.who.display + (auth.who.via === "datarail" ? " · DataRail admin" : auth.who.via === "ghostsignal" ? " · admin" : "");
      resetDraft();
      renderSignals();
      renderTracks();
      api("/v1/admin/glasses").then(function (d) {
        var n = d.links.filter(function (l) { return l.status === "pending"; }).length;
        $("glassesCount").textContent = n ? "(" + n + ")" : "";
      }).catch(function () {});
    });
  }

  function view(name) {
    document.querySelectorAll("[data-view]").forEach(function (t) { t.setAttribute("aria-selected", String(t.dataset.view === name)); });
    ["signals", "tracks", "players", "glasses"].forEach(function (v) { $("view-" + v).hidden = v !== name; });
    if (name === "signals" && map) setTimeout(function () { map.invalidateSize(); }, 0);
    if (name === "players") loadPlayers();
    if (name === "glasses") loadGlasses();
  }

  // ------------------------------------------------------------------ signals

  function authored(s) {
    return { lat: s.location.lat, lon: s.location.lon, radiusM: s.radiusM, lockRadiusM: s.lockRadiusM, name: s.name, place: s.place };
  }

  /** What every phone gets now: the authored signal with the stored route on top. */
  function live(s) {
    var a = authored(s), p = (route.placements || {})[s.id], l = (route.labels || {})[s.id] || {};
    return {
      lat: p ? p.location.lat : a.lat,
      lon: p ? p.location.lon : a.lon,
      radiusM: p ? p.radiusM : a.radiusM,
      lockRadiusM: p ? p.lockRadiusM : a.lockRadiusM,
      name: l.name || a.name,
      place: l.place || a.place,
    };
  }

  function resetDraft() {
    draft = {};
    mission.signals.forEach(function (s) { draft[s.id] = live(s); });
  }

  var FIELDS = ["name", "place", "lat", "lon", "radiusM", "lockRadiusM"];

  function changed(s, field) { return String(draft[s.id][field]) !== String(live(s)[field]); }
  function moved(s) { var a = authored(s), d = draft[s.id]; return d.lat !== a.lat || d.lon !== a.lon; }

  function problems() {
    var out = [];
    mission.signals.forEach(function (s, i) {
      var d = draft[s.id];
      if (!(Math.abs(d.lat) <= 90 && Math.abs(d.lon) <= 180)) out.push("Signal " + (i + 1) + " needs a valid position.");
      if (!(d.radiusM >= 5 && d.radiusM <= 500)) out.push("Signal " + (i + 1) + ": near radius is 5 to 500 m.");
      if (!(d.lockRadiusM >= 5 && d.lockRadiusM <= d.radiusM)) out.push("Signal " + (i + 1) + ": capture radius is 5 m up to its near radius.");
      if (d.lockRadiusM < 10) out.push("Signal " + (i + 1) + ": a capture radius under 10 m is unreliable on phone GPS.");
    });
    return out;
  }

  function refreshDirty() {
    var dirty = mission.signals.some(function (s) { return FIELDS.some(function (f) { return changed(s, f); }); });
    var issues = problems();
    $("signalSave").hidden = !dirty;
    $("signalSaveBtn").disabled = issues.some(function (p) { return p.indexOf("unreliable") < 0; });
    $("signalDirty").textContent = issues.length ? issues[0] : "Unsaved changes";
    mission.signals.forEach(function (s) {
      var row = document.querySelector('[data-signal="' + s.id + '"]');
      if (!row) return;
      FIELDS.forEach(function (f) { row.querySelector('[name="' + f + '"]').classList.toggle("changed", changed(s, f)); });
      var icon = pins[s.id] && pins[s.id].getElement();
      if (icon) icon.firstChild.classList.toggle("moved", moved(s));
    });
  }

  function pinIcon(i) {
    return L.divIcon({ className: "", html: '<div class="pin">' + (i + 1) + "</div>", iconSize: [28, 28], iconAnchor: [14, 14] });
  }

  function select(id, pan) {
    selected = id;
    document.querySelectorAll(".signal").forEach(function (r) { r.classList.toggle("selected", r.dataset.signal === id); });
    Object.keys(pins).forEach(function (k) {
      var icon = pins[k].getElement();
      if (icon) icon.firstChild.classList.toggle("selected", k === id);
    });
    if (pan && map && pins[id]) map.panTo(pins[id].getLatLng());
  }

  function placeOnMap(s) {
    var d = draft[s.id], at = [d.lat, d.lon];
    if (!(Math.abs(d.lat) <= 90 && Math.abs(d.lon) <= 180)) return;
    pins[s.id].setLatLng(at);
    rings[s.id].near.setLatLng(at).setRadius(Math.max(1, d.radiusM || 1));
    rings[s.id].lock.setLatLng(at).setRadius(Math.max(1, d.lockRadiusM || 1));
  }

  function renderSignals() {
    var list = $("signalList");
    list.textContent = "";
    if (!map) {
      map = L.map("map", { scrollWheelZoom: false });
      L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 19,
        // OpenStreetMap's tile servers block browser requests without a Referer, and this page's
        // policy is no-referrer. The tiles alone send this site's origin (never the path).
        referrerPolicy: "strict-origin-when-cross-origin",
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      }).addTo(map);
    }
    Object.keys(pins).forEach(function (k) { pins[k].remove(); rings[k].near.remove(); rings[k].lock.remove(); });
    pins = {};
    rings = {};

    mission.signals.forEach(function (s, i) {
      var d = draft[s.id];
      rings[s.id] = {
        near: L.circle([d.lat, d.lon], { radius: d.radiusM, color: "#42f5ff", weight: 1, dashArray: "4 6", fillOpacity: 0.04 }).addTo(map),
        lock: L.circle([d.lat, d.lon], { radius: d.lockRadiusM, color: "#42f5ff", weight: 1.5, fillOpacity: 0.1 }).addTo(map),
      };
      var pin = L.marker([d.lat, d.lon], { icon: pinIcon(i), draggable: true, title: d.name, keyboard: false }).addTo(map);
      pin.on("drag", function (e) {
        var p = e.target.getLatLng();
        rings[s.id].near.setLatLng(p);
        rings[s.id].lock.setLatLng(p);
      });
      pin.on("dragend", function (e) {
        var p = e.target.getLatLng();
        d.lat = round6(p.lat);
        d.lon = round6(p.lng);
        var row = document.querySelector('[data-signal="' + s.id + '"]');
        row.querySelector('[name="lat"]').value = d.lat;
        row.querySelector('[name="lon"]').value = d.lon;
        select(s.id, false);
        refreshDirty();
      });
      pin.on("click", function () { select(s.id, false); document.querySelector('[data-signal="' + s.id + '"] [name="name"]').focus(); });
      pins[s.id] = pin;

      function field(name, label, type, extra) {
        var input = el("input", Object.assign({ name: name, value: d[name], type: type || "text" }, extra || {}));
        input.addEventListener("input", function () {
          d[name] = type === "number" ? num(input.value) : input.value;
          if (type === "number") placeOnMap(s);
          if (name === "name") pin.options.title = input.value;
          refreshDirty();
        });
        input.addEventListener("focus", function () { select(s.id, true); });
        return el("label", null, label, input);
      }

      var a = authored(s);
      list.appendChild(el("li", { class: "signal", "data-signal": s.id },
        el("span", { class: "num" }, pad2(i + 1), el("small", { text: s.stem })),
        field("name", "Name", "text", { maxlength: 60, placeholder: a.name }),
        field("place", "Place (spoken in hints)", "text", { maxlength: 160, placeholder: a.place }),
        field("lat", "Latitude", "number", { step: "0.000001" }),
        field("lon", "Longitude", "number", { step: "0.000001" }),
        field("radiusM", "Near m", "number", { step: "1", min: 5, max: 500 }),
        field("lockRadiusM", "Capture m", "number", { step: "1", min: 5, max: 500 }),
        el("button", {
          type: "button", class: "btn ghost small reset", text: "As written",
          title: "Back to the position and name in the mission file",
          onclick: function () {
            draft[s.id] = authored(s);
            renderSignals();
            select(s.id, true);
          },
        })));
    });
    var bounds = L.latLngBounds(mission.signals.map(function (s) { return [draft[s.id].lat, draft[s.id].lon]; }));
    map.fitBounds(bounds.pad(0.25));
    setTimeout(function () { map.invalidateSize(); }, 0);
    refreshDirty();
    if (selected) select(selected, false);
  }

  function saveSignals() {
    var placements = {}, labels = {};
    mission.signals.forEach(function (s) {
      var a = authored(s), d = draft[s.id];
      var samePlace = d.lat === a.lat && d.lon === a.lon && d.radiusM === a.radiusM && d.lockRadiusM === a.lockRadiusM;
      placements[s.id] = samePlace ? null : { location: { lat: d.lat, lon: d.lon }, radiusM: d.radiusM, lockRadiusM: d.lockRadiusM };
      var name = d.name.trim() === a.name ? "" : d.name.trim();
      var place = d.place.trim() === a.place ? "" : d.place.trim();
      labels[s.id] = name || place ? { name: name, place: place } : null;
    });
    $("signalSaveBtn").disabled = true;
    api("/v1/routes/" + mission.id, { method: "PUT", body: { missionId: mission.id, name: "Venue route", placements: placements, labels: labels } })
      .then(function () { return api("/v1/routes/" + mission.id); })
      .then(function (r) {
        route = r;
        resetDraft();
        renderSignals();
        $("signalStatus").textContent = "Saved. Phones pick it up the next time GhostSignal opens, or when the operator pulls from the server.";
      })
      .catch(function (e) { $("signalStatus").textContent = "Couldn't save: " + e.message; })
      .then(function () { $("signalSaveBtn").disabled = false; refreshDirty(); });
  }

  // ------------------------------------------------------------------ soundtracks

  function signalName(s) { return live(s).name; }

  function renderTracks() {
    var deployed = catalog.soundtracks;
    var base = deployed.filter(function (t) { return t.published && !t.premium && t.unlockAfterCompletions === 0; });
    var premium = deployed.filter(function (t) { return t.published && t.premium; });
    var bonus = mission.bundledSoundtracks.map(function (t) { return t.title + " after " + t.unlockAfterCompletions + (t.unlockAfterCompletions === 1 ? " run" : " runs"); })
      .concat(deployed.filter(function (t) { return t.published && t.unlockAfterCompletions > 0; })
        .map(function (t) { return t.title + " after " + t.unlockAfterCompletions + (t.unlockAfterCompletions === 1 ? " run" : " runs"); }));
    $("trackSummary").textContent =
      "Base game: " + (1 + base.length) + " soundtrack" + (base.length ? "s" : "") + " (" +
      [mission.music.title || "Original"].concat(base.map(function (t) { return t.title; })).join(", ") + "). " +
      (bonus.length ? "Earned: " + bonus.join(", ") + ". " : "") +
      (premium.length ? "Premium: " + premium.map(function (t) { return t.title; }).join(", ") + "." : "");

    var list = $("trackList");
    list.textContent = "";
    list.appendChild(el("li", { class: "track" },
      el("div", { class: "t" }, el("b", { text: mission.music.title || "Original" }), el("span", { text: "original · " + mission.music.bpm + " BPM · ships with the app" })),
      el("span", { class: "badge base", text: "Base" }), el("span", { class: "badge live", text: "In the app" })));
    mission.bundledSoundtracks.forEach(function (t) {
      list.appendChild(el("li", { class: "track" },
        el("div", { class: "t" }, el("b", { text: t.title }), el("span", { text: t.id + " · ships with the app" })),
        el("span", { class: "badge bonus", text: "Bonus · after " + t.unlockAfterCompletions }), el("span", { class: "badge live", text: "In the app" })));
    });
    deployed.forEach(function (t) {
      var stems = t.music.stems.length, all = mission.signals.length;
      list.appendChild(el("li", { class: "track" },
        el("div", { class: "t" }, el("b", { text: t.title }),
          el("span", { text: t.id + " · " + t.music.bpm + " BPM · " + stems + "/" + all + " stems · updated " + new Date(t.updatedAtMs).toLocaleString() })),
        el("span", {
          class: t.premium ? "badge premium" : t.unlockAfterCompletions ? "badge bonus" : "badge base",
          text: t.premium ? "Premium" : t.unlockAfterCompletions ? "Bonus · after " + t.unlockAfterCompletions : "Base",
        }),
        el("span", { class: t.published ? "badge live" : "badge draft", text: t.published ? "Published" : "Draft" }),
        el("button", { type: "button", class: "btn ghost small", text: "Edit", onclick: function () { openEditor(t); } }),
        el("button", {
          type: "button", class: "btn danger small", text: "Delete",
          onclick: function () {
            if (!confirm("Take “" + t.title + "” down? Phones drop it the next time they sync.")) return;
            api("/v1/tracks/" + mission.id + "/" + t.id, { method: "DELETE" })
              .then(reloadTracks)
              .then(function () { $("trackStatus").textContent = "Deleted " + t.title + "."; })
              .catch(function (e) { $("trackStatus").textContent = "Couldn't delete: " + e.message; });
          },
        })));
    });
  }

  function reloadTracks() {
    return api("/v1/tracks/" + mission.id + "?all=1").then(function (c) { catalog = c; renderTracks(); });
  }

  function form() { return $("editor").elements; }

  function currentLoop() {
    var f = form();
    var s = loopSeconds(num(f.bpm.value), parseInt(f.beatsPerBar.value, 10), parseInt(f.loopBars.value, 10));
    return isFinite(s) && s > 0 ? s : NaN;
  }

  function openEditor(track) {
    var f = form();
    $("editor").hidden = false;
    $("editorError").textContent = "";
    $("warnings").textContent = "";
    editing = { id: track ? track.id : "", isNew: !track, published: track ? track.published : false, stems: {}, idTouched: Boolean(track) };
    $("editorTitle").textContent = track ? "Edit “" + track.title + "”" : "New soundtrack";
    f.title.value = track ? track.title : "";
    f.id.value = track ? track.id : "";
    f.id.readOnly = Boolean(track);
    f.description.value = track ? track.description || "" : "";
    var m = track ? track.music : mission.music;
    f.bpm.value = m.bpm;
    f.beatsPerBar.value = m.beatsPerBar || 4;
    f.loopBars.value = m.loopBars || 8;
    var unlock = track ? track.unlockAfterCompletions : 0;
    f.avail.value = track && track.premium ? "premium" : unlock > 0 ? "bonus" : "base";
    f.unlock.value = unlock > 0 ? unlock : 1;
    if (track) {
      track.music.stems.forEach(function (st) {
        editing.stems[st.id] = {
          blob: st.file.replace(/^server:/, ""),
          info: { format: st.format, durationS: st.durationS, bytes: st.bytes },
          name: st.name, gainDb: st.gainDb,
        };
      });
    }
    renderStems();
    $("editor").scrollIntoView({ behavior: "smooth", block: "start" });
  }

  function stemInfo(st) {
    if (!st) return { text: "No file yet", cls: "" };
    if (st.uploading) return { text: "Uploading…", cls: "" };
    if (st.error) return { text: st.error, cls: "bad" };
    var i = st.info, loop = currentLoop();
    var what = (st.original ? st.original + " · " : "") + i.durationS.toFixed(2) + " s" +
      (i.sampleRate ? " · " + Math.round(i.sampleRate / 100) / 10 + " kHz" : "") +
      (i.channels ? (i.channels === 1 ? " mono" : " stereo") : "") + " · " + (i.format || "").toUpperCase();
    if (!isFinite(loop)) return { text: what, cls: "" };
    var off = i.durationS - loop;
    if (Math.abs(off) <= 0.02) return { text: what + " · loops on the beat", cls: "ok" };
    return { text: what + " · the loop is " + loop.toFixed(2) + " s, so it'll be " + (off > 0 ? "cut short" : "padded with silence"), cls: "warn" };
  }

  function renderStems() {
    var list = $("stemList");
    list.textContent = "";
    mission.signals.forEach(function (s, i) {
      var st = editing.stems[s.stem];
      var info = stemInfo(st);
      var file = el("input", { type: "file", accept: ".ogg,.opus,.wav,.flac,audio/ogg,audio/wav,audio/flac", "aria-label": "Audio for " + signalName(s) });
      file.addEventListener("change", function () { if (file.files[0]) upload(s, file.files[0]); });
      var name = el("input", { maxlength: 60, placeholder: signalName(s), value: st && st.name !== signalName(s) ? st.name || "" : "" });
      name.addEventListener("input", function () { if (editing.stems[s.stem]) editing.stems[s.stem].name = name.value; });
      var gain = el("input", { type: "number", min: -30, max: 12, step: 0.5, value: st ? st.gainDb || 0 : 0 });
      gain.addEventListener("input", function () { if (editing.stems[s.stem]) editing.stems[s.stem].gainDb = num(gain.value) || 0; });
      list.appendChild(el("li", { class: "stem" },
        el("span", { class: "num" }, pad2(i + 1), el("small", { text: s.stem })),
        el("div", { class: "file" }, el("b", { text: signalName(s) }), file, el("span", { class: "info " + info.cls, text: info.text })),
        el("label", null, "Layer name", name),
        el("label", null, "Gain dB", gain)));
    });
    refreshEditor();
  }

  function refreshEditor() {
    var f = form();
    var loop = currentLoop();
    $("loopLength").textContent = isFinite(loop) ? f.loopBars.value + " bars = " + loop.toFixed(2) + " s per loop" : "";
    var ready = mission.signals.every(function (s) { var st = editing.stems[s.stem]; return st && st.blob && !st.uploading && !st.error; });
    var busy = mission.signals.some(function (s) { var st = editing.stems[s.stem]; return st && st.uploading; });
    $("editorPublish").disabled = !ready || busy;
    $("editorDraft").disabled = busy;
    $("editorPublish").textContent = editing.published ? "Save and keep published" : "Publish";
    $("editorDraft").textContent = editing.published ? "Unpublish (back to draft)" : "Save as draft";
    $("editorPublish").title = ready ? "" : "Every signal needs a stem before the track can be published";
  }

  function upload(s, file) {
    var prev = editing.stems[s.stem] || {};
    editing.stems[s.stem] = Object.assign({}, prev, { uploading: true, error: null });
    renderStems();
    api("/v1/blobs?name=" + encodeURIComponent(file.name), { method: "PUT", body: file })
      .then(function (blob) {
        editing.stems[s.stem] = { blob: blob.name, info: blob, original: file.name, name: prev.name || "", gainDb: prev.gainDb || 0 };
      })
      .catch(function (e) {
        editing.stems[s.stem] = Object.assign({}, prev, { uploading: false, error: file.name + ": " + e.message });
      })
      .then(renderStems);
  }

  function saveTrack(publish) {
    var f = form();
    var id = f.id.value.trim();
    var unlock = f.avail.value === "bonus" ? parseInt(f.unlock.value, 10) : 0;
    var body = {
      title: f.title.value.trim(),
      description: f.description.value.trim(),
      unlockAfterCompletions: unlock,
      premium: f.avail.value === "premium",
      published: publish,
      music: {
        bpm: num(f.bpm.value),
        beatsPerBar: parseInt(f.beatsPerBar.value, 10),
        loopBars: parseInt(f.loopBars.value, 10),
        stems: mission.signals.filter(function (s) { var st = editing.stems[s.stem]; return st && st.blob; }).map(function (s) {
          var st = editing.stems[s.stem];
          return { id: s.stem, blob: st.blob, name: (st.name || "").trim() || signalName(s), gainDb: st.gainDb || 0 };
        }),
      },
    };
    $("editorError").textContent = "";
    $("warnings").textContent = "";
    if (!/^[a-z0-9][a-z0-9-]{1,39}$/.test(id)) { $("editorError").textContent = "The ID is 2 to 40 lowercase letters, digits and dashes."; return; }
    if (editing.isNew && catalog.soundtracks.some(function (t) { return t.id === id; })) { $("editorError").textContent = "There's already a soundtrack called " + id + "."; return; }
    $("editorPublish").disabled = $("editorDraft").disabled = true;
    api("/v1/tracks/" + mission.id + "/" + id, { method: "PUT", body: body })
      .then(function (res) {
        editing.isNew = false;
        editing.id = id;
        editing.published = res.track.published;
        f.id.readOnly = true;
        res.warnings.forEach(function (w) { $("warnings").appendChild(el("li", { text: w })); });
        $("trackStatus").textContent = (res.track.published ? "Published " : "Saved draft of ") + res.track.title +
          (res.track.published ? ". Phones download it the next time GhostSignal opens." : ".");
        return reloadTracks().then(function () { if (!res.warnings.length) $("editor").hidden = true; });
      })
      .catch(function (e) { $("editorError").textContent = e.message; })
      .then(refreshEditor);
  }

  // ------------------------------------------------------------------ players

  var players = { accounts: [], unclaimed: [] };
  var searchTimer = null;

  function loadPlayers() {
    var q = $("playerSearch").value.trim();
    return Promise.all([api("/v1/admin/accounts?q=" + encodeURIComponent(q)), api("/v1/admin/settings")])
      .then(function (got) {
        players = got[0];
        renderPlayers();
        renderPayment(got[1]);
      })
      .catch(function (e) { $("playerStatus").textContent = "Couldn't load players: " + e.message; });
  }

  function when(ms) { return ms ? new Date(ms).toLocaleDateString() : "never"; }

  function renderPayment(s) {
    var f = $("payForm").elements;
    f.paymentLink.value = s.settings.paymentLink || "";
    f.priceLabel.value = s.settings.priceLabel || "";
    f.manageBillingUrl.value = s.settings.manageBillingUrl || "";
    var hooks = $("hooks");
    hooks.textContent = "";
    var provider = s.provider;
    hooks.appendChild(el("li", {
      text: s.settings.paymentLink
        ? "Link: " + (provider === "stripe" ? "Stripe Payment Link" : provider === "lemonsqueezy" ? "Lemon Squeezy checkout" : "other provider (grant premium by hand)")
        : "No payment link yet: players see “Premium isn't on sale yet”.",
    }));
    [["stripe", "Stripe", "STRIPE_WEBHOOK_SECRET", "checkout.session.completed, customer.subscription.updated, customer.subscription.deleted"],
     ["lemonsqueezy", "Lemon Squeezy", "LEMONSQUEEZY_WEBHOOK_SECRET", "order_created, order_refunded, subscription_created, subscription_updated, subscription_expired"]].forEach(function (h) {
      var w = s.webhooks[h[0]];
      hooks.appendChild(el("li", null,
        el("span", { class: w.configured ? "on" : "off", text: h[1] + " webhook " + (w.configured ? "✓ " : "– not set (" + h[2] + ") ") }),
        "endpoint ", el("code", { text: location.origin + w.path }),
        w.configured ? " · last event " + (w.lastMs ? new Date(w.lastMs).toLocaleString() : "none yet") : "",
        el("br"), el("span", { class: "small", text: "Events: " + h[3] })));
    });
  }

  function renderPlayers() {
    var c = players.counts;
    $("playerCounts").textContent = c.total + " accounts · " + c.premium + " premium · " + c.admins + " admin" + (c.admins === 1 ? "" : "s");

    var un = $("unclaimedList");
    un.textContent = "";
    $("unclaimedCard").hidden = !players.unclaimed.length;
    players.unclaimed.forEach(function (u) {
      var pick = el("select", { "aria-label": "Account to apply this payment to" }, el("option", { value: "", text: "Choose an account…" }));
      players.accounts.forEach(function (a) {
        pick.appendChild(el("option", { value: a.id, text: a.email + (u.email && a.email === u.email ? " (same email)" : "") }));
      });
      var match = players.accounts.find(function (a) { return u.email && a.email === u.email; });
      if (match) pick.value = match.id;
      un.appendChild(el("li", { class: "track" },
        el("div", { class: "t" }, el("b", { text: u.email || "no email" }),
          el("span", { text: u.provider + " · " + new Date(u.atMs).toLocaleString() + (u.customerId ? " · customer " + u.customerId : "") })),
        pick,
        el("button", {
          type: "button", class: "btn small", text: "Apply",
          onclick: function () {
            if (!pick.value) return;
            api("/v1/admin/unclaimed/" + u.id, { method: "POST", body: { accountId: pick.value } })
              .then(loadPlayers)
              .then(function () { $("playerStatus").textContent = "Premium applied."; })
              .catch(function (e) { $("playerStatus").textContent = e.message; });
          },
        }),
        el("button", {
          type: "button", class: "btn ghost small", text: "Dismiss",
          onclick: function () {
            if (!confirm("Dismiss this payment? Nobody gets premium for it.")) return;
            api("/v1/admin/unclaimed/" + u.id, { method: "DELETE" }).then(loadPlayers);
          },
        })));
    });

    var list = $("playerList");
    list.textContent = "";
    if (!players.accounts.length) list.appendChild(el("li", { class: "muted", text: "No accounts yet." }));
    players.accounts.forEach(function (a) {
      var p = a.premium;
      function change(body, done) {
        return function () {
          api("/v1/admin/accounts/" + a.id, { method: "PUT", body: body })
            .then(loadPlayers)
            .then(function () { $("playerStatus").textContent = done; })
            .catch(function (e) { $("playerStatus").textContent = e.message; });
        };
      }
      list.appendChild(el("li", { class: "track" },
        el("div", { class: "t" }, el("b", { text: a.email }),
          el("span", { text: "joined " + when(a.createdAtMs) + " · last seen " + when(a.lastSeenMs) + " · " +
            Object.keys(a.completions).reduce(function (n, k) { return n + a.completions[k]; }, 0) + " finished runs" +
            (p.active && p.source ? " · premium " + (p.source === "grant" ? "granted" : "paid via " + p.provider) : "") })),
        a.role === "admin" ? el("span", { class: "badge admin", text: a.adminFromConfig ? "Admin · ADMIN_EMAILS" : "Admin" }) : null,
        p.active ? el("span", { class: "badge premium", text: "Premium" }) : el("span", { class: "badge", text: "Free" }),
        a.disabled ? el("span", { class: "badge off", text: "Disabled" }) : null,
        el("button", { type: "button", class: "btn ghost small", text: p.active ? "Remove premium" : "Give premium",
          onclick: change({ premium: !p.active }, p.active ? "Premium removed from " + a.email + "." : a.email + " has premium.") }),
        a.adminFromConfig ? null : el("button", { type: "button", class: "btn ghost small", text: a.role === "admin" ? "Remove admin" : "Make admin",
          onclick: change({ role: a.role === "admin" ? "player" : "admin" }, "Updated " + a.email + ".") }),
        el("button", { type: "button", class: a.disabled ? "btn ghost small" : "btn danger small", text: a.disabled ? "Enable" : "Disable",
          onclick: change({ disabled: !a.disabled }, (a.disabled ? "Enabled " : "Disabled ") + a.email + ".") })));
    });
  }

  function savePayment(e) {
    e.preventDefault();
    var f = $("payForm").elements;
    $("payStatus").textContent = "";
    api("/v1/admin/settings", { method: "PUT", body: { paymentLink: f.paymentLink.value.trim(), priceLabel: f.priceLabel.value.trim(), manageBillingUrl: f.manageBillingUrl.value.trim() } })
      .then(function () { return api("/v1/admin/settings"); })
      .then(function (s) { renderPayment(s); $("payStatus").textContent = "Saved. Players see it the next time they open their account."; })
      .catch(function (err) { $("payStatus").textContent = err.message; });
  }

  // ------------------------------------------------------------------ glasses

  function loadGlasses() {
    return api("/v1/admin/glasses")
      .then(renderGlasses)
      .catch(function (e) { $("glassesStatus").textContent = "Couldn't load glasses: " + e.message; });
  }

  function model(t) { return (t || "UNKNOWN").replace(/_/g, " ").toLowerCase().replace(/\b\w/g, function (c) { return c.toUpperCase(); }); }

  function renderGlasses(data) {
    var pending = data.links.filter(function (l) { return l.status === "pending"; });
    $("glassesCount").textContent = pending.length ? "(" + pending.length + ")" : "";

    function decide(l, status, done) {
      return function () {
        api("/v1/admin/glasses/" + l.id, { method: "PUT", body: { status: status } })
          .then(loadGlasses)
          .then(function () { $("glassesStatus").textContent = done; })
          .catch(function (e) { $("glassesStatus").textContent = e.message; });
      };
    }

    function row(l, buttons) {
      var when = l.status === "pending" ? "asked " + new Date(l.requestedAtMs).toLocaleString() :
        (l.status === "approved" ? "approved " : l.status === "rejected" ? "turned down " : "removed ") +
        (l.decidedAtMs ? new Date(l.decidedAtMs).toLocaleString() : "") + (l.decidedBy ? " by " + l.decidedBy : "");
      return el("li", { class: "track" },
        el("div", { class: "t" },
          el("b", { text: l.email }),
          el("span", { text: model(l.deviceType) + (l.name ? " \u201c" + l.name + "\u201d" : "") + " \u00b7 " + l.fingerprint + (l.firmware ? " \u00b7 firmware " + l.firmware : "") + " \u00b7 " + when }),
          l.alsoRequestedBy.length ? el("span", { class: "warnline", text: "Also asked for by " + l.alsoRequestedBy.join(", ") }) : null,
          l.enrolled ? el("span", { class: "warnline", text: "This pair is enrolled as event glasses" }) : null),
        buttons);
    }

    function fill(id, list, empty, make) {
      var ul = $(id);
      ul.textContent = "";
      if (!list.length) ul.appendChild(el("li", { class: "muted", text: empty }));
      list.forEach(function (l) { ul.appendChild(make(l)); });
    }

    fill("glassesPending", pending, "Nothing waiting.", function (l) {
      var wrap = el("span", { class: "actions-inline" },
        el("button", { type: "button", class: "btn small", text: "Approve", onclick: decide(l, "approved", l.email + " has premium.") }),
        el("button", { type: "button", class: "btn ghost small", text: "Turn down", onclick: decide(l, "rejected", "Turned down " + l.email + "'s glasses.") }));
      return row(l, wrap);
    });
    fill("glassesLinked", data.links.filter(function (l) { return l.status === "approved"; }), "No linked glasses yet.", function (l) {
      return row(l, el("button", {
        type: "button", class: "btn danger small", text: "Revoke",
        onclick: function () { if (confirm("Unlink these glasses from " + l.email + "? Their premium from this pair ends.")) decide(l, "revoked", "Revoked.")(); },
      }));
    });
    fill("glassesClosed", data.links.filter(function (l) { return l.status === "rejected" || l.status === "revoked"; }), "None.", function (l) {
      return row(l, el("button", { type: "button", class: "btn ghost small", text: "Approve", onclick: decide(l, "approved", l.email + " has premium.") }));
    });
    fill("glassesEnrolled", data.enrolled, "No event glasses enrolled.", function (x) {
      return el("li", { class: "track" },
        el("div", { class: "t" }, el("b", { text: model(x.deviceType) + (x.name ? " \u201c" + x.name + "\u201d" : "") }),
          el("span", { text: x.fingerprint + (x.note ? " \u00b7 " + x.note : "") + " \u00b7 by " + x.by })),
        el("button", {
          type: "button", class: "btn ghost small", text: "Remove",
          onclick: function () {
            api("/v1/admin/glasses/enrolled/" + x.id, { method: "DELETE" }).then(loadGlasses)
              .catch(function (e) { $("glassesStatus").textContent = e.message; });
          },
        }));
    });
  }

  // ------------------------------------------------------------------ wiring

  Object.keys(SIGNIN).forEach(function (k) { $(SIGNIN[k][0]).addEventListener("click", function () { pickTab(k); }); });
  $("formAccount").addEventListener("submit", function (e) {
    e.preventDefault();
    signIn({ email: this.elements.email.value, password: this.elements.password.value }, this);
  });
  $("formDatarail").addEventListener("submit", function (e) {
    e.preventDefault();
    signIn({ provider: "datarail", email: this.elements.email.value, password: this.elements.password.value }, this);
  });
  $("payForm").addEventListener("submit", savePayment);
  $("playerSearch").addEventListener("input", function () {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(loadPlayers, 250);
  });
  $("formToken").addEventListener("submit", function (e) {
    e.preventDefault();
    signIn({ token: this.elements.token.value }, this);
  });
  $("signOut").addEventListener("click", function () { signOut(""); });
  document.querySelectorAll("[data-view]").forEach(function (t) { t.addEventListener("click", function () { view(t.dataset.view); }); });

  $("signalSaveBtn").addEventListener("click", saveSignals);
  $("signalDiscard").addEventListener("click", function () { resetDraft(); renderSignals(); $("signalStatus").textContent = ""; });

  $("newTrack").addEventListener("click", function () { openEditor(null); });
  $("editorCancel").addEventListener("click", function () { $("editor").hidden = true; editing = null; });
  $("editorDraft").addEventListener("click", function () { saveTrack(false); });
  $("editorPublish").addEventListener("click", function () { saveTrack(true); });
  form().title.addEventListener("input", function () {
    if (editing && editing.isNew && !editing.idTouched) form().id.value = slug(form().title.value);
  });
  form().id.addEventListener("input", function () { if (editing) editing.idTouched = true; });
  ["bpm", "beatsPerBar", "loopBars"].forEach(function (n) { form()[n].addEventListener("input", renderStems); });
  form().unlock.addEventListener("focus", function () { form().avail.value = "bonus"; });

  window.addEventListener("beforeunload", function (e) {
    if (!$("signalSave").hidden) { e.preventDefault(); e.returnValue = ""; }
  });

  // ------------------------------------------------------------------ boot

  fetch("/health").then(function (r) { return r.json(); }).catch(function () { return {}; }).then(function (health) {
    var admin = health.admin || {};
    if (!admin.datarail) {
      $("tabDatarail").disabled = true;
      $("tabDatarail").title = "DataRail sign-in isn't set up on this server";
      $("datarailNote").textContent = "DataRail sign-in isn't set up on this server yet (DATARAIL_API).";
    }
    pickTab("account");
    auth = stored();
    if (!auth) return showSignin("");
    return api("/v1/admin/me").then(showApp).catch(function () { auth = null; store(null); showSignin(""); });
  });
})();
