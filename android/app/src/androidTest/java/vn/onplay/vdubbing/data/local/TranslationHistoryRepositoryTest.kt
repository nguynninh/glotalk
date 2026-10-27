package vn.onplay.vdubbing.data.local

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import vn.onplay.vdubbing.domain.model.Language

class TranslationHistoryRepositoryTest {
    @Test fun historySurvivesReopenAndRepeatTranslationPreservesBookmark() = runBlocking {
        val context = object : android.content.ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getApplicationContext() = this
            override fun getDatabasePath(name: String) = java.io.File(cacheDir, "test-$name")
            override fun deleteDatabase(name: String) = android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        }
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
