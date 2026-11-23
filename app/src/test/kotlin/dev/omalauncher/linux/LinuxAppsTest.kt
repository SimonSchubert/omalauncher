package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

class LinuxAppsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entry(vararg lines: String) =
        tmp.newFile("files.desktop").apply {
            writeText((listOf("[Desktop Entry]", "Type=Application", "Exec=omarchy-files") + lines).joinToString("\n"))
        }

    @Test
    fun nameFollowsTheLanguageAndKeepsTheUntranslatedOneForSearch() {
        val file = entry("Name=Files", "Name[ar]=الملفات", "Name[pt_BR]=Arquivos", "Name[pt]=Ficheiros")
        val ar = parseDesktopEntry(file, Locale.forLanguageTag("ar-JO-u-nu-latn"))!!
        assertEquals("الملفات", ar.name)
        assertEquals("Files", ar.alias)
        // A country's own name before the language's, as XDG picks.
        assertEquals("Arquivos", parseDesktopEntry(file, Locale.forLanguageTag("pt-BR"))!!.name)
        assertEquals("Ficheiros", parseDesktopEntry(file, Locale.forLanguageTag("pt-PT"))!!.name)
        val en = parseDesktopEntry(file, Locale.ENGLISH)!!
        assertEquals("Files", en.name)
        assertNull(en.alias)
    }

    @Test
    fun indonesianIsIdAsDesktopEntriesWriteIt() {
        val file = entry("Name=Files", "Name[id]=Berkas")
        assertEquals("Berkas", parseDesktopEntry(file, Locale.forLanguageTag("id"))!!.name)
    }

    @Test
    fun defaultEntriesNameFilesAndEditorInArabic() {
        val apps = tmp.newFolder("applications")
        defaultApps(apps).forEach { it.file.writeBytes(it.content()) }
        val ar = Locale.forLanguageTag("ar")
        assertEquals("الملفات", parseDesktopEntry(apps.resolve("omarchy-files.desktop"), ar)!!.name)
        assertEquals("المحرر", parseDesktopEntry(apps.resolve("micro.desktop"), ar)!!.name)
        assertEquals("Editor", parseDesktopEntry(apps.resolve("micro.desktop"), Locale.GERMAN)!!.name)
    }
}
