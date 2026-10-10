"""平台内部二进制通道；只连接Windows回环PostgreSQL，不接受网络目标参数。"""
import os
import socket
import sys
import threading


def main():
    if os.name != 'nt':
        return 1
    import msvcrt
    msvcrt.setmode(sys.stdin.fileno(), os.O_BINARY)
    msvcrt.setmode(sys.stdout.fileno(), os.O_BINARY)
    try:
        with socket.create_connection(('127.0.0.1', 5432), timeout=10) as connection:
            connection.settimeout(None)

            def upload():
                try:
                    while data := os.read(sys.stdin.fileno(), 65536):
                        connection.sendall(data)
                except OSError:
                    pass
                finally:
                    try:
                        connection.shutdown(socket.SHUT_WR)
                    except OSError:
                        pass

            threading.Thread(target=upload, daemon=True).start()
            while data := connection.recv(65536):
                sys.stdout.buffer.write(data)
                sys.stdout.buffer.flush()
        return 0
    except OSError:
        print('PG_RELAY_UNAVAILABLE', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
