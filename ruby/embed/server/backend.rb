# frozen_string_literal: true

# SERVER ONLY. The Ruby embed backend tool (EMBED_CONTRACT.md, "Backend
# tools"). It extends the integration allocator
# (../../integration/server/allocator.rb): the same settings, map format, key
# pattern, lock and response checks, plus the embed commands.
#
#   ruby backend.rb provision <key> <client-jwt-file>
#   ruby backend.rb cap <key> [--monthly <bytes>|--monthly null] [--total <bytes>|--total null] [--reset-total]
#   ruby backend.rb usage <key>
#   ruby backend.rb usage-all
#   ruby backend.rb remove <key>
#   ruby backend.rb acl <key> default|isolated
#   ruby backend.rb status
#   ruby backend.rb --self-test
#
# <key> is user:<service-user-id>, or user:<service-user-id>:<installation-id>
# for one client per running installation. Your service authenticates the
# user and supplies the key itself, never from a raw request field.
#
# Settings:
# - URNETWORK_ROOT_JWT: the root credential, an API key (urn_...) or a network
#   JWT, from the backend's secret store. Never sent to an app.
# - URNETWORK_CLIENT_MAP: absolute filename of this tool's private client map,
#   in an existing private, service-owned directory. Not the token server's map.
# - URNETWORK_API_URL: optional HTTPS origin, default https://api.bringyour.com.
# - URNETWORK_DEFAULT_ACL_GROUP: optional, the ACL group of each new client:
#   isolated (the default), so your users never see each other in the peer
#   list, or default, for an app that uses Messages.
#
# Exit codes: 0 success; 78 a configuration or credential problem (missing
# settings, an invalid key or map, the root credential refused, the client
# limit); 1 any other failure. Each failure prints one stderr line, which never
# carries a secret.

require "base64"
require "json"
require "net/http"
require "openssl"
require "securerandom"
require "stringio"
require "tmpdir"
require "uri"
require_relative "../../integration/server/allocator"

module EmbedBackend
  DESCRIPTION = "embed client"
  DEVICE_SPEC = "urnetwork-examples/ruby-embed-server"

  AUTH_CLIENT_ROUTE = "/network/auth-client"
  REMOVE_CLIENT_ROUTE = "/network/remove-client"
  CAP_ROUTE = "/network/client-data-cap"
  CAPS_ROUTE = "/network/client-data-caps"
  ACL_ROUTE = "/network/client-acl-group"
  EMBED_ROUTE = "/network/embed"
  USAGE_ALL_PAGE_LIMIT = 1000

  MAX_BYTE_COUNT = 9_223_372_036_854_775_807
  BYTE_COUNT_PATTERN = /\A[0-9]{1,19}\z/.freeze

  CLIENT_DOES_NOT_EXIST = "Client does not exist."
  CLIENT_LIMIT_MESSAGE = "client limit reached: your network is at its client limit; see https://ur.io/services"
  UNMAPPED_MESSAGE = "no client is mapped for that key; run provision first"
  ACL_UNSUPPORTED_MESSAGE = "/network/client-acl-group answered 404: the server predates ACL groups"
  # The server refuses the data-cap and ACL-group routes with this message
  # while the team has not enabled Embed for the network (EMBED_CONTRACT.md,
  # "Embed enablement"); caps and groups set earlier stay enforced.
  EMBED_NOT_ENABLED_MESSAGE = "Embed isn't enabled for this network."
  # cap, usage, usage-all and acl print this for the refusal and exit 78
  EMBED_NOT_ENABLED_LINE = "embed not enabled: Embed isn't enabled for this network; see https://ur.io/services"
  # provision prints this on stderr when the client's default ACL group stays
  # pending because Embed isn't enabled, and exits 0
  EMBED_PENDING_LINE = "embed not enabled: the client's defaults stay pending until Embed is enabled; see https://ur.io/services"
  EMBED_UNSUPPORTED_MESSAGE = "/network/embed answered 404: the server predates Embed enablement"

  ACL_GROUP_DEFAULT = "default"
  ACL_GROUP_ISOLATED = "isolated"
  ACL_GROUPS = [ACL_GROUP_DEFAULT, ACL_GROUP_ISOLATED].freeze

  # a language tool's map has only these fields, pending_acl only while it is
  # not empty; the token server's adds pending_caps
  MAP_FIELDS = %w[version clients pending_acl].freeze

  EXIT_OK = 0
  EXIT_FAILURE = 1
  # sysexits EX_CONFIG
  EXIT_CONFIG = 78

  # the longest API error message the tool shows
  ERROR_MESSAGE_LIMIT = 300

  COMMANDS = %w[provision cap usage usage-all remove acl status].freeze

  USAGE = "usage: backend.rb provision <key> <client-jwt-file> | cap <key> [--monthly <bytes>|--monthly null] " \
          "[--total <bytes>|--total null] [--reset-total] | usage <key> | usage-all | remove <key> | " \
          "acl <key> default|isolated | status | --self-test"

  # A failure with its exit code and its stderr line, which never carries a
  # secret.
  class ToolError < StandardError
    attr_reader :exit_code

    def initialize(exit_code, message)
      super(message)
      @exit_code = exit_code
    end
  end

  module_function

  def config_problem(message)
    ToolError.new(EXIT_CONFIG, message)
  end

  def failure(message)
    ToolError.new(EXIT_FAILURE, message)
  end

  # A map key in the allocator's pattern.
  def check_key(key)
    raise config_problem("invalid key: use user:<service-user-id> or user:<service-user-id>:<installation-id>") unless key.is_a?(String) && Allocator::USER.match?(key)

    key
  end

  # The API origin with the allocator's rules: HTTPS, or explicit loopback
  # HTTP for local mocks; no credentials, path, query or fragment.
  def api_origin(base)
    begin
      Allocator.endpoint(base)
    rescue StandardError
      raise config_problem("URNETWORK_API_URL must be an HTTPS origin; HTTP is allowed only for explicit loopback mocks")
    end
    raise config_problem("URNETWORK_API_URL must be an origin, with no query or fragment") if base.include?("?") || base.include?("#")

    base.sub(%r{/\z}, "")
  end

  # The allocator's private map, with an optional pending_acl: the keys whose
  # new clients still owe their default ACL group. A map with other fields
  # (the token server's map has pending_caps) is refused, so that two tools
  # never rewrite each other's map.
  def load_map(file)
    begin
      map = Allocator.load_map(file)
    rescue StandardError => error
      raise config_problem("invalid client map: #{error.message}")
    end
    extra = map.keys - MAP_FIELDS
    raise config_problem("the client map has fields other than version, clients and pending_acl (#{extra.sort.join(', ')}); give this tool its own map") unless extra.empty?

    pending = map["pending_acl"] ||= []
    unless pending.is_a?(Array) && pending.all? { |key| key.is_a?(String) && map["clients"].key?(key) } && pending.uniq.length == pending.length
      raise config_problem("the client map's pending_acl is not a list of mapped keys")
    end

    map
  end

  # Saves the map; pending_acl only while it is not empty.
  def save_map(file, map)
    saved = {"version" => map["version"], "clients" => map["clients"]}
    saved["pending_acl"] = map["pending_acl"] unless map.fetch("pending_acl", []).empty?
    Allocator.save_map(file, saved)
  rescue SystemCallError, IOError => error
    raise failure("save the client map: #{error.message}")
  end

  # Records that key's new client owes its default ACL group, or drops the
  # record.
  def set_acl_pending(map, key, pending)
    keys = map.fetch("pending_acl", []).reject { |entry| entry == key }
    keys << key if pending
    map["pending_acl"] = keys
  end

  # The allocator's exclusive <map>.lock directory, held through the remote
  # call and the map update.
  def with_map_lock(file)
    lock = "#{file}.lock"
    begin
      Dir.mkdir(lock, 0o700)
    rescue Errno::EEXIST
      raise failure("the client map is locked by another run; retry when it finishes")
    rescue SystemCallError => error
      raise failure("lock the client map: #{error.message}")
    end
    begin
      yield
    ensure
      Dir.rmdir(lock)
    end
  end

  # Replaces a file atomically with owner-only permissions: a private
  # temporary file in the same directory is written, synced and renamed over it.
  def write_private_file(path, data)
    temp_path = File.join(File.dirname(File.expand_path(path)), ".#{File.basename(path)}.#{SecureRandom.hex(8)}")
    file = File.open(temp_path, File::WRONLY | File::CREAT | File::EXCL | File::BINARY, 0o600)
    begin
      file.chmod(0o600) unless Gem.win_platform?
      file.write(data)
      file.flush
      file.fsync
      file.close
      File.rename(temp_path, path)
    rescue StandardError
      file.close unless file.closed?
      begin
        File.unlink(temp_path)
      rescue SystemCallError
        nil
      end
      raise
    end
  end

  # An API error object's message on one bounded line.
  def message_of(error)
    message = error.is_a?(Hash) ? error["message"] : nil
    return "no message" unless message.is_a?(String)

    message.split.join(" ")[0, ERROR_MESSAGE_LIMIT]
  end

  def client_does_not_exist?(answer)
    error = answer["error"]
    error.is_a?(Hash) && error["message"] == CLIENT_DOES_NOT_EXIST
  end

  # A refusal for either client limit flag is a configuration problem.
  def check_client_limit(answer)
    error = answer["error"]
    return unless error.is_a?(Hash) && (error["client_limit_exceeded"] == true || error["upgrade_required"] == true)

    raise config_problem(CLIENT_LIMIT_MESSAGE)
  end

  def embed_not_enabled?(answer)
    error = answer["error"]
    error.is_a?(Hash) && error["message"] == EMBED_NOT_ENABLED_MESSAGE
  end

  # The Embed-not-enabled refusal is a configuration problem with the fixed
  # line.
  def check_embed_enabled(answer)
    raise config_problem(EMBED_NOT_ENABLED_LINE) if embed_not_enabled?(answer)
  end

  # A cap object: a JSON object with a client_id and no error.
  def check_cap_object(value)
    check_embed_enabled(value) if value.is_a?(Hash)
    raise failure("the cap request was refused: #{message_of(value['error'])}") if value.is_a?(Hash) && !value["error"].nil?
    raise failure("the API answered something that is not a cap object") unless value.is_a?(Hash) && value["client_id"].is_a?(String)

    %w[monthly_byte_limit total_byte_limit].each do |name|
      limit = value[name]
      raise failure("the cap object has an invalid #{name}") unless limit.nil? || limit.is_a?(Integer)
    end
    value
  end

  # A byte count option: a decimal integer from 0 to 9223372036854775807 with
  # no units, or null to clear the cap.
  def parse_byte_limit(text)
    return nil if text == "null"
    unless BYTE_COUNT_PATTERN.match?(text) && Integer(text, 10) <= MAX_BYTE_COUNT
      raise config_problem("a byte count is a decimal integer from 0 to 9223372036854775807, with no units, or null")
    end

    Integer(text, 10)
  end

  # The fields to merge: only the options given, so an omitted cap is absent
  # from the request and keeps its value.
  def parse_cap_options(args)
    fields = {}
    index = 0
    while index < args.length
      option = args[index]
      case option
      when "--monthly", "--total"
        name = option == "--monthly" ? "monthly_byte_limit" : "total_byte_limit"
        raise config_problem("#{option} is given twice") if fields.key?(name)
        raise config_problem("#{option} needs a byte count or null") if args.length <= index + 1

        fields[name] = parse_byte_limit(args[index + 1])
        index += 2
      when "--reset-total"
        raise config_problem("--reset-total is given twice") if fields.key?("reset_total")

        fields["reset_total"] = true
        index += 1
      else
        raise config_problem(USAGE)
      end
    end
    raise config_problem("cap needs at least one of --monthly, --total or --reset-total") if fields.empty?

    fields
  end

  # The URnetwork API with the root credential. transport.call(method, path,
  # body) returns [status, answer]; the self-test passes a stand-in.
  class Api
    def initialize(transport)
      @transport = transport
    end

    def call(method, path, body = nil)
      status, answer = @transport.call(method, path, body.nil? ? nil : JSON.generate(body))
      route = path.split("?", 2).first
      raise EmbedBackend.config_problem("the root credential was refused; check URNETWORK_ROOT_JWT") if [401, 403].include?(status)
      if status == 404
        # a server that predates the route (EMBED_CONTRACT.md, "Backend tools")
        raise EmbedBackend.failure(ACL_UNSUPPORTED_MESSAGE) if route == ACL_ROUTE
        raise EmbedBackend.failure("#{route} answered 404: the server predates the data-cap routes") if [CAP_ROUTE, CAPS_ROUTE].include?(route)
        raise EmbedBackend.failure(EMBED_UNSUPPORTED_MESSAGE) if route == EMBED_ROUTE

        raise EmbedBackend.failure("#{route} answered 404")
      end
      raise EmbedBackend.failure("#{route} failed with HTTP #{status}") unless (200..299).cover?(status)

      value =
        begin
          JSON.parse(answer)
        rescue JSON::ParserError
          nil
        end
      raise EmbedBackend.failure("#{route} answered something that is not a JSON object") unless value.is_a?(Hash)

      value
    end
  end

  # The real transport: Net::HTTP, which never follows a redirect, with the
  # allocator's answer size limit.
  def net_http_transport(origin, root)
    raise config_problem("set a valid URNETWORK_ROOT_JWT on the backend") if root.nil? || root.empty? || /\s/.match?(root)

    lambda do |method, path, body|
      uri = URI(origin + path)
      request = method == "POST" ? Net::HTTP::Post.new(uri) : Net::HTTP::Get.new(uri)
      request["Authorization"] = "Bearer #{root}"
      request["Accept"] = "application/json"
      unless body.nil?
        request["Content-Type"] = "application/json"
        request.body = body
      end
      status = nil
      answer = +""
      begin
        Net::HTTP.start(uri.hostname, uri.port, use_ssl: uri.scheme == "https", open_timeout: 15, read_timeout: 15, write_timeout: 15) do |http|
          http.request(request) do |response|
            status = response.code.to_i
            response.read_body do |part|
              raise EmbedBackend.failure("the API answer is too large") if Allocator::LIMIT < answer.bytesize + part.bytesize

              answer << part
            end
          end
        end
      rescue SystemCallError, IOError, SocketError, Timeout::Error, OpenSSL::SSL::SSLError, Net::ProtocolError
        # the reason can name the host; it never carries the credential
        raise EmbedBackend.failure("could not reach the URnetwork API; check URNETWORK_API_URL and the network")
      end
      [status, answer]
    end
  end

  # Reissues the key's client, or provisions a new one; on "Client does not
  # exist." it drops the mapping and provisions a new client. A new client
  # goes into acl_group, with a pending_acl record until the group is applied,
  # before any client JWT is written. Writes the client JWT to client_jwt_file
  # and prints only the client ID. While Embed isn't enabled the record stays,
  # with a line on stderr, and a provision after the team enables Embed applies
  # it.
  def provision(api, file, key, client_jwt_file, acl_group)
    result = nil
    embed_enabled = true
    with_map_lock(file) do
      map = load_map(file)
      old = map["clients"][key]
      answer = nil
      unless old.nil?
        answer = api.call("POST", AUTH_CLIENT_ROUTE, {"client_id" => old, "description" => DESCRIPTION, "device_spec" => DEVICE_SPEC})
        if client_does_not_exist?(answer)
          # deactivated after 30 days without connecting, or removed
          map["clients"].delete(key)
          set_acl_pending(map, key, false)
          save_map(file, map)
          old = nil
        end
      end
      # a new top-level client: neither client_id nor source_client_id
      answer = api.call("POST", AUTH_CLIENT_ROUTE, {"description" => DESCRIPTION, "device_spec" => DEVICE_SPEC}) if old.nil?
      check_client_limit(answer)
      raise failure("provisioning was refused: #{message_of(answer['error'])}") unless answer["error"].nil?

      begin
        result = Allocator.parse_response(JSON.generate(answer), old)
      rescue StandardError => error
        raise failure("the provisioning answer failed its checks: #{error.message}")
      end
      if old.nil?
        raise failure("the API returned a client that the map assigns to another key") if map["clients"].value?(result["client_id"])

        map["clients"][key] = result["client_id"]
        # the mapping and the record that the client owes its group, in one save
        set_acl_pending(map, key, acl_group == ACL_GROUP_ISOLATED)
        save_map(file, map)
      end
      if map["pending_acl"].include?(key)
        # a new client is "default": only an isolated default needs the request.
        # A failure raises with the record kept, before any client JWT is written.
        embed_enabled = post_acl_group(api, result["client_id"], ACL_GROUP_ISOLATED, allow_not_enabled: true) if acl_group == ACL_GROUP_ISOLATED
        # while Embed isn't enabled the record stays and the client works
        if embed_enabled
          set_acl_pending(map, key, false)
          save_map(file, map)
        end
      end
      begin
        write_private_file(client_jwt_file, "#{result['by_client_jwt']}\n")
      rescue SystemCallError, IOError => error
        raise failure("write the client JWT file: #{error.message}")
      end
    end
    puts JSON.generate({"client_id" => result["client_id"]})
    warn EMBED_PENDING_LINE unless embed_enabled
  end

  def mapped_client(file, key)
    client = load_map(file)["clients"][key]
    raise config_problem(UNMAPPED_MESSAGE) if client.nil?

    client
  end

  # Sets the client's ACL group; the answer must name the client and the
  # group. The Embed-not-enabled refusal exits 78, or returns false when
  # allow_not_enabled is set.
  def post_acl_group(api, client, acl_group, allow_not_enabled: false)
    answer = api.call("POST", ACL_ROUTE, {"client_id" => client, "acl_group" => acl_group})
    return false if allow_not_enabled && embed_not_enabled?(answer)

    check_embed_enabled(answer)
    raise failure("the ACL group request was refused: #{message_of(answer['error'])}") unless answer["error"].nil?
    raise failure("the API answered another client or ACL group") unless answer["client_id"] == client && answer["acl_group"] == acl_group

    true
  end

  # Sets the ACL group of the key's client and prints {"client_id": ...,
  # "acl_group": ...}. An explicit group settles a pending default group, so
  # the record is dropped.
  def acl(api, file, key, acl_group)
    client = nil
    with_map_lock(file) do
      map = load_map(file)
      client = map["clients"][key]
      raise config_problem(UNMAPPED_MESSAGE) if client.nil?

      post_acl_group(api, client, acl_group)
      if map["pending_acl"].include?(key)
        set_acl_pending(map, key, false)
        save_map(file, map)
      end
    end
    puts JSON.generate({"client_id" => client, "acl_group" => acl_group})
  end

  # Posts only the given fields to POST /network/client-data-cap and prints
  # the cap object.
  def cap(api, file, key, fields)
    answer = nil
    with_map_lock(file) do
      client = mapped_client(file, key)
      answer = api.call("POST", CAP_ROUTE, {"client_id" => client}.merge(fields))
    end
    puts JSON.generate(check_cap_object(answer))
  end

  # Prints the key's cap object, read with the root credential.
  def usage(api, file, key)
    client = mapped_client(file, key)
    answer = api.call("GET", "#{CAP_ROUTE}?#{URI.encode_www_form('client_id' => client)}")
    puts JSON.generate(check_cap_object(answer))
  end

  # Pages through GET /network/client-data-caps and prints one cap object per
  # line, stopping at a null cursor or a repeated one.
  def usage_all(api)
    cursor = nil
    seen = {}
    loop do
      query = [["limit", USAGE_ALL_PAGE_LIMIT.to_s]]
      query << ["cursor", cursor] unless cursor.nil?
      answer = api.call("GET", "#{CAPS_ROUTE}?#{URI.encode_www_form(query)}")
      check_embed_enabled(answer)
      raise failure("the cap list was refused: #{message_of(answer['error'])}") unless answer["error"].nil?

      clients = answer["clients"]
      raise failure("the cap list has no clients array") unless clients.is_a?(Array)

      clients.each { |value| puts JSON.generate(check_cap_object(value)) }
      next_cursor = answer["next_cursor"]
      return if next_cursor.nil?
      raise failure("the cap list has an invalid next_cursor") unless next_cursor.is_a?(String)
      # a server that repeats a cursor would page forever
      return if seen[next_cursor]

      seen[next_cursor] = true
      cursor = next_cursor
    end
  end

  def count?(value)
    value.is_a?(Integer) && value >= 0
  end

  # Prints the network's Embed state from GET /network/embed as one line,
  # "embed enabled: yes | client limit: 5000 | active clients: 1234". A
  # refusal, such as for a client JWT, is a configuration problem.
  def status(api)
    answer = api.call("GET", EMBED_ROUTE)
    raise config_problem("the Embed state request was refused: #{message_of(answer['error'])}") unless answer["error"].nil?

    enabled = answer["enabled"]
    client_limit = answer["client_limit"]
    active_client_count = answer["active_client_count"]
    unless (enabled == true || enabled == false) && count?(client_limit) && count?(active_client_count)
      raise failure("#{EMBED_ROUTE} answered something that is not an Embed state")
    end

    puts "embed enabled: #{enabled ? 'yes' : 'no'} | client limit: #{client_limit} | active clients: #{active_client_count}"
  end

  # Removes the key's client, then its mapping, also when the client is
  # already gone.
  def remove(api, file, key)
    client = nil
    with_map_lock(file) do
      map = load_map(file)
      client = map["clients"][key]
      raise config_problem(UNMAPPED_MESSAGE) if client.nil?

      answer = api.call("POST", REMOVE_CLIENT_ROUTE, {"client_id" => client})
      raise failure("remove was refused: #{message_of(answer['error'])}") if !answer["error"].nil? && !client_does_not_exist?(answer)

      map["clients"].delete(key)
      set_acl_pending(map, key, false)
      save_map(file, map)
    end
    puts JSON.generate({"removed" => client})
  end

  # Runs one command and returns the exit code. transport_factory.call(origin,
  # root) makes the API transport; the self-test passes a stand-in.
  def run(args, env = ENV, transport_factory = method(:net_http_transport))
    if args == ["--self-test"]
      begin
        self_test
      rescue StandardError => error
        # test data only: the self-test has no credentials to expose
        warn "embed backend self-test failed: #{error.message}"
        return EXIT_FAILURE
      end
      puts "embed backend self-test passed"
      return EXIT_OK
    end
    command = args.first
    operands = args.drop(1)
    raise config_problem(USAGE) unless COMMANDS.include?(command)
    raise config_problem(USAGE) if command == "provision" && operands.length != 2
    raise config_problem(USAGE) if %w[usage remove].include?(command) && operands.length != 1
    raise config_problem(USAGE) if command == "cap" && operands.empty?
    raise config_problem(USAGE) if %w[usage-all status].include?(command) && !operands.empty?
    raise config_problem(USAGE) if command == "acl" && (operands.length != 2 || !ACL_GROUPS.include?(operands[1]))

    key = %w[usage-all status].include?(command) ? nil : check_key(operands.first)
    fields = command == "cap" ? parse_cap_options(operands.drop(1)) : nil

    root = env.fetch("URNETWORK_ROOT_JWT", "")
    raise config_problem("set URNETWORK_ROOT_JWT from the backend's secret store") if root.empty?

    api_url = env.fetch("URNETWORK_API_URL", "")
    origin = api_origin(api_url.empty? ? "https://api.bringyour.com" : api_url)
    acl_group = env.fetch("URNETWORK_DEFAULT_ACL_GROUP", "")
    acl_group = ACL_GROUP_ISOLATED if acl_group.empty?
    raise config_problem("URNETWORK_DEFAULT_ACL_GROUP must be default or isolated") unless ACL_GROUPS.include?(acl_group)
    file = nil
    unless %w[usage-all status].include?(command)
      file = env.fetch("URNETWORK_CLIENT_MAP", "")
      raise config_problem("set URNETWORK_CLIENT_MAP to this tool's private client map") if file.empty?
      unless File.absolute_path?(file) && File.directory?(File.dirname(file))
        raise config_problem("URNETWORK_CLIENT_MAP must be absolute, in an existing service-owned directory")
      end
    end
    api = Api.new(transport_factory.call(origin, root))
    case command
    when "provision" then provision(api, file, key, operands[1], acl_group)
    when "cap" then cap(api, file, key, fields)
    when "usage" then usage(api, file, key)
    when "usage-all" then usage_all(api)
    when "acl" then acl(api, file, key, operands[1])
    when "status" then status(api)
    else remove(api, file, key)
    end
    EXIT_OK
  rescue ToolError => error
    warn error.message
    error.exit_code
  rescue StandardError
    # neither remote bodies nor exception text may expose a credential
    warn "embed backend failed: check the key, the private map and the API configuration"
    EXIT_FAILURE
  end

  # ---------------------------------------------------------------- self-test

  SELF_TEST_ROOT = "urn_self-test-root-credential"
  SELF_TEST_CLIENT = "11111111-1111-1111-1111-111111111111"
  SELF_TEST_OTHER_CLIENT = "22222222-2222-2222-2222-222222222222"
  SELF_TEST_KEY = "user:alice:33333333-3333-3333-3333-333333333333"

  # A self-test check that failed.
  class SelfTestError < StandardError; end

  # A stand-in URnetwork API: answers each request from a queue of [status,
  # JSON value] answers and logs the requests with their parsed bodies.
  class StandInApi
    attr_reader :requests

    def initialize(*answers)
      @answers = answers
      @requests = []
    end

    def factory
      lambda do |_origin, root|
        raise SelfTestError, "the tool passed a different root credential" unless root == SELF_TEST_ROOT

        self
      end
    end

    def call(method, path, body)
      @requests << {method: method, path: path, body: body.nil? ? nil : JSON.parse(body)}
      answer = @answers.shift
      raise SelfTestError, "unexpected request #{method} #{path}" if answer.nil?
      raise answer if answer.is_a?(Exception)

      status, value = answer
      [status, JSON.generate(value)]
    end
  end

  # An unsigned client JWT carrying client_id, for the claim checks only.
  def self_test_jwt(client_id)
    "e30.#{Base64.urlsafe_encode64(JSON.generate({'client_id' => client_id}), padding: false)}.c2lnbmF0dXJl"
  end

  def self_test_expect(condition, message)
    raise SelfTestError, message unless condition
  end

  # Runs the tool with output captured; the root credential must never appear.
  def self_test_tool(args, env, api)
    out = StringIO.new
    err = StringIO.new
    saved_out, saved_err = $stdout, $stderr
    $stdout = out
    $stderr = err
    code = run(args, env, api.factory)
    raise SelfTestError, "the root credential reached the output" if (out.string + err.string).include?(SELF_TEST_ROOT)

    [code, out.string, err.string]
  ensure
    $stdout, $stderr = saved_out, saved_err
  end

  # The credential-free self-test: allocator rules, re-provision, the client
  # JWT file, the merge request bodies, cap objects, paging, remove, map
  # refusal and the exit codes.
  def self_test
    Dir.mktmpdir("ur-embed-backend-") do |directory|
      File.chmod(0o700, directory) unless Gem.win_platform?
      map_path = File.join(directory, "clients.json")
      jwt_path = File.join(directory, "client.jwt")
      # the checks before the ACL group checks keep new clients in "default",
      # which sends no ACL request
      env = {"URNETWORK_ROOT_JWT" => SELF_TEST_ROOT, "URNETWORK_CLIENT_MAP" => map_path, "URNETWORK_API_URL" => "http://127.0.0.1:1",
             "URNETWORK_DEFAULT_ACL_GROUP" => ACL_GROUP_DEFAULT}
      tool = ->(args, api) { self_test_tool(args, env, api) }
      expect = method(:self_test_expect)
      client_jwt = self_test_jwt(SELF_TEST_CLIENT)
      provisioned = {"client_id" => SELF_TEST_CLIENT, "by_client_jwt" => client_jwt}

      # a new key provisions a top-level client; the token goes only to the file
      api = StandInApi.new([200, provisioned])
      code, out, err = tool.call(["provision", SELF_TEST_KEY, jwt_path], api)
      expect.call(code == EXIT_OK && JSON.parse(out) == {"client_id" => SELF_TEST_CLIENT}, "provision must print the client ID: #{code} #{err}")
      expect.call(api.requests[0][:path] == AUTH_CLIENT_ROUTE, "provision must post /network/auth-client")
      expect.call(api.requests[0][:body] == {"description" => DESCRIPTION, "device_spec" => DEVICE_SPEC}, "a new client sends neither client_id nor source_client_id")
      expect.call(!(out + err).include?(client_jwt), "the client JWT must never reach stdout or stderr")
      expect.call(File.read(jwt_path) == "#{client_jwt}\n", "the client JWT file must hold the token")
      unless Gem.win_platform?
        expect.call((File.stat(jwt_path).mode & 0o777) == 0o600, "the client JWT file must be private (0600)")
        expect.call((File.stat(map_path).mode & 0o077).zero?, "the map must be private")
      end
      expect.call(load_map(map_path)["clients"] == {SELF_TEST_KEY => SELF_TEST_CLIENT}, "the map must round-trip")

      # a mapped key reissues with its client_id
      api = StandInApi.new([200, provisioned])
      code, = tool.call(["provision", SELF_TEST_KEY, jwt_path], api)
      expect.call(code == EXIT_OK && api.requests[0][:body]["client_id"] == SELF_TEST_CLIENT, "a mapped key must reissue with its client_id")
      expect.call(!api.requests[0][:body].key?("source_client_id"), "a reissue never sends source_client_id")

      # "Client does not exist." drops the mapping and provisions a new client
      other = {"client_id" => SELF_TEST_OTHER_CLIENT, "by_client_jwt" => self_test_jwt(SELF_TEST_OTHER_CLIENT)}
      api = StandInApi.new([200, {"error" => {"message" => CLIENT_DOES_NOT_EXIST}}], [200, other])
      code, out, = tool.call(["provision", SELF_TEST_KEY, jwt_path], api)
      expect.call(code == EXIT_OK && JSON.parse(out)["client_id"] == SELF_TEST_OTHER_CLIENT, "a deactivated client must be re-provisioned")
      expect.call(!api.requests[1][:body].key?("client_id"), "the re-provision must create a new client")
      expect.call(load_map(map_path)["clients"][SELF_TEST_KEY] == SELF_TEST_OTHER_CLIENT, "the map must hold the new client")

      # response and claim checks
      mismatch = {"client_id" => SELF_TEST_CLIENT, "by_client_jwt" => self_test_jwt("44444444-4444-4444-4444-444444444444")}
      [mismatch, {"client_id" => SELF_TEST_CLIENT}, {"by_client_jwt" => client_jwt}, {"client_id" => "not-a-uuid", "by_client_jwt" => client_jwt}].each do |bad|
        code, = tool.call(["provision", "user:bob", jwt_path], StandInApi.new([200, bad]))
        expect.call(code == EXIT_FAILURE, "a provisioning answer #{bad} must fail its checks")
      end
      # a client the map already assigns to another key is refused
      code, = tool.call(["provision", "user:carol", jwt_path], StandInApi.new([200, other]))
      expect.call(code == EXIT_FAILURE, "a client assigned to another key must be refused")

      # the client limit: either flag, exit 78 with the exact line
      [{"client_limit_exceeded" => true, "message" => "Client limit exceeded."},
       {"client_limit_exceeded" => false, "upgrade_required" => true, "message" => "upgrade"}].each do |flags|
        code, _, err = tool.call(["provision", "user:dave", jwt_path], StandInApi.new([200, {"error" => flags}]))
        expect.call(code == EXIT_CONFIG && err.strip == CLIENT_LIMIT_MESSAGE, "a client limit refusal must exit 78 with the limit line: #{flags}")
      end

      # the merge request bodies: only the given fields, null as JSON null, integers
      cap_answer = {"client_id" => SELF_TEST_OTHER_CLIENT, "monthly_byte_limit" => 10_000_000_000, "total_byte_limit" => nil,
                    "capped" => false, "capped_reason" => ""}
      [
        [["--monthly", "10000000000"], {"monthly_byte_limit" => 10_000_000_000}],
        [["--total", "null"], {"total_byte_limit" => nil}],
        [["--monthly", "0"], {"monthly_byte_limit" => 0}],
        [["--reset-total"], {"reset_total" => true}],
        [["--monthly", "20000000000", "--total", "9223372036854775807", "--reset-total"],
         {"monthly_byte_limit" => 20_000_000_000, "total_byte_limit" => MAX_BYTE_COUNT, "reset_total" => true}],
      ].each do |options, fields|
        api = StandInApi.new([200, cap_answer])
        code, out, err = tool.call(["cap", SELF_TEST_KEY, *options], api)
        expect.call(code == EXIT_OK, "cap #{options} must succeed: #{err}")
        sent = api.requests[0][:body]
        expect.call(api.requests[0][:path] == CAP_ROUTE && api.requests[0][:method] == "POST", "cap must post /network/client-data-cap")
        expect.call(sent == {"client_id" => SELF_TEST_OTHER_CLIENT}.merge(fields), "cap #{options} must send #{fields}, sent #{sent}")
        fields.each { |name, value| expect.call(sent[name].class == value.class, "#{name} must keep its JSON type") }
        expect.call(JSON.parse(out) == cap_answer, "cap must print the cap object")
      end
      [[], ["--monthly"], ["--monthly", "-1"], ["--monthly", "1e3"], ["--monthly", "10GB"], ["--total", "9223372036854775808"],
       ["--monthly", "1", "--monthly", "2"], ["--weekly", "1"]].each do |options|
        code, = tool.call(["cap", SELF_TEST_KEY, *options], StandInApi.new)
        expect.call(code == EXIT_CONFIG, "cap #{options} must be a usage error")
      end
      code, = tool.call(["cap", SELF_TEST_KEY, "--monthly", "1"], StandInApi.new([200, {"error" => {"message" => "no such client"}}]))
      expect.call(code == EXIT_FAILURE, "a refused cap request must fail")
      code, = tool.call(["cap", SELF_TEST_KEY, "--monthly", "1"], StandInApi.new([404, {}]))
      expect.call(code == EXIT_FAILURE, "a server without the cap routes must fail")

      # usage reads the key's client with the root credential
      api = StandInApi.new([200, cap_answer])
      code, out, = tool.call(["usage", SELF_TEST_KEY], api)
      expect.call(code == EXIT_OK && JSON.parse(out) == cap_answer, "usage must print the cap object")
      expect.call(api.requests[0] == {method: "GET", path: "#{CAP_ROUTE}?client_id=#{SELF_TEST_OTHER_CLIENT}", body: nil}, "usage must GET the key's client")

      # usage-all pages until a null cursor, and stops at a repeated cursor
      page = ->(client, cursor) { [200, {"clients" => [cap_answer.merge("client_id" => client)], "next_cursor" => cursor}] }
      api = StandInApi.new(page.call("a", "c1"), page.call("b", "c2"), page.call("c", nil))
      code, out, = tool.call(["usage-all"], api)
      expect.call(code == EXIT_OK && out.lines.map { |line| JSON.parse(line)["client_id"] } == %w[a b c], "usage-all must print every page")
      expect.call(api.requests.map { |request| request[:path] } ==
                  ["#{CAPS_ROUTE}?limit=1000", "#{CAPS_ROUTE}?limit=1000&cursor=c1", "#{CAPS_ROUTE}?limit=1000&cursor=c2"],
                  "usage-all must page with the cursor")
      api = StandInApi.new(page.call("a", "c1"), page.call("b", "c1"))
      code, out, = tool.call(["usage-all"], api)
      expect.call(code == EXIT_OK && out.lines.length == 2 && api.requests.length == 2, "usage-all must stop at a repeated cursor")

      # remove drops the mapping for both answers, and keeps it on a refusal
      code, = tool.call(["remove", SELF_TEST_KEY], StandInApi.new([200, {"error" => {"message" => "not allowed"}}]))
      expect.call(code == EXIT_FAILURE && load_map(map_path)["clients"].key?(SELF_TEST_KEY), "a refused remove must keep the mapping")
      api = StandInApi.new([200, {}])
      code, out, = tool.call(["remove", SELF_TEST_KEY], api)
      expect.call(code == EXIT_OK && JSON.parse(out) == {"removed" => SELF_TEST_OTHER_CLIENT}, "remove must print the removed client")
      expect.call(api.requests[0][:body] == {"client_id" => SELF_TEST_OTHER_CLIENT}, "remove must post the client_id")
      expect.call(!load_map(map_path)["clients"].key?(SELF_TEST_KEY), "remove must drop the mapping")
      tool.call(["provision", SELF_TEST_KEY, jwt_path], StandInApi.new([200, provisioned]))
      code, = tool.call(["remove", SELF_TEST_KEY], StandInApi.new([200, {"error" => {"message" => CLIENT_DOES_NOT_EXIST}}]))
      expect.call(code == EXIT_OK && !load_map(map_path)["clients"].key?(SELF_TEST_KEY), "remove must drop the mapping of a client that is already gone")

      # a map with other fields, such as the token server's, is refused
      token_server_map = File.join(directory, "token-server.json")
      write_private_file(token_server_map, JSON.generate({"version" => 1, "clients" => {}, "pending_caps" => []}))
      code, = self_test_tool(["usage", SELF_TEST_KEY], env.merge("URNETWORK_CLIENT_MAP" => token_server_map), StandInApi.new)
      expect.call(code == EXIT_CONFIG, "a map with pending_caps must be refused")

      # key and argument rejection, settings, credential refusal and the network
      [["provision", SELF_TEST_CLIENT, jwt_path], ["provision", "user:../a", jwt_path], ["provision", SELF_TEST_KEY], ["usage"],
       ["usage-all", "extra"], ["status", "extra"], ["bogus"], []].each do |args|
        code, = tool.call(args, StandInApi.new)
        expect.call(code == EXIT_CONFIG, "#{args} must be a usage error")
      end
      code, = self_test_tool(["usage-all"], env.merge("URNETWORK_ROOT_JWT" => ""), StandInApi.new)
      expect.call(code == EXIT_CONFIG, "a missing root credential must exit 78")
      code, = self_test_tool(["usage", SELF_TEST_KEY], env.merge("URNETWORK_CLIENT_MAP" => "clients.json"), StandInApi.new)
      expect.call(code == EXIT_CONFIG, "a relative map must exit 78")
      code, = self_test_tool(["usage-all"], env.merge("URNETWORK_API_URL" => "http://example.com"), StandInApi.new)
      expect.call(code == EXIT_CONFIG, "a non-HTTPS API must exit 78")
      code, = tool.call(["usage-all"], StandInApi.new([401, {}]))
      expect.call(code == EXIT_CONFIG, "a refused root credential must exit 78")
      code, = tool.call(["usage-all"], StandInApi.new(failure("could not reach the URnetwork API")))
      expect.call(code == EXIT_FAILURE, "an unreachable API must exit 1")
      [["acl"], ["acl", SELF_TEST_KEY], ["acl", SELF_TEST_KEY, "public"], ["acl", SELF_TEST_KEY, "Default"],
       ["acl", SELF_TEST_KEY, "default", "extra"], ["acl", "alice", "default"]].each do |args|
        code, = tool.call(args, StandInApi.new)
        expect.call(code == EXIT_CONFIG, "#{args} must be a usage error")
      end
      code, _, err = self_test_tool(["provision", "user:x", jwt_path], env.merge("URNETWORK_DEFAULT_ACL_GROUP" => "private"), StandInApi.new)
      expect.call(code == EXIT_CONFIG && err.strip == "URNETWORK_DEFAULT_ACL_GROUP must be default or isolated", "an invalid default ACL group must exit 78")

      self_test_acl_groups(directory, env, expect)
      self_test_embed_not_enabled(directory, env, expect)
      self_test_embed_status(env, expect)
      self_test_failure_texts(directory, env, expect)
    end
  end

  # The default ACL group: applied to a new client only, with pending_acl set
  # before and cleared after, kept and retried after a failure, kept on a
  # server without ACL groups, and settled by acl; the acl command.
  def self_test_acl_groups(directory, env, expect)
    acl_map = File.join(directory, "acl.json")
    isolated = env.merge("URNETWORK_CLIENT_MAP" => acl_map).reject { |name, _| name == "URNETWORK_DEFAULT_ACL_GROUP" }
    jwt_path = File.join(directory, "acl.jwt")
    pending = -> { load_map(acl_map)["pending_acl"] }
    provisioned = ->(client) { [200, {"client_id" => client, "by_client_jwt" => self_test_jwt(client)}] }
    applied = ->(client, group) { [200, {"client_id" => client, "acl_group" => group}] }

    # unset means isolated: the new client, then one ACL request, then no record
    api = StandInApi.new(provisioned.call(SELF_TEST_CLIENT), applied.call(SELF_TEST_CLIENT, ACL_GROUP_ISOLATED))
    code, _, err = self_test_tool(["provision", "user:ivan", jwt_path], isolated, api)
    expect.call(code == EXIT_OK && api.requests.map { |request| request[:path] } == [AUTH_CLIENT_ROUTE, ACL_ROUTE], "a new client must be put in isolated: #{err}")
    expect.call(api.requests[1][:body] == {"client_id" => SELF_TEST_CLIENT, "acl_group" => ACL_GROUP_ISOLATED}, "the ACL request must name the client and isolated")
    expect.call(pending.call.empty? && !File.read(acl_map).include?("pending_acl"), "an applied group must leave no record")
    expect.call(File.read(jwt_path) == "#{self_test_jwt(SELF_TEST_CLIENT)}\n", "the client JWT must be written after the group")
    api = StandInApi.new(provisioned.call(SELF_TEST_CLIENT))
    code, = self_test_tool(["provision", "user:ivan", jwt_path], isolated, api)
    expect.call(code == EXIT_OK && api.requests.length == 1, "a reissue must send no ACL request")

    # a failed request keeps the record and writes no client JWT; the next issue retries
    judy_jwt = File.join(directory, "judy.jwt")
    code, = self_test_tool(["provision", "user:judy", judy_jwt], isolated, StandInApi.new(provisioned.call(SELF_TEST_OTHER_CLIENT), [500, {}]))
    expect.call(code == EXIT_FAILURE && !File.exist?(judy_jwt) && pending.call == ["user:judy"], "a failed ACL request must keep the record and write no client JWT")
    expect.call(load_map(acl_map)["clients"]["user:judy"] == SELF_TEST_OTHER_CLIENT, "the new client must be mapped with its record")
    api = StandInApi.new(provisioned.call(SELF_TEST_OTHER_CLIENT), applied.call(SELF_TEST_OTHER_CLIENT, ACL_GROUP_ISOLATED))
    code, = self_test_tool(["provision", "user:judy", judy_jwt], isolated, api)
    expect.call(code == EXIT_OK && api.requests[1][:path] == ACL_ROUTE && pending.call.empty? && File.exist?(judy_jwt), "a pending group must be applied on the next issue")

    # a server without ACL groups: exit 1 with the record kept; a default of default then provisions
    older_map = File.join(directory, "older.json")
    kim_jwt = File.join(directory, "kim.jwt")
    older = isolated.merge("URNETWORK_CLIENT_MAP" => older_map)
    kim = "55555555-5555-5555-5555-555555555555"
    code, _, err = self_test_tool(["provision", "user:kim", kim_jwt], older, StandInApi.new(provisioned.call(kim), [404, {}]))
    expect.call(code == EXIT_FAILURE && err.strip == ACL_UNSUPPORTED_MESSAGE && !File.exist?(kim_jwt), "a server without ACL groups must exit 1 with its line: #{err}")
    expect.call(load_map(older_map)["pending_acl"] == ["user:kim"], "a server without ACL groups must keep the record")
    api = StandInApi.new(provisioned.call(kim))
    code, = self_test_tool(["provision", "user:kim", kim_jwt], older.merge("URNETWORK_DEFAULT_ACL_GROUP" => ACL_GROUP_DEFAULT), api)
    expect.call(code == EXIT_OK && api.requests.length == 1 && load_map(older_map)["pending_acl"].empty? && File.exist?(kim_jwt),
                "a default of default must settle the record without a request")

    # the acl command: the request, the printed answer, and its refusals
    api = StandInApi.new(applied.call(SELF_TEST_CLIENT, ACL_GROUP_DEFAULT))
    code, out, = self_test_tool(["acl", "user:ivan", ACL_GROUP_DEFAULT], isolated, api)
    expect.call(code == EXIT_OK && JSON.parse(out) == {"client_id" => SELF_TEST_CLIENT, "acl_group" => ACL_GROUP_DEFAULT}, "acl must print the answer")
    expect.call(api.requests == [{method: "POST", path: ACL_ROUTE, body: {"client_id" => SELF_TEST_CLIENT, "acl_group" => ACL_GROUP_DEFAULT}}], "acl must post the client and the group")
    code, = self_test_tool(["acl", "user:ivan", ACL_GROUP_ISOLATED], isolated, StandInApi.new(applied.call(SELF_TEST_CLIENT, ACL_GROUP_DEFAULT)))
    expect.call(code == EXIT_FAILURE, "an answer for another group must fail")
    code, = self_test_tool(["acl", "user:ivan", ACL_GROUP_ISOLATED], isolated, StandInApi.new([200, {"error" => {"message" => CLIENT_DOES_NOT_EXIST}}]))
    expect.call(code == EXIT_FAILURE, "a refused ACL request must fail")
    code, _, err = self_test_tool(["acl", "user:ivan", ACL_GROUP_ISOLATED], isolated, StandInApi.new([404, {}]))
    expect.call(code == EXIT_FAILURE && err.strip == ACL_UNSUPPORTED_MESSAGE, "acl on a server without ACL groups must print its line: #{err}")

    # an explicit group settles a pending record, so a later issue keeps it
    mia = "66666666-6666-6666-6666-666666666666"
    self_test_tool(["provision", "user:mia", File.join(directory, "mia.jwt")], isolated, StandInApi.new(provisioned.call(mia), [500, {}]))
    expect.call(pending.call == ["user:mia"], "the failed group must be recorded")
    code, = self_test_tool(["acl", "user:mia", ACL_GROUP_DEFAULT], isolated, StandInApi.new(applied.call(mia, ACL_GROUP_DEFAULT)))
    expect.call(code == EXIT_OK && pending.call.empty?, "acl must settle a pending record")
    api = StandInApi.new(provisioned.call(mia))
    code, = self_test_tool(["provision", "user:mia", File.join(directory, "mia.jwt")], isolated, api)
    expect.call(code == EXIT_OK && api.requests.length == 1, "a settled group must not be overridden on the next issue")

    # remove drops a pending record with its mapping
    noor = "77777777-7777-7777-7777-777777777777"
    self_test_tool(["provision", "user:noor", File.join(directory, "noor.jwt")], isolated, StandInApi.new(provisioned.call(noor), [500, {}]))
    code, = self_test_tool(["remove", "user:noor"], isolated, StandInApi.new([200, {}]))
    expect.call(code == EXIT_OK && pending.call.empty?, "remove must drop a pending record")

    # a pending_acl that is not a list of distinct mapped keys is refused untouched
    pending_map = File.join(directory, "pending.json")
    [
      {"version" => 1, "clients" => {}, "pending_acl" => ["user:x"]},
      {"version" => 1, "clients" => {"user:x" => SELF_TEST_CLIENT}, "pending_acl" => ["user:x", "user:x"]},
      {"version" => 1, "clients" => {"user:x" => SELF_TEST_CLIENT}, "pending_acl" => "user:x"},
      {"version" => 1, "clients" => {"user:x" => SELF_TEST_CLIENT}, "pending_acl" => [1]},
    ].each do |value|
      text = JSON.generate(value)
      write_private_file(pending_map, text)
      api = StandInApi.new
      code, = self_test_tool(["provision", "user:x", jwt_path], env.merge("URNETWORK_CLIENT_MAP" => pending_map), api)
      expect.call(code == EXIT_CONFIG && File.read(pending_map) == text && api.requests.empty?, "the map #{value} must be refused untouched")
    end
  end

  # The Embed-not-enabled refusal: cap, usage, usage-all and acl exit 78 with
  # the fixed line; provision still provisions and writes the client JWT,
  # keeps the default group pending with the fixed stderr line, and a
  # provision after the team enables Embed applies it.
  def self_test_embed_not_enabled(directory, env, expect)
    embed_map = File.join(directory, "embed.json")
    isolated = env.merge("URNETWORK_CLIENT_MAP" => embed_map).reject { |name, _| name == "URNETWORK_DEFAULT_ACL_GROUP" }
    refusal = [200, {"error" => {"message" => EMBED_NOT_ENABLED_MESSAGE}}]
    pia = "88888888-8888-8888-8888-888888888888"
    pia_answer = [200, {"client_id" => pia, "by_client_jwt" => self_test_jwt(pia)}]
    code, = self_test_tool(["provision", "user:pia", File.join(directory, "pia.jwt")], isolated,
                           StandInApi.new(pia_answer, [200, {"client_id" => pia, "acl_group" => ACL_GROUP_ISOLATED}]))
    expect.call(code == EXIT_OK, "provision before the refusal failed")
    [["cap", "user:pia", "--monthly", "1"], ["usage", "user:pia"], ["usage-all"], ["acl", "user:pia", ACL_GROUP_ISOLATED]].each do |args|
      code, out, err = self_test_tool(args, isolated, StandInApi.new(refusal))
      expect.call(code == EXIT_CONFIG && out.empty? && err == "#{EMBED_NOT_ENABLED_LINE}\n", "#{args.first} while Embed isn't enabled: #{code} #{err}")
    end
    quinn = "99999999-9999-9999-9999-999999999999"
    quinn_jwt = File.join(directory, "quinn.jwt")
    quinn_answer = [200, {"client_id" => quinn, "by_client_jwt" => self_test_jwt(quinn)}]
    api = StandInApi.new(quinn_answer, refusal)
    code, out, err = self_test_tool(["provision", "user:quinn", quinn_jwt], isolated, api)
    expect.call(code == EXIT_OK && JSON.parse(out) == {"client_id" => quinn} && err == "#{EMBED_PENDING_LINE}\n", "provision while Embed isn't enabled: #{code} #{out} #{err}")
    expect.call(api.requests.map { |request| request[:path] } == [AUTH_CLIENT_ROUTE, ACL_ROUTE], "provision while Embed isn't enabled must try the group")
    expect.call(File.read(quinn_jwt) == "#{self_test_jwt(quinn)}\n" && load_map(embed_map)["pending_acl"] == ["user:quinn"],
                "provision while Embed isn't enabled must write the client JWT and keep the record")
    api = StandInApi.new(quinn_answer, [200, {"client_id" => quinn, "acl_group" => ACL_GROUP_ISOLATED}])
    code, _, err = self_test_tool(["provision", "user:quinn", quinn_jwt], isolated, api)
    expect.call(code == EXIT_OK && err.empty? && load_map(embed_map)["pending_acl"].empty?, "provision after Embed was enabled must apply the group: #{err}")
    expect.call(api.requests[1][:body] == {"client_id" => quinn, "acl_group" => ACL_GROUP_ISOLATED}, "provision after Embed was enabled must post isolated")
  end

  # The status command: GET /network/embed with the root credential, printed
  # as the fixed line for an enabled and a not enabled network; a refusal
  # exits 78; a server without the route and an invalid answer exit 1.
  def self_test_embed_status(env, expect)
    api = StandInApi.new([200, {"enabled" => true, "client_limit" => 5000, "active_client_count" => 1234}])
    code, out, err = self_test_tool(["status"], env, api)
    expect.call(code == EXIT_OK && out == "embed enabled: yes | client limit: 5000 | active clients: 1234\n" && err.empty?, "status printed #{out} #{err}")
    expect.call(api.requests == [{method: "GET", path: EMBED_ROUTE, body: nil}], "status must GET /network/embed")
    code, out, = self_test_tool(["status"], env, StandInApi.new([200, {"enabled" => false, "client_limit" => 100, "active_client_count" => 0}]))
    expect.call(code == EXIT_OK && out == "embed enabled: no | client limit: 100 | active clients: 0\n", "status while Embed isn't enabled printed #{out}")
    code, out, err = self_test_tool(["status"], env, StandInApi.new([200, {"error" => {"message" => "Invalid credential."}}]))
    expect.call(code == EXIT_CONFIG && out.empty? && err.include?("Invalid credential.") && err.count("\n") == 1, "status on a refusal: #{code} #{err}")
    code, = self_test_tool(["status"], env, StandInApi.new([401, {}]))
    expect.call(code == EXIT_CONFIG, "status with a refused root credential must exit 78")
    code, _, err = self_test_tool(["status"], env, StandInApi.new([404, {}]))
    expect.call(code == EXIT_FAILURE && err.strip == EMBED_UNSUPPORTED_MESSAGE, "status on a server without Embed enablement: #{code} #{err}")
    [
      {"enabled" => true},
      {"enabled" => "yes", "client_limit" => 1, "active_client_count" => 1},
      {"enabled" => true, "client_limit" => -1, "active_client_count" => 0},
      {"enabled" => true, "client_limit" => 1.5, "active_client_count" => 0},
      {"enabled" => 1, "client_limit" => 1, "active_client_count" => 1},
      {"enabled" => true, "client_limit" => true, "active_client_count" => 1},
    ].each do |value|
      code, out, = self_test_tool(["status"], env, StandInApi.new([200, value]))
      expect.call(code == EXIT_FAILURE && out.empty?, "status must refuse #{value}")
    end
  end

  # The unmapped-key and 404 texts.
  def self_test_failure_texts(directory, env, expect)
    texts = env.merge("URNETWORK_CLIENT_MAP" => File.join(directory, "texts.json"))
    [["cap", "user:nobody", "--monthly", "1"], ["usage", "user:nobody"], ["remove", "user:nobody"], ["acl", "user:nobody", ACL_GROUP_DEFAULT]].each do |args|
      api = StandInApi.new
      code, _, err = self_test_tool(args, texts, api)
      expect.call(code == EXIT_CONFIG && err.strip == UNMAPPED_MESSAGE && api.requests.empty?, "#{args.first} for an unmapped key must exit 78 with the unmapped line: #{err}")
    end
    olga = {"client_id" => SELF_TEST_CLIENT, "by_client_jwt" => self_test_jwt(SELF_TEST_CLIENT)}
    self_test_tool(["provision", "user:olga", File.join(directory, "olga.jwt")], texts, StandInApi.new([200, olga]))
    [[["cap", "user:olga", "--monthly", "1"], CAP_ROUTE], [["usage", "user:olga"], CAP_ROUTE], [["usage-all"], CAPS_ROUTE]].each do |args, route|
      code, _, err = self_test_tool(args, texts, StandInApi.new([404, {}]))
      expect.call(code == EXIT_FAILURE && err.strip == "#{route} answered 404: the server predates the data-cap routes", "#{args.first} on a server without the cap routes: #{err}")
    end
  end
end

exit(EmbedBackend.run(ARGV)) if File.expand_path($PROGRAM_NAME) == File.expand_path(__FILE__)
