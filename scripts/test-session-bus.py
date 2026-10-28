#!/usr/bin/env python3
"""Host-only smoke test of SessionBus's actual D-Bus policy and shared address.

Requires dbus-daemon and gdbus; does not start Android, proot or a device.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/kotlin/dev/omalauncher/linux/SessionBus.kt'


class SessionBusTest(unittest.TestCase):
    def test_independent_clients_share_anonymous_session_bus(self):
        source = SOURCE.read_text()
        match = re.search(r'private val config\s*=\s*"""(.*?)"""\.trimIndent\(\)', source, re.S)
        assert match is not None, 'SessionBus config not found'
        self.assertIn('<standard_session_servicedirs/>', match.group(1))
        self.assertIn('const val ADDRESS = "unix:path=${Proot.GUEST_RUNTIME_DIR}/bus"', source)
        with tempfile.TemporaryDirectory(prefix='omalauncher-bus-') as tmp:
            socket = Path(tmp) / 'bus'
            config = Path(tmp) / 'bus.conf'
            # Mirror Kotlin trimIndent and interpolation from the production config.
            lines = match.group(1).splitlines()
            lines = [line for line in lines if line.strip()]
            indent = min(len(line) - len(line.lstrip()) for line in lines)
            config.write_text('\n'.join(line[indent:] for line in lines).replace('$ADDRESS', f'unix:path={socket}') + '\n')
            daemon = subprocess.Popen(['dbus-daemon', '--config-file=' + str(config), '--nofork', '--nopidfile'],
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                for _ in range(100):
                    if socket.exists() or daemon.poll() is not None:
                        break
                    time.sleep(.02)
                assert daemon.stderr is not None
                self.assertTrue(socket.exists(), daemon.stderr.read().decode() if daemon.poll() is not None else 'no socket')
                env = {**os.environ, 'DBUS_SESSION_BUS_ADDRESS': f'unix:path={socket}'}
                def call(method, *args):
                    return subprocess.run(['gdbus', 'call', '--session', '--dest', 'org.freedesktop.DBus',
                                           '--object-path', '/org/freedesktop/DBus',
                                           '--method', 'org.freedesktop.DBus.' + method, *args],
                                          env=env, text=True, capture_output=True, timeout=5, check=True).stdout.strip()
                # Two separately launched clients get the same daemon, not a per-app bus.
                first = call('GetId')
                second = call('GetId')
                self.assertEqual(first, second)
                self.assertIn('org.freedesktop.DBus', call('ListNames'))
            finally:
                daemon.terminate()
                daemon.communicate(timeout=5)


if __name__ == '__main__':
    unittest.main()
