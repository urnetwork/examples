/* SERVER ONLY: ./allocator user:<authenticated-service-user-id> | --self-test
 * The authenticated backend supplies the service key internally, never a raw
 * request field or UR client ID. Set URNETWORK_ROOT_JWT, URNETWORK_CLIENT_MAP
 * (absolute path under an existing service-owned directory), and optional
 * URNETWORK_API_URL. POSIX CLI, libcurl + json-c. Confirm a crash-left .lock is
 * stale before removal. */
#define _DARWIN_C_SOURCE 1
#define _POSIX_C_SOURCE 200809L
#include <curl/curl.h>
#include <json-c/json.h>
#define CHECK(condition)                                                       \
  do {                                                                         \
    if (!(condition)) {                                                        \
      fputs("allocator self-test failed\n", stderr);                           \
      exit(1);                                                                 \
    }                                                                          \
  } while (0)
#include <ctype.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#define LIMIT (1u << 20)
typedef struct json_object Json;
typedef char *(*Provision)(Json *, void *);
static int user_valid(const char *s) {
  if (!s || strncmp(s, "user:", 5) || strlen(s) < 6 || strlen(s) > 128 ||
      !isalnum((unsigned char)s[5]))
    return 0;
  for (size_t i = 5; s[i]; i++)
    if (!isalnum((unsigned char)s[i]) && !strchr("_.:@-", s[i]))
      return 0;
  return 1;
}
static const char *service_user(int argc, char **argv) {
  return argc == 1 && user_valid(argv[0]) ? argv[0] : NULL;
}
static int id_valid(const char *s) {
  if (!s || strlen(s) != 36)
    return 0;
  for (size_t i = 0; i < 36; i++) {
    if (i == 8 || i == 13 || i == 18 || i == 23) {
      if (s[i] != '-')
        return 0;
    } else if (!strchr("0123456789abcdef", s[i]))
      return 0;
  }
  return 1;
}
static const char *string_field(Json *obj, const char *name) {
  Json *v = NULL;
  if (!obj || !json_object_object_get_ex(obj, name, &v) ||
      !json_object_is_type(v, json_type_string))
    return NULL;
  const char *s = json_object_get_string(v);
  return s && strlen(s) == (size_t)json_object_get_string_len(v) ? s : NULL;
}
static Json *parse_json(const char *raw) {
  if (!raw || strlen(raw) > LIMIT)
    return NULL;
  struct json_tokener *tok = json_tokener_new();
  if (!tok)
    return NULL;
  json_tokener_set_flags(tok, JSON_TOKENER_STRICT | JSON_TOKENER_VALIDATE_UTF8);
  Json *obj = json_tokener_parse_ex(tok, raw, (int)strlen(raw));
  size_t end = json_tokener_get_parse_end(tok);
  int ok = json_tokener_get_error(tok) == json_tokener_success;
  while (raw[end] && isspace((unsigned char)raw[end]))
    end++;
  if (!ok || raw[end]) {
    json_object_put(obj);
    obj = NULL;
  }
  json_tokener_free(tok);
  return obj;
}
static char *part(CURLU *url, CURLUPart field) {
  char *s = NULL;
  if (curl_url_get(url, field, &s, 0) != CURLUE_OK)
    return NULL;
  return s;
}
static char *endpoint(const char *base) {
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
  int local = host && (!strcmp(host, "localhost") ||
                       !strcmp(host, "127.0.0.1") || !strcmp(host, "[::1]"));
  if (!scheme || !host || user || pass || query || fragment ||
      (path && strcmp(path, "/")) ||
      (strcmp(scheme, "https") && !(local && !strcmp(scheme, "http"))))
    goto done;
  if (curl_url_set(u, CURLUPART_PATH, "/network/auth-client", 0) == CURLUE_OK) {
    char *raw = part(u, CURLUPART_URL);
    if (raw) {
      result = strdup(raw);
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
static Json *request_for(const char *user, const char *client) {
  if (!user_valid(user) || (client && !id_valid(client)))
    return NULL;
  char description[160];
  snprintf(description, sizeof(description), "service %s", user);
  Json *body = json_object_new_object();
  json_object_object_add(body, "description",
                         json_object_new_string(description));
  json_object_object_add(body, "device_spec",
                         json_object_new_string("urnetwork-examples/c-server"));
  if (client)
    json_object_object_add(body, "client_id", json_object_new_string(client));
  return body;
}
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
    const char *p = strchr(alphabet, raw[i]);
    if (!p || !raw[i]) {
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
static Json *parse_response(const char *raw, const char *expected) {
  Json *obj = parse_json(raw), *claims = NULL, *result = NULL, *error = NULL;
  char *decoded = NULL;
  if (!obj || !json_object_is_type(obj, json_type_object))
    goto done;
  if (json_object_object_get_ex(obj, "error", &error) && error &&
      !json_object_is_type(error, json_type_null))
    goto done;
  const char *id = string_field(obj, "client_id"),
             *jwt = string_field(obj, "by_client_jwt");
  if (!id_valid(id) || !jwt)
    goto done;
  const char *a = strchr(jwt, '.'), *b = a ? strchr(a + 1, '.') : NULL;
  if (!a || a == jwt || !b || b == a + 1 || !b[1] || strchr(b + 1, '.'))
    goto done;
  decoded = decode64(a + 1, (size_t)(b - a - 1));
  claims = parse_json(decoded);
  const char *claim = string_field(claims, "client_id");
  if (!claim || strcmp(claim, id) || (expected && strcmp(expected, id)))
    goto done;
  /* Claim comparison checks consistency; it is not local signature
   * verification. */
  result = json_object_new_object();
  json_object_object_add(result, "client_id", json_object_new_string(id));
  json_object_object_add(result, "by_client_jwt", json_object_new_string(jwt));
done:
  free(decoded);
  json_object_put(claims);
  json_object_put(obj);
  return result;
}
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
  int read_ok = !ferror(f) && n == (size_t)st.st_size;
  fclose(f);
  raw[n] = 0;
  Json *map = read_ok && strlen(raw) == n ? parse_json(raw) : NULL;
  free(raw);
  Json *version = NULL, *clients = NULL, *seen = json_object_new_object();
  int valid = 0;
  if (!map || !json_object_object_get_ex(map, "version", &version) ||
      !json_object_is_type(version, json_type_int) ||
      json_object_get_int(version) != 1 ||
      !json_object_object_get_ex(map, "clients", &clients) ||
      !json_object_is_type(clients, json_type_object))
    goto done;
  valid = 1;
  json_object_object_foreach(clients, key, value) {
    const char *id = json_object_is_type(value, json_type_string)
                         ? json_object_get_string(value)
                         : NULL;
    Json *duplicate = NULL;
    if (!user_valid(key) || !id_valid(id) ||
        strlen(id) != (size_t)json_object_get_string_len(value) ||
        json_object_object_get_ex(seen, id, &duplicate)) {
      valid = 0;
      break;
    }
    json_object_object_add(seen, id, json_object_new_boolean(1));
  }
done:
  json_object_put(seen);
  if (!valid) {
    json_object_put(map);
    map = NULL;
  }
  return map;
}
static int save_map(const char *path, Json *map) {
  char *tmp = malloc(strlen(path) + 8);
  if (!tmp)
    return 0;
  sprintf(tmp, "%s.XXXXXX", path);
  int fd = mkstemp(tmp);
  if (fd < 0) {
    free(tmp);
    return 0;
  }
  int ok = fchmod(fd, 0600) == 0;
  const char *raw = json_object_to_json_string_ext(map, JSON_C_TO_STRING_PLAIN);
  size_t offset = 0, length = strlen(raw);
  while (ok && offset < length) {
    ssize_t n = write(fd, raw + offset, length - offset);
    if (n < 0 && errno == EINTR)
      continue;
    if (n <= 0) {
      ok = 0;
      break;
    }
    offset += (size_t)n;
  }
  if (ok)
    ok = fsync(fd) == 0;
  if (close(fd))
    ok = 0;
  if (ok)
    ok = rename(tmp, path) == 0;
  if (!ok)
    unlink(tmp);
  free(tmp);
  return ok;
}
static Json *allocate(const char *user, const char *path, Provision call,
                      void *context) {
  if (!user_valid(user) || !path || path[0] != '/')
    return NULL;
  char *lock = malloc(strlen(path) + 6);
  if (!lock)
    return NULL;
  sprintf(lock, "%s.lock", path);
  if (mkdir(lock, 0700)) {
    free(lock);
    return NULL;
  }
  Json *map = load_map(path), *clients = NULL, *body = NULL, *result = NULL;
  char *raw = NULL;
  if (!map || !json_object_object_get_ex(map, "clients", &clients))
    goto done;
  const char *old = string_field(clients, user);
  body = request_for(user, old);
  if (!body)
    goto done;
  raw = call(body, context);
  result = parse_response(raw, old);
  if (!result)
    goto done;
  if (!old) {
    const char *id = string_field(result, "client_id");
    json_object_object_foreach(clients, key, value) {
      (void)key;
      if (!strcmp(json_object_get_string(value), id)) {
        json_object_put(result);
        result = NULL;
        goto done;
      }
    }
    json_object_object_add(clients, user, json_object_new_string(id));
    if (!save_map(path, map)) {
      json_object_put(result);
      result = NULL;
    }
  }
done:
  free(raw);
  json_object_put(body);
  json_object_put(map);
  rmdir(lock);
  free(lock);
  return result;
}
struct Buffer {
  char *bytes;
  size_t length;
};
struct Backend {
  const char *url, *root;
};
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
static char *post(Json *body, void *context) {
  struct Backend *backend = context;
  if (!backend->root || !*backend->root)
    return NULL;
  for (const char *p = backend->root; *p; p++)
    if (isspace((unsigned char)*p))
      return NULL;
  CURL *curl = curl_easy_init();
  if (!curl)
    return NULL;
  struct Buffer response = {0};
  char *authorization = malloc(strlen(backend->root) + 23);
  if (!authorization) {
    curl_easy_cleanup(curl);
    return NULL;
  }
  sprintf(authorization, "Authorization: Bearer %s", backend->root);
  struct curl_slist *headers = NULL;
  headers = curl_slist_append(headers, authorization);
  headers = curl_slist_append(headers, "Content-Type: application/json");
  free(authorization);
  const char *raw =
      json_object_to_json_string_ext(body, JSON_C_TO_STRING_PLAIN);
  curl_easy_setopt(curl, CURLOPT_URL, backend->url);
  curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
  curl_easy_setopt(curl, CURLOPT_POSTFIELDS, raw);
  curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, (long)strlen(raw));
  curl_easy_setopt(curl, CURLOPT_TIMEOUT, 15L);
  curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 0L);
  curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, receive);
  curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
  CURLcode error = curl_easy_perform(curl);
  long code = 0;
  curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &code);
  curl_slist_free_all(headers);
  curl_easy_cleanup(curl);
  if (error != CURLE_OK || code < 200 || code >= 300 || !response.bytes ||
      strlen(response.bytes) != response.length) {
    free(response.bytes);
    return NULL;
  }
  return response.bytes;
}
struct Mock {
  const char *response;
  Json *calls[2];
  size_t count;
};
static char *mock(Json *body, void *context) {
  struct Mock *m = context;
  if (m->count >= 2)
    return NULL;
  m->calls[m->count++] = json_object_get(body);
  return strdup(m->response);
}
static void self_test(void) {
  const char *id = "11111111-1111-1111-1111-111111111111";
  const char *jwt = "e30."
                    "eyJjbGllbnRfaWQiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMT"
                    "ExMTExMTEifQ.test";
  Json *obj = json_object_new_object();
  json_object_object_add(obj, "client_id", json_object_new_string(id));
  json_object_object_add(obj, "by_client_jwt", json_object_new_string(jwt));
  struct Mock test = {json_object_to_json_string(obj), {0}, 0};
  char directory[] = "/tmp/ur-allocator-XXXXXX";
  CHECK(mkdtemp(directory));
  char path[128];
  snprintf(path, sizeof(path), "%s/clients.json", directory);
  Json *first = allocate("user:alice", path, mock, &test),
       *second = allocate("user:alice", path, mock, &test);
  CHECK(first && second);
  CHECK(!strcmp(string_field(first, "client_id"), id));
  CHECK(!strcmp(string_field(second, "by_client_jwt"), jwt));
  Json *unused = NULL;
  CHECK(!json_object_object_get_ex(test.calls[0], "client_id", &unused));
  CHECK(!json_object_object_get_ex(test.calls[0], "source_client_id", &unused));
  CHECK(!json_object_object_get_ex(test.calls[1], "source_client_id", &unused));
  CHECK(!strcmp(string_field(test.calls[1], "client_id"), id));
  Json *map = load_map(path), *clients = NULL;
  CHECK(map && json_object_object_get_ex(map, "clients", &clients) &&
        !strcmp(string_field(clients, "user:alice"), id));
  char *url = endpoint("http://127.0.0.1:1234");
  CHECK(url && !strcmp(url, "http://127.0.0.1:1234/network/auth-client"));
  free(url);
  char *invalid[] = {(char *)id},
       *extra[] = {"user:a", "--client-id", (char *)id};
  CHECK(!service_user(1, invalid) && !service_user(3, extra) &&
        !user_valid("user:../a"));
  CHECK(!endpoint("http://example.com") &&
        !endpoint("https://example.com/path") &&
        !parse_response("{\"error\":{}}", NULL) &&
        !parse_response(test.response, "22222222-2222-2222-2222-222222222222"));
  json_object_put(map);
  json_object_put(first);
  json_object_put(second);
  json_object_put(test.calls[0]);
  json_object_put(test.calls[1]);
  json_object_put(obj);
  unlink(path);
  rmdir(directory);
  puts("allocator self-test passed");
}
int main(int argc, char **argv) {
  if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK)
    return 1;
  if (argc == 2 && !strcmp(argv[1], "--self-test")) {
    self_test();
    curl_global_cleanup();
    return 0;
  }
  const char *user = service_user(argc - 1, argv + 1),
             *base = getenv("URNETWORK_API_URL"),
             *file = getenv("URNETWORK_CLIENT_MAP"),
             *root = getenv("URNETWORK_ROOT_JWT");
  char *url = endpoint(base ? base : "https://api.bringyour.com");
  Json *result = NULL;
  if (user && url && file && root) {
    struct Backend backend = {url, root};
    result = allocate(user, file, post, &backend);
  }
  free(url);
  curl_global_cleanup();
  if (!result) {
    fputs("allocator failed: check service key, private mapping and backend "
          "API configuration\n",
          stderr);
    return 1;
  }
  puts(json_object_to_json_string_ext(result, JSON_C_TO_STRING_PLAIN));
  json_object_put(result);
  return 0;
}
