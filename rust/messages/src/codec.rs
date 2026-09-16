#[derive(Debug, PartialEq, Eq)]
pub struct Frame {
    pub kind: u8,
    pub id: u64,
    pub text: String,
}
pub fn encode(f: &Frame) -> Result<Vec<u8>, &'static str> {
    let text = f.text.as_bytes();
    if f.id == 0
        || ![1, 2].contains(&f.kind)
        || text.len() > 4096
        || (f.kind == 2 && !text.is_empty())
    {
        return Err("invalid message");
    }
    let mut b = Vec::with_capacity(16 + text.len());
    b.extend_from_slice(b"URMS");
    b.extend([1, f.kind]);
    b.extend((text.len() as u16).to_be_bytes());
    b.extend(f.id.to_be_bytes());
    b.extend(text);
    Ok(b)
}
pub fn decode(b: &[u8]) -> Result<Frame, &'static str> {
    if b.len() < 16
        || b.len() > 4112
        || &b[..4] != b"URMS"
        || b[4] != 1
        || ![1, 2].contains(&b[5])
        || u16::from_be_bytes([b[6], b[7]]) as usize != b.len() - 16
        || (b[5] == 2 && b.len() != 16)
    {
        return Err("invalid frame");
    }
    let id = b[8..16]
        .iter()
        .fold(0u64, |id, b| (id << 8) | u64::from(*b));
    if id == 0 {
        return Err("zero message ID");
    }
    let text = std::str::from_utf8(&b[16..])
        .map_err(|_| "invalid UTF-8")?
        .to_owned();
    Ok(Frame {
        kind: b[5],
        id,
        text,
    })
}
pub fn self_test() -> Result<(), &'static str> {
    let golden = vec![
        0x55, 0x52, 0x4d, 0x53, 1, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 1, 0x68, 0x69,
    ];
    let ack = vec![0x55, 0x52, 0x4d, 0x53, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1];
    for f in [
        Frame {
            kind: 1,
            id: 1,
            text: "hi".into(),
        },
        Frame {
            kind: 2,
            id: 1,
            text: String::new(),
        },
    ] {
        let expected = if f.kind == 1 { &golden } else { &ack };
        if &encode(&f)? != expected || decode(expected)? != f {
            return Err("golden test failed");
        }
    }
    for text in [
        String::new(),
        "é🙂\0".into(),
        "x".repeat(4096),
        "é".repeat(2048),
    ] {
        let f = Frame {
            kind: 1,
            id: u64::MAX,
            text,
        };
        if decode(&encode(&f)?)? != f {
            return Err("round trip failed");
        }
    }
    let mut bad: Vec<Vec<u8>> = (0..golden.len()).map(|n| golden[..n].to_vec()).collect();
    let mut oversize = encode(&Frame {
        kind: 1,
        id: 1,
        text: "x".repeat(4096),
    })?;
    oversize.push(0);
    bad.push(oversize);
    let mut extra = golden.clone();
    extra.push(0);
    bad.push(extra);
    let mut utf8 = golden.clone();
    utf8[16] = 0xc0;
    utf8[17] = 0xaf;
    bad.push(utf8);
    for (offset, value) in [(0, 0), (4, 2), (5, 3), (5, 2), (6, 16), (7, 1), (15, 0)] {
        let mut b = golden.clone();
        b[offset] = value;
        bad.push(b)
    }
    for b in bad {
        if decode(&b).is_ok() {
            return Err("accepted malformed frame");
        }
    }
    for f in [
        Frame {
            kind: 1,
            id: 0,
            text: String::new(),
        },
        Frame {
            kind: 2,
            id: 1,
            text: "x".into(),
        },
        Frame {
            kind: 3,
            id: 1,
            text: String::new(),
        },
        Frame {
            kind: 1,
            id: 1,
            text: "x".repeat(4097),
        },
    ] {
        if encode(&f).is_ok() {
            return Err("accepted invalid message");
        }
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    #[test]
    fn protocol_vectors() {
        super::self_test().unwrap()
    }
}
