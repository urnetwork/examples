"""A running embed app over the SDK's C ABI, through the urnetwork package
(ctypes): the network space manager, the embedded device, the listeners that
feed its status, and the reader of the client's own data caps
(EMBED_CONTRACT.md, "App lifecycle").

SDK callbacks run on SDK threads. They copy what they carry and hand it to the
run loop through the events queue. The cap reader makes its HTTP reads on a
thread of its own and hands each reading over the same queue. The run loop's
thread owns every other field and makes every other SDK call. The ctypes
callback objects stay referenced for the life of the process.

The urnetwork package is passed in rather than imported, so that the self-test
never loads the native runtime and the tests can run the lifecycle against a
stand-in for the C ABI."""

from contextlib import ExitStack
import ctypes as C
from dataclasses import dataclass
import os
import queue
import sys
import threading
import time

from caps import CapReading, EmbedNotEnabled, read_own_caps
from state import ConfigError, save_client_jwt
from status import (
    CapState,
    EmbedStatus,
    parse_client_limit_status,
    parse_providers_added,
    status_snapshot,
)

# How often the status is read, and the longest gap between status lines.
STATUS_POLL_INTERVAL_SECONDS = 1
STATUS_REPEAT_INTERVAL_SECONDS = 60

# How often the app reads its own caps. A contract status change reads them at
# once.
CAP_READ_INTERVAL_SECONDS = 5 * 60

# The device description and spec recorded for this installation's device.
DEVICE_DESCRIPTION = "Python embed example"
DEVICE_SPEC = "urnetwork-examples/python-embed"
APP_VERSION = "1"

# the ur.network main network space, as the integration helpers create it
NETWORK_SPACE_KEY_JSON = b'{"host_name":"ur.network","env_name":"main"}'
NETWORK_SPACE_VALUES_JSON = b'{"migration_host_name":"bringyour.com"}'

# the destination of the app's own traffic: the best available location
CONNECT_LOCATION_JSON = b'{"connect_location_id":{"best_available":true}}'

# run loop events, each a tuple that starts with one of these
EVENT_STOP = "stop"
EVENT_JWT_REFRESHED = "jwt refreshed"
EVENT_AUTH_LOGOUT = "auth logout"
EVENT_CONTRACT_STATUS_CHANGED = "contract status changed"
EVENT_CAPS_READ = "caps read"

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
    """Keeps the SDK's log files in log_dir, which the SDK bounds, instead of the
    system temp directory. The SDK also copies its log lines to stderr, and the
    C ABI keeps that copy. Raises OSError."""
    os.makedirs(log_dir, mode=0o700, exist_ok=True)
    error = C.c_void_p()
    if not sdk.raw.urnet_set_log_dir(log_dir.encode("utf-8"), C.byref(error)):
        raise OSError(take_string(sdk.raw, error.value) or "the sdk refused the log directory")


@dataclass(frozen=True)
class EmbedConfig:
    """What the session starts with."""

    state_dir: str
    client_jwt: str
    # the client_id claim of client_jwt
    client_id: str
    instance_id: str
    # the API origin for the cap reads (URNETWORK_API_URL)
    api_origin: str
    # the token server's data_cap, the first cap reading; None to read at start
    first_cap_reading: CapReading | None = None


class CapReader:
    """Reads the client's own caps on a thread of its own: at start unless a first
    reading is known, every five minutes, and at once when woken. Each reading,
    None for a failed read and EMBED_NOT_ENABLED for the Embed-not-enabled
    refusal, reaches the run loop as an EVENT_CAPS_READ event."""

    def __init__(self, read, events: queue.SimpleQueue, interval_seconds: float = CAP_READ_INTERVAL_SECONDS):
        """read() returns a CapReading, EMBED_NOT_ENABLED or None and may block on
        the network."""
        self._read = read
        self._events = events
        self._interval_seconds = interval_seconds
        self._wake = threading.Event()
        self._stopped = threading.Event()
        self._thread = None

    def start(self, read_now: bool):
        """Starts the reader; read_now reads at once instead of after one interval."""
        # a daemon thread: a read blocked on the network never holds up the exit
        self._thread = threading.Thread(target=self._loop, args=(read_now,), name="urnetwork-cap-reader", daemon=True)
        self._thread.start()

    def wake(self):
        """Reads at once, as after a contract status change."""
        self._wake.set()

    def stop(self):
        """Stops reading; a read in progress finishes on its own."""
        self._stopped.set()
        self._wake.set()

    def _loop(self, read_now: bool):
        delay = 0 if read_now else self._interval_seconds
        while not self._stopped.is_set():
            self._wake.wait(timeout=delay)
            self._wake.clear()
            if self._stopped.is_set():
                return
            try:
                reading = self._read()
            except Exception:
                # a failed read keeps the last value; the reader keeps running
                reading = None
            self._events.put((EVENT_CAPS_READ, reading))
            delay = self._interval_seconds


class EmbedSession:
    """One run of the embedded device. The constructor starts it; run shows the
    status until a stop request; close stops it."""

    def __init__(self, config: EmbedConfig, sdk, events: queue.SimpleQueue, transport, cap_reader_class=CapReader):
        """Creates the manager, the network space and the device with the
        installation's client JWT and instance ID, adds the listeners, sets the
        connect location to best available and starts reading the caps. sdk is
        the urnetwork package; events is the run loop's queue, which also carries
        stop requests; transport makes the cap reads (transport.py)."""
        self._config = config
        self._raw = sdk.raw
        self._events = events
        self._transport = transport
        self._cap_state = CapState(config.first_cap_reading)
        # the latest client JWT, which the SDK refreshes; read by the cap reader's thread
        self._client_jwt = config.client_jwt
        self._client_jwt_lock = threading.Lock()
        self._subs = []
        self._closed = False

        callback_types = sdk._raw
        self._jwt_refresh_callback = callback_types.urnet_jwt_refresh_cb(self._jwt_refreshed)
        self._auth_logout_callback = callback_types.urnet_auth_logout_cb(self._auth_logout)
        self._contract_status_callback = callback_types.urnet_contract_status_change_cb(self._contract_status_changed)
        _callback_roots.extend((self._jwt_refresh_callback, self._auth_logout_callback, self._contract_status_callback))

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
            device = self._device.handle
            self._subs = [
                raw.urnet_device_add_jwt_refresh_listener(device, self._jwt_refresh_callback, None),
                raw.urnet_device_add_auth_logout_listener(device, self._auth_logout_callback, None),
                raw.urnet_device_add_contract_status_change_listener(device, self._contract_status_callback, None),
            ]
            stack.callback(self._close_subs)
            raw.urnet_device_set_connect_location(device, CONNECT_LOCATION_JSON)
            # close unwinds this stack: the subscriptions, the device, then the manager
            self._close_stack = stack.pop_all()

        self._cap_reader = cap_reader_class(self._read_caps, events)
        self._cap_reader.start(read_now=config.first_cap_reading is None)

    def _new_device(self, space: int) -> int:
        """Creates the local device. An embed app leaves the provide mode at its
        default: it does not provide."""
        raw = self._raw
        config = self._config
        error = C.c_void_p()
        device = raw.urnet_new_device_local_with_defaults(
            space,
            config.client_jwt.encode(),
            DEVICE_DESCRIPTION.encode(),
            DEVICE_SPEC.encode(),
            APP_VERSION.encode(),
            config.instance_id.encode(),
            False,
            C.byref(error),
        )
        message = take_string(raw, error.value)
        if message:
            raise RuntimeError(message)
        if not device:
            raise RuntimeError("the sdk did not create the device")
        return device

    def status(self) -> EmbedStatus:
        """Reads the status from the device getters and the latest cap reading."""
        raw = self._raw
        device = self._device.handle
        # "client_limit_exceeded" with the hold's end in RetryTime while the
        # platform holds this client off for its network's client limit
        client_limit_status, client_limit_retry_time = parse_client_limit_status(
            take_string(raw, raw.urnet_device_get_client_limit_status(device))
        )
        providers_added = parse_providers_added(take_string(raw, raw.urnet_device_get_window_status(device)))
        return status_snapshot(
            True,
            False,
            client_limit_status,
            client_limit_retry_time,
            self._cap_state,
            providers_added,
        )

    def run(self):
        """Prints a status line when any field's text changes, and otherwise once a
        minute, until a stop request. Raises ConfigError when the server rejects
        the client credential."""
        last_line = None
        last_print_time = 0.0
        while True:
            line = self.status().line()
            now = time.monotonic()
            if line != last_line or STATUS_REPEAT_INTERVAL_SECONDS <= now - last_print_time:
                print(line)
                last_line = line
                last_print_time = now
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
            if self.apply_event(event):
                return True

    def apply_event(self, event: tuple) -> bool:
        """Applies one run loop event; True for a stop request. Raises ConfigError
        when the server rejected the client credential."""
        kind = event[0]
        if kind == EVENT_STOP:
            return True
        if kind == EVENT_AUTH_LOGOUT:
            raise ConfigError("the server rejected the client credential; sign in again so that your backend reissues the client")
        if kind == EVENT_JWT_REFRESHED:
            self._save_client_jwt(event[1])
        elif kind == EVENT_CONTRACT_STATUS_CHANGED:
            # a cap may have been reached or lifted
            self._cap_reader.wake()
        elif kind == EVENT_CAPS_READ:
            self._cap_state.record(event[1])
        return False

    def close(self):
        """Stops reading the caps, closes the subscriptions, the device and then the
        manager, and saves a refreshed credential that arrived meanwhile."""
        if self._closed:
            return
        self._closed = True
        self._cap_reader.stop()
        self._close_stack.close()
        # keep a refresh that arrived after the run loop's last read
        while True:
            try:
                event = self._events.get_nowait()
            except queue.Empty:
                break
            if event[0] == EVENT_JWT_REFRESHED:
                self._save_client_jwt(event[1])

    def _close_subs(self):
        """Closes and releases the listener subscriptions."""
        for sub in self._subs:
            self._raw.urnet_sub_close(sub)
            self._raw.urnet_release(sub)
        self._subs = []

    def _current_client_jwt(self) -> str:
        with self._client_jwt_lock:
            return self._client_jwt

    def _read_caps(self) -> CapReading | EmbedNotEnabled | None:
        """Reads this client's own caps with its latest client JWT. Runs on the cap
        reader's thread."""
        return read_own_caps(self._config.api_origin, self._current_client_jwt(), self._transport)

    def _save_client_jwt(self, client_jwt: bytes | None):
        """Keeps the refreshed credential for the cap reads and the next start."""
        if not client_jwt:
            return
        text = client_jwt.decode("utf-8", errors="replace").strip()
        with self._client_jwt_lock:
            self._client_jwt = text
        try:
            save_client_jwt(self._config.state_dir, text)
        except OSError as error:
            # never print the token itself
            print(f"could not save the refreshed client credential: {error}", file=sys.stderr)

    def _jwt_refreshed(self, _user_data, client_jwt):
        """Hands a refreshed client JWT to the run loop, which saves it. Runs on an
        SDK thread."""
        self._events.put((EVENT_JWT_REFRESHED, client_jwt))

    def _auth_logout(self, _user_data):
        """Ends the run: the server no longer accepts this client's credential. Runs
        on an SDK thread."""
        self._events.put((EVENT_AUTH_LOGOUT,))

    def _contract_status_changed(self, _user_data, _contract_status_json):
        """Asks for a cap read: a contract status change can mean a cap was reached.
        Runs on an SDK thread."""
        self._events.put((EVENT_CONTRACT_STATUS_CHANGED,))
