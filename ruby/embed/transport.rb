# frozen_string_literal: true

# HTTP for the embed app: the origin rule shared by the token server URL and
# the API URL, and one small JSON client over Net::HTTP, which never follows a
# redirect, with a bounded answer. The app passes a transport around as a
# callable, so the self-test runs the request and answer handling against a
# stand-in without a socket.
#
# A transport is called as transport.call(method, url, headers, body) and
# returns [status, answer], also for an HTTP error status. It raises
# Embed::TransportError when the server cannot be reached.

require "net/http"
require "openssl"
require "uri"
require_relative "caps"
require_relative "state"

module Embed
  # The origin rule and the Net::HTTP transport.
  module Transport
    DEFAULT_API_URL = "https://api.bringyour.com"

    # the largest answer the app reads
    ANSWER_BYTE_LIMIT = 1024 * 1024

    # seconds for one request
    REQUEST_TIMEOUT_SECONDS = 15

    LOOPBACK_HOSTS = ["localhost", "127.0.0.1", "::1"].freeze

    # scheme://authority, optionally with one trailing slash
    ORIGIN_PATTERN = %r{\A(https?)://([^/?#@]+)/?\z}.freeze

    # what Net::HTTP raises when a server cannot be reached
    UNREACHABLE_ERRORS = [
      SystemCallError, IOError, SocketError, EOFError, Timeout::Error,
      OpenSSL::SSL::SSLError, Net::ProtocolError,
    ].freeze

    module_function

    # The origin of url, without a trailing slash: an HTTPS origin, or
    # explicit loopback HTTP (localhost, 127.0.0.1, [::1]) for local testing,
    # with no credentials, path, query or fragment. Raises ConfigError naming
    # the setting.
    def check_origin(url, setting)
      refused = "#{setting} must be an HTTPS origin; HTTP is allowed only for localhost, 127.0.0.1 or [::1]"
      match = url.is_a?(String) ? ORIGIN_PATTERN.match(url) : nil
      raise ConfigError, refused if match.nil?

      begin
        uri = URI.parse(url)
      rescue URI::InvalidURIError
        raise ConfigError, "#{setting} is not a valid URL"
      end
      host = uri.hostname
      raise ConfigError, refused if host.nil? || host.empty? || !uri.userinfo.nil?
      raise ConfigError, refused unless uri.scheme == "https" || (uri.scheme == "http" && LOOPBACK_HOSTS.include?(host))

      "#{match[1]}://#{match[2]}"
    end

    # Sends one request and returns [status, answer]. Raises TransportError
    # when the server cannot be reached or the answer is too large.
    def net_http(method, url, headers, body)
      uri = URI(url)
      request = method == "POST" ? Net::HTTP::Post.new(uri) : Net::HTTP::Get.new(uri)
      headers.each { |name, value| request[name] = value }
      request.body = body unless body.nil?
      status = nil
      answer = +""
      Net::HTTP.start(
        uri.hostname, uri.port,
        use_ssl: uri.scheme == "https",
        open_timeout: REQUEST_TIMEOUT_SECONDS, read_timeout: REQUEST_TIMEOUT_SECONDS, write_timeout: REQUEST_TIMEOUT_SECONDS,
      ) do |http|
        http.request(request) do |response|
          status = response.code.to_i
          response.read_body do |chunk|
            raise TransportError, "the answer is too large" if ANSWER_BYTE_LIMIT < answer.bytesize + chunk.bytesize

            answer << chunk
          end
        end
      end
      [status, answer]
    rescue *UNREACHABLE_ERRORS => error
      raise TransportError, error.message
    end

    # The Net::HTTP transport as a callable.
    NET_HTTP = method(:net_http)
  end
end
