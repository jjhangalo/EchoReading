package com.echoreading.reader

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.util.Log
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
    private const val DB_NAME = "history.db"
    private const val TAG = "ReadingHistory"

    private var appContext: Context? = null
    private var helper: DbHelper? = null

    @Synchronized
    fun init(context: Context) {
        appContext = context.applicationContext
        if (helper == null) helper = DbHelper(appContext!!)
    }

    /**
     * SQLiteOpenHelper falls back to a read-only database when a writable open
     * fails (disk I/O, a corrupt file, or a transient lock). Its version check
     * then throws "Can't upgrade read-only database" and would crash playback,
     * which only writes a history entry. History is non-critical: retry once,
     * recreate the helper, and as a last resort delete an unusable database and
     * degrade to an empty history instead of taking the app down.
     */
    @Synchronized
    private fun <T> access(
        write: Boolean,
        fallback: T,
        block: (SQLiteDatabase) -> T,
    ): T {
        val context = checkNotNull(appContext) { "ReadingHistory must be initialized before use" }
        repeat(3) { attempt ->
            try {
                val database = checkNotNull(helper).let {
                    if (write) it.writableDatabase else it.readableDatabase
                }
                return block(database)
            } catch (error: SQLiteException) {
                Log.e(TAG, "History database access failed (attempt ${attempt + 1})", error)
                if (!isRecoverable(error)) return fallback
                if (attempt == 1) {
                    val deleted = context.deleteDatabase(DB_NAME)
                    if (!deleted) {
                        Log.w(TAG, "Could not delete the unusable history database")
                    }
                }
                resetHelper(context)
            }
        }
        Log.e(TAG, "History database remains unavailable; continuing without history")
        return fallback
    }

    private fun isRecoverable(error: SQLiteException): Boolean =
        error is SQLiteDatabaseCorruptException ||
            error is SQLiteCantOpenDatabaseException ||
            error is SQLiteDiskIOException ||
            error is SQLiteFullException ||
            error is SQLiteReadOnlyDatabaseException ||
            error is SQLiteDatabaseLockedException ||
            error.message?.contains("read-only", ignoreCase = true) == true ||
            error.message?.contains("read/write mode", ignoreCase = true) == true ||
            error.message?.contains("could not open the database", ignoreCase = true) == true ||
            error.message?.contains("locked", ignoreCase = true) == true

    private fun resetHelper(context: Context) {
        runCatching { helper?.close() }
            .onFailure { Log.w(TAG, "Could not close the history database", it) }
        helper = DbHelper(context)
    }

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

        access(write = false, fallback = emptyList()) { database ->
            val cursor = database.rawQuery(
                "SELECT id, text, timestamp, word_count, is_favorite FROM entries WHERE $where ORDER BY timestamp DESC LIMIT ? OFFSET ?",
                args.toTypedArray(),
            )
            cursor.use { it.toList() }
        }
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

        access(write = false, fallback = 0) { database ->
            val cursor = database.rawQuery(
                "SELECT COUNT(*) FROM entries WHERE $where",
                args.toTypedArray(),
            )
            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        }
    }

    suspend fun totalCount(): Int = withContext(Dispatchers.IO) {
        access(write = false, fallback = 0) { database ->
            val cursor = database.rawQuery("SELECT COUNT(*) FROM entries", null)
            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        }
    }

    suspend fun add(context: Context, text: String) = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext
        init(context)
        access(write = true, fallback = Unit) { database ->
            val cursor = database.rawQuery(
                "SELECT text FROM entries ORDER BY timestamp DESC LIMIT 1",
                null,
            )
            val isDuplicate = cursor.use { it.moveToFirst() && it.getString(0) == text }
            if (!isDuplicate) {
                val wordCount = text.split("\\s+".toRegex()).count { it.isNotBlank() }
                val row = database.insert("entries", null, ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("text", text)
                    put("timestamp", System.currentTimeMillis())
                    put("word_count", wordCount)
                    put("is_favorite", 0)
                })
                if (row == -1L) Log.e(TAG, "Could not insert the history entry")
            }
        }
    }

    suspend fun toggleFavorite(id: String) = withContext(Dispatchers.IO) {
        access(write = true, fallback = Unit) { database ->
            database.execSQL(
                "UPDATE entries SET is_favorite = CASE WHEN is_favorite=1 THEN 0 ELSE 1 END WHERE id=?",
                arrayOf(id),
            )
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        access(write = true, fallback = Unit) { database ->
            database.delete("entries", "id=?", arrayOf(id))
        }
    }

    suspend fun clearNonFavorites() = withContext(Dispatchers.IO) {
        access(write = true, fallback = Unit) { database ->
            database.delete("entries", "is_favorite=0", null)
        }
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
