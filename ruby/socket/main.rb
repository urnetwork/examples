require "bundler/setup"
require_relative "../integration/client"
require "uri"
require "net/http"
require "faraday"

mode = ARGV[0] || "tls"
if ["--version", "--new-id"].include?(mode)
  puts(mode == "--version" ? URnetwork.version : URnetwork.take_string(URnetwork::Raw.urnet_new_id))
  exit
end

if ["net-http", "faraday"].include?(mode)
  # These clients' HTTPS implementations require a kernel file descriptor.
  # A loopback HTTP CONNECT proxy delegates its upstream dial to the SDK.
  proxy = URI(ENV.fetch("URNETWORK_HTTP_PROXY"))
  raise "Expected a loopback HTTP proxy" unless proxy.scheme == "http" && ["127.0.0.1", "[::1]", "::1"].include?(proxy.host)
  url = URI(ARGV[1] || "https://example.com/")
  if mode == "net-http"
    client = Net::HTTP::Proxy(proxy.host, proxy.port).new(url.host, url.port)
    client.use_ssl = url.scheme == "https" # Ruby's default verifies certificates.
    client.open_timeout = client.read_timeout = client.write_timeout = 30
    response = client.start { |h| h.get(url.request_uri) }
    puts response.code, response.body
  else
    client = Faraday.new(proxy: proxy.to_s) do |f|
      f.options.timeout = f.options.open_timeout = 30
      f.adapter :net_http
    end
    response = client.get(url.to_s)
    puts response.status, response.body
  end
  exit
end

raise "Modes: tls, udp, dtls, net-http, faraday" unless ["tls", "udp", "dtls"].include?(mode)
raise "UDP/DTLS require an echo server host:port" if mode != "tls" && !ARGV[1]
session, conn = nil, nil
begin
  session = UrSession.new
  device = session.device
  address = ARGV[1] || "example.com:443"
  conn = mode == "udp" ? device.dial("udp", address) : device.dial_tls(mode == "dtls" ? "udp" : "tcp", address)
  conn.set_deadline((Time.now.to_f * 1000).to_i + 10_000)
  payload = mode == "tls" ? "GET / HTTP/1.1\r\nHost: #{address}\r\nConnection: close\r\n\r\n" : "hello"
  offset = 0
  while offset < payload.bytesize
    count = conn.write(payload.byteslice(offset..))
    raise IOError, "Write made no progress" if count == 0
    offset += count
  end
  received = 0
  loop do
    result = conn.read
    STDOUT.write(result.data)
    received += result.data.bytesize
    break if result.eof || mode != "tls" || received >= 1_048_576
  end
ensure
  conn&.close
  session&.close
end
