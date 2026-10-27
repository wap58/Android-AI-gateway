package dev.aigw.app.ui.providers.lingxi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.ChoiceChip
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.ACTION_CHECKIN
import com.google.gson.JsonObject

/**
 * WPS 灵犀的界面。
 *
 * 主入口是「网页登录」：App 内嵌页面让用户正常用 WPS 账号登录，
 * 登录成功后从 WebView 的 cookie 存储里读出 `wps_sid` 自动落库。
 * 手机上没有 F12，粘贴框只作为兜底（电脑上复制过来的情况）。
 */
object LingxiUi : ProviderUi {

    override val id: String = "lingxi"

    override fun description(): String = "金山办公灵犀，网页登录即可，覆盖 DeepSeek/Kimi/GLM/Qwen 等"

    /**
     * 登录页与要抓的 cookie。
     *
     * ★ 必须用 lingxi.kdocs.cn 首页，不能换成 account.wps.cn 的登录地址。
     *   实测：用 account.wps.cn 那个带 cb 参数的地址，登录完成后页面不会
     *   自动跳回 lingxi.kdocs.cn，wps_sid 种不到业务域上，一直读不到凭证。
     *   首页的登录入口藏在侧边栏底部，确实不好找，但那是唯一能正确种 cookie 的路径。
     */
    private const val LOGIN_URL = "https://lingxi.kdocs.cn/"
    private const val COOKIE_HOST = "lingxi.kdocs.cn"
    private const val COOKIE_NAME = "wps_sid"

    /** 登录后跳这里，让 SSO 在 wps.cn 域种下最新会话的 cookie。 */
    private const val SSO_URL = "https://www.wps.cn/"

    /** 用来验证 cookie 真的能用（拉模型清单）。 */
    private const val VERIFY_URL = "https://lingxi.kdocs.cn/api/aioffice/v1/sessions/plans"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        SectionCard {
            SectionLabel("登录 WPS 灵犀")
            Text(
                text = "点下面的按钮，在弹出的页面里用 WPS 账号登录。登录完成后返回本页即可自动添加账号。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 网页已登录时会直接复用当前会话，所以这里不加「清空」：
            // 用户点它就是要拿当前已登录账号的凭证。
            Button(
                onClick = {
                    actions.onCookieLogin(
                        id, LOGIN_URL, COOKIE_HOST, COOKIE_NAME, SSO_URL, VERIFY_URL,
                        accounts.isNotEmpty(),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (accounts.isEmpty()) "网页登录" else "重新获取当前账号")
            }

            if (accounts.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        // 先清空 WebView 的 cookie，否则只会读到当前这个账号
                        actions.onCookieLogin(
                            id, LOGIN_URL, COOKIE_HOST, COOKIE_NAME, SSO_URL, VERIFY_URL,
                            true,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("登录其他账号")
                }
            }
        }
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        SectionCard {
            SectionLabel("智点余额")
            Text(
                text = if (status.creditsKnown) "${status.credits}" else "—",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (status.detail.isNotEmpty()) {
                Text(
                    text = status.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("刷新额度", false) {
                    actions.onRefreshCredits(id, status.uid)
                }
                ChoiceChip("签到", false) {
                    actions.onAction(id, status.uid, ACTION_CHECKIN, JsonObject())
                }
            }

            Text(
                text = "每天签到领 100 智点，连续签到第 7 天为 200。登录态失效（401）时重新走一次网页登录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
