package dev.omalauncher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.omalauncher.LauncherData
import dev.omalauncher.MainActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The actual launcher, widget renderer, touch routing and Android IME, without Linux. */
class HomeKeyboardDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity

    @Before fun openHome() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LauncherData.prefs(context).edit().putBoolean("setup_done", true).putBoolean("home_role_asked", true).commit()
        val dir = LauncherData.widgetsDir(context).also { it.mkdirs() }
        File(dir, "00-regression.owl").writeText("""
            widget "keyboard regression" {
              meta { owl: 10 }
              size: 6x3
              page: 1
              order: -100
              state first = ""
              state second = ""
              Column {
                Input { text: first; placeholder: "Regression first"; height: 48; onChange: first = value }
                Input { text: second; placeholder: "Regression second"; height: 48; onChange: second = value }
              }
            }
        """.trimIndent())
        val home = LauncherData.homeDir(context).also { it.mkdirs() }
        File(home, "layout.toml").writeText("""
            columns = 6
            version = 1
            [[page]]
            tiles = [{ widget = "00-regression.owl", x = 0, y = 4, w = 6, h = 3 }]
        """.trimIndent())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
        // A resumed activity can still sit behind an emulator boot-error dialog. Its Compose
        // tree accepts injected touches there, but Android correctly refuses to open its IME.
        compose.waitUntil(15000) {
            var focused = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { focused = activity.hasWindowFocus() }
            focused
        }
        compose.waitUntil(15000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2 }
    }

    @After fun closeHome() { if (::scenario.isInitialized) scenario.close() }

    private fun keyboardVisible(): Boolean {
        var visible = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            visible = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun first() = compose.onAllNodes(hasSetTextAction())[0]
    private fun second() = compose.onAllNodes(hasSetTextAction())[1]

    private fun tapOutsideAndCheckItStaysClosed() {
        compose.onRoot().performTouchInput { click(Offset(5f, 100f)) }
        compose.waitUntil(5000) { !keyboardVisible() }
        // The reported failure can cycle after an apparently successful dismissal.
        repeat(20) {
            Thread.sleep(100)
            assertFalse("IME reopened after an outside tap (sample $it)", keyboardVisible())
        }
        first().assertIsNotFocused()
        second().assertIsNotFocused()
    }

    @Test fun outsideTapDismissesAWidgetKeyboard() {
        first().performTouchInput { click() }
        first().performTextInput("brave query")
        compose.waitUntil(5000) { keyboardVisible() }
        tapOutsideAndCheckItStaysClosed()
    }

    @Test fun switchingFieldsThenTappingOutsideDoesNotLoopTheKeyboard() {
        first().performTouchInput { click() }
        first().performTextInput("first")
        compose.waitUntil(5000) { keyboardVisible() }
        Thread.sleep(500) // Switch only after the first field's keyboard animation has settled.
        val firstTop = first().fetchSemanticsNode().boundsInRoot.top
        second().performTouchInput { click() }
        second().performTextInput("second")
        second().assertIsFocused()
        Thread.sleep(500)
        org.junit.Assert.assertEquals("Switching fields must keep their tile above the keyboard", firstTop, first().fetchSemanticsNode().boundsInRoot.top, 2f)
        tapOutsideAndCheckItStaysClosed()
    }
}
