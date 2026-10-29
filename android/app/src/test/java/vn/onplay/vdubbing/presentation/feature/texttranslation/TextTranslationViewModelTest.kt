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
import vn.onplay.vdubbing.domain.time.Countdown

@OptIn(ExperimentalCoroutinesApi::class)
class TextTranslationViewModelTest {
    private val history = History()
    private var countdown = Countdown { }

    @Test fun typingAndLanguageChangesReplaceOneEntryUntilInputIsCleared() = runTest {
        val model = viewModel { text, _, _ -> "translated $text" }
        model.translate("xin", false, Language.VIETNAMESE, Language.ENGLISH) {}
        model.translate("xin chào", false, Language.VIETNAMESE, Language.ENGLISH) {}
        model.translate("xin chào bạn", false, Language.VIETNAMESE, Language.CHINESE) {}
        assertEquals(listOf("xin chào bạn"), history.saved)
        model.translate("", false, Language.VIETNAMESE, Language.CHINESE) {}
        model.translate("cảm ơn", false, Language.VIETNAMESE, Language.CHINESE) {}
        assertEquals(listOf("xin chào bạn", "cảm ơn"), history.saved)
    }

    @Test fun cancellingDuringSaveStillTracksTheRowForTheNextTranslation() = runTest {
        val saveStarted = CompletableDeferred<Unit>()
        val finishSave = CompletableDeferred<Unit>()
        history.beforeSave = {
            if (it == "old") {
                saveStarted.complete(Unit)
                finishSave.await()
            }
        }
        val model = viewModel { text, _, _ -> "translated $text" }
        val old = launch { model.translate("old", false, Language.ENGLISH, Language.VIETNAMESE) {} }
        saveStarted.await()
        old.cancel()
        val next = launch { model.translate("new", false, Language.ENGLISH, Language.VIETNAMESE) {} }
        advanceTimeBy(451)
        finishSave.complete(Unit)
        old.join()
        next.join()
        assertEquals(listOf("new"), history.saved)
    }

    @Test fun clearingWhileSaveIsInProgressStartsAnIndependentEntry() = runTest {
        val saveStarted = CompletableDeferred<Unit>()
        val finishSave = CompletableDeferred<Unit>()
        history.beforeSave = {
            if (it == "old") {
                saveStarted.complete(Unit)
                finishSave.await()
            }
        }
        val model = viewModel { text, _, _ -> text }
        val old = launch { model.translate("old", false, Language.ENGLISH, Language.VIETNAMESE) {} }
        saveStarted.await()
        old.cancel()
        model.translate("", false, Language.ENGLISH, Language.VIETNAMESE) {}
        finishSave.complete(Unit)
        old.join()
        model.translate("new", false, Language.ENGLISH, Language.VIETNAMESE) {}
        assertEquals(listOf("old", "new"), history.saved)
    }

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
        val expired = CompletableDeferred<Unit>()
        countdown = Countdown { milliseconds ->
            assertEquals(450L, milliseconds)
            expired.await()
        }
        var calls = 0
        val model = viewModel({ _, _, _ -> calls++; "Xin chào" })
        model.translate("Hello", true, Language.ENGLISH, Language.VIETNAMESE) {}
        assertEquals(0, calls)
        val work = launch { model.translate("Hello", false, Language.ENGLISH, Language.VIETNAMESE) {} }
        runCurrent()
        assertEquals(0, calls)
        expired.complete(Unit)
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
        }, history, countdown)

    private class History : TranslationHistoryRepository {
        val saved = mutableListOf<String>()
        var failSave = false
        var beforeSave: suspend (String) -> Unit = {}
        override suspend fun entries() = emptyList<TranslationHistoryEntry>()
        override suspend fun add(original: String, translated: String, source: Language, target: Language, replaceId: Long?): Long? {
            check(!failSave)
            beforeSave(original)
            if (replaceId != null) saved[replaceId.toInt() - 1] = original else saved += original
            return replaceId ?: saved.size.toLong()
        }
        override suspend fun setBookmarked(id: Long, bookmarked: Boolean) = Unit
        override suspend fun delete(ids: Set<Long>) = Unit
    }
}
