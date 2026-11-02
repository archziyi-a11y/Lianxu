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
import com.example.lianxu.archive.ArchiveRepository
import com.example.lianxu.data.repository.ExternalSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import com.example.lianxu.data.repository.ForegroundSyncLoop
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
            val polling = launch(Dispatchers.IO) { ForegroundSyncLoop(150) { sync.sync() }.run() }
            withTimeout(10000) {
                while (true) {
                    val image=mcp(10,"tools/call",JSONObject().put("name","read_attachment").put("arguments",JSONObject().put("attachment_id","fixture-photo")))
                    if (image.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("type") == "image") break
                    delay(150)
                }
            }
            polling.cancelAndJoin()
            val capabilities = mcp(11, "tools/call", JSONObject().put("name", "describe_capabilities").put("arguments", JSONObject()))
            val capabilityData = JSONObject(capabilities.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text"))
            assertEquals(3, capabilityData.getJSONObject("operationSchema").getJSONObject("items").getJSONArray("anyOf").length())
            val examples = capabilityData.getJSONObject("factOperations")
            val annotationId = JSONObject(repo.pending().single().payload).getJSONArray("annotations").getJSONObject(0).getString("annotationId")
            val ops = JSONArray()
            for (kind in listOf("create_measurement", "create_intake")) {
                ops.put(JSONObject().put("operationId", kind).put("kind", kind)
                    .put("payload", examples.getJSONObject(kind).getJSONObject("payload"))
                    .put("relatedAnnotationIds", JSONArray().put(annotationId)).put("reason", "测试输入的图片内容"))
            }
            val factArgs = JSONObject().put("proposal_id", "image-facts").put("base_revision", revision)
                .put("input_id", repo.pending().single().id).put("operations", ops)
            assertFalse(mcp(12, "tools/call", JSONObject().put("name", "submit_facts").put("arguments", factArgs)).getJSONObject("result").optBoolean("isError"))
            sync.sync()
            assertEquals(1, db.measurementDao().all().size)
            assertEquals(1, db.recordDao().allIntakes().size)
            assertTrue(repo.pending().isEmpty())
            val archive = ArchiveRepository(context, db)
            archive.methods.installBuiltins(); archive.refresh()
            sync.sync()
            val afterFacts = mcp(13, "tools/call", JSONObject().put("name", "read_handoff").put("arguments", JSONObject()))
            val afterFactData = JSONObject(afterFacts.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text"))
            val snapshot = afterFactData.getJSONObject("snapshot")
            assertTrue(snapshot.getJSONArray("archiveMethods").length() > 0)
            assertTrue(snapshot.getJSONArray("calculations").length() > 0)
            val topics = snapshot.getJSONArray("archiveTopics")
            val topic = (0 until topics.length()).map { topics.getJSONObject(it) }.first { it.getString("methodId") == "builtin.weight-series" && it.getString("scopeKey") == "accumulated" && it.getString("state") == "current" }
            val chart = JSONObject().put("title", "体重图表").put("span", 6).put("blocks", JSONArray().put(JSONObject().put("topicKey", topic.getString("id")).put("type", "trend").put("label", "体重")))
            val archiveArgs = JSONObject().put("proposal_id", "fact-chart").put("base_revision", afterFactData.getLong("revision")).put("cards", JSONArray().put(chart)).put("input_ids", JSONArray())
            assertFalse(mcp(15, "tools/call", JSONObject().put("name", "submit_archive").put("arguments", archiveArgs)).getJSONObject("result").optBoolean("isError"))
            sync.sync()
            assertNotNull(db.collaborationDao().receipt("external:fact-chart", "apply"))
            assertNotNull(db.archiveDao().presentation())
            sync.sync()
            val chartRead = mcp(16, "tools/call", JSONObject().put("name", "read_handoff").put("arguments", JSONObject()))
            val afterFactRevision = JSONObject(chartRead.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")).getLong("revision")
            val proposal = JSONObject().put("boardId", boardId).put("boardRevision", db.collaborationDao().board(boardId)!!.revision)
                .put("inputIds", JSONArray())
                .put("cards", JSONArray().put(JSONObject().put("id", "$boardId:external:reply").put("title", "训练反馈").put("text", "继续观察训练感受").put("span", 6)))
            val submitted = mcp(4, "tools/call", JSONObject().put("name", "submit_proposal").put("arguments", JSONObject()
                .put("proposal_id", "test-proposal").put("base_revision", afterFactRevision).put("proposal", proposal)))
            assertFalse(submitted.getJSONObject("result").optBoolean("isError"))
            board.edit(boardId) { cards -> cards.map { it.copy(isAnnotationMode = true) } }
            sync.sync()
            assertNull(db.collaborationDao().receipt("external:test-proposal", "apply"))
            board.edit(boardId) { cards -> cards.map { it.copy(isAnnotationMode = false) } }
            val proposalPolling = launch(Dispatchers.IO) { ForegroundSyncLoop(150) { sync.sync() }.run() }
            withTimeout(10000) { while (db.collaborationDao().receipt("external:test-proposal", "apply") == null) delay(150) }
            proposalPolling.cancelAndJoin()
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
            val decision = repo.requirements().single { it.mergeKey == "decision:decision-proposal" }
            repo.resolveDecision(decision, false)
            sync.sync()
            val feedback = mcp(14, "tools/call", JSONObject().put("name", "read_pending").put("arguments", JSONObject()))
            assertTrue(feedback.toString().contains("decision_resolved"))
            assertTrue(feedback.toString().contains("rejected"))
            repo.switchToBuiltin(); sync.sync()
            val req = Request.Builder().url("http://127.0.0.1:8765/mcp/").header("Authorization", "Bearer ${"a".repeat(40)}")
                .post("{}".toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { assertEquals(401, it.code) }
            assertEquals(control.epoch + 1, repo.control().epoch)
            assertEquals("builtin", repo.pending().single().ownerMode)
        } finally { db.close(); scope.cancel(); file.delete(); imageFile.delete() }
    }
}
