#define _POSIX_C_SOURCE 200809L
#include "../integration/ur_session.h"
#include "codec.h"
#include <ctype.h>
#include <inttypes.h>
#ifdef _WIN32
#include <bcrypt.h>
#include <windows.h>
typedef SRWLOCK ur_mutex;
typedef CONDITION_VARIABLE ur_condition;
#define UR_MUTEX_INITIALIZER SRWLOCK_INIT
#define UR_CONDITION_INITIALIZER CONDITION_VARIABLE_INIT
#define ur_lock AcquireSRWLockExclusive
#define ur_unlock ReleaseSRWLockExclusive
#define ur_signal WakeConditionVariable
static void ur_wait(ur_condition *condition, ur_mutex *mutex,
                    const struct timespec *until) {
  (void)until;
  SleepConditionVariableSRW(condition, mutex, 100, 0);
}
#else
#include <pthread.h>
typedef pthread_mutex_t ur_mutex;
typedef pthread_cond_t ur_condition;
#define UR_MUTEX_INITIALIZER PTHREAD_MUTEX_INITIALIZER
#define UR_CONDITION_INITIALIZER PTHREAD_COND_INITIALIZER
#define ur_lock pthread_mutex_lock
#define ur_unlock pthread_mutex_unlock
#define ur_signal pthread_cond_signal
#define ur_wait pthread_cond_timedwait
#endif
#include <signal.h>

typedef struct event {
  struct event *next;
  int kind, ok;
  char *json;
  char source[37];
  size_t length;
  uint8_t bytes[URMS_MAX_FRAME];
} event;
/* Process-lifetime callback state: even a racing native callback cannot see
 * freed user_data. */
static struct {
  ur_mutex mutex;
  ur_condition ready;
  event *head, *tail;
  int count, stopped;
} queue = {UR_MUTEX_INITIALIZER, UR_CONDITION_INITIALIZER, NULL, NULL, 0, 0};
static volatile sig_atomic_t interrupted;
static void stop(int signal_number) {
  (void)signal_number;
  interrupted = 1;
}
static void enqueue(event *e) {
  if (!e)
    return;
  ur_lock(&queue.mutex);
  if (queue.stopped || queue.count >= 256) {
    ur_unlock(&queue.mutex);
    free(e->json);
    free(e);
    return;
  }
  if (queue.tail)
    queue.tail->next = e;
  else
    queue.head = e;
  queue.tail = e;
  queue.count++;
  ur_signal(&queue.ready);
  ur_unlock(&queue.mutex);
}
static void message(void *u, int64_t protocol, const char *source,
                    const uint8_t *b, int32_t n) {
  (void)u;
  if (protocol != URMS_SUBPROTOCOL || !source || strlen(source) != 36 ||
      n < 16 || n > URMS_MAX_FRAME)
    return;
  event *e = calloc(1, sizeof(*e));
  if (!e)
    return;
  e->kind = 1;
  e->length = (size_t)n;
  memcpy(e->source, source, 37);
  memcpy(e->bytes, b, (size_t)n);
  enqueue(e); /* Copy ephemeral bytes now. */
}
static char *copy_string(const char *value) {
  if (!value)
    return NULL;
  size_t size = strlen(value) + 1;
  char *copy = malloc(size);
  if (copy)
    memcpy(copy, value, size);
  return copy;
}
static void peers(void *u, const char *json) {
  (void)u;
  event *e = calloc(1, sizeof(*e));
  if (e) {
    e->kind = 2;
    e->json = copy_string(json);
    enqueue(e);
  }
}
static void query(void *u, const char *json, bool ok) {
  (void)u;
  event *e = calloc(1, sizeof(*e));
  if (e) {
    e->kind = 3;
    e->ok = ok;
    e->json = copy_string(json);
    enqueue(e);
  }
}
static event *next_event(void) {
  struct timespec until;
  timespec_get(&until, TIME_UTC);
  until.tv_nsec += 100000000;
  if (until.tv_nsec >= 1000000000) {
    until.tv_nsec -= 1000000000;
    until.tv_sec++;
  }
  ur_lock(&queue.mutex);
  if (!queue.head)
    ur_wait(&queue.ready, &queue.mutex, &until);
  event *e = queue.head;
  if (e) {
    queue.head = e->next;
    if (!queue.head)
      queue.tail = NULL;
    queue.count--;
  }
  ur_unlock(&queue.mutex);
  return e;
}
static void show_peers(const char *json) {
  if (!json || !strcmp(json, "null")) {
    puts("peers unavailable (no snapshot)");
    return;
  }
  puts(json); /* Complete SDK JSON includes provide state, principal, roles,
                 names and disconnected count. */
  const char *p = json;
  while ((p = strstr(p, "\"ClientId\""))) {
    p += 10;
    while (*p && (*p == ':' || isspace((unsigned char)*p)))
      p++;
    if (*p != '\"')
      continue;
    p++;
    const char *end = strchr(p, '\"');
    if (!end)
      break;
    if (end - p == 36) {
      char id[37];
      memcpy(id, p, 36);
      id[36] = 0;
      char *color = urnet_get_color_hex(id);
      printf("color %s %s\n", id, color ? color : "unavailable");
      urnet_free_string(color);
    }
    p = end + 1;
  }
}
static int supports(const char *json) {
  if (!json)
    return 0;
  const char *p = json;
  while (isspace((unsigned char)*p))
    p++;
  if (*p++ != '[')
    return 0;
  while (*p) {
    while (isspace((unsigned char)*p) || *p == ',')
      p++;
    char *end;
    long id = strtol(p, &end, 10);
    if (end == p)
      return 0;
    if (id == URMS_SUBPROTOCOL)
      return 1;
    p = end;
  }
  return 0;
}
int ur_messages_main(int argc, char **argv) {
  setvbuf(stdout, NULL, _IOLBF, 0);
  if (argc == 2 && !strcmp(argv[1], "--version")) {
    char *v = urnet_version();
    puts(v ? v : "");
    urnet_free_string(v);
    return 0;
  }
  if (argc < 2 ||
      (strcmp(argv[1], "self") && strcmp(argv[1], "peers") &&
       strcmp(argv[1], "watch") && strcmp(argv[1], "send")) ||
      (!strcmp(argv[1], "send") && argc != 4)) {
    fputs("usage: --self-test | --version | self | peers | watch | send "
          "CLIENT_ID 'TEXT'\n",
          stderr);
    return 1;
  }
  char destination_id[37];
  const char *destination = !strcmp(argv[1], "send") ? argv[2] : NULL;
  if (destination) {
    char *error = NULL;
    char *id = urnet_parse_id(destination, &error);
    int bad = ur_error(error);
    if (id && strlen(id) == 36) {
      memcpy(destination_id, id, 37);
      destination = destination_id;
    } else
      bad = 1;
    if (id)
      urnet_free_string(id);
    if (bad)
      return 1;
  }
  ur_session session;
  if (ur_session_open_mode(&session, 0))
    return 1;
  uint64_t sub = 0, peer_sub = 0;
  int result = 1;
  char *error = NULL;
  sub = urnet_device_local_enable_subprotocol(session.device, URMS_SUBPROTOCOL,
                                              message, NULL, &error);
  if (ur_error(error) || !sub)
    goto done;
  peer_sub = urnet_device_add_network_peers_change_listener(session.device,
                                                            peers, NULL);
  urnet_device_set_provide_mode(session.device, URNET_PROVIDE_MODE_NETWORK);
  char *self = urnet_device_get_client_id(session.device);
  printf("self: %s\n", self ? self : "unavailable");
  urnet_free_string(self);
  if (!strcmp(argv[1], "self")) {
    result = 0;
    goto done;
  }
  char *snapshot = urnet_device_get_network_peers(session.device);
  show_peers(snapshot);
  int available = snapshot && strcmp(snapshot, "null");
  urnet_free_string(snapshot);
  if (!strcmp(argv[1], "peers") && available) {
    result = 0;
    goto done;
  }
  uint64_t pending = 0;
#ifdef _WIN32
  if (BCryptGenRandom(NULL, (PUCHAR)&pending, sizeof(pending),
                      BCRYPT_USE_SYSTEM_PREFERRED_RNG) < 0) {
    fputs("cannot read system random source\n", stderr);
    goto done;
  }
#else
  FILE *random = fopen("/dev/urandom", "rb");
  if (!random) {
    fputs("cannot open system random source\n", stderr);
    goto done;
  }
  size_t random_bytes = fread(&pending, 1, sizeof(pending), random);
  fclose(random);
  if (random_bytes != sizeof(pending)) {
    fputs("cannot read system random source\n", stderr);
    goto done;
  }
#endif
  if (!pending)
    pending = 1;
  int queried = 0;
  int64_t deadline = ur_now_millis() + 30000;
  signal(SIGINT, stop);
  signal(SIGTERM, stop);
  while (!interrupted) {
    if (destination && !queried &&
        urnet_device_local_get_provider_connected(session.device)) {
      queried = 1;
      urnet_device_local_query_subprotocols(session.device, destination, 10000,
                                            query, NULL);
    }
    event *e = next_event();
    if (e) {
      if (e->kind == 2) {
        show_peers(e->json);
        if (!strcmp(argv[1], "peers") && e->json && strcmp(e->json, "null"))
          result = 0;
      } else if (e->kind == 3) {
        if (!e->ok || !supports(e->json)) {
          fputs("peer query failed or peer does not advertise 4096\n", stderr);
          free(e->json);
          free(e);
          goto done;
        }
        uint8_t b[URMS_MAX_FRAME];
        size_t n = urms_encode(b, sizeof(b), 1, pending,
                               (const uint8_t *)argv[3], strlen(argv[3]));
        if (!n ||
            !urnet_device_local_send_subprotocol_bytes(
                session.device, URMS_SUBPROTOCOL, destination, b, (int32_t)n)) {
          fputs("invalid text or SDK did not enqueue message\n", stderr);
          free(e->json);
          free(e);
          goto done;
        }
        printf("sent: %" PRIu64 " waiting for ACK\n", pending);
        deadline = ur_now_millis() + 10000;
      } else if (e->kind == 1) {
        urms_frame f;
        if (!urms_decode(e->bytes, e->length, &f))
          fputs("rejected malformed frame\n", stderr);
        else {
          printf("kind=%d source=%s id=%" PRIu64 " text=", f.kind, e->source,
                 f.id);
          for (size_t i = 0; i < f.length; i++) {
            unsigned c = f.text[i];
            if (c < 32 || c == 127)
              printf("\\x%02x", c);
            else
              putchar((int)c);
          }
          putchar('\n');
          if (f.kind == 1) {
            uint8_t ack[16];
            urms_encode(ack, sizeof(ack), 2, f.id, NULL, 0);
            if (!urnet_device_local_send_subprotocol_bytes(
                    session.device, URMS_SUBPROTOCOL, e->source, ack, 16))
              fputs("ACK was not enqueued\n", stderr);
          } else if (destination && !strcmp(e->source, destination) &&
                     f.id == pending)
            result = 0;
        }
      }
      free(e->json);
      free(e);
      if (!result)
        goto done;
    }
    if (strcmp(argv[1], "watch") && ur_now_millis() > deadline) {
      fputs("connection, peer snapshot, query, or ACK timed out\n", stderr);
      goto done;
    }
  }
  result = 0;
done:
  if (peer_sub) {
    urnet_sub_close(peer_sub);
    urnet_release(peer_sub);
  }
  if (sub) {
    urnet_sub_close(sub);
    urnet_release(sub);
  }
  urnet_device_local_disable_subprotocol(session.device, URMS_SUBPROTOCOL);
  ur_session_close(&session);
  ur_lock(&queue.mutex);
  queue.stopped = 1;
  ur_unlock(&queue.mutex);
  event *remaining;
  while ((remaining = next_event())) {
    free(remaining->json);
    free(remaining);
  }
  return result;
}
