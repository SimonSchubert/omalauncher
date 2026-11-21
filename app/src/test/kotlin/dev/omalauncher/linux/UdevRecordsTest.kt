package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class UdevRecordsTest {
    private val root = createTempDirectory("udev").toFile()
    private val sys = File(root, "sys")
    private val data = File(root, "udev/data")
    private val records = UdevRecords(sys, data)

    private fun real(path: String) = File(sys, path).toPath().toRealPath().toFile()

    /** What libudev reads to list a joystick: class/input/eventN leading to a device with a node, in the database. */
    @Test fun aControllerIsListedAsLibudevExpects() {
        records.add(0, "Xbox Wireless Controller")
        val event = real("class/input/event0")
        assertEquals(File(sys, "devices/virtual/input/input100/event0").canonicalFile, event)
        assertEquals("MAJOR=13\nMINOR=64\nDEVNAME=input/event0\n", File(event, "uevent").readText())
        assertEquals(File(sys, "class/input").canonicalFile, File(event, "subsystem").toPath().toRealPath().toFile())
        assertEquals("input", Files.readSymbolicLink(File(event, "subsystem").toPath()).fileName.toString())
        val db = File(data, "c13:64").readLines()
        assertTrue(db.first().startsWith("I:"))
        assertTrue("E:ID_INPUT_JOYSTICK=1" in db)
    }

    @Test fun removingOneLeavesTheOther() {
        records.add(0, "Pad A")
        records.add(1, "Pad B")
        records.remove(0)
        assertFalse(File(sys, "class/input/event0").toPath().let { Files.exists(it) || Files.isSymbolicLink(it) })
        assertFalse(File(data, "c13:64").exists())
        assertFalse(File(sys, "devices/virtual/input/input100").exists())
        assertEquals(File(sys, "devices/virtual/input/input101/event1").canonicalFile, real("class/input/event1"))
        assertTrue(File(data, "c13:65").exists())
        // The links out of a device (device, subsystem) weren't followed: class/input is still there.
        assertTrue(File(sys, "class/input").isDirectory)
    }

    @Test fun clearRemovesAnEarlierRun() {
        records.add(0, "Pad \"quoted\"")
        records.clear()
        assertEquals(0, File(sys, "class/input").list()?.size)
        assertEquals(0, File(sys, "devices/virtual/input").list()?.size)
        assertEquals(0, data.list()?.size)
    }
}
