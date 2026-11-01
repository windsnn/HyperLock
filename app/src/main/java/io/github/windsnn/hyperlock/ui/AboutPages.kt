package io.github.windsnn.hyperlock.ui

import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
) = AppPage(stringResource(R.string.page_about), back) { padding, scroll ->
    val context = LocalContext.current
    AppList(padding, scroll, 28) {
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Image(painterResource(R.drawable.ic_hyperlock_full), stringResource(R.string.app_name), Modifier.size(72.dp), contentScale = ContentScale.Fit)
                Text(stringResource(R.string.app_name), style = MiuixTheme.textStyles.title1, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                Text(stringResource(R.string.about_app_desc), style = MiuixTheme.textStyles.body1, modifier = Modifier.padding(top = 6.dp))
                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(MiuixTheme.colorScheme.outline.copy(alpha = .22f)))
                Text(BuildConfig.VERSION_NAME, style = MiuixTheme.textStyles.body2)
            }
        }
        item {
            Group(stringResource(R.string.about_group_open_source)) {
                ArrowPreference(
                    title = stringResource(R.string.about_item_repo),
                    summary = "github.com/windsnn/HyperLock",
                    onClick = { openUrl(context, "https://github.com/windsnn/HyperLock") },
                )
                ArrowPreference(
                    title = stringResource(R.string.about_item_upstream),
                    summary = stringResource(R.string.about_item_upstream_summary),
                    onClick = { openUrl(context, "https://github.com/ColdP/HyperChanger") },
                )
                ArrowPreference(
                    title = stringResource(R.string.about_item_ui_lib),
                    summary = stringResource(R.string.about_item_ui_lib_summary),
                    onClick = { openUrl(context, "https://compose-miuix-ui.github.io/miuix/") },
                )
                ArrowPreference(
                    title = stringResource(R.string.about_item_licenses),
                    summary = stringResource(R.string.about_item_licenses_summary),
                    onClick = { open(PageId.OPEN) },
                )
            }
        }
        item {
            Column(Modifier.fillMaxWidth()) {
                SmallTitle(stringResource(R.string.about_disclaimer_title), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Text(
                        stringResource(R.string.about_disclaimer_text),
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
    val descriptionRes: Int,
    val url: String,
)

private val openProjects = listOf(
    OpenProject("MIUIX", "0.9.3", "Apache-2.0", R.string.open_proj_miuix_desc, "https://github.com/compose-miuix-ui/miuix"),
    OpenProject("Backdrop", "2.0.0", "Apache-2.0", R.string.open_proj_backdrop_desc, "https://github.com/Kyant0/AndroidLiquidGlass"),
    OpenProject("Shapes", "1.2.0", "Apache-2.0", R.string.open_proj_shapes_desc, "https://github.com/Kyant0/Shapes"),
    OpenProject("Lyricon", "0.1.70", "Apache-2.0", R.string.open_proj_lyricon_desc, "https://github.com/proify/lyricon"),
    OpenProject("libxposed", "102.0.0", "Apache-2.0", R.string.open_proj_libxposed_desc, "https://github.com/libxposed"),
    OpenProject("AndroidX", "1.7.8 – 1.17.0", "Apache-2.0", R.string.open_proj_androidx_desc, "https://developer.android.com/jetpack/androidx"),
    OpenProject("Compose Multiplatform", "1.11.0", "Apache-2.0", R.string.open_proj_compose_desc, "https://github.com/JetBrains/compose-multiplatform"),
    OpenProject("HyperChanger", "1.0.1", "MIT", R.string.open_proj_hyperchanger_desc, "https://github.com/ColdP/HyperChanger"),
)

@Composable
internal fun OpenSource(back: () -> Unit) = AppPage(stringResource(R.string.page_open_source), back) { padding, scroll ->
    val context = LocalContext.current
    AppList(padding, scroll, 28) {
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Text(
                    stringResource(R.string.open_source_intro),
                    style = MiuixTheme.textStyles.body1,
                )
            }
        }
        item {
            SmallTitle(stringResource(R.string.open_source_projects_title), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
        }
        items(openProjects.size) { i ->
            val item = openProjects[i]
            Card(Modifier.fillMaxWidth().clickable { openUrl(context, item.url) }, insideMargin = PaddingValues(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                        Text("${item.version} · ${item.license}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 3.dp))
                        Text(stringResource(item.descriptionRes), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 6.dp))
                    }
                    Image(MiuixIcons.Regular.ChevronForward, null, Modifier.padding(start = 12.dp).size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary))
                }
            }
        }
        item {
            Group(stringResource(R.string.open_source_license_info)) {
                ArrowPreference(
                    title = stringResource(R.string.open_source_license_text_title),
                    onClick = {
                        openUrl(context, "https://github.com/windsnn/HyperLock/blob/main/LICENSE")
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.open_source_third_party_title),
                    summary = stringResource(R.string.open_source_third_party_summary),
                    onClick = {
                        openUrl(context, "https://github.com/windsnn/HyperLock/blob/main/THIRD-PARTY.md")
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.open_source_upstream_notice_title),
                    onClick = {
                        openUrl(context, "https://github.com/windsnn/HyperLock/blob/main/NOTICE")
                    },
                )
            }
        }
    }
}
