require "bundler/setup"
require "urnetwork"
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
jwt, id = ENV.fetch("URNETWORK_JWT"), ENV.fetch("URNETWORK_INSTANCE_ID")
raw, handles, device, conn = URnetwork::Raw, [], nil, nil
manager = 0
begin
  manager = raw.urnet_new_network_space_manager_no_storage
  handles << URnetwork::Handle.new(manager)
  space = raw.urnet_network_space_manager_update_network_space_values(manager,
    JSON.generate(host_name: "ur.network", env_name: "main"), JSON.generate(migration_host_name: "bringyour.com"))
  handles << URnetwork::Handle.new(space)
  api = raw.urnet_network_space_get_api(space)
  handles << URnetwork::Handle.new(api)
  raw.urnet_api_set_by_jwt(api, jwt)
  error = FFI::MemoryPointer.new(:pointer)
  handle = raw.urnet_new_device_local_with_defaults(space, jwt, "Ruby socket example", "ruby", "1", id, false, error)
  message = URnetwork.take_string(error.read_pointer)
  raise message if message
  device = URnetwork::Device.new(handle)
  raw.urnet_device_set_connect_location(handle, JSON.generate(connect_location_id: {best_available: true}))
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
  device&.close
  raw.urnet_network_space_manager_close(manager) if manager != 0
  handles.reverse_each(&:close)
end
