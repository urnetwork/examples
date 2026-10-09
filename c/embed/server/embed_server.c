/* SERVER ONLY: the C embed backend tool (EMBED_CONTRACT.md, "Backend tools").
 *
 *   embed-server provision <key> <client-jwt-file>
 *   embed-server cap <key> [--monthly <bytes>|--monthly null]
 *                          [--total <bytes>|--total null] [--reset-total]
 *   embed-server usage <key>
 *   embed-server usage-all
 *   embed-server remove <key>
 *   embed-server acl <key> default|isolated
 *   embed-server status
 *   embed-server --self-test
 *
 * It extends the C integration allocator (../../integration/server): the same
 * settings (URNETWORK_ROOT_JWT, an API key or a network JWT; URNETWORK_CLIENT_MAP,
 * an absolute path in an existing private, service-owned directory; optional
 * URNETWORK_API_URL), map format, key pattern, lock and response checks, and
 * reads URNETWORK_DEFAULT_ACL_GROUP: the ACL group of each new client,
 * isolated (the default) or default. Your
 * service authenticates its user first and supplies the key internally, as
 * user:<service-user-id>:<installation-id>, never a raw request field or a
 * URnetwork client ID. Exit codes: 0 success, 78 configuration or credential
 * problem, 1 any other failure, with one stderr line that never holds a
 * secret. POSIX CLI, libcurl + json-c. Confirm a crash-left .lock is stale
 * before removing it. */
#define _DARWIN_C_SOURCE 1
#define _POSIX_C_SOURCE 200809L
#include <curl/curl.h>
#include <json-c/json.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define LIMIT (1u << 20)
#define EXIT_OK 0
#define EXIT_FAILURE_CODE 1
#define EXIT_CONFIG 78
#define DESCRIPTION "embed client"
#define DEVICE_SPEC "urnetwork-examples/c-embed-server"
#define USAGE                                                                  \
  "usage: embed-server provision <key> <client-jwt-file> | cap <key> "         \
  "[--monthly <bytes>|null] [--total <bytes>|null] [--reset-total] | usage "   \
  "<key> | usage-all | remove <key> | acl <key> default|isolated | status " \
  "| --self-test"
#define UNMAPPED_TEXT "no client is mapped for that key; run provision first"
#define ACL_UNSUPPORTED_TEXT                                                   \
  "/network/client-acl-group answered 404: the server predates ACL groups"
#define CLIENT_LIMIT_TEXT                                                      \
  "client limit reached: your network is at its client limit; see "           \
  "https://ur.io/services"
/* The server refuses the data-cap and ACL-group routes with this message
 * while the team has not enabled Embed for the network (EMBED_CONTRACT.md,
 * "Embed enablement"); caps and groups set earlier stay enforced. */
#define EMBED_NOT_ENABLED_MESSAGE "Embed isn't enabled for this network."
/* cap, usage, usage-all and acl print this for the refusal and exit 78 */
#define EMBED_NOT_ENABLED_TEXT                                                 \
  "embed not enabled: Embed isn't enabled for this network; see "             \
  "https://ur.io/services"
/* provision prints this on stderr when the client's default ACL group stays
 * pending because Embed isn't enabled, and exits 0 */
#define EMBED_PENDING_TEXT                                                     \
  "embed not enabled: the client's defaults stay pending until Embed is "     \
  "enabled; see https://ur.io/services"
#define EMBED_UNSUPPORTED_TEXT                                                 \
  "/network/embed answered 404: the server predates Embed enablement"

typedef struct json_object Json;

/* One call to the URnetwork API: the method, the path with its query, and an
 * optional json body. Returns the answer's body (owned) with its status, or
 * NULL when no answer arrived. */
typedef char *(*Call)(void *context, const char *method, const char *path,
                      Json *body, long *status);

/* The tool's settings and the API it calls. */
typedef struct {
  const char *map_path;
  Call call;
  void *context;
  FILE *out;
  FILE *err;
  /* the ACL group of each new client: "isolated" (also when NULL) or
   * "default" */
  const char *acl_group;
} Tool;

/* Writes one stderr line and returns the exit code. */
static int report(const Tool *tool, int code, const char *format, ...) {
  va_list arguments;
  va_start(arguments, format);
  vfprintf(tool->err, format, arguments);
  va_end(arguments);
  fputc('\n', tool->err);
  return code;
}

/* A map key: user:<service-user-id> or user:<service-user-id>:<installation-id>,
 * the allocators' pattern user:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}. */
static bool key_valid(const char *s) {
  if (!s || strncmp(s, "user:", 5) || strlen(s) < 6 || strlen(s) > 128 ||
      !isalnum((unsigned char)s[5]))
    return false;
  for (size_t i = 5; s[i]; i++)
    if (!isalnum((unsigned char)s[i]) && !strchr("_.:@-", s[i]))
      return false;
  return true;
}

/* A canonical lowercase uuid. */
static bool id_valid(const char *s) {
  if (!s || strlen(s) != 36)
    return false;
  for (size_t i = 0; i < 36; i++) {
    if (i == 8 || i == 13 || i == 18 || i == 23) {
      if (s[i] != '-')
        return false;
    } else if (!strchr("0123456789abcdef", s[i]))
      return false;
  }
  return true;
}

/* A string member, NULL when absent, of another type or holding a NUL. */
static const char *string_field(Json *obj, const char *name) {
  Json *v = NULL;
  if (!obj || !json_object_object_get_ex(obj, name, &v) ||
      !json_object_is_type(v, json_type_string))
    return NULL;
  const char *s = json_object_get_string(v);
  return s && strlen(s) == (size_t)json_object_get_string_len(v) ? s : NULL;
}

/* A boolean member that is true. */
static bool true_field(Json *obj, const char *name) {
  Json *v = NULL;
  return obj && json_object_object_get_ex(obj, name, &v) &&
         json_object_is_type(v, json_type_boolean) && json_object_get_boolean(v);
}

/* Parses one json document strictly, with only whitespace after it. */
static Json *parse_json(const char *raw) {
  if (!raw || strlen(raw) > LIMIT)
    return NULL;
  struct json_tokener *tok = json_tokener_new();
  if (!tok)
    return NULL;
  json_tokener_set_flags(tok, JSON_TOKENER_STRICT | JSON_TOKENER_VALIDATE_UTF8);
  Json *obj = json_tokener_parse_ex(tok, raw, (int)strlen(raw));
  size_t end = json_tokener_get_parse_end(tok);
  bool ok = json_tokener_get_error(tok) == json_tokener_success;
  while (raw[end] && isspace((unsigned char)raw[end]))
    end++;
  if (!ok || raw[end]) {
    json_object_put(obj);
    obj = NULL;
  }
  json_tokener_free(tok);
  return obj;
}

/* Compact json text, without escaping slashes. */
static const char *json_text(Json *obj) {
  return json_object_to_json_string_ext(
      obj, JSON_C_TO_STRING_PLAIN | JSON_C_TO_STRING_NOSLASHESCAPE);
}

/* A url part, owned by curl. */
static char *part(CURLU *url, CURLUPart field) {
  char *s = NULL;
  if (curl_url_get(url, field, &s, 0) != CURLUE_OK)
    return NULL;
  return s;
}

/* The API origin with the allocators' rules: HTTPS, or explicit loopback HTTP
 * for local tests and mocks; no credentials, path beyond "/", query or
 * fragment. Owned; NULL when invalid. */
static char *origin(const char *base) {
  CURLU *u = curl_url();
  if (!u)
    return NULL;
  char *result = NULL, *scheme = NULL, *host = NULL, *path = NULL, *user = NULL,
       *pass = NULL, *query = NULL, *fragment = NULL;
  if (curl_url_set(u, CURLUPART_URL, base, 0) != CURLUE_OK)
    goto done;
  scheme = part(u, CURLUPART_SCHEME);
  host = part(u, CURLUPART_HOST);
  path = part(u, CURLUPART_PATH);
  user = part(u, CURLUPART_USER);
  pass = part(u, CURLUPART_PASSWORD);
  query = part(u, CURLUPART_QUERY);
  fragment = part(u, CURLUPART_FRAGMENT);
  bool local = host && (!strcmp(host, "localhost") ||
                        !strcmp(host, "127.0.0.1") || !strcmp(host, "[::1]"));
  if (!scheme || !host || user || pass || query || fragment ||
      (path && strcmp(path, "/")) ||
      (strcmp(scheme, "https") && !(local && !strcmp(scheme, "http"))))
    goto done;
  if (curl_url_set(u, CURLUPART_PATH, "", 0) == CURLUE_OK) {
    char *raw = part(u, CURLUPART_URL);
    if (raw) {
      result = strdup(raw);
      size_t length = strlen(result);
      while (length && result[length - 1] == '/')
        result[--length] = 0;
      curl_free(raw);
    }
  }
done:
  curl_free(scheme);
  curl_free(host);
  curl_free(path);
  curl_free(user);
  curl_free(pass);
  curl_free(query);
  curl_free(fragment);
  curl_url_cleanup(u);
  return result;
}

/* Decodes base64url without padding, as jwt segments use it; owned. */
static char *decode64(const char *raw, size_t size) {
  if (!size || size % 4 == 1)
    return NULL;
  char *out = malloc(size + 1);
  if (!out)
    return NULL;
  const char *alphabet =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  unsigned int bits = 0;
  int count = 0;
  size_t length = 0;
  for (size_t i = 0; i < size; i++) {
    const char *p = raw[i] ? strchr(alphabet, raw[i]) : NULL;
    if (!p) {
      free(out);
      return NULL;
    }
    bits = (bits << 6) | (unsigned)(p - alphabet);
    count += 6;
    if (count >= 8) {
      count -= 8;
      out[length++] = (char)((bits >> count) & 255);
    }
  }
  out[length] = 0;
  if (strlen(out) != length) {
    free(out);
    return NULL;
  }
  return out;
}

/* Whether a client JWT's client_id claim equals id. This checks consistency;
 * it is not local signature verification. */
static bool claim_matches(const char *jwt, const char *id) {
  const char *a = strchr(jwt, '.'), *b = a ? strchr(a + 1, '.') : NULL;
  if (!a || a == jwt || !b || b == a + 1 || !b[1] || strchr(b + 1, '.'))
    return false;
  char *decoded = decode64(a + 1, (size_t)(b - a - 1));
  Json *claims = parse_json(decoded);
  const char *claim = string_field(claims, "client_id");
  bool matches = claim && !strcmp(claim, id);
  json_object_put(claims);
  free(decoded);
  return matches;
}

/* ----- the client map ----- */

/* Loads the private map: a missing file is an empty map. Only the allocators'
 * fields are accepted, so a map with other fields (such as the token server's
 * pending_caps) is refused and never rewritten by this tool. */
static Json *load_map(const char *path) {
  struct stat st;
  if (lstat(path, &st)) {
    if (errno != ENOENT)
      return NULL;
    Json *empty = json_object_new_object();
    json_object_object_add(empty, "version", json_object_new_int(1));
    json_object_object_add(empty, "clients", json_object_new_object());
    return empty;
  }
  if (!S_ISREG(st.st_mode) || (st.st_mode & 0077) || st.st_size > (off_t)LIMIT)
    return NULL;
  FILE *f = fopen(path, "rb");
  if (!f)
    return NULL;
  char *raw = malloc((size_t)st.st_size + 1);
  if (!raw) {
    fclose(f);
    return NULL;
  }
  size_t n = fread(raw, 1, (size_t)st.st_size, f);
  bool read_ok = !ferror(f) && n == (size_t)st.st_size;
  fclose(f);
  raw[n] = 0;
  Json *map = read_ok && strlen(raw) == n ? parse_json(raw) : NULL;
  free(raw);
  Json *version = NULL, *clients = NULL, *seen = json_object_new_object();
  bool valid = false;
  if (!map || !json_object_is_type(map, json_type_object) ||
      !json_object_object_get_ex(map, "version", &version) ||
      !json_object_is_type(version, json_type_int) ||
      json_object_get_int(version) != 1 ||
      !json_object_object_get_ex(map, "clients", &clients) ||
      !json_object_is_type(clients, json_type_object))
    goto done;
  valid = true;
  json_object_object_foreach(map, field, field_value) {
    (void)field_value;
    if (strcmp(field, "version") && strcmp(field, "clients") &&
        strcmp(field, "pending_acl"))
      valid = false;
  }
  json_object_object_foreach(clients, key, value) {
    const char *id = json_object_is_type(value, json_type_string)
                         ? json_object_get_string(value)
                         : NULL;
    Json *duplicate = NULL;
    if (!valid || !key_valid(key) || !id_valid(id) ||
        strlen(id) != (size_t)json_object_get_string_len(value) ||
        json_object_object_get_ex(seen, id, &duplicate)) {
      valid = false;
      break;
    }
    json_object_object_add(seen, id, json_object_new_boolean(1));
  }
  /* pending_acl: distinct mapped keys that still owe their default ACL group */
  Json *pending = NULL, *pending_seen = json_object_new_object();
  if (valid && json_object_object_get_ex(map, "pending_acl", &pending)) {
    if (!json_object_is_type(pending, json_type_array))
      valid = false;
    for (size_t i = 0; valid && i < json_object_array_length(pending); i++) {
      Json *entry = json_object_array_get_idx(pending, i), *mapped = NULL;
      const char *pending_key = json_object_is_type(entry, json_type_string)
                                    ? json_object_get_string(entry)
                                    : NULL;
      if (!pending_key || !json_object_object_get_ex(clients, pending_key, &mapped) ||
          json_object_object_get_ex(pending_seen, pending_key, &mapped))
        valid = false;
      else
        json_object_object_add(pending_seen, pending_key, json_object_new_boolean(1));
    }
  }
  json_object_put(pending_seen);
done:
  json_object_put(seen);
  if (!valid) {
    json_object_put(map);
    map = NULL;
  }
  return map;
}

/* Writes text to path atomically, owner-only: a private temporary file in the
 * same directory, synced, then renamed over the old file. */
static bool write_private(const char *path, const char *text) {
  char *tmp = malloc(strlen(path) + 8);
  if (!tmp)
    return false;
  sprintf(tmp, "%s.XXXXXX", path);
  int fd = mkstemp(tmp);
  if (fd < 0) {
    free(tmp);
    return false;
  }
  bool ok = fchmod(fd, 0600) == 0;
  size_t offset = 0, length = strlen(text);
  while (ok && offset < length) {
    ssize_t n = write(fd, text + offset, length - offset);
    if (n < 0 && errno == EINTR)
      continue;
    if (n <= 0) {
      ok = false;
      break;
    }
    offset += (size_t)n;
  }
  if (ok)
    ok = fsync(fd) == 0;
  if (close(fd))
    ok = false;
  if (ok)
    ok = rename(tmp, path) == 0;
  if (!ok)
    unlink(tmp);
  free(tmp);
  return ok;
}

/* Saves the map atomically with private permissions. pending_acl is written
 * only while it is not empty, so the map stays the allocators' map. */
static bool save_map(const char *path, Json *map) {
  Json *pending = NULL;
  if (json_object_object_get_ex(map, "pending_acl", &pending) &&
      json_object_array_length(pending) == 0)
    json_object_object_del(map, "pending_acl");
  return write_private(path, json_text(map));
}

/* Whether key still owes its default ACL group. */
static bool acl_pending(Json *map, const char *key) {
  Json *pending = NULL;
  if (!json_object_object_get_ex(map, "pending_acl", &pending))
    return false;
  for (size_t i = 0; i < json_object_array_length(pending); i++)
    if (!strcmp(json_object_get_string(json_object_array_get_idx(pending, i)), key))
      return true;
  return false;
}

/* Records or clears that key owes its default ACL group. */
static void set_acl_pending(Json *map, const char *key, bool owes) {
  Json *pending = NULL, *kept = json_object_new_array();
  if (json_object_object_get_ex(map, "pending_acl", &pending))
    for (size_t i = 0; i < json_object_array_length(pending); i++) {
      const char *other = json_object_get_string(json_object_array_get_idx(pending, i));
      if (strcmp(other, key))
        json_object_array_add(kept, json_object_new_string(other));
    }
  if (owes)
    json_object_array_add(kept, json_object_new_string(key));
  json_object_object_add(map, "pending_acl", kept);
}

/* The exclusive <map>.lock directory, held through the remote call and the
 * map update. */
static char *take_lock(const char *path) {
  char *lock = malloc(strlen(path) + 6);
  if (!lock)
    return NULL;
  sprintf(lock, "%s.lock", path);
  if (mkdir(lock, 0700)) {
    free(lock);
    return NULL;
  }
  return lock;
}

/* Releases the lock. */
static void release_lock(char *lock) {
  if (lock)
    rmdir(lock);
  free(lock);
}

/* The client mapped to key, or NULL. */
static const char *mapped_client(Json *map, const char *key) {
  Json *clients = NULL;
  if (!json_object_object_get_ex(map, "clients", &clients))
    return NULL;
  return string_field(clients, key);
}

/* ----- API answers ----- */

/* The kind of an API answer. */
typedef enum {
  ANSWER_OK,
  /* the API answered with an error object */
  ANSWER_REFUSED,
  /* the root credential was refused: 401 or 403 */
  ANSWER_UNAUTHORIZED,
  /* no answer, another status, or not json */
  ANSWER_FAILED,
} answer_kind;

/* Calls the API and reads its answer as a json object; on ANSWER_OK or
 * ANSWER_REFUSED *answer holds the object. */
static answer_kind call_api(const Tool *tool, const char *method,
                            const char *path, Json *body, Json **answer,
                            long *status) {
  *answer = NULL;
  *status = 0;
  char *raw = tool->call(tool->context, method, path, body, status);
  if (!raw)
    return ANSWER_FAILED;
  if (*status == 401 || *status == 403) {
    free(raw);
    return ANSWER_UNAUTHORIZED;
  }
  Json *obj = 200 <= *status && *status < 300 ? parse_json(raw) : NULL;
  free(raw);
  if (!obj || !json_object_is_type(obj, json_type_object)) {
    json_object_put(obj);
    return ANSWER_FAILED;
  }
  *answer = obj;
  Json *error = NULL;
  if (json_object_object_get_ex(obj, "error", &error) && error &&
      !json_object_is_type(error, json_type_null))
    return ANSWER_REFUSED;
  return ANSWER_OK;
}

/* The message of a refusal, or "". */
static const char *refusal_message(Json *answer) {
  Json *error = NULL;
  if (!json_object_object_get_ex(answer, "error", &error))
    return "";
  const char *message = string_field(error, "message");
  return message ? message : "";
}

/* Whether a refusal is either client limit flag. */
static bool client_limit_refusal(Json *answer) {
  Json *error = NULL;
  return json_object_object_get_ex(answer, "error", &error) &&
         (true_field(error, "client_limit_exceeded") ||
          true_field(error, "upgrade_required"));
}

/* Whether an answer is the Embed-not-enabled refusal. */
static bool embed_not_enabled(answer_kind kind, Json *answer) {
  return kind == ANSWER_REFUSED &&
         !strcmp(refusal_message(answer), EMBED_NOT_ENABLED_MESSAGE);
}

/* Whether an answer is a cap object: a json object without an error. */
static bool cap_object(Json *answer) {
  return answer && json_object_is_type(answer, json_type_object) &&
         string_field(answer, "client_id");
}

/* ----- commands ----- */

/* Whether new clients go into the isolated ACL group (the default). */
static bool isolated_by_default(const Tool *tool) {
  return !tool->acl_group || !strcmp(tool->acl_group, "isolated");
}

/* Posts client's ACL group and checks that the answer names the client and the
 * group; prints {"client_id": "...", "acl_group": "..."} when print is set.
 * Returns EXIT_OK, or the exit code after reporting the failure. With
 * not_enabled, the Embed-not-enabled refusal sets it and returns EXIT_OK
 * without a report; without it, the refusal exits 78 with the fixed line. */
static int post_acl_group(const Tool *tool, const char *client,
                          const char *group, bool print, bool *not_enabled) {
  Json *body = json_object_new_object(), *answer = NULL;
  json_object_object_add(body, "client_id", json_object_new_string(client));
  json_object_object_add(body, "acl_group", json_object_new_string(group));
  long status;
  answer_kind kind = call_api(tool, "POST", "/network/client-acl-group", body,
                              &answer, &status);
  json_object_put(body);
  const char *answered_client = answer ? string_field(answer, "client_id") : NULL,
             *answered_group = answer ? string_field(answer, "acl_group") : NULL;
  int code;
  if (kind == ANSWER_UNAUTHORIZED)
    code = report(tool, EXIT_CONFIG, "the API refused the root credential");
  else if (kind == ANSWER_FAILED && status == 404)
    code = report(tool, EXIT_FAILURE_CODE, ACL_UNSUPPORTED_TEXT);
  else if (embed_not_enabled(kind, answer) && not_enabled) {
    *not_enabled = true;
    code = EXIT_OK;
  } else if (embed_not_enabled(kind, answer))
    code = report(tool, EXIT_CONFIG, EMBED_NOT_ENABLED_TEXT);
  else if (kind == ANSWER_REFUSED)
    code = report(tool, EXIT_FAILURE_CODE, "the API refused: %s",
                  refusal_message(answer));
  else if (kind != ANSWER_OK || !answered_client || strcmp(answered_client, client) ||
           !answered_group || strcmp(answered_group, group))
    code = report(tool, EXIT_FAILURE_CODE,
                  "setting the ACL group failed: no valid answer (HTTP %ld)", status);
  else {
    if (print)
      fprintf(tool->out, "{\"client_id\":\"%s\",\"acl_group\":\"%s\"}\n", client, group);
    code = EXIT_OK;
  }
  json_object_put(answer);
  return code;
}

/* The auth-client request: a new client, or a reissue with its client id. */
static Json *auth_client_request(const char *client) {
  Json *body = json_object_new_object();
  json_object_object_add(body, "description", json_object_new_string(DESCRIPTION));
  json_object_object_add(body, "device_spec", json_object_new_string(DEVICE_SPEC));
  if (client)
    json_object_object_add(body, "client_id", json_object_new_string(client));
  return body;
}

/* provision <key> <client-jwt-file>: reissues the key's client, or provisions
 * a new one; on "Client does not exist." it drops the mapping and provisions a
 * new client. The client JWT goes only to the file. While Embed isn't enabled
 * the default ACL group stays pending, and a provision after the team enables
 * Embed applies it. */
static int provision(const Tool *tool, const char *key, const char *jwt_file) {
  if (!key_valid(key) || !jwt_file || !*jwt_file)
    return report(tool, EXIT_CONFIG, "provision: invalid key or file");
  char *lock = take_lock(tool->map_path);
  if (!lock)
    return report(tool, EXIT_FAILURE_CODE,
                  "the client map is locked by another run; retry");
  int code = EXIT_FAILURE_CODE;
  Json *map = load_map(tool->map_path), *answer = NULL;
  if (!map) {
    code = report(tool, EXIT_CONFIG, "the client map is not a valid private map");
    goto done;
  }
  const char *mapped = mapped_client(map, key);
  char old[37] = "";
  if (mapped)
    snprintf(old, sizeof(old), "%s", mapped);
  for (int attempt = 0; attempt < 2; attempt++) {
    Json *body = auth_client_request(old[0] ? old : NULL);
    long status;
    answer_kind kind =
        call_api(tool, "POST", "/network/auth-client", body, &answer, &status);
    json_object_put(body);
    if (kind == ANSWER_UNAUTHORIZED) {
      code = report(tool, EXIT_CONFIG, "the API refused the root credential");
      goto done;
    }
    if (kind == ANSWER_FAILED) {
      code = report(tool, EXIT_FAILURE_CODE,
                    "provisioning failed: no valid answer (HTTP %ld)", status);
      goto done;
    }
    if (kind == ANSWER_REFUSED) {
      if (client_limit_refusal(answer)) {
        code = report(tool, EXIT_CONFIG, CLIENT_LIMIT_TEXT);
        goto done;
      }
      if (old[0] && !strcmp(refusal_message(answer), "Client does not exist.")) {
        /* deactivated after 30 days without connecting: provision anew */
        Json *clients = NULL;
        json_object_object_get_ex(map, "clients", &clients);
        json_object_object_del(clients, key);
        set_acl_pending(map, key, false);
        if (!save_map(tool->map_path, map)) {
          code = report(tool, EXIT_FAILURE_CODE, "could not save the client map");
          goto done;
        }
        old[0] = 0;
        json_object_put(answer);
        answer = NULL;
        continue;
      }
      code = report(tool, EXIT_FAILURE_CODE, "provisioning refused: %s",
                    refusal_message(answer));
      goto done;
    }
    break;
  }
  const char *id = string_field(answer, "client_id"),
             *jwt = string_field(answer, "by_client_jwt");
  if (!answer || !id_valid(id) || !jwt || !claim_matches(jwt, id) ||
      (old[0] && strcmp(old, id))) {
    code = report(tool, EXIT_FAILURE_CODE,
                  "provisioning answered an invalid client");
    goto done;
  }
  if (!old[0]) {
    Json *clients = NULL;
    json_object_object_get_ex(map, "clients", &clients);
    json_object_object_foreach(clients, other_key, value) {
      (void)other_key;
      if (!strcmp(json_object_get_string(value), id)) {
        code = report(tool, EXIT_FAILURE_CODE,
                      "provisioning answered a client mapped to another key");
        goto done;
      }
    }
    json_object_object_add(clients, key, json_object_new_string(id));
    /* the mapping and the record that the client owes its group, in one save */
    if (isolated_by_default(tool))
      set_acl_pending(map, key, true);
    if (!save_map(tool->map_path, map)) {
      code = report(tool, EXIT_FAILURE_CODE, "could not save the client map");
      goto done;
    }
  }
  bool not_enabled = false;
  if (acl_pending(map, key)) {
    /* a new client is "default": only an isolated default needs the request.
     * A failure keeps the record, and no client JWT is written. */
    if (isolated_by_default(tool) &&
        (code = post_acl_group(tool, id, "isolated", false, &not_enabled)) != EXIT_OK)
      goto done;
    /* while Embed isn't enabled the record stays and the client works */
    if (!not_enabled) {
      set_acl_pending(map, key, false);
      if (!save_map(tool->map_path, map)) {
        code = report(tool, EXIT_FAILURE_CODE, "could not save the client map");
        goto done;
      }
    }
  }
  char *line = malloc(strlen(jwt) + 2);
  bool written = line && (sprintf(line, "%s\n", jwt), write_private(jwt_file, line));
  if (line) {
    memset(line, 0, strlen(line));
    free(line);
  }
  if (!written) {
    code = report(tool, EXIT_FAILURE_CODE, "could not write the client JWT file");
    goto done;
  }
  fprintf(tool->out, "{\"client_id\":\"%s\"}\n", id);
  if (not_enabled)
    fprintf(tool->err, "%s\n", EMBED_PENDING_TEXT);
  code = EXIT_OK;
done:
  json_object_put(answer);
  json_object_put(map);
  release_lock(lock);
  return code;
}

/* A byte count: a decimal integer from 0 to 9223372036854775807, no units. */
static bool parse_byte_count(const char *text, int64_t *value) {
  if (!text || !*text)
    return false;
  uint64_t number = 0;
  for (const char *p = text; *p; p++) {
    if (*p < '0' || '9' < *p)
      return false;
    number = number * 10 + (uint64_t)(*p - '0');
    if (number > (uint64_t)INT64_MAX)
      return false;
  }
  *value = (int64_t)number;
  return true;
}

/* The cap request body from the options: only the given fields, with null for
 * a cleared cap and reset_total only when given. NULL for invalid options. */
static Json *cap_request(const char *client, int argc, char **argv) {
  Json *body = json_object_new_object();
  json_object_object_add(body, "client_id", json_object_new_string(client));
  bool monthly = false, total = false, reset = false;
  for (int i = 0; i < argc; i++) {
    bool is_monthly = !strcmp(argv[i], "--monthly");
    bool is_total = !strcmp(argv[i], "--total");
    if (!strcmp(argv[i], "--reset-total") && !reset) {
      reset = true;
      json_object_object_add(body, "reset_total", json_object_new_boolean(1));
      continue;
    }
    if (!(is_monthly && !monthly) && !(is_total && !total))
      goto invalid;
    if (i + 1 >= argc)
      goto invalid;
    const char *value = argv[++i];
    Json *limit = NULL;
    int64_t count;
    if (strcmp(value, "null")) {
      if (!parse_byte_count(value, &count))
        goto invalid;
      limit = json_object_new_int64(count);
    }
    json_object_object_add(body, is_monthly ? "monthly_byte_limit"
                                            : "total_byte_limit",
                           limit);
    if (is_monthly)
      monthly = true;
    else
      total = true;
  }
  if (monthly || total || reset)
    return body;
invalid:
  json_object_put(body);
  return NULL;
}

/* Prints a cap object answer, or reports the failure. */
static int print_cap(const Tool *tool, const char *route, answer_kind kind,
                     Json *answer, long status) {
  if (kind == ANSWER_UNAUTHORIZED)
    return report(tool, EXIT_CONFIG, "the API refused the root credential");
  if (kind == ANSWER_FAILED && status == 404)
    return report(tool, EXIT_FAILURE_CODE,
                  "%s answered 404: the server predates the data-cap routes", route);
  if (embed_not_enabled(kind, answer))
    return report(tool, EXIT_CONFIG, EMBED_NOT_ENABLED_TEXT);
  if (kind == ANSWER_REFUSED)
    return report(tool, EXIT_FAILURE_CODE, "the API refused: %s",
                  refusal_message(answer));
  if (kind == ANSWER_FAILED || !cap_object(answer))
    return report(tool, EXIT_FAILURE_CODE, "no cap object in the answer (HTTP %ld)",
                  status);
  fprintf(tool->out, "%s\n", json_text(answer));
  return EXIT_OK;
}

/* cap <key> [options]: posts only the given fields. */
static int cap(const Tool *tool, const char *key, int argc, char **argv) {
  if (!key_valid(key))
    return report(tool, EXIT_CONFIG, "cap: invalid key");
  Json *map = load_map(tool->map_path);
  if (!map)
    return report(tool, EXIT_CONFIG, "the client map is not a valid private map");
  const char *client = mapped_client(map, key);
  if (!client) {
    json_object_put(map);
    return report(tool, EXIT_CONFIG, UNMAPPED_TEXT);
  }
  Json *body = cap_request(client, argc, argv);
  json_object_put(map);
  if (!body)
    return report(tool, EXIT_CONFIG, "cap: give --monthly, --total or "
                                     "--reset-total with byte counts or null");
  Json *answer = NULL;
  long status;
  answer_kind kind =
      call_api(tool, "POST", "/network/client-data-cap", body, &answer, &status);
  json_object_put(body);
  int code = print_cap(tool, "/network/client-data-cap", kind, answer, status);
  json_object_put(answer);
  return code;
}

/* usage <key>: the key's cap object, read with the root credential. */
static int usage(const Tool *tool, const char *key) {
  if (!key_valid(key))
    return report(tool, EXIT_CONFIG, "usage: invalid key");
  Json *map = load_map(tool->map_path);
  if (!map)
    return report(tool, EXIT_CONFIG, "the client map is not a valid private map");
  const char *client = mapped_client(map, key);
  if (!client) {
    json_object_put(map);
    return report(tool, EXIT_CONFIG, UNMAPPED_TEXT);
  }
  char path[96];
  snprintf(path, sizeof(path), "/network/client-data-cap?client_id=%s", client);
  json_object_put(map);
  Json *answer = NULL;
  long status;
  answer_kind kind = call_api(tool, "GET", path, NULL, &answer, &status);
  int code = print_cap(tool, "/network/client-data-cap", kind, answer, status);
  json_object_put(answer);
  return code;
}

/* Appends text to path, percent-encoding all but the unreserved characters. */
static void append_encoded(char *path, size_t capacity, const char *text) {
  size_t length = strlen(path);
  for (const unsigned char *p = (const unsigned char *)text;
       *p && length + 4 < capacity; p++) {
    if (isalnum(*p) || strchr("-._~", *p))
      path[length++] = (char)*p;
    else
      length += (size_t)snprintf(path + length, capacity - length, "%%%02X", *p);
  }
  path[length] = 0;
}

/* usage-all: pages through GET /network/client-data-caps with limit=1000 and
 * prints one cap object per line, stopping at a null cursor or a repeated
 * one. */
static int usage_all(const Tool *tool) {
  char *cursor = NULL;
  int code = EXIT_OK;
  for (;;) {
    char path[1024] = "/network/client-data-caps?limit=1000";
    if (cursor) {
      strcat(path, "&cursor=");
      append_encoded(path, sizeof(path), cursor);
    }
    Json *answer = NULL;
    long status;
    answer_kind kind = call_api(tool, "GET", path, NULL, &answer, &status);
    Json *clients = NULL;
    if (kind == ANSWER_UNAUTHORIZED) {
      code = report(tool, EXIT_CONFIG, "the API refused the root credential");
    } else if (kind == ANSWER_FAILED && status == 404) {
      code = report(tool, EXIT_FAILURE_CODE, "/network/client-data-caps answered "
                                             "404: the server predates the data-cap routes");
    } else if (embed_not_enabled(kind, answer)) {
      code = report(tool, EXIT_CONFIG, EMBED_NOT_ENABLED_TEXT);
    } else if (kind != ANSWER_OK ||
               !json_object_object_get_ex(answer, "clients", &clients) ||
               !json_object_is_type(clients, json_type_array)) {
      code = report(tool, EXIT_FAILURE_CODE,
                    "no page of cap objects in the answer (HTTP %ld)", status);
    }
    if (code != EXIT_OK) {
      json_object_put(answer);
      break;
    }
    for (size_t i = 0; i < json_object_array_length(clients); i++)
      fprintf(tool->out, "%s\n", json_text(json_object_array_get_idx(clients, i)));
    const char *next = string_field(answer, "next_cursor");
    bool stop = !next || (cursor && !strcmp(next, cursor));
    free(cursor);
    cursor = stop ? NULL : strdup(next);
    json_object_put(answer);
    if (stop || !cursor)
      break;
  }
  free(cursor);
  return code;
}

/* remove <key>: removes the key's client, then the mapping, also when the
 * client no longer exists. */
static int remove_client(const Tool *tool, const char *key) {
  if (!key_valid(key))
    return report(tool, EXIT_CONFIG, "remove: invalid key");
  char *lock = take_lock(tool->map_path);
  if (!lock)
    return report(tool, EXIT_FAILURE_CODE,
                  "the client map is locked by another run; retry");
  int code = EXIT_FAILURE_CODE;
  Json *map = load_map(tool->map_path), *answer = NULL, *body = NULL;
  const char *client = map ? mapped_client(map, key) : NULL;
  char id[37] = "";
  if (!map) {
    code = report(tool, EXIT_CONFIG, "the client map is not a valid private map");
    goto done;
  }
  if (!client) {
    code = report(tool, EXIT_CONFIG, UNMAPPED_TEXT);
    goto done;
  }
  snprintf(id, sizeof(id), "%s", client);
  body = json_object_new_object();
  json_object_object_add(body, "client_id", json_object_new_string(id));
  long status;
  answer_kind kind =
      call_api(tool, "POST", "/network/remove-client", body, &answer, &status);
  if (kind == ANSWER_UNAUTHORIZED) {
    code = report(tool, EXIT_CONFIG, "the API refused the root credential");
    goto done;
  }
  if (kind == ANSWER_FAILED ||
      (kind == ANSWER_REFUSED &&
       strcmp(refusal_message(answer), "Client does not exist."))) {
    code = report(tool, EXIT_FAILURE_CODE, "removing the client failed%s%s",
                  kind == ANSWER_REFUSED ? ": " : "",
                  kind == ANSWER_REFUSED ? refusal_message(answer) : "");
    goto done;
  }
  Json *clients = NULL;
  json_object_object_get_ex(map, "clients", &clients);
  json_object_object_del(clients, key);
  set_acl_pending(map, key, false);
  if (!save_map(tool->map_path, map)) {
    code = report(tool, EXIT_FAILURE_CODE, "could not save the client map");
    goto done;
  }
  fprintf(tool->out, "{\"removed\":\"%s\"}\n", id);
  code = EXIT_OK;
done:
  json_object_put(body);
  json_object_put(answer);
  json_object_put(map);
  release_lock(lock);
  return code;
}

/* acl <key> default|isolated: sets the ACL group of the key's client and prints
 * {"client_id": "...", "acl_group": "..."}. An explicit group settles a
 * pending default group, so the record is dropped. */
static int acl(const Tool *tool, const char *key, const char *group) {
  if (!key_valid(key) || (strcmp(group, "default") && strcmp(group, "isolated")))
    return report(tool, EXIT_CONFIG, USAGE);
  char *lock = take_lock(tool->map_path);
  if (!lock)
    return report(tool, EXIT_FAILURE_CODE,
                  "the client map is locked by another run; retry");
  int code;
  Json *map = load_map(tool->map_path);
  const char *client = map ? mapped_client(map, key) : NULL;
  if (!map)
    code = report(tool, EXIT_CONFIG, "the client map is not a valid private map");
  else if (!client)
    code = report(tool, EXIT_CONFIG, UNMAPPED_TEXT);
  else if ((code = post_acl_group(tool, client, group, true, NULL)) == EXIT_OK &&
           acl_pending(map, key)) {
    set_acl_pending(map, key, false);
    if (!save_map(tool->map_path, map))
      code = report(tool, EXIT_FAILURE_CODE, "could not save the client map");
  }
  json_object_put(map);
  release_lock(lock);
  return code;
}

/* A non-negative integer member, or -1. */
static int64_t count_field(Json *obj, const char *name) {
  Json *v = NULL;
  if (!json_object_object_get_ex(obj, name, &v) ||
      !json_object_is_type(v, json_type_int))
    return -1;
  int64_t count = json_object_get_int64(v);
  return count < 0 ? -1 : count;
}

/* status: prints the network's Embed state from GET /network/embed as one
 * line, "embed enabled: yes | client limit: 5000 | active clients: 1234". A
 * refusal, such as for a client JWT, is a configuration problem. */
static int embed_status(const Tool *tool) {
  Json *answer = NULL, *enabled = NULL;
  long status;
  answer_kind kind = call_api(tool, "GET", "/network/embed", NULL, &answer, &status);
  int64_t limit = answer ? count_field(answer, "client_limit") : -1,
          active = answer ? count_field(answer, "active_client_count") : -1;
  int code;
  if (kind == ANSWER_UNAUTHORIZED)
    code = report(tool, EXIT_CONFIG, "the API refused the root credential");
  else if (kind == ANSWER_FAILED && status == 404)
    code = report(tool, EXIT_FAILURE_CODE, EMBED_UNSUPPORTED_TEXT);
  else if (kind == ANSWER_REFUSED)
    code = report(tool, EXIT_CONFIG, "the API refused: %s", refusal_message(answer));
  else if (kind != ANSWER_OK || !json_object_object_get_ex(answer, "enabled", &enabled) ||
           !json_object_is_type(enabled, json_type_boolean) || limit < 0 || active < 0)
    code = report(tool, EXIT_FAILURE_CODE, "no Embed state in the answer (HTTP %ld)", status);
  else {
    fprintf(tool->out, "embed enabled: %s | client limit: %" PRId64 " | active clients: %" PRId64 "\n",
            json_object_get_boolean(enabled) ? "yes" : "no", limit, active);
    code = EXIT_OK;
  }
  json_object_put(answer);
  return code;
}

/* Runs one command line (the arguments after the program name). */
static int run(const Tool *tool, int argc, char **argv) {
  if (argc == 3 && !strcmp(argv[0], "provision"))
    return provision(tool, argv[1], argv[2]);
  if (argc >= 2 && !strcmp(argv[0], "cap"))
    return cap(tool, argv[1], argc - 2, argv + 2);
  if (argc == 2 && !strcmp(argv[0], "usage"))
    return usage(tool, argv[1]);
  if (argc == 1 && !strcmp(argv[0], "usage-all"))
    return usage_all(tool);
  if (argc == 2 && !strcmp(argv[0], "remove"))
    return remove_client(tool, argv[1]);
  if (argc == 3 && !strcmp(argv[0], "acl"))
    return acl(tool, argv[1], argv[2]);
  if (argc == 1 && !strcmp(argv[0], "status"))
    return embed_status(tool);
  return report(tool, EXIT_CONFIG, USAGE);
}

/* ----- the API over libcurl ----- */

struct Buffer {
  char *bytes;
  size_t length;
};

struct Backend {
  const char *origin, *root;
};

/* Appends one chunk of the answer. */
static size_t receive(char *bytes, size_t size, size_t count, void *context) {
  struct Buffer *b = context;
  size_t n = size * count;
  if (n > LIMIT - b->length)
    return 0;
  char *next = realloc(b->bytes, b->length + n + 1);
  if (!next)
    return 0;
  b->bytes = next;
  memcpy(next + b->length, bytes, n);
  b->length += n;
  next[b->length] = 0;
  return n;
}

/* One API call with the root credential as the bearer token. */
static char *api_call(void *context, const char *method, const char *path,
                      Json *body, long *status) {
  struct Backend *backend = context;
  *status = 0;
  CURL *curl = curl_easy_init();
  if (!curl)
    return NULL;
  struct Buffer response = {0};
  char *url = malloc(strlen(backend->origin) + strlen(path) + 1);
  char *authorization = malloc(strlen(backend->root) + 23);
  if (!url || !authorization) {
    free(url);
    free(authorization);
    curl_easy_cleanup(curl);
    return NULL;
  }
  sprintf(url, "%s%s", backend->origin, path);
  sprintf(authorization, "Authorization: Bearer %s", backend->root);
  struct curl_slist *headers = curl_slist_append(NULL, authorization);
  memset(authorization, 0, strlen(authorization));
  free(authorization);
  if (body)
    headers = curl_slist_append(headers, "Content-Type: application/json");
  curl_easy_setopt(curl, CURLOPT_URL, url);
  curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
  curl_easy_setopt(curl, CURLOPT_TIMEOUT, 15L);
  curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 0L);
  curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, receive);
  curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
  const char *raw = body ? json_text(body) : NULL;
  if (raw) {
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, raw);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, (long)strlen(raw));
  } else if (!strcmp(method, "GET")) {
    curl_easy_setopt(curl, CURLOPT_HTTPGET, 1L);
  }
  CURLcode error = curl_easy_perform(curl);
  curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, status);
  curl_slist_free_all(headers);
  curl_easy_cleanup(curl);
  free(url);
  if (error != CURLE_OK || (response.bytes && strlen(response.bytes) != response.length)) {
    free(response.bytes);
    return NULL;
  }
  return response.bytes ? response.bytes : strdup("");
}

/* ----- self-test ----- */

#define CHECK(condition)                                                       \
  do {                                                                         \
    if (!(condition)) {                                                        \
      fprintf(stderr, "embed-server self-test failed at line %d\n", __LINE__); \
      exit(1);                                                                 \
    }                                                                          \
  } while (0)

/* A mock API: canned answers in order, and the requests it saw. */
struct Mock {
  const char *answers[4];
  long statuses[4];
  size_t count;
  char methods[4][8];
  char paths[4][256];
  Json *bodies[4];
  size_t calls;
};

/* The mock's API call. */
static char *mock(void *context, const char *method, const char *path,
                  Json *body, long *status) {
  struct Mock *m = context;
  if (m->calls >= m->count)
    return NULL;
  size_t i = m->calls++;
  snprintf(m->methods[i], sizeof(m->methods[i]), "%s", method);
  snprintf(m->paths[i], sizeof(m->paths[i]), "%s", path);
  m->bodies[i] = body ? json_object_get(body) : NULL;
  *status = m->statuses[i];
  return strdup(m->answers[i]);
}

/* Frees what the mock kept. */
static void mock_free(struct Mock *m) {
  for (size_t i = 0; i < m->calls; i++)
    json_object_put(m->bodies[i]);
}

/* The whole content of a temporary output stream. */
static char *stream_text(FILE *stream) {
  fflush(stream);
  long length = ftell(stream);
  char *text = calloc(1, (size_t)(length < 0 ? 0 : length) + 1);
  rewind(stream);
  if (text && length > 0)
    fread(text, 1, (size_t)length, stream);
  return text;
}

/* The default ACL group of the self-test's runs: "default" sends no ACL
 * request, so the checks that predate ACL groups keep their answer order. */
static const char *test_acl_group = "default";

/* Runs a command line against the mock with fresh output streams. */
static int run_with(struct Mock *m, const char *map_path, char **out,
                    char **err, int argc, char **argv) {
  FILE *out_stream = tmpfile(), *err_stream = tmpfile();
  CHECK(out_stream && err_stream);
  Tool tool = {map_path, mock, m, out_stream, err_stream, test_acl_group};
  int code = run(&tool, argc, argv);
  *out = stream_text(out_stream);
  *err = stream_text(err_stream);
  fclose(out_stream);
  fclose(err_stream);
  return code;
}

/* A string member of a recorded body. */
static const char *body_string(Json *body, const char *name) {
  return string_field(body, name);
}

/* Whether a recorded body has the member. */
static bool body_has(Json *body, const char *name) {
  Json *v = NULL;
  return body && json_object_object_get_ex(body, name, &v);
}

static void self_test(void) {
  const char *id = "11111111-1111-1111-1111-111111111111";
  const char *id2 = "22222222-2222-2222-2222-222222222222";
  /* fixed offline token payloads holding only {"client_id": id} and
   * {"client_id": id2} */
  const char *jwt = "e30.eyJjbGllbnRfaWQiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0x"
                    "MTExMTExMTExMTEifQ.test";
  const char *jwt2 = "e30.eyJjbGllbnRfaWQiOiIyMjIyMjIyMi0yMjIyLTIyMjItMjIyMi0y"
                     "MjIyMjIyMjIyMjIifQ.test";
  char answer[512], answer2[512];
  snprintf(answer, sizeof(answer), "{\"client_id\":\"%s\",\"by_client_jwt\":\"%s\"}", id, jwt);
  snprintf(answer2, sizeof(answer2), "{\"client_id\":\"%s\",\"by_client_jwt\":\"%s\"}", id2, jwt2);
  char directory[] = "/tmp/ur-embed-server-XXXXXX";
  CHECK(mkdtemp(directory));
  char map_path[128], jwt_path[128];
  snprintf(map_path, sizeof(map_path), "%s/clients.json", directory);
  snprintf(jwt_path, sizeof(jwt_path), "%s/client.jwt", directory);
  const char *key = "user:alice:22222222-2222-2222-2222-222222222222";
  char *out, *err;

  /* provision: a new client, then a reissue */
  struct Mock m = {{answer, answer}, {200, 200}, 2, {{0}}, {{0}}, {0}, 0};
  char *provision_args[] = {"provision", (char *)key, jwt_path};
  CHECK(run_with(&m, map_path, &out, &err, 3, provision_args) == EXIT_OK);
  CHECK(!strcmp(out, "{\"client_id\":\"11111111-1111-1111-1111-111111111111\"}\n"));
  CHECK(!strstr(out, jwt) && !strstr(err, jwt) && !strstr(out, ".test"));
  free(out);
  free(err);
  CHECK(run_with(&m, map_path, &out, &err, 3, provision_args) == EXIT_OK);
  free(out);
  free(err);
  CHECK(!strcmp(m.paths[0], "/network/auth-client") && !strcmp(m.methods[0], "POST"));
  CHECK(!body_has(m.bodies[0], "client_id") && !body_has(m.bodies[0], "source_client_id"));
  CHECK(!strcmp(body_string(m.bodies[0], "description"), DESCRIPTION) &&
        !strcmp(body_string(m.bodies[0], "device_spec"), DEVICE_SPEC));
  CHECK(!strcmp(body_string(m.bodies[1], "client_id"), id) &&
        !body_has(m.bodies[1], "source_client_id"));
  mock_free(&m);
  /* the client JWT file and the map are private, the map round trips */
  struct stat st;
  CHECK(!stat(jwt_path, &st) && !(st.st_mode & 0077));
  CHECK(!stat(map_path, &st) && !(st.st_mode & 0077));
  FILE *jwt_file = fopen(jwt_path, "r");
  char jwt_line[256] = "";
  CHECK(jwt_file && fgets(jwt_line, sizeof(jwt_line), jwt_file));
  fclose(jwt_file);
  CHECK(!strncmp(jwt_line, jwt, strlen(jwt)));
  Json *map = load_map(map_path);
  CHECK(map && !strcmp(mapped_client(map, key), id));
  json_object_put(map);

  /* "Client does not exist." drops the mapping and provisions anew */
  struct Mock gone = {{"{\"error\":{\"message\":\"Client does not exist.\"}}", answer2},
                      {200, 200}, 2, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&gone, map_path, &out, &err, 3, provision_args) == EXIT_OK);
  free(out);
  free(err);
  CHECK(!strcmp(body_string(gone.bodies[0], "client_id"), id) &&
        !body_has(gone.bodies[1], "client_id"));
  map = load_map(map_path);
  CHECK(map && !strcmp(mapped_client(map, key), id2));
  json_object_put(map);
  mock_free(&gone);

  /* the client limit (either flag) and a refused root credential are 78 */
  const char *limits[] = {
      "{\"error\":{\"client_limit_exceeded\":true,\"message\":\"Client limit exceeded.\"}}",
      "{\"error\":{\"client_limit_exceeded\":false,\"upgrade_required\":true,\"message\":\"x\"}}"};
  char *new_key_args[] = {"provision", "user:bob:33333333-3333-3333-3333-333333333333", jwt_path};
  for (int i = 0; i < 2; i++) {
    struct Mock limit = {{limits[i]}, {200}, 1, {{0}}, {{0}}, {0}, 0};
    CHECK(run_with(&limit, map_path, &out, &err, 3, new_key_args) == EXIT_CONFIG);
    CHECK(!strcmp(err, CLIENT_LIMIT_TEXT "\n"));
    free(out);
    free(err);
    mock_free(&limit);
  }
  struct Mock unauthorized = {{"{}"}, {401}, 1, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&unauthorized, map_path, &out, &err, 3, new_key_args) == EXIT_CONFIG);
  free(out);
  free(err);
  mock_free(&unauthorized);
  /* invalid answers are failures: an error, a mismatched claim, a reissue for
   * another client */
  char mismatched[512];
  snprintf(mismatched, sizeof(mismatched), "{\"client_id\":\"%s\",\"by_client_jwt\":\"%s\"}", id2, jwt);
  const char *invalid[] = {"{\"error\":{\"message\":\"no\"}}", mismatched, "not json", answer};
  for (int i = 0; i < 4; i++) {
    struct Mock bad = {{invalid[i]}, {200}, 1, {{0}}, {{0}}, {0}, 0};
    /* the last case reissues key (mapped to id2) and gets id */
    CHECK(run_with(&bad, map_path, &out, &err, 3, i == 3 ? provision_args : new_key_args) ==
          EXIT_FAILURE_CODE);
    free(out);
    free(err);
    mock_free(&bad);
  }

  /* cap: only the given fields, null for a cleared cap */
  const char *cap_answer =
      "{\"client_id\":\"22222222-2222-2222-2222-222222222222\",\"monthly_byte_limit\":5,"
      "\"monthly_used_byte_count\":0,\"total_byte_limit\":null,\"capped\":false,\"capped_reason\":\"\"}";
  char *monthly_args[] = {"cap", (char *)key, "--monthly", "5"};
  char *null_args[] = {"cap", (char *)key, "--total", "null", "--monthly", "0"};
  char *reset_args[] = {"cap", (char *)key, "--reset-total"};
  struct Mock caps = {{cap_answer, cap_answer, cap_answer}, {200, 200, 200}, 3, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&caps, map_path, &out, &err, 4, monthly_args) == EXIT_OK);
  CHECK(strstr(out, "\"monthly_byte_limit\":5") && out[strlen(out) - 1] == '\n');
  free(out);
  free(err);
  CHECK(run_with(&caps, map_path, &out, &err, 6, null_args) == EXIT_OK);
  free(out);
  free(err);
  CHECK(run_with(&caps, map_path, &out, &err, 3, reset_args) == EXIT_OK);
  free(out);
  free(err);
  CHECK(!strcmp(caps.paths[0], "/network/client-data-cap") && !strcmp(caps.methods[0], "POST"));
  CHECK(!strcmp(json_text(caps.bodies[0]),
                "{\"client_id\":\"22222222-2222-2222-2222-222222222222\",\"monthly_byte_limit\":5}"));
  CHECK(!strcmp(json_text(caps.bodies[1]),
                "{\"client_id\":\"22222222-2222-2222-2222-222222222222\",\"total_byte_limit\":null,"
                "\"monthly_byte_limit\":0}"));
  CHECK(!strcmp(json_text(caps.bodies[2]),
                "{\"client_id\":\"22222222-2222-2222-2222-222222222222\",\"reset_total\":true}"));
  mock_free(&caps);
  /* invalid options are 78 and reach no API */
  char *bad_caps[][6] = {
      {"cap", (char *)key},
      {"cap", (char *)key, "--monthly", "10GB"},
      {"cap", (char *)key, "--monthly", "-1"},
      {"cap", (char *)key, "--monthly", "9223372036854775808"},
      {"cap", (char *)key, "--monthly", "1.5"},
      {"cap", (char *)key, "--monthly", "+5"},
      {"cap", (char *)key, "--monthly"},
      {"cap", (char *)key, "--monthly", "1", "--monthly", "2"},
      {"cap", (char *)key, "--weekly", "1"},
  };
  int bad_counts[] = {2, 4, 4, 4, 4, 4, 3, 6, 4};
  for (int i = 0; i < 9; i++) {
    struct Mock none = {{0}, {0}, 0, {{0}}, {{0}}, {0}, 0};
    CHECK(run_with(&none, map_path, &out, &err, bad_counts[i], bad_caps[i]) == EXIT_CONFIG);
    CHECK(none.calls == 0);
    free(out);
    free(err);
  }
  char *max_args[] = {"cap", (char *)key, "--total", "9223372036854775807"};
  struct Mock max = {{cap_answer}, {200}, 1, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&max, map_path, &out, &err, 4, max_args) == EXIT_OK);
  CHECK(strstr(json_text(max.bodies[0]), "\"total_byte_limit\":9223372036854775807"));
  free(out);
  free(err);
  mock_free(&max);

  /* usage: the cap object, read with the root credential; a 404 is a failure */
  char *usage_args[] = {"usage", (char *)key};
  struct Mock reads = {{cap_answer, "404 page not found", "{\"error\":{\"message\":\"no\"}}"},
                       {200, 404, 200}, 3, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&reads, map_path, &out, &err, 2, usage_args) == EXIT_OK);
  CHECK(!strcmp(reads.methods[0], "GET") &&
        !strcmp(reads.paths[0], "/network/client-data-cap?client_id=22222222-2222-2222-2222-222222222222"));
  free(out);
  free(err);
  CHECK(run_with(&reads, map_path, &out, &err, 2, usage_args) == EXIT_FAILURE_CODE);
  free(out);
  free(err);
  CHECK(run_with(&reads, map_path, &out, &err, 2, usage_args) == EXIT_FAILURE_CODE);
  free(out);
  free(err);
  mock_free(&reads);
  char *unmapped[] = {"usage", "user:nobody"};
  struct Mock none = {{0}, {0}, 0, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&none, map_path, &out, &err, 2, unmapped) == EXIT_CONFIG);
  free(out);
  free(err);

  /* usage-all: paging, and the stop on a repeated cursor */
  char *all_args[] = {"usage-all"};
  struct Mock pages = {{"{\"clients\":[{\"client_id\":\"a\"},{\"client_id\":\"b\"}],\"next_cursor\":\"c 1/+\"}",
                        "{\"clients\":[{\"client_id\":\"c\"}],\"next_cursor\":null}"},
                       {200, 200}, 2, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&pages, map_path, &out, &err, 1, all_args) == EXIT_OK);
  CHECK(!strcmp(out, "{\"client_id\":\"a\"}\n{\"client_id\":\"b\"}\n{\"client_id\":\"c\"}\n"));
  CHECK(!strcmp(pages.paths[0], "/network/client-data-caps?limit=1000") &&
        !strcmp(pages.paths[1], "/network/client-data-caps?limit=1000&cursor=c%201%2F%2B"));
  free(out);
  free(err);
  mock_free(&pages);
  struct Mock repeated = {{"{\"clients\":[],\"next_cursor\":\"x\"}",
                           "{\"clients\":[{\"client_id\":\"d\"}],\"next_cursor\":\"x\"}",
                           "{\"clients\":[],\"next_cursor\":null}"},
                          {200, 200, 200}, 3, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&repeated, map_path, &out, &err, 1, all_args) == EXIT_OK);
  CHECK(repeated.calls == 2 && !strcmp(out, "{\"client_id\":\"d\"}\n"));
  free(out);
  free(err);
  mock_free(&repeated);

  /* remove: the mapping goes for both answers */
  char *remove_args[] = {"remove", (char *)key};
  struct Mock removed = {{"{}"}, {200}, 1, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&removed, map_path, &out, &err, 2, remove_args) == EXIT_OK);
  CHECK(!strcmp(out, "{\"removed\":\"22222222-2222-2222-2222-222222222222\"}\n"));
  CHECK(!strcmp(removed.paths[0], "/network/remove-client") &&
        !strcmp(body_string(removed.bodies[0], "client_id"), id2));
  free(out);
  free(err);
  mock_free(&removed);
  map = load_map(map_path);
  CHECK(map && !mapped_client(map, key));
  json_object_put(map);
  struct Mock again = {{answer}, {200}, 1, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&again, map_path, &out, &err, 3, provision_args) == EXIT_OK);
  free(out);
  free(err);
  mock_free(&again);
  struct Mock already = {{"{\"error\":{\"message\":\"Client does not exist.\"}}"}, {200}, 1, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&already, map_path, &out, &err, 2, remove_args) == EXIT_OK);
  free(out);
  free(err);
  mock_free(&already);
  map = load_map(map_path);
  CHECK(map && !mapped_client(map, key));
  json_object_put(map);

  /* a map with other fields, such as the token server's, is refused */
  FILE *f = fopen(map_path, "w");
  CHECK(f);
  fputs("{\"version\":1,\"clients\":{},\"pending_caps\":[]}", f);
  fclose(f);
  chmod(map_path, 0600);
  struct Mock refused = {{answer}, {200}, 1, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&refused, map_path, &out, &err, 3, provision_args) == EXIT_CONFIG);
  CHECK(refused.calls == 0);
  free(out);
  free(err);
  mock_free(&refused);
  unlink(map_path);

  /* fixed texts: a key with no mapped client */
  char *unmapped_cap[] = {"cap", "user:nobody", "--monthly", "1"};
  char *unmapped_usage[] = {"usage", "user:nobody"};
  char *unmapped_remove[] = {"remove", "user:nobody"};
  char *unmapped_acl[] = {"acl", "user:nobody", "isolated"};
  char **unmapped_lines[] = {unmapped_cap, unmapped_usage, unmapped_remove, unmapped_acl};
  int unmapped_counts[] = {4, 2, 2, 3};
  for (int i = 0; i < 4; i++) {
    struct Mock unused = {{0}, {0}, 0, {{0}}, {{0}}, {0}, 0};
    CHECK(run_with(&unused, map_path, &out, &err, unmapped_counts[i], unmapped_lines[i]) == EXIT_CONFIG);
    CHECK(!strcmp(err, UNMAPPED_TEXT "\n") && unused.calls == 0);
    free(out);
    free(err);
  }

  /* ACL groups: a new client goes into the isolated group before its client
   * JWT is written, with the pending_acl record saved first and then dropped */
  test_acl_group = "isolated";
  const char *id3 = "33333333-3333-3333-3333-333333333333";
  const char *jwt3 = "e30.eyJjbGllbnRfaWQiOiIzMzMzMzMzMy0zMzMzLTMzMzMtMzMzMy0zMzMzMzMzMzMzMzMifQ.test";
  const char *id4 = "44444444-4444-4444-4444-444444444444";
  const char *jwt4 = "e30.eyJjbGllbnRfaWQiOiI0NDQ0NDQ0NC00NDQ0LTQ0NDQtNDQ0NC00NDQ0NDQ0NDQ0NDQifQ.test";
  char answer3[512], answer4[512], isolated3[160], isolated4[160], default3[160];
  snprintf(answer3, sizeof(answer3), "{\"client_id\":\"%s\",\"by_client_jwt\":\"%s\"}", id3, jwt3);
  snprintf(answer4, sizeof(answer4), "{\"client_id\":\"%s\",\"by_client_jwt\":\"%s\"}", id4, jwt4);
  snprintf(isolated3, sizeof(isolated3), "{\"client_id\":\"%s\",\"acl_group\":\"isolated\"}", id3);
  snprintf(isolated4, sizeof(isolated4), "{\"client_id\":\"%s\",\"acl_group\":\"isolated\"}", id4);
  snprintf(default3, sizeof(default3), "{\"client_id\":\"%s\",\"acl_group\":\"default\"}", id3);
  const char *key3 = "user:carol:33333333-3333-3333-3333-333333333333";
  const char *key4 = "user:carol:44444444-4444-4444-4444-444444444444";
  const char *key5 = "user:carol:55555555-5555-5555-5555-555555555555";
  char jwt3_path[160], jwt4_path[160], jwt5_path[160];
  snprintf(jwt3_path, sizeof(jwt3_path), "%s/c3.jwt", directory);
  snprintf(jwt4_path, sizeof(jwt4_path), "%s/c4.jwt", directory);
  snprintf(jwt5_path, sizeof(jwt5_path), "%s/c5.jwt", directory);
  char *provision3[] = {"provision", (char *)key3, jwt3_path};
  char *provision4[] = {"provision", (char *)key4, jwt4_path};
  char *provision5[] = {"provision", (char *)key5, jwt5_path};
  struct Mock isolate = {{answer3, isolated3}, {200, 200}, 2, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&isolate, map_path, &out, &err, 3, provision3) == EXIT_OK);
  CHECK(isolate.calls == 2 && !strcmp(isolate.methods[1], "POST") &&
        !strcmp(isolate.paths[1], "/network/client-acl-group"));
  CHECK(!strcmp(json_text(isolate.bodies[1]),
                "{\"client_id\":\"33333333-3333-3333-3333-333333333333\",\"acl_group\":\"isolated\"}"));
  free(out);
  free(err);
  mock_free(&isolate);
  map = load_map(map_path);
  CHECK(map && !acl_pending(map, key3) && !json_object_object_get_ex(map, "pending_acl", NULL));
  json_object_put(map);
  struct Mock reissue3 = {{answer3}, {200}, 1, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&reissue3, map_path, &out, &err, 3, provision3) == EXIT_OK && reissue3.calls == 1);
  free(out);
  free(err);
  mock_free(&reissue3);
  /* the ACL request fails: no client JWT, the record kept; the next provision
   * reissues and applies it */
  struct Mock failing = {{answer4, "{}"}, {200, 500}, 2, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&failing, map_path, &out, &err, 3, provision4) == EXIT_FAILURE_CODE);
  CHECK(access(jwt4_path, F_OK) != 0);
  free(out);
  free(err);
  mock_free(&failing);
  map = load_map(map_path);
  CHECK(map && !strcmp(mapped_client(map, key4), id4) && acl_pending(map, key4));
  json_object_put(map);
  struct Mock retried = {{answer4, isolated4}, {200, 200}, 2, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&retried, map_path, &out, &err, 3, provision4) == EXIT_OK);
  CHECK(!strcmp(body_string(retried.bodies[0], "client_id"), id4) &&
        !strcmp(retried.paths[1], "/network/client-acl-group"));
  CHECK(access(jwt4_path, F_OK) == 0);
  free(out);
  free(err);
  mock_free(&retried);
  map = load_map(map_path);
  CHECK(map && !acl_pending(map, key4));
  json_object_put(map);
  /* a server without ACL groups: exit 1 with the fixed text, the record kept
   * and no client JWT; a default of "default" then provisions with no ACL
   * request and drops the record */
  struct Mock old_server = {{answer2, "404 page not found"}, {200, 404}, 2, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&old_server, map_path, &out, &err, 3, provision5) == EXIT_FAILURE_CODE);
  CHECK(!strcmp(err, ACL_UNSUPPORTED_TEXT "\n") && access(jwt5_path, F_OK) != 0);
  free(out);
  free(err);
  mock_free(&old_server);
  map = load_map(map_path);
  CHECK(map && acl_pending(map, key5));
  json_object_put(map);
  test_acl_group = "default";
  struct Mock as_default = {{answer2}, {200}, 1, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&as_default, map_path, &out, &err, 3, provision5) == EXIT_OK && as_default.calls == 1);
  CHECK(access(jwt5_path, F_OK) == 0);
  free(out);
  free(err);
  mock_free(&as_default);
  map = load_map(map_path);
  CHECK(map && !acl_pending(map, key5));
  json_object_put(map);

  /* acl: the request and its printed answer; an invalid group reaches no API;
   * an answer for another group and a server without ACL groups fail */
  char *acl_default[] = {"acl", (char *)key3, "default"};
  struct Mock set_default = {{default3}, {200}, 1, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&set_default, map_path, &out, &err, 3, acl_default) == EXIT_OK);
  CHECK(!strcmp(out, "{\"client_id\":\"33333333-3333-3333-3333-333333333333\",\"acl_group\":\"default\"}\n"));
  CHECK(!strcmp(set_default.paths[0], "/network/client-acl-group") &&
        !strcmp(json_text(set_default.bodies[0]),
                "{\"client_id\":\"33333333-3333-3333-3333-333333333333\",\"acl_group\":\"default\"}"));
  free(out);
  free(err);
  mock_free(&set_default);
  char *acl_invalid[] = {"acl", (char *)key3, "private"};
  struct Mock unused_acl = {{0}, {0}, 0, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&unused_acl, map_path, &out, &err, 3, acl_invalid) == EXIT_CONFIG && unused_acl.calls == 0);
  free(out);
  free(err);
  struct Mock wrong_group = {{isolated3}, {200}, 1, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&wrong_group, map_path, &out, &err, 3, acl_default) == EXIT_FAILURE_CODE);
  free(out);
  free(err);
  mock_free(&wrong_group);
  struct Mock old_acl = {{"404 page not found"}, {404}, 1, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&old_acl, map_path, &out, &err, 3, acl_default) == EXIT_FAILURE_CODE);
  CHECK(!strcmp(err, ACL_UNSUPPORTED_TEXT "\n"));
  free(out);
  free(err);
  mock_free(&old_acl);
  /* an explicit group settles a pending default group, and a map with a valid
   * pending_acl is accepted */
  f = fopen(map_path, "w");
  CHECK(f);
  fprintf(f, "{\"version\":1,\"clients\":{\"%s\":\"%s\"},\"pending_acl\":[\"%s\"]}", key3, id3, key3);
  fclose(f);
  chmod(map_path, 0600);
  struct Mock settle = {{default3}, {200}, 1, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&settle, map_path, &out, &err, 3, acl_default) == EXIT_OK);
  free(out);
  free(err);
  mock_free(&settle);
  map = load_map(map_path);
  CHECK(map && !acl_pending(map, key3) && !strcmp(mapped_client(map, key3), id3));
  json_object_put(map);

  /* a server without the cap routes: exit 1 with the fixed text */
  char *cap_one[] = {"cap", (char *)key3, "--monthly", "1"};
  char *usage_three[] = {"usage", (char *)key3};
  char **old_cap_lines[] = {cap_one, usage_three, all_args};
  int old_cap_counts[] = {4, 2, 1};
  const char *old_cap_texts[] = {
      "/network/client-data-cap answered 404: the server predates the data-cap routes\n",
      "/network/client-data-cap answered 404: the server predates the data-cap routes\n",
      "/network/client-data-caps answered 404: the server predates the data-cap routes\n"};
  for (int i = 0; i < 3; i++) {
    struct Mock old_caps = {{"404 page not found"}, {404}, 1, {{0}}, {{0}}, {0}, 0};
    CHECK(run_with(&old_caps, map_path, &out, &err, old_cap_counts[i], old_cap_lines[i]) == EXIT_FAILURE_CODE);
    CHECK(!strcmp(err, old_cap_texts[i]));
    free(out);
    free(err);
    mock_free(&old_caps);
  }
  /* Embed not enabled: cap, usage, usage-all and acl exit 78 with the fixed
   * line */
  const char *not_enabled = "{\"error\":{\"message\":\"Embed isn't enabled for this network.\"}}";
  char *acl_isolated[] = {"acl", (char *)key3, "isolated"};
  char **embed_lines[] = {cap_one, usage_three, all_args, acl_isolated};
  int embed_counts[] = {4, 2, 1, 3};
  for (int i = 0; i < 4; i++) {
    struct Mock refused_embed = {{not_enabled}, {200}, 1, {{0}}, {{0}}, {0}, 0};
    CHECK(run_with(&refused_embed, map_path, &out, &err, embed_counts[i], embed_lines[i]) == EXIT_CONFIG);
    CHECK(!strcmp(out, "") && !strcmp(err, EMBED_NOT_ENABLED_TEXT "\n"));
    free(out);
    free(err);
    mock_free(&refused_embed);
  }
  /* provision still provisions: the client JWT written, the key kept in
   * pending_acl and the pending line on stderr; a provision after Embed is
   * enabled applies the group and drops the record */
  test_acl_group = "isolated";
  unlink(jwt4_path);
  struct Mock pending = {{answer4, not_enabled}, {200, 200}, 2, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&pending, map_path, &out, &err, 3, provision4) == EXIT_OK);
  CHECK(!strcmp(out, "{\"client_id\":\"44444444-4444-4444-4444-444444444444\"}\n") &&
        !strcmp(err, EMBED_PENDING_TEXT "\n"));
  CHECK(pending.calls == 2 && !strcmp(pending.paths[1], "/network/client-acl-group"));
  free(out);
  free(err);
  mock_free(&pending);
  jwt_file = fopen(jwt4_path, "r");
  CHECK(jwt_file && fgets(jwt_line, sizeof(jwt_line), jwt_file));
  fclose(jwt_file);
  CHECK(!strncmp(jwt_line, jwt4, strlen(jwt4)));
  map = load_map(map_path);
  CHECK(map && !strcmp(mapped_client(map, key4), id4) && acl_pending(map, key4));
  json_object_put(map);
  struct Mock enabled = {{answer4, isolated4}, {200, 200}, 2, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&enabled, map_path, &out, &err, 3, provision4) == EXIT_OK && !strcmp(err, ""));
  CHECK(!strcmp(body_string(enabled.bodies[0], "client_id"), id4) &&
        !strcmp(json_text(enabled.bodies[1]),
                "{\"client_id\":\"44444444-4444-4444-4444-444444444444\",\"acl_group\":\"isolated\"}"));
  free(out);
  free(err);
  mock_free(&enabled);
  map = load_map(map_path);
  CHECK(map && !acl_pending(map, key4));
  json_object_put(map);
  test_acl_group = "default";

  /* status: GET /network/embed printed as one line for an enabled and a not
   * enabled network; a refusal or a refused root credential is 78; a server
   * without the route and an invalid answer are 1 */
  char *status_args[] = {"status"};
  const char *states[] = {"{\"enabled\":true,\"client_limit\":5000,\"active_client_count\":1234}",
                          "{\"enabled\":false,\"client_limit\":100,\"active_client_count\":0}"};
  const char *state_lines[] = {"embed enabled: yes | client limit: 5000 | active clients: 1234\n",
                               "embed enabled: no | client limit: 100 | active clients: 0\n"};
  for (int i = 0; i < 2; i++) {
    struct Mock state = {{states[i]}, {200}, 1, {{0}}, {{0}}, {0}, 0};
    CHECK(run_with(&state, map_path, &out, &err, 1, status_args) == EXIT_OK);
    CHECK(!strcmp(out, state_lines[i]) && !strcmp(err, ""));
    CHECK(state.calls == 1 && !strcmp(state.methods[0], "GET") &&
          !strcmp(state.paths[0], "/network/embed") && !state.bodies[0]);
    free(out);
    free(err);
    mock_free(&state);
  }
  struct Mock state_refused = {{"{\"error\":{\"message\":\"Invalid credential.\"}}"}, {200}, 1, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&state_refused, map_path, &out, &err, 1, status_args) == EXIT_CONFIG);
  CHECK(!strcmp(out, "") && strstr(err, "Invalid credential.") && strchr(err, '\n') == err + strlen(err) - 1);
  free(out);
  free(err);
  mock_free(&state_refused);
  struct Mock state_unauthorized = {{""}, {401}, 1, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&state_unauthorized, map_path, &out, &err, 1, status_args) == EXIT_CONFIG);
  free(out);
  free(err);
  mock_free(&state_unauthorized);
  struct Mock state_old = {{"404 page not found"}, {404}, 1, {{0}}, {{0}}, {0}, 0};
  CHECK(run_with(&state_old, map_path, &out, &err, 1, status_args) == EXIT_FAILURE_CODE);
  CHECK(!strcmp(err, EMBED_UNSUPPORTED_TEXT "\n"));
  free(out);
  free(err);
  mock_free(&state_old);
  const char *invalid_states[] = {
      "{\"enabled\":true}",
      "{\"enabled\":\"yes\",\"client_limit\":1,\"active_client_count\":1}",
      "{\"enabled\":true,\"client_limit\":-1,\"active_client_count\":0}",
      "{\"enabled\":true,\"client_limit\":1.5,\"active_client_count\":0}"};
  for (int i = 0; i < 4; i++) {
    struct Mock state_invalid = {{invalid_states[i]}, {200}, 1, {{0}}, {{0}}, {0}, 0};
    CHECK(run_with(&state_invalid, map_path, &out, &err, 1, status_args) == EXIT_FAILURE_CODE);
    CHECK(!strcmp(out, ""));
    free(out);
    free(err);
    mock_free(&state_invalid);
  }

  /* a pending_acl entry that is not a mapped key, or not an array, is refused */
  const char *bad_pending[] = {
      "{\"version\":1,\"clients\":{},\"pending_acl\":[\"user:nobody\"]}",
      "{\"version\":1,\"clients\":{},\"pending_acl\":{}}"};
  for (int i = 0; i < 2; i++) {
    f = fopen(map_path, "w");
    CHECK(f);
    fputs(bad_pending[i], f);
    fclose(f);
    chmod(map_path, 0600);
    struct Mock unused_map = {{answer}, {200}, 1, {{0}}, {{0}}, {0}, 0};
    CHECK(run_with(&unused_map, map_path, &out, &err, 3, provision_args) == EXIT_CONFIG && unused_map.calls == 0);
    free(out);
    free(err);
  }
  unlink(map_path);
  unlink(jwt3_path);
  unlink(jwt4_path);
  unlink(jwt5_path);

  /* keys and command lines */
  CHECK(key_valid("user:alice") && key_valid(key) && !key_valid("user:../a") &&
        !key_valid(id) && !key_valid("user:") && !key_valid("user:-a"));
  char *bad_lines[][3] = {{"provision", "user:a"}, {"usage"}, {"usage-all", "x"}, {"--client-id", "x"}, {"remove", (char *)id}, {"status", "x"}};
  int bad_line_counts[] = {2, 1, 2, 2, 2, 2};
  for (int i = 0; i < 6; i++) {
    struct Mock unused = {{0}, {0}, 0, {{0}}, {{0}}, {0}, 0};
    CHECK(run_with(&unused, map_path, &out, &err, bad_line_counts[i], bad_lines[i]) == EXIT_CONFIG);
    CHECK(unused.calls == 0);
    free(out);
    free(err);
  }
  /* origins follow the allocators' rules */
  char *url = origin("http://127.0.0.1:1234");
  CHECK(url && !strcmp(url, "http://127.0.0.1:1234"));
  free(url);
  url = origin("https://api.bringyour.com/");
  CHECK(url && !strcmp(url, "https://api.bringyour.com"));
  free(url);
  CHECK(!origin("http://example.com") && !origin("https://example.com/path") &&
        !origin("https://u:p@example.com") && !origin("https://example.com?x=1"));
  unlink(jwt_path);
  rmdir(directory);
  puts("embed-server self-test passed");
}

/* Reads a required setting; NULL when unset or empty. */
static const char *setting(const char *name) {
  const char *value = getenv(name);
  return value && *value ? value : NULL;
}

int main(int argc, char **argv) {
  if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK)
    return EXIT_FAILURE_CODE;
  if (argc == 2 && !strcmp(argv[1], "--self-test")) {
    self_test();
    curl_global_cleanup();
    return EXIT_OK;
  }
  Tool tool = {NULL, api_call, NULL, stdout, stderr, NULL};
  const char *acl_group = setting("URNETWORK_DEFAULT_ACL_GROUP");
  const char *root = setting("URNETWORK_ROOT_JWT"),
             *map_path = setting("URNETWORK_CLIENT_MAP"),
             *base = setting("URNETWORK_API_URL");
  char *api_origin = origin(base ? base : "https://api.bringyour.com");
  int code;
  if (argc < 2) {
    code = report(&tool, EXIT_CONFIG, USAGE);
  } else if (acl_group && strcmp(acl_group, "isolated") && strcmp(acl_group, "default")) {
    code = report(&tool, EXIT_CONFIG, "URNETWORK_DEFAULT_ACL_GROUP must be default or isolated");
  } else if (!root || strpbrk(root, " \t\r\n")) {
    code = report(&tool, EXIT_CONFIG, "set URNETWORK_ROOT_JWT to the root credential");
  } else if (!map_path || map_path[0] != '/') {
    code = report(&tool, EXIT_CONFIG,
                  "set URNETWORK_CLIENT_MAP to an absolute path in a private directory");
  } else if (!api_origin) {
    code = report(&tool, EXIT_CONFIG,
                  "URNETWORK_API_URL must be an HTTPS origin, or loopback HTTP for local tests");
  } else {
    struct Backend backend = {api_origin, root};
    tool.map_path = map_path;
    tool.context = &backend;
    tool.acl_group = acl_group ? acl_group : "isolated";
    code = run(&tool, argc - 1, argv + 1);
  }
  free(api_origin);
  curl_global_cleanup();
  return code;
}
