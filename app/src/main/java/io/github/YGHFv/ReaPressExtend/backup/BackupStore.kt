/*
 * Copyright (C) 2026 YGHFv
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.YGHFv.ReaPressExtend.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.YGHFv.ReaPressExtend.core.BackupBundle
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import java.io.File

/**
 * 「备份往哪儿放」接口，两种实现：默认目录（应用专属外部目录，无需存储权限、任何时候都写得进去）
 * 与自选目录（[SafBackupStore]，放到模块管不着的地方，授权须持久化）。
 * [list] 只认 [BackupBundle.FILE_PREFIX] / [BackupBundle.FILE_SUFFIX] 的文件，份数裁剪也只删这个集合
 * —— 用户选的目录里可能塞满别的东西，为一个「备份上限」删掉用户的其他文件是最严重的错误。
 */
internal interface BackupStore {

    val label: String

    /** 目录里属于本模块的备份，按文件名倒序（文件名里带时间戳，倒序即最新在前）。 */
    fun list(): List<BackupEntry>

    fun read(name: String): String

    fun write(name: String, text: String)

    /** 失败不抛 —— 裁剪是「顺手清理」，不该因为它让整次备份显示为失败。 */
    fun delete(name: String)
}

internal class BackupEntry(
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

/** 默认目录：应用专属外部目录（数据线连电脑能直接看到）；外部存储不可用时退回内部目录。 */
internal class AppDirBackupStore(private val dir: File) : BackupStore {

    override val label: String get() = dir.absolutePath

    override fun list(): List<BackupEntry> = ours(dir.listFiles()).mapNotNull { file ->
        runCatching { BackupEntry(file.name, file.length(), file.lastModified()) }.getOrNull()
    }.sortedByDescending { it.name }

    override fun read(name: String): String = File(dir, name).readText()

    override fun write(name: String, text: String) {
        dir.mkdirs()
        File(dir, name).writeText(text)
    }

    override fun delete(name: String) {
        runCatching { File(dir, name).delete() }
            .onFailure { ModuleAndroidLog.error(TAG, "backup delete failed: $name", it) }
    }

    private fun ours(files: Array<File>?): List<File> = files.orEmpty().filter {
        it.isFile && it.name.startsWith(BackupBundle.FILE_PREFIX) &&
            it.name.endsWith(BackupBundle.FILE_SUFFIX)
    }

    private companion object {
        const val TAG = "ReaPress"
    }
}

/** 用户自选目录（SAF 树）。直接用 [DocumentsContract]，不引 androidx.documentfile（只需四件能力）。授权必须持久化（[takePersistable]），自动备份可能在无界面时发生。 */
internal class SafBackupStore(
    private val context: Context,
    private val uri: Uri,
) : BackupStore {

    private val treeDocId: String get() = DocumentsContract.getTreeDocumentId(uri)

    private val childrenUri: Uri
        get() = DocumentsContract.buildChildDocumentsUriUsingTree(uri, treeDocId)

    private val rootDocUri: Uri
        get() = DocumentsContract.buildDocumentUriUsingTree(uri, treeDocId)

    private val resolver get() = context.contentResolver

    override val label: String by lazy {
        runCatching {
            resolver.query(
                rootDocUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.toString()
    }

    override fun list(): List<BackupEntry> {
        val out = mutableListOf<BackupEntry>()
        runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    if (!isOurs(name)) continue
                    out += BackupEntry(
                        name = name,
                        sizeBytes = if (cursor.isNull(1)) 0L else cursor.getLong(1),
                        modifiedAt = if (cursor.isNull(2)) 0L else cursor.getLong(2),
                    )
                }
            }
        }.onFailure { ModuleAndroidLog.error(TAG, "saf list failed", it) }
        return out.sortedByDescending { it.name }
    }

    override fun read(name: String): String {
        val docUri = findUri(name) ?: error("在所选目录里找不到「$name」")
        return resolver.openInputStream(docUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("读不到「$name」")
    }

    override fun write(name: String, text: String) {
        // 先删同名：部分 provider 的 createDocument 撞名时会给「xxx (1)」，
        // 备份名就跟时间戳对不上了，裁剪与列表也跟着乱。
        findUri(name)?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }

        val created = DocumentsContract.createDocument(
            resolver,
            rootDocUri,
            BackupBundle.MIME_TYPE,
            name,
        ) ?: error("在所选目录里建不了文件（授权可能已失效，请重新选一次目录）")

        resolver.openOutputStream(created)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("写不进「$name」")
    }

    override fun delete(name: String) {
        val docUri = findUri(name) ?: return
        runCatching { DocumentsContract.deleteDocument(resolver, docUri) }
            .onFailure { ModuleAndroidLog.error(TAG, "saf delete failed: $name", it) }
    }

    private fun findUri(name: String): Uri? = runCatching {
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val display = cursor.getString(1) ?: continue
                if (display == name) {
                    return@runCatching DocumentsContract.buildDocumentUriUsingTree(
                        uri,
                        cursor.getString(0),
                    )
                }
            }
            null
        }
    }.getOrNull()

    private fun isOurs(name: String): Boolean =
        name.startsWith(BackupBundle.FILE_PREFIX) && name.endsWith(BackupBundle.FILE_SUFFIX)

    internal companion object {
        private const val TAG = "ReaPress"

        /** 记住用户选的目录（自动备份要在无界面时也能写）。拿不到持久授权不算失败，界面会如实说明。 */
        fun takePersistable(context: Context, uri: Uri): Boolean = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            true
        }.getOrElse {
            ModuleAndroidLog.error(TAG, "take persistable uri permission failed", it)
            false
        }
    }
}
