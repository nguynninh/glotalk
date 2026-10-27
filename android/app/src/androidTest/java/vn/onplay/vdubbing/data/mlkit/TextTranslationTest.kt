package vn.onplay.vdubbing.data.mlkit

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.onplay.vdubbing.domain.model.Language

class TextTranslationTest {
    @Test fun translatesTypedTextWithDownloadedModel() = runBlocking {
        val translator = TranslationRepositoryImpl()
        try {
            val result = withTimeout(120_000) {
                translator.translate(listOf("Hello"), Language.ENGLISH, Language.VIETNAMESE).single()
            }
            assertTrue("must produce a Vietnamese translation: $result", result.isNotBlank() && !result.equals("Hello", ignoreCase = true))
            val chineseResult = withTimeout(120_000) {
                translator.translate(listOf("我"), Language.CHINESE, Language.VIETNAMESE).single()
            }
            assertTrue("must translate the Chinese character: $chineseResult", chineseResult.isNotBlank() && chineseResult != "我")
        } finally {
            translator.close()
        }
    }
}
