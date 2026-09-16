# frozen_string_literal: true
# SERVER ONLY: ruby allocator.rb user:<authenticated-service-user-id> | --self-test
# The authenticated backend supplies the service key internally, never as a raw
# request field or a UR client ID. Set URNETWORK_ROOT_JWT, URNETWORK_CLIENT_MAP
# (absolute path under an existing service-owned directory) and optional URNETWORK_API_URL.
# An interrupted allocator may leave .lock; remove it only after verifying it is stale.
require 'base64'
require 'json'
require 'net/http'
require 'uri'
require 'tempfile'
require 'tmpdir'
require 'fileutils'

module Allocator
  LIMIT = 1 << 20
  USER = /\Auser:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}\z/
  ID = /\A[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\z/
  module_function

  def service_user(args)
    raise 'expected one user:<service-user-id>' unless args.length == 1 && USER.match?(args.first)
    args.first
  end

  def endpoint(base)
    u = URI(base)
    unless u.host && !u.userinfo && ['', '/'].include?(u.path) && !u.query && !u.fragment &&
           (u.scheme == 'https' || (u.scheme == 'http' && ['localhost', '127.0.0.1', '::1'].include?(u.hostname)))
      raise 'API must be an HTTPS origin; HTTP is allowed only for explicit loopback mocks'
    end
    URI(base.sub(%r{/$}, '') + '/network/auth-client')
  end

  def request_for(user, client = nil)
    service_user([user])
    body = {'description' => "service #{user}", 'device_spec' => 'urnetwork-examples/ruby-server'}
    unless client.nil?
      raise 'invalid mapped client' unless client.is_a?(String) && ID.match?(client)
      body['client_id'] = client
    end
    body
  end

  def parse_response(raw, expected = nil)
    obj = JSON.parse(raw)
    raise 'API failure' unless obj.is_a?(Hash) && obj['error'].nil?
    client, jwt = obj.values_at('client_id', 'by_client_jwt')
    raise 'invalid API result' unless client.is_a?(String) && ID.match?(client) && jwt.is_a?(String)
    parts = jwt.split('.', -1)
    raise 'invalid client JWT' unless parts.length == 3 && parts.none?(&:empty?) && /\A[A-Za-z0-9_-]+\z/.match?(parts[1])
    claims = JSON.parse(Base64.urlsafe_decode64(parts[1]))
    unless claims.is_a?(Hash) && claims['client_id'] == client && (expected.nil? || expected == client)
      raise 'scoped client identity mismatch'
    end
    # Consistency check only; the API authenticates the root and signs the client JWT.
    {'client_id' => client, 'by_client_jwt' => jwt}
  end

  def load_map(file)
    raise 'mapping cannot be a symlink' if File.symlink?(file)
    return {'version' => 1, 'clients' => {}} unless File.exist?(file)
    stat = File.stat(file)
    raise 'mapping must be private (0600)' unless stat.file? && stat.size <= LIMIT && (stat.mode & 0o077).zero?
    map = JSON.parse(File.read(file))
    raise 'invalid mapping' unless map.is_a?(Hash) && map['version'] == 1 && map['clients'].is_a?(Hash)
    seen = {}
    map['clients'].each do |user, client|
      service_user([user])
      raise 'invalid mapped client' unless client.is_a?(String) && ID.match?(client) && !seen[client]
      seen[client] = true
    end
    map
  end

  def save_map(file, map)
    Tempfile.create(['clients-', '.json'], File.dirname(file)) do |out|
      out.chmod(0o600)
      out.write(JSON.generate(map))
      out.flush
      out.fsync
      File.rename(out.path, file)
    end
  end

  def allocate(user, file)
    raise 'mapping needs an absolute path and existing parent' unless file.start_with?('/') && File.directory?(File.dirname(file))
    lock = file + '.lock'
    Dir.mkdir(lock, 0o700)
    begin
      map = load_map(file)
      old = map['clients'][user]
      result = parse_response(yield(request_for(user, old)), old)
      if old.nil?
        raise 'client assigned to another user' if map['clients'].value?(result['client_id'])
        map['clients'][user] = result['client_id']
        save_map(file, map)
      end
      result
    ensure
      Dir.rmdir(lock)
    end
  end

  def post(url, root, body)
    raise 'set backend root JWT' if root.nil? || root.empty? || /\s/.match?(root)
    request = Net::HTTP::Post.new(url)
    request['Authorization'] = 'Bearer ' + root
    request['Content-Type'] = 'application/json'
    request.body = JSON.generate(body)
    result = +''
    Net::HTTP.start(url.hostname, url.port, use_ssl: url.scheme == 'https', open_timeout: 15, read_timeout: 15, write_timeout: 15) do |http|
      http.request(request) do |response|
        raise 'provisioning HTTP failure' unless response.is_a?(Net::HTTPSuccess)
        response.read_body do |part|
          raise 'response too large' if result.bytesize + part.bytesize > LIMIT
          result << part
        end
      end
    end
    result
  end

  def self_test
    client = '11111111-1111-1111-1111-111111111111'
    jwt = 'e30.' + Base64.urlsafe_encode64(JSON.generate({'client_id' => client}), padding: false) + '.test'
    response = JSON.generate({'client_id' => client, 'by_client_jwt' => jwt})
    calls = []
    mock = ->(body) { calls << body; response }
    Dir.mktmpdir('ur-allocator-') do |directory|
      file = File.join(directory, 'clients.json')
      raise 'new allocation failed' unless allocate('user:alice', file, &mock)['client_id'] == client
      raise 'reissue failed' unless allocate('user:alice', file, &mock)['by_client_jwt'] == jwt
      raise 'wrong request shape' if calls[0].key?('client_id') || calls.any? { |c| c.key?('source_client_id') }
      raise 'mapped ID missing' unless calls[1]['client_id'] == client
      raise 'round-trip failed' unless load_map(file)['clients'] == {'user:alice' => client}
      raise 'endpoint failed' unless endpoint('http://127.0.0.1:1234').path == '/network/auth-client'
      [-> { service_user([client]) }, -> { service_user(['user:a', '--client-id', client]) },
       -> { service_user(['user:../a']) }, -> { endpoint('http://example.com') }, -> { endpoint('https://example.com/path') },
       -> { parse_response('{"error":{}}') }, -> { parse_response(response, '22222222-2222-2222-2222-222222222222') }].each do |invalid|
        rejected = false
        begin
          invalid.call
        rescue StandardError
          rejected = true
        end
        raise 'invalid input accepted' unless rejected
      end
    end
    puts 'allocator self-test passed'
  end
end

begin
  if ARGV == ['--self-test']
    Allocator.self_test
  else
    user = Allocator.service_user(ARGV)
    url = Allocator.endpoint(ENV.fetch('URNETWORK_API_URL', 'https://api.bringyour.com'))
    root, file = ENV.fetch('URNETWORK_ROOT_JWT'), ENV.fetch('URNETWORK_CLIENT_MAP')
    puts JSON.generate(Allocator.allocate(user, file) { |body| Allocator.post(url, root, body) })
  end
rescue StandardError
  warn 'allocator failed: check service key, private mapping and backend API configuration'
  exit 1
end
