mod session;
mod ur_http;
use std::{io::{self, Read, Write}, time::{Duration, SystemTime, UNIX_EPOCH}};
use urnetwork_sdk::{native, take_string};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = std::env::args().collect();
    let mode = args.get(1).map(String::as_str).unwrap_or("ureq");
    if mode == "--version" {println!("{}", urnetwork_sdk::version()?); return Ok(());}
    if mode == "--new-id" {println!("{}", unsafe {take_string((native()?.urnet_new_id)()).unwrap()}); return Ok(());}
    let target = args.get(2).map(String::as_str).unwrap_or("https://example.com/");
    if mode == "reqwest" {
        // reqwest's connector_layer wraps its existing connector. Its public
        // builder cannot substitute arbitrary Conn I/O. Use the Go proxy.
        let proxy = std::env::var("URNETWORK_HTTP_PROXY")?;
        let client = reqwest::blocking::Client::builder().proxy(reqwest::Proxy::all(proxy)?)
            .timeout(Duration::from_secs(30)).http1_only().build()?;
        let response = client.get(target).send()?;
        println!("{}\n{}", response.status(), response.text()?); return Ok(());
    }
    let session = session::Session::open()?;
    match mode {
        "ureq" => {
            let config = ureq::Agent::config_builder().proxy(None).max_idle_connections(0)
                .timeout_global(Some(Duration::from_secs(30))).build();
            let agent = ureq::Agent::with_parts(config, ur_http::UrConnector(session.device.clone()), ur_http::DeferredDNS);
            let mut response = agent.get(target).call()?;
            println!("{}\n{}", response.status(), response.body_mut().read_to_string()?);
        }
        "tls" | "udp" | "dtls" => {
            let target = args.get(2).ok_or("Raw socket modes require host:port")?;
            let mut conn = if mode == "udp" {session.device.dial("udp", target, 30_000)?}
                else {session.device.dial_tls(if mode == "dtls" {"udp"} else {"tcp"}, target, 30_000, "{}")?};
            conn.set_deadline((SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis() + 10_000) as i64)?;
            if mode == "tls" {
                conn.write_all(format!("GET / HTTP/1.1\r\nHost: {target}\r\nConnection: close\r\n\r\n").as_bytes())?;
                io::copy(&mut conn.take(1_048_576), &mut io::stdout())?;
            } else {
                let sent = conn.send(b"hello")?;
                if let Some(error) = sent.error {return Err(io::Error::other(error).into());}
                let reply = conn.recv(65_535)?;
                println!("Datagram: {:?}; EOF: {}", reply.data, reply.eof);
                if let Some(error) = reply.error {return Err(io::Error::other(error).into());}
            }
        }
        _ => return Err("Modes: ureq, reqwest, tls, udp, dtls".into()),
    }
    Ok(())
}
