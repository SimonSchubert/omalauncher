"""Test double for the existing Files/notify FIFO; never Android UI coverage."""
import os
import errno
from pathlib import Path
import select
import stat
import threading


class FakeLegacyBridge:
    def __init__(self, runtime):
        self.runtime = runtime
        self.path = runtime / 'open.fifo'
        os.mkfifo(self.path, 0o600)
        self.fd = os.open(self.path, os.O_RDWR | os.O_NONBLOCK | os.O_CLOEXEC)
        self.condition = threading.Condition()
        self.records = []
        self.errors = []
        self.paths = []
        self.hold = False
        self.running = True
        self.worker = threading.Thread(target=self._serve, daemon=True)
        self.worker.start()

    def configure(self, paths=(), hold=False):
        with self.condition:
            self.paths = list(map(str, paths))
            self.hold = hold
            self.records.clear()

    def _serve(self):
        pending = b''
        try:
            while self.running:
                if not select.select([self.fd], [], [], .1)[0]:
                    continue
                pending += os.read(self.fd, 4096)
                while b'\n' in pending:
                    raw, pending = pending.split(b'\n', 1)
                    line = raw.decode()
                    with self.condition:
                        self.records.append(line)
                        paths, hold = self.paths[:], self.hold
                        self.condition.notify_all()
                    if not line.startswith('omarchy-pick:') or hold:
                        continue
                    reply = Path(line.split('\t', 1)[0].removeprefix('omarchy-pick:'))
                    assert reply.parent == self.runtime
                    try:
                        fd = os.open(reply, os.O_WRONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
                    except OSError as error:
                        if error.errno in (errno.ENOENT, errno.ENXIO):
                            continue  # Caller may already have cancelled.
                        raise
                    try:
                        assert stat.S_ISFIFO(os.fstat(fd).st_mode)
                        data = ('\n'.join(paths) + '\n').encode()
                        assert os.write(fd, data) == len(data)
                    finally:
                        os.close(fd)
        except Exception as error:
            if self.running:
                self.errors.append(repr(error))

    def close(self):
        self.running = False
        self.worker.join(timeout=1)
        os.close(self.fd)
