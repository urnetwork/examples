#ifndef URMS_CODEC_H
#define URMS_CODEC_H
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#define URMS_SUBPROTOCOL 4096
#define URMS_MAX_FRAME 4112
typedef struct {
  uint8_t kind;
  uint64_t id;
  const uint8_t *text;
  size_t length;
} urms_frame;
/* Validate Unicode scalars: reject overlong encodings, surrogates and
 * >U+10FFFF. */
static int urms_utf8(const uint8_t *p, size_t n) {
  for (size_t i = 0; i < n;) {
    uint32_t c = p[i++], min = 0;
    unsigned more = 0;
    if (c < 0x80)
      continue;
    if (c >= 0xc2 && c <= 0xdf) {
      c &= 0x1f;
      more = 1;
      min = 0x80;
    } else if (c >= 0xe0 && c <= 0xef) {
      c &= 15;
      more = 2;
      min = 0x800;
    } else if (c >= 0xf0 && c <= 0xf4) {
      c &= 7;
      more = 3;
      min = 0x10000;
    } else
      return 0;
    if (n - i < more)
      return 0;
    while (more--) {
      uint8_t v = p[i++];
      if ((v & 0xc0) != 0x80)
        return 0;
      c = (c << 6) | (v & 0x3f);
    }
    if (c < min || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff))
      return 0;
  }
  return 1;
}
static size_t urms_encode(uint8_t *out, size_t capacity, uint8_t kind,
                          uint64_t id, const uint8_t *text, size_t n) {
  if (!id || (kind != 1 && kind != 2) || n > 4096 || (kind == 2 && n) ||
      capacity < 16 + n || !urms_utf8(text, n))
    return 0;
  memcpy(out, "URMS", 4);
  out[4] = 1;
  out[5] = kind;
  out[6] = (uint8_t)(n >> 8);
  out[7] = (uint8_t)n;
  for (unsigned i = 0; i < 8; i++)
    out[8 + i] = (uint8_t)(id >> (56 - 8 * i));
  if (n)
    memcpy(out + 16, text, n);
  return 16 + n;
}
static int urms_decode(const uint8_t *b, size_t n, urms_frame *f) {
  if (n < 16 || n > URMS_MAX_FRAME || memcmp(b, "URMS", 4) || b[4] != 1 ||
      (b[5] != 1 && b[5] != 2) || (((size_t)b[6] << 8) | b[7]) != n - 16 ||
      (b[5] == 2 && n != 16) || !urms_utf8(b + 16, n - 16))
    return 0;
  uint64_t id = 0;
  for (unsigned i = 8; i < 16; i++)
    id = (id << 8) | b[i];
  if (!id)
    return 0;
  f->kind = b[5];
  f->id = id;
  f->text = b + 16;
  f->length = n - 16;
  return 1;
}
static inline int urms_self_test(void) {
  const uint8_t golden[] = {0x55, 0x52, 0x4d, 0x53, 1, 1, 0, 2,    0,
                            0,    0,    0,    0,    0, 0, 1, 0x68, 0x69};
  const uint8_t ack[] = {0x55, 0x52, 0x4d, 0x53, 1, 2, 0, 0,
                         0,    0,    0,    0,    0, 0, 0, 1};
  uint8_t b[URMS_MAX_FRAME + 1], text[4097];
  urms_frame f;
  if (urms_encode(b, sizeof(b), 1, 1, (const uint8_t *)"hi", 2) !=
          sizeof(golden) ||
      memcmp(b, golden, sizeof(golden)))
    return 1;
  if (!urms_decode(golden, sizeof(golden), &f) || f.kind != 1 || f.id != 1 ||
      f.length != 2)
    return 1;
  if (urms_encode(b, sizeof(b), 2, 1, NULL, 0) != sizeof(ack) ||
      memcmp(b, ack, sizeof(ack)) || !urms_decode(ack, sizeof(ack), &f))
    return 1;
  memset(text, 'x', sizeof(text));
  size_t n = urms_encode(b, sizeof(b), 1, UINT64_MAX, text, 4096);
  if (n != URMS_MAX_FRAME || !urms_decode(b, n, &f) || f.id != UINT64_MAX)
    return 1;
  b[n] = 0;
  if (urms_decode(b, n + 1, &f))
    return 1;
  const uint8_t unicode[] = {0xc3, 0xa9, 0xf0, 0x9f, 0x99, 0x82, 0};
  n = urms_encode(b, sizeof(b), 1, 7, unicode, sizeof(unicode));
  if (!n || !urms_decode(b, n, &f) || f.length != sizeof(unicode))
    return 1;
  n = urms_encode(b, sizeof(b), 1, 1, NULL, 0);
  if (!n || !urms_decode(b, n, &f))
    return 1;
  for (size_t i = 0; i < sizeof(golden); i++)
    if (urms_decode(golden, i, &f))
      return 1;
  const unsigned edits[][2] = {{0, 0},  {4, 2}, {5, 3}, {5, 2},
                               {6, 16}, {7, 1}, {15, 0}};
  for (size_t i = 0; i < sizeof(edits) / sizeof(*edits); i++) {
    memcpy(b, golden, sizeof(golden));
    b[edits[i][0]] = (uint8_t)edits[i][1];
    if (urms_decode(b, sizeof(golden), &f))
      return 1;
  }
  memcpy(b, golden, sizeof(golden));
  b[18] = 0;
  if (urms_decode(b, 19, &f))
    return 1;
  b[16] = 0xc0;
  b[17] = 0xaf;
  if (urms_decode(b, 18, &f))
    return 1;
  const uint8_t bad[][4] = {{0xed, 0xa0, 0x80, 0},
                            {0xf4, 0x90, 0x80, 0x80},
                            {0xe2, 0x82, 0, 0},
                            {0x80, 0, 0, 0}};
  for (size_t i = 0; i < 4; i++)
    if (urms_encode(b, sizeof(b), 1, 1, bad[i], 4))
      return 1;
  if (urms_encode(b, sizeof(b), 1, 0, NULL, 0) ||
      urms_encode(b, sizeof(b), 2, 1, text, 1) ||
      urms_encode(b, sizeof(b), 3, 1, NULL, 0) ||
      urms_encode(b, sizeof(b), 1, 1, text, 4097))
    return 1;
  return 0;
}
#endif
