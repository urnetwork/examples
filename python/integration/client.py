"""Shared local-device lifetime. Receive a scoped client JWT from your backend."""

import ctypes as C
from contextlib import ExitStack, contextmanager
import os
import uuid
from urnetwork import Device, Handle, raw


def take_string(pointer):
    if not pointer:
        return None
    try:
        return C.string_at(pointer).decode("utf-8")
    finally:
        raw.urnet_free_string(pointer)


@contextmanager
def local_device(connect=True):
    jwt = os.environ.get("URNETWORK_CLIENT_JWT")
    instance = os.environ.get("URNETWORK_INSTANCE_ID")
    if not jwt or not instance:
        raise ValueError(
            "Set URNETWORK_CLIENT_JWT and a persistent URNETWORK_INSTANCE_ID; see README.md."
        )
    instance = str(uuid.UUID(instance))
    with ExitStack() as stack:
        manager = stack.enter_context(
            Handle(raw.urnet_new_network_space_manager_no_storage())
        )
        stack.callback(raw.urnet_network_space_manager_close, manager.handle)
        space = stack.enter_context(
            Handle(
                raw.urnet_network_space_manager_update_network_space_values(
                    manager.handle,
                    b'{"host_name":"ur.network","env_name":"main"}',
                    b'{"migration_host_name":"bringyour.com"}',
                )
            )
        )
        api = stack.enter_context(Handle(raw.urnet_network_space_get_api(space.handle)))
        raw.urnet_api_set_by_jwt(api.handle, jwt.encode())
        error = C.c_void_p()
        handle = raw.urnet_new_device_local_with_defaults(
            space.handle,
            jwt.encode(),
            b"Python example",
            b"python",
            b"1",
            instance.encode(),
            False,
            C.byref(error),
        )
        message = take_string(error.value)
        if message:
            raise OSError(message)
        device = stack.enter_context(Device(handle))
        if connect:
            raw.urnet_device_set_connect_location(
                device.handle, b'{"connect_location_id":{"best_available":true}}'
            )
        yield device
