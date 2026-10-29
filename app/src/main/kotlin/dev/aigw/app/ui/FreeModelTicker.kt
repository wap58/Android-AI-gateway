package dev.aigw.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 今日免费模型条。
 *
 * 用**上下翻页**而不是左右跑马灯：手机横向空间窄，左右滚动永远只能看到半截名字，
 * 纵向每次完整展示一个模型，信息更完整也更好读。
 *
 * 左侧「今日免费」固定，右侧每 3 秒向上翻一条，循环播放。整条可点击进完整清单。
 */
@Composable
fun FreeModelTicker(
    items: List<String>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    if (items.isEmpty()) return

    val borderColor = Color(0xFFE5484D)
    val bgColor = borderColor.copy(alpha = 0.12f)
    val textColor = Color(0xFF2BB673)

    var index by remember(items) { mutableIntStateOf(0) }
    // 只有一条时不用翻页，省掉无意义的动画
    LaunchedEffect(items) {
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            delay(3000)
            index = (index + 1) % items.size
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "今日免费",
            style = MaterialTheme.typography.labelLarge,
            fontSize = 13.sp,
            color = borderColor,
        )
        Box(
            modifier = Modifier
                .padding(start = 10.dp)
                .width(1.dp)
                .height(14.dp)
                .background(borderColor.copy(alpha = 0.35f)),
        )
        // 翻页区：固定单行高度，内容垂直滑入滑出
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
                .height(20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    // 新的从下方进、旧的往上出（“向上翻”的观感）
                    (slideInVertically(tween(320)) { it } + fadeIn(tween(320)))
                        .togetherWith(slideOutVertically(tween(320)) { -it } + fadeOut(tween(320)))
                },
                label = "freeTicker",
            ) { i ->
                Text(
                    text = items[i % items.size],
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 13.sp,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (items.size > 1) {
            Text(
                text = "${(index % items.size) + 1}/${items.size}",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = textColor.copy(alpha = 0.65f),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * 判定某个模型「今日免费」（倍率 0）。
 *
 * 倍率可能出现在三处，按可信度依次取：
 *   1. `extra["rate"]` / `extra["multiplier"]` —— 供应商算好的结构化字段（WorkBuddy/Trae/灵犀）
 *   2. 模型名含「限时免费」/「免费」—— Loomy 这类直接写在标题里
 *   3. 模型名里的 `(x0.0)` —— 必须有括号或 x 前缀，否则 `Spark-X2.6` 会被当成 2.6 倍
 */
fun isFreeToday(m: dev.aigw.core.provider.RoutedModel): Boolean {
    for (key in listOf("rate", "multiplier")) {
        val txt = m.model.extra[key].orEmpty().trim()
        if (txt.isEmpty()) continue
        val num = Regex("""(\d+(?:\.\d+)?)""").find(txt)?.groupValues?.get(1) ?: continue
        return num.toDoubleOrNull() == 0.0
    }
    val name = m.model.name
    if (name.contains("免费") || name.contains("free", ignoreCase = true)) return true
    Regex("""\((?:x|×)\s*(\d+(?:\.\d+)?)\)""", RegexOption.IGNORE_CASE)
        .find(name)?.let { return it.groupValues[1].toDoubleOrNull() == 0.0 }
    Regex("""(?:x|×)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        .find(name)?.let { return it.groupValues[1].toDoubleOrNull() == 0.0 }
    return false
}
