"""python main.py --self-test | --version | self | peers | watch | send CLIENT_ID TEXT"""

import ctypes as C
import json
from pathlib import Path
import queue
import secrets
import sys
import time
import uuid
from codec import SUBPROTOCOL, decode, encode, self_test

# This CLI creates one bounded callback set. Device.close() cancels asynchronous
# workers; retain their C trampolines until process exit, including late replies.
_CALLBACK_ROOTS = []


def main():
    sys.stdout.reconfigure(line_buffering=True)
    args = sys.argv[1:]
    if args == ["--self-test"]:
        self_test()
        return
    import urnetwork
    from urnetwork import raw
    from urnetwork._raw import (
        urnet_network_peers_change_cb,
        urnet_subprotocol_cb,
        urnet_subprotocols_query_cb,
    )

    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "integration"))
    from client import local_device, take_string

    if args == ["--version"]:
        print(urnetwork.version())
        return
    if (
        not args
        or args[0] not in ("self", "peers", "watch", "send")
        or (args[0] == "send" and len(args) < 3)
    ):
        raise ValueError(__doc__)
    destination = str(uuid.UUID(args[1])) if args[0] == "send" else None
    events = queue.Queue(maxsize=256)

    def enqueue(event):
        try:
            events.put_nowait(event)
        except queue.Full:
            print("receive queue full; event dropped", file=sys.stderr)

    # These callable objects remain strongly referenced until after Device.close().
    @urnet_subprotocol_cb
    def on_message(_, protocol, source, pointer, length):
        if protocol == SUBPROTOCOL and 16 <= length <= 4112:
            enqueue(("message", source.decode(), C.string_at(pointer, length)))

    @urnet_network_peers_change_cb
    def on_peers(_, data):
        enqueue(("peers", bytes(data) if data else None))

    @urnet_subprotocols_query_cb
    def on_query(_, data, ok):
        enqueue(("query", bool(ok), bytes(data) if data else b"null"))

    _CALLBACK_ROOTS.extend((on_message, on_peers, on_query))

    def show_peers(data):
        peers = json.loads(data) if data else None
        if peers is None:
            print("peers unavailable (connection has not supplied a snapshot)")
            return False
        print("disconnected:", peers["DisconnectedCount"])
        if peers.get("Connected") is None:
            print("connected peers unavailable")
        for peer in peers.get("Connected") or []:
            color = take_string(
                raw.urnet_get_color_hex((peer.get("ClientId") or "").encode())
            )
            print(json.dumps({**peer, "Color": color}, ensure_ascii=True))
        return True

    with local_device(connect=False) as device:
        h = device.handle
        subscriptions = []
        try:
            error = C.c_void_p()
            subscription = raw.urnet_device_local_enable_subprotocol(
                h, SUBPROTOCOL, on_message, None, C.byref(error)
            )
            message = take_string(error.value)
            if message or not subscription:
                raise RuntimeError(message or "subprotocol registration failed")
            subscriptions.append(subscription)
            subscriptions.append(
                raw.urnet_device_add_network_peers_change_listener(h, on_peers, None)
            )
            # Explicitly allow this device to receive messages from its network.
            raw.urnet_device_set_provide_mode(h, 1)  # URNET_PROVIDE_MODE_NETWORK
            print("self:", take_string(raw.urnet_device_get_client_id(h)))
            if args[0] == "self":
                return
            available = show_peers(take_string(raw.urnet_device_get_network_peers(h)))
            if args[0] == "peers" and available:
                return
            deadline = time.monotonic() + (30 if destination else 10)
            query_started = False
            pending = secrets.randbelow(0xFFFFFFFFFFFFFFFF) + 1

            def send(target, frame):
                buffer = (C.c_uint8 * len(frame)).from_buffer_copy(frame)
                if not raw.urnet_device_local_send_subprotocol_bytes(
                    h, SUBPROTOCOL, target.encode(), buffer, len(frame)
                ):
                    raise RuntimeError("SDK did not enqueue message")

            while True:
                if (
                    destination
                    and not query_started
                    and raw.urnet_device_local_get_provider_connected(h)
                ):
                    query_started = True
                    raw.urnet_device_local_query_subprotocols(
                        h, destination.encode(), 10000, on_query, None
                    )
                try:
                    event = events.get(timeout=0.1)
                except queue.Empty:
                    event = None
                if event and event[0] == "peers":
                    show_peers(event[1])
                    if (
                        args[0] == "peers"
                        and event[1]
                        and json.loads(event[1]) is not None
                    ):
                        return
                elif event and event[0] == "query":
                    if not event[1] or SUBPROTOCOL not in (json.loads(event[2]) or []):
                        raise RuntimeError(
                            "peer subprotocol query timed out or peer does not advertise 4096"
                        )
                    send(destination, encode(1, pending, " ".join(args[2:])))
                    print("sent:", pending, "waiting for ACK")
                    deadline = time.monotonic() + 10
                elif event and event[0] == "message":
                    source, data = event[1:]
                    try:
                        kind, message_id, text = decode(data)
                    except ValueError as error:
                        print("rejected frame:", error, file=sys.stderr)
                        continue
                    print(
                        "TEXT" if kind == 1 else "ACK",
                        source,
                        message_id,
                        json.dumps(text, ensure_ascii=True),
                    )
                    if kind == 1:
                        send(source, encode(2, message_id))
                    elif source == destination and message_id == pending:
                        return  # ACKs are never ACKed.
                if args[0] != "watch" and time.monotonic() > deadline:
                    raise TimeoutError(
                        "no peer snapshot"
                        if not destination
                        else "peer connection/query/ACK timed out"
                    )
        finally:
            for subscription in reversed(subscriptions):
                raw.urnet_sub_close(subscription)
                raw.urnet_release(subscription)
            raw.urnet_device_local_disable_subprotocol(h, SUBPROTOCOL)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except Exception as error:
        print(error, file=sys.stderr)
        sys.exit(1)
