"""A running provider over the SDK's C ABI, through the urnetwork package
(ctypes): the network space manager, the provider device with the
installation's identity, and the listeners that feed its status.

SDK callbacks run on SDK threads. They copy what they carry, then either count
it under the clients-served lock or hand it to the run loop through the events
queue; the run loop's thread owns every other field and makes every other SDK
call. The ctypes callback objects stay referenced for the life of the process,
because the SDK can still answer a wallet read after the device closes.

The urnetwork package is passed in rather than imported, so that the self-test
never loads the native runtime and the tests can run the lifecycle against a
stand-in for the C ABI."""

from contextlib import ExitStack
import ctypes as C
import os
import queue
import sys
import time

from state import (
    CLIENT_JWT_FILE_NAME,
    IDENTITY_FILE_NAME,
    ConfigError,
    ProviderIdentity,
    provider_key_material,
    save_provider_identity,
    write_private_file,
)
from status import (
    CLIENTS_SERVED_LIMIT,
    PAYOUT_WALLET_CHECKING,
    PAYOUT_WALLET_NOT_SET,
    PAYOUT_WALLET_UNAVAILABLE,
    PROVIDE_MODE_NONE,
    PROVIDE_MODE_PUBLIC,
    ClientsServed,
    ProviderStatus,
    json_text,
    parse_client_limit_status,
    parse_data_provided_byte_count,
    parse_json_object,
    payout_wallet_scope,
    provider_state,
)

# How often the status is read, and the longest gap between status lines.
STATUS_POLL_INTERVAL_SECONDS = 1
STATUS_REPEAT_INTERVAL_SECONDS = 60

# How often the payout wallet is read again. The wallet is fixed; a reread shows
# a mapping that the backend completes while the provider runs.
WALLET_SYNC_INTERVAL_SECONDS = 10 * 60

# The device description and spec recorded for this installation's device.
DEVICE_DESCRIPTION = "Python provider example"
DEVICE_SPEC = "urnetwork-examples/python-provider"
APP_VERSION = "1"

# The provider extender role's two device settings (PROVIDER_CONTRACT.md,
# "Extender role"), passed explicitly and both on. PROVIDE_EXTENDER_ENABLED is
# the embedder's hard switch: False means the role never runs.
# DEFAULT_PROVIDE_EXTENDER is the setting the device uses until the user sets
# one: False turns the default off.
PROVIDE_EXTENDER_ENABLED = True
DEFAULT_PROVIDE_EXTENDER = True

# the ur.network main network space, as the integration helpers create it
NETWORK_SPACE_KEY_JSON = b'{"host_name":"ur.network","env_name":"main"}'
NETWORK_SPACE_VALUES_JSON = b'{"migration_host_name":"bringyour.com"}'

# run loop events, each a tuple that starts with one of these
EVENT_STOP = "stop"
EVENT_JWT_REFRESHED = "jwt refreshed"
EVENT_AUTH_LOGOUT = "auth logout"
EVENT_WALLET_SYNCED = "wallet synced"

# ctypes callback objects that the SDK may still call; one set per session
_callback_roots = []


def take_string(raw, pointer) -> str | None:
    """The text of a string that the C ABI returned, freed after the copy; None
    for NULL."""
    if not pointer:
        return None
    try:
        return C.string_at(pointer).decode("utf-8", errors="replace")
    finally:
        raw.urnet_free_string(pointer)


def configure_sdk_logs(sdk, log_dir: str):
    """Keeps the SDK's log files in log_dir, which the SDK bounds (16 MiB files, the
    newest four kept at each start), instead of the system temp directory. The
    SDK also copies its log lines to stderr, and the C ABI keeps that copy.
    Raises OSError."""
    os.makedirs(log_dir, mode=0o700, exist_ok=True)
    error = C.c_void_p()
    if not sdk.raw.urnet_set_log_dir(log_dir.encode("utf-8"), C.byref(error)):
        raise OSError(take_string(sdk.raw, error.value) or "the sdk refused the log directory")


def new_key_material(raw, key_material) -> int:
    """A native key material handle for the device constructor, which the caller
    releases after the call; 0 without key material (the first run)."""
    if key_material is None:
        return 0

    def buffer(data: bytes):
        if not data:
            return None, 0
        return (C.c_uint8 * len(data)).from_buffer_copy(data), len(data)

    seed, seed_length = buffer(key_material.client_key_seed)
    certificate, certificate_length = buffer(key_material.provide_tls_certificate_pem)
    private_key, private_key_length = buffer(key_material.provide_tls_private_key_pem)
    handle = raw.urnet_new_device_local_key_material(
        seed,
        seed_length,
        certificate,
        certificate_length,
        private_key,
        private_key_length,
    )
    if not handle:
        raise RuntimeError("the sdk did not create the key material")
    if key_material.extender_key_seed:
        extender_seed, extender_seed_length = buffer(key_material.extender_key_seed)
        raw.urnet_device_local_key_material_set_extender_key_seed(handle, extender_seed, extender_seed_length)
    return handle


def read_device_bytes(getter, device: int) -> bytes:
    """Bytes from a buffer-out getter of the C ABI: the first call asks for the
    size, the second copies. b"" when the device has none."""
    size = C.c_int32(0)
    getter(device, None, C.byref(size))
    if size.value <= 0:
        return b""
    buffer = (C.c_uint8 * size.value)()
    if not getter(device, buffer, C.byref(size)):
        return b""
    return bytes(buffer[: size.value])


class ProviderSession:
    """One run of the provider. The constructor starts providing; run shows the
    status until a stop request; close stops providing."""

    def __init__(self, config, sdk, events: queue.SimpleQueue):
        """Creates the provider device with the installation's identity and the
        extender role's settings on, saves a new identity on first run, and starts
        providing publicly. The SDK declares provide intent on the device's
        platform connections by itself while the provide mode is public. sdk is
        the urnetwork package; events is the run loop's queue, which also
        carries stop requests."""
        self._config = config
        self._raw = sdk.raw
        self._events = events
        self._clients_served = ClientsServed(CLIENTS_SERVED_LIMIT)
        # PAYOUT_WALLET_CHECKING, PAYOUT_WALLET_UNAVAILABLE, PAYOUT_WALLET_NOT_SET
        # or the mapped coldkey
        self._payout_wallet = PAYOUT_WALLET_CHECKING
        self._payout_wallet_scope = ""
        self._subs = []
        self._closed = False

        callback_types = sdk._raw
        self._jwt_refresh_callback = callback_types.urnet_jwt_refresh_cb(self._jwt_refreshed)
        self._auth_logout_callback = callback_types.urnet_auth_logout_cb(self._auth_logout)
        self._ingress_callback = callback_types.urnet_contract_details_change_cb(self._ingress_contract_details_changed)
        self._egress_callback = callback_types.urnet_contract_details_change_cb(self._egress_contract_details_changed)
        self._wallet_callback = callback_types.urnet_sn_get_wallet_cb(self._wallet_synced)
        _callback_roots.extend(
            (
                self._jwt_refresh_callback,
                self._auth_logout_callback,
                self._ingress_callback,
                self._egress_callback,
                self._wallet_callback,
            )
        )

        raw = self._raw
        # on an error the stack closes what exists, in reverse order
        with ExitStack() as stack:
            manager = stack.enter_context(sdk.Handle(raw.urnet_new_network_space_manager_no_storage()))
            stack.callback(raw.urnet_network_space_manager_close, manager.handle)
            space = stack.enter_context(
                sdk.Handle(
                    raw.urnet_network_space_manager_update_network_space_values(
                        manager.handle,
                        NETWORK_SPACE_KEY_JSON,
                        NETWORK_SPACE_VALUES_JSON,
                    )
                )
            )
            api = stack.enter_context(sdk.Handle(raw.urnet_network_space_get_api(space.handle)))
            raw.urnet_api_set_by_jwt(api.handle, config.client_jwt.encode())
            self._device = stack.enter_context(sdk.Device(self._new_device(space.handle)))
            if config.identity is None:
                # keep the new identity, so later starts present the same provider
                self._save_new_identity()
            device = self._device.handle
            self._subs = [
                raw.urnet_device_add_jwt_refresh_listener(device, self._jwt_refresh_callback, None),
                raw.urnet_device_add_auth_logout_listener(device, self._auth_logout_callback, None),
                raw.urnet_device_add_provider_ingress_contract_details_change_listener(device, self._ingress_callback, None),
                raw.urnet_device_add_provider_egress_contract_details_change_listener(device, self._egress_callback, None),
            ]
            stack.callback(self._close_subs)
            raw.urnet_device_set_provide_mode(device, PROVIDE_MODE_PUBLIC)
            # close unwinds this stack: the subscriptions, the device, then the manager
            self._close_stack = stack.pop_all()
        self.sync_wallet()

    def _new_device(self, space: int) -> int:
        """Creates the device with the extender-aware constructor: one call for both
        runs. On first run there is no identity, the key material is 0 and the
        device makes a new identity, which the caller saves."""
        raw = self._raw
        config = self._config
        key_material = new_key_material(raw, provider_key_material(config.identity))
        try:
            error = C.c_void_p()
            device = raw.urnet_new_device_local_with_provide_extender(
                space,
                config.client_jwt.encode(),
                DEVICE_DESCRIPTION.encode(),
                DEVICE_SPEC.encode(),
                APP_VERSION.encode(),
                config.instance_id.encode(),
                False,
                key_material,
                PROVIDE_EXTENDER_ENABLED,
                DEFAULT_PROVIDE_EXTENDER,
                C.byref(error),
            )
            message = take_string(raw, error.value)
        finally:
            if key_material:
                raw.urnet_release(key_material)
        if message:
            raise RuntimeError(message)
        if not device:
            raise RuntimeError("the sdk did not create the device")
        return device

    def _save_new_identity(self):
        """Saves the device's new identity as identity.json, on first run."""
        raw = self._raw
        device = self._device.handle
        identity = ProviderIdentity(
            client_id=self._config.client_id,
            client_key_seed=read_device_bytes(raw.urnet_device_local_get_client_key_seed, device),
            provide_tls_certificate_pem=read_device_bytes(raw.urnet_device_local_get_provide_tls_certificate_pem, device),
            provide_tls_private_key_pem=read_device_bytes(raw.urnet_device_local_get_provide_tls_private_key_pem, device),
            extender_key_seed=read_device_bytes(raw.urnet_device_local_get_extender_key_seed, device),
        )
        if len(identity.client_key_seed) != 32:
            raise RuntimeError("the device has no provider identity to save")
        try:
            save_provider_identity(self._config.state_dir, identity)
        except OSError as error:
            raise RuntimeError(f"save {IDENTITY_FILE_NAME}: {error}") from None

    def status(self) -> ProviderStatus:
        """Reads the status from the device getters and the listener state."""
        raw = self._raw
        device = self._device.handle
        # "client_limit_exceeded" with the hold's end in RetryTime while the
        # platform holds this client off for its network's client limit
        client_limit_status, client_limit_retry_time = parse_client_limit_status(
            take_string(raw, raw.urnet_device_get_client_limit_status(device))
        )
        # urnet_device_get_provider_ready also waits for processed client key
        # registration, which default device settings do not enable, so the
        # connected carrier is the readiness signal here
        state = provider_state(
            raw.urnet_device_get_provide_mode(device),
            client_limit_status,
            raw.urnet_device_get_provide_paused(device),
            raw.urnet_device_get_provide_enabled(device),
            raw.urnet_device_local_get_provider_connected(device),
        )
        data_provided_byte_count = parse_data_provided_byte_count(
            take_string(raw, raw.urnet_device_get_provider_packet_stats(device))
        )
        clients_served, clients_served_at_limit = self._clients_served.count()
        return ProviderStatus(
            state=state,
            client_limit_retry_time=client_limit_retry_time,
            clients_served=clients_served,
            clients_served_at_limit=clients_served_at_limit,
            data_provided_byte_count=data_provided_byte_count,
            payout_wallet=self._payout_wallet,
            payout_wallet_scope=self._payout_wallet_scope,
        )

    def sync_wallet(self):
        """Reads the payout wallet (GET /sn/wallet with the client credential). The
        app only displays the wallet: the backend maps it, never the app."""
        self._raw.urnet_device_local_sync_sn_wallet(self._device.handle, self._wallet_callback, None)

    def run(self):
        """Prints status lines until a stop request. Raises ConfigError when the
        server rejects the client credential."""
        last_key = None
        last_print_time = 0.0
        next_wallet_sync_time = time.monotonic() + WALLET_SYNC_INTERVAL_SECONDS
        while True:
            status = self.status()
            now = time.monotonic()
            if status.key() != last_key or STATUS_REPEAT_INTERVAL_SECONDS <= now - last_print_time:
                print(status.line())
                last_key = status.key()
                last_print_time = now
            if next_wallet_sync_time <= now:
                self.sync_wallet()
                next_wallet_sync_time = now + WALLET_SYNC_INTERVAL_SECONDS
            if self._apply_events_until(now + STATUS_POLL_INTERVAL_SECONDS):
                return

    def _apply_events_until(self, deadline: float) -> bool:
        """Applies run loop events as they arrive until the deadline; True when a
        stop was requested."""
        while True:
            timeout = deadline - time.monotonic()
            if timeout <= 0:
                return False
            try:
                event = self._events.get(timeout=timeout)
            except queue.Empty:
                return False
            if self._apply_event(event):
                return True

    def _apply_event(self, event: tuple) -> bool:
        """Applies one run loop event; True for a stop request. Raises ConfigError
        when the server rejected the client credential."""
        kind = event[0]
        if kind == EVENT_STOP:
            return True
        if kind == EVENT_AUTH_LOGOUT:
            raise ConfigError("the server rejected the client credential; issue a new scoped client JWT from your backend")
        if kind == EVENT_JWT_REFRESHED:
            self._save_client_jwt(event[1])
        elif kind == EVENT_WALLET_SYNCED:
            self._record_wallet_read(event[1], event[2])
        return False

    def close(self):
        """Stops providing, closes the subscriptions, the device and then the
        manager, and saves a refreshed credential that arrived meanwhile."""
        if self._closed:
            return
        self._closed = True
        self._raw.urnet_device_set_provide_mode(self._device.handle, PROVIDE_MODE_NONE)
        self._close_stack.close()
        # keep a refresh that arrived after the run loop's last read
        while True:
            try:
                event = self._events.get_nowait()
            except queue.Empty:
                break
            if event[0] == EVENT_JWT_REFRESHED:
                self._save_client_jwt(event[1])
        print("status: stopped")

    def _close_subs(self):
        """Closes and releases the listener subscriptions."""
        for sub in self._subs:
            self._raw.urnet_sub_close(sub)
            self._raw.urnet_release(sub)
        self._subs = []

    def _save_client_jwt(self, client_jwt: bytes | None):
        """Keeps the refreshed credential, so the next start uses a valid token."""
        if not client_jwt:
            return
        try:
            write_private_file(os.path.join(self._config.state_dir, CLIENT_JWT_FILE_NAME), client_jwt + b"\n")
        except OSError as error:
            # never print the token itself
            print(f"could not save the refreshed client credential: {error}", file=sys.stderr)

    def _record_wallet_read(self, result_json: bytes | None, error_text: bytes | None):
        """Records a payout wallet read. A failure keeps the last known wallet."""
        result = parse_json_object(result_json)
        if error_text is not None or result is None or result.get("error") is not None:
            if self._payout_wallet == PAYOUT_WALLET_CHECKING:
                self._payout_wallet = PAYOUT_WALLET_UNAVAILABLE
            return
        # the sdk caches the effective wallet before the callback: this client's
        # own consent, else the network consent, else (with hotkey delegations)
        # the network's hotkey entry, else a non-consent wallet
        wallet = parse_json_object(take_string(self._raw, self._raw.urnet_device_local_get_sn_wallet(self._device.handle)))
        coldkey = json_text(wallet.get("coldkey_ss58")) if wallet else ""
        if not coldkey:
            self._payout_wallet = PAYOUT_WALLET_NOT_SET
            self._payout_wallet_scope = ""
            return
        self._payout_wallet = coldkey
        self._payout_wallet_scope = payout_wallet_scope(
            json_text(wallet.get("consent_scope")),
            json_text(wallet.get("client_id")),
            self._config.client_id,
        )

    def _jwt_refreshed(self, _user_data, client_jwt):
        """Hands a refreshed client JWT to the run loop, which saves it. Runs on an
        SDK thread."""
        self._events.put((EVENT_JWT_REFRESHED, client_jwt))

    def _auth_logout(self, _user_data):
        """Ends the run: the server no longer accepts this client's credential. Runs
        on an SDK thread."""
        self._events.put((EVENT_AUTH_LOGOUT,))

    def _ingress_contract_details_changed(self, _user_data, details_json):
        """Counts the peer of a receive contract. Runs on an SDK thread."""
        self._clients_served.add(parse_json_object(details_json), receive=True)

    def _egress_contract_details_changed(self, _user_data, details_json):
        """Counts the peer of a send contract. Runs on an SDK thread."""
        self._clients_served.add(parse_json_object(details_json), receive=False)

    def _wallet_synced(self, _user_data, result_json, error_text):
        """Hands a payout wallet read to the run loop. Runs on an SDK thread."""
        self._events.put((EVENT_WALLET_SYNCED, result_json, error_text))
