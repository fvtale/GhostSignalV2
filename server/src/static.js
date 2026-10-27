import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

const TYPES = {
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
const PAGE_TYPES = new Set(["html", "js", "mjs", "css", "json", "webmanifest", "svg", "png"]);
const ASSET_TYPES = new Set(["ogg", "wav", "flac", "json"]);
const TEXT = new Set(["html", "js", "mjs", "css", "json", "webmanifest", "svg"]);

const BASE_CSP = ["default-src 'self'", "frame-ancestors 'none'", "base-uri 'none'", "form-action 'none'"];
const CSP = {
  // The admin page's map tiles come from OpenStreetMap.
  "/admin": [...BASE_CSP, "img-src 'self' data: https://tile.openstreetmap.org"].join("; "),
  "/account": [...BASE_CSP, "img-src 'self' data:"].join("; "),
  // The player keeps a silent looping track (a blob: URL) so the glasses' buttons reach it.
  "/play": [...BASE_CSP, "img-src 'self' data:", "media-src 'self' blob: data:"].join("; "),
};

const safeRel = (rel) => /^[\w./-]+$/.test(rel) && !rel.split("/").some((part) => !part || part.startsWith("."));

/**
 * The server's static files: the admin page, the account page and the web player (each a folder
 * of pages, scripts and styles), plus the game's own audio and mission files under /play/assets,
 * read straight from the app's assets folder so the web and the app play the same files.
 * Text is gzipped when the browser accepts it; everything carries an ETag.
 */
export function createStatic({ pages, assetsDir }) {
  const zipped = new Map();

  function resolve(pathname) {
    if (pathname.startsWith("/play/assets/")) {
      const rel = pathname.slice("/play/assets/".length);
      if (!safeRel(rel) || !/^(music|mission)\//.test(rel) || !ASSET_TYPES.has(ext(rel))) return null;
      const file = path.join(assetsDir, rel);
      return fs.existsSync(file) ? { file, prefix: null, asset: true } : null;
    }
    for (const [prefix, dir] of Object.entries(pages)) {
      if (pathname === prefix || pathname === `${prefix}/`) {
        const index = path.join(dir, "index.html");
        return fs.existsSync(index) ? { file: index, prefix, asset: false } : null;
      }
      if (!pathname.startsWith(`${prefix}/`)) continue;
      const rel = pathname.slice(prefix.length + 1);
      if (!safeRel(rel) || !PAGE_TYPES.has(ext(rel))) return null;
      const file = path.join(dir, rel);
      return fs.existsSync(file) ? { file, prefix, asset: false } : null;
    }
    return null;
  }

  function ext(name) {
    return name.split(".").pop();
  }

  /** Serves a GET for a static file; returns false when the path isn't one. */
  return function serve(req, res, pathname) {
    if (req.method !== "GET" && req.method !== "HEAD") return false;
    const found = resolve(pathname);
    if (!found) return false;
    const stat = fs.statSync(found.file);
    const etag = `W/"${stat.size.toString(36)}-${Math.floor(stat.mtimeMs).toString(36)}"`;
    const kind = ext(found.file);
    const headers = {
      "content-type": TYPES[kind],
      etag,
      // Pages revalidate every time (cheap with the ETag); audio rarely changes.
      "cache-control": found.asset ? "public, max-age=86400" : "no-cache",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
    };
    if (kind === "html") {
      headers["content-security-policy"] = CSP[found.prefix] ?? BASE_CSP.join("; ");
      headers["permissions-policy"] = "geolocation=(self), microphone=(self), camera=()";
    }
    if (req.headers["if-none-match"] === etag) {
      res.writeHead(304, headers);
      res.end();
      return true;
    }
    let body = fs.readFileSync(found.file);
    if (TEXT.has(kind) && body.length > 1024 && /\bgzip\b/.test(req.headers["accept-encoding"] ?? "")) {
      const cached = zipped.get(found.file);
      if (cached?.etag === etag) body = cached.body;
      else {
        body = zlib.gzipSync(body, { level: 9 });
        zipped.set(found.file, { etag, body });
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
