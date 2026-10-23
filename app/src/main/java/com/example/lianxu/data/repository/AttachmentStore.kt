package com.example.lianxu.data.repository

import android.content.Context
import android.net.Uri
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.AttachmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class AttachmentStore(private val context: Context, private val db: AppDatabase) {
    suspend fun import(uri: Uri): AttachmentEntity = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        require(mime.startsWith("image/")) { "请选择图片文件" }
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "attachments").apply { mkdirs() }
        val file = File(directory, id)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取图片" }.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        require(size <= 25L * 1024 * 1024) { "图片超过25MB，请选择较小的图片" }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
            }
            require(size > 0) { "图片为空" }
            AttachmentEntity(id, "attachments/$id", mime,
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, size, System.currentTimeMillis()).also {
                db.collaborationDao().putAttachment(it)
            }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }
}
