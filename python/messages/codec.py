"""URMS v1: one frame per DeviceLocal subprotocol message (see ../../MESSAGES_PROTOCOL.md)."""

import struct

SUBPROTOCOL = 4096
HEADER = struct.Struct("!4sBBHQ")


def encode(kind, message_id, text=""):
    payload = text.encode("utf-8", errors="strict")
    if kind not in (1, 2) or len(payload) > 4096 or (kind == 2 and payload):
        raise ValueError("invalid message")
    if not 1 <= message_id <= 0xFFFFFFFFFFFFFFFF:
        raise ValueError("invalid message ID")
    return HEADER.pack(b"URMS", 1, kind, len(payload), message_id) + payload


def decode(data):
    data = bytes(data)
    if not 16 <= len(data) <= 4112:
        raise ValueError("invalid frame size")
    magic, version, kind, length, message_id = HEADER.unpack_from(data)
    if magic != b"URMS" or version != 1 or kind not in (1, 2):
        raise ValueError("invalid header")
    if length != len(data) - 16 or (kind == 2 and length) or message_id == 0:
        raise ValueError("invalid payload length")
    return kind, message_id, data[16:].decode("utf-8", errors="strict")


def self_test():
    golden = bytes.fromhex("55524d530101000200000000000000016869")
    ack = bytes.fromhex("55524d53010200000000000000000001")
    assert encode(1, 1, "hi") == golden and decode(golden) == (1, 1, "hi")
    assert encode(2, 1) == ack and decode(ack) == (2, 1, "")
    for value in ("", "é🙂\x00", "x" * 4096, "é" * 2048):
        assert decode(encode(1, 0xFFFFFFFFFFFFFFFF, value)) == (
            1,
            0xFFFFFFFFFFFFFFFF,
            value,
        )
    bad = [golden[:n] for n in range(len(golden))] + [
        golden + b"x",
        golden[:16] + b"\xc0\xaf",
        encode(1, 1, "x" * 4096) + b"x",
    ]
    for offset, value in ((0, 0), (4, 2), (5, 3), (5, 2), (6, 16), (7, 1), (15, 0)):
        frame = bytearray(golden)
        frame[offset] = value
        bad.append(frame)
    for frame in bad:
        try:
            decode(frame)
        except (ValueError, UnicodeError):
            pass
        else:
            raise AssertionError("accepted malformed frame")
    for args in (
        (1, 1, "x" * 4097),
        (2, 1, "x"),
        (3, 1, ""),
        (1, -1, ""),
        (1, 1, "\ud800"),
    ):
        try:
            encode(*args)
        except (ValueError, UnicodeError):
            pass
        else:
            raise AssertionError("accepted invalid message")
    print("URMS codec self-test passed")
