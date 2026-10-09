"""Loads the urnetwork package. A native library older than the package's
bindings lacks a function that the bindings name, so loading the package
raises AttributeError with ctypes' "symbol not found" text. That is an SDK
version mismatch: a configuration problem that a restart does not fix, so it
exits with 78 and one line instead of a traceback. The socket and messages
programs call load_urnetwork_or_exit() before anything else loads the
package."""

import importlib
import re
import sys

EXIT_FAILURE = 1
# sysexits EX_CONFIG
EXIT_CONFIG = 78

# the C ABI's function names, as ctypes names a missing one on each platform
NATIVE_FUNCTION_PATTERN = re.compile(r"urnet_[A-Za-z0-9_]+")


class SdkLoadError(Exception):
    """The urnetwork package did not load: exit_code is 78 for an SDK version
    mismatch and 1 for any other failure."""

    def __init__(self, exit_code: int, message: str):
        super().__init__(message)
        self.exit_code = exit_code


def sdk_mismatch_message(error: BaseException) -> str | None:
    """The line for a native library older than the urnetwork package: ctypes
    raised AttributeError for a C ABI function that the library lacks. None for
    any other error."""
    if not isinstance(error, AttributeError):
        return None
    match = NATIVE_FUNCTION_PATTERN.search(str(error))
    if match is None:
        return None
    return (
        f"SDK version mismatch: the URnetwork native library has no {match.group(0)}, which the urnetwork "
        "package calls; install the native library of the package's SDK release (URNETWORK_SDK_LIBRARY, "
        "when set, must name a build of that release)"
    )


def load_urnetwork(importer=importlib.import_module):
    """The urnetwork package. Raises SdkLoadError."""
    try:
        return importer("urnetwork")
    except (ImportError, OSError, AttributeError) as error:
        mismatch = sdk_mismatch_message(error)
        if mismatch is not None:
            raise SdkLoadError(EXIT_CONFIG, mismatch) from None
        raise SdkLoadError(EXIT_FAILURE, f"could not load the urnetwork package: {error}") from None


def load_urnetwork_or_exit(importer=importlib.import_module):
    """The urnetwork package; when it does not load, prints why on one stderr line
    and exits: 78 for an SDK version mismatch, 1 otherwise."""
    try:
        return load_urnetwork(importer)
    except SdkLoadError as error:
        print(error, file=sys.stderr)
        sys.exit(error.exit_code)
