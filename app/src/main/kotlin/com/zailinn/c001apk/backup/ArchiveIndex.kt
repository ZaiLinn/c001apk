package com.zailinn.c001apk.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.zailinn.c001apk.core.backup.*
import com.zailinn.c001apk.data.FeedRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Rebuildable index only; the SAF files remain authoritative. */
class ArchiveIndex(context: Context) : SQLiteOpenHelper(context,
    File(context.filesDir, "archive/index.db").apply { parentFile!!.mkdirs() }.path, null, 1) {
    private val changes = MutableStateFlow(0L)
    val revision: StateFlow<Long> = changes
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(false) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS backup(feed_id TEXT PRIMARY KEY, base TEXT NOT NULL, doc_modified INTEGER NOT NULL, doc_size INTEGER NOT NULL, saved_at INTEGER NOT NULL, uid TEXT, username TEXT, title TEXT, body TEXT NOT NULL, dateline INTEGER, image_quality TEXT NOT NULL, includes_replies INTEGER NOT NULL, list_fingerprint TEXT NOT NULL DEFAULT '', warnings INTEGER NOT NULL DEFAULT 0, online_state TEXT NOT NULL DEFAULT 'unknown')")
        db.execSQL("CREATE TABLE IF NOT EXISTS source(feed_id TEXT NOT NULL, kind TEXT NOT NULL, account_uid TEXT NOT NULL DEFAULT '', collection_id TEXT NOT NULL DEFAULT '', collection_title TEXT NOT NULL DEFAULT '', added_at INTEGER NOT NULL, removed_at INTEGER, PRIMARY KEY(feed_id,kind,account_uid,collection_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS queue(feed_id TEXT PRIMARY KEY, options TEXT NOT NULL, list_fingerprint TEXT NOT NULL DEFAULT '', state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, error TEXT, enqueued_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS document(name TEXT PRIMARY KEY, feed_id TEXT NOT NULL, modified INTEGER NOT NULL, size INTEGER NOT NULL, saved_at INTEGER NOT NULL, valid INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        listOf("backup", "source", "queue", "meta", "document").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }; onCreate(db)
        db.execSQL("INSERT INTO meta VALUES('needs_rebuild','1')")
    }
    @Synchronized fun meta(key: String): String? = readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    @Synchronized fun meta(key: String, value: String) { writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE) }
    @Synchronized fun directory(uri: String) {
        if (meta("directory") == uri) return
        transaction {
            delete("backup", null, null); delete("queue", null, null); delete("document", null, null)
            delete("source", "kind='cloud'", null)
            delete("meta", null, null)
            meta("directory", uri); meta("needs_rebuild", "1")
        }
    }
    @Synchronized fun transaction(block: SQLiteDatabase.() -> Unit) {
        val db = writableDatabase; db.beginTransaction()
        try { db.block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
        changes.value++
    }
    @Synchronized fun put(backup: FeedBackup, base: String, modified: Long, size: Long) {
        val feed = backup.data
        val oldState = state(backup.id)
        val values = ContentValues().apply {
            put("feed_id", backup.id); put("base", base); put("doc_modified", modified); put("doc_size", size)
            put("saved_at", backup.savedAt.takeIf { it > 0 } ?: modified); put("uid", feed.uid.orEmpty()); put("username", feed.displayName)
            put("title", BackupText.title(backup)); put("body", BackupText.searchable(backup)); put("dateline", feed.dateline ?: 0)
            put("image_quality", backup.imageQuality.name); put("includes_replies", if (backup.includesReplies) 1 else 0)
            put("list_fingerprint", backup.listFingerprint); put("warnings", backup.warnings.size); put("online_state", oldState)
        }
        writableDatabase.insertWithOnConflict("backup", null, values, SQLiteDatabase.CONFLICT_REPLACE); changes.value++
    }
    @Synchronized fun records(): List<BackupRecord> = readableDatabase.rawQuery("SELECT feed_id,base,saved_at,uid,username,title,dateline,warnings FROM backup ORDER BY saved_at DESC,feed_id", null).use { cursor -> buildList { while (cursor.moveToNext()) add(record(cursor)) } }
    private fun record(c: Cursor): BackupRecord {
        fun text(key: String) = c.getString(c.getColumnIndexOrThrow(key)).orEmpty()
        fun long(key: String) = c.getLong(c.getColumnIndexOrThrow(key))
        return BackupRecord(FeedRecord(text("feed_id"), text("uid"), text("username"), message = text("title"), dateline = long("dateline"), time = long("saved_at")),
            text("base"), long("saved_at"), if (long("warnings") > 0) listOf("备份有缺失") else emptyList())
    }
    @Synchronized fun row(id: String): Map<String, String>? = readableDatabase.rawQuery("SELECT feed_id,base,doc_modified,doc_size,saved_at,uid,username,title,dateline,image_quality,includes_replies,list_fingerprint,warnings,online_state FROM backup WHERE feed_id=?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null else c.columnNames.associateWith { c.getString(c.getColumnIndexOrThrow(it)).orEmpty() }
    }
    @Synchronized fun state(id: String): String = row(id)?.get("online_state") ?: "unknown"
    @Synchronized fun state(id: String, state: String) { require(state in listOf("unknown", "ok", "gone")); writableDatabase.update("backup", ContentValues().apply { put("online_state", state) }, "feed_id=?", arrayOf(id)); changes.value++ }
    @Synchronized fun fingerprint(id: String, value: String) {
        writableDatabase.update("backup", ContentValues().apply { put("list_fingerprint", value) }, "feed_id=?", arrayOf(id))
        changes.value++
    }
    @Synchronized fun remove(id: String) { writableDatabase.delete("backup", "feed_id=?", arrayOf(id)); changes.value++ }
    @Synchronized fun clearBackups() { writableDatabase.delete("backup", null, null); changes.value++ }
    @Synchronized fun retain(ids: Set<String>) { records().filter { it.feed.id !in ids }.forEach { remove(it.feed.id) } }
    @Synchronized fun source(id: String, kind: String, account: String = "", collection: String = "", title: String = "", removed: Boolean = false) {
        val added = readableDatabase.rawQuery("SELECT added_at FROM source WHERE feed_id=? AND kind=? AND account_uid=? AND collection_id=?", arrayOf(id, kind, account, collection)).use { if (it.moveToFirst()) it.getLong(0) else System.currentTimeMillis() }
        val values = ContentValues().apply {
            put("feed_id", id); put("kind", kind); put("account_uid", account); put("collection_id", collection); put("collection_title", title)
            put("added_at", added); if (removed) put("removed_at", System.currentTimeMillis()) else putNull("removed_at")
        }
        writableDatabase.insertWithOnConflict("source", null, values, SQLiteDatabase.CONFLICT_REPLACE); changes.value++
    }
    @Synchronized fun localSources(ids: Set<String>) {
        transaction {
            val existing = rawQuery("SELECT feed_id FROM source WHERE kind='local' AND removed_at IS NULL", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            existing.filter { it !in ids }.forEach { source(it, "local", removed = true) }
            ids.forEach { source(it, "local") }
        }
    }
    @Synchronized fun sources(id: String): List<Map<String, String>> = readableDatabase.rawQuery("SELECT * FROM source WHERE feed_id=?", arrayOf(id)).use { c -> buildList {
        while (c.moveToNext()) add(c.columnNames.associateWith { c.getString(c.getColumnIndexOrThrow(it)).orEmpty() })
    } }
    @Synchronized fun markRemoved(account: String, collection: String, seen: Set<String>): Int {
        val ids = readableDatabase.rawQuery("SELECT feed_id FROM source WHERE kind='cloud' AND account_uid=? AND collection_id=? AND removed_at IS NULL", arrayOf(account, collection)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return ids.filter { it !in seen }.sumOf { id -> writableDatabase.update("source", ContentValues().apply { put("removed_at", System.currentTimeMillis()) }, "feed_id=? AND kind='cloud' AND account_uid=? AND collection_id=?", arrayOf(id, account, collection)) }.also { changes.value++ }
    }
    @Synchronized fun enqueue(id: String, options: BackupOptions, fingerprint: String) {
        val existing = queue().firstOrNull { it.id == id && it.state in listOf("pending", "running") }
        if (existing != null && existing.fingerprint == fingerprint && existing.options == options) return
        writableDatabase.insertWithOnConflict("queue", null, ContentValues().apply {
            put("feed_id", id); put("options", FeedBackup.json.encodeToString(BackupOptions.serializer(), options)); put("list_fingerprint", fingerprint)
            put("state", "pending"); put("attempts", 0); putNull("error"); put("enqueued_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE); changes.value++
    }
    @Synchronized fun queue(): List<ArchiveQueueItem> = readableDatabase.rawQuery("SELECT * FROM queue ORDER BY enqueued_at,feed_id", null).use { c -> buildList {
        while (c.moveToNext()) {
            fun text(key: String) = c.getString(c.getColumnIndexOrThrow(key)).orEmpty()
            val options = runCatching { FeedBackup.json.decodeFromString(BackupOptions.serializer(), text("options")) }.getOrDefault(BackupOptions())
            add(ArchiveQueueItem(text("feed_id"), options, text("list_fingerprint"), text("state"), c.getInt(c.getColumnIndexOrThrow("attempts")), text("error")))
        }
    } }
    @Synchronized fun queueState(id: String, state: String, attempts: Int? = null, error: String? = null) {
        require(state in listOf("pending", "running", "done", "failed", "skipped"))
        writableDatabase.update("queue", ContentValues().apply { put("state", state); if (attempts != null) put("attempts", attempts); if (error != null) put("error", error) else putNull("error") }, "feed_id=?", arrayOf(id)); changes.value++
    }
    @Synchronized fun recoverQueue() { writableDatabase.execSQL("UPDATE queue SET state='pending' WHERE state='running'"); changes.value++ }
    @Synchronized fun clearFinishedQueue() { writableDatabase.delete("queue", "state IN ('done','failed','skipped')", null); changes.value++ }
    @Synchronized fun cancelQueue() { writableDatabase.execSQL("UPDATE queue SET state='skipped',error='已取消' WHERE state IN ('pending','running')"); changes.value++ }
    @Synchronized fun cloudCollections(account: String): List<Pair<String, String>> = readableDatabase.rawQuery("SELECT DISTINCT collection_id,collection_title FROM source WHERE kind='cloud' AND account_uid=? ORDER BY collection_title", arrayOf(account)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
    @Synchronized fun document(name: String): Map<String, String>? = readableDatabase.rawQuery("SELECT * FROM document WHERE name=?", arrayOf(name)).use { c ->
        if (!c.moveToFirst()) null else c.columnNames.associateWith { c.getString(c.getColumnIndexOrThrow(it)).orEmpty() }
    }
    @Synchronized fun document(name: String, id: String, modified: Long, size: Long, savedAt: Long, valid: Boolean) {
        writableDatabase.insertWithOnConflict("document", null, ContentValues().apply {
            put("name", name); put("feed_id", id); put("modified", modified); put("size", size); put("saved_at", savedAt); put("valid", if (valid) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun documents(): List<Map<String, String>> = readableDatabase.rawQuery("SELECT * FROM document ORDER BY saved_at DESC,name DESC", null).use { c -> buildList {
        while (c.moveToNext()) add(c.columnNames.associateWith { c.getString(c.getColumnIndexOrThrow(it)).orEmpty() })
    } }
    @Synchronized fun retainDocuments(names: Set<String>) { documents().filter { it["name"] !in names }.forEach { writableDatabase.delete("document", "name=?", arrayOf(it.getValue("name"))) } }
    @Synchronized fun rebuild() { transaction { delete("backup", null, null); delete("document", null, null) }; meta("needs_rebuild", "1") }
    @Synchronized fun search(query: String, scope: String = "all", account: String = "", collection: String = "", sort: String = "relevance", limit: Int = 100, offset: Int = 0): List<ArchiveHit> {
        val terms = BackupText.terms(query)
        require(terms.size <= 200) { "搜索词过多，请减少到 200 个以内" }
        val args = mutableListOf<String>(); val conditions = mutableListOf<String>()
        terms.forEach { conditions += "b.body LIKE ? ESCAPE '\\'"; args += BackupText.like(it) }
        if (scope != "all") {
            val predicate = if (scope == "local") "s.kind='local' AND s.removed_at IS NULL" else {
                args += account
                if (collection.isNotEmpty()) { args += collection; "s.kind='cloud' AND s.account_uid=? AND s.collection_id=?" } else "s.kind='cloud' AND s.account_uid=?"
            }
            conditions += "EXISTS(SELECT 1 FROM source s WHERE s.feed_id=b.feed_id AND $predicate)"
        }
        val score = if (terms.isEmpty()) "0" else terms.joinToString("+") { "((length(b.body)-length(replace(b.body,?,'')))/max(length(?),1))" }
        val scoreArgs = terms.flatMap { listOf(it, it) }
        val order = when (sort) { "published" -> "b.dateline DESC"; "relevance" -> "relevance_score DESC,b.saved_at DESC"; else -> "b.saved_at DESC" }
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        return readableDatabase.rawQuery("SELECT b.*,($score) AS relevance_score FROM backup b $where ORDER BY $order,b.feed_id LIMIT ? OFFSET ?", (scoreArgs + args + limit.coerceIn(1, 20000).toString() + offset.coerceAtLeast(0).toString()).toTypedArray()).use { c -> buildList {
            while (c.moveToNext()) {
                val record = record(c); val body = c.getString(c.getColumnIndexOrThrow("body"))
                val match = terms.map { body.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
                val start = (match - 40).coerceAtLeast(0); val end = (match + (terms.maxOfOrNull { it.length } ?: 0) + 40).coerceAtMost(body.length)
                add(ArchiveHit(record, body.substring(start, end), sources(record.feed.id), c.getString(c.getColumnIndexOrThrow("image_quality")), c.getInt(c.getColumnIndexOrThrow("warnings")) > 0, c.getString(c.getColumnIndexOrThrow("online_state")), c.getInt(c.getColumnIndexOrThrow("relevance_score"))))
            }
        } }
    }
}

data class ArchiveHit(val record: BackupRecord, val snippet: String, val sources: List<Map<String, String>>, val quality: String, val incomplete: Boolean, val onlineState: String, val relevance: Int = 0)

data class ArchiveQueueItem(val id: String, val options: BackupOptions, val fingerprint: String, val state: String, val attempts: Int, val error: String)
