"""Run with python main.py --mode httpx; see README.md for account setup."""
import argparse
import ctypes as C
from contextlib import ExitStack, contextmanager
import json
import os
import time
from urllib.parse import urlsplit
import uuid
import urnetwork
from urnetwork import Device, Handle, raw
import httpx
import requests
from ur_http import UrHttpxTransport, UrRequestsAdapter


def take_string(pointer):
    if not pointer:
        return None
    try:
        return C.string_at(pointer).decode("utf-8")
    finally:
        raw.urnet_free_string(pointer)


@contextmanager
def local_device():
    jwt = os.environ.get("URNETWORK_JWT")
    instance = os.environ.get("URNETWORK_INSTANCE_ID")
    if not jwt or not instance:
        raise ValueError("Set URNETWORK_JWT and a persistent URNETWORK_INSTANCE_ID; see README.md.")
    instance = str(uuid.UUID(instance))
    with ExitStack() as stack:
        manager = stack.enter_context(Handle(raw.urnet_new_network_space_manager_no_storage()))
        stack.callback(raw.urnet_network_space_manager_close, manager.handle)
        space = stack.enter_context(Handle(raw.urnet_network_space_manager_update_network_space_values(
            manager.handle, b'{"host_name":"ur.network","env_name":"main"}',
            b'{"migration_host_name":"bringyour.com"}')))
        api = stack.enter_context(Handle(raw.urnet_network_space_get_api(space.handle)))
        raw.urnet_api_set_by_jwt(api.handle, jwt.encode())
        error = C.c_void_p()
        handle = raw.urnet_new_device_local_with_defaults(
            space.handle, jwt.encode(), b"Python socket example", b"python", b"1",
            instance.encode(), False, C.byref(error))
        message = take_string(error.value)
        if message:
            raise OSError(message)
        device = stack.enter_context(Device(handle))
        raw.urnet_device_set_connect_location(device.handle, b'{"connect_location_id":{"best_available":true}}')
        yield device


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", action="store_true")
    parser.add_argument("--new-id", action="store_true")
    parser.add_argument("--mode", choices=["httpx", "requests", "tls", "udp", "dtls"], default="httpx")
    parser.add_argument("--url", default="https://example.com/")
    parser.add_argument("--target", help="UDP/DTLS echo server host:port")
    args = parser.parse_args()
    if args.version:
        print(urnetwork.version())
        return
    if args.new_id:
        print(uuid.uuid4())
        return
    with local_device() as device:
        if args.mode == "httpx":
            with httpx.Client(transport=UrHttpxTransport(device), trust_env=False, timeout=30) as client:
                response = client.get(args.url)
                response.raise_for_status()
                print(response.text[:4096])
        elif args.mode == "requests":
            with requests.Session() as client:
                client.trust_env = False
                adapter = UrRequestsAdapter(device)
                client.mount("http://", adapter)
                client.mount("https://", adapter)
                with client.get(args.url, timeout=30) as response:
                    response.raise_for_status()
                    print(response.text[:4096])
        else:
            if args.mode == "tls":
                url = urlsplit(args.url)
                if url.scheme != "https" or not url.hostname:
                    raise ValueError("--url must be HTTPS")
                host = "[" + url.hostname + "]" if ":" in url.hostname else url.hostname
                conn = device.dial_tls("tcp", host + ":" + str(url.port or 443), server_name=url.hostname, next_protos=["http/1.1"])
                payload = ("GET " + (url.path or "/") + ("?" + url.query if url.query else "") +
                           " HTTP/1.1\r\nHost: " + url.netloc + "\r\nConnection: close\r\n\r\n").encode()
            else:
                if not args.target:
                    raise ValueError("--target is required for UDP/DTLS")
                conn = device.dial("udp", args.target) if args.mode == "udp" else device.dial_tls("udp", args.target)
                payload = b"hello"
            with conn:
                conn.set_deadline(int(time.time() * 1000) + 30000)
                conn.write(payload)
                result = conn.read()
                print(result.data.decode("utf-8", errors="replace"))
                print("EOF:", result.eof, "peer:", conn.remote_address)


if __name__ == "__main__":
    main()
