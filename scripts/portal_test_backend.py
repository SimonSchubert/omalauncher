"""Test double for the Android socket endpoint, never claimed as device coverage."""
import array
import base64
import os
import socket
import stat
import struct
import threading
import time
import uuid


class FakeAndroid:
    def __init__(self, runtime):
        self.name = 'omalauncher-test-' + uuid.uuid4().hex
        self.server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.server.bind('\0' + self.name)
        self.server.listen(16)
        (runtime / 'omalauncher-portal-socket').write_text(self.name)
        self.condition = threading.Condition()
        self.records = []
        self.fd_stats = []
        self.print_status = 0
        self.print_disconnect = False
        self.hold_print = False
        self.closed = 0
        self.errors = []
        self.clients = set()
        self.running = True
        threading.Thread(target=self._accept, daemon=True).start()

    def _accept(self):
        while self.running:
            try:
                client, _ = self.server.accept()
            except OSError:
                return
            with self.condition:
                self.clients.add(client)
            threading.Thread(target=self._serve, args=(client,), daemon=True).start()

    def _serve(self, client):
        fds = []
        recorded = False
        try:
            client.settimeout(5)
            header = bytearray()
            while len(header) < 4:
                data, anc, flags, address = client.recvmsg(4 - len(header), socket.CMSG_SPACE(8 * array.array('i').itemsize))
                for level, kind, content in anc:
                    if level == socket.SOL_SOCKET and kind == socket.SCM_RIGHTS:
                        values = array.array('i'); values.frombytes(content[:len(content) - len(content) % values.itemsize]); fds.extend(values)
                if not data:
                    return  # A request may be closed before its worker connects.
                header.extend(data)
            length, = struct.unpack('!I', header)
            assert 0 < length <= 65536
            body = bytearray()
            while len(body) < length:
                part = client.recv(length - len(body))
                if not part:
                    return
                body.extend(part)
            fields = {}
            for line in body.decode('utf-8').splitlines():
                key, value = line.split('=', 1)
                assert key not in fields
                fields[key] = base64.b64decode(value, validate=True).decode('utf-8')
            assert fields['version'] == '1'
            assert int(fields['fd-count']) == len(fds)
            contents = [os.pread(fd, min(os.fstat(fd).st_size, 4096), 0)
                        if stat.S_ISREG(os.fstat(fd).st_mode) else None for fd in fds]
            with self.condition:
                self.records.append((fields, contents))
                self.fd_stats.append([os.fstat(fd) for fd in fds])
                recorded = True
                self.condition.notify_all()
            if fields['operation'] == 'print' and self.print_disconnect:
                return
            print_hold = fields['operation'] == 'print' and self.hold_print
            hold = fields['operation'] in ('inhibit', 'notification') or fields.get('uri', '').endswith('/hold') or print_hold
            if fields['operation'] in ('inhibit', 'notification') or not hold:
                status = self.print_status if fields['operation'] == 'print' else 0
                client.sendall(f'{status}\n'.encode())
            if fields['operation'] == 'notification' and fields.get('title') == 'Portal test':
                client.sendall(b'A\t0\n')
            if hold:
                assert client.recv(1) == b''
                with self.condition:
                    self.closed += 1
                    self.condition.notify_all()
        except (BrokenPipeError, ConnectionResetError):
            pass  # Authorized cancellation can race the backend's reply.
        except Exception as error:
            with self.condition:
                self.errors.append(repr(error)); self.condition.notify_all()
        finally:
            for fd in fds:
                os.close(fd)
            with self.condition:
                self.clients.discard(client)
            client.close()

    def wait_closed(self, count, timeout=3):
        with self.condition:
            return self.condition.wait_for(lambda: self.closed >= count, timeout)

    def close(self):
        self.running = False
        self.server.close()
        with self.condition:
            clients = list(self.clients)
        for client in clients:
            try:
                client.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
