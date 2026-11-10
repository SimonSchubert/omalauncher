package dev.omalauncher.ui

import android.app.Application
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses the real home focus modifiers: clearing a field must not give it focus back on the next frame. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeInputFocusTest {
    @get:Rule val compose = createComposeRule()

    private fun home(twoFields: Boolean = false): () -> Unit {
        var release: () -> Unit = {}
        compose.setContent {
            val home = remember { FocusRequester() }
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            release = { focus.clearFocus(); keyboard?.hide() }
            Box(Modifier.fillMaxSize().keysRoot()) {
                Column(Modifier.focusRequester(home).focusGroup()) {
                    Column(Modifier.passesKeys(home).keyActivated({}, null)) {
                        BasicTextField("query", {}, Modifier.testTag("input").height(54.dp))
                        if (twoFields) BasicTextField("second", {}, Modifier.testTag("second").height(54.dp))
                    }
                }
            }
        }
        return { release() }
    }

    @Test fun releasingAHomeFieldDoesNotFocusItAgain() {
        val release = home()
        compose.onNodeWithTag("input").performClick().assertIsFocused()
        compose.runOnIdle { release() }
        compose.waitForIdle()
        compose.onNodeWithTag("input").assertIsNotFocused()
        // Dismissal stays dismissed across more than one posted focus-recovery callback.
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("input").assertIsNotFocused()
        compose.onNodeWithTag("input").performClick().assertIsFocused()
    }

    @Test fun switchingFieldsThenReleasingDoesNotRestartTheFirstField() {
        val release = home(twoFields = true)
        compose.onNodeWithTag("input").performClick().assertIsFocused()
        compose.onNodeWithTag("second").performClick().assertIsFocused()
        compose.onNodeWithTag("input").assertIsNotFocused()
        compose.runOnIdle { release() }
        compose.waitForIdle()
        compose.onNodeWithTag("input").assertIsNotFocused()
        compose.onNodeWithTag("second").assertIsNotFocused()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("input").assertIsNotFocused()
        compose.onNodeWithTag("second").assertIsNotFocused()
    }

    @Test fun removingAKeyboardFocusedTileStillPassesFocusToHome() {
        val showing = mutableStateOf(true)
        lateinit var first: FocusRequester
        lateinit var inputMode: InputModeManager
        compose.setContent {
            val home = remember { FocusRequester() }
            first = remember { FocusRequester() }
            inputMode = LocalInputModeManager.current
            Box(Modifier.fillMaxSize().keysRoot()) {
                Column(Modifier.focusRequester(home).focusGroup()) {
                    if (showing.value) {
                        Box(Modifier.focusRequester(first).passesKeys(home).keyActivated({}, null).testTag("first").height(54.dp))
                    }
                    Box(Modifier.keyActivated({}, null).testTag("next").height(54.dp))
                }
            }
        }
        compose.runOnIdle {
            inputMode.requestInputMode(InputMode.Keyboard)
        }
        compose.runOnIdle { first.requestFocus() }
        compose.onNodeWithTag("first").assertIsFocused()
        compose.runOnIdle { showing.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("next").assertIsFocused()
    }

}
