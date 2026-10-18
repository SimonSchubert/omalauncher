package dev.omalauncher.widgets.lang

/*
 * The compiler's numeric fast path; see Ev.fast. A node that has it evaluates as a
 * boundary: its children run on doubles, and the result is boxed once.
 */

/** Could be a number: not a constant of another kind, which would always leave the fast path. */
internal fun numberish(ev: Ev) = ev.const == null || ev.const is Num

/** [ev]'s fast form, or reading it the general way: a number, or NaN for anything else. */
internal fun fastOf(ev: Ev): Fast =
    ev.fast ?: Fast { env ->
        val v = ev(env)
        if (v is Num) v.v else Double.NaN
    }

/** [slow] evaluated through [fast], falling back to [slow] when an input isn't a number. */
internal fun numeric(
    slow: Ev,
    fast: Fast,
): Ev {
    if (slow.const != null) return slow
    return Ev(
        null,
        { env ->
            val d = fast.of(env)
            if (d.isNaN()) slow(env) else Num(d)
        },
        fast,
    )
}

internal fun arithmetic(
    op: String,
    a: Ev,
    b: Ev,
    slow: Ev,
): Ev {
    if (slow.const != null || !numberish(a) || !numberish(b)) return slow
    val x = fastOf(a)
    val y = fastOf(b)
    // Same results as Builtins.operator for two numbers.
    when (op) {
        "+" -> return numeric(slow) { env -> x.of(env) + y.of(env) }
        "-" -> return numeric(slow) { env -> x.of(env) - y.of(env) }
        "*" -> return numeric(slow) { env -> x.of(env) * y.of(env) }
        // Dividing by zero gives 0, but not for a NaN: that may be a null.
        "/" -> return numeric(slow) { env ->
            val p = x.of(env)
            val q = y.of(env)
            if (q == 0.0 && !p.isNaN()) 0.0 else p / q
        }
        "%" -> return numeric(slow) { env ->
            val p = x.of(env)
            val q = y.of(env)
            if (q == 0.0 && !p.isNaN()) 0.0 else ((p % q) + q) % q
        }
    }
    // A comparison of two plain values gains nothing: Builtins.operator compares numbers unboxed already.
    if (a.fast == null && b.fast == null) return slow
    val test: ((Int) -> Boolean)? =
        when (op) {
            "<" -> { c -> c < 0 }
            "<=" -> { c -> c <= 0 }
            ">" -> { c -> c > 0 }
            ">=" -> { c -> c >= 0 }
            else -> null
        }
    if (test == null) return slow
    return Ev(null) { env ->
        val p = x.of(env)
        val q = y.of(env)
        if (p.isNaN() || q.isNaN()) slow(env) else bool(test(p.compareTo(q)))
    }
}

internal fun numericCall(
    name: String,
    args: List<Ev>,
    slow: Ev,
): Ev {
    if (!args.all(::numberish)) return slow
    val f = args.map(::fastOf)
    val fast: Fast =
        when {
            (name == "min" || name == "max") && f.size >= 2 -> {
                val min = name == "min"
                Fast { env ->
                    env.host.budget.tick()
                    var acc = f[0].of(env)
                    for (i in 1 until f.size) acc = if (min) Math.min(acc, f[i].of(env)) else Math.max(acc, f[i].of(env))
                    acc
                }
            }
            f.size == 1 && name in Builtins.numeric1 -> {
                val g = Builtins.numeric1.getValue(name)
                val x = f[0]
                Fast { env ->
                    env.host.budget.tick()
                    g.of(x.of(env))
                }
            }
            f.size == 2 && name in Builtins.numeric2 -> {
                val g = Builtins.numeric2.getValue(name)
                val x = f[0]
                val y = f[1]
                Fast { env ->
                    env.host.budget.tick()
                    g.of(x.of(env), y.of(env))
                }
            }
            f.size == 3 && name in Builtins.numeric3 -> {
                val g = Builtins.numeric3.getValue(name)
                val x = f[0]
                val y = f[1]
                val z = f[2]
                Fast { env ->
                    env.host.budget.tick()
                    g.of(x.of(env), y.of(env), z.of(env))
                }
            }
            else -> return slow
        }
    return numeric(slow, fast)
}
