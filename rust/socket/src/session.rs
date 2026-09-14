use std::{ffi::CString, io, sync::Arc};
use urnetwork_sdk::{native, take_string, Device, Handle};

pub struct Session {
    pub device: Arc<Device>,
    manager: Handle,
    _space: Handle,
    _api: Handle,
}
impl Drop for Session {
    fn drop(&mut self) {
        self.device.close();
        if let Ok(h) = self.manager.value() { unsafe { (native().unwrap().urnet_network_space_manager_close)(h); } }
    }
}
impl Session {
    pub fn open() -> Result<Self, Box<dyn std::error::Error>> {
        let jwt = CString::new(std::env::var("URNETWORK_JWT")?)?;
        let id = CString::new(std::env::var("URNETWORK_INSTANCE_ID")?)?;
        let raw = native()?;
        unsafe {
            let manager = Handle::from_owned((raw.urnet_new_network_space_manager_no_storage)())?;
            let space = Handle::from_owned((raw.urnet_network_space_manager_update_network_space_values)(manager.value()?,
                c"{\"host_name\":\"ur.network\",\"env_name\":\"main\"}".as_ptr(), c"{\"migration_host_name\":\"bringyour.com\"}".as_ptr()))?;
            let api = Handle::from_owned((raw.urnet_network_space_get_api)(space.value()?))?;
            (raw.urnet_api_set_by_jwt)(api.value()?, jwt.as_ptr());
            let mut error = std::ptr::null_mut();
            let handle = (raw.urnet_new_device_local_with_defaults)(space.value()?, jwt.as_ptr(), c"Rust socket example".as_ptr(),
                c"rust".as_ptr(), c"1".as_ptr(), id.as_ptr(), false, &mut error);
            if let Some(message) = take_string(error) {
                (raw.urnet_network_space_manager_close)(manager.value()?);
                return Err(io::Error::other(message).into());
            }
            let device = Arc::new(Device::from_owned(handle)?);
            (raw.urnet_device_set_connect_location)(handle, c"{\"connect_location_id\":{\"best_available\":true}}".as_ptr());
            Ok(Self {device, manager, _space: space, _api: api})
        }
    }
}
