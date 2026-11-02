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

/** Synchronizes only after explicit configuration. Never logs tokens or personal payloads. */
class ExternalSync(private val db: AppDatabase, private val settings: SettingsManager) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val repo = ExternalRepository(db)
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
            .put("nutritionPlans", JSONArray(collaboration.allPlans().map(::json)))
            .put("trainingPlans", JSONArray(repo.plans().map(::json)))
            .put("currentTrainingPlan", repo.currentPlan()?.let(::json))
            .put("boards", JSONArray(boards.map { board ->
                json(board).put("cards", JSONArray(collaboration.cards(board.id).map(::json)))
                    .put("draft", collaboration.draft(board.id)?.let(::json))
            }))
            .put("archiveTopics", JSONArray(db.archiveDao().topics().map(::json)))
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
        val remote = call(credentials, "control", JSONObject())
        if (remote.getLong("epoch") == control.epoch && remote.optInt("accepted") == 1 && !control.handoffAccepted) {
            repo.acceptHandoff(requireNotNull(control.agentId), control.epoch)
        }
        val proposals = call(credentials, "proposals", JSONObject().put("epoch", control.epoch)).getJSONArray("proposals")
        for (i in 0 until proposals.length()) {
            val item = proposals.getJSONObject(i)
            val id = item.getString("id")
            val receipt = db.collaborationDao().receipt("external:$id", "apply")
            val result = if (receipt != null) "applied" else if (item.getLong("base_revision") != snapshotRevision) "rejected" else {
                try {
                    db.withTransaction {
                        if (basis(snapshot()) != basis(body)) "deferred"
                        else repo.applyTextProposal(requireNotNull(control.agentId), control.epoch, id, JSONObject(item.getString("body")))
                    }
                } catch (error: IllegalArgumentException) { "rejected" }
            }
            if (result != "deferred") call(credentials, "ack", JSONObject().put("epoch", control.epoch).put("id", id).put("status", result))
        }
        count
    }
}
