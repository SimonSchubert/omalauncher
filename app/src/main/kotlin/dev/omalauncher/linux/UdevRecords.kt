package dev.omalauncher.linux

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * udev's record of each game controller, for programs that look for them only through libudev (RetroArch's udev
 * driver, Godot): a device in the guest's /sys stand-in ([sys]) and its entry in udev's database ([data], the
 * guest's /run/udev/data) saying it's a joystick. Programs then open /dev/input/eventN as they would anyway. No udev
 * daemon runs, so nothing announces a controller that comes later: a program finds the ones there as it starts.
 */
class UdevRecords(
    private val sys: File,
    private val data: File,
) {
    private val classInput = File(sys, "class/input")
    private val virtualInput = File(sys, "devices/virtual/input")

    /** Removes the records of an earlier run. */
    fun clear() {
        classInput.listFiles()?.forEach { it.delete() }
        virtualInput.listFiles()?.forEach(::deleteTree)
        data.listFiles()?.forEach { it.delete() }
    }

    /** Records eventN, the controller called [name]; its device number is 13:(64 + [number]), as libgamepads' fstat says. */
    fun add(
        number: Int,
        name: String,
    ) {
        val minor = MINOR_BASE + number
        val name = name.replace(Regex("[\"\n]"), " ")
        val input = File(virtualInput, inputName(number))
        val event = File(input, "event$number")
        event.mkdirs()
        classInput.mkdirs()
        data.mkdirs()
        // An input device (the controller) with its event device below it, as the kernel lays them out.
        File(input, "uevent").writeText("PRODUCT=3/45e/28e/114\nNAME=\"$name\"\n")
        File(input, "name").writeText("$name\n")
        link(File(input, "subsystem"), "../../../../class/input")
        File(event, "uevent").writeText("MAJOR=$MAJOR\nMINOR=$minor\nDEVNAME=input/event$number\n")
        File(event, "dev").writeText("$MAJOR:$minor\n")
        link(File(event, "subsystem"), "../../../../../class/input")
        link(File(event, "device"), "..")
        link(File(classInput, inputName(number)), "../../devices/virtual/input/${inputName(number)}")
        link(File(classInput, "event$number"), "../../devices/virtual/input/${inputName(number)}/event$number")
        // libudev lists a device with a node only once udev has seen it: the I line (when) says so.
        File(data, "c$MAJOR:$minor").writeText(
            "I:${System.nanoTime() / 1000}\nE:ID_INPUT=1\nE:ID_INPUT_JOYSTICK=1\nE:ID_BUS=usb\nE:ID_VENDOR_ID=045e\nE:ID_MODEL_ID=028e\n",
        )
    }

    fun remove(number: Int) {
        File(data, "c$MAJOR:${MINOR_BASE + number}").delete()
        File(classInput, "event$number").delete()
        File(classInput, inputName(number)).delete()
        deleteTree(File(virtualInput, inputName(number)))
    }

    /** Deletes [f] and what's below it, without following the links: `device` and `subsystem` point outside. */
    private fun deleteTree(f: File) {
        val path = f.toPath()
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) f.listFiles()?.forEach(::deleteTree)
        Files.deleteIfExists(path)
    }

    private fun link(
        at: File,
        target: String,
    ) {
        at.delete()
        Files.createSymbolicLink(Paths.get(at.path), Paths.get(target))
    }

    private fun inputName(number: Int) = "input${INPUT_BASE + number}"

    private companion object {
        /** evdev's major and first minor. */
        const val MAJOR = 13
        const val MINOR_BASE = 64

        /** The first input device number; any would do, since the /sys stand-in has no other input devices. */
        const val INPUT_BASE = 100
    }
}
