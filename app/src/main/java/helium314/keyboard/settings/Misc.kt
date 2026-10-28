// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ExpandButton
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs

@Composable
fun WithSmallTitle(
    description: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(description, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit
) {
    val clickableModifier = if (onClick != null) Modifier.clickable(onClick = onClick)
    else Modifier
    Row(
        modifier = modifier
            .then(clickableModifier)
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = verticalAlignment,
        content = content
    )
}

/** Icon if resource is a vector image, (bitmap) Image otherwise */
@Composable
fun IconOrImage(@DrawableRes resId: Int, name: String?, sizeDp: Int) {
    val ctx = LocalContext.current
    val drawable = ContextCompat.getDrawable(ctx, resId)
    if (drawable is VectorDrawable)
        Icon(painterResource(resId), name, Modifier.size(sizeDp.dp))
    else {
        val px = sizeDp.dpToPx(LocalResources.current)
        Image(drawable!!.toBitmap(px, px).asImageBitmap(), name)
    }
}

@Composable
fun KeyboardIconsSet.GetIconOrEmpty(name: String?) {
    val iconId = iconIds[name?.lowercase()]
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (iconId != null)
            Icon(painterResourceCompat(iconId), name, Modifier.fillMaxSize(0.8f))
    }
}

/** [painterResource], but also works for non-vector/bitmaps (needs a size for bitmap creation) */
@Composable
fun painterResourceCompat(@DrawableRes resId: Int, sizeDp: Int = 40): Painter {
    val ctx = LocalContext.current
    val drawable = ContextCompat.getDrawable(ctx, resId)
    return if (drawable is VectorDrawable || drawable is BitmapDrawable)
        painterResource(resId)
    else {
        val px = sizeDp.dpToPx(LocalResources.current)
        BitmapPainter(drawable!!.toBitmap(px, px).asImageBitmap())
    }
}

@Composable
fun <T>DropDownField(
    items: List<T>,
    selectedItem: T,
    onSelected: (T) -> Unit,
    extraButton: @Composable (() -> Unit)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        Modifier.clickable { expanded = !expanded }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
        ) {
            Box(Modifier.weight(1f)) {
                itemContent(selectedItem)
            }
            ExpandButton(items.size > 1) { expanded = !expanded }
            if (extraButton != null)
                extraButton()
        }
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        items.forEach {
            DropdownMenuItem(
                text = { itemContent(it) },
                onClick = { expanded = false; onSelected(it) }
            )
        }
    }
}

@Composable
fun isWideScreen(): Boolean {
    val width = LocalConfiguration.current.screenWidthDp
    val height = LocalConfiguration.current.screenHeightDp
    return height < 500 && width > height
}

val contentTextDirectionStyle = TextStyle(textDirection = TextDirection.Content)

private val CardShape = RoundedCornerShape(8.dp)

/** White (or dark) rounded group used by settings lists and the dashboard. */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(vertical = 2.dp), content = content)
    }
}

/** Bottom-of-section card that keeps the less common settings one tap away. */
@Composable
fun ExpandableSettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    painterResource(R.drawable.ic_arrow_left),
                    contentDescription = title,
                    modifier = Modifier.rotate(if (expanded) 90f else -90f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    content()
                }
            }
        }
    }
}

@Composable
fun RoundIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription, Modifier.size(18.dp), tint = tint)
    }
}

private enum class KeyboardLookMode(@StringRes val label: Int) {
    LIGHT(R.string.teklado_theme_light),
    DARK(R.string.teklado_theme_dark),
    SYSTEM(R.string.teklado_theme_system),
}

private val darkKeyboardThemes = setOf(
    KeyboardTheme.THEME_DARK,
    KeyboardTheme.THEME_DARKER,
    KeyboardTheme.THEME_BLACK,
)

/**
 * Light / dark / follow-system control for the keyboard.
 * Writes the existing day-night and color-theme preferences. There is no separate dim slider for background images.
 */
@Composable
fun KeyboardThemeModePicker(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val tick = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((tick?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val prefs = ctx.prefs()
    val allowSystem = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    val dayNight = prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT)
    val colors = prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS)
    val night = prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT)
    val selected = when {
        allowSystem && dayNight -> KeyboardLookMode.SYSTEM
        colors in darkKeyboardThemes -> KeyboardLookMode.DARK
        else -> KeyboardLookMode.LIGHT
    }
    val modes = if (allowSystem)
        listOf(KeyboardLookMode.LIGHT, KeyboardLookMode.DARK, KeyboardLookMode.SYSTEM)
    else
        listOf(KeyboardLookMode.LIGHT, KeyboardLookMode.DARK)
    val selectedContainer = MaterialTheme.colorScheme.surfaceContainerHighest
    val selectedContent = MaterialTheme.colorScheme.onSurface

    fun apply(mode: KeyboardLookMode) {
        prefs.edit {
            when (mode) {
                KeyboardLookMode.SYSTEM -> {
                    putBoolean(Settings.PREF_THEME_DAY_NIGHT, true)
                    // The Dark chip stores THEME_DARK as the day colors. Put a light day theme back
                    // so follow-system is light during the day and dark at night.
                    if (colors == KeyboardTheme.THEME_DARK)
                        putString(Settings.PREF_THEME_COLORS, KeyboardTheme.THEME_LIGHT)
                    if (night == KeyboardTheme.THEME_LIGHT)
                        putString(Settings.PREF_THEME_COLORS_NIGHT, KeyboardTheme.THEME_DARK)
                }
                KeyboardLookMode.LIGHT -> {
                    putBoolean(Settings.PREF_THEME_DAY_NIGHT, false)
                    putString(Settings.PREF_THEME_COLORS, KeyboardTheme.THEME_LIGHT)
                }
                KeyboardLookMode.DARK -> {
                    putBoolean(Settings.PREF_THEME_DAY_NIGHT, false)
                    putString(Settings.PREF_THEME_COLORS, KeyboardTheme.THEME_DARK)
                }
            }
        }
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        modes.forEach { mode ->
            val isSelected = mode == selected
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (isSelected) selectedContainer else MaterialTheme.colorScheme.surface)
                    .border(
                        width = 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
                        shape = shape,
                    )
                    .clickable(role = Role.RadioButton) { apply(mode) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(mode.label),
                    color = if (isSelected) selectedContent else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private fun Color.luminance(): Float {
    // Relative luminance is unnecessary here; a channel average is enough to pick chip contrast.
    return (red + green + blue) / 3f
}
