#!/usr/bin/env python3
"""Minimal fixed-response HTTP server, for benchmarking the gateway against
a uniform pool of identical backends (instead of two different real services).

Usage: python3 dummy_backend.py <port> [service_name] [body_bytes]

The optional service_name is echoed back in the response body so you can
confirm path-based routing is sending requests to the right service, and
which replica within that service answered.

body_bytes sizes the response payload, defaulting to 65536 — the gateway's
HttpObjectAggregator limit (64 * 1024) on both the request and the backend
response side. Asking for more than that makes the gateway reject the
response rather than forward it.

The payload is random alphanumeric data, generated once at startup: doing it
per request would make Python's RNG the bottleneck instead of the gateway.
"""
import sys
import json
import random
import string
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9001
SERVICE = sys.argv[2] if len(sys.argv) > 2 else "dummy"
BODY_BYTES = int(sys.argv[3]) if len(sys.argv) > 3 else 64 * 1024


def build_body(target_bytes):
    """Return a JSON body of exactly target_bytes, padded with random data."""
    envelope = {"service": SERVICE, "port": PORT, "payload": ""}
    overhead = len(json.dumps(envelope).encode("utf-8"))
    # Alphanumerics need no JSON escaping, so one character is one byte.
    pad = max(0, target_bytes - overhead)
    envelope["payload"] = "".join(
        random.choices(string.ascii_letters + string.digits, k=pad)
    )
    return json.dumps(envelope).encode("utf-8")


BODY = build_body(BODY_BYTES)


class Handler(BaseHTTPRequestHandler):
    # BaseHTTPRequestHandler defaults to HTTP/1.0, which closes the connection
    # after every response. Keep-alive is required for the gateway's connection
    # pool to have anything to reuse, so opt into HTTP/1.1.
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(BODY)))
        self.end_headers()
        self.wfile.write(BODY)

    def do_POST(self):
        self.do_GET()

    def log_message(self, format, *args):
        pass  # silence per-request logging


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"dummy backend '{SERVICE}' listening on :{PORT} ({len(BODY)} byte body)")
    server.serve_forever()
