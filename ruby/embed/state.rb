# frozen_string_literal: true

# Installation state for one embed installation, kept in one private directory
# named by URNETWORK_EMBED_STATE_DIR (EMBED_CONTRACT.md, "Installation state"):
#
# - client.jwt: the scoped client JWT. With a token server configured the app
#   fetches and writes it at every start; otherwise a backend tool's
#   `provision` or the developer writes it. The app rewrites it whenever the
#   SDK refreshes the token. It is a bearer secret: never printed or logged.
# - instance-id: this installation's UUID, created on first run and kept for
#   the life of the installation. It is also the installation ID that the app
#   sends to the token server.
# - logs/: the SDK's bounded log files.
#
# Every file is replaced atomically with owner-only permissions, and a
# symlinked file is refused. On POSIX the directory and its files must not be
# accessible to group or others; Windows relies on the access control of the
# user's profile directory (keep the directory under %LOCALAPPDATA%). Plain
# Ruby: the self-test runs it without the native SDK.

require "json"
require "securerandom"

module Embed
  # A configuration or credential problem that restarting does not fix (exit 78).
  class ConfigError < StandardError; end

  # A state file that the app must not read: not a regular file, too large or
  # accessible to others.
  class StateFileError < StandardError; end

  # A JSON object from text (the C ABI's strings, an answer body), or nil for
  # nil, JSON null or anything that is not an object.
  def self.parse_json_object(text)
    return nil if text.nil? || text.empty?

    value = JSON.parse(text)
    value.is_a?(Hash) ? value : nil
  rescue JSON::ParserError, EncodingError
    nil
  end

  # Reads and writes the installation state directory.
  module State
    STATE_DIR_SETTING = "URNETWORK_EMBED_STATE_DIR"

    CLIENT_JWT_FILE_NAME = "client.jwt"
    INSTANCE_ID_FILE_NAME = "instance-id"
    LOG_DIR_NAME = "logs"

    # the largest state file the app reads
    STATE_FILE_BYTE_LIMIT = 64 * 1024

    # a uuid in its canonical 8-4-4-4-12 form, or as 32 hex digits
    UUID_PATTERN = /\A(?:\h{8}-\h{4}-\h{4}-\h{4}-\h{12}|\h{32})\z/.freeze

    # unpadded base64url, the encoding of a JWT segment
    BASE64URL_PATTERN = /\A[A-Za-z0-9_-]*\z/.freeze

    POSIX = !Gem.win_platform?

    module_function

    # The state directory must be an existing absolute directory, private to
    # its owner on POSIX.
    def check_state_dir(state_dir)
      if state_dir.nil? || state_dir.empty?
        raise ConfigError, "set #{STATE_DIR_SETTING} to this installation's private state directory"
      end
      raise ConfigError, "#{STATE_DIR_SETTING} must be an absolute path" unless File.absolute_path?(state_dir)

      begin
        info = File.stat(state_dir)
      rescue SystemCallError => error
        raise ConfigError, "state directory: #{error.message}"
      end
      raise ConfigError, "#{STATE_DIR_SETTING} is not a directory" unless info.directory?
      raise ConfigError, "the state directory must be private to its owner (chmod 700)" if POSIX && (info.mode & 0o077) != 0
    end

    # Reads a regular, private state file of bounded size, as a binary string.
    # A symlink is refused so that the credential cannot be redirected to
    # another file. Raises Errno::ENOENT when the file does not exist.
    def read_private_file(path)
      info = File.lstat(path)
      raise StateFileError, "not a regular file" unless info.file?
      raise StateFileError, "file is too large" if STATE_FILE_BYTE_LIMIT < info.size
      raise StateFileError, "file must be private to its owner (chmod 600)" if POSIX && (info.mode & 0o077) != 0

      flags = File::RDONLY | File::BINARY
      # no-follow closes the window between the check and the open where POSIX has it
      flags |= File::NOFOLLOW if defined?(File::NOFOLLOW)
      data = File.open(path, flags) { |file| file.read(STATE_FILE_BYTE_LIMIT + 1) } || "".b
      raise StateFileError, "file is too large" if STATE_FILE_BYTE_LIMIT < data.bytesize

      data
    end

    # Replaces a state file atomically: a private temporary file in the same
    # directory is written, synced and renamed over the old file.
    def write_private_file(path, data)
      temp_path = File.join(File.dirname(path), ".#{File.basename(path)}.#{SecureRandom.hex(8)}")
      file = File.open(temp_path, File::WRONLY | File::CREAT | File::EXCL | File::BINARY, 0o600)
      begin
        # the umask can only narrow 0o600; this keeps it exact
        file.chmod(0o600) if POSIX
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

    # The UTF-8 text of file bytes, with invalid bytes replaced.
    def text(data)
      data.dup.force_encoding(Encoding::UTF_8).scrub
    end

    # The canonical lowercase form of a UUID, or nil when value is not one.
    def parse_uuid(value)
      return nil unless value.is_a?(String) && UUID_PATTERN.match?(value)

      hex = value.delete("-").downcase
      "#{hex[0, 8]}-#{hex[8, 4]}-#{hex[12, 4]}-#{hex[16, 4]}-#{hex[20, 12]}"
    end

    # The client_id claim of a scoped client JWT, in canonical form. This
    # checks the token's shape and claim only; the SDK and the server verify
    # the token itself. A network JWT has no client_id claim and is refused.
    def parse_client_jwt_client_id(client_jwt)
      not_jwt = "client.jwt does not hold a JWT; fetch one from your token server or write the scoped client JWT from your backend"
      parts = client_jwt.split(".", -1)
      raise ConfigError, not_jwt unless parts.length == 3 && parts.none?(&:empty?)

      payload_text = parts[1].sub(/=+\z/, "")
      raise ConfigError, not_jwt unless BASE64URL_PATTERN.match?(payload_text) && payload_text.length % 4 != 1

      begin
        payload = (payload_text.tr("-_", "+/") + ("=" * (-payload_text.length % 4))).unpack1("m0")
      rescue ArgumentError
        raise ConfigError, not_jwt
      end
      claims = Embed.parse_json_object(text(payload))
      client_id_claim = claims && claims["client_id"]
      unless client_id_claim.is_a?(String) && !client_id_claim.empty?
        raise ConfigError, "client.jwt has no client_id claim; use a scoped client JWT, not a network JWT"
      end

      client_id = parse_uuid(client_id_claim)
      raise ConfigError, "client.jwt has an invalid client_id claim" if client_id.nil?

      client_id
    end

    # Reads instance-id, creating it on first run. An installation keeps one
    # instance id for its lifetime; it is also its installation ID.
    def load_or_create_instance_id(state_dir)
      path = File.join(state_dir, INSTANCE_ID_FILE_NAME)
      begin
        data = read_private_file(path)
      rescue Errno::ENOENT
        data = nil
      rescue SystemCallError, StateFileError => error
        raise ConfigError, "read #{INSTANCE_ID_FILE_NAME} from the state directory: #{error.message}"
      end
      unless data.nil?
        instance_id = parse_uuid(text(data).strip)
        raise ConfigError, "#{INSTANCE_ID_FILE_NAME} does not hold a UUID" if instance_id.nil?

        return instance_id
      end
      instance_id = SecureRandom.uuid
      begin
        write_private_file(path, "#{instance_id}\n")
      rescue SystemCallError => error
        raise ConfigError, "write #{INSTANCE_ID_FILE_NAME}: #{error.message}"
      end
      instance_id
    end

    # The client JWT in client.jwt with surrounding whitespace removed, or nil
    # when the file does not exist.
    def load_client_jwt(state_dir)
      text(read_private_file(File.join(state_dir, CLIENT_JWT_FILE_NAME))).strip
    rescue Errno::ENOENT
      nil
    rescue SystemCallError, StateFileError => error
      raise ConfigError, "read #{CLIENT_JWT_FILE_NAME} from the state directory: #{error.message}"
    end

    # Writes client.jwt atomically. Raises SystemCallError.
    def save_client_jwt(state_dir, client_jwt)
      write_private_file(File.join(state_dir, CLIENT_JWT_FILE_NAME), "#{client_jwt}\n")
    end
  end
end
