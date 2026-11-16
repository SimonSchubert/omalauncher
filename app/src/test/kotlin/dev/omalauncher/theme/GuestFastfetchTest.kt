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

    @Test fun leavesTheOlderConfigAsItWas() {
        val old = "{\n  \"modules\": [\n    {\n      \"type\": \"uptime\",\n      \"key\": \"󱫐 Uptime\"\n    }\n  ]\n}"
        assertEquals(old, guestFastfetch(old))
    }
}
