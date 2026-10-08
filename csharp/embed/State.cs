// Installation state for one embed installation, kept in one private directory
// named by URNETWORK_EMBED_STATE_DIR (EMBED_CONTRACT.md, "Installation
// state"):
//
//   - client.jwt: the installation's scoped client JWT. The token fetch writes
//     it (Token.cs), or your backend tool's provision command, or you; the app
//     rewrites it whenever the SDK refreshes the token.
//   - instance-id: this installation's UUID, created on first run and kept for
//     the life of the installation. It is also the installation ID that the
//     app sends to the token server.
//   - logs/: the SDK's bounded log files.
//
// Every file is replaced atomically with owner-only permissions. On POSIX the
// directory and its files must not be accessible to group or others, and a
// symlinked file is refused; Windows relies on the access control of the
// user's profile directory. This is managed code only: the self-test checks it
// without the native SDK runtime.
using System.Diagnostics.CodeAnalysis;
using System.Text;
using System.Text.Json;

/// A configuration or credential problem that restarting does not fix (exit
/// 78).
internal sealed class EmbedConfigException : Exception {
  /// A configuration error with the reason to show.
  public EmbedConfigException(string message) : base(message) {}
}

/// UUID text in the forms the SDK parses: 36 characters with dashes or 32 hex
/// digits, in either case. The canonical form is lowercase with dashes, as the
/// SDK writes ids.
internal static class Uuid {
  /// The canonical form, or null when the text is not a UUID.
  public static string? Parse(string? text) {
    if (text != null && (Guid.TryParseExact(text, "D", out var id) ||
                         Guid.TryParseExact(text, "N", out id))) {
      return id.ToString("D");
    }
    return null;
  }
}

/// The installation's settings, read once at start: the state directory, its
/// instance id, the token server (optional) and the API origin for the caps.
internal sealed class EmbedSettings {
  public const string DefaultApiUrl = "https://api.bringyour.com";

  public required string StateDir { get; init; }
  public required string InstanceId { get; init; }
  // the token server's origin; null when no token server is configured, and
  // the app then uses the client.jwt already in the state directory
  public Uri? TokenServer { get; init; }
  // the demo session token for the token server; a bearer secret, never
  // printed
  public string? DemoSession { get; init; }
  // the origin of the URnetwork API, where the app reads its own caps
  public required Uri ApiOrigin { get; init; }

  /// Checks the settings and loads the installation state, creating
  /// instance-id on first run. Every error is a configuration error:
  /// restarting does not fix it.
  public static EmbedSettings Load(string? stateDir, string? tokenServerUrl,
                                   string? demoSession, string? apiUrl) {
    StateFiles.CheckStateDir(stateDir);
    Uri apiOrigin = Origins.Parse(
        string.IsNullOrEmpty(apiUrl) ? DefaultApiUrl : apiUrl, "URNETWORK_API_URL");
    Uri? tokenServer = null;
    if (!string.IsNullOrEmpty(tokenServerUrl)) {
      tokenServer = Origins.Parse(tokenServerUrl, "URNETWORK_TOKEN_SERVER_URL");
      if (!TokenServerClient.IsSessionToken(demoSession)) {
        throw new EmbedConfigException(
            "set URNETWORK_DEMO_SESSION to the demo session token for the token server");
      }
    } else if (!string.IsNullOrEmpty(demoSession)) {
      throw new EmbedConfigException(
          "URNETWORK_DEMO_SESSION is set without URNETWORK_TOKEN_SERVER_URL");
    }
    string instanceId = StateFiles.LoadOrCreateInstanceId(stateDir);
    return new EmbedSettings {
      StateDir = stateDir,
      InstanceId = instanceId,
      TokenServer = tokenServer,
      DemoSession = tokenServer == null ? null : demoSession,
      ApiOrigin = apiOrigin,
    };
  }
}

/// Origins that the app sends credentials to: an HTTPS origin, or explicit
/// loopback HTTP (localhost, 127.0.0.1, [::1]) for local testing. No
/// credentials, path, query or fragment.
internal static class Origins {
  /// The origin as a base URI ending in "/"; name is the setting, for the
  /// error.
  public static Uri Parse(string value, string name) {
    if (!Uri.TryCreate(value, UriKind.Absolute, out Uri? uri)) {
      throw new EmbedConfigException($"{name} is not an absolute URL");
    }
    bool loopback = uri.Host is "localhost" or "127.0.0.1" or "[::1]" or "::1";
    if (uri.Host.Length == 0 || uri.UserInfo.Length != 0 ||
        uri.AbsolutePath != "/" || uri.Query.Length != 0 ||
        uri.Fragment.Length != 0 ||
        !(uri.Scheme == "https" || (uri.Scheme == "http" && loopback))) {
      throw new EmbedConfigException(
          $"{name} must be an HTTPS origin, or loopback HTTP (localhost, 127.0.0.1 or [::1]) for local testing");
    }
    return new Uri(uri.GetLeftPart(UriPartial.Authority) + "/");
  }
}

/// The files of the state directory.
internal static class StateFiles {
  public const string ClientJwtFileName = "client.jwt";
  public const string InstanceIdFileName = "instance-id";
  public const string LogDirName = "logs";

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
      throw new EmbedConfigException(
          "set URNETWORK_EMBED_STATE_DIR to this installation's private state directory");
    }
    if (!Path.IsPathFullyQualified(stateDir)) {
      throw new EmbedConfigException("URNETWORK_EMBED_STATE_DIR must be an absolute path");
    }
    if (!Directory.Exists(stateDir)) {
      throw new EmbedConfigException(
          File.Exists(stateDir)
              ? "URNETWORK_EMBED_STATE_DIR is not a directory"
              : $"state directory {stateDir} does not exist");
    }
    if (!OperatingSystem.IsWindows() &&
        (File.GetUnixFileMode(stateDir) & GroupOrOtherAccess) != 0) {
      throw new EmbedConfigException(
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
      throw new EmbedConfigException($"read {fileName} from the state directory: {e.Message}");
    }
  }

  /// Saves the client JWT as client.jwt. A failure is a configuration error:
  /// the state directory cannot hold the credential.
  public static void SaveClientJwt(string stateDir, string clientJwt) {
    try {
      WritePrivateFile(Path.Combine(stateDir, ClientJwtFileName),
                       Encoding.UTF8.GetBytes(clientJwt + "\n"));
    } catch (Exception e) when (e is IOException or UnauthorizedAccessException) {
      // never print the token itself
      throw new EmbedConfigException($"write {ClientJwtFileName}: {e.Message}");
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
  /// itself. A network JWT has no client_id claim and is refused.
  public static string ParseClientJwtClientId(string clientJwt) {
    const string notJwt =
        "client.jwt does not hold a JWT; obtain the scoped client JWT from your backend";
    string[] parts = clientJwt.Split('.');
    if (parts.Length != 3 || parts.Any(part => part.Length == 0)) {
      throw new EmbedConfigException(notJwt);
    }
    byte[] payload = DecodeBase64Url(parts[1].TrimEnd('=')) ??
                     throw new EmbedConfigException(notJwt);
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
      throw new EmbedConfigException(
          "the JWT has no client_id claim; use a scoped client JWT, not a network JWT");
    }
    return Uuid.Parse(claim) ??
           throw new EmbedConfigException("the JWT has an invalid client_id claim");
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
             throw new EmbedConfigException($"{InstanceIdFileName} does not hold a UUID");
    }
    string instanceId = Guid.NewGuid().ToString("D");
    try {
      WritePrivateFile(Path.Combine(stateDir, InstanceIdFileName),
                       Encoding.UTF8.GetBytes(instanceId + "\n"));
    } catch (Exception e) when (e is IOException or UnauthorizedAccessException) {
      throw new EmbedConfigException($"write {InstanceIdFileName}: {e.Message}");
    }
    return instanceId;
  }
}
