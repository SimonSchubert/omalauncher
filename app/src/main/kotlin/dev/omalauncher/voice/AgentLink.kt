package dev.omalauncher.voice

import dev.omalauncher.widgets.WidgetEngine
import org.json.JSONArray
import org.json.JSONObject

/** What an agent did, read from its output by an [AgentLink]. */
sealed interface LinkEvent {
    /** Its session, to continue the conversation later. */
    data class Session(
        val id: String,
    ) : LinkEvent

    /** Ready for prompts. */
    data object Ready : LinkEvent

    /** The session asked to continue is gone; a new one was started instead. */
    data object ResumeFailed : LinkEvent

    /** More of its reply, as it's written. */
    data class Text(
        val text: String,
    ) : LinkEvent

    /** It started using a tool; what for comes with the [Step]. */
    data object Working : LinkEvent

    /** A tool call ([sub] when a subtask made it), with the pictures it reads. */
    data class Step(
        val act: Act,
        val images: List<String> = emptyList(),
        val sub: Boolean = false,
    ) : LinkEvent

    /** It asks before using a tool: [id] goes back in [AgentLink.answer]. [key] is what "always" allows. */
    data class Ask(
        val id: String,
        val act: Act,
        val detail: String,
        val key: String,
    ) : LinkEvent

    /** A prompt is answered: [error] when it failed (empty when it didn't say why), [cancelled] when it was stopped. */
    data class Done(
        val error: String? = null,
        val cancelled: Boolean = false,
    ) : LinkEvent

    /** It retries after [error], like a network error. */
    data class Retrying(
        val error: String,
    ) : LinkEvent

    /** It can't go on: not signed in, no session; [message] is its own, empty when it gave none. */
    data class Failed(
        val message: String,
    ) : LinkEvent
}

/**
 * One agent CLI kept running for a spoken conversation: the command that starts it, what to write for a prompt,
 * a stop or an answer, and the [LinkEvent]s its output means. No process or threads here: lines come in
 * through [read] and replies go out through [write], so a link is tested with recorded output.
 */
abstract class AgentLink(
    protected val write: (String) -> Unit,
) {
    /** The guest shell command that starts it, continuing [resume] when it's given. */
    abstract fun command(resume: String?): String

    /** Writes what it needs before the first prompt; what that means already, like being ready. */
    open fun open(resume: String?): List<LinkEvent> = emptyList()

    abstract fun prompt(text: String)

    /** Stops the turn in progress, and any prompts waiting behind it. */
    abstract fun cancel()

    /**
     * Answers [LinkEvent.Ask] [id]. [instead] is what the user said to do instead of a refused tool; true when
     * the agent got it with the refusal, false when it has to be sent as the next prompt.
     */
    abstract fun answer(
        id: String,
        allow: Boolean,
        always: Boolean,
        instead: String?,
    ): Boolean

    abstract fun read(line: String): List<LinkEvent>

    /** A prompt sent mid-turn may be folded into the turn, with no [LinkEvent.Done] of its own. */
    open val foldsPrompts = false

    /** Whether [VOICE_PROMPT] stays with the agent as its instructions, rather than coming once with the first prompt. */
    open val instructed = false
}

/** How the agent is told it's in a spoken conversation. */
const val VOICE_PROMPT =
    "You are talking with the user through a voice interface on their phone: what they say reaches you as " +
        "speech-to-text, which can mishear words, and your replies are read aloud by text-to-speech, so every word " +
        "takes their time. Talk like a person in a conversation: one to three short sentences, longer only when " +
        "asked; they'll ask if they want more. Answer, then stop: no preamble, no repeating their question, no " +
        "offers of further help. Answer in the language the user speaks. Never use markdown, lists, headings, code " +
        "blocks, tables, emoji or URLs; say numbers, paths and symbols the way you'd say them out loud. For a task " +
        "with tools, say once in a few words what you're going to do, then work without talking about each step, " +
        "and at the end say the outcome in a sentence or two, not everything you did."

/** Where agents work in Linux, unless one needs a folder of its own. */
const val GUEST_HOME = "/root"

/** The agents that can talk, by bin, with the link that talks to each. */
val VOICE_LINKS: Map<String, (write: (String) -> Unit) -> AgentLink> =
    mapOf(
        "claude" to ::ClaudeLink,
        "codex" to { w -> CodexLink(w) },
        "grok" to { w -> AcpLink(w, "grok agent stdio") },
        // OpenCode snapshots its folder with git before every turn, and all of home took minutes before its first
        // word; the widgets are what voice is mostly for.
        "opencode" to { w -> AcpLink(w, "opencode acp", "$GUEST_HOME/${WidgetEngine.GUEST_DIR}") },
        "copilot" to { w -> AcpLink(w, "copilot --acp") },
        "kimi" to { w -> AcpLink(w, "kimi acp") },
        "cursor-agent" to { w -> AcpLink(w, "cursor-agent acp") },
    )

private fun sh(s: String) = "'" + s.replace("'", "'\\''") + "'"

/**
 * Claude Code over stream-json both ways: `claude -p --input-format stream-json`, one user message per prompt,
 * its partial messages as the reply streams, and approvals through `--permission-prompt-tool stdio`.
 */
class ClaudeLink(
    write: (String) -> Unit,
) : AgentLink(write) {
    override val foldsPrompts = true
    override val instructed = true

    private var session: String? = null
    private val asked = HashMap<String, JSONObject>()

    override fun command(resume: String?) =
        "exec claude -p --input-format stream-json --output-format stream-json --verbose --include-partial-messages " +
            "--append-system-prompt ${sh(VOICE_PROMPT)} --allowedTools Read,Glob,Grep,WebSearch,WebFetch " +
            "--permission-prompt-tool stdio" + (resume?.let { " --resume ${sh(it)}" } ?: "")

    override fun open(resume: String?): List<LinkEvent> {
        session = resume
        // As the Agent SDK opens a session.
        send(json("type" to "control_request", "request_id" to "init", "request" to json("subtype" to "initialize")))
        // It says nothing until the first prompt; it's ready once it runs.
        return listOf(LinkEvent.Ready)
    }

    override fun prompt(text: String) = send(json("type" to "user", "message" to json("role" to "user", "content" to text)))

    override fun cancel() = send(json("type" to "control_request", "request_id" to "stop${System.nanoTime()}", "request" to json("subtype" to "interrupt")))

    override fun answer(
        id: String,
        allow: Boolean,
        always: Boolean,
        instead: String?,
    ): Boolean {
        val input = asked.remove(id) ?: JSONObject()
        val response =
            if (allow) {
                json("behavior" to "allow", "updatedInput" to input)
            } else {
                json("behavior" to "deny", "message" to (instead?.let { "The user said no, and said: $it" } ?: "The user said no to this."))
            }
        respond(json("subtype" to "success", "request_id" to id, "response" to response))
        return true
    }

    private fun send(message: JSONObject) = write(message.toString())

    private fun respond(response: JSONObject) = send(json("type" to "control_response", "response" to response))

    override fun read(line: String): List<LinkEvent> {
        val ev = runCatching { JSONObject(line) }.getOrNull() ?: return emptyList()
        val out = ArrayList<LinkEvent>()
        ev.optString("session_id").takeIf { it.isNotEmpty() && it != session }?.let {
            session = it
            out += LinkEvent.Session(it)
        }
        when (ev.optString("type")) {
            "system" ->
                when (ev.optString("subtype")) {
                    "init" -> out += LinkEvent.Ready
                    "api_retry" -> out += LinkEvent.Retrying(ev.optString("error", "network"))
                }
            "stream_event" -> {
                if (!ev.isNull("parent_tool_use_id")) return out
                val e = ev.optJSONObject("event") ?: return out
                when (e.optString("type")) {
                    "content_block_delta" ->
                        e.optJSONObject("delta")?.takeIf { it.optString("type") == "text_delta" }?.let { out += LinkEvent.Text(it.optString("text")) }
                    "content_block_start" ->
                        if (e.optJSONObject("content_block")?.optString("type") == "tool_use") out += LinkEvent.Working
                }
            }
            // The whole message, tool inputs included: each tool call is a step.
            "assistant" ->
                ev.optJSONObject("message")?.optJSONArray("content")?.objects()?.filter { it.optString("type") == "tool_use" }?.forEach {
                    val name = it.optString("name")
                    val input = it.optJSONObject("input") ?: JSONObject()
                    val sub = !ev.isNull("parent_tool_use_id")
                    val images = if (name == "Read") listOfNotNull(input.optString("file_path").takeIf(::isImage)) else emptyList()
                    out += LinkEvent.Step(Steps.label(name, input), images, sub)
                }
            "result" ->
                out +=
                    if (ev.optBoolean("is_error")) {
                        LinkEvent.Done(error = ev.optString("result").ifEmpty { ev.optString("subtype") })
                    } else {
                        LinkEvent.Done()
                    }
            "control_request" -> {
                val id = ev.optString("request_id")
                val req = ev.optJSONObject("request") ?: JSONObject()
                if (req.optString("subtype") != "can_use_tool") {
                    respond(json("subtype" to "error", "request_id" to id, "error" to "not supported"))
                } else {
                    val name = req.optString("tool_name")
                    val input = req.optJSONObject("input") ?: JSONObject()
                    asked[id] = input
                    val detail = input.firstString("command", "file_path", "url", "pattern")
                    out += LinkEvent.Ask(id, Steps.label(name, input), detail, Steps.alwaysKey(name, input.optString("command")))
                }
            }
        }
        return out
    }
}

/**
 * An agent spoken to in JSON-RPC on stdio, a request's answer coming to the callback that sent it, and its prompts
 * one at a time: a later one waits for the one in flight, and none goes before it's ready. [jsonrpc] is whether
 * messages carry the "jsonrpc" field (Codex leaves it out).
 */
abstract class RpcLink(
    write: (String) -> Unit,
    private val jsonrpc: Boolean,
) : AgentLink(write) {
    private var nextId = 0
    private val calls = HashMap<Int, (JSONObject?, JSONObject?) -> List<LinkEvent>>()
    private var ready = false
    private var inFlight = false
    private val queue = ArrayDeque<String>()

    /** Starts a turn with [text]; it's in flight until [finished]. */
    protected abstract fun startTurn(text: String)

    /** What a request or notification of its own means; null for one it doesn't know, refused when it's a request. */
    protected abstract fun handle(
        method: String,
        id: Any?,
        params: JSONObject,
    ): List<LinkEvent>?

    final override fun prompt(text: String) {
        queue.addLast(text)
        next()
    }

    /** Takes prompts from now on, the first waiting one now. */
    protected fun becomeReady(): List<LinkEvent> {
        ready = true
        next()
        return listOf(LinkEvent.Ready)
    }

    /** The turn in flight ended with [done]; the next waiting prompt goes. */
    protected fun finished(done: LinkEvent.Done): List<LinkEvent> {
        inFlight = false
        next()
        return listOf(done)
    }

    /** Drops the prompts waiting, as for a stop. */
    protected fun dropQueued() = queue.clear()

    private fun next() {
        if (!ready || inFlight || queue.isEmpty()) return
        inFlight = true
        startTurn(queue.removeFirst())
    }

    final override fun read(line: String): List<LinkEvent> {
        val ev = runCatching { JSONObject(line) }.getOrNull() ?: return emptyList()
        val method = ev.optString("method")
        // An answer to one of ours.
        if (method.isEmpty() && ev.has("id")) {
            val then = calls.remove(ev.optInt("id")) ?: return emptyList()
            return then(ev.optJSONObject("result"), ev.optJSONObject("error"))
        }
        val id = ev.opt("id")
        handle(method, id, ev.optJSONObject("params") ?: JSONObject())?.let { return it }
        if (id != null) send(json("id" to id, "error" to json("code" to -32601, "message" to "$method isn't supported")))
        return emptyList()
    }

    protected fun call(
        method: String,
        params: JSONObject,
        then: (JSONObject?, JSONObject?) -> List<LinkEvent>,
    ) {
        val id = ++nextId
        calls[id] = then
        send(json("id" to id, "method" to method, "params" to params))
    }

    protected fun notify(
        method: String,
        params: JSONObject? = null,
    ) = send(json("method" to method, "params" to params))

    protected fun reply(
        id: Any,
        result: JSONObject,
    ) = send(json("id" to id, "result" to result))

    private fun send(message: JSONObject) = write((if (jsonrpc) message.put("jsonrpc", "2.0") else message).toString())
}

/**
 * An agent over ACP, the Agent Client Protocol: JSON-RPC on stdio. One session; prompts go one at a time, a
 * later one waiting for the one in flight. session/update notifications carry the reply and tool calls,
 * session/request_permission asks before a tool, session/load continues a conversation (its replay of the old
 * one isn't shown). ACP has no system prompt, so the first prompt of a conversation says how to talk.
 */
class AcpLink(
    write: (String) -> Unit,
    /** The guest command that serves ACP on stdio, such as `grok agent stdio` or `kimi acp`. */
    private val serve: String,
    /** Where its sessions work. */
    private val cwd: String = GUEST_HOME,
) : RpcLink(write, jsonrpc = true) {
    private var session: String? = null
    private var loading = false
    private var primed = false
    private val asked = HashMap<String, Pair<Any, JSONArray>>()

    override fun command(resume: String?) = "exec $serve"

    override fun open(resume: String?): List<LinkEvent> {
        session = resume
        primed = resume != null
        val fs = json("readTextFile" to false, "writeTextFile" to false)
        call("initialize", json("protocolVersion" to 1, "clientCapabilities" to json("fs" to fs, "terminal" to false))) { _, error ->
            if (error != null) return@call listOf(LinkEvent.Failed(error.optString("message")))
            val id = session
            if (id != null) {
                loading = true
                call("session/load", json("sessionId" to id, "cwd" to cwd, "mcpServers" to JSONArray())) { _, e -> loaded(e) }
            } else {
                newSession()
            }
            emptyList()
        }
        return emptyList()
    }

    private fun newSession() {
        call("session/new", json("cwd" to cwd, "mcpServers" to JSONArray())) { result, error ->
            val id = result?.optString("sessionId")?.takeIf { it.isNotEmpty() }
            if (id == null) return@call listOf(LinkEvent.Failed(error?.optString("message").orEmpty()))
            session = id
            listOf(LinkEvent.Session(id)) + becomeReady()
        }
    }

    private fun loaded(error: JSONObject?): List<LinkEvent> {
        loading = false
        if (error == null) return becomeReady()
        session = null
        primed = false
        newSession()
        return listOf(LinkEvent.ResumeFailed)
    }

    override fun startTurn(text: String) {
        val said = if (primed) text else "($VOICE_PROMPT)\n\n$text"
        primed = true
        call("session/prompt", json("sessionId" to session, "prompt" to JSONArray().put(json("type" to "text", "text" to said)))) { result, error ->
            finished(LinkEvent.Done(error = error?.optString("message"), cancelled = result?.optString("stopReason") == "cancelled"))
        }
    }

    override fun cancel() {
        dropQueued()
        session?.let { notify("session/cancel", json("sessionId" to it)) }
    }

    override fun answer(
        id: String,
        allow: Boolean,
        always: Boolean,
        instead: String?,
    ): Boolean {
        val (rpcId, options) = asked.remove(id) ?: return false
        val want = if (allow) (if (always) listOf("allow_always", "allow_once") else listOf("allow_once", "allow_always")) else listOf("reject_once", "reject_always")
        val pick = want.firstNotNullOfOrNull { k -> options.objects().firstOrNull { it.optString("kind") == k } }
        val outcome = if (pick != null) json("outcome" to "selected", "optionId" to pick.optString("optionId")) else json("outcome" to "cancelled")
        reply(rpcId, json("outcome" to outcome))
        return false
    }

    override fun handle(
        method: String,
        id: Any?,
        params: JSONObject,
    ): List<LinkEvent>? =
        when (method) {
            "session/update" -> if (loading) emptyList() else update(params.optJSONObject("update") ?: JSONObject())
            "session/request_permission" -> if (id == null) emptyList() else listOf(permission(id, params))
            // fs/… or terminal/…, which we said we don't offer; notifications (_x.ai/…) need nothing.
            else -> null
        }

    private fun update(u: JSONObject): List<LinkEvent> =
        when (u.optString("sessionUpdate")) {
            "agent_message_chunk" ->
                u.optJSONObject("content")?.takeIf { it.optString("type") == "text" }?.optString("text")
                    // Copilot's note of the file a change it was allowed touched, sent as reply text: not to be read out.
                    ?.takeUnless { NOTICE.matches(it) }
                    ?.let { listOf(LinkEvent.Text(it)) } ?: emptyList()
            "tool_call" -> listOf(LinkEvent.Working, LinkEvent.Step(Steps.acpLabel(u), Steps.acpPaths(u).filter(::isImage)))
            else -> emptyList()
        }

    private fun permission(
        rpcId: Any,
        params: JSONObject,
    ): LinkEvent {
        val call = params.optJSONObject("toolCall") ?: JSONObject()
        val id = "p$rpcId"
        asked[id] = rpcId to (params.optJSONArray("options") ?: JSONArray())
        val raw = call.optJSONObject("rawInput") ?: JSONObject()
        val key = if (call.optString("kind") == "execute" && raw.has("command")) Steps.commandKey(raw.optString("command")) else "acp:" + call.optString("kind")
        return LinkEvent.Ask(id, Steps.acpLabel(call), raw.firstString("command", "path", "file_path", "url"), key)
    }

    companion object {
        /** A chunk that's a whole "Info: /path" line, Copilot's after an approved file change. */
        private val NOTICE = Regex("Info: /\\S+\\s*")
    }
}

/**
 * Codex over its app-server protocol (`codex app-server`, what its editor extensions use): JSON-RPC on stdio, without
 * the "jsonrpc" field. One thread, started with [VOICE_PROMPT] as its developer instructions, or resumed. A prompt is
 * a turn; a later one waits for the turn in flight to complete. item/agentMessage/delta streams the reply,
 * item/started shows each tool call, and with the "untrusted" approval policy it asks before anything but a command
 * it knows only reads.
 */
class CodexLink(
    write: (String) -> Unit,
    private val cwd: String = GUEST_HOME,
) : RpcLink(write, jsonrpc = false) {
    override val instructed = true

    private var thread: String? = null
    private var turn: String? = null
    private val asked = HashMap<String, Any>()

    /** What each tool call in this turn does, by item, for a file change's approval, which only names the item. */
    private val acts = HashMap<String, Act>()

    /** The reply's message so far: a new one is a new paragraph. */
    private var message: String? = null

    override fun command(resume: String?) = "exec codex app-server"

    override fun open(resume: String?): List<LinkEvent> {
        val info = json("name" to "omalauncher_voice", "title" to "Omalauncher Voice", "version" to "1")
        call("initialize", json("clientInfo" to info)) { _, error ->
            if (error != null) return@call listOf(LinkEvent.Failed(error.optString("message")))
            notify("initialized")
            if (resume != null) {
                call("thread/resume", threadParams().put("threadId", resume)) { result, e ->
                    if (e == null && started(result) != null) return@call becomeReady()
                    listOf(LinkEvent.ResumeFailed).also { startThread() }
                }
            } else {
                startThread()
            }
            emptyList()
        }
        return emptyList()
    }

    /** How the thread works: in [cwd], asking before what isn't known to only read, told it's spoken to. */
    private fun threadParams() = json("cwd" to cwd, "approvalPolicy" to "untrusted", "developerInstructions" to VOICE_PROMPT)

    private fun startThread() {
        call("thread/start", threadParams()) { result, error ->
            val id = started(result) ?: return@call listOf(LinkEvent.Failed(error?.optString("message").orEmpty()))
            listOf(LinkEvent.Session(id)) + becomeReady()
        }
    }

    private fun started(result: JSONObject?) = result?.optJSONObject("thread")?.optString("id")?.takeIf { it.isNotEmpty() }?.also { thread = it }

    override fun startTurn(text: String) {
        message = null
        acts.clear()
        call("turn/start", json("threadId" to thread, "input" to JSONArray().put(json("type" to "text", "text" to text)))) { result, error ->
            if (error != null) return@call ended(LinkEvent.Done(error = error.optString("message")))
            turn = result?.optJSONObject("turn")?.optString("id")
            emptyList()
        }
    }

    private fun ended(done: LinkEvent.Done): List<LinkEvent> {
        turn = null
        return finished(done)
    }

    override fun cancel() {
        dropQueued()
        val t = turn ?: return
        call("turn/interrupt", json("threadId" to thread, "turnId" to t)) { _, _ -> emptyList() }
    }

    override fun answer(
        id: String,
        allow: Boolean,
        always: Boolean,
        instead: String?,
    ): Boolean {
        val rpcId = asked.remove(id) ?: return false
        reply(rpcId, json("decision" to if (!allow) "decline" else if (always) "acceptForSession" else "accept"))
        return false
    }

    override fun handle(
        method: String,
        id: Any?,
        params: JSONObject,
    ): List<LinkEvent>? =
        when (method) {
            "item/agentMessage/delta" -> {
                val item = params.optString("itemId")
                val gap = if (message != null && message != item) "\n\n" else ""
                message = item
                listOf(LinkEvent.Text(gap + params.optString("delta")))
            }
            "item/started" -> {
                val item = params.optJSONObject("item")
                val act = item?.let(::act)
                if (item == null || act == null) {
                    emptyList()
                } else {
                    acts[item.optString("id")] = act
                    val images = if (item.optString("type") == "imageView") listOf(item.optString("path")) else emptyList()
                    listOf(LinkEvent.Working, LinkEvent.Step(act, images))
                }
            }
            "turn/completed" -> {
                val t = params.optJSONObject("turn") ?: JSONObject()
                val status = t.optString("status")
                val error = t.optJSONObject("error")?.optString("message")?.ifEmpty { null }
                ended(LinkEvent.Done(error = if (status == "failed") error ?: "" else null, cancelled = status == "interrupted"))
            }
            "error" ->
                if (params.optBoolean("willRetry")) {
                    listOf(LinkEvent.Retrying(params.optJSONObject("error")?.optString("message").orEmpty().ifEmpty { "network" }))
                } else {
                    emptyList()
                }
            "item/commandExecution/requestApproval" ->
                ask("c", id) {
                    val command = unwrap(params.optString("command"))
                    val act = acts[params.optString("itemId")] ?: Act(Act.Verb.Run, command.take(60))
                    LinkEvent.Ask(it, act, command, Steps.commandKey(command))
                }
            "item/fileChange/requestApproval" ->
                ask("f", id) { LinkEvent.Ask(it, acts[params.optString("itemId")] ?: Act(Act.Verb.Edit), params.optString("reason"), "codex:fileChange") }
            // Its other questions (user input, MCP forms, more permissions): not something to answer by voice.
            else -> null
        }

    /** A question with request [rpcId], kept for the answer under its own id ([prefix] and the request's). */
    private fun ask(
        prefix: String,
        rpcId: Any?,
        ask: (String) -> LinkEvent.Ask,
    ): List<LinkEvent> {
        if (rpcId == null) return emptyList()
        val id = prefix + rpcId
        asked[id] = rpcId
        return listOf(ask(id))
    }

    /** A tool call item as an [Act]; null for what isn't one (its messages, reasoning). */
    private fun act(item: JSONObject): Act? =
        when (item.optString("type")) {
            "commandExecution" -> {
                // What it understood the command to do, when that's one plain read, listing or search.
                val action = item.optJSONArray("commandActions")?.objects()?.singleOrNull()
                when (action?.optString("type")) {
                    "read" -> Act(Act.Verb.Read, action.optString("name").ifEmpty { fileName(action.optString("path")) })
                    "listFiles" -> Act(Act.Verb.Read, fileName(action.optString("path").ifEmpty { "." }))
                    "search" -> Act(Act.Verb.Search, action.optString("query").take(50))
                    else -> Act(Act.Verb.Run, unwrap(item.optString("command")).take(60))
                }
            }
            "fileChange" -> {
                val change = item.optJSONArray("changes")?.objects()?.firstOrNull()
                val verb =
                    when (change?.optJSONObject("kind")?.optString("type")) {
                        "add" -> Act.Verb.Write
                        "delete" -> Act.Verb.Delete
                        else -> Act.Verb.Edit
                    }
                if (change == null) Act(Act.Verb.Edit) else Act(verb, fileName(change.optString("path")))
            }
            "webSearch" -> Act(Act.Verb.SearchWeb, item.optString("query").take(50))
            "imageView" -> Act(Act.Verb.Read, fileName(item.optString("path")))
            "mcpToolCall", "dynamicToolCall" -> Act(Act.Verb.Tool, item.optString("tool").take(60))
            else -> null
        }

    companion object {
        /** The command inside Codex's shell wrapper: `/bin/bash -lc 'ls -la'` is `ls -la`. */
        fun unwrap(command: String): String {
            val m = Regex("^(?:\\S*/)?(?:ba|z)?sh -l?c (.+)$", RegexOption.DOT_MATCHES_ALL).find(command.trim()) ?: return command.trim()
            val inner = m.groupValues[1].trim()
            return if (inner.length >= 2 && inner.first() == inner.last() && inner.first() in "'\"") inner.substring(1, inner.length - 1) else inner
        }
    }
}

/**
 * What a tool call does: a [verb] and what it does it to ([what]: a file, a command's description, a search), put
 * into words in the phone's language by the voice session.
 */
data class Act(
    val verb: Verb,
    val what: String = "",
) {
    enum class Verb { Run, Read, Write, Edit, Delete, Search, SearchWeb, Subtask, Plan, Tool }
}

/** Tool calls as [Act]s, and what "always" covers. */
object Steps {
    fun label(
        name: String,
        input: JSONObject,
    ): Act =
        when (name) {
            "Bash" -> Act(Act.Verb.Run, input.optString("description").ifEmpty { input.optString("command") }.take(60))
            "Read" -> Act(Act.Verb.Read, fileName(input.optString("file_path")))
            "Write" -> Act(Act.Verb.Write, fileName(input.optString("file_path")))
            "Edit", "NotebookEdit" -> Act(Act.Verb.Edit, fileName(input.optString("file_path")))
            "Glob", "Grep" -> Act(Act.Verb.Search, input.optString("pattern").take(50))
            "WebSearch" -> Act(Act.Verb.SearchWeb, input.optString("query").take(50))
            "WebFetch" -> Act(Act.Verb.Read, input.optString("url").substringAfter("://").substringBefore('/'))
            "Task" -> Act(Act.Verb.Subtask, input.optString("description").take(50))
            "TodoWrite" -> Act(Act.Verb.Plan)
            else -> Act(Act.Verb.Tool, name.lowercase())
        }

    fun acpPaths(u: JSONObject): List<String> {
        val raw = u.optJSONObject("rawInput") ?: JSONObject()
        val locations = u.optJSONArray("locations")?.objects()?.mapNotNull { it.optString("path").takeIf(String::isNotEmpty) } ?: emptyList()
        return locations + listOf("path", "file_path").mapNotNull { k -> raw.optString(k).takeIf(String::isNotEmpty) }
    }

    fun acpLabel(u: JSONObject): Act {
        val kind = u.optString("kind")
        val title = u.optString("title").trim()
        val raw = u.optJSONObject("rawInput") ?: JSONObject()
        val path = acpPaths(u).firstOrNull()
        return when {
            kind == "execute" -> Act(Act.Verb.Run, raw.optString("description").ifEmpty { title.ifEmpty { raw.optString("command") } }.take(60))
            kind in setOf("read", "edit", "delete") && path != null ->
                Act(mapOf("read" to Act.Verb.Read, "edit" to Act.Verb.Edit, "delete" to Act.Verb.Delete).getValue(kind), fileName(path))
            // An empty [Act.what] is "a tool".
            else -> Act(Act.Verb.Tool, title.ifEmpty { kind }.take(60))
        }
    }

    /** Every program a command line runs: `cd x && curl y | head` is cd, curl and head. */
    fun programs(command: String): Set<String> =
        command
            .split(Regex("&&|\\|\\||[;|&\\n]|\\$\\(|`"))
            .mapNotNull { part -> part.trim().split(Regex("\\s+")).firstOrNull { it.isNotEmpty() && !Regex("^\\w+=").containsMatchIn(it) } }
            .map { it.trimStart('(', '{').substringAfterLast('/') }
            .filter { it.isNotEmpty() }
            .toSet()

    /** What an "always" answer to a shell command allows: every program in it. */
    fun commandKey(command: String) = "Bash:" + programs(command).sorted().joinToString(" ")

    /** What an "always" answer allows: Claude's tool [name], or for its Bash tool every program in [command]. */
    fun alwaysKey(
        name: String,
        command: String,
    ) = if (name == "Bash") commandKey(command) else name

    /** Whether "always" answers so far cover [key]: for commands, each of their programs was allowed. */
    fun covered(
        key: String,
        always: Set<String>,
    ): Boolean {
        if (!key.startsWith("Bash:")) return key in always
        val allowed = always.filter { it.startsWith("Bash:") }.flatMap { it.removePrefix("Bash:").split(' ') }.toSet()
        val progs = key.removePrefix("Bash:").split(' ').filter { it.isNotEmpty() }.toSet()
        return progs.isNotEmpty() && allowed.containsAll(progs)
    }
}

fun isImage(path: String?) = path != null && Regex("\\.(png|jpe?g|webp|gif)$", RegexOption.IGNORE_CASE).containsMatchIn(path)

/** A path's last part, as a step names a file: `/root/w/a.png` is `a.png`. */
internal fun fileName(path: String) = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

/** The first of [keys] with a value that isn't empty; empty when none has one. */
internal fun JSONObject.firstString(vararg keys: String) = keys.firstNotNullOfOrNull { k -> optString(k).takeIf { it.isNotEmpty() } } ?: ""

/** A JSON object of [fields]; a null value leaves its field out. */
internal fun json(vararg fields: Pair<String, Any?>) = JSONObject().apply { for ((k, v) in fields) put(k, v) }

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
