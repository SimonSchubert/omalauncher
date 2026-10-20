#!/usr/bin/env python3
"""Host checks for the non-emulator demo; dummy SDL cannot prove phone Wayland integration."""
import ctypes.util
from pathlib import Path
import os
import subprocess
import sys
import unittest

root = Path(__file__).resolve().parent

class DemoTest(unittest.TestCase):
    def test_self_contained_face_matches_inspected_script(self):
        face = (root / 'linux-app.owl').read_text()
        script = face.split('let demo = """', 1)[1].split('"""', 1)[0]
        self.assertEqual((root / 'demo.py').read_text(), script)
        compile(script, 'embedded-demo', 'exec')

    @unittest.skipUnless(ctypes.util.find_library('SDL2-2.0'), 'SDL2 is not installed on this host')
    def test_software_rendering_input_and_exit(self):
        runner = r'''
import ctypes as c, runpy, struct, sys, threading, time
sdl = c.CDLL('libSDL2-2.0.so.0')
sdl.SDL_GetWindowFromID.restype = c.c_void_p
sdl.SDL_GetWindowFromID.argtypes = [c.c_uint32]
sdl.SDL_PushEvent.argtypes = [c.c_void_p]
def send_input():
    deadline = time.monotonic() + 5
    while not sdl.SDL_GetWindowFromID(1):
        if time.monotonic() > deadline:
            return
        time.sleep(0.01)
    for kind, key in ((0x300, 1073741904), (0x300, 122), (0x301, 1073741904), (0x301, 122), (0x100, 0)):
        event = (c.c_uint64 * 7)()
        c.memmove(c.byref(event), struct.pack('I', kind), 4)
        if kind in (0x300, 0x301):
            c.memmove(c.byref(event, 20), struct.pack('i', key), 4)
        assert sdl.SDL_PushEvent(c.byref(event)) == 1
threading.Thread(target=send_input, daemon=True).start()
result = runpy.run_path(sys.argv[1])
assert result['clicks'] == 2, result['clicks']
assert result['max_held'] == 2, result['max_held']
assert result['key_ups'] == 2, result['key_ups']
assert result['held'] == set(), result['held']
print('Software SDL rendering, simultaneous keys, key-up and clean exit: PASS')
'''
        result = subprocess.run([sys.executable, '-c', runner, str(root / 'demo.py')],
                                env={**os.environ, 'SDL_VIDEODRIVER': 'dummy'},
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        print(result.stdout.strip())

if __name__ == '__main__':
    unittest.main()
