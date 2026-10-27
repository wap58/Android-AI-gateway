package dev.aigw.app.ui.providers.agnes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus

/**
 * Agnes AI 的界面。
 *
 * 两个独立站点（国内 / 国际），各用各的 API Key，所以按区域分区展示：
 * 顶部切区域 → 只显示该区域的账号 + 该区域的注册引导与输入框。
 *
 * 上游是标准 OpenAI 兼容接口，鉴权就是一个 Key，没有登录流程。
 */
object AgnesUi : ProviderUi {

    override val id: String = "agnes"

    private const val REGION_CN = "cn"
    private const val REGION_GLOBAL = "global"

    override fun description(): String = "免费对话模型 agnes-3.0-flash，国内/国际两站，每分钟限 10 次"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Extension,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var region by remember { mutableStateOf(actions.providerOptionOf(id, "region", REGION_CN)) }
        var key by remember { mutableStateOf("") }

        val isGlobal = region == REGION_GLOBAL
        val platform = if (isGlobal) "https://platform.agnes-ai.com/" else "https://platform.agnes-ai.cn/"
        val endpoint = if (isGlobal) "https://apihub.agnes-ai.com/v1" else "https://api.agnes-ai.cn/v1"

        SectionCard {
            SectionLabel("服务区域")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChoiceChip("国内", !isGlobal) {
                    region = REGION_CN
                    actions.onUpdateProviderOption(id, "region", REGION_CN)
                    actions.onRegionPicked(REGION_CN)
                }
                ChoiceChip("国际", isGlobal) {
                    region = REGION_GLOBAL
                    actions.onUpdateProviderOption(id, "region", REGION_GLOBAL)
                    actions.onRegionPicked(REGION_GLOBAL)
                }
            }
            Text(
                text = "接口：$endpoint",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "两站的 API Key 不通用，切到哪个区域就填哪个站点申请的 Key。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard {
            SectionLabel(if (isGlobal) "国际版 · 申请与填入" else "国内版 · 申请与填入")

            // 直接用浏览器打开对应站点，注册和创建 Key 都在那边完成
            OutlinedButton(
                onClick = { actions.onOpenUrl(platform) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isGlobal) "打开国际站申请 Key" else "打开国内站申请 Key")
            }

            if (!isGlobal) {
                Text(
                    text = "国内站需要实名验证。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API Key") },
                singleLine = false,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    if (key.isBlank()) {
                        actions.onNotice("请先填入 API Key")
                        return@Button
                    }
                    val json = """{"apiKey":"${key.trim()}","region":"$region"}"""
                    actions.onImport(id, json)
                    key = ""
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("添加${if (isGlobal) "国际版" else "国内版"}账号")
            }

        }
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        SectionCard {
            SectionLabel("说明")
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "当前区域：${if (status.region == REGION_GLOBAL) "国际版" else "国内版"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "免费额度无法查询余额，只限制每分钟 10 次请求；触发限流时等一会儿再试即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "该供应商没有签到制度。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
