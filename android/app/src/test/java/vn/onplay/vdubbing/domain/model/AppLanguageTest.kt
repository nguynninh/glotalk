package vn.onplay.vdubbing.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {

    @Test
    fun `no tag means follow the system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
    }

    @Test
    fun `plain tags map to their choice`() {
        assertEquals(AppLanguage.VIETNAMESE, AppLanguage.fromTag("vi"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromTag("ja"))
        assertEquals(AppLanguage.KOREAN, AppLanguage.fromTag("ko"))
    }

    @Test
    fun `every chinese variant maps to simplified chinese`() {
        listOf("zh", "zh-CN", "zh_Hans_CN", "zh-rCN", "zh-TW").forEach { tag ->
            assertEquals(tag, AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromTag(tag))
        }
    }

    @Test
    fun `region and case are ignored`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-GB"))
        assertEquals(AppLanguage.VIETNAMESE, AppLanguage.fromTag("VI"))
    }

    @Test
    fun `an unsupported language falls back to the system setting`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("fr"))
    }
}
