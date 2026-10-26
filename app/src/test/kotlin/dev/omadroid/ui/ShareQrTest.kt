package dev.omadroid.ui

import com.google.zxing.WriterException
import dev.omadroid.widgets.market.Share
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShareQrTest {
    // A link's characters, as Share.link writes them: the prefix, then base64url.
    private fun link(length: Int) = (Share.LINK + Share.PREFIX).let { it + "A".repeat(length - it.length) }

    @Test fun theLongestLinkThatFitsMakesACode() {
        // Version 40 is 177 modules, plus the 2-module margin on each side.
        assertEquals(181, qrCode(link(Share.MAX_LINK)).width)
    }

    @Test fun oneCharacterMoreDoesnt() {
        assertThrows(WriterException::class.java) { qrCode(link(Share.MAX_LINK + 1)) }
    }

    @Test fun aPastedLinkMakesASmallCode() {
        assert(qrCode(Share.pasteLink("XXXXXXXXX")).width < 50)
    }
}
