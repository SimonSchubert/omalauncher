package dev.omalauncher.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YesNoTest {
    private fun yes(s: String) = VoiceSession.YES.containsMatchIn(s)

    private fun no(s: String) = VoiceSession.NO.matches(s)

    private fun always(s: String) = VoiceSession.ALWAYS.containsMatchIn(s)

    @Test
    fun yesInEachLanguage() {
        for (s in listOf("Yes.", "okay go ahead", "ja, klar", "sí", "Sim", "ya boleh", "iya", "是的", "好", "可以。", "嗯，行", "Да, давай", "так", "Так.", "добре", "はい", "うん、いいよ")) assertTrue(s, yes(s))
    }

    @Test
    fun notYes() {
        for (s in listOf("yesterday's file instead", "goal is different", "simpan ke folder lain", "不好", "不是这个", "别删", "nope", "Так, сделай по-другому", "такой не нужен")) assertFalse(s, yes(s))
    }

    @Test
    fun noInEachLanguage() {
        for (s in listOf("No.", "nein", "não", "nao!", "tidak", "jangan", "不", "不要", "别。", "算了", "Нет.", "не надо", "ні", "いいえ", "だめ")) assertTrue(s, no(s))
    }

    @Test
    fun anythingMoreIsWhatToDoInstead() {
        for (s in listOf("no, use the other folder", "不要删除，改一下名字", "tidak, pakai yang lain", "nein danke, mach es anders")) assertFalse(s, no(s))
    }

    @Test
    fun alwaysAlone() {
        for (s in listOf("always", "Always.", "yes, always", "Ja, immer.", "always allow", "allow always", "siempre", "всегда", "Так, завжди", "总是允许", "いつも")) {
            assertTrue(s, always(s))
        }
    }

    @Test
    fun alwaysInASentenceIsWhatToDoInstead() {
        for (s in listOf("always ask me first", "no, not always", "immer im anderen ordner", "yes")) assertFalse(s, always(s))
    }
}
