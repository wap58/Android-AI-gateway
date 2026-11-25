package dev.aigw.app.ui.providers.codebuddy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.google.gson.JsonObject
import dev.aigw.app.ui.AccountCardShell
import dev.aigw.app.ui.ChoiceChip
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.PagedAccountList
import dev.aigw.app.ui.RegionSelector
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.SubPage
import dev.aigw.app.ui.bouncyClick
import dev.aigw.app.ui.accountStatusText
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.ProviderTask

import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.ACTION_TASKS
import dev.aigw.core.provider.codebuddy.CodeBuddyProvider

object CodeBuddyUi : ProviderUi {

    override val id = CodeBuddyProvider.ID

    override val managesOwnAccounts: Boolean
        get() = true

    override fun description() = "在浏览器中登录，走每日积分"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Language,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        // 滑块状态持久化在供应商设置里（region=cn/global），登录与账号列表都跟随它
        var global by rememberSaveable {
            mutableStateOf(actions.providerOptionOf(id, "region", CodeBuddyProvider.REGION_CN) == CodeBuddyProvider.REGION_GLOBAL)
        }
        var expanded by remember { mutableStateOf(false) }
        var raw by remember { mutableStateOf("") }
        val region = if (global) CodeBuddyProvider.REGION_GLOBAL else CodeBuddyProvider.REGION_CN
        SectionCard(enterIndex = 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    SectionLabel("服务区域")
                }
                RegionSelector(
                    isGlobal = global,
                    onSelectGlobal = { isGlobal ->
                        global = isGlobal
                        actions.onUpdateProviderOption(
                            id,
                            "region",
                            if (isGlobal) CodeBuddyProvider.REGION_GLOBAL else CodeBuddyProvider.REGION_CN,
                        )
                    },
                )
            }
            SectionLabel(if (global) "登录 · 国际版 workbuddy.ai" else "登录 · 国内版 copilot.tencent.com")
            Button(
                onClick = { actions.onDeviceLogin(id, region) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (global) "在浏览器中打开国际版登录页" else "在浏览器中打开国内版登录页")
            }
            // 不用跳出 App：WebView 里完成授权，关闭后由后台轮询取结果。
            OutlineActionButton(text = "在应用内登录（不用跳出 App）") {
                actions.onInAppLogin(id, region)
            }
            OutlineActionButton(
                text = if (expanded) "收起粘贴导入" else "粘贴凭证 JSON 导入",
            ) { expanded = !expanded }
            if (expanded) {
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                    label = { Text("凭证 JSON") },
                )
                Button(
                    onClick = {
                        actions.onImport(id, raw.trim())
                        raw = ""
                    },
                    enabled = raw.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("导入")
                }
            }
        }
        if (accounts.isNotEmpty()) {
            AccountRegionSection(global, accounts, actions)
        }
    }

    @Composable
    private fun AccountRegionSection(global: Boolean, accounts: List<AccountStatus>, actions: ProviderUiActions) {
        val current = if (global) CodeBuddyProvider.REGION_GLOBAL else CodeBuddyProvider.REGION_CN
        // 读快照里的 region（随账号状态下发），不要实时查 engine：
        // 两个数据源刷新不同步，会出现「账号时有时无」。
        val scoped = remember(accounts, current) { accounts.filter { it.region == current } }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (scoped.isEmpty()) {
                SectionCard(enterIndex = 1) {
                    SectionLabel("账号")
                    Text(
                        text = if (global) "还没有国际版账号，先登录" else "还没有国内版账号，先登录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return
            }
            SectionCard(enterIndex = 1) {
                SectionLabel("账号 · ${scoped.size} 个")
                PagedAccountList(scoped, keyOf = { it.uid }) { _, account ->
                    AccountBlock(account, actions)
                }
                OutlineActionButton("刷新额度") { actions.onRefreshCredits(id, null) }
            }
        }
    }

    @Composable
    private fun AccountBlock(status: AccountStatus, actions: ProviderUiActions) {
        AccountCardShell(
            account = status,
            providerId = id,
            actions = actions,
            extra = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("额度", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        text = if (status.creditsKnown) status.credits.toString() else "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (status.detail.isNotEmpty()) {
                    Text(
                        text = status.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 国际版（workbuddy.ai）没有签到制度与成长中心，只给国内账号渲染这两个入口
                if (status.region != CodeBuddyProvider.REGION_GLOBAL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip("签到", false) {
                            actions.onAction(id, status.uid, ACTION_CHECKIN, JsonObject())
                        }
                        ChoiceChip("一键任务", false) {
                            // 先跑一次领奖，再进明细页看结果；任务多时不把列表塞在卡里
                            actions.onAction(id, status.uid, ACTION_TASKS, JsonObject())
                            actions.onOpenSubPage(SubPage.ProviderTaskCenter(id, status.uid))
                        }
                    }
                }
            },
        )
    }

}
