/* The credential-free self-test (EMBED_CONTRACT.md, "Self-test"). It checks
 * the status text and its rules, the data fields, the cap object, the client
 * JWT claim, the token fetch and the cap read against a stand-in server, the
 * installation state files and the configuration errors, without a network,
 * credentials or a device, and without calling the sdk or libcurl:
 * `embed --self-test` runs it in the app, and `make self-test` runs it in a
 * binary that links neither library (selftest_main.c). The data is
 * synthetic. */
#include "embed.h"
#include <stdarg.h>
#include <stdio.h>

#ifndef _WIN32
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

#define CLIENT_1 "11111111-1111-1111-1111-111111111111"
#define CLIENT_2 "22222222-2222-2222-2222-222222222222"
/* jwt payloads (base64url): {"client_id":CLIENT_1}, {"client_id":CLIENT_2},
 * a network JWT's {"network_id":...}, {"client_id":"not-a-uuid"} and an
 * uppercase client id; the header and signature are placeholders */
#define CLIENT_1_JWT                                                           \
  "e30.eyJjbGllbnRfaWQiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEifQ" \
  ".sig"
#define CLIENT_2_JWT                                                           \
  "e30.eyJjbGllbnRfaWQiOiIyMjIyMjIyMi0yMjIyLTIyMjItMjIyMi0yMjIyMjIyMjIyMjIifQ" \
  ".sig"
#define NETWORK_JWT                                                            \
  "e30.eyJuZXR3b3JrX2lkIjoiMzMzMzMzMzMtMzMzMy0zMzMzLTMzMzMtMzMzMzMzMzMzMzMzIn0" \
  ".sig"
#define INVALID_CLIENT_ID_JWT "e30.eyJjbGllbnRfaWQiOiJub3QtYS11dWlkIn0.sig"
#define UPPERCASE_CLIENT_ID_JWT                                                \
  "e30.eyJjbGllbnRfaWQiOiJBQUFBQUFBQS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEifQ" \
  ".sig"

#define TOKEN_SERVER_URL "http://127.0.0.1:8790"
#define DEMO_SESSION "demo-session-0123456789abcdef0123456789"
#define API_URL "http://127.0.0.1:8791"

/* Writes the failure and returns false. */
static bool fail(char *failure, size_t capacity, const char *format, ...) {
  va_list arguments;
  va_start(arguments, format);
  vsnprintf(failure, capacity, format, arguments);
  va_end(arguments);
  return false;
}

/* ----- the stand-in server ----- */

/* One answer of the stand-in server. */
typedef struct {
  /* no answer at all, as when the server is unreachable */
  bool unreachable;
  long status;
  const char *body;
} stand_in_answer;

/* A server that gives its answers in order and keeps the last request. */
typedef struct {
  stand_in_answer answers[4];
  size_t answer_count;
  size_t next;
  size_t request_count;
  char method[8];
  char url[UR_EMBED_URL_CAPACITY];
  char authorization[256];
  char body[256];
  bool has_body;
} stand_in;

/* A stand-in that gives one answer. */
static stand_in one_answer(long status, const char *body) {
  stand_in server;
  memset(&server, 0, sizeof(server));
  server.answers[0].status = status;
  server.answers[0].body = body;
  server.answer_count = 1;
  return server;
}

/* The stand-in's HTTP function. */
static bool stand_in_http(void *context, const ur_embed_http_request *request,
                          ur_embed_http_response *response, char *error,
                          size_t capacity) {
  stand_in *server = context;
  memset(response, 0, sizeof(*response));
  server->request_count += 1;
  snprintf(server->method, sizeof(server->method), "%s", request->method);
  snprintf(server->url, sizeof(server->url), "%s", request->url);
  snprintf(server->authorization, sizeof(server->authorization), "%s",
           request->authorization ? request->authorization : "");
  server->has_body = request->body != NULL;
  snprintf(server->body, sizeof(server->body), "%s",
           request->body ? request->body : "");
  if (server->answer_count <= server->next) {
    snprintf(error, capacity, "the stand-in has no more answers");
    return false;
  }
  stand_in_answer answer = server->answers[server->next++];
  if (answer.unreachable) {
    snprintf(error, capacity, "connection refused");
    return false;
  }
  response->status = answer.status;
  response->body = ur_embed_copy_string(answer.body);
  response->body_length = strlen(answer.body);
  return response->body != NULL;
}

/* ----- temporary state directories ----- */

/* Reads a whole file as text, owned; NULL when it cannot be read. */
static char *read_text_file(const char *path) {
  FILE *file = fopen(path, "rb");
  if (!file)
    return NULL;
  char *text = calloc(1, 4096);
  size_t length = text ? fread(text, 1, 4095, file) : 0;
  fclose(file);
  if (text)
    text[length] = 0;
  return text;
}

/* Writes a whole file as text with the given POSIX mode. */
static bool write_text_file(const char *path, const char *text, int mode) {
#ifdef _WIN32
  (void)mode;
#endif
  FILE *file = fopen(path, "wb");
  if (!file)
    return false;
  bool written = fwrite(text, 1, strlen(text), file) == strlen(text);
  fclose(file);
#ifndef _WIN32
  written = written && chmod(path, (mode_t)mode) == 0;
#endif
  return written;
}

/* Removes a temporary state directory and the files in it. */
static void remove_state_dir(const char *dir) {
#ifndef _WIN32
  DIR *handle = opendir(dir);
  if (handle) {
    struct dirent *entry;
    while ((entry = readdir(handle))) {
      if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, ".."))
        continue;
      char path[UR_EMBED_PATH_CAPACITY];
      if (ur_embed_path_join(path, sizeof(path), dir, entry->d_name))
        unlink(path);
    }
    closedir(handle);
  }
#else
  const char *names[] = {UR_EMBED_CLIENT_JWT_FILE_NAME,
                         UR_EMBED_INSTANCE_ID_FILE_NAME, "file"};
  for (size_t i = 0; i < sizeof(names) / sizeof(*names); i++) {
    char path[UR_EMBED_PATH_CAPACITY];
    if (ur_embed_path_join(path, sizeof(path), dir, names[i]))
      ur_embed_remove_path(path, false);
  }
#endif
  ur_embed_remove_path(dir, true);
}

#ifndef _WIN32
/* The number of entries in a directory, or -1. */
static int count_dir_entries(const char *dir) {
  DIR *handle = opendir(dir);
  if (!handle)
    return -1;
  int count = 0;
  struct dirent *entry;
  while ((entry = readdir(handle))) {
    if (strcmp(entry->d_name, ".") && strcmp(entry->d_name, ".."))
      count += 1;
  }
  closedir(handle);
  return count;
}
#endif

/* ----- checks ----- */

/* Data amounts use decimal units with one decimal, ties to even on the exact
 * value: 1050 bytes is exactly 1.05 kB and shows "1.0 kB". */
static bool check_format_byte_count(char *failure, size_t capacity) {
  const struct {
    int64_t byte_count;
    const char *text;
  } cases[] = {
      {0, "0 B"},
      {999, "999 B"},
      {1000, "1.0 kB"},
      {999949, "999.9 kB"},
      {1050, "1.0 kB"},
      {1150, "1.2 kB"},
      {1250, "1.2 kB"},
      {1750, "1.8 kB"},
      {999950, "1.0 MB"},
      {999999, "1.0 MB"},
      {1234567890, "1.2 GB"},
      {5000000000, "5.0 GB"},
      {10000000000, "10.0 GB"},
      {3000000000000, "3.0 TB"},
      {INT64_MAX, "9.2 EB"},
  };
  for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
    char text[32];
    ur_embed_format_byte_count(cases[i].byte_count, text, sizeof(text));
    if (strcmp(text, cases[i].text))
      return fail(failure, capacity, "byte count %lld shows \"%s\", want \"%s\"",
                  (long long)cases[i].byte_count, text, cases[i].text);
  }
  return true;
}

/* The monthly reset time is in UTC, rounded up to the next minute. */
static bool check_reset_text(char *failure, size_t capacity) {
  const struct {
    const char *period_end;
    const char *text;
  } cases[] = {
      {"2026-11-01T00:00:00Z", "resets 2026-11-01 00:00 UTC"},
      {"2026-10-31T23:59:00.001Z", "resets 2026-11-01 00:00 UTC"},
      {"2026-10-31T19:00:00-05:00", "resets 2026-11-01 00:00 UTC"},
      {"2026-12-31T23:59:30Z", "resets 2027-01-01 00:00 UTC"},
      {"2028-02-29T12:00:00+01:00", "resets 2028-02-29 11:00 UTC"},
      {"2026-11-01T00:00:00.000Z", "resets 2026-11-01 00:00 UTC"},
  };
  for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
    char text[64];
    if (!ur_embed_reset_text(cases[i].period_end, text, sizeof(text)) ||
        strcmp(text, cases[i].text))
      return fail(failure, capacity, "period end %s shows \"%s\", want \"%s\"",
                  cases[i].period_end, text, cases[i].text);
  }
  const char *invalid[] = {"",
                           "soon",
                           "2026-11-01",
                           "2026-11-01T00:00:00",
                           "2026-13-01T00:00:00Z",
                           "2027-02-29T00:00:00Z",
                           "2026-11-01T24:00:00Z",
                           "2026-11-01T00:00:00+0500",
                           "2026-11-01T00:00:00.Z",
                           "2026-11-01T00:00:00Zjunk"};
  for (size_t i = 0; i < sizeof(invalid) / sizeof(*invalid); i++) {
    char text[64];
    if (ur_embed_reset_text(invalid[i], text, sizeof(text)))
      return fail(failure, capacity, "period end \"%s\" parsed", invalid[i]);
  }
  return true;
}

/* The client limit text names the rounded-up retry time. */
static bool check_client_limit_text(char *failure, size_t capacity) {
  const struct {
    int64_t retry_time;
    const char *text;
  } cases[] = {
      {1791313500000, "client limit, retry at 19:05 UTC"},
      {1791313440001, "client limit, retry at 19:05 UTC"},
      {0, "client limit"},
  };
  for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
    char text[64];
    ur_embed_client_limit_text(cases[i].retry_time, text, sizeof(text));
    if (strcmp(text, cases[i].text))
      return fail(failure, capacity, "retry %lld shows \"%s\", want \"%s\"",
                  (long long)cases[i].retry_time, text, cases[i].text);
  }
  return true;
}

/* A cap reading with the given caps; a limit below 0 is no cap. */
static ur_embed_caps reading(int64_t monthly_limit, int64_t monthly_used,
                             int64_t total_limit, int64_t total_used,
                             bool capped, const char *reason,
                             const char *period_end) {
  ur_embed_caps caps;
  ur_embed_caps_init(&caps);
  ur_embed_cap cap;
  memset(&cap, 0, sizeof(cap));
  cap.has_monthly_byte_limit = 0 <= monthly_limit;
  cap.monthly_byte_limit = 0 <= monthly_limit ? monthly_limit : 0;
  cap.monthly_used_byte_count = monthly_used;
  cap.has_total_byte_limit = 0 <= total_limit;
  cap.total_byte_limit = 0 <= total_limit ? total_limit : 0;
  cap.total_used_byte_count = total_used;
  cap.capped = capped;
  snprintf(cap.capped_reason, sizeof(cap.capped_reason), "%s", reason);
  snprintf(cap.monthly_period_end, sizeof(cap.monthly_period_end), "%s",
           period_end);
  ur_embed_caps_apply(&caps, &cap);
  return caps;
}

/* The console status line matches the contract's golden lines. */
static bool check_status_lines(char *failure, size_t capacity) {
  ur_embed_caps checking;
  ur_embed_caps_init(&checking);
  ur_embed_caps unavailable;
  ur_embed_caps_init(&unavailable);
  ur_embed_caps_apply(&unavailable, NULL);
  ur_embed_caps connected = reading(5000000000, 1234567890, -1, 0, false, "", "");
  ur_embed_caps monthly = reading(5000000000, 5000000000, -1, 0, true,
                                  "monthly", "2026-11-01T00:00:00Z");
  ur_embed_caps total =
      reading(-1, 0, 10000000000, 10000000000, true, "total", "");
  ur_embed_caps paused = reading(0, 0, -1, 0, true, "monthly", "");
  ur_embed_caps open = reading(5000000000, 0, -1, 0, false, "", "");
  /* the first reading answers the Embed-not-enabled refusal; and a capped
   * monthly reading, then the refusal */
  ur_embed_caps refused;
  ur_embed_caps_init(&refused);
  ur_embed_caps_clear(&refused);
  ur_embed_caps cleared = reading(5000000000, 5000000000, -1, 0, true,
                                  "monthly", "2026-11-01T00:00:00Z");
  ur_embed_caps_clear(&cleared);
  const struct {
    const char *client_limit_status;
    int64_t retry_time;
    const ur_embed_caps *caps;
    int64_t providers_added;
    const char *line;
  } cases[] = {
      {"", 0, &checking, 0,
       "status: connecting | data this month: checking | data total: checking"},
      {"", 0, &connected, 1,
       "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no "
       "cap"},
      {"", 0, &unavailable, 1,
       "status: connected | data this month: unavailable | data total: "
       "unavailable"},
      {"", 0, &refused, 1,
       "status: connected | data this month: unavailable | data total: "
       "unavailable"},
      {"", 0, &cleared, 1,
       "status: connected | data this month: unavailable | data total: "
       "unavailable"},
      {"", 0, &monthly, 1,
       "status: data cap reached, resets 2026-11-01 00:00 UTC | data this "
       "month: 5.0 GB of 5.0 GB | data total: no cap"},
      {"", 0, &total, 1,
       "status: data cap reached | data this month: no cap | data total: 10.0 "
       "GB of 10.0 GB"},
      {"", 0, &paused, 1,
       "status: paused | data this month: 0 B of 0 B | data total: no cap"},
      {URNET_CLIENT_LIMIT_STATUS_EXCEEDED, 1791313500000, &open, 1,
       "status: client limit, retry at 19:05 UTC | data this month: 0 B of 5.0 "
       "GB | data total: no cap"},
  };
  for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
    ur_embed_status_input input = {
        .started = true,
        .signed_out = false,
        .client_limit_status = cases[i].client_limit_status,
        .client_limit_retry_time = cases[i].retry_time,
        .caps = cases[i].caps,
        .providers_added = cases[i].providers_added,
    };
    char line[UR_EMBED_LINE_CAPACITY];
    ur_embed_status_line(&input, line, sizeof(line));
    if (strcmp(line, cases[i].line))
      return fail(failure, capacity, "status line case %d is \"%s\", want \"%s\"",
                  (int)i, line, cases[i].line);
  }
  return true;
}

/* The status rules apply in the contract's order. */
static bool check_status_rules(char *failure, size_t capacity) {
  ur_embed_caps checking;
  ur_embed_caps_init(&checking);
  ur_embed_caps monthly_zero = reading(0, 0, -1, 0, true, "monthly", "");
  ur_embed_caps total_zero = reading(5000000000, 0, 0, 0, true, "total", "");
  ur_embed_caps monthly =
      reading(5000000000, 5000000000, -1, 0, true, "monthly",
              "2026-11-01T00:00:00Z");
  ur_embed_caps total =
      reading(-1, 0, 10000000000, 10000000000, true, "total", "");
  ur_embed_caps not_capped = reading(5000000000, 0, -1, 0, false, "", "");
  const struct {
    bool started;
    bool signed_out;
    const char *client_limit_status;
    int64_t retry_time;
    const ur_embed_caps *caps;
    int64_t providers_added;
    const char *status;
  } cases[] = {
      {false, false, "", 0, &checking, 0, "stopped"},
      {false, true, "", 0, &checking, 0, "signed out"},
      {true, false, URNET_CLIENT_LIMIT_STATUS_EXCEEDED, 1791313500000,
       &monthly_zero, 3, "client limit, retry at 19:05 UTC"},
      {true, false, "", 0, &monthly_zero, 3, "paused"},
      {true, false, "", 0, &total_zero, 3, "paused"},
      {true, false, "", 0, &monthly, 3,
       "data cap reached, resets 2026-11-01 00:00 UTC"},
      {true, false, "", 0, &total, 0, "data cap reached"},
      {true, false, "", 0, &not_capped, 1, "connected"},
      {true, false, "", 0, &checking, 0, "connecting"},
  };
  for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
    ur_embed_status_input input = {
        .started = cases[i].started,
        .signed_out = cases[i].signed_out,
        .client_limit_status = cases[i].client_limit_status,
        .client_limit_retry_time = cases[i].retry_time,
        .caps = cases[i].caps,
        .providers_added = cases[i].providers_added,
    };
    char status[128];
    ur_embed_status_text(&input, status, sizeof(status));
    if (strcmp(status, cases[i].status))
      return fail(failure, capacity, "status rule case %d is \"%s\", want \"%s\"",
                  (int)i, status, cases[i].status);
  }
  return true;
}

/* The data fields: checking, unavailable, a later failure keeping the last
 * value, the Embed-not-enabled refusal clearing it, no cap for a null limit
 * even with a used count, and used of limit. */
static bool check_data_fields(char *failure, size_t capacity) {
  ur_embed_caps caps;
  ur_embed_caps_init(&caps);
  char text[80];
  ur_embed_data_field(&caps, true, text, sizeof(text));
  if (strcmp(text, "checking"))
    return fail(failure, capacity, "before a reading: \"%s\"", text);
  ur_embed_caps_apply(&caps, NULL);
  ur_embed_data_field(&caps, false, text, sizeof(text));
  if (strcmp(text, "unavailable"))
    return fail(failure, capacity, "after a failed first reading: \"%s\"",
                text);
  ur_embed_cap cap;
  if (!ur_embed_parse_cap(
          "{\"monthly_byte_limit\":null,\"monthly_used_byte_count\":500,"
          "\"total_byte_limit\":2000,\"total_used_byte_count\":1250}",
          strlen("{\"monthly_byte_limit\":null,\"monthly_used_byte_count\":500,"
                 "\"total_byte_limit\":2000,\"total_used_byte_count\":1250}"),
          &cap))
    return fail(failure, capacity, "a cap object did not parse");
  ur_embed_caps_apply(&caps, &cap);
  ur_embed_data_field(&caps, true, text, sizeof(text));
  if (strcmp(text, "no cap"))
    return fail(failure, capacity, "a null limit with a used count: \"%s\"",
                text);
  ur_embed_data_field(&caps, false, text, sizeof(text));
  if (strcmp(text, "1.2 kB of 2.0 kB"))
    return fail(failure, capacity, "used of limit: \"%s\"", text);
  ur_embed_caps_apply(&caps, NULL);
  ur_embed_data_field(&caps, false, text, sizeof(text));
  if (strcmp(text, "1.2 kB of 2.0 kB"))
    return fail(failure, capacity, "a later failure: \"%s\"", text);
  ur_embed_caps_clear(&caps);
  char monthly_text[80];
  ur_embed_data_field(&caps, true, monthly_text, sizeof(monthly_text));
  ur_embed_data_field(&caps, false, text, sizeof(text));
  if (strcmp(monthly_text, "unavailable") || strcmp(text, "unavailable"))
    return fail(failure, capacity,
                "the Embed-not-enabled refusal: \"%s\", \"%s\"",
                monthly_text, text);
  /* the outcome of a cap read: a reading replaces, another failure keeps it,
   * and the Embed-not-enabled refusal clears it */
  ur_embed_caps recorded;
  ur_embed_caps_init(&recorded);
  ur_embed_caps_record(&recorded, UR_EMBED_CAP_READ_OK, &cap);
  ur_embed_caps_record(&recorded, UR_EMBED_CAP_READ_FAILED, &cap);
  if (recorded.state != UR_EMBED_CAPS_READ)
    return fail(failure, capacity, "a failed cap read did not keep the reading");
  ur_embed_caps_record(&recorded, UR_EMBED_CAP_READ_NOT_ENABLED, &cap);
  if (recorded.state != UR_EMBED_CAPS_UNAVAILABLE)
    return fail(failure, capacity,
                "the Embed-not-enabled cap read did not clear the reading");
  return true;
}

/* The cap object: null or absent limits, capped and its reason, an unknown
 * reason read as capped without a reset time, and refused answers. */
static bool check_cap_object(char *failure, size_t capacity) {
  const char *full =
      "{\"client_id\":\"" CLIENT_1 "\",\"monthly_byte_limit\":5000000000,"
      "\"monthly_used_byte_count\":1234567890,"
      "\"monthly_period_start\":\"2026-10-01T00:00:00Z\","
      "\"monthly_period_end\":\"2026-11-01T00:00:00Z\","
      "\"total_byte_limit\":null,\"total_used_byte_count\":99,"
      "\"total_period_start\":\"2026-09-15T00:00:00Z\",\"capped\":false,"
      "\"capped_reason\":\"\"}";
  ur_embed_cap cap;
  if (!ur_embed_parse_cap(full, strlen(full), &cap) ||
      !cap.has_monthly_byte_limit || cap.monthly_byte_limit != 5000000000 ||
      cap.monthly_used_byte_count != 1234567890 ||
      strcmp(cap.monthly_period_end, "2026-11-01T00:00:00Z") ||
      cap.has_total_byte_limit || cap.total_used_byte_count != 99 ||
      cap.capped || cap.capped_reason[0])
    return fail(failure, capacity, "the full cap object reads differently");
  const char *absent = "{\"capped\":false}";
  if (!ur_embed_parse_cap(absent, strlen(absent), &cap) ||
      cap.has_monthly_byte_limit || cap.has_total_byte_limit ||
      cap.monthly_used_byte_count || cap.total_used_byte_count)
    return fail(failure, capacity, "absent limits read as caps");
  /* an unknown reason is capped, without a reset time and without a pause */
  const char *unknown = "{\"monthly_byte_limit\":0,\"capped\":true,"
                        "\"capped_reason\":\"weekly\","
                        "\"monthly_period_end\":\"2026-11-01T00:00:00Z\"}";
  if (!ur_embed_parse_cap(unknown, strlen(unknown), &cap) || !cap.capped)
    return fail(failure, capacity, "an unknown reason did not read as capped");
  ur_embed_caps caps;
  ur_embed_caps_init(&caps);
  ur_embed_caps_apply(&caps, &cap);
  ur_embed_status_input input = {.started = true,
                                 .client_limit_status = "",
                                 .caps = &caps,
                                 .providers_added = 1};
  char status[128];
  ur_embed_status_text(&input, status, sizeof(status));
  if (strcmp(status, "data cap reached"))
    return fail(failure, capacity, "an unknown reason shows \"%s\"", status);
  /* a monthly cap whose period end does not parse */
  const char *no_end = "{\"monthly_byte_limit\":5,\"monthly_used_byte_count\":5,"
                       "\"monthly_period_end\":\"soon\",\"capped\":true,"
                       "\"capped_reason\":\"monthly\"}";
  if (!ur_embed_parse_cap(no_end, strlen(no_end), &cap))
    return fail(failure, capacity, "a cap object with a bad end did not parse");
  ur_embed_caps_apply(&caps, &cap);
  ur_embed_status_text(&input, status, sizeof(status));
  if (strcmp(status, "data cap reached"))
    return fail(failure, capacity, "an unreadable period end shows \"%s\"",
                status);
  const char *refused[] = {"{\"error\":{\"message\":\"no\"}}",
                           "{\"monthly_byte_limit\":\"5\"}",
                           "{\"monthly_used_byte_count\":1.5}",
                           "{\"capped\":\"yes\"}",
                           "{\"capped_reason\":7}",
                           "[]",
                           "null",
                           "not json"};
  for (size_t i = 0; i < sizeof(refused) / sizeof(*refused); i++) {
    if (ur_embed_parse_cap(refused[i], strlen(refused[i]), &cap) ||
        ur_embed_cap_not_enabled(refused[i], strlen(refused[i])))
      return fail(failure, capacity, "%s read as a cap object", refused[i]);
  }
  /* the Embed-not-enabled refusal is no cap object, and is recognized */
  const char *not_enabled =
      "{\"error\":{\"message\":\"Embed isn't enabled for this network.\"}}";
  if (ur_embed_parse_cap(not_enabled, strlen(not_enabled), &cap) ||
      !ur_embed_cap_not_enabled(not_enabled, strlen(not_enabled)))
    return fail(failure, capacity, "the Embed-not-enabled refusal misread");
  return true;
}

/* Only a JWT with a valid client_id claim is a client credential. */
static bool check_client_jwt_claims(char *failure, size_t capacity) {
  char client_id[UR_EMBED_ID_CAPACITY];
  char error[UR_EMBED_ERROR_CAPACITY];
  if (!ur_embed_parse_client_jwt_client_id(CLIENT_1_JWT, client_id, error,
                                           sizeof(error)) ||
      strcmp(client_id, CLIENT_1))
    return fail(failure, capacity, "a client JWT was refused");
  if (!ur_embed_parse_client_jwt_client_id(UPPERCASE_CLIENT_ID_JWT, client_id,
                                           error, sizeof(error)) ||
      strcmp(client_id, "aaaaaaaa-1111-1111-1111-111111111111"))
    return fail(failure, capacity, "an uppercase client id is not canonical");
  const char *refused[] = {NETWORK_JWT, INVALID_CLIENT_ID_JWT, "",
                           "not-a-jwt", "a..b", "a.b.c.d", "e30.!!!.sig",
                           "e30." "eyJjbGllbnRfaWQiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEifQ" "."};
  for (size_t i = 0; i < sizeof(refused) / sizeof(*refused); i++) {
    if (ur_embed_parse_client_jwt_client_id(refused[i], client_id, error,
                                            sizeof(error)))
      return fail(failure, capacity, "JWT case %d was accepted", (int)i);
  }
  return true;
}

/* Origins follow the allocators' rules. */
static bool check_origin_url(char *failure, size_t capacity) {
  const struct {
    const char *origin;
    const char *url;
  } accepted[] = {
      {"https://api.bringyour.com", "https://api.bringyour.com/x"},
      {"https://api.bringyour.com/", "https://api.bringyour.com/x"},
      {"https://example.com:8443", "https://example.com:8443/x"},
      {"http://localhost:8790", "http://localhost:8790/x"},
      {"http://127.0.0.1", "http://127.0.0.1/x"},
      {"http://[::1]:8790", "http://[::1]:8790/x"},
      {"https://[2001:db8::1]", "https://[2001:db8::1]/x"},
  };
  for (size_t i = 0; i < sizeof(accepted) / sizeof(*accepted); i++) {
    char url[UR_EMBED_URL_CAPACITY];
    if (!ur_embed_origin_url(accepted[i].origin, "/x", url, sizeof(url)) ||
        strcmp(url, accepted[i].url))
      return fail(failure, capacity, "origin %s was refused or changed",
                  accepted[i].origin);
  }
  const char *refused[] = {
      "http://example.com",       "https://example.com/path",
      "https://user@example.com", "https://example.com?x=1",
      "https://example.com#f",    "ftp://example.com",
      "https://",                 "https://exa mple.com",
      "https://example.com:0",    "https://example.com:99999",
      "https://example.com:",     "localhost:8790",
      "http://127.0.0.2",         "http://[::2]"};
  for (size_t i = 0; i < sizeof(refused) / sizeof(*refused); i++) {
    char url[UR_EMBED_URL_CAPACITY];
    if (ur_embed_origin_url(refused[i], "/x", url, sizeof(url)))
      return fail(failure, capacity, "origin %s was accepted", refused[i]);
  }
  char url[UR_EMBED_URL_CAPACITY];
  if (ur_embed_origin_url(NULL, "/x", url, sizeof(url)))
    return fail(failure, capacity, "no origin was accepted");
  return true;
}

/* Settings for a temporary state directory. */
static ur_embed_settings token_settings(const char *state_dir) {
  ur_embed_settings settings = {
      .state_dir = state_dir,
      .token_server_url = TOKEN_SERVER_URL,
      .demo_session = DEMO_SESSION,
      .api_url = API_URL,
  };
  return settings;
}

/* The token fetch against a stand-in server: the request, saving client.jwt,
 * a mismatched client refused, and the answers mapped to exit codes. */
static bool check_token_fetch(char *failure, size_t capacity) {
  char dir[UR_EMBED_PATH_CAPACITY];
  if (!ur_embed_make_temp_dir(dir, sizeof(dir)))
    return fail(failure, capacity, "cannot make a temporary directory");
  bool ok = false;
  char error[UR_EMBED_ERROR_CAPACITY];
  char jwt_path[UR_EMBED_PATH_CAPACITY];
  char instance_path[UR_EMBED_PATH_CAPACITY];
  ur_embed_path_join(jwt_path, sizeof(jwt_path), dir,
                     UR_EMBED_CLIENT_JWT_FILE_NAME);
  ur_embed_path_join(instance_path, sizeof(instance_path), dir,
                     UR_EMBED_INSTANCE_ID_FILE_NAME);
  ur_embed_settings settings = token_settings(dir);
  ur_embed_config config;
  char *saved = NULL;
  char *instance_id = NULL;

  stand_in server = one_answer(
      200, "{\"client_id\":\"" CLIENT_1 "\",\"by_client_jwt\":\"" CLIENT_1_JWT
           "\",\"data_cap\":{\"monthly_byte_limit\":5000000000,"
           "\"monthly_used_byte_count\":1234567890,\"capped\":false}}");
  int code = ur_embed_load_config(&settings, stand_in_http, &server, &config,
                                  error, sizeof(error));
  if (code != 0) {
    fail(failure, capacity, "a token answer gave exit %d: %s", code, error);
    goto done;
  }
  saved = read_text_file(jwt_path);
  instance_id = read_text_file(instance_path);
  bool request_ok =
      !strcmp(server.method, "POST") &&
      !strcmp(server.url, TOKEN_SERVER_URL "/urnetwork/client-token") &&
      !strcmp(server.authorization, DEMO_SESSION) && server.has_body;
  char *installation_id = ur_json_string(ur_json_member(
      ur_json_parse(server.body, strlen(server.body)), "installation_id"));
  request_ok = request_ok && installation_id && instance_id &&
               !strncmp(instance_id, installation_id, 36) &&
               !strcmp(installation_id, config.instance_id);
  free(installation_id);
  if (!request_ok) {
    fail(failure, capacity, "the token request is not the contract's");
    ur_embed_config_free(&config);
    goto done;
  }
  bool loaded = saved && !strcmp(saved, CLIENT_1_JWT "\n") &&
                !strcmp(config.client_id, CLIENT_1) &&
                !strcmp(config.client_jwt, CLIENT_1_JWT) &&
                config.has_first_cap &&
                config.first_cap.monthly_byte_limit == 5000000000;
  ur_embed_config_free(&config);
  if (!loaded) {
    fail(failure, capacity, "the token answer was not saved or kept");
    goto done;
  }
#ifndef _WIN32
  struct stat info;
  if (stat(jwt_path, &info) || (info.st_mode & 077)) {
    fail(failure, capacity, "client.jwt is not private");
    goto done;
  }
  if (count_dir_entries(dir) != 2) {
    fail(failure, capacity, "the state directory has leftover files");
    goto done;
  }
#endif

  /* answers that are refusals (78) or failures (1); client.jwt stays */
  const struct {
    stand_in_answer answer;
    int code;
  } answers[] = {
      {{false, 200,
        "{\"client_id\":\"" CLIENT_2 "\",\"by_client_jwt\":\"" CLIENT_1_JWT
        "\",\"data_cap\":null}"},
       UR_EMBED_EXIT_FAILURE},
      {{false, 200,
        "{\"client_id\":\"" CLIENT_1 "\",\"by_client_jwt\":\"" NETWORK_JWT
        "\"}"},
       UR_EMBED_EXIT_FAILURE},
      {{false, 200, "not json"}, UR_EMBED_EXIT_FAILURE},
      {{false, 401,
        "{\"error\":{\"code\":\"unauthorized\",\"message\":\"unknown demo "
        "session\"}}"},
       UR_EMBED_EXIT_CONFIG},
      {{false, 409,
        "{\"error\":{\"code\":\"installation_limit\",\"message\":\"this user "
        "has 5 installations\"}}"},
       UR_EMBED_EXIT_CONFIG},
      {{false, 409,
        "{\"error\":{\"code\":\"client_limit\",\"message\":\"the network is "
        "at its client limit; see https://ur.io/services\"}}"},
       UR_EMBED_EXIT_CONFIG},
      {{false, 503, "{\"error\":{\"code\":\"busy\",\"message\":\"retry\"}}"},
       UR_EMBED_EXIT_FAILURE},
      {{false, 500, ""}, UR_EMBED_EXIT_FAILURE},
      {{false, 400, "{\"error\":{\"code\":\"invalid_request\"}}"},
       UR_EMBED_EXIT_FAILURE},
      {{true, 0, NULL}, UR_EMBED_EXIT_FAILURE},
  };
  for (size_t i = 0; i < sizeof(answers) / sizeof(*answers); i++) {
    stand_in refusing;
    memset(&refusing, 0, sizeof(refusing));
    refusing.answers[0] = answers[i].answer;
    refusing.answer_count = 1;
    code = ur_embed_load_config(&settings, stand_in_http, &refusing, &config,
                                error, sizeof(error));
    ur_embed_config_free(&config);
    if (code != answers[i].code) {
      fail(failure, capacity, "token answer case %d gave exit %d, want %d",
           (int)i, code, answers[i].code);
      goto done;
    }
    if (i == 3 && !strstr(error, "unknown demo session")) {
      fail(failure, capacity, "a refusal does not show its message");
      goto done;
    }
    char *after = read_text_file(jwt_path);
    bool unchanged = after && !strcmp(after, CLIENT_1_JWT "\n");
    free(after);
    if (!unchanged) {
      fail(failure, capacity, "token answer case %d replaced client.jwt",
           (int)i);
      goto done;
    }
  }
  /* a null data_cap is no first reading */
  stand_in no_cap = one_answer(
      200, "{\"client_id\":\"" CLIENT_1 "\",\"by_client_jwt\":\"" CLIENT_1_JWT
           "\",\"data_cap\":null}");
  code = ur_embed_load_config(&settings, stand_in_http, &no_cap, &config,
                              error, sizeof(error));
  bool no_first_cap = code == 0 && !config.has_first_cap;
  ur_embed_config_free(&config);
  if (!no_first_cap) {
    fail(failure, capacity, "a null data_cap gave a first reading");
    goto done;
  }
  ok = true;
done:
  free(saved);
  free(instance_id);
  remove_state_dir(dir);
  return ok;
}

/* The cap read: the client JWT as the bearer, failed readings, and the
 * Embed-not-enabled refusal. */
static bool check_cap_read(char *failure, size_t capacity) {
  char error[UR_EMBED_ERROR_CAPACITY];
  ur_embed_cap cap;
  stand_in server = one_answer(
      200, "{\"client_id\":\"" CLIENT_1 "\",\"monthly_byte_limit\":0,"
           "\"monthly_used_byte_count\":0,\"capped\":true,"
           "\"capped_reason\":\"monthly\"}");
  if (ur_embed_read_caps(stand_in_http, &server, API_URL, CLIENT_1_JWT, &cap,
                         error, sizeof(error)) != UR_EMBED_CAP_READ_OK ||
      !cap.capped || strcmp(cap.capped_reason, "monthly"))
    return fail(failure, capacity, "a cap answer was not read: %s", error);
  if (strcmp(server.method, "GET") ||
      strcmp(server.url, API_URL "/network/client-data-cap") ||
      strcmp(server.authorization, CLIENT_1_JWT) || server.has_body)
    return fail(failure, capacity, "the cap request is not the contract's");
  const stand_in_answer failed[] = {
      {false, 404, "404 page not found"},
      {false, 200, "{\"error\":{\"message\":\"no such client\"}}"},
      {false, 401, ""},
      {true, 0, NULL},
  };
  for (size_t i = 0; i < sizeof(failed) / sizeof(*failed); i++) {
    stand_in failing;
    memset(&failing, 0, sizeof(failing));
    failing.answers[0] = failed[i];
    failing.answer_count = 1;
    if (ur_embed_read_caps(stand_in_http, &failing, API_URL, CLIENT_1_JWT,
                           &cap, error, sizeof(error)) != UR_EMBED_CAP_READ_FAILED)
      return fail(failure, capacity, "failed cap answer %d was read", (int)i);
  }
  stand_in not_enabled = one_answer(
      200, "{\"error\":{\"message\":\"Embed isn't enabled for this network.\"}}");
  if (ur_embed_read_caps(stand_in_http, &not_enabled, API_URL, CLIENT_1_JWT,
                         &cap, error, sizeof(error)) !=
          UR_EMBED_CAP_READ_NOT_ENABLED ||
      strcmp(error, UR_EMBED_NOT_ENABLED_MESSAGE))
    return fail(failure, capacity, "the Embed-not-enabled refusal read as \"%s\"",
                error);
  return true;
}

/* State files are private, atomic and created once; a symlink is refused. */
static bool check_state_files(char *failure, size_t capacity) {
  char dir[UR_EMBED_PATH_CAPACITY];
  if (!ur_embed_make_temp_dir(dir, sizeof(dir)))
    return fail(failure, capacity, "cannot make a temporary directory");
  bool ok = false;
  char error[UR_EMBED_ERROR_CAPACITY];
  char path[UR_EMBED_PATH_CAPACITY];
  ur_embed_path_join(path, sizeof(path), dir, "file");
  char first[UR_EMBED_ID_CAPACITY];
  char second[UR_EMBED_ID_CAPACITY];
  ur_bytes data;
  if (!ur_embed_check_state_dir(dir, error, sizeof(error))) {
    fail(failure, capacity, "a private state directory was refused: %s", error);
    goto done;
  }
  if (!ur_embed_write_private_file(path, "one", 3, error, sizeof(error)) ||
      !ur_embed_write_private_file(path, "two", 3, error, sizeof(error)) ||
      ur_embed_read_private_file(path, &data, error, sizeof(error)) !=
          UR_EMBED_READ_OK) {
    fail(failure, capacity, "a state file did not round trip: %s", error);
    goto done;
  }
  bool replaced = data.length == 3 && !memcmp(data.data, "two", 3);
  ur_bytes_free(&data);
  if (!replaced) {
    fail(failure, capacity, "a state file was not replaced");
    goto done;
  }
  if (!ur_embed_load_or_create_instance_id(dir, first, error, sizeof(error)) ||
      !ur_embed_load_or_create_instance_id(dir, second, error,
                                           sizeof(error)) ||
      strcmp(first, second)) {
    fail(failure, capacity, "instance-id was not created once and reused");
    goto done;
  }
#ifndef _WIN32
  struct stat info;
  if (stat(path, &info) || (info.st_mode & 077)) {
    fail(failure, capacity, "a state file is not private");
    goto done;
  }
  /* only the two files: no temporary file is left behind */
  if (count_dir_entries(dir) != 2) {
    fail(failure, capacity, "a replacement left a temporary file");
    goto done;
  }
  char shared[UR_EMBED_PATH_CAPACITY];
  ur_embed_path_join(shared, sizeof(shared), dir, "shared");
  if (!write_text_file(shared, "x", 0644) ||
      ur_embed_read_private_file(shared, &data, error, sizeof(error)) !=
          UR_EMBED_READ_FAILED) {
    fail(failure, capacity, "a file open to others was read");
    goto done;
  }
  char link[UR_EMBED_PATH_CAPACITY];
  ur_embed_path_join(link, sizeof(link), dir, "link");
  if (symlink(path, link) ||
      ur_embed_read_private_file(link, &data, error, sizeof(error)) !=
          UR_EMBED_READ_FAILED) {
    fail(failure, capacity, "a symlinked state file was read");
    goto done;
  }
  if (chmod(dir, 0755) || ur_embed_check_state_dir(dir, error, sizeof(error))) {
    chmod(dir, 0700);
    fail(failure, capacity, "a state directory open to others was accepted");
    goto done;
  }
  chmod(dir, 0700);
#endif
  char missing[UR_EMBED_PATH_CAPACITY];
  ur_embed_path_join(missing, sizeof(missing), dir, "missing");
  if (ur_embed_read_private_file(missing, &data, error, sizeof(error)) !=
      UR_EMBED_READ_MISSING) {
    fail(failure, capacity, "a missing file did not read as missing");
    goto done;
  }
  ok = true;
done:
  remove_state_dir(dir);
  return ok;
}

/* A missing or relative state directory, no token server and no client.jwt,
 * a network JWT and invalid settings are configuration errors (78). */
static bool check_config(char *failure, size_t capacity) {
  char dir[UR_EMBED_PATH_CAPACITY];
  if (!ur_embed_make_temp_dir(dir, sizeof(dir)))
    return fail(failure, capacity, "cannot make a temporary directory");
  bool ok = false;
  char error[UR_EMBED_ERROR_CAPACITY];
  ur_embed_config config;
  stand_in none;
  memset(&none, 0, sizeof(none));
  char jwt_path[UR_EMBED_PATH_CAPACITY];
  ur_embed_path_join(jwt_path, sizeof(jwt_path), dir,
                     UR_EMBED_CLIENT_JWT_FILE_NAME);
  const ur_embed_settings refused[] = {
      {NULL, NULL, NULL, NULL},
      {"", NULL, NULL, NULL},
      {"relative/state", NULL, NULL, NULL},
      /* no token server and no client.jwt */
      {dir, NULL, NULL, NULL},
      {dir, TOKEN_SERVER_URL, NULL, NULL},
      {dir, NULL, DEMO_SESSION, NULL},
      {dir, "http://example.com", DEMO_SESSION, NULL},
      {dir, TOKEN_SERVER_URL, "has space", NULL},
      {dir, NULL, NULL, "https://example.com/path"},
  };
  for (size_t i = 0; i < sizeof(refused) / sizeof(*refused); i++) {
    int code = ur_embed_load_config(&refused[i], stand_in_http, &none, &config,
                                    error, sizeof(error));
    ur_embed_config_free(&config);
    if (code != UR_EMBED_EXIT_CONFIG) {
      fail(failure, capacity, "configuration case %d gave exit %d, want 78",
           (int)i, code);
      goto done;
    }
  }
  if (none.request_count) {
    fail(failure, capacity, "a configuration error reached the token server");
    goto done;
  }
  ur_embed_settings from_file = {dir, NULL, NULL, NULL};
  if (!write_text_file(jwt_path, NETWORK_JWT "\n", 0600) ||
      ur_embed_load_config(&from_file, stand_in_http, &none, &config, error,
                           sizeof(error)) != UR_EMBED_EXIT_CONFIG) {
    ur_embed_config_free(&config);
    fail(failure, capacity, "a network JWT in client.jwt was accepted");
    goto done;
  }
  ur_embed_config_free(&config);
  if (!write_text_file(jwt_path, "  " CLIENT_1_JWT "\n\n", 0600) ||
      ur_embed_load_config(&from_file, stand_in_http, &none, &config, error,
                           sizeof(error)) != 0 ||
      strcmp(config.client_id, CLIENT_1) ||
      strcmp(config.client_jwt, CLIENT_1_JWT) ||
      strcmp(config.api_url, UR_EMBED_DEFAULT_API_URL) ||
      config.has_first_cap) {
    ur_embed_config_free(&config);
    fail(failure, capacity, "client.jwt from the backend tool did not load");
    goto done;
  }
  ur_embed_config_free(&config);
  ok = true;
done:
  remove_state_dir(dir);
  return ok;
}

/* Unknown arguments are a usage error with exit code 78. */
static bool check_usage(char *failure, size_t capacity) {
  char program[] = "embed";
  char run[] = "run";
  char self_test[] = "--self-test";
  char licenses[] = "--licenses";
  char version[] = "--version";
  char unknown[] = "--unknown";
  const struct {
    int argc;
    char *argv[3];
    ur_embed_command command;
  } cases[] = {
      {1, {program, NULL, NULL}, UR_EMBED_COMMAND_RUN},
      {2, {program, run, NULL}, UR_EMBED_COMMAND_RUN},
      {2, {program, self_test, NULL}, UR_EMBED_COMMAND_SELF_TEST},
      {2, {program, licenses, NULL}, UR_EMBED_COMMAND_LICENSES},
      {2, {program, version, NULL}, UR_EMBED_COMMAND_VERSION},
      {2, {program, unknown, NULL}, UR_EMBED_COMMAND_USAGE},
      {3, {program, run, unknown}, UR_EMBED_COMMAND_USAGE},
  };
  for (size_t i = 0; i < sizeof(cases) / sizeof(*cases); i++) {
    char *argv[3] = {cases[i].argv[0], cases[i].argv[1], cases[i].argv[2]};
    if (ur_embed_parse_command(cases[i].argc, argv) != cases[i].command)
      return fail(failure, capacity, "command line case %d parses differently",
                  (int)i);
  }
  if (UR_EMBED_EXIT_USAGE != 78 || UR_EMBED_EXIT_CONFIG != 78 ||
      UR_EMBED_EXIT_FAILURE != 1 || UR_EMBED_EXIT_STOPPED != 0)
    return fail(failure, capacity, "the exit codes are not 0, 1 and 78");
  return true;
}

/* The start line names the client and the installation, and --licenses names
 * the license kind of the platform it was built for. */
static bool check_start_line(char *failure, size_t capacity) {
  char line[UR_EMBED_LINE_CAPACITY];
  ur_embed_start_line("11111111-1111-1111-1111-111111111111",
                      "22222222-2222-2222-2222-222222222222", line, sizeof(line));
  if (strcmp(line, "embed client 11111111-1111-1111-1111-111111111111, "
                   "installation 22222222-2222-2222-2222-222222222222"))
    return fail(failure, capacity, "start line \"%s\"", line);
#if defined(_WIN32)
  const char *expected = "windows";
#elif defined(__APPLE__)
  const char *expected = "apple";
#else
  const char *expected = "linux";
#endif
  if (strcmp(ur_embed_license_app(), expected))
    return fail(failure, capacity, "license app kind \"%s\", want \"%s\"",
                ur_embed_license_app(), expected);
  return true;
}

/* Runs every check and keeps the first failure. */
bool ur_embed_self_test(char *failure, size_t capacity) {
  bool (*const checks[])(char *, size_t) = {
      check_format_byte_count, check_reset_text,   check_client_limit_text,
      check_status_lines,      check_status_rules, check_data_fields,
      check_cap_object,        check_client_jwt_claims, check_origin_url,
      check_token_fetch,       check_cap_read,     check_state_files,
      check_config,            check_usage,         check_start_line,
  };
  for (size_t i = 0; i < sizeof(checks) / sizeof(*checks); i++) {
    if (!checks[i](failure, capacity))
      return false;
  }
  return true;
}

/* Runs the self-test and prints one line: 0 when it passed, 1 when not. */
int ur_embed_self_test_main(void) {
  char failure[UR_EMBED_ERROR_CAPACITY * 2];
  if (!ur_embed_self_test(failure, sizeof(failure))) {
    fprintf(stderr, "embed self-test failed: %s\n", failure);
    return UR_EMBED_EXIT_FAILURE;
  }
  ur_embed_print_line("embed self-test passed");
  return UR_EMBED_EXIT_STOPPED;
}
