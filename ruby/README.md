# Ruby SDK installation

Ruby uses FFI over sdk/cgo. Platform gems contain the SDK runtime and depend on the ffi gem. Use blocks or explicit close for handle ownership.

**Publication status:** new package coordinates below describe the intended public release. They are not yet all published. Build the local package while publication is being prepared.

## Install with the native package manager

```sh
gem install urnetwork-sdk
```

Bundler: `gem "urnetwork-sdk"` in the Gemfile, then `bundle install`. Pin with `gem install urnetwork-sdk -v <version>`; nightlies require `--pre`.

## Supported platforms

The wrapper supports Ruby 2.6+ syntax; supported engines and native platforms require release qualification. Match the gem's CPU/OS and the documented Linux libc floor.

## GitHub and local builds

Bundler supports `gem "urnetwork-sdk", git: "https://github.com/urnetwork/sdk", ref: "<commit>", glob: "ruby/*.gemspec"` after preparing the native runtime in the checkout. Bare gem install does not accept Git dependencies. `make -C sdk/ruby` builds installable platform gems.

## Use the SDK

Start with [socket/README.md](socket/README.md) for native socket replacements, TLS/DTLS, Happy Eyeballs, and HTTP client integration. The executable examples include Device setup; see the [SDK authentication and connection setup](https://ur.io/docs/getting-started-sdk). A native handle must come from the same runtime in the same process.

Socket APIs shown here are part of the new socket release. Installing an older SDK successfully does not add those APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and first-publication gates.

## References

- [Bundler Git dependencies](https://bundler.io/guides/git.html) — official package-manager documentation, checked September 14, 2026.
