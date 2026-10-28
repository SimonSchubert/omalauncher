#!/usr/bin/env python3
"""Exercise the production FD-rights JNI function on host Linux, not Android."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PortalFdTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='portal-fd-')
        cls.root = Path(cls.temp.name)
        javac = shutil.which('javac')
        if not os.environ.get('JAVA_HOME') and not javac:
            raise RuntimeError('A JDK is required (JAVA_HOME or javac on PATH)')
        java = Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else Path(str(javac)).resolve().parent.parent
        cls.java = java / 'bin/java'
        library = cls.root / 'libportal-fd-fixture.so'
        subprocess.run(['cc', '-shared', '-fPIC', '-Wall', '-Wextra', '-Wno-unused-parameter', '-I' + str(java / 'include'), '-I' + str(java / 'include/linux'), str(ROOT / 'app/src/main/cpp/portal-fd.c'), '-o', str(library)], check=True)
        source = cls.root / 'PortalFd.java'
        source.write_text('''package dev.omalauncher.linux;
import java.io.IOException;
public class PortalFd {
    public native int flags(int fd) throws IOException;
    public static void main(String[] args) {
        System.load(args[0]);
        try { System.out.println(new PortalFd().flags(Integer.parseInt(args[1]))); }
        catch (IOException expected) { System.out.println("IOException"); System.exit(3); }
    }
}
''')
        subprocess.run([str(java / 'bin/javac'), '-d', str(cls.root), str(source)], check=True)
        cls.command = [str(cls.java), '-cp', str(cls.root), 'dev.omalauncher.linux.PortalFd', str(library)]
        cls.document = cls.root / 'document'
        cls.document.write_bytes(b'rights fixture')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def flags(self, access):
        fd = os.open(self.document, access | os.O_CLOEXEC)
        try:
            result = subprocess.run([*self.command, '0'], stdin=fd, capture_output=True, text=True, check=True)
            return int(result.stdout)
        finally:
            os.close(fd)

    def test_read_only(self):
        self.assertEqual(self.flags(os.O_RDONLY) & os.O_ACCMODE, os.O_RDONLY)

    def test_write_only(self):
        self.assertEqual(self.flags(os.O_WRONLY) & os.O_ACCMODE, os.O_WRONLY)
        self.assertEqual(self.document.read_bytes(), b'rights fixture')

    def test_read_write(self):
        self.assertEqual(self.flags(os.O_RDWR) & os.O_ACCMODE, os.O_RDWR)

    def test_path_only_is_not_mistaken_for_read_only(self):
        self.assertTrue(self.flags(os.O_PATH) & os.O_PATH)

    def test_invalid_descriptor_raises_ioexception(self):
        result = subprocess.run([*self.command, '-1'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 3)
        self.assertEqual(result.stdout.strip(), 'IOException')


if __name__ == '__main__':
    unittest.main(verbosity=2)
