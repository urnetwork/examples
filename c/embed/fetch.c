/* Obtaining the client JWT and reading the caps (EMBED_CONTRACT.md,
 * "Obtaining the client JWT" and "App lifecycle"), over a replaceable HTTP
 * function: the app passes ur_embed_curl_http (http_curl.c) and the self-test
 * a stand-in server. ur_embed_fetch_client_jwt is the one function to replace
 * with your own sign-in: it posts this installation's instance-id to your
 * backend and keeps the scoped client JWT that comes back. Nothing here prints
 * a client JWT or the demo session. */
#include "embed.h"
#include <ctype.h>
#include <stdarg.h>
#include <stdio.h>

/* Writes the message to error and returns false. */
static bool fail(char *error, size_t capacity, const char *format, ...) {
  va_list arguments;
  va_start(arguments, format);
  vsnprintf(error, capacity, format, arguments);
  va_end(arguments);
  return false;
}

/* Frees the answer's body and empties it. */
void ur_embed_http_response_free(ur_embed_http_response *response) {
  free(response->body);
  response->body = NULL;
  response->body_length = 0;
  response->status = 0;
}

/* Whether text[0, length) is a host name: letters, digits, dots and hyphens. */
static bool is_host_name(const char *text, size_t length) {
  if (length == 0 || 253 < length)
    return false;
  for (size_t i = 0; i < length; i++) {
    unsigned char c = (unsigned char)text[i];
    if (!isalnum(c) && c != '.' && c != '-')
      return false;
  }
  return true;
}

/* Whether text[0, length) is a bracketed IPv6 literal such as "[::1]". */
static bool is_ipv6_literal(const char *text, size_t length) {
  if (length < 3 || text[0] != '[' || text[length - 1] != ']')
    return false;
  for (size_t i = 1; i + 1 < length; i++) {
    unsigned char c = (unsigned char)text[i];
    if (!isxdigit(c) && c != ':' && c != '.')
      return false;
  }
  return true;
}

/* Checks an origin, as the allocators do: an HTTPS origin, or explicit
 * loopback HTTP (localhost, 127.0.0.1, [::1]) for local testing, optionally
 * with a port, and no credentials, path beyond "/", query or fragment. Writes
 * the origin with path appended to url. False for any other text. */
bool ur_embed_origin_url(const char *origin, const char *path, char *url,
                         size_t capacity) {
  if (!origin)
    return false;
  bool https = !strncmp(origin, "https://", 8);
  bool http = !strncmp(origin, "http://", 7);
  if (!https && !http)
    return false;
  const char *authority = origin + (https ? 8 : 7);
  size_t authority_length = strcspn(authority, "/?#");
  const char *rest = authority + authority_length;
  /* nothing after the authority but one optional "/" */
  if (*rest && strcmp(rest, "/"))
    return false;
  if (memchr(authority, '@', authority_length))
    return false;
  /* the host, then an optional ":port" */
  size_t host_length = authority_length;
  if (authority_length && authority[0] == '[') {
    const char *close = memchr(authority, ']', authority_length);
    if (!close)
      return false;
    host_length = (size_t)(close - authority) + 1;
  } else {
    const char *colon = memchr(authority, ':', authority_length);
    if (colon)
      host_length = (size_t)(colon - authority);
  }
  const char *port = authority + host_length;
  size_t port_length = authority_length - host_length;
  if (port_length) {
    if (port[0] != ':' || port_length < 2 || 6 < port_length)
      return false;
    long number = 0;
    for (size_t i = 1; i < port_length; i++) {
      if (!isdigit((unsigned char)port[i]))
        return false;
      number = number * 10 + (port[i] - '0');
    }
    if (number < 1 || 65535 < number)
      return false;
  }
  bool loopback =
      (host_length == 9 && !strncmp(authority, "localhost", 9)) ||
      (host_length == 9 && !strncmp(authority, "127.0.0.1", 9)) ||
      (host_length == 5 && !strncmp(authority, "[::1]", 5));
  if (http && !loopback)
    return false;
  if (!is_host_name(authority, host_length) &&
      !is_ipv6_literal(authority, host_length))
    return false;
  int written = snprintf(url, capacity, "%.*s%s",
                         (int)(authority + authority_length - origin), origin,
                         path);
  return 0 <= written && (size_t)written < capacity;
}

/* The error message of a refusal answer, {"error": {"message": ...}}, owned;
 * NULL when it has none. */
static char *answer_error_message(const ur_embed_http_response *response) {
  ur_json_value answer = ur_json_parse(response->body, response->body_length);
  return ur_json_string(
      ur_json_member(ur_json_member(answer, "error"), "message"));
}

/* Frees the token's client JWT and empties it. */
void ur_embed_token_free(ur_embed_token *token) {
  free(token->client_jwt);
  memset(token, 0, sizeof(*token));
}

/* Obtains this installation's client JWT from the token server: posts the
 * instance-id with the demo session as the bearer token to
 * POST /urnetwork/client-token, checks that by_client_jwt carries a client_id
 * claim equal to client_id, and saves it as client.jwt. A 401 or 409 is a
 * refusal (exit 78, GUI signed out); no answer, another status or an invalid
 * answer is a failure (exit 1, GUI stopped). The token server's data_cap, when
 * present, is the first cap reading. */
ur_embed_fetch_result ur_embed_fetch_client_jwt(
    ur_embed_http_fn http, void *http_context, const char *token_server_url,
    const char *demo_session, const char *state_dir, const char *instance_id,
    ur_embed_token *token, char *error, size_t capacity) {
  memset(token, 0, sizeof(*token));
  char url[UR_EMBED_URL_CAPACITY];
  if (!ur_embed_origin_url(token_server_url, UR_EMBED_CLIENT_TOKEN_PATH, url,
                           sizeof(url))) {
    fail(error, capacity, "the token server URL is not an HTTPS origin");
    return UR_EMBED_FETCH_FAILED;
  }
  /* the instance id is a canonical uuid, which needs no json escaping */
  char body[96];
  snprintf(body, sizeof(body), "{\"installation_id\":\"%s\"}", instance_id);
  ur_embed_http_request request = {
      .method = "POST",
      .url = url,
      .authorization = demo_session,
      .body = body,
  };
  ur_embed_http_response response = {0};
  char http_error[UR_EMBED_ERROR_CAPACITY];
  if (!http(http_context, &request, &response, http_error,
            sizeof(http_error))) {
    fail(error, capacity, "the token server is unreachable: %s", http_error);
    return UR_EMBED_FETCH_FAILED;
  }
  if (response.status == 401 || response.status == 409) {
    char *message = answer_error_message(&response);
    fail(error, capacity, "the token server refused this installation: %s",
         message ? message : response.status == 401 ? "unauthorized"
                                                    : "conflict");
    free(message);
    ur_embed_http_response_free(&response);
    return UR_EMBED_FETCH_REFUSED;
  }
  if (response.status != 200) {
    fail(error, capacity, "the token server answered HTTP %ld",
         response.status);
    ur_embed_http_response_free(&response);
    return UR_EMBED_FETCH_FAILED;
  }
  ur_json_value answer = ur_json_parse(response.body, response.body_length);
  char *client_id = ur_json_string(ur_json_member(answer, "client_id"));
  char *client_jwt = ur_json_string(ur_json_member(answer, "by_client_jwt"));
  ur_json_value data_cap = ur_json_member(answer, "data_cap");
  token->has_data_cap = ur_embed_parse_cap_value(data_cap, &token->data_cap);
  ur_embed_http_response_free(&response);
  char answer_client_id[UR_EMBED_ID_CAPACITY];
  char claim_client_id[UR_EMBED_ID_CAPACITY];
  char claim_error[UR_EMBED_ERROR_CAPACITY];
  ur_embed_fetch_result result = UR_EMBED_FETCH_FAILED;
  if (answer.type != UR_JSON_OBJECT || !client_id || !client_jwt ||
      !ur_embed_parse_uuid(client_id, strlen(client_id), answer_client_id)) {
    fail(error, capacity, "the token server's answer is not valid");
  } else if (!ur_embed_parse_client_jwt_client_id(
                 client_jwt, claim_client_id, claim_error,
                 sizeof(claim_error))) {
    fail(error, capacity, "the token server's answer is not valid: %s",
         claim_error);
  } else if (strcmp(claim_client_id, answer_client_id)) {
    fail(error, capacity,
         "the token server's client JWT belongs to another client");
  } else {
    char save_error[UR_EMBED_ERROR_CAPACITY];
    if (!ur_embed_save_client_jwt(state_dir, client_jwt, save_error,
                                  sizeof(save_error))) {
      fail(error, capacity, "save %s: %s", UR_EMBED_CLIENT_JWT_FILE_NAME,
           save_error);
    } else {
      snprintf(token->client_id, sizeof(token->client_id), "%s",
               answer_client_id);
      token->client_jwt = client_jwt;
      client_jwt = NULL;
      result = UR_EMBED_FETCH_OK;
    }
  }
  free(client_id);
  free(client_jwt);
  if (result != UR_EMBED_FETCH_OK)
    ur_embed_token_free(token);
  return result;
}

/* Reads this client's caps with its own client JWT:
 * GET /network/client-data-cap at the api origin. False with the reason for no
 * answer, another status (a server without the cap routes answers 404) or an
 * answer that is not a cap object. */
bool ur_embed_read_caps(ur_embed_http_fn http, void *http_context,
                        const char *api_url, const char *client_jwt,
                        ur_embed_cap *cap, char *error, size_t capacity) {
  char url[UR_EMBED_URL_CAPACITY];
  if (!ur_embed_origin_url(api_url, UR_EMBED_CLIENT_DATA_CAP_PATH, url,
                           sizeof(url)))
    return fail(error, capacity, "the API URL is not an HTTPS origin");
  ur_embed_http_request request = {
      .method = "GET",
      .url = url,
      .authorization = client_jwt,
      .body = NULL,
  };
  ur_embed_http_response response = {0};
  char http_error[UR_EMBED_ERROR_CAPACITY];
  if (!http(http_context, &request, &response, http_error,
            sizeof(http_error)))
    return fail(error, capacity, "%s", http_error);
  bool read = 200 <= response.status && response.status < 300 &&
              ur_embed_parse_cap(response.body, response.body_length, cap);
  if (!read) {
    if (200 <= response.status && response.status < 300)
      fail(error, capacity, "the answer is not a cap object");
    else
      fail(error, capacity, "HTTP %ld", response.status);
  }
  ur_embed_http_response_free(&response);
  return read;
}

/* Whether the demo session can go in an Authorization header: printable ascii
 * without spaces. */
static bool is_bearer_token(const char *text) {
  if (!*text)
    return false;
  for (const char *p = text; *p; p++) {
    if (*p <= ' ' || '~' < *p)
      return false;
  }
  return true;
}

/* Whether a setting is set: not NULL and not empty. */
static bool is_set(const char *value) { return value && *value; }

/* Loads the configuration of a run: checks the state directory and the URLs,
 * creates instance-id on first run, and obtains the client JWT from the token
 * server when one is configured, otherwise from client.jwt. Returns 0 when
 * loaded, or the exit code with the reason in error: 78 for a configuration or
 * credential problem, 1 for a token server failure. */
int ur_embed_load_config(const ur_embed_settings *settings,
                         ur_embed_http_fn http, void *http_context,
                         ur_embed_config *config, char *error,
                         size_t capacity) {
  memset(config, 0, sizeof(*config));
  const char *state_dir = settings->state_dir ? settings->state_dir : "";
  if (!ur_embed_check_state_dir(state_dir, error, capacity))
    return UR_EMBED_EXIT_CONFIG;
  snprintf(config->state_dir, sizeof(config->state_dir), "%s", state_dir);
  const char *api_url =
      is_set(settings->api_url) ? settings->api_url : UR_EMBED_DEFAULT_API_URL;
  if (!ur_embed_origin_url(api_url, "", config->api_url,
                           sizeof(config->api_url))) {
    fail(error, capacity,
         "URNETWORK_API_URL must be an HTTPS origin, or loopback HTTP for "
         "local testing");
    return UR_EMBED_EXIT_CONFIG;
  }
  bool token_server = is_set(settings->token_server_url);
  if (token_server != is_set(settings->demo_session)) {
    fail(error, capacity,
         "set both URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION, or "
         "neither");
    return UR_EMBED_EXIT_CONFIG;
  }
  char token_server_url[UR_EMBED_URL_CAPACITY];
  if (token_server &&
      !ur_embed_origin_url(settings->token_server_url, "", token_server_url,
                           sizeof(token_server_url))) {
    fail(error, capacity,
         "URNETWORK_TOKEN_SERVER_URL must be an HTTPS origin, or loopback HTTP "
         "for local testing");
    return UR_EMBED_EXIT_CONFIG;
  }
  if (token_server && !is_bearer_token(settings->demo_session)) {
    fail(error, capacity,
         "URNETWORK_DEMO_SESSION must hold the demo session token");
    return UR_EMBED_EXIT_CONFIG;
  }
  if (!ur_embed_load_or_create_instance_id(state_dir, config->instance_id,
                                           error, capacity))
    return UR_EMBED_EXIT_CONFIG;
  if (token_server) {
    ur_embed_token token;
    ur_embed_fetch_result result = ur_embed_fetch_client_jwt(
        http, http_context, token_server_url, settings->demo_session,
        state_dir, config->instance_id, &token, error, capacity);
    if (result == UR_EMBED_FETCH_REFUSED)
      return UR_EMBED_EXIT_CONFIG;
    if (result == UR_EMBED_FETCH_FAILED)
      return UR_EMBED_EXIT_FAILURE;
    config->client_jwt = token.client_jwt;
    snprintf(config->client_id, sizeof(config->client_id), "%s",
             token.client_id);
    config->has_first_cap = token.has_data_cap;
    config->first_cap = token.data_cap;
    return UR_EMBED_EXIT_STOPPED;
  }
  ur_embed_read_result read_result = ur_embed_load_client_jwt(
      state_dir, &config->client_jwt, config->client_id, error, capacity);
  if (read_result == UR_EMBED_READ_MISSING) {
    fail(error, capacity,
         "no token server and no %s: set URNETWORK_TOKEN_SERVER_URL and "
         "URNETWORK_DEMO_SESSION, or write %s with your backend tool's "
         "provision",
         UR_EMBED_CLIENT_JWT_FILE_NAME, UR_EMBED_CLIENT_JWT_FILE_NAME);
    return UR_EMBED_EXIT_CONFIG;
  }
  if (read_result == UR_EMBED_READ_FAILED)
    return UR_EMBED_EXIT_CONFIG;
  return UR_EMBED_EXIT_STOPPED;
}

/* Frees the client JWT. */
void ur_embed_config_free(ur_embed_config *config) {
  free(config->client_jwt);
  config->client_jwt = NULL;
}
