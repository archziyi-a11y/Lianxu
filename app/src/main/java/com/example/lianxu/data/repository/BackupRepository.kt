package com.example.lianxu.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.model.DashboardCard
import com.example.lianxu.engine.DomainJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*

/** Logical snapshots include every business relation and attachment, never preferences or Keystore. */
class BackupRepository(private val context: Context, private val db: AppDatabase) {
    companion object {
        val tables = listOf("profiles","goal_phases","knowledge_notes","training_sessions","intake_entries","measurements","turns","boards","cards","drafts","attachments",
            "input_bundles","tasks","board_snapshots","calculations","analyses","day_plans","nutrition_policies","mutation_receipts","entity_snapshots","agent_cache","meal_progress","rest_timers")
        private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun key(id: String)=hash(id.toByteArray())
    }
    suspend fun export(output: OutputStream) = withContext(Dispatchers.IO) { db.withTransaction {
        val sql=db.openHelper.readableDatabase
        val data=JSONObject()
        for(table in tables) {
            val rows=JSONArray()
            sql.query("SELECT * FROM `$table`").use { cursor -> while(cursor.moveToNext()) {
                val row=JSONObject()
                for(i in 0 until cursor.columnCount) row.put(cursor.getColumnName(i),when(cursor.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> JSONObject.NULL; Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i); Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(i); else -> error("不支持的备份字段")
                })
                rows.put(row)
            } }
            data.put(table,rows)
        }
        val encoded=data.toString().toByteArray(); require(encoded.size<=16*1024*1024) { "记录包超过16MB，请先整理后再导出" }
        val files=JSONArray()
        val attachments=data.getJSONArray("attachments")
        for(i in 0 until attachments.length()) {
            val a=attachments.getJSONObject(i); val file=File(context.filesDir,a.getString("localPath")).canonicalFile
            require(file.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && file.isFile) { "附件缺失，未生成不完整备份" }
            require(hash(file.readBytes())==a.getString("sha256")) { "附件校验失败" }
            files.put(JSONObject().put("id",a.getString("id")).put("entry","attachments/${key(a.getString("id"))}").put("sha256",a.getString("sha256")))
        }
        val manifest=JSONObject().put("format","lianxu-backup").put("version",1).put("schemaVersion",sql.version).put("dataSha256",hash(encoded)).put("attachments",files)
        ZipOutputStream(output).use { zip ->
            fun entry(name: String,content: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() }
            entry("manifest.json",manifest.toString().toByteArray()); entry("data.json",encoded)
            for(i in 0 until attachments.length()) {
                zip.putNextEntry(ZipEntry(files.getJSONObject(i).getString("entry")))
                File(context.filesDir,attachments.getJSONObject(i).getString("localPath")).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    } }
    suspend fun restore(input: InputStream) = withContext(Dispatchers.IO) {
        val directory=File(context.filesDir,"restored/${UUID.randomUUID()}").apply { mkdirs() }
        val staged=linkedMapOf<String,File>(); var success=false
        try {
            var manifestBytes: ByteArray?=null; var dataBytes: ByteArray?=null; var total=0L
            val names=mutableSetOf<String>()
            ZipInputStream(input).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    require(names.add(entry.name) && names.size<=10002 && !entry.isDirectory) { "备份条目重复或数量异常" }
                    require(entry.name in listOf("manifest.json","data.json") || Regex("attachments/[a-f0-9]{64}").matches(entry.name)) { "备份包含未知文件" }
                    val limit=if(entry.name=="data.json")16L*1024*1024 else if(entry.name=="manifest.json")2L*1024*1024 else 25L*1024*1024
                    val buffer=ByteArrayOutputStream(); val chunk=ByteArray(8192); var size=0L
                    while(true) { val n=zip.read(chunk); if(n<0)break; size+=n;total+=n;require(size<=limit && total<=256L*1024*1024) { "备份过大" };buffer.write(chunk,0,n) }
                    val bytes=buffer.toByteArray()
                    when(entry.name) { "manifest.json" -> manifestBytes=bytes; "data.json" -> dataBytes=bytes; else -> {
                        val file=File(directory,entry.name.substringAfter('/'));file.writeBytes(bytes);staged[entry.name]=file
                    } }
                    zip.closeEntry()
                }
            }
            val manifest=JSONObject(requireNotNull(manifestBytes) { "备份缺少清单" }.toString(Charsets.UTF_8))
            require(manifest.getString("format")=="lianxu-backup" && manifest.getInt("version")==1 && manifest.getInt("schemaVersion")==db.openHelper.readableDatabase.version) { "备份版本不匹配" }
            val bytes=requireNotNull(dataBytes) { "备份缺少记录" }; require(hash(bytes)==manifest.getString("dataSha256")) { "备份记录校验失败" }
            val data=JSONObject(bytes.toString(Charsets.UTF_8)); require(data.keys().asSequence().toSet()==tables.toSet()) { "备份表不完整" }
            val files=manifest.getJSONArray("attachments"); val attachments=data.getJSONArray("attachments")
            require(files.length()==attachments.length() && staged.size==files.length()) { "备份附件数量不匹配" }
            val fileIds=mutableSetOf<String>()
            for(i in 0 until files.length()) {
                val item=files.getJSONObject(i); val id=item.getString("id"); require(fileIds.add(id))
                val row=(0 until attachments.length()).map { attachments.getJSONObject(it) }.single { it.getString("id")==id }
                val file=requireNotNull(staged[item.getString("entry")]) { "备份缺少附件" }
                require(item.getString("entry")=="attachments/${key(id)}" && hash(file.readBytes())==item.getString("sha256") && item.getString("sha256")==row.getString("sha256") && file.length()==row.getLong("byteSize")) { "附件校验失败" }
                row.put("localPath",file.relativeTo(context.filesDir).invariantSeparatorsPath)
            }
            validateGraph(data)
            val attachmentPaths=(0 until attachments.length()).associate { attachments.getJSONObject(it).getString("id") to attachments.getJSONObject(it).getString("localPath") }
            for(i in 0 until data.getJSONArray("drafts").length()) {
                val draft=data.getJSONArray("drafts").getJSONObject(i)
                val cards=JSONArray(draft.getString("cardsJson"))
                for(j in 0 until cards.length()) { val card=cards.getJSONObject(j); val path=card.optString("attachmentId").let { attachmentPaths[it] }
                    card.put("annotationImageUri",path?.let { android.net.Uri.fromFile(File(context.filesDir,it)).toString() } ?: JSONObject.NULL)
                }
                draft.put("cardsJson",cards.toString())
            }
            db.withTransaction {
                check(db.collaborationDao().unfinishedTasks().isEmpty()) { "请先等待或取消正在处理的补充，再恢复备份" }
                check(db.collaborationDao().timer()?.status!="running") { "请先结束计时，再恢复备份" }
                val sql=db.openHelper.writableDatabase
                for(table in tables) {
                    val columns=sql.query("PRAGMA table_info(`$table`)").use { cursor -> buildSet { while(cursor.moveToNext())add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) } }
                    val rows=data.getJSONArray(table)
                    for(i in 0 until rows.length())require(rows.getJSONObject(i).keys().asSequence().toSet()==columns) { "备份字段不匹配：$table" }
                }
                tables.reversed().forEach { sql.execSQL("DELETE FROM `$it`") }
                for(table in tables) {
                    val rows=data.getJSONArray(table)
                    for(i in 0 until rows.length()) {
                        val row=rows.getJSONObject(i)
                        if(table=="tasks" && row.getString("status") in listOf("queued","running","staged","applying"))row.put("status","failed").put("lastError","备份中的任务已中断，输入保留，可重试")
                        if(table=="turns" && row.getString("status") in listOf("queued","running","staged","applying"))row.put("status","failed")
                        if(table=="rest_timers" && row.getString("status")=="running")row.put("status","cancelled")
                        val values=ContentValues()
                        row.keys().forEach { column -> when(val value=row.get(column)) {
                            JSONObject.NULL -> values.putNull(column); is Int -> values.put(column,value);is Long -> values.put(column,value);is Double -> values.put(column,value)
                            is String -> values.put(column,value);else -> error("备份字段类型不正确")
                        } }
                        sql.insert(table,SQLiteDatabase.CONFLICT_ABORT,values)
                    }
                }
            }
            success=true
        } finally { if(!success) { staged.values.forEach { it.delete() };directory.delete() } }
    }
    private fun validateGraph(data: JSONObject) {
        fun rows(table: String)=data.getJSONArray(table).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        fun ids(table: String)=rows(table).map { it.getString(if(table=="agent_cache")"taskId" else "id") }.also { require(it.distinct().size==it.size) { "备份编号重复" } }.toSet()
        val boards=ids("boards"); val turns=ids("turns");val inputs=ids("input_bundles"); val snapshots=ids("board_snapshots")
        val calculations=ids("calculations"); val analyses=ids("analyses"); val attachments=ids("attachments")
        tables.forEach(::ids)
        rows("cards").forEach { require(it.getString("boardId") in boards); val card=DomainJson.decode<DashboardCard>(it.getString("data")); require(card.id==it.getString("id"));
            card.blocks.forEach { block -> require(block.content.calculationId?.let { id -> id in calculations } != false); require(block.content.contentRef?.entityRef?.let { ref -> ref.type!="analysis" || ref.id in analyses } != false);require(block.content.attachmentId?.let { id -> id in attachments } != false) }
        }
        rows("drafts").forEach { require(it.getString("boardId") in boards); BoardStore(db).decode(it.getString("cardsJson")).forEach { card -> require(card.attachmentId?.let { id -> id in attachments } != false) } }
        rows("turns").forEach { require(it.getString("inputBundleId") in inputs);require(it.optString("boardId") in boards);require(it.isNull("beforeBoardSnapshotId") || it.getString("beforeBoardSnapshotId") in snapshots) }
        rows("tasks").forEach { require(it.getString("turnId") in turns && it.getString("boardId") in boards) }
        rows("input_bundles").forEach { require(it.getString("turnId") in turns) }
    }
}
