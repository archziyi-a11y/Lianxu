package com.example.lianxu

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.agent.*
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.model.*
import com.example.lianxu.data.repository.*
import com.example.lianxu.engine.DomainJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Exercises the real two-turn invalidation path without network or production data. */
@RunWith(AndroidJUnit4::class)
class InheritedAnalysisTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun operation(w: AgentWorkspace, id: String, kind: String, payload: JSONObject) =
        JSONObject().put("operationId", id).put("clientRef", id).put("kind", kind).put("payload", payload)
            .put("relatedAnnotationIds", JSONArray().put(w.annotations.getJSONObject(0).getString("annotationId")))
            .put("reason", "依据本轮明确训练输入")

    private fun proposal(w: AgentWorkspace, operations: JSONArray) = JSONObject()
        .put("contractVersion", "2").put("turnId", w.submission.turn.id).put("taskId", w.submission.task.id)
        .put("stageId", w.stageId).put("operations", operations).put("summary", "本轮记录已整理，未知负重保留未知")

    private fun training(w: AgentWorkspace, title: String) = operation(w, "training", "create_training",
        JSONObject().put("kind", "strength").put("title", title).put("exercises", JSONArray()).put("rawSummary", title))

    private suspend fun scenario(stableReply: Boolean = false, check: suspend (AppDatabase, NutritionBoardRepository, Submission, DashboardCard) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val store = BoardStore(db)
            val id = store.open(emptyList(), LocalDate.of(2026, 10, 28))
            val repo = NutritionBoardRepository.create(context, db)
            repo.refresh(id)
            store.edit(id) { cards -> cards.map { if (it.id.endsWith(":free-input")) it.copy(annotationText="卧推三组") else it } }
            val first = store.prepare(id)
            AgentWorkspace.open(context, db, first, repo.catalog).use { w ->
                w.stage(JSONArray().put(training(w, "卧推三组")))
                val analysis = operation(w, "first-analysis", "create_analysis", JSONObject()
                    .put("kind", "reply").put("summaryText", "本轮卧推已记录；负重未知。"))
                val block = BoardComponent("historical-text", "text", ComponentContent(
                    contentRef=ContentRef(EntityRef("analysis", "first-analysis", 1), "summaryText")))
                val blocks = JSONArray().put(JSONObject(DomainJson.encode(block)))
                val p = proposal(w, JSONArray().put(analysis))
                if (stableReply) p.put("blocks", blocks)
                else p.put("boardPatch", JSONObject().put("upsertCards", JSONArray().put(JSONObject()
                    .put("clientRef", "history-card").put("title", "上轮记录依据").put("span", 6).put("blocks", blocks))))
                val a = ProposalApplier(w)
                a.commit(a.finish(p), p)
            }
            val inherited = store.watchCards(id).first().single { c -> c.blocks.any { it.componentId == "historical-text" } }
            assertTrue(ComponentResolver(db).resolve(inherited).single().text.contains("卧推"))
            store.edit(id) { cards -> cards.map { if (it.id.endsWith(":free-input")) it.copy(annotationText="新增划船两组") else it } }
            check(db, repo, store.prepare(id), inherited)
        } finally { db.close() }
    }

    @Test fun unchangedInheritedAnalysisSurvivesNewFactsAsExplicitlyStale() = runBlocking {
        scenario { db, repo, second, inherited ->
            AgentWorkspace.open(context, db, second, repo.catalog).use { w ->
                w.stage(JSONArray().put(training(w, "划船两组")))
                val ref = inherited.blocks.single().content.contentRef!!.entityRef
                assertNotEquals("current", w.dao.analysis(ref.id)!!.freshness)
                val p = proposal(w, JSONArray()); val a = ProposalApplier(w)
                a.commit(a.finish(p), p)
                val kept = BoardStore(db).watchCards(second.task.boardId).first().single { it.id == inherited.id }
                assertEquals(inherited.blocks, kept.blocks)
                assertEquals("依据已更新，正在刷新建议", ComponentResolver(db).resolve(kept).single().text)
                assertEquals(2, db.recordDao().allTrainings().size)
                assertEquals("completed", db.collaborationDao().turn(second.turn.id)!!.status)
            }
        }
    }

    @Test fun newAndRewrittenCardsCannotPublishStaleAnalysis() = runBlocking {
        scenario { db, repo, second, inherited ->
            for (rewrite in listOf(false, true)) {
                AgentWorkspace.open(context, db, second, repo.catalog).use { w ->
                    w.stage(JSONArray().put(training(w, "划船两组")))
                    val card = JSONObject().put("title", "新发布的分析").put("span", 6)
                        .put("blocks", JSONArray(inherited.blocks.map { JSONObject(DomainJson.encode(it)) }))
                    if (rewrite) card.put("cardId", inherited.id).put("revision", inherited.revision)
                    else card.put("clientRef", "new-card")
                    val p = proposal(w, JSONArray()).put("boardPatch", JSONObject().put("upsertCards", JSONArray().put(card)))
                    val error = runCatching { ProposalApplier(w).finish(p) }.exceptionOrNull()
                    assertNotNull("New/upserted stale text must be rejected", error)
                    assertTrue(error!!.message.orEmpty().contains("VERSION_CONFLICT: analysis"))
                    assertEquals(1, db.recordDao().allTrainings().size)
                    assertEquals("running", db.collaborationDao().turn(second.turn.id)!!.status)
                }
            }
        }
    }

    @Test fun inheritedAnalysisStillRequiresMatchingRevision() = runBlocking {
        scenario { db, repo, second, inherited ->
            val ref = inherited.blocks.single().content.contentRef!!.entityRef
            val old = db.collaborationDao().analysis(ref.id)!!
            db.collaborationDao().putAnalysis(old.copy(revision=old.revision+1, freshness="stale"))
            AgentWorkspace.open(context, db, second, repo.catalog).use { w ->
                val error = runCatching { ProposalApplier(w).finish(proposal(w, JSONArray())) }.exceptionOrNull()
                assertNotNull("Historical display does not waive revision checks", error)
                assertTrue(error!!.message.orEmpty().contains("VERSION_CONFLICT: analysis"))
                assertEquals(1, db.recordDao().allTrainings().size)
            }
        }
    }

    @Test fun defaultReplyBlocksReplaceOneStableCardAcrossRounds() = runBlocking {
        scenario(stableReply=true) { db, repo, second, firstReply ->
            assertEquals("本轮建议", firstReply.title)
            AgentWorkspace.open(context, db, second, repo.catalog).use { w ->
                w.stage(JSONArray().put(training(w, "划船两组")))
                val analysis = operation(w, "second-analysis", "create_analysis", JSONObject()
                    .put("kind", "reply").put("summaryText", "新增划船两组，卧推原记录保留。"))
                val block = BoardComponent("latest-text", "text", ComponentContent(
                    contentRef=ContentRef(EntityRef("analysis", "second-analysis", 1), "summaryText")))
                val p = proposal(w, JSONArray().put(analysis))
                    .put("blocks", JSONArray().put(JSONObject(DomainJson.encode(block))))
                val a = ProposalApplier(w); a.commit(a.finish(p), p)
                val cards = BoardStore(db).watchCards(second.task.boardId).first()
                val reply = cards.single { it.title == "本轮建议" }
                assertEquals(firstReply.id, reply.id)
                assertEquals(reply.id, cards.first().id)
                assertEquals("新增划船两组，卧推原记录保留。", ComponentResolver(db).resolve(reply).single().text)
                assertEquals(setOf("卧推三组", "划船两组"), db.recordDao().allTrainings().map { it.title }.toSet())
                assertEquals(2, db.collaborationDao().turns(second.task.boardId).count { it.status == "completed" })
            }
        }
    }
}
