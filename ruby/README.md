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

## Examples

- [Integration](integration/README.md): service provisioning, scoped client credentials and Device lifecycle.
- [Sockets](socket/README.md): TCP/UDP, TLS/DTLS and HTTP client adapters.
- [Messages](messages/README.md): live peer discovery and interoperable text/ACK exchange.
- [Provider](provider/README.md): a provider client of your network with the consent disclaimer, status, payout wallet display and background run.
- [Embed](embed/README.md): URnetwork inside your own app, for its own traffic: your backend provisions one client per installation, puts it in its ACL group and sets its data caps; the app runs a local Device and shows its status.

Apps use `URNETWORK_CLIENT_JWT` and a persisted `URNETWORK_INSTANCE_ID`; only the service backend holds `URNETWORK_ROOT_JWT`. See the [shared contract](../INTEGRATION_CONTRACT.md) and [official networking research](../NETWORK_EXAMPLES.md).

Use an SDK release containing the required APIs. The [package plan](https://github.com/urnetwork/sdk/blob/main/PACKAGEMANAGERS.md) records package names, build outputs and publication gates.

## References

- [Bundler Git dependencies](https://bundler.io/guides/git.html) — official package-manager documentation, checked September 14, 2026.
