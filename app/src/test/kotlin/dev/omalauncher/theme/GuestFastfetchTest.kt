package dev.omalauncher.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestFastfetchTest {
    // As in Omarchy 4.5's etc/fastfetch/config.jsonc.
    private val upstream =
        """
        {
          "logo": {
            "type": "file",
            "source": "~/.config/omarchy/branding/about.txt"
          },
          "modules": [
            {
              "type": "command",
              "key": "󱦟 OS Age",
              "keyColor": "magenta",
              "text": "days=$(( ($(date +%s) - $(stat -c %W /var/log)) / 86400 )); omarchy-i18n --count \"${'$'}days\" about.days \"${'$'}days\""
            },
            {
              "type": "command",
              "key": "󱫐 Uptime",
              "keyColor": "magenta",
              "text": "up=$(cut -d. -f1 /proc/uptime); d=$((up / 86400)); if [ \"${'$'}d\" -gt 0 ]; then omarchy-i18n agents.durationDaysHours \"${'$'}d\" \"${'$'}h\"; fi"
            }
          ]
        }
        """.trimIndent()

    @Test fun dropsOmarchyI18n() {
        val out = guestFastfetch(upstream)
        assertFalse(out, "omarchy-i18n" in out)
        assertFalse(out, "/proc/uptime" in out)
        assertTrue(out, "/ 86400 )); echo \\\"${'$'}days days\\\"\"" in out)
        assertTrue(out, "\"type\": \"uptime\",\n      \"key\": \"󱫐 Uptime\",\n      \"keyColor\": \"magenta\"\n    }" in out)
        assertTrue(out, "\"type\": \"file\",\n    \"position\": \"top\"," in out)
    }

    // The lines of Omarchy 4.5's config that run Omarchy's own commands, and the ones around them.
    private val software =
        """
        {
          "modules": [
            {
              "type": "command",
              "key": "\ue900 OS",
              "keyColor": "blue",
              "text": "version=$(omarchy-version) && echo \"Omarchy ${'$'}version\""
            },
            {
              "type": "command",
              "key": "│ ├󰘬",
              "keyColor": "blue",
              "text": "omarchy-version-branch"
            },
            {
              "type": "command",
              "key": "│ ├󰔫",
              "keyColor": "blue",
              "text": "channel=$(omarchy-version-channel); echo \"${'$'}channel\""
            },
            {
              "type": "kernel",
              "key": "│ ├"
            },
            {
              "type": "command",
              "key": "│ ├󰸌",
              "keyColor": "blue",
              "text": "theme=$(omarchy-theme-current); echo -e \"${'$'}theme \\e[38m●\""
            },
            {
              "type": "command",
              "key": "󱦟 OS Age",
              "keyColor": "magenta",
              "text": "days=$(( ($(date +%s) - $(stat -c %W /var/log)) / 86400 )); echo \"${'$'}days days\""
            },
            {
              "type": "command",
              "key": " Update",
              "keyColor": "magenta",
              "text": "updated=$(omarchy-version-pkgs); echo \"${'$'}updated\""
            },
            {
              "type": "custom",
              "format": "└┘"
            }
          ]
        }
        """.trimIndent()

    @Test fun runsNoOmarchyCommand() {
        val out = guestFastfetch(software)
        assertFalse(out, "omarchy-" in out)
        assertTrue(out, "{\n      \"type\": \"os\",\n      \"key\": \"\\ue900 OS\",\n      \"keyColor\": \"blue\"\n    },\n    {\n      \"type\": \"kernel\"," in out)
        assertTrue(out, "theme=$(cat ~/.local/state/omarchy/current/theme.name); echo -e" in out)
        // The last of Omarchy's lines goes without leaving a gap or a comma behind.
        assertTrue(out, "${'$'}days days\\\"\"\n    },\n    {\n      \"type\": \"custom\"," in out)
        org.json.JSONObject(out)
    }

    @Test fun countsTheDaysSincePacmansFirstLogLine() {
        val out = guestFastfetch(software)
        assertFalse(out, "%W" in out)
        assertTrue(out, "- $(date -d \\\"$(head -n1 /var/log/pacman.log | cut -c2-25)\\\" +%s)) / 86400 ))" in out)
    }

    @Test fun leavesTheOlderConfigAsItWas() {
        val old = "{\n  \"modules\": [\n    {\n      \"type\": \"uptime\",\n      \"key\": \"󱫐 Uptime\"\n    }\n  ]\n}"
        assertEquals(old, guestFastfetch(old))
    }
}
