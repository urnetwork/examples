// Obtaining the installation's client JWT (EMBED_CONTRACT.md, "Obtaining the
// client JWT"). With a token server configured, FetchClientJwt posts this
// installation's instance id to your backend's token endpoint with the demo
// session and saves the scoped client JWT it answers as client.jwt; that one
// function is the place to put your own sign-in. Without one, the app uses the
// client.jwt that your backend tool's provision command (or you) wrote.
//
// The app never holds the root credential: the backend provisions the client
// and only the scoped client JWT reaches the installation.
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

/// The installation's credential: the scoped client JWT, its client id and,
/// from the token server, a first cap reading.
internal sealed record ClientCredential(string ClientJwt, string ClientId, DataCap? FirstCap);

/// The token server did not deliver a client JWT. ExitCode is 78 when
/// restarting does not help (the session was refused, or a limit was hit;
/// GUI apps show "signed out") and 1 for any other failure (GUI apps show
/// "stopped").
internal sealed class TokenFetchException : Exception {
  public int ExitCode { get; }

  /// A token fetch failure with its exit code and the reason to show.
  public TokenFetchException(int exitCode, string message) : base(message) {
    ExitCode = exitCode;
  }

  /// The status that GUI and Android apps show for this failure.
  public string GuiStatus => ExitCode == Program.ExitConfig
                                 ? StatusRules.StatusSignedOut
                                 : StatusRules.StatusStopped;
}

/// Chooses where the client JWT comes from.
internal static class ClientJwtSource {
  /// The client JWT from the token server when one is configured, otherwise
  /// the client.jwt in the state directory. A missing or invalid JWT, or a
  /// network JWT, is a configuration error.
  public static ClientCredential ObtainClientJwt(EmbedSettings settings,
                                                 HttpMessageInvoker? invoker,
                                                 CancellationToken cancel) {
    if (settings.TokenServer != null) {
      return TokenServerClient.FetchClientJwt(settings.TokenServer, settings.DemoSession!,
                                              settings.InstanceId, settings.StateDir,
                                              invoker, cancel);
    }
    byte[] data = StateFiles.ReadStateFile(settings.StateDir, StateFiles.ClientJwtFileName) ??
                  throw new EmbedConfigException(
                      "no token server is configured and the state directory has no client.jwt: " +
                      "set URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION, or write client.jwt " +
                      "with your backend tool's provision command");
    string clientJwt = StateFiles.DecodeText(data).Trim();
    string clientId = StateFiles.ParseClientJwtClientId(clientJwt);
    return new ClientCredential(clientJwt, clientId, FirstCap: null);
  }
}

/// The token server's HTTP contract (EMBED_CONTRACT.md, "The token server").
internal static class TokenServerClient {
  public const string TokenPath = "/urnetwork/client-token";
  private const int ResponseByteLimit = 1 << 20;
  // the longest error message from the token server that the app shows
  private const int MessageLimit = 300;

  private static readonly HttpMessageInvoker SharedInvoker =
      new(new HttpClientHandler { AllowAutoRedirect = false });

  /// Whether text can be a demo session token: printable ASCII without
  /// whitespace, so that it fits an Authorization header.
  public static bool IsSessionToken(string? text) {
    return !string.IsNullOrEmpty(text) && text.All(c => '!' <= c && c <= '~');
  }

  /// FetchClientJwt obtains this installation's client JWT from your backend:
  /// POST /urnetwork/client-token with the demo session as the bearer token
  /// and {"installation_id": instanceId}. It checks that the JWT's client_id
  /// claim equals the answer's client_id and saves the JWT as client.jwt. A
  /// start fetches again, so every start reissues the client. Replace this
  /// function with your own sign-in.
  public static ClientCredential FetchClientJwt(Uri tokenServer, string demoSession,
                                                string instanceId, string stateDir,
                                                HttpMessageInvoker? invoker,
                                                CancellationToken cancel) {
    int status;
    string? body;
    try {
      (status, body) = PostAsync(tokenServer, demoSession, instanceId, invoker ?? SharedInvoker,
                                 cancel).GetAwaiter().GetResult();
    } catch (Exception e) when (e is HttpRequestException or OperationCanceledException or
                                    IOException or InvalidOperationException) {
      throw new TokenFetchException(Program.ExitFailure,
                                    $"could not reach the token server: {e.GetBaseException().Message}");
    }
    return Answer(status, body, stateDir);
  }

  /// The request, sent with a 15 second deadline; the status and the body
  /// (null when it is too long).
  private static async Task<(int Status, string? Body)> PostAsync(
      Uri tokenServer, string demoSession, string instanceId, HttpMessageInvoker invoker,
      CancellationToken cancel) {
    using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(tokenServer, TokenPath));
    request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", demoSession);
    request.Content = new StringContent(
        JsonSerializer.Serialize(new Dictionary<string, string> { ["installation_id"] = instanceId }),
        Encoding.UTF8, "application/json");
    using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancel);
    deadline.CancelAfter(TimeSpan.FromSeconds(15));
    using HttpResponseMessage response = await invoker.SendAsync(request, deadline.Token);
    string? body = await CapClient.ReadBoundedAsync(response.Content, ResponseByteLimit,
                                                    deadline.Token);
    return ((int)response.StatusCode, body);
  }

  /// Interprets the token server's answer: 200 saves and returns the
  /// credential; 401 and 409 are configuration errors with the server's
  /// message; anything else is a failure.
  public static ClientCredential Answer(int status, string? body, string stateDir) {
    if (status == 200) {
      ClientCredential credential = ParseToken(body);
      StateFiles.SaveClientJwt(stateDir, credential.ClientJwt);
      return credential;
    }
    string? message = ErrorMessage(body);
    if (status is 401 or 409) {
      throw new TokenFetchException(
          Program.ExitConfig,
          $"the token server refused this installation: {message ?? DefaultMessage(status)}");
    }
    throw new TokenFetchException(
        Program.ExitFailure,
        message == null ? $"the token server answered HTTP {status}"
                        : $"the token server answered HTTP {status}: {message}");
  }

  /// The credential of a 200 answer; an invalid answer is a failure.
  private static ClientCredential ParseToken(string? body) {
    const string invalid = "the token server answered an invalid token";
    try {
      using var document = JsonDocument.Parse(body ?? "");
      JsonElement root = document.RootElement;
      if (root.ValueKind != JsonValueKind.Object ||
          !root.TryGetProperty("client_id", out var clientIdValue) ||
          clientIdValue.ValueKind != JsonValueKind.String ||
          !root.TryGetProperty("by_client_jwt", out var jwtValue) ||
          jwtValue.ValueKind != JsonValueKind.String) {
        throw new TokenFetchException(Program.ExitFailure, invalid);
      }
      string clientJwt = (jwtValue.GetString() ?? "").Trim();
      string clientId = Uuid.Parse(clientIdValue.GetString()) ??
                        throw new TokenFetchException(Program.ExitFailure, invalid);
      string claim;
      try {
        claim = StateFiles.ParseClientJwtClientId(clientJwt);
      } catch (EmbedConfigException) {
        throw new TokenFetchException(Program.ExitFailure,
                                      "the token server answered a JWT without a valid client_id claim");
      }
      if (claim != clientId) {
        throw new TokenFetchException(
            Program.ExitFailure, "the token server's client_id does not match its client JWT");
      }
      DataCap? firstCap = null;
      if (root.TryGetProperty("data_cap", out var dataCap)) {
        firstCap = DataCap.FromElement(dataCap);
      }
      return new ClientCredential(clientJwt, clientId, firstCap);
    } catch (JsonException) {
      throw new TokenFetchException(Program.ExitFailure, invalid);
    }
  }

  /// The message of an error answer {"error": {"code": ..., "message": ...}},
  /// made safe to print; null when there is none.
  private static string? ErrorMessage(string? body) {
    if (body == null) {
      return null;
    }
    try {
      using var document = JsonDocument.Parse(body);
      if (document.RootElement.ValueKind == JsonValueKind.Object &&
          document.RootElement.TryGetProperty("error", out var error) &&
          error.ValueKind == JsonValueKind.Object &&
          error.TryGetProperty("message", out var message) &&
          message.ValueKind == JsonValueKind.String) {
        string text = new((message.GetString() ?? "")
                              .Select(c => char.IsControl(c) ? ' ' : c)
                              .Take(MessageLimit)
                              .ToArray());
        return text.Trim().Length == 0 ? null : text.Trim();
      }
    } catch (JsonException) {
      // not an error answer
    }
    return null;
  }

  /// The reason for a 401 or 409 answer without a message.
  private static string DefaultMessage(int status) {
    return status == 401 ? "the demo session is not valid"
                         : "no client is available for this installation";
  }
}
