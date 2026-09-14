#ifndef UR_EXAMPLE_SESSION_H
#define UR_EXAMPLE_SESSION_H
#include <urnetwork_sdk.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* Handles are SDK object references, never file descriptors. Keep the network
 * space alive until its Device has closed. Every returned string is owned. */
typedef struct { uint64_t manager, space, api, device; } ur_session;
static int ur_error(char *error) {
    if (!error) return 0;
    fprintf(stderr, "%s\n", error); urnet_free_string(error); return 1;
}
static void ur_session_close(ur_session *s) {
    if (s->device) { urnet_device_close(s->device); urnet_release(s->device); }
    if (s->api) urnet_release(s->api);
    if (s->space) urnet_release(s->space);
    if (s->manager) { urnet_network_space_manager_close(s->manager); urnet_release(s->manager); }
    memset(s, 0, sizeof(*s));
}
static int ur_session_open(ur_session *s) {
    memset(s, 0, sizeof(*s));
    const char *jwt = getenv("URNETWORK_JWT"), *id = getenv("URNETWORK_INSTANCE_ID");
    if (!jwt || !*jwt || !id || !*id) { fputs("Set URNETWORK_JWT and a persistent URNETWORK_INSTANCE_ID; see README.md\n", stderr); return 1; }
    s->manager = urnet_new_network_space_manager_no_storage();
    s->space = urnet_network_space_manager_update_network_space_values(s->manager,
        "{\"host_name\":\"ur.network\",\"env_name\":\"main\"}", "{\"migration_host_name\":\"bringyour.com\"}");
    s->api = urnet_network_space_get_api(s->space);
    urnet_api_set_by_jwt(s->api, jwt);
    char *error = NULL;
    s->device = urnet_new_device_local_with_defaults(s->space, jwt, "C ABI socket example", "desktop", "1", id, false, &error);
    if (ur_error(error) || !s->device) { ur_session_close(s); return 1; }
    urnet_device_set_connect_location(s->device, "{\"connect_location_id\":{\"best_available\":true}}");
    return 0;
}
static int64_t ur_now_millis(void) {
    struct timespec ts; timespec_get(&ts, TIME_UTC);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
#endif
