/* A running provider: the network space manager, the provider device with the
 * installation's identity, and the listeners that feed its status, on the
 * sdk's C ABI (urnetwork_sdk.h). sdk callbacks run on sdk threads: they copy
 * what they carry into process-lifetime state guarded by its state_lock and
 * hand the work to the thread that runs the session, which saves refreshed
 * tokens, applies wallet reads and ends the run on a logout. The state lives
 * for the whole process, so a native callback that races the close never
 * sees freed memory. */
#include "provider.h"
#include <signal.h>
#include <stdio.h>
#include <time.h>

/* How often the status is read, and the longest gap between status lines. */
#define STATUS_POLL_MILLIS 1000
#define STATUS_REPEAT_MILLIS (60 * 1000)

/* How often the payout wallet is read again. The wallet is fixed; a reread
 * shows a mapping that the backend completes while the provider runs. */
#define WALLET_SYNC_MILLIS (10 * 60 * 1000)

/* The device description and spec recorded for this installation's device. */
#define DEVICE_DESCRIPTION "C provider example"
#define DEVICE_SPEC "urnetwork-examples/c-provider"

/* The provider extender role's two device settings (PROVIDER_CONTRACT.md,
 * "Extender role"), passed explicitly and both on.
 * provide_extender_enabled is the embedder's hard switch: false means the role
 * never runs. default_provide_extender is the setting the device uses until
 * the user sets one: false turns the default off. */
static const bool provide_extender_enabled = true;
static const bool default_provide_extender = true;

/* The outcome of a wallet read that the run loop has not applied yet. */
typedef enum {
  WALLET_READ_NONE,
  WALLET_READ_SUCCEEDED,
  WALLET_READ_FAILED,
} wallet_read_outcome;

#ifdef _WIN32
typedef CONDITION_VARIABLE condition;
#define CONDITION_INITIALIZER CONDITION_VARIABLE_INIT
#else
typedef pthread_cond_t condition;
#define CONDITION_INITIALIZER PTHREAD_COND_INITIALIZER
#endif

/* Work that sdk callbacks hand to the run loop, guarded by state_lock. */
static struct {
  ur_mutex state_lock;
  /* signaled when work arrives, so the run loop does it at once */
  condition changed;
  /* the server rejected the client credential */
  bool logout;
  /* a refreshed client jwt that is not saved yet, owned */
  char *refreshed_client_jwt;
  wallet_read_outcome wallet_read;
} callbacks = {
    .state_lock = UR_MUTEX_INITIALIZER,
    .changed = CONDITION_INITIALIZER,
    .logout = false,
    .refreshed_client_jwt = NULL,
    .wallet_read = WALLET_READ_NONE,
};

/* The distinct clients served, fed by the contract listeners. Initialized once
 * by ur_provider_session_open, before any listener exists. */
static ur_provider_clients_served clients_served;
static bool clients_served_ready = false;

/* Set from a signal handler or the Windows console handler. */
static volatile sig_atomic_t stop_requested = 0;

/* Asks the run loop to stop; safe in a signal handler. The loop notices within
 * one status poll. */
void ur_provider_request_stop(void) { stop_requested = 1; }

/* Wakes the run loop; the caller holds callbacks.state_lock. */
static void signal_changed_with_lock(void) {
#ifdef _WIN32
  WakeConditionVariable(&callbacks.changed);
#else
  pthread_cond_signal(&callbacks.changed);
#endif
}

/* Waits up to timeout_millis for a callback to hand over work; the caller
 * holds callbacks.state_lock. */
static void wait_changed_with_lock(int64_t timeout_millis) {
#ifdef _WIN32
  SleepConditionVariableSRW(&callbacks.changed, &callbacks.state_lock,
                            (DWORD)timeout_millis, 0);
#else
  struct timespec deadline;
  clock_gettime(CLOCK_REALTIME, &deadline);
  deadline.tv_sec += (time_t)(timeout_millis / 1000);
  deadline.tv_nsec += (long)(timeout_millis % 1000 * 1000 * 1000);
  if (1000 * 1000 * 1000 <= deadline.tv_nsec) {
    deadline.tv_sec += 1;
    deadline.tv_nsec -= 1000 * 1000 * 1000;
  }
  pthread_cond_timedwait(&callbacks.changed, &callbacks.state_lock,
                         &deadline);
#endif
}

/* Milliseconds of a clock that never goes back, for the intervals. */
static int64_t monotonic_millis(void) {
#ifdef _WIN32
  return (int64_t)GetTickCount64();
#else
  struct timespec now;
  clock_gettime(CLOCK_MONOTONIC, &now);
  return (int64_t)now.tv_sec * 1000 + now.tv_nsec / (1000 * 1000);
#endif
}

/* Keeps the refreshed credential for the run loop to save. A newer token
 * replaces one that is not saved yet. */
static void jwt_refreshed(void *user_data, const char *client_jwt) {
  (void)user_data;
  char *copy = ur_provider_copy_string(client_jwt);
  if (!copy)
    return;
  ur_mutex_lock(&callbacks.state_lock);
  free(callbacks.refreshed_client_jwt);
  callbacks.refreshed_client_jwt = copy;
  signal_changed_with_lock();
  ur_mutex_unlock(&callbacks.state_lock);
}

/* Ends the run: the server no longer accepts this client's credential. */
static void auth_logout(void *user_data) {
  (void)user_data;
  ur_mutex_lock(&callbacks.state_lock);
  callbacks.logout = true;
  signal_changed_with_lock();
  ur_mutex_unlock(&callbacks.state_lock);
}

/* Counts the peer of a receive (ingress) contract. */
static void provider_ingress_contract_changed(
    void *user_data, const char *contract_details_json) {
  (void)user_data;
  ur_provider_clients_served_add(&clients_served, contract_details_json, true);
}

/* Counts the peer of a send (egress) contract. */
static void provider_egress_contract_changed(void *user_data,
                                             const char *contract_details_json) {
  (void)user_data;
  ur_provider_clients_served_add(&clients_served, contract_details_json, false);
}

/* Records the outcome of a wallet read for the run loop, which then reads the
 * wallet the sdk cached. */
static void wallet_synced(void *user_data, const char *result_json,
                          const char *error) {
  (void)user_data;
  bool succeeded = ur_provider_wallet_read_succeeded(result_json, error);
  ur_mutex_lock(&callbacks.state_lock);
  callbacks.wallet_read =
      succeeded ? WALLET_READ_SUCCEEDED : WALLET_READ_FAILED;
  signal_changed_with_lock();
  ur_mutex_unlock(&callbacks.state_lock);
}

/* Copies one buffer-out key material value of the device: the first call
 * asks for the size. */
static bool device_bytes(uint64_t device,
                         bool (*get)(uint64_t, uint8_t *, int32_t *),
                         ur_bytes *bytes) {
  bytes->data = NULL;
  bytes->length = 0;
  int32_t length = 0;
  get(device, NULL, &length);
  if (length <= 0)
    return length == 0;
  bytes->data = malloc((size_t)length);
  if (!bytes->data)
    return false;
  int32_t capacity = length;
  if (!get(device, bytes->data, &capacity) || length < capacity) {
    ur_bytes_free(bytes);
    return false;
  }
  bytes->length = (size_t)capacity;
  return true;
}

/* Reads the identity of a device that was created without one and saves it as
 * identity.json, so later starts present the same provider. */
static bool save_new_identity(uint64_t device, const ur_provider_config *config,
                              char *error, size_t capacity) {
  ur_provider_identity identity;
  memset(&identity, 0, sizeof(identity));
  identity.version = UR_PROVIDER_IDENTITY_VERSION;
  snprintf(identity.client_id, sizeof(identity.client_id), "%s",
           config->client_id);
  bool read = device_bytes(device, urnet_device_local_get_client_key_seed,
                           &identity.client_key_seed) &&
              device_bytes(device,
                           urnet_device_local_get_provide_tls_certificate_pem,
                           &identity.provide_tls_certificate_pem) &&
              device_bytes(device,
                           urnet_device_local_get_provide_tls_private_key_pem,
                           &identity.provide_tls_private_key_pem) &&
              device_bytes(device, urnet_device_local_get_extender_key_seed,
                           &identity.extender_key_seed);
  bool saved = false;
  if (!read || identity.client_key_seed.length != 32) {
    snprintf(error, capacity, "the device has no provider identity to save");
  } else {
    char save_error[UR_PROVIDER_ERROR_CAPACITY];
    saved = ur_provider_save_identity(config->state_dir, &identity, save_error,
                                      sizeof(save_error));
    if (!saved)
      snprintf(error, capacity, "save %s: %s", UR_PROVIDER_IDENTITY_FILE_NAME,
               save_error);
  }
  ur_provider_identity_free(&identity);
  return saved;
}

/* The key material that recreates the device's identity, or 0 without an
 * identity (the first run, or another client's identity): the device then
 * makes a new identity, which the app saves. Release it after the device
 * call. */
static uint64_t provider_key_material(const ur_provider_config *config) {
  if (!config->has_identity)
    return 0;
  const ur_provider_identity *identity = &config->identity;
  uint64_t key_material = urnet_new_device_local_key_material(
      identity->client_key_seed.data,
      (int32_t)identity->client_key_seed.length,
      identity->provide_tls_certificate_pem.data,
      (int32_t)identity->provide_tls_certificate_pem.length,
      identity->provide_tls_private_key_pem.data,
      (int32_t)identity->provide_tls_private_key_pem.length);
  urnet_device_local_key_material_set_extender_key_seed(
      key_material, identity->extender_key_seed.data,
      (int32_t)identity->extender_key_seed.length);
  return key_material;
}

/* Releases what is open, in order: the subscriptions, the device, the api and
 * the space, then the manager. */
static void release_session(ur_provider_session *session) {
  for (size_t i = 0; i < sizeof(session->subs) / sizeof(*session->subs); i++) {
    if (session->subs[i]) {
      urnet_sub_close(session->subs[i]);
      urnet_release(session->subs[i]);
      session->subs[i] = 0;
    }
  }
  if (session->device) {
    urnet_device_close(session->device);
    urnet_release(session->device);
  }
  if (session->api)
    urnet_release(session->api);
  if (session->space)
    urnet_release(session->space);
  if (session->manager) {
    urnet_network_space_manager_close(session->manager);
    urnet_release(session->manager);
  }
  session->device = 0;
  session->api = 0;
  session->space = 0;
  session->manager = 0;
}

/* Creates the provider device with the installation's identity and the
 * extender role's settings on, saves a new identity on first run, and starts
 * providing publicly. The sdk declares provide intent on the device's platform
 * connections by itself while the provide mode is public. */
bool ur_provider_session_open(ur_provider_session *session,
                              const ur_provider_config *config, char *error,
                              size_t capacity) {
  memset(session, 0, sizeof(*session));
  session->config = config;
  session->payout_wallet_text = UR_PROVIDER_PAYOUT_WALLET_CHECKING;
  if (!clients_served_ready) {
    ur_provider_clients_served_init(&clients_served,
                                    UR_PROVIDER_CLIENTS_SERVED_LIMIT);
    clients_served_ready = true;
  }

  session->manager = urnet_new_network_space_manager_no_storage();
  session->space = urnet_network_space_manager_update_network_space_values(
      session->manager, "{\"host_name\":\"ur.network\",\"env_name\":\"main\"}",
      "{\"migration_host_name\":\"bringyour.com\"}");
  if (!session->space) {
    snprintf(error, capacity, "the sdk did not create the network space");
    release_session(session);
    return false;
  }
  session->api = urnet_network_space_get_api(session->space);
  urnet_api_set_by_jwt(session->api, config->client_jwt);
  /* one call for both runs: on first run there is no identity, the key
   * material is 0 and the device makes a new identity, saved below */
  uint64_t key_material = provider_key_material(config);
  char *sdk_error = NULL;
  session->device = urnet_new_device_local_with_provide_extender(
      session->space, config->client_jwt, DEVICE_DESCRIPTION, DEVICE_SPEC, "1",
      config->instance_id, false, key_material, provide_extender_enabled,
      default_provide_extender, &sdk_error);
  if (key_material)
    urnet_release(key_material);
  if (sdk_error || !session->device) {
    snprintf(error, capacity, "%s",
             sdk_error ? sdk_error : "the sdk did not create the device");
    urnet_free_string(sdk_error);
    release_session(session);
    return false;
  }
  if (!config->has_identity &&
      !save_new_identity(session->device, config, error, capacity)) {
    release_session(session);
    return false;
  }

  session->subs[0] =
      urnet_device_add_jwt_refresh_listener(session->device, jwt_refreshed, NULL);
  session->subs[1] =
      urnet_device_add_auth_logout_listener(session->device, auth_logout, NULL);
  session->subs[2] =
      urnet_device_add_provider_ingress_contract_details_change_listener(
          session->device, provider_ingress_contract_changed, NULL);
  session->subs[3] =
      urnet_device_add_provider_egress_contract_details_change_listener(
          session->device, provider_egress_contract_changed, NULL);
  urnet_device_set_provide_mode(session->device, URNET_PROVIDE_MODE_PUBLIC);
  /* the app only displays the wallet: the backend maps it, never the app */
  urnet_device_local_sync_sn_wallet(session->device, wallet_synced, NULL);
  return true;
}

/* Reads the status from the device getters and the listener state. */
static void read_status(ur_provider_session *session,
                        ur_provider_status *status) {
  uint64_t device = session->device;
  /* "client_limit_exceeded" with the hold's end in RetryTime while the
   * platform holds this client off for its network's client limit */
  char *client_limit_status_json = urnet_device_get_client_limit_status(device);
  ur_provider_client_limit_status client_limit_status;
  ur_provider_read_client_limit_status(client_limit_status_json,
                                       &client_limit_status);
  urnet_free_string(client_limit_status_json);
  /* GetProviderReady also waits for processed client key registration, which
   * default device settings do not enable, so the connected carrier is the
   * readiness signal here */
  status->state = ur_provider_state(
      urnet_device_get_provide_mode(device), client_limit_status.status,
      urnet_device_get_provide_paused(device),
      urnet_device_get_provide_enabled(device),
      urnet_device_local_get_provider_connected(device));
  status->client_limit_retry_time = client_limit_status.retry_time;
  char *packet_stats_json = urnet_device_get_provider_packet_stats(device);
  status->data_provided_byte_count =
      ur_provider_data_provided_byte_count(packet_stats_json);
  urnet_free_string(packet_stats_json);
  ur_provider_clients_served_count(&clients_served, &status->clients_served,
                                   &status->clients_served_at_limit);
  if (session->payout_wallet_address) {
    status->payout_wallet = session->payout_wallet_address;
    status->payout_wallet_scope = session->payout_wallet_scope;
  } else {
    status->payout_wallet = session->payout_wallet_text;
    status->payout_wallet_scope = NULL;
  }
}

/* Applies a wallet read. A failure keeps the last known wallet; after a
 * success the sdk has cached the effective wallet: this client's own consent,
 * else the network consent, else (with hotkey delegations) the network's
 * hotkey entry, else a non-consent wallet. */
static void apply_wallet_read(ur_provider_session *session, bool succeeded) {
  if (!succeeded) {
    if (!session->payout_wallet_address &&
        !strcmp(session->payout_wallet_text, UR_PROVIDER_PAYOUT_WALLET_CHECKING))
      session->payout_wallet_text = UR_PROVIDER_PAYOUT_WALLET_UNAVAILABLE;
    return;
  }
  char *sn_wallet_json = urnet_device_local_get_sn_wallet(session->device);
  const char *scope = NULL;
  char *address = ur_provider_payout_wallet(
      sn_wallet_json, session->config->client_id, &scope);
  urnet_free_string(sn_wallet_json);
  free(session->payout_wallet_address);
  session->payout_wallet_address = address;
  session->payout_wallet_scope = scope;
  if (!address)
    session->payout_wallet_text = UR_PROVIDER_PAYOUT_WALLET_NOT_SET;
}

/* Keeps a refreshed credential, so the next start uses a valid token. The
 * token itself is never printed. */
static void save_client_jwt(ur_provider_session *session,
                            const char *client_jwt) {
  char path[UR_PROVIDER_PATH_CAPACITY];
  char error[UR_PROVIDER_ERROR_CAPACITY];
  size_t length = strlen(client_jwt);
  char *line = malloc(length + 2);
  bool saved =
      line &&
      ur_provider_path_join(path, sizeof(path), session->config->state_dir,
                            UR_PROVIDER_CLIENT_JWT_FILE_NAME);
  if (!saved) {
    snprintf(error, sizeof(error), "out of memory or the path is too long");
  } else {
    memcpy(line, client_jwt, length);
    line[length] = '\n';
    saved = ur_provider_write_private_file(path, line, length + 1, error,
                                           sizeof(error));
  }
  free(line);
  if (!saved)
    fprintf(stderr, "could not save the refreshed client credential: %s\n",
            error);
}

/* Takes the work that callbacks handed over and does it on this thread.
 * Returns whether the server rejected the credential. */
static bool take_callback_work(ur_provider_session *session) {
  ur_mutex_lock(&callbacks.state_lock);
  char *refreshed_client_jwt = callbacks.refreshed_client_jwt;
  wallet_read_outcome wallet_read = callbacks.wallet_read;
  bool logout = callbacks.logout;
  callbacks.refreshed_client_jwt = NULL;
  callbacks.wallet_read = WALLET_READ_NONE;
  ur_mutex_unlock(&callbacks.state_lock);
  if (refreshed_client_jwt) {
    save_client_jwt(session, refreshed_client_jwt);
    free(refreshed_client_jwt);
  }
  if (wallet_read != WALLET_READ_NONE)
    apply_wallet_read(session, wallet_read == WALLET_READ_SUCCEEDED);
  return logout;
}

/* Prints status lines until a stop is requested or the server rejects the
 * credential, and returns the process exit code. */
int ur_provider_session_run(ur_provider_session *session) {
  char last_key[UR_PROVIDER_LINE_CAPACITY] = "";
  bool printed = false;
  int64_t last_print_millis = 0;
  int64_t next_wallet_sync_millis = monotonic_millis() + WALLET_SYNC_MILLIS;
  for (;;) {
    if (take_callback_work(session)) {
      fprintf(stderr, "the server rejected the client credential; issue a new "
                      "scoped client JWT from your backend\n");
      return UR_PROVIDER_EXIT_CONFIG;
    }
    if (stop_requested)
      return UR_PROVIDER_EXIT_STOPPED;
    ur_provider_status status;
    read_status(session, &status);
    char line[UR_PROVIDER_LINE_CAPACITY];
    char key[UR_PROVIDER_LINE_CAPACITY];
    ur_provider_status_line(&status, line, sizeof(line));
    ur_provider_status_key(&status, key, sizeof(key));
    int64_t now_millis = monotonic_millis();
    if (!printed || strcmp(key, last_key) ||
        STATUS_REPEAT_MILLIS <= now_millis - last_print_millis) {
      ur_provider_print_line(line);
      snprintf(last_key, sizeof(last_key), "%s", key);
      last_print_millis = now_millis;
      printed = true;
    }
    if (next_wallet_sync_millis <= now_millis) {
      urnet_device_local_sync_sn_wallet(session->device, wallet_synced, NULL);
      next_wallet_sync_millis = now_millis + WALLET_SYNC_MILLIS;
    }
    ur_mutex_lock(&callbacks.state_lock);
    if (!callbacks.logout && !callbacks.refreshed_client_jwt &&
        callbacks.wallet_read == WALLET_READ_NONE)
      wait_changed_with_lock(STATUS_POLL_MILLIS);
    ur_mutex_unlock(&callbacks.state_lock);
  }
}

/* Stops providing and releases the subscriptions, the device and the manager.
 * A token refreshed before the close is still saved. */
void ur_provider_session_close(ur_provider_session *session) {
  if (session->device)
    urnet_device_set_provide_mode(session->device, URNET_PROVIDE_MODE_NONE);
  for (size_t i = 0; i < sizeof(session->subs) / sizeof(*session->subs); i++) {
    if (session->subs[i]) {
      urnet_sub_close(session->subs[i]);
      urnet_release(session->subs[i]);
      session->subs[i] = 0;
    }
  }
  take_callback_work(session);
  release_session(session);
  free(session->payout_wallet_address);
  session->payout_wallet_address = NULL;
  ur_provider_print_line("status: stopped");
}
