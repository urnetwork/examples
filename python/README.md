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

## Use the SDK

Start with [socket/README.md](socket/README.md) for native socket replacements, TLS/DTLS, Happy Eyeballs, and HTTP client integration. The executable examples include Device setup; see the [SDK authentication and connection setup](https://ur.io/docs/getting-started-sdk). A native handle must come from the same runtime in the same process.

Socket APIs shown here are part of the new socket release. Installing an older SDK successfully does not add those APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and first-publication gates.

## References

- [pip VCS installation](https://pip.pypa.io/en/stable/topics/vcs-support/) — official package-manager documentation, checked September 14, 2026.
