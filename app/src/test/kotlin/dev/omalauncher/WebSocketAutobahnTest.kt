package dev.omalauncher

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException

/**
 * Runs [WebSocket] through the Autobahn TestSuite's client cases: it echoes every message back, and
 * the fuzzing server judges how it handled each case. Only with scripts/autobahn.sh, which starts the
 * server and sets AUTOBAHN to its address, then reads the report.
 */
class WebSocketAutobahnTest {
    @Test fun autobahn() {
        val server = System.getenv("AUTOBAHN")
        assumeTrue("AUTOBAHN isn't set", server != null)
        val cases = text("$server/getCaseCount").toInt()
        for (case in 1..cases) {
            try {
                // Its biggest messages are 16 MB.
                val ws = WebSocket.connect("$server/runCase?case=$case&agent=$AGENT", maxMessage = 64 shl 20)
                ws.listen(onText = { ws.send(it) }, onBinary = { ws.send(it) })
            } catch (_: IOException) {
                // Failing the connection is what many cases ask for; the report says whether it was right.
            }
        }
        val ws = WebSocket.connect("$server/updateReports?agent=$AGENT")
        ws.listen(onText = {})
    }

    private fun text(url: String): String {
        val ws = WebSocket.connect(url)
        var answer = ""
        ws.listen(onText = { answer = it })
        return answer
    }

    private companion object {
        const val AGENT = "omalauncher"
    }
}
