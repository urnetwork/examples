# frozen_string_literal: true

# Obtaining the client JWT (EMBED_CONTRACT.md, "Obtaining the client JWT").
#
# ClientToken.fetch_client_jwt is the one function to replace with your own
# sign-in. It posts the installation's instance-id to the token server's
# POST /urnetwork/client-token with the demo session as the bearer token,
# checks that the answer's by_client_jwt carries a client_id claim equal to
# its client_id, and saves the token as client.jwt. The app fetches at every
# start, so a start reissues the client. Plain Ruby apart from the transport
# it is given (transport.rb).

require "json"
require_relative "caps"
require_relative "state"

module Embed
  # The token server refused this installation: 401 unauthorized, or 409
  # installation_limit or client_limit. Restarting does not help (exit 78).
  class TokenServerRefused < StandardError; end

  # The token server could not be reached, failed, or answered something
  # invalid (exit 1).
  class TokenServerError < StandardError; end

  # A token server answer that passed its checks. data_cap is the client's cap
  # object, the app's first cap reading; nil when the token server could not
  # read it.
  FetchedClient = Struct.new(:client_jwt, :client_id, :data_cap, keyword_init: true)

  # The token server request.
  module ClientToken
    TOKEN_ROUTE = "/urnetwork/client-token"

    # the longest token server error message the app shows
    ERROR_MESSAGE_LIMIT = 300

    module_function

    # Obtains this installation's client JWT from the token server and saves
    # it as client.jwt. Raises TokenServerRefused or TokenServerError; the
    # message never carries the session or a token.
    def fetch_client_jwt(state_dir, instance_id, token_server_origin, demo_session, transport)
      body = JSON.generate({"installation_id" => instance_id})
      headers = {
        "Authorization" => "Bearer #{demo_session}",
        "Content-Type" => "application/json",
        "Accept" => "application/json",
      }
      begin
        status, answer = transport.call("POST", token_server_origin + TOKEN_ROUTE, headers, body)
      rescue TransportError => error
        raise TokenServerError, "could not reach the token server: #{error.message}"
      end
      message = error_message(answer)
      if [401, 409].include?(status)
        raise TokenServerRefused, message.empty? ? "the token server refused this installation (HTTP #{status})" : message
      end
      raise TokenServerError, message.empty? ? "the token server failed (HTTP #{status})" : message unless status == 200

      fields = Embed.parse_json_object(answer)
      raise TokenServerError, "the token server's answer is not a JSON object" if fields.nil?

      client_id = State.parse_uuid(fields["client_id"])
      client_jwt = fields["by_client_jwt"]
      if client_id.nil? || !client_jwt.is_a?(String) || client_jwt.empty?
        raise TokenServerError, "the token server's answer has no client_id or by_client_jwt"
      end

      begin
        claim_client_id = State.parse_client_jwt_client_id(client_jwt)
      rescue ConfigError
        raise TokenServerError, "the token server's by_client_jwt is not a scoped client JWT"
      end
      if claim_client_id != client_id
        raise TokenServerError, "the token server's client_id does not match the client_id claim of its client JWT"
      end

      begin
        State.save_client_jwt(state_dir, client_jwt)
      rescue SystemCallError => error
        raise TokenServerError, "save client.jwt: #{error.message}"
      end
      FetchedClient.new(client_jwt: client_jwt, client_id: client_id, data_cap: Caps.parse_cap_object(fields["data_cap"]))
    end

    # The error.message of a token server error answer on one bounded line,
    # or "".
    def error_message(answer)
      fields = Embed.parse_json_object(answer)
      error = fields && fields["error"]
      message = error.is_a?(Hash) ? error["message"] : nil
      return "" unless message.is_a?(String)

      message.split.join(" ")[0, ERROR_MESSAGE_LIMIT]
    end
  end
end
