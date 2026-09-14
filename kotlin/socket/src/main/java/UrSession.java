import io.ur.sdk.Sdk;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.util.UUID;

public final class UrSession implements AutoCloseable {
    private Sdk.Handle manager, space, api;
    public Sdk.Device device;
    public UrSession() throws IOException {
        String jwt = System.getenv("URNETWORK_JWT"), instance = System.getenv("URNETWORK_INSTANCE_ID");
        if (jwt == null || instance == null) throw new IOException("Set URNETWORK_JWT and a persistent URNETWORK_INSTANCE_ID; see README.md.");
        instance = UUID.fromString(instance).toString();
        try {
            manager = new Sdk.Handle(Sdk.raw.urnet_new_network_space_manager_no_storage());
            space = new Sdk.Handle(Sdk.raw.urnet_network_space_manager_update_network_space_values(manager.handle(),
                "{\"host_name\":\"ur.network\",\"env_name\":\"main\"}", "{\"migration_host_name\":\"bringyour.com\"}"));
            api = new Sdk.Handle(Sdk.raw.urnet_network_space_get_api(space.handle()));
            Sdk.raw.urnet_api_set_by_jwt(api.handle(), jwt);
            PointerByReference error = new PointerByReference();
            long handle = Sdk.raw.urnet_new_device_local_with_defaults(space.handle(), jwt, "JVM socket example", "jvm", "1", instance, (byte)0, error);
            String message = Sdk.takeString(error.getValue());
            if (message != null) throw new IOException(message);
            device = new Sdk.Device(handle);
            Sdk.raw.urnet_device_set_connect_location(handle, "{\"connect_location_id\":{\"best_available\":true}}");
        } catch (Throwable error) {close(); throw error;}
    }
    @Override public void close() {
        if (device != null) {device.close(); device = null;}
        if (api != null) {api.close(); api = null;}
        if (space != null) {space.close(); space = null;}
        if (manager != null) {Sdk.raw.urnet_network_space_manager_close(manager.handle()); manager.close(); manager = null;}
    }
}
