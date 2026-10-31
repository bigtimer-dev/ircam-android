package io.github.bigtimer_dev.ircam.thermal

import android.content.Context

/** The 52 built-in palettes of the original program (16384 RGB entries each, index 0 = coldest). */
class Palettes(context: Context) {
    val names: List<String>
    private val data: ByteArray
    private val cache = HashMap<String, IntArray>()

    init {
        val am = context.assets
        names = am.open("palette_names.txt").bufferedReader().readLines().filter { it.isNotBlank() }
        data = am.open("palettes.bin").use { it.readBytes() }
    }

    /** ARGB colours, 16384 entries; inverted when [invert] is set. */
    @Synchronized
    fun argb(name: String, invert: Boolean = false): IntArray {
        val key = "$name|$invert"
        cache[key]?.let { return it }
        var idx = names.indexOf(name)
        if (idx < 0) idx = names.indexOf("Iron").coerceAtLeast(0)
        val base = idx * SIZE * 3
        val out = IntArray(SIZE) { i ->
            val j = if (invert) SIZE - 1 - i else i
            val o = base + j * 3
            (0xFF shl 24) or ((data[o].toInt() and 0xFF) shl 16) or ((data[o + 1].toInt() and 0xFF) shl 8) or (data[o + 2].toInt() and 0xFF)
        }
        if (cache.size > 12) cache.clear()
        cache[key] = out
        return out
    }

    /** A few colour stops for drawing small previews. */
    fun preview(name: String, stops: Int = 24): IntArray {
        val p = argb(name)
        return IntArray(stops) { p[(it * (SIZE - 1)) / (stops - 1)] }
    }

    companion object {
        const val SIZE = 16384

        /** Palettes shown first in the quick picker; the rest follow in the original order. */
        val FEATURED = listOf("Iron", "InfiRay", "Turbo", "Inferno", "Lava", "Gray", "Rain", "Magma", "Medical1", "Hot", "Jet", "Viridis")
    }
}
