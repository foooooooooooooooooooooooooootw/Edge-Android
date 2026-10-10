package com.edge.mobile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Same thresholds and colours as streakColor() in the desktop renderer. */
fun streakColor(n: Int): Color = when {
    n >= 20 -> Color(0xFF00EEFF)
    n >= 10 -> Color(0xFF8D01DF)
    n >= 5 -> Color(0xFFFF2200)
    else -> Color(0xFFFF6600)
}

@Composable
fun StreakBadge(count: Int) {
    val color = streakColor(count)
    val scale = remember { Animatable(0.5f) }
    LaunchedEffect(count) {
        scale.snapTo(0.5f)
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = scale.value.coerceIn(0f, 1f) },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🔥", fontSize = 22.sp)
        Spacer(Modifier.width(5.dp))
        Text(
            "$count", color = color, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
            style = TextStyle(shadow = Shadow(color, Offset.Zero, 12f)),
        )
        Spacer(Modifier.width(5.dp))
        Text("EDGE STREAK", color = color.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.SansSerif, letterSpacing = 0.5.sp)
    }
}
