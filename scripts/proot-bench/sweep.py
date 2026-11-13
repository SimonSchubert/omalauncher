# sweep.py <out>: every way of asking about every path below the roots, one line per answer
import os, sys, errno, ctypes
libc = ctypes.CDLL(None, use_errno=True)
libc.realpath.restype = ctypes.c_void_p; libc.realpath.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
libc.llistxattr.restype = ctypes.c_ssize_t; libc.listxattr.restype = ctypes.c_ssize_t
SKIP = {"/root/.fp-cmd", "/root/.fp-out", "/root/fp-test/.out", "/root/fp-test/prof.log", "/root/fp-test/st.txt", "/root/fp-test/sweep.log"}
out = open(sys.argv[1], "w")
roots = sys.argv[2:] or ["/usr", "/etc", "/var", "/opt", "/root", "/srv", "/home", "/bin", "/lib", "/sbin"]
def st(f, *a, **k):
    try:
        s = f(*a, **k)
        return "%o %d %d %d %d %d %d %d" % (s.st_mode, s.st_uid, s.st_gid, s.st_size, s.st_nlink, s.st_mtime_ns, s.st_ino, s.st_dev)
    except OSError as e: return "E%d" % e.errno
def rl(p, **k):
    try: return os.readlink(p, **k)
    except OSError as e: return "E%d" % e.errno
def rp(p):
    buf = ctypes.create_string_buffer(4200)
    r = libc.realpath(os.fsencode(p), buf)
    return buf.value.decode("utf8", "replace") if r else "E%d" % ctypes.get_errno()
def xa(f, p):
    buf = ctypes.create_string_buffer(1024)
    r = f(os.fsencode(p), buf, 1024)
    return "%d:%s" % (r, buf.raw[:max(r, 0)].hex()) if r >= 0 else "E%d" % ctypes.get_errno()
def op(p):
    try:
        fd = os.open(p, os.O_RDONLY | os.O_NONBLOCK | os.O_NOCTTY)
    except OSError as e: return "E%d" % e.errno
    try:
        s = os.fstat(fd); return "%o %d %d %d" % (s.st_mode, s.st_ino, s.st_uid, s.st_nlink)
    finally: os.close(fd)
n = 0
def ask(p, d, name, dfd):
    global n
    if p in SKIP or "/fp-test/sweep" in p: return
    n += 1
    a = [st(os.lstat, p), st(os.stat, p), rl(p), "%d%d%d%d" % (os.access(p, os.R_OK), os.access(p, os.W_OK), os.access(p, os.X_OK), os.access(p, os.F_OK)), rp(p), xa(libc.llistxattr, p)]
    m = os.lstat(p).st_mode if not a[0].startswith("E") else 0
    if (m & 0o170000) in (0o100000, 0o040000): a.append(op(p))
    # relative to the working directory, through "..", and by directory descriptor
    a += [st(os.lstat, name), st(os.stat, "./" + name), st(os.lstat, "../" + os.path.basename(d) + "/" + name) if d != "/" else "-",
          st(os.stat, name, dir_fd=dfd), st(os.stat, name, dir_fd=dfd, follow_symlinks=False), rl(name, dir_fd=dfd), rp(name), rp(p + "/."), st(os.stat, p + "/"), st(os.lstat, p + "/..")]
    out.write(p + "\t" + "\t".join(a) + "\n")
for root in roots:
    for d, dirs, files in os.walk(root):
        if d.startswith("/root/fp-test/sweep"): continue
        try:
            os.chdir(d); dfd = os.open(d, os.O_RDONLY | os.O_DIRECTORY)
        except OSError: continue
        out.write("cwd %s %s\n" % (d, os.getcwd()))
        for name in sorted(dirs + files): ask(os.path.join(d, name), d, name, dfd)
        ask(d + "/nonexistent-fp", d, "nonexistent-fp", dfd)
        os.close(dfd)
print("asked", n)
