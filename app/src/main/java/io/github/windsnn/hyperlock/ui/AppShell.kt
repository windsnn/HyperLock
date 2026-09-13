package io.github.windsnn.hyperlock.ui

import android.app.Activity
import android.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.effects.blur
import io.github.libxposed.service.XposedService
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.*
import io.github.windsnn.hyperlock.HookApplication
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import top.yukonga.miuix.kmp.icon.extended.All
import io.github.windsnn.hyperlock.HookSettings
import io.github.windsnn.hyperlock.HookSettingsStore

internal enum class Tab(val title: String) {
    LOCK("锁屏"),
    SETTINGS("设置"),
}

internal enum class PageId {
    ABOUT,
    OPEN,
}

@Composable
internal fun Root(hooks: HookSettingsStore) {
    val service by HookApplication.service.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(hooks.settings) }
    val controller = remember(settings.themeMode) {
        ThemeController(
            when (settings.themeMode) {
                "light" -> ColorSchemeMode.Light
                "dark" -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            },
        )
    }
    LaunchedEffect(service) {
        service?.let {
            hooks.syncRemote(it)
            settings = hooks.settings
        }
    }
    MiuixTheme(controller = controller) {
        ApplySystemBarAppearance()
        Shell(
            settings = settings,
            service = service,
            update = { transform ->
                hooks.update(service, transform)
                settings = hooks.settings
            },
        )
    }
}

@Composable
internal fun ApplySystemBarAppearance() {
    val activity = LocalContext.current as? Activity ?: return
    val view = LocalView.current
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    SideEffect {
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }
}

@Composable
internal fun Shell(
    settings: HookSettings,
    service: XposedService?,
    update: ((HookSettings) -> HookSettings) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.LOCK) }
    val pageStack = remember { mutableStateListOf<PageId>() }
    val page = pageStack.lastOrNull()
    val openPage: (PageId) -> Unit = { target ->
        if (pageStack.lastOrNull() != target) pageStack += target
    }
    val dismissPage: () -> Unit = {
        if (pageStack.isNotEmpty()) pageStack.removeAt(pageStack.lastIndex)
    }
    BackHandler(enabled = page != null, onBack = dismissPage)
    val backdrop = rememberLayerBackdrop()

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            when (tab) {
                Tab.LOCK -> Lock(settings, update)
                Tab.SETTINGS -> SettingsHome(
                    settings = settings,
                    online = service != null,
                    update = update,
                    open = openPage,
                )
            }
        }
        BottomBar(tab, { tab = it }, backdrop, Modifier.align(Alignment.BottomCenter))

        AnimatedVisibility(
            visible = page != null,
            enter = slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 6 } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(240)) { it / 6 } + fadeOut(tween(180)),
        ) {
            Box(Modifier.fillMaxSize()) {
                when (page) {
                    PageId.ABOUT -> About(dismissPage, openPage)
                    PageId.OPEN -> OpenSource(dismissPage)
                    null -> Unit
                }
            }
        }
    }
}

@Composable
internal fun BottomBar(
    tab: Tab,
    select: (Tab) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val surface = MiuixTheme.colorScheme.surface
    Box(
        modifier
            .fillMaxWidth()
            .drawPlainBackdrop(
                backdrop = backdrop,
                shape = { androidx.compose.ui.graphics.RectangleShape },
                effects = { blur(20.dp.toPx()) },
                onDrawSurface = { drawRect(surface.copy(alpha = 0.75f)) },
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(64.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { item ->
                val isSelected = tab == item
                val color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { select(item) }
                        .padding(horizontal = 28.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        imageVector = if (item == Tab.LOCK) MiuixIcons.Regular.All else MiuixIcons.Regular.Settings,
                        contentDescription = item.title,
                        colorFilter = ColorFilter.tint(color),
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = item.title,
                        style = MiuixTheme.textStyles.body2,
                        color = color,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
