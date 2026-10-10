package com.edge.mobile

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The desktop's empty-state hexagon (index.html .es-icon), bobbing 8px over 4s like @keyframes float. */
@Composable
fun FloatingHex(modifier: Modifier = Modifier) {
    val c = LocalEdge.current
    val t = rememberInfiniteTransition(label = "float")
    val dy by t.animateFloat(
        0f, -8f,
        infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dy",
    )
    Canvas(modifier.size(56.dp).graphicsLayer { translationY = dy * density }) {
        val u = size.width / 24f                       // the svg is drawn in a 24x24 viewBox
        fun hex(vararg p: Float) = Path().apply {
            moveTo(p[0] * u, p[1] * u)
            for (i in 2 until p.size step 2) lineTo(p[i] * u, p[i + 1] * u)
            close()
        }
        drawPath(hex(12f, 2f, 22f, 7f, 22f, 17f, 12f, 22f, 2f, 17f, 2f, 7f), c.accent.copy(alpha = 0.3f), style = Stroke(width = u))
        drawPath(hex(12f, 6f, 18f, 9.5f, 18f, 16.5f, 12f, 20f, 6f, 16.5f, 6f, 9.5f), c.accent.copy(alpha = 0.06f))
        drawCircle(c.accent.copy(alpha = 0.4f), radius = 2f * u, center = Offset(12f * u, 12f * u))
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    val c = LocalEdge.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FloatingHex(Modifier.padding(bottom = 8.dp))
        Text(title, color = c.text2, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
        Text(body, color = c.text3, fontSize = 13.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
    }
}
