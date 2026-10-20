package dev.aigw.core.security

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.regex.Pattern

/**
 * 出网取证记录：记录脱敏拦截与穿透处理的快照。
 */
data class OutboundEvidence(
    /** 单调自增序号：LazyColumn 的 key 用它保证唯一。 */
    val seq: Long,
    val atMillis: Long,
    val model: String,
    val matchedTerms: List<String>,
    val originalSnippet: String,
    val sanitizedSnippet: String,
)

/**
 * 反审核脱敏器：
 * 采用“零宽字符（Zero-width Space \u200B）注入”技术穿透敏感词过滤。
 * 作用范围严格限定在 `system` / `developer` 消息，完全不触碰用户 `user` 输入。
 */
class RequestSanitizer(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** 零宽空格，肉眼不可见、LLM Tokenizer 还原理解，但能彻底打断关键词匹配。 */
    val ZERO_WIDTH_SPACE = "\u200B"

    /** 内置高频误拦敏感词列表（涵盖网络安全、渗透测试、Agent合规声明常见词）。 */
    val DEFAULT_SENSITIVE_WORDS = listOf(
        "DoS",
        "DDoS",
        "exploit",
        "exploits",
        "exploiting",
        "vulnerability",
        "vulnerabilities",
        "payload",
        "payloads",
        "shellcode",
        "backdoor",
        "rootkit",
        "trojan",
        "jailbreak",
        "bypass",
        "injection",
        "SQLi",
        "XSS",
        "CSRF",
        "privilege escalation",
        "brute force",
        "reverse shell",
        "malware",
        "ransomware",
    )

    /**
     * 允许脱敏的最大 body 字符数。
     *
     * 超过就不改（原样放行）：脱敏需要 parse 成 JSON 树再序列化一遍，
     * 期间同时持有原文、JSON 树、新文本三份，大 body 上峰值可能翻数倍。
     */
    private val MAX_SANITIZE_CHARS = 512 * 1024

    private val evidenceHistory = ConcurrentLinkedDeque<OutboundEvidence>()
    private val maxEvidenceCount = 50

    /** 取证记录序号：单调递增，供 LazyColumn 稳定 key 使用。 */
    private val evidenceSeq = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile
    private var activeWordList: List<String> = DEFAULT_SENSITIVE_WORDS

    @Volatile
    private var compiledRegexes: List<Pair<String, Pattern>> = compilePatterns(DEFAULT_SENSITIVE_WORDS)

    /** 重新加载处理链规则与词表。 */
    fun reloadPipeline(extraWords: List<String> = emptyList()) {
        val combined = (DEFAULT_SENSITIVE_WORDS + extraWords)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        activeWordList = combined
        compiledRegexes = compilePatterns(combined)
    }

    fun currentRulesCount(): Int = activeWordList.size

    fun evidenceList(): List<OutboundEvidence> = evidenceHistory.toList()

    fun clearEvidence() {
        evidenceHistory.clear()
    }

    /**
     * 针对 OpenAI 格式请求体进行脱敏处理：
     * 仅修改 `messages` 数组中 `role == "system"` 或 `role == "developer"` 的消息。
     * 若未开启脱敏，或无匹配，返回原 body。
     */
    fun sanitizeOpenAiBody(body: String, model: String, enabled: Boolean): String {
        if (!enabled) return body
        // 超大请求体直接放行：脱敏要重建整棵 JSON 树，内存峰值约为「原 body + 新 body」，
        // 大 body 上做这件事风险大于收益（崩溃比被上游误拦严重得多）。
        if (body.length > MAX_SANITIZE_CHARS) return body
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return body
        val messages = obj.arrayOrNull("messages") ?: return body

        var modified = false
        val allMatchedTerms = mutableListOf<String>()
        var originalSample = ""
        var sanitizedSample = ""

        val newMessages = JsonArray()
        for (element in messages) {
            val msg = element.asObjectOrNull()
            if (msg == null) {
                newMessages.add(element)
                continue
            }
            val role = msg.stringOrNull("role").orEmpty()
            if (role == "system" || role == "developer") {
                val contentElem = msg.get("content")
                if (contentElem != null && contentElem.isJsonPrimitive) {
                    val originalText = contentElem.asString
                    val (resultText, matches) = sanitizeText(originalText)
                    if (matches.isNotEmpty()) {
                        modified = true
                        allMatchedTerms.addAll(matches)
                        if (originalSample.isEmpty()) {
                            originalSample = originalText.take(300)
                            sanitizedSample = resultText.take(300)
                        }
                        val copyMsg = JsonObject()
                        for ((k, v) in msg.entrySet()) copyMsg.add(k, v)
                        copyMsg.addProperty("content", resultText)
                        newMessages.add(copyMsg)
                        continue
                    }
                }
            }
            newMessages.add(element)
        }

        if (!modified) return body

        obj.add("messages", newMessages)
        val finalBody = obj.toString()

        recordEvidence(model, allMatchedTerms.distinct(), originalSample, sanitizedSample)
        return finalBody
    }

    /**
     * 将敏感词内部每两个相邻字符之间注入零宽空格。
     * 例：DoS -> D\u200Bo\u200BS
     */
    internal fun obfuscateWord(word: String): String {
        if (word.length <= 1) return word
        // 只在第一个字符后插一个零宽空格，就足以打断关键词匹配。
        //
        // 早先的写法是「每个字符之间都插」，看着更彻底，代价却极大：
        // 零宽空格在 UTF-8 里占 3 字节，一个 15 字符的 vulnerability 会从 15 字节涨到 57 字节（3.8 倍）。
        // 合规声明类 system prompt 敏感词密集，整个请求体能膨胀 2.5 倍以上，
        // 在小内存机型上足以把进程压垮（表现为对话时 App 直接崩溃）。
        return word.substring(0, 1) + ZERO_WIDTH_SPACE + word.substring(1)
    }

    /**
     * 对一段字符串执行敏感词检测与零宽字符注入替换。
     */
    internal fun sanitizeText(input: String): Pair<String, List<String>> {
        var current = input
        val matched = mutableListOf<String>()

        for ((word, pattern) in compiledRegexes) {
            val matcher = pattern.matcher(current)
            if (matcher.find()) {
                matched.add(word)
                current = replaceAllSafe(matcher) { obfuscateWord(it) }
            }
        }
        return current to matched
    }

    /**
     * 用 [Matcher.appendReplacement] 完成「按函数替换」。
     *
     * ## 为什么不用 matcher.replaceAll { }
     *
     * Kotlin 里 `matcher.replaceAll { ... }` 的尾随 lambda 会被编译成
     * `Matcher.replaceAll(Function)` —— 这是 **Java 9 才引入**、Android 上
     * **API 34（Android 14）才有** 的重载。在 Android 13 及以下调用会抛：
     *
     * ```
     * NoSuchMethodError: No virtual method
     *   replaceAll(Ljava/util/function/Function;)Ljava/lang/String;
     *   in class Ljava/util/regex/Matcher;
     * ```
     *
     * 而 appendReplacement/appendTail 从 Java 1.4 就有，全平台可用。
     * 这个坑很隐蔽：编译期完全正常，只有低版本设备运行时才炸。
     */
    private inline fun replaceAllSafe(matcher: java.util.regex.Matcher, transform: (String) -> String): String {
        val sb = StringBuilder()
        while (matcher.find()) {
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(transform(matcher.group())))
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    private fun recordEvidence(
        model: String,
        terms: List<String>,
        originalSnippet: String,
        sanitizedSnippet: String,
    ) {
        val evidence = OutboundEvidence(
            seq = evidenceSeq.getAndIncrement(),
            atMillis = nowMillis(),
            model = model,
            matchedTerms = terms,
            originalSnippet = originalSnippet,
            sanitizedSnippet = sanitizedSnippet,
        )
        evidenceHistory.addFirst(evidence)
        while (evidenceHistory.size > maxEvidenceCount) {
            evidenceHistory.removeLast()
        }
    }

    private fun compilePatterns(words: List<String>): List<Pair<String, Pattern>> {
        // 词长降序排列，优先匹配较长词组（如 brute force 优先于 force）
        return words.sortedByDescending { it.length }.map { word ->
            val regex = if (word.all { it.isLetterOrDigit() }) {
                "\\b" + Pattern.quote(word) + "\\b"
            } else {
                Pattern.quote(word)
            }
            word to Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
        }
    }
}
