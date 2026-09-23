package com.airsink.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airsink.airplay.AirPlayKind
import com.airsink.airpods.FormFactor
import com.airsink.ui.theme.Ios

private val White = Color(0xFFFAFAFA)
private val Shade = Color(0xFFD9D9DE)
private val DeepShade = Color(0xFFB9B9C0)
private val Grille = Color(0xFF2C2C2E)

/** Stylised illustrations of the supported devices, drawn in code. */
@Composable
fun HeadphonesArt(formFactor: FormFactor, modifier: Modifier = Modifier, size: Dp = 120.dp, showCase: Boolean = true) {
    Canvas(modifier.size(size)) {
        when (formFactor) {
            FormFactor.OVER_EAR -> drawOverEar()
            else -> {
                if (showCase) {
                    drawCase(Offset(this.size.width * 0.5f, this.size.height * 0.73f), this.size.width * 0.46f)
                    drawBud(Offset(this.size.width * 0.33f, this.size.height * 0.2f), this.size.width * 0.22f, mirrored = false)
                    drawBud(Offset(this.size.width * 0.67f, this.size.height * 0.2f), this.size.width * 0.22f, mirrored = true)
                } else {
                    drawBud(Offset(this.size.width * 0.33f, this.size.height * 0.3f), this.size.width * 0.3f, mirrored = false)
                    drawBud(Offset(this.size.width * 0.67f, this.size.height * 0.3f), this.size.width * 0.3f, mirrored = true)
                }
            }
        }
    }
}

@Composable
fun BudArt(mirrored: Boolean, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Canvas(modifier.size(size)) { drawBud(Offset(this.size.width / 2, this.size.height * 0.22f), this.size.width * 0.8f, mirrored) }
}

@Composable
fun CaseArt(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Canvas(modifier.size(size)) { drawCase(center, this.size.width * 0.9f) }
}

@Composable
fun SpeakerArt(kind: AirPlayKind, modifier: Modifier = Modifier, size: Dp = 56.dp, active: Boolean = false) {
    val glow by rememberInfiniteTransition(label = "siri").animateFloat(
        0f, 360f, infiniteRepeatable(tween(4000), RepeatMode.Restart), label = "siriRotation",
    )
    val dark = Ios.colors.isDark
    Canvas(modifier.size(size)) {
        when (kind) {
            AirPlayKind.HOMEPOD -> drawHomePod(dark, active, glow)
            AirPlayKind.HOMEPOD_MINI -> drawHomePodMini(dark, active, glow)
            AirPlayKind.APPLE_TV -> drawAppleTv()
            AirPlayKind.SONOS -> drawSonos(dark)
            else -> drawGenericSpeaker(dark)
        }
    }
}

@Composable
fun SonosArt(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val dark = Ios.colors.isDark
    Canvas(modifier.size(size)) { drawSonos(dark) }
}

// ---- Drawing ---------------------------------------------------------------------------

private fun DrawScope.drawBud(top: Offset, width: Float, mirrored: Boolean) {
    scale(if (mirrored) -1f else 1f, 1f, pivot = top) {
        val head = width * 0.62f
        // Stem
        val stemW = width * 0.2f
        val stemTop = top.y + head * 0.55f
        drawRoundRect(
            Brush.horizontalGradient(listOf(Shade, White, DeepShade), startX = top.x - stemW, endX = top.x + stemW),
            topLeft = Offset(top.x + head * 0.12f, stemTop),
            size = Size(stemW, width * 0.95f),
            cornerRadius = CornerRadius(stemW / 2),
        )
        // Head
        drawOval(
            Brush.radialGradient(listOf(White, Shade, DeepShade), center = Offset(top.x - head * 0.15f, top.y + head * 0.3f), radius = head),
            topLeft = Offset(top.x - head / 2, top.y),
            size = Size(head, head * 0.92f),
        )
        // Speaker mesh
        drawOval(
            Grille.copy(alpha = 0.85f),
            topLeft = Offset(top.x - head * 0.36f, top.y + head * 0.3f),
            size = Size(head * 0.2f, head * 0.26f),
        )
    }
}

private fun DrawScope.drawCase(center: Offset, width: Float) {
    val h = width * 0.78f
    val topLeft = Offset(center.x - width / 2, center.y - h / 2)
    drawRoundRect(
        Brush.verticalGradient(listOf(White, Shade), startY = topLeft.y, endY = topLeft.y + h),
        topLeft = topLeft, size = Size(width, h), cornerRadius = CornerRadius(width * 0.3f),
    )
    // Lid seam
    drawLine(DeepShade, Offset(topLeft.x + width * 0.04f, topLeft.y + h * 0.32f), Offset(topLeft.x + width * 0.96f, topLeft.y + h * 0.32f), strokeWidth = width * 0.012f)
    // Status light
    drawCircle(Color(0xFF34C759), radius = width * 0.025f, center = Offset(center.x, topLeft.y + h * 0.55f))
}

private fun DrawScope.drawOverEar() {
    val w = size.width
    val h = size.height
    drawArc(
        Brush.verticalGradient(listOf(Shade, DeepShade)), 180f, 180f, false,
        topLeft = Offset(w * 0.2f, h * 0.1f), size = Size(w * 0.6f, h * 0.7f),
        style = Stroke(w * 0.07f),
    )
    listOf(0.14f, 0.62f).forEach { x ->
        drawRoundRect(
            Brush.verticalGradient(listOf(White, Shade)),
            topLeft = Offset(w * x, h * 0.4f), size = Size(w * 0.24f, h * 0.42f),
            cornerRadius = CornerRadius(w * 0.1f),
        )
        drawRoundRect(
            DeepShade, topLeft = Offset(w * (x + 0.04f), h * 0.46f), size = Size(w * 0.16f, h * 0.3f),
            cornerRadius = CornerRadius(w * 0.07f),
        )
    }
}

private fun DrawScope.siriGlow(center: Offset, radius: Float, angle: Float) {
    rotate(angle, pivot = center) {
        drawCircle(
            Brush.sweepGradient(
                listOf(Color(0xFFFF2D92), Color(0xFF5E5CE6), Color(0xFF32ADE6), Color(0xFF30D158), Color(0xFFFF2D92)),
                center = center,
            ),
            radius = radius, center = center,
        )
    }
    drawCircle(Color.White.copy(alpha = 0.35f), radius = radius * 0.45f, center = center)
}

private fun DrawScope.drawHomePod(dark: Boolean, active: Boolean, angle: Float) {
    val w = size.width * 0.62f
    val h = size.height * 0.9f
    val left = (size.width - w) / 2
    val top = size.height * 0.05f
    val body = if (dark) listOf(Color(0xFF3A3A3C), Color(0xFF1C1C1E)) else listOf(Color(0xFFF5F5F7), Color(0xFFD1D1D6))
    drawRoundRect(Brush.horizontalGradient(body.reversed() + body, startX = left, endX = left + w), Offset(left, top), Size(w, h), CornerRadius(w * 0.42f))
    // Mesh texture
    val step = w / 9
    for (row in 1 until 14) {
        val y = top + h * 0.12f + row * (h * 0.8f / 14)
        for (col in 1 until 9) {
            drawCircle(Color.Black.copy(alpha = if (dark) 0.25f else 0.06f), radius = step * 0.12f, center = Offset(left + col * step, y))
        }
    }
    // Top surface
    val topCenter = Offset(size.width / 2, top + h * 0.06f)
    drawOval(if (dark) Color(0xFF111113) else Color(0xFFE5E5EA), Offset(left + w * 0.1f, top + h * 0.01f), Size(w * 0.8f, h * 0.1f))
    if (active) siriGlow(topCenter, w * 0.2f, angle)
}

private fun DrawScope.drawHomePodMini(dark: Boolean, active: Boolean, angle: Float) {
    val d = size.width * 0.86f
    val c = Offset(size.width / 2, size.height * 0.55f)
    val body = if (dark) listOf(Color(0xFF48484A), Color(0xFF1C1C1E)) else listOf(Color(0xFFFFFFFF), Color(0xFFC7C7CC))
    drawCircle(Brush.radialGradient(body, center = Offset(c.x - d * 0.15f, c.y - d * 0.1f), radius = d * 0.7f), radius = d / 2, center = c)
    // Flat top
    val topY = c.y - d * 0.36f
    drawOval(if (dark) Color(0xFF111113) else Color(0xFFE5E5EA), Offset(c.x - d * 0.34f, topY - d * 0.08f), Size(d * 0.68f, d * 0.16f))
    if (active) siriGlow(Offset(c.x, topY), d * 0.14f, angle)
}

private fun DrawScope.drawSonos(dark: Boolean) {
    val w = size.width * 0.56f
    val h = size.height * 0.86f
    val left = (size.width - w) / 2
    val top = size.height * 0.07f
    val body = if (dark) listOf(Color(0xFFEDEDED), Color(0xFFBDBDBD)) else listOf(Color(0xFF3A3A3C), Color(0xFF111111))
    drawRoundRect(Brush.horizontalGradient(body.reversed() + body, startX = left, endX = left + w), Offset(left, top), Size(w, h), CornerRadius(w * 0.18f))
    val perf = if (dark) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.07f)
    val step = w / 8
    for (row in 0 until 16) for (col in 1 until 8) {
        drawCircle(perf, radius = step * 0.13f, center = Offset(left + col * step, top + h * 0.2f + row * (h * 0.75f / 16)))
    }
    // Touch controls strip
    drawRoundRect(if (dark) Color(0xFF9A9A9A) else Color(0xFF636366), Offset(left + w * 0.3f, top + h * 0.06f), Size(w * 0.4f, h * 0.02f), CornerRadius(h))
}

private fun DrawScope.drawAppleTv() {
    val w = size.width * 0.8f
    val h = size.height * 0.3f
    val left = (size.width - w) / 2
    val top = (size.height - h) / 2
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF3A3A3C), Color(0xFF111111))), Offset(left, top), Size(w, h), CornerRadius(w * 0.12f))
    drawRoundRect(Color.White.copy(alpha = 0.08f), Offset(left, top), Size(w, h * 0.12f), CornerRadius(w * 0.12f))
}

private fun DrawScope.drawGenericSpeaker(dark: Boolean) {
    val w = size.width * 0.6f
    val h = size.height * 0.82f
    val left = (size.width - w) / 2
    val top = size.height * 0.09f
    drawRoundRect(if (dark) Color(0xFF3A3A3C) else Color(0xFF8E8E93), Offset(left, top), Size(w, h), CornerRadius(w * 0.16f))
    val c = Offset(size.width / 2, top + h * 0.62f)
    drawCircle(Color.Black.copy(alpha = 0.35f), radius = w * 0.3f, center = c)
    drawCircle(Color.Black.copy(alpha = 0.45f), radius = w * 0.12f, center = Offset(size.width / 2, top + h * 0.22f))
}
