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
        for (s in listOf("Yes.", "okay go ahead", "ja, klar", "sí", "Sim", "ya boleh", "iya", "是的", "好", "可以。", "嗯，行", "Да, давай", "так", "Так.", "добре", "はい", "うん、いいよ", "Oui.", "ouais vas-y", "d'accord", "D’accord.", "Evet.", "tamam, devam et", "olur", "izin ver", "Sì.", "sì, procedi", "va bene", "d’accordo, fallo", "certo", "Có.", "được", "Vâng, làm đi", "ừ", "Đồng ý.", "cho phép", "نعم", "نعم.", "حسنًا، تابع", "موافق", "طيب", "اسمح")) assertTrue(s, yes(s))
    }

    @Test
    fun notYes() {
        for (s in listOf("yesterday's file instead", "goal is different", "simpan ke folder lain", "不好", "不是这个", "别删", "nope", "Так, сделай по-другому", "такой не нужен", "ouvre plutôt l’autre dossier", "tamamen başka bir şey yap", "yarın tekrar sor", "sistema l’altro file", "certificato sbagliato", "có thể dùng thư mục khác không", "được không, hay thử cách khác", "طيب، استخدم المجلد الآخر", "نعمة", "لاحقًا")) assertFalse(s, yes(s))
    }

    @Test
    fun noInEachLanguage() {
        for (s in listOf("No.", "nein", "não", "nao!", "tidak", "jangan", "不", "不要", "别。", "算了", "Нет.", "не надо", "ні", "いいえ", "だめ", "Non.", "non merci", "laisse tomber", "Hayır.", "iptal", "İptal.", "boş ver", "gerek yok", "No grazie.", "annulla", "lascia perdere", "Không.", "không cần", "thôi", "Hủy", "đừng", "لا", "لا.", "لا شكرًا", "إلغاء", "لا تفعل")) assertTrue(s, no(s))
    }

    @Test
    fun anythingMoreIsWhatToDoInstead() {
        for (s in listOf("no, use the other folder", "不要删除，改一下名字", "tidak, pakai yang lain", "nein danke, mach es anders", "non, utilise l’autre dossier", "hayır, diğer klasörü kullan", "no, usa l’altra cartella", "không, dùng thư mục khác", "لا، استخدم المجلد الآخر")) assertFalse(s, no(s))
    }

    @Test
    fun alwaysAlone() {
        for (s in listOf("always", "Always.", "yes, always", "Ja, immer.", "always allow", "allow always", "siempre", "всегда", "Так, завжди", "总是允许", "いつも", "oui, toujours", "toujours autoriser", "evet, her zaman", "Her zaman izin ver.", "daima", "sì, sempre", "Sempre.", "consenti sempre", "luôn luôn", "Có, luôn cho phép.", "cho phép luôn", "دائمًا", "دائما", "نعم، دائمًا", "اسمح دائمًا", "دائمًا اسمح")) {
            assertTrue(s, always(s))
        }
    }

    @Test
    fun alwaysInASentenceIsWhatToDoInstead() {
        for (s in listOf("always ask me first", "no, not always", "immer im anderen ordner", "toujours me demander", "her zaman önce bana sor", "sempre nella cartella home", "luôn hỏi tôi trước", "دائمًا اسألني أولًا", "yes")) assertFalse(s, always(s))
    }
}
