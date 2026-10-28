// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.CloseIcon
import helium314.keyboard.latin.utils.SearchIcon

@Composable
fun SearchSettingsScreen(
    onClickBack: () -> Unit,
    title: String,
    settings: List<Any?>,
    content: @Composable (ColumnScope.() -> Unit)? = null // overrides settings if not null
) {
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(title) },
        content = {
            if (content != null) content()
            else {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                ) { innerPadding ->
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(innerPadding)
                            .padding(top = 4.dp, bottom = 12.dp)
                    ) {
                        SettingsBlocks(settings)
                    }
                }
            }
        },
        filteredItems = { SettingsActivity.settingsContainer.filter(it) },
        itemContent = { it.Preference() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T: Any?> SearchScreen(
    onClickBack: () -> Unit,
    title: @Composable () -> Unit,
    filteredItems: (String) -> List<T>,
    itemContent: @Composable (T) -> Unit,
    icon: @Composable (() -> Unit)? = null,
    menu: List<Pair<String, () -> Unit>>? = null,
    content: @Composable (ColumnScope.() -> Unit)? = null,
) {
    // searchText and showSearch should have the same remember or rememberSaveable
    // saveable survives orientation changes and switching between screens, but shows the
    // keyboard in unexpected situations such as going back from another screen, which is rather annoying
    var searchText by remember { mutableStateOf(TextFieldValue()) }
    var showSearch by remember { mutableStateOf(false) }
    val barColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
        actionIconContentColor = MaterialTheme.colorScheme.onBackground,
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {

            fun setShowSearch(value: Boolean) {
                showSearch = value
                if (!value) searchText = TextFieldValue()
            }
            BackHandler {
                if (showSearch || searchText.text.isNotEmpty()) setShowSearch(false)
                else onClickBack()
            }
            Column {
                TopAppBar(
                    title = title,
                    colors = barColors,
                    windowInsets = WindowInsets(0),
                    navigationIcon = {
                        BackButton {
                            if (showSearch) setShowSearch(false)
                            else onClickBack()
                        }
                    },
                    actions = {
                        if (icon == null)
                            RoundIconButton(
                                icon = R.drawable.sym_keyboard_search_lxx,
                                contentDescription = stringResource(R.string.label_search_key),
                                onClick = { setShowSearch(!showSearch) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        else
                            icon()
                        if (menu != null)
                            Box {
                                var showMenu by remember { mutableStateOf(false) }
                                IconButton(
                                    onClick = { showMenu = true }
                                ) { Icon(painterResource(R.drawable.ic_arrow_left), "menu", Modifier.rotate(-90f)) }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    menu.forEach {
                                        DropdownMenuItem(
                                            text = { Text(it.first) },
                                            onClick = { showMenu = false; it.second() }
                                        )
                                    }
                                }
                            }
                    },
                )
                ExpandableSearchField(
                    expanded = showSearch,
                    onDismiss = { setShowSearch(false) },
                    search = searchText,
                    onSearchChange = { searchText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    )
                )
            }
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
                    if (searchText.text.isBlank() && content != null) {
                        Column(Modifier.fillMaxSize()) {
                            content()
                        }
                    } else {
                        val results = filteredItems(searchText.text)
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(results) {
                                SettingsCard {
                                    itemContent(it)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class SettingsGroup(val titleRes: Int?, val keys: List<Any>)

private fun groupSettings(settings: List<Any?>): List<SettingsGroup> {
    val groups = mutableListOf<SettingsGroup>()
    var title: Int? = null
    val current = mutableListOf<Any>()
    fun flush() {
        if (current.isNotEmpty()) {
            groups += SettingsGroup(title, current.toList())
            current.clear()
        }
    }
    for (item in settings) {
        when (item) {
            null -> Unit
            is Int -> {
                flush()
                title = item
            }
            else -> current += item
        }
    }
    flush()
    return groups
}

@Composable
private fun SettingsBlocks(settings: List<Any?>) {
    groupSettings(settings).forEach { group ->
        val rows = group.keys.mapNotNull { SettingsActivity.settingsContainer[it] }
        if (rows.isEmpty()) return@forEach
        if (group.titleRes != null) {
            Text(
                text = stringResource(group.titleRes),
                modifier = Modifier.padding(start = 28.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleSmall,
            )
        } else {
            Spacer(Modifier.height(8.dp))
        }
        SettingsCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            rows.forEachIndexed { index, setting ->
                setting.Preference()
                if (index != rows.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

// from StreetComplete
/** Expandable text field that can be dismissed and requests focus when it is expanded */
@Composable
fun ExpandableSearchField(
    expanded: Boolean,
    onDismiss: () -> Unit,
    search: TextFieldValue,
    onSearchChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    colors: TextFieldColors = TextFieldDefaults.colors(),
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(expanded) {
        if (expanded) focusRequester.requestFocus()
    }
    AnimatedVisibility(visible = expanded, modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = modifier.focusRequester(focusRequester),
            shape = RoundedCornerShape(8.dp),
            leadingIcon = { SearchIcon() },
            trailingIcon = { IconButton(onClick = {
                if (search.text.isBlank()) onDismiss()
                else onSearchChange(TextFieldValue())
            }) { CloseIcon(android.R.string.cancel) } },
            singleLine = true,
            colors = colors,
            textStyle = contentTextDirectionStyle,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
    }
}
