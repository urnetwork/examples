/* The provider status that every provider example shows, with the exact text
 * rules of PROVIDER_CONTRACT.md ("Status"): providing state, clients served,
 * data provided and the payout wallet, read only. The functions are pure and
 * read the sdk's json values (json.c), so the self-test checks them without
 * the sdk, a network or credentials. */
#include "provider.h"
#include <inttypes.h>
#include <stdio.h>

/* Shown once at start, and in every example's README. The app that integrates
 * a provider owns the consent screen; this example starts without asking. One
 * literal per line of the text. */
const char ur_provider_consent_disclaimer[] =
    "Consent disclaimer: an app that integrates a URnetwork provider must collect the user's consent before it provides.\n"
    "Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address.\n"
    "This example starts providing without asking, because the consent screen belongs to your app.";

/* the nil id, which marks an absent peer in a contract path */
static const char zero_id[] = "00000000-0000-0000-0000-000000000000";

/* Prints one line on stdout at once, also when stdout is a file or a pipe
 * (launchd and the Windows task keep the console output in files). */
void ur_provider_print_line(const char *line) {
  fputs(line, stdout);
  fputc('\n', stdout);
  fflush(stdout);
}

/* Binary units with one decimal: "0 B", "1023 B", "1.0 KiB", "12.4 MiB". A
 * value that rounds to 1024.0 moves to the next unit. The arithmetic is exact
 * integer arithmetic, so the text does not depend on the C library's float
 * formatting; a tie rounds to even, as Go's %.1f does. */
void ur_provider_format_byte_count(int64_t byte_count, char *text,
                                   size_t capacity) {
  static const char *const units[] = {"KiB", "MiB", "GiB",
                                      "TiB", "PiB", "EiB"};
  if (byte_count < 1024) {
    snprintf(text, capacity, "%" PRId64 " B", byte_count);
    return;
  }
  uint64_t count = (uint64_t)byte_count;
  uint64_t divisor = 1024;
  size_t unit_index = 0;
  while (unit_index + 1 < sizeof(units) / sizeof(*units)) {
    /* the value rounds to 1024.0 or more from 1023.95 up */
    uint64_t whole = count / divisor;
    uint64_t remainder = count % divisor;
    if (whole < 1023 || (whole == 1023 && 20 * remainder < 19 * divisor))
      break;
    divisor *= 1024;
    unit_index += 1;
  }
  uint64_t scaled = count % divisor * 10;
  uint64_t tenths = count / divisor * 10 + scaled / divisor;
  uint64_t remainder = scaled % divisor;
  if (divisor < 2 * remainder || (divisor == 2 * remainder && tenths % 2 == 1))
    tenths += 1;
  snprintf(text, capacity, "%" PRIu64 ".%" PRIu64 " %s", tenths / 10,
           tenths % 10, units[unit_index]);
}

/* The status field text: the state, and for the client limit state the time
 * the sdk retries, for example "client limit, retry at 19:05 UTC". The retry
 * time (unix milliseconds) is rounded up to the next whole minute in UTC, so
 * the shown time is never before the real retry; a retry time of 0 shows
 * "client limit". Unix time has no leap seconds, so every day is 1440
 * minutes. */
void ur_provider_status_text(const char *state,
                             int64_t client_limit_retry_time, char *text,
                             size_t capacity) {
  if (strcmp(state, UR_PROVIDER_STATE_CLIENT_LIMIT) ||
      client_limit_retry_time <= 0) {
    snprintf(text, capacity, "%s", state);
    return;
  }
  int64_t minute_millis = 60 * 1000;
  int64_t retry_minute = client_limit_retry_time / minute_millis +
                         (client_limit_retry_time % minute_millis != 0);
  int minute_of_day = (int)(retry_minute % (24 * 60));
  snprintf(text, capacity, "%s, retry at %02d:%02d UTC",
           UR_PROVIDER_STATE_CLIENT_LIMIT, minute_of_day / 60,
           minute_of_day % 60);
}

/* The status line, for example "status: providing | clients served: 3 | data
 * provided: 12.4 MiB | payout wallet: 5Grw... (network)". */
void ur_provider_status_line(const ur_provider_status *status, char *line,
                             size_t capacity) {
  char status_text[64];
  char data_provided[32];
  ur_provider_status_text(status->state, status->client_limit_retry_time,
                          status_text, sizeof(status_text));
  ur_provider_format_byte_count(status->data_provided_byte_count,
                                data_provided, sizeof(data_provided));
  snprintf(line, capacity,
           "status: %s | clients served: %" PRIu64
           "%s | data provided: %s | payout wallet: %s%s%s%s",
           status_text, (uint64_t)status->clients_served,
           status->clients_served_at_limit ? "+" : "", data_provided,
           status->payout_wallet, status->payout_wallet_scope ? " (" : "",
           status->payout_wallet_scope ? status->payout_wallet_scope : "",
           status->payout_wallet_scope ? ")" : "");
}

/* The fields that change rarely. A change prints a status line at once; the
 * data counter alone only prints on the periodic line. The status text carries
 * the client limit retry time, so a new retry time prints too. */
void ur_provider_status_key(const ur_provider_status *status, char *key,
                            size_t capacity) {
  char status_text[64];
  ur_provider_status_text(status->state, status->client_limit_retry_time,
                          status_text, sizeof(status_text));
  snprintf(key, capacity, "%s|%" PRIu64 "|%d|%s|%s", status_text,
           (uint64_t)status->clients_served, status->clients_served_at_limit,
           status->payout_wallet,
           status->payout_wallet_scope ? status->payout_wallet_scope : "");
}

/* The providing state from the device getters, in this order: stopped unless
 * the provide mode is public; client limit while the sdk holds this client off
 * for its network's client limit; paused while paused; providing once the
 * provider is enabled and its platform carrier is connected; starting
 * otherwise. */
const char *ur_provider_state(int64_t provide_mode,
                              const char *client_limit_status,
                              bool provide_paused, bool provide_enabled,
                              bool provider_connected) {
  if (provide_mode != URNET_PROVIDE_MODE_PUBLIC)
    return UR_PROVIDER_STATE_STOPPED;
  if (!strcmp(client_limit_status, URNET_CLIENT_LIMIT_STATUS_EXCEEDED))
    return UR_PROVIDER_STATE_CLIENT_LIMIT;
  if (provide_paused)
    return UR_PROVIDER_STATE_PAUSED;
  if (provide_enabled && provider_connected)
    return UR_PROVIDER_STATE_PROVIDING;
  return UR_PROVIDER_STATE_STARTING;
}

/* Which owner the effective payout wallet belongs to. The consent scope comes
 * first: a hotkey delegation is network-level, with no client id, but is not
 * the network's wallet. Otherwise by the wallet's client id: this provider's
 * own mapping, the network's wallet, or another provider of the network. */
const char *ur_provider_payout_wallet_scope(const char *wallet_consent_scope,
                                            const char *wallet_client_id,
                                            const char *client_id) {
  if (!strcmp(wallet_consent_scope, UR_PROVIDER_SN_WALLET_CONSENT_SCOPE_HOTKEY))
    return UR_PROVIDER_PAYOUT_WALLET_SCOPE_HOTKEY;
  if (!*wallet_client_id)
    return UR_PROVIDER_PAYOUT_WALLET_SCOPE_NETWORK;
  if (!strcmp(wallet_client_id, client_id))
    return UR_PROVIDER_PAYOUT_WALLET_SCOPE_PROVIDER;
  return UR_PROVIDER_PAYOUT_WALLET_SCOPE_ANOTHER_PROVIDER;
}

/* Bytes relayed for clients, in both directions, since the device started:
 * RemoteEgressByteCount + RemoteIngressByteCount of the provider packet stats
 * json. 0 when the stats are null; a missing counter counts as 0. */
int64_t ur_provider_data_provided_byte_count(const char *packet_stats_json) {
  if (!packet_stats_json)
    return 0;
  ur_json_value stats =
      ur_json_parse(packet_stats_json, strlen(packet_stats_json));
  int64_t egress_byte_count = 0;
  int64_t ingress_byte_count = 0;
  ur_json_int64(ur_json_member(stats, "RemoteEgressByteCount"),
                &egress_byte_count);
  ur_json_int64(ur_json_member(stats, "RemoteIngressByteCount"),
                &ingress_byte_count);
  return egress_byte_count + ingress_byte_count;
}

/* Reads the client limit status json, {"Status": ..., "RetryTime": ...}.
 * NULL, which the sdk returns only when the call cannot run, reads as no
 * limit. */
void ur_provider_read_client_limit_status(
    const char *client_limit_status_json,
    ur_provider_client_limit_status *client_limit_status) {
  client_limit_status->status[0] = 0;
  client_limit_status->retry_time = 0;
  if (!client_limit_status_json)
    return;
  ur_json_value value = ur_json_parse(client_limit_status_json,
                                      strlen(client_limit_status_json));
  char *status = ur_json_string(ur_json_member(value, "Status"));
  if (status) {
    snprintf(client_limit_status->status, sizeof(client_limit_status->status),
             "%s", status);
    free(status);
  }
  ur_json_int64(ur_json_member(value, "RetryTime"),
                &client_limit_status->retry_time);
}

/* A contract path id that names a peer: a uuid other than the nil id, written
 * to id in canonical form. */
static bool present_id(ur_json_value value, char id[UR_PROVIDER_ID_CAPACITY]) {
  char *text = ur_json_string(value);
  if (!text)
    return false;
  bool present =
      ur_provider_parse_uuid(text, strlen(text), id) && strcmp(id, zero_id);
  free(text);
  return present;
}

/* The peer of one provider contract, from its ContractDetails json, by
 * direction as the sdk's contract screens resolve it: the source of a receive
 * (ingress) contract, the destination of a send (egress) contract. A path
 * without that client id is keyed by its stream id, then by the contract id.
 * False when the contract names no peer. */
bool ur_provider_contract_peer_key(const char *contract_details_json,
                                   bool receive, char *peer_key,
                                   size_t capacity) {
  if (!contract_details_json)
    return false;
  ur_json_value details =
      ur_json_parse(contract_details_json, strlen(contract_details_json));
  ur_json_value path = ur_json_member(details, "ContractTransferPath");
  char id[UR_PROVIDER_ID_CAPACITY];
  if (path.type == UR_JSON_OBJECT) {
    if (present_id(ur_json_member(path, receive ? "SourceId" : "DestinationId"),
                   id)) {
      snprintf(peer_key, capacity, "%s", id);
      return true;
    }
    if (present_id(ur_json_member(path, "StreamId"), id)) {
      snprintf(peer_key, capacity, "stream:%s", id);
      return true;
    }
  }
  if (present_id(ur_json_member(details, "ContractId"), id)) {
    snprintf(peer_key, capacity, "contract:%s", id);
    return true;
  }
  return false;
}

/* The payout wallet to show, from the sdk's cached SnWallet json: the coldkey
 * address (owned), with its scope label for this client in scope. NULL when no
 * wallet is mapped. */
char *ur_provider_payout_wallet(const char *sn_wallet_json,
                                const char *client_id, const char **scope) {
  *scope = NULL;
  if (!sn_wallet_json)
    return NULL;
  ur_json_value wallet = ur_json_parse(sn_wallet_json, strlen(sn_wallet_json));
  char *coldkey = ur_json_string(ur_json_member(wallet, "coldkey_ss58"));
  if (!coldkey || !*coldkey) {
    free(coldkey);
    return NULL;
  }
  char *wallet_client_id = ur_json_string(ur_json_member(wallet, "client_id"));
  char *consent_scope = ur_json_string(ur_json_member(wallet, "consent_scope"));
  *scope = ur_provider_payout_wallet_scope(
      consent_scope ? consent_scope : "",
      wallet_client_id ? wallet_client_id : "", client_id);
  free(wallet_client_id);
  free(consent_scope);
  return coldkey;
}

/* Whether a wallet read (GET /sn/wallet) succeeded: no error text, a result,
 * and no error in the result. */
bool ur_provider_wallet_read_succeeded(const char *result_json,
                                       const char *error) {
  if (error || !result_json)
    return false;
  ur_json_value result = ur_json_parse(result_json, strlen(result_json));
  if (result.type != UR_JSON_OBJECT)
    return false;
  ur_json_type error_type = ur_json_member(result, "error").type;
  return error_type == UR_JSON_NONE || error_type == UR_JSON_NULL;
}

/* An empty count that keeps at most limit distinct peers. */
void ur_provider_clients_served_init(ur_provider_clients_served *served,
                                     size_t limit) {
  memset(served, 0, sizeof(*served));
  served->limit = limit;
  ur_mutex_init(&served->state_lock);
}

/* The fnv-1a hash of a peer key. */
static uint64_t peer_key_hash(const char *peer_key) {
  uint64_t hash = 14695981039346656037u;
  for (const unsigned char *p = (const unsigned char *)peer_key; *p; p++)
    hash = (hash ^ *p) * 1099511628211u;
  return hash;
}

/* The slot of peer_key in the table: where it is, or the empty slot where it
 * belongs. */
static size_t peer_key_slot(char **peer_keys, size_t capacity,
                            const char *peer_key) {
  size_t slot = (size_t)(peer_key_hash(peer_key) & (capacity - 1));
  while (peer_keys[slot] && strcmp(peer_keys[slot], peer_key))
    slot = (slot + 1) & (capacity - 1);
  return slot;
}

/* Counts one peer key; the caller holds state_lock. The table doubles before
 * it is half full, so a probe always ends at an empty slot. */
static void add_peer_key_with_lock(ur_provider_clients_served *served,
                                   const char *peer_key) {
  if (served->capacity &&
      served->peer_keys[peer_key_slot(served->peer_keys, served->capacity,
                                      peer_key)])
    return;
  if (served->limit <= served->count) {
    served->at_limit = true;
    return;
  }
  if (served->capacity < 2 * (served->count + 1)) {
    size_t capacity = served->capacity ? 2 * served->capacity : 64;
    char **peer_keys = calloc(capacity, sizeof(*peer_keys));
    if (!peer_keys)
      return;
    for (size_t i = 0; i < served->capacity; i++) {
      if (served->peer_keys[i])
        peer_keys[peer_key_slot(peer_keys, capacity, served->peer_keys[i])] =
            served->peer_keys[i];
    }
    free(served->peer_keys);
    served->peer_keys = peer_keys;
    served->capacity = capacity;
  }
  char *copy = ur_provider_copy_string(peer_key);
  if (!copy)
    return;
  served->peer_keys[peer_key_slot(served->peer_keys, served->capacity,
                                  peer_key)] = copy;
  served->count += 1;
}

/* Counts the peer of one provider contract (ContractDetails json). */
void ur_provider_clients_served_add(ur_provider_clients_served *served,
                                    const char *contract_details_json,
                                    bool receive) {
  char peer_key[64];
  if (!ur_provider_contract_peer_key(contract_details_json, receive, peer_key,
                                     sizeof(peer_key)))
    return;
  ur_mutex_lock(&served->state_lock);
  add_peer_key_with_lock(served, peer_key);
  ur_mutex_unlock(&served->state_lock);
}

/* The distinct count, and whether the count stopped at the limit. */
void ur_provider_clients_served_count(ur_provider_clients_served *served,
                                      size_t *count, bool *at_limit) {
  ur_mutex_lock(&served->state_lock);
  *count = served->count;
  *at_limit = served->at_limit;
  ur_mutex_unlock(&served->state_lock);
}

/* Frees the count. No listener may still add to it. */
void ur_provider_clients_served_free(ur_provider_clients_served *served) {
  for (size_t i = 0; i < served->capacity; i++)
    free(served->peer_keys[i]);
  free(served->peer_keys);
  ur_mutex_destroy(&served->state_lock);
  served->peer_keys = NULL;
  served->capacity = 0;
  served->count = 0;
}
