//! A loopback HTTP stand-in for the token server and the API, for the credential-free self-test and
//! the Tauri app's tests: it answers each connection with the next scripted answer, in order, and
//! records the requests. Plain HTTP on `127.0.0.1`, which the origin rules accept for local testing.
//! It never waits long: accepting stops at a deadline or when the stand-in is dropped.

use std::{
    io::{self, Read, Write},
    net::{Shutdown, TcpListener, TcpStream},
    sync::{
        Arc, Mutex, PoisonError,
        atomic::{AtomicBool, Ordering},
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

/// How long the stand-in waits for each connection.
const ACCEPT_DEADLINE: Duration = Duration::from_secs(10);

/// How long one connection may take to send its request.
const IO_TIMEOUT: Duration = Duration::from_secs(5);

/// The largest request the stand-in reads.
const REQUEST_BYTE_LIMIT: usize = 64 * 1024;

/// One scripted answer.
#[derive(Clone, Debug)]
pub struct StandInAnswer {
    pub status: u16,
    pub body: String,
}

impl StandInAnswer {
    /// An answer with a JSON body.
    pub fn json(status: u16, body: impl Into<String>) -> Self {
        Self {
            status,
            body: body.into(),
        }
    }
}

/// One recorded request.
#[derive(Clone, Debug, Default)]
pub struct StandInRequest {
    pub method: String,
    /// the request target: path and query
    pub target: String,
    /// header names in lowercase
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl StandInRequest {
    /// The first value of a header, by case-insensitive name.
    pub fn header(&self, name: &str) -> Option<&str> {
        let name = name.to_ascii_lowercase();
        self.headers
            .iter()
            .find(|(header_name, _)| *header_name == name)
            .map(|(_, value)| value.as_str())
    }

    /// The body as JSON.
    pub fn json(&self) -> Option<serde_json::Value> {
        serde_json::from_slice(&self.body).ok()
    }
}

/// The running stand-in. Dropping it stops accepting.
pub struct StandInServer {
    origin: String,
    requests: Arc<Mutex<Vec<StandInRequest>>>,
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl StandInServer {
    /// Starts answering on a free loopback port with `answers`, one per connection, in order.
    pub fn start(answers: Vec<StandInAnswer>) -> io::Result<Self> {
        let listener = TcpListener::bind("127.0.0.1:0")?;
        listener.set_nonblocking(true)?;
        let origin = format!("http://{}", listener.local_addr()?);
        let requests = Arc::new(Mutex::new(Vec::new()));
        let stop = Arc::new(AtomicBool::new(false));
        let thread = {
            let requests = requests.clone();
            let stop = stop.clone();
            thread::spawn(move || serve(listener, answers, &requests, &stop))
        };
        Ok(Self {
            origin,
            requests,
            stop,
            thread: Some(thread),
        })
    }

    /// The stand-in's origin, such as `http://127.0.0.1:50123`.
    pub fn origin(&self) -> &str {
        &self.origin
    }

    /// The requests received so far.
    pub fn requests(&self) -> Vec<StandInRequest> {
        self.requests
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
    }
}

impl Drop for StandInServer {
    /// Stops accepting and waits for the serving thread.
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

/// Answers one connection per scripted answer, until the answers run out, the deadline passes or the
/// stand-in is dropped.
fn serve(
    listener: TcpListener,
    answers: Vec<StandInAnswer>,
    requests: &Mutex<Vec<StandInRequest>>,
    stop: &AtomicBool,
) {
    for answer in answers {
        let deadline = Instant::now() + ACCEPT_DEADLINE;
        let mut stream = loop {
            match listener.accept() {
                Ok((stream, _)) => break stream,
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                    if stop.load(Ordering::Relaxed) || deadline <= Instant::now() {
                        return;
                    }
                    thread::sleep(Duration::from_millis(5));
                }
                Err(_) => return,
            }
        };
        if let Ok(request) = read_request(&mut stream) {
            requests
                .lock()
                .unwrap_or_else(PoisonError::into_inner)
                .push(request);
            let _ = write_answer(&mut stream, &answer);
        }
        let _ = stream.shutdown(Shutdown::Both);
    }
}

/// Reads one HTTP/1.1 request: the request line, the headers and a `Content-Length` or chunked body.
fn read_request(stream: &mut TcpStream) -> io::Result<StandInRequest> {
    stream.set_nonblocking(false)?;
    stream.set_read_timeout(Some(IO_TIMEOUT))?;
    stream.set_write_timeout(Some(IO_TIMEOUT))?;
    let mut data = Vec::new();
    let mut buffer = [0u8; 4096];
    let header_end = loop {
        if let Some(index) = data.windows(4).position(|window| window == b"\r\n\r\n") {
            break index;
        }
        if REQUEST_BYTE_LIMIT < data.len() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "request too large",
            ));
        }
        let count = stream.read(&mut buffer)?;
        if count == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "request ended",
            ));
        }
        data.extend_from_slice(&buffer[..count]);
    };
    let head = String::from_utf8_lossy(&data[..header_end]).into_owned();
    let mut lines = head.split("\r\n");
    let request_line = lines.next().unwrap_or_default();
    let mut parts = request_line.split(' ');
    let method = parts.next().unwrap_or_default().to_string();
    let target = parts.next().unwrap_or_default().to_string();
    let headers: Vec<(String, String)> = lines
        .filter_map(|line| line.split_once(':'))
        .map(|(name, value)| (name.trim().to_ascii_lowercase(), value.trim().to_string()))
        .collect();
    let mut body = data[header_end + 4..].to_vec();
    let header = |name: &str| {
        headers
            .iter()
            .find(|(header_name, _)| header_name == name)
            .map(|(_, value)| value.clone())
    };
    if let Some(length) = header("content-length").and_then(|value| value.parse::<usize>().ok()) {
        if REQUEST_BYTE_LIMIT < length {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "body too large"));
        }
        while body.len() < length {
            let count = stream.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            body.extend_from_slice(&buffer[..count]);
        }
        body.truncate(length);
    } else if header("transfer-encoding").is_some_and(|value| value.eq_ignore_ascii_case("chunked"))
    {
        body = read_chunked(stream, body)?;
    }
    Ok(StandInRequest {
        method,
        target,
        headers,
        body,
    })
}

/// Decodes a chunked body, given the bytes already read after the headers.
fn read_chunked(stream: &mut TcpStream, mut data: Vec<u8>) -> io::Result<Vec<u8>> {
    let mut buffer = [0u8; 4096];
    let mut body = Vec::new();
    loop {
        let Some(line_end) = data.windows(2).position(|window| window == b"\r\n") else {
            let count = stream.read(&mut buffer)?;
            if count == 0 || REQUEST_BYTE_LIMIT < data.len() {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "bad chunked body",
                ));
            }
            data.extend_from_slice(&buffer[..count]);
            continue;
        };
        let size_text = String::from_utf8_lossy(&data[..line_end]).into_owned();
        let size = usize::from_str_radix(size_text.split(';').next().unwrap_or("").trim(), 16)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "bad chunk size"))?;
        while data.len() < line_end + 2 + size + 2 {
            let count = stream.read(&mut buffer)?;
            if count == 0 || REQUEST_BYTE_LIMIT < data.len() {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "bad chunked body",
                ));
            }
            data.extend_from_slice(&buffer[..count]);
        }
        if size == 0 {
            return Ok(body);
        }
        body.extend_from_slice(&data[line_end + 2..line_end + 2 + size]);
        data.drain(..line_end + 2 + size + 2);
    }
}

/// Writes one answer and asks the client to close the connection.
fn write_answer(stream: &mut TcpStream, answer: &StandInAnswer) -> io::Result<()> {
    let reason = match answer.status {
        200 => "OK",
        400 => "Bad Request",
        401 => "Unauthorized",
        404 => "Not Found",
        405 => "Method Not Allowed",
        409 => "Conflict",
        500 => "Internal Server Error",
        502 => "Bad Gateway",
        503 => "Service Unavailable",
        _ => "Status",
    };
    let head = format!(
        "HTTP/1.1 {} {reason}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
        answer.status,
        answer.body.len()
    );
    stream.write_all(head.as_bytes())?;
    stream.write_all(answer.body.as_bytes())?;
    stream.flush()
}
