package dev.aigw.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString

/** 接入设置：客户端要用的地址、端口与网关鉴权 Key。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApiSettingsScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val settings = state.settings
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var bodyLimit by remember(settings.maxRequestBodyMb) {
        mutableStateOf(settings.maxRequestBodyMb.toString())
    }

    PageScaffold(
        title = "接入设置",
        subtitle = "端口与 API Key",
        leading = { BackButton(onBack) },
    ) {
        SectionCard(enterIndex = 0) {
            SectionLabel("接入地址")
            AddressTile(state.localUrl.ifEmpty { "http://127.0.0.1:${settings.port}/v1" }) {
                clipboard.setText(AnnotatedString(state.localUrl))
                Toast.makeText(context, "已复制本机地址", Toast.LENGTH_SHORT).show()
            }
            state.lanUrls.forEach { url ->
                AddressTile(url) {
                    clipboard.setText(AnnotatedString(url))
                    Toast.makeText(context, "已复制局域网地址", Toast.LENGTH_SHORT).show()
                }
            }
        }

        SectionCard(enterIndex = 1) {
            SectionLabel("端口")
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("监听端口") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("同时监听局域网", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.exposeLan,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(exposeLan = it)) },
                )
            }
        }

        SectionCard(enterIndex = 2) {
            SectionLabel("请求体上限")
            Text(
                text = "长对话（上千轮）会把完整历史塞进请求体，容易撞上限并收到 413。" +
                    "按实际场景调大即可；解析时内存峰值约为上限的 2~3 倍。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = bodyLimit,
                onValueChange = { bodyLimit = it.filter(Char::isDigit).take(4) },
                label = { Text("上限（MB，${dev.aigw.core.gateway.MIN_MAX_BODY_MB}~${dev.aigw.core.gateway.MAX_MAX_BODY_MB}）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ChoiceChip(
                text = "保存",
                selected = false,
                onClick = {
                    val mb = bodyLimit.toIntOrNull()
                    if (mb == null) {
                        Toast.makeText(context, "请输入数字", Toast.LENGTH_SHORT).show()
                    } else {
                        val fixed = mb.coerceIn(
                            dev.aigw.core.gateway.MIN_MAX_BODY_MB,
                            dev.aigw.core.gateway.MAX_MAX_BODY_MB,
                        )
                        bodyLimit = fixed.toString()
                        viewModel.updateSettings(settings.copy(maxRequestBodyMb = fixed))
                        Toast.makeText(context, "已设为 ${fixed}MB", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            // 快捷档位：常用值一键切换，省得手输
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(8, 16, 32, 64).forEach { mb ->
                    ChoiceChip(
                        text = "${mb}MB",
                        selected = settings.maxRequestBodyMb == mb,
                        onClick = {
                            bodyLimit = mb.toString()
                            viewModel.updateSettings(settings.copy(maxRequestBodyMb = mb))
                        },
                    )
                }
            }
        }

        SectionCard(enterIndex = 3) {
            SectionLabel("API Key")
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("网关鉴权 Key（留空表示不校验）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("无 Key 调用", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.allowNoKey,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(allowNoKey = it)) },
                )
            }
        }

        SectionCard(enterIndex = 4) {
            SectionLabel("默认供应商")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.providers.forEach { provider ->
                    ChoiceChip(
                        text = provider.displayName,
                        selected = (settings.defaultProvider == provider.id),
                        onClick = { viewModel.updateSettings(settings.copy(defaultProvider = provider.id)) },
                    )
                }
            }
        }

        SectionCard(enterIndex = 5) {
            OutlineActionButton("保存") {
                val parsed = port.toIntOrNull()
                if (parsed == null || parsed !in 1024..65535) {
                    viewModel.notice("端口需要在 1024~65535 之间")
                } else {
                    viewModel.updateSettings(settings.copy(port = parsed, apiKey = apiKey.trim()))
                    viewModel.notice("已保存；端口改动需重启服务生效")
                }
            }
        }
    }
}
