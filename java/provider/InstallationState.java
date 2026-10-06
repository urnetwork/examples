// Installation state for one provider install, kept in one private directory
// named by URNETWORK_PROVIDER_STATE_DIR (PROVIDER_CONTRACT.md, "Installation
// state"):
//
// - client.jwt: the scoped client credential that the developer's backend
//   issued for this installation. The developer writes it; the app rewrites it
//   whenever the SDK refreshes the token.
// - instance-id: this installation's UUID, created on first run.
// - identity.json: the provider identity (client key seed, provide TLS
//   certificate and key, extender seed), created on first run so the provider
//   keeps one identity across restarts.
//
// Every file is replaced atomically with owner-only permissions. Where the
// file system has POSIX permissions, the directory and its files must not be
// accessible to group or others; Windows relies on the access control of the
// user's profile directory. Nothing here calls the SDK.
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Loads and saves the installation state. One process owns a state directory at a time. */
final class InstallationState {
  static final String CLIENT_JWT_FILE_NAME = "client.jwt";
  static final String INSTANCE_ID_FILE_NAME = "instance-id";
  static final String IDENTITY_FILE_NAME = "identity.json";
  // the sdk's bounded log files
  static final String LOG_DIR_NAME = "logs";
  // the largest state file the app reads
  static final int STATE_FILE_BYTE_LIMIT = 64 * 1024;
  static final int PROVIDER_IDENTITY_VERSION = 1;
  static final int CLIENT_KEY_SEED_BYTE_COUNT = 32;

  static final Set<PosixFilePermission> OWNER_READ_WRITE =
      PosixFilePermissions.fromString("rw-------");
  static final Set<PosixFilePermission> OWNER_ALL =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> GROUP_AND_OTHERS = EnumSet.of(
      PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
      PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
      PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);
  // the 32 hex digits of a uuid, ascii only
  private static final Pattern UUID_HEX = Pattern.compile("[0-9a-fA-F]{32}");

  /** Static members only. */
  private InstallationState() {}

  /** A configuration or credential problem that a restart does not fix (exit 78). */
  static final class ConfigException extends Exception {
    private static final long serialVersionUID = 1;

    /** A configuration error with the message the app prints. */
    ConfigException(String message) { super(message); }
  }

  /**
   * identity.json: the provider identity of one client. Byte fields are standard base64 in the
   * file. An identity belongs to one client: a newly provisioned client gets a new identity. Holds
   * private keys, so toString leaves them out.
   */
  record ProviderIdentity(String clientId, byte[] clientKeySeed,
                          byte[] provideTlsCertificatePem,
                          byte[] provideTlsPrivateKeyPem,
                          byte[] extenderKeySeed) {
    /** The client id only. */
    @Override
    public String toString() {
      return "ProviderIdentity[clientId=" + clientId + "]";
    }
  }

  /**
   * The installation state loaded at start. clientId is the client_id claim of clientJwt. identity
   * is null on first run and when the stored identity belongs to another client. The credential is a
   * bearer secret, so toString leaves it out.
   */
  record ProviderConfig(Path stateDir, String clientJwt, String clientId,
                        String instanceId, ProviderIdentity identity) {
    /** The state directory and ids only. */
    @Override
    public String toString() {
      return "ProviderConfig[stateDir=" + stateDir + ", clientId=" + clientId +
          ", instanceId=" + instanceId + "]";
    }
  }

  /**
   * Loads the installation state, creating instance-id on first run. Every error is a configuration
   * error: restarting does not fix it.
   */
  static ProviderConfig load(String stateDirText) throws ConfigException {
    Path stateDir = checkStateDir(stateDirText);
    byte[] clientJwtBytes;
    try {
      clientJwtBytes = readPrivateFile(stateDir.resolve(CLIENT_JWT_FILE_NAME));
    } catch (IOException e) {
      throw new ConfigException("read " + CLIENT_JWT_FILE_NAME +
                                " from the state directory: " + describe(e));
    }
    String clientJwt =
        new String(clientJwtBytes, StandardCharsets.UTF_8).strip();
    String clientId = parseClientJwtClientId(clientJwt);
    String instanceId = loadOrCreateInstanceId(stateDir);
    ProviderIdentity identity = loadProviderIdentity(stateDir, clientId);
    return new ProviderConfig(stateDir, clientJwt, clientId, instanceId,
                              identity);
  }

  /**
   * The state directory, which must be an existing absolute directory, private to its owner where
   * the file system has POSIX permissions.
   */
  static Path checkStateDir(String stateDirText) throws ConfigException {
    if (stateDirText == null || stateDirText.isEmpty()) {
      throw new ConfigException(
          "set URNETWORK_PROVIDER_STATE_DIR to this installation's private state directory");
    }
    Path stateDir;
    try {
      stateDir = Path.of(stateDirText);
    } catch (InvalidPathException e) {
      throw new ConfigException(
          "URNETWORK_PROVIDER_STATE_DIR is not a valid path");
    }
    if (!stateDir.isAbsolute()) {
      throw new ConfigException(
          "URNETWORK_PROVIDER_STATE_DIR must be an absolute path");
    }
    try {
      if (!Files.readAttributes(stateDir, BasicFileAttributes.class)
               .isDirectory()) {
        throw new ConfigException(
            "URNETWORK_PROVIDER_STATE_DIR is not a directory");
      }
      if (hasPosixPermissions(stateDir) &&
          !Collections.disjoint(Files.getPosixFilePermissions(stateDir),
                                GROUP_AND_OTHERS)) {
        throw new ConfigException(
            "the state directory must be private to its owner (chmod 700)");
      }
    } catch (IOException e) {
      throw new ConfigException("state directory: " + describe(e));
    }
    return stateDir;
  }

  /**
   * Reads a regular, private state file of bounded size. A symlink is refused so that the
   * credential cannot be redirected to another file.
   */
  static byte[] readPrivateFile(Path path) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(
        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile()) {
      throw new IOException("not a regular file: " + path);
    }
    if (STATE_FILE_BYTE_LIMIT < attributes.size()) {
      throw new IOException("file is too large: " + path);
    }
    if (hasPosixPermissions(path) &&
        !Collections.disjoint(
            Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS),
            GROUP_AND_OTHERS)) {
      throw new IOException("file must be private to its owner (chmod 600): " +
                            path);
    }
    // no-follow again at open, and a bounded read in case the file grew
    try (InputStream input =
             Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] data = input.readNBytes(STATE_FILE_BYTE_LIMIT + 1);
      if (STATE_FILE_BYTE_LIMIT < data.length) {
        throw new IOException("file is too large: " + path);
      }
      return data;
    }
  }

  /**
   * Replaces a state file atomically: a private temporary file in the same directory is written,
   * synced and renamed over the old file.
   */
  static void writePrivateFile(Path path, byte[] data) throws IOException {
    Path dir = path.toAbsolutePath().getParent();
    String prefix = "." + path.getFileName() + ".";
    boolean posix = hasPosixPermissions(dir);
    Path tempPath =
        posix ? Files.createTempFile(
                    dir, prefix, ".tmp",
                    PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE))
              : Files.createTempFile(dir, prefix, ".tmp");
    boolean replaced = false;
    try {
      if (posix) {
        // owner-only whatever the umask
        Files.setPosixFilePermissions(tempPath, OWNER_READ_WRITE);
      }
      try (FileChannel channel =
               FileChannel.open(tempPath, StandardOpenOption.WRITE,
                                StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      Files.move(tempPath, path, StandardCopyOption.ATOMIC_MOVE,
                 StandardCopyOption.REPLACE_EXISTING);
      replaced = true;
    } finally {
      if (!replaced) {
        try {
          Files.deleteIfExists(tempPath);
        } catch (IOException e) {
          // keep the original failure
        }
      }
    }
  }

  /** Creates a directory private to its owner, or keeps an existing one. */
  static void createPrivateDirectory(Path dir) throws IOException {
    if (Files.isDirectory(dir)) {
      return;
    }
    if (hasPosixPermissions(dir)) {
      Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(OWNER_ALL));
    } else {
      Files.createDirectory(dir);
    }
  }

  /** Whether the file system of path has POSIX permissions; Windows does not. */
  static boolean hasPosixPermissions(Path path) {
    return path.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  /**
   * The client_id claim of a scoped client JWT, in canonical form. This checks the token's shape
   * and claim only; the SDK and the server verify the token itself.
   */
  static String parseClientJwtClientId(String clientJwt)
      throws ConfigException {
    String notJwt =
        "client.jwt does not hold a JWT; write the scoped client JWT from your backend";
    String[] parts = clientJwt.split("\\.", -1);
    if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() ||
        parts[2].isEmpty()) {
      throw new ConfigException(notJwt);
    }
    byte[] payload;
    try {
      payload = Base64.getUrlDecoder().decode(stripPadding(parts[1]));
    } catch (IllegalArgumentException e) {
      throw new ConfigException(notJwt);
    }
    String clientIdClaim;
    try {
      clientIdClaim =
          Json.string(Json.parseObject(new String(payload, StandardCharsets.UTF_8)),
                      "client_id");
    } catch (Json.ParseException e) {
      // a payload that is not an object, or a client_id that is not a string
      clientIdClaim = null;
    }
    if (clientIdClaim == null || clientIdClaim.isEmpty()) {
      throw new ConfigException(
          "client.jwt has no client_id claim; write a scoped client JWT, not a network JWT");
    }
    String clientId = parseId(clientIdClaim);
    if (clientId == null) {
      throw new ConfigException("client.jwt has an invalid client_id claim");
    }
    return clientId;
  }

  /**
   * Reads instance-id, creating it on first run. An installation keeps one instance id for its
   * lifetime.
   */
  static String loadOrCreateInstanceId(Path stateDir) throws ConfigException {
    Path path = stateDir.resolve(INSTANCE_ID_FILE_NAME);
    try {
      String instanceId = parseId(
          new String(readPrivateFile(path), StandardCharsets.UTF_8).strip());
      if (instanceId == null) {
        throw new ConfigException(INSTANCE_ID_FILE_NAME + " does not hold a UUID");
      }
      return instanceId;
    } catch (NoSuchFileException e) {
      // first run
    } catch (IOException e) {
      throw new ConfigException("read " + INSTANCE_ID_FILE_NAME +
                                " from the state directory: " + describe(e));
    }
    String instanceId = UUID.randomUUID().toString();
    try {
      writePrivateFile(path,
                       (instanceId + "\n").getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new ConfigException("write " + INSTANCE_ID_FILE_NAME + ": " +
                                describe(e));
    }
    return instanceId;
  }

  /**
   * Reads identity.json for clientId. A missing file, or an identity of another client, returns
   * null: the device then creates a new identity, which the app saves. A file that does not parse,
   * has another version or a client key seed that is not 32 bytes is a configuration error.
   */
  static ProviderIdentity loadProviderIdentity(Path stateDir, String clientId)
      throws ConfigException {
    byte[] data;
    try {
      data = readPrivateFile(stateDir.resolve(IDENTITY_FILE_NAME));
    } catch (NoSuchFileException e) {
      return null;
    } catch (IOException e) {
      throw new ConfigException("read " + IDENTITY_FILE_NAME +
                                " from the state directory: " + describe(e));
    }
    ProviderIdentity identity;
    try {
      identity = parseProviderIdentity(new String(data, StandardCharsets.UTF_8));
    } catch (Json.ParseException | IllegalArgumentException e) {
      // not json, a field of the wrong type, or invalid base64
      identity = null;
    }
    if (identity == null) {
      throw new ConfigException(
          IDENTITY_FILE_NAME +
          " is not a valid provider identity; remove it to create a new one");
    }
    if (!identity.clientId().equals(clientId)) {
      return null;
    }
    return identity;
  }

  /** Writes identity.json. */
  static void saveProviderIdentity(Path stateDir, ProviderIdentity identity)
      throws IOException {
    Map<String, Object> members = new LinkedHashMap<>();
    members.put("version", PROVIDER_IDENTITY_VERSION);
    members.put("client_id", identity.clientId());
    members.put("client_key_seed", base64(identity.clientKeySeed()));
    members.put("provide_tls_certificate_pem",
                base64(identity.provideTlsCertificatePem()));
    members.put("provide_tls_private_key_pem",
                base64(identity.provideTlsPrivateKeyPem()));
    if (identity.extenderKeySeed() != null &&
        0 < identity.extenderKeySeed().length) {
      members.put("extender_key_seed", base64(identity.extenderKeySeed()));
    }
    writePrivateFile(stateDir.resolve(IDENTITY_FILE_NAME),
                     Json.write(members).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The canonical lowercase form of a UUID, given in its 36-character form or as 32 hex digits;
   * null when the text is not one.
   */
  static String parseId(String text) {
    String hex;
    if (text.length() == 36) {
      if (text.charAt(8) != '-' || text.charAt(13) != '-' ||
          text.charAt(18) != '-' || text.charAt(23) != '-') {
        return null;
      }
      hex = text.substring(0, 8) + text.substring(9, 13) +
          text.substring(14, 18) + text.substring(19, 23) + text.substring(24);
    } else if (text.length() == 32) {
      hex = text;
    } else {
      return null;
    }
    if (!UUID_HEX.matcher(hex).matches()) {
      return null;
    }
    hex = hex.toLowerCase(Locale.ROOT);
    return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" +
        hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" +
        hex.substring(20);
  }

  /** A short reason for a file error, without a stack trace. */
  static String describe(IOException e) {
    if (e instanceof NoSuchFileException) {
      return "no such file: " + e.getMessage();
    }
    if (e instanceof AccessDeniedException) {
      return "permission denied: " + e.getMessage();
    }
    // a FileSystemException message names the file and the reason
    String message = e.getMessage();
    return message != null ? message : e.getClass().getSimpleName();
  }

  /** identity.json's fields; null when the version or the client key seed is not valid. */
  private static ProviderIdentity parseProviderIdentity(String text)
      throws Json.ParseException {
    Map<String, Object> members = Json.parseObject(text);
    if (Json.longValue(members, "version", 0) != PROVIDER_IDENTITY_VERSION) {
      return null;
    }
    byte[] clientKeySeed = base64Member(members, "client_key_seed");
    if (clientKeySeed.length != CLIENT_KEY_SEED_BYTE_COUNT) {
      return null;
    }
    String clientId = Json.string(members, "client_id");
    return new ProviderIdentity(
        clientId == null ? "" : clientId, clientKeySeed,
        base64Member(members, "provide_tls_certificate_pem"),
        base64Member(members, "provide_tls_private_key_pem"),
        base64Member(members, "extender_key_seed"));
  }

  /** A standard base64 member as bytes; empty when it is absent or null. */
  private static byte[] base64Member(Map<String, Object> members, String name)
      throws Json.ParseException {
    String value = Json.string(members, name);
    if (value == null) {
      return new byte[0];
    }
    return Base64.getDecoder().decode(value);
  }

  /** Standard base64 of bytes; "" for none. */
  private static String base64(byte[] bytes) {
    return bytes == null ? "" : Base64.getEncoder().encodeToString(bytes);
  }

  /** The text without trailing base64 padding. */
  private static String stripPadding(String text) {
    int end = text.length();
    while (0 < end && text.charAt(end - 1) == '=') {
      end -= 1;
    }
    return text.substring(0, end);
  }
}
