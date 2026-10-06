# frozen_string_literal: true

# The Ruby provider example: a console app for Windows, macOS and Linux that
# runs a URnetwork provider for the developer's network and shows its status
# (PROVIDER_CONTRACT.md). It provides publicly with the scoped client
# credential that the developer's backend issued for this installation; the
# payout wallet is mapped by the backend and is only displayed here.
#
# Usage: ruby main.rb [run] | --self-test | --version. All installation state
# is in the private directory named by URNETWORK_PROVIDER_STATE_DIR (state.rb).
# The commands and exit codes are in commands.rb.

require_relative "commands"

$stdout.sync = true
exit_code =
  begin
    Provider.run(ARGV)
  rescue Interrupt
    # ctrl-c before the provider installed its own handler
    Provider::EXIT_STOPPED
  end
exit(exit_code)
