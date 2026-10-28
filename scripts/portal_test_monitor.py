"""Real-pipe regressions for the signal capture helper (no native service needed)."""
import os
import subprocess
import sys
import unittest
from typing import Any
from unittest.mock import patch


class SignalCaptureTests(unittest.TestCase):
    # Bound by test-guest-portals.py so tests exercise its actual helper.
    capture_bus_text: Any = None

    def capture_fake(self, readiness, signals, *, exit_before=False, exit_after=False):
        # Each phase is ONE write. stdin is the action barrier, not a sleep.
        code = (
            'import os\n'
            f'os.write(1, {readiness!r})\n'
            + ('raise SystemExit(0)\n' if exit_before else '')
            + 'os.read(0, 1)\n'
            + f'os.write(1, {signals!r})\n'
            + ('' if exit_after else 'os.read(0, 1)\n')
        )
        monitor = subprocess.Popen([sys.executable, '-c', code], stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        assert monitor.stdin is not None
        action_fd = monitor.stdin.fileno()
        def action():
            os.write(action_fd, b'x')
        with patch.object(subprocess, 'Popen', return_value=monitor):
            return self.capture_bus_text(action, timeout=.2)

    def test_capture_coalesced_readiness(self):
        seen = self.capture_fake(b'Monitoring object\nname is owned by :1.2\n', b'expected signal\n')
        self.assertIn('expected signal', seen)

    def test_capture_negative_assertion_sees_coalesced_forbidden_signal(self):
        seen = self.capture_fake(b'name is owned by :1.2\n',
                                 b'unrelated signal\nPropertiesChanged power-saver-enabled <true>\n')
        self.assertIn('unrelated signal', seen)
        # The *negative* assertion must reject this capture, not silently pass.
        with self.assertRaises(AssertionError):
            self.assertNotIn('PropertiesChanged', seen)

    def test_capture_all_coalesced_settings_signals(self):
        seen = self.capture_fake(b'name is owned by :1.2\n',
                                 b'SettingChanged contrast\nSettingChanged reduced-motion\n')
        self.assertIn('contrast', seen)
        self.assertIn('reduced-motion', seen)

    def test_capture_exit_before_readiness_is_error(self):
        with self.assertRaisesRegex(AssertionError, 'monitor exited'):
            self.capture_fake(b'failed to connect\n', b'', exit_before=True)

    def test_capture_exit_after_readiness_is_not_silence(self):
        with self.assertRaisesRegex(AssertionError, 'monitor exited'):
            self.capture_fake(b'name is owned by :1.2\n', b'', exit_after=True)
