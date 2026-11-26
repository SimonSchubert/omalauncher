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
        for (s in listOf("Yes.", "okay go ahead", "ja, klar", "sí", "Sim", "ya boleh", "iya", "是的", "好", "可以。", "嗯，行", "Да, давай", "так", "Так.", "добре", "はい", "うん、いいよ", "Oui.", "ouais vas-y", "d'accord", "D’accord.", "Evet.", "tamam, devam et", "olur", "izin ver", "Sì.", "sì, procedi", "va bene", "d’accordo, fallo", "certo", "Có.", "được", "Vâng, làm đi", "ừ", "Đồng ý.", "cho phép", "نعم", "نعم.", "أيوه", "ايوة", "موافق", "تمام، تفضل", "حسنا", "حسنًا", "طيب", "أكيد", "بالتاكيد", "طبعاً", "اسمح", "أجل", "اوكي", "افعل ذلك", "لا بأس", "استمر", "بله", "بله.", "آره", "اره", "باشه", "حتما", "حتماً", "اوکی", "اوكي", "قبوله", "موافقم", "موافق", "تایید می‌کنم", "تأیید ميكنم", "اجازه", "اجازه بده", "اجازه می دم", "انجامش بده", "ادامه بده", "برو", "بله، انجام بده", "باشه، اجرا کن", "اشکالی نداره", "مشکلی نیست", "تمام")) assertTrue(s, yes(s))
    }

    @Test
    fun notYes() {
        for (s in listOf("yesterday's file instead", "goal is different", "simpan ke folder lain", "不好", "不是这个", "别删", "nope", "Так, сделай по-другому", "такой не нужен", "ouvre plutôt l’autre dossier", "tamamen başka bir şey yap", "yarın tekrar sor", "sistema l’altro file", "certificato sbagliato", "có thể dùng thư mục khác không", "được không, hay thử cách khác", "نعمل على الملف الآخر", "أجل المهمة إلى الغد", "ممكن تستخدم مجلدا آخر", "تابع الملف الآخر", "افعل شيئا آخر", "تماما كما في الملف السابق", "أي ملف تقصد", "لا", "تمام فایل‌ها را پاک کن", "تمام پوشه را نشانم بده", "موافق نیستم", "اجازه نده", "قبول نمی‌کنم", "تایید نکن", "انجام نده", "ادامه نده", "البته که نه", "برو به پوشه دیگر", "درست نیست", "درست کن آن فایل را", "خوب نیست", "نه")) assertFalse(s, yes(s))
    }

    @Test
    fun noInEachLanguage() {
        for (s in listOf("No.", "nein", "não", "nao!", "tidak", "jangan", "不", "不要", "别。", "算了", "Нет.", "не надо", "ні", "いいえ", "だめ", "Non.", "non merci", "laisse tomber", "Hayır.", "iptal", "İptal.", "boş ver", "gerek yok", "No grazie.", "annulla", "lascia perdere", "Không.", "không cần", "thôi", "Hủy", "đừng", "لا", "لا.", "لأ", "كلا", "لا، شكرا", "لا شكرًا", "إلغاء", "الغاء", "ألغ", "الغي", "توقف", "لا تفعل", "لا أريد", "لا داعي", "انس الأمر", "انسى الامر", "نه", "نه.", "نه، ممنون", "نه ممنون", "نخیر", "لغو", "لغو کن", "کنسل", "كنسل كن", "متوقف کن", "توقف", "بسه", "نکن", "انجام نده", "اجازه نده", "نمی‌خوام", "نمی خوام", "نمیخوام", "نمي‌خواهم", "موافق نیستم", "رد کن", "بی‌خیال", "بیخیال", "بی خیال شو", "ولش کن", "مهم نیست", "ایست", "بایست", "هرگز", "اصلا")) assertTrue(s, no(s))
    }

    @Test
    fun anythingMoreIsWhatToDoInstead() {
        for (s in listOf("no, use the other folder", "不要删除，改一下名字", "tidak, pakai yang lain", "nein danke, mach es anders", "non, utilise l’autre dossier", "hayır, diğer klasörü kullan", "no, usa l’altra cartella", "không, dùng thư mục khác", "لا، استخدم المجلد الآخر", "لا تفعل ذلك في هذا المجلد", "إلغاء التثبيت فقط", "لا بأس", "نه، از پوشه دیگر استفاده کن", "لغو نصب فقط", "نکن این کار را در این پوشه", "نه همیشه", "اشکالی نداره")) assertFalse(s, no(s))
    }

    @Test
    fun alwaysAlone() {
        for (s in listOf("always", "Always.", "yes, always", "Ja, immer.", "always allow", "allow always", "siempre", "всегда", "Так, завжди", "总是允许", "いつも", "oui, toujours", "toujours autoriser", "evet, her zaman", "Her zaman izin ver.", "daima", "sì, sempre", "Sempre.", "consenti sempre", "luôn luôn", "Có, luôn cho phép.", "cho phép luôn", "دائما", "دائماً.", "دايما", "نعم، دائما", "اسمح دائما", "السماح دائمًا", "دائما اسمح", "موافق، اسمح به دائما", "في كل مرة", "همیشه", "همیشه.", "هميشه", "بله، همیشه", "آره همیشه", "همیشه اجازه بده", "اجازه بده همیشه", "همیشه مجاز", "برای همیشه", "باشه، همیشه اجازه می‌دم", "هر بار", "دایما")) {
            assertTrue(s, always(s))
        }
    }

    @Test
    fun alwaysInASentenceIsWhatToDoInstead() {
        for (s in listOf("always ask me first", "no, not always", "immer im anderen ordner", "toujours me demander", "her zaman önce bana sor", "sempre nella cartella home", "luôn hỏi tôi trước", "دائما اسألني أولا", "لا، ليس دائما", "دائما في المجلد الآخر", "همیشه اول از من بپرس", "نه، نه همیشه", "همیشه در پوشه دیگر", "بله", "نعم", "yes")) assertFalse(s, always(s))
    }
}
