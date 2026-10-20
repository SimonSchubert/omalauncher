package dev.omalauncher.widgets.lang

import org.junit.Assert.*
import org.junit.Test

class LinuxAppTest {
    @Test fun requiresNewLanguageAndACommand() {
        assertTrue(error("face \"test\" { meta { owl: 12 }; LinuxApp { command: \"demo\" } }").contains("13"))
        assertTrue(error("face \"test\" { meta { owl: 13 }; LinuxApp {} }").contains("command"))
        assertTrue(error("face \"test\" { meta { owl: 13 }; LinuxApp { command: \"  \" } }").contains("blank"))
    }

    @Test fun reviewsTheRealCommandAndRequiresLinuxWithoutATerminal() {
        val p = Compiler.compile("""face "test" {
            meta { owl: 13 }
            LinuxApp { command: "my-viewer --wayland"; appId: "org.example.Viewer" }
        }""")
        assertTrue(p.needsLinux)
        assertFalse(p.terminal)
        assertEquals(listOf("my-viewer --wayland"), p.commands)
        assertEquals("LinuxApp", (p.root.single() as WNode.Element).kind)
    }

    @Test fun dynamicCommandsAreReviewedAsSuch() {
        val p = Compiler.compile("""face "test" {
            meta { owl: 13 }
            state command = "demo"
            LinuxApp { command: command }
        }""")
        assertTrue(p.commands.single().contains("LinuxApp command"))
    }

    @Test fun rejectsInvalidAppIdsAndEmulatorSpecificNodes() {
        assertTrue(error("face \"test\" { meta { owl: 13 }; LinuxApp { command: \"demo\"; appId: \"bad id\" } }").contains("appId"))
        assertTrue(error("face \"test\" { meta { owl: 13 }; Gba { rom: \"game.gba\" } }").contains("Gba"))
    }

    private fun error(source: String): String = try {
        Compiler.compile(source)
        fail("Expected a compile error")
        ""
    } catch (e: CompileError) {
        e.message.orEmpty()
    }
}
