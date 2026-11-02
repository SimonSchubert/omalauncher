package dev.omalauncher.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.AppLanguage
import dev.omalauncher.R
import dev.omalauncher.linux.AGENTS
import dev.omalauncher.linux.Agent
import dev.omalauncher.linux.AgentStore
import dev.omalauncher.linux.JobState
import dev.omalauncher.linux.Proot
import dev.omalauncher.linux.killTree
import dev.omalauncher.ui.Speaker
import dev.omalauncher.ui.Speech
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * A spoken conversation with a coding agent in Linux: one agent CLI kept running through an [AgentLink], its reply
 * said sentence by sentence by [Speaker] as it streams in, its tool calls shown as steps, its questions as
 * approvals, the conversation kept per agent and continued when it's opened again. It belongs to the launcher, not
 * to a screen: leaving the voice screen doesn't stop a task. The agent is stopped when the screen has been closed
 * and nothing's happening for [IDLE_STOP_MS]. Main thread.
 */
class VoiceSession(
    private val context: Context,
    private val proot: Proot,
    private val agents: AgentStore,
    private val speaker: Speaker,
) {
    enum class Phase { Off, Starting, Idle, Thinking, Working, Approval }

    /** A line of the conversation: [who] is "you", "agent", "note" or "image" ([text] then a file in [dir]). */
    data class Entry(
        val who: String,
        val text: String,
    )

    /** A question: [label] as a step is shown, [said] as what it wants to do ("read x.png"), [detail] whole. */
    data class Approval(
        val id: String,
        val act: Act,
        val label: String,
        val said: String,
        val detail: String,
        val key: String,
    )

    /** The agents that can talk: installed, and with a link. */
    val voiceAgents: List<Agent> get() = agents.installed.filter { it.bin in VOICE_LINKS }

    /** Every agent with a link, installed or not, in Omarchy's order: the ones to pick from, installed when picked. */
    val linked: List<Agent> get() = AGENTS.filter { it.bin in VOICE_LINKS }

    /** Whether [agent] can talk here: one with a link. */
    fun talksTo(agent: Agent?) = agent != null && agent.bin in VOICE_LINKS

    /** An agent picked that wasn't installed: installing, or failed to (its log in [installState]); null otherwise. */
    var installing by mutableStateOf<Agent?>(null)
        private set

    val installState: JobState get() = installing?.let { agents.states[it.bin] } ?: JobState.Idle

    private val scope = MainScope()

    /** Installs [to], picked though it isn't there, and talks to it once it is (not while a task runs). */
    fun install(to: Agent) {
        installing = to
        if (agents.states[to.bin] is JobState.Working) return
        scope.launch {
            if (!agents.install(to) || installing != to) return@launch
            installing = null
            if (agent == null) {
                wanted = to
                if (screens > 0) wake()
            } else if (screens > 0) {
                switchTo(to)
            } else {
                prefer(to)
            }
        }
    }

    /**
     * Whether the AI bar's mic opens the voice screen for [agent]: it talks here, and the phone can hear the user.
     * Without a speech recognizer there'd be no way to say anything, and the mic stays the microphone.
     */
    fun opensFor(agent: Agent?) = talksTo(agent) && Speech.available(context)

    /** The agent the screen opens with next, from the AI bar; null for the last one used. */
    private var wanted: Agent? = null

    /** Talks to [to] when the screen opens, as the AI bar asks: now, or as it starts. Not while a task runs. */
    fun prefer(to: Agent) {
        if (!talksTo(to) || busy) return
        if (agent == null) {
            wanted = to
        } else if (to.bin != agent?.bin) {
            // Started as the screen opens ([wake]), after [openedAnew] has had its say: not twice.
            stopAgent()
            load(to)
            saveSettings()
            if (screens > 0) start()
        }
    }

    var agent by mutableStateOf<Agent?>(null)
        private set
    var phase by mutableStateOf(Phase.Off)
        private set
    val transcript = mutableStateListOf<Entry>()

    /** The reply being written now, before it joins [transcript]. */
    var reply by mutableStateOf("")
        private set

    /** This task's tool calls, the latest last. */
    val steps = mutableStateListOf<String>()

    /** The agent's questions waiting for an answer; the first is shown. */
    val approvals = mutableStateListOf<Approval>()

    /** Something to tell the user, like an error; null when there's nothing. */
    var notice by mutableStateOf<String?>(null)

    /** Whether [notice] says the agent isn't signed in ("Not logged in · Please run /login"), for a way to. */
    val needsLogin get() = notice?.let(LOGIN::containsMatchIn) == true

    /** Signing in, in the agent's own terminal: this one stops, so the next prompt starts it signed in. */
    fun loggingIn() {
        stopAgent()
        notice = null
    }

    /** When the task in progress started (ms), for the time shown. */
    var since by mutableLongStateOf(0L)
        private set

    /** Prompts sent and not answered yet. */
    var turns by mutableIntStateOf(0)
        private set
    val busy get() = turns > 0

    /** Every question answered yes without asking. */
    var approveAll by mutableStateOf(false)
        private set

    /** The user types and reads instead of talking: nothing is said aloud. */
    var typing by mutableStateOf(false)
        private set

    /** Whether the agent was last told the user types: it starts out knowing it's a voice conversation. */
    private var toldTyping = false

    /** A conversation put aside by a new one, which [resume] brings back. */
    var canResume by mutableStateOf(false)
        private set

    /** Opened anew: the next [wake] puts the conversation aside and starts a new one. */
    private var anew = false

    /** Prompts sent since the agent started, for [VOICE_REMINDER]. */
    private var sent = 0

    private var link: AgentLink? = null
    private var process: Process? = null
    private var session: String? = null
    private val always = HashSet<String>()
    private var buffer = ""
    private var afterTool = false
    private var lastSaid = 0L
    private var lastEvent = 0L
    private var doneAt = 0L

    /** When the turn in progress was asked, and whether anything came back since. */
    private var asked = 0L
    private var heardBack = false
    private var restarts = 0
    private var screens = 0
    private val complaints = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())

    /** Writes to the agent off the main thread, in order: a full pipe mustn't freeze the screen. */
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "voice-write").apply { isDaemon = true } }

    /**
     * Files, the trace and stopping the agent, in order, apart from [writer]: an agent that stopped reading blocks
     * its writes, and that mustn't hold up saving the conversation, [resume]'s wait for it, or the kill that ends it.
     */
    private val files = Executors.newSingleThreadExecutor { Thread(it, "voice-files").apply { isDaemon = true } }

    val dir get() = File(context.filesDir, "voice")

    /**
     * What went to and came from the agent, for finding out why it misbehaves: /tmp/voice-agent.log in Linux,
     * readable from a terminal, cut in half when it passes 1 MB; /tmp is emptied as the launcher starts.
     */
    private fun trace(
        way: Char,
        line: String,
    ) = files.execute {
        runCatching {
            val log = proot.hostFile("/tmp/voice-agent.log")
            if (log.length() > 1_000_000) log.writeText(log.readText().takeLast(500_000))
            log.appendText("${System.currentTimeMillis() % 100_000_000} $way ${line.take(4000)}\n")
        }
    }
    private val settingsFile get() = File(dir, "settings.json")

    /** The language it listens and reads aloud in, a tag ("de"); null for the launcher's. */
    var language by mutableStateOf<String?>(null)
        private set

    /** The voice replies are read in, by name; null for the engine's choice for [language]. */
    var voice by mutableStateOf<String?>(null)
        private set

    /** How fast replies are read: 1 is the engine's normal speed; a little faster by default, as replies can be long. */
    var rate by mutableFloatStateOf(DEFAULT_RATE)
        private set

    init {
        readJson(settingsFile)?.let { s ->
            // Saved in three letters ("deu") before Speaker named the engine's languages in two.
            language = s.optString("language").ifEmpty { null }?.let(AppLanguage::short)
            voice = s.optString("voice").ifEmpty { null }
            rate = s.optDouble("rate", DEFAULT_RATE.toDouble()).toFloat().takeIf { it in 0.5f..2f } ?: DEFAULT_RATE
        }
        speaker.use(language, voice, rate)
    }

    /** Listens and reads aloud in [language] (null for the launcher's), with [voice], at [rate]. */
    fun useVoice(
        language: String?,
        voice: String?,
        rate: Float,
    ) {
        this.language = language
        this.voice = voice
        this.rate = rate
        speaker.use(language, voice, rate)
        saveSettings()
    }

    private fun transcriptFile(bin: String) = File(dir, "transcript-$bin.json")

    private fun sessionFile(bin: String) = File(dir, "session-$bin.json")

    private fun previousTranscriptFile(bin: String) = File(dir, "previous-transcript-$bin.json")

    private fun previousSessionFile(bin: String) = File(dir, "previous-session-$bin.json")

    // ---------- the screen

    /** The voice screen is in sight: the agent starts (or keeps running). */
    fun opened() {
        screens++
        main.removeCallbacks(idleStop)
        wake()
    }

    /** Talks to an agent that's there: the one it had, or one installed since; nothing when there's none. */
    fun wake() {
        // Removed since it last talked: put it aside, and pick from the ones there are now.
        agent?.let { a ->
            if (voiceAgents.none { it.bin == a.bin }) {
                stopAgent()
                agent = null
                transcript.clear()
                notice = null
            }
        }
        if (agent == null) {
            val settings = readJson(settingsFile)
            approveAll = settings?.optBoolean("approveAll") == true
            typing = settings?.optBoolean("typing") == true
            val pick = wanted?.takeIf { it in voiceAgents } ?: voiceAgents.firstOrNull { it.bin == settings?.optString("agent") } ?: voiceAgents.firstOrNull { it.bin == agents.default?.bin } ?: voiceAgents.firstOrNull()
            wanted = null
            pick?.let { load(it, anyAge = anew) }
        }
        // Kept until there's an agent: in a fresh process the installed agents may not be known yet, and the
        // conversation would come back with the next wake.
        if (anew && agent != null) {
            anew = false
            if (!busy && approvals.isEmpty()) putAside()
        }
        // No agent that talks: the screen says which to install, and nothing starts.
        if (agent != null && process?.isAlive != true) start()
    }

    /** The voice screen went out of sight: the agent stops after [IDLE_STOP_MS] with nothing going on. */
    fun closed() {
        screens = (screens - 1).coerceAtLeast(0)
        scheduleIdleStop()
    }

    // ---------- what the user does

    fun ask(text: String) {
        val said = text.trim()
        if (said.isEmpty()) return
        if (agent == null) return
        notice = null
        // An answer to the question on screen: yes, no, or what to do instead.
        approvals.firstOrNull()?.let { a ->
            transcript += Entry("you", said)
            saveTranscript()
            when {
                YES.containsMatchIn(said) -> answer(a.id, true)
                NO.matches(said) -> answer(a.id, false)
                else -> answer(a.id, false, instead = said)
            }
            return
        }
        if (process?.isAlive != true) start()
        // It didn't start: start() said why, and there's no one to wait for.
        if (process == null) return
        val fresh = turns == 0
        if (fresh) {
            steps.clear()
            since = System.currentTimeMillis()
        }
        turns++
        transcript += Entry("you", said)
        saveTranscript()
        saveSession()
        phase = Phase.Thinking
        lastEvent = System.currentTimeMillis()
        if (fresh) {
            asked = lastEvent
            heardBack = false
        }
        link?.prompt(withNote(said))
    }

    /**
     * [said] with what the agent should know first: that the user switched between typing and talking, and, for
     * an agent that got [VOICE_PROMPT] only with its first prompt (ACP), a reminder to keep it short now and then.
     */
    private fun withNote(said: String): String {
        sent++
        val note =
            when {
                typing != toldTyping -> if (typing) TYPING_NOTE else TALKING_NOTE
                !typing && link?.instructed == false && sent % REMIND_EVERY == 0 -> VOICE_REMINDER
                else -> null
            }
        toldTyping = typing
        return if (note == null) said else "($note)\n\n$said"
    }

    /** Types and reads instead of talking, or talks again; the agent hears of it with the next prompt. */
    fun type(on: Boolean) {
        if (on == typing) return
        typing = on
        if (on) speaker.stop()
        saveSettings()
    }

    /** Stops the task: the voice, the turn and anything waiting. */
    fun stop() {
        speaker.stop()
        for (a in approvals.toList()) answer(a.id, false)
        if (turns > 0) link?.cancel()
        turns = 0
        endReply()
        phase = if (process?.isAlive == true) Phase.Idle else Phase.Off
        scheduleIdleStop()
    }

    fun answer(
        id: String,
        allow: Boolean,
        always: Boolean = false,
        instead: String? = null,
    ) {
        val a = approvals.firstOrNull { it.id == id } ?: return
        approvals.remove(a)
        if (always) this.always += a.key
        val what = if (a.act.verb == Act.Verb.Run) a.act.what else a.label
        transcript += Entry("note", (if (allow) "✓ " else "✗ ") + what + if (always) " · " + str(R.string.voice_always) else "")
        saveTranscript()
        val took = link?.answer(id, allow, always, instead) ?: true
        if (!took && instead != null) link?.prompt(instead)
        // Questions that came while this one was up and that "always" now covers.
        for (other in approvals.toList()) if (approveAll || Steps.covered(other.key, this.always)) answer(other.id, true)
        settle()
        approvals.firstOrNull()?.let(::announce)
    }

    fun approveEverything(on: Boolean) {
        approveAll = on
        saveSettings()
        transcript += Entry("note", if (on) str(R.string.voice_note_allow_all, name) else str(R.string.voice_note_ask_again))
        saveTranscript()
        if (on) for (a in approvals.toList()) answer(a.id, true)
    }

    /** Talks to [to] instead: the conversation so far is put aside and [to]'s comes back. */
    fun switchTo(to: Agent) {
        installing = null
        if (to.bin == agent?.bin || busy) return
        stopAgent()
        load(to)
        saveSettings()
        start()
    }

    /**
     * Opened from the drawer, the AI bar or a terminal, not back from Recents: a new conversation, the last one put
     * aside for [resume]. Not while a task runs, nor when nothing has been said yet (the agent keeps running then).
     * Called before the screen's [opened], so the agent doesn't start with the old conversation first.
     */
    fun openedAnew() {
        anew = true
        if (screens > 0) wake()
    }

    /** A new conversation with the same agent; the last one can be resumed. */
    fun startOver() {
        if (agent == null || busy) return
        putAside()
        if (process?.isAlive != true) start()
    }

    /**
     * Back to the conversation put aside. The one it replaces is put aside in turn when anything was said in it, so
     * it can be gone back to.
     */
    fun resume() {
        val a = agent ?: return
        if (busy || !canResume) return
        // Its files are written in order on [writer]: wait for any still on their way.
        runCatching { files.submit {}.get() }
        val last = readJson(previousSessionFile(a.bin))?.optString("id")?.ifEmpty { null }
        val lines = readLines(previousTranscriptFile(a.bin))
        if (last == null) {
            canResume = false
            return
        }
        val keep = session?.takeIf { said() }
        stopAgent()
        if (keep != null) {
            keepPrevious(a, keep, transcript.toList())
        } else {
            files.execute {
                previousSessionFile(a.bin).delete()
                previousTranscriptFile(a.bin).delete()
            }
            canResume = false
        }
        transcript.clear()
        transcript += lines
        session = last
        notice = null
        saveTranscript()
        saveSession()
        start()
    }

    /** Whether the user said anything in this conversation: one with only notes isn't worth keeping. */
    private fun said() = transcript.any { it.who == "you" }

    /** The conversation aside for [resume] and a new one begun; the agent is stopped when it was in the old one. */
    private fun putAside() {
        val a = agent ?: return
        if (!said()) return
        val id = session
        stopAgent()
        if (id != null) keepPrevious(a, id, transcript.toList())
        transcript.clear()
        saveTranscript()
        files.execute { sessionFile(a.bin).delete() }
        session = null
        notice = null
    }

    private fun keepPrevious(
        a: Agent,
        id: String,
        lines: List<Entry>,
    ) {
        writeLines(previousTranscriptFile(a.bin), lines)
        writeFile(previousSessionFile(a.bin), json("id" to id, "at" to System.currentTimeMillis()).toString())
        canResume = true
    }

    // ---------- the agent

    /** [to]'s conversation: the last one when it's recent, or, with [anyAge] (to be put aside), whatever its age. */
    private fun load(
        to: Agent,
        anyAge: Boolean = false,
    ) {
        agent = to
        always.clear()
        // The last agent's trouble isn't this one's.
        notice = null
        transcript.clear()
        canResume = previousSessionFile(to.bin).isFile
        val last = readJson(sessionFile(to.bin))
        val fresh = last != null && (anyAge || System.currentTimeMillis() - last.optLong("at") < RESUME_MS)
        session = if (fresh) last!!.optString("id").ifEmpty { null } else null
        if (fresh) {
            transcript += readLines(transcriptFile(to.bin))
            if (last!!.optBoolean("busy")) transcript += Entry("note", str(R.string.voice_note_task_stopped))
        }
    }

    private fun start() {
        val a = agent ?: return
        // Lines go to the process this link started, never to one started after it (a switch, a restart).
        var to: Process? = null
        val l = VOICE_LINKS.getValue(a.bin).invoke { line -> to?.let { p -> writer.execute { write(p, line) } } }
        link = l
        toldTyping = false
        sent = 0
        complaints.clear()
        phase = Phase.Starting
        val p =
            runCatching { proot.process(l.command(session)).redirectErrorStream(false).start() }.getOrElse {
                phase = Phase.Off
                notice = str(R.string.voice_cant_start, a.name, it.message.orEmpty())
                return
            }
        process = p
        to = p
        val opening = l.open(session)
        trace('$', "start ${a.bin}" + (session?.let { " resuming $it" } ?: ""))
        // Stopping the agent closes its pipes under these readers: that ends them, it isn't an error.
        Thread({
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    trace('<', line)
                    main.post {
                        if (process === p) {
                            // A bug in what an event does is logged; it never takes the launcher down.
                            runCatching { events(read(l, line)) }.onFailure { Log.w(TAG, "couldn't handle the agent's line", it) }
                        }
                    }
                }
            }
            runCatching { p.waitFor() }
            main.post { exited(p) }
        }, "voice-read").apply { isDaemon = true }.start()
        Thread({
            runCatching {
                p.errorStream.bufferedReader().forEachLine { line ->
                    trace('!', line)
                    main.post { if (process === p) complain(line) }
                }
            }
        }, "voice-err").apply { isDaemon = true }.start()
        events(opening)
        startWatch()
    }

    /** What [line] means; a line it can't make sense of is logged and skipped, it never takes the launcher down. */
    private fun read(
        l: AgentLink,
        line: String,
    ): List<LinkEvent> =
        runCatching { l.read(line) }.getOrElse {
            Log.w(TAG, "couldn't read the agent's line", it)
            emptyList()
        }

    private fun write(
        p: Process,
        line: String,
    ) {
        trace('>', line)
        runCatching {
            p.outputStream.write((line + "\n").toByteArray())
            p.outputStream.flush()
        }.onFailure { Log.w(TAG, "couldn't write to the agent", it) }
    }

    private fun stopAgent() {
        val p = process ?: return
        process = null
        if (turns > 0) saveSession(busy = true)
        speaker.stop()
        approvals.clear()
        turns = 0
        endReply()
        phase = Phase.Off
        files.execute { killTree(p) }
    }

    private val idleStop = Runnable { if (screens == 0 && !busy && approvals.isEmpty()) stopAgent() }

    private fun scheduleIdleStop() {
        main.removeCallbacks(idleStop)
        if (screens == 0) main.postDelayed(idleStop, IDLE_STOP_MS)
    }

    private fun exited(p: Process) {
        if (process !== p) return
        process = null
        val code = runCatching { p.exitValue() }.getOrDefault(-1)
        val said = complaints.joinToString(" ").lowercase()
        Log.i(TAG, "${agent?.bin} exited $code: ${said.takeLast(300)}")
        val wasBusy = busy
        turns = 0
        approvals.clear()
        endReply()
        if (session != null && Regex("no conversation|not found|invalid session").containsMatchIn(said)) {
            agent?.let { sessionFile(it.bin).delete() }
            session = null
            transcript += Entry("note", str(R.string.voice_note_cant_continue))
            saveTranscript()
            return start()
        }
        if (restarts < 3 && screens > 0) {
            restarts++
            start()
            if (wasBusy) notice = str(R.string.voice_restarted, name)
        } else {
            phase = Phase.Off
            if (wasBusy || screens > 0) notice = str(R.string.voice_stopped, name, complaints.lastOrNull().orEmpty().take(200))
        }
    }

    private fun complain(line: String) {
        complaints.addLast(line)
        while (complaints.size > 20) complaints.removeFirst()
    }

    // ---------- what it says

    private fun events(list: List<LinkEvent>) {
        if (list.isEmpty()) return
        lastEvent = System.currentTimeMillis()
        for (e in list) {
            when (e) {
                is LinkEvent.Session -> {
                    session = e.id
                    saveSession()
                }
                LinkEvent.Ready -> if (phase == Phase.Starting) phase = Phase.Idle
                LinkEvent.ResumeFailed -> {
                    transcript += Entry("note", str(R.string.voice_note_cant_continue))
                    saveTranscript()
                }
                is LinkEvent.Text -> {
                    heardBack = true
                    var d = e.text
                    if (afterTool && reply.isNotEmpty() && !reply.last().isWhitespace()) d = " " + d.trimStart()
                    afterTool = false
                    buffer += d
                    reply += d
                    handOver(final = false)
                    if (phase != Phase.Approval) phase = Phase.Working
                }
                LinkEvent.Working -> {
                    heardBack = true
                    handOver(final = true)
                    afterTool = true
                    if (System.currentTimeMillis() - lastSaid > PROGRESS_MS) say(spoken(R.string.voice_say_moment))
                    if (phase != Phase.Approval) phase = Phase.Working
                }
                is LinkEvent.Step -> {
                    steps += (if (e.sub) "  " else "") + shown(e.act)
                    while (steps.size > 50) steps.removeAt(0)
                    for (path in e.images) keepImage(path)
                }
                is LinkEvent.Ask -> {
                    heardBack = true
                    val a = Approval(e.id, e.act, shown(e.act), said(e.act), e.detail.take(400), e.key)
                    if (approveAll || Steps.covered(e.key, always)) {
                        link?.answer(e.id, true, false, null)
                    } else {
                        approvals += a
                        if (approvals.size == 1) {
                            phase = Phase.Approval
                            announce(a)
                        }
                    }
                }
                is LinkEvent.Done -> {
                    // Not an answer to a prompt (Claude ends a failed --resume with an error result): nothing to say.
                    val prompted = turns > 0
                    turns = (turns - 1).coerceAtLeast(0)
                    doneAt = System.currentTimeMillis()
                    restarts = 0
                    val had = reply.isNotBlank() || transcript.lastOrNull()?.who == "image"
                    endReply()
                    saveSession()
                    if (prompted && e.error != null) {
                        say(spoken(R.string.voice_say_failed))
                        notice = e.error.take(200).ifEmpty { str(R.string.voice_failed) }
                    } else if (prompted && !had && !e.cancelled) {
                        notice = str(R.string.voice_nothing_to_say, name)
                    }
                    if (turns == 0) {
                        settle()
                        scheduleIdleStop()
                    }
                }
                is LinkEvent.Retrying -> notice = str(R.string.voice_retrying, e.error)
                // It can't take prompts (not signed in): stopped, so the next one starts it again rather than waiting forever.
                is LinkEvent.Failed -> {
                    stopAgent()
                    notice = "$name: " + e.message.ifEmpty { str(R.string.voice_failed) }
                    return
                }
            }
        }
    }

    /** The phase once something's done: a question waiting, the task going on, or nothing. */
    private fun settle() {
        phase = if (approvals.isNotEmpty()) Phase.Approval else if (busy) Phase.Working else Phase.Idle
    }

    private fun announce(a: Approval) = say(spoken(R.string.voice_say_wants, name, said(a.act, aloud = true)))

    /** The agent's name, for what's shown and said. */
    private val name get() = agent?.name ?: str(R.string.voice_the_agent)

    /** Text in the launcher's language: the application's resources follow the language picked in the launcher. */
    private fun str(
        id: Int,
        vararg args: Any,
    ) = context.getString(id, *args)

    /**
     * Text said aloud: in the language replies are read in, when the launcher has its text in it, so a German voice
     * doesn't read the launcher's English; else the launcher's.
     */
    private fun spoken(
        id: Int,
        vararg args: Any,
    ) = AppLanguage.stringIn(context, speaker.locale, id, *args) ?: str(id, *args)

    /** A step as it's shown: "reading x.png". */
    fun shown(act: Act): String = if (act.verb == Act.Verb.Tool) act.what.ifEmpty { str(R.string.voice_step_tool) } else str(STEP.getValue(act.verb), act.what)

    /** What it wants to do, after "Claude Code wants to": "read x.png"; [aloud], in the language it's said in. */
    private fun said(
        act: Act,
        aloud: Boolean = false,
    ): String {
        val id = if (act.verb == Act.Verb.Tool && act.what.isEmpty()) R.string.voice_do_tool_unknown else DOES.getValue(act.verb)
        return if (aloud) spoken(id, act.what) else str(id, act.what)
    }

    /** Finished sentences to the voice as soon as they're complete; the rest when the reply ends. */
    private fun handOver(final: Boolean) {
        while (true) {
            val m = SENTENCE.find(buffer) ?: break
            if (m.range.last + 1 == buffer.length && !final && !buffer.endsWith("\n")) break
            say(buffer.substring(0, m.range.last + 1))
            buffer = buffer.substring(m.range.last + 1)
        }
        if (final && buffer.isNotBlank()) say(buffer)
        if (final) buffer = ""
    }

    private fun say(text: String) {
        if (typing) return
        val clean = spokenText(text)
        if (clean.isEmpty()) return
        lastSaid = System.currentTimeMillis()
        speaker.speak(clean)
    }

    /** The reply so far into the transcript, as its own paragraph. */
    private fun endReply() {
        handOver(final = true)
        if (reply.isNotBlank()) {
            transcript += Entry("agent", reply.trim())
            saveTranscript()
        }
        reply = ""
        afterTool = false
    }

    /** A picture it read, like a widget's preview: copied now, as the next owl-check overwrites it, after what it said so far. */
    private fun keepImage(guestPath: String) {
        val source = proot.guestFile(guestPath)?.takeIf { it.isFile } ?: return
        endReply()
        val images = File(dir, "images").apply { mkdirs() }
        images.listFiles()?.sortedBy { it.name }?.dropLast(29)?.forEach { it.delete() }
        val copy = File(images, "${System.nanoTime()}.${source.extension.lowercase()}")
        runCatching { source.copyTo(copy) }.onFailure { return }
        // The same picture again with nothing in between: just the newer one.
        transcript.lastOrNull()?.takeIf { it.who == "image" && it.text.substringAfterLast('|') == guestPath }?.let {
            File(it.text.substringBefore('|')).delete()
            transcript.removeAt(transcript.lastIndex)
        }
        transcript += Entry("image", copy.path + "|" + guestPath)
        saveTranscript()
    }

    // ---------- a watch for stuck turns and folded prompts

    private val watch =
        object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                // Claude can fold a prompt sent mid-task into that task, with no answer of its own.
                if (link?.foldsPrompts == true && turns > 0 && doneAt > 0 && lastEvent <= doneAt && now - doneAt > 4000) {
                    turns = 0
                    doneAt = 0
                    settle()
                }
                // Nothing at all since the prompt: OpenCode out of its free quota leaves a prompt hanging without a word.
                if (turns > 0 && !heardBack && now - asked > SILENT_MS && notice == null) notice = str(R.string.voice_no_answer, name)
                if (turns > 0 && now - lastEvent > QUIET_MS && notice == null) notice = str(R.string.voice_quiet, name)
                if (process != null) main.postDelayed(this, 1000)
            }
        }

    private fun startWatch() {
        main.removeCallbacks(watch)
        main.postDelayed(watch, 1000)
    }

    // ---------- files

    private fun saveTranscript() {
        val a = agent ?: return
        writeLines(transcriptFile(a.bin), transcript)
    }

    private fun saveSession(busy: Boolean = this.busy) {
        val a = agent ?: return
        val id = session ?: return
        writeFile(sessionFile(a.bin), json("id" to id, "at" to System.currentTimeMillis(), "busy" to busy).toString())
    }

    private fun saveSettings() =
        writeFile(
            settingsFile,
            json("agent" to agent?.bin, "approveAll" to approveAll, "typing" to typing, "language" to language, "voice" to voice, "rate" to rate.toDouble()).toString(),
        )

    private fun writeFile(
        file: File,
        text: String,
    ) = files.execute {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }

    private fun readJson(file: File) = runCatching { JSONObject(file.readText()) }.getOrNull()

    /** A conversation's lines, its last 200. */
    private fun writeLines(
        file: File,
        lines: List<Entry>,
    ) = writeFile(file, JSONArray(lines.takeLast(200).map { json("who" to it.who, "text" to it.text) }).toString())

    private fun readLines(file: File) =
        runCatching { JSONArray(file.readText()).objects().map { Entry(it.optString("who"), it.optString("text")) } }.getOrNull().orEmpty()

    companion object {
        private const val TAG = "voice"

        /** An agent's error that it isn't signed in: Claude's "Not logged in", Grok's "Authentication required". */
        private val LOGIN = Regex("log ?in|sign ?in|authenticat|api key|credential", RegexOption.IGNORE_CASE)

        /** How a step is shown ([shown]) and how what it wants to do is said ([said]), by verb; each takes [Act.what]. */
        private val STEP =
            mapOf(
                Act.Verb.Run to R.string.voice_step_run,
                Act.Verb.Read to R.string.voice_step_read,
                Act.Verb.Write to R.string.voice_step_write,
                Act.Verb.Edit to R.string.voice_step_edit,
                Act.Verb.Delete to R.string.voice_step_delete,
                Act.Verb.Search to R.string.voice_step_search,
                Act.Verb.SearchWeb to R.string.voice_step_web,
                Act.Verb.Subtask to R.string.voice_step_subtask,
                Act.Verb.Plan to R.string.voice_step_plan,
            )
        private val DOES =
            mapOf(
                Act.Verb.Run to R.string.voice_do_run,
                Act.Verb.Read to R.string.voice_do_read,
                Act.Verb.Write to R.string.voice_do_write,
                Act.Verb.Edit to R.string.voice_do_edit,
                Act.Verb.Delete to R.string.voice_do_delete,
                Act.Verb.Search to R.string.voice_do_search,
                Act.Verb.SearchWeb to R.string.voice_do_web,
                Act.Verb.Subtask to R.string.voice_do_subtask,
                Act.Verb.Plan to R.string.voice_do_plan,
                Act.Verb.Tool to R.string.voice_do_tool,
            )

        const val DEFAULT_RATE = 1.25f
        const val IDLE_STOP_MS = 10 * 60_000L
        const val RESUME_MS = 12 * 3_600_000L
        const val QUIET_MS = 120_000L
        const val SILENT_MS = 45_000L
        const val PROGRESS_MS = 12_000L
        private const val REMIND_EVERY = 5
        private const val VOICE_REMINDER = "Still a voice conversation: keep it short."
        private const val TYPING_NOTE =
            "The user switched to typing: they type their messages and read your replies on screen, nothing is read " +
                "aloud now. You may say a little more, but keep it plain text without markdown."
        private const val TALKING_NOTE = "The user is talking again: your replies are read aloud. Keep them short and spoken, as before."
        private val SENTENCE = Regex("[.!?:;](\\s|$)|\\n")
        /*
         * Yes and no to a question, in the screen's languages, as speech-to-text writes them. Not \W or \b: Android's
         * ICU counts CJK as word characters and the JVM doesn't, and Chinese has no word breaks to find anyway.
         */
        private const val EDGE = "[\\s\\p{P}]*"
        internal val YES =
            Regex(
                "^$EDGE(?:(?:yes|yeah|yep|sure|ok|okay|allow|allowed|approve|go ahead|do it|go|fine|alright|please do|" +
                    "ja|klar|si|sí|vale|claro|oui|sim|pode|ya|iya|boleh|oke)(?![\\p{L}\\p{N}])|是|好|可以|行|对|嗯)",
                RegexOption.IGNORE_CASE,
            )
        internal val NO =
            Regex(
                "^$EDGE(?:no|nope|deny|don't|do not|stop|cancel|never mind|nevermind|nein|non|não|nao|tidak|jangan|" +
                    "nggak|gak|enggak|不要|不用|不行|不|别|算了)$EDGE$",
                RegexOption.IGNORE_CASE,
            )

        /** Text as it's read aloud: no code, links or markdown marks. */
        fun spokenText(s: String): String =
            s
                .replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
                .replace(Regex("https?://\\S+"), "a link")
                .replace(Regex("[*_#`>|~]"), "")
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .joinToString(" ")
    }
}
