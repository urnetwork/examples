/* Declarations shared by the files of the C provider example
 * (PROVIDER_CONTRACT.md): main.c runs the commands, session.c runs the
 * provider device, status.c and state.c hold the status rules and the
 * installation state, json.c reads the json values that the sdk returns, and
 * selftest.c checks the rules. Only main.c and session.c call the sdk, so
 * `make self-test` builds the other files into a self-test that never loads
 * the sdk library; urnetwork_sdk.h is included here for its constants. */
#ifndef UR_PROVIDER_H
#define UR_PROVIDER_H

/* POSIX 2008 with its XSI part (mkstemp, SA_ONSTACK), and on macOS the full
 * system headers (O_NOFOLLOW, mkdtemp). Every file includes this header
 * first, before any system header. */
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

/* ----- commands and exit codes (main.c) ----- */

/* Exit codes, for supervisors (background/README.md). */
enum {
  /* a requested stop: Ctrl-C, SIGTERM */
  UR_PROVIDER_EXIT_STOPPED = 0,
  /* any other failure; supervisors restart after a delay */
  UR_PROVIDER_EXIT_FAILURE = 1,
  /* a configuration or credential problem that a restart does not fix
   * (sysexits EX_CONFIG) */
  UR_PROVIDER_EXIT_CONFIG = 78,
  /* an unknown command line is a configuration problem */
  UR_PROVIDER_EXIT_USAGE = UR_PROVIDER_EXIT_CONFIG,
};

#define UR_PROVIDER_USAGE "usage: provider [run] | --self-test | --version"

/* The forms of the command line. */
typedef enum {
  UR_PROVIDER_COMMAND_RUN,
  UR_PROVIDER_COMMAND_SELF_TEST,
  UR_PROVIDER_COMMAND_VERSION,
  /* anything else: print the usage and exit with UR_PROVIDER_EXIT_USAGE */
  UR_PROVIDER_COMMAND_USAGE,
} ur_provider_command;

/* The command that the arguments name: no argument or `run` runs the
 * provider. */
static inline ur_provider_command ur_provider_parse_command(int argc,
                                                            char **argv) {
  if (argc <= 1 || (argc == 2 && !strcmp(argv[1], "run")))
    return UR_PROVIDER_COMMAND_RUN;
  if (argc == 2 && !strcmp(argv[1], "--self-test"))
    return UR_PROVIDER_COMMAND_SELF_TEST;
  if (argc == 2 && !strcmp(argv[1], "--version"))
    return UR_PROVIDER_COMMAND_VERSION;
  return UR_PROVIDER_COMMAND_USAGE;
}

/* ----- small shared helpers ----- */

/* A mutex that guards state shared with sdk callbacks. */
#ifdef _WIN32
typedef SRWLOCK ur_mutex;
#define UR_MUTEX_INITIALIZER SRWLOCK_INIT
/* Prepares a mutex that has no static initializer. */
static inline void ur_mutex_init(ur_mutex *mutex) { InitializeSRWLock(mutex); }
/* Takes the mutex. */
static inline void ur_mutex_lock(ur_mutex *mutex) {
  AcquireSRWLockExclusive(mutex);
}
/* Releases the mutex. */
static inline void ur_mutex_unlock(ur_mutex *mutex) {
  ReleaseSRWLockExclusive(mutex);
}
/* Ends the mutex; a slim reader/writer lock needs no cleanup. */
static inline void ur_mutex_destroy(ur_mutex *mutex) { (void)mutex; }
#else
typedef pthread_mutex_t ur_mutex;
#define UR_MUTEX_INITIALIZER PTHREAD_MUTEX_INITIALIZER
/* Prepares a mutex that has no static initializer. */
static inline void ur_mutex_init(ur_mutex *mutex) {
  pthread_mutex_init(mutex, NULL);
}
/* Takes the mutex. */
static inline void ur_mutex_lock(ur_mutex *mutex) {
  pthread_mutex_lock(mutex);
}
/* Releases the mutex. */
static inline void ur_mutex_unlock(ur_mutex *mutex) {
  pthread_mutex_unlock(mutex);
}
/* Ends the mutex. */
static inline void ur_mutex_destroy(ur_mutex *mutex) {
  pthread_mutex_destroy(mutex);
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
static inline char *ur_provider_copy_string(const char *text) {
  if (!text)
    return NULL;
  size_t size = strlen(text) + 1;
  char *copy = malloc(size);
  if (copy)
    memcpy(copy, text, size);
  return copy;
}

/* Room for an error message, a path and a status line. */
#define UR_PROVIDER_ERROR_CAPACITY 512
#define UR_PROVIDER_PATH_CAPACITY 4096
#define UR_PROVIDER_LINE_CAPACITY 512
/* a canonical uuid and its terminator */
#define UR_PROVIDER_ID_CAPACITY 37

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

extern const char ur_provider_consent_disclaimer[];

#define UR_PROVIDER_STATE_STOPPED "stopped"
/* the platform disconnected this client for its network's client limit, and
 * the sdk holds off reconnecting until the retry time */
#define UR_PROVIDER_STATE_CLIENT_LIMIT "client limit"
#define UR_PROVIDER_STATE_STARTING "starting"
#define UR_PROVIDER_STATE_PAUSED "paused"
#define UR_PROVIDER_STATE_PROVIDING "providing"

/* the payout wallet before the first wallet read finishes */
#define UR_PROVIDER_PAYOUT_WALLET_CHECKING "checking"
/* the payout wallet when the first wallet read failed */
#define UR_PROVIDER_PAYOUT_WALLET_UNAVAILABLE "unavailable"
/* the payout wallet when no wallet is mapped */
#define UR_PROVIDER_PAYOUT_WALLET_NOT_SET "not set"

#define UR_PROVIDER_PAYOUT_WALLET_SCOPE_HOTKEY "hotkey"
#define UR_PROVIDER_PAYOUT_WALLET_SCOPE_PROVIDER "this provider"
#define UR_PROVIDER_PAYOUT_WALLET_SCOPE_NETWORK "network"
#define UR_PROVIDER_PAYOUT_WALLET_SCOPE_ANOTHER_PROVIDER "another provider"

/* The consent_scope of a network's hotkey delegation entry in GET /sn/wallet.
 * Hotkey delegations come with a later server and sdk change, which adds the
 * sdk's own constant; the app only labels the entry. */
#define UR_PROVIDER_SN_WALLET_CONSENT_SCOPE_HOTKEY "hotkey"

/* Distinct clients are counted up to this many; beyond it the count is a
 * lower bound, shown with a trailing "+". */
#define UR_PROVIDER_CLIENTS_SERVED_LIMIT (100 * 1000)

/* One status snapshot. The strings are borrowed: constants, or the session's
 * wallet address. */
typedef struct {
  const char *state;
  /* the end of the client limit hold in unix milliseconds, shown with the
   * client limit state; 0 when unknown */
  int64_t client_limit_retry_time;
  size_t clients_served;
  bool clients_served_at_limit;
  int64_t data_provided_byte_count;
  /* a coldkey ss58 address, or one of the payout wallet texts */
  const char *payout_wallet;
  /* NULL unless payout_wallet is an address */
  const char *payout_wallet_scope;
} ur_provider_status;

/* The client limit status json of the sdk, read. */
typedef struct {
  /* URNET_CLIENT_LIMIT_STATUS_NONE or URNET_CLIENT_LIMIT_STATUS_EXCEEDED */
  char status[32];
  /* the end of the hold in unix milliseconds, 0 without a hold */
  int64_t retry_time;
} ur_provider_client_limit_status;

/* The distinct clients that held a contract with this provider since the app
 * started. Safe for concurrent use: the sdk delivers contract details on its
 * own threads. */
typedef struct {
  size_t limit;
  ur_mutex state_lock;
  /* an open addressing set of owned peer keys */
  char **peer_keys;
  size_t capacity;
  size_t count;
  bool at_limit;
} ur_provider_clients_served;

void ur_provider_print_line(const char *line);
void ur_provider_format_byte_count(int64_t byte_count, char *text,
                                   size_t capacity);
void ur_provider_status_text(const char *state,
                             int64_t client_limit_retry_time, char *text,
                             size_t capacity);
void ur_provider_status_line(const ur_provider_status *status, char *line,
                             size_t capacity);
void ur_provider_status_key(const ur_provider_status *status, char *key,
                            size_t capacity);
const char *ur_provider_state(int64_t provide_mode,
                              const char *client_limit_status,
                              bool provide_paused, bool provide_enabled,
                              bool provider_connected);
const char *ur_provider_payout_wallet_scope(const char *wallet_consent_scope,
                                            const char *wallet_client_id,
                                            const char *client_id);
int64_t ur_provider_data_provided_byte_count(const char *packet_stats_json);
void ur_provider_read_client_limit_status(
    const char *client_limit_status_json,
    ur_provider_client_limit_status *client_limit_status);
bool ur_provider_contract_peer_key(const char *contract_details_json,
                                   bool receive, char *peer_key,
                                   size_t capacity);
char *ur_provider_payout_wallet(const char *sn_wallet_json,
                                const char *client_id, const char **scope);
bool ur_provider_wallet_read_succeeded(const char *result_json,
                                       const char *error);
void ur_provider_clients_served_init(ur_provider_clients_served *served,
                                     size_t limit);
void ur_provider_clients_served_add(ur_provider_clients_served *served,
                                    const char *contract_details_json,
                                    bool receive);
void ur_provider_clients_served_count(ur_provider_clients_served *served,
                                      size_t *count, bool *at_limit);
void ur_provider_clients_served_free(ur_provider_clients_served *served);

/* ----- installation state (state.c) ----- */

#define UR_PROVIDER_CLIENT_JWT_FILE_NAME "client.jwt"
#define UR_PROVIDER_INSTANCE_ID_FILE_NAME "instance-id"
#define UR_PROVIDER_IDENTITY_FILE_NAME "identity.json"
/* the largest state file the app reads */
#define UR_PROVIDER_STATE_FILE_BYTE_LIMIT (64 * 1024)
#define UR_PROVIDER_IDENTITY_VERSION 1

/* identity.json. An identity belongs to one client: a newly provisioned client
 * gets a new identity. */
typedef struct {
  int version;
  char client_id[UR_PROVIDER_ID_CAPACITY];
  ur_bytes client_key_seed;
  ur_bytes provide_tls_certificate_pem;
  ur_bytes provide_tls_private_key_pem;
  ur_bytes extender_key_seed;
} ur_provider_identity;

/* The installation state loaded at start. */
typedef struct {
  char state_dir[UR_PROVIDER_PATH_CAPACITY];
  /* owned */
  char *client_jwt;
  /* the client_id claim of client_jwt */
  char client_id[UR_PROVIDER_ID_CAPACITY];
  char instance_id[UR_PROVIDER_ID_CAPACITY];
  /* false on first run, and when the stored identity belongs to another
   * client: the device then makes a new identity, which the app saves */
  bool has_identity;
  ur_provider_identity identity;
} ur_provider_config;

/* The outcome of reading a state file. */
typedef enum {
  UR_PROVIDER_READ_OK,
  UR_PROVIDER_READ_MISSING,
  UR_PROVIDER_READ_FAILED,
} ur_provider_read_result;

char *ur_provider_environment_state_dir(void);
bool ur_provider_path_join(char *path, size_t capacity, const char *dir,
                           const char *name);
bool ur_provider_check_state_dir(const char *state_dir, char *error,
                                 size_t capacity);
ur_provider_read_result ur_provider_read_private_file(const char *path,
                                                      ur_bytes *data,
                                                      char *error,
                                                      size_t capacity);
bool ur_provider_write_private_file(const char *path, const void *data,
                                    size_t length, char *error,
                                    size_t capacity);
bool ur_provider_make_private_dir(const char *path, char *error,
                                  size_t capacity);
bool ur_provider_make_temp_dir(char *path, size_t capacity);
bool ur_provider_remove_path(const char *path, bool dir);
bool ur_provider_random_bytes(uint8_t *data, size_t length);
char *ur_provider_base64_encode(const uint8_t *data, size_t length);
bool ur_provider_base64_decode(const char *text, size_t length, bool url,
                               ur_bytes *data);
bool ur_provider_parse_uuid(const char *text, size_t length,
                            char uuid[UR_PROVIDER_ID_CAPACITY]);
bool ur_provider_new_uuid(char uuid[UR_PROVIDER_ID_CAPACITY]);
bool ur_provider_parse_client_jwt_client_id(
    const char *client_jwt, char client_id[UR_PROVIDER_ID_CAPACITY],
    char *error, size_t capacity);
bool ur_provider_load_or_create_instance_id(
    const char *state_dir, char instance_id[UR_PROVIDER_ID_CAPACITY],
    char *error, size_t capacity);
bool ur_provider_load_identity(const char *state_dir, const char *client_id,
                               ur_provider_identity *identity, bool *found,
                               char *error, size_t capacity);
bool ur_provider_save_identity(const char *state_dir,
                               const ur_provider_identity *identity,
                               char *error, size_t capacity);
void ur_provider_identity_free(ur_provider_identity *identity);
bool ur_provider_load_config(const char *state_dir, ur_provider_config *config,
                             char *error, size_t capacity);
void ur_provider_config_free(ur_provider_config *config);

/* ----- the running provider (session.c) ----- */

/* One run of the provider: the network space manager, the provider device and
 * its subscriptions. The wallet fields belong to the thread that runs it. */
typedef struct {
  const ur_provider_config *config;
  uint64_t manager;
  uint64_t space;
  uint64_t api;
  uint64_t device;
  uint64_t subs[4];
  /* the mapped coldkey, owned; NULL while payout_wallet_text is shown */
  char *payout_wallet_address;
  /* checking, unavailable or not set */
  const char *payout_wallet_text;
  /* the scope label of payout_wallet_address */
  const char *payout_wallet_scope;
} ur_provider_session;

void ur_provider_request_stop(void);
bool ur_provider_session_open(ur_provider_session *session,
                              const ur_provider_config *config, char *error,
                              size_t capacity);
int ur_provider_session_run(ur_provider_session *session);
void ur_provider_session_close(ur_provider_session *session);

/* ----- self-test (selftest.c) ----- */

bool ur_provider_self_test(char *failure, size_t capacity);
int ur_provider_self_test_main(void);

#endif
