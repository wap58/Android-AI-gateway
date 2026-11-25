package dev.aigw.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.provider.ACTION_TASKS
import dev.aigw.core.provider.ProviderTask
import dev.aigw.core.provider.ProviderTaskListView

/**
 * 单个账号的成长任务明细页。
 *
 * 从供应商详情的「一键任务」进入，而不是把任务列表塞在账号卡里：
 * 任务动辄十几条，卡内展示会把卡片撑到两三屏，同页其他账号被挤得很远。
 *
 * ★ 这里**只能用 Column，不能用 LazyColumn**：外层 PageScaffold 的内容区已经套了
 *   verticalScroll，再嵌一层可滚动容器会拿到无限高度约束，直接抛
 *   `IllegalStateException: Vertically scrollable component was measured with an
 *   infinity maximum height constraints` 闪退。
 */
@Composable
fun ProviderTaskCenterScreen(
    providerId: String,
    uid: String,
    actions: ProviderUiActions,
) {
    val view = actions.taskListOf(providerId, uid)

    LaunchedEffect(uid) {
        if (view == null) actions.onLoadTaskList(providerId, uid)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 抬头由外层 PageScaffold 提供（进入任务中心时它显示「任务中心」），
        // 这里再画一遍会变成双抬头，且顶部留出大片空白。
        SectionCard {
            SummaryCard(view, providerId, uid, actions)

            when {
                view == null -> Hint("正在加载任务列表…")
                view.error.isNotEmpty() && view.tasks.isEmpty() -> Text(
                    text = "加载失败：${view.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                view.tasks.isEmpty() -> Hint(
                    // 三态：只有上游明确说不在期才说「未开启」，没说就照实说没有任务
                    if (view.inPeriod == false) "成长活动未开启，暂无可查看的任务"
                    else "该账号没有返回任何任务",
                )
                else -> {
                // 一张卡里用分隔线排开全部任务：一条任务一张卡会把页面撑得很长（5 条就 5 张卡）
                view.tasks.forEachIndexed { index, task ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                        TaskRow(task, providerId, uid, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    view: ProviderTaskListView?,
    providerId: String,
    uid: String,
    actions: ProviderUiActions,
) {
    val tasks = view?.tasks.orEmpty()
    val done = tasks.count { it.completed }
    val claimable = tasks.count { it.claimable }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (tasks.isEmpty()) "暂无任务" else "共 ${tasks.size} 个任务",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = buildString {
                        append("已完成 $done 个")
                        if (claimable > 0) append(" · $claimable 个待领取")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "刷新任务列表",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { actions.onLoadTaskList(providerId, uid) }
                    .padding(8.dp),
            )
        }
        if (claimable > 0) {
            OutlineActionButton("一键领取（$claimable）") {
                actions.onAction(providerId, uid, ACTION_TASKS, JsonObject())
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: ProviderTask,
    providerId: String,
    uid: String,
    actions: ProviderUiActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = statusText(task),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor(task),
            )
        }
        val meta = buildString {
            if (task.target > 0) append("进度 ${task.progressText()}")
            if (task.rewardCredit > 0) {
                if (isNotEmpty()) append(" · ")
                append("奖励 ${task.rewardCredit} 积分")
            }
            if (task.description.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(task.description)
            }
        }
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (task.claimable) {
            OutlineActionButton("领取") {
                actions.onAction(providerId, uid, ACTION_TASKS, JsonObject())
            }
        }
    }
}

private fun statusText(task: ProviderTask): String = when {
    task.claimed -> "已领取"
    task.locked -> "未解锁"
    task.progressDone -> "可领取"
    else -> "未完成"
}

@Composable
private fun statusColor(task: ProviderTask): androidx.compose.ui.graphics.Color = when {
    task.claimed -> MaterialTheme.colorScheme.onSurfaceVariant
    task.locked -> MaterialTheme.colorScheme.outline
    task.progressDone -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.error
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
