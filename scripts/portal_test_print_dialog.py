#!/usr/bin/env python3
"""Deterministic printer-selection test double; NOT printer/device validation.
The real GTK helper supplies its serialized settings, but no GUI is opened.
"""
import os
import signal
import subprocess
import sys

payload = sys.stdin.read()
if len(sys.argv) != 3:
    raise SystemExit(2)
mode, title = sys.argv[1:]
if title == 'hold':
    signal.pause()
if title == 'cancel':
    print('(uint32 1, @a{sv} {}, @a{sv} {})')
    raise SystemExit(0)
if mode == '--print':
    assert 'Android' in payload
    assert os.pread(3, 4, 0) == b'%!PS'
print(subprocess.check_output([os.environ['PORTAL_TEST_GTK_SERIALIZER'], '--self-test'], text=True).strip())
