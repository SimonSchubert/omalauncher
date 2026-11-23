package dev.omalauncher.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaVoiceTest {
    @Test
    fun aShortSentenceIsOnePart() {
        assertEquals(listOf("Got it."), SherpaVoice.parts("Got it.", first = true))
        assertEquals(listOf("Got it."), SherpaVoice.parts("  Got it.  ", first = false))
    }

    @Test
    fun aLongFirstSentenceIsCutAtItsFirstPause() {
        val text = "P root lets this phone run Linux without root, by intercepting the system calls programs make and rewriting paths on the fly."
        val parts = SherpaVoice.parts(text, first = true)
        assertEquals("P root lets this phone run Linux without root,", parts.first())
        assertEquals(text, parts.joinToString(" "))
        // Later in a reply it's read whole: the part before it is still playing.
        assertEquals(listOf(text), SherpaVoice.parts(text, first = false))
    }

    @Test
    fun noPauseNearTheStartLeavesTheFirstSentenceWhole() {
        // A comma within the first few words isn't worth a cut there; a long sentence without pauses stays one part.
        val text = "Yes, this is a sentence that goes on for quite a while without any pause in it at all until it ends."
        assertEquals(listOf(text), SherpaVoice.parts(text, first = true))
    }

    @Test
    fun runOnTextIsCutIntoPartsNoLongerThanTheMost() {
        val words = (1..300).joinToString(" ") { "word$it" }
        val parts = SherpaVoice.parts(words, first = false)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= SherpaVoice.MOST })
        // At spaces: no word is split.
        assertEquals(words, parts.joinToString(" "))
        // No spaces at all: hard cuts.
        val blob = "a".repeat(1000)
        assertEquals(5, SherpaVoice.parts(blob, first = false).size)
        assertEquals(blob, SherpaVoice.parts(blob, first = false).joinToString(""))
    }

    @Test
    fun nothingToSayIsNoParts() {
        for (text in listOf("", "   ", ".", "...", "👍🎉", "—")) assertEquals(text, emptyList<String>(), SherpaVoice.parts(text, first = true))
        assertEquals(listOf("Done 👍 all good."), SherpaVoice.parts("Done 👍 all good.", first = true))
    }

    @Test
    fun sentenceEnds() {
        assertTrue(SherpaVoice.ends("Done."))
        assertTrue(SherpaVoice.ends("Really? "))
        assertFalse(SherpaVoice.ends("Linux without root,"))
    }

    @Test
    fun trimKeepsTheSpeechAndAShortPause() {
        val rate = 1000
        val speech = FloatArray(500) { 0.5f }
        val samples = FloatArray(350) + speech + FloatArray(500)
        val sentence = SherpaVoice.trim(samples, rate, sentence = true)
        // 40 ms before, 300 ms after a full stop, 150 after a comma.
        assertEquals(40 + 500 + 300, sentence.size)
        assertArrayEquals(speech, sentence.copyOfRange(40, 540), 0f)
        assertEquals(40 + 500 + 150, SherpaVoice.trim(samples, rate, sentence = false).size)
        assertEquals(0, SherpaVoice.trim(FloatArray(1000), rate, sentence = true).size)
        // Speech to the very edges: nothing cut, nothing added.
        assertEquals(500, SherpaVoice.trim(speech, rate, sentence = true).size)
    }
}
