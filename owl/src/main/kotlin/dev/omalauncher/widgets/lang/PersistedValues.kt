package dev.omalauncher.widgets.lang

/**
 * A widget's `state ... persist` and `history(...) persist` values on disk:
 * `{"v":1,"savedAt":<Unix seconds>,"values":{"name":value}}`. "v" is the
 * format version; values are matched by name, so renamed ones start over.
 */
object PersistedValues {
    /** The slots of [program]'s persisted globals, in the order [encode] takes their values. */
    fun slots(program: Program): List<Int> =
        program.globals.indices.filter { i -> program.globals[i].let { (it is Global.State && it.persist) || (it is Global.Sampled && it.persist) } }

    /** [values], one per slot of [slots], as the file's text; values JSON can't hold are left out. */
    fun encode(
        program: Program,
        values: List<Value>,
        nowSeconds: Long,
    ): String {
        val fields = LinkedHashMap<String, String>()
        slots(program).forEachIndexed { k, i -> Json.write(values[k])?.let { fields[program.globals[i].name] = it } }
        return "{\"v\":1,\"savedAt\":$nowSeconds,\"values\":{" + fields.entries.joinToString(",") { (n, v) -> "${Json.write(Str(n))}:$v" } + "}}"
    }

    /**
     * What to restore from [text], by slot. State comes back as saved. A history
     * comes back trimmed to its size, and only while it's younger than the fastest
     * polling source takes to fill it: an older one would be all gap.
     */
    fun decode(
        program: Program,
        text: String,
        nowSeconds: Double,
    ): Map<Int, Value> {
        val saved = runCatching { Json.parse(text) }.getOrNull() as? Obj ?: return emptyMap()
        val values = saved.fields["values"] as? Obj ?: return emptyMap()
        val age = nowSeconds - (saved.fields["savedAt"]?.num() ?: 0.0)
        val step = program.globals.filterIsInstance<Global.Source>().filter { it.kind != "stream" }.minOfOrNull { it.every }
        val out = LinkedHashMap<Int, Value>()
        for (i in slots(program)) {
            val g = program.globals[i]
            val v = values.fields[g.name] ?: continue
            when (g) {
                is Global.State -> out[i] = v
                is Global.Sampled -> {
                    val items = (v as? Lst)?.items ?: continue
                    if (step == null || age < step * g.keep) out[i] = Lst(items.takeLast(g.keep))
                }
                else -> {}
            }
        }
        return out
    }
}
