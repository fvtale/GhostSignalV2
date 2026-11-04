// Where the runner is and which way they face: device GPS and compass,
// or a simulated walker for desk testing. Fixes use the shared shape
// { lat, lon, accuracyM, timeMs, speedMps, courseDeg, simulated }.

const EARTH_RADIUS_M = 6371008.8;
const toRadians = (degrees) => (degrees * Math.PI) / 180;
const toDegrees = (radians) => (radians * 180) / Math.PI;

/** The point [metres] from (lat, lon) toward [bearingDeg]. */
export function step(lat, lon, bearingDeg, metres) {
  const angular = metres / EARTH_RADIUS_M;
  const bearing = toRadians(bearingDeg);
  const startLat = toRadians(lat);
  const startLon = toRadians(lon);

  const endLat = Math.asin(
    Math.sin(startLat) * Math.cos(angular) +
    Math.cos(startLat) * Math.sin(angular) * Math.cos(bearing),
  );
  const endLon = startLon + Math.atan2(
    Math.sin(bearing) * Math.sin(angular) * Math.cos(startLat),
    Math.cos(angular) - Math.sin(startLat) * Math.sin(endLat),
  );
  return [toDegrees(endLat), toDegrees(endLon)];
}

export class Gps {
  constructor(onFix) {
    this.onFix = onFix;
    this.watchId = null;
  }

  start() {
    if (this.watchId !== null || !("geolocation" in navigator)) return;
    this.watchId = navigator.geolocation.watchPosition(
      (position) => this.onFix({
        lat: position.coords.latitude,
        lon: position.coords.longitude,
        accuracyM: position.coords.accuracy,
        timeMs: position.timestamp || Date.now(),
        speedMps: Number.isFinite(position.coords.speed) ? position.coords.speed : null,
        courseDeg: Number.isFinite(position.coords.heading) ? position.coords.heading : null,
        simulated: false,
      }),
      () => {},
      { enableHighAccuracy: true, maximumAge: 1000, timeout: 20000 },
    );
  }

  stop() {
    if (this.watchId !== null) navigator.geolocation.clearWatch(this.watchId);
    this.watchId = null;
  }

  /** One-shot permission/accuracy probe, called from a tap. */
  static check() {
    return new Promise((resolve, reject) => {
      if (!("geolocation" in navigator)) {
        reject(new Error("this browser has no location"));
        return;
      }
      navigator.geolocation.getCurrentPosition(
        (position) => resolve(position.coords.accuracy),
        (error) => reject(new Error(
          error.code === 1 ? "location is blocked for this site" : "no location fix yet",
        )),
        { enableHighAccuracy: true, timeout: 15000 },
      );
    });
  }
}

/** Phone compass heading: degrees clockwise from north, screen-adjusted. */
export class Compass {
  constructor(onHeading) {
    this.onHeading = onHeading;
    this.listener = (event) => {
      let degrees = null;
      if (typeof event.webkitCompassHeading === "number") {
        degrees = event.webkitCompassHeading; // iOS reports heading directly.
      } else if (event.absolute && event.alpha !== null) {
        degrees = 360 - event.alpha; // alpha runs counter-clockwise.
      }
      if (degrees === null) return;
      const screenAngle = window.screen.orientation?.angle || 0;
      this.onHeading((((degrees + screenAngle) % 360) + 360) % 360);
    };
    this.eventName = "ondeviceorientationabsolute" in window
      ? "deviceorientationabsolute"
      : "deviceorientation";
  }

  /** iOS asks before sharing motion; call from a tap. */
  static async permit() {
    if (typeof DeviceOrientationEvent === "undefined") {
      throw new Error("this device has no compass");
    }
    if (typeof DeviceOrientationEvent.requestPermission === "function") {
      const answer = await DeviceOrientationEvent.requestPermission();
      if (answer !== "granted") throw new Error("motion access was declined");
    }
  }

  start() {
    window.addEventListener(this.eventName, this.listener);
  }

  stop() {
    window.removeEventListener(this.eventName, this.listener);
  }
}

/** A pretend runner for desk testing: walks steadily toward its target. */
export class SimWalker {
  constructor(onFix, measure) {
    this.onFix = onFix;
    this.measure = measure; // (lat1, lon1, lat2, lon2) -> [distanceM, bearingDeg]
    this.position = null;
    this.target = null;
    this.timer = null;
    this.speedMps = 1.6;
  }

  start(lat, lon) {
    if (!this.position) this.position = [lat, lon];
    if (this.timer === null) this.timer = setInterval(() => this.tick(), 1000);
    this.emit(null, 0);
  }

  stop() {
    clearInterval(this.timer);
    this.timer = null;
  }

  teleport(lat, lon) {
    this.position = [lat, lon];
    this.target = null;
    this.emit(null, 0);
  }

  walkTo(lat, lon, speedMps = 1.6) {
    this.target = [lat, lon];
    this.speedMps = speedMps;
  }

  tick() {
    if (!this.position) return;
    let courseDeg = null;
    let speedMps = 0;
    if (this.target) {
      const [distanceM, bearingDeg] = this.measure(
        this.position[0], this.position[1], this.target[0], this.target[1],
      );
      if (distanceM <= this.speedMps) {
        this.position = this.target;
        this.target = null;
      } else {
        this.position = step(this.position[0], this.position[1], bearingDeg, this.speedMps);
        courseDeg = bearingDeg;
        speedMps = this.speedMps;
      }
    }
    this.emit(courseDeg, speedMps);
  }

  emit(courseDeg, speedMps) {
    this.onFix({
      lat: this.position[0],
      lon: this.position[1],
      accuracyM: 5,
      timeMs: Date.now(),
      speedMps,
      courseDeg,
      simulated: true,
    });
  }
}
