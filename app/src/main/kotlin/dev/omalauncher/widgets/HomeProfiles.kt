package dev.omalauncher.widgets

/** Home's two arrangements on a foldable: [Folded] (and on phones and tablets), [Open] on the inner screen. */
enum class Profile {
    Folded,
    Open,
    ;

    val other get() = if (this == Open) Folded else Open
}
