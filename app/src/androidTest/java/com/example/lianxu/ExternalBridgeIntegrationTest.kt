package com.example.lianxu

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.model.DashboardCard
import com.example.lianxu.data.model.CardSpan
import com.example.lianxu.data.entity.GoalPhase
import com.example.lianxu.data.entity.AttachmentEntity
import com.example.lianxu.data.prefs.SettingsManager
import com.example.lianxu.data.repository.BoardStore
import com.example.lianxu.data.repository.ExternalRepository
import com.example.lianxu.data.repository.ExternalSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.security.MessageDigest

/** Requires the isolated local bridge fixture listening on 127.0.0.1:8765. */
@RunWith(AndroidJUnit4::class)
class ExternalBridgeIntegrationTest {
    @Test fun androidQueueMcpHandoffAndRevocation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(context.cacheDir, "bridge-settings-${UUID.randomUUID()}.preferences_pb")
        val imageFile = File(context.filesDir, "attachments/bridge-fixture-${UUID.randomUUID()}.jpg")
        val settings = SettingsManager(context, PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        val client = OkHttpClient()
        fun mcp(id: Int, method: String, params: JSONObject): JSONObject {
            val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
            val request = Request.Builder().url("http://127.0.0.1:8765/mcp/")
                .header("Authorization", "Bearer ${"a".repeat(40)}").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            return client.newCall(request).execute().use { response -> assertEquals(200, response.code); JSONObject(response.body.string()) }
        }
        try {
            settings.saveBridgeCredentials("http://127.0.0.1:8765", "o".repeat(40), "a".repeat(40))
            imageFile.parentFile!!.mkdirs()
            InstrumentationRegistry.getInstrumentation().context.assets.open("training-chest.jpg").use { input -> imageFile.outputStream().use { input.copyTo(it) } }
            val sha=MessageDigest.getInstance("SHA-256").digest(imageFile.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            db.collaborationDao().putAttachment(AttachmentEntity("fixture-photo",imageFile.relativeTo(context.filesDir).invariantSeparatorsPath,"image/jpeg",sha,imageFile.length(),1))
            db.goalPhaseDao().insert(GoalPhase("goal", "gain", "2026-01-01", null, null, 1, 1, 1))
            val repo = ExternalRepository(db); val control = repo.beginHandoff("integration-agent")
            val board = BoardStore(db); val boardId = board.open(listOf(DashboardCard(id="c", title="训练", value="待补充", span=CardSpan.LARGE)))
            board.edit(boardId) { cards -> cards.map { it.copy(annotationText="今天感觉不错", attachmentId="fixture-photo", isAnnotationMode=true) } }
            val submission = board.prepare(boardId)
            repo.queueSubmission(submission)
            assertEquals(1, repo.pending().size)
            val sync = ExternalSync(db, settings, context)
            assertEquals(1, sync.sync())
            val initialize = JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "android-test").put("version", "1"))
            assertTrue(mcp(1, "initialize", initialize).has("result"))
            val read = mcp(2, "tools/call", JSONObject().put("name", "read_handoff").put("arguments", JSONObject()))
            val structured = JSONObject(read.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text"))
            assertEquals(1, structured.getJSONArray("pending").length())
            val revision = structured.getLong("revision")
            val accepted = mcp(3, "tools/call", JSONObject().put("name", "accept_handoff").put("arguments", JSONObject().put("snapshot_revision", revision)))
            assertFalse(accepted.getJSONObject("result").optBoolean("isError"))
            sync.sync(); assertTrue(repo.control().handoffAccepted)
            val requestImage=mcp(9,"tools/call",JSONObject().put("name","request_attachment").put("arguments",JSONObject().put("attachment_id","fixture-photo")))
            assertFalse(requestImage.getJSONObject("result").optBoolean("isError"))
            sync.sync()
            val image=mcp(10,"tools/call",JSONObject().put("name","read_attachment").put("arguments",JSONObject().put("attachment_id","fixture-photo")))
            assertEquals("image",image.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("type"))
            val proposal = JSONObject().put("boardId", boardId).put("boardRevision", db.collaborationDao().board(boardId)!!.revision)
                .put("inputIds", JSONArray().put(repo.pending().single().id))
                .put("cards", JSONArray().put(JSONObject().put("id", "$boardId:external:reply").put("title", "训练反馈").put("text", "继续观察训练感受").put("span", 6)))
            val submitted = mcp(4, "tools/call", JSONObject().put("name", "submit_proposal").put("arguments", JSONObject()
                .put("proposal_id", "test-proposal").put("base_revision", revision).put("proposal", proposal)))
            assertFalse(submitted.getJSONObject("result").optBoolean("isError"))
            board.edit(boardId) { cards -> cards.map { it.copy(isAnnotationMode = true) } }
            sync.sync()
            assertNull(db.collaborationDao().receipt("external:test-proposal", "apply"))
            board.edit(boardId) { cards -> cards.map { it.copy(isAnnotationMode = false) } }
            sync.sync()
            assertNotNull(db.collaborationDao().receipt("external:test-proposal", "apply"))
            assertTrue(repo.pending().isEmpty())
            assertTrue(db.collaborationDao().cards(boardId).any { it.id == "$boardId:external:reply" })
            sync.sync()
            val refreshed = mcp(5, "tools/call", JSONObject().put("name", "read_handoff").put("arguments", JSONObject()))
            val planBasis = JSONObject(refreshed.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")).getLong("revision")
            val planArgs = JSONObject().put("proposal_id", "plan-proposal").put("base_revision", planBasis).put("plan_id", "version-1")
                .put("goal_id", "goal").put("goal_revision", 1).put("content", "按训练反馈调整安排").put("reason", "根据近期反馈")
                .put("effective_at", 1).put("activate", true).put("changes_goal", false).put("breaks_constraints", false).put("input_ids", JSONArray())
            val planResult = mcp(6, "tools/call", JSONObject().put("name", "submit_plan").put("arguments", planArgs))
            assertFalse(planResult.getJSONObject("result").optBoolean("isError"))
            sync.sync(); assertEquals("version-1", repo.currentPlan()!!.id)
            sync.sync()
            val latest = mcp(7, "tools/call", JSONObject().put("name", "read_handoff").put("arguments", JSONObject()))
            val decisionBasis = JSONObject(latest.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")).getLong("revision")
            val decisionArgs = JSONObject(planArgs.toString()).put("proposal_id", "decision-proposal").put("plan_id", "version-2")
                .put("base_revision", decisionBasis).put("changes_goal", true)
            assertFalse(mcp(8, "tools/call", JSONObject().put("name", "submit_plan").put("arguments", decisionArgs)).getJSONObject("result").optBoolean("isError"))
            sync.sync()
            assertEquals("version-1", repo.currentPlan()!!.id)
            assertTrue(repo.requirements().any { it.mergeKey == "decision:decision-proposal" })
            repo.switchToBuiltin(); sync.sync()
            val req = Request.Builder().url("http://127.0.0.1:8765/mcp/").header("Authorization", "Bearer ${"a".repeat(40)}")
                .post("{}".toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { assertEquals(401, it.code) }
            assertEquals(control.epoch + 1, repo.control().epoch)
            assertTrue(repo.pending().isEmpty())
        } finally { db.close(); scope.cancel(); file.delete(); imageFile.delete() }
    }
}
