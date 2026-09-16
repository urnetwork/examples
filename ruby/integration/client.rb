require "urnetwork"

# Owned bootstrap shared by sockets and messages. Credentials come from the backend.
class UrSession
  attr_reader :device
  def initialize(connect: true)
    @handles = []
    @manager = 0
    jwt, id = ENV.fetch("URNETWORK_CLIENT_JWT"), ENV.fetch("URNETWORK_INSTANCE_ID")
    raise "URNETWORK_INSTANCE_ID must be a persistent UUID" unless /\A[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\z/.match?(id)
    raw = URnetwork::Raw
    begin
      @manager = raw.urnet_new_network_space_manager_no_storage
      @handles << URnetwork::Handle.new(@manager)
      space = raw.urnet_network_space_manager_update_network_space_values(@manager,
        JSON.generate(host_name: "ur.network", env_name: "main"), JSON.generate(migration_host_name: "bringyour.com"))
      @handles << URnetwork::Handle.new(space)
      api = raw.urnet_network_space_get_api(space)
      @handles << URnetwork::Handle.new(api)
      raw.urnet_api_set_by_jwt(api, jwt)
      error = FFI::MemoryPointer.new(:pointer)
      handle = raw.urnet_new_device_local_with_defaults(space, jwt, "Ruby example", "ruby", "1", id, false, error)
      message = URnetwork.take_string(error.read_pointer)
      raise message if message
      @device = URnetwork::Device.new(handle)
      raw.urnet_device_set_connect_location(handle, JSON.generate(connect_location_id: {best_available: true})) if connect
    rescue Exception
      close
      raise
    end
  end
  def close
    @device&.close
    URnetwork::Raw.urnet_network_space_manager_close(@manager) if @manager != 0
    @manager = 0
    @handles.reverse_each(&:close)
  end
end
