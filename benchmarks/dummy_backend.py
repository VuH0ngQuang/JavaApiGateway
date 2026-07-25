#!/usr/bin/env python3
"""Minimal fixed-response HTTP server, for benchmarking the gateway against
a uniform pool of identical backends (instead of two different real services).

Usage: python3 dummy_backend.py <port>
"""
import sys
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9001

BODY = json.dumps([{"movie_id": 1, "title": "Dummy Movie"}]).encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(BODY)))
        self.end_headers()
        self.wfile.write(BODY)

    def log_message(self, format, *args):
        pass  # silence per-request logging


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"dummy backend listening on :{PORT}")
    server.serve_forever()
