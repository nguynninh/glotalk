package vn.onplay.vdubbing.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.TranslationHistoryEntry
import vn.onplay.vdubbing.domain.repository.TranslationHistoryRepository

class TranslationHistoryRepositoryImpl(context: Context) : TranslationHistoryRepository, java.io.Closeable {
    private val database = object : SQLiteOpenHelper(context.applicationContext ?: context, "translation_history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    original TEXT NOT NULL,
                    translated TEXT NOT NULL,
                    source TEXT NOT NULL,
                    target TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    bookmarked INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(original, source, target)
                )
            """.trimIndent())
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    override fun close() = database.close()

    override suspend fun entries(): List<TranslationHistoryEntry> = withContext(Dispatchers.IO) {
        database.readableDatabase.rawQuery("SELECT * FROM history ORDER BY created_at DESC, id DESC", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(TranslationHistoryEntry(
                        id = cursor.getLong(0),
                        original = cursor.getString(1),
                        translated = cursor.getString(2),
                        source = Language.fromCode(cursor.getString(3)),
                        target = Language.fromCode(cursor.getString(4)),
                        createdAt = cursor.getLong(5),
                        bookmarked = cursor.getInt(6) != 0,
                    ))
                }
            }
        }
    }

    override suspend fun add(original: String, translated: String, source: Language, target: Language, replaceId: Long?): Long? {
        if (original.isBlank() || translated.isBlank()) return replaceId
        return withContext(Dispatchers.IO) {
            val db = database.writableDatabase
            val values = ContentValues().apply {
                put("original", original)
                put("translated", translated)
                put("source", source.code)
                put("target", target.code)
                put("created_at", System.currentTimeMillis())
            }
            db.beginTransaction()
            try {
                val existingId = db.rawQuery("SELECT id FROM history WHERE original = ? AND source = ? AND target = ?",
                    arrayOf(original, source.code, target.code)).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else null
                }
                val savedId = if (existingId != null) {
                    db.update("history", values, "id = ?", arrayOf(existingId.toString()))
                    if (replaceId != null && replaceId != existingId) {
                        db.delete("history", "id = ? AND bookmarked = 0", arrayOf(replaceId.toString()))
                    }
                    existingId.takeIf { it == replaceId }
                } else if (replaceId != null && db.update("history", values, "id = ? AND bookmarked = 0",
                        arrayOf(replaceId.toString())) > 0) {
                    replaceId
                } else {
                    db.insertOrThrow("history", null, values)
                }
                db.setTransactionSuccessful()
                savedId
            } finally {
                db.endTransaction()
            }
        }
    }

    override suspend fun setBookmarked(id: Long, bookmarked: Boolean): Unit = withContext(Dispatchers.IO) {
        database.writableDatabase.update("history", ContentValues().apply { put("bookmarked", bookmarked) },
            "id = ?", arrayOf(id.toString()))
        Unit
    }

    override suspend fun delete(ids: Set<Long>): Unit = withContext(Dispatchers.IO) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("history", "id = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
