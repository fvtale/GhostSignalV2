import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { createStatic } from "../src/static.js";

function fakeResponse() {
  return {
    status: null,
    headers: null,
    body: null,
    writeHead(status, headers) { this.status = status; this.headers = headers; },
    end(body) { this.body = body ?? null; },
  };
}

test("the Android APK is served as an installable download", () => {
  const downloads = fs.mkdtempSync(path.join(os.tmpdir(), "ghostsignal-downloads-"));
  fs.writeFileSync(path.join(downloads, "ghostsignal.apk"), Buffer.from("fake-apk-bytes"));
  const serve = createStatic({ pages: {}, assetsDir: downloads, landingDir: null, downloadsDir: downloads });

  const res = fakeResponse();
  const handled = serve({ method: "GET", headers: {} }, res, "/download/ghostsignal.apk");
  assert.equal(handled, true);
  assert.equal(res.status, 200);
  assert.equal(res.headers["content-type"], "application/vnd.android.package-archive");
  assert.match(res.headers["content-disposition"], /attachment; filename="ghostsignal\.apk"/);
  assert.equal(res.body.toString(), "fake-apk-bytes");

  // A missing APK is a plain 404 through the normal fallthrough, never a crash.
  const empty = fs.mkdtempSync(path.join(os.tmpdir(), "ghostsignal-downloads-empty-"));
  const serveEmpty = createStatic({ pages: {}, assetsDir: empty, landingDir: null, downloadsDir: empty });
  assert.equal(serveEmpty({ method: "GET", headers: {} }, fakeResponse(), "/download/ghostsignal.apk"), false);
  // Nothing else under /download/ is exposed.
  assert.equal(serve({ method: "GET", headers: {} }, fakeResponse(), "/download/other.apk"), false);
});
