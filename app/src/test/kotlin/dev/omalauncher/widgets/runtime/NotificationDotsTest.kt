package dev.omalauncher.widgets.runtime

import android.app.Notification
import android.app.NotificationChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDotsTest {
    private val chat = DotKey("chat", null)
    private val workChat = DotKey("chat", 10L)

    @Test fun anAppHasADotWhileOneOfItsNotificationsIsThere() {
        val t = DotTable()
        t.post("a", chat, true)
        t.post("b", chat, true)
        assertEquals(setOf(chat), t.dots())
        t.remove("a")
        assertEquals(setOf(chat), t.dots())
        t.remove("b")
        assertEquals(emptySet<DotKey>(), t.dots())
    }

    @Test fun aWorkCopyGetsItsOwnDot() {
        val t = DotTable()
        t.post("a", workChat, true)
        assertEquals(setOf(workChat), t.dots())
    }

    @Test fun aConversationDotsTheAppAndThatShortcutOnly() {
        val t = DotTable()
        t.post("a", chat.copy(shortcut = "alice"), true)
        assertEquals(setOf(chat, chat.copy(shortcut = "alice")), t.dots())
    }

    @Test fun androidTakesADotAwayAndGivesItBack() {
        val t = DotTable()
        t.post("a", chat, true)
        t.rank { false }
        assertEquals(emptySet<DotKey>(), t.dots())
        t.rank { true }
        assertEquals(setOf(chat), t.dots())
    }

    @Test fun aChannelWithoutDotsShowsNone() {
        val t = DotTable()
        t.post("a", chat, false)
        assertEquals(emptySet<DotKey>(), t.dots())
    }

    @Test fun losingAccessClearsTheDots() {
        val t = DotTable()
        t.post("a", chat, true)
        t.clear()
        assertEquals(emptySet<DotKey>(), t.dots())
    }

    @Test fun groupHeadersAndEmptyNotificationsDontCount() {
        assertFalse(countsForDot(Notification.FLAG_GROUP_SUMMARY, "c", "title", "text"))
        assertFalse(countsForDot(0, "c", null, ""))
        assertTrue(countsForDot(0, "c", "title", null))
        assertTrue(countsForDot(0, "c", null, "text"))
    }

    @Test fun onlyOldAppsOngoingNotificationsAreLeftOut() {
        assertFalse(countsForDot(Notification.FLAG_ONGOING_EVENT, NotificationChannel.DEFAULT_CHANNEL_ID, "title", null))
        assertTrue(countsForDot(0, NotificationChannel.DEFAULT_CHANNEL_ID, "title", null))
        assertTrue(countsForDot(Notification.FLAG_ONGOING_EVENT, "c", "title", null))
    }
}
