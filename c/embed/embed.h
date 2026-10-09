/* Declarations shared by the files of the C embed example
 * (EMBED_CONTRACT.md): main.c runs the commands, session.c runs the embedded
 * device, status.c holds the status rules and reads the cap object, state.c
 * keeps the installation state, fetch.c obtains the client JWT and reads the
 * caps over a replaceable HTTP function, http_curl.c is that function on
 * libcurl, json.c reads json, and selftest.c checks the rules. Only main.c and
 * session.c call the sdk and only http_curl.c calls libcurl, so
 * `make self-test` builds the other files into a self-test that loads neither
 * library; urnetwork_sdk.h is included here for its constants. */
#ifndef UR_EMBED_H
#define UR_EMBED_H

/* POSIX 2008 with its XSI part (mkstemp), and on macOS the full system
 * headers (O_NOFOLLOW, mkdtemp). Every file includes this header first, before
 * any system header. */
#ifndef _WIN32
#define _XOPEN_SOURCE 700
#define _DARWIN_C_SOURCE
#endif

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#else
#include <pthread.h>
#endif

#include <urnetwork_sdk.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ----- commands and exit codes (main.c) ----- */

enum {
  /* a requested stop: Ctrl-C, SIGTERM */
  UR_EMBED_EXIT_STOPPED = 0,
  /* any other failure */
  UR_EMBED_EXIT_FAILURE = 1,
  /* a configuration or credential problem that a restart does not fix
   * (sysexits EX_CONFIG) */
  UR_EMBED_EXIT_CONFIG = 78,
  /* an unknown command line is a configuration problem */
  UR_EMBED_EXIT_USAGE = UR_EMBED_EXIT_CONFIG,
};

#define UR_EMBED_USAGE "usage: embed [run] | --self-test | --licenses | --version"

/* The forms of the command line. */
typedef enum {
  UR_EMBED_COMMAND_RUN,
  UR_EMBED_COMMAND_SELF_TEST,
  UR_EMBED_COMMAND_LICENSES,
  UR_EMBED_COMMAND_VERSION,
  /* anything else: print the usage and exit with UR_EMBED_EXIT_USAGE */
  UR_EMBED_COMMAND_USAGE,
} ur_embed_command;

/* The command that the arguments name: no argument or `run` runs the app. */
static inline ur_embed_command ur_embed_parse_command(int argc, char **argv) {
  if (argc <= 1 || (argc == 2 && !strcmp(argv[1], "run")))
    return UR_EMBED_COMMAND_RUN;
  if (argc == 2 && !strcmp(argv[1], "--self-test"))
    return UR_EMBED_COMMAND_SELF_TEST;
  if (argc == 2 && !strcmp(argv[1], "--licenses"))
    return UR_EMBED_COMMAND_LICENSES;
  if (argc == 2 && !strcmp(argv[1], "--version"))
    return UR_EMBED_COMMAND_VERSION;
  return UR_EMBED_COMMAND_USAGE;
}

/* ----- small shared helpers ----- */

/* A mutex that guards state shared with sdk callbacks and the cap reader. */
#ifdef _WIN32
typedef SRWLOCK ur_mutex;
#define UR_MUTEX_INITIALIZER SRWLOCK_INIT
/* Takes the mutex. */
static inline void ur_mutex_lock(ur_mutex *mutex) {
  AcquireSRWLockExclusive(mutex);
}
/* Releases the mutex. */
static inline void ur_mutex_unlock(ur_mutex *mutex) {
  ReleaseSRWLockExclusive(mutex);
}
#else
typedef pthread_mutex_t ur_mutex;
#define UR_MUTEX_INITIALIZER PTHREAD_MUTEX_INITIALIZER
/* Takes the mutex. */
static inline void ur_mutex_lock(ur_mutex *mutex) {
  pthread_mutex_lock(mutex);
}
/* Releases the mutex. */
static inline void ur_mutex_unlock(ur_mutex *mutex) {
  pthread_mutex_unlock(mutex);
}
#endif

/* Owned bytes; data is NULL when length is 0. */
typedef struct {
  uint8_t *data;
  size_t length;
} ur_bytes;

/* Frees the bytes and empties them. */
static inline void ur_bytes_free(ur_bytes *bytes) {
  free(bytes->data);
  bytes->data = NULL;
  bytes->length = 0;
}

/* An owned copy of a string, NULL for NULL or when out of memory. */
static inline char *ur_embed_copy_string(const char *text) {
  if (!text)
    return NULL;
  size_t size = strlen(text) + 1;
  char *copy = (char *)malloc(size);
  if (copy)
    memcpy(copy, text, size);
  return copy;
}

/* Room for an error message, a path, a url and a status line. */
#define UR_EMBED_ERROR_CAPACITY 512
#define UR_EMBED_PATH_CAPACITY 4096
#define UR_EMBED_URL_CAPACITY 1024
#define UR_EMBED_LINE_CAPACITY 512
/* a canonical uuid and its terminator */
#define UR_EMBED_ID_CAPACITY 37

/* ----- json values (json.c) ----- */

/* The kind of a json value; none is a missing member or a malformed
 * document. */
typedef enum {
  UR_JSON_NONE = 0,
  UR_JSON_NULL,
  UR_JSON_FALSE,
  UR_JSON_TRUE,
  UR_JSON_NUMBER,
  UR_JSON_STRING,
  UR_JSON_ARRAY,
  UR_JSON_OBJECT,
} ur_json_type;

/* One json value: a span of the document it was read from. */
typedef struct {
  ur_json_type type;
  const char *text;
  size_t length;
} ur_json_value;

ur_json_value ur_json_parse(const char *text, size_t length);
ur_json_value ur_json_member(ur_json_value object, const char *key);
bool ur_json_int64(ur_json_value value, int64_t *number);
char *ur_json_string(ur_json_value value);

/* ----- status (status.c) ----- */

/* The status field's texts (EMBED_CONTRACT.md, "Status"). GUI apps show the
 * first two; a console app never prints them. */
#define UR_EMBED_STATUS_SIGNED_OUT "signed out"
#define UR_EMBED_STATUS_STOPPED "stopped"
#define UR_EMBED_STATUS_CLIENT_LIMIT "client limit"
#define UR_EMBED_STATUS_PAUSED "paused"
#define UR_EMBED_STATUS_DATA_CAP_REACHED "data cap reached"
#define UR_EMBED_STATUS_CONNECTED "connected"
#define UR_EMBED_STATUS_CONNECTING "connecting"

/* The data fields' texts before and without a reading. */
#define UR_EMBED_DATA_CHECKING "checking"
#define UR_EMBED_DATA_UNAVAILABLE "unavailable"
#define UR_EMBED_DATA_NO_CAP "no cap"

/* The capped_reason values of the cap object. */
#define UR_EMBED_CAPPED_REASON_MONTHLY "monthly"
#define UR_EMBED_CAPPED_REASON_TOTAL "total"

/* One cap object (EMBED_CONTRACT.md, "The cap object"), read. A null or absent
 * limit is no cap; an absent used count is 0. */
typedef struct {
  bool has_monthly_byte_limit;
  int64_t monthly_byte_limit;
  int64_t monthly_used_byte_count;
  /* RFC 3339, as the server wrote it; empty when absent */
  char monthly_period_end[64];
  bool has_total_byte_limit;
  int64_t total_byte_limit;
  int64_t total_used_byte_count;
  bool capped;
  /* monthly, total, empty, or another value cut to fit */
  char capped_reason[24];
} ur_embed_cap;

/* What the data fields show: no reading yet, the first reading failed, or the
 * latest reading. A failure after a reading keeps that reading. */
typedef enum {
  UR_EMBED_CAPS_CHECKING,
  UR_EMBED_CAPS_UNAVAILABLE,
  UR_EMBED_CAPS_READ,
} ur_embed_caps_state;

typedef struct {
  ur_embed_caps_state state;
  /* the latest reading, when state is UR_EMBED_CAPS_READ */
  ur_embed_cap cap;
} ur_embed_caps;

/* The inputs of the status rules. */
typedef struct {
  /* GUI apps: before start or after stop; a console app is always started */
  bool started;
  /* GUI apps: after an auth logout or a token server refusal */
  bool signed_out;
  /* URNET_CLIENT_LIMIT_STATUS_NONE or URNET_CLIENT_LIMIT_STATUS_EXCEEDED */
  const char *client_limit_status;
  /* the end of the client limit hold in unix milliseconds, 0 without one */
  int64_t client_limit_retry_time;
  const ur_embed_caps *caps;
  /* ProviderStateAdded of the window status, 0 before the window exists */
  int64_t providers_added;
} ur_embed_status_input;

void ur_embed_print_line(const char *line);
/* "embed client <client_id>, installation <instance_id>", printed at start. */
void ur_embed_start_line(const char *client_id, const char *instance_id,
                         char *line, size_t capacity);
/* The GetLicenses app kind that --licenses prints: "apple" on Apple platforms,
 * "windows" on Windows, "linux" elsewhere. */
const char *ur_embed_license_app(void);
void ur_embed_format_byte_count(int64_t byte_count, char *text,
                                size_t capacity);
bool ur_embed_reset_text(const char *period_end, char *text, size_t capacity);
void ur_embed_client_limit_text(int64_t retry_time, char *text,
                                size_t capacity);
bool ur_embed_parse_cap(const char *json, size_t length, ur_embed_cap *cap);
bool ur_embed_parse_cap_value(ur_json_value value, ur_embed_cap *cap);
void ur_embed_caps_init(ur_embed_caps *caps);
void ur_embed_caps_apply(ur_embed_caps *caps, const ur_embed_cap *reading);
void ur_embed_data_field(const ur_embed_caps *caps, bool monthly, char *text,
                         size_t capacity);
void ur_embed_status_text(const ur_embed_status_input *input, char *text,
                          size_t capacity);
void ur_embed_status_line(const ur_embed_status_input *input, char *line,
                          size_t capacity);
int64_t ur_embed_providers_added(const char *window_status_json);
void ur_embed_read_client_limit_status(const char *client_limit_status_json,
                                       char *status, size_t capacity,
                                       int64_t *retry_time);

/* ----- installation state (state.c) ----- */

#define UR_EMBED_CLIENT_JWT_FILE_NAME "client.jwt"
#define UR_EMBED_INSTANCE_ID_FILE_NAME "instance-id"
/* the largest state file the app reads */
#define UR_EMBED_STATE_FILE_BYTE_LIMIT (64 * 1024)

/* The outcome of reading a state file. */
typedef enum {
  UR_EMBED_READ_OK,
  UR_EMBED_READ_MISSING,
  UR_EMBED_READ_FAILED,
} ur_embed_read_result;

char *ur_embed_environment(const char *name);
bool ur_embed_path_join(char *path, size_t capacity, const char *dir,
                        const char *name);
bool ur_embed_check_state_dir(const char *state_dir, char *error,
                              size_t capacity);
ur_embed_read_result ur_embed_read_private_file(const char *path,
                                                ur_bytes *data, char *error,
                                                size_t capacity);
bool ur_embed_write_private_file(const char *path, const void *data,
                                 size_t length, char *error, size_t capacity);
bool ur_embed_make_private_dir(const char *path, char *error,
                               size_t capacity);
bool ur_embed_make_temp_dir(char *path, size_t capacity);
bool ur_embed_remove_path(const char *path, bool dir);
bool ur_embed_random_bytes(uint8_t *data, size_t length);
bool ur_embed_base64_decode(const char *text, size_t length, bool url,
                            ur_bytes *data);
bool ur_embed_parse_uuid(const char *text, size_t length,
                         char uuid[UR_EMBED_ID_CAPACITY]);
bool ur_embed_new_uuid(char uuid[UR_EMBED_ID_CAPACITY]);
bool ur_embed_parse_client_jwt_client_id(const char *client_jwt,
                                         char client_id[UR_EMBED_ID_CAPACITY],
                                         char *error, size_t capacity);
bool ur_embed_load_or_create_instance_id(const char *state_dir,
                                         char instance_id[UR_EMBED_ID_CAPACITY],
                                         char *error, size_t capacity);
ur_embed_read_result ur_embed_load_client_jwt(
    const char *state_dir, char **client_jwt,
    char client_id[UR_EMBED_ID_CAPACITY], char *error, size_t capacity);
bool ur_embed_save_client_jwt(const char *state_dir, const char *client_jwt,
                              char *error, size_t capacity);

/* ----- http, the token fetch and the cap read (fetch.c, http_curl.c) ----- */

/* One HTTP request. authorization is the bearer token, sent as
 * "Authorization: Bearer <token>"; body, when set, is sent as
 * application/json. */
typedef struct {
  const char *method;
  const char *url;
  const char *authorization;
  const char *body;
} ur_embed_http_request;

/* One HTTP answer: its status and owned body. */
typedef struct {
  long status;
  char *body;
  size_t body_length;
} ur_embed_http_response;

/* Sends one request. False with the reason in error when no answer arrived:
 * the server is unreachable, the answer is too large, or a timeout. */
typedef bool (*ur_embed_http_fn)(void *context,
                                 const ur_embed_http_request *request,
                                 ur_embed_http_response *response, char *error,
                                 size_t capacity);

void ur_embed_http_response_free(ur_embed_http_response *response);
bool ur_embed_curl_http(void *context, const ur_embed_http_request *request,
                        ur_embed_http_response *response, char *error,
                        size_t capacity);

#define UR_EMBED_DEFAULT_API_URL "https://api.bringyour.com"
#define UR_EMBED_CLIENT_TOKEN_PATH "/urnetwork/client-token"
#define UR_EMBED_CLIENT_DATA_CAP_PATH "/network/client-data-cap"

bool ur_embed_origin_url(const char *origin, const char *path, char *url,
                         size_t capacity);

/* The outcome of a token fetch, with its exit code and GUI state
 * (EMBED_CONTRACT.md, "Obtaining the client JWT"). */
typedef enum {
  UR_EMBED_FETCH_OK,
  /* 401 unauthorized, 409 installation_limit or client_limit: exit 78, GUI
   * signed out */
  UR_EMBED_FETCH_REFUSED,
  /* unreachable, 5xx, or an invalid answer: exit 1, GUI stopped */
  UR_EMBED_FETCH_FAILED,
} ur_embed_fetch_result;

/* The token server's answer, checked. */
typedef struct {
  char client_id[UR_EMBED_ID_CAPACITY];
  /* owned; never print it */
  char *client_jwt;
  bool has_data_cap;
  ur_embed_cap data_cap;
} ur_embed_token;

ur_embed_fetch_result ur_embed_fetch_client_jwt(
    ur_embed_http_fn http, void *http_context, const char *token_server_url,
    const char *demo_session, const char *state_dir, const char *instance_id,
    ur_embed_token *token, char *error, size_t capacity);
void ur_embed_token_free(ur_embed_token *token);
bool ur_embed_read_caps(ur_embed_http_fn http, void *http_context,
                        const char *api_url, const char *client_jwt,
                        ur_embed_cap *cap, char *error, size_t capacity);

/* ----- configuration (fetch.c) ----- */

/* The settings of a run, as the environment gives them; NULL when unset. */
typedef struct {
  const char *state_dir;
  const char *token_server_url;
  const char *demo_session;
  const char *api_url;
} ur_embed_settings;

/* The configuration of a run, loaded at start. */
typedef struct {
  char state_dir[UR_EMBED_PATH_CAPACITY];
  /* owned; the run loop replaces it when the sdk refreshes the token */
  char *client_jwt;
  char client_id[UR_EMBED_ID_CAPACITY];
  char instance_id[UR_EMBED_ID_CAPACITY];
  /* the cap read's origin, checked */
  char api_url[UR_EMBED_URL_CAPACITY];
  /* the token server's data_cap, the first cap reading */
  bool has_first_cap;
  ur_embed_cap first_cap;
} ur_embed_config;

int ur_embed_load_config(const ur_embed_settings *settings,
                         ur_embed_http_fn http, void *http_context,
                         ur_embed_config *config, char *error,
                         size_t capacity);
void ur_embed_config_free(ur_embed_config *config);

/* ----- the running app (session.c) ----- */

/* One run: the network space manager, the local device and its
 * subscriptions. */
typedef struct {
  ur_embed_config *config;
  uint64_t manager;
  uint64_t space;
  uint64_t api;
  uint64_t device;
  uint64_t subs[3];
  ur_embed_caps caps;
} ur_embed_session;

void ur_embed_request_stop(void);
bool ur_embed_session_open(ur_embed_session *session, ur_embed_config *config,
                           char *error, size_t capacity);
int ur_embed_session_run(ur_embed_session *session);
void ur_embed_session_close(ur_embed_session *session);

/* ----- self-test (selftest.c) ----- */

bool ur_embed_self_test(char *failure, size_t capacity);
int ur_embed_self_test_main(void);

#ifdef __cplusplus
}
#endif

#endif
