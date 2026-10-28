package com.zailinn.c001apk.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream

internal data class BackupDocument(val name: String, val size: Long = 0, val modified: Long = 0)

/** Small SAF boundary; tests use a real filesystem implementation of the same operations. */
internal interface BackupDirectory {
    fun list(): List<BackupDocument>
    fun read(name: String): InputStream
    fun write(name: String, source: File)
    fun delete(name: String): Boolean
}

internal class SafBackupDirectory(context: Context, private val tree: Uri) : BackupDirectory {
    private val resolver = context.contentResolver
    private val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private val documents = mutableMapOf<String, Uri>()
    override fun list(): List<BackupDocument> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val cursor = if (android.os.Build.VERSION.SDK_INT >= 26) resolver.query(children, columns, android.os.Bundle.EMPTY, null)
            else resolver.query(children, columns, null, null, null)
        checkNotNull(cursor) { "无法读取备份目录，请重新选择" }
        return cursor.use {
            documents.clear()
            buildList {
                while (it.moveToNext()) {
                    if (it.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = it.getString(1) ?: continue
                    documents[name] = DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
                    add(BackupDocument(name, it.getLong(2), it.getLong(3)))
                }
            }
        }
    }
    private fun document(name: String): Uri {
        if (name !in documents) list()
        return documents[name] ?: error("备份文件已不存在，请同步备份列表")
    }
    override fun read(name: String) = resolver.openInputStream(document(name)) ?: error("无法读取备份文件")
    override fun write(name: String, source: File) {
        check(name !in documents) { "备份文件已存在" }
        val mime = if (name.endsWith(".json")) "application/json" else "application/zip"
        val uri = DocumentsContract.createDocument(resolver, root, mime, name) ?: error("无法创建备份文件")
        try {
            resolver.openOutputStream(uri, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: error("无法写入备份目录")
            documents[name] = uri
        } catch (e: Throwable) { runCatching { DocumentsContract.deleteDocument(resolver, uri) }; throw e }
    }
    override fun delete(name: String): Boolean {
        val uri = documents[name] ?: run { list(); documents[name] } ?: return true
        return DocumentsContract.deleteDocument(resolver, uri).also { if (it) documents.remove(name) }
    }
}
