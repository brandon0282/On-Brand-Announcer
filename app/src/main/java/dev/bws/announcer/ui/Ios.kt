package dev.bws.announcer.ui

import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

/**
 * A small iOS-flavoured design kit for Compose: grouped inset lists, system colors,
 * large titles, iOS-style switch and segmented control. Colors follow Apple's HIG system palette.
 */
@Immutable
data class IosColors(
    val background: Color,      // systemGroupedBackground
    val card: Color,            // secondarySystemGroupedBackground
    val fill: Color,            // tertiarySystemFill (search field, segmented track)
    val label: Color,
    val secondary: Color,       // secondaryLabel
    val separator: Color,
    val tint: Color,            // systemBlue
    val green: Color, val red: Color, val orange: Color, val purple: Color,
    val indigo: Color, val teal: Color, val pink: Color, val gray: Color,
    val dark: Boolean,
)

val LightIos = IosColors(
    background = Color(0xFFF2F2F7), card = Color.White, fill = Color(0x1F767680),
    label = Color.Black, secondary = Color(0x993C3C43), separator = Color(0x4A3C3C43),
    tint = Color(0xFF007AFF), green = Color(0xFF34C759), red = Color(0xFFFF3B30), orange = Color(0xFFFF9500),
    purple = Color(0xFFAF52DE), indigo = Color(0xFF5856D6), teal = Color(0xFF30B0C7), pink = Color(0xFFFF2D55),
    gray = Color(0xFF8E8E93), dark = false,
)

val DarkIos = IosColors(
    background = Color.Black, card = Color(0xFF1C1C1E), fill = Color(0x3D767680),
    label = Color.White, secondary = Color(0x99EBEBF5), separator = Color(0xA6545458),
    tint = Color(0xFF0A84FF), green = Color(0xFF30D158), red = Color(0xFFFF453A), orange = Color(0xFFFF9F0A),
    purple = Color(0xFFBF5AF2), indigo = Color(0xFF5E5CE6), teal = Color(0xFF40C8E0), pink = Color(0xFFFF375F),
    gray = Color(0xFF8E8E93), dark = true,
)

val LocalIos = staticCompositionLocalOf { LightIos }
val ios: IosColors @Composable get() = LocalIos.current

object IosType {
    val largeTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.25.sp, lineHeight = 41.sp)
    val title2 = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
    val headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val stat = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
}

@Composable
fun IosTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) DarkIos else LightIos
    val m3 = if (c.dark) darkColorScheme(primary = c.tint, background = c.background, surface = c.card)
    else lightColorScheme(primary = c.tint, background = c.background, surface = c.card)
    CompositionLocalProvider(LocalIos provides c, LocalContentColor provides c.label) {
        MaterialTheme(colorScheme = m3) {
            CompositionLocalProvider(LocalContentColor provides c.label) {
                ProvideTextStyle(IosType.body.copy(color = c.label), content)
            }
        }
    }
}

// ------------------------------------------------------------------ layout

@Composable
fun LargeTitle(text: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)) {
        Text(text, style = IosType.largeTitle, color = ios.label)
        subtitle?.let { Text(it, style = IosType.subhead, color = ios.secondary) }
    }
}

/** Inset grouped section: uppercase header, rounded card, optional footer. Rows get hairline separators. */
@Composable
fun IosSection(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        header?.let {
            Text(it.uppercase(), style = IosType.footnote, color = ios.secondary,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        }
        Column(Modifier.clip(RoundedCornerShape(12.dp)).background(ios.card), content = content)
        footer?.let {
            Text(it, style = IosType.footnote, color = ios.secondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        }
    }
}

@Composable
fun IosDivider(inset: Dp = 16.dp) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(0.5.dp).background(ios.separator))
}

private typealias Dp = androidx.compose.ui.unit.Dp

/** Rounded-square colored icon badge, like Settings.app. */
@Composable
fun IconBadge(icon: ImageVector, color: Color) {
    Box(Modifier.size(29.dp).clip(RoundedCornerShape(7.dp)).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

@Composable
fun IosRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = ios.tint,
    leading: (@Composable () -> Unit)? = null,
    value: String? = null,
    chevron: Boolean = false,
    titleColor: Color = ios.label,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val hasLeading = icon != null || leading != null
    Column {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 44.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) { IconBadge(icon, iconColor); Spacer(Modifier.width(14.dp)) }
            else if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = IosType.body, color = titleColor)
                subtitle?.let { Text(it, style = IosType.footnote, color = ios.secondary) }
            }
            value?.let { Text(it, style = IosType.body, color = ios.secondary) }
            trailing?.invoke()
            if (chevron) Icon(Icons.Filled.KeyboardArrowRight, null, tint = ios.gray.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4.dp).size(22.dp))
        }
        if (divider) IosDivider(if (hasLeading) 59.dp else 16.dp)
    }
}

@Composable
fun IosToggleRow(
    title: String, checked: Boolean, subtitle: String? = null,
    icon: ImageVector? = null, iconColor: Color = ios.tint, divider: Boolean = true,
    onChange: (Boolean) -> Unit,
) = IosRow(title, subtitle, icon, iconColor, divider = divider, onClick = { onChange(!checked) }) {
    IosSwitch(checked, onChange)
}

/** 51×31 pill switch with a white thumb, green when on. */
@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) ios.green else ios.fill, label = "track")
    val offset by animateDpAsState(if (checked) 22.dp else 2.dp, spring(dampingRatio = 0.7f), label = "thumb")
    Box(
        Modifier.size(51.dp, 31.dp).clip(CircleShape).background(track)
            .clickable(remember { MutableInteractionSource() }, null) { onChange(!checked) },
    ) {
        Box(Modifier.offset(x = offset, y = 2.dp).size(27.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape))
    }
}

@Composable
fun IosSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(9.dp)).background(ios.fill).padding(2.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(28.dp)
                    .then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(7.dp))
                        .background(if (ios.dark) Color(0xFF636366) else Color.White, RoundedCornerShape(7.dp)) else Modifier)
                    .clickable(remember { MutableInteractionSource() }, null) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(o, style = IosType.footnote.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), color = ios.label)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosSlider(
    title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, display: String,
    subtitle: String? = null, divider: Boolean = true, onChange: (Float) -> Unit,
) {
    Column {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row { Text(title, Modifier.weight(1f)); Text(display, color = ios.secondary) }
            subtitle?.let { Text(it, style = IosType.footnote, color = ios.secondary) }
            val tint = ios.tint
            val fill = ios.fill
            Slider(
                value, onChange, valueRange = range, steps = steps,
                thumb = { Box(Modifier.size(28.dp).shadow(3.dp, CircleShape).background(Color.White, CircleShape)) },
                track = { state ->
                    val f = ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(fill)) {
                        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(tint))
                    }
                },
            )
        }
        if (divider) IosDivider()
    }
}

@Composable
fun IosSearchField(value: String, onChange: (String) -> Unit, placeholder: String = "Search") {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(36.dp)
            .clip(RoundedCornerShape(10.dp)).background(ios.fill).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = ios.secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = ios.secondary)
            BasicTextField(value, onChange, singleLine = true, textStyle = IosType.body.copy(color = ios.label),
                cursorBrush = SolidColor(ios.tint), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun IosTextField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
        if (value.isEmpty()) Text(placeholder, color = ios.secondary.copy(alpha = 0.5f))
        BasicTextField(value, onChange, singleLine = true, textStyle = IosType.body.copy(color = ios.label),
            cursorBrush = SolidColor(ios.tint), modifier = Modifier.fillMaxWidth())
    }
}

/** Plain blue text button, like a UIKit system button. */
@Composable
fun IosButton(text: String, color: Color = ios.tint, bold: Boolean = false, onClick: () -> Unit) {
    Text(text, style = IosType.body.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal), color = color,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp))
}

/** Filled capsule button (iOS 15+ "borderedProminent"). */
@Composable
fun IosFilledButton(text: String, color: Color = ios.tint, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(color).clickable(onClick = onClick).padding(vertical = 13.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = IosType.headline, color = Color.White)
    }
}

// ------------------------------------------------------------------ app icons

private val iconCache = LruCache<String, ImageBitmap>(120)

@Composable
fun AppIcon(pkg: String, size: Dp = 29.dp) {
    val pm: PackageManager = LocalContext.current.packageManager
    val bmp = remember(pkg) {
        iconCache.get(pkg) ?: runCatching {
            pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap().also { iconCache.put(pkg, it) }
        }.getOrNull()
    }
    if (bmp != null) Image(bmp, null, Modifier.size(size).clip(RoundedCornerShape(size * 0.225f)))
    else Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.225f)).background(ios.gray), contentAlignment = Alignment.Center) {
        Text(pkg.substringAfterLast('.').take(1).uppercase(), color = Color.White, style = IosType.footnote)
    }
}
