# Python SDK installation

Python uses ctypes over sdk/cgo. Platform wheels contain the matching native library and do not require Go, a compiler, or a custom library search path.

**Publication status:** new package coordinates below describe the intended public release. They are not yet all published. Build the local package while publication is being prepared.

## Install with the native package manager

```sh
pip install urnetwork-sdk
```

Equivalent project installs: `uv add urnetwork-sdk` and `poetry add urnetwork-sdk`. Python code imports `urnetwork`. Pin with `pip install urnetwork-sdk==<version>`.

For preview releases, use `pip install --pre urnetwork-sdk` or pin the exact preview version. The unqualified command targets stable releases.

## Supported platforms

Python 3.10+, on the OS/architecture of an available wheel. Linux tags state the glibc floor; current cross-builds target glibc 2.35. Do not assume Alpine/musl compatibility.

## GitHub and local builds

For source development: `pip install 'urnetwork-sdk @ git+https://github.com/urnetwork/sdk.git@<commit>#subdirectory=python'` requires Go/C tools and resolvable Go dependencies. Alternatively clone the workspace, run `make -C sdk/python`, and pip-install its .whl. Standalone source archives fail explicitly without a staged runtime.

## Examples

- [Integration](integration/README.md): service provisioning, scoped client credentials and Device lifecycle.
- [Sockets](socket/README.md): TCP/UDP, TLS/DTLS and HTTP client adapters.
- [Messages](messages/README.md): live peer discovery and interoperable text/ACK exchange.
- [Provider](provider/README.md): a provider client of your network with the consent disclaimer, status, payout wallet display and background run.
- [Embed](embed/README.md): URnetwork inside your own app, for its own traffic: your backend provisions one client per installation, puts it in its ACL group and sets its data caps; the app runs a local Device and shows its status.

Apps use `URNETWORK_CLIENT_JWT` and a persisted `URNETWORK_INSTANCE_ID`; only the service backend holds `URNETWORK_ROOT_JWT`. See the [shared contract](../INTEGRATION_CONTRACT.md) and [official networking research](../NETWORK_EXAMPLES.md).

Use an SDK release containing the required APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and publication gates.

## References

- [pip VCS installation](https://pip.pypa.io/en/stable/topics/vcs-support/) — official package-manager documentation, checked September 14, 2026.
