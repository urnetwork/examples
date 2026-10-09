/* The C embed example: a console app for Windows, macOS and Linux that embeds
 * the URnetwork SDK in the developer's own product (EMBED_CONTRACT.md). It
 * obtains this installation's scoped client JWT from the developer's backend,
 * starts a local device with it on the SDK's C ABI, and shows the status and
 * the data caps that the backend set for this installation. The device carries
 * only the app's own traffic; what the app sends through it continues in the
 * Sockets and Messages examples.
 *
 * Usage: embed [run] | --self-test | --licenses | --version. The settings come
 * from the environment: URNETWORK_EMBED_STATE_DIR (required),
 * URNETWORK_TOKEN_SERVER_URL with URNETWORK_DEMO_SESSION, and
 * URNETWORK_API_URL.
 *
 * Exit codes: 0 stopped on request, 78 configuration or credential problem
 * (restarting does not help), 1 any other failure. */
#include "embed.h"
#include <curl/curl.h>
#include <signal.h>
#include <stdio.h>


#ifdef _WIN32
/* Ctrl-C and Ctrl-Break stop the app. The handler runs on its own thread;
 * returning TRUE keeps the process alive until the run loop stops. */
static BOOL WINAPI console_control(DWORD control) {
  if (control != CTRL_C_EVENT && control != CTRL_BREAK_EVENT)
    return FALSE;
  ur_embed_request_stop();
  return TRUE;
}
#else
/* SIGINT and SIGTERM stop the app. */
static void stop_signal(int signal_number) {
  (void)signal_number;
  ur_embed_request_stop();
}
#endif

/* Routes Ctrl-C (and SIGTERM on POSIX) to a requested stop, exit code 0. */
static bool handle_stop_requests(void) {
#ifdef _WIN32
  return SetConsoleCtrlHandler(console_control, TRUE);
#else
  struct sigaction action;
  memset(&action, 0, sizeof(action));
  action.sa_handler = stop_signal;
  sigemptyset(&action.sa_mask);
  /* the go runtime in the sdk library needs handlers that run on the
   * alternate signal stack */
  action.sa_flags = SA_ONSTACK;
  return !sigaction(SIGINT, &action, NULL) && !sigaction(SIGTERM, &action, NULL);
#endif
}

/* Keeps the sdk's log files in the logs directory of the installation state,
 * which the sdk bounds, instead of the system temp directory. The sdk still
 * copies its log lines to stderr. */
static bool configure_sdk_logs(const char *state_dir, char *error,
                               size_t capacity) {
  char log_dir[UR_EMBED_PATH_CAPACITY];
  if (!ur_embed_path_join(log_dir, sizeof(log_dir), state_dir, "logs")) {
    snprintf(error, capacity, "the path is too long");
    return false;
  }
  if (!ur_embed_make_private_dir(log_dir, error, capacity))
    return false;
  char *sdk_error = NULL;
  bool set = urnet_set_log_dir(log_dir, &sdk_error);
  if (!set)
    snprintf(error, capacity, "%s",
             sdk_error ? sdk_error : "the sdk refused the directory");
  urnet_free_string(sdk_error);
  return set;
}

/* Runs the app until a stop is requested or the credential is rejected, and
 * returns the exit code. */
static int run_embed(void) {
  char *state_dir = ur_embed_environment("URNETWORK_EMBED_STATE_DIR");
  char *token_server_url = ur_embed_environment("URNETWORK_TOKEN_SERVER_URL");
  char *demo_session = ur_embed_environment("URNETWORK_DEMO_SESSION");
  char *api_url = ur_embed_environment("URNETWORK_API_URL");
  ur_embed_settings settings = {
      .state_dir = state_dir,
      .token_server_url = token_server_url,
      .demo_session = demo_session,
      .api_url = api_url,
  };
  char error[UR_EMBED_ERROR_CAPACITY];
  ur_embed_config config;
  int exit_code = ur_embed_load_config(&settings, ur_embed_curl_http, NULL,
                                       &config, error, sizeof(error));
  free(state_dir);
  free(token_server_url);
  if (demo_session)
    memset(demo_session, 0, strlen(demo_session));
  free(demo_session);
  free(api_url);
  if (exit_code != UR_EMBED_EXIT_STOPPED) {
    fprintf(stderr, "%s\n", error);
    ur_embed_config_free(&config);
    return exit_code;
  }
  if (!configure_sdk_logs(config.state_dir, error, sizeof(error))) {
    fprintf(stderr, "could not set the sdk log directory: %s\n", error);
    ur_embed_config_free(&config);
    return UR_EMBED_EXIT_FAILURE;
  }
  if (!handle_stop_requests()) {
    fprintf(stderr, "could not handle stop signals\n");
    ur_embed_config_free(&config);
    return UR_EMBED_EXIT_FAILURE;
  }
  ur_embed_session session;
  if (!ur_embed_session_open(&session, &config, error, sizeof(error))) {
    fprintf(stderr, "could not start the device: %s\n", error);
    ur_embed_config_free(&config);
    return UR_EMBED_EXIT_FAILURE;
  }
  char line[UR_EMBED_LINE_CAPACITY];
  ur_embed_start_line(config.client_id, config.instance_id, line, sizeof(line));
  ur_embed_print_line(line);
  exit_code = ur_embed_session_run(&session);
  ur_embed_session_close(&session);
  ur_embed_config_free(&config);
  return exit_code;
}

/* Prints the sdk's licenses and data attributions for this kind of app, as
 * json. Publish them with the app. */
static int print_licenses(void) {
  char *licenses = urnet_get_licenses(ur_embed_license_app());
  if (!licenses) {
    fprintf(stderr, "the sdk returned no licenses\n");
    return UR_EMBED_EXIT_FAILURE;
  }
  ur_embed_print_line(licenses);
  urnet_free_string(licenses);
  return UR_EMBED_EXIT_STOPPED;
}

/* Runs one command and returns its exit code. */
int main(int argc, char **argv) {
  switch (ur_embed_parse_command(argc, argv)) {
  case UR_EMBED_COMMAND_SELF_TEST:
    return ur_embed_self_test_main();
  case UR_EMBED_COMMAND_LICENSES:
    return print_licenses();
  case UR_EMBED_COMMAND_VERSION: {
    char *version = urnet_version();
    ur_embed_print_line(version ? version : "");
    urnet_free_string(version);
    return UR_EMBED_EXIT_STOPPED;
  }
  case UR_EMBED_COMMAND_USAGE:
    fprintf(stderr, "%s\n", UR_EMBED_USAGE);
    return UR_EMBED_EXIT_USAGE;
  case UR_EMBED_COMMAND_RUN:
    break;
  }
  /* once, before any thread; there is no curl_global_cleanup, because a cap
   * read thread may still be in libcurl when the process exits */
  if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK) {
    fprintf(stderr, "libcurl did not start\n");
    return UR_EMBED_EXIT_FAILURE;
  }
  return run_embed();
}
