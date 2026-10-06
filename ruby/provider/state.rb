# frozen_string_literal: true

# Installation state for one provider install, kept in one private directory
# named by URNETWORK_PROVIDER_STATE_DIR (PROVIDER_CONTRACT.md, "Installation
# state"):
#
# - client.jwt: the scoped client credential that the developer's backend
#   issued for this installation. The developer writes it; the app rewrites it
#   whenever the SDK refreshes the token.
# - instance-id: this installation's UUID, created on first run.
# - identity.json: the provider identity (client key seed, provide TLS
#   certificate and key, extender seed), created on first run so the provider
#   keeps one identity across restarts.
#
# Every file is replaced atomically with owner-only permissions, and a
# symlinked file is refused. On POSIX the directory and its files must not be
# accessible to group or others; Windows relies on the access control of the
# user's profile directory. Plain Ruby: the self-test runs it without the
# native SDK.

require "json"
require "securerandom"
require_relative "status"

module Provider
  # A configuration or credential problem that restarting does not fix (exit 78).
  class ConfigError < StandardError; end

  # A state file that the app must not read: not a regular file, too large or
  # accessible to others.
  class StateFileError < StandardError; end

  # identity.json. Byte fields are binary strings here and standard base64 in
  # JSON. An identity belongs to one client: a newly provisioned client gets a
  # new identity.
  Identity = Struct.new(
    :client_id,
    :client_key_seed,
    :provide_tls_certificate_pem,
    :provide_tls_private_key_pem,
    :extender_key_seed,
    keyword_init: true,
  )

  # The byte fields of the SDK's device key material: the client key seed, the
  # provide TLS certificate and key in the order
  # urnet_new_device_local_key_material takes them, and the extender seed for
  # urnet_device_local_key_material_set_extender_key_seed.
  KeyMaterial = Struct.new(
    :client_key_seed,
    :provide_tls_certificate_pem,
    :provide_tls_private_key_pem,
    :extender_key_seed,
    keyword_init: true,
  )

  # The installation state loaded at start. client_id is the client_id claim
  # of client_jwt; identity is nil on first run, and when the stored identity
  # belongs to another client.
  Config = Struct.new(:state_dir, :client_jwt, :client_id, :instance_id, :identity, keyword_init: true)

  # Reads and writes the installation state directory.
  module State
    CLIENT_JWT_FILE_NAME = "client.jwt"
    INSTANCE_ID_FILE_NAME = "instance-id"
    IDENTITY_FILE_NAME = "identity.json"

    # the largest state file the app reads
    STATE_FILE_BYTE_LIMIT = 64 * 1024

    PROVIDER_IDENTITY_VERSION = 1

    # a uuid in its canonical 8-4-4-4-12 form, or as 32 hex digits
    UUID_PATTERN = /\A(?:\h{8}-\h{4}-\h{4}-\h{4}-\h{12}|\h{32})\z/.freeze

    # unpadded base64url, the encoding of a JWT segment
    BASE64URL_PATTERN = /\A[A-Za-z0-9_-]*\z/.freeze

    POSIX = !Gem.win_platform?

    module_function

    # Loads the installation state, creating instance-id on first run. Every
    # error is a ConfigError: restarting does not fix it.
    def load_config(state_dir)
      check_state_dir(state_dir)
      begin
        client_jwt_bytes = read_private_file(File.join(state_dir, CLIENT_JWT_FILE_NAME))
      rescue SystemCallError, StateFileError => error
        raise ConfigError, "read #{CLIENT_JWT_FILE_NAME} from the state directory: #{error.message}"
      end
      client_jwt = text(client_jwt_bytes).strip
      client_id = parse_client_jwt_client_id(client_jwt)
      Config.new(
        state_dir: state_dir,
        client_jwt: client_jwt,
        client_id: client_id,
        instance_id: load_or_create_instance_id(state_dir),
        identity: load_identity(state_dir, client_id),
      )
    end

    # The state directory must be an existing absolute directory, private to
    # its owner on POSIX.
    def check_state_dir(state_dir)
      if state_dir.nil? || state_dir.empty?
        raise ConfigError, "set URNETWORK_PROVIDER_STATE_DIR to this installation's private state directory"
      end
      raise ConfigError, "URNETWORK_PROVIDER_STATE_DIR must be an absolute path" unless File.absolute_path?(state_dir)

      begin
        info = File.stat(state_dir)
      rescue SystemCallError => error
        raise ConfigError, "state directory: #{error.message}"
      end
      raise ConfigError, "URNETWORK_PROVIDER_STATE_DIR is not a directory" unless info.directory?
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

    # The client_id claim of a scoped client JWT. This checks the token's shape
    # and claim only; the SDK and the server verify the token itself.
    def parse_client_jwt_client_id(client_jwt)
      not_jwt = "client.jwt does not hold a JWT; write the scoped client JWT from your backend"
      parts = client_jwt.split(".", -1)
      raise ConfigError, not_jwt unless parts.length == 3 && parts.none?(&:empty?)

      payload_text = parts[1].sub(/=+\z/, "")
      raise ConfigError, not_jwt unless BASE64URL_PATTERN.match?(payload_text) && payload_text.length % 4 != 1

      begin
        payload = (payload_text.tr("-_", "+/") + ("=" * (-payload_text.length % 4))).unpack1("m0")
      rescue ArgumentError
        raise ConfigError, not_jwt
      end
      claims = Provider.parse_json_object(payload)
      client_id_claim = claims && claims["client_id"]
      unless client_id_claim.is_a?(String) && !client_id_claim.empty?
        raise ConfigError, "client.jwt has no client_id claim; write a scoped client JWT, not a network JWT"
      end

      client_id = parse_uuid(client_id_claim)
      raise ConfigError, "client.jwt has an invalid client_id claim" if client_id.nil?

      client_id
    end

    # Reads instance-id, creating it on first run. An installation keeps one
    # instance id for its lifetime.
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

    # Reads identity.json for client_id. A missing file, or an identity of
    # another client, returns nil: the device then creates a new identity,
    # which the app saves.
    def load_identity(state_dir, client_id)
      begin
        data = read_private_file(File.join(state_dir, IDENTITY_FILE_NAME))
      rescue Errno::ENOENT
        return nil
      rescue SystemCallError, StateFileError => error
        raise ConfigError, "read #{IDENTITY_FILE_NAME} from the state directory: #{error.message}"
      end
      identity = parse_identity(data)
      if identity.nil?
        raise ConfigError, "#{IDENTITY_FILE_NAME} is not a valid provider identity; remove it to create a new one"
      end
      return nil if identity.client_id != client_id

      identity
    end

    # The identity in identity.json, or nil when the file does not parse, has
    # another version or a client key seed that is not 32 bytes.
    def parse_identity(data)
      decode_bytes = lambda do |value|
        # json null and a missing field are empty, as in the go reference
        next "".b if value.nil?
        next nil unless value.is_a?(String)

        begin
          value.unpack1("m0")
        rescue ArgumentError
          nil
        end
      end
      fields = Provider.parse_json_object(data)
      return nil if fields.nil?
      return nil unless fields["version"].is_a?(Integer) && fields["version"] == PROVIDER_IDENTITY_VERSION

      # a null client id names no client, as in the go reference
      client_id = fields["client_id"] || ""
      return nil unless client_id.is_a?(String)

      byte_fields = %w[client_key_seed provide_tls_certificate_pem provide_tls_private_key_pem extender_key_seed].map do |name|
        decode_bytes.call(fields[name])
      end
      return nil if byte_fields.any?(&:nil?) || byte_fields[0].bytesize != 32

      Identity.new(
        client_id: client_id,
        client_key_seed: byte_fields[0],
        provide_tls_certificate_pem: byte_fields[1],
        provide_tls_private_key_pem: byte_fields[2],
        extender_key_seed: byte_fields[3],
      )
    end

    # Writes identity.json.
    def save_identity(state_dir, identity)
      encode_bytes = ->(value) { [value].pack("m0") }
      fields = {
        "version" => PROVIDER_IDENTITY_VERSION,
        "client_id" => identity.client_id,
        "client_key_seed" => encode_bytes.call(identity.client_key_seed),
        "provide_tls_certificate_pem" => encode_bytes.call(identity.provide_tls_certificate_pem),
        "provide_tls_private_key_pem" => encode_bytes.call(identity.provide_tls_private_key_pem),
      }
      unless identity.extender_key_seed.nil? || identity.extender_key_seed.empty?
        fields["extender_key_seed"] = encode_bytes.call(identity.extender_key_seed)
      end
      write_private_file(File.join(state_dir, IDENTITY_FILE_NAME), JSON.generate(fields))
    end

    # The key material that recreates the device's identity. nil without an
    # identity (the first run, or another client's identity): the device then
    # makes a new identity, which the app saves.
    def key_material(identity)
      return nil if identity.nil?

      KeyMaterial.new(
        client_key_seed: identity.client_key_seed,
        provide_tls_certificate_pem: identity.provide_tls_certificate_pem,
        provide_tls_private_key_pem: identity.provide_tls_private_key_pem,
        extender_key_seed: identity.extender_key_seed || "".b,
      )
    end
  end
end
