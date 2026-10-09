# C++ SDK installation

C++ consumes sdk/cgo directly. Include urnetwork_sdk.hpp (C++17 and nlohmann/json), or the C header for the manual socket ABI.

**Publication status:** new package coordinates below describe the intended public release. They are not yet all published. Build the local package while publication is being prepared.

## Install with the native package manager

```sh
vcpkg add port urnetwork-sdk
```

Conan: `conan install --requires=urnetwork-sdk/<version> --build=missing`. The C/C++ package is shared. Use the first-party registry setup below. Upstream public registry inclusion requires separate maintainer acceptance.

## Supported platforms

Same native platform matrix as C. A C ABI handle is not a POSIX fd or a Winsock SOCKET.

## GitHub and local builds

Clone sdk and build sdk/cgo. Link the library and install the nlohmann/json dependency for the generated C++ convenience classes.

## Examples

- [Integration](integration/README.md): service provisioning, scoped client credentials and Device lifecycle.
- [Sockets](socket/README.md): TCP/UDP, TLS/DTLS and HTTP client adapters.
- [Messages](messages/README.md): live peer discovery and interoperable text/ACK exchange.
- [Provider](provider/README.md): a provider client of your network with the consent disclaimer, status, payout wallet display and background run.
- [Embed](embed/README.md): URnetwork inside your own app, for its own traffic: your backend provisions one client per installation, puts it in its ACL group and sets its data caps; the app runs a local Device and shows its status.

Apps use `URNETWORK_CLIENT_JWT` and a persisted `URNETWORK_INSTANCE_ID`; only the service backend holds `URNETWORK_ROOT_JWT`. See the [shared contract](../INTEGRATION_CONTRACT.md) and [official networking research](../NETWORK_EXAMPLES.md).

Use an SDK release containing the required APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and publication gates.

## References

- [Conan package installation](https://docs.conan.io/2/reference/commands/install.html) — official package-manager documentation, checked September 14, 2026.

## First-party registry setup

Conan needs `conan remote add urnetwork https://<public-conan-remote>` once; then use the install command above. The release operator supplies that public remote URL. For the generated C++ facade, use `-o "urnetwork-sdk/*:cpp=True"` to add nlohmann/json.

For vcpkg, create `vcpkg-configuration.json` in your manifest project with a pinned baseline from the first-party registry:

```json
{"registries":[{"kind":"git","repository":"https://github.com/urnetwork/vcpkg-registry","baseline":"<published-registry-commit>","packages":["urnetwork-sdk"]}]}
```

Then `vcpkg add port urnetwork-sdk` and `vcpkg install`. The first-party repository and remote need their initial release before those commands can resolve the package. Local archives already provide `find_package(urnetwork-sdk CONFIG REQUIRED)` and the `urnetwork::sdk` target.
