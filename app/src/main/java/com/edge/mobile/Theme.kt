package com.edge.mobile

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colors copied from the desktop app's [data-theme] blocks in styles.css. */
data class EdgeColors(
    val id: String, val label: String,
    val base: Color, val side: Color, val chat: Color, val elevated: Color, val active: Color,
    val accent: Color, val msgOut: Color, val msgIn: Color,
    val text1: Color, val text2: Color, val text3: Color,
    val online: Color, val offline: Color,
)

private fun c(v: Long) = Color(0xFF000000 or v)

object Themes {
    val all = listOf(
        EdgeColors("ocean", "Ocean", c(0x070b16), c(0x0c1020), c(0x080c18), c(0x111827), c(0x19243a), c(0x4d9fff), c(0x1a3d7a), c(0x141c2e), c(0xe4eaf6), c(0x7a8caa), c(0x45566a), c(0x22c55e), c(0x3d4f65)),
        EdgeColors("forest", "Forest", c(0x060d08), c(0x0a120b), c(0x070e08), c(0x0f1e12), c(0x182a1c), c(0x4ade80), c(0x14421e), c(0x101e13), c(0xe2f0e5), c(0x6a9272), c(0x3a5540), c(0x4ade80), c(0x2a3d2e)),
        EdgeColors("seafoam", "Seafoam", c(0x050e0e), c(0x09191a), c(0x060f10), c(0x0e2022), c(0x162e30), c(0x2dd4bf), c(0x0f3d3a), c(0x0c2224), c(0xddf4f2), c(0x5fa09c), c(0x2e5856), c(0x2dd4bf), c(0x1e3a3a)),
        EdgeColors("ember", "Ember", c(0x0f0a06), c(0x180f08), c(0x100b07), c(0x231508), c(0x321f10), c(0xfb923c), c(0x4a2010), c(0x1e1208), c(0xf5e8dc), c(0x9a7055), c(0x5a3d28), c(0xfbbf24), c(0x3a2210)),
        EdgeColors("violet", "Violet", c(0x080610), c(0x100d1c), c(0x090710), c(0x171228), c(0x221c3c), c(0xa78bfa), c(0x2e1a6a), c(0x16102a), c(0xede8ff), c(0x7c6aab), c(0x453a6a), c(0xa78bfa), c(0x2a2040)),
        EdgeColors("slate", "Slate", c(0x0a0c10), c(0x111318), c(0x0b0d12), c(0x181b22), c(0x232840), c(0x38bdf8), c(0x0f3558), c(0x131822), c(0xe0e8f4), c(0x637a96), c(0x384555), c(0x38bdf8), c(0x1e2a38)),
        EdgeColors("rose", "Rose", c(0x100608), c(0x1c0c10), c(0x110708), c(0x280f14), c(0x3a1820), c(0xfb7185), c(0x5a1a28), c(0x22101a), c(0xf5e0e4), c(0xa06070), c(0x5a3040), c(0xfb7185), c(0x3a1520)),
    )
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all[0]
}

val LocalEdge = staticCompositionLocalOf { Themes.all[0] }

/** Same hash and palette as peerColor() in the desktop renderer, so a device gets the same avatar color on both. */
fun peerColor(id: String): Color {
    val palette = listOf(0x3b82f6, 0x8b5cf6, 0x06b6d4, 0x10b981, 0xf59e0b, 0xec4899, 0xef4444, 0x6366f1)
    var h = 0
    for (ch in id) h = (h shl 5) - h + ch.code
    return c(palette[(Math.abs(h.toLong()) % 8).toInt()].toLong())
}

fun initials(name: String): String =
    name.trim().split(Regex("\\s+")).mapNotNull { it.firstOrNull()?.toString() }.joinToString("").take(2).uppercase().ifEmpty { "?" }
