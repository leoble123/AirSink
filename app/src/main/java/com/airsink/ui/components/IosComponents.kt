package com.airsink.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airsink.ui.theme.Ios
import kotlin.math.roundToInt

// ---- Motion ---------------------------------------------------------------------------

/** The springy "squish" iOS gives tappable cards. */
fun Modifier.bouncyClick(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = this.then(
    Modifier.composedBouncy(enabled, onLongClick, onClick),
)

@Composable
private fun bouncyScale(pressed: Boolean) = animateFloatAsState(
    targetValue = if (pressed) 0.96f else 1f,
    animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
    label = "bounce",
).value

private fun Modifier.composedBouncy(enabled: Boolean, onLongClick: (() -> Unit)?, onClick: () -> Unit) =
    composed {
        var pressed by remember { mutableStateOf(false) }
        val haptics = LocalHapticFeedback.current
        val click by rememberUpdatedState(onClick)
        val longClick by rememberUpdatedState(onLongClick)
        this
            .scale(bouncyScale(pressed))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onLongPress = longClick?.let { lc ->
                        { _: Offset -> haptics.performHapticFeedback(HapticFeedbackType.LongPress); lc() }
                    },
                    onTap = { click() },
                )
            }
    }

// ---- Grouped list ---------------------------------------------------------------------

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Ios.type.footnote,
        color = Ios.colors.secondaryLabel,
        modifier = modifier.padding(start = 36.dp, end = 36.dp, top = 22.dp, bottom = 7.dp),
    )
}

@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Ios.type.footnote,
        color = Ios.colors.secondaryLabel,
        modifier = modifier.padding(start = 36.dp, end = 36.dp, top = 7.dp),
    )
}

/** An "inset grouped" table section: rounded card with hairline separators between rows. */
@Composable
fun Section(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier) {
        if (header != null) SectionHeader(header) else Spacer(Modifier.height(20.dp))
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Ios.colors.card),
            content = content,
        )
        if (footer != null) SectionFooter(footer)
    }
}

@Composable
fun Separator(inset: Dp = 16.dp) {
    Box(
        Modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(Ios.colors.separator),
    )
}

/** The rounded-square colored icon used in iOS Settings. */
@Composable
fun SettingsIcon(icon: ImageVector, background: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(29.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconColor: Color = Ios.colors.blue,
    subtitle: String? = null,
    value: String? = null,
    titleColor: Color = Ios.colors.label,
    chevron: Boolean = false,
    showSeparator: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(if (pressed) Ios.colors.fill else Color.Transparent, label = "rowPress")
    Column(
        modifier
            .fillMaxWidth()
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(interaction, null, onClick = onClick) else Modifier),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                SettingsIcon(icon, iconColor)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = Ios.type.body, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = Ios.type.footnote, color = Ios.colors.secondaryLabel, maxLines = 2)
                }
            }
            if (value != null) {
                Text(value, style = Ios.type.body, color = Ios.colors.secondaryLabel, maxLines = 1)
            }
            trailing?.invoke(this)
            if (chevron) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                    tint = Ios.colors.tertiaryLabel, modifier = Modifier.size(22.dp),
                )
            }
        }
        if (showSeparator) Separator(inset = if (icon != null) 59.dp else 16.dp)
    }
}

@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    iconColor: Color = Ios.colors.green,
    subtitle: String? = null,
    enabled: Boolean = true,
    showSeparator: Boolean = true,
) {
    ListRow(
        title = title, icon = icon, iconColor = iconColor, subtitle = subtitle,
        showSeparator = showSeparator,
        titleColor = if (enabled) Ios.colors.label else Ios.colors.tertiaryLabel,
    ) {
        Switch(checked, onCheckedChange, enabled = enabled)
    }
}

@Composable
fun CheckRow(title: String, checked: Boolean, showSeparator: Boolean = true, onClick: () -> Unit) {
    ListRow(title = title, showSeparator = showSeparator, onClick = onClick) {
        if (checked) Icon(Icons.Rounded.Check, null, tint = Ios.colors.blue, modifier = Modifier.size(22.dp))
    }
}

// ---- Controls -------------------------------------------------------------------------

/** UISwitch: 51×31 track, 27pt thumb, springy travel. */
@Composable
fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    val track by animateColorAsState(if (checked) Ios.colors.green else Ios.colors.fill, label = "track")
    val offset by animateDpAsState(
        if (checked) 22.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 700f),
        label = "thumb",
    )
    Box(
        Modifier
            .size(51.dp, 31.dp)
            .clip(CircleShape)
            .background(track.copy(alpha = if (enabled) track.alpha else track.alpha * 0.5f))
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(!checked)
            },
    ) {
        Box(
            Modifier
                .offset(x = offset, y = 2.dp)
                .size(27.dp)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/** UISegmentedControl with a sliding thumb. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Ios.colors.secondaryFill)
            .padding(2.dp),
    ) {
        val segment = maxWidth / options.size.coerceAtLeast(1)
        val thumbX by animateDpAsState(
            segment * selectedIndex.coerceAtLeast(0),
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
            label = "segment",
        )
        if (selectedIndex >= 0) {
            Box(
                Modifier
                    .offset(x = thumbX)
                    .width(segment)
                    .fillMaxHeight()
                    .shadow(2.dp, RoundedCornerShape(7.dp))
                    .background(if (Ios.colors.isDark) Color(0xFF636366) else Color.White, RoundedCornerShape(7.dp)),
            )
        }
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(remember { MutableInteractionSource() }, null) {
                            if (i != selectedIndex) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(i)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = (if (options.size >= 4) Ios.type.caption1 else Ios.type.footnote)
                            .copy(fontWeight = if (i == selectedIndex) FontWeight.SemiBold else FontWeight.Medium),
                        color = Ios.colors.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Control Center–style slider: a thick rounded bar that fills as you drag anywhere on it.
 * [value] is 0..1.
 */
@Composable
fun FatSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 44.dp,
    // Control Center style in dark mode; on light cards a white fill would vanish, so use blue.
    fillColor: Color = if (Ios.colors.isDark) Color.White else Ios.colors.blue,
    trackColor: Color = Ios.colors.fill,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    var dragValue by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    val shown = if (dragging) dragValue else value
    val animated by animateFloatAsState(shown, spring(stiffness = if (dragging) 3000f else 400f), label = "slider")
    val expanded by animateFloatAsState(if (dragging) 1.03f else 1f, label = "sliderScale")
    val haptics = LocalHapticFeedback.current
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .scale(expanded)
            .clip(RoundedCornerShape(height / 3.2f))
            .background(trackColor)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; dragValue = value },
                    onDragEnd = { dragging = false; finished?.invoke() },
                    onDragCancel = { dragging = false },
                ) { change2, dx ->
                    change2.consume()
                    val before = dragValue
                    dragValue = (dragValue + dx / size.width).coerceIn(0f, 1f)
                    if ((before > 0f && dragValue == 0f) || (before < 1f && dragValue == 1f)) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    change(dragValue)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val v = (pos.x / size.width).coerceIn(0f, 1f)
                    change(v)
                    finished?.invoke()
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(maxWidth * animated)
                .background(fillColor.copy(alpha = if (Ios.colors.isDark) 0.95f else 1f)),
        )
        if (icon != null) {
            Icon(
                icon, null,
                tint = when {
                    animated <= 0.12f -> Ios.colors.secondaryLabel
                    Ios.colors.isDark -> Color(0xFF3A3A3C)
                    else -> Color.White
                },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp).size(20.dp),
            )
        }
    }
}

/** Classic thin UISlider with a round thumb, for settings like EQ. [value] in [range]. */
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val span = range.endInclusive - range.start
    fun snap(v: Float): Float {
        if (steps <= 0) return v.coerceIn(range)
        val step = span / steps
        return (range.start + ((v - range.start) / step).roundToInt() * step).coerceIn(range)
    }
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    val blue = Ios.colors.blue
    val track = Ios.colors.fill
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(range) {
                detectHorizontalDragGestures(onDragEnd = { finished?.invoke() }) { c, _ ->
                    c.consume()
                    change(snap(range.start + span * (c.position.x / size.width).coerceIn(0f, 1f)))
                }
            }
            .pointerInput(range) {
                detectTapGestures { p ->
                    change(snap(range.start + span * (p.x / size.width).coerceIn(0f, 1f)))
                    finished?.invoke()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val thumb = 28.dp
        val usable = maxWidth - thumb
        Canvas(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = thumb / 2)) {
            drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            drawRoundRect(
                blue, size = Size(size.width * fraction, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            )
        }
        Box(
            Modifier
                .offset(x = usable * fraction)
                .size(thumb)
                .shadow(4.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

// ---- Buttons --------------------------------------------------------------------------

@Composable
fun FilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Ios.colors.blue,
    contentColor: Color = Color.White,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .bouncyClick(enabled, onClick = onClick)
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) color else Ios.colors.fill)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, null, tint = contentColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = Ios.type.headline, color = if (enabled) contentColor else Ios.colors.tertiaryLabel)
        }
    }
}

@Composable
fun TintedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Ios.colors.blue, icon: ImageVector? = null) {
    FilledButton(text, onClick, modifier, color = color.copy(alpha = 0.15f), contentColor = color, icon = icon)
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    background: Color = Ios.colors.fill,
    tint: Color = Ios.colors.secondaryLabel,
) {
    Box(
        modifier
            .size(size)
            .bouncyClick(onClick = onClick)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.58f))
    }
}

// ---- Battery ---------------------------------------------------------------------------

/** Circular battery gauge like the iOS Batteries widget. */
@Composable
fun BatteryRing(
    level: Int?,
    charging: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    content: @Composable () -> Unit = {},
) {
    val colors = Ios.colors
    val animated by animateFloatAsState((level ?: 0) / 100f, spring(dampingRatio = 0.9f, stiffness = 60f), label = "battery")
    val ringColor = when {
        level == null -> colors.fill
        charging -> colors.green
        level <= 20 -> colors.red
        else -> colors.green
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val stroke = size.toPx() * 0.085f
                val inset = stroke / 2
                val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
                drawArc(colors.fill, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                if (level != null) {
                    drawArc(ringColor, -90f, 360f * animated, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            content()
            if (charging) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(size * 0.3f)
                        .background(colors.card, CircleShape)
                        .padding(2.dp)
                        .background(colors.green, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Bolt, null, tint = Color.White, modifier = Modifier.size(size * 0.2f))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (level != null) "$level%" else "—",
            style = Ios.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
            color = colors.label,
        )
        Text(label, style = Ios.type.caption1, color = colors.secondaryLabel, textAlign = TextAlign.Center)
    }
}
