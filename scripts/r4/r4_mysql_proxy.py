"""Minimal TCP proxy used by R4 fault injection to disconnect a JVM from MySQL."""
import argparse
import socket
import socketserver
import threading


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        upstream = socket.create_connection((self.server.upstream_host,
                                            self.server.upstream_port), timeout=10)
        self.request.settimeout(None)
        upstream.settimeout(None)
        stop = threading.Event()

        def pipe(source, target):
            try:
                while not stop.is_set():
                    data = source.recv(65536)
                    if not data:
                        break
                    target.sendall(data)
            except OSError:
                pass
            finally:
                stop.set()
                try:
                    target.shutdown(socket.SHUT_WR)
                except OSError:
                    pass

        first = threading.Thread(target=pipe, args=(self.request, upstream), daemon=True)
        second = threading.Thread(target=pipe, args=(upstream, self.request), daemon=True)
        first.start()
        second.start()
        first.join()
        second.join()
        upstream.close()


class Proxy(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address, upstream_host, upstream_port):
        super().__init__(address, Handler)
        self.upstream_host = upstream_host
        self.upstream_port = upstream_port


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--listen-port', type=int, required=True)
    parser.add_argument('--upstream-host', default='127.0.0.1')
    parser.add_argument('--upstream-port', type=int, default=3306)
    args = parser.parse_args()
    with Proxy(('127.0.0.1', args.listen_port), args.upstream_host,
               args.upstream_port) as server:
        server.serve_forever()
