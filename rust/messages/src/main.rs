mod codec;
#[path = "../../integration/session.rs"]
mod session;
use codec::{Frame, decode, encode};
use std::{
    ffi::{CStr, CString, c_char, c_void},
    io,
    sync::mpsc::{self, SyncSender},
    time::{Duration, Instant},
};
use urnetwork_sdk::{native, take_string};
enum Event {
    Message(String, Vec<u8>),
    Peers(Option<String>),
    Query(bool, Option<String>),
}
unsafe fn copied(p: *const c_char) -> Option<String> {
    if p.is_null() {
        None
    } else {
        Some(unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned())
    }
}
unsafe extern "C" fn message(
    context: *mut c_void,
    protocol: i64,
    source: *const c_char,
    bytes: *const u8,
    length: i32,
) {
    if protocol != 4096 || !(16..=4112).contains(&length) || source.is_null() || bytes.is_null() {
        return;
    }
    let sender = unsafe { &*context.cast::<SyncSender<Event>>() };
    let source = unsafe { CStr::from_ptr(source) }
        .to_string_lossy()
        .into_owned();
    let data = unsafe { std::slice::from_raw_parts(bytes, length as usize) }.to_vec(); // Copy before callback return.
    let _ = sender.try_send(Event::Message(source, data));
}
unsafe extern "C" fn peers(context: *mut c_void, json: *const c_char) {
    let sender = unsafe { &*context.cast::<SyncSender<Event>>() };
    let _ = sender.try_send(Event::Peers(unsafe { copied(json) }));
}
unsafe extern "C" fn query(context: *mut c_void, json: *const c_char, ok: bool) {
    let sender = unsafe { &*context.cast::<SyncSender<Event>>() };
    let _ = sender.try_send(Event::Query(ok, unsafe { copied(json) }));
}
fn show_peers(json: Option<String>) -> Result<bool, Box<dyn std::error::Error>> {
    let value: serde_json::Value = serde_json::from_str(json.as_deref().unwrap_or("null"))?;
    if value.is_null() {
        println!("peers unavailable (no snapshot)");
        return Ok(false);
    }
    println!("{}", value);
    if let Some(peers) = value["Connected"].as_array() {
        for peer in peers {
            if let Some(id) = peer["ClientId"].as_str() {
                let id_c = CString::new(id)?;
                let color = unsafe { take_string((native()?.urnet_get_color_hex)(id_c.as_ptr())) };
                println!("color {id} {}", color.as_deref().unwrap_or("unavailable"));
            }
        }
    }
    Ok(true)
}
fn send(handle: u64, destination: &str, frame: Frame) -> Result<(), Box<dyn std::error::Error>> {
    let id = CString::new(destination)?;
    let bytes = encode(&frame)?;
    if !unsafe {
        (native()?.urnet_device_local_send_subprotocol_bytes)(
            handle,
            4096,
            id.as_ptr(),
            bytes.as_ptr(),
            bytes.len() as i32,
        )
    } {
        return Err(io::Error::other("SDK did not enqueue message").into());
    }
    Ok(())
}
struct Sub(u64);
impl Drop for Sub {
    fn drop(&mut self) {
        if let Ok(raw) = native() {
            unsafe {
                (raw.urnet_sub_close)(self.0);
                (raw.urnet_release)(self.0);
            }
        }
    }
}
fn run() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args == ["--self-test"] {
        codec::self_test()?;
        println!("URMS codec self-test passed");
        return Ok(());
    }
    if args == ["--version"] {
        println!("{}", urnetwork_sdk::version()?);
        return Ok(());
    }
    if args.is_empty()
        || !["self", "peers", "watch", "send"].contains(&args[0].as_str())
        || (args[0] == "send" && args.len() < 3)
    {
        return Err(
            "usage: --self-test | --version | self | peers | watch | send CLIENT_ID TEXT".into(),
        );
    }
    let destination = if args[0] == "send" {
        Some(args[1].to_lowercase())
    } else {
        None
    };
    let raw = native()?;
    if let Some(id) = &destination {
        let id = CString::new(id.as_str())?;
        let mut error = std::ptr::null_mut();
        let parsed = unsafe { take_string((raw.urnet_parse_id)(id.as_ptr(), &mut error)) };
        if let Some(error) = unsafe { take_string(error) } {
            return Err(error.into());
        }
        if parsed.is_none() {
            return Err("invalid client ID".into());
        }
    }
    let (tx, rx) = mpsc::sync_channel(256);
    // One tiny process-lifetime callback context. A late native callback can never
    // dereference freed memory, including during Device shutdown or a query timeout.
    let callback_context = Box::leak(Box::new(tx)) as *mut SyncSender<Event> as *mut c_void;
    let session = session::Session::open_with_connect(false)?;
    let h = session.device.handle()?;
    let mut error = std::ptr::null_mut();
    let sub = unsafe {
        (raw.urnet_device_local_enable_subprotocol)(
            h,
            4096,
            Some(message),
            callback_context,
            &mut error,
        )
    };
    if let Some(error) = unsafe { take_string(error) } {
        return Err(error.into());
    }
    if sub == 0 {
        return Err("subprotocol registration failed".into());
    }
    let _sub = Sub(sub);
    let _peers_sub = Sub(unsafe {
        (raw.urnet_device_add_network_peers_change_listener)(h, Some(peers), callback_context)
    });
    unsafe { (raw.urnet_device_set_provide_mode)(h, 1) };
    println!(
        "self: {}",
        unsafe { take_string((raw.urnet_device_get_client_id)(h)) }.unwrap_or_default()
    );
    if args[0] == "self" {
        return Ok(());
    }
    if show_peers(unsafe { take_string((raw.urnet_device_get_network_peers)(h)) })?
        && args[0] == "peers"
    {
        return Ok(());
    }
    let mut queried = false;
    let mut deadline = Instant::now() + Duration::from_secs(30);
    let mut random = [0u8; 8];
    getrandom::fill(&mut random).map_err(|error| io::Error::other(error.to_string()))?;
    let pending = u64::from_be_bytes(random).max(1);
    loop {
        if let Some(id) = &destination {
            if !queried && unsafe { (raw.urnet_device_local_get_provider_connected)(h) } {
                queried = true;
                let id = CString::new(id.as_str())?;
                unsafe {
                    (raw.urnet_device_local_query_subprotocols)(
                        h,
                        id.as_ptr(),
                        10000,
                        Some(query),
                        callback_context,
                    )
                }
            }
        }
        if let Ok(event) = rx.recv_timeout(Duration::from_millis(100)) {
            match event {
                Event::Peers(json) => {
                    if show_peers(json)? && args[0] == "peers" {
                        return Ok(());
                    }
                }
                Event::Query(ok, json) => {
                    let ids: Option<Vec<i64>> =
                        serde_json::from_str(json.as_deref().unwrap_or("null"))?;
                    if !ok || !ids.unwrap_or_default().contains(&4096) {
                        return Err("peer query failed or peer does not advertise 4096".into());
                    }
                    send(
                        h,
                        destination.as_deref().ok_or("unsolicited query result")?,
                        Frame {
                            kind: 1,
                            id: pending,
                            text: args[2..].join(" "),
                        },
                    )?;
                    println!("sent: {pending} waiting for ACK");
                    deadline = Instant::now() + Duration::from_secs(10);
                }
                Event::Message(source, bytes) => {
                    let frame = match decode(&bytes) {
                        Ok(frame) => frame,
                        Err(error) => {
                            eprintln!("rejected frame: {error}");
                            continue;
                        }
                    };
                    println!(
                        "kind={} source={} id={} text={:?}",
                        frame.kind, source, frame.id, frame.text
                    );
                    if frame.kind == 1 {
                        send(
                            h,
                            &source,
                            Frame {
                                kind: 2,
                                id: frame.id,
                                text: String::new(),
                            },
                        )?
                    } else if destination.as_deref() == Some(source.as_str()) && frame.id == pending
                    {
                        return Ok(());
                    }
                }
            }
        }
        if args[0] != "watch" && Instant::now() > deadline {
            return Err("connection, peer snapshot, query, or ACK timed out".into());
        }
    }
}
fn main() {
    if let Err(error) = run() {
        eprintln!("{error}");
        std::process::exit(1)
    }
}
