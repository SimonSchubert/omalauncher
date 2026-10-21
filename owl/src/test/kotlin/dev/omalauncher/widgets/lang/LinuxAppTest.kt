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

    @Test fun controllerTargetsALocalInputIdWithoutAddingAShellCommand() {
        val p = Compiler.compile("""face "test" {
            meta { owl: 13 }
            LinuxApp { command: "demo"; inputId: "game" }
            LinuxKey { target: "game"; keys: "left"; text: "←" }
            LinuxKey { target: "game"; keys: ["leftctrl", "s"]; text: "Save" }
        }""")
        assertEquals(listOf("demo"), p.commands)
        assertEquals(listOf("LinuxApp", "LinuxKey", "LinuxKey"), p.root.map { (it as WNode.Element).kind })
    }

    @Test fun rejectsMissingOrInvalidControllerPropertiesAndAmbiguousGestureHandlers() {
        for (body in listOf(
            "LinuxKey { keys: \"z\" }",
            "LinuxKey { target: \"game\" }",
            "LinuxKey { target: \"\"; keys: \"z\" }",
            "LinuxApp { command: \"demo\"; inputId: \"bad id\" }",
            "LinuxKey { target: \"game\"; keys: \"not-a-key\" }",
            "LinuxKey { target: \"game\"; keys: [] }",
            "LinuxKey { target: \"game\"; keys: [\"z\", \"unknown\"] }",
            "LinuxKey { target: \"game\"; keys: 44 }",
            "LinuxKey { target: \"game\"; keys: \"z\"; onTap: haptic() }",
        )) assertTrue(body, error("face \"test\" { meta { owl: 13 }; $body }").isNotEmpty())
        assertTrue(error("face \"test\" { meta { owl: 12 }; LinuxKey { target: \"game\"; keys: \"z\" } }").contains("13"))
    }

    @Test fun keyNamesArePhysicalCodesAndInvalidDynamicValuesDisableTheWholeChord() {
        assertEquals(listOf(105, 44), LinuxKeys.codes(Lst(listOf(Str("LEFT"), Str("z"), Str("z")))))
        assertEquals(listOf(28), LinuxKeys.codes(Str("enter")))
        assertEquals(listOf(42, 44), LinuxKeys.codes(Lst(listOf(Str("leftshift"), Str("z")))))
        assertNull(LinuxKeys.codes(Lst(listOf(Str("z"), Str("invalid")))))
        assertNull(LinuxKeys.codes(Null))
        assertNull(LinuxKeys.codes(Lst(List(17) { Str("z") })))
        assertFalse(LinuxKeys.validTarget(Num(1.0)))
    }

    private fun error(source: String): String = try {
        Compiler.compile(source)
        fail("Expected a compile error")
        ""
    } catch (e: CompileError) {
        e.message.orEmpty()
    }
}
