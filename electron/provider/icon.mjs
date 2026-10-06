// The tray icon, drawn at runtime so the example ships no image files: a ring
// with an anti-aliased edge, as raw premultiplied BGRA pixels for
// nativeImage.createFromBitmap. macOS shows it as a template image (only its
// alpha counts), so it follows the menu bar's light or dark appearance.

// the ring's color where the OS does not tint it, in BGRA order
const ringColor = [0xe0, 0x7a, 0x2f];

// A size by size bitmap of the ring.
export function trayIconBitmap(size) {
  const pixels = Buffer.alloc(size * size * 4);
  const center = size / 2;
  const outerRadius = size * 0.44;
  const innerRadius = size * 0.24;
  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const distance = Math.hypot(x + 0.5 - center, y + 0.5 - center);
      // one pixel of linear falloff on both edges of the ring
      const coverage = Math.max(0, Math.min(1, outerRadius - distance + 0.5, distance - innerRadius + 0.5));
      const offset = (y * size + x) * 4;
      pixels[offset] = Math.round(ringColor[0] * coverage);
      pixels[offset + 1] = Math.round(ringColor[1] * coverage);
      pixels[offset + 2] = Math.round(ringColor[2] * coverage);
      pixels[offset + 3] = Math.round(255 * coverage);
    }
  }
  return pixels;
}
