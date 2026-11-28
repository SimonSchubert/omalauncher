package dev.omalauncher.linux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtraceRefusedTest {
    @Test
    fun prootThatMayNotTraceItsChildIsRecognized() {
        val output =
            """
            proot error: ptrace(TRACEME): Operation not permitted
            proot error: execve("/usr/bin/env"): Operation not permitted
            proot info: It seems your kernel contains this bug: https://bugs.launchpad.net/ubuntu/+source/linux/+bug/1202161
            To workaround it, set the env. variable PROOT_NO_SECCOMP to 1.
            fatal error: see `libproot.so --help`.
            proot error: can't chmod '/data/data/dev.omalauncher/files/tmp/proot-21804-x7Qp1c': No such file or directory
            """.trimIndent()
        assertTrue(ptraceRefused(output))
    }

    @Test
    fun aRefusalByAnotherErrnoIsRecognized() {
        assertTrue(ptraceRefused("proot error: ptrace(TRACEME): Permission denied\n"))
    }

    @Test
    fun otherFailuresAreNotARefusal() {
        assertFalse(ptraceRefused(""))
        assertFalse(ptraceRefused("forks\n"))
        assertFalse(ptraceRefused("proot error: execve(\"/usr/bin/env\"): No such file or directory\nfatal error: see `libproot.so --help`.\n"))
        assertFalse(ptraceRefused("sh: ptrace(TRACEME): command not found\n"))
    }
}
