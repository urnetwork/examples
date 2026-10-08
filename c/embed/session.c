/* A running embed app (EMBED_CONTRACT.md, "App lifecycle"): the network space
 * manager, the local device with this installation's client JWT, and the
 * listeners that feed its status, on the sdk's C ABI (urnetwork_sdk.h). The
 * device carries only the app's own traffic; it does not provide.
 *
 * sdk callbacks run on sdk threads, and the cap reads run on their own
 * thread: each copies what it carries into process-lifetime state guarded by
 * its state_lock and hands the work to the thread that runs the session, which
 * saves refreshed tokens, applies cap readings and ends the run on a logout.
 * The state lives for the whole process, so a native callback or a cap read
 * that races the close never sees freed memory. */
#include "embed.h"
#include <signal.h>
#include <stdio.h>
#include <time.h>

/* How often the status is read, and the longest gap between status lines. */
#define STATUS_POLL_MILLIS 1000
#define STATUS_REPEAT_MILLIS (60 * 1000)

/* How often the caps are read. A contract status change reads them at the
 * next pass of the run loop, well within the contract's 5 seconds; changes
 * during a read add one more read. */
#define CAP_READ_MILLIS (5 * 60 * 1000)

/* The device description and spec recorded for this installation's device. */
#define DEVICE_DESCRIPTION "C embed example"
#define DEVICE_SPEC "urnetwork-examples/c-embed"

/* The destination of the app's own traffic: the best available location. */
static const char best_available_location[] =
    "{\"connect_location_id\":{\"best_available\":true}}";

#ifdef _WIN32
typedef CONDITION_VARIABLE condition;
#define CONDITION_INITIALIZER CONDITION_VARIABLE_INIT
#else
typedef pthread_cond_t condition;
#define CONDITION_INITIALIZER PTHREAD_COND_INITIALIZER
#endif

/* Work that sdk callbacks and the cap reader hand to the run loop, guarded by
 * state_lock. */
static struct {
  ur_mutex state_lock;
  /* signaled when work arrives, so the run loop does it at once */
  condition changed;
  /* the server rejected the client credential */
  bool logout;
  /* a refreshed client jwt that is not saved yet, owned */
  char *refreshed_client_jwt;
  /* a contract status changed: read the caps again soon */
  bool contract_status_changed;
  /* a finished cap read that the run loop has not applied yet */
  bool cap_read_done;
  bool cap_read_succeeded;
  ur_embed_cap cap_reading;
  char cap_read_error[UR_EMBED_ERROR_CAPACITY];
} callbacks = {
    .state_lock = UR_MUTEX_INITIALIZER,
    .changed = CONDITION_INITIALIZER,
};

/* The work taken from callbacks in one wake of the run loop. */
typedef struct {
  bool logout;
  char *refreshed_client_jwt;
  bool contract_status_changed;
  bool cap_read_done;
  bool cap_read_succeeded;
  ur_embed_cap cap_reading;
  char cap_read_error[UR_EMBED_ERROR_CAPACITY];
} callback_work;

/* Set from a signal handler or the Windows console handler. */
static volatile sig_atomic_t stop_requested = 0;

/* Asks the run loop to stop; safe in a signal handler. The loop notices within
 * one status poll. */
void ur_embed_request_stop(void) { stop_requested = 1; }

/* Wakes the run loop; the caller holds callbacks.state_lock. */
static void signal_changed_with_lock(void) {
#ifdef _WIN32
  WakeConditionVariable(&callbacks.changed);
#else
  pthread_cond_signal(&callbacks.changed);
#endif
}

/* Waits up to timeout_millis for work; the caller holds
 * callbacks.state_lock. */
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
  char *copy = ur_embed_copy_string(client_jwt);
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

/* A contract opened, closed or was refused: the caps may have changed. */
static void contract_status_changed(void *user_data,
                                    const char *contract_status_json) {
  (void)user_data;
  (void)contract_status_json;
  ur_mutex_lock(&callbacks.state_lock);
  callbacks.contract_status_changed = true;
  signal_changed_with_lock();
  ur_mutex_unlock(&callbacks.state_lock);
}

/* One cap read: the api origin and an owned copy of the client JWT. */
typedef struct {
  char api_url[UR_EMBED_URL_CAPACITY];
  char *client_jwt;
} cap_read_job;

/* Reads the caps with the client JWT and hands the reading to the run loop. */
static void run_cap_read(cap_read_job *job) {
  ur_embed_cap cap;
  char error[UR_EMBED_ERROR_CAPACITY];
  bool succeeded = ur_embed_read_caps(ur_embed_curl_http, NULL, job->api_url,
                                      job->client_jwt, &cap, error,
                                      sizeof(error));
  memset(job->client_jwt, 0, strlen(job->client_jwt));
  free(job->client_jwt);
  free(job);
  ur_mutex_lock(&callbacks.state_lock);
  callbacks.cap_read_done = true;
  callbacks.cap_read_succeeded = succeeded;
  if (succeeded)
    callbacks.cap_reading = cap;
  else
    snprintf(callbacks.cap_read_error, sizeof(callbacks.cap_read_error), "%s",
             error);
  signal_changed_with_lock();
  ur_mutex_unlock(&callbacks.state_lock);
}

#ifdef _WIN32
/* The cap reader thread. */
static DWORD WINAPI cap_read_main(LPVOID argument) {
  run_cap_read(argument);
  return 0;
}
#else
/* The cap reader thread. */
static void *cap_read_main(void *argument) {
  run_cap_read(argument);
  return NULL;
}
#endif

/* Starts a cap read on its own thread, so a slow answer never holds up the
 * status. False when the thread could not start. */
static bool start_cap_read(const ur_embed_config *config) {
  cap_read_job *job = calloc(1, sizeof(*job));
  if (!job)
    return false;
  snprintf(job->api_url, sizeof(job->api_url), "%s", config->api_url);
  job->client_jwt = ur_embed_copy_string(config->client_jwt);
  if (!job->client_jwt) {
    free(job);
    return false;
  }
#ifdef _WIN32
  HANDLE thread = CreateThread(NULL, 0, cap_read_main, job, 0, NULL);
  bool started = thread != NULL;
  if (started)
    CloseHandle(thread);
#else
  pthread_t thread;
  bool started = pthread_create(&thread, NULL, cap_read_main, job) == 0;
  if (started)
    pthread_detach(thread);
#endif
  if (!started) {
    free(job->client_jwt);
    free(job);
  }
  return started;
}

/* Releases what is open, in order: the subscriptions, the device, the api and
 * the space, then the manager. */
static void release_session(ur_embed_session *session) {
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

/* Creates the local device with the installation's client JWT and instance
 * id, adds the listeners and sets the destination of the app's traffic. The
 * provide mode stays at its default: an embed app does not provide. */
bool ur_embed_session_open(ur_embed_session *session, ur_embed_config *config,
                           char *error, size_t capacity) {
  memset(session, 0, sizeof(*session));
  session->config = config;
  ur_embed_caps_init(&session->caps);
  if (config->has_first_cap)
    ur_embed_caps_apply(&session->caps, &config->first_cap);

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
  char *sdk_error = NULL;
  session->device = urnet_new_device_local_with_defaults(
      session->space, config->client_jwt, DEVICE_DESCRIPTION, DEVICE_SPEC, "1",
      config->instance_id, false, &sdk_error);
  if (sdk_error || !session->device) {
    snprintf(error, capacity, "%s",
             sdk_error ? sdk_error : "the sdk did not create the device");
    urnet_free_string(sdk_error);
    release_session(session);
    return false;
  }
  session->subs[0] =
      urnet_device_add_jwt_refresh_listener(session->device, jwt_refreshed, NULL);
  session->subs[1] =
      urnet_device_add_auth_logout_listener(session->device, auth_logout, NULL);
  session->subs[2] = urnet_device_add_contract_status_change_listener(
      session->device, contract_status_changed, NULL);
  urnet_device_set_connect_location(session->device, best_available_location);
  return true;
}

/* Keeps a refreshed credential, so later cap reads and the next start use a
 * valid token. The token itself is never printed. */
static void save_refreshed_client_jwt(ur_embed_session *session,
                                      char *client_jwt) {
  char error[UR_EMBED_ERROR_CAPACITY];
  if (!ur_embed_save_client_jwt(session->config->state_dir, client_jwt, error,
                                sizeof(error)))
    fprintf(stderr, "could not save the refreshed client credential: %s\n",
            error);
  free(session->config->client_jwt);
  session->config->client_jwt = client_jwt;
}

/* Takes the work that callbacks and the cap reader handed over. */
static void take_callback_work(callback_work *work) {
  ur_mutex_lock(&callbacks.state_lock);
  work->logout = callbacks.logout;
  work->refreshed_client_jwt = callbacks.refreshed_client_jwt;
  work->contract_status_changed = callbacks.contract_status_changed;
  work->cap_read_done = callbacks.cap_read_done;
  work->cap_read_succeeded = callbacks.cap_read_succeeded;
  work->cap_reading = callbacks.cap_reading;
  snprintf(work->cap_read_error, sizeof(work->cap_read_error), "%s",
           callbacks.cap_read_error);
  callbacks.refreshed_client_jwt = NULL;
  callbacks.contract_status_changed = false;
  callbacks.cap_read_done = false;
  ur_mutex_unlock(&callbacks.state_lock);
}

/* Reads the status inputs from the device getters and the caps. */
static void read_status_line(ur_embed_session *session, char *line,
                             size_t capacity) {
  char client_limit_status[32];
  int64_t client_limit_retry_time;
  /* "client_limit_exceeded" with the hold's end in RetryTime while the
   * platform holds this client off for its network's concurrent client
   * limit */
  char *client_limit_status_json =
      urnet_device_get_client_limit_status(session->device);
  ur_embed_read_client_limit_status(client_limit_status_json,
                                    client_limit_status,
                                    sizeof(client_limit_status),
                                    &client_limit_retry_time);
  urnet_free_string(client_limit_status_json);
  /* NULL before the window exists */
  char *window_status_json = urnet_device_get_window_status(session->device);
  int64_t providers_added = ur_embed_providers_added(window_status_json);
  urnet_free_string(window_status_json);
  ur_embed_status_input input = {
      .started = true,
      .signed_out = false,
      .client_limit_status = client_limit_status,
      .client_limit_retry_time = client_limit_retry_time,
      .caps = &session->caps,
      .providers_added = providers_added,
  };
  ur_embed_status_line(&input, line, capacity);
}

/* Prints status lines until a stop is requested or the server rejects the
 * credential, and returns the process exit code. The caps are read at start
 * (unless the token server's data_cap was the first reading), every 5 minutes
 * and within 5 seconds of a contract status change. */
int ur_embed_session_run(ur_embed_session *session) {
  char last_line[UR_EMBED_LINE_CAPACITY] = "";
  bool printed = false;
  int64_t last_print_millis = 0;
  bool cap_read_running = false;
  bool cap_read_failing = false;
  int64_t next_cap_read_millis =
      monotonic_millis() + (session->config->has_first_cap ? CAP_READ_MILLIS : 0);
  /* a contract status change waits for a cap read */
  bool contract_cap_read_pending = false;
  for (;;) {
    callback_work work;
    take_callback_work(&work);
    if (work.refreshed_client_jwt)
      save_refreshed_client_jwt(session, work.refreshed_client_jwt);
    if (work.logout) {
      fprintf(stderr, "the server rejected the client credential; sign in "
                      "again to obtain a new client JWT from your backend\n");
      return UR_EMBED_EXIT_CONFIG;
    }
    if (stop_requested)
      return UR_EMBED_EXIT_STOPPED;
    int64_t now_millis = monotonic_millis();
    if (work.cap_read_done) {
      cap_read_running = false;
      ur_embed_caps_apply(&session->caps,
                          work.cap_read_succeeded ? &work.cap_reading : NULL);
      if (!work.cap_read_succeeded && !cap_read_failing)
        fprintf(stderr, "could not read the data caps: %s\n",
                work.cap_read_error);
      cap_read_failing = !work.cap_read_succeeded;
    }
    if (work.contract_status_changed)
      contract_cap_read_pending = true;
    if (!cap_read_running &&
        (next_cap_read_millis <= now_millis || contract_cap_read_pending)) {
      cap_read_running = start_cap_read(session->config);
      if (!cap_read_running)
        ur_embed_caps_apply(&session->caps, NULL);
      next_cap_read_millis = now_millis + CAP_READ_MILLIS;
      contract_cap_read_pending = false;
    }
    char line[UR_EMBED_LINE_CAPACITY];
    read_status_line(session, line, sizeof(line));
    if (!printed || strcmp(line, last_line) ||
        STATUS_REPEAT_MILLIS <= now_millis - last_print_millis) {
      ur_embed_print_line(line);
      snprintf(last_line, sizeof(last_line), "%s", line);
      last_print_millis = now_millis;
      printed = true;
    }
    ur_mutex_lock(&callbacks.state_lock);
    if (!callbacks.logout && !callbacks.refreshed_client_jwt &&
        !callbacks.contract_status_changed && !callbacks.cap_read_done)
      wait_changed_with_lock(STATUS_POLL_MILLIS);
    ur_mutex_unlock(&callbacks.state_lock);
  }
}

/* Closes the subscriptions, the device and the manager, in that order. A token
 * refreshed before the close is still saved. */
void ur_embed_session_close(ur_embed_session *session) {
  for (size_t i = 0; i < sizeof(session->subs) / sizeof(*session->subs); i++) {
    if (session->subs[i]) {
      urnet_sub_close(session->subs[i]);
      urnet_release(session->subs[i]);
      session->subs[i] = 0;
    }
  }
  callback_work work;
  take_callback_work(&work);
  if (work.refreshed_client_jwt)
    save_refreshed_client_jwt(session, work.refreshed_client_jwt);
  release_session(session);
}
