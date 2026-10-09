"""Tests for sdk_load.py: a native library older than the urnetwork package
makes the socket and messages programs exit 78 with the SDK version mismatch
line instead of a traceback. The stand-in package raises ctypes' own
AttributeError for a function the platform library lacks, so no SDK, network
or credentials are needed.

Run from this directory: python3 -m unittest -v test_sdk_load"""

import contextlib
import ctypes as C
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from sdk_load import EXIT_CONFIG, EXIT_FAILURE, SdkLoadError, load_urnetwork, load_urnetwork_or_exit, sdk_mismatch_message

# a urnetwork package whose bindings name a function that the native library
# lacks, as when the library is older than the package
STALE_PACKAGE = """
import ctypes
import os

_library = ctypes.WinDLL("kernel32") if os.name == "nt" else ctypes.CDLL(None)
_library.urnet_self_test_newer_function.restype = ctypes.c_void_p
"""

PYTHON = Path(__file__).resolve().parents[1]


def missing_function_error() -> AttributeError:
    """ctypes' own error for a C ABI function that the library lacks."""
    library = C.WinDLL("kernel32") if os.name == "nt" else C.CDLL(None)
    try:
        library.urnet_self_test_newer_function
    except AttributeError as error:
        return error
    raise AssertionError("the platform library has the test's function")


def raising(error):
    def importer(_name):
        raise error

    return importer


class SdkMismatch(unittest.TestCase):
    def test_a_missing_function_is_a_version_mismatch(self):
        message = sdk_mismatch_message(missing_function_error())
        self.assertIsNotNone(message)
        self.assertTrue(message.startswith("SDK version mismatch"))
        self.assertIn("urnet_self_test_newer_function", message)

    def test_other_errors_are_not(self):
        self.assertIsNone(sdk_mismatch_message(AttributeError("'NoneType' object has no attribute 'raw'")))
        self.assertIsNone(sdk_mismatch_message(OSError("dlopen failed: urnet_x")))
        self.assertIsNone(sdk_mismatch_message(ImportError("No module named 'urnetwork'")))

    def test_load_exit_codes(self):
        for error, exit_code in [
            (missing_function_error(), EXIT_CONFIG),
            (ImportError("No module named 'urnetwork'"), EXIT_FAILURE),
            (OSError("no library"), EXIT_FAILURE),
        ]:
            with self.subTest(error=error), self.assertRaises(SdkLoadError) as raised:
                load_urnetwork(raising(error))
            self.assertEqual(raised.exception.exit_code, exit_code)

    def test_load_or_exit_prints_one_line(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as raised:
            load_urnetwork_or_exit(raising(missing_function_error()))
        self.assertEqual(raised.exception.code, EXIT_CONFIG)
        self.assertEqual(len(stderr.getvalue().strip().splitlines()), 1)


class ProgramsWithAStaleLibrary(unittest.TestCase):
    """The socket and messages programs in child processes, with the stale
    package first on the import path."""

    def run_program(self, program: str, *args: str):
        with tempfile.TemporaryDirectory() as package_root:
            os.mkdir(os.path.join(package_root, "urnetwork"))
            Path(package_root, "urnetwork", "__init__.py").write_text(STALE_PACKAGE)
            environment = {name: value for name, value in os.environ.items() if not name.startswith("URNETWORK_")}
            environment.update(PYTHONPATH=package_root, PYTHONDONTWRITEBYTECODE="1")
            return subprocess.run(
                [sys.executable, str(PYTHON / program), *args], env=environment, capture_output=True, text=True, timeout=60
            )

    def test_socket_and_messages_exit_78(self):
        for program in ("socket/main.py", "messages/main.py"):
            with self.subTest(program=program):
                completed = self.run_program(program, "--version")
                self.assertEqual(completed.returncode, EXIT_CONFIG, completed.stderr)
                lines = completed.stderr.strip().splitlines()
                self.assertEqual(len(lines), 1, completed.stderr)
                self.assertTrue(lines[0].startswith("SDK version mismatch"), completed.stderr)
                self.assertIn("urnet_self_test_newer_function", lines[0])
                self.assertEqual(completed.stdout, "")


if __name__ == "__main__":
    unittest.main()
