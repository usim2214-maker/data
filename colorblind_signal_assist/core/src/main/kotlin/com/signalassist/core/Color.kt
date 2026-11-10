package com.signalassist.core

internal fun argb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

internal inline val Int.red get() = (this shr 16) and 0xFF
internal inline val Int.green get() = (this shr 8) and 0xFF
internal inline val Int.blue get() = this and 0xFF

/**
 * RGB(0..255) -> HSV. out[0]=hue(0..360), out[1]=saturation(0..1), out[2]=value(0..1)
 */
internal fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    out[2] = max / 255f
    out[1] = if (max == 0) 0f else delta.toFloat() / max
    out[0] = when {
        delta == 0 -> 0f
        max == r -> {
            val h = 60f * ((g - b).toFloat() / delta)
            if (h < 0) h + 360f else h
        }
        max == g -> 60f * ((b - r).toFloat() / delta) + 120f
        else -> 60f * ((r - g).toFloat() / delta) + 240f
    }
}
