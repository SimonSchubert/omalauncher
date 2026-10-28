#!/usr/bin/env python3
"""Protocol regressions against the real GLib service on a private session bus.
Run: python3 scripts/test-guest-portals.py (starts its own activation-free bus).
Requires a C compiler, pkg-config, gio-2.0, gio-unix-2.0 and gdbus; no Python dependencies.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import shlex
import shutil
import sys
from portal_test_backend import FakeAndroid
from portal_test_legacy import FakeLegacyBridge

ROOT = Path(__file__).resolve().parents[1]
BUS = 'org.freedesktop.portal.Desktop'
PATH = '/org/freedesktop/portal/desktop'
SETTINGS = 'org.freedesktop.portal.Settings'
POWER = 'org.freedesktop.portal.PowerProfileMonitor'
NETWORK = 'org.freedesktop.portal.NetworkMonitor'


class PortalTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not os.environ.get('DBUS_SESSION_BUS_ADDRESS'):
            raise RuntimeError('Run this test under dbus-run-session')
        cls.scratch = tempfile.TemporaryDirectory(prefix='omalauncher-portals-')
        cls.runtime = Path(cls.scratch.name)
        cls.appearance = cls.runtime / 'omalauncher-color-scheme'
        cls.status = cls.runtime / 'omalauncher-system-status'
        flags = subprocess.check_output(['pkg-config', '--cflags', '--libs', 'gio-2.0', 'gio-unix-2.0'], text=True).split()
        flags += shlex.split(os.environ.get('PORTAL_TEST_CFLAGS', ''))
        cls.binary = cls.runtime / 'portal'
        subprocess.run(['cc', '-Wall', '-Wextra', '-Wno-unused-parameter', '-O0', '-g',
                        str(ROOT / 'guest/omalauncher-portal/omalauncher-portal.c'), str(ROOT / 'guest/omalauncher-portal/portal-settings.c'), str(ROOT / 'guest/omalauncher-portal/portal-actions.c'),
 str(ROOT / 'guest/omalauncher-portal/portal-status.c'), str(ROOT / 'guest/omalauncher-portal/portal-transport.c'), '-o', str(cls.binary)] + flags, check=True)
        cls.log = (cls.runtime / 'service.log').open('w+')
        cls.watch_interposer = cls.runtime / 'watch-interposer.so'
        subprocess.run(['cc', '-Wall', '-Wextra', '-shared', '-fPIC',
                        str(ROOT / 'scripts/portal-test-watch-interposer.c'), '-o', str(cls.watch_interposer),
                        '-ldl'] + flags, check=True)
        cls.client = cls.runtime / 'client'
        subprocess.run(['cc', '-Wall', '-Wextra', '-Wno-unused-parameter', '-O0', '-g', str(ROOT / 'scripts/portal-test-client.c'), '-o', str(cls.client)] + flags, check=True)
        cls.serializer = cls.runtime / 'gtk-serializer'
        gtk_flags = shlex.split(subprocess.check_output(['pkg-config', '--cflags', '--libs', 'gtk+-unix-print-3.0'], text=True))
        subprocess.run(['cc', '-Wall', '-O0', '-g', str(ROOT / 'guest/omalauncher-portal/portal-print-dialog.c'), '-o', str(cls.serializer), *gtk_flags], check=True)
        cls.print_helper = cls.runtime / 'print-helper'
        shutil.copyfile(ROOT / 'scripts/portal_test_print_dialog.py', cls.print_helper)
        cls.print_helper.chmod(0o700)
        cls.backend = FakeAndroid(cls.runtime)
        cls.legacy = FakeLegacyBridge(cls.runtime)
        # Optional explicit command exercises a shipped ARM64 binary under QEMU
        # while retaining the same private bus, client and endpoint doubles.
        portal_command = shlex.split(os.environ.get('PORTAL_TEST_PORTAL_COMMAND', '')) or [str(cls.binary)]
        cls.service = subprocess.Popen(portal_command, env={**os.environ, 'XDG_RUNTIME_DIR': str(cls.runtime), 'G_DEBUG': 'fatal-warnings', 'GIO_USE_VFS': 'local', 'OMALAUNCHER_PRINT_HELPER': str(cls.print_helper), 'PORTAL_TEST_GTK_SERIALIZER': str(cls.serializer)}, stdout=cls.log, stderr=cls.log)
        for _ in range(60):
            if cls.service.poll() is not None:
                cls.log.seek(0)
                raise RuntimeError(cls.log.read())
            result = subprocess.run(['gdbus', 'call', '--session', '--dest', 'org.freedesktop.DBus', '--object-path', '/org/freedesktop/DBus', '--method', 'org.freedesktop.DBus.GetConnectionUnixProcessID', BUS], text=True, capture_output=True, timeout=5)
            if result.returncode == 0:
                if str(cls.service.pid) not in result.stdout:
                    raise RuntimeError('private bus name belongs to a different portal process: ' + result.stdout)
                break
            time.sleep(.025)
        else:
            raise RuntimeError('portal did not acquire its bus name')

    @classmethod
    def tearDownClass(cls):
        cls.legacy.close()
        cls.backend.close()
        cls.service.terminate()
        cls.service.wait(timeout=5)
        cls.log.close()
        cls.scratch.cleanup()

    @classmethod
    def dbus(cls, method, *args):
        return subprocess.run(['gdbus', 'call', '--session', '--dest', BUS, '--object-path', PATH, '--method', method, *args], text=True, capture_output=True, timeout=5)

    def call(self, method, *args):
        result = self.dbus(method, *args)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    def setUp(self):
        self.appearance.unlink(missing_ok=True)
        self.status.unlink(missing_ok=True)
        self.legacy.configure()

    def write_appearance(self, lines):
        tmp = self.appearance.with_suffix('.tmp')
        tmp.write_text('\n'.join(lines) + '\n')
        tmp.replace(self.appearance)

    def write_status(self, text):
        tmp = self.status.with_suffix('.tmp')
        tmp.write_text(text)
        tmp.replace(self.status)

    def capture_bus_text(self, actions, matches=None, timeout=3.0):
        """Runs `actions` after a `gdbus monitor` on the portal object is
        confirmed ready, and returns whatever signal text it captured.
        `matches(text)` breaks the capture early once satisfied; otherwise
        capture runs for the full `timeout`, which is what a dedup/no-signal
        assertion needs."""
        import select
        monitor = subprocess.Popen(['gdbus', 'monitor', '--session', '--dest', BUS, '--object-path', PATH],
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=0)
        assert monitor.stdout is not None
        monitor_fd = monitor.stdout.fileno()
        pending = b''
        seen = b''

        def read_chunk(deadline):
            if select.select([monitor_fd], [], [], max(0, deadline - time.monotonic()))[0]:
                chunk = os.read(monitor_fd, 65536)
                self.assertTrue(chunk, 'signal monitor exited: ' + (pending + seen).decode(errors='replace'))
                return chunk
            self.assertIsNone(monitor.poll(), 'signal monitor exited')
            return b''

        try:
            ready = False
            until = time.monotonic() + 3
            while time.monotonic() < until and not ready:
                pending += read_chunk(until)
                while b'\n' in pending:
                    line, pending = pending.split(b'\n', 1)
                    if b'is owned by' in line:
                        ready = True
                        break
            self.assertTrue(ready, 'signal monitor not ready: ' + pending.decode(errors='replace'))
            seen = pending
            pending = b''
            actions()
            until = time.monotonic() + timeout
            while time.monotonic() < until:
                seen += read_chunk(until)
                # Only complete lines may satisfy a payload assertion. os.read
                # drains bytes directly, never hiding lines in TextIO read-ahead.
                complete = seen[:seen.rfind(b'\n') + 1].decode(errors='replace')
                if matches and matches(complete):
                    break
        finally:
            if monitor.poll() is None:
                monitor.terminate()
            remaining, _ = monitor.communicate(timeout=5)
            if remaining:
                seen += remaining.encode() if isinstance(remaining, str) else remaining
        return seen.decode(errors='replace')

    def native(self, case, *args):
        result = subprocess.run([str(self.client), case, *map(str, args)], text=True, capture_output=True, timeout=8)
        if result.returncode:
            self.log.flush(); self.log.seek(0)
            self.fail(result.stdout + result.stderr + '\nSERVICE:\n' + self.log.read())
        self.assertIsNone(self.service.poll(), 'portal died')
        self.assertFalse(self.backend.errors, self.backend.errors)
        self.assertFalse(self.legacy.errors, self.legacy.errors)

    def assert_no_picker_fifos(self):
        deadline = time.monotonic() + 3
        while list(self.runtime.glob('pick.*')) and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertFalse(list(self.runtime.glob('pick.*')), 'cancelled picker leaked its reply FIFO')

    def test_legacy_filechooser_open_multiple_roundtrip(self):
        paths = [self.runtime / 'first document.txt', self.runtime / 'second.txt']
        self.legacy.configure(paths)
        self.native('files-open', *paths)
        self.assertIn('\topen\t1\t', self.legacy.records[-1])

    def test_legacy_filechooser_save_preserves_folder_and_name(self):
        path = self.runtime / 'saved document.txt'
        self.legacy.configure([path])
        self.native('files-save', path)
        self.assertIn(f'\tsave\t0\t{self.runtime}\t{path.name}\t', self.legacy.records[-1])

    def test_legacy_filechooser_cancel(self):
        self.native('files-cancel')

    def test_legacy_filechooser_close_is_owner_bound_and_releases_fifo(self):
        self.legacy.configure(hold=True)
        self.native('files-close')
        self.assert_no_picker_fifos()

    def test_legacy_filechooser_caller_exit_releases_fifo(self):
        self.legacy.configure(hold=True)
        self.native('files-gone')
        self.assert_no_picker_fifos()

    def test_legacy_filechooser_invalid_token_does_not_kill_service(self):
        self.native('files-invalid')

    def test_legacy_filechooser_pending_requests_are_bounded(self):
        self.legacy.configure(hold=True)
        self.native('files-limit')
        self.assert_no_picker_fifos()

    def test_legacy_notifications_still_use_the_original_bridge(self):
        self.native('legacy-notify')
        with self.legacy.condition:
            self.assertTrue(self.legacy.condition.wait_for(lambda: any('Legacy title' in line for line in self.legacy.records), timeout=2))

    def test_uri_real_dbus_to_socket_roundtrip(self):
        self.native('uri')
        self.assertTrue(any(fields.get('uri') == 'https://example.org/portal-test' for fields, _ in self.backend.records))

    def test_email_fields_and_attachment_descriptors_roundtrip(self):
        attachments = [self.runtime / 'mail first.txt', self.runtime / 'mail second.txt']
        for path, content in zip(attachments, (b'MAIL-FIRST', b'MAIL-SECOND')):
            path.write_bytes(content)
        self.native('email', *attachments)
        index = next(i for i in range(len(self.backend.records) - 1, -1, -1)
                     if self.backend.records[i][0]['operation'] == 'email')
        fields, contents = self.backend.records[index]
        self.assertEqual(fields['address'], 'legacy@example.org')
        self.assertEqual([fields[f'addresses-{i}'] for i in range(2)], ['first@example.org', 'second@example.org'])
        self.assertEqual(fields['cc-0'], 'copy@example.org')
        self.assertEqual(fields['bcc-0'], 'hidden@example.org')
        self.assertEqual(fields['subject'], 'Portal email fixture')
        self.assertEqual(fields['body'], 'First line\noperation=print\nLast line')
        self.assertEqual(contents, [b'MAIL-FIRST', b'MAIL-SECOND'])
        self.assertEqual([(st.st_dev, st.st_ino) for st in self.backend.fd_stats[index]],
                         [(path.stat().st_dev, path.stat().st_ino) for path in attachments])

    def test_email_lists_over_64_are_not_truncated(self):
        attachment = self.runtime / 'mail many.txt'; attachment.write_text('attachment')
        self.native('email-many', attachment)
        fields = next(fields for fields, _ in reversed(self.backend.records) if fields.get('operation') == 'email')
        for key in ('addresses', 'cc', 'bcc'):
            actual = [fields[f'{key}-{i}'] for i in range(65)]
            self.assertEqual(actual, [f'{key}-{i}@example.org' for i in range(65)])

    def test_email_rejects_wrong_attachment_list_types(self):
        before = len(self.backend.records)
        for kind in ('ai', 'as', 's', 'u'):
            with self.subTest(kind=kind):
                self.native('email-invalid-option', 'attachment_fds', kind)
        self.assertEqual(len(self.backend.records), before, 'invalid attachments reached Android')

    def test_email_rejects_mistyped_compose_fields(self):
        before = len(self.backend.records)
        for key in ('address', 'addresses', 'cc', 'bcc', 'subject', 'body'):
            with self.subTest(key=key):
                self.native('email-invalid-option', key, 'u')
        self.assertEqual(len(self.backend.records), before, 'invalid compose fields reached Android')

    def test_email_optional_fields_can_be_absent_or_empty(self):
        for case in ('email-empty', 'email-empty-list'):
            with self.subTest(case=case):
                before = len(self.backend.records)
                self.native(case)
                self.assertEqual(len(self.backend.records), before + 1)
                fields, contents = self.backend.records[-1]
                self.assertEqual(fields['operation'], 'email')
                self.assertEqual(fields['fd-count'], '0')
                self.assertEqual(contents, [])

    def test_email_accepts_eight_attachment_capabilities(self):
        source = self.runtime / 'mail eight.txt'; source.write_bytes(b'EIGHT-ATTACHMENTS')
        before = len(self.backend.records)
        self.native('email', *([source] * 8))
        self.assertEqual(len(self.backend.records), before + 1)
        fields, contents = self.backend.records[-1]
        self.assertEqual(fields['fd-count'], '8')
        self.assertEqual(contents, [b'EIGHT-ATTACHMENTS'] * 8)

    def test_email_invalid_fd_batches_release_handles_and_descriptors(self):
        source = self.runtime / 'mail rejected.txt'; source.write_bytes(b'VALID-AFTER-REJECTION')
        original = source.stat()
        for kind in ('bad-index', 'directory', 'too-many', 'missing-list'):
            with self.subTest(kind=kind):
                before = len(self.backend.records)
                self.native('email-invalid-fds', kind, source)
                # Seventy rejected batches must not reach Android; the final
                # valid request reuses their handle and is the sole new packet.
                self.assertEqual(len(self.backend.records), before + 1)
                self.assertEqual(self.backend.records[-1][1], [b'VALID-AFTER-REJECTION'])
                deadline = time.monotonic() + 3
                while True:
                    retained = []
                    for fd in Path(f'/proc/{self.service.pid}/fd').iterdir():
                        try:
                            actual = fd.stat()
                        except FileNotFoundError:
                            continue
                        if (actual.st_dev, actual.st_ino) == (original.st_dev, original.st_ino):
                            retained.append(fd.name)
                    if not retained or time.monotonic() >= deadline:
                        break
                    time.sleep(.01)
                self.assertFalse(retained, f'portal leaked document descriptors: {retained}')

    def test_open_file_accepts_directory_descriptor(self):
        self.native('fd', self.runtime)
        index = next(i for i in range(len(self.backend.records) - 1, -1, -1)
                     if self.backend.records[i][0]['operation'] == 'open-file')
        received = self.backend.fd_stats[index][0]
        self.assertEqual((received.st_dev, received.st_ino), (self.runtime.stat().st_dev, self.runtime.stat().st_ino))

    def test_print_token_is_owner_bound_and_single_use(self):
        document = self.runtime / 'print.ps'
        document.write_bytes(b'%!PS-Adobe-3.0\nshowpage\n')
        self.native('print-token', document)

    def test_cancelled_print_selection_has_no_token(self):
        self.native('print-cancel')

    def test_pending_print_dialog_close_is_owner_bound(self):
        self.native('print-close')

    def test_message_attached_fd_is_transferred_not_an_integer(self):
        source = self.runtime / 'document.txt'
        source.write_bytes(b'FD-PAYLOAD')
        self.native('fd', source)
        self.assertTrue(any(contents == [b'FD-PAYLOAD'] for fields, contents in self.backend.records if fields['operation'] == 'open-file'))

    def test_bad_fd_index_is_rejected(self):
        source = self.runtime / 'bad-fd.txt'; source.write_text('data')
        self.native('bad-fd', source)

    def test_request_close_is_caller_owned(self):
        self.native('cancel')

    def test_duplicate_handle_does_not_corrupt_original(self):
        self.native('duplicate')

    def test_bad_handle_token_is_rejected(self):
        self.native('bad-token')

    def test_inhibit_has_no_response_and_releases_on_close(self):
        before = self.backend.closed
        self.native('inhibit')
        self.assertTrue(self.backend.wait_closed(before + 1), 'inhibitor transport not closed')

    def test_inhibit_releases_on_caller_exit(self):
        before = self.backend.closed
        self.native('inhibit-exit')
        self.assertTrue(self.backend.wait_closed(before + 1), 'caller exit leaked inhibitor transport')

    def test_notification_action_preserves_target_variant(self):
        self.native('notification')

    def test_notification_outlives_sender_and_new_instance_can_withdraw(self):
        self.native('notification-exit')
        before = self.backend.closed
        self.native('notification-withdraw')
        self.assertTrue(self.backend.wait_closed(before + 1), 'new instance did not withdraw persistent notification')

    def test_notification_replace_retires_both_connections(self):
        before = self.backend.closed
        self.native('notification-replace')
        self.assertTrue(self.backend.wait_closed(before + 2), 'replacement left old notification connection alive')

    def test_settings_version(self):
        self.assertIn('uint32 2', self.call('org.freedesktop.DBus.Properties.Get', SETTINGS, 'version'))

    def test_read_one_no_preference(self):
        self.assertIn('<uint32 0>', self.call(SETTINGS + '.ReadOne', 'org.freedesktop.appearance', 'color-scheme'))

    def test_read_one_dark_and_light(self):
        for text, expected in [('dark\n', 1), ('light\n', 2), ('garbage\n', 0)]:
            with self.subTest(text=text):
                self.appearance.write_text(text)
                self.assertIn(f'<uint32 {expected}>', self.call(SETTINGS + '.ReadOne', 'org.freedesktop.appearance', 'color-scheme'))

    def test_legacy_read_is_double_wrapped(self):
        self.appearance.write_text('dark\n')
        self.assertIn('<<uint32 1>>', self.call(SETTINGS + '.Read', 'org.freedesktop.appearance', 'color-scheme'))

    def test_read_all_namespace_filter(self):
        for pattern in ['[]', "['org.freedesktop.appearance']", "['org.freedesktop.*']", "['*']", "['']"]:
            self.assertIn('color-scheme', self.call(SETTINGS + '.ReadAll', pattern))
        self.assertNotIn('color-scheme', self.call(SETTINGS + '.ReadAll', "['org.other.*']"))

    def test_unknown_setting(self):
        for ns, key in [('org.unknown', 'color-scheme'), ('org.freedesktop.appearance', 'missing')]:
            result = self.dbus(SETTINGS + '.ReadOne', ns, key)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('org.freedesktop.portal.Error.NotFound', result.stderr)

    def test_atomic_replace_notifies(self):
        self.appearance.write_text('dark\n')
        seen = self.capture_bus_text(lambda: self.write_appearance(['light']),
                                     matches=lambda s: "'color-scheme', <uint32 2>" in s)
        self.assertIn('SettingChanged', seen)
        self.assertIn("'color-scheme', <uint32 2>", seen)

    # ---- PowerProfileMonitor v1 / NetworkMonitor v2, over the shared
    # omalauncher-system-status snapshot. ----

    def test_power_profile_monitor_version_is_one(self):
        self.assertIn('uint32 1', self.call('org.freedesktop.DBus.Properties.Get', POWER, 'version'))

    def test_network_monitor_version_is_two_not_three(self):
        self.assertIn('uint32 2', self.call('org.freedesktop.DBus.Properties.Get', NETWORK, 'version'))

    def test_network_monitor_does_not_advertise_v3_methods(self):
        # GetStatus/CanReach are version-3 additions; a real v3 implementation
        # is a separate deliberate reachability deliverable, not this one.
        for method, args in [('GetStatus', []), ('CanReach', ['example.org', '443'])]:
            with self.subTest(method=method):
                result = self.dbus(f'{NETWORK}.{method}', *args)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('UnknownMethod', result.stderr)

    def test_power_saver_unknown_without_snapshot(self):
        result = self.dbus('org.freedesktop.DBus.Properties.Get', POWER, 'power-saver-enabled')
        self.assertNotEqual(result.returncode, 0)

    def test_power_saver_known_values(self):
        for value, expected in [('0', 'false'), ('1', 'true')]:
            with self.subTest(value=value):
                self.write_status(f'power-saver={value}\n')
                self.assertIn(expected, self.call('org.freedesktop.DBus.Properties.Get', POWER, 'power-saver-enabled'))

    def test_power_saver_malformed_value_is_unknown(self):
        self.write_status('power-saver=2\n')
        result = self.dbus('org.freedesktop.DBus.Properties.Get', POWER, 'power-saver-enabled')
        self.assertNotEqual(result.returncode, 0)

    def test_glib_monitor_frontends_select_portals_and_receive_live_changes(self):
        # Establish the published baseline via an observed transition, not a
        # getter that could run before the file-monitor event.
        baseline = 'power-saver=1\navailable=1\nmetered=0\nconnectivity=4\n'
        seen = self.capture_bus_text(lambda: self.write_status(baseline), matches=lambda s: "'power-saver-enabled': <true>" in s and f'{NETWORK}.changed' in s)
        self.assertIn("'power-saver-enabled': <true>", seen)
        self.assertIn(f'{NETWORK}.changed', seen)
        env = {**os.environ, 'GTK_USE_PORTAL': '1', 'GIO_USE_PORTALS': '0'}
        old = subprocess.run([str(self.client), 'monitor-frontends', str(self.status)], env=env,
                             text=True, capture_output=True, timeout=8)
        self.assertEqual(old.returncode, 42, old.stdout + old.stderr)
        self.assertNotIn('network-backend=GNetworkMonitorPortal', old.stdout)
        current = subprocess.run([str(self.client), 'monitor-frontends', str(self.status)],
                                 env={**env, 'GIO_USE_PORTALS': '1'}, text=True, capture_output=True, timeout=8)
        self.assertEqual(current.returncode, 0, current.stdout + current.stderr)
        self.assertIn('GLib monitor initial state and live transitions: OK', current.stdout)
        self.assertIsNone(self.service.poll())

    def test_network_unknown_without_snapshot(self):
        for method in ('GetAvailable', 'GetMetered', 'GetConnectivity'):
            with self.subTest(method=method):
                result = self.dbus(f'{NETWORK}.{method}')
                self.assertNotEqual(result.returncode, 0)

    def test_network_full_captive_limited_offline_mappings(self):
        cases = [
            ('full', 'available=1\nmetered=0\nconnectivity=4\n', True, False, 4),
            ('captive', 'available=1\nmetered=0\nconnectivity=3\n', True, False, 3),
            ('limited', 'available=1\nmetered=1\nconnectivity=2\n', True, True, 2),
            ('offline', 'available=0\nmetered=0\nconnectivity=1\n', False, False, 1),
        ]
        for name, text, available, metered, connectivity in cases:
            with self.subTest(name=name):
                self.write_status(text)
                self.assertIn('true' if available else 'false', self.call(f'{NETWORK}.GetAvailable'))
                self.assertIn('true' if metered else 'false', self.call(f'{NETWORK}.GetMetered'))
                self.assertIn(f'uint32 {connectivity}', self.call(f'{NETWORK}.GetConnectivity'))

    def test_network_malformed_boolean_is_unknown(self):
        self.write_status('available=2\nmetered=0\nconnectivity=4\n')
        result = self.dbus(f'{NETWORK}.GetAvailable')
        self.assertNotEqual(result.returncode, 0)

    def test_network_out_of_range_connectivity_is_unknown(self):
        self.write_status('available=1\nmetered=0\nconnectivity=5\n')
        result = self.dbus(f'{NETWORK}.GetConnectivity')
        self.assertNotEqual(result.returncode, 0)

    def test_network_partial_snapshot_is_unknown(self):
        # available/metered without connectivity: the triplet is only as
        # good as its worst field, so no getter may report a value.
        self.write_status('available=1\nmetered=0\n')
        for method in ('GetAvailable', 'GetMetered', 'GetConnectivity'):
            with self.subTest(method=method):
                result = self.dbus(f'{NETWORK}.{method}')
                self.assertNotEqual(result.returncode, 0)

    def test_network_known_while_power_unknown_and_vice_versa(self):
        self.write_status('available=1\nmetered=0\nconnectivity=4\n')
        self.assertIn('true', self.call(f'{NETWORK}.GetAvailable'))
        self.assertNotEqual(self.dbus('org.freedesktop.DBus.Properties.Get', POWER, 'power-saver-enabled').returncode, 0)
        self.write_status('power-saver=1\n')
        self.assertIn('true', self.call('org.freedesktop.DBus.Properties.Get', POWER, 'power-saver-enabled'))
        self.assertNotEqual(self.dbus(f'{NETWORK}.GetAvailable').returncode, 0)

    def test_status_symlink_destination_is_ignored(self):
        real = self.runtime / 'real-status'
        real.write_text('power-saver=1\n')
        self.status.symlink_to(real)
        result = self.dbus('org.freedesktop.DBus.Properties.Get', POWER, 'power-saver-enabled')
        self.assertNotEqual(result.returncode, 0)

    def test_status_oversized_snapshot_is_ignored(self):
        self.write_status('power-saver=1\n' + 'x' * 600 + '\n')
        result = self.dbus('org.freedesktop.DBus.Properties.Get', POWER, 'power-saver-enabled')
        self.assertNotEqual(result.returncode, 0)

    def startup_watch_regression(self, kind):
        result = subprocess.run(['dbus-run-session', '--config-file=' + str(ROOT / 'scripts/portal-test-bus.conf'),
                                 '--', sys.executable, str(ROOT / 'scripts/portal_test_startup.py'),
                                 str(self.binary), str(self.watch_interposer), kind],
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_startup_status_watch_precedes_baseline(self):
        self.startup_watch_regression('status')

    def test_startup_settings_watch_precedes_baseline(self):
        self.startup_watch_regression('settings')

    def test_power_saver_same_value_rewrite_does_not_resignal(self):
        seen = self.capture_bus_text(lambda: self.write_status('power-saver=1\n'),
                                     matches=lambda s: 'PropertiesChanged' in s and '<true>' in s)
        self.assertIn("'power-saver-enabled': <true>", seen)
        seen = self.capture_bus_text(lambda: self.write_status('power-saver=1\n'), timeout=1.5)
        self.assertNotIn('PropertiesChanged', seen)

    def test_power_saver_change_emits_properties_changed(self):
        self.write_status('power-saver=0\n')
        self.call('org.freedesktop.DBus.Properties.Get', POWER, 'power-saver-enabled')
        seen = self.capture_bus_text(lambda: self.write_status('power-saver=1\n'),
                                      matches=lambda s: 'PropertiesChanged' in s)
        self.assertIn('PropertiesChanged', seen)
        self.assertIn('power-saver-enabled', seen)

    def test_power_saver_invalidation_on_becoming_unknown(self):
        self.write_status('power-saver=1\n')
        self.call('org.freedesktop.DBus.Properties.Get', POWER, 'power-saver-enabled')
        seen = self.capture_bus_text(lambda: self.write_status('available=1\nmetered=0\nconnectivity=4\n'),
                                      matches=lambda s: 'PropertiesChanged' in s)
        line = next(l for l in seen.splitlines() if 'PropertiesChanged' in l)
        self.assertIn('power-saver-enabled', line)
        # Invalidated, not a fabricated false: an empty changed-properties
        # dict, with the name only in the invalidated-properties list.
        self.assertIn('{}', line)
        self.assertNotIn('<true>', line)
        self.assertNotIn('<false>', line)

    def test_network_changed_dedup_on_same_values(self):
        seen = self.capture_bus_text(lambda: self.write_status('available=1\nmetered=0\nconnectivity=4\n'),
                                     matches=lambda s: NETWORK + '.changed' in s)
        self.assertIn(NETWORK + '.changed', seen)
        seen = self.capture_bus_text(lambda: self.write_status('available=1\nmetered=0\nconnectivity=4\n'), timeout=1.5)
        self.assertNotIn('.changed', seen)

    def test_network_changed_emitted_on_value_change(self):
        self.write_status('available=1\nmetered=0\nconnectivity=4\n')
        self.call(f'{NETWORK}.GetAvailable')
        seen = self.capture_bus_text(lambda: self.write_status('available=1\nmetered=0\nconnectivity=2\n'),
                                      matches=lambda s: '.changed' in s)
        self.assertIn('.changed', seen)

    # ---- Settings accessibility fields (contrast, reduced-motion): the
    # Android/parent wire contract appends two lines to the existing
    # two-line appearance writer. portal-settings.c itself remains the
    # parent's file; these cases exercise the agreed contract so the native
    # suite documents it, and are expected to start passing once the
    # parent's reader lands. ----

    def test_accessibility_fields_default_zero_for_old_two_line_writer(self):
        self.write_appearance(['dark', '#112233'])
        for key in ('contrast', 'reduced-motion'):
            with self.subTest(key=key):
                self.assertIn('uint32 0', self.call(SETTINGS + '.ReadOne', 'org.freedesktop.appearance', key))

    def test_accessibility_fields_exact_uint_types_from_new_four_line_writer(self):
        self.write_appearance(['dark', '#112233', '1', '1'])
        self.assertIn('uint32 1', self.call(SETTINGS + '.ReadOne', 'org.freedesktop.appearance', 'contrast'))
        self.assertIn('uint32 1', self.call(SETTINGS + '.ReadOne', 'org.freedesktop.appearance', 'reduced-motion'))

    def test_accessibility_fields_in_read_all_and_namespace_filter(self):
        self.write_appearance(['light', '#aabbcc', '0', '1'])
        included = self.call(SETTINGS + '.ReadAll', "['org.freedesktop.appearance']")
        self.assertIn('contrast', included)
        self.assertIn('reduced-motion', included)
        excluded = self.call(SETTINGS + '.ReadAll', "['org.other.*']")
        self.assertNotIn('contrast', excluded)

    def test_accessibility_setting_changed_is_deduplicated(self):
        seen = self.capture_bus_text(lambda: self.write_appearance(['dark', '#112233', '1', '1']),
                                     matches=lambda s: "'contrast', <uint32 1>" in s and "'reduced-motion', <uint32 1>" in s)
        self.assertIn("'contrast', <uint32 1>", seen)
        self.assertIn("'reduced-motion', <uint32 1>", seen)
        seen = self.capture_bus_text(lambda: self.write_appearance(['dark', '#112233', '1', '1']), timeout=1.5)
        self.assertNotIn('contrast', seen)
        self.assertNotIn('reduced-motion', seen)
        seen = self.capture_bus_text(lambda: self.write_appearance(['dark', '#112233', '0', '0']),
                                     matches=lambda s: "'contrast', <uint32 0>" in s and "'reduced-motion', <uint32 0>" in s)
        self.assertIn("'contrast', <uint32 0>", seen)
        self.assertIn("'reduced-motion', <uint32 0>", seen)

    def test_existing_filechooser_and_notifications(self):
        self.assertIn('uint32', self.call('org.freedesktop.DBus.Properties.Get', 'org.freedesktop.portal.FileChooser', 'version'))
        result = subprocess.run(['gdbus', 'call', '--session', '--dest', 'org.freedesktop.Notifications', '--object-path', '/org/freedesktop/Notifications', '--method', 'org.freedesktop.Notifications.GetCapabilities'], text=True, capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('body', result.stdout)


from portal_test_monitor import SignalCaptureTests
SignalCaptureTests.capture_bus_text = PortalTests.capture_bus_text


if __name__ == '__main__':
    if os.environ.get('OMALAUNCHER_PRIVATE_TEST_BUS') != '1':
        sys.exit(subprocess.call(['dbus-run-session', '--config-file=' + str(ROOT / 'scripts/portal-test-bus.conf'), '--', sys.executable, str(Path(__file__).resolve()), *sys.argv[1:]], env={**os.environ, 'OMALAUNCHER_PRIVATE_TEST_BUS': '1'}))
    unittest.main(verbosity=2)
