package dev.aigw.app.ui.providers.opencode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus

/**
 * OpenCode 免费通道的界面。
 *
 * 它**没有登录流程**：上游凭据是公开哨兵 `Bearer public`，任何人直接可用。
 * 所以登录入口只显示一段说明，不提供任何输入框——避免让人以为需要填 key。
 */
object OpenCodeUi : ProviderUi {

    override val id: String = "opencode"

    override fun description(): String = "免登录免费模型，无需账号即可使用"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        SectionCard {
            SectionLabel("无需添加账号")
            Text(
                text = "OpenCode 免费通道使用公开凭据，已经自动就绪，不需要登录或填写密钥。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "按模型名是否带「free」可判断是否为免费模型；免费层有限流，" +
                    "遇到额度用尽时稍后重试即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        SectionCard {
            SectionLabel("说明")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "免费额度由上游按 IP 与请求频率限制，没有可查询的余额。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "若出现「免费额度已用尽」，说明触发了上游限流，换账号无效，等几分钟再试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
