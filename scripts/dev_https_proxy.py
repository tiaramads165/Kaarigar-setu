#!/usr/bin/env python3
"""Development-only HTTPS front for the app, so a PHONE can use the microphone.

Browsers only give a page the microphone over HTTPS (or http://localhost on the same computer). The app speaks plain HTTP in development, so a
phone that opens http://<your-computer>:8090 sees "this phone cannot record audio". This script listens on HTTPS and passes every request on to
the app. Use it through scripts/dev-https.sh (it makes the certificate). Nothing here is for production: use the Caddy setup in docker-compose.yml
or your real TLS terminator there.

Uses only the Python standard library. The Host header is passed through unchanged (the app's CSRF check compares it with the browser's Origin),
and redirects that Tomcat builds with http:// are rewritten to https://.
"""
import argparse
import http.client
import http.server
import socketserver
import ssl
import sys

HOP_BY_HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailers", "transfer-encoding", "upgrade", "content-length"}


class Proxy(http.server.BaseHTTPRequestHandler):
    backend_port = 8090
    protocol_version = "HTTP/1.1"

    def _forward(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_BY_HOP}
        headers["X-Forwarded-Proto"] = "https"
        headers["X-Forwarded-For"] = self.client_address[0]
        if body is not None:
            headers["Content-Length"] = str(len(body))
        try:
            connection = http.client.HTTPConnection("127.0.0.1", self.backend_port, timeout=120)
            connection.request(self.command, self.path, body=body, headers=headers)
            response = connection.getresponse()
            payload = response.read()
        except OSError as error:
            message = f"The app is not answering on port {self.backend_port}: {error}".encode()
            self.send_response(502)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(message)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(message)
            return
        self.send_response(response.status, response.reason)
        host = self.headers.get("Host", "")
        for name, value in response.getheaders():
            lower = name.lower()
            if lower in HOP_BY_HOP:
                continue
            if lower == "location" and value.startswith("http://" + host):
                value = "https://" + value[len("http://"):]
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)

    do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = do_HEAD = do_OPTIONS = _forward

    def log_message(self, fmt, *args):   # quiet: the app's own log has the detail
        pass


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen", type=int, default=8443)
    parser.add_argument("--backend", type=int, default=8090)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    args = parser.parse_args()
    Proxy.backend_port = args.backend
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.cert, args.key)
    server = Server(("0.0.0.0", args.listen), Proxy)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print(f"HTTPS on port {args.listen} -> app on http://127.0.0.1:{args.backend}   (Ctrl-C to stop)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
