// The tray icon bitmap, drawn at runtime: its size, its transparent corners
// and center, and premultiplied alpha.

import {test} from "node:test";
import assert from "node:assert/strict";
import {trayIconBitmap} from "../icon.mjs";

test("the tray icon is a ring with transparent corners and center", () => {
  const size = 32;
  const pixels = trayIconBitmap(size);
  assert.equal(pixels.length, size * size * 4);
  const alpha = (x, y) => pixels[(y * size + x) * 4 + 3];
  assert.equal(alpha(0, 0), 0);
  assert.equal(alpha(16, 16), 0);
  // on the ring, between the inner and the outer radius
  assert.equal(alpha(16, 5), 255);
  // premultiplied: no color channel exceeds alpha
  for (let offset = 0; offset < pixels.length; offset += 4) {
    assert.ok(Math.max(pixels[offset], pixels[offset + 1], pixels[offset + 2]) <= pixels[offset + 3]);
  }
});
