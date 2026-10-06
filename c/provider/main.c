/* The C provider example: a console app for Windows, macOS and Linux that
 * runs a URnetwork provider for the developer's network and shows its status
 * (PROVIDER_CONTRACT.md). It provides publicly with the scoped client
 * credential that the developer's backend issued for this installation; the
 * payout wallet is mapped by the backend and is only displayed here.
 *
 * Usage: provider [run] | --self-test | --version. All installation state is
 * in the private directory named by URNETWORK_PROVIDER_STATE_DIR (state.c).
 *
 * Exit codes, for supervisors: 0 stopped on request, 78 configuration or
 * credential problem (restarting does not help), 1 any other failure. */
#include "provider.h"
#include <signal.h>
#include <stdio.h>

#ifdef _WIN32
/* Ctrl-C and Ctrl-Break stop the provider. The handler runs on its own
 * thread; returning TRUE keeps the process alive until the run loop stops. */
static BOOL WINAPI console_control(DWORD control) {
  if (control != CTRL_C_EVENT && control != CTRL_BREAK_EVENT)
    return FALSE;
  ur_provider_request_stop();
  return TRUE;
}
#else
/* SIGINT and SIGTERM stop the provider. */
static void stop_signal(int signal_number) {
  (void)signal_number;
  ur_provider_request_stop();
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
 * which the sdk bounds (16 MiB files, the newest four kept at each start),
 * instead of the system temp directory. The sdk still copies its log lines to
 * stderr. */
static bool configure_sdk_logs(const char *state_dir, char *error,
                               size_t capacity) {
  char log_dir[UR_PROVIDER_PATH_CAPACITY];
  if (!ur_provider_path_join(log_dir, sizeof(log_dir), state_dir, "logs")) {
    snprintf(error, capacity, "the path is too long");
    return false;
  }
  if (!ur_provider_make_private_dir(log_dir, error, capacity))
    return false;
  char *sdk_error = NULL;
  bool set = urnet_set_log_dir(log_dir, &sdk_error);
  if (!set)
    snprintf(error, capacity, "%s",
             sdk_error ? sdk_error : "the sdk refused the directory");
  urnet_free_string(sdk_error);
  return set;
}

/* Runs the provider until a stop is requested or the credential is rejected,
 * and returns the exit code. */
static int run_provider(void) {
  ur_provider_print_line(ur_provider_consent_disclaimer);
  char error[UR_PROVIDER_ERROR_CAPACITY];
  char *state_dir = ur_provider_environment_state_dir();
  ur_provider_config config;
  bool loaded =
      ur_provider_load_config(state_dir ? state_dir : "", &config, error,
                              sizeof(error));
  free(state_dir);
  if (!loaded) {
    fprintf(stderr, "%s\n", error);
    return UR_PROVIDER_EXIT_CONFIG;
  }
  if (!configure_sdk_logs(config.state_dir, error, sizeof(error))) {
    fprintf(stderr, "could not set the sdk log directory: %s\n", error);
    ur_provider_config_free(&config);
    return UR_PROVIDER_EXIT_FAILURE;
  }
  if (!handle_stop_requests()) {
    fprintf(stderr, "could not handle stop signals\n");
    ur_provider_config_free(&config);
    return UR_PROVIDER_EXIT_FAILURE;
  }
  ur_provider_session session;
  if (!ur_provider_session_open(&session, &config, error, sizeof(error))) {
    fprintf(stderr, "could not start the provider: %s\n", error);
    ur_provider_config_free(&config);
    return UR_PROVIDER_EXIT_FAILURE;
  }
  char line[UR_PROVIDER_LINE_CAPACITY];
  snprintf(line, sizeof(line), "provider client %s, instance %s",
           config.client_id, config.instance_id);
  ur_provider_print_line(line);
  int exit_code = ur_provider_session_run(&session);
  ur_provider_session_close(&session);
  ur_provider_config_free(&config);
  return exit_code;
}

/* Runs one command and returns its exit code. */
int main(int argc, char **argv) {
  switch (ur_provider_parse_command(argc, argv)) {
  case UR_PROVIDER_COMMAND_SELF_TEST:
    return ur_provider_self_test_main();
  case UR_PROVIDER_COMMAND_VERSION: {
    char *version = urnet_version();
    ur_provider_print_line(version ? version : "");
    urnet_free_string(version);
    return UR_PROVIDER_EXIT_STOPPED;
  }
  case UR_PROVIDER_COMMAND_USAGE:
    fprintf(stderr, "%s\n", UR_PROVIDER_USAGE);
    return UR_PROVIDER_EXIT_USAGE;
  case UR_PROVIDER_COMMAND_RUN:
    break;
  }
  return run_provider();
}
