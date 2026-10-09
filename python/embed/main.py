"""The Python embed example: a console app for Windows, macOS and Linux that
embeds the URnetwork SDK in your own product (EMBED_CONTRACT.md). It obtains
this installation's scoped client JWT from your token server (or uses the one
your backend wrote), starts a local device with it, and shows the status and
this client's own data caps. The device carries only the app's own traffic:
continue with the Sockets and Messages examples.

Usage: main.py [run] | --self-test | --licenses | --version. --licenses prints
the SDK's licenses and data attributions, as JSON, to publish with the app.

Settings:
- URNETWORK_EMBED_STATE_DIR: the installation's private state directory
  (state.py), absolute. Required.
- URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION: the token server
  origin and the demo session token. Optional, as a pair; without them the app
  uses the client.jwt already in the state directory.
- URNETWORK_API_URL: the API origin for the cap reads, default
  https://api.bringyour.com.

The self-test needs only Python; the other commands load the urnetwork package,
and a native library older than the package exits 78 (sdk_load.py).

Exit codes, for supervisors: 0 stopped on request, 78 configuration or
credential problem (restarting does not help), 1 any other failure."""

import os
import platform
import queue
import signal
import sys

from client_token import TokenServerError, TokenServerRefused, fetch_client_jwt
from sdk_load import SdkLoadError, load_urnetwork, sdk_mismatch_message
from state import (
    LOG_DIR_NAME,
    ConfigError,
    check_state_dir,
    load_client_jwt,
    load_or_create_instance_id,
    parse_client_jwt_client_id,
)
from status import license_app, start_line
from transport import DEFAULT_API_URL, check_origin, urllib_transport

EXIT_STOPPED = 0
EXIT_FAILURE = 1
# sysexits EX_CONFIG
EXIT_CONFIG = 78

USAGE = "usage: main.py [run] | --self-test | --licenses | --version"

TOKEN_SERVER_URL_SETTING = "URNETWORK_TOKEN_SERVER_URL"
DEMO_SESSION_SETTING = "URNETWORK_DEMO_SESSION"
API_URL_SETTING = "URNETWORK_API_URL"


def main():
    """Exits with the code of the command."""
    sys.stdout.reconfigure(line_buffering=True)
    try:
        exit_code = run(sys.argv[1:])
    except KeyboardInterrupt:
        # ctrl-c before the app installed its own handler
        exit_code = EXIT_STOPPED
    sys.exit(exit_code)


def run(args: list, transport=urllib_transport) -> int:
    """Runs one command and returns the exit code. transport makes the token server
    and cap requests; the self-test passes a stand-in."""
    if args == ["--self-test"]:
        from selftest import run_self_test

        try:
            run_self_test()
        except Exception as error:
            print(f"embed self-test failed: {error}", file=sys.stderr)
            return EXIT_FAILURE
        print("embed self-test passed")
        return EXIT_STOPPED
    if args in (["--version"], ["--licenses"]):
        try:
            urnetwork = load_urnetwork()
        except SdkLoadError as error:
            print(error, file=sys.stderr)
            return error.exit_code
        if args == ["--version"]:
            print(urnetwork.version())
            return EXIT_STOPPED
        from session import take_string

        try:
            licenses = take_string(urnetwork.raw, urnetwork.raw.urnet_get_licenses(license_app(platform.system()).encode()))
        except AttributeError as error:
            return report_sdk_failure(error, "could not read the sdk licenses")
        if licenses is None:
            print("the sdk returned no licenses", file=sys.stderr)
            return EXIT_FAILURE
        print(licenses)
        return EXIT_STOPPED
    if args not in ([], ["run"]):
        print(USAGE, file=sys.stderr)
        return EXIT_CONFIG
    return run_embed(transport)


def run_embed(transport) -> int:
    """Obtains the client JWT, runs the device until a stop request and returns the
    exit code."""
    state_dir = os.environ.get("URNETWORK_EMBED_STATE_DIR", "")
    try:
        check_state_dir(state_dir)
        instance_id = load_or_create_instance_id(state_dir)
        api_origin = check_origin(os.environ.get(API_URL_SETTING) or DEFAULT_API_URL, API_URL_SETTING)
        client_jwt, first_cap_reading = obtain_client_jwt(state_dir, instance_id, transport)
        client_id = parse_client_jwt_client_id(client_jwt)
    except (ConfigError, TokenServerRefused) as error:
        print(error, file=sys.stderr)
        return EXIT_CONFIG
    except TokenServerError as error:
        print(error, file=sys.stderr)
        return EXIT_FAILURE

    try:
        urnetwork = load_urnetwork()
    except SdkLoadError as error:
        print(error, file=sys.stderr)
        return error.exit_code
    from session import EVENT_STOP, EmbedConfig, EmbedSession, configure_sdk_logs

    try:
        configure_sdk_logs(urnetwork, os.path.join(state_dir, LOG_DIR_NAME))
    except (OSError, AttributeError) as error:
        return report_sdk_failure(error, "could not set the sdk log directory")

    # the run loop's events; a stop request is one of them. SimpleQueue.put is
    # safe in a signal handler, which can interrupt the main thread anywhere.
    events = queue.SimpleQueue()

    def request_stop(_signal_number, _frame):
        """Asks the run loop to stop; the app then exits with 0."""
        events.put((EVENT_STOP,))

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)
    config = EmbedConfig(
        state_dir=state_dir,
        client_jwt=client_jwt,
        client_id=client_id,
        instance_id=instance_id,
        api_origin=api_origin,
        first_cap_reading=first_cap_reading,
    )
    try:
        session = EmbedSession(config, urnetwork, events, transport)
    except Exception as error:
        return report_sdk_failure(error, "could not start the device")
    try:
        print(start_line(client_id, instance_id))
        session.run()
        return EXIT_STOPPED
    except ConfigError as error:
        # the server rejected the client credential
        print(error, file=sys.stderr)
        return EXIT_CONFIG
    finally:
        session.close()


def report_sdk_failure(error: Exception, doing: str) -> int:
    """Prints why an SDK call failed and returns the exit code: 78 with the SDK
    version mismatch line for a C ABI function that the native library lacks, 1
    otherwise."""
    mismatch = sdk_mismatch_message(error)
    if mismatch is not None:
        print(mismatch, file=sys.stderr)
        return EXIT_CONFIG
    print(f"{doing}: {error}", file=sys.stderr)
    return EXIT_FAILURE


def obtain_client_jwt(state_dir: str, instance_id: str, transport):
    """The client JWT and the first cap reading (None to read at start): from the
    token server when one is configured, otherwise the client.jwt in the state
    directory. Raises ConfigError, TokenServerRefused or TokenServerError."""
    token_server_url = os.environ.get(TOKEN_SERVER_URL_SETTING, "")
    demo_session = os.environ.get(DEMO_SESSION_SETTING, "")
    if token_server_url or demo_session:
        if not token_server_url or not demo_session:
            raise ConfigError(f"set both {TOKEN_SERVER_URL_SETTING} and {DEMO_SESSION_SETTING}, or neither")
        if any(character.isspace() or not character.isprintable() for character in demo_session):
            raise ConfigError(f"{DEMO_SESSION_SETTING} must be one printable token without spaces")
        token_server_origin = check_origin(token_server_url, TOKEN_SERVER_URL_SETTING)
        fetched = fetch_client_jwt(state_dir, instance_id, token_server_origin, demo_session, transport)
        return fetched.client_jwt, fetched.data_cap
    client_jwt = load_client_jwt(state_dir)
    if not client_jwt:
        raise ConfigError(
            f"no token server and no client.jwt: set {TOKEN_SERVER_URL_SETTING} and {DEMO_SESSION_SETTING}, "
            "or write client.jwt with your backend tool's provision"
        )
    return client_jwt, None


if __name__ == "__main__":
    main()
