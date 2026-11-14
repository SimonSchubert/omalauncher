package dev.omalauncher.widgets.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The stock drawer's arrange mode (long-press > move, then drag): the file compiles, its order state persists,
 * and the expressions the drop runs - ordering a section, where a drag lands, and the reorder itself - do what the
 * gestures mean. There's no Android runtime here, so the expressions are exercised as the file writes them. Where
 * a drop lands counts cells in the cell's real, laid-out size, reported by onSize (owl 24), so no font scale or
 * theme metric can move a landing; the landing test stands measured values in to exercise the math.
 */
class DrawerArrangeTest {
    private class TestHost(
        val program: Program,
    ) : Host {
        var slots = Array<Value>(program.globals.size) { Null }
        override val budget = Budget(100_000)

        init {
            program.globals.forEachIndexed { i, g -> if (g is Global.State) slots[i] = g.init(Env.root(this)) }
        }

        override fun read(slot: Int): Value = (program.globals.getOrNull(slot) as? Global.Let)?.value?.invoke(Env.root(this)) ?: slots[slot]

        override fun write(
            slot: Int,
            value: Value,
        ) {
            slots[slot] = value
        }

        override fun theme(name: String) = null

        override fun time() = 0.0

        override fun act(
            name: String,
            args: List<Value>,
        ) {}

        fun set(
            name: String,
            value: Value,
        ) = write(program.globals.indexOfFirst { it.name == name }.also { require(it >= 0) { "no global $name" } }, value)
    }

    /** [expr] over [decls], evaluated as a Text's prop would be. */
    private fun eval(
        expr: String,
        decls: String = "",
    ): Value {
        val p = Compiler.compile("widget \"t\" {\n meta { owl: 17 }\n$decls\n Text { text: $expr }\n}")
        val host = TestHost(p)
        val el = p.root.single() as WNode.Element
        return el.props.getValue("text")(Env.root(host))
    }

    private fun keys(v: Value): List<String> = (v as Lst).items.map { (it as Str).v }

    private val drawer = sequenceOf(File("../app/src/main/assets/widgets/drawer.owl"), File("app/src/main/assets/widgets/drawer.owl")).firstOrNull { it.exists() }

    @Test fun stockDrawerCompilesWithArrangeStates() {
        assertTrue("drawer.owl not found from ${File(".").absolutePath}", drawer != null)
        val p = Compiler.compile(drawer!!.readText())
        val states = p.globals.filterIsInstance<Global.State>().associateBy { it.name }
        for (name in listOf("arranging", "order", "dragKey", "dragX", "dragY", "pressX", "pressY", "cellW", "cellH")) {
            assertTrue("missing state $name", name in states)
        }
        assertTrue("order must keep across restarts", states.getValue("order").persist)
        for (name in listOf("arranging", "dragKey", "dragX", "dragY", "pressX", "pressY", "cellW", "cellH")) {
            assertFalse("$name is a gesture's, not a setting's", states.getValue(name).persist)
        }
    }

    /** A section: apps a drag placed come by their place in order, ones never moved last, in their old order. */
    @Test fun sectionOrdering() {
        val apps = """[{key: "a:camera"}, {key: "a:clock"}, {key: "a:gmail"}]"""
        val expr = "sortBy($apps, a => indexOf(order, a.key) == -1 ? 100000 : indexOf(order, a.key)).map(x => x.key)"
        // Nothing arranged yet: the drawer's own order, untouched.
        assertEquals(listOf("a:camera", "a:clock", "a:gmail"), keys(eval(expr, """state order = []""")))
        // Gmail dragged first, camera second: they lead in that order, the rest follows as it was.
        assertEquals(listOf("a:gmail", "a:camera", "a:clock"), keys(eval(expr, """state order = ["a:gmail", "a:camera"]""")))
        // A new install isn't in order: it goes last, it doesn't jump the arranged ones.
        val withNew = """[{key: "a:camera"}, {key: "a:new"}, {key: "a:gmail"}]"""
        assertEquals(
            listOf("a:gmail", "a:camera", "a:new"),
            keys(eval("sortBy($withNew, a => indexOf(order, a.key) == -1 ? 100000 : indexOf(order, a.key)).map(x => x.key)", """state order = ["a:gmail", "a:camera"]""")),
        )
    }

    /** Where a drop lands: the press point and the drag, counted in whole cells of the measured size, wrapping
     * row to row, clamped to the section. cellW and cellH come from onSize - the cell's real, laid-out size, in
     * the same coordinate space as the press point - so no font scale or theme metric can move a landing; here
     * they stand in as measured values to exercise the math. */
    @Test fun dropLanding() {
        val decls = """
            let cellW = 96
            let cellH = 110
            let cols = 4
            let from = 5
            let n = 12
        """.trimIndent()
        fun lands(
            pressX: Double,
            dragX: Double,
            pressY: Double,
            dragY: Double,
        ) = eval(
            "clamp(from + clamp(floor((pressX + dragX) / cellW), 0 - from % cols, cols - 1 - from % cols) + floor((pressY + dragY) / cellH) * cols, 0, n - 1)",
            "$decls\nlet pressX = $pressX\nlet dragX = $dragX\nlet pressY = $pressY\nlet dragY = $dragY",
        ).num()!!.toInt()
        // Let go where it was picked up: it stays.
        assertEquals(5, lands(40.0, 0.0, 50.0, 0.0))
        // Finger into the next cell: it moves there; halfway isn't far enough.
        assertEquals(6, lands(40.0, 60.0, 50.0, 0.0))
        assertEquals(5, lands(40.0, 50.0, 50.0, 0.0))
        // A row up is cols places back, a row down cols forward.
        assertEquals(1, lands(40.0, 0.0, 50.0, -115.0))
        assertEquals(9, lands(40.0, 0.0, 50.0, 115.0))
        // Both at once: two cells right and a row down (180 dp isn't two cells from a press at 10; 182 is).
        assertEquals(11, lands(10.0, 182.0, 50.0, 115.0))
        assertEquals(10, lands(10.0, 180.0, 50.0, 115.0))
        // Past either end: the nearest end, not a wrap or a crash.
        assertEquals(11, lands(80.0, 900.0, 50.0, 600.0))
        assertEquals(0, lands(20.0, -900.0, 50.0, -900.0))
    }

    /** The semantics are the cell containing the fingertip: the landing depends on where the finger IS, not on
     * where in the cell it came down. The same fingertip position reached from any press point gives the same
     * landing, and the boundary between two landings is the cell's edge, from any press point. */
    @Test fun dropLandingIsUnderTheFinger() {
        val decls = """
            let cellW = 96
            let cellH = 110
            let cols = 4
            let from = 5
            let n = 12
        """.trimIndent()
        fun lands(
            pressX: Double,
            dragX: Double,
            pressY: Double,
            dragY: Double,
        ) = eval(
            "clamp(from + clamp(floor((pressX + dragX) / cellW), 0 - from % cols, cols - 1 - from % cols) + floor((pressY + dragY) / cellH) * cols, 0, n - 1)",
            "$decls\nlet pressX = $pressX\nlet dragX = $dragX\nlet pressY = $pressY\nlet dragY = $dragY",
        ).num()!!.toInt()
        // The fingertip at the same place from a press at the left edge, the middle and the right edge: one landing.
        assertEquals(6, lands(5.0, 100.0, 50.0, 0.0))
        assertEquals(6, lands(48.0, 57.0, 50.0, 0.0))
        assertEquals(6, lands(90.0, 15.0, 50.0, 0.0))
        // The boundary between staying and moving is the cell's own edge, wherever the press was: a fingertip at
        // 95.9 is in, at 96.0 is out.
        assertEquals(5, lands(90.0, 5.9, 50.0, 0.0))
        assertEquals(6, lands(90.0, 6.0, 50.0, 0.0))
        assertEquals(5, lands(5.0, 90.9, 50.0, 0.0))
        assertEquals(6, lands(5.0, 91.0, 50.0, 0.0))
        // The same vertically: the fingertip's row is what counts, from a press at the top or the bottom edge.
        assertEquals(9, lands(40.0, 0.0, 2.0, 120.0))
        assertEquals(9, lands(40.0, 0.0, 108.0, 14.0))
        assertEquals(5, lands(40.0, 0.0, 108.0, 1.9))
        assertEquals(9, lands(40.0, 0.0, 108.0, 2.0))
        // Crossing a boundary backwards lands a cell back, from any press point.
        assertEquals(4, lands(5.0, -6.0, 50.0, 0.0))
        assertEquals(4, lands(90.0, -91.0, 50.0, 0.0))
        assertEquals(1, lands(40.0, 0.0, 2.0, -3.0))
        assertEquals(1, lands(40.0, 0.0, 108.0, -109.0))
    }

    /** Off the grid's sides the landing clamps to the row's own edge: a sideways drag never wraps into another
     * row, rows change only by dragging up or down, and a fingertip off the grid lands on the nearest cell. */
    @Test fun dropLandingClampsAtGridEdges() {
        fun lands(
            from: Int,
            pressX: Double,
            dragX: Double,
            pressY: Double,
            dragY: Double,
            n: Int = 12,
        ) = eval(
            "clamp(from + clamp(floor((pressX + dragX) / cellW), 0 - from % cols, cols - 1 - from % cols) + floor((pressY + dragY) / cellH) * cols, 0, n - 1)",
            "let cellW = 96\nlet cellH = 110\nlet cols = 4\nlet from = $from\nlet n = $n\nlet pressX = $pressX\nlet dragX = $dragX\nlet pressY = $pressY\nlet dragY = $dragY",
        ).num()!!.toInt()
        // Far past the left or right edge: the row's edge cell, not a wrap into the row before or after.
        assertEquals(4, lands(5, 40.0, -350.0, 50.0, 0.0))
        assertEquals(7, lands(5, 40.0, 350.0, 50.0, 0.0))
        // One cell past the edge from an edge column is still the edge: no wrap to the next row's start or the
        // previous row's end.
        assertEquals(4, lands(4, 40.0, -60.0, 50.0, 0.0))
        assertEquals(7, lands(7, 40.0, 60.0, 50.0, 0.0))
        // Diagonally out and back: the column the fingertip is actually over, one row up.
        assertEquals(1, lands(5, 40.0, -20.0, 50.0, -115.0))
        // Off the corner: the nearest end of the section.
        assertEquals(0, lands(5, 40.0, -350.0, 50.0, -400.0))
        // Onto the short last row's empty cells: the section's last app.
        assertEquals(5, lands(1, 40.0, 150.0, 50.0, 115.0, n = 6))
    }

    /** The reorder itself: out of the old place, into the new, the section's keys replaced in the kept list. */
    @Test fun dropReorder() {
        fun reorder(
            order: String,
            keys: String,
            from: Int,
            to: Int,
        ) = eval(
            "order.filter(k => indexOf(keys, k) == -1) + take(moved, to) + [keys[from]] + drop(moved, to)",
            """state order = $order
               let keys = $keys
               let moved = remove(keys, $from)
               let to = $to
               let from = $from""",
        ).let { keys(it) }

        val keys = """["a", "b", "c", "d"]"""
        // Move within the section, other sections' keys (x9 before, z1 after) keep their places around it.
        assertEquals(listOf("x9", "z1", "b", "c", "a", "d"), reorder("""["x9", "a", "b", "c", "d", "z1"]""", keys, 0, 2))
        assertEquals(listOf("x9", "z1", "d", "a", "b", "c"), reorder("""["x9", "a", "b", "c", "d", "z1"]""", keys, 3, 0))
        assertEquals(listOf("x9", "z1", "a", "c", "b", "d"), reorder("""["x9", "a", "b", "c", "d", "z1"]""", keys, 1, 2))
        assertEquals(listOf("x9", "z1", "a", "b", "c", "d"), reorder("""["x9", "a", "b", "c", "d", "z1"]""", keys, 2, 2))
        // First drag ever: the list starts empty, the section's whole new order is kept.
        assertEquals(listOf("b", "c", "a", "d"), reorder("[]", keys, 0, 2))
    }

    /** The kept order survives a restart: it encodes and decodes back whole. */
    @Test fun orderPersists() {
        val p = Compiler.compile(drawer!!.readText())
        val slots = PersistedValues.slots(p)
        val orderSlot = p.globals.indexOfFirst { it.name == "order" }
        assertTrue(orderSlot in slots)
        val values = slots.map { if (it == orderSlot) Lst(listOf(Str("a:gmail"), Str("a:camera"))) else Null }
        val text = PersistedValues.encode(p, values, 1_700_000_000)
        val back = PersistedValues.decode(p, text, 1_700_000_000.0)
        assertEquals(Lst(listOf(Str("a:gmail"), Str("a:camera"))), back[orderSlot])
    }
}
