# Hosted proxies with unique exit IPs

A hosted proxy is a URnetwork device that runs on URnetwork's proxy hosts. Your programs reach it through ordinary proxy URLs: HTTPS on every plan, and SOCKS5 and WireGuard on Pro. You create one with `proxy_config` on `POST /network/auth-client`.

By default a hosted proxy spreads its traffic over several providers at once, and separately created proxies draw from the same provider pool. So two proxies can exit from the same IP address, and one proxy's exit can change between requests.

With `"unique_exit": true`, each proxy exits through **one** provider whose egress IP no other active unique-exit proxy of your network uses. It keeps that exit until the provider fails or you rotate it. N unique-exit proxies give you N distinct exit IPs at the same time.

Runnable programs that do everything below:
- [Python](python/hosted-proxy/README.md)
- [TypeScript](typescript/hosted-proxy/README.md)

## Plan and limits

- **Pro only.** On a free network the create is refused with a message, and the proxy is not created.
- **Caps:** a Pro network holds up to **1,000** active hosted proxies and creates up to **20 a minute**. Unique-exit proxies count toward the same cap and rate as any hosted proxy. A refused create uses neither.
- **Rotation** is rate-limited per proxy.
- **Supply:** if no healthy provider with an unused egress IP exists in the location you ask for, the create is refused with `no_unique_supply`. Try a wider location, or best available.

## Walkthrough with curl

Hosted proxies are created with the network credential, which only your backend holds. The examples use `jq` to read the JSON.

```sh
export URNETWORK_ROOT_JWT='your-network-credential'
API=https://api.bringyour.com
AUTH=(-H "Authorization: Bearer $URNETWORK_ROOT_JWT" -H 'Content-Type: application/json')
```

**1. Create a unique-exit proxy.** `best_available` draws the exit from any location; use `"country_code": "de"` in `initial_device_state` instead to pick a country.

```sh
curl -sS "$API/network/auth-client" "${AUTH[@]}" -d '{
  "description": "unique exit 1",
  "device_spec": "curl",
  "proxy_config": {
    "unique_exit": true,
    "initial_device_state": {"location": {"connect_location_id": {"best_available": true}}}
  }
}' > proxy1.json
jq '{client_id, exit: .proxy_config_result.exit}' proxy1.json
```

The result's `proxy_config_result` carries the proxy URLs and its `exit`:

```json
{
  "client_id": "019a…",
  "exit": {
    "exit_id": "…",
    "country_code": "de",
    "region": "Berlin",
    "city": "Berlin",
    "exit_since": "2026-11-05T19:00:00Z",
    "state": "assigned"
  }
}
```

- `exit_id` is an opaque id. It stays the same while the proxy keeps one exit.
- `state` is `assigned`, `replacing` while a failed exit is being replaced, or `no_supply`.
- The API never returns the exit IP. You see it through a request over the proxy, as in the next step.

**2. Send a request through the proxy.** The HTTPS proxy URL already carries the proxy's token in its host name:

```sh
curl -sS -x "$(jq -r .proxy_config_result.https_proxy_url proxy1.json)" https://api.ipify.org; echo
```

On SOCKS5 the token is the username, and any password works. Use a placeholder such as `x`, because some clients send no credentials at all without one:

```sh
TOKEN=$(jq -r .proxy_config_result.auth_token proxy1.json)
HOST=$(jq -r .proxy_config_result.proxy_host proxy1.json)
PORT=$(jq -r .proxy_config_result.socks_proxy_port proxy1.json)
curl -sS -x "socks5h://$TOKEN:x@$HOST:$PORT" https://api.ipify.org; echo
```

**3. Create more and compare.** Each unique-exit proxy reports a different IP:

```sh
for i in 2 3; do
  curl -sS "$API/network/auth-client" "${AUTH[@]}" -d "{\"description\": \"unique exit $i\", \"device_spec\": \"curl\",
    \"proxy_config\": {\"unique_exit\": true, \"initial_device_state\": {\"location\": {\"connect_location_id\": {\"best_available\": true}}}}}" \
    > "proxy$i.json"
done
for i in 1 2 3; do curl -sS -x "$(jq -r .proxy_config_result.https_proxy_url "proxy$i.json")" https://api.ipify.org; echo; done
```

To create many, stay under 20 a minute: 1,000 proxies take about 50 minutes.

**4. List your proxies and their exits.**

```sh
curl -sS "$API/network/proxies" -H "Authorization: Bearer $URNETWORK_ROOT_JWT" \
  | jq '.clients[] | {client_id, exit: .proxy_client.exit}'
```

**5. Rotate a proxy to a new unique exit.**

```sh
curl -sS "$API/network/proxy/rotate-exit" "${AUTH[@]}" \
  -d "{\"client_id\": \"$(jq -r .client_id proxy1.json)\"}" | jq .exit
```

**6. Remove proxies you no longer need**, so they stop counting toward the cap:

```sh
for i in 1 2 3; do
  curl -sS "$API/network/remove-client" "${AUTH[@]}" -d "{\"client_id\": \"$(jq -r .client_id "proxy$i.json")\"}"
done
```

## When an exit fails

- **`auto` (the default):** when a proxy's provider goes offline or fails its checks, the server assigns a new unique exit after a short grace. Meanwhile `state` is `replacing`, and afterwards `exit_id` and `exit_since` change.
- **`manual`:** for session-sticky work, add `"unique_exit_replace": "manual"` to `proxy_config`. The proxy then stays on its exit until you rotate it.

## Errors

A refusal is an `error` object with a `message`; the HTTP status is still 200.

- **`client_limit_exceeded: true`:** the cap of 1,000 active proxies or the rate of 20 a minute. For the rate, wait a minute and try again.
- **`no_unique_supply`:** no unused egress IP in that location right now.
- **Pro only:** `unique_exit` on a free network.
