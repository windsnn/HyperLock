package io.github.windsnn.hyperlock.ui

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
) = AppPage("设置") { padding, scroll ->
    val context = LocalContext.current
    var showScopeRestartDialog by remember { mutableStateOf(false) }
    val compat = remember(context) { checkDeviceCompat(context) }
    AppList(padding, scroll, 88) {
        item { ServiceCard(online) }
        item { DeviceCompatCard(compat) }
        item {
            Group("应用设置") {
                ArrowPreference(
                    title = "重启作用域应用",
                    onClick = { showScopeRestartDialog = true },
                )
                OverlayDropdownPreference(
                    title = "主题模式",
                    items = listOf("跟随系统", "浅色模式", "深色模式"),
                    selectedIndex = listOf("system", "light", "dark").indexOf(settings.themeMode).coerceAtLeast(0),
                    onSelectedIndexChange = { i -> update { it.copy(themeMode = listOf("system", "light", "dark")[i]) } },
                )
                SwitchPreference(
                    title = "详细日志",
                    summary = "捕获运行状态与排查日志，默认关闭仅记录异常",
                    checked = settings.verboseLog,
                    onCheckedChange = { v ->
                        HyperLog.isVerbose = v
                        update { it.copy(verboseLog = v) }
                    },
                )
            }
        }
        item {
            Group("应用信息") {
                ArrowPreference(title = "关于", onClick = { open(PageId.ABOUT) })
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
                "重启作用域应用",
                modifier = Modifier.fillMaxWidth(),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start,
            )
            Text(
                "选择需要重启的应用",
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
                Button(onDismiss, Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = { onRestart(selectedTargets) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    enabled = selectedTargets.isNotEmpty(),
                ) { Text("重启") }
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
                target.title,
                style = MiuixTheme.textStyles.body1,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}
