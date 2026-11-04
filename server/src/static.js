import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

// Static hosting for the three browser surfaces (admin, account, web
// player), each a folder of pages/scripts/styles, plus the game's audio and
// mission files under /play/assets read straight from the app's assets so
// web and app always play identical files. Text responses are gzipped when
// accepted; everything carries an ETag and revalidates cheaply.

const MIME_TYPES = {
  html: "text/html; charset=utf-8",
  js: "text/javascript; charset=utf-8",
  mjs: "text/javascript; charset=utf-8",
  css: "text/css; charset=utf-8",
  json: "application/json; charset=utf-8",
  webmanifest: "application/manifest+json; charset=utf-8",
  svg: "image/svg+xml",
  png: "image/png",
  ogg: "audio/ogg",
  wav: "audio/wav",
  flac: "audio/flac",
};

const SERVED_PAGE_EXTENSIONS = new Set(["html", "js", "mjs", "css", "json", "webmanifest", "svg", "png"]);
const SERVED_ASSET_EXTENSIONS = new Set(["ogg", "wav", "flac", "json"]);
const COMPRESSIBLE = new Set(["html", "js", "mjs", "css", "json", "webmanifest", "svg"]);

const BASE_CSP_DIRECTIVES = [
  "default-src 'self'",
  "frame-ancestors 'none'",
  "base-uri 'none'",
  "form-action 'none'",
];

const CSP_BY_PREFIX = {
  // The admin map renders OpenStreetMap tiles.
  "/admin": [...BASE_CSP_DIRECTIVES, "img-src 'self' data: https://tile.openstreetmap.org"].join("; "),
  "/account": [...BASE_CSP_DIRECTIVES, "img-src 'self' data:"].join("; "),
  // The player keeps a silent blob: loop playing so glasses buttons reach it.
  "/play": [...BASE_CSP_DIRECTIVES, "img-src 'self' data:", "media-src 'self' blob: data:"].join("; "),
};

const SAFE_RELATIVE_PATH = /^[\w./-]+$/;

function isSafeRelative(relative) {
  if (!SAFE_RELATIVE_PATH.test(relative)) return false;
  return !relative.split("/").some((part) => part === "" || part.startsWith("."));
}

function extensionOf(name) {
  return name.split(".").pop();
}

export function createStatic({ pages, assetsDir }) {
  const gzipCache = new Map();

  function locate(pathname) {
    if (pathname.startsWith("/play/assets/")) {
      const relative = pathname.slice("/play/assets/".length);
      const allowed = isSafeRelative(relative) &&
        /^(music|mission)\//.test(relative) &&
        SERVED_ASSET_EXTENSIONS.has(extensionOf(relative));
      if (!allowed) return null;
      const file = path.join(assetsDir, relative);
      return fs.existsSync(file) ? { file, prefix: null, isAsset: true } : null;
    }

    for (const [prefix, dir] of Object.entries(pages)) {
      if (pathname === prefix || pathname === `${prefix}/`) {
        const index = path.join(dir, "index.html");
        return fs.existsSync(index) ? { file: index, prefix, isAsset: false } : null;
      }
      if (!pathname.startsWith(`${prefix}/`)) continue;
      const relative = pathname.slice(prefix.length + 1);
      if (!isSafeRelative(relative) || !SERVED_PAGE_EXTENSIONS.has(extensionOf(relative))) {
        return null;
      }
      const file = path.join(dir, relative);
      return fs.existsSync(file) ? { file, prefix, isAsset: false } : null;
    }
    return null;
  }

  /** Serves one static GET/HEAD; returns false when the path is not static. */
  return function serveStatic(req, res, pathname) {
    if (req.method !== "GET" && req.method !== "HEAD") return false;
    const found = locate(pathname);
    if (!found) return false;

    const stat = fs.statSync(found.file);
    const etag = `W/"${stat.size.toString(36)}-${Math.floor(stat.mtimeMs).toString(36)}"`;
    const kind = extensionOf(found.file);

    const headers = {
      "content-type": MIME_TYPES[kind],
      etag,
      // Pages revalidate every visit (cheap via ETag); audio is stable.
      "cache-control": found.isAsset ? "public, max-age=86400" : "no-cache",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
    };
    if (kind === "html") {
      headers["content-security-policy"] = CSP_BY_PREFIX[found.prefix] ?? BASE_CSP_DIRECTIVES.join("; ");
      headers["permissions-policy"] = "geolocation=(self), microphone=(self), camera=()";
    }

    if (req.headers["if-none-match"] === etag) {
      res.writeHead(304, headers);
      res.end();
      return true;
    }

    let body = fs.readFileSync(found.file);
    const wantsGzip = /\bgzip\b/.test(req.headers["accept-encoding"] ?? "");
    if (COMPRESSIBLE.has(kind) && body.length > 1024 && wantsGzip) {
      const cached = gzipCache.get(found.file);
      if (cached?.etag === etag) {
        body = cached.body;
      } else {
        body = zlib.gzipSync(body, { level: 9 });
        gzipCache.set(found.file, { etag, body });
      }
      headers["content-encoding"] = "gzip";
      headers.vary = "accept-encoding";
    }

    headers["content-length"] = body.length;
    res.writeHead(200, headers);
    res.end(req.method === "HEAD" ? undefined : body);
    return true;
  };
}
