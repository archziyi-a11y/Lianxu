package com.example.lianxu.data.repository

import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.prefs.SettingsManager
import com.example.lianxu.engine.DomainJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.lianxu.R
import com.example.lianxu.MainActivity
import android.util.Base64
import java.io.File
import java.security.MessageDigest

/** Synchronizes only after explicit configuration. Never logs tokens or personal payloads. */
class ExternalSync(private val db: AppDatabase, private val settings: SettingsManager, private val context: Context? = null) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val repo = ExternalRepository(db)
    private fun notifyDecision(id: String) {
        val app = context ?: return
        val manager = NotificationManagerCompat.from(app)
        if (!manager.areNotificationsEnabled()) return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            app.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("external-decisions", "需要决定", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, "external-decisions").setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("有一项训练安排需要决定").setContentText("打开档案查看建议并作出选择")
            .setContentIntent(intent).setAutoCancel(true).build()
        try { manager.notify(id.hashCode(), notification) } catch (_: SecurityException) { }
    }
    private fun basis(value: JSONObject): String {
        val copy = JSONObject(value.toString())
        val boards = copy.getJSONArray("boards")
        for (i in 0 until boards.length()) boards.getJSONObject(i).remove("draft")
        return copy.toString()
    }
    private fun call(credentials: SettingsManager.BridgeCredentials, action: String, payload: JSONObject): JSONObject {
        val request = Request.Builder().url("${credentials.url}/phone/$action")
            .header("Authorization", "Bearer ${credentials.ownerToken}")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            check(response.isSuccessful) { "同步失败（${response.code}）" }
            return JSONObject(body)
        }
    }
    private suspend fun snapshot(): JSONObject = db.withTransaction {
        fun json(value: Any) = JSONObject(DomainJson.encode(value))
        val recordDao = db.recordDao()
        val collaboration = db.collaborationDao()
        val boards = collaboration.allBoards()
        JSONObject().put("profile", db.profileDao().getProfile()?.let(::json))
            .put("goals", JSONArray(db.goalPhaseDao().getGoalPhasesFlow().first().map(::json)))
            .put("trainings", JSONArray(recordDao.allTrainings().map(::json)))
            .put("measurements", JSONArray(db.measurementDao().all().map(::json)))
            .put("intakes", JSONArray(recordDao.allIntakes().map(::json)))
            .put("knowledge", JSONArray(recordDao.allKnowledge().map(::json)))
            .put("analyses", JSONArray(collaboration.allAnalyses().map(::json)))
            .put("calculations", JSONArray(collaboration.allCalculations().map(::json)))
            .put("nutritionPlans", JSONArray(collaboration.allPlans().map(::json)))
            .put("trainingPlans", JSONArray(repo.plans().map(::json)))
            .put("currentTrainingPlan", repo.currentPlan()?.let(::json))
            .put("requirements", JSONArray(repo.requirements().map(::json)))
            .put("boards", JSONArray(boards.map { board ->
                json(board).put("cards", JSONArray(collaboration.cards(board.id).map(::json)))
                    .put("draft", collaboration.draft(board.id)?.let(::json))
                    .put("turns", JSONArray(collaboration.turns(board.id).map { turn ->
                        json(turn).put("input", collaboration.input(turn.inputBundleId)?.let(::json))
                    }))
            }))
            .put("archiveTopics", JSONArray(db.archiveDao().topics().map(::json)))
            .put("archivePlacements", JSONArray(db.archiveDao().placements().map(::json)))
    }
    suspend fun sync(): Int = withContext(Dispatchers.IO) {
        val credentials = requireNotNull(settings.bridgeCredentials.first()) { "请先设置外部连接" }
        val control = repo.control()
        call(credentials, "switch", JSONObject().put("epoch", control.epoch).put("mode", control.mode)
            .put("agent", control.agentId).put("agentToken", if (control.mode == "external") credentials.agentToken else JSONObject.NULL))
        if (control.mode != "external") return@withContext 0
        val body = snapshot()
        check(repo.control() == control) { "处理方已切换，请重新同步" }
        val snapshotRevision = call(credentials, "snapshot", JSONObject().put("epoch", control.epoch).put("body", body)).getLong("revision")
        var count = 0
        for (input in repo.pending()) {
            if (repo.control() != control) error("处理方已切换，请重新同步")
            call(credentials, "enqueue", JSONObject().put("epoch", control.epoch).put("id", input.id).put("body", JSONObject(input.payload)))
            count++
        }
        val requested = call(credentials, "image_requests", JSONObject().put("epoch", control.epoch)).getJSONArray("ids")
        for (i in 0 until requested.length()) {
            val id = requested.getString(i)
            val allowed = repo.pending().any { input ->
                val annotations = JSONObject(input.payload).getJSONArray("annotations")
                (0 until annotations.length()).any { n ->
                    val parts = annotations.getJSONObject(n).getJSONArray("inputParts")
                    (0 until parts.length()).any { p -> parts.getJSONObject(p).let { it.optString("type") == "image" && it.optString("attachmentId") == id } }
                }
            }
            require(allowed) { "图片不属于待处理输入" }
            val app = requireNotNull(context)
            val attachment = requireNotNull(db.collaborationDao().attachment(id))
            val file = File(app.filesDir, attachment.localPath).canonicalFile
            require(file.toPath().startsWith(app.filesDir.canonicalFile.toPath()) && file.isFile && file.length() == attachment.byteSize && file.length() in 1..25L*1024*1024)
            val bytes = file.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(hash == attachment.sha256) { "图片校验失败" }
            call(credentials, "image", JSONObject().put("epoch", control.epoch).put("id", id).put("mime", attachment.mimeType)
                .put("sha256", hash).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))
        }
        val remote = call(credentials, "control", JSONObject())
        if (remote.getLong("epoch") == control.epoch && remote.optInt("accepted") == 1 && !control.handoffAccepted) {
            repo.acceptHandoff(requireNotNull(control.agentId), control.epoch)
        }
        val proposals = call(credentials, "proposals", JSONObject().put("epoch", control.epoch)).getJSONArray("proposals")
        for (i in 0 until proposals.length()) {
            val item = proposals.getJSONObject(i)
            val id = item.getString("id")
            val receipt = db.collaborationDao().receipt("external:$id", "apply")
            val result = if (receipt != null) if (receipt.resultRefs == "decision") "decision" else "applied" else if (item.getLong("base_revision") != snapshotRevision) "rejected" else {
                try {
                    db.withTransaction {
                        if (basis(snapshot()) != basis(body)) "deferred"
                        else {
                            val proposal = JSONObject(item.getString("body"))
                            if (proposal.optString("type") == "requirement") {
                                repo.applyRequirementProposal(requireNotNull(control.agentId), control.epoch, id, proposal)
                                "applied"
                            } else if (proposal.optString("type") == "plan") {
                                repo.applyPlanProposal(requireNotNull(control.agentId), control.epoch, id, proposal)
                            } else if (proposal.optString("type") == "archive") {
                                repo.applyArchiveProposal(requireNotNull(context), requireNotNull(control.agentId), control.epoch, id, proposal)
                                "applied"
                            } else repo.applyTextProposal(requireNotNull(control.agentId), control.epoch, id, proposal)
                        }
                    }
                } catch (error: IllegalArgumentException) { "rejected" }
                catch (error: org.json.JSONException) { "rejected" }
                catch (error: IllegalStateException) {
                    val latest = repo.control()
                    if (latest.mode != "external" || latest.epoch != control.epoch || !latest.handoffAccepted) throw error
                    "rejected"
                }
            }
            if (receipt == null && result == "decision") notifyDecision(id)
            if (result != "deferred") call(credentials, "ack", JSONObject().put("epoch", control.epoch).put("id", id).put("status", result))
        }
        count
    }
}
