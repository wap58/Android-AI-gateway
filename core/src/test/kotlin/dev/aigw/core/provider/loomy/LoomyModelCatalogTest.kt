package dev.aigw.core.provider.loomy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 模型目录在「没有账号」时的行为。
 *
 * 登录前本就不该有模型，这是正常状态：返回空列表即可，不能当成失败报错
 * （否则模型页会顶一条红色「没有可用账号，无法拉取模型列表」吓人）。
 */
class LoomyModelCatalogTest {

    @Test
    fun `没有账号时返回空列表且不算失败`() {
        val catalog = LoomyModelCatalog()
        val models = catalog.models(null)
        assertTrue(models.isEmpty(), "无账号不该列出模型，实际=${models.map { it.id }}")
        assertFalse(catalog.fromFallback, "无账号是正常状态，不是回退")
        assertEquals("", catalog.lastError, "无账号不该报错")
    }

    @Test
    fun `空 session 与 null 行为一致`() {
        val catalog = LoomyModelCatalog()
        assertTrue(catalog.models("").isEmpty())
        assertFalse(catalog.fromFallback)
        assertEquals("", catalog.lastError)
    }
}