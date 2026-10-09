# frozen_string_literal: true

# The Ruby embed example: a console app for Windows, macOS and Linux that
# embeds the URnetwork SDK in your own product (EMBED_CONTRACT.md). It obtains
# this installation's scoped client JWT from your token server (or uses the
# one your backend wrote), starts a local device with it, and shows the status
# and this client's own data caps. The device carries only the app's own
# traffic: continue with the Sockets and Messages examples.
#
# Usage: ruby main.rb [run] | --self-test | --licenses | --version. The
# settings, commands and exit codes are in commands.rb.

require_relative "commands"

$stdout.sync = true
exit_code =
  begin
    Embed.run(ARGV)
  rescue Interrupt
    # ctrl-c before the app installed its own handler
    Embed::EXIT_STOPPED
  end
exit(exit_code)
