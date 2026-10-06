/* The credential-free self-test (PROVIDER_CONTRACT.md, "Self-test"). It
 * checks the disclaimer, the status text, the providing state, the
 * clients-served count, the json values the sdk returns and the installation
 * state files without a network, credentials or a device, and without calling
 * the sdk: `provider --self-test` runs it in the app, and `make self-test`
 * runs it in a binary that does not link the sdk library (selftest_main.c).
 * The data is synthetic; the wallet address is the public Substrate
 * development account. */
#include "provider.h"
#include <stdarg.h>
#include <stdio.h>

#ifndef _WIN32
#include <sys/stat.h>
#include <unistd.h>
#endif

/* sha-256 of the consent disclaimer (utf-8, lf line breaks, no trailing
 * newline), published in PROVIDER_CONTRACT.md for every example to check */
static const char consent_disclaimer_sha256[] =
    "83edee1e45cccd5deb6b86755cc5f6a91b6ade26e7eefc1e7670b5833a95502c";

/* the public Substrate development account, used only as test data */
#define WALLET_A "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY"
#define CLIENT_1 "11111111-1111-1111-1111-111111111111"
#define CLIENT_2 "22222222-2222-2222-2222-222222222222"
#define CLIENT_3 "33333333-3333-3333-3333-333333333333"
#define STREAM_4 "44444444-4444-4444-4444-444444444444"

/* Writes the failure and returns false. */
static bool fail(char *failure, size_t capacity, const char *format, ...) {
  va_list arguments;
  va_start(arguments, format);
  vsnprintf(failure, capacity, format, arguments);
  va_end(arguments);
  return false;
}

/* Rotates a word right. */
static uint32_t rotate_right(uint32_t word, int count) {
  return word >> count | word << (32 - count);
}

/* Mixes one 64-byte block into the sha-256 state. */
static void sha256_block(uint32_t state[8], const uint8_t block[64]) {
  static const uint32_t round_constants[64] = {
      0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
      0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
      0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
      0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
      0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
      0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
      0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
      0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
      0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
      0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
      0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
  uint32_t w[64];
  for (int i = 0; i < 16; i++)
    w[i] = (uint32_t)block[4 * i] << 24 | (uint32_t)block[4 * i + 1] << 16 |
           (uint32_t)block[4 * i + 2] << 8 | block[4 * i + 3];
  for (int i = 16; i < 64; i++) {
    uint32_t s0 = rotate_right(w[i - 15], 7) ^ rotate_right(w[i - 15], 18) ^
                  w[i - 15] >> 3;
    uint32_t s1 = rotate_right(w[i - 2], 17) ^ rotate_right(w[i - 2], 19) ^
                  w[i - 2] >> 10;
    w[i] = w[i - 16] + s0 + w[i - 7] + s1;
  }
  uint32_t v[8];
  memcpy(v, state, sizeof(v));
  for (int i = 0; i < 64; i++) {
    uint32_t s1 =
        rotate_right(v[4], 6) ^ rotate_right(v[4], 11) ^ rotate_right(v[4], 25);
    uint32_t choice = (v[4] & v[5]) ^ (~v[4] & v[6]);
    uint32_t t1 = v[7] + s1 + choice + round_constants[i] + w[i];
    uint32_t s0 =
        rotate_right(v[0], 2) ^ rotate_right(v[0], 13) ^ rotate_right(v[0], 22);
    uint32_t majority = (v[0] & v[1]) ^ (v[0] & v[2]) ^ (v[1] & v[2]);
    memmove(v + 1, v, 7 * sizeof(*v));
    v[4] += t1;
    v[0] = t1 + s0 + majority;
  }
  for (int i = 0; i < 8; i++)
    state[i] += v[i];
}

/* The sha-256 digest of data as lowercase hex. */
static void sha256_hex(const uint8_t *data, size_t length, char hex[65]) {
  uint32_t state[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                       0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
  size_t full_length = length / 64 * 64;
  for (size_t i = 0; i < full_length; i += 64)
    sha256_block(state, data + i);
  /* the last bytes, the 0x80 marker and the bit length fill one or two
   * blocks */
  uint8_t tail[128] = {0};
  size_t rest = length - full_length;
  memcpy(tail, data + full_length, rest);
  tail[rest] = 0x80;
  size_t tail_length = rest + 1 + 8 <= 64 ? 64 : 128;
  uint64_t bit_count = (uint64_t)length * 8;
  for (int i = 0; i < 8; i++)
    tail[tail_length - 1 - i] = (uint8_t)(bit_count >> (8 * i));
  for (size_t i = 0; i < tail_length; i += 64)
    sha256_block(state, tail + i);
  for (int i = 0; i < 8; i++)
    snprintf(hex + 8 * i, 9, "%08x", (unsigned)state[i]);
}

/* The disclaimer is the contract's exact text. */
static bool check_consent_disclaimer(char *failure, size_t capacity) {
  char hex[65];
  sha256_hex((const uint8_t *)"abc", 3, hex);
  if (strcmp(hex, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f"
                  "20015ad"))
    return fail(failure, capacity, "sha-256 of \"abc\" is %s", hex);
  sha256_hex((const uint8_t *)ur_provider_consent_disclaimer,
             strlen(ur_provider_consent_disclaimer), hex);
  if (strcmp(hex, consent_disclaimer_sha256))
    return fail(failure, capacity,
                "consent disclaimer differs from PROVIDER_CONTRACT.md");
  return true;
}

/* Byte counts use binary units with one decimal. */
static bool check_format_byte_count(char *failure, size_t capacity) {
  const struct {
    int64_t byte_count;
    const char *text;
  } cases[] = {
      {0, "0 B"},
      {1023, "1023 B"},
      {1024, "1.0 KiB"},
      {1536, "1.5 KiB"},
      {1048575, "1.0 MiB"},
      {13002342, "12.4 MiB"},
      {(int64_t)5 * 1024 * 1024 * 1024, "5.0 GiB"},
      {(int64_t)3 * 1024 * 1024 * 1024 * 1024, "3.0 TiB"},
      /* an exact tie rounds to even, as Go's %.1f does */
      {1280, "1.2 KiB"},
      {1792, "1.8 KiB"},
      {INT64_MAX, "8.0 EiB"},
  };
  for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
    char text[32];
    ur_provider_format_byte_count(cases[i].byte_count, text, sizeof(text));
    if (strcmp(text, cases[i].text))
      return fail(failure, capacity, "byte count %lld formats as \"%s\", want \"%s\"",
                  (long long)cases[i].byte_count, text, cases[i].text);
  }
  return true;
}

/* The client limit status names the sdk's retry time in UTC, rounded up to the
 * next whole minute; other states never show it. */
static bool check_status_text(char *failure, size_t capacity) {
  const struct {
    const char *state;
    int64_t client_limit_retry_time;
    const char *text;
  } cases[] = {
      /* 2026-10-06 19:05:00.000 UTC, exactly on a minute */
      {UR_PROVIDER_STATE_CLIENT_LIMIT, 1791313500000,
       "client limit, retry at 19:05 UTC"},
      /* 19:04:00.001 rounds up, so the shown time is never before the retry */
      {UR_PROVIDER_STATE_CLIENT_LIMIT, 1791313440001,
       "client limit, retry at 19:05 UTC"},
      /* no retry time */
      {UR_PROVIDER_STATE_CLIENT_LIMIT, 0, "client limit"},
      /* 23:59:00.001 rolls over the hour and the day */
      {UR_PROVIDER_STATE_CLIENT_LIMIT, 1791331140001,
       "client limit, retry at 00:00 UTC"},
      {UR_PROVIDER_STATE_STARTING, 1791313500000, "starting"},
  };
  for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
    char text[64];
    ur_provider_status_text(cases[i].state, cases[i].client_limit_retry_time,
                            text, sizeof(text));
    if (strcmp(text, cases[i].text))
      return fail(failure, capacity,
                  "status text \"%s\" for \"%s\" retrying at %lld, want \"%s\"",
                  text, cases[i].state,
                  (long long)cases[i].client_limit_retry_time, cases[i].text);
  }
  return true;
}

/* The status line matches the contract's golden lines. */
static bool check_status_lines(char *failure, size_t capacity) {
  const struct {
    ur_provider_status status;
    const char *line;
  } cases[] = {
      {{.state = UR_PROVIDER_STATE_STARTING,
        .payout_wallet = UR_PROVIDER_PAYOUT_WALLET_CHECKING},
       "status: starting | clients served: 0 | data provided: 0 B | payout "
       "wallet: checking"},
      {{.state = UR_PROVIDER_STATE_PROVIDING,
        .clients_served = 3,
        .data_provided_byte_count = 13002342,
        .payout_wallet = WALLET_A,
        .payout_wallet_scope = UR_PROVIDER_PAYOUT_WALLET_SCOPE_NETWORK},
       "status: providing | clients served: 3 | data provided: 12.4 MiB | "
       "payout wallet: " WALLET_A " (network)"},
      {{.state = UR_PROVIDER_STATE_PROVIDING,
        .clients_served = 3,
        .data_provided_byte_count = 13002342,
        .payout_wallet = WALLET_A,
        .payout_wallet_scope = UR_PROVIDER_PAYOUT_WALLET_SCOPE_HOTKEY},
       "status: providing | clients served: 3 | data provided: 12.4 MiB | "
       "payout wallet: " WALLET_A " (hotkey)"},
      {{.state = UR_PROVIDER_STATE_PAUSED,
        .clients_served = UR_PROVIDER_CLIENTS_SERVED_LIMIT,
        .clients_served_at_limit = true,
        .data_provided_byte_count = 1536,
        .payout_wallet = WALLET_A,
        .payout_wallet_scope = UR_PROVIDER_PAYOUT_WALLET_SCOPE_PROVIDER},
       "status: paused | clients served: 100000+ | data provided: 1.5 KiB | "
       "payout wallet: " WALLET_A " (this provider)"},
      {{.state = UR_PROVIDER_STATE_STOPPED,
        .payout_wallet = UR_PROVIDER_PAYOUT_WALLET_NOT_SET},
       "status: stopped | clients served: 0 | data provided: 0 B | payout "
       "wallet: not set"},
      {{.state = UR_PROVIDER_STATE_CLIENT_LIMIT,
        .client_limit_retry_time = 1791313500000,
        .payout_wallet = WALLET_A,
        .payout_wallet_scope = UR_PROVIDER_PAYOUT_WALLET_SCOPE_NETWORK},
       "status: client limit, retry at 19:05 UTC | clients served: 0 | data "
       "provided: 0 B | payout wallet: " WALLET_A " (network)"},
  };
  for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
    char line[UR_PROVIDER_LINE_CAPACITY];
    ur_provider_status_line(&cases[i].status, line, sizeof(line));
    if (strcmp(line, cases[i].line))
      return fail(failure, capacity, "status line \"%s\", want \"%s\"", line,
                  cases[i].line);
  }
  return true;
}

/* A change of the status text prints a line at once, including a new client
 * limit retry time; the data counter alone does not. */
static bool check_status_key(char *failure, size_t capacity) {
  ur_provider_status status = {
      .state = UR_PROVIDER_STATE_CLIENT_LIMIT,
      .client_limit_retry_time = 1791313500000,
      .payout_wallet = UR_PROVIDER_PAYOUT_WALLET_CHECKING,
  };
  char key[UR_PROVIDER_LINE_CAPACITY];
  char changed_key[UR_PROVIDER_LINE_CAPACITY];
  ur_provider_status_key(&status, key, sizeof(key));
  /* 19:25 UTC */
  ur_provider_status retried = status;
  retried.client_limit_retry_time = 1791314700000;
  ur_provider_status_key(&retried, changed_key, sizeof(changed_key));
  if (!strcmp(key, changed_key))
    return fail(failure, capacity,
                "a new client limit retry time does not print a status line");
  ur_provider_status counted = status;
  counted.data_provided_byte_count = 1536;
  ur_provider_status_key(&counted, changed_key, sizeof(changed_key));
  if (strcmp(key, changed_key))
    return fail(failure, capacity,
                "the data counter alone prints a status line");
  return true;
}

/* The providing state follows the provide mode, client limit, pause, enable
 * and connected rules, in that order. */
static bool check_provider_state(char *failure, size_t capacity) {
  const char *none = URNET_CLIENT_LIMIT_STATUS_NONE;
  const char *exceeded = URNET_CLIENT_LIMIT_STATUS_EXCEEDED;
  const struct {
    int64_t provide_mode;
    const char *client_limit_status;
    bool provide_paused;
    bool provide_enabled;
    bool provider_connected;
    const char *state;
  } cases[] = {
      {URNET_PROVIDE_MODE_NONE, none, false, false, false,
       UR_PROVIDER_STATE_STOPPED},
      {URNET_PROVIDE_MODE_NETWORK, none, false, true, true,
       UR_PROVIDER_STATE_STOPPED},
      {URNET_PROVIDE_MODE_PUBLIC, none, false, true, false,
       UR_PROVIDER_STATE_STARTING},
      {URNET_PROVIDE_MODE_PUBLIC, none, false, false, true,
       UR_PROVIDER_STATE_STARTING},
      {URNET_PROVIDE_MODE_PUBLIC, none, false, true, true,
       UR_PROVIDER_STATE_PROVIDING},
      {URNET_PROVIDE_MODE_PUBLIC, none, true, true, true,
       UR_PROVIDER_STATE_PAUSED},
      /* the client limit comes after stopped and before every other state */
      {URNET_PROVIDE_MODE_NETWORK, exceeded, false, true, true,
       UR_PROVIDER_STATE_STOPPED},
      {URNET_PROVIDE_MODE_PUBLIC, exceeded, true, true, true,
       UR_PROVIDER_STATE_CLIENT_LIMIT},
      {URNET_PROVIDE_MODE_PUBLIC, exceeded, false, false, false,
       UR_PROVIDER_STATE_CLIENT_LIMIT},
      {URNET_PROVIDE_MODE_PUBLIC, none, true, true, true,
       UR_PROVIDER_STATE_PAUSED},
  };
  for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
    const char *state = ur_provider_state(
        cases[i].provide_mode, cases[i].client_limit_status,
        cases[i].provide_paused, cases[i].provide_enabled,
        cases[i].provider_connected);
    if (strcmp(state, cases[i].state))
      return fail(failure, capacity,
                  "provider state \"%s\" for case %d, want \"%s\"", state,
                  (int)i, cases[i].state);
  }
  return true;
}

/* The payout wallet is labeled by its consent scope first, then by the owner
 * of its mapping. */
static bool check_payout_wallet_scope(char *failure, size_t capacity) {
  const struct {
    const char *wallet_consent_scope;
    const char *wallet_client_id;
    const char *scope;
  } cases[] = {
      /* a hotkey delegation is network-level but not the network's wallet */
      {UR_PROVIDER_SN_WALLET_CONSENT_SCOPE_HOTKEY, "",
       UR_PROVIDER_PAYOUT_WALLET_SCOPE_HOTKEY},
      {UR_PROVIDER_SN_WALLET_CONSENT_SCOPE_HOTKEY, CLIENT_1,
       UR_PROVIDER_PAYOUT_WALLET_SCOPE_HOTKEY},
      {URNET_SN_WALLET_CONSENT_SCOPE_NETWORK, "",
       UR_PROVIDER_PAYOUT_WALLET_SCOPE_NETWORK},
      {"", "", UR_PROVIDER_PAYOUT_WALLET_SCOPE_NETWORK},
      {URNET_SN_WALLET_CONSENT_SCOPE_PROVIDER, CLIENT_1,
       UR_PROVIDER_PAYOUT_WALLET_SCOPE_PROVIDER},
      {"", CLIENT_1, UR_PROVIDER_PAYOUT_WALLET_SCOPE_PROVIDER},
      {URNET_SN_WALLET_CONSENT_SCOPE_PROVIDER, CLIENT_2,
       UR_PROVIDER_PAYOUT_WALLET_SCOPE_ANOTHER_PROVIDER},
  };
  for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
    const char *scope = ur_provider_payout_wallet_scope(
        cases[i].wallet_consent_scope, cases[i].wallet_client_id, CLIENT_1);
    if (strcmp(scope, cases[i].scope))
      return fail(failure, capacity,
                  "payout wallet scope \"%s\" for consent scope \"%s\" and "
                  "client \"%s\", want \"%s\"",
                  scope, cases[i].wallet_consent_scope,
                  cases[i].wallet_client_id, cases[i].scope);
  }
  return true;
}

/* The json reader refuses malformed documents, reads members at the top level
 * of their object only, and decodes strings and integers exactly. */
static bool check_json_reader(char *failure, size_t capacity) {
  const char *malformed[] = {
      "",          "{",         "{\"a\":}",       "{\"a\":1,}",
      "[1,]",      "\"\x01\"",  "{\"a\":1} x",    "01",
      "1.",        "-",         "\"\\q\"",        "{\"a\" 1}",
      "nul",       "[1 2]",     "\"\\u12\"",      "{1:2}",
  };
  for (size_t i = 0; i < sizeof(malformed) / sizeof(*malformed); i++) {
    if (ur_json_parse(malformed[i], strlen(malformed[i])).type != UR_JSON_NONE)
      return fail(failure, capacity, "malformed json \"%s\" was accepted",
                  malformed[i]);
  }
  /* a NUL byte is never part of a well formed document */
  if (ur_json_parse("\"\\\0\"", 4).type != UR_JSON_NONE)
    return fail(failure, capacity, "an escaped NUL byte was accepted");
  ur_json_value document =
      ur_json_parse(" {\"a\": null, \"b\": [1, {\"c\": 2}], \"d\": true} ",
                    strlen(" {\"a\": null, \"b\": [1, {\"c\": 2}], \"d\": "
                           "true} "));
  if (document.type != UR_JSON_OBJECT ||
      ur_json_member(document, "a").type != UR_JSON_NULL ||
      ur_json_member(document, "b").type != UR_JSON_ARRAY ||
      ur_json_member(document, "c").type != UR_JSON_NONE ||
      ur_json_member(document, "d").type != UR_JSON_TRUE ||
      ur_json_member(document, "missing").type != UR_JSON_NONE)
    return fail(failure, capacity, "json member types differ");
  if (ur_json_parse("null", 4).type != UR_JSON_NULL ||
      ur_json_member(ur_json_parse("null", 4), "a").type != UR_JSON_NONE)
    return fail(failure, capacity, "a null document is not null");

  const char *nested = "{\"TransportStats\": [{\"Stats\": "
                       "{\"RemoteEgressByteCount\": 9}}], "
                       "\"RemoteEgressByteCount\": 5}";
  int64_t number = 0;
  if (!ur_json_int64(ur_json_member(ur_json_parse(nested, strlen(nested)),
                                    "RemoteEgressByteCount"),
                     &number) ||
      number != 5)
    return fail(failure, capacity,
                "a nested member was read as the top-level one (%lld)",
                (long long)number);

  const struct {
    const char *json;
    bool valid;
    int64_t number;
  } numbers[] = {
      {"9223372036854775807", true, INT64_MAX},
      {"-9223372036854775808", true, INT64_MIN},
      {"9223372036854775808", false, 0},
      {"-1", true, -1},
      {"1.5", false, 0},
      {"1e3", false, 0},
      {"\"1\"", false, 0},
  };
  for (size_t i = 0; i < sizeof(numbers) / sizeof(*numbers); i++) {
    number = 0;
    bool valid = ur_json_int64(
        ur_json_parse(numbers[i].json, strlen(numbers[i].json)), &number);
    if (valid != numbers[i].valid || (valid && number != numbers[i].number))
      return fail(failure, capacity, "json integer %s read as %lld (%d)",
                  numbers[i].json, (long long)number, valid);
  }

  const char *escaped = "\"q\\\"b\\\\s\\/ \\u00e9 \\ud83d\\ude42 \\ud800\"";
  char *text = ur_json_string(ur_json_parse(escaped, strlen(escaped)));
  bool decoded =
      text && !strcmp(text, "q\"b\\s/ \xc3\xa9 \xf0\x9f\x99\x82 \xef\xbf\xbd");
  free(text);
  if (!decoded)
    return fail(failure, capacity, "json string escapes decode differently");
  text = ur_json_string(ur_json_parse("\"a\\u0000b\"", 10));
  if (text) {
    free(text);
    return fail(failure, capacity, "a json string with \\u0000 was accepted");
  }
  return true;
}

/* The json values of the sdk, in the contract's shapes, with null and missing
 * keys. */
static bool check_sdk_values(char *failure, size_t capacity) {
  const struct {
    const char *json;
    int64_t byte_count;
  } packet_stats[] = {
      {"{\"RemoteEgressByteCount\": 5, \"RemoteIngressByteCount\": 7, "
       "\"LocalEgressByteCount\": 100}",
       12},
      {"{\"TransportStats\": [{\"TransportType\": \"h1\", \"Stats\": "
       "{\"RemoteEgressByteCount\": 40, \"RemoteIngressByteCount\": 60}}], "
       "\"RemoteEgressByteCount\": 5, \"RemoteIngressByteCount\": 7}",
       12},
      {"{\"RemoteEgressByteCount\": 5}", 5},
      {"{}", 0},
      {"null", 0},
      {NULL, 0},
  };
  for (size_t i = 0; i < sizeof(packet_stats) / sizeof(*packet_stats); i++) {
    int64_t byte_count =
        ur_provider_data_provided_byte_count(packet_stats[i].json);
    if (byte_count != packet_stats[i].byte_count)
      return fail(failure, capacity, "data provided %lld for %s, want %lld",
                  (long long)byte_count,
                  packet_stats[i].json ? packet_stats[i].json : "NULL",
                  (long long)packet_stats[i].byte_count);
  }

  const struct {
    const char *json;
    const char *status;
    int64_t retry_time;
  } client_limits[] = {
      {"{\"Status\": \"client_limit_exceeded\", \"RetryTime\": 1791313500000}",
       URNET_CLIENT_LIMIT_STATUS_EXCEEDED, 1791313500000},
      {"{\"Status\": \"\", \"RetryTime\": 0}", URNET_CLIENT_LIMIT_STATUS_NONE,
       0},
      {"{\"Status\": null}", URNET_CLIENT_LIMIT_STATUS_NONE, 0},
      {"{}", URNET_CLIENT_LIMIT_STATUS_NONE, 0},
      /* the call could not run: read as no limit */
      {NULL, URNET_CLIENT_LIMIT_STATUS_NONE, 0},
  };
  for (size_t i = 0; i < sizeof(client_limits) / sizeof(*client_limits); i++) {
    ur_provider_client_limit_status client_limit_status;
    ur_provider_read_client_limit_status(client_limits[i].json,
                                         &client_limit_status);
    if (strcmp(client_limit_status.status, client_limits[i].status) ||
        client_limit_status.retry_time != client_limits[i].retry_time)
      return fail(failure, capacity,
                  "client limit status \"%s\" retrying at %lld for %s",
                  client_limit_status.status,
                  (long long)client_limit_status.retry_time,
                  client_limits[i].json ? client_limits[i].json : "NULL");
  }

  const struct {
    const char *json;
    const char *payout_wallet;
    const char *scope;
  } wallets[] = {
      {"{\"coldkey_ss58\": \"" WALLET_A "\", \"client_id\": \"" CLIENT_1
       "\", \"set_at_millis\": 1, \"consent_scope\": \"provider\"}",
       WALLET_A, UR_PROVIDER_PAYOUT_WALLET_SCOPE_PROVIDER},
      {"{\"coldkey_ss58\": \"" WALLET_A
       "\", \"set_at_millis\": 1, \"consent_scope\": \"network\"}",
       WALLET_A, UR_PROVIDER_PAYOUT_WALLET_SCOPE_NETWORK},
      {"{\"coldkey_ss58\": \"" WALLET_A "\", \"client_id\": null}", WALLET_A,
       UR_PROVIDER_PAYOUT_WALLET_SCOPE_NETWORK},
      {"{\"coldkey_ss58\": \"" WALLET_A "\", \"consent_scope\": \"hotkey\"}",
       WALLET_A, UR_PROVIDER_PAYOUT_WALLET_SCOPE_HOTKEY},
      {"{\"coldkey_ss58\": \"" WALLET_A "\", \"client_id\": \"" CLIENT_2
       "\", \"consent_scope\": \"provider\"}",
       WALLET_A, UR_PROVIDER_PAYOUT_WALLET_SCOPE_ANOTHER_PROVIDER},
      {"{\"coldkey_ss58\": \"\", \"set_at_millis\": 0}", NULL, NULL},
      {"{\"set_at_millis\": 1}", NULL, NULL},
      {"null", NULL, NULL},
      {NULL, NULL, NULL},
  };
  for (size_t i = 0; i < sizeof(wallets) / sizeof(*wallets); i++) {
    const char *scope = NULL;
    char *payout_wallet =
        ur_provider_payout_wallet(wallets[i].json, CLIENT_1, &scope);
    bool same = wallets[i].payout_wallet
                    ? payout_wallet &&
                          !strcmp(payout_wallet, wallets[i].payout_wallet) &&
                          scope && !strcmp(scope, wallets[i].scope)
                    : !payout_wallet && !scope;
    free(payout_wallet);
    if (!same)
      return fail(failure, capacity, "payout wallet differs for %s",
                  wallets[i].json ? wallets[i].json : "NULL");
  }

  const struct {
    const char *result_json;
    const char *error;
    bool succeeded;
  } wallet_reads[] = {
      {"{\"wallet\": {\"coldkey_ss58\": \"" WALLET_A "\"}}", NULL, true},
      {"{\"wallet\": null, \"wallets\": null, \"error\": null}", NULL, true},
      {"{\"error\": {\"code\": \"server_error\", \"message\": \"down\"}}", NULL,
       false},
      {"{\"wallet\": null}", "request failed", false},
      {"null", NULL, false},
      {NULL, NULL, false},
  };
  for (size_t i = 0; i < sizeof(wallet_reads) / sizeof(*wallet_reads); i++) {
    if (ur_provider_wallet_read_succeeded(wallet_reads[i].result_json,
                                          wallet_reads[i].error) !=
        wallet_reads[i].succeeded)
      return fail(failure, capacity, "wallet read outcome differs for %s",
                  wallet_reads[i].result_json ? wallet_reads[i].result_json
                                              : "NULL");
  }
  return true;
}

/* Contract peers resolve by direction and count once per client, up to the
 * limit. */
static bool check_clients_served(char *failure, size_t capacity) {
  /* the peer is the source of a receive contract and the destination of a
   * send contract */
  const struct {
    const char *json;
    bool receive;
    const char *peer_key;
  } cases[] = {
      {"{\"ContractId\": \"55555555-5555-5555-5555-555555555555\", "
       "\"ContractTransferPath\": {\"SourceId\": \"" CLIENT_2
       "\", \"DestinationId\": \"" CLIENT_1
       "\", \"StreamId\": null}, \"Status\": \"open\"}",
       true, CLIENT_2},
      {"{\"ContractId\": \"66666666-6666-6666-6666-666666666666\", "
       "\"ContractTransferPath\": {\"SourceId\": \"" CLIENT_1
       "\", \"DestinationId\": \"" CLIENT_2 "\", \"StreamId\": null}}",
       false, CLIENT_2},
      {"{\"ContractId\": \"77777777-7777-7777-7777-777777777777\", "
       "\"ContractTransferPath\": {\"SourceId\": "
       "\"00000000-0000-0000-0000-000000000000\", \"DestinationId\": "
       "\"" CLIENT_1 "\", \"StreamId\": \"" STREAM_4 "\"}}",
       true, "stream:" STREAM_4},
      {"{\"ContractId\": \"88888888-8888-8888-8888-888888888888\", "
       "\"ContractTransferPath\": null}",
       true, "contract:88888888-8888-8888-8888-888888888888"},
      /* a missing path or path id falls back the same way as a null one */
      {"{\"ContractId\": \"88888888-8888-8888-8888-888888888888\"}", true,
       "contract:88888888-8888-8888-8888-888888888888"},
      {"{\"ContractId\": \"88888888-8888-8888-8888-888888888888\", "
       "\"ContractTransferPath\": {\"DestinationId\": \"" CLIENT_1 "\"}}",
       true, "contract:88888888-8888-8888-8888-888888888888"},
      {"{\"ContractId\": null, \"ContractTransferPath\": null}", true, NULL},
      {"null", true, NULL},
      {NULL, true, NULL},
  };
  for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
    char peer_key[64];
    bool found = ur_provider_contract_peer_key(cases[i].json, cases[i].receive,
                                               peer_key, sizeof(peer_key));
    bool same = cases[i].peer_key ? found && !strcmp(peer_key, cases[i].peer_key)
                                  : !found;
    if (!same)
      return fail(failure, capacity, "contract peer key \"%s\" for %s, want \"%s\"",
                  found ? peer_key : "", cases[i].json ? cases[i].json : "NULL",
                  cases[i].peer_key ? cases[i].peer_key : "");
  }

  /* both directions of one client count once */
  ur_provider_clients_served served;
  ur_provider_clients_served_init(&served, 2);
  ur_provider_clients_served_add(&served, cases[0].json, true);
  ur_provider_clients_served_add(&served, cases[1].json, false);
  ur_provider_clients_served_add(
      &served,
      "{\"ContractId\": \"99999999-9999-9999-9999-999999999999\", "
      "\"ContractTransferPath\": {\"SourceId\": \"" CLIENT_2
      "\", \"DestinationId\": \"" CLIENT_1 "\"}}",
      true);
  size_t count;
  bool at_limit;
  ur_provider_clients_served_count(&served, &count, &at_limit);
  bool passed = count == 1 && !at_limit;
  if (passed) {
    ur_provider_clients_served_add(
        &served,
        "{\"ContractId\": \"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\", "
        "\"ContractTransferPath\": {\"SourceId\": \"" CLIENT_3
        "\", \"DestinationId\": \"" CLIENT_1 "\"}}",
        true);
    ur_provider_clients_served_count(&served, &count, &at_limit);
    passed = count == 2 && !at_limit;
  }
  if (passed) {
    /* a third distinct peer reaches the limit of 2 */
    ur_provider_clients_served_add(&served, cases[2].json, true);
    ur_provider_clients_served_count(&served, &count, &at_limit);
    passed = count == 2 && at_limit;
  }
  ur_provider_clients_served_free(&served);
  if (!passed)
    return fail(failure, capacity, "clients served %d (at limit %d)",
                (int)count, at_limit);

  /* the set grows past its first table without losing a peer */
  ur_provider_clients_served_init(&served, UR_PROVIDER_CLIENTS_SERVED_LIMIT);
  for (int round = 0; round < 2; round++) {
    for (int i = 0; i < 1000; i++) {
      char json[160];
      snprintf(json, sizeof(json),
               "{\"ContractTransferPath\": {\"SourceId\": "
               "\"%08x-0000-4000-8000-000000000000\"}}",
               (unsigned)(i + 1));
      ur_provider_clients_served_add(&served, json, true);
    }
  }
  ur_provider_clients_served_count(&served, &count, &at_limit);
  ur_provider_clients_served_free(&served);
  if (count != 1000 || at_limit)
    return fail(failure, capacity, "1000 clients counted as %d (at limit %d)",
                (int)count, at_limit);
  return true;
}

/* Base64 round trips, and malformed text is refused. */
static bool check_base64(char *failure, size_t capacity) {
  const struct {
    const char *bytes;
    const char *text;
  } cases[] = {
      {"", ""},           {"f", "Zg=="},        {"fo", "Zm8="},
      {"foo", "Zm9v"},    {"foob", "Zm9vYg=="}, {"\xfb\xff", "+/8="},
  };
  for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
    size_t length = strlen(cases[i].bytes);
    char *text = ur_provider_base64_encode((const uint8_t *)cases[i].bytes,
                                           length);
    ur_bytes bytes;
    bool decoded = ur_provider_base64_decode(cases[i].text,
                                             strlen(cases[i].text), false,
                                             &bytes);
    bool same = text && !strcmp(text, cases[i].text) && decoded &&
                bytes.length == length &&
                (!length || !memcmp(bytes.data, cases[i].bytes, length));
    free(text);
    ur_bytes_free(&bytes);
    if (!same)
      return fail(failure, capacity, "base64 \"%s\" does not round trip",
                  cases[i].text);
  }
  /* the url alphabet, with or without padding, as jwt segments use it */
  const char *url_texts[] = {"-_8", "-_8="};
  for (size_t i = 0; i < 2; i++) {
    ur_bytes bytes;
    bool same = ur_provider_base64_decode(url_texts[i], strlen(url_texts[i]),
                                          true, &bytes) &&
                bytes.length == 2 && bytes.data[0] == 0xfb &&
                bytes.data[1] == 0xff;
    ur_bytes_free(&bytes);
    if (!same)
      return fail(failure, capacity, "base64url \"%s\" decodes differently",
                  url_texts[i]);
  }
  const char *standard_invalid[] = {"Zg=", "Zg", "Zm9v!", "Z===", "Zg==Zg==",
                                    "-_8="};
  for (size_t i = 0; i < sizeof(standard_invalid) / sizeof(*standard_invalid);
       i++) {
    ur_bytes bytes;
    if (ur_provider_base64_decode(standard_invalid[i],
                                  strlen(standard_invalid[i]), false, &bytes)) {
      ur_bytes_free(&bytes);
      return fail(failure, capacity, "invalid base64 \"%s\" was accepted",
                  standard_invalid[i]);
    }
  }
  ur_bytes bytes;
  if (ur_provider_base64_decode("Zm9vY", 5, true, &bytes) ||
      ur_provider_base64_decode("+/8", 3, true, &bytes))
    return fail(failure, capacity, "invalid base64url was accepted");
  return true;
}

/* Uuids are read in canonical form, and new ones are random version 4 uuids. */
static bool check_uuid(char *failure, size_t capacity) {
  char uuid[UR_PROVIDER_ID_CAPACITY];
  const char *upper = "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE";
  if (!ur_provider_parse_uuid(upper, strlen(upper), uuid) ||
      strcmp(uuid, "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"))
    return fail(failure, capacity, "uuid \"%s\" is not canonical", upper);
  const char *invalid[] = {"", "not-a-uuid",
                           "11111111-1111-1111-1111-11111111111",
                           "11111111-1111-1111-1111-1111111111111",
                           "11111111x1111-1111-1111-111111111111",
                           "1111111g-1111-1111-1111-111111111111"};
  for (size_t i = 0; i < sizeof(invalid) / sizeof(*invalid); i++) {
    if (ur_provider_parse_uuid(invalid[i], strlen(invalid[i]), uuid))
      return fail(failure, capacity, "invalid uuid \"%s\" was accepted",
                  invalid[i]);
  }
  char other[UR_PROVIDER_ID_CAPACITY];
  char parsed[UR_PROVIDER_ID_CAPACITY];
  if (!ur_provider_new_uuid(uuid) || !ur_provider_new_uuid(other) ||
      !ur_provider_parse_uuid(uuid, strlen(uuid), parsed) ||
      strcmp(uuid, parsed) || uuid[14] != '4' ||
      !strchr("89ab", uuid[19]) || !strcmp(uuid, other))
    return fail(failure, capacity, "new uuid \"%s\" is not a random uuid",
                uuid);
  return true;
}

/* A synthetic, unsigned jwt with the given payload json. */
static void self_test_jwt(const char *payload_json, char *jwt,
                          size_t capacity) {
  char *payload = ur_provider_base64_encode((const uint8_t *)payload_json,
                                            strlen(payload_json));
  /* the url alphabet without padding */
  for (char *p = payload; p && *p; p++) {
    if (*p == '+')
      *p = '-';
    else if (*p == '/')
      *p = '_';
    else if (*p == '=')
      *p = 0;
  }
  snprintf(jwt, capacity, "e30.%s.test", payload ? payload : "");
  free(payload);
}

/* Only a JWT with a valid client_id claim is a client credential. */
static bool check_client_jwt_claims(char *failure, size_t capacity) {
  char jwt[256];
  char client_id[UR_PROVIDER_ID_CAPACITY];
  char error[UR_PROVIDER_ERROR_CAPACITY];
  self_test_jwt("{\"client_id\":\"" CLIENT_1 "\",\"network_id\":\"" CLIENT_2
                "\"}",
                jwt, sizeof(jwt));
  if (!ur_provider_parse_client_jwt_client_id(jwt, client_id, error,
                                              sizeof(error)) ||
      strcmp(client_id, CLIENT_1))
    return fail(failure, capacity, "client jwt claim refused: %s", error);
  char invalid_jwts[7][256] = {"", "not-a-jwt", "", "", "e30.%%%.test",
                               "e30..test", ""};
  self_test_jwt("{\"network_id\":\"" CLIENT_2 "\"}", invalid_jwts[2],
                sizeof(invalid_jwts[2]));
  self_test_jwt("{\"client_id\":\"not-a-uuid\"}", invalid_jwts[3],
                sizeof(invalid_jwts[3]));
  self_test_jwt("[1]", invalid_jwts[6], sizeof(invalid_jwts[6]));
  for (size_t i = 0; i < sizeof(invalid_jwts) / sizeof(*invalid_jwts); i++) {
    if (ur_provider_parse_client_jwt_client_id(invalid_jwts[i], client_id,
                                               error, sizeof(error)))
      return fail(failure, capacity, "invalid client jwt \"%s\" accepted",
                  invalid_jwts[i]);
  }
  return true;
}

/* Removes the files the state checks create, then the directory. */
static void remove_state_dir(const char *state_dir) {
  const char *names[] = {UR_PROVIDER_CLIENT_JWT_FILE_NAME,
                         UR_PROVIDER_INSTANCE_ID_FILE_NAME,
                         UR_PROVIDER_IDENTITY_FILE_NAME, "client.jwt.link"};
  for (size_t i = 0; i < sizeof(names) / sizeof(*names); i++) {
    char path[UR_PROVIDER_PATH_CAPACITY];
    if (ur_provider_path_join(path, sizeof(path), state_dir, names[i]))
      ur_provider_remove_path(path, false);
  }
  ur_provider_remove_path(state_dir, true);
}

/* Fills bytes with a copy of text. */
static void set_bytes(ur_bytes *bytes, const char *text, size_t length) {
  bytes->data = malloc(length);
  bytes->length = bytes->data ? length : 0;
  if (bytes->data)
    memcpy(bytes->data, text, length);
}

/* The state checks, in a new private directory. */
static bool check_state_files_in(const char *state_dir, char *failure,
                                 size_t capacity) {
  char error[UR_PROVIDER_ERROR_CAPACITY];
  char path[UR_PROVIDER_PATH_CAPACITY];
  ur_provider_path_join(path, sizeof(path), state_dir,
                        UR_PROVIDER_CLIENT_JWT_FILE_NAME);

  /* an atomic private write */
  if (!ur_provider_write_private_file(path, "first\n", 6, error,
                                      sizeof(error)) ||
      !ur_provider_write_private_file(path, "second\n", 7, error,
                                      sizeof(error)))
    return fail(failure, capacity, "private write: %s", error);
  ur_bytes data;
  bool same = ur_provider_read_private_file(path, &data, error,
                                            sizeof(error)) ==
                  UR_PROVIDER_READ_OK &&
              data.length == 7 && !memcmp(data.data, "second\n", 7);
  ur_bytes_free(&data);
  if (!same)
    return fail(failure, capacity, "private file round trip failed (%s)",
                error);
#ifndef _WIN32
  struct stat info;
  if (stat(path, &info) || (info.st_mode & 0777) != 0600)
    return fail(failure, capacity, "private file mode %o",
                (unsigned)(info.st_mode & 0777));
  /* a file or directory that others can read is refused, and so is a
   * symlinked file */
  chmod(path, 0644);
  if (ur_provider_read_private_file(path, &data, error, sizeof(error)) ==
      UR_PROVIDER_READ_OK) {
    ur_bytes_free(&data);
    return fail(failure, capacity, "a group-readable credential file was "
                                   "accepted");
  }
  chmod(path, 0600);
  char link_path[UR_PROVIDER_PATH_CAPACITY];
  ur_provider_path_join(link_path, sizeof(link_path), state_dir,
                        "client.jwt.link");
  if (symlink(path, link_path))
    return fail(failure, capacity, "cannot create a symlink");
  if (ur_provider_read_private_file(link_path, &data, error, sizeof(error)) ==
      UR_PROVIDER_READ_OK) {
    ur_bytes_free(&data);
    return fail(failure, capacity, "a symlinked credential file was accepted");
  }
  chmod(state_dir, 0755);
  bool refused = !ur_provider_check_state_dir(state_dir, error, sizeof(error));
  chmod(state_dir, 0700);
  if (!refused)
    return fail(failure, capacity,
                "a group-readable state directory was accepted");
#endif
  if (!ur_provider_check_state_dir(state_dir, error, sizeof(error)))
    return fail(failure, capacity, "%s", error);

  /* the instance id is created once and reused */
  char instance_id[UR_PROVIDER_ID_CAPACITY];
  char again[UR_PROVIDER_ID_CAPACITY];
  if (!ur_provider_load_or_create_instance_id(state_dir, instance_id, error,
                                              sizeof(error)) ||
      !ur_provider_load_or_create_instance_id(state_dir, again, error,
                                              sizeof(error)) ||
      strcmp(instance_id, again))
    return fail(failure, capacity, "instance id changed or failed (%s)",
                error);

  /* the identity belongs to its client */
  ur_provider_identity identity;
  memset(&identity, 0, sizeof(identity));
  identity.version = UR_PROVIDER_IDENTITY_VERSION;
  snprintf(identity.client_id, sizeof(identity.client_id), "%s", CLIENT_1);
  set_bytes(&identity.client_key_seed,
            "\x01\x01\x01\x01\x01\x01\x01\x01\x01\x01\x01\x01\x01\x01\x01\x01"
            "\x01\x01\x01\x01\x01\x01\x01\x01\x01\x01\x01\x01\x01\x01\x01\x01",
            32);
  set_bytes(&identity.provide_tls_certificate_pem, "synthetic certificate",
            21);
  set_bytes(&identity.provide_tls_private_key_pem, "synthetic private key",
            21);
  set_bytes(&identity.extender_key_seed, "synthetic extender seed", 23);
  bool saved = ur_provider_save_identity(state_dir, &identity, error,
                                         sizeof(error));
  ur_provider_identity loaded;
  bool found = false;
  bool round_trip =
      saved &&
      ur_provider_load_identity(state_dir, CLIENT_1, &loaded, &found, error,
                                sizeof(error)) &&
      found && !strcmp(loaded.client_id, CLIENT_1) &&
      loaded.client_key_seed.length == 32 &&
      !memcmp(loaded.client_key_seed.data, identity.client_key_seed.data, 32) &&
      loaded.provide_tls_certificate_pem.length == 21 &&
      loaded.provide_tls_private_key_pem.length == 21 &&
      !memcmp(loaded.provide_tls_private_key_pem.data,
              "synthetic private key", 21) &&
      loaded.extender_key_seed.length == 23;
  if (found)
    ur_provider_identity_free(&loaded);
  ur_provider_identity_free(&identity);
  if (!round_trip)
    return fail(failure, capacity, "identity round trip failed (%s)", error);
  if (!ur_provider_load_identity(state_dir, CLIENT_2, &loaded, &found, error,
                                 sizeof(error)) ||
      found) {
    if (found)
      ur_provider_identity_free(&loaded);
    return fail(failure, capacity, "another client's identity was used");
  }
  ur_provider_path_join(path, sizeof(path), state_dir,
                        UR_PROVIDER_IDENTITY_FILE_NAME);
  /* a 32-byte seed in base64 */
#define SEED_32 "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
  const char *invalid_identities[] = {
      "{\"version\":1}",
      ("{\"version\":2,\"client_id\":\"" CLIENT_1
       "\",\"client_key_seed\":\"" SEED_32 "\"}"),
      ("{\"version\":1,\"client_id\":\"" CLIENT_1
       "\",\"client_key_seed\":\"AQEB\"}"),
      ("{\"version\":1,\"client_id\":7,\"client_key_seed\":\"" SEED_32
       "\"}"),
      ("{\"version\":1,\"client_id\":\"" CLIENT_1
       "\",\"client_key_seed\":\"" SEED_32 "\",\"extender_key_seed\":7}"),
      "not json",
  };
#undef SEED_32
  for (size_t i = 0;
       i < sizeof(invalid_identities) / sizeof(*invalid_identities); i++) {
    if (!ur_provider_write_private_file(path, invalid_identities[i],
                                        strlen(invalid_identities[i]), error,
                                        sizeof(error)))
      return fail(failure, capacity, "private write: %s", error);
    if (ur_provider_load_identity(state_dir, CLIENT_1, &loaded, &found, error,
                                  sizeof(error))) {
      if (found)
        ur_provider_identity_free(&loaded);
      return fail(failure, capacity, "an invalid identity was accepted: %s",
                  invalid_identities[i]);
    }
  }
  return true;
}

/* State files are private, replaced atomically, created once and bound to
 * their client. */
static bool check_state_files(char *failure, size_t capacity) {
  char state_dir[UR_PROVIDER_PATH_CAPACITY];
  if (!ur_provider_make_temp_dir(state_dir, sizeof(state_dir)))
    return fail(failure, capacity,
                "cannot create a temporary state directory");
  bool passed = check_state_files_in(state_dir, failure, capacity);
  remove_state_dir(state_dir);
  return passed;
}

/* The provider configuration checks, in a new private directory. */
static bool check_provider_config_in(const char *state_dir, char *failure,
                                     size_t capacity) {
  char error[UR_PROVIDER_ERROR_CAPACITY];
  ur_provider_config config;
  if (ur_provider_load_config(state_dir, &config, error, sizeof(error)))
    return fail(failure, capacity,
                "a state directory without client.jwt was accepted");
  char path[UR_PROVIDER_PATH_CAPACITY];
  ur_provider_path_join(path, sizeof(path), state_dir,
                        UR_PROVIDER_CLIENT_JWT_FILE_NAME);
  /* a network jwt has no client_id claim */
  char jwt[256];
  char line[260];
  self_test_jwt("{\"network_id\":\"" CLIENT_2 "\"}", jwt, sizeof(jwt));
  snprintf(line, sizeof(line), "%s\n", jwt);
  if (!ur_provider_write_private_file(path, line, strlen(line), error,
                                      sizeof(error)))
    return fail(failure, capacity, "private write: %s", error);
  if (ur_provider_load_config(state_dir, &config, error, sizeof(error))) {
    ur_provider_config_free(&config);
    return fail(failure, capacity, "a network jwt was accepted");
  }
  self_test_jwt("{\"client_id\":\"" CLIENT_1 "\"}", jwt, sizeof(jwt));
  snprintf(line, sizeof(line), "%s\n", jwt);
  if (!ur_provider_write_private_file(path, line, strlen(line), error,
                                      sizeof(error)))
    return fail(failure, capacity, "private write: %s", error);
  if (!ur_provider_load_config(state_dir, &config, error, sizeof(error)))
    return fail(failure, capacity, "first run refused: %s", error);
  /* without an identity the device gets key material 0 and makes a new
   * identity */
  char instance_id[UR_PROVIDER_ID_CAPACITY];
  bool first_run =
      !strcmp(config.client_jwt, jwt) && !strcmp(config.client_id, CLIENT_1) &&
      ur_provider_parse_uuid(config.instance_id, strlen(config.instance_id),
                             instance_id) &&
      !config.has_identity;
  ur_provider_config_free(&config);
  if (!first_run)
    return fail(failure, capacity, "first-run configuration differs");
  return true;
}

/* A missing or incomplete installation state is refused; a first run loads. */
static bool check_provider_config(char *failure, size_t capacity) {
  char error[UR_PROVIDER_ERROR_CAPACITY];
  ur_provider_config config;
  if (ur_provider_load_config("", &config, error, sizeof(error)))
    return fail(failure, capacity, "a missing state directory was accepted");
  if (ur_provider_load_config("relative/state", &config, error, sizeof(error)))
    return fail(failure, capacity, "a relative state directory was accepted");
  char state_dir[UR_PROVIDER_PATH_CAPACITY];
  if (!ur_provider_make_temp_dir(state_dir, sizeof(state_dir)))
    return fail(failure, capacity,
                "cannot create a temporary state directory");
  bool passed = check_provider_config_in(state_dir, failure, capacity);
  remove_state_dir(state_dir);
  return passed;
}

/* The command line forms, and the usage error's exit code 78. */
static bool check_usage(char *failure, size_t capacity) {
  char program[] = "provider";
  char run[] = "run";
  char self_test[] = "--self-test";
  char version[] = "--version";
  char unknown[] = "--unknown";
  const struct {
    int argc;
    char *argv[3];
    ur_provider_command command;
  } cases[] = {
      {1, {program, NULL, NULL}, UR_PROVIDER_COMMAND_RUN},
      {2, {program, run, NULL}, UR_PROVIDER_COMMAND_RUN},
      {2, {program, self_test, NULL}, UR_PROVIDER_COMMAND_SELF_TEST},
      {2, {program, version, NULL}, UR_PROVIDER_COMMAND_VERSION},
      {2, {program, unknown, NULL}, UR_PROVIDER_COMMAND_USAGE},
      {3, {program, run, unknown}, UR_PROVIDER_COMMAND_USAGE},
  };
  for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
    char *argv[3] = {cases[i].argv[0], cases[i].argv[1], cases[i].argv[2]};
    if (ur_provider_parse_command(cases[i].argc, argv) != cases[i].command)
      return fail(failure, capacity, "command line case %d parses differently",
                  (int)i);
  }
  if (UR_PROVIDER_EXIT_USAGE != 78)
    return fail(failure, capacity, "usage error exit code %d, want 78",
                UR_PROVIDER_EXIT_USAGE);
  return true;
}

/* Runs every check and keeps the first failure. */
bool ur_provider_self_test(char *failure, size_t capacity) {
  bool (*const checks[])(char *, size_t) = {
      check_consent_disclaimer, check_format_byte_count,
      check_status_text,        check_status_lines,
      check_status_key,         check_provider_state,
      check_payout_wallet_scope, check_json_reader,
      check_sdk_values,         check_clients_served,
      check_base64,             check_uuid,
      check_client_jwt_claims,  check_state_files,
      check_provider_config,    check_usage,
  };
  for (size_t i = 0; i < sizeof(checks) / sizeof(*checks); i++) {
    if (!checks[i](failure, capacity))
      return false;
  }
  return true;
}

/* Runs the self-test and prints one line: 0 when it passed, 1 when not. */
int ur_provider_self_test_main(void) {
  char failure[UR_PROVIDER_ERROR_CAPACITY * 2];
  if (!ur_provider_self_test(failure, sizeof(failure))) {
    fprintf(stderr, "provider self-test failed: %s\n", failure);
    return UR_PROVIDER_EXIT_FAILURE;
  }
  ur_provider_print_line("provider self-test passed");
  return UR_PROVIDER_EXIT_STOPPED;
}
