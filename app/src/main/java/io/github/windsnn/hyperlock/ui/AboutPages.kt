package io.github.windsnn.hyperlock.ui

import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.kyant.backdrop.Backdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.*
import io.github.windsnn.hyperlock.BuildConfig
import io.github.windsnn.hyperlock.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.icon.extended.ChevronForward

@Composable
internal fun About(
    back: () -> Unit,
    open: (PageId) -> Unit,
) = AppPage("关于", back) { padding, scroll ->
    val context = LocalContext.current
    AppList(padding, scroll, 28) {
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Image(painterResource(R.drawable.ic_hyperlock_full), "澎湃锁屏", Modifier.size(72.dp), contentScale = ContentScale.Fit)
                Text("澎湃锁屏", style = MiuixTheme.textStyles.title1, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                Text("一个小巧的用于自定义小米澎湃 OS 锁屏与息屏的模块 (HyperLock)。", style = MiuixTheme.textStyles.body1, modifier = Modifier.padding(top = 6.dp))
                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(MiuixTheme.colorScheme.outline.copy(alpha = .22f)))
                Text(BuildConfig.VERSION_NAME, style = MiuixTheme.textStyles.body2)
            }
        }
        item {
            Group("开源与致谢") {
                ArrowPreference(
                    title = "项目仓库",
                    summary = "github.com/windsnn/HyperLock",
                    onClick = { openUrl(context, "https://github.com/windsnn/HyperLock") },
                )
                ArrowPreference(
                    title = "上游项目",
                    summary = "衍生自 github.com/ColdP/HyperChanger",
                    onClick = { openUrl(context, "https://github.com/ColdP/HyperChanger") },
                )
                ArrowPreference(
                    title = "界面组件",
                    summary = "基于 MIUIX 的 HyperOS 风格控件库",
                    onClick = { openUrl(context, "https://compose-miuix-ui.github.io/miuix/") },
                )
                ArrowPreference(
                    title = "开源许可",
                    summary = "第三方组件、署名与许可文本",
                    onClick = { open(PageId.OPEN) },
                )
            }
        }
        item {
            Column(Modifier.fillMaxWidth()) {
                SmallTitle("免责声明", insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Text(
                        "本模块为个人第三方项目，与小米公司无任何关联，未获其授权或背书。\n" +
                            "它通过 LSPosed 修改系统界面与息屏的内部实现，仅在你勾选的作用域内生效；" +
                            "不同机型与系统版本的表现可能不同，未验证的版本请自行评估风险。\n" +
                            "刷机、Root、LSPosed 与安装本模块的风险由使用者自行承担。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

private data class OpenProject(
    val name: String,
    val version: String,
    val license: String,
    val description: String,
    val url: String,
)

private val openProjects = listOf(
    OpenProject("MIUIX", "0.9.3", "Apache-2.0", "HyperOS 风格界面、偏好设置、图标与模糊效果", "https://github.com/compose-miuix-ui/miuix"),
    OpenProject("Backdrop", "2.0.0", "Apache-2.0", "基于 RuntimeShader 的实时背景模糊渲染组件", "https://github.com/Kyant0/AndroidLiquidGlass"),
    OpenProject("Shapes", "1.2.0", "Apache-2.0", "Compose 矢量形状与插值工具", "https://github.com/Kyant0/Shapes"),
    OpenProject("Lyricon", "0.1.70", "Apache-2.0", "锁屏迷你播放器歌词订阅与解析组件", "https://github.com/proify/lyricon"),
    OpenProject("libxposed", "102.0.0", "Apache-2.0", "Xposed 模块 API 与运行时服务", "https://github.com/libxposed"),
    OpenProject("AndroidX", "1.7.8 – 1.17.0", "Apache-2.0", "core-ktx、activity-compose、lifecycle-runtime-compose、material-icons-extended", "https://developer.android.com/jetpack/androidx"),
    OpenProject("Compose Multiplatform", "1.11.0", "Apache-2.0", "ui、foundation、animation", "https://github.com/JetBrains/compose-multiplatform"),
    OpenProject("HyperChanger", "1.0.1", "MIT", "上游项目：代码与界面基础", "https://github.com/ColdP/HyperChanger"),
)

@Composable
internal fun OpenSource(back: () -> Unit) = AppPage("开源代码声明", back) { padding, scroll ->
    val context = LocalContext.current
    AppList(padding, scroll, 28) {
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Text(
                    "澎湃锁屏 (HyperLock) 使用了以下开源项目，感谢所有项目作者与贡献者。",
                    style = MiuixTheme.textStyles.body1,
                )
            }
        }
        item {
            SmallTitle("使用到的开源项目", insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
        }
        items(openProjects.size) { i ->
            val item = openProjects[i]
            Card(Modifier.fillMaxWidth().clickable { openUrl(context, item.url) }, insideMargin = PaddingValues(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                        Text("${item.version} · ${item.license}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 3.dp))
                        Text(item.description, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 6.dp))
                    }
                    Image(MiuixIcons.Regular.ChevronForward, null, Modifier.padding(start = 12.dp).size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary))
                }
            }
        }
        item {
            Group("许可信息") {
                ArrowPreference(
                    title = "Apache License 2.0 全文",
                    onClick = {
                        openUrl(context, "https://github.com/windsnn/HyperLock/blob/main/LICENSE")
                    },
                )
                ArrowPreference(
                    title = "第三方组件完整清单",
                    summary = "含传递依赖与版本对应关系",
                    onClick = {
                        openUrl(context, "https://github.com/windsnn/HyperLock/blob/main/THIRD-PARTY.md")
                    },
                )
                ArrowPreference(
                    title = "上游署名与 MIT 许可",
                    onClick = {
                        openUrl(context, "https://github.com/windsnn/HyperLock/blob/main/NOTICE")
                    },
                )
            }
        }
    }
}
