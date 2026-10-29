package dev.aigw.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.filled.Check

/**
 * 登录前选浏览器。
 *
 * 同一个上游的多个账号在**同一个浏览器**里登录会互相顶掉（Cookie 只有一份），
 * 每个账号换一个浏览器就能各自保留登录态，不必反复退出重登。
 * 所以这里每次登录都问一次，而不是做成一次性偏好。
 */
@Composable
fun LoginBrowserDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    val browsers = remember { BrowserLauncher.available(context) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = "浏览器选择",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "每个浏览器独立保存登录状态。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    BrowserChoice(
                        label = "系统默认",
                        packageName = null,
                        onClick = { onPick("") },
                    )
                    for (b in browsers) {
                        BrowserChoice(
                            label = b.label,
                            packageName = b.packageName,
                            onClick = { onPick(b.packageName) },
                        )
                    }
                }

                if (browsers.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "没有找到其它浏览器",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 取消按钮贴着列表收尾：TextButton 自带 48dp 最小高度，
                // 再叠 Spacer 会空出一大截，这里直接紧接列表。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp),
                    ) { Text("取消", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

/** 一行浏览器：左侧应用图标，右侧名称；选中项高亮。 */
@Composable
private fun BrowserChoice(
    label: String,
    packageName: String?,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val icon = remember(packageName) {
        packageName?.let { BrowserLauncher.iconOf(context, it) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            // 应用图标转 Bitmap 显示：Drawable 直接进 Image 会因可变尺寸报错
            Image(
                bitmap = remember(packageName, icon) { icon.toBitmap(96, 96).asImageBitmap() },
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        } else {
            // 「系统默认」没有具体应用图标：用内置的通用浏览器图标
            Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (packageName == null) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
