package dev.omalauncher.widgets.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import dev.omalauncher.ui.decodeSampled
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.flow.first
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

    /** An HTTP GET from Android, not the guest: [ShellResult.code] is the HTTP status, or -1 if the request failed. */
    suspend fun http(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Int,
    ): ShellResult

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
    /** Decoded images by src, filled in off the main thread; see [image]. */
    private val images = mutableStateMapOf<String, ImageBitmap?>()

    /** Modification times the images were decoded at; written by the decoding thread. */
    private val imageStamps = ConcurrentHashMap<String, Long>()

    /** Srcs asked for so far; main thread only. */
    private val imageRequests = HashSet<String>()

    /** Where the sources and image decodes run; set by [start]. */
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
    private val persisted = program.globals.indices.filter { i -> program.globals[i].let { (it is Global.State && it.persist) || (it is Global.Sampled && it.persist) } }

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
        val saved = runCatching { Json.parse(file.readText()) }.getOrNull() as? Obj ?: return
        val values = saved.fields["values"] as? Obj ?: return
        val age = System.currentTimeMillis() / 1000.0 - (saved.fields["savedAt"]?.num() ?: 0.0)
        // The fastest polling source sets how long a history takes to fill; one older than that is all gap.
        val step = program.globals.filterIsInstance<Global.Source>().filter { it.kind != "stream" }.minOfOrNull { it.every }
        for (i in persisted) {
            val g = program.globals[i]
            val v = values.fields[g.name] ?: continue
            when (g) {
                is Global.State -> write(i, v)
                is Global.Sampled -> {
                    val items = (v as? Lst)?.items ?: continue
                    if (step == null || age < step * g.keep) write(i, Lst(items.takeLast(g.keep)))
                }
                else -> {}
            }
        }
        lastSaved = persisted.map { read(it) }
    }

    /** Writes the persisted values if they changed since the last write. */
    private fun save(values: List<Value>) {
        val file = savedFile ?: return
        if (values == lastSaved) return
        val fields = LinkedHashMap<String, String>()
        persisted.forEachIndexed { k, i -> Json.write(values[k])?.let { fields[program.globals[i].name] = it } }
        // "v" is the format version; values are matched by name, so renamed ones start over.
        val text = "{\"v\":1,\"savedAt\":${System.currentTimeMillis() / 1000},\"values\":{" + fields.entries.joinToString(",") { (n, v) -> "${Json.write(Str(n))}:$v" } + "}}"
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
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

    override fun tile(name: String): Value {
        val v =
            when (name) {
                "linux" -> return bool(linux())
                "corners" -> return Num(LauncherStyle.cornerScale.toDouble())
                "screenWidth" -> screenSize.value[0]
                "screenHeight" -> screenSize.value[1]
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
            if (r.code != 0 && r.err.isNotBlank()) report("run: exit ${r.code}: ${r.err.lines().take(3).joinToString(" / ")}")
            write(slot, Str(r.out))
            refresh()
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

    /**
     * The image for [src], or null until it's decoded, which happens off the
     * main thread and at most at widget size. A guest file is checked again
     * (a stat; decoded only if it changed) whenever a source updates, so a
     * file a command rewrites (a cover, a chart) shows its new content. Safe
     * to call while drawing: it only reads state.
     */
    fun image(src: String): ImageBitmap? {
        // The launcher already holds every app's icon, as snapshot state: reading it redraws once it loads.
        if (src.startsWith("app:")) return services.appIcon(src.removePrefix("app:"))
        // Album art changes with every track: Android holds the current one. Only its wrapper is
        // kept, so asking again for the same art gives the same image and redraws nothing.
        if (src.startsWith("media:")) {
            val art = services.androidImage(src) ?: return null
            mediaArt?.takeIf { it.first === art }?.let { return it.second }
            return art.asImageBitmap().also { mediaArt = art to it }
        }
        if (src !in imageRequests) {
            // Not from inside a draw: that would write state while drawing.
            scope?.launch {
                // A src that changes (a cover per track) mustn't keep every image it ever showed.
                if (imageRequests.size >= MAX_IMAGES) {
                    imageRequests.clear()
                    images.clear()
                    imageStamps.clear()
                }
                if (imageRequests.add(src)) loadImage(src)
            }
        }
        return images[src]
    }

    /** The album art last asked for, and its ImageBitmap; main thread only. */
    private var mediaArt: Pair<Bitmap, ImageBitmap>? = null

    /** When guest image files were last checked for changes, in elapsed milliseconds. */
    private var imagesChecked = 0L

    private fun loadImage(src: String) {
        val scope = scope ?: return
        scope.launch {
            val file = if (src.startsWith("asset:")) null else services.guestFile(src)
            val decoded =
                withContext(Dispatchers.IO) {
                    val stamp = file?.lastModified() ?: 0L
                    if (imageStamps[src] == stamp) return@withContext null
                    imageStamps[src] = stamp
                    runCatching {
                        if (file == null) {
                            val bytes = (program.globals.firstOrNull { it.name == src.removePrefix("asset:") } as? Global.Asset)?.bytes
                            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        } else {
                            file.takeIf { it.length() < 8 shl 20 }?.let { decodeSampled(it, MAX_IMAGE_SIDE / 2) }
                        }
                        // A small PNG can hold a huge flat image; drawing one over Android's limit throws on every frame.
                    }.getOrNull()?.takeIf { it.byteCount <= MAX_IMAGE_BYTES }.let { Result.success(it) }
                } ?: return@launch
            val bitmap = decoded.getOrNull()
            if (bitmap == null) report("image '$src' couldn't be loaded")
            images[src] = bitmap?.asImageBitmap()
        }
    }

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
    fun loaded() = program.globals.indices.all { program.globals[it] !is Global.Source || read(it) != Null }

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
                                val start = System.currentTimeMillis()
                                update(slot, g)
                                // An interval of NaN ("NaN" is a number) would be 0: a % by zero for a clock, a busy loop otherwise.
                                val wait = (g.every * 1000).toLong().coerceAtLeast(100)
                                // Clocks tick on the second (or minute) boundary so displays change in step;
                                // other intervals count from the start of the update, however long it took.
                                val target = if (g.kind == "clock") wait - System.currentTimeMillis() % wait else (start + wait - System.currentTimeMillis()).coerceAtLeast(0)
                                // Sleeps until the interval is up, or a refresh() wakes it early.
                                withTimeoutOrNull(target) { refreshes.first { it != seen } }
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

    /** Per trigger, when its current one-second window started and how often it ran in it. */
    private val windows = LongArray(program.triggers.size)
    private val runs = IntArray(program.triggers.size)

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
                        "every" -> {
                            // At least a second, as the compiler means it to be: a NaN interval would spin the main thread.
                            val ms = (t.expr.const!!.num()!! * 1000).toLong().coerceAtLeast(1000)
                            while (isActive) {
                                snapshotFlow { active() }.first { it }
                                delay(ms)
                                if (active()) fire(i, t)
                            }
                        }
                        "at" ->
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
                        "when" ->
                            snapshotFlow { eval(t.expr, root, "when").truthy }.collect { now ->
                                val was = seen[i]?.truthy ?: false
                                seen[i] = bool(now)
                                if (now && !was) fire(i, t)
                            }
                        "on" ->
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
        // A trigger that changes what it watches would otherwise loop for as long as the widget runs.
        val now = System.nanoTime() / 1_000_000
        if (now - windows[i] > 1000) {
            windows[i] = now
            runs[i] = 0
        }
        if (++runs[i] > MAX_TRIGGER_RUNS) {
            if (runs[i] == MAX_TRIGGER_RUNS + 1) report("${t.kind} ran over $MAX_TRIGGER_RUNS times in a second; does it change what it watches?")
            return
        }
        t.action.run(root, values)
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
        jobs.forEach { it.cancel() }
        jobs.clear()
        terminals.values.forEach { it.close() }
        terminals.clear()
        // A change made during the write interval would otherwise be lost.
        if (persisted.isNotEmpty()) save(persisted.map { read(it) })
    }

    private suspend fun update(
        slot: Int,
        g: Global.Source,
    ) {
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
                        if (g.status) {
                            Obj(mapOf("out" to out, "err" to Str(r.err), "code" to Num(r.code.toDouble()), "ok" to bool(ok(g, r))))
                        } else {
                            reportFailure(g, r)
                            out
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        report("${g.name}: ${e.message}")
                        return
                    }
                }
                else ->
                    try {
                        services.android(g.kind.removePrefix("android."))
                    } catch (e: RuntimeException) {
                        report("${g.name}: ${e.message ?: e}")
                        return
                    }
            }
        write(slot, value)
        sample(g)
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
            g.kind == "http" -> if (!ok(g, r)) report("${g.name}: ${if (r.code > 0) "HTTP ${r.code}" else "request failed"}: ${r.err}")
            r.timedOut -> report("${g.name}: timed out after ${g.timeout}s; add `timeout 30s` to the source if it needs longer")
            r.code != 0 && r.err.isNotBlank() -> report("${g.name}: exit ${r.code}: ${r.err.lines().take(3).joinToString(" / ")}")
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
        // Guest image files are checked again (a stat each) after guest commands, which may have
        // rewritten them; after a clock tick or Android data, only every few seconds.
        val now = android.os.SystemClock.elapsedRealtime()
        if (g.kind == "cmd" || g.kind == "stream" || g.kind == "file" || now - imagesChecked >= IMAGE_RECHECK_MS) {
            imagesChecked = now
            for (src in imageRequests) if (!src.startsWith("asset:")) loadImage(src)
        }
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

        /** How often guest image files are checked for changes when no guest command ran. */
        private const val IMAGE_RECHECK_MS = 5_000L

        /** Runs per trigger per second before it's held back. */
        private const val MAX_TRIGGER_RUNS = 10

        /** Distinct sprites kept per widget. */
        private const val MAX_SPRITES = 64

        /** Distinct images kept per widget. */
        private const val MAX_IMAGES = 32

        /** Images are decoded no larger than needed for a tile this big, in pixels. */
        private const val MAX_IMAGE_SIDE = 1080

        /** Decoded images larger than this are dropped; Android refuses to draw past 100 MB. */
        private const val MAX_IMAGE_BYTES = 32 shl 20
    }
}
