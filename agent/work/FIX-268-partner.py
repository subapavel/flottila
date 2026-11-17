#!/usr/bin/env python3
"""A stand-in for the demo's partner, for FIX-268's measurement only (the stack's own echo logs no header).

    python3 FIX-268-partner.py <port> <log file> <flag file>

It listens on 127.0.0.1:<port>, appends one line per request to <log file>
(`<n> <method> <path> x-message-id=<value or -> bytes=<n> -> <status>`) and answers 503 while <flag file> does not
exist, 200 with the request's body once it does. It reads nothing else and keeps no state but the request count.
"""
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

port, log_path, flag_path = int(sys.argv[1]), sys.argv[2], sys.argv[3]
count = 0


class Partner(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def answer(self):
        global count
        count += 1
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        status = 200 if os.path.exists(flag_path) else 503
        with open(log_path, "a") as log:
            log.write("%d %s %s x-message-id=%s bytes=%d -> %d\n" % (
                count, self.command, self.path, self.headers.get("x-message-id") or "-", len(body), status))
        reply = body if status == 200 else b"partner down (stand-in)\n"
        self.send_response(status)
        self.send_header("Content-Type", self.headers.get("Content-Type") or "text/plain")
        self.send_header("Content-Length", str(len(reply)))
        self.end_headers()
        self.wfile.write(reply)

    do_POST = do_PUT = do_GET = answer

    def log_message(self, fmt, *args):   # the log file is the record; nothing on stderr
        pass


HTTPServer(("127.0.0.1", port), Partner).serve_forever()
