package com.powercess.mbrain.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val Light = lightColorScheme(
    primary = Color(0xFF5E6AD2), onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E8FF), onPrimaryContainer = Color(0xFF172E73),
    secondary = Color(0xFF526079), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5EAF3), onSecondaryContainer = Color(0xFF273349),
    tertiary = Color(0xFF14756A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD7F2EB), onTertiaryContainer = Color(0xFF005046),
    background = Color(0xFFF3F5FA), onBackground = Color(0xFF1B2435),
    surface = Color(0xFFFAFBFF), onSurface = Color(0xFF1B2435),
    surfaceVariant = Color(0xFFE8ECF4), onSurfaceVariant = Color(0xFF566174),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3F9),
    surfaceContainer = Color(0xFFECEFF6), surfaceContainerHigh = Color(0xFFE6EBF3),
    surfaceContainerHighest = Color(0xFFDEE5F0),
    outline = Color(0xFF747E90), outlineVariant = Color(0xFFD9DFEA),
    error = Color(0xFFB32635), errorContainer = Color(0xFFFFDADB), onErrorContainer = Color(0xFF690017),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB1C5FF), onPrimary = Color(0xFF002B79),
    primaryContainer = Color(0xFF263F79), onPrimaryContainer = Color(0xFFDFE7FF),
    secondary = Color(0xFFBBC7DD), onSecondary = Color(0xFF253147),
    secondaryContainer = Color(0xFF303D54), onSecondaryContainer = Color(0xFFDEE7F8),
    tertiary = Color(0xFF85D6C5), onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF164E45), onTertiaryContainer = Color(0xFFACF1E1),
    background = Color(0xFF111216), onBackground = Color(0xFFE3E8F2),
    surface = Color(0xFF191B21), onSurface = Color(0xFFE3E8F2),
    surfaceVariant = Color(0xFF303A4A), onSurfaceVariant = Color(0xFFB7C1D3),
    surfaceContainerLowest = Color(0xFF1B1D24), surfaceContainerLow = Color(0xFF22252E),
    surfaceContainer = Color(0xFF272B35), surfaceContainerHigh = Color(0xFF303540),
    surfaceContainerHighest = Color(0xFF3A404D),
    outline = Color(0xFF8490A5), outlineVariant = Color(0xFF354052),
    error = Color(0xFFFFB3B7), errorContainer = Color(0xFF642532), onErrorContainer = Color(0xFFFFDADB),
)

@Composable
internal fun MBrainTheme(dark: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view, dark) {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        onDispose { }
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = Shapes(
        small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(18.dp)
    ), content = content)
}

@Composable
internal fun ScreenList(state: LazyListState = rememberLazyListState(), content: LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun SectionLabel(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun Group(card: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = if (card) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
internal fun GroupDivider() = Spacer(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.background))

@Composable
internal fun IconTile(icon: ImageVector, prominent: Boolean = false) {
    Surface(shape = RoundedCornerShape(14.dp), color = if (prominent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ActionRow(title: String, subtitle: String? = null, icon: ImageVector,
    onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val body: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = if (subtitle == null) 56.dp else 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (trailing != null) trailing() else if (onClick != null)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    // The group's outer clip rounds the first/last rows; inner corners remain small.
    val shape = RoundedCornerShape(4.dp)
    if (onClick == null) Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, content = body)
    else Surface(onClick = onClick, shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, content = body)
}

@Composable
internal fun StatusPill(text: String, good: Boolean = false, error: Boolean = false) {
    val foreground = when { error -> MaterialTheme.colorScheme.error; good -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Surface(color = foreground.copy(alpha = .10f), shape = CircleShape) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(5.dp).background(foreground, CircleShape))
            Text(text, color = foreground, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun EmptyState(icon: ImageVector, title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        IconTile(icon, prominent = true)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

@Composable
internal fun Note(text: String, error: Boolean = false) {
    Surface(shape = RoundedCornerShape(16.dp), color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
