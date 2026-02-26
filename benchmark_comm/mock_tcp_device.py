from __future__ import annotations

import argparse
import base64
import json
import os
import socket
import threading
import time


class MockTCPDeviceServer:
    """Minimal JSONL TCP mock device for benchmark_comm TCP adapter."""

    def __init__(
        self,
        host: str,
        port: int,
        screenshot_kb: int,
        dump_bytes: int,
        process_delay_ms: int,
    ) -> None:
        self.host = host
        self.port = port
        self.screenshot_bytes = max(screenshot_kb, 1) * 1024
        self.dump_bytes = max(dump_bytes, 128)
        self.process_delay_ms = max(process_delay_ms, 0)
        self._stop = threading.Event()

    def serve_forever(self) -> None:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind((self.host, self.port))
            srv.listen(8)
            print(
                f"[mock_tcp_device] listening on {self.host}:{self.port} "
                f"(screenshot={self.screenshot_bytes}B dump={self.dump_bytes}B)",
                flush=True,
            )
            while not self._stop.is_set():
                try:
                    conn, addr = srv.accept()
                except OSError:
                    break
                t = threading.Thread(target=self._handle_client, args=(conn, addr), daemon=True)
                t.start()

    def _handle_client(self, conn: socket.socket, addr: tuple[str, int]) -> None:
        with conn:
            fp = conn.makefile("rb")
            print(f"[mock_tcp_device] client connected: {addr[0]}:{addr[1]}", flush=True)
            while not self._stop.is_set():
                line = fp.readline()
                if not line:
                    break
                try:
                    req = json.loads(line.decode("utf-8", errors="ignore"))
                    cmd = str(req.get("cmd", "")).strip()
                except Exception:
                    self._send(conn, ok=False, payload=b"", error="invalid_json")
                    continue

                if self.process_delay_ms > 0:
                    time.sleep(self.process_delay_ms / 1000.0)

                if cmd == "handshake":
                    self._send(conn, ok=True, payload=b"pong", error="")
                elif cmd == "dump":
                    payload = self._fake_dump()
                    self._send(conn, ok=True, payload=payload, error="")
                elif cmd == "screenshot":
                    payload = self._fake_screenshot()
                    self._send(conn, ok=True, payload=payload, error="")
                else:
                    self._send(conn, ok=False, payload=b"", error=f"unsupported_command:{cmd}")

            print(f"[mock_tcp_device] client closed: {addr[0]}:{addr[1]}", flush=True)

    def _send(self, conn: socket.socket, ok: bool, payload: bytes, error: str) -> None:
        obj = {
            "ok": bool(ok),
            "payload_b64": base64.b64encode(payload).decode("ascii") if payload else "",
            "error": error,
        }
        conn.sendall((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))

    def _fake_dump(self) -> bytes:
        # Fixed-size XML-like payload for small-packet simulation.
        head = b"<hierarchy><node text='mock'/></hierarchy>"
        if len(head) >= self.dump_bytes:
            return head[: self.dump_bytes]
        return head + (b"x" * (self.dump_bytes - len(head)))

    def _fake_screenshot(self) -> bytes:
        # Deterministic pseudo image-like payload; size controls large-packet behavior.
        seed = b"MOCKPNG"
        body = bytearray(self.screenshot_bytes)
        for i in range(self.screenshot_bytes):
            body[i] = seed[i % len(seed)] ^ (i % 251)
        return bytes(body)


def main() -> None:
    parser = argparse.ArgumentParser(description="Mock TCP device server for benchmark_comm")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=22345)
    parser.add_argument("--screenshot-kb", type=int, default=600)
    parser.add_argument("--dump-bytes", type=int, default=6000)
    parser.add_argument("--process-delay-ms", type=int, default=10)
    args = parser.parse_args()

    server = MockTCPDeviceServer(
        host=args.host,
        port=args.port,
        screenshot_kb=args.screenshot_kb,
        dump_bytes=args.dump_bytes,
        process_delay_ms=args.process_delay_ms,
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
