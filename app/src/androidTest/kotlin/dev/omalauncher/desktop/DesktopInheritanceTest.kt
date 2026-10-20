package dev.omalauncher.desktop

import android.content.ComponentName
import android.content.Context
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.omalauncher.AndroidApp
import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.Folder
import dev.omalauncher.widgets.Shortcuts
import dev.omalauncher.widgets.Shortcut
import dev.omalauncher.widgets.WidgetLayout
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DesktopInheritanceTest {
    @Test fun desktopScaleReflowsItsOwnCellsWithoutChangingPhonePlacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phone = "phone_" + UUID.randomUUID()
        val desktop = "workspace_" + UUID.randomUUID()
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val phoneLayout = WidgetLayout(context, phone)
                val desktopLayout = WidgetLayout(context, desktop)
                val first = Shortcut("one", 1, "One")
                val second = Shortcut("two", 1, "Two")
                phoneLayout.set("one", Cell(4, 0, 1, 1))
                phoneLayout.save()
                desktopLayout.reflowDesktop(8, 5, listOf(first, second))
                desktopLayout.set("one", Cell(6, 0, 1, 1))
                desktopLayout.set("two", Cell(7, 0, 1, 1))
                desktopLayout.save()
                desktopLayout.reflowDesktop(4, 3, listOf(first, second))
                assertEquals(4, desktopLayout.columns)
                assertEquals(Cell(0, 0, 1, 1), desktopLayout.cell("one"))
                assertEquals(Cell(1, 0, 1, 1), desktopLayout.cell("two"))
                assertEquals(Cell(4, 0, 1, 1), WidgetLayout(context, phone).cell("one"))
                assertEquals(desktopLayout.cell("two"), WidgetLayout(context, desktop).cell("two"))
            }
        } finally {
            for (profile in listOf(phone, desktop)) {
                context.getSharedPreferences("widget_grid_$profile", Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
    }

    @Test fun clonedPlacementFoldersAndProfileIdentityRemainIndependentAndPersist() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sourceProfile = "source_" + UUID.randomUUID()
        val desktopProfile = "desktop_" + UUID.randomUUID()
        try {
            instrumentation.runOnMainSync {
                val sourceLayout = WidgetLayout(context, sourceProfile)
                val sourceApps = Shortcuts(context, sourceLayout, sourceProfile)
                sourceLayout.regrid(4, emptyList())
                sourceLayout.set("widget.owl", Cell(0, 7, 4, 3))
                sourceLayout.setPage("widget.owl", 3)
                val personal = AndroidApp("Personal", ComponentName("fixture.one", "fixture.one.Main"), Process.myUserHandle())
                val work = AndroidApp("Work", ComponentName("fixture.two", "fixture.two.Main"), Process.myUserHandle(), profile = 42L)
                sourceApps.addAndroid(personal, 2)
                sourceApps.addAndroid(work, 2)
                val first = sourceApps.items[0]
                val second = sourceApps.items[1]
                sourceLayout.set(first.key, Cell(1, 1, 1, 1))
                val folderKey = sourceApps.group(second, first)!!

                val layout = WidgetLayout(context, desktopProfile)
                val apps = Shortcuts(context, layout, desktopProfile)
                layout.seedFrom(sourceLayout)
                apps.seedFrom(sourceApps)
                assertEquals(4, layout.columns)
                assertEquals(Cell(0, 7, 4, 3), layout.cell("widget.owl"))
                assertEquals(3, layout.page("widget.owl"))
                assertEquals(sourceApps.onGrid(), apps.onGrid())
                assertEquals(42L, (apps.onGrid().single() as Folder).apps.last().profile)
                apps.move(folderKey, 3)
                apps.remove(second.key)
                layout.set("widget.owl", Cell(1, 0, 3, 2))
                layout.setPage("widget.owl", 1)

                // Re-read both profiles to catch accidental persistence into phone preferences.
                val reloadedSource = WidgetLayout(context, sourceProfile)
                val reloadedApps = Shortcuts(context, reloadedSource, sourceProfile)
                assertEquals(Cell(0, 7, 4, 3), reloadedSource.cell("widget.owl"))
                assertEquals(3, reloadedSource.page("widget.owl"))
                assertEquals(2, (reloadedApps.onGrid().single() as Folder).apps.size)
                assertEquals(2, reloadedApps.onGrid().single().page)
                val reloadedDesktop = WidgetLayout(context, desktopProfile)
                assertEquals(4, reloadedDesktop.columns)
                assertEquals(Cell(1, 0, 3, 2), reloadedDesktop.cell("widget.owl"))
                assertEquals(1, reloadedDesktop.page("widget.owl"))
                assertEquals(3, Shortcuts(context, reloadedDesktop, desktopProfile).onGrid().single().page)
            }
        } finally {
            for (profile in listOf(sourceProfile, desktopProfile)) {
                for (name in listOf("widget_grid_$profile", "shortcuts_$profile")) {
                    context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                }
            }
        }
    }
}
