/* A small json reader for the values this example needs, with no library:
 * the sdk's PacketStats, ContractDetails, ClientLimitStatus, SnWallet and
 * SnGetWalletResult (PROVIDER_CONTRACT.md, "App lifecycle"), identity.json and
 * the jwt payload. A document is checked in full before any of it is read:
 * one value with only whitespace around it. Members are looked up at the top
 * level of their object only, because the sdk nests the same names deeper
 * (PacketStats repeats its counters under TransportStats). Values are spans of
 * the original text; only ur_json_string allocates. Keys are compared as
 * written, without decoding escapes, and the first of duplicate keys wins. */
#include "provider.h"

/* Nesting deeper than this is refused rather than recursed into. */
#define UR_JSON_DEPTH_LIMIT 64

static const char *skip_value(const char *p, const char *end, int depth,
                              ur_json_type *type);

/* Past the whitespace at p. */
static const char *skip_space(const char *p, const char *end) {
  while (p < end && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r'))
    p++;
  return p;
}

/* Whether c is an ascii digit. */
static bool is_digit(char c) { return '0' <= c && c <= '9'; }

/* The value of one hex digit, or -1. */
static int hex_value(char c) {
  if ('0' <= c && c <= '9')
    return c - '0';
  if ('a' <= c && c <= 'f')
    return c - 'a' + 10;
  if ('A' <= c && c <= 'F')
    return c - 'A' + 10;
  return -1;
}

/* Past the string whose opening quote is at p, or NULL when it is not a well
 * formed string. */
static const char *skip_string(const char *p, const char *end) {
  for (p++; p < end; p++) {
    unsigned char c = (unsigned char)*p;
    if (c == '"')
      return p + 1;
    if (c < 0x20)
      return NULL;
    if (c != '\\')
      continue;
    p++;
    if (p >= end)
      return NULL;
    if (*p == 'u') {
      if (end - p < 5)
        return NULL;
      for (int i = 1; i <= 4; i++) {
        if (hex_value(p[i]) < 0)
          return NULL;
      }
      p += 4;
    } else if (!*p || !strchr("\"\\/bfnrt", *p)) {
      /* strchr would match the terminator of its set for a NUL byte */
      return NULL;
    }
  }
  return NULL;
}

/* Past the number at p, or NULL when it is not a json number. */
static const char *skip_number(const char *p, const char *end) {
  if (p < end && *p == '-')
    p++;
  if (p >= end || !is_digit(*p))
    return NULL;
  if (*p == '0') {
    p++;
  } else {
    while (p < end && is_digit(*p))
      p++;
  }
  if (p < end && *p == '.') {
    p++;
    if (p >= end || !is_digit(*p))
      return NULL;
    while (p < end && is_digit(*p))
      p++;
  }
  if (p < end && (*p == 'e' || *p == 'E')) {
    p++;
    if (p < end && (*p == '+' || *p == '-'))
      p++;
    if (p >= end || !is_digit(*p))
      return NULL;
    while (p < end && is_digit(*p))
      p++;
  }
  return p;
}

/* Past the literal at p, or NULL when p does not start with it. */
static const char *skip_literal(const char *p, const char *end,
                                const char *literal) {
  size_t length = strlen(literal);
  if ((size_t)(end - p) < length || memcmp(p, literal, length))
    return NULL;
  return p + length;
}

/* Past the members of the object whose brace is at p. */
static const char *skip_object(const char *p, const char *end, int depth) {
  p = skip_space(p + 1, end);
  if (p < end && *p == '}')
    return p + 1;
  for (;;) {
    if (p >= end || *p != '"')
      return NULL;
    p = skip_string(p, end);
    if (!p)
      return NULL;
    p = skip_space(p, end);
    if (p >= end || *p != ':')
      return NULL;
    ur_json_type type;
    p = skip_value(skip_space(p + 1, end), end, depth + 1, &type);
    if (!p)
      return NULL;
    p = skip_space(p, end);
    if (p < end && *p == '}')
      return p + 1;
    if (p >= end || *p != ',')
      return NULL;
    p = skip_space(p + 1, end);
  }
}

/* Past the elements of the array whose bracket is at p. */
static const char *skip_array(const char *p, const char *end, int depth) {
  p = skip_space(p + 1, end);
  if (p < end && *p == ']')
    return p + 1;
  for (;;) {
    ur_json_type type;
    p = skip_value(p, end, depth + 1, &type);
    if (!p)
      return NULL;
    p = skip_space(p, end);
    if (p < end && *p == ']')
      return p + 1;
    if (p >= end || *p != ',')
      return NULL;
    p = skip_space(p + 1, end);
  }
}

/* Past the value at p, setting its type, or NULL when it is not well formed
 * or nests deeper than the limit. */
static const char *skip_value(const char *p, const char *end, int depth,
                              ur_json_type *type) {
  if (p >= end || UR_JSON_DEPTH_LIMIT < depth)
    return NULL;
  switch (*p) {
  case '{':
    *type = UR_JSON_OBJECT;
    return skip_object(p, end, depth);
  case '[':
    *type = UR_JSON_ARRAY;
    return skip_array(p, end, depth);
  case '"':
    *type = UR_JSON_STRING;
    return skip_string(p, end);
  case 't':
    *type = UR_JSON_TRUE;
    return skip_literal(p, end, "true");
  case 'f':
    *type = UR_JSON_FALSE;
    return skip_literal(p, end, "false");
  case 'n':
    *type = UR_JSON_NULL;
    return skip_literal(p, end, "null");
  default:
    *type = UR_JSON_NUMBER;
    return skip_number(p, end);
  }
}

/* The document in text[0, length): its one value, or a value of type none
 * when the document is missing or malformed. */
ur_json_value ur_json_parse(const char *text, size_t length) {
  ur_json_value none = {.type = UR_JSON_NONE, .text = NULL, .length = 0};
  if (!text)
    return none;
  const char *end = text + length;
  const char *start = skip_space(text, end);
  ur_json_type type;
  const char *value_end = skip_value(start, end, 0, &type);
  if (!value_end || skip_space(value_end, end) != end)
    return none;
  ur_json_value value = {
      .type = type, .text = start, .length = (size_t)(value_end - start)};
  return value;
}

/* The member named key at the top level of an object, or a value of type none
 * when the object has no such member or is not an object. */
ur_json_value ur_json_member(ur_json_value object, const char *key) {
  ur_json_value none = {.type = UR_JSON_NONE, .text = NULL, .length = 0};
  if (object.type != UR_JSON_OBJECT)
    return none;
  const char *end = object.text + object.length;
  size_t key_length = strlen(key);
  const char *p = skip_space(object.text + 1, end);
  while (p < end && *p == '"') {
    const char *key_end = skip_string(p, end);
    if (!key_end)
      return none;
    bool match = (size_t)(key_end - p) == key_length + 2 &&
                 !memcmp(p + 1, key, key_length);
    /* past the colon */
    p = skip_space(skip_space(key_end, end) + 1, end);
    ur_json_type type;
    const char *value_end = skip_value(p, end, 1, &type);
    if (!value_end)
      return none;
    if (match) {
      ur_json_value member = {
          .type = type, .text = p, .length = (size_t)(value_end - p)};
      return member;
    }
    p = skip_space(value_end, end);
    if (p >= end || *p != ',')
      break;
    p = skip_space(p + 1, end);
  }
  return none;
}

/* Reads an integer number. False, leaving number unchanged, for any other
 * value: a fraction, an exponent or a value outside int64. */
bool ur_json_int64(ur_json_value value, int64_t *number) {
  if (value.type != UR_JSON_NUMBER)
    return false;
  const char *p = value.text;
  const char *end = value.text + value.length;
  bool negative = p < end && *p == '-';
  if (negative)
    p++;
  if (p >= end)
    return false;
  /* accumulate as a negative number, whose range includes INT64_MIN */
  int64_t result = 0;
  for (; p < end; p++) {
    if (!is_digit(*p))
      return false;
    int digit = *p - '0';
    if (result < (INT64_MIN + digit) / 10)
      return false;
    result = result * 10 - digit;
  }
  if (!negative) {
    if (result == INT64_MIN)
      return false;
    result = -result;
  }
  *number = result;
  return true;
}

/* Appends one code point as utf-8. */
static char *append_utf8(char *out, uint32_t code_point) {
  if (code_point < 0x80) {
    *out++ = (char)code_point;
  } else if (code_point < 0x800) {
    *out++ = (char)(0xc0 | code_point >> 6);
    *out++ = (char)(0x80 | (code_point & 0x3f));
  } else if (code_point < 0x10000) {
    *out++ = (char)(0xe0 | code_point >> 12);
    *out++ = (char)(0x80 | (code_point >> 6 & 0x3f));
    *out++ = (char)(0x80 | (code_point & 0x3f));
  } else {
    *out++ = (char)(0xf0 | code_point >> 18);
    *out++ = (char)(0x80 | (code_point >> 12 & 0x3f));
    *out++ = (char)(0x80 | (code_point >> 6 & 0x3f));
    *out++ = (char)(0x80 | (code_point & 0x3f));
  }
  return out;
}

/* The four hex digits at p, which the document check already validated. */
static uint32_t hex4(const char *p) {
  uint32_t code_unit = 0;
  for (int i = 0; i < 4; i++)
    code_unit = code_unit << 4 | (uint32_t)hex_value(p[i]);
  return code_unit;
}

/* An owned copy of a string value with its escapes decoded, or NULL for any
 * other value and for a string that contains \u0000, which a C string cannot
 * hold. A lone surrogate becomes U+FFFD, as Go's decoder does. */
char *ur_json_string(ur_json_value value) {
  if (value.type != UR_JSON_STRING)
    return NULL;
  /* decoding never makes the text longer than its quoted form */
  char *text = malloc(value.length + 1);
  if (!text)
    return NULL;
  char *out = text;
  const char *p = value.text + 1;
  const char *end = value.text + value.length - 1;
  while (p < end) {
    if (*p != '\\') {
      *out++ = *p++;
      continue;
    }
    p++;
    char escape = *p++;
    switch (escape) {
    case 'b':
      *out++ = '\b';
      break;
    case 'f':
      *out++ = '\f';
      break;
    case 'n':
      *out++ = '\n';
      break;
    case 'r':
      *out++ = '\r';
      break;
    case 't':
      *out++ = '\t';
      break;
    case 'u': {
      uint32_t code_point = hex4(p);
      p += 4;
      if (code_point == 0) {
        free(text);
        return NULL;
      }
      if (0xd800 <= code_point && code_point < 0xdc00) {
        /* a high surrogate pairs with a following low surrogate */
        if (end - p >= 6 && p[0] == '\\' && p[1] == 'u') {
          uint32_t low = hex4(p + 2);
          if (0xdc00 <= low && low < 0xe000) {
            code_point = 0x10000 + ((code_point - 0xd800) << 10) +
                         (low - 0xdc00);
            p += 6;
          } else {
            code_point = 0xfffd;
          }
        } else {
          code_point = 0xfffd;
        }
      } else if (0xdc00 <= code_point && code_point < 0xe000) {
        code_point = 0xfffd;
      }
      out = append_utf8(out, code_point);
      break;
    }
    default:
      /* a quote, a backslash or a slash stands for itself */
      *out++ = escape;
      break;
    }
  }
  *out = 0;
  return text;
}
