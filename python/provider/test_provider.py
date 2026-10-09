"""Tests for the provider example: each self-test check as a test, so unittest
and --self-test cover the same behavior, and the provider lifecycle
(session.py) against a stand-in for the SDK's C ABI. The stand-in follows the
C ABI's documented behavior for handles, returned strings, buffer-out getters
and callbacks, without a device, network or credentials. When the urnetwork
package and its native library load (URNETWORK_SDK_LIBRARY), every call is
also checked against the real ctypes signatures and the real callback types.

Run from this directory: python3 -m unittest -v"""

import contextlib
import ctypes as C
import io
import json
import os
import queue
import shutil
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

import main
import selftest
from session import (
    DEVICE_DESCRIPTION,
    DEVICE_SPEC,
    EVENT_STOP,
    ProviderSession,
    configure_sdk_logs,
)
from state import (
    CLIENT_JWT_FILE_NAME,
    IDENTITY_FILE_NAME,
    POSIX,
    ConfigError,
    ProviderIdentity,
    load_provider_config,
    load_provider_identity,
    save_provider_identity,
    write_private_file,
)
from status import PROVIDE_MODE_NONE, PROVIDE_MODE_PUBLIC

try:
    import urnetwork
    from urnetwork import _raw as native_callback_types
except (ImportError, OSError, AttributeError):
    # not installed, no native library, or a native library older than the
    # package (an SDK version mismatch, which main.py reports with exit 78)
    urnetwork = None
    native_callback_types = None

TEST_CLIENT_ID = selftest.TEST_PROVIDER_ID
TEST_CLIENT_JWT = selftest.self_test_jwt(f'{{"client_id":"{TEST_CLIENT_ID}"}}')

# the identity that the stand-in device makes on first run
DEVICE_CLIENT_KEY_SEED = bytes(range(32))
DEVICE_CERTIFICATE_PEM = b"synthetic device certificate"
DEVICE_PRIVATE_KEY_PEM = b"synthetic device private key"
DEVICE_EXTENDER_KEY_SEED = bytes(range(32, 64))


def referenced(argument):
    """The object behind a ctypes byref() or pointer() argument."""
    return argument._obj if hasattr(argument, "_obj") else argument.contents


class FakeCAbi:
    """The C ABI functions that the provider calls, with the behavior the C ABI
    header documents, and a log of every call. Tests drive the SDK side through
    the fields and the trigger methods; callbacks run on the test thread."""

    def __init__(self):
        """A stand-in with no handles, no device and every getter at its default."""
        self.calls = []
        self.next_handle = 100
        # handle -> kind
        self.live_handles = {}
        # address -> buffer of a returned string that the caller has not freed
        self.unfreed_strings = {}
        # subscription handle -> (listener name, callback)
        self.listeners = {}
        self.wallet_callbacks = []
        self.key_materials = {}
        self.device_arguments = None
        self.device_error = None
        self.provide_mode = PROVIDE_MODE_NONE
        self.provide_paused = False
        self.provide_enabled = False
        self.provider_connected = False
        self.client_limit_status_json = None
        self.packet_stats_json = None
        self.sn_wallet_json = None

    def _record(self, name, *args):
        """Logs one call."""
        self.calls.append((name, args))

    def _new_handle(self, kind) -> int:
        """A new live handle of a kind."""
        self.next_handle += 1
        self.live_handles[self.next_handle] = kind
        return self.next_handle

    def _string(self, text) -> int | None:
        """A string the caller owns and frees with urnet_free_string; None is NULL."""
        if text is None:
            return None
        buffer = C.create_string_buffer(text if isinstance(text, bytes) else text.encode())
        self.unfreed_strings[C.addressof(buffer)] = buffer
        return C.addressof(buffer)

    def _copy_out(self, data: bytes, out, inout_length) -> bool:
        """The buffer-out pattern: the needed size always, the copy only when out
        has the capacity."""
        length = referenced(inout_length)
        capacity = length.value
        length.value = len(data)
        if out is None or capacity < len(data):
            return False
        C.memmove(out, data, len(data))
        return True

    def listener_callbacks(self, name):
        """The live callbacks of one listener kind."""
        return [callback for listener_name, callback in self.listeners.values() if listener_name == name]

    def trigger(self, name, *args):
        """Calls every live listener of one kind, as the SDK would."""
        for callback in self.listener_callbacks(name):
            callback(None, *args)

    def call_names(self):
        """The names of the logged calls, in order."""
        return [name for name, _ in self.calls]

    def urnet_free_string(self, pointer):
        self._record("urnet_free_string", pointer)
        self.unfreed_strings.pop(pointer, None)

    def urnet_release(self, handle):
        self._record("urnet_release", handle)
        return self.live_handles.pop(handle, None) is not None

    def urnet_version(self):
        self._record("urnet_version")
        return self._string("0.0.0-test")

    def urnet_set_log_dir(self, log_dir, out_error):
        self._record("urnet_set_log_dir", log_dir)
        return True

    def urnet_new_network_space_manager_no_storage(self):
        self._record("urnet_new_network_space_manager_no_storage")
        return self._new_handle("manager")

    def urnet_network_space_manager_update_network_space_values(self, manager, key_json, values_json):
        self._record("urnet_network_space_manager_update_network_space_values", manager, key_json, values_json)
        return self._new_handle("space")

    def urnet_network_space_get_api(self, space):
        self._record("urnet_network_space_get_api", space)
        return self._new_handle("api")

    def urnet_api_set_by_jwt(self, api, by_jwt):
        self._record("urnet_api_set_by_jwt", api, by_jwt)

    def urnet_network_space_manager_close(self, manager):
        self._record("urnet_network_space_manager_close", manager)

    def urnet_new_device_local_key_material(self, seed, seed_length, certificate, certificate_length, private_key, private_key_length):
        self._record("urnet_new_device_local_key_material")
        handle = self._new_handle("key material")
        self.key_materials[handle] = {
            "client_key_seed": C.string_at(seed, seed_length) if seed_length else b"",
            "provide_tls_certificate_pem": C.string_at(certificate, certificate_length) if certificate_length else b"",
            "provide_tls_private_key_pem": C.string_at(private_key, private_key_length) if private_key_length else b"",
            "extender_key_seed": b"",
        }
        return handle

    def urnet_device_local_key_material_set_extender_key_seed(self, key_material, seed, seed_length):
        self._record("urnet_device_local_key_material_set_extender_key_seed", key_material)
        self.key_materials[key_material]["extender_key_seed"] = C.string_at(seed, seed_length) if seed_length else b""

    def urnet_new_device_local_with_provide_extender(
        self,
        space,
        by_jwt,
        device_description,
        device_spec,
        app_version,
        instance_id,
        enable_rpc,
        key_material,
        provide_extender_enabled,
        default_provide_extender,
        out_error,
    ):
        self._record("urnet_new_device_local_with_provide_extender", key_material)
        self.device_arguments = {
            "space": space,
            "by_jwt": by_jwt,
            "device_description": device_description,
            "device_spec": device_spec,
            "app_version": app_version,
            "instance_id": instance_id,
            "enable_rpc": enable_rpc,
            # the constructor copies the key material; the caller may release it after
            "key_material": dict(self.key_materials[key_material]) if key_material else None,
            "provide_extender_enabled": provide_extender_enabled,
            "default_provide_extender": default_provide_extender,
        }
        if self.device_error is not None:
            referenced(out_error).value = self._string(self.device_error)
            return 0
        return self._new_handle("device")

    def urnet_device_local_get_client_key_seed(self, device, out, inout_length):
        self._record("urnet_device_local_get_client_key_seed", device)
        return self._copy_out(DEVICE_CLIENT_KEY_SEED, out, inout_length)

    def urnet_device_local_get_provide_tls_certificate_pem(self, device, out, inout_length):
        self._record("urnet_device_local_get_provide_tls_certificate_pem", device)
        return self._copy_out(DEVICE_CERTIFICATE_PEM, out, inout_length)

    def urnet_device_local_get_provide_tls_private_key_pem(self, device, out, inout_length):
        self._record("urnet_device_local_get_provide_tls_private_key_pem", device)
        return self._copy_out(DEVICE_PRIVATE_KEY_PEM, out, inout_length)

    def urnet_device_local_get_extender_key_seed(self, device, out, inout_length):
        self._record("urnet_device_local_get_extender_key_seed", device)
        return self._copy_out(DEVICE_EXTENDER_KEY_SEED, out, inout_length)

    def _add_listener(self, name, callback) -> int:
        """A subscription handle for one listener."""
        self._record(name)
        sub = self._new_handle("sub")
        self.listeners[sub] = (name, callback)
        return sub

    def urnet_device_add_jwt_refresh_listener(self, device, callback, user_data):
        return self._add_listener("urnet_device_add_jwt_refresh_listener", callback)

    def urnet_device_add_auth_logout_listener(self, device, callback, user_data):
        return self._add_listener("urnet_device_add_auth_logout_listener", callback)

    def urnet_device_add_provider_ingress_contract_details_change_listener(self, device, callback, user_data):
        return self._add_listener("urnet_device_add_provider_ingress_contract_details_change_listener", callback)

    def urnet_device_add_provider_egress_contract_details_change_listener(self, device, callback, user_data):
        return self._add_listener("urnet_device_add_provider_egress_contract_details_change_listener", callback)

    def urnet_sub_close(self, sub):
        self._record("urnet_sub_close", sub)
        self.listeners.pop(sub, None)

    def urnet_device_set_provide_mode(self, device, provide_mode):
        self._record("urnet_device_set_provide_mode", provide_mode)
        self.provide_mode = provide_mode

    def urnet_device_get_provide_mode(self, device):
        self._record("urnet_device_get_provide_mode")
        return self.provide_mode

    def urnet_device_get_provide_paused(self, device):
        self._record("urnet_device_get_provide_paused")
        return self.provide_paused

    def urnet_device_get_provide_enabled(self, device):
        self._record("urnet_device_get_provide_enabled")
        return self.provide_enabled

    def urnet_device_local_get_provider_connected(self, device):
        self._record("urnet_device_local_get_provider_connected")
        return self.provider_connected

    def urnet_device_get_client_limit_status(self, device):
        self._record("urnet_device_get_client_limit_status")
        return self._string(self.client_limit_status_json)

    def urnet_device_get_provider_packet_stats(self, device):
        self._record("urnet_device_get_provider_packet_stats")
        return self._string(self.packet_stats_json)

    def urnet_device_local_sync_sn_wallet(self, device, callback, user_data):
        self._record("urnet_device_local_sync_sn_wallet")
        self.wallet_callbacks.append(callback)

    def urnet_device_local_get_sn_wallet(self, device):
        self._record("urnet_device_local_get_sn_wallet")
        return self._string(self.sn_wallet_json)

    def urnet_device_close(self, device):
        self._record("urnet_device_close", device)


class CheckedCAbi:
    """Checks every call of a FakeCAbi against the real ctypes signature: the
    argument count, each argument's conversion with argtypes, and the type of
    the result for the restype."""

    def __init__(self, fake: FakeCAbi, native_raw):
        """Wraps fake with the signatures of native_raw."""
        self._fake = fake
        self._native_raw = native_raw

    def __getattr__(self, name):
        """The checked function name."""
        native_function = getattr(self._native_raw, name)
        fake_function = getattr(self._fake, name)

        def call(*args):
            if len(args) != len(native_function.argtypes):
                raise TypeError(f"{name} takes {len(native_function.argtypes)} arguments, called with {len(args)}")
            for argtype, arg in zip(native_function.argtypes, args):
                argtype.from_param(arg)
            result = fake_function(*args)
            restype = native_function.restype
            if restype is None:
                expected_types = (type(None),)
            elif restype is C.c_bool:
                expected_types = (bool,)
            elif restype is C.c_void_p:
                expected_types = (int, type(None))
            else:
                expected_types = (int,)
            if not isinstance(result, expected_types):
                raise TypeError(f"the stand-in {name} returned {result!r} for restype {restype}")
            return result

        return call


# callback types with the C ABI's prototypes, used when the native library does not load
TEST_CALLBACK_TYPES = SimpleNamespace(
    urnet_jwt_refresh_cb=C.CFUNCTYPE(None, C.c_void_p, C.c_char_p),
    urnet_auth_logout_cb=C.CFUNCTYPE(None, C.c_void_p),
    urnet_contract_details_change_cb=C.CFUNCTYPE(None, C.c_void_p, C.c_char_p),
    urnet_sn_get_wallet_cb=C.CFUNCTYPE(None, C.c_void_p, C.c_char_p, C.c_char_p),
)


def fake_sdk(fake: FakeCAbi):
    """A stand-in for the urnetwork package over fake: raw, the Handle and Device
    owners, and the callback types."""
    raw = CheckedCAbi(fake, urnetwork.raw) if urnetwork is not None else fake

    class Handle:
        """Owns one handle, as urnetwork.Handle does."""

        def __init__(self, handle, *, device=False):
            if not handle:
                raise ValueError("a nonzero, owned native handle is required")
            self._handle = handle
            self._device = device

        @property
        def handle(self):
            return self._handle

        def close(self):
            if self._handle:
                if self._device:
                    raw.urnet_device_close(self._handle)
                raw.urnet_release(self._handle)
                self._handle = 0

        def __enter__(self):
            return self

        def __exit__(self, *_):
            self.close()

    class Device(Handle):
        """Owns a device handle, as urnetwork.Device does."""

        def __init__(self, handle):
            super().__init__(handle, device=True)

    return SimpleNamespace(
        raw=raw,
        Handle=Handle,
        Device=Device,
        _raw=native_callback_types if native_callback_types is not None else TEST_CALLBACK_TYPES,
    )


def contract_details_json(source_id, destination_id) -> bytes:
    """A provider contract, as the contract details listeners deliver it."""
    return selftest.contract_json("55555555-5555-5555-5555-555555555555", source_id, destination_id, None).encode()


class SelfTestChecks(unittest.TestCase):
    """Each self-test check as a test."""

    def test_consent_disclaimer(self):
        selftest.check_consent_disclaimer()

    def test_format_byte_count(self):
        selftest.check_format_byte_count()

    def test_status_text(self):
        selftest.check_status_text()

    def test_status_lines(self):
        selftest.check_status_lines()

    def test_status_key(self):
        selftest.check_status_key()

    def test_provider_state(self):
        selftest.check_provider_state()

    def test_payout_wallet_scope(self):
        selftest.check_payout_wallet_scope()

    def test_clients_served(self):
        selftest.check_clients_served()

    def test_sdk_status_json(self):
        selftest.check_sdk_status_json()

    def test_client_jwt_claims(self):
        selftest.check_client_jwt_claims()

    def test_state_files(self):
        selftest.check_state_files()

    def test_provider_config(self):
        selftest.check_provider_config()

    def test_usage_exit_code(self):
        selftest.check_usage_exit_code()

    def test_sdk_mismatch(self):
        selftest.check_sdk_mismatch()


class ProviderRunExitCodes(unittest.TestCase):
    """The run command's exit codes for configuration problems, before any SDK call."""

    def test_missing_or_relative_state_dir_exits_78(self):
        for state_dir in ("", os.path.join("relative", "state")):
            with mock.patch.dict(os.environ, {"URNETWORK_PROVIDER_STATE_DIR": state_dir}):
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(main.run(["run"]), main.EXIT_CONFIG, state_dir)

    def test_missing_client_jwt_exits_78(self):
        state_dir = tempfile.mkdtemp(prefix="ur-provider-test-")
        try:
            with mock.patch.dict(os.environ, {"URNETWORK_PROVIDER_STATE_DIR": state_dir}):
                with contextlib.redirect_stdout(io.StringIO()) as stdout, contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(main.run([]), main.EXIT_CONFIG)
            # the disclaimer comes first, even when the configuration is missing
            self.assertTrue(stdout.getvalue().startswith("Consent disclaimer: "))
        finally:
            shutil.rmtree(state_dir, ignore_errors=True)


@unittest.skipIf(urnetwork is None, "the urnetwork package and its native library do not load")
class NativeLibrary(unittest.TestCase):
    """The calls that need the native library but no device, network or
    credentials."""

    def test_version_prints_the_sdk_version(self):
        with contextlib.redirect_stdout(io.StringIO()) as stdout:
            self.assertEqual(main.run(["--version"]), main.EXIT_STOPPED)
        self.assertEqual(stdout.getvalue(), urnetwork.version() + "\n")
        self.assertTrue(urnetwork.version())

    def test_configure_sdk_logs_points_the_sdk_at_a_private_log_directory(self):
        state_dir = tempfile.mkdtemp(prefix="ur-provider-test-")
        try:
            log_dir = os.path.join(state_dir, "logs")
            configure_sdk_logs(urnetwork, log_dir)
            self.assertTrue(os.path.isdir(log_dir))
            if POSIX:
                self.assertEqual(os.stat(log_dir).st_mode & 0o077, 0)
        finally:
            shutil.rmtree(state_dir, ignore_errors=True)


class ProviderSessionLifecycle(unittest.TestCase):
    """The provider lifecycle over the C ABI stand-in."""

    def setUp(self):
        self.state_dir = tempfile.mkdtemp(prefix="ur-provider-test-")
        if POSIX:
            os.chmod(self.state_dir, 0o700)
        write_private_file(os.path.join(self.state_dir, CLIENT_JWT_FILE_NAME), (TEST_CLIENT_JWT + "\n").encode())
        self.fake = FakeCAbi()
        self.events = queue.SimpleQueue()

    def tearDown(self):
        shutil.rmtree(self.state_dir, ignore_errors=True)

    def new_session(self) -> ProviderSession:
        """A session for the test installation, over the stand-in."""
        config = load_provider_config(self.state_dir)
        with contextlib.redirect_stdout(io.StringIO()):
            return ProviderSession(config, fake_sdk(self.fake), self.events)

    def close_session(self, session) -> str:
        """Closes session and returns what it printed."""
        with contextlib.redirect_stdout(io.StringIO()) as stdout:
            session.close()
        return stdout.getvalue()

    def run_session(self, session) -> str:
        """Runs session until a queued stop and returns what it printed."""
        with contextlib.redirect_stdout(io.StringIO()) as stdout:
            session.run()
        return stdout.getvalue()

    def test_first_run_creates_the_device_without_key_material_and_saves_its_identity(self):
        session = self.new_session()
        arguments = self.fake.device_arguments
        self.assertIsNone(arguments["key_material"])
        self.assertEqual(arguments["by_jwt"], TEST_CLIENT_JWT.encode())
        self.assertEqual(arguments["device_description"], DEVICE_DESCRIPTION.encode())
        self.assertEqual(arguments["device_spec"], DEVICE_SPEC.encode())
        self.assertEqual(arguments["app_version"], b"1")
        self.assertEqual(arguments["instance_id"], load_provider_config(self.state_dir).instance_id.encode())
        self.assertIs(arguments["enable_rpc"], False)
        # the extender role's default stays on: both settings are passed as true
        self.assertIs(arguments["provide_extender_enabled"], True)
        self.assertIs(arguments["default_provide_extender"], True)
        self.assertEqual(
            load_provider_identity(self.state_dir, TEST_CLIENT_ID),
            ProviderIdentity(
                client_id=TEST_CLIENT_ID,
                client_key_seed=DEVICE_CLIENT_KEY_SEED,
                provide_tls_certificate_pem=DEVICE_CERTIFICATE_PEM,
                provide_tls_private_key_pem=DEVICE_PRIVATE_KEY_PEM,
                extender_key_seed=DEVICE_EXTENDER_KEY_SEED,
            ),
        )
        if POSIX:
            self.assertEqual(os.stat(os.path.join(self.state_dir, IDENTITY_FILE_NAME)).st_mode & 0o777, 0o600)
        # the listeners exist before providing starts, then the wallet is read
        names = self.fake.call_names()
        provide_index = names.index("urnet_device_set_provide_mode")
        for listener_name in (
            "urnet_device_add_jwt_refresh_listener",
            "urnet_device_add_auth_logout_listener",
            "urnet_device_add_provider_ingress_contract_details_change_listener",
            "urnet_device_add_provider_egress_contract_details_change_listener",
        ):
            self.assertLess(names.index(listener_name), provide_index, listener_name)
        self.assertEqual(self.fake.provide_mode, PROVIDE_MODE_PUBLIC)
        self.assertLess(provide_index, names.index("urnet_device_local_sync_sn_wallet"))
        self.close_session(session)

    def test_later_run_passes_the_stored_identity_and_releases_the_key_material(self):
        identity = ProviderIdentity(
            client_id=TEST_CLIENT_ID,
            client_key_seed=bytes([7]) * 32,
            provide_tls_certificate_pem=b"synthetic stored certificate",
            provide_tls_private_key_pem=b"synthetic stored private key",
            extender_key_seed=bytes([8]) * 32,
        )
        save_provider_identity(self.state_dir, identity)
        session = self.new_session()
        self.assertEqual(
            self.fake.device_arguments["key_material"],
            {
                "client_key_seed": identity.client_key_seed,
                "provide_tls_certificate_pem": identity.provide_tls_certificate_pem,
                "provide_tls_private_key_pem": identity.provide_tls_private_key_pem,
                "extender_key_seed": identity.extender_key_seed,
            },
        )
        self.assertNotIn("key material", self.fake.live_handles.values())
        # the stored identity is kept, not replaced by the device's
        self.assertNotIn("urnet_device_local_get_client_key_seed", self.fake.call_names())
        self.assertEqual(load_provider_identity(self.state_dir, TEST_CLIENT_ID), identity)
        self.close_session(session)

    def test_another_clients_identity_is_replaced_on_first_run_of_this_client(self):
        save_provider_identity(
            self.state_dir,
            ProviderIdentity(
                client_id=selftest.TEST_CLIENT_A_ID,
                client_key_seed=bytes([7]) * 32,
                provide_tls_certificate_pem=b"synthetic other certificate",
                provide_tls_private_key_pem=b"synthetic other private key",
            ),
        )
        session = self.new_session()
        self.assertIsNone(self.fake.device_arguments["key_material"])
        self.assertEqual(load_provider_identity(self.state_dir, TEST_CLIENT_ID).client_key_seed, DEVICE_CLIENT_KEY_SEED)
        self.close_session(session)

    def test_status_reads_the_device_getters_and_frees_every_string(self):
        session = self.new_session()
        # null client limit status and packet stats: no limit and 0 bytes
        self.assertEqual(session.status().line(), "status: starting | clients served: 0 | data provided: 0 B | payout wallet: checking")
        self.fake.provide_enabled = True
        self.fake.provider_connected = True
        self.fake.packet_stats_json = json.dumps({"RemoteEgressByteCount": 13002342 - 1000, "RemoteIngressByteCount": 1000})
        self.fake.trigger("urnet_device_add_provider_ingress_contract_details_change_listener", contract_details_json(selftest.TEST_CLIENT_A_ID, TEST_CLIENT_ID))
        self.fake.trigger("urnet_device_add_provider_egress_contract_details_change_listener", contract_details_json(TEST_CLIENT_ID, selftest.TEST_CLIENT_A_ID))
        self.fake.trigger("urnet_device_add_provider_ingress_contract_details_change_listener", contract_details_json(selftest.TEST_CLIENT_B_ID, TEST_CLIENT_ID))
        self.assertEqual(session.status().line(), "status: providing | clients served: 2 | data provided: 12.4 MiB | payout wallet: checking")
        self.fake.client_limit_status_json = json.dumps({"Status": "client_limit_exceeded", "RetryTime": 1791313440001})
        self.assertEqual(session.status().line(), "status: client limit, retry at 19:05 UTC | clients served: 2 | data provided: 12.4 MiB | payout wallet: checking")
        self.fake.provide_mode = PROVIDE_MODE_NONE
        self.assertEqual(session.status().state, "stopped")
        self.close_session(session)
        self.assertEqual(self.fake.unfreed_strings, {})

    def test_wallet_reads_label_the_wallet_and_a_later_failure_keeps_it(self):
        session = self.new_session()
        wallet_callback = self.fake.wallet_callbacks[0]
        # the first read fails
        wallet_callback(None, b"null", b"synthetic network failure")
        self.events.put((EVENT_STOP,))
        self.run_session(session)
        self.assertEqual(session.status().payout_wallet, "unavailable")
        # a read with a network consent
        self.fake.sn_wallet_json = json.dumps({"coldkey_ss58": selftest.TEST_WALLET, "set_at_millis": 1, "consent_scope": "network"})
        wallet_callback(None, json.dumps({"wallet": json.loads(self.fake.sn_wallet_json)}).encode(), None)
        self.events.put((EVENT_STOP,))
        self.run_session(session)
        self.assertIn(f"payout wallet: {selftest.TEST_WALLET} (network)", session.status().line())
        # a later failure keeps the wallet, also for an empty error object
        for failed_read in (b'{"error": {"message": "synthetic refusal"}}', b'{"error": {}}'):
            wallet_callback(None, failed_read, None)
            self.events.put((EVENT_STOP,))
            self.run_session(session)
            self.assertEqual((session.status().payout_wallet, session.status().payout_wallet_scope), (selftest.TEST_WALLET, "network"))
        # a hotkey delegation is labeled by its scope, and this client's own consent wins
        self.fake.sn_wallet_json = json.dumps({"coldkey_ss58": selftest.TEST_WALLET, "set_at_millis": 1, "consent_scope": "hotkey"})
        wallet_callback(None, b"{}", None)
        self.events.put((EVENT_STOP,))
        self.run_session(session)
        self.assertEqual(session.status().payout_wallet_scope, "hotkey")
        self.fake.sn_wallet_json = json.dumps({"coldkey_ss58": selftest.TEST_WALLET, "client_id": TEST_CLIENT_ID, "set_at_millis": 1, "consent_scope": "provider"})
        wallet_callback(None, b"{}", None)
        self.events.put((EVENT_STOP,))
        self.run_session(session)
        self.assertEqual(session.status().payout_wallet_scope, "this provider")
        # no mapped wallet
        self.fake.sn_wallet_json = None
        wallet_callback(None, b"{}", None)
        self.events.put((EVENT_STOP,))
        self.run_session(session)
        self.assertEqual((session.status().payout_wallet, session.status().payout_wallet_scope), ("not set", ""))
        self.close_session(session)

    def test_an_empty_error_object_is_a_failed_first_wallet_read(self):
        session = self.new_session()
        self.fake.sn_wallet_json = json.dumps({"coldkey_ss58": selftest.TEST_WALLET, "set_at_millis": 1})
        self.fake.wallet_callbacks[0](None, b'{"error": {}}', None)
        self.events.put((EVENT_STOP,))
        self.run_session(session)
        self.assertEqual(session.status().payout_wallet, "unavailable")
        self.close_session(session)

    def test_run_saves_a_refreshed_jwt_and_prints_the_status(self):
        session = self.new_session()
        self.fake.trigger("urnet_device_add_jwt_refresh_listener", b"synthetic.refreshed.jwt")
        self.events.put((EVENT_STOP,))
        output = self.run_session(session)
        self.assertEqual(output, "status: starting | clients served: 0 | data provided: 0 B | payout wallet: checking\n")
        with open(os.path.join(self.state_dir, CLIENT_JWT_FILE_NAME), "rb") as file:
            self.assertEqual(file.read(), b"synthetic.refreshed.jwt\n")
        if POSIX:
            self.assertEqual(os.stat(os.path.join(self.state_dir, CLIENT_JWT_FILE_NAME)).st_mode & 0o777, 0o600)
        self.close_session(session)

    def test_auth_logout_ends_the_run_with_a_credential_error(self):
        session = self.new_session()
        self.fake.trigger("urnet_device_add_auth_logout_listener")
        with self.assertRaises(ConfigError):
            self.run_session(session)
        self.close_session(session)

    def test_close_stops_providing_then_closes_subscriptions_device_and_manager(self):
        session = self.new_session()
        self.fake.calls.clear()
        # a refresh that arrives after the run loop's last read is still saved
        self.fake.trigger("urnet_device_add_jwt_refresh_listener", b"synthetic.late.jwt")
        output = self.close_session(session)
        self.assertEqual(output, "status: stopped\n")
        with open(os.path.join(self.state_dir, CLIENT_JWT_FILE_NAME), "rb") as file:
            self.assertEqual(file.read(), b"synthetic.late.jwt\n")
        names = self.fake.call_names()
        self.assertEqual(names[0], "urnet_device_set_provide_mode")
        self.assertEqual(self.fake.provide_mode, PROVIDE_MODE_NONE)
        self.assertEqual(names[1:9], ["urnet_sub_close", "urnet_release"] * 4)
        self.assertEqual(
            names[9:],
            [
                "urnet_device_close",
                "urnet_release",
                "urnet_release",
                "urnet_release",
                "urnet_network_space_manager_close",
                "urnet_release",
            ],
        )
        self.assertEqual(self.fake.listeners, {})
        self.assertEqual(self.fake.live_handles, {})
        # closing again does nothing
        self.fake.calls.clear()
        self.close_session(session)
        self.assertEqual(self.fake.calls, [])

    def test_device_error_closes_the_manager_and_releases_every_handle(self):
        self.fake.device_error = "synthetic device error"
        with self.assertRaisesRegex(RuntimeError, "synthetic device error"):
            self.new_session()
        self.assertIn("urnet_network_space_manager_close", self.fake.call_names())
        self.assertEqual(self.fake.live_handles, {})
        self.assertEqual(self.fake.unfreed_strings, {})
        self.assertFalse(os.path.exists(os.path.join(self.state_dir, IDENTITY_FILE_NAME)))


if __name__ == "__main__":
    unittest.main()
