// Obtaining the installation's client JWT (EMBED_CONTRACT.md, "Obtaining the
// client JWT"). With a token server configured, fetchClientJwt posts this
// installation's instance id to your backend's token endpoint with the demo
// session and saves the scoped client JWT it answers as client.jwt; that one
// method is the place to put your own sign-in. Without one, the app uses the
// client.jwt that your backend tool's provision command (or you) wrote.
//
// The app never holds the root credential: the backend provisions the client
// and only the scoped client JWT reaches the installation.
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** The client JWT from the token server or the state directory. */
final class Token {
  static final String TOKEN_PATH = "/urnetwork/client-token";
  // the largest answer the app reads
  static final int RESPONSE_BYTE_LIMIT = 1 << 20;
  // the longest error message from the token server that the app shows
  private static final int MESSAGE_LIMIT = 300;

  /** Static members only. */
  private Token() {}

  /**
   * The installation's credential: the scoped client JWT, its client id and, from the token
   * server, a first cap reading (or null). The JWT is a bearer secret, so toString leaves it out.
   */
  record ClientCredential(String clientJwt, String clientId, Caps.DataCap firstCap) {
    /** The client id only. */
    @Override
    public String toString() {
      return "ClientCredential[clientId=" + clientId + "]";
    }
  }

  /**
   * The token server did not deliver a client JWT. exitCode is 78 when restarting does not help
   * (the session was refused, or a limit was hit; GUI apps show "signed out") and 1 for any other
   * failure (GUI apps show "stopped").
   */
  static final class TokenFetchException extends Exception {
    private static final long serialVersionUID = 1;
    private final int exitCode;

    /** A token fetch failure with its exit code and the reason to show. */
    TokenFetchException(int exitCode, String message) {
      super(message);
      this.exitCode = exitCode;
    }

    /** The process exit code. */
    int exitCode() { return exitCode; }

    /** The status that GUI and Android apps show for this failure. */
    String guiStatus() {
      return exitCode == Main.EXIT_CONFIG ? EmbedStatus.STATUS_SIGNED_OUT
                                          : EmbedStatus.STATUS_STOPPED;
    }
  }

  /** One HTTP exchange with a bounded answer; the self-test passes a stand-in. */
  @FunctionalInterface
  interface HttpTransport {
    /**
     * Sends method to uri with the bearer token and, when jsonBody is not null, a JSON body. The
     * answer's body is null when it is longer than {@link #RESPONSE_BYTE_LIMIT}.
     */
    HttpAnswer send(String method, URI uri, String bearer, String jsonBody)
        throws IOException, InterruptedException;

    /** The status and body of one answer. */
    record HttpAnswer(int status, String body) {}

    /** HTTPS through the JDK client: 15 seconds, no redirects, a bounded answer. */
    static HttpTransport jdk() {
      HttpClient client = HttpClient.newBuilder()
                              .connectTimeout(Duration.ofSeconds(15))
                              .followRedirects(HttpClient.Redirect.NEVER)
                              .build();
      return (method, uri, bearer, jsonBody) -> {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                                          .timeout(Duration.ofSeconds(15))
                                          .header("Authorization", "Bearer " + bearer);
        if (jsonBody != null) {
          request.header("Content-Type", "application/json")
              .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
          request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<InputStream> response =
            client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
          byte[] data = body.readNBytes(RESPONSE_BYTE_LIMIT + 1);
          return new HttpAnswer(response.statusCode(),
                                RESPONSE_BYTE_LIMIT < data.length
                                    ? null
                                    : new String(data, StandardCharsets.UTF_8));
        }
      };
    }
  }

  /** Whether text can be a demo session token: printable ASCII without whitespace. */
  static boolean isSessionToken(String text) {
    return text != null && !text.isEmpty() && text.chars().allMatch(c -> '!' <= c && c <= '~');
  }

  /**
   * The client JWT from the token server when one is configured, otherwise the client.jwt in the
   * state directory. A missing or invalid JWT, or a network JWT, is a configuration error.
   */
  static ClientCredential obtainClientJwt(InstallationState.EmbedSettings settings,
                                          HttpTransport transport)
      throws InstallationState.ConfigException, TokenFetchException {
    if (settings.tokenServer() != null) {
      return fetchClientJwt(settings.tokenServer(), settings.demoSession(), settings.instanceId(),
                            settings.stateDir(), transport);
    }
    String clientJwt = InstallationState.readClientJwt(settings.stateDir());
    if (clientJwt == null) {
      throw new InstallationState.ConfigException(
          "no token server is configured and the state directory has no client.jwt: set " +
          "URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION, or write client.jwt with your " +
          "backend tool's provision command");
    }
    return new ClientCredential(clientJwt, InstallationState.parseClientJwtClientId(clientJwt),
                                null);
  }

  /**
   * fetchClientJwt obtains this installation's client JWT from your backend: POST
   * /urnetwork/client-token with the demo session as the bearer token and {"installation_id":
   * instanceId}. It checks that the JWT's client_id claim equals the answer's client_id and saves
   * the JWT as client.jwt. A start fetches again, so every start reissues the client. Replace this
   * method with your own sign-in.
   */
  static ClientCredential fetchClientJwt(URI tokenServer, String demoSession, String instanceId,
                                         Path stateDir, HttpTransport transport)
      throws InstallationState.ConfigException, TokenFetchException {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("installation_id", instanceId);
    HttpTransport.HttpAnswer answer;
    try {
      answer = transport.send("POST", tokenServer.resolve(TOKEN_PATH), demoSession,
                              Json.write(request));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TokenFetchException(Main.EXIT_FAILURE, "the token fetch was interrupted");
    } catch (IOException | IllegalArgumentException e) {
      String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      throw new TokenFetchException(Main.EXIT_FAILURE,
                                    "could not reach the token server: " + printable(reason));
    }
    return answer(answer.status(), answer.body(), stateDir);
  }

  /**
   * Interprets the token server's answer: 200 saves and returns the credential; 401 and 409 are
   * configuration errors with the server's message; anything else is a failure.
   */
  static ClientCredential answer(int status, String body, Path stateDir)
      throws InstallationState.ConfigException, TokenFetchException {
    if (status == 200) {
      ClientCredential credential = parseToken(body);
      InstallationState.saveClientJwt(stateDir, credential.clientJwt());
      return credential;
    }
    String message = errorMessage(body);
    if (status == 401 || status == 409) {
      throw new TokenFetchException(
          Main.EXIT_CONFIG, "the token server refused this installation: " +
                                (message != null ? message : defaultMessage(status)));
    }
    throw new TokenFetchException(
        Main.EXIT_FAILURE, "the token server answered HTTP " + status +
                               (message != null ? ": " + message : ""));
  }

  /** The credential of a 200 answer; an invalid answer is a failure. */
  private static ClientCredential parseToken(String body) throws TokenFetchException {
    String invalid = "the token server answered an invalid token";
    Map<String, Object> members;
    String clientIdText;
    String clientJwtText;
    try {
      members = Json.parseObject(body == null ? "" : body);
      clientIdText = Json.string(members, "client_id");
      clientJwtText = Json.string(members, "by_client_jwt");
    } catch (Json.ParseException e) {
      throw new TokenFetchException(Main.EXIT_FAILURE, invalid);
    }
    String clientId = InstallationState.parseId(clientIdText);
    if (clientId == null || clientJwtText == null) {
      throw new TokenFetchException(Main.EXIT_FAILURE, invalid);
    }
    String clientJwt = clientJwtText.strip();
    String claim;
    try {
      claim = InstallationState.parseClientJwtClientId(clientJwt);
    } catch (InstallationState.ConfigException e) {
      throw new TokenFetchException(Main.EXIT_FAILURE,
                                    "the token server answered a JWT without a valid client_id claim");
    }
    if (!claim.equals(clientId)) {
      throw new TokenFetchException(Main.EXIT_FAILURE,
                                    "the token server's client_id does not match its client JWT");
    }
    Caps.DataCap firstCap;
    try {
      firstCap = Caps.DataCap.fromMembers(Json.object(members, "data_cap"));
    } catch (Json.ParseException e) {
      firstCap = null;
    }
    return new ClientCredential(clientJwt, clientId, firstCap);
  }

  /**
   * The message of an error answer {"error": {"code": ..., "message": ...}}, made safe to print;
   * null when there is none.
   */
  private static String errorMessage(String body) {
    if (body == null) {
      return null;
    }
    try {
      Map<String, Object> error = Json.object(Json.parseObject(body), "error");
      String message = error == null ? null : Json.string(error, "message");
      if (message == null || message.isBlank()) {
        return null;
      }
      return printable(message);
    } catch (Json.ParseException e) {
      return null;
    }
  }

  /** The reason for a 401 or 409 answer without a message. */
  private static String defaultMessage(int status) {
    return status == 401 ? "the demo session is not valid"
                         : "no client is available for this installation";
  }

  /** Text safe for one line: no control characters, bounded. */
  private static String printable(String text) {
    StringBuilder out = new StringBuilder();
    text.codePoints().limit(MESSAGE_LIMIT).forEach(
        c -> out.appendCodePoint(Character.isISOControl(c) ? ' ' : c));
    return out.toString().strip();
  }
}
