package dev.omalauncher.widgets

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.strings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where an item sits folded: its [page], and its [cell] (null while that page hasn't been laid out); it's never
 * narrower than [minWidth] cells.
 */
data class Placed(
    val key: String,
    val page: Int,
    val cell: Cell?,
    val minWidth: Int = 1,
)

/**
 * The open layout a foldable starts from the first time it's unfolded: what home showed there without it, folded
 * pages side by side. Folded pages 2k-1 and 2k become open page k, the second shifted right by a folded grid's
 * [foldedColumns]; the pair, twice that wide, then moves onto the open grid's [openColumns] (see [Cell.rescaled]),
 * each tile at least its [minWidth]. A tile that lands on one before it on its open page loses its cell (null), so
 * that page places it afresh.
 */
fun seedOpen(
    folded: List<Placed>,
    foldedColumns: Int,
    openColumns: Int,
    minWidth: (String) -> Int,
): Map<String, Pair<Int, Cell?>> {
    val span = 2 * foldedColumns
    val kept = HashMap<Int, MutableList<Cell>>()
    return folded.associate { p ->
        val page = openPageOf(p.page)
        val right = p.page.coerceAtLeast(1) % 2 == 0
        val cell =
            p.cell
                ?.let { if (right) it.copy(x = it.x + foldedColumns) else it }
                ?.rescaled(span, openColumns, minWidth(p.key))
                ?.takeIf { c -> kept[page].orEmpty().none { it.overlaps(c) } }
                ?.also { kept.getOrPut(page) { ArrayList() } += it }
        p.key to (page to cell)
    }
}

/** Home's two arrangements on a foldable: [Folded] (and on phones and tablets), [Open] on the inner screen. */
enum class Profile {
    Folded,
    Open,
    ;

    val other get() = if (this == Open) Folded else Open
}

/** The open page folded page [page] pairs into: folded pages 2k-1 and 2k sit side by side on open page k. */
fun openPageOf(page: Int) = (page.coerceAtLeast(1) + 1) / 2

/** The first folded page of those open page [page] holds: its left half. */
fun foldedPageOf(page: Int) = page * 2 - 1

/** What adding something from the home screen comes to; see [HomeProfiles.arrive]. */
enum class Arrival {
    /** It didn't exist: made now, shown only in the profile showing. */
    New,

    /** It exists but is hidden here: brought back instead of a second copy. */
    Reveal,

    /** It exists and shows here already. */
    Present,
}

/**
 * Folded and open home layouts, and what each one hides. Folded is the phone's layout, kept as launchers always
 * saved it; open is the inner screen's own with home.unfolded-layout, home.unfolded-columns wide, so a tile can span
 * the hinge. The two start alike ([seed]) and then go their own ways: each hides what the user took off it, and
 * something made on one shows only there until it's brought to the other.
 *
 * While the setting is off ([enabled]) there's one home: nothing is hidden or seeded, and something new shows in
 * both. Forgetting still clears both layouts, so turning it on later finds nothing stale.
 */
class HomeProfiles(
    private val prefs: SharedPreferences,
    val folded: WidgetLayout,
    val open: WidgetLayout,
    val enabled: () -> Boolean,
) {
    var active by mutableStateOf(Profile.Folded)

    /** The layout showing. */
    val layout get() = layoutOf(active)

    val layouts get() = listOf(folded, open)

    fun layoutOf(profile: Profile) = if (profile == Profile.Open) open else folded

    private val hidden = mapOf(Profile.Folded to mutableStateListOf<String>(), Profile.Open to mutableStateListOf<String>())

    /** Whether the open layout was made from the folded one yet; see [seed]. */
    var seeded = false
        private set

    /** Open pages live in [WidgetLayout]'s plain map; this is read with them so pages recompose when one moves. */
    private var openPages by mutableIntStateOf(0)

    init {
        (Versioned.read(prefs.getString(STATE, null), VERSION) as? JSONObject)?.let { data ->
            data.optJSONObject("hidden")?.let { h ->
                for (p in Profile.entries) h.optJSONArray(p.name.lowercase())?.strings()?.let(hidden.getValue(p)::addAll)
            }
            seeded = data.optBoolean("seeded")
        }
    }

    /** Whether [key] is off [profile]'s home; never while the inner screen has no home of its own ([enabled]). */
    fun isHidden(
        key: String,
        profile: Profile = active,
    ) = enabled() && key in hidden.getValue(profile)

    fun hiddenKeys(profile: Profile = active): List<String> = if (enabled()) hidden.getValue(profile).toList() else emptyList()

    /** Takes [key] off [profile]'s home; its cell there goes, the other profile keeps it. */
    fun hide(
        key: String,
        profile: Profile = active,
    ) {
        if (key !in hidden.getValue(profile)) hidden.getValue(profile) += key
        layoutOf(profile).clearCell(key)
        save()
    }

    /** Brings [key] back to [profile]'s home. Any cell it kept there (from while the setting was off) goes, so its page places it afresh rather than over what moved in. */
    fun unhide(
        key: String,
        profile: Profile = active,
    ) {
        if (!hidden.getValue(profile).remove(key)) return
        layoutOf(profile).clearCell(key)
        save()
    }

    /** [key] is gone for good: no cell, page or hidden mark in either profile, so something new by that name starts fresh. */
    fun forget(key: String) {
        var changed = false
        for (list in hidden.values) changed = list.remove(key) || changed
        var placed = false
        for (l in layouts) placed = l.remove(key) || placed
        if (changed || placed) openPages++
        if (changed) save()
    }

    /**
     * [to] takes [from]'s cell, open page and hidden marks in both profiles, and [from] is forgotten: a folder made from
     * an app, or the app left when one dissolves. In a layout where [to] has a cell of its own, it keeps that and its page.
     */
    fun inherit(
        from: String,
        to: String,
    ) {
        for (l in layouts) {
            if (l.cell(to) != null) continue
            l.cell(from)?.let { l.set(to, it) }
            l.page(from)?.let { l.setPage(to, it, persist = false) }
        }
        for (list in hidden.values) if (from in list && to !in list) list += to
        forget(from)
        for (l in layouts) l.save()
    }

    /**
     * The page [item] is on in [profile]. Folded, its own. Open, the one it was put on there; something that
     * arrived some other way (an agent wrote a widget file) is on the open page its folded page pairs into.
     */
    fun pageIn(
        item: HomeItem,
        profile: Profile = active,
    ): Int {
        if (profile == Profile.Folded) return item.page
        openPages
        return open.page(item.key) ?: openPageOf(item.page)
    }

    fun setOpenPage(
        key: String,
        page: Int,
    ) {
        open.setPage(key, page)
        openPages++
    }

    /**
     * Something is being added from the home screen under [key]. New, it shows only in the profile showing:
     * the other one hides it, but only once the open layout exists ([seeded]): before the first unfold, [seed] places
     * everything from the folded home, so nothing needs hiding there; nor while there's one home ([enabled]).
     * One that [existed] and is hidden here comes back rather than as a second copy.
     */
    fun arrive(
        key: String,
        existed: Boolean,
    ): Arrival {
        if (existed) return if (isHidden(key)) Arrival.Reveal else Arrival.Present
        hidden.getValue(active).remove(key)
        if (enabled() && seeded && !isHidden(key, active.other)) hidden.getValue(active.other) += key
        save()
        return Arrival.New
    }

    /**
     * Shows [profile]. The first unfold makes the open layout from the folded one ([seed]), but only once
     * everything home holds has loaded ([ready]): a launcher started unfolded shows before its widgets are read,
     * and seeding then would leave them out for good. Until then, [catchUp] seeds once they're in.
     */
    fun show(
        profile: Profile,
        ready: Boolean,
        folded: () -> List<Placed>,
    ) {
        active = profile
        catchUp(ready, folded)
    }

    /** Seeds the open layout if it's showing, not made yet, and everything has loaded ([ready]); see [show]. */
    fun catchUp(
        ready: Boolean,
        folded: () -> List<Placed>,
    ) {
        if (ready && enabled() && active == Profile.Open && !seeded) seed(folded())
    }

    /**
     * Once the open layout exists, each of [items] without an open page of its own gets the one it shows on now,
     * the open page its folded page pairs into: something that arrived some other way (an agent wrote a widget
     * file) then stays put open when it's moved folded, as the two layouts go their own ways after [seed].
     * While there's one home ([enabled]) nothing is pinned: turned on, the inner screen pairs what came meanwhile.
     */
    fun settle(items: List<HomeItem>) {
        if (!seeded || !enabled()) return
        var changed = false
        for (item in items) {
            if (open.page(item.key) != null) continue
            open.setPage(item.key, pageIn(item, Profile.Open), persist = false)
            changed = true
        }
        if (changed) {
            open.save()
            openPages++
        }
    }

    /**
     * The first unfold: the open layout starts as home looked there before, folded pages side by side; see [seedOpen].
     * Anything the open grid placed on its own before this (it shows while widgets load) starts over from the folded place.
     * Not while there's one home ([enabled]).
     */
    fun seed(foldedItems: List<Placed>) {
        if (seeded || !enabled()) return
        val minWidth = foldedItems.associate { it.key to it.minWidth }
        for ((key, place) in seedOpen(foldedItems, folded.columns, open.columns) { minWidth[it] ?: 1 }) {
            open.setPage(key, place.first, persist = false)
            place.second?.let { open.set(key, it) } ?: open.clearCell(key, persist = false)
        }
        open.save()
        for (key in hidden.getValue(Profile.Folded)) if (!isHidden(key, Profile.Open)) hidden.getValue(Profile.Open) += key
        seeded = true
        openPages++
        save()
    }

    private fun save() {
        val h = JSONObject()
        for (p in Profile.entries) h.put(p.name.lowercase(), JSONArray(hidden.getValue(p).toList()))
        prefs.edit().putString(STATE, Versioned.write(VERSION, JSONObject().put("hidden", h).put("seeded", seeded))).apply()
    }

    companion object {
        /** The open layout starts [unfoldedColumns] wide; [enabled] reads home.unfolded-layout. */
        fun of(
            context: Context,
            unfoldedColumns: Int,
            enabled: () -> Boolean,
        ) = HomeProfiles(
            context.getSharedPreferences("home_profiles", Context.MODE_PRIVATE),
            WidgetLayout.phone(context),
            WidgetLayout.unfolded(context, unfoldedColumns),
            enabled,
        )

        private const val STATE = "state"
        private const val VERSION = 1
    }
}
