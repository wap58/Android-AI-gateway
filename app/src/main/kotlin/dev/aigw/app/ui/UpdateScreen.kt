package dev.aigw.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * 从「设置 → 关于软件」里的版本号点进来（更新越来越频繁，
 * 这些条目再挤在「设置」页底部会把关于段落切得很碎）。
 */
@Composable
fun UpdateScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    // 「项目地址」有两个仓库（本 fork / 上游原项目），让用户自己选
    var showRepoPicker by remember { mutableStateOf(false) }

    if (showRepoPicker) {
        AlertDialog(
            onDismissRequest = { showRepoPicker = false },
            title = { Text("打开哪个仓库？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RepoOption(
                        title = "本项目（天元鼎）",
                        detail = "当前使用的版本、更新与 Issue 都在这里",
                        url = "github.com/wap58/Android-AI-gateway",
                    ) {
                        showRepoPicker = false
                        viewModel.openProjectRepo()
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RepoOption(
                        title = "原项目（上游）",
                        detail = "天元鼎基于它二次开发，功能与实现思路的源头",
                        url = "github.com/xibai6668/Android-AI-gateway",
                    ) {
                        showRepoPicker = false
                        viewModel.openUpstreamRepo()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRepoPicker = false }) { Text("取消") }
            },
        )
    }

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
            ) { showRepoPicker = true }
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

/** 选择器里的一项：标题 + 说明 + 仓库地址。 */
@Composable
private fun RepoOption(title: String, detail: String, url: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = LocalAppColors.current.goldStart,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = url,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
