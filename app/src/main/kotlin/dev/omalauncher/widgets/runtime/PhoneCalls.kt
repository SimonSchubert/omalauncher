package dev.omalauncher.widgets.runtime

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.apps.AppLabels
import dev.omalauncher.system.changeObserver
import dev.omalauncher.system.hasPermission
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import dev.omalauncher.widgets.lang.textOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's calls and contacts, for widgets and owl apps: `source c = android.calls`, and `calls("request")`,
 * `calls("call", n)`, `calls("open", e)`, `calls("contact", x)`, `calls("delete", e)` and `calls("undo")`. The call log
 * (READ_CALL_LOG, the foss build only: Play allows it only to the default phone app) and the contacts (READ_CONTACTS)
 * are only read once a tap's calls("request") allowed them, and calls in other apps, WhatsApp and the like, only kept
 * from then on ([VoipCalls]), the phone's own too where the call log can't be read. Read while a widget showing them is
 * on screen, like every android source.
 */
class PhoneCalls(
    private val context: Context,
    private val media: Media,
) {
    /** Asks the activity in front for [permission], then says whether it was granted; set as it resumes. */
    var ask: ((permission: String, granted: (Boolean) -> Unit) -> Unit)? = null

    private var log by mutableStateOf(check(Manifest.permission.READ_CALL_LOG))
    private var contacts by mutableStateOf(check(Manifest.permission.READ_CONTACTS))
    private var calling by mutableStateOf(check(Manifest.permission.CALL_PHONE))

    /** Bumped as an activity resumes, since notification access is given in Android's settings, not here. */
    private var resumed by mutableIntStateOf(0)

    /**
     * Entries calls("delete") took away, by id: left out of android.calls until calls("undo"), or once deleted, until
     * a read no longer has them, so they don't show again for the moment the list takes to be read anew.
     */
    private var hidden by mutableStateOf(emptySet<String>())

    /** Those of [hidden] past undo: deleted, or being deleted. */
    private var settled by mutableStateOf(emptySet<String>())
    private val deleting = HashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val labels = AppLabels(context)

    private fun check(permission: String) = context.hasPermission(permission)

    fun recheck() {
        log = check(Manifest.permission.READ_CALL_LOG)
        contacts = check(Manifest.permission.READ_CONTACTS)
        calling = check(Manifest.permission.CALL_PHONE)
        resumed++
    }

    /**
     * calls("request") from a tap: from now on calls in other apps are kept, then Android's dialogs for the call log
     * (where this build has it), the contacts and placing calls, each one not yet allowed, one after another; then
     * notification access, which those calls are seen through, if it's missing.
     */
    fun request() {
        VoipCalls.record(context)
        recheck()
        val log = Manifest.permission.READ_CALL_LOG.takeIf { callLogDeclared(context) }
        val missing = listOfNotNull(log, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE).filterNot(::check)
        val ask = ask
        // A preview or a widget behind another app has no activity to ask from.
        if (missing.isEmpty() || ask == null) return accessThen()

        fun next(i: Int) {
            if (i == missing.size) return accessThen()
            ask(missing[i]) {
                recheck()
                next(i + 1)
            }
        }
        next(0)
    }

    private fun accessThen() {
        recheck()
        if (!media.granted) runCatching { media.openAccessSettings() }
    }

    /**
     * calls("call", n): a phone call to [target], a number or an entry of calls or contacts (its first number). Placed at
     * once where placing calls is allowed, else the dialer opens with the number to press call. A call in another app is
     * returned in that app, never on the phone: a WhatsApp call to someone saved with WhatsApp starts one there (a video
     * call for a video call), through the contact's WhatsApp entry as the contacts app does; otherwise the app opens.
     */
    fun call(target: Value?) {
        val entry = target as? Obj
        if (entry != null && entry.fields["voip"] == bool(true)) {
            if (entry.fields["package"].textOrNull() == WHATSAPP && entry.fields["callable"] == bool(true) && whatsappCall(entry)) return
            return open(entry)
        }
        val number = numberOf(target) ?: return
        val uri = Uri.fromParts("tel", number, null)
        val intent = Intent(if (calling) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
        start(intent) || start(Intent(Intent.ACTION_DIAL, uri))
    }

    /** Starts a WhatsApp call to [entry]'s number through its WhatsApp row in the contacts; false where there's none. */
    private fun whatsappCall(entry: Obj): Boolean {
        val digits = entry.fields["number"].textOrNull()?.filter(Char::isDigit).orEmpty()
        if (digits.length < 7 || !contacts) return false
        val mime = if (entry.fields["video"] == bool(true)) WA_VIDEO else WA_VOICE
        val id =
            runCatching {
                context.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID),
                    "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.DATA1} = ?", arrayOf(mime, "$digits@s.whatsapp.net"), null,
                )?.use { if (it.moveToFirst()) it.getLong(0) else null }
            }.getOrNull() ?: return false
        val uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id)
        return start(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).setPackage(WHATSAPP))
    }

    /** People saved with WhatsApp, from WhatsApp's own rows in the contacts: who, the contact, and their WhatsApp number. */
    internal class WaPerson(
        val contact: Long,
        val name: String,
        val digits: String,
    )

    private fun whatsappPeople(): List<WaPerson> {
        val out = ArrayList<WaPerson>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME_PRIMARY, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.MIMETYPE} = ?", arrayOf(WA_VOICE), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val digits = c.getString(2)?.substringBefore('@')?.takeIf { d -> d.isNotEmpty() && d.all(Char::isDigit) } ?: continue
                out += WaPerson(c.getLong(0), c.getString(1).orEmpty(), digits)
            }
        }
        return out
    }

    /**
     * calls("open", e): an entry of calls in the app it was made in: a phone call's number in the dialer, without
     * calling, and a call in another app that app, on the chat with its number where it has one (WhatsApp's wa.me).
     */
    fun open(target: Value?) {
        val entry = target as? Obj
        val pkg = entry?.fields?.get("package").textOrNull()
        if (entry?.fields?.get("voip") == bool(true) && pkg != null) {
            val digits = entry.fields["number"].textOrNull()?.filter(Char::isDigit).orEmpty()
            if (pkg == WHATSAPP && digits.length >= 7 && start(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).setPackage(pkg))) return
            context.packageManager.getLaunchIntentForPackage(pkg)?.let { start(it) }
            return
        }
        val number = numberOf(target) ?: return
        start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
    }

    /**
     * calls("contact", x): the contacts app on a contact (one from contacts, or an entry of calls for a known number),
     * or offering to add a number it doesn't know.
     */
    fun contact(target: Value?) {
        val entry = target as? Obj
        val id = entry?.fields?.get("contact").textOrNull()?.toLongOrNull()
        if (id != null) {
            start(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)))
            return
        }
        val number = numberOf(target) ?: return
        start(Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT, Uri.fromParts("tel", number, null)))
    }

    private fun numberOf(target: Value?): String? {
        val entry = target as? Obj
        val number =
            when {
                entry == null -> target.textOrNull()
                entry.fields["numbers"] is Lst -> (entry.fields["numbers"] as Lst).items.firstOrNull().textOrNull()
                else -> entry.fields["number"].textOrNull()
            }
        return number?.trim()?.takeIf { n -> n.any(Char::isDigit) }
    }

    /**
     * calls("delete", e): takes an entry of calls out of the list at once and deletes it [UNDO_MS] later, unless
     * calls("undo") brings it back first: one from Android's call log from there (where the dialer loses it too, so it
     * needs WRITE_CALL_LOG, asked for here the first time), one kept here from a notification from the calls kept. Gone
     * for good after that.
     */
    fun delete(target: Value?) {
        val id = (target as? Obj)?.fields?.get("id").textOrNull() ?: return
        if (id in hidden) return
        val row = id.removePrefix("log:").takeIf { it != id }?.toLongOrNull()
        val kept = id.removePrefix("kept:").takeIf { it != id }
        when {
            kept != null ->
                later(id) {
                    VoipCalls.forget(context, kept).join()
                    true
                }
            // Only the build with the call log lists its rows; a made-up id asks nothing of the Play build.
            row != null && callLogDeclared(context) -> {
                val gone = suspend {
                    withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} = ?", arrayOf(row.toString())) }.isSuccess
                    }
                }
                if (check(Manifest.permission.WRITE_CALL_LOG)) return later(id, gone)
                // A preview has no activity to ask from; refused, the entry stays.
                ask?.invoke(Manifest.permission.WRITE_CALL_LOG) { if (it) later(id, gone) }
            }
        }
    }

    private fun later(
        id: String,
        gone: suspend () -> Boolean,
    ) {
        hidden = hidden + id
        deleting[id] =
            scope.launch {
                delay(UNDO_MS)
                // Past undo now: a calls("undo") from here on leaves it, and the delete runs to its end.
                deleting.remove(id)
                settled = settled + id
                val done = withContext(NonCancellable) { gone() }
                // Not deleted (the call log refused): it shows again. Deleted, it stays out until a read has it no more.
                if (!done) {
                    hidden = hidden - id
                    settled = settled - id
                }
            }
    }

    /** Lets go of the deleted entries a new read no longer has, so [hidden] only holds what's still to go. */
    private fun forgetDeleted(read: Value) {
        if (settled.isEmpty()) return
        val ids = ((read as? Obj)?.fields?.get("calls") as? Lst)?.items.orEmpty().mapTo(HashSet()) { (it as? Obj)?.fields?.get("id").textOrNull() }
        val left = settled.filterTo(HashSet()) { it !in ids }
        if (left.isEmpty()) return
        hidden = hidden - left
        settled = settled - left
    }

    /** calls("undo"): every entry calls("delete") took away and hasn't started deleting yet comes back. */
    fun undo() {
        for (job in deleting.values) job.cancel()
        hidden = hidden - deleting.keys
        deleting.clear()
    }

    /** Started from the launcher in front; false where nothing handles it, which mustn't crash the launcher. */
    private fun start(intent: Intent): Boolean = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    /** android.calls: {granted, log, contactsGranted, canCall, voip, calls, contacts, deleting}, the lists empty until each is allowed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun updates(): Flow<Value> =
        snapshotFlow { Grants(log, contacts, calling, resumed) }.flatMapLatest { g ->
            changes(g).mapLatest {
                // Provider queries can block; never on the UI thread. A grant can be taken back at any time.
                withContext(Dispatchers.IO) { value(g) }
            }.catch { if (it is SecurityException) emit(value(Grants(false, false, calling, resumed))) else throw it }
        }.onEach { withContext(Dispatchers.Main.immediate) { forgetDeleted(it) } }
            .combine(snapshotFlow { hidden to settled }) { read, (hidden, settled) -> withoutHidden(read, hidden, settled) }
            .distinctUntilChanged()

    /** What was read, less the entries calls("delete") took away: at once, without reading it all again. */
    private fun withoutHidden(
        read: Value,
        hidden: Set<String>,
        settled: Set<String>,
    ): Value {
        val fields = (read as? Obj)?.fields ?: return read
        val calls = (fields["calls"] as? Lst)?.items.orEmpty()
        return Obj(
            fields +
                mapOf(
                    "calls" to Lst(if (hidden.isEmpty()) calls else calls.filter { (it as? Obj)?.fields?.get("id").textOrNull() !in hidden }),
                    // Only those undo can still bring back.
                    "deleting" to Num((hidden - settled).size.toDouble()),
                ),
        )
    }

    private data class Grants(
        val log: Boolean,
        val contacts: Boolean,
        val calling: Boolean,
        val resumed: Int,
    )

    /** The call log, the contacts and the calls kept from other apps, as each changes. */
    private fun changes(g: Grants): Flow<Unit> =
        callbackFlow {
            val resolver = context.contentResolver
            val observer = changeObserver(Handler(Looper.getMainLooper())) { trySend(Unit) }
            // Without the permission there's nothing to watch, and some Androids refuse to watch it.
            if (g.log) runCatching { resolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer) }
            if (g.contacts) runCatching { resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer) }
            val voip = VoipCalls.changes.onEach { trySend(Unit) }.launchIn(this)
            trySend(Unit)
            awaitClose {
                voip.cancel()
                resolver.unregisterContentObserver(observer)
            }
        }.conflate()

    private fun value(g: Grants): Value {
        val people = if (g.contacts) runCatching { people() }.getOrDefault(emptyList()) else emptyList()
        val byNumber = HashMap<String, Person>()
        val byName = HashMap<String, Person>()
        for (p in people) for (n in p.numbers) byNumber.putIfAbsent(numberKey(n), p)
        // A name finds a contact only when one has it: two people of the same name would call back the wrong one.
        for ((name, same) in people.groupBy { it.name.lowercase() }) same.singleOrNull()?.let { byName[name] = it }
        val dialer = runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull().orEmpty()
        val rows = ArrayList<Pair<Long, Value>>()
        if (g.log) runCatching { rows += logged(dialer, byNumber) }
        val recording = VoipCalls.recording(context)
        if (recording) {
            // WhatsApp's notification has a name and rarely a number: the person saved with WhatsApp under that name has
            // the number WhatsApp calls, which may not be their contact's first.
            val wa = if (g.contacts) runCatching { whatsappPeople() }.getOrDefault(emptyList()) else emptyList()
            val waByDigits = wa.associateBy { numberKey(it.digits) }
            val waByName = wa.groupBy { it.name.lowercase() }
            for (c in VoipCalls.all(context)) {
                if (c.phone) {
                    // Kept only for want of the call log: where it can be read now, it has these calls itself.
                    if (!g.log) rows += c.start to keptPhoneCall(c, dialer, byNumber, byName)
                    continue
                }
                val onWa = if (c.pkg != WHATSAPP) null else whatsappMatch(c.number, c.name, waByDigits, waByName)
                val known = onWa?.let { w -> people.firstOrNull { it.id == w.contact } } ?: c.number.takeIf { it.isNotBlank() }?.let { byNumber[numberKey(it)] }
                    ?: if (c.pkg == WHATSAPP) null else byName[c.name.lowercase()]
                val number = onWa?.let { "+${it.digits}" } ?: c.number.ifBlank { known?.numbers?.singleOrNull().orEmpty() }
                rows += c.start to entry(
                    id = "kept:${c.id}", pkg = c.pkg, app = labels[c.pkg], direction = c.direction, name = known?.name ?: onWa?.name ?: c.name,
                    number = number, video = c.video, start = c.start, duration = c.duration,
                    voip = true, contact = known?.id ?: onWa?.contact, callable = onWa != null,
                )
            }
        }
        val calls = rows.sortedByDescending { it.first }.take(MAX_CALLS).map { it.second }
        return Obj(
            mapOf(
                // The phone's calls are listed: from the call log, or kept from its notifications from calls("request") on.
                "granted" to bool(g.log || recording),
                "log" to bool(g.log),
                "contactsGranted" to bool(g.contacts),
                "canCall" to bool(g.calling),
                "voip" to bool(recording && media.granted),
                "calls" to Lst(calls),
                "contacts" to Lst(people.map { it.value }),
            ),
        )
    }

    /**
     * A call on the phone kept from the phone app's notifications, as the call log would list it: its number from the
     * notification, or from the contact of that name where they have only the one number.
     */
    private fun keptPhoneCall(
        c: VoipCall,
        dialer: String,
        byNumber: Map<String, Person>,
        byName: Map<String, Person>,
    ): Value {
        val known = c.number.takeIf { it.isNotBlank() }?.let { byNumber[numberKey(it)] } ?: c.name.takeIf { it.isNotBlank() }?.let { byName[it.lowercase()] }
        val number = c.number.ifBlank { known?.numbers?.singleOrNull().orEmpty() }
        return entry(
            id = "kept:${c.id}", pkg = dialer.ifEmpty { c.pkg }, app = "phone", direction = c.direction, name = known?.name ?: c.name,
            number = number, video = c.video, start = c.start, duration = c.duration,
            voip = false, contact = known?.id, callable = number.any(Char::isDigit),
        )
    }

    /** The newest [MAX_CALLS] of the call log, named from the contacts where the log has no name (imported ones). */
    private fun logged(
        dialer: String,
        byNumber: Map<String, Person>,
    ): List<Pair<Long, Value>> {
        val out = ArrayList<Pair<Long, Value>>()
        val uri = CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, MAX_CALLS.toString()).build()
        val projection = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.FEATURES)
        context.contentResolver.query(uri, projection, null, null, "${CallLog.Calls.DATE} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val direction = DIRECTION[cursor.getInt(3)] ?: continue
                val number = cursor.getString(1).orEmpty()
                val known = number.takeIf { it.isNotBlank() }?.let { byNumber[numberKey(it)] }
                val start = cursor.getLong(4)
                out += start to entry(
                    id = "log:${cursor.getLong(0)}", pkg = dialer, app = "phone", direction = direction,
                    name = known?.name ?: cursor.getString(2).orEmpty(), number = number,
                    video = cursor.getInt(6) and CallLog.Calls.FEATURES_VIDEO != 0, start = start, duration = cursor.getLong(5),
                    voip = false, contact = known?.id, callable = number.any(Char::isDigit),
                )
            }
        }
        return out
    }

    private fun entry(
        id: String,
        pkg: String,
        app: String,
        direction: String,
        name: String,
        number: String,
        video: Boolean,
        start: Long,
        duration: Long,
        voip: Boolean,
        contact: Long?,
        callable: Boolean,
    ): Value =
        Obj(
            mapOf(
                "id" to Str(id),
                "app" to Str(app),
                "package" to Str(pkg),
                "icon" to Str(if (pkg.isEmpty()) "" else "app:$pkg"),
                "direction" to Str(direction),
                "name" to Str(name),
                "number" to Str(number),
                "dial" to Str(dialable(number)),
                "video" to bool(video),
                "start" to Num(start / 1000.0),
                "duration" to Num(duration.toDouble()),
                "voip" to bool(voip),
                "callable" to bool(callable),
                "contact" to Str(contact?.toString().orEmpty()),
            ),
        )

    private class Person(
        val id: Long,
        val name: String,
        val numbers: List<String>,
        val starred: Boolean,
    ) {
        val value: Value
            get() = Obj(mapOf("contact" to Str(id.toString()), "name" to Str(name), "numbers" to Lst(numbers.map(::Str)), "dial" to Lst(numbers.map { Str(dialable(it)) }), "starred" to bool(starred)))
    }

    /** Every contact with a phone number, by name, each number once however it's written, and each person once. */
    private fun people(): List<Person> {
        val byId = LinkedHashMap<Long, Triple<String, Boolean, LinkedHashMap<String, String>>>()
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.STARRED)
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(2)?.trim()?.takeIf { n -> n.any(Char::isDigit) } ?: continue
                val (_, _, numbers) = byId.getOrPut(cursor.getLong(0)) { Triple(cursor.getString(1).orEmpty(), cursor.getInt(3) != 0, LinkedHashMap()) }
                numbers.putIfAbsent(numberKey(number), number)
            }
        }
        // The same person saved twice (a SIM copy, an account's), with the same name and numbers, is listed once.
        return byId.map { (id, t) -> Person(id, t.first, t.third.values.toList(), t.second) }
            .filter { it.name.isNotBlank() }
            .groupBy { it.name.lowercase() to it.numbers.map(::numberKey).toSet() }
            .map { (_, same) -> same.firstOrNull { it.starred } ?: same.first() }
            // Names before contacts saved as a bare number, which would otherwise sort first.
            .sortedBy { !it.name.first().isLetter() }
            .take(MAX_CONTACTS)
    }

    companion object {
        @Volatile
        private var declared: Boolean? = null

        /**
         * Whether this build may have the call log at all: the foss build declares READ_CALL_LOG
         * (src/foss/AndroidManifest.xml), the Play build doesn't, so it never asks for it.
         */
        fun callLogDeclared(context: Context): Boolean =
            declared ?: runCatching {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                Manifest.permission.READ_CALL_LOG in info.requestedPermissions.orEmpty()
            }.getOrDefault(false).also { declared = it }

        /** Whether the phone's own calls come from the call log now; otherwise [VoipCalls] keeps them from its notifications. */
        fun callLogReadable(context: Context): Boolean = callLogDeclared(context) && context.hasPermission(Manifest.permission.READ_CALL_LOG)

        /** The newest calls listed, the phone's and other apps' together. */
        const val MAX_CALLS = 500
        const val MAX_CONTACTS = 5000

        /** How long a deleted entry can be brought back with calls("undo"). */
        const val UNDO_MS = 5_000L
        const val WHATSAPP = "com.whatsapp"
        private const val WA_VOICE = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
        private const val WA_VIDEO = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"

        /** in, out or missed for each of the call log's types; voicemail, blocked and the rest aren't calls to list. */
        private val DIRECTION =
            mapOf(
                CallLog.Calls.INCOMING_TYPE to "in",
                CallLog.Calls.OUTGOING_TYPE to "out",
                CallLog.Calls.MISSED_TYPE to "missed",
                CallLog.Calls.REJECTED_TYPE to "missed",
                CallLog.Calls.ANSWERED_EXTERNALLY_TYPE to "in",
            )

        /**
         * One number however it's written: +420 777 123 456, 777123456 and 00420777123456 are the same, and so are
         * +47 12345678 and 12345678. Its last eight digits: the whole of an eight-digit national number (Norway,
         * Denmark, Singapore), and enough to tell numbers apart elsewhere.
         */
        fun numberKey(number: String): String = number.filter(Char::isDigit).takeLast(8)

        /**
         * Who a WhatsApp call was with: by the number its notification gave, which is never overridden; without one, the
         * one person saved with WhatsApp under that name, and nobody when two share it or one has two numbers there.
         */
        internal fun <T> whatsappMatch(
            number: String,
            name: String,
            byDigits: Map<String, T>,
            byName: Map<String, List<T>>,
        ): T? =
            if (number.isNotBlank()) byDigits[numberKey(number)] else name.takeIf { it.isNotBlank() }?.let { byName[it.lowercase()]?.singleOrNull() }

        /**
         * A number as it's dialled and searched: its digits, with the + of an international one and 00 read as one,
         * so "+420 777-123 456", "(777) 123.456" and "00420777123456" each become one run that typed digits are found in.
         */
        fun dialable(number: String): String {
            val t = number.trim()
            val digits = t.filter { it.isDigit() || it == '*' || it == '#' }
            return when {
                t.startsWith("+") -> "+$digits"
                digits.startsWith("00") && digits.length > 6 -> "+" + digits.drop(2)
                else -> digits
            }
        }
    }
}
