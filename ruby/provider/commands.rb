# frozen_string_literal: true

# The provider example's commands and exit codes: run (the default),
# --self-test and --version. Loading this file starts nothing, so the
# self-test and the tests can call Provider.run; main.rb runs it for the
# command line. The self-test needs only Ruby; the other commands load the
# urnetwork gem through Bundler.
#
# Exit codes, for supervisors: 0 stopped on request, 78 configuration or
# credential problem (restarting does not help), 1 any other failure.

require_relative "state"
require_relative "status"

module Provider
  EXIT_STOPPED = 0
  EXIT_FAILURE = 1
  # sysexits EX_CONFIG
  EXIT_CONFIG = 78

  USAGE = "usage: main.rb [run] | --self-test | --version"

  # Runs one command and returns the exit code.
  def self.run(args)
    case args
    when ["--self-test"]
      run_self_test
    when ["--version"]
      sdk = load_sdk
      return EXIT_FAILURE if sdk.nil?

      puts sdk.version
      EXIT_STOPPED
    when [], ["run"]
      run_provider
    else
      warn USAGE
      EXIT_CONFIG
    end
  end

  # Runs the self-test and returns the exit code.
  def self.run_self_test
    require_relative "selftest"
    begin
      SelfTest.run
    rescue StandardError => error
      warn "provider self-test failed: #{error.message}"
      return EXIT_FAILURE
    end
    puts "provider self-test passed"
    EXIT_STOPPED
  end

  # Provides until a stop request and returns the exit code.
  def self.run_provider
    puts CONSENT_DISCLAIMER
    begin
      config = State.load_config(ENV.fetch("URNETWORK_PROVIDER_STATE_DIR", ""))
    rescue ConfigError => error
      warn error.message
      return EXIT_CONFIG
    end
    sdk = load_sdk
    return EXIT_FAILURE if sdk.nil?

    require_relative "session"
    begin
      Session.configure_sdk_logs(sdk, File.join(config.state_dir, "logs"))
    rescue StandardError => error
      warn "could not set the sdk log directory: #{error.message}"
      return EXIT_FAILURE
    end

    # the run loop's events; a stop request is one of them. Queue#push works in
    # a trap handler, and the run loop wakes for it within a poll interval.
    events = Queue.new
    %w[INT TERM].each do |signal_name|
      Signal.trap(signal_name) { events.push([Session::EVENT_STOP]) } if Signal.list.key?(signal_name)
    end
    begin
      session = Session.new(config, sdk, events)
    rescue StandardError => error
      warn "could not start the provider: #{error.message}"
      return EXIT_FAILURE
    end
    begin
      puts "provider client #{config.client_id}, instance #{config.instance_id}"
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

  # The URnetwork module of the urnetwork gem, set up by Bundler from this
  # directory's Gemfile wherever the app starts from; nil after printing why it
  # does not load.
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
