package dev.aigw.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.theme.LocalAppColors

/**
 * 软件更新页。
 *
 * 从「我的 → 关于软件」里的版本号点进来（更新越来越频繁，
 * 这些条目再挤在「我的」页底部会把关于段落切得很碎）。
 */
@Composable
fun UpdateScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current

    PageScaffold(
        title = "软件更新",
        subtitle = "版本、通道与下载",
        leading = { BackButton(onBack) },
    ) {
        SectionCard(enterIndex = 0) {
            SectionLabel("当前版本")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = installedVersionLabel(context),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "天元鼎 · 本地 OpenAI 兼容网关",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ActionRow(
                title = "检查更新",
                subtitle = if (state.updateChannel == UpdateChannel.Beta) {
                    "获取最新 CI 构建包（覆盖在 ci-latest 上）"
                } else {
                    "从 GitHub Releases 获取最新正式版"
                },
                meta = if (state.updateChecking) "检查中…" else "",
                enabled = !state.updateChecking,
            ) { viewModel.checkUpdate() }
            ActionRow(
                title = "项目地址",
                subtitle = "在浏览器打开源码仓库",
                meta = "GitHub",
            ) { viewModel.openProjectRepo() }
        }

        SectionCard(enterIndex = 1) {
            SectionLabel("更新通道")
            Text(
                text = "选择跟随哪条线。当前是${state.updateChannel.label}：${state.updateChannel.detail}。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChannelPicker(
                current = state.updateChannel,
                onSelect = { viewModel.setUpdateChannel(it) },
            )
            Text(
                text = "说明：测试版直接取 CI 构建产物（每次代码改动都会重新打包），" +
                    "可能包含未验证的改动，出问题请切回公开版。切换通道后会立即重新检查一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(enterIndex = 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("自动检查更新", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "打开应用时后台检测，每天最多一次（失败静默）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.autoCheckUpdate,
                    onCheckedChange = { viewModel.setAutoCheckUpdate(it) },
                )
            }
        }
    }
}

/** 通道单选：整行可点，右侧显示选中态（不用 RadioButton，避免和整体编辑式排版冲突）。 */
@Composable
private fun ChannelPicker(current: UpdateChannel, onSelect: (UpdateChannel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        UpdateChannel.entries.forEach { channel ->
            val selected = channel == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !selected) { onSelect(channel) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = channel.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (selected) LocalAppColors.current.goldStart else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = channel.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selected) {
                    Text(
                        text = "当前",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAppColors.current.goldStart,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    meta: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "→",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalAppColors.current.goldStart,
        )
    }
}

/** 版本展示：versionName 去掉与 versionCode 重复的末段（0.1.1.42 → 0.1.1（build 42））。 */
@Composable
private fun installedVersionLabel(context: android.content.Context): String = runCatching {
    val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
    val name = pkg.versionName.orEmpty()
    @Suppress("DEPRECATION")
    val code = pkg.versionCode
    val parts = name.split('.')
    val display = if (parts.size > 3 && parts.last().toIntOrNull() == code) {
        parts.dropLast(1).joinToString(".")
    } else {
        name
    }
    "$display（build $code）"
}.getOrDefault("未知")
