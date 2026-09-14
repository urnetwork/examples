# C SDK installation

C consumes sdk/cgo directly. Link the matching URnetworkSdk library and include urnetwork_sdk.h.

**Publication status:** new package coordinates below describe the intended public release. They are not yet all published. Build the local package while publication is being prepared.

## Install with the native package manager

```sh
vcpkg add port urnetwork-sdk
```

Conan: `conan install --requires=urnetwork-sdk/<version> --build=missing`. The implemented recipes publish to a first-party Conan remote and vcpkg Git registry. Configure that remote/registry once; upstream Conan Center and vcpkg inclusion require maintainer acceptance.

## Supported platforms

Release packaging supports Windows, Linux/glibc and macOS x64/arm64 when their runtimes are present in the manifest. Check the release's native manifest for the exact OS and libc floor.

## GitHub and local builds

A Git checkout is source, not an installed C library. Clone sdk and run `make -C sdk/cgo package check-package` to build a host runtime, CMake archive and recipes. Use `SDK_NATIVE_MANIFEST` to package a prebuilt matrix.

## Use the SDK

Start with [socket/README.md](socket/README.md) for native socket replacements, TLS/DTLS, Happy Eyeballs, and HTTP client integration. The executable examples include Device setup; see the [SDK authentication and connection setup](https://ur.io/docs/getting-started-sdk). A native handle must come from the same runtime in the same process.

Socket APIs shown here are part of the new socket release. Installing an older SDK successfully does not add those APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and first-publication gates.

## References

- [vcpkg registries](https://learn.microsoft.com/en-us/vcpkg/consume/git-registries) — official package-manager documentation, checked September 14, 2026.

## First-party registry setup

Conan needs `conan remote add urnetwork https://<public-conan-remote>` once; then use the install command above. The release operator supplies that public remote URL. For the generated C++ facade, use `-o "urnetwork-sdk/*:cpp=True"` to add nlohmann/json.

For vcpkg, create `vcpkg-configuration.json` in your manifest project with a pinned baseline from the first-party registry:

```json
{"registries":[{"kind":"git","repository":"https://github.com/urnetwork/vcpkg-registry","baseline":"<published-registry-commit>","packages":["urnetwork-sdk"]}]}
```

Then `vcpkg add port urnetwork-sdk` and `vcpkg install`. The first-party repository and remote need their initial release before those commands can resolve the package. Local archives already provide `find_package(urnetwork-sdk CONFIG REQUIRED)` and the `urnetwork::sdk` target.
