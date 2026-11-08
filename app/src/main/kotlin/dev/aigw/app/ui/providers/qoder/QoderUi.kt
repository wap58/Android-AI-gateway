package dev.aigw.app.ui.providers.qoder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.ChoiceChip
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus

/**
 * Qoder 界面：国内 + 国际双区，设备授权登录。
 *
 * ## 为什么账号列表要自己管
 *
 * qoder.com.cn 与 qoder.sh 是**两套独立的账号体系**：同一个邮箱在两边是不同的
 * 账号，额度也分开算。通用账号卡会把两个区的账号混在一起显示，用户看到
 * 「国内登录的号出现在国际列表里」——不是数据错，是没按区域分组。
 * 这里按当前选中的区域过滤，两个区的账号各归各的。
 */
object QoderUi : ProviderUi {

    override val id: String = "qoder"

    /** 账号由本组件按区域分组渲染，页面骨架不再重复渲染通用「账号」卡。 */
    override val managesOwnAccounts: Boolean = true

    override fun description(): String = "Qoder（国内 qoder.com.cn / 国际 qoder.sh）设备授权登录"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Code,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var global by remember {
            mutableStateOf(actions.providerOptionOf(id, "region", "cn") == "intl")
        }
        var expanded by remember { mutableStateOf(false) }
        var raw by remember { mutableStateOf("") }
        val region = if (global) "intl" else "cn"

        // 按区域分组：读快照里的 region（随账号状态下发），不实时查引擎——
        // 两个数据源刷新不同步，会出现「账号时有时无」。
        //
        // 老账号（本功能上线前添加）凭证里没有 realm，region 为空串。
        // 这类账号无处归属，会从两边列表同时消失；把它们归到当前选中的区域，
        // 至少保证看得见、能删能重登，不会凭空不见。
        val scoped = remember(accounts, region) {
            accounts.filter { it.region == region || it.region.isEmpty() }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(enterIndex = 0) {
                SectionLabel("区域")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(
                        text = "国内 qoder.com.cn",
                        selected = !global,
                        onClick = {
                            global = false
                            actions.onUpdateProviderOption(id, "region", "cn")
                        },
                    )
                    ChoiceChip(
                        text = "国际 qoder.sh",
                        selected = global,
                        onClick = {
                            global = true
                            actions.onUpdateProviderOption(id, "region", "intl")
                        },
                    )
                }
                SectionLabel(if (global) "登录 · 国际版 qoder.sh" else "登录 · 国内版 qoder.com.cn")
                // 设备授权是自定义 scheme 回调（qoder-work-cn:// / qoder://），
                // 只能由 App 内 WebView 接住，系统浏览器接不了。
                Button(
                    onClick = { actions.onInAppLogin(id, region) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (global) "打开国际版授权页" else "打开国内版授权页")
                }
                // 系统浏览器入口：部分设备上内置 WebView 渲染异常（登录页空白），
                // 或者用户更习惯用自己的浏览器，留一条旁路。
                // 设备授权的回调是自定义 scheme，浏览器接不住，所以拿到授权码后
                // 仍需回到 App 完成换取；这条路径适合「浏览器里能走完授权页」的场景。
                OutlineActionButton(text = "用浏览器打开授权页") {
                    actions.onWebLogin(id, region)
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
                        label = { Text("凭证 JSON 或 access token") },
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

            // 当前区域的账号
            SectionCard(enterIndex = 1) {
                SectionLabel(if (global) "国际版账号" else "国内版账号")
                if (scoped.isEmpty()) {
                    Text(
                        text = if (global) "还没有国际版账号，先在上面登录" else "还没有国内版账号，先在上面登录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    for (account in scoped) {
                        AccountRow(account, actions)
                    }
                }
            }
        }
    }

    @Composable
    private fun AccountRow(status: AccountStatus, actions: ProviderUiActions) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = status.nickname.ifEmpty { status.uid.take(16) },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = when {
                            status.disabled -> "需要重新登录"
                            !status.enabled -> "已停用"
                            else -> "可用"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = status.enabled && !status.disabled,
                    onCheckedChange = { actions.onToggleAccount(status.providerId, status.uid, it) },
                )
            }

            // 积分：creditsKnown=false 时显示「—」，不能显示 0（会被误读成余额为零）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "积分",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = if (status.creditsKnown) status.credits.toString() else "—",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (status.detail.isNotEmpty()) {
                Text(
                    text = status.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 三个动作挤成一排：纵向堆叠会把「签到」顶出屏幕可见范围，
            // 用户以为没有签到入口。
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlineActionButton(
                    text = "刷新",
                    modifier = Modifier.weight(1f),
                ) {
                    actions.onRefreshCredits(status.providerId, status.uid)
                }
                // 签到：Qoder 每日 10:00 起算，一个周期只能领一次（每日 100 积分）
                OutlineActionButton(
                    text = "签到",
                    modifier = Modifier.weight(1f),
                ) {
                    actions.onAction(
                        status.providerId,
                        status.uid,
                        dev.aigw.core.provider.ACTION_CHECKIN,
                        com.google.gson.JsonObject(),
                    )
                }
                OutlineActionButton(
                    text = "删除",
                    modifier = Modifier.weight(1f),
                ) {
                    actions.onRemoveAccount(status.providerId, status.uid)
                }
            }
        }
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("积分", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (status.creditsKnown) status.credits.toString() else "—",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "每日 10:00 起算，一个周期只能领一次",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
