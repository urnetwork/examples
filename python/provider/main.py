"""The Python provider example: a console app for Windows, macOS and Linux that
runs a URnetwork provider for the developer's network and shows its status
(PROVIDER_CONTRACT.md). It provides publicly with the scoped client credential
that the developer's backend issued for this installation; the payout wallet is
mapped by the backend and is only displayed here.

Usage: main.py [run] | --self-test | --version. All installation state is in the
private directory named by URNETWORK_PROVIDER_STATE_DIR (state.py). The
self-test needs only Python; the other commands load the urnetwork package.

Exit codes, for supervisors: 0 stopped on request, 78 configuration or
credential problem (restarting does not help), 1 any other failure."""

import os
import queue
import signal
import sys

from state import ConfigError, load_provider_config
from status import CONSENT_DISCLAIMER

EXIT_STOPPED = 0
EXIT_FAILURE = 1
# sysexits EX_CONFIG
EXIT_CONFIG = 78

USAGE = "usage: main.py [run] | --self-test | --version"


def main():
    """Exits with the code of the command."""
    sys.stdout.reconfigure(line_buffering=True)
    try:
        exit_code = run(sys.argv[1:])
    except KeyboardInterrupt:
        # ctrl-c before the provider installed its own handler
        exit_code = EXIT_STOPPED
    sys.exit(exit_code)


def run(args: list) -> int:
    """Runs one command and returns the exit code."""
    if args == ["--self-test"]:
        from selftest import run_self_test

        try:
            run_self_test()
        except Exception as error:
            print(f"provider self-test failed: {error}", file=sys.stderr)
            return EXIT_FAILURE
        print("provider self-test passed")
        return EXIT_STOPPED
    if args == ["--version"]:
        try:
            import urnetwork
        except (ImportError, OSError) as error:
            print(f"could not load the urnetwork package: {error}", file=sys.stderr)
            return EXIT_FAILURE
        print(urnetwork.version())
        return EXIT_STOPPED
    if args not in ([], ["run"]):
        print(USAGE, file=sys.stderr)
        return EXIT_CONFIG

    print(CONSENT_DISCLAIMER)
    try:
        config = load_provider_config(os.environ.get("URNETWORK_PROVIDER_STATE_DIR", ""))
    except ConfigError as error:
        print(error, file=sys.stderr)
        return EXIT_CONFIG
    try:
        import urnetwork
    except (ImportError, OSError) as error:
        print(f"could not load the urnetwork package: {error}", file=sys.stderr)
        return EXIT_FAILURE
    from session import EVENT_STOP, ProviderSession, configure_sdk_logs

    try:
        configure_sdk_logs(urnetwork, os.path.join(config.state_dir, "logs"))
    except OSError as error:
        print(f"could not set the sdk log directory: {error}", file=sys.stderr)
        return EXIT_FAILURE

    # the run loop's events; a stop request is one of them. SimpleQueue.put is
    # safe in a signal handler, which can interrupt the main thread anywhere.
    events = queue.SimpleQueue()

    def request_stop(_signal_number, _frame):
        """Asks the run loop to stop providing; the app then exits with 0."""
        events.put((EVENT_STOP,))

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)
    try:
        session = ProviderSession(config, urnetwork, events)
    except Exception as error:
        print(f"could not start the provider: {error}", file=sys.stderr)
        return EXIT_FAILURE
    try:
        print(f"provider client {config.client_id}, instance {config.instance_id}")
        session.run()
        return EXIT_STOPPED
    except ConfigError as error:
        # the server rejected the client credential
        print(error, file=sys.stderr)
        return EXIT_CONFIG
    finally:
        session.close()


if __name__ == "__main__":
    main()
