package com.echoreading.reader

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class ReadingEntry(
    val id: String,
    val text: String,
    val timestamp: Long,
    val wordCount: Int,
    val isFavorite: Boolean,
)

/** ponytail: SQLiteOpenHelper — zero deps, proper SQL queries with date filters + pagination */
object ReadingHistory {
    private var helper: DbHelper? = null

    fun init(context: Context) {
        if (helper == null) helper = DbHelper(context.applicationContext)
    }

    private val db get() = checkNotNull(helper) { "ReadingHistory must be initialized before use" }

    suspend fun query(
        search: String? = null,
        filter: String = "all",    // all | short | long | favorites
        dateFrom: Long? = null,    // epoch millis (inclusive)
        dateTo: Long? = null,      // epoch millis (inclusive)
        limit: Int = 20,
        offset: Int = 0,
    ): List<ReadingEntry> = withContext(Dispatchers.IO) {
        val where = StringBuilder("1=1")
        val args = mutableListOf<String>()

        if (!search.isNullOrBlank()) {
            where.append(" AND text LIKE ?")
            args.add("%$search%")
        }
        when (filter) {
            "short" -> where.append(" AND word_count < 100")
            "long" -> where.append(" AND word_count >= 100")
            "favorites" -> where.append(" AND is_favorite = 1")
        }
        if (dateFrom != null) {
            where.append(" AND timestamp >= ?")
            args.add(dateFrom.toString())
        }
        if (dateTo != null) {
            where.append(" AND timestamp <= ?")
            args.add(dateTo.toString())
        }
        args.add(limit.toString())
        args.add(offset.toString())

        val cursor = db.readableDatabase.rawQuery(
            "SELECT id, text, timestamp, word_count, is_favorite FROM entries WHERE $where ORDER BY timestamp DESC LIMIT ? OFFSET ?",
            args.toTypedArray(),
        )
        cursor.use { it.toList() }
    }

    suspend fun count(
        search: String? = null,
        filter: String = "all",
        dateFrom: Long? = null,
        dateTo: Long? = null,
    ): Int = withContext(Dispatchers.IO) {
        val where = StringBuilder("1=1")
        val args = mutableListOf<String>()
        if (!search.isNullOrBlank()) {
            where.append(" AND text LIKE ?")
            args.add("%$search%")
        }
        when (filter) {
            "short" -> where.append(" AND word_count < 100")
            "long" -> where.append(" AND word_count >= 100")
            "favorites" -> where.append(" AND is_favorite = 1")
        }
        if (dateFrom != null) {
            where.append(" AND timestamp >= ?")
            args.add(dateFrom.toString())
        }
        if (dateTo != null) {
            where.append(" AND timestamp <= ?")
            args.add(dateTo.toString())
        }

        val cursor = db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM entries WHERE $where",
            args.toTypedArray(),
        )
        cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    suspend fun totalCount(): Int = withContext(Dispatchers.IO) {
        val cursor = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM entries", null)
        cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    suspend fun add(context: Context, text: String) = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext
        init(context)
        val cursor = db.readableDatabase.rawQuery(
            "SELECT text FROM entries ORDER BY timestamp DESC LIMIT 1",
            null,
        )
        val isDuplicate = cursor.use { it.moveToFirst() && it.getString(0) == text }
        if (isDuplicate) return@withContext

        val wordCount = text.split("\\s+".toRegex()).count { it.isNotBlank() }
        db.writableDatabase.insert("entries", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("text", text)
            put("timestamp", System.currentTimeMillis())
            put("word_count", wordCount)
            put("is_favorite", 0)
        })
    }

    suspend fun toggleFavorite(id: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.execSQL(
            "UPDATE entries SET is_favorite = CASE WHEN is_favorite=1 THEN 0 ELSE 1 END WHERE id=?",
            arrayOf(id),
        )
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("entries", "id=?", arrayOf(id))
    }

    suspend fun clearNonFavorites() = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("entries", "is_favorite=0", null)
    }

    fun sizeBytes(context: Context): Long =
        context.getDatabasePath("history.db").let { if (it.exists()) it.length() else 0 }

    private fun Cursor.toList(): List<ReadingEntry> = buildList {
        while (moveToNext()) {
            add(
                ReadingEntry(
                    id = getString(getColumnIndexOrThrow("id")),
                    text = getString(getColumnIndexOrThrow("text")),
                    timestamp = getLong(getColumnIndexOrThrow("timestamp")),
                    wordCount = getInt(getColumnIndexOrThrow("word_count")),
                    isFavorite = getInt(getColumnIndexOrThrow("is_favorite")) == 1,
                )
            )
        }
    }

    private class DbHelper(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE entries (
                    id TEXT PRIMARY KEY,
                    text TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    word_count INTEGER NOT NULL,
                    is_favorite INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_timestamp ON entries(timestamp)")
            db.execSQL("CREATE INDEX idx_favorite ON entries(is_favorite)")
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
    }
}
