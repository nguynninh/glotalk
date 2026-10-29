package vn.onplay.vdubbing.data.local

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import vn.onplay.vdubbing.domain.model.Language

class TranslationHistoryRepositoryTest {
    private fun testContext() = object : android.content.ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getApplicationContext() = this
        override fun getDatabasePath(name: String) = java.io.File(cacheDir, "test-$name")
        override fun deleteDatabase(name: String) = android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    }

    @Test fun sessionReplacesDraftsWithoutOverwritingExistingHistoryOrBookmarks() = runBlocking {
        val context = testContext()
        context.deleteDatabase("translation_history.db")
        try {
            TranslationHistoryRepositoryImpl(context).use { history ->
                val old = history.add("old", "cũ", Language.ENGLISH, Language.VIETNAMESE)!!
                val draft = history.add("he", "anh ấy", Language.ENGLISH, Language.VIETNAMESE)!!
                assertEquals(draft, history.add("hello", "xin chào", Language.ENGLISH, Language.VIETNAMESE, draft))
                assertEquals(setOf("old", "hello"), history.entries().map { it.original }.toSet())
                assertNull(history.add("old", "cũ", Language.ENGLISH, Language.VIETNAMESE, draft))
                assertEquals(old, history.entries().single().id)

                val bookmarked = history.add("hi", "chào", Language.ENGLISH, Language.VIETNAMESE)!!
                history.setBookmarked(bookmarked, true)
                val next = history.add("hi there", "xin chào", Language.ENGLISH, Language.VIETNAMESE, bookmarked)!!
                assertTrue(history.entries().single { it.id == bookmarked }.bookmarked)
                assertEquals("hi", history.entries().single { it.id == bookmarked }.original)
                assertEquals(next, history.add("", "", Language.ENGLISH, Language.VIETNAMESE, next))
                history.delete(setOf(next))
                val restored = history.add("new", "mới", Language.ENGLISH, Language.VIETNAMESE, next)
                assertTrue(restored != null && restored != next)
                assertEquals(3, history.entries().size)
            }
        } finally {
            context.deleteDatabase("translation_history.db")
        }
    }

    @Test fun historySurvivesReopenAndRepeatTranslationPreservesBookmark() = runBlocking {
        val context = testContext()
        context.deleteDatabase("translation_history.db")
        try {
            TranslationHistoryRepositoryImpl(context).use { history ->
                history.add("义务", "nghĩa vụ", Language.CHINESE, Language.VIETNAMESE)
                val first = history.entries().single()
                history.setBookmarked(first.id, true)
                history.add("义务", "nghĩa vụ; tình nguyện", Language.CHINESE, Language.VIETNAMESE)
                val updated = history.entries().single()
                assertEquals(first.id, updated.id)
                assertTrue(updated.bookmarked)
                assertEquals("nghĩa vụ; tình nguyện", updated.translated)
                history.add("义务", "duty", Language.CHINESE, Language.ENGLISH)
                history.add("", "empty", Language.CHINESE, Language.VIETNAMESE)
                assertEquals(2, history.entries().size)
            }
            TranslationHistoryRepositoryImpl(context).use { history ->
                val restored = history.entries()
                assertEquals(2, restored.size)
                assertTrue(restored.single { it.target == Language.VIETNAMESE }.bookmarked)
                history.delete(setOf(restored.first().id))
                assertEquals(1, history.entries().size)
                history.delete(history.entries().map { it.id }.toSet())
                assertTrue(history.entries().isEmpty())
            }
        } finally {
            context.deleteDatabase("translation_history.db")
        }
    }
}
