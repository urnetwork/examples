# frozen_string_literal: true

# The embed example's commands and exit codes: run (the default), --self-test,
# --licenses (the SDK's licenses and data attributions, as JSON, to publish
# with the app) and --version. Loading this file starts nothing, so the
# self-test and the
# tests can call Embed.run; main.rb runs it for the command line. The
# self-test needs only Ruby; the other commands load the urnetwork gem
# through Bundler.
#
# Settings:
# - URNETWORK_EMBED_STATE_DIR: the installation's private state directory
#   (state.rb), absolute. Required.
# - URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION: the token server
#   origin and the demo session token. Optional, as a pair; without them the
#   app uses the client.jwt already in the state directory.
# - URNETWORK_API_URL: the API origin for the cap reads, default
#   https://api.bringyour.com.
#
# Exit codes, for supervisors: 0 stopped on request, 78 configuration or
# credential problem (restarting does not help), 1 any other failure.

require "rbconfig"
require_relative "client_token"
require_relative "state"
require_relative "status"
require_relative "transport"

module Embed
  EXIT_STOPPED = 0
  EXIT_FAILURE = 1
  # sysexits EX_CONFIG
  EXIT_CONFIG = 78

  USAGE = "usage: main.rb [run] | --self-test | --licenses | --version"

  TOKEN_SERVER_URL_SETTING = "URNETWORK_TOKEN_SERVER_URL"
  DEMO_SESSION_SETTING = "URNETWORK_DEMO_SESSION"
  API_URL_SETTING = "URNETWORK_API_URL"

  # Runs one command and returns the exit code. transport makes the token
  # server and cap requests; the self-test passes a stand-in.
  def self.run(args, transport: Transport::NET_HTTP)
    case args
    when ["--self-test"]
      run_self_test
    when ["--version"]
      sdk = load_sdk
      return EXIT_FAILURE if sdk.nil?

      puts sdk.version
      EXIT_STOPPED
    when ["--licenses"]
      sdk = load_sdk
      return EXIT_FAILURE if sdk.nil?

      print_licenses(sdk)
    when [], ["run"]
      run_embed(transport)
    else
      warn USAGE
      EXIT_CONFIG
    end
  end

  # Prints the SDK's licenses for this kind of app and returns the exit code.
  def self.print_licenses(sdk, host_os = RbConfig::CONFIG["host_os"])
    licenses = sdk.take_string(sdk::Raw.urnet_get_licenses(Embed.license_app(host_os)))
    if licenses.nil?
      warn "the sdk returned no licenses"
      return EXIT_FAILURE
    end
    puts licenses
    EXIT_STOPPED
  end

  # Runs the self-test and returns the exit code.
  def self.run_self_test
    require_relative "selftest"
    begin
      SelfTest.run
    rescue StandardError => error
      warn "embed self-test failed: #{error.message}"
      return EXIT_FAILURE
    end
    puts "embed self-test passed"
    EXIT_STOPPED
  end

  # Obtains the client JWT, runs the device until a stop request and returns
  # the exit code.
  def self.run_embed(transport)
    state_dir = ENV.fetch(State::STATE_DIR_SETTING, "")
    begin
      State.check_state_dir(state_dir)
      instance_id = State.load_or_create_instance_id(state_dir)
      api_url = ENV.fetch(API_URL_SETTING, "")
      api_origin = Transport.check_origin(api_url.empty? ? Transport::DEFAULT_API_URL : api_url, API_URL_SETTING)
      client_jwt, first_cap_reading = obtain_client_jwt(state_dir, instance_id, transport)
      client_id = State.parse_client_jwt_client_id(client_jwt)
    rescue ConfigError, TokenServerRefused => error
      warn error.message
      return EXIT_CONFIG
    rescue TokenServerError => error
      warn error.message
      return EXIT_FAILURE
    end

    sdk = load_sdk
    return EXIT_FAILURE if sdk.nil?

    require_relative "session"
    begin
      Session.configure_sdk_logs(sdk, File.join(state_dir, State::LOG_DIR_NAME))
    rescue StandardError => error
      warn "could not set the sdk log directory: #{error.message}"
      return EXIT_FAILURE
    end

    # the run loop's events; a stop request is one of them. Queue#push works
    # in a trap handler, and the run loop wakes for it within a poll interval.
    events = Queue.new
    %w[INT TERM].each do |signal_name|
      Signal.trap(signal_name) { events.push([EVENT_STOP]) } if Signal.list.key?(signal_name)
    end
    config = Config.new(
      state_dir: state_dir,
      client_jwt: client_jwt,
      client_id: client_id,
      instance_id: instance_id,
      api_origin: api_origin,
      first_cap_reading: first_cap_reading,
    )
    begin
      session = Session.new(config, sdk, events, transport)
    rescue StandardError => error
      warn "could not start the device: #{error.message}"
      return EXIT_FAILURE
    end
    begin
      puts Embed.start_line(client_id, instance_id)
      session.run
      EXIT_STOPPED
    rescue ConfigError => error
      # the server rejected the client credential
      warn error.message
      EXIT_CONFIG
    ensure
      session.close
    end
  end

  # The client JWT and the first cap reading (nil to read at start): from the
  # token server when one is configured, otherwise the client.jwt in the
  # state directory. Raises ConfigError, TokenServerRefused or
  # TokenServerError.
  def self.obtain_client_jwt(state_dir, instance_id, transport)
    token_server_url = ENV.fetch(TOKEN_SERVER_URL_SETTING, "")
    demo_session = ENV.fetch(DEMO_SESSION_SETTING, "")
    unless token_server_url.empty? && demo_session.empty?
      if token_server_url.empty? || demo_session.empty?
        raise ConfigError, "set both #{TOKEN_SERVER_URL_SETTING} and #{DEMO_SESSION_SETTING}, or neither"
      end
      unless demo_session.match?(/\A[[:graph:]]+\z/)
        raise ConfigError, "#{DEMO_SESSION_SETTING} must be one printable token without spaces"
      end

      token_server_origin = Transport.check_origin(token_server_url, TOKEN_SERVER_URL_SETTING)
      fetched = ClientToken.fetch_client_jwt(state_dir, instance_id, token_server_origin, demo_session, transport)
      return [fetched.client_jwt, fetched.data_cap]
    end
    client_jwt = State.load_client_jwt(state_dir)
    if client_jwt.nil? || client_jwt.empty?
      raise ConfigError,
            "no token server and no client.jwt: set #{TOKEN_SERVER_URL_SETTING} and #{DEMO_SESSION_SETTING}, " \
            "or write client.jwt with your backend tool's provision"
    end
    [client_jwt, nil]
  end

  # The URnetwork module of the urnetwork gem, set up by Bundler from this
  # directory's Gemfile wherever the app starts from; nil after printing why
  # it does not load.
  def self.load_sdk
    ENV["BUNDLE_GEMFILE"] ||= File.expand_path("Gemfile", __dir__)
    require "bundler"
    # stdout carries the status lines; bundler raises its errors, and its
    # progress messages are not for the app's output
    Bundler.ui.silence { Bundler.setup(:default) }
    require "urnetwork"
    URnetwork
  rescue LoadError, StandardError => error
    warn "could not load the urnetwork gem: #{error.message}"
    nil
  end
end
