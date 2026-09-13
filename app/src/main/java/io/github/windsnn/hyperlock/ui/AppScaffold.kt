package io.github.windsnn.hyperlock.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Refresh
import io.github.windsnn.hyperlock.DeviceCompat

@Composable
internal fun AppPage(
    title: String,
    onBack: (() -> Unit)? = null,
    onRestoreDefaults: (() -> Unit)? = null,
    content: @Composable (PaddingValues, ScrollBehavior) -> Unit,
) {
    val scroll = MiuixScrollBehavior()
    val backdrop = rememberLayerBackdrop()
    val surface = MiuixTheme.colorScheme.surface
    val collapsedFraction = scroll.state.collapsedFraction.coerceIn(0f, 1f)
    Scaffold(
        topBar = {
            Box {
                if (isRuntimeShaderSupported()) {
                    Box(
                        Modifier.matchParentSize()
                            .graphicsLayer { alpha = 1f - collapsedFraction }
                            .drawPlainBackdrop(
                                backdrop = backdrop,
                                shape = { androidx.compose.ui.graphics.RectangleShape },
                                effects = { blur(6.dp.toPx()) },
                                onDrawSurface = { drawRect(surface.copy(alpha = 0.55f)) },
                            ),
                    )
                    ProgressiveBlurLayer(backdrop, collapsedFraction)
                } else {
                    Box(Modifier.matchParentSize().background(surface.copy(alpha = 0.82f * (1f - collapsedFraction))))
                }
                TopAppBar(
                    title = "",
                    largeTitle = title,
                    color = ComposeColor.Transparent,
                    scrollBehavior = scroll,
                    navigationIcon = {
                        if (onBack != null) GlassBackButton(backdrop, collapsedFraction, onBack)
                    },
                    actions = {
                        if (onRestoreDefaults != null) {
                            GlassRestoreButton(backdrop, collapsedFraction, onRestoreDefaults)
                        }
                    },
                )
                Box(
                    Modifier.windowInsetsPadding(WindowInsets.statusBars).height(52.dp).fillMaxWidth()
                        .graphicsLayer { alpha = scroll.state.collapsedFraction }
                        .padding(horizontal = 64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(title, style = MiuixTheme.textStyles.title3, maxLines = 1)
                }
            }
        },
    ) { padding -> Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { content(padding, scroll) } }
}

@Composable
internal fun BoxScope.ProgressiveBlurLayer(backdrop: LayerBackdrop, collapsedFraction: Float) {
    Box(
        Modifier.matchParentSize()
            .expandDrawHeight(1.184625f)
            .graphicsLayer { alpha = collapsedFraction }
            .drawPlainBackdrop(
                backdrop = backdrop,
                shape = { androidx.compose.ui.graphics.RectangleShape },
                effects = {
                    blur(16.dp.toPx())
                    runtimeShaderEffect(
                        key = "progressive-blur-alpha-mask",
                        shaderString = PROGRESSIVE_BLUR_ALPHA_MASK_SHADER,
                        uniformShaderName = "content",
                    ) { setFloatUniform("size", size.width, size.height) }
                },
            ),
    )
}

@Composable
internal fun GlassBackButton(backdrop: LayerBackdrop, collapsedFraction: Float, onClick: () -> Unit) {
    val glassTint = MiuixTheme.colorScheme.surface.copy(alpha = .42f)
    val iconColor = MiuixTheme.colorScheme.onSurface
    val glassAlpha by animateFloatAsState(collapsedFraction, tween(180), label = "backButtonGlassAlpha")
    Box(
        Modifier
            .padding(start = 8.dp)
            .size(46.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { alpha = glassAlpha }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        blur(4.dp.toPx())
                    },
                    highlight = { Highlight.Default },
                    shadow = {
                        Shadow.Default.copy(
                            radius = 5.dp,
                            color = ComposeColor.Black,
                            alpha = .24f,
                        )
                    },
                    innerShadow = null,
                    onDrawSurface = { drawCircle(glassTint) },
                ),
        )
        Image(
            MiuixIcons.Regular.ChevronBackward,
            "返回",
            Modifier.size(22.dp).graphicsLayer { translationX = -1.15.dp.toPx() },
            colorFilter = ColorFilter.tint(iconColor),
        )
    }
}

@Composable
internal fun GlassRestoreButton(backdrop: LayerBackdrop, collapsedFraction: Float, onClick: () -> Unit) {
    val glassTint = MiuixTheme.colorScheme.surface.copy(alpha = .42f)
    val iconColor = MiuixTheme.colorScheme.onSurface
    val glassAlpha by animateFloatAsState(collapsedFraction, tween(180), label = "restoreButtonGlassAlpha")
    Box(
        Modifier
            .padding(end = 8.dp)
            .size(46.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { alpha = glassAlpha }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        blur(4.dp.toPx())
                    },
                    highlight = { Highlight.Default },
                    shadow = {
                        Shadow.Default.copy(
                            radius = 5.dp,
                            color = ComposeColor.Black,
                            alpha = .24f,
                        )
                    },
                    innerShadow = null,
                    onDrawSurface = { drawCircle(glassTint) },
                ),
        )
        Image(
            MiuixIcons.Regular.Refresh,
            "恢复默认值",
            Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(iconColor),
        )
    }
}

private const val PROGRESSIVE_BLUR_ALPHA_MASK_SHADER = """
uniform shader content;
uniform float2 size;
half4 main(float2 coord) {
    float blurAlpha = smoothstep(size.y, size.y * 0.5, coord.y);
    return content.eval(coord) * blurAlpha;
}
"""

internal fun Modifier.expandDrawHeight(factor: Float) = layout { measurable, constraints ->
    val expandedHeight = (constraints.maxHeight * factor).toInt()
    val placeable = measurable.measure(constraints.copy(minHeight = expandedHeight, maxHeight = expandedHeight))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(0, 0) }
}

@Composable
internal fun AppList(padding: PaddingValues, scroll: ScrollBehavior, bottom: Int = 112, items: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).nestedScroll(scroll.nestedScrollConnection),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 4.dp, 16.dp, padding.calculateBottomPadding() + bottom.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = items,
    )
}

@Composable
internal fun ServiceCard(online: Boolean) {
    val color = if (online) ComposeColor(0xFF38A169) else ComposeColor(0xFFE05353)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color.copy(alpha = .14f)), insideMargin = PaddingValues(16.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text("LSPosed", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
            Text(if (online) "已连接" else "未连接", style = MiuixTheme.textStyles.body2, color = color, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** 设备与系统环境卡片：一眼看出当前机型/系统是否处在适配范围内。 */
@Composable
internal fun DeviceCompatCard(compat: DeviceCompat) {
    val color = when (compat.level) {
        DeviceCompat.Level.SUPPORTED -> ComposeColor(0xFF38A169)
        DeviceCompat.Level.UNVERIFIED -> ComposeColor(0xFFC98A18)
        DeviceCompat.Level.UNSUPPORTED -> ComposeColor(0xFFE05353)
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color.copy(alpha = .14f)), insideMargin = PaddingValues(16.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text("设备检测", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
            Text(compat.title, style = MiuixTheme.textStyles.body2, color = color, modifier = Modifier.padding(top = 4.dp))
            Text(
                listOfNotNull(
                    compat.deviceName,
                    compat.romVersion,
                    compat.systemUiVersion?.let { "系统界面 $it" },
                ).joinToString(" · "),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (compat.level != DeviceCompat.Level.SUPPORTED) {
                Text(
                    compat.reason,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            compat.notes.forEach { note ->
                Text(
                    note,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SmallTitle(title, insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
        Card(Modifier.fillMaxWidth()) { content() }
    }
}

/** 锁屏页统一的分组展开动画。 */
@Composable
internal fun ExpandGroup(visible: Boolean, content: @Composable AnimatedVisibilityScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
        content = content,
    )
}

internal fun openUrl(context: android.content.Context, url: String) = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
