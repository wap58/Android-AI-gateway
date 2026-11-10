package dev.aigw.app.ui.providers.autoclaw

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import dev.aigw.app.ui.AccountDeleteChipRow
import dev.aigw.app.ui.AccountEnableSwitch
import dev.aigw.app.ui.AccountTitleRow
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.PagedAccountList
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.ACTION_CHECKIN_DAILY
import dev.aigw.core.provider.ACTION_TASKS
import kotlinx.coroutines.delay

/**
 * AutoClaw（智谱 AutoGLM 客户端）界面。
 *
 * 登录只有一条路：**手机号 + 短信验证码**（国内站）。
 * 国际站没有手机客户端、且 OAuth 带阿里云验证码过不去，本产品不做国际版。
 */
class AutoClawUi(override val id: String) : ProviderUi {

    /** 验证码重发间隔（秒）：上游有频率限制，连点只会被拒。 */
    private val resendSeconds = 60

    /** 账号按本组件自己渲染（要放签到/额度按钮，通用卡放不下）。 */
    override val managesOwnAccounts: Boolean = true

    override fun description(): String = "智谱 AutoGLM 客户端，手机号登录"

    @Composable
    override fun Icon(modifier: Modifier) {
        MaterialIcon(
            Icons.Filled.Terminal,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        SmsSection(actions)
        PagedAccountList(accounts, keyOf = { it.uid }) { _, account ->
            AccountDetailCard(account, actions)
        }
    }

    @Composable
    private fun SmsSection(actions: ProviderUiActions) {
        var ccode by rememberSaveable { mutableStateOf("86") }
        var phone by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var countdown by remember { mutableStateOf(0) }

        LaunchedEffect(countdown) {
            if (countdown > 0) {
                delay(1_000)
                countdown -= 1
            }
        }

        SectionCard(enterIndex = 0) {
            SectionLabel("登录")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = ccode,
                    onValueChange = { ccode = it.filter(Char::isDigit).take(4) },
                    modifier = Modifier.width(88.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("国家码") },
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter(Char::isDigit).take(15) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    label = { Text("手机号") },
                )
            }
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(8) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text("验证码") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        // 先开倒计时让按钮立刻有反馈；发送失败再放开
                        countdown = resendSeconds
                        actions.onSmsLogin(id, phone.trim(), "", ccode.trim()) { error ->
                            if (error.isNotEmpty()) countdown = 0
                        }
                    },
                    enabled = phone.isNotBlank() && countdown == 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (countdown > 0) "${countdown} 秒后重发" else "发送验证码")
                }
                Button(
                    onClick = { actions.onSmsLogin(id, phone.trim(), code.trim(), ccode.trim()) { } },
                    enabled = phone.isNotBlank() && code.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("登录") }
            }
        }
    }

    /** 账号卡：名称、额度、签到、删除。 */
    @Composable
    private fun AccountDetailCard(account: AccountStatus, actions: ProviderUiActions) {
        SectionCard {
            AccountTitleRow(
                account = account,
                trailing = {
                    AccountEnableSwitch(
                        account = account,
                        providerId = account.providerId,
                        actions = actions,
                    )
                },
            )

            Text(
                text = "积分 ${if (account.creditsKnown) account.credits else "—"}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (account.detail.isNotEmpty()) {
                Text(
                    text = account.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 签到 / 新手任务 / 刷新 / 删除：四份挤一排会窄到换行，
            // 拆成两排——任务类在上（用户的主动目标），维护类在下。
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlineActionButton("每日签到", Modifier.weight(1f)) {
                    actions.onAction(
                        account.providerId,
                        account.uid,
                        ACTION_CHECKIN_DAILY,
                        JsonObject(),
                    )
                }
                OutlineActionButton("新手任务", Modifier.weight(1f)) {
                    actions.onAction(
                        account.providerId,
                        account.uid,
                        ACTION_TASKS,
                        JsonObject(),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlineActionButton("刷新", Modifier.weight(1f)) {
                    actions.onRefreshCredits(account.providerId, account.uid)
                }
                OutlineActionButton("删除", Modifier.weight(1f)) {
                    actions.onRemoveAccount(account.providerId, account.uid)
                }
            }
        }
    }
}
