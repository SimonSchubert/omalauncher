"""Startup A->B injection then subscribed B->A, on a separate private bus.

Invoked by the native suite using its freshly compiled host binary. This does
not claim to preload a host interposer into an optional QEMU/ARM64 artifact.
"""
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import time


def main():
    suite = runpy.run_path(str(Path(__file__).with_name('test-guest-portals.py')))
    binary, interposer, kind = sys.argv[1:]
    case = suite['PortalTests']()
    with tempfile.TemporaryDirectory(prefix='portal-startup-') as scratch:
        case.runtime = Path(scratch)
        case.appearance = case.runtime / 'omalauncher-color-scheme'
        case.status = case.runtime / 'omalauncher-system-status'
        case.write_appearance(['dark', '#112233', '0', '0'])
        status_a = 'power-saver=0\navailable=0\nmetered=0\nconnectivity=1\n'
        case.write_status(status_a)
        preload = interposer
        if 'address' in os.environ.get('PORTAL_TEST_CFLAGS', ''):
            # ASan must precede the test interposer in the link map.
            asan = subprocess.check_output(['cc', '-print-file-name=libasan.so'], text=True).strip()
            preload = asan + ':' + preload
        with tempfile.TemporaryFile(mode='w+') as log:
            service = subprocess.Popen([binary], stdout=log, stderr=log, env={
                **os.environ, 'XDG_RUNTIME_DIR': scratch, 'GIO_USE_VFS': 'local',
                'G_DEBUG': 'fatal-warnings', 'LD_PRELOAD': preload,
                'PORTAL_TEST_WATCH_KIND': kind,
            })
            try:
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    case.assertIsNone(service.poll(), 'startup service exited')
                    owner = subprocess.run(['gdbus', 'call', '--session', '--dest', 'org.freedesktop.DBus',
                        '--object-path', '/org/freedesktop/DBus', '--method',
                        'org.freedesktop.DBus.GetConnectionUnixProcessID', suite['BUS']],
                        capture_output=True, text=True, timeout=5)
                    if owner.returncode == 0:
                        case.assertIn(str(service.pid), owner.stdout)
                        break
                    time.sleep(.01)
                else:
                    case.fail('startup service did not acquire name')

                def restore_a():
                    # Subscription is live BEFORE reading the client baseline B.
                    if kind == 'status':
                        case.assertIn('<true>', case.call('org.freedesktop.DBus.Properties.Get', suite['POWER'], 'power-saver-enabled'))
                        case.assertIn('true', case.call(suite['NETWORK'] + '.GetAvailable'))
                        case.assertIn('uint32 4', case.call(suite['NETWORK'] + '.GetConnectivity'))
                        case.write_status(status_a)
                    else:
                        for key in ('contrast', 'reduced-motion'):
                            case.assertIn('uint32 1', case.call(suite['SETTINGS'] + '.ReadOne', 'org.freedesktop.appearance', key))
                        case.write_appearance(['dark', '#112233', '0', '0'])

                expected = (["'power-saver-enabled': <false>", suite['NETWORK'] + '.changed']
                            if kind == 'status' else ["'contrast', <uint32 0>", "'reduced-motion', <uint32 0>"])
                seen = case.capture_bus_text(restore_a, matches=lambda s: all(value in s for value in expected))
                print('captured:', repr(seen), flush=True)
                for value in expected:
                    case.assertIn(value, seen)
                case.assertIsNone(service.poll())
            finally:
                if service.poll() is None:
                    service.terminate()
                service.wait(timeout=5)
                log.seek(0)
                output = log.read()
                print(output, end='', flush=True)
            case.assertIn('startup injection: ' + kind, output)


if __name__ == '__main__':
    main()
