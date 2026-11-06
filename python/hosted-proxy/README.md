# Python hosted proxies with unique exit IPs

[Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](../messages/README.md) · [Provider](../provider/README.md) · [Embed](../embed/README.md) · [Hosted proxies](README.md)

[unique_exit.py](unique_exit.py) is a complete backend program. It:
1. creates N hosted proxies with `proxy_config.unique_exit`;
2. prints each proxy's exit location and `exit_id`;
3. sends one request through each proxy's HTTPS and SOCKS5 URLs to an IP echo, and shows that the exits are distinct;
4. optionally rotates one proxy's exit;
5. removes the proxies again unless `--keep`.

See the [hosted proxy guide](../../HOSTED_PROXIES.md) for what unique exits are, the curl walkthrough, and errors.

**Pro only.** A Pro network holds up to 1,000 active hosted proxies and creates up to 20 a minute. The program paces its creates to that rate, so 1,000 proxies take about 50 minutes.

## Install and run

```sh
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
export URNETWORK_ROOT_JWT='your-network-credential'
python unique_exit.py --count 3
python unique_exit.py --count 3 --country de --rotate
python unique_exit.py --count 10 --replace manual --keep
```

| Option | Meaning |
| --- | --- |
| `--count N` | proxies to create, at most 1,000 |
| `--country CC` | a two-letter country for the exits; the default is best available |
| `--replace auto\|manual` | on exit failure, `auto` (the default) assigns a new unique exit, while `manual` keeps the proxy on its exit until you rotate it |
| `--rotate` | rotate the first proxy to a new unique exit (a proxy may rotate once a minute and 60 times a day) |
| `--echo-url URL` | the IP echo reached through each proxy (default `https://api.ipify.org?format=json`) |
| `--keep` | keep the proxies instead of removing them |

`URNETWORK_ROOT_JWT` is the network credential, so only your backend holds it. `URNETWORK_API_URL` overrides `https://api.bringyour.com`.

The program never prints a credential. It prints each proxy's client id, and its exit's location, `exit_id`, `state` (with `state_reason` while a failed exit waits) and `replace_mode`, plus the exit IP seen through an echo. The API itself never returns exit IPs.

## Refusals

The program reads the server's flags on each refusal:
- **`client_limit_exceeded` about the minute** (the create rate): it waits a minute and tries once more.
- **`client_limit_exceeded` otherwise** (the cap of 1,000): it stops creating.
- **`upgrade_required`** (not on Pro) and **`no_unique_supply`** (no unused exit IP in that location): it stops creating.
- **A rotate refused with `rate_limited` or `no_unique_supply`:** it reports the refusal, and the proxy keeps its exit.

Either way, the proxies it created are still removed at the end.

## How the proxies are reached

- **HTTPS:** `proxy_config_result.https_proxy_url` carries the proxy's token in its host name. Requests sends it in the TLS server name.
- **SOCKS5:** the token is the username, with any password. The program uses `socks5h://<token>:x@<proxy_host>:<socks_proxy_port>`, because Requests (PySocks) sends no credentials without a password.

## Tests

```sh
python -m unittest -v
python unique_exit.py --self-test
```

The tests and the self-test run against a loopback stand-in for the API, so they need no credentials, network or installed packages. They check:
- the `auth-client`, `rotate-exit` and `remove-client` requests;
- result parsing, and the refusal flags `client_limit_exceeded`, `upgrade_required`, `no_unique_supply` and `rate_limited`;
- that a refused rotate still removes the proxies, and that `no_unique_supply` stops creating;
- duplicate-exit detection;
- pacing at the Pro creation rate;
- that no credential is printed.
