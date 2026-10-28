#!/usr/bin/env python3
"""Real private CUPS queue + CUPS-PDF + Ghostscript; Android endpoint is simulated.

Requires PORTAL_TEST_PROOT, CUPS_PDF_TEST_SOURCE and PORTAL_TEST_CUPSD (isolated
CUPS 2.4 scheduler build with adjacent cups-exec), GTK3 Unix-print + Broadway,
Ghostscript, pdfinfo/pdftotext and CUPS clients/development headers. No host
installation/config. Actual GTK submission uses an isolated headless display.
Inherits direct backend regressions and exercises the production Kotlin
configuration through a Unix-only cupsd, real job-state readback and filters.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import textwrap
import time
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('cups_bridge', HERE / 'test-cups-pdf-bridge.py')
assert spec is not None and spec.loader is not None
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class PrivateCupsTests(bridge.CupsPdfTests):
    def test_private_queue_uses_production_configuration(self):
        cupsd = Path(os.environ['PORTAL_TEST_CUPSD']).resolve()
        shell = shutil.which('sh')
        broadway_binary = shutil.which('broadwayd')
        assert shell is not None and broadway_binary is not None, 'sh and broadwayd are required'
        self.assertTrue(cupsd.is_file())
        self.assertTrue(cupsd.with_name('cups-exec').is_file())
        config_source = (bridge.ROOT / 'app/src/main/kotlin/dev/omalauncher/linux/GuestPrintingConfig.kt').read_text()
        values = {}
        for key, value in re.findall(r'const val (\w+) = ("(?:\\.|[^"\\])*")', config_source):
            values[key] = json.loads(value).replace('${Proot.GUEST_RUNTIME_DIR}', '/run/user/0')
        def configuration(name):
            match = re.search(r'val ' + name + r' = """\n(.*?)\n\s*"""\.trimIndent', config_source, re.S)
            self.assertIsNotNone(match, name)
            text = textwrap.dedent(match.group(1)) + '\n'
            return re.sub(r'\$([A-Z][A-Z_]*)', lambda found: values[found.group(1)], text).replace("${'$'}", '$')

        server = self.root / 'server'
        server.mkdir()
        (server / 'cupsd.conf').write_text(configuration('daemon').replace('LogLevel warn', 'LogLevel debug2'))
        (server / 'cups-files.conf').write_text(configuration('files'))
        (server / 'printers.conf').write_text(configuration('printer'))
        host_dirs = {}
        values['CACHE'] = re.search(r'CacheDir (\S+)', configuration('files')).group(1)
        for key in ('STATE', 'SPOOL', 'CACHE', 'OUTPUT'):
            host_dirs[key] = self.output if key == 'OUTPUT' else self.root / key.lower()
            host_dirs[key].mkdir(exist_ok=True)
        ppds = host_dirs['STATE'] / 'ppd'
        ppds.mkdir()
        for directory in (host_dirs['SPOOL'] / 'tmp', self.output / 'output', self.output / 'tmp', self.output / 'log'):
            directory.mkdir(exist_ok=True)
        (host_dirs['STATE'] / 'printers.conf').write_text(configuration('printer'))
        packaged_ppd = (Path(self.source).parent.parent / 'extra/CUPS-PDF_noopt.ppd').read_text()
        patched_ppd = '\n'.join(line for line in packaged_ppd.splitlines() if not line.lstrip().startswith(('*cupsFilter:', '*cupsFilter2:', '*cupsManualCopies:')))
        (ppds / 'Android.ppd').write_text(patched_ppd.rstrip() + '\n*cupsManualCopies: True\n' + values['PDF_FILTER'] + '\n')
        original_pdf_config = self.config.read_bytes()
        self.config.write_text(configuration('pdf'))
        self.wrapper.chmod(0o700)  # Same owner-only mode as managed guest backend.
        pdf_filter = self.root / 'pdf-filter'
        pdf_filter.write_text(configuration('filter'))
        pdf_filter.chmod(0o755)
        bindings = {
            str(server): '/usr/local/etc/omalauncher/printing',
            str(self.runtime): '/run/user/0',
            str(pdf_filter): values['FILTER'],
            str(self.config): values['PDF_CONFIG'],
            str(self.pdf_backend): '/usr/lib/cups/backend/cups-pdf',
            str(self.wrapper): '/usr/lib/cups/backend/omalauncher-pdf',
            str(self.hook): values['FORWARDER'],
            str(cupsd.with_name('cups-exec')): '/usr/lib/cups/daemon/cups-exec',
        }
        bindings.update({str(host_dirs[key]): values[key] for key in host_dirs})
        command = [self.proot, '--kill-on-exit', '-0']
        for host, guest in bindings.items():
            command.extend(['-b', host + ':' + guest])
        # The shell records the daemon's real PID. --kill-on-exit prevents orphan
        # tracees; signal only our captured PID, never an arbitrary system cupsd.
        pidfile = self.root / 'cupsd.pid'
        command.extend([shell, '-c', 'echo $$ > "$1"; shift; exec "$@"', 'cups-fixture', str(pidfile), str(cupsd), '-f', '-c', '/usr/local/etc/omalauncher/printing/cupsd.conf', '-s', '/usr/local/etc/omalauncher/printing/cups-files.conf'])
        log_path = self.root / 'cupsd-test.log'
        client_env = {**os.environ, 'CUPS_SERVER': str(self.runtime / 'omalauncher-cups.sock'), 'CUPS_USER': 'root'}
        log = log_path.open('w')
        daemon = subprocess.Popen(command, env=self.env, stdout=log, stderr=subprocess.STDOUT)
        broadway = None
        try:
            deadline = time.monotonic() + 10
            while True:
                ready = subprocess.run(['lpstat', '-p', 'Android'], env=client_env, capture_output=True, text=True)
                if ready.returncode == 0:
                    break
                if daemon.poll() is not None or time.monotonic() >= deadline:
                    self.fail('private CUPS not ready: ' + ready.stderr + '\n' + log_path.read_text())
                time.sleep(.05)
            job_client = self.root / 'cups-job-states'
            subprocess.run(['cc', str(HERE / 'portal-cups-test-client.c'), '-lcups', '-o', str(job_client)], check=True, capture_output=True)
            def states():
                query = subprocess.run([str(job_client)], env=client_env, capture_output=True, text=True, check=True, timeout=5)
                return {int(job): state for job, state in (line.split() for line in query.stdout.splitlines())}

            def finish(submit, expected_texts, expected_state='completed'):
                before_files = set(self.output.rglob('*.pdf'))
                before_records = len(self.backend.records)
                existing = set(states())
                submit()
                deadline = time.monotonic() + 15
                with self.backend.condition:
                    self.assertTrue(self.backend.condition.wait_for(lambda: len(self.backend.records) > before_records, timeout=15), log_path.read_text())
                while True:
                    jobs = states()
                    new_jobs = set(jobs) - existing
                    if len(new_jobs) == 1 and jobs[next(iter(new_jobs))] == expected_state:
                        break
                    if time.monotonic() >= deadline:
                        self.fail('CUPS job states: ' + repr(jobs) + '\n' + log_path.read_text())
                    time.sleep(.05)
                created = set(self.output.rglob('*.pdf')) - before_files
                self.assertEqual(len(created), 1, log_path.read_text())
                completed_pdf = created.pop()
                info = subprocess.run(['pdfinfo', str(completed_pdf)], capture_output=True, text=True, check=True)
                self.assertRegex(info.stdout, r'(?m)^Pages:\s+' + str(len(expected_texts)) + r'\s*$', log_path.read_text())
                text = subprocess.run(['pdftotext', str(completed_pdf), '-'], capture_output=True, text=True, check=True).stdout
                self.assertEqual([page.strip() for page in text.split('\f') if page.strip()], expected_texts)
                self.assertFalse(list(host_dirs['SPOOL'].rglob('omalauncher-print.*')), 'filter staging must be cleaned')
                record, fds = self.backend.records[-1]
                self.assertEqual(record['operation'], 'print')
                self.assertEqual(record['fd-count'], '1')
                self.assertTrue(fds[0].startswith(b'%PDF-'))

            def lp(document, copies, *options):
                result = subprocess.run(['lp', '-d', 'Android', '-n', str(copies), *options, str(document)], env=client_env, capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr + '\n' + log_path.read_text())

            rendered = self.root / 'rendered.pdf'
            subprocess.run(['gs', '-q', '-dBATCH', '-dNOPAUSE', '-dSAFER', '-sDEVICE=pdfwrite', '-sOutputFile=' + str(rendered), str(self.document)], check=True, capture_output=True)
            original_text = subprocess.run(['pdftotext', str(rendered), '-'], capture_output=True, text=True, check=True).stdout.strip()
            self.assertTrue(original_text, 'fixture content must be nonempty')
            finish(lambda: lp(rendered, 3, '-o', 'media=A4'), [original_text] * 3)

            multipage_ps = self.root / 'pages.ps'
            multipage_ps.write_text('%!PS-Adobe-3.0\n%%Pages: 3\n%%EndComments\n/Helvetica findfont 18 scalefont setfont\n' + ''.join(f'%%Page: {number} {number}\n72 720 moveto (Selected page {number}) show\nshowpage\n' for number in range(1, 4)) + '%%Trailer\n%%EOF\n')
            multipage = self.root / 'pages.pdf'
            subprocess.run(['gs', '-q', '-dBATCH', '-dNOPAUSE', '-dSAFER', '-sDEVICE=pdfwrite', '-sOutputFile=' + str(multipage), str(multipage_ps)], check=True, capture_output=True)
            finish(lambda: lp(multipage, 2, '-o', 'page-ranges=2'), ['Selected page 2'] * 2)

            # Actual production GTK helper, not the protocol test-double. A
            # private Broadway display needs no desktop/session configuration.
            gtk = self.root / 'gtk-print'
            flags = subprocess.check_output(['pkg-config', '--cflags', '--libs', 'gtk+-unix-print-3.0'], text=True).split()
            subprocess.run(['cc', '-O1', str(bridge.ROOT / 'guest/omalauncher-portal/portal-print-dialog.c'), *flags, '-o', str(gtk)], check=True, capture_output=True)
            config_home = self.root / 'gtk-config'
            config_home.mkdir()
            gtk_env = {**client_env, 'GDK_BACKEND': 'broadway', 'BROADWAY_DISPLAY': ':5', 'XDG_RUNTIME_DIR': str(self.runtime), 'XDG_CONFIG_HOME': str(config_home), 'GSETTINGS_BACKEND': 'memory', 'NO_AT_BRIDGE': '1', 'GTK_USE_PORTAL': '1', 'DBUS_SESSION_BUS_ADDRESS': 'unix:path=' + str(self.root / 'no-session-bus')}
            broadway_log = (self.root / 'broadway.log').open('w')
            self.addCleanup(broadway_log.close)
            broadway = subprocess.Popen([broadway_binary, '--unixsocket', str(self.root / 'broadway-http.sock'), ':5'], env=gtk_env, stdout=broadway_log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 5
            # Linux Broadway uses an abstract Unix socket, so no filesystem
            # entry appears. Its flushed listener log is the readiness signal.
            while 'Listening on ' not in (self.root / 'broadway.log').read_text():
                if broadway.poll() is not None or time.monotonic() > deadline:
                    self.fail('Broadway not ready: ' + (self.root / 'broadway.log').read_text())
                time.sleep(.02)
            def gtk_submit():
                result = subprocess.run([shell, '-c', 'exec 3<"$1"; shift; exec "$@"', 'gtk-fixture', str(gtk_rendered), str(gtk), '--print', 'GTK native fixture'], env=gtk_env, input="({'printer': <'Android'>, 'n-copies': <'2'>, 'print-pages': <'ranges'>, 'page-ranges': <'1'>}, @a{sv} {}, ['pdf'], false)", capture_output=True, text=True, timeout=15)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertRegex(result.stdout, r'^\((?:uint32 )?0,', result.stdout + result.stderr)
            # PreparePrint returns formatting instructions, not an instruction
            # to apply copies twice. Use an actual GTK PrintOperation (its file
            # backend is also used by GTK's portal caller) to format those
            # copies/ranges, then submit with the actual production helper.
            renderer = self.root / 'gtk-render'
            subprocess.run(['cc', '-O1', str(HERE / 'portal-gtk-render-client.c'), *flags, '-o', str(renderer)], check=True, capture_output=True)
            gtk_rendered = self.root / 'gtk-rendered.pdf'
            rendering = subprocess.run([str(renderer), str(gtk_rendered)], env={**gtk_env, 'GTK_USE_PORTAL': '0', 'LC_ALL': 'C'}, capture_output=True, text=True, timeout=15)
            self.assertEqual(rendering.returncode, 0, rendering.stdout + rendering.stderr)
            rendered_text = subprocess.run(['pdftotext', str(gtk_rendered), '-'], check=True, capture_output=True, text=True).stdout
            self.assertEqual([page.strip() for page in rendered_text.split('\f') if page.strip()], ['GTK selected page 2'] * 2)
            finish(gtk_submit, ['GTK selected page 2'] * 2)

            # Truthful terminal states and a failed job must not disable the
            # whole virtual printer. Failed handoffs retain their completed PDF.
            self.backend.print_status = 2
            finish(lambda: lp(rendered, 1), [original_text], 'aborted')
            self.backend.print_status = 1
            finish(lambda: lp(rendered, 1), [original_text], 'canceled')
            self.backend.print_status = 0
            finish(lambda: lp(rendered, 1), [original_text])
            too_many = subprocess.run(['lp', '-d', 'Android', '-n', '1000', str(rendered)], env=client_env, capture_output=True, text=True, timeout=5)
            self.assertNotEqual(too_many.returncode, 0, 'queue must advertise and enforce its 999-copy bound')
        finally:
            if broadway is not None and broadway.poll() is None:
                broadway.terminate()
                broadway.wait(timeout=5)
            if daemon.poll() is None:
                self.assertTrue(pidfile.is_file(), log_path.read_text())
                pid = int(pidfile.read_text())
                pinned = os.pidfd_open(pid)
                try:
                    signal.pidfd_send_signal(pinned, signal.SIGTERM)
                    daemon.wait(timeout=8)
                finally:
                    os.close(pinned)
            log.close()
            self.config.write_bytes(original_pdf_config)


if __name__ == '__main__':
    unittest.main(verbosity=2)
