// Installation state for one provider install, kept in one private directory
// named by URNETWORK_PROVIDER_STATE_DIR (PROVIDER_CONTRACT.md, "Installation
// state"):
//
//   - client.jwt: the scoped client credential that the developer's backend
//     issued for this installation. The developer writes it; the app rewrites
//     it whenever the SDK refreshes the token.
//   - instance-id: this installation's UUID, created on first run.
//   - identity.json: the provider identity (client key seed, provide TLS
//     certificate and key, extender seed), created on first run so the
//     provider keeps one identity across restarts.
//
// Every file is replaced atomically with owner-only permissions. On POSIX the
// directory and its files must not be accessible to group or others, and a
// symlinked file is refused; Windows relies on the access control of the
// user's profile directory. This is managed code only: the self-test checks
// it without the native SDK runtime.
using System.Diagnostics.CodeAnalysis;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

/// A configuration or credential problem that restarting does not fix (exit
/// 78).
internal sealed class ProviderConfigException : Exception {
  /// A configuration error with the reason to show.
  public ProviderConfigException(string message) : base(message) {}
}

/// UUID text in the forms the SDK parses: 36 characters with dashes or 32 hex
/// digits, in either case. The canonical form is lowercase with dashes, as the
/// SDK writes ids.
internal static class Uuid {
  public const string Zero = "00000000-0000-0000-0000-000000000000";

  /// The canonical form, or null when the text is not a UUID.
  public static string? Parse(string? text) {
    if (text != null && (Guid.TryParseExact(text, "D", out var id) ||
                         Guid.TryParseExact(text, "N", out id))) {
      return id.ToString("D");
    }
    return null;
  }
}

/// identity.json. Byte fields are standard base64 in JSON. An identity belongs
/// to one client: a newly provisioned client gets a new identity.
internal sealed class ProviderIdentity {
  public const int CurrentVersion = 1;

  [JsonPropertyName("version")]
  public int Version { get; set; }
  [JsonPropertyName("client_id")]
  public string ClientId { get; set; } = "";
  [JsonPropertyName("client_key_seed")]
  public byte[]? ClientKeySeed { get; set; }
  [JsonPropertyName("provide_tls_certificate_pem")]
  public byte[]? ProvideTlsCertificatePem { get; set; }
  [JsonPropertyName("provide_tls_private_key_pem")]
  public byte[]? ProvideTlsPrivateKeyPem { get; set; }
  [JsonPropertyName("extender_key_seed")]
  [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
  public byte[]? ExtenderKeySeed { get; set; }
}

/// The installation state loaded at start.
internal sealed class ProviderConfig {
  public required string StateDir { get; init; }
  public required string ClientJwt { get; init; }
  // the client_id claim of ClientJwt
  public required string ClientId { get; init; }
  public required string InstanceId { get; init; }
  // null on first run, and when the stored identity belongs to another client
  public required ProviderIdentity? Identity { get; init; }

  /// Loads the installation state, creating instance-id on first run. Every
  /// error is a configuration error: restarting does not fix it.
  public static ProviderConfig Load(string? stateDir) {
    StateFiles.CheckStateDir(stateDir);
    byte[] clientJwtBytes =
        StateFiles.ReadStateFile(stateDir, StateFiles.ClientJwtFileName) ??
        throw new ProviderConfigException(
            $"read {StateFiles.ClientJwtFileName} from the state directory: the file does not exist; write the scoped client JWT from your backend");
    string clientJwt = StateFiles.DecodeText(clientJwtBytes).Trim();
    string clientId = StateFiles.ParseClientJwtClientId(clientJwt);
    string instanceId = StateFiles.LoadOrCreateInstanceId(stateDir);
    ProviderIdentity? identity = StateFiles.LoadProviderIdentity(stateDir, clientId);
    return new ProviderConfig {
      StateDir = stateDir,
      ClientJwt = clientJwt,
      ClientId = clientId,
      InstanceId = instanceId,
      Identity = identity,
    };
  }
}

/// The files of the state directory.
internal static class StateFiles {
  public const string ClientJwtFileName = "client.jwt";
  public const string InstanceIdFileName = "instance-id";
  public const string IdentityFileName = "identity.json";

  // the largest state file the app reads
  public const int StateFileByteLimit = 64 * 1024;

  // the permission bits that make a file or directory accessible to group or
  // others (0o077)
  public const UnixFileMode GroupOrOtherAccess =
      UnixFileMode.GroupRead | UnixFileMode.GroupWrite |
      UnixFileMode.GroupExecute | UnixFileMode.OtherRead |
      UnixFileMode.OtherWrite | UnixFileMode.OtherExecute;

  /// The state directory must be an existing absolute directory, private to
  /// its owner on POSIX. A symlinked directory is checked at its target.
  public static void CheckStateDir([NotNull] string? stateDir) {
    if (string.IsNullOrEmpty(stateDir)) {
      throw new ProviderConfigException(
          "set URNETWORK_PROVIDER_STATE_DIR to this installation's private state directory");
    }
    if (!Path.IsPathFullyQualified(stateDir)) {
      throw new ProviderConfigException(
          "URNETWORK_PROVIDER_STATE_DIR must be an absolute path");
    }
    if (!Directory.Exists(stateDir)) {
      throw new ProviderConfigException(
          File.Exists(stateDir)
              ? "URNETWORK_PROVIDER_STATE_DIR is not a directory"
              : $"state directory {stateDir} does not exist");
    }
    if (!OperatingSystem.IsWindows() &&
        (File.GetUnixFileMode(stateDir) & GroupOrOtherAccess) != 0) {
      throw new ProviderConfigException(
          "the state directory must be private to its owner (chmod 700)");
    }
  }

  /// Reads a regular, private state file of bounded size; null when it does
  /// not exist. A symlink is refused so that the credential cannot be
  /// redirected to another file. Other problems throw IOException.
  public static byte[]? ReadPrivateFile(string path) {
    // FileInfo reads the path itself: a dangling symlink exists and has a target
    var info = new FileInfo(path);
    if (info.LinkTarget == null && !info.Exists && !Directory.Exists(path)) {
      return null;
    }
    if (info.LinkTarget != null || !info.Exists) {
      throw new IOException("not a regular file");
    }
    if (StateFileByteLimit < info.Length) {
      throw new IOException("file is too large");
    }
    if (!OperatingSystem.IsWindows() &&
        (File.GetUnixFileMode(path) & GroupOrOtherAccess) != 0) {
      throw new IOException("file must be private to its owner (chmod 600)");
    }
    return File.ReadAllBytes(path);
  }

  /// Replaces a state file atomically: a private temporary file in the same
  /// directory is written, synced and renamed over the old file.
  public static void WritePrivateFile(string path, byte[] data) {
    string directory = Path.GetDirectoryName(path) ?? ".";
    string tempPath = Path.Combine(
        directory, "." + Path.GetFileName(path) + "." + Guid.NewGuid().ToString("N"));
    try {
      var options = new FileStreamOptions {
        Mode = FileMode.CreateNew,
        Access = FileAccess.Write,
        Share = FileShare.None,
      };
      if (!OperatingSystem.IsWindows()) {
        options.UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite;
      }
      using (var file = new FileStream(tempPath, options)) {
        file.Write(data);
        file.Flush(flushToDisk: true);
      }
      File.Move(tempPath, path, overwrite: true);
    } finally {
      if (File.Exists(tempPath)) {
        File.Delete(tempPath);
      }
    }
  }

  /// One file of the state directory, null when it does not exist. Every other
  /// problem is a configuration error that names the file.
  public static byte[]? ReadStateFile(string stateDir, string fileName) {
    try {
      return ReadPrivateFile(Path.Combine(stateDir, fileName));
    } catch (Exception e) when (e is IOException or UnauthorizedAccessException) {
      throw new ProviderConfigException(
          $"read {fileName} from the state directory: {e.Message}");
    }
  }

  /// The text of a state file: UTF-8, or the encoding its byte order mark names
  /// (Windows PowerShell 5 writes UTF-16 with one).
  public static string DecodeText(byte[] data) {
    using var reader = new StreamReader(new MemoryStream(data), Encoding.UTF8,
                                        detectEncodingFromByteOrderMarks: true);
    return reader.ReadToEnd();
  }

  /// The client_id claim of a scoped client JWT, in canonical form. This checks
  /// the token's shape and claim only; the SDK and the server verify the token
  /// itself.
  public static string ParseClientJwtClientId(string clientJwt) {
    const string notJwt =
        "client.jwt does not hold a JWT; write the scoped client JWT from your backend";
    string[] parts = clientJwt.Split('.');
    if (parts.Length != 3 || parts.Any(part => part.Length == 0)) {
      throw new ProviderConfigException(notJwt);
    }
    byte[] payload = DecodeBase64Url(parts[1].TrimEnd('=')) ??
                     throw new ProviderConfigException(notJwt);
    string claim = "";
    try {
      using var claims = JsonDocument.Parse(payload);
      if (claims.RootElement.ValueKind == JsonValueKind.Object &&
          claims.RootElement.TryGetProperty("client_id", out var clientId) &&
          clientId.ValueKind == JsonValueKind.String) {
        claim = clientId.GetString() ?? "";
      }
    } catch (Exception e) when (e is JsonException or ArgumentException or
                                    InvalidOperationException) {
      // not a JSON object: no claim
    }
    if (claim == "") {
      throw new ProviderConfigException(
          "client.jwt has no client_id claim; write a scoped client JWT, not a network JWT");
    }
    return Uuid.Parse(claim) ??
           throw new ProviderConfigException("client.jwt has an invalid client_id claim");
  }

  /// Unpadded base64url, as JWT parts are encoded; null when the text is not.
  private static byte[]? DecodeBase64Url(string text) {
    if (text.Length % 4 == 1 ||
        text.Any(c => !char.IsAsciiLetterOrDigit(c) && c != '-' && c != '_')) {
      return null;
    }
    string base64 = text.Replace('-', '+').Replace('_', '/') +
                    new string('=', (4 - text.Length % 4) % 4);
    try {
      return Convert.FromBase64String(base64);
    } catch (FormatException) {
      return null;
    }
  }

  /// Reads instance-id, creating it on first run. An installation keeps one
  /// instance id for its lifetime.
  public static string LoadOrCreateInstanceId(string stateDir) {
    byte[]? data = ReadStateFile(stateDir, InstanceIdFileName);
    if (data != null) {
      return Uuid.Parse(DecodeText(data).Trim()) ??
             throw new ProviderConfigException($"{InstanceIdFileName} does not hold a UUID");
    }
    string instanceId = Guid.NewGuid().ToString("D");
    try {
      WritePrivateFile(Path.Combine(stateDir, InstanceIdFileName),
                       Encoding.UTF8.GetBytes(instanceId + "\n"));
    } catch (Exception e) when (e is IOException or UnauthorizedAccessException) {
      throw new ProviderConfigException($"write {InstanceIdFileName}: {e.Message}");
    }
    return instanceId;
  }

  /// Reads identity.json for clientId. A missing file, or an identity of
  /// another client, returns null: the device then creates a new identity,
  /// which the app saves.
  public static ProviderIdentity? LoadProviderIdentity(string stateDir,
                                                       string clientId) {
    byte[]? data = ReadStateFile(stateDir, IdentityFileName);
    if (data == null) {
      return null;
    }
    ProviderIdentity? identity;
    try {
      identity = JsonSerializer.Deserialize<ProviderIdentity>(data);
    } catch (Exception e) when (e is JsonException or FormatException or
                                    InvalidOperationException) {
      identity = null;
    }
    if (identity == null || identity.Version != ProviderIdentity.CurrentVersion ||
        identity.ClientKeySeed?.Length != 32) {
      throw new ProviderConfigException(
          $"{IdentityFileName} is not a valid provider identity; remove it to create a new one");
    }
    if (identity.ClientId != clientId) {
      return null;
    }
    return identity;
  }

  /// Writes identity.json.
  public static void SaveProviderIdentity(string stateDir,
                                          ProviderIdentity identity) {
    WritePrivateFile(Path.Combine(stateDir, IdentityFileName),
                     JsonSerializer.SerializeToUtf8Bytes(identity));
  }
}
