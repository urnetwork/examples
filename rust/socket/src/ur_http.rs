use std::{fmt, io::{self, Read, Write}, sync::Arc, time::{Duration, SystemTime, UNIX_EPOCH}};
use urnetwork_sdk::{Conn, Device};
use ureq::{config::Config, http::Uri, unversioned::{resolver::{Resolver, ResolvedSocketAddrs}, transport::{Buffers, ConnectionDetails, Connector, LazyBuffers, NextTimeout, Transport}}};

// This is deliberately paired with UrConnector. The placeholder is never
// dialed: the connector passes the URI hostname unchanged to the SDK.
#[derive(Debug)]
pub struct DeferredDNS;
impl Resolver for DeferredDNS {
    fn resolve(&self, _: &Uri, _: &Config, _: NextTimeout) -> Result<ResolvedSocketAddrs, ureq::Error> {
        let mut addrs = self.empty(); addrs.push(([0,0,0,0], 0).into()); Ok(addrs)
    }
}
pub struct UrConnector(pub Arc<Device>);
impl fmt::Debug for UrConnector {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {f.write_str("UrConnector")}
}
fn millis(timeout: NextTimeout) -> i64 {
    timeout.not_zero().map(|d| d.as_millis().clamp(1, 2_147_483_647) as i64).unwrap_or(30_000)
}
fn deadline(timeout: NextTimeout) -> i64 {
    (SystemTime::now().duration_since(UNIX_EPOCH).unwrap() + Duration::from_millis(millis(timeout) as u64)).as_millis() as i64
}
impl Connector<()> for UrConnector {
    type Out = UrTransport;
    fn connect(&self, details: &ConnectionDetails, _: Option<()>) -> Result<Option<Self::Out>, ureq::Error> {
        if details.config.proxy().is_some() {return Err(io::Error::other("Use the UR connector without an additional HTTP proxy").into());}
        let tls = details.uri.scheme_str() == Some("https");
        let host = details.uri.host().ok_or_else(|| io::Error::other("Missing hostname"))?;
        let port = details.uri.port_u16().unwrap_or(if tls {443} else {80});
        let host = host.trim_start_matches('[').trim_end_matches(']');
        let address = if host.contains(':') {format!("[{host}]:{port}")} else {format!("{host}:{port}")};
        let conn = if tls {self.0.dial_tls("tcp", &address, millis(details.timeout), "{\"nextProtos\":[\"http/1.1\"]}")?}
                   else {self.0.dial("tcp", &address, millis(details.timeout))?};
        Ok(Some(UrTransport {conn, buffers: LazyBuffers::new(details.config.input_buffer_size(), details.config.output_buffer_size()), tls}))
    }
}
pub struct UrTransport { conn: Conn, buffers: LazyBuffers, tls: bool }
impl fmt::Debug for UrTransport {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {f.debug_struct("UrTransport").field("tls", &self.tls).finish()}
}
impl Transport for UrTransport {
    fn buffers(&mut self) -> &mut dyn Buffers {&mut self.buffers}
    fn transmit_output(&mut self, amount: usize, timeout: NextTimeout) -> Result<(), ureq::Error> {
        self.conn.set_write_deadline(deadline(timeout))?;
        self.conn.write_all(&self.buffers.output()[..amount])?; Ok(())
    }
    fn await_input(&mut self, timeout: NextTimeout) -> Result<bool, ureq::Error> {
        self.conn.set_read_deadline(deadline(timeout))?;
        let count = self.conn.read(self.buffers.input_append_buf())?;
        self.buffers.input_appended(count); Ok(count != 0)
    }
    // This example disables idle pooling: Conn has no nonblocking peek for a
    // safe reusable-connection probe. Active request I/O uses read/write above.
    fn is_open(&mut self) -> bool {false}
    fn is_tls(&self) -> bool {self.tls}
}
