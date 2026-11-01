package io.github.windsnn.hyperlock.ui

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.*
import io.github.windsnn.hyperlock.HyperLog
import io.github.windsnn.hyperlock.R
import io.github.windsnn.hyperlock.ScopeApplication
import io.github.windsnn.hyperlock.SystemUiRestarter
import io.github.windsnn.hyperlock.checkDeviceCompat
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import io.github.windsnn.hyperlock.HookSettings

@Composable
internal fun SettingsHome(
    settings: HookSettings,
    online: Boolean,
    update: ((HookSettings) -> HookSettings) -> Unit,
    open: (PageId) -> Unit,
) = AppPage(stringResource(R.string.page_settings)) { padding, scroll ->
    val context = LocalContext.current
    var showScopeRestartDialog by remember { mutableStateOf(false) }
    val compat = remember(context) { checkDeviceCompat(context) }
    val localeManager = remember(context) { context.getSystemService(LocaleManager::class.java) }
    val currentAppLocales = localeManager?.applicationLocales
    var selectedLanguageIndex by remember(currentAppLocales) {
        mutableIntStateOf(
            if (currentAppLocales == null || currentAppLocales.isEmpty) {
                0
            } else {
                when (currentAppLocales[0].language) {
                    "zh" -> 1
                    "en" -> 2
                    else -> 0
                }
            }
        )
    }

    AppList(padding, scroll, 88) {
        item { ServiceCard(online) }
        item { DeviceCompatCard(compat) }
        item {
            Group(stringResource(R.string.group_app_settings)) {
                ArrowPreference(
                    title = stringResource(R.string.pref_restart_scopes),
                    onClick = { showScopeRestartDialog = true },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_language),
                    items = listOf(
                        stringResource(R.string.language_system),
                        stringResource(R.string.language_zh_cn),
                        stringResource(R.string.language_en),
                    ),
                    selectedIndex = selectedLanguageIndex,
                    onSelectedIndexChange = { index ->
                        if (index != selectedLanguageIndex) {
                            selectedLanguageIndex = index
                            val targetLocales = when (index) {
                                1 -> LocaleList.forLanguageTags("zh-CN")
                                2 -> LocaleList.forLanguageTags("en")
                                else -> LocaleList.getEmptyLocaleList()
                            }
                            localeManager?.applicationLocales = targetLocales
                        }
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.pref_theme_mode),
                    items = listOf(
                        stringResource(R.string.theme_mode_system),
                        stringResource(R.string.theme_mode_light),
                        stringResource(R.string.theme_mode_dark),
                    ),
                    selectedIndex = listOf("system", "light", "dark").indexOf(settings.themeMode).coerceAtLeast(0),
                    onSelectedIndexChange = { i -> update { it.copy(themeMode = listOf("system", "light", "dark")[i]) } },
                )
                SwitchPreference(
                    title = stringResource(R.string.pref_verbose_log),
                    summary = stringResource(R.string.pref_verbose_log_summary),
                    checked = settings.verboseLog,
                    onCheckedChange = { v ->
                        HyperLog.isVerbose = v
                        update { it.copy(verboseLog = v) }
                    },
                )
            }
        }
        item {
            Group(stringResource(R.string.group_app_info)) {
                ArrowPreference(title = stringResource(R.string.pref_about), onClick = { open(PageId.ABOUT) })
            }
        }
    }
    RestartScopeDialog(
        show = showScopeRestartDialog,
        onDismiss = { showScopeRestartDialog = false },
        onRestart = { targets ->
            SystemUiRestarter.restart(context, targets)
            showScopeRestartDialog = false
        },
    )
}

@Composable
internal fun RestartScopeDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onRestart: (Set<ScopeApplication>) -> Unit,
    availableTargets: Set<ScopeApplication> = ScopeApplication.entries.toSet(),
) {
    var selectedTargets by remember(show) {
        mutableStateOf<Set<ScopeApplication>>(emptySet())
    }
    WindowDialog(
        show = show,
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.dialog_restart_scope_title),
                modifier = Modifier.fillMaxWidth(),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start,
            )
            Text(
                stringResource(R.string.dialog_restart_scope_subtitle),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Start,
            )
            ScopeRestartCheckboxes(
                selectedTargets = selectedTargets,
                onSelectedTargetsChange = { selectedTargets = it },
                availableTargets = availableTargets,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onDismiss, Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = { onRestart(selectedTargets) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    enabled = selectedTargets.isNotEmpty(),
                ) { Text(stringResource(R.string.action_restart)) }
            }
        }
    }
}

@Composable
internal fun ScopeRestartCheckboxes(
    selectedTargets: Set<ScopeApplication>,
    onSelectedTargetsChange: (Set<ScopeApplication>) -> Unit,
    availableTargets: Set<ScopeApplication> = ScopeApplication.entries.toSet(),
) {
    ScopeApplication.entries.filter { it in availableTargets }.forEach { target ->
        val toggle = {
            onSelectedTargetsChange(
                if (target in selectedTargets) selectedTargets - target else selectedTargets + target,
            )
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                state = if (target in selectedTargets) ToggleableState.On else ToggleableState.Off,
                onClick = toggle,
            )
            Text(
                stringResource(target.titleRes),
                style = MiuixTheme.textStyles.body1,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}
