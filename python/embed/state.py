"""Installation state for one embed installation, kept in one private directory
named by URNETWORK_EMBED_STATE_DIR (EMBED_CONTRACT.md, "Installation state"):

- client.jwt: the scoped client JWT. With a token server configured the app
  fetches and writes it at every start; otherwise a backend tool's `provision`
  or the developer writes it. The app rewrites it whenever the SDK refreshes
  the token. It is a bearer secret: never printed or logged.
- instance-id: this installation's UUID, created on first run and kept for the
  life of the installation. It is also the installation ID that the app sends
  to the token server.
- logs/: the SDK's bounded log files.

Every file is replaced atomically with owner-only permissions, and a symlinked
file is refused. On POSIX the directory and its files must not be accessible to
group or others; Windows relies on the access control of the user's profile
directory (keep the directory under %LOCALAPPDATA%). Pure Python: the self-test
runs it without the native SDK."""

import base64
import json
import os
import re
import secrets
import stat
import uuid

STATE_DIR_SETTING = "URNETWORK_EMBED_STATE_DIR"

CLIENT_JWT_FILE_NAME = "client.jwt"
INSTANCE_ID_FILE_NAME = "instance-id"
LOG_DIR_NAME = "logs"

# the largest state file the app reads
STATE_FILE_BYTE_LIMIT = 64 * 1024

# a uuid in its canonical 8-4-4-4-12 form, or as 32 hex digits
UUID_PATTERN = re.compile(r"\A(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{32})\Z")

# unpadded base64url, the encoding of a JWT segment
BASE64URL_PATTERN = re.compile(r"\A[A-Za-z0-9_-]*\Z")

POSIX = os.name != "nt"


class ConfigError(Exception):
    """A configuration or credential problem that restarting does not fix (exit 78)."""


class StateFileError(Exception):
    """A state file that the app must not read: not a regular file, too large or
    accessible to others."""


def check_state_dir(state_dir: str):
    """The state directory must be an existing absolute directory, private to its
    owner on POSIX."""
    if not state_dir:
        raise ConfigError(f"set {STATE_DIR_SETTING} to this installation's private state directory")
    if not os.path.isabs(state_dir):
        raise ConfigError(f"{STATE_DIR_SETTING} must be an absolute path")
    try:
        info = os.stat(state_dir)
    except OSError as error:
        raise ConfigError(f"state directory: {error}") from None
    if not stat.S_ISDIR(info.st_mode):
        raise ConfigError(f"{STATE_DIR_SETTING} is not a directory")
    if POSIX and info.st_mode & 0o077:
        raise ConfigError("the state directory must be private to its owner (chmod 700)")


def read_private_file(path: str) -> bytes:
    """Reads a regular, private state file of bounded size. A symlink is refused so
    that the credential cannot be redirected to another file. Raises
    FileNotFoundError when the file does not exist."""
    info = os.lstat(path)
    if not stat.S_ISREG(info.st_mode):
        raise StateFileError("not a regular file")
    if STATE_FILE_BYTE_LIMIT < info.st_size:
        raise StateFileError("file is too large")
    if POSIX and info.st_mode & 0o077:
        raise StateFileError("file must be private to its owner (chmod 600)")
    # no-follow closes the window between the check and the open where POSIX has it
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0))
    with os.fdopen(fd, "rb") as file:
        data = file.read(STATE_FILE_BYTE_LIMIT + 1)
    if STATE_FILE_BYTE_LIMIT < len(data):
        raise StateFileError("file is too large")
    return data


def write_private_file(path: str, data: bytes):
    """Replaces a state file atomically: a private temporary file in the same
    directory is written, synced and renamed over the old file."""
    temp_path = os.path.join(os.path.dirname(path), f".{os.path.basename(path)}.{secrets.token_hex(8)}")
    fd = os.open(temp_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0), 0o600)
    try:
        with os.fdopen(fd, "wb") as file:
            if POSIX:
                # the umask can only narrow 0o600; this keeps it exact
                os.fchmod(file.fileno(), 0o600)
            file.write(data)
            file.flush()
            os.fsync(file.fileno())
        os.replace(temp_path, path)
    except BaseException:
        try:
            os.remove(temp_path)
        except OSError:
            pass
        raise


def parse_uuid(text) -> str | None:
    """The canonical lowercase form of a UUID, or None when text is not one."""
    if not isinstance(text, str) or not UUID_PATTERN.match(text):
        return None
    return str(uuid.UUID(hex=text))


def parse_client_jwt_client_id(client_jwt: str) -> str:
    """The client_id claim of a scoped client JWT, in canonical form. This checks
    the token's shape and claim only; the SDK and the server verify the token
    itself. A network JWT has no client_id claim and is refused."""
    not_jwt = ConfigError("client.jwt does not hold a JWT; fetch one from your token server or write the scoped client JWT from your backend")
    parts = client_jwt.split(".")
    if len(parts) != 3 or not all(parts):
        raise not_jwt
    payload_text = parts[1].rstrip("=")
    if not BASE64URL_PATTERN.match(payload_text) or len(payload_text) % 4 == 1:
        raise not_jwt
    payload = base64.urlsafe_b64decode(payload_text + "=" * (-len(payload_text) % 4))
    try:
        claims = json.loads(payload)
    except ValueError:
        claims = None
    if not isinstance(claims, dict) or not isinstance(claims.get("client_id"), str) or not claims["client_id"]:
        raise ConfigError("client.jwt has no client_id claim; use a scoped client JWT, not a network JWT")
    client_id = parse_uuid(claims["client_id"])
    if client_id is None:
        raise ConfigError("client.jwt has an invalid client_id claim")
    return client_id


def load_or_create_instance_id(state_dir: str) -> str:
    """Reads instance-id, creating it on first run. An installation keeps one
    instance id for its lifetime; it is also its installation ID."""
    path = os.path.join(state_dir, INSTANCE_ID_FILE_NAME)
    try:
        data = read_private_file(path)
    except FileNotFoundError:
        data = None
    except (OSError, StateFileError) as error:
        raise ConfigError(f"read {INSTANCE_ID_FILE_NAME} from the state directory: {error}") from None
    if data is not None:
        instance_id = parse_uuid(data.decode("utf-8", errors="replace").strip())
        if instance_id is None:
            raise ConfigError(f"{INSTANCE_ID_FILE_NAME} does not hold a UUID")
        return instance_id
    instance_id = str(uuid.uuid4())
    try:
        write_private_file(path, (instance_id + "\n").encode())
    except OSError as error:
        raise ConfigError(f"write {INSTANCE_ID_FILE_NAME}: {error}") from None
    return instance_id


def load_client_jwt(state_dir: str) -> str | None:
    """The client JWT in client.jwt with surrounding whitespace removed, or None
    when the file does not exist."""
    try:
        data = read_private_file(os.path.join(state_dir, CLIENT_JWT_FILE_NAME))
    except FileNotFoundError:
        return None
    except (OSError, StateFileError) as error:
        raise ConfigError(f"read {CLIENT_JWT_FILE_NAME} from the state directory: {error}") from None
    return data.decode("utf-8", errors="replace").strip()


def save_client_jwt(state_dir: str, client_jwt: str):
    """Writes client.jwt atomically. Raises OSError."""
    write_private_file(os.path.join(state_dir, CLIENT_JWT_FILE_NAME), (client_jwt + "\n").encode())
