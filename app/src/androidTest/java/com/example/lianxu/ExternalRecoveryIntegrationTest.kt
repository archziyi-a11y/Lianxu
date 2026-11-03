package com.example.lianxu

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.prefs.SettingsManager
import com.example.lianxu.data.repository.BoardStore
import com.example.lianxu.data.repository.ExternalConnectionCoordinator
import com.example.lianxu.data.repository.ExternalRepository
import com.example.lianxu.data.repository.ExternalSync
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a fresh isolated local bridge on 127.0.0.1:8765 using test fixture tokens. */
@RunWith(AndroidJUnit4::class)
class ExternalRecoveryIntegrationTest {
    @Test fun explicitRetryRepairsEpochDriftAndKeepsPendingProposal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(context.cacheDir, "recovery-test-${UUID.randomUUID()}.preferences_pb")
        val settings = SettingsManager(context, PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        val client = OkHttpClient()
        fun mcp(id: Int, name: String, args: JSONObject): JSONObject {
            val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "tools/call")
                .put("params", JSONObject().put("name", name).put("arguments", args))
            val request = Request.Builder().url("http://127.0.0.1:8765/mcp/")
                .header("Authorization", "Bearer ${"a".repeat(40)}").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            return client.newCall(request).execute().use { response -> assertEquals(200, response.code); JSONObject(response.body.string()) }
        }
        try {
            settings.saveBridgeCredentials("http://127.0.0.1:8765", "o".repeat(40), "a".repeat(40))
            val repo = ExternalRepository(db)
            repo.beginHandoff("Grok Bot"); val initial = repo.beginHandoff("Grok Bot")
            val boardId = BoardStore(db).open(emptyList())
            val sync = ExternalSync(db, settings, context)
            sync.sync()
            val handoff = mcp(1, "read_handoff", JSONObject())
            val revision = JSONObject(handoff.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")).getLong("revision")
            assertFalse(mcp(2, "accept_handoff", JSONObject().put("snapshot_revision", revision)).getJSONObject("result").optBoolean("isError"))
            sync.sync(); assertTrue(repo.control().handoffAccepted)
            val cardId = "$boardId:external:recovery"
            val proposal = JSONObject().put("boardId", boardId).put("boardRevision", db.collaborationDao().board(boardId)!!.revision)
                .put("inputIds", JSONArray()).put("cards", JSONArray().put(JSONObject().put("id", cardId).put("title", "已接续").put("span", 6).put("text", "隔离恢复测试")))
            val args = JSONObject().put("proposal_id", "recovery-proposal").put("base_revision", revision).put("proposal", proposal)
            assertFalse(mcp(3, "submit_proposal", args).getJSONObject("result").optBoolean("isError"))
            repeat(5) { repo.beginHandoff("Grok Bot") }
            assertEquals(7L, repo.control().epoch); assertEquals(2L, initial.epoch)
            try { sync.sync(); fail("strict switch must reject token reuse") } catch (_: IllegalStateException) { }
            val attempt = requireNotNull(ExternalConnectionCoordinator(db, settings).prepareRetry())
            assertTrue(attempt.resumeActive); assertEquals(7L, attempt.epoch)
            assertEquals(7L, repo.control().epoch)
            sync.sync(attempt)
            assertTrue(repo.control().handoffAccepted)
            assertNotNull(db.collaborationDao().receipt("external:recovery-proposal", "apply"))
            assertTrue(db.collaborationDao().cards(boardId).any { it.id == cardId })
            ExternalSync.withConnectionLock { repo.switchToBuiltin() }
            try { ExternalConnectionCoordinator(db, settings).configure("http://127.0.0.1:8765", "o".repeat(40), "a".repeat(40), "Grok Bot"); fail("old token after offline builtin") } catch (_: IllegalArgumentException) { }
        } finally { scope.cancel(); db.close(); file.delete() }
    }
}
