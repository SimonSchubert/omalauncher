#!/usr/bin/env python3
"""Real CUPS-PDF/Ghostscript conversion and backend outcomes, mocked Android only.
Requires PORTAL_TEST_PROOT (real executable), CUPS_PDF_TEST_SOURCE (unmodified
cups-pdf.c), compiler, libcups headers, GLib and Ghostscript. No system changes.
"""
import os
from pathlib import Path
import shlex
import shutil
import signal
import subprocess
import tempfile
import time
import unittest
from portal_test_backend import FakeAndroid

ROOT = Path(__file__).resolve().parents[1]


class CupsPdfTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.proot = os.environ.get('PORTAL_TEST_PROOT')
        cls.source = os.environ.get('CUPS_PDF_TEST_SOURCE')
        if not cls.proot or not cls.source:
            raise RuntimeError('Set PORTAL_TEST_PROOT and CUPS_PDF_TEST_SOURCE; this test does not silently skip missing dependencies')
        cls.temp = tempfile.TemporaryDirectory(prefix='omalauncher-cups-pdf-')
        cls.root = Path(cls.temp.name)
        cls.runtime = cls.root / 'runtime'
        cls.runtime.mkdir(mode=0o700)
        cls.backend = FakeAndroid(cls.runtime)
        cls.output = cls.root / 'output'
        for name in ['output', 'spool', 'log']:
            (cls.root / name).mkdir()
        flags = subprocess.check_output(['pkg-config', '--cflags', '--libs', 'gio-2.0', 'gio-unix-2.0'], text=True).split()
        native = ROOT / 'guest/omalauncher-portal'
        cls.wrapper = cls.root / 'wrapper'
        cls.hook = cls.root / 'hook'
        cls.pdf_backend = cls.root / 'cups-pdf'
        subprocess.run(['cc', '-O1', '-g', str(native / 'portal-cups-backend.c'), '-o', str(cls.wrapper)], check=True)
        subprocess.run(['cc', '-O1', '-g', str(native / 'portal-print-forward.c'), str(native / 'portal-transport.c'), '-o', str(cls.hook), *flags], check=True)
        cups_flags = shlex.split(subprocess.check_output(['cups-config', '--cflags', '--libs'], text=True))
        subprocess.run(['cc', '-O1', '-g', cls.source, '-o', str(cls.pdf_backend), *cups_flags], check=True)
        cls.config = cls.root / 'cups-pdf-omalauncher.conf'
        cls.config.write_text('\n'.join([
            f'Out {cls.output}', f'AnonDirName {cls.output}',
            f'Spool {cls.root / "spool"}', f'GSTmp {cls.root / "spool"}',
            f'Log {cls.root / "log"}', 'LogType 7', 'Grp root', 'AnonUser root',
            f'GhostScript {shutil.which("gs")}', 'PDFVer 1.7', 'Label 1', 'UserUMask 0077', 'AllowUnsafe 0',
            'PostProcessing /usr/local/lib/omalauncher/omalauncher-print-android',
        ]) + '\n')
        cls.document = cls.root / 'fixture.ps'
        cls.document.write_text('%!PS-Adobe-3.0\n%%Title: Omalauncher bridge fixture\n%%Pages: 1\n/Helvetica findfont 12 scalefont setfont\n72 720 moveto (Real CUPS-PDF / Ghostscript test) show\nshowpage\n%%EOF\n')
        cls.env = dict(os.environ, XDG_RUNTIME_DIR=str(cls.runtime))

    @classmethod
    def tearDownClass(cls):
        cls.backend.close()
        cls.temp.cleanup()

    def command(self, job, extra='', capture_status=False):
        arguments = [str(self.wrapper), str(job), 'root', f'Fixture {job}', '1', extra, str(self.document)]
        if capture_status:
            # PRoot returns its last tracee's exit status (tracee/event.c), which
            # can be an orphaned CUPS-PDF worker after cancellation. Capture the
            # actual backend wait status in its parent, as cupsd itself does.
            arguments = [shutil.which('sh'), '-c', '"$@"; outcome=$?; printf "backend-exit=%s\\n" "$outcome"; exit "$outcome"', 'backend-status', *arguments]
        return [self.proot, '-0',
                '-b', f'{self.pdf_backend}:/usr/lib/cups/backend/cups-pdf',
                '-b', f'{self.hook}:/usr/local/lib/omalauncher/omalauncher-print-android',
                '-b', f'{self.config}:/etc/cups/cups-pdf-omalauncher.conf', *arguments]

    def run_job(self, job, status, expected, extra=''):
        self.backend.print_status = status
        before = len(self.backend.records)
        result = subprocess.run(self.command(job, extra), env=self.env, text=True, capture_output=True, timeout=25)
        logs = '\n'.join(p.read_text(errors='replace') for p in (self.root / 'log').glob('*'))
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr + logs)
        self.assertEqual(len(self.backend.records), before + 1, result.stdout + result.stderr + logs)
        record = self.backend.records[-1]
        self.assertEqual(record[0]['operation'], 'print')
        self.assertTrue(record[1][0].startswith(b'%PDF-'), record)
        self.assertGreater(self.backend.fd_stats[-1][0].st_size, 0)
        self.assertFalse(self.backend.errors, self.backend.errors)
        return result

    def reject_input(self, document):
        before = len(self.backend.records)
        command = self.command(107)
        command[-1] = str(document)
        result = subprocess.run(command, env=self.env, capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertEqual(len(self.backend.records), before, 'invalid input must not launch Android printing')

    def test_empty_input_does_not_print_a_blank_document(self):
        empty = self.root / 'empty.ps'
        empty.write_bytes(b'')
        self.reject_input(empty)

    def test_unfiltered_pdf_is_rejected_not_misread_as_postscript(self):
        pdf = self.root / 'unfiltered.pdf'
        subprocess.run(['gs', '-q', '-dBATCH', '-dNOPAUSE', '-dSAFER', '-sDEVICE=pdfwrite', '-sOutputFile=' + str(pdf), str(self.document)], check=True, capture_output=True)
        self.reject_input(pdf)

    def test_completed_android_job_is_backend_success(self):
        self.run_job(101, 0, 0)

    def test_android_cancel_is_cups_cancel_not_success(self):
        self.run_job(102, 1, 5)

    def test_android_failure_is_cups_failure_not_success(self):
        self.run_job(103, 2, 1)

    def test_job_options_cannot_replace_android_hook(self):
        self.run_job(104, 0, 0, 'PostProcessing=/bin/false Label=0 Out=/tmp/invalid-output')

    def test_missing_or_dead_android_does_not_complete_job(self):
        self.backend.print_disconnect = True
        try:
            self.run_job(105, 0, 1)
        finally:
            self.backend.print_disconnect = False

    def test_wrapper_cancel_closes_android_request(self):
        self.backend.hold_print = True
        before = len(self.backend.records)
        closed = self.backend.closed
        process = subprocess.Popen(self.command(106, capture_status=True), env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            with self.backend.condition:
                self.assertTrue(self.backend.condition.wait_for(lambda: len(self.backend.records) > before, timeout=15))
            # CUPS signals its backend tracee, not the surrounding proot tracer.
            # PRoot exposes its loader as /proc/PID/exe; identify the fixture's
            # actual mapped ELF and pin that tracee with a pidfd before signalling.
            pending = [process.pid]
            wrapper_pidfd = None
            while pending:
                pid = pending.pop()
                fd = None
                try:
                    children = Path(f'/proc/{pid}/task/{pid}/children').read_text().split()
                    pending.extend(map(int, children))
                    fd = os.pidfd_open(pid)
                    if any(line.endswith(' ' + str(self.wrapper)) for line in Path(f'/proc/{pid}/maps').read_text().splitlines()):
                        wrapper_pidfd = fd
                        break
                except (FileNotFoundError, ProcessLookupError):
                    continue
                finally:
                    if fd is not None and fd != wrapper_pidfd:
                        os.close(fd)
            if wrapper_pidfd is None:
                self.fail('CUPS backend must be live before cancellation')
            try:
                signal.pidfd_send_signal(wrapper_pidfd, signal.SIGTERM)
            finally:
                os.close(wrapper_pidfd)
            stdout, stderr = process.communicate(timeout=8)
            self.assertIn('backend-exit=5', stdout.splitlines(), stdout + stderr)
            with self.backend.condition:
                self.assertTrue(self.backend.condition.wait_for(lambda: self.backend.closed > closed, timeout=3))
        finally:
            self.backend.hold_print = False
            if process.poll() is None:
                process.kill(); process.communicate()


if __name__ == '__main__':
    unittest.main(verbosity=2)
