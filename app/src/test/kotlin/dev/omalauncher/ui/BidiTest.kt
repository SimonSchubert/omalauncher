package dev.omalauncher.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BidiTest {
    private val lri = "⁦"
    private val pdi = "⁩"

    @Test
    fun anArabicOrHebrewLetterMakesTextRightToLeft() {
        for (s in listOf("الطقس", "linux غير مثبت", "-7° يبدو كأنه", "שלום")) {
            assertTrue(s, hasRtl(s))
            assertEquals(s, TextDirection.Rtl, textDirectionOf(s))
        }
    }

    @Test
    fun numbersAndLatinStayLeftToRight() {
        for (s in listOf("-1°", "720x1560 @ 60Hz", "Omarchy's themes, in settings.", "٣٠")) {
            assertFalse(s, hasRtl(s))
            assertEquals(s, TextDirection.Ltr, textDirectionOf(s))
        }
    }

    @Test
    fun punctuationAloneFollowsTheLayout() {
        for (s in listOf("[", "]", " · ", "")) {
            assertFalse(s, hasRtl(s))
            assertEquals(s, TextDirection.Content, textDirectionOf(s))
            assertEquals(s, TextDirection.Ltr, textDirectionOf(s, neutral = TextDirection.Ltr))
        }
    }

    @Test
    fun startAndEndAreTheLayoutsEdges() {
        assertEquals(TextAlign.Left, layoutAlign(null, LayoutDirection.Ltr))
        assertEquals(TextAlign.Right, layoutAlign("end", LayoutDirection.Ltr))
        assertEquals(TextAlign.Right, layoutAlign("start", LayoutDirection.Rtl))
        assertEquals(TextAlign.Left, layoutAlign("end", LayoutDirection.Rtl))
        assertEquals(TextAlign.Center, layoutAlign("center", LayoutDirection.Rtl))
    }

    @Test
    fun signedNumbersInArabicAreIsolated() {
        assertEquals("يبدو كأنه $lri-7°$pdi · berlin", keepSigns(AnnotatedString("يبدو كأنه -7° · berlin")).text)
        assertEquals("الحرارة $lri+3.5%$pdi و $lri-2$pdi", keepSigns(AnnotatedString("الحرارة +3.5% و -2")).text)
    }

    @Test
    fun otherTextIsLeftAlone() {
        for (s in listOf("feels -7° · berlin", "الفترة 2-3", "الإصدار v1-2", "بلا أرقام")) {
            val text = AnnotatedString(s)
            assertSame(s, text, keepSigns(text))
        }
    }

    @Test
    fun spansMoveWithTheirText() {
        val styled =
            buildAnnotatedString {
                append("الحرارة ")
                withStyle(SpanStyle(fontSize = androidx.compose.ui.unit.TextUnit.Unspecified)) { append("-7°") }
                append(" الآن")
            }
        val kept = keepSigns(styled)
        val span = kept.spanStyles.single()
        assertEquals("$lri-7°", kept.text.substring(span.start, span.end))
    }
}
