using URnetwork.SDK;

public sealed class UrSession : IDisposable
{
    private Handle? manager, space, api;
    public Device Device { get; private set; } = null!;
    public UrSession()
    {
        string jwt = Environment.GetEnvironmentVariable("URNETWORK_JWT") ?? throw new ArgumentException("Set URNETWORK_JWT; see README.md.");
        string instance = Guid.Parse(Environment.GetEnvironmentVariable("URNETWORK_INSTANCE_ID") ?? throw new ArgumentException("Set a persistent URNETWORK_INSTANCE_ID.")).ToString();
        try {
            manager = new Handle(Raw.urnet_new_network_space_manager_no_storage());
            space = new Handle(Raw.urnet_network_space_manager_update_network_space_values(manager.Value,
                "{\"host_name\":\"ur.network\",\"env_name\":\"main\"}", "{\"migration_host_name\":\"bringyour.com\"}"));
            api = new Handle(Raw.urnet_network_space_get_api(space.Value));
            Raw.urnet_api_set_by_jwt(api.Value, jwt);
            ulong handle = Raw.urnet_new_device_local_with_defaults(space.Value, jwt, "C# socket example", "csharp", "1", instance, 0, out var error);
            string? message = Sdk.TakeString(error);
            if (message != null) throw new IOException(message);
            Device = new Device(handle);
            Raw.urnet_device_set_connect_location(handle, "{\"connect_location_id\":{\"best_available\":true}}");
        } catch { Dispose(); throw; }
    }
    public void Dispose()
    {
        Device?.Dispose(); api?.Dispose(); space?.Dispose();
        if (manager is {IsClosed: false}) {Raw.urnet_network_space_manager_close(manager.Value); manager.Dispose();}
    }
}
