package vn.onplay.vdubbing.presentation.feature.texttranslation

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import vn.onplay.vdubbing.R
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationHistoryEntry
import vn.onplay.vdubbing.domain.repository.TranslationRepository
import vn.onplay.vdubbing.domain.repository.TranslationHistoryRepository

@OptIn(ExperimentalCoroutinesApi::class)
class TextTranslationViewModelTest {
    private val history = History()

    @Test fun cancelledLateResultCannotReplaceNewTranslationOrEnterHistory() = runTest {
        val oldResult = CompletableDeferred<String>()
        val model = viewModel({ text, _, _ ->
            if (text == "old") withContext(NonCancellable) { oldResult.await() } else "new result"
        })
        val old = launch { model.translate("old", false, Language.ENGLISH, Language.VIETNAMESE) {} }
        advanceTimeBy(451)
        old.cancel()
        val current = launch { model.translate("new", false, Language.ENGLISH, Language.VIETNAMESE) {} }
        advanceTimeBy(451)
        oldResult.complete("stale result")
        old.join()
        current.join()
        assertEquals("new result", model.state.value.result)
        assertEquals(listOf("new"), history.saved)
    }

    @Test fun waitsForTypingAndSkipsComposingInput() = runTest {
        var calls = 0
        val model = viewModel({ _, _, _ -> calls++; "Xin chào" })
        model.translate("Hello", true, Language.ENGLISH, Language.VIETNAMESE) {}
        assertEquals(0, calls)
        val work = launch { model.translate("Hello", false, Language.ENGLISH, Language.VIETNAMESE) {} }
        advanceTimeBy(449)
        assertEquals(0, calls)
        work.join()
        assertEquals(1, calls)
        assertEquals("Xin chào", model.state.value.result)
        model.translate("", false, Language.ENGLISH, Language.VIETNAMESE) {}
        assertNull(model.state.value.result)
    }

    @Test fun translationFailureIsRetryableAndSaveFailureKeepsResult() = runTest {
        val model = viewModel({ text, _, _ ->
            check(text != "fail")
            "result"
        })
        model.translate("fail", false, Language.ENGLISH, Language.VIETNAMESE) {}
        assertEquals(R.string.text_translation_error, model.state.value.error)
        history.failSave = true
        var saveFailure = false
        model.translate("retry", false, Language.ENGLISH, Language.VIETNAMESE) { saveFailure = true }
        assertTrue(saveFailure)
        assertEquals("result", model.state.value.result)
        assertNull(model.state.value.error)
    }

    private fun viewModel(translateText: suspend (String, Language, Language) -> String) =
        TextTranslationViewModel(object : TranslationRepository {
            override suspend fun detect(text: String) = Language.AUTO
            override suspend fun translate(lines: List<String>, source: Language, target: Language) =
                lines.map { translateText(it, source, target) }
        }, history)

    private class History : TranslationHistoryRepository {
        val saved = mutableListOf<String>()
        var failSave = false
        override suspend fun entries() = emptyList<TranslationHistoryEntry>()
        override suspend fun add(original: String, translated: String, source: Language, target: Language) {
            check(!failSave)
            saved += original
        }
        override suspend fun setBookmarked(id: Long, bookmarked: Boolean) = Unit
        override suspend fun delete(ids: Set<Long>) = Unit
    }
}
