package integration

import (
	"fmt"
	sdk "github.com/urnetwork/sdk/v2026"
	"os"
)

// Session owns the manager for at least as long as Device. JWT is client-scoped;
// the caller obtains it from its authenticated backend, never a shipped root JWT.
type Session struct {
	Device  *sdk.DeviceLocal
	manager *sdk.NetworkSpaceManager
}

func Open(connect bool) (*Session, error) {
	jwt, instance := os.Getenv("URNETWORK_CLIENT_JWT"), os.Getenv("URNETWORK_INSTANCE_ID")
	if jwt == "" || instance == "" {
		return nil, fmt.Errorf("set URNETWORK_CLIENT_JWT and a stable URNETWORK_INSTANCE_ID")
	}
	id, err := sdk.ParseId(instance)
	if err != nil {
		return nil, err
	}
	manager := sdk.NewNetworkSpaceManagerNoStorage()
	space := manager.UpdateNetworkSpaceValues(sdk.NewNetworkSpaceKey("ur.network", "main"), &sdk.NetworkSpaceValues{MigrationHostName: "bringyour.com"})
	space.GetApi().SetByJwt(jwt)
	device, err := sdk.NewDeviceLocalWithDefaults(space, jwt, "Go example", "go", "1", id, false)
	if err != nil {
		manager.Close()
		return nil, err
	}
	if connect {
		device.SetConnectLocation(&sdk.ConnectLocation{ConnectLocationId: &sdk.ConnectLocationId{BestAvailable: true}})
	}
	return &Session{device, manager}, nil
}
func (s *Session) Close() { s.Device.Close(); s.manager.Close() }
