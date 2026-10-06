"""Installation state for one provider install, kept in one private directory
named by URNETWORK_PROVIDER_STATE_DIR (PROVIDER_CONTRACT.md, "Installation
state"):

- client.jwt: the scoped client credential that the developer's backend issued
  for this installation. The developer writes it; the app rewrites it whenever
  the SDK refreshes the token.
- instance-id: this installation's UUID, created on first run.
- identity.json: the provider identity (client key seed, provide TLS
  certificate and key, extender seed), created on first run so the provider
  keeps one identity across restarts.

Every file is replaced atomically with owner-only permissions, and a symlinked
file is refused. On POSIX the directory and its files must not be accessible to
group or others; Windows relies on the access control of the user's profile
directory. Pure Python: the self-test runs it without the native SDK."""

import base64
from dataclasses import dataclass
import json
import os
import re
import secrets
import stat
import uuid

CLIENT_JWT_FILE_NAME = "client.jwt"
INSTANCE_ID_FILE_NAME = "instance-id"
IDENTITY_FILE_NAME = "identity.json"

# the largest state file the app reads
STATE_FILE_BYTE_LIMIT = 64 * 1024

PROVIDER_IDENTITY_VERSION = 1

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


@dataclass(frozen=True)
class ProviderIdentity:
    """identity.json. Byte fields are standard base64 in JSON. An identity belongs
    to one client: a newly provisioned client gets a new identity."""

    client_id: str
    client_key_seed: bytes
    provide_tls_certificate_pem: bytes
    provide_tls_private_key_pem: bytes
    extender_key_seed: bytes = b""


@dataclass(frozen=True)
class ProviderKeyMaterial:
    """The byte fields of the SDK's device key material: the client key seed, the
    provide TLS certificate and key in the order urnet_new_device_local_key_material
    takes them, and the extender seed for urnet_device_local_key_material_set_extender_key_seed."""

    client_key_seed: bytes
    provide_tls_certificate_pem: bytes
    provide_tls_private_key_pem: bytes
    extender_key_seed: bytes


@dataclass(frozen=True)
class ProviderConfig:
    """The installation state loaded at start."""

    state_dir: str
    client_jwt: str
    # the client_id claim of client_jwt
    client_id: str
    instance_id: str
    # None on first run, and when the stored identity belongs to another client
    identity: ProviderIdentity | None


def load_provider_config(state_dir: str) -> ProviderConfig:
    """Loads the installation state, creating instance-id on first run. Every error
    is a ConfigError: restarting does not fix it."""
    check_state_dir(state_dir)
    try:
        client_jwt_bytes = read_private_file(os.path.join(state_dir, CLIENT_JWT_FILE_NAME))
    except (OSError, StateFileError) as error:
        raise ConfigError(f"read {CLIENT_JWT_FILE_NAME} from the state directory: {error}") from None
    client_jwt = client_jwt_bytes.decode("utf-8", errors="replace").strip()
    client_id = parse_client_jwt_client_id(client_jwt)
    instance_id = load_or_create_instance_id(state_dir)
    identity = load_provider_identity(state_dir, client_id)
    return ProviderConfig(
        state_dir=state_dir,
        client_jwt=client_jwt,
        client_id=client_id,
        instance_id=instance_id,
        identity=identity,
    )


def check_state_dir(state_dir: str):
    """The state directory must be an existing absolute directory, private to its
    owner on POSIX."""
    if not state_dir:
        raise ConfigError("set URNETWORK_PROVIDER_STATE_DIR to this installation's private state directory")
    if not os.path.isabs(state_dir):
        raise ConfigError("URNETWORK_PROVIDER_STATE_DIR must be an absolute path")
    try:
        info = os.stat(state_dir)
    except OSError as error:
        raise ConfigError(f"state directory: {error}") from None
    if not stat.S_ISDIR(info.st_mode):
        raise ConfigError("URNETWORK_PROVIDER_STATE_DIR is not a directory")
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


def parse_uuid(text: str) -> str | None:
    """The canonical lowercase form of a UUID, or None when text is not one."""
    if not isinstance(text, str) or not UUID_PATTERN.match(text):
        return None
    return str(uuid.UUID(hex=text))


def parse_client_jwt_client_id(client_jwt: str) -> str:
    """The client_id claim of a scoped client JWT. This checks the token's shape and
    claim only; the SDK and the server verify the token itself."""
    not_jwt = ConfigError("client.jwt does not hold a JWT; write the scoped client JWT from your backend")
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
        raise ConfigError("client.jwt has no client_id claim; write a scoped client JWT, not a network JWT")
    client_id = parse_uuid(claims["client_id"])
    if client_id is None:
        raise ConfigError("client.jwt has an invalid client_id claim")
    return client_id


def load_or_create_instance_id(state_dir: str) -> str:
    """Reads instance-id, creating it on first run. An installation keeps one
    instance id for its lifetime."""
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


def load_provider_identity(state_dir: str, client_id: str) -> ProviderIdentity | None:
    """Reads identity.json for client_id. A missing file, or an identity of another
    client, returns None: the device then creates a new identity, which the app
    saves."""
    try:
        data = read_private_file(os.path.join(state_dir, IDENTITY_FILE_NAME))
    except FileNotFoundError:
        return None
    except (OSError, StateFileError) as error:
        raise ConfigError(f"read {IDENTITY_FILE_NAME} from the state directory: {error}") from None
    identity = parse_provider_identity(data)
    if identity is None:
        raise ConfigError(f"{IDENTITY_FILE_NAME} is not a valid provider identity; remove it to create a new one")
    if identity.client_id != client_id:
        return None
    return identity


def parse_provider_identity(data: bytes) -> ProviderIdentity | None:
    """The identity in identity.json, or None when the file does not parse, has
    another version or a client key seed that is not 32 bytes."""

    def decode_bytes(value) -> bytes | None:
        # json null and a missing field are empty, as in the go reference
        if value is None:
            return b""
        if not isinstance(value, str):
            return None
        try:
            return base64.b64decode(value, validate=True)
        except ValueError:
            return None

    try:
        fields = json.loads(data)
    except ValueError:
        return None
    if not isinstance(fields, dict):
        return None
    version = fields.get("version")
    if not isinstance(version, int) or isinstance(version, bool) or version != PROVIDER_IDENTITY_VERSION:
        return None
    # a null client id names no client, as in the go reference
    client_id = fields.get("client_id")
    if client_id is None:
        client_id = ""
    if not isinstance(client_id, str):
        return None
    byte_fields = [
        decode_bytes(fields.get(name))
        for name in (
            "client_key_seed",
            "provide_tls_certificate_pem",
            "provide_tls_private_key_pem",
            "extender_key_seed",
        )
    ]
    if any(value is None for value in byte_fields) or len(byte_fields[0]) != 32:
        return None
    return ProviderIdentity(
        client_id=client_id,
        client_key_seed=byte_fields[0],
        provide_tls_certificate_pem=byte_fields[1],
        provide_tls_private_key_pem=byte_fields[2],
        extender_key_seed=byte_fields[3],
    )


def save_provider_identity(state_dir: str, identity: ProviderIdentity):
    """Writes identity.json."""

    def encode_bytes(value: bytes) -> str:
        return base64.b64encode(value).decode("ascii")

    fields = {
        "version": PROVIDER_IDENTITY_VERSION,
        "client_id": identity.client_id,
        "client_key_seed": encode_bytes(identity.client_key_seed),
        "provide_tls_certificate_pem": encode_bytes(identity.provide_tls_certificate_pem),
        "provide_tls_private_key_pem": encode_bytes(identity.provide_tls_private_key_pem),
    }
    if identity.extender_key_seed:
        fields["extender_key_seed"] = encode_bytes(identity.extender_key_seed)
    write_private_file(
        os.path.join(state_dir, IDENTITY_FILE_NAME),
        json.dumps(fields, separators=(",", ":")).encode(),
    )


def provider_key_material(identity: ProviderIdentity | None) -> ProviderKeyMaterial | None:
    """The key material that recreates the device's identity. None without an
    identity (the first run, or another client's identity): the device then
    makes a new identity, which the app saves."""
    if identity is None:
        return None
    return ProviderKeyMaterial(
        client_key_seed=identity.client_key_seed,
        provide_tls_certificate_pem=identity.provide_tls_certificate_pem,
        provide_tls_private_key_pem=identity.provide_tls_private_key_pem,
        extender_key_seed=identity.extender_key_seed,
    )
