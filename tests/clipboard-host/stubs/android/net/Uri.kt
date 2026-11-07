package android.net

data class Uri(private val value: String) {
    override fun toString() = value
    companion object {
        fun parse(value: String) = Uri(value)
    }
}
