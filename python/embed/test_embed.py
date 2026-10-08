"""Tests for the embed example: each self-test check as a test, so unittest and
--self-test cover the same behavior; the device lifecycle (session.py) against a
stand-in for the SDK's C ABI; and the real urllib transport against a loopback
stand-in for the token server and the API. The C ABI stand-in follows the C
ABI's documented behavior for handles, returned strings and callbacks, without
a device, network or credentials. When the urnetwork package's source is
importable (installed, or PYTHONPATH naming an sdk checkout's sdk/python/src),
every call is also checked against the package's own ctypes signatures and
callback types, read from its bindings without loading the native library.

Run from this directory: python3 -m unittest -v"""

import contextlib
import ctypes as C
import http.server
import importlib.util
import io
import json
import os
import queue
import threading
from types import SimpleNamespace
import unittest

import selftest
from caps import read_own_caps
from client_token import TokenServerRefused, fetch_client_jwt
from session import (
    CONNECT_LOCATION_JSON,
    DEVICE_DESCRIPTION,
    DEVICE_SPEC,
    EVENT_AUTH_LOGOUT,
    EVENT_CAPS_READ,
    EVENT_CONTRACT_STATUS_CHANGED,
    EVENT_JWT_REFRESHED,
    EVENT_STOP,
    CapReader,
    EmbedConfig,
    EmbedSession,
    configure_sdk_logs,
)
from state import CLIENT_JWT_FILE_NAME, POSIX, ConfigError, read_private_file
from transport import urllib_transport



class RecordingFunction:
    """A function of RecordingLibrary: bind() assigns its signature."""

    argtypes = None
    # the ctypes default
    restype = C.c_int


class RecordingLibrary:
    """Stands in for the native library while the package's bind() assigns every
    function's argtypes and restype."""

    def __init__(self):
        self.functions = {}

    def __getattr__(self, name):
        if name.startswith("__"):
            raise AttributeError(name)
        return self.functions.setdefault(name, RecordingFunction())


def package_bindings():
    """The urnetwork package's ctypes signatures and callback types, read by running
    its generated bind() against a RecordingLibrary, so neither the package's
    __init__ nor the native library loads. None when the package source is not
    importable."""
    try:
        spec = importlib.util.find_spec("urnetwork")
    except (ImportError, ValueError):
        return None
    if spec is None or not spec.submodule_search_locations:
        return None
    raw_path = os.path.join(list(spec.submodule_search_locations)[0], "_raw.py")
    if not os.path.isfile(raw_path):
        return None
    module_spec = importlib.util.spec_from_file_location("urnetwork_raw_bindings", raw_path)
    module = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(module)
    library = RecordingLibrary()
    module.bind(library)
    return SimpleNamespace(functions=library.functions, callback_types=module)


BINDINGS = package_bindings()

TEST_CLIENT_ID = selftest.TEST_CLIENT_ID
TEST_CLIENT_JWT = selftest.TEST_CLIENT_JWT
TEST_REFRESHED_JWT = selftest.self_test_jwt(f'{{"client_id":"{TEST_CLIENT_ID}","exp":2000000000}}')


def referenced(argument):
    """The object behind a ctypes byref() or pointer() argument."""
    return argument._obj if hasattr(argument, "_obj") else argument.contents


class FakeCAbi:
    """The C ABI functions that the embed app calls, with the behavior the C ABI
    header documents, and a log of every call. Tests drive the SDK side through
    the fields and trigger(); callbacks run on the test thread."""

    def __init__(self):
        self.calls = []
        self.next_handle = 100
        # handle -> kind
        self.live_handles = {}
        # address -> buffer of a returned string that the caller has not freed
        self.unfreed_strings = {}
        # subscription handle -> (listener name, callback)
        self.listeners = {}
        self.device_arguments = None
        self.device_error = None
        self.connect_location_json = None
        self.client_limit_status_json = None
        self.window_status_json = None

    def _record(self, name, *args):
        self.calls.append((name, args))

    def _new_handle(self, kind) -> int:
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

    def trigger(self, name, *args):
        """Calls every live listener of one kind, as the SDK would."""
        for listener_name, callback in list(self.listeners.values()):
            if listener_name == name:
                callback(None, *args)

    def call_names(self):
        return [name for name, _ in self.calls]

    def urnet_free_string(self, pointer):
        self._record("urnet_free_string", pointer)
        self.unfreed_strings.pop(pointer, None)

    def urnet_release(self, handle):
        self._record("urnet_release", handle)
        return self.live_handles.pop(handle, None) is not None

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

    def urnet_new_device_local_with_defaults(self, space, by_jwt, device_description, device_spec, app_version, instance_id, enable_rpc, out_error):
        self._record("urnet_new_device_local_with_defaults")
        self.device_arguments = {
            "space": space,
            "by_jwt": by_jwt,
            "device_description": device_description,
            "device_spec": device_spec,
            "app_version": app_version,
            "instance_id": instance_id,
            "enable_rpc": enable_rpc,
        }
        if self.device_error is not None:
            referenced(out_error).value = self._string(self.device_error)
            return 0
        return self._new_handle("device")

    def _add_listener(self, name, callback) -> int:
        self._record(name)
        sub = self._new_handle("sub")
        self.listeners[sub] = (name, callback)
        return sub

    def urnet_device_add_jwt_refresh_listener(self, device, callback, user_data):
        return self._add_listener("urnet_device_add_jwt_refresh_listener", callback)

    def urnet_device_add_auth_logout_listener(self, device, callback, user_data):
        return self._add_listener("urnet_device_add_auth_logout_listener", callback)

    def urnet_device_add_contract_status_change_listener(self, device, callback, user_data):
        return self._add_listener("urnet_device_add_contract_status_change_listener", callback)

    def urnet_sub_close(self, sub):
        self._record("urnet_sub_close", sub)
        self.listeners.pop(sub, None)

    def urnet_device_set_connect_location(self, device, location_json):
        self._record("urnet_device_set_connect_location", location_json)
        self.connect_location_json = location_json

    def urnet_device_get_client_limit_status(self, device):
        self._record("urnet_device_get_client_limit_status")
        return self._string(self.client_limit_status_json)

    def urnet_device_get_window_status(self, device):
        self._record("urnet_device_get_window_status")
        return self._string(self.window_status_json)

    def urnet_device_close(self, device):
        self._record("urnet_device_close", device)


class CheckedCAbi:
    """Checks every call of a FakeCAbi against the package's ctypes signature: the
    argument count, each argument's conversion with argtypes, and the type of
    the result for the restype. A function the bindings do not have fails."""

    def __init__(self, fake: FakeCAbi, functions: dict):
        self._fake = fake
        self._functions = functions

    def __getattr__(self, name):
        if name not in self._functions:
            raise AttributeError(f"the urnetwork package has no {name}")
        native_function = self._functions[name]
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
    urnet_contract_status_change_cb=C.CFUNCTYPE(None, C.c_void_p, C.c_char_p),
)


def fake_sdk(fake: FakeCAbi):
    """A stand-in for the urnetwork package over fake: raw, the Handle and Device
    owners, and the callback types."""
    raw = CheckedCAbi(fake, BINDINGS.functions) if BINDINGS is not None else fake

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
        _raw=BINDINGS.callback_types if BINDINGS is not None else TEST_CALLBACK_TYPES,
    )


class RecordingCapReader:
    """A cap reader that runs no thread: it records how the session drives it."""

    instances = []

    def __init__(self, read, events):
        self.read = read
        self.events = events
        self.started_with = None
        self.wakes = 0
        self.stopped = False
        RecordingCapReader.instances.append(self)

    def start(self, read_now: bool):
        self.started_with = read_now

    def wake(self):
        self.wakes += 1

    def stop(self):
        self.stopped = True


class SelfTestChecks(unittest.TestCase):
    """Each self-test check as a test."""


for _check in selftest.CHECKS:
    setattr(SelfTestChecks, "test_" + _check.__name__.removeprefix("check_"), staticmethod(_check))


class SessionLifecycle(unittest.TestCase):
    """The device lifecycle against the C ABI stand-in."""

    def setUp(self):
        self.state_context = selftest.private_dir()
        self.state_dir = self.state_context.__enter__()
        self.fake = FakeCAbi()
        self.events = queue.SimpleQueue()
        RecordingCapReader.instances.clear()

    def tearDown(self):
        self.state_context.__exit__(None, None, None)

    def start(self, first_cap_reading=None, transport=None) -> EmbedSession:
        config = EmbedConfig(
            state_dir=self.state_dir,
            client_jwt=TEST_CLIENT_JWT,
            client_id=TEST_CLIENT_ID,
            instance_id=selftest.TEST_INSTANCE_ID,
            api_origin=selftest.TEST_API,
            first_cap_reading=first_cap_reading,
        )
        return EmbedSession(
            config,
            fake_sdk(self.fake),
            self.events,
            transport or selftest.StandInServer(),
            cap_reader_class=RecordingCapReader,
        )

    def test_start_creates_the_device_and_listeners(self):
        session = self.start()
        names = self.fake.call_names()
        self.assertEqual(
            names[:4],
            [
                "urnet_new_network_space_manager_no_storage",
                "urnet_network_space_manager_update_network_space_values",
                "urnet_network_space_get_api",
                "urnet_api_set_by_jwt",
            ],
        )
        self.assertEqual(self.fake.calls[1][1][1:], (b'{"host_name":"ur.network","env_name":"main"}', b'{"migration_host_name":"bringyour.com"}'))
        self.assertEqual(self.fake.calls[3][1][1], TEST_CLIENT_JWT.encode())
        device_arguments = dict(self.fake.device_arguments)
        space = device_arguments.pop("space")
        self.assertEqual(
            device_arguments,
            {
                "by_jwt": TEST_CLIENT_JWT.encode(),
                "device_description": DEVICE_DESCRIPTION.encode(),
                "device_spec": DEVICE_SPEC.encode(),
                "app_version": b"1",
                "instance_id": selftest.TEST_INSTANCE_ID.encode(),
                "enable_rpc": False,
            },
        )
        self.assertEqual(self.fake.live_handles[space], "space")
        for listener in (
            "urnet_device_add_jwt_refresh_listener",
            "urnet_device_add_auth_logout_listener",
            "urnet_device_add_contract_status_change_listener",
        ):
            self.assertIn(listener, names)
        self.assertEqual(self.fake.connect_location_json, CONNECT_LOCATION_JSON)
        self.assertEqual(json.loads(CONNECT_LOCATION_JSON), {"connect_location_id": {"best_available": True}})
        # no first reading: the caps are read at start
        self.assertTrue(RecordingCapReader.instances[0].started_with)
        session.close()

    def test_first_cap_reading_from_the_token_server(self):
        reading = selftest.cap_reading(monthly_byte_limit=5000000000, monthly_used_byte_count=1234567890)
        self.fake.window_status_json = '{"ProviderStateAdded":1}'
        session = self.start(first_cap_reading=reading)
        self.assertFalse(RecordingCapReader.instances[0].started_with)
        self.assertEqual(session.status().line(), "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap")
        session.close()

    def test_status_from_the_device_getters(self):
        session = self.start()
        self.assertEqual(session.status().line(), "status: connecting | data this month: checking | data total: checking")
        self.fake.window_status_json = '{"ProviderStateAdded":2}'
        session.apply_event((EVENT_CAPS_READ, None))
        self.assertEqual(session.status().line(), "status: connected | data this month: unavailable | data total: unavailable")
        self.fake.client_limit_status_json = f'{{"Status":"client_limit_exceeded","RetryTime":{selftest.TEST_RETRY_TIME}}}'
        session.apply_event((EVENT_CAPS_READ, selftest.cap_reading(monthly_byte_limit=5000000000)))
        self.assertEqual(session.status().line(), "status: client limit, retry at 19:05 UTC | data this month: 0 B of 5.0 GB | data total: no cap")
        # every returned string was freed
        self.assertEqual(self.fake.unfreed_strings, {})
        session.close()

    def test_caps_read_uses_the_latest_client_jwt(self):
        server = selftest.StandInServer((200, {"client_id": TEST_CLIENT_ID, "monthly_byte_limit": 9}))
        session = self.start(transport=server)
        reader = RecordingCapReader.instances[0]
        self.fake.trigger("urnet_device_add_jwt_refresh_listener", TEST_REFRESHED_JWT.encode())
        self.assertFalse(session.apply_event(self.events.get_nowait()))
        reading = reader.read()
        self.assertEqual(reading.monthly_byte_limit, 9)
        self.assertEqual(server.requests[0]["headers"]["Authorization"], "Bearer " + TEST_REFRESHED_JWT)
        self.assertEqual(read_private_file(os.path.join(self.state_dir, CLIENT_JWT_FILE_NAME)), (TEST_REFRESHED_JWT + "\n").encode())
        session.close()

    def test_contract_status_change_reads_the_caps(self):
        session = self.start()
        self.fake.trigger("urnet_device_add_contract_status_change_listener", b'{"InsufficientBalance":false}')
        event = self.events.get_nowait()
        self.assertEqual(event, (EVENT_CONTRACT_STATUS_CHANGED,))
        session.apply_event(event)
        self.assertEqual(RecordingCapReader.instances[0].wakes, 1)
        session.close()

    def test_auth_logout_ends_the_run_with_a_config_error(self):
        session = self.start()
        self.fake.trigger("urnet_device_add_auth_logout_listener")
        event = self.events.get_nowait()
        self.assertEqual(event, (EVENT_AUTH_LOGOUT,))
        with self.assertRaises(ConfigError):
            session.apply_event(event)
        session.close()

    def test_stop_and_close_order(self):
        session = self.start()
        self.assertTrue(session.apply_event((EVENT_STOP,)))
        # a refresh that arrives after the run loop's last read is still saved
        self.fake.trigger("urnet_device_add_jwt_refresh_listener", TEST_REFRESHED_JWT.encode())
        self.fake.calls.clear()
        session.close()
        names = self.fake.call_names()
        self.assertEqual(names[:6], ["urnet_sub_close", "urnet_release"] * 3)
        device_close = names.index("urnet_device_close")
        manager_close = names.index("urnet_network_space_manager_close")
        self.assertLess(device_close, manager_close)
        self.assertEqual(self.fake.live_handles, {})
        self.assertEqual(self.fake.listeners, {})
        self.assertTrue(RecordingCapReader.instances[0].stopped)
        self.assertEqual(read_private_file(os.path.join(self.state_dir, CLIENT_JWT_FILE_NAME)), (TEST_REFRESHED_JWT + "\n").encode())
        session.close()

    def test_device_error_closes_what_exists(self):
        self.fake.device_error = "invalid jwt"
        with self.assertRaisesRegex(RuntimeError, "invalid jwt"):
            self.start()
        self.assertEqual(self.fake.live_handles, {})
        self.assertIn("urnet_network_space_manager_close", self.fake.call_names())
        self.assertEqual(self.fake.unfreed_strings, {})

    def test_sdk_logs(self):
        log_dir = os.path.join(self.state_dir, "logs")
        configure_sdk_logs(fake_sdk(self.fake), log_dir)
        self.assertTrue(os.path.isdir(log_dir))
        self.assertEqual(self.fake.calls[-1], ("urnet_set_log_dir", (log_dir.encode(),)))


class CapReaderThread(unittest.TestCase):
    """The real cap reader: a read at start, then a read when woken."""

    def test_reads_at_start_and_when_woken(self):
        events = queue.SimpleQueue()
        readings = iter([selftest.cap_reading(monthly_byte_limit=1), selftest.cap_reading(monthly_byte_limit=2)])
        reader = CapReader(lambda: next(readings), events, interval_seconds=3600)
        reader.start(read_now=True)
        try:
            first = events.get(timeout=5)
            self.assertEqual((first[0], first[1].monthly_byte_limit), (EVENT_CAPS_READ, 1))
            reader.wake()
            second = events.get(timeout=5)
            self.assertEqual(second[1].monthly_byte_limit, 2)
        finally:
            reader.stop()

    def test_a_failing_read_reports_a_failure(self):
        events = queue.SimpleQueue()

        def read():
            raise RuntimeError("boom")

        reader = CapReader(read, events, interval_seconds=3600)
        reader.start(read_now=True)
        try:
            self.assertEqual(events.get(timeout=5), (EVENT_CAPS_READ, None))
        finally:
            reader.stop()


class LoopbackServer:
    """A stand-in token server and API on 127.0.0.1, answering each request from a
    queue of (status, JSON body) answers and logging the requests."""

    def __init__(self, *answers):
        self.answers = list(answers)
        self.requests = []
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def _answer(self):
                length = int(self.headers.get("Content-Length") or 0)
                owner.requests.append(
                    {
                        "method": self.command,
                        "path": self.path,
                        "authorization": self.headers.get("Authorization"),
                        "body": self.rfile.read(length) if length else b"",
                    }
                )
                status, body = owner.answers.pop(0)
                data = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(data)

            do_GET = _answer
            do_POST = _answer

            def log_message(self, *_):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.origin = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.server.shutdown()
        self.server.server_close()


class TransportOverHttp(unittest.TestCase):
    """The real urllib transport against a loopback stand-in."""

    def test_token_fetch_and_cap_read(self):
        with selftest.private_dir() as state_dir, LoopbackServer(
            (200, selftest.token_answer()),
            (200, {"client_id": TEST_CLIENT_ID, "total_byte_limit": 5, "total_used_byte_count": 2}),
        ) as server:
            fetched = fetch_client_jwt(state_dir, selftest.TEST_INSTANCE_ID, server.origin, selftest.TEST_SESSION, urllib_transport)
            self.assertEqual(fetched.client_id, TEST_CLIENT_ID)
            reading = read_own_caps(server.origin, fetched.client_jwt, urllib_transport)
            self.assertEqual((reading.total_byte_limit, reading.total_used_byte_count), (5, 2))
            token_request, cap_request = server.requests
            self.assertEqual((token_request["method"], token_request["path"]), ("POST", "/urnetwork/client-token"))
            self.assertEqual(token_request["authorization"], "Bearer " + selftest.TEST_SESSION)
            self.assertEqual(json.loads(token_request["body"]), {"installation_id": selftest.TEST_INSTANCE_ID})
            self.assertEqual((cap_request["method"], cap_request["path"]), ("GET", "/network/client-data-cap"))
            self.assertEqual(cap_request["authorization"], "Bearer " + TEST_CLIENT_JWT)

    def test_error_status_keeps_its_answer(self):
        with selftest.private_dir() as state_dir, LoopbackServer((401, {"error": {"code": "unauthorized", "message": "unknown session"}})) as server:
            with self.assertRaisesRegex(TokenServerRefused, "unknown session"):
                fetch_client_jwt(state_dir, selftest.TEST_INSTANCE_ID, server.origin, selftest.TEST_SESSION, urllib_transport)

    def test_cap_routes_missing_is_a_failed_read(self):
        with LoopbackServer((404, {"error": {"message": "not found"}})) as server:
            self.assertIsNone(read_own_caps(server.origin, TEST_CLIENT_JWT, urllib_transport))


@unittest.skipIf(BINDINGS is None, "the urnetwork package source is not importable")
class PackageSignatures(unittest.TestCase):
    """The functions and callback types the app uses exist in the package's
    bindings; SessionLifecycle checks every call's types against them."""

    def test_functions_exist(self):
        for name in (
            "urnet_set_log_dir",
            "urnet_new_network_space_manager_no_storage",
            "urnet_network_space_manager_update_network_space_values",
            "urnet_network_space_get_api",
            "urnet_api_set_by_jwt",
            "urnet_new_device_local_with_defaults",
            "urnet_device_add_jwt_refresh_listener",
            "urnet_device_add_auth_logout_listener",
            "urnet_device_add_contract_status_change_listener",
            "urnet_device_set_connect_location",
            "urnet_device_get_client_limit_status",
            "urnet_device_get_window_status",
            "urnet_sub_close",
            "urnet_release",
            "urnet_device_close",
            "urnet_network_space_manager_close",
            "urnet_free_string",
            "urnet_get_licenses",
        ):
            self.assertIn(name, BINDINGS.functions)
            self.assertIsNotNone(BINDINGS.functions[name].argtypes, name)
        for name in ("urnet_jwt_refresh_cb", "urnet_auth_logout_cb", "urnet_contract_status_change_cb"):
            self.assertTrue(hasattr(BINDINGS.callback_types, name), name)


if __name__ == "__main__":
    unittest.main()
