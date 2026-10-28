package dev.aigw.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp


/**
 * 任务中心：批量签到与额度维护。
 *
 * 只对声明了签到能力的供应商（Trae / WorkBuddy）执行签到；
 * 额度刷新对所有供应商生效（自定义供应商没有额度接口，会返回「不支持」）。
 */
/** 折叠时仍然展示的账号数：超过这个数才值得折叠。 */
private const val COLLAPSE_THRESHOLD = 3

@Composable
fun TaskCenterScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    BatchOutcomeHost(state.batchOutcome) { viewModel.dismissBatchOutcome() }
    PageScaffold(
        title = "任务中心",
        subtitle = "签到与额度",
        leading = { BackButton(onBack) },
    ) {
        BatchProgressHost(state.batchProgress)

        SectionCard(enterIndex = 0) {
            SectionLabel("批量操作")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { viewModel.checkinAll() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("批量签到")
                }
                OutlinedButton(
                    onClick = { viewModel.runAllTasks() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("批量任务")
                }
            }
            OutlinedButton(
                onClick = { viewModel.refreshAllCredits() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("刷新全部额度")
            }
            Text(
                text = "签到作用于 Trae、WorkBuddy、小浣熊（国际版没有签到制度，自动跳过）；" +
                    "任务作用于 Loomy 的新手任务；额度刷新作用于全部账号。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.providers.none { state.accountsOf(it.id).isNotEmpty() }) {
            SectionCard(enterIndex = 1) {
                EmptyHint("还没有账号", "先去「账号」页添加账号，再回来签到。")
            }
            return@PageScaffold
        }

        // ★ 只列有额度/签到概念的供应商。
        //   Agnes 这类免费且无余额可查的供应商原样列出只会显示「额度 —」，
        //   看着像数据缺失；它们也没有签到，放进任务中心没有意义。
        val shown = state.providers.filter {
            it.capabilities.any { c ->
                c == dev.aigw.core.provider.ProviderCapability.CREDIT_REFRESH ||
                    c == dev.aigw.core.provider.ProviderCapability.CHECKIN ||
                    c == dev.aigw.core.provider.ProviderCapability.TASKS
            }
        }
        // 折叠状态按供应商记住（键 = providerId）：账号多的供应商默认收起，
        // 避免一屏被某个供应商占满、其它账号翻不到。
        // 用 ArrayList 而不是 Set：rememberSaveable 只认 Bundle 可序列化类型，
        // Set<String> 存不进去，旋转屏幕会崩（IllegalArgumentException: cannot be saved）。
        var expandedIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }

        for (info in shown) {
            val accounts = state.accountsOf(info.id)
            if (accounts.isEmpty()) continue
            val collapsible = accounts.size > COLLAPSE_THRESHOLD
            val isExpanded = !collapsible || expandedIds.contains(info.id)
            val visible = if (isExpanded) accounts else accounts.take(COLLAPSE_THRESHOLD)
            SectionCard(enterIndex = 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .then(
                            if (collapsible) Modifier.clickable {
                                expandedIds = ArrayList(expandedIds).apply {
                                    if (isExpanded) remove(info.id) else add(info.id)
                                }
                            } else {
                                Modifier
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        SectionLabel(info.displayName)
                        Text(
                            text = "${accounts.size} 个账号 · 可用 ${accounts.count { it.usable }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (collapsible) {
                        Text(
                            text = if (isExpanded) "收起" else "展开",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Icon(
                            imageVector = if (isExpanded) {
                                Icons.Filled.KeyboardArrowUp
                            } else {
                                Icons.Filled.KeyboardArrowDown
                            },
                            contentDescription = if (isExpanded) "收起" else "展开",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                for (account in visible) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = account.nickname.ifEmpty { account.uid },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = buildString {
                                append("额度 ")
                                append(if (account.creditsKnown) account.credits.toString() else "—")
                                append(" · ")
                                append(accountStatusText(account))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (account.detail.isNotEmpty()) {
                            Text(
                                text = account.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
