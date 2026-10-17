package dev.omalauncher.widgets.runtime

import android.graphics.Bitmap
import android.util.Log
import dev.omalauncher.upgrade.writeAtomically
import dev.omalauncher.widgets.lang.PersistedValues
import dev.omalauncher.widgets.lang.TriggerKind
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.graphics.toArgb
import dev.omalauncher.linux.ShellResult
import dev.omalauncher.ui.EmbeddedTerminal
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Palette
import dev.omalauncher.widgets.lang.Budget
import dev.omalauncher.widgets.lang.Env
import dev.omalauncher.widgets.lang.Ev
import dev.omalauncher.widgets.lang.Global
import dev.omalauncher.widgets.lang.Host
import dev.omalauncher.widgets.lang.Json
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.TRUE
import dev.omalauncher.widgets.lang.FALSE
import dev.omalauncher.widgets.lang.Program
import dev.omalauncher.widgets.lang.Sprite
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.Trigger
import dev.omalauncher.widgets.lang.WidgetError
import dev.omalauncher.widgets.lang.bool
import dev.omalauncher.widgets.lang.num
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.io.File

/** What widgets need from the launcher: guest commands, Android data and actions. */
interface Services {
    suspend fun cmd(
        command: String,
        timeoutSeconds: Int,
    ): ShellResult

    /**
     * An HTTP request from Android, not the guest: a GET, or a POST of [body] for `post(...)`.
     * [ShellResult.code] is the HTTP status, or -1 if the request failed.
     */
    suspend fun http(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Int,
        body: ByteArray? = null,
    ): ShellResult

    /**
     * A file from the web, for `Image { src: "https://..." }` and `sound("https://...")`: kept on disk,
     * so it works offline after the first time; null if it can't be downloaded and was never kept.
     */
    suspend fun remoteFile(url: String): File? = null

    /**
     * `sound(...)`: plays [file] or [bytes] for [owner], in place of what it was playing; both null
     * stops it. [failed] hears why one couldn't play.
     */
    fun sound(
        owner: Any,
        file: File?,
        bytes: ByteArray?,
        failed: (String) -> Unit = {},
    ) {}

    /** Runs [command] for `x = run(...)`; [done] gets the result, or null if the command couldn't run. */
    fun run(
        command: String,
        done: (ShellResult?) -> Unit,
    )

    /** Lines printed by a long-running guest command; shared between widgets running the same one. */
    suspend fun stream(
        command: String,
        onLine: (String) -> Unit,
    )

    fun android(name: String): Value

    /** Updates for Android data that changes on its own (battery, media); null means poll [android]. */
    fun androidUpdates(name: String): Flow<Value>? = null

    /** An image Android holds, like album art: `Image { src: m.art }`. */
    fun androidImage(src: String): Bitmap? = null

    /** An Android app's icon, `Image { src: "app:org.pkg" }`; null until the launcher has loaded it, or if there's no such app. */
    fun appIcon(pkg: String): ImageBitmap? = null

    /** Runs an action; [done] is called once its effects should be visible, to refresh the widget that ran it. */
    fun act(
        name: String,
        args: List<Value>,
        done: () -> Unit,
    )

    /** A file inside the guest, for Image { src: "~/pics/x.png" }. */
    fun guestFile(path: String): java.io.File

    /** A guest file's text now and after every change, for `file(...)`; a missing file reads as "". */
    fun fileUpdates(path: String): Flow<String>

    /** A terminal for a `Terminal` node, not started yet; [command] null means the login shell. */
    fun terminal(
        command: String?,
        fontSize: Double?,
    ): EmbeddedTerminal
}

/**
 * One running widget. Every global is a snapshot state: sources and state are
 * mutable, lets are derived, so a binding only invalidates what reads it, and
 * a value that comes back unchanged invalidates nothing.
 */
class WidgetInstance(
    val program: Program,
    private val services: Services,
    /** Reports runtime errors once per distinct message. */
    private val onError: (String) -> Unit,
    /** Where `persist` state and histories are kept between runs. */
    private val savedFile: File? = null,
    /** screen() and close(): opens or closes the widget's screen { }. */
    private val onScreen: (Boolean) -> Unit = {},
    /** Linux is installed and running; `optional` sources wait for it. Snapshot state. */
    private val linux: () -> Boolean = { true },
    /** An app's window or the widget's screen is showing. Snapshot state. */
    private val foreground: () -> Boolean = { false },
) : Host {
    override val budget = Budget()
    val root = Env.root(this)
    private val frameTime = mutableDoubleStateOf(0.0)
    /** Whether anything read `time` since the frame clock last set it; see [awaitTimeRead]. */
    @Volatile var timeRead = true
    private val timeWake = Channel<Unit>(Channel.CONFLATED)
    /** The parked frame clock's start, or [RUNNING]; see [awaitTimeRead]. */
    @Volatile private var parkedStart = RUNNING
    /** The latest time a read got while the clock was parked. */
    @Volatile private var parkedTime = 0.0
    private val cells = arrayOfNulls<State<Value>>(program.globals.size)
    private val images = WidgetImages(program, services, ::report)

    /** Where the sources run; set by [start]. */
    private var scope: CoroutineScope? = null

    /** Parsed `sprite` art by its rows value; a let or a literal keeps the same value from frame to frame. */
    private val sprites = java.util.IdentityHashMap<Value, Sprite>()

    /** The runs of [rows], parsed once; called while drawing, on the main thread. */
    fun sprite(rows: Value): Sprite {
        sprites[rows]?.let { return it }
        // Art built anew each frame would otherwise pile up.
        if (sprites.size >= MAX_SPRITES) sprites.clear()
        return Sprite.parse(rows).also { sprites[rows] = it }
    }

    /** Bumped whenever a source updates; album art follows it. */
    val updates = mutableIntStateOf(0)
    private val jobs = ArrayList<Job>()
    private val reported = HashSet<String>()

    /** The tile's grid cells and size in dp, set by the home screen as it lays the tile out or it's resized. */
    private val tileSize = mutableStateOf(listOf<Double?>(null, null, null, null), structuralEqualityPolicy())

    /** Slots of `state ... persist` and `history(...) persist`. */
    private val persisted = PersistedValues.slots(program)

    private var lastSaved: List<Value>? = null

    init {
        program.globals.forEachIndexed { i, g ->
            cells[i] =
                when (g) {
                    is Global.Let -> derivedStateOf(structuralEqualityPolicy()) { eval(g.value, root, g.name) }
                    is Global.Sampled -> mutableStateOf(if (g.prev) Null else Lst(emptyList()), structuralEqualityPolicy())
                    is Global.Asset -> mutableStateOf(Str("asset:${g.name}"))
                    else -> mutableStateOf(Null, structuralEqualityPolicy())
                }
        }
        // State starts from its initial value; it may read sources, which are still null here.
        program.globals.forEachIndexed { i, g -> if (g is Global.State) write(i, eval(g.init, root, g.name)) }
        restore()
    }

    private fun restore() {
        val file = savedFile?.takeIf { persisted.isNotEmpty() && it.exists() } ?: return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        for ((i, v) in PersistedValues.decode(program, text, System.currentTimeMillis() / 1000.0)) write(i, v)
        lastSaved = persisted.map { read(it) }
    }

    /** Writes the persisted values if they changed since the last write. */
    private fun save(values: List<Value>) {
        val file = savedFile ?: return
        if (values == lastSaved) return
        val text = PersistedValues.encode(program, values, System.currentTimeMillis() / 1000)
        runCatching {
            writeAtomically(file, text.toByteArray())
            lastSaved = values
        }.onFailure { report("couldn't save persisted values: ${it.message}") }
    }

    override fun read(slot: Int): Value = cells[slot]!!.value

    override fun write(
        slot: Int,
        value: Value,
    ) {
        @Suppress("UNCHECKED_CAST")
        (cells[slot] as MutableState<Value>).value = value
    }

    override fun theme(name: String): Int? =
        when (name) {
            "accent" -> Palette.accent
            "foreground" -> Palette.foreground
            "background" -> Palette.background
            "muted" -> Palette.muted
            "surface" -> Palette.surface
            "border" -> Palette.border
            "white" -> androidx.compose.ui.graphics.Color.White
            "black" -> androidx.compose.ui.graphics.Color.Black
            else -> Palette.named(name)
        }?.toArgb()

    override fun time(): Double {
        if (!timeRead) {
            timeRead = true
            timeWake.trySend(Unit)
        }
        val t = frameTime.doubleValue
        val start = parkedStart
        if (start == RUNNING) return t
        // The last frame's time is old by now: a tap that keeps `time`, like `rollAt = time`
        // under a `since = time - rollAt` drawn only while it rolls, needs the real one.
        parkedTime = maxOf(parkedTime, (System.nanoTime() - start) / 1e9)
        return maxOf(t, parkedTime)
    }

    /**
     * Suspends until something reads `time`: a `time` under an `if` that's false needs no frames.
     * [start] is the frame clock's start, in [System.nanoTime], so reads meanwhile get the real time.
     */
    suspend fun awaitTimeRead(start: Long) {
        while (timeWake.tryReceive().isSuccess) Unit
        if (timeRead) return
        parkedTime = 0.0
        parkedStart = start
        try {
            timeWake.receive()
        } catch (e: kotlinx.coroutines.CancellationException) {
            parkedStart = RUNNING
            throw e
        }
    }

    fun setTile(
        cols: Int?,
        rows: Int?,
        widthDp: Double?,
        heightDp: Double?,
    ) {
        val old = tileSize.value
        tileSize.value = listOf(cols?.toDouble() ?: old[0], rows?.toDouble() ?: old[1], widthDp ?: old[2], heightDp ?: old[3])
    }

    /** The screen's size in dp, while it's open. */
    private val screenSize = mutableStateOf(listOf<Double?>(null, null), structuralEqualityPolicy())

    fun setScreen(
        widthDp: Double,
        heightDp: Double,
    ) {
        screenSize.value = listOf(widthDp, heightDp)
    }

    /** How much of the screen's bottom the navigation bar covers, in dp: only an app with `edge` draws there. */
    private val screenInset = mutableDoubleStateOf(0.0)

    fun setScreenInset(dp: Double) {
        screenInset.doubleValue = dp
    }

    override fun tile(name: String): Value {
        val v =
            when (name) {
                "linux" -> return bool(linux())
                "corners" -> return Num(LauncherStyle.cornerScale.toDouble())
                "screenWidth" -> screenSize.value[0]
                "screenHeight" -> screenSize.value[1]
                "screenInset" -> screenInset.doubleValue
                "cols" -> tileSize.value[0]
                "rows" -> tileSize.value[1]
                "tileWidth" -> tileSize.value[2]
                "tileHeight" -> tileSize.value[3]
                else -> null
            }
        return v?.let { Num(Math.round(it).toDouble()) } ?: Null
    }

    var time: Double
        get() = frameTime.doubleValue
        set(v) {
            // The first frame after parking doesn't go back before a time a read already got.
            frameTime.doubleValue = if (parkedStart == RUNNING) v else maxOf(v, parkedTime)
            parkedStart = RUNNING
        }

    override fun runInto(
        command: String,
        slot: Int,
    ) {
        services.run(command) { r ->
            if (r == null) return@run report("run: the command couldn't start")
            if (r.code != 0 && r.err.isNotBlank()) report("run: ${commandFailure(r)}")
            write(slot, Str(r.out))
            refresh()
        }
    }

    override fun post(
        url: String,
        body: Value,
        headers: Map<String, String>,
        slot: Int?,
    ) {
        // A map or list goes as JSON, anything else as its text.
        val json = if (body is Obj || body is Lst) Json.write(body) else null
        val bytes = (json ?: if (body == Null) "" else body.toString()).toByteArray()
        val sent = if (json != null && headers.keys.none { it.equals("Content-Type", true) }) headers + ("Content-Type" to "application/json") else headers
        val scope = scope ?: return
        scope.launch {
            val r = services.http(url, sent, POST_TIMEOUT_S, bytes)
            val ok = r.code in 200..299
            // `x = post(...)` gets the answer and handles it (a sign-in polled while pending is a 400 each time).
            if (!ok && slot == null) report("post: ${httpFailure(r)}")
            if (slot != null) {
                write(slot, Obj(mapOf("code" to Num(r.code.toDouble()), "ok" to if (ok) TRUE else FALSE, "out" to if (r.out.isEmpty()) Null else Json.parseOrText(r.out))))
            }
            // What it changed shows in this widget's sources. A refusal changed nothing: a sign-in polled
            // until it's approved mustn't fetch everything again each time.
            if (ok) refresh()
        }
    }

    override fun act(
        name: String,
        args: List<Value>,
    ) {
        if (name == "refresh") {
            refresh()
            return
        }
        if (name == "screen" || name == "close") {
            onScreen(name == "screen")
            return
        }
        if (name == "sound") {
            args.firstOrNull()?.takeIf { it != Null }?.let { sound(it.toString()) }
            return
        }
        // Only this widget refreshes afterwards; the others keep their own intervals.
        services.act(name, args) { refresh() }
    }

    /**
     * Rolling average of Canvas/Shader draw time. Over budget halves the
     * widget's animation frame rate; well under it again, full rate comes back.
     */
    private var drawAverage = 0.0

    /** Draws recorded so far; the first ones run before the JIT has compiled the drawing code, and don't count. */
    private var draws = 0

    var slow = false
        private set

    private var reportedSlow = false

    fun recordDraw(nanos: Long) {
        if (draws < WARMUP_DRAWS) {
            draws++
            return
        }
        drawAverage = drawAverage * 0.9 + nanos * 0.1
        if (!slow && drawAverage > SLOW_DRAW_NANOS) {
            slow = true
            // Once: a widget that hovers around the budget would otherwise report every time it crosses it.
            if (!reportedSlow) report("drawing takes %.1f ms per frame; animating at half rate".format(drawAverage / 1e6))
            reportedSlow = true
        } else if (slow && drawAverage < SLOW_DRAW_NANOS / 2) {
            slow = false
        }
    }

    /** Evaluates [ev], turning errors into null plus a report; a broken binding never takes the launcher down. */
    fun eval(
        ev: Ev,
        env: Env,
        where: String,
    ): Value {
        ev.const?.let { return it }
        return try {
            budget.run { ev(env) }
        } catch (e: WidgetError) {
            report("$where: ${e.message}")
            Null
        } catch (e: RuntimeException) {
            report("$where: $e")
            Null
        } catch (e: StackOverflowError) {
            report("$where: too deeply nested")
            Null
        }
    }

    override fun report(message: String) {
        if (reported.add(message) && reported.size < 50) {
            Log.w("widgets", "${program.name}: $message")
            onError(message)
        }
    }

    /** See [WidgetImages.image]. */
    fun image(src: String): WidgetImage? = images.image(src)

    /** Keeps values of sources and state across a reload of the same file, so saving doesn't flash "…". */
    fun adopt(old: WidgetInstance) {
        program.globals.forEachIndexed { i, g ->
            if (g is Global.Source || g is Global.State || g is Global.Sampled) {
                val j = old.program.globals.indexOfFirst { it.name == g.name && it::class == g::class }
                if (j >= 0) write(i, old.read(j))
            }
        }
        // What triggers last saw, so saving the file doesn't run a `when` again for a condition that was already true.
        program.triggers.forEachIndexed { i, t ->
            val o = old.program.triggers.getOrNull(i)
            if (o?.kind == t.kind) seen[i] = old.seen[i]
        }
    }

    /** Every source has a value; what a preview waits for. Reads state, so it can be observed. */
    fun loaded() = program.globals.indices.all { program.globals[it] !is Global.Source || read(it) != Null || paused[it] == true }

    /** Sources whose command or URL is null: they wait without a value, and count as loaded. */
    private val paused = mutableStateMapOf<Int, Boolean>()

    /** Bumped by refresh(); each source loop wakes early when it changes. */
    private val refreshes = MutableStateFlow(0)

    private fun refresh() = refreshes.update { it + 1 }

    /**
     * Runs the sources while [active] is true (home screen showing this page or
     * a neighbour); otherwise they wait without polling.
     */
    fun start(
        scope: CoroutineScope,
        active: () -> Boolean,
    ) {
        this.scope = scope
        images.scope = scope
        // A sound stops with the app's window or the screen it played in.
        jobs +=
            scope.launch {
                var shown = foreground()
                snapshotFlow { foreground() }.collect { now ->
                    if (shown && !now) services.sound(this@WidgetInstance, null, null)
                    shown = now
                }
            }
        if (persisted.isNotEmpty() && savedFile != null) {
            // At most one write every few seconds, however fast the sources sample.
            jobs +=
                scope.launch {
                    snapshotFlow { persisted.map { read(it) } }.conflate().collect { values ->
                        withContext(Dispatchers.IO) { save(values) }
                        delay(5000)
                    }
                }
        }
        startTriggers(scope, active)
        program.globals.forEachIndexed { slot, g ->
            if (g !is Global.Source) return@forEachIndexed
            jobs +=
                scope.launch {
                    // The command or URL a cmd or http source last ran, and when; whether it must run again now.
                    var last: Value? = null
                    var lastAt = 0L
                    var lastWait = 0L
                    var due = true
                    while (isActive) {
                        snapshotFlow { active() }.first { it }
                        if (g.optional && !linux()) {
                            // Waits for Linux, without a value; the widget reads `linux` to say why.
                            write(slot, Null)
                            snapshotFlow { linux() }.first { it }
                            continue
                        }
                        val pushed = if (g.kind.startsWith("android.")) services.androidUpdates(g.kind.removePrefix("android.")) else null
                        if (pushed != null) {
                            // Listens while on screen, like a stream, instead of polling.
                            untilPaused(active, g) {
                                pushed.collect { v ->
                                    write(slot, v)
                                    sample(g)
                                }
                            }
                            continue
                        }
                        when (g.kind) {
                            "stream" -> {
                                val command = eval(g.arg!!, root, g.name).toString()
                                // Off screen: stop listening (the process stops once nobody listens).
                                val ended = untilPaused(active, g) { services.stream(command) { line -> set(slot, g, line) } }
                                if (ended) delay(5000) // the command exited: restart it, but not in a tight loop
                            }
                            "file" ->
                                untilPaused(active, g) {
                                    // A path built from state is watched anew when it changes.
                                    snapshotFlow { eval(g.arg!!, root, g.name) }.collectLatest { path ->
                                        if (path != Null) services.fileUpdates(path.toString()).collect { text -> set(slot, g, text) }
                                    }
                                }
                            else -> {
                                val seen = refreshes.value
                                val built = g.arg?.takeIf { it.const == null }
                                val arg = built?.let { eval(it, root, g.name) }
                                if (arg == Null) {
                                    // A null command or URL pauses the source, keeping its value, until it's something again.
                                    paused[slot] = true
                                    untilPaused(active, g) { snapshotFlow { eval(built, root, g.name) }.first { it != Null } }
                                    paused.remove(slot)
                                    due = false
                                    continue
                                }
                                // An interval of NaN ("NaN" is a number) would be 0: a % by zero for a clock, a busy loop otherwise.
                                val wait = (g.every * 1000).toLong().coerceAtLeast(100)
                                // Back to what it last ran, within the interval (a tab shown again): its value still holds.
                                if (due || arg != last || System.currentTimeMillis() >= lastAt + lastWait) {
                                    last = arg
                                    lastAt = System.currentTimeMillis()
                                    // A failed fetch (offline, or a server's rate limit) is tried again in half a minute.
                                    lastWait = if (update(slot, g)) wait else minOf(wait, RETRY_MS)
                                }
                                // Clocks tick on the second (or minute) boundary so displays change in step;
                                // other intervals count from the start of the update, however long it took.
                                val target = if (g.kind == "clock") wait - System.currentTimeMillis() % wait else (lastAt + lastWait - System.currentTimeMillis()).coerceAtLeast(0)
                                // Sleeps until the interval is up, a refresh() wakes it early, or what it runs changes
                                // (a coin tapped, a tab opened): then it runs at once, not at the next interval.
                                val changed = built?.let { snapshotFlow { eval(it, root, g.name) }.filter { v -> v != arg }.map { false } } ?: emptyFlow()
                                due = withTimeoutOrNull(target) { merge(refreshes.filter { it != seen }.map { true }, changed).first() } ?: true
                                // A command built from something that changes all the time runs at most once a second.
                                if (built != null && !due) delay((lastAt + MIN_RERUN_MS - System.currentTimeMillis()).coerceAtLeast(0))
                            }
                        }
                    }
                }
        }
    }

    /**
     * Runs [block] until it ends or the page stops being live; true if it ended
     * on its own. Its failures are reported, never thrown.
     */
    private suspend fun untilPaused(
        active: () -> Boolean,
        g: Global.Source,
        block: suspend () -> Unit,
    ): Boolean {
        var ended = false
        coroutineScope {
            val reader =
                launch {
                    try {
                        block()
                    } catch (e: Exception) {
                        // Pausing cancels the listener; that's no error.
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        report("${g.name}: ${e.message ?: e}")
                    }
                    ended = true
                }
            val pause = launch { snapshotFlow { active() }.first { !it } }
            select {
                reader.onJoin {}
                pause.onJoin {}
            }
            reader.cancel()
            pause.cancel()
        }
        return ended
    }

    /** Per trigger, what it last saw: the time an `at` ran for, whether a `when` held, the value an `on` had. */
    private val seen = arrayOfNulls<Value>(program.triggers.size)

    private val triggerRuns = RunLimit(program.triggers.size, MAX_TRIGGER_RUNS)

    /**
     * Runs the triggers. `every` ticks only while the page is live, like the
     * sources; `at`, `when` and `on` react whenever what they read changes.
     */
    private fun startTriggers(
        scope: CoroutineScope,
        active: () -> Boolean,
    ) {
        program.triggers.forEachIndexed { i, t ->
            jobs +=
                scope.launch {
                    when (t.kind) {
                        TriggerKind.EVERY -> {
                            // At least a second, as the compiler means it to be: a NaN interval would spin the main thread.
                            val ms = (t.expr.const!!.num()!! * 1000).toLong().coerceAtLeast(1000)
                            while (isActive) {
                                snapshotFlow { active() }.first { it }
                                delay(ms)
                                if (active()) fire(i, t)
                            }
                        }
                        TriggerKind.AT ->
                            snapshotFlow { eval(t.expr, root, "at") }.collectLatest { v ->
                                val at = v.num()?.takeIf { it > 0 } ?: return@collectLatest
                                if (seen[i] == Num(at)) return@collectLatest
                                // In steps: the clock behind delay stops while the phone sleeps, so one long wait would end late.
                                while (true) {
                                    val left = (at * 1000).toLong() - System.currentTimeMillis()
                                    if (left <= 0) break
                                    delay(left.coerceAtMost(30_000))
                                }
                                seen[i] = Num(at)
                                fire(i, t)
                            }
                        TriggerKind.WHEN ->
                            snapshotFlow { eval(t.expr, root, "when").truthy }.collect { now ->
                                val was = seen[i]?.truthy ?: false
                                seen[i] = bool(now)
                                if (now && !was) fire(i, t)
                            }
                        TriggerKind.ON ->
                            snapshotFlow { eval(t.expr, root, "on") }.collect { v ->
                                // Null is "still loading": neither a change nor something to compare with.
                                if (v == Null) return@collect
                                val old = seen[i]
                                seen[i] = v
                                if (old != null && old != v) fire(i, t, listOf(v, old))
                            }
                    }
                }
        }
    }

    private fun fire(
        i: Int,
        t: Trigger,
        values: List<Value> = emptyList(),
    ) {
        when (triggerRuns.check(i, System.nanoTime() / 1_000_000)) {
            RunLimit.Verdict.RUN -> {}
            RunLimit.Verdict.JUST_HELD -> return report("${t.kind.word} ran over $MAX_TRIGGER_RUNS times in a second; does it change what it watches?")
            RunLimit.Verdict.HELD -> return
        }
        inTrigger = true
        try {
            t.action.run(root, values)
        } finally {
            inTrigger = false
        }
    }

    /** A trigger's statements are running, not a tap's; see [sound]. */
    private var inTrigger = false

    /** sound(...)s asked for so far: a clip still downloading doesn't play over a later one. */
    private var sounds = 0

    /**
     * Plays [src] (a URL, an asset or a guest file), or stops with "stop". A tile plays only when
     * tapped: a trigger plays only while the app or the widget's screen is showing, so nothing on
     * the home screen makes a sound on its own.
     */
    private fun sound(src: String) {
        val n = ++sounds
        if (src == "stop") return services.sound(this, null, null)
        if (inTrigger && !foreground()) return report("sound(...) in a trigger plays only while the app or screen is open")
        val scope = scope ?: return
        scope.launch {
            val (file, bytes) =
                withContext(Dispatchers.IO) {
                    when {
                        src.startsWith("https://") -> services.remoteFile(src) to null
                        src.startsWith("asset:") -> null to (program.globals.firstOrNull { it.name == src.removePrefix("asset:") } as? Global.Asset)?.bytes
                        else -> services.guestFile(src).takeIf { it.isFile } to null
                    }
                }
            if (n != sounds) return@launch
            if (file == null && bytes == null) return@launch report("sound '$src' couldn't be ${if (src.startsWith("https://")) "downloaded" else "found"}")
            services.sound(this@WidgetInstance, file, bytes) { report("sound '$src' couldn't play: $it") }
        }
    }

    /** `owl-send <file> set name=value`: sets a state from outside; returns what went wrong, or null. */
    fun setState(
        name: String,
        value: Value,
    ): String? {
        val i = program.globals.indexOfFirst { it.name == name }
        if (i < 0 || program.globals[i] !is Global.State) {
            val states = program.globals.filterIsInstance<Global.State>().joinToString { it.name }
            return if (i < 0) "no state '$name' (states: ${states.ifEmpty { "none" }})" else "'$name' isn't a state, so it can't be set (states: ${states.ifEmpty { "none" }})"
        }
        write(i, value)
        refresh()
        return null
    }

    /** Whether an app's Back goes up a level inside it now, because its `back` holds. */
    fun canGoUp() = program.back?.let { eval(it, root, "back").truthy } == true

    /** Runs an app's onBack while its `back` holds; false when Back should leave the app instead. */
    fun goUp(): Boolean {
        if (!canGoUp()) return false
        program.onBack?.run(root, emptyList())
        return true
    }

    /** `owl-send <file> refresh`. */
    fun refreshNow() = refresh()

    /**
     * Terminal nodes' sessions, by node. They keep running while the tile is
     * off screen, and end with the widget or when their command changes.
     */
    private val terminals = HashMap<Any, EmbeddedTerminal>()

    fun terminal(
        node: Any,
        command: String?,
        fontSize: Double?,
    ): EmbeddedTerminal {
        terminals[node]?.let { if (it.command == command && it.fontSize == fontSize) return it else it.close() }
        return services.terminal(command, fontSize).also { terminals[node] = it }
    }

    /** [term] is one of this widget's terminals. */
    fun owns(term: EmbeddedTerminal?) = term != null && term in terminals.values

    fun stop() {
        services.sound(this, null, null)
        jobs.forEach { it.cancel() }
        jobs.clear()
        terminals.values.forEach { it.close() }
        terminals.clear()
        // A change made during the write interval would otherwise be lost.
        if (persisted.isNotEmpty()) save(persisted.map { read(it) })
    }

    /** Runs [g] and writes its value; false when an http fetch failed, so it's tried again soon. */
    private suspend fun update(
        slot: Int,
        g: Global.Source,
    ): Boolean {
        var fetched = true
        val value: Value =
            when (g.kind) {
                "clock" -> Num(Math.floor(System.currentTimeMillis() / 1000.0))
                "cmd", "http" -> {
                    val target = eval(g.arg!!, root, g.name).toString()
                    try {
                        val r =
                            if (g.kind == "http") {
                                val headers = (g.headers?.let { eval(it, root, g.name) } as? Obj)?.fields.orEmpty().mapValues { it.value.toString() }
                                services.http(target, headers, g.timeout)
                            } else {
                                services.cmd(target, g.timeout)
                            }
                        val out = if (g.json) Json.parseOrText(r.out) else Str(r.out)
                        fetched = g.kind != "http" || ok(g, r)
                        if (g.status) {
                            Obj(mapOf("out" to out, "err" to Str(r.err), "code" to Num(r.code.toDouble()), "ok" to bool(ok(g, r))))
                        } else {
                            reportFailure(g, r)
                            out
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        report("${g.name}: ${e.message}")
                        return g.kind != "http"
                    }
                }
                else ->
                    try {
                        services.android(g.kind.removePrefix("android."))
                    } catch (e: RuntimeException) {
                        report("${g.name}: ${e.message ?: e}")
                        return true
                    }
            }
        write(slot, value)
        sample(g)
        return fetched
    }

    /**
     * A timeout, or a non-zero exit that printed to stderr. A quiet non-zero
     * exit is normal (grep finding nothing, pacman -Qu with no updates).
     */
    private fun ok(
        g: Global.Source,
        r: ShellResult,
    ) = if (g.kind == "http") r.code in 200..299 else r.code == 0

    private fun reportFailure(
        g: Global.Source,
        r: ShellResult,
    ) {
        when {
            g.kind == "http" -> if (!ok(g, r)) report("${g.name}: ${httpFailure(r)}")
            r.timedOut -> report("${g.name}: timed out after ${g.timeout}s; add `timeout 30s` to the source if it needs longer")
            r.code != 0 && r.err.isNotBlank() -> report("${g.name}: ${commandFailure(r)}")
        }
    }

    private fun set(
        slot: Int,
        g: Global.Source,
        line: String,
    ) {
        write(slot, if (g.json) Json.parseOrText(line) else Str(line))
        sample(g)
    }

    /** history()/prev() lets take a sample whenever any source changes. */
    private fun sample(g: Global.Source) {
        updates.intValue++
        images.recheck(g.kind)
        program.globals.forEachIndexed { i, g ->
            if (g !is Global.Sampled) return@forEachIndexed
            val v = eval(g.of, root, g.name)
            if (v == Null) return@forEachIndexed
            if (g.prev) {
                val last = lastSample[i]
                if (last != v) {
                    write(i, last ?: Null)
                    lastSample[i] = v
                }
            } else {
                val list = (read(i) as? Lst)?.items.orEmpty()
                write(i, Lst((list + v).takeLast(g.keep)))
            }
        }
    }

    private val lastSample = HashMap<Int, Value>()

    companion object {
        private const val SLOW_DRAW_NANOS = 4_000_000.0

        /** [parkedStart] while the frame clock runs. */
        private const val RUNNING = Long.MIN_VALUE

        /** Draws at the start that aren't timed: about two seconds at full rate. */
        private const val WARMUP_DRAWS = 120

        /** The least time between two runs of a source whose command or URL is built from state. */
        private const val MIN_RERUN_MS = 1_000L

        /** Runs per trigger per second before it's held back. */
        private const val MAX_TRIGGER_RUNS = 10

        /** Distinct sprites kept per widget. */
        private const val MAX_SPRITES = 64

        /** How soon a failed http fetch is tried again, if its interval is longer. */
        private const val RETRY_MS = 30_000L

        /** How long a post(...) may take: a sign-in or a change, sent once and never retried. */
        private const val POST_TIMEOUT_S = 30
    }
}

/** A request that failed, for a report: its status, or that it never got one. */
private fun httpFailure(r: ShellResult) = "${if (r.code > 0) "HTTP ${r.code}" else "request failed"}: ${r.err}"

/** A command that failed, for a report: its exit code and the first lines it printed to stderr. */
private fun commandFailure(r: ShellResult) = "exit ${r.code}: ${r.err.lines().take(3).joinToString(" / ")}"
