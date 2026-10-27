/**
 * Where the runner is and which way they face: GPS and the phone's compass, or a simulated walk
 * for trying the game at a desk.
 */

export class Gps {
  constructor(onFix) {
    this.onFix = onFix;
    this.watch = null;
  }

  start() {
    if (this.watch !== null || !("geolocation" in navigator)) return;
    this.watch = navigator.geolocation.watchPosition(
      (p) =>
        this.onFix({
          lat: p.coords.latitude,
          lon: p.coords.longitude,
          accuracyM: p.coords.accuracy,
          timeMs: p.timestamp || Date.now(),
          speedMps: Number.isFinite(p.coords.speed) ? p.coords.speed : null,
          courseDeg: Number.isFinite(p.coords.heading) ? p.coords.heading : null,
          simulated: false,
        }),
      () => {},
      { enableHighAccuracy: true, maximumAge: 1000, timeout: 20000 },
    );
  }

  stop() {
    if (this.watch !== null) navigator.geolocation.clearWatch(this.watch);
    this.watch = null;
  }

  /** Asks once, from a tap: resolves with the accuracy in metres, or rejects with why not. */
  static check() {
    return new Promise((resolve, reject) => {
      if (!("geolocation" in navigator)) return reject(new Error("this browser has no location"));
      navigator.geolocation.getCurrentPosition(
        (p) => resolve(p.coords.accuracy),
        (e) => reject(new Error(e.code === 1 ? "location is blocked for this site" : "no location fix yet")),
        { enableHighAccuracy: true, timeout: 15000 },
      );
    });
  }
}

/** The phone's compass, in degrees clockwise from north. */
export class Compass {
  constructor(onHeading) {
    this.onHeading = onHeading;
    this.handler = (e) => {
      let deg = null;
      if (typeof e.webkitCompassHeading === "number") deg = e.webkitCompassHeading; // iOS
      else if (e.absolute && e.alpha !== null) deg = 360 - e.alpha; // alpha turns the other way
      if (deg === null) return;
      const screen = (window.screen.orientation && window.screen.orientation.angle) || 0;
      this.onHeading((((deg + screen) % 360) + 360) % 360);
    };
    this.event = "ondeviceorientationabsolute" in window ? "deviceorientationabsolute" : "deviceorientation";
  }

  /** iPhone asks before sharing motion; call from a tap. */
  static async permit() {
    if (typeof DeviceOrientationEvent !== "undefined" && typeof DeviceOrientationEvent.requestPermission === "function") {
      const r = await DeviceOrientationEvent.requestPermission();
      if (r !== "granted") throw new Error("motion access was declined");
    }
    if (typeof DeviceOrientationEvent === "undefined") throw new Error("this device has no compass");
  }

  start() {
    window.addEventListener(this.event, this.handler);
  }

  stop() {
    window.removeEventListener(this.event, this.handler);
  }
}

const R = 6371008.8;
const rad = (d) => (d * Math.PI) / 180;

/** [m] metres from (lat, lon) toward [bearingDeg]. */
export function step(lat, lon, bearingDeg, m) {
  const d = m / R;
  const b = rad(bearingDeg);
  const la = rad(lat);
  const lo = rad(lon);
  const la2 = Math.asin(Math.sin(la) * Math.cos(d) + Math.cos(la) * Math.sin(d) * Math.cos(b));
  const lo2 = lo + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(la), Math.cos(d) - Math.sin(la) * Math.sin(la2));
  return [(la2 * 180) / Math.PI, (lo2 * 180) / Math.PI];
}

/** A pretend runner for testing at a desk: walks at a steady pace toward wherever it's sent. */
export class SimWalker {
  constructor(onFix, measure) {
    this.onFix = onFix;
    this.measure = measure; // (lat1, lon1, lat2, lon2) => [distanceM, bearingDeg]
    this.pos = null;
    this.target = null;
    this.timer = null;
    this.speed = 1.6;
  }

  start(lat, lon) {
    if (!this.pos) this.pos = [lat, lon];
    if (this.timer === null) this.timer = setInterval(() => this.tick(), 1000);
    this.emit(null, 0);
  }

  stop() {
    clearInterval(this.timer);
    this.timer = null;
  }

  teleport(lat, lon) {
    this.pos = [lat, lon];
    this.target = null;
    this.emit(null, 0);
  }

  walkTo(lat, lon, speed = 1.6) {
    this.target = [lat, lon];
    this.speed = speed;
  }

  tick() {
    if (!this.pos) return;
    let course = null;
    let speed = 0;
    if (this.target) {
      const [dist, bearing] = this.measure(this.pos[0], this.pos[1], this.target[0], this.target[1]);
      if (dist <= this.speed) {
        this.pos = this.target;
        this.target = null;
      } else {
        this.pos = step(this.pos[0], this.pos[1], bearing, this.speed);
        course = bearing;
        speed = this.speed;
      }
    }
    this.emit(course, speed);
  }

  emit(courseDeg, speedMps) {
    this.onFix({ lat: this.pos[0], lon: this.pos[1], accuracyM: 5, timeMs: Date.now(), speedMps, courseDeg, simulated: true });
  }
}
