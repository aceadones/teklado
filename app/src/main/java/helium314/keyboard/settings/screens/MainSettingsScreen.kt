// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import helium314.keyboard.latin.settings.findIndexOfDefaultSetting
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.SearchIcon
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.ExpandableSettingsCard
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.KeyboardThemeModePicker
import helium314.keyboard.settings.RoundIconButton
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsCard
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.screens.gesturedata.END_DATE_EPOCH_MILLIS
import helium314.keyboard.settings.screens.gesturedata.TWO_WEEKS_IN_MILLIS
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DashboardSection(
    @StringRes val label: Int,
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    @DrawableRes val icon: Int,
) {
    Overview(
        R.string.teklado_section_overview,
        R.string.teklado_overview_title,
        R.string.teklado_overview_subtitle,
        R.drawable.ic_teklado_nav_overview,
    ),
    Typing(
        R.string.teklado_section_typing,
        R.string.teklado_typing_title,
        R.string.teklado_typing_subtitle,
        R.drawable.ic_teklado_nav_typing,
    ),
    Look(
        R.string.teklado_section_look,
        R.string.teklado_look_title,
        R.string.teklado_look_subtitle,
        R.drawable.ic_teklado_nav_look,
    ),
    Toolbar(
        R.string.teklado_section_toolbar,
        R.string.teklado_toolbar_title,
        R.string.teklado_toolbar_subtitle,
        R.drawable.ic_teklado_nav_toolbar,
    ),
    More(
        R.string.teklado_section_more,
        R.string.teklado_more_title,
        R.string.teklado_more_subtitle,
        R.drawable.ic_teklado_nav_more,
    ),
}

@Composable
fun MainSettingsScreen(
    onClickAbout: () -> Unit,
    onClickTextCorrection: () -> Unit,
    onClickPreferences: () -> Unit,
    onClickToolbar: () -> Unit,
    onClickGestureTyping: () -> Unit,
    onClickDataGathering: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickAppearance: () -> Unit,
    onClickLanguage: () -> Unit,
    onClickLayouts: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickBack: () -> Unit,
) {
    val prefs = currentPrefs()
    var section by rememberSaveable { mutableStateOf(DashboardSection.Overview) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var query by remember { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }
    val scroll = rememberScrollState()

    fun closeSearch() {
        showSearch = false
        query = TextFieldValue()
    }

    BackHandler {
        if (showSearch) closeSearch()
        else onClickBack()
    }
    LaunchedEffect(showSearch) {
        if (showSearch) focusRequester.requestFocus()
        else query = TextFieldValue()
    }
    LaunchedEffect(section) { scroll.scrollTo(0) }

    val showGestureData = JniUtils.sHaveGestureLib &&
        System.currentTimeMillis() < END_DATE_EPOCH_MILLIS + TWO_WEEKS_IN_MILLIS

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        bottomBar = {
            if (!showSearch) {
                Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                    DashboardBottomBar(section) {
                        section = it
                        closeSearch()
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .then(
                    if (showSearch) Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .windowInsetsPadding(WindowInsets.ime)
                    else Modifier
                )
        ) {
            DashboardHeader(
                showSearch = showSearch,
                onSearch = { if (showSearch) closeSearch() else showSearch = true },
                onAbout = onClickAbout,
            )
            if (showSearch) {
                SearchField(
                    query = query,
                    onQuery = { query = it },
                    focusRequester = focusRequester,
                    onClear = { query = TextFieldValue() },
                )
                if (query.text.isBlank()) {
                    Text(
                        stringResource(R.string.teklado_search_hint),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val results = SettingsActivity.settingsContainer.filter(query.text)
                    if (results.isEmpty()) {
                        Text(
                            stringResource(R.string.teklado_search_empty),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(results) { setting ->
                                SettingsCard { setting.Preference() }
                            }
                        }
                    }
                }
            } else {
                Text(
                    stringResource(section.title),
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    stringResource(section.subtitle),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(scroll)
                        .padding(bottom = 12.dp)
                ) {
                    when (section) {
                        DashboardSection.Overview -> OverviewSection(
                            prefs = prefs,
                            showGestureData = showGestureData,
                            onClickPreferences = onClickPreferences,
                            onClickDataGathering = onClickDataGathering,
                        )
                        DashboardSection.Typing -> TypingSection(
                            prefs = prefs,
                            showGestureData = showGestureData,
                            onClickLanguage = onClickLanguage,
                            onClickPreferences = onClickPreferences,
                            onClickTextCorrection = onClickTextCorrection,
                            onClickDictionaries = onClickDictionaries,
                            onClickLayouts = onClickLayouts,
                            onClickGestureTyping = onClickGestureTyping,
                            onClickDataGathering = onClickDataGathering,
                        )
                        DashboardSection.Look -> LookSection(prefs, onClickAppearance)
                        DashboardSection.Toolbar -> ToolbarSection(prefs, onClickToolbar)
                        DashboardSection.More -> MoreSection(
                            showGestureData = showGestureData,
                            onClickAbout = onClickAbout,
                            onClickAdvanced = onClickAdvanced,
                            onClickDataGathering = onClickDataGathering,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun currentPrefs(): SharedPreferences {
    val ctx = LocalContext.current
    val tick = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((tick?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    return ctx.prefs()
}

@Composable
private fun DashboardHeader(
    showSearch: Boolean,
    onSearch: () -> Unit,
    onAbout: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.ime_settings),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RoundIconButton(
            icon = if (showSearch) R.drawable.ic_close else R.drawable.sym_keyboard_search_lxx,
            contentDescription = stringResource(R.string.label_search_key),
            onClick = onSearch,
        )
        Spacer(Modifier.size(8.dp))
        RoundIconButton(
            icon = R.drawable.sym_keyboard_settings_lxx,
            contentDescription = stringResource(R.string.settings_screen_about),
            onClick = onAbout,
        )
    }
}

@Composable
private fun SearchField(
    query: TextFieldValue,
    onQuery: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onClear: () -> Unit,
) {
    TextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .focusRequester(focusRequester),
        placeholder = { Text(stringResource(R.string.search_field_placeholder)) },
        leadingIcon = { SearchIcon() },
        trailingIcon = {
            if (query.text.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(android.R.string.cancel))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun SectionChips(selected: DashboardSection, onSelect: (DashboardSection) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DashboardSection.entries.forEach { section ->
            val isSelected = section == selected
            val shape = RoundedCornerShape(6.dp)
            Text(
                stringResource(section.label),
                modifier = Modifier
                    .clip(shape)
                    .background(if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
                        shape = shape,
                    )
                    .clickable(role = Role.Tab) { onSelect(section) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun DashboardBottomBar(selected: DashboardSection, onSelect: (DashboardSection) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DashboardSection.entries.forEach { section ->
                val active = section == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Tab) { onSelect(section) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        painterResource(section.icon),
                        contentDescription = stringResource(section.label),
                        tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingPref(key: String) {
    SettingsActivity.settingsContainer[key]?.Preference()
}

@Composable
private fun NavRow(
    @StringRes name: Int,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    description: String? = null,
) {
    Preference(
        name = stringResource(name),
        description = description,
        onClick = onClick,
        icon = icon,
    ) { NextScreenIcon() }
}

@Composable
private fun OverviewSection(
    prefs: SharedPreferences,
    showGestureData: Boolean,
    onClickPreferences: () -> Unit,
    onClickDataGathering: () -> Unit,
) {
    KeyboardStatusCards()
    Spacer(Modifier.height(6.dp))
    SettingsCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (JniUtils.hasGlide())
            SettingPref(Settings.PREF_GESTURE_INPUT)
        SettingPref(Settings.PREF_AUTO_CORRECTION)
        SettingPref(Settings.PREF_SHOW_SUGGESTIONS)
        SettingPref(Settings.PREF_ENABLE_CLIPBOARD_HISTORY)
        SettingPref(Settings.PREF_VIBRATE_ON)
        SettingPref(Settings.PREF_SOUND_ON)
    }
    val vibrate = prefs.getBoolean(Settings.PREF_VIBRATE_ON, Defaults.PREF_VIBRATE_ON)
    val sound = prefs.getBoolean(Settings.PREF_SOUND_ON, Defaults.PREF_SOUND_ON)
    val clipboard = prefs.getBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY)
    val clipboardFiles = clipboard && prefs.getBoolean(Settings.PREF_CLIPBOARD_USE_FILES, Defaults.PREF_CLIPBOARD_USE_FILES)
    ExpandableSettingsCard(
        title = stringResource(R.string.settings_screen_advanced),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        if (vibrate) {
            SettingPref(Settings.PREF_VIBRATION_DURATION_SETTINGS)
            SettingPref(Settings.PREF_VIBRATE_IN_DND_MODE)
        }
        if (sound) SettingPref(Settings.PREF_KEYPRESS_SOUND_VOLUME)
        if (clipboard) {
            SettingPref(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME)
            SettingPref(Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST)
            SettingPref(Settings.PREF_CLIPBOARD_USE_FILES)
            if (clipboardFiles) SettingPref(Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT)
        }
        NavRow(R.string.settings_screen_preferences, R.drawable.ic_settings_preferences, onClickPreferences)
        if (showGestureData)
            NavRow(R.string.gesture_data_screen, R.drawable.ic_settings_gesture, onClickDataGathering)
    }
}

@Composable
private fun KeyboardStatusCards() {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    var enabled by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(false) }
    fun refresh() {
        val manager = imm ?: return
        enabled = UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, manager)
        current = UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, manager)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED) refresh()
    }
    val scope = rememberCoroutineScope()
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh()
    }
    StatusCard(
        title = stringResource(if (enabled) R.string.teklado_status_enabled else R.string.teklado_status_disabled),
        body = stringResource(if (enabled) R.string.teklado_status_enabled_body else R.string.teklado_status_disabled_body),
        icon = R.drawable.ic_teklado_nav_typing,
        done = enabled,
        action = if (enabled) null else stringResource(R.string.teklado_action_enable),
        onAction = {
            val intent = Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
                .addCategory(Intent.CATEGORY_DEFAULT)
            enableLauncher.launch(intent)
        },
    )
    StatusCard(
        title = stringResource(if (current) R.string.teklado_status_selected else R.string.teklado_status_not_selected),
        body = stringResource(if (current) R.string.teklado_status_selected_body else R.string.teklado_status_not_selected_body),
        icon = R.drawable.ic_setup_select,
        done = current,
        action = if (enabled && !current) stringResource(R.string.teklado_action_switch) else null,
        onAction = {
            imm?.showInputMethodPicker()
            scope.launch {
                repeat(40) {
                    delay(100)
                    refresh()
                }
            }
        },
    )
}

@Composable
private fun StatusCard(
    title: String,
    body: String,
    @DrawableRes icon: Int,
    done: Boolean,
    action: String?,
    onAction: () -> Unit,
) {
    SettingsCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (action != null) {
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text(action) }
            } else if (done) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_setup_check),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TypingSection(
    prefs: SharedPreferences,
    showGestureData: Boolean,
    onClickLanguage: () -> Unit,
    onClickPreferences: () -> Unit,
    onClickTextCorrection: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickLayouts: () -> Unit,
    onClickGestureTyping: () -> Unit,
    onClickDataGathering: () -> Unit,
) {
    if (JniUtils.hasGlide()) {
        val gesture = prefs.getBoolean(Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT)
        val floatingPreview = prefs.getBoolean(
            Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT,
            Defaults.PREF_GESTURE_FLOATING_PREVIEW_TEXT,
        )
        SettingsCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            SettingPref(Settings.PREF_GESTURE_INPUT)
            if (gesture) {
                SettingPref(Settings.PREF_GESTURE_PREVIEW_TRAIL)
                SettingPref(Settings.PREF_GESTURE_FLOATING_PREVIEW_TEXT)
                if (floatingPreview) SettingPref(Settings.PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC)
            }
        }
    }
    SettingsCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        SettingPref(Settings.PREF_EMOJI_KITCHEN)
    }
    SettingsCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        SettingPref(Settings.PREF_AUTO_CORRECTION)
        SettingPref(Settings.PREF_SHOW_SUGGESTIONS)
        SettingPref(Settings.PREF_AUTO_CAP)
    }
    val languages = SubtypeSettings.getEnabledSubtypes(true).joinToString(", ") { it.displayName() }
    SettingsCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        NavRow(
            name = R.string.language_and_layouts_title,
            icon = R.drawable.ic_settings_languages,
            description = languages.ifBlank { null },
            onClick = onClickLanguage,
        )
        SettingPref(Settings.PREF_SHOW_NUMBER_ROW)
        OneHandedToggle()
    }
    ExpandableSettingsCard(
        title = stringResource(R.string.settings_screen_advanced),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        NavRow(R.string.settings_screen_preferences, R.drawable.ic_settings_preferences, onClickPreferences)
        NavRow(R.string.settings_screen_correction, R.drawable.ic_settings_correction, onClickTextCorrection)
        NavRow(R.string.dictionary_settings_category, R.drawable.ic_dictionary, onClickDictionaries)
        NavRow(R.string.settings_screen_secondary_layouts, R.drawable.ic_settings_layout, onClickLayouts)
        if (JniUtils.hasGlide())
            NavRow(R.string.settings_screen_gesture, R.drawable.ic_settings_gesture, onClickGestureTyping)
        if (showGestureData)
            NavRow(R.string.gesture_data_screen, R.drawable.ic_settings_gesture, onClickDataGathering)
    }
}

@Composable
private fun OneHandedToggle() {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val folded = FoldableUtils.isFoldable && FoldableUtils.isFolded
    val splitKey = when {
        landscape && folded -> Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED_LANDSCAPE
        landscape -> Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE
        folded -> Settings.PREF_ENABLE_SPLIT_KEYBOARD_FOLDED
        else -> Settings.PREF_ENABLE_SPLIT_KEYBOARD
    }
    val split = LocalContext.current.prefs().getBoolean(splitKey, Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
    val key = createPrefKeyForBooleanSettings(
        Settings.PREF_ONE_HANDED_MODE_PREFIX,
        findIndexOfDefaultSetting(landscape, split, folded),
        3,
    )
    SwitchPreference(
        name = stringResource(R.string.one_handed),
        description = stringResource(R.string.teklado_one_handed_summary),
        key = key,
        default = Defaults.PREF_ONE_HANDED_MODE,
    )
}

@Composable
private fun LookSection(prefs: SharedPreferences, onClickAppearance: () -> Unit) {
    val dayNight = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P &&
        prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT)
    SettingsCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                stringResource(R.string.settings_screen_theme),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            KeyboardThemeModePicker()
        }
        SettingPref(Settings.PREF_THEME_STYLE)
        SettingPref(Settings.PREF_THEME_COLORS)
        if (dayNight) SettingPref(Settings.PREF_THEME_COLORS_NIGHT)
    }
    SettingsCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        SettingPref(SettingsWithoutKey.BACKGROUND_IMAGE)
    }
    SettingsCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        SettingPref(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX)
        SettingPref(Settings.PREF_THEME_KEY_BORDERS)
        SettingPref(SettingsWithoutKey.CUSTOM_FONT)
        SettingPref(Settings.PREF_FONT_SCALE)
    }
    ExpandableSettingsCard(
        title = stringResource(R.string.settings_screen_advanced),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        NavRow(R.string.settings_screen_appearance, R.drawable.ic_settings_appearance, onClickAppearance)
    }
}

@Composable
private fun ToolbarSection(prefs: SharedPreferences, onClickToolbar: () -> Unit) {
    val mode = Settings.readToolbarMode(prefs)
    SettingsCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        SettingPref(Settings.PREF_TOOLBAR_MODE)
        if (mode == ToolbarMode.EXPANDABLE || mode == ToolbarMode.SUGGESTION_STRIP)
            SettingPref(Settings.PREF_PINNED_TOOLBAR_KEYS)
        if (mode == ToolbarMode.EXPANDABLE || mode == ToolbarMode.TOOLBAR_KEYS)
            SettingPref(Settings.PREF_TOOLBAR_KEYS)
        if (mode != ToolbarMode.HIDDEN ||
            !prefs.getBoolean(Settings.PREF_TOOLBAR_HIDING_GLOBAL, Defaults.PREF_TOOLBAR_HIDING_GLOBAL)
        ) {
            SettingPref(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES)
        }
    }
    ExpandableSettingsCard(
        title = stringResource(R.string.settings_screen_advanced),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        NavRow(R.string.settings_screen_toolbar, R.drawable.ic_settings_toolbar, onClickToolbar)
    }
}

@Composable
private fun MoreSection(
    showGestureData: Boolean,
    onClickAbout: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickDataGathering: () -> Unit,
) {
    SettingsCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        NavRow(R.string.settings_screen_about, R.drawable.ic_settings_about, onClickAbout)
        SettingPref(SettingsWithoutKey.BACKUP_RESTORE)
    }
    ExpandableSettingsCard(
        title = stringResource(R.string.settings_screen_advanced),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        NavRow(R.string.settings_screen_advanced, R.drawable.ic_settings_advanced, onClickAdvanced)
        if (showGestureData)
            NavRow(R.string.gesture_data_screen, R.drawable.ic_settings_gesture, onClickDataGathering)
    }
}

fun createEmojiKitchenSettings(context: Context) = listOf(
    Setting(
        context,
        Settings.PREF_EMOJI_KITCHEN,
        R.string.emoji_kitchen,
        R.string.emoji_kitchen_summary,
    ) {
        SwitchPreference(it, Defaults.PREF_EMOJI_KITCHEN)
    },
)

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}
