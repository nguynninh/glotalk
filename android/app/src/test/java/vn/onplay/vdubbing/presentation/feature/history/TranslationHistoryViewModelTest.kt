package vn.onplay.vdubbing.presentation.feature.history

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationHistoryEntry
import vn.onplay.vdubbing.domain.repository.TranslationHistoryRepository

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun loadsBookmarksAndDeletesOnlyRequestedEntries() = runTest(dispatcher) {
        val history = History()
        val model = TranslationHistoryViewModel(history)
        model.onCommand(TranslationHistoryCommand.Retry)
        advanceUntilIdle()
        assertFalse(model.state.value.loading)
        assertEquals(2, model.state.value.entries.size)
        model.onCommand(TranslationHistoryCommand.Bookmark(1, true))
        advanceUntilIdle()
        assertTrue(model.state.value.entries.first().bookmarked)
        model.onCommand(TranslationHistoryCommand.Delete(setOf(1)))
        advanceUntilIdle()
        assertEquals(listOf(2L), model.state.value.entries.map { it.id })
    }

    @Test fun readFailureCanBeRetriedAndFailedDeletionKeepsRecords() = runTest(dispatcher) {
        val history = History().apply { fail = true }
        val model = TranslationHistoryViewModel(history)
        model.onCommand(TranslationHistoryCommand.Retry)
        advanceUntilIdle()
        assertTrue(model.state.value.failed)
        assertFalse(model.state.value.busy)
        history.fail = false
        model.onCommand(TranslationHistoryCommand.Retry)
        advanceUntilIdle()
        assertFalse(model.state.value.failed)
        history.fail = true
        model.onCommand(TranslationHistoryCommand.Delete(setOf(1)))
        advanceUntilIdle()
        assertEquals(2, model.state.value.entries.size)
        assertEquals(TranslationHistoryEvent.Failure, model.events.first())
        assertFalse(model.state.value.busy)
    }

    @Test fun commandsEmitNavigationAndSpeechEventsWithoutChangingRecords() = runTest(dispatcher) {
        val history = History()
        val model = TranslationHistoryViewModel(history)
        model.onCommand(TranslationHistoryCommand.Init)
        advanceUntilIdle()
        model.onCommand(TranslationHistoryCommand.Back)
        assertEquals(TranslationHistoryEvent.Back, model.events.first())
        model.onCommand(TranslationHistoryCommand.Review)
        assertEquals(TranslationHistoryEvent.Review, model.events.first())
        model.onCommand(TranslationHistoryCommand.ReadAloud("Hello", Language.ENGLISH))
        assertEquals(TranslationHistoryEvent.ReadAloud("Hello", Language.ENGLISH), model.events.first())
        assertEquals(2, history.records.size)
    }

    private class History : TranslationHistoryRepository {
        var fail = false
        var records = listOf(1L, 2L).map {
            TranslationHistoryEntry(it, "Hello", "Xin chào", Language.ENGLISH, Language.VIETNAMESE, 0, false)
        }
        override suspend fun entries(): List<TranslationHistoryEntry> {
            check(!fail)
            return records
        }
        override suspend fun add(original: String, translated: String, source: Language, target: Language) = Unit
        override suspend fun setBookmarked(id: Long, bookmarked: Boolean) {
            check(!fail)
            records = records.map { if (it.id == id) it.copy(bookmarked = bookmarked) else it }
        }
        override suspend fun delete(ids: Set<Long>) {
            check(!fail)
            records = records.filterNot { it.id in ids }
        }
    }
}
