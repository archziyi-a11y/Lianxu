package com.example.lianxu

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.repository.ExternalRepository
import com.example.lianxu.data.repository.BackupRepository
import com.example.lianxu.data.repository.AppRepository
import com.example.lianxu.data.repository.BoardStore
import com.example.lianxu.data.repository.NutritionBoardRepository
import com.example.lianxu.data.repository.ActiveInputRepository
import com.example.lianxu.data.model.*
import com.example.lianxu.data.api.*
import com.example.lianxu.agent.AgentClient
import com.example.lianxu.agent.AgentRunner
import com.example.lianxu.agent.AgentWorkspace
import com.example.lianxu.agent.ProposalApplier
import com.example.lianxu.archive.ArchiveRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalRepositoryTest {
    @Test fun builtinHandoffWaitsForDraftEdits() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val store=BoardStore(db);val board=store.open(listOf(DashboardCard(id="a",title="输入",span=CardSpan.LARGE)))
            val repo=ExternalRepository(db);repo.beginHandoff("agent")
            store.edit(board) { cards -> cards.map { it.copy(annotationText="原输入",isAnnotationMode=true) } }
            repo.queueSubmission(store.prepare(board));repo.switchToBuiltin()
            store.edit(board) { cards -> cards.map { it.copy(annotationText="编辑中",isAnnotationMode=true) } }
            val id=repo.pending().single().id
            assertNull(repo.prepareBuiltinPending(id))
            store.edit(board) { cards -> cards.map { it.copy(annotationText=null,isAnnotationMode=false,annotationAnchor=null) } }
            assertNotNull(repo.prepareBuiltinPending(id))
        } finally { db.close() }
    }
    @Test fun cancelledFollowUpCannotBeRepublishedAndUnsentTextSurvives() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val active=ActiveInputRepository(db);val followId=active.arrange("恢复怎么样？")
            val store=BoardStore(db);val boardId=store.open(emptyList());val repo=ExternalRepository(db)
            val control=repo.beginHandoff("a");repo.acceptHandoff("a",control.epoch)
            val cardId="$boardId:external:follow"
            val card=JSONObject().put("id",cardId).put("title","恢复").put("span",6).put("text","今天感觉如何？").put("followUpId",followId)
            val proposal=JSONObject().put("boardId",boardId).put("boardRevision",1).put("cards",JSONArray().put(card)).put("inputIds",JSONArray())
            repo.applyTextProposal("a",control.epoch,"follow1",proposal)
            store.edit(boardId) { cards -> cards.map { it.copy(annotationText="有好转",isAnnotationMode=true) } }
            active.arrange("恢复怎么样？",db.recordDao().knowledgeById(followId),"cancelled")
            val preserved=store.watchCards(boardId).first().single()
            assertEquals(followId,preserved.followUpId);assertTrue(preserved.title.contains("已取消跟进"));assertEquals("有好转",preserved.annotationText)
            assertFalse(db.collaborationDao().cards(boardId).any { it.id==cardId })
            store.edit(boardId) { emptyList() }
            proposal.put("boardRevision",db.collaborationDao().board(boardId)!!.revision)
            try { repo.applyTextProposal("a",control.epoch,"follow2",proposal);fail("cancelled follow-up") } catch (_: IllegalArgumentException) { }
        } finally { db.close() }
    }
    @Test fun nativeComponentsUseEvidenceAndRecordOnlyTheTappedSet() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val store = BoardStore(db); val boardId = store.open(emptyList())
            db.collaborationDao().putCalculation(CalculationEntity("calc", "fixture", "1", "{}", "[]", "{\"value\":42,\"unit\":\"kg\"}", 1))
            val repo=ExternalRepository(db); val control=repo.beginHandoff("a"); repo.acceptHandoff("a",control.epoch)
            val actions=JSONArray().put(JSONObject().put("kind","record_set").put("label","记下这一组"))
                .put(JSONObject().put("kind","start_timer").put("label","休息").put("seconds",90))
            val components=JSONArray().put(JSONObject().put("type","text").put("text","本次安排"))
                .put(JSONObject().put("type","metric").put("calculationId","calc").put("path","value").put("label","参考").put("unit","kg"))
                .put(JSONObject().put("type","input").put("kind","training").put("fields",JSONArray().put("name").put("repsMin").put("loadKg")).put("actions",actions))
            val cardId="$boardId:external:main"
            val proposal=JSONObject().put("boardId",boardId).put("boardRevision",1).put("inputIds",JSONArray())
                .put("order",JSONArray().put(cardId)).put("cards",JSONArray().put(JSONObject().put("id",cardId).put("title","主项").put("span",6)
                    .put("group","训练").put("emphasis","high").put("collapsed",false).put("components",components)))
            val wrongUnit=JSONObject(proposal.toString())
            wrongUnit.getJSONArray("cards").getJSONObject(0).getJSONArray("components").getJSONObject(1).put("unit","kcal")
            try { repo.applyTextProposal("a",control.epoch,"bad-unit",wrongUnit);fail("wrong unit") } catch (_: IllegalArgumentException) { }
            assertNull(db.collaborationDao().analysis("external:bad-unit:0:0"))
            assertEquals("applied",repo.applyTextProposal("a",control.epoch,"components",proposal))
            val card=store.watchCards(boardId).first().single()
            assertEquals("训练",card.groupTitle);assertEquals("high",card.emphasis)
            assertEquals(listOf("text","metric","input"),card.blocks.map { it.type })
            assertTrue(db.recordDao().allTrainings().isEmpty())
            store.edit(boardId) { cards->cards.map { it.copy(inputValues=mapOf("name" to "卧推","repsMin" to "8","loadKg" to "50")) } }
            val action=card.blocks.last().actions.first { it.kind=="record_set" }
            repo.recordSet(boardId,action)
            assertEquals(1,db.recordDao().allTrainings().size)
            try { repo.recordSet(boardId,action); fail("duplicate click") } catch (_: IllegalArgumentException) { }
            assertEquals(1,db.recordDao().allTrainings().size)
        } finally { db.close() }
    }
    @Test fun externalArchiveHonorsArchivedAndPriorityTopics() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val archive=ArchiveRepository(context,db);archive.methods.installBuiltins()
            db.measurementDao().insert(Measurement("w","weight",java.time.LocalDate.now().toString(),null,70f,"kg","user",null,1,1,1))
            archive.refresh()
            val topic=db.archiveDao().topics().first { it.methodId=="builtin.weight-series" && it.scopeKey=="accumulated" }
            val repo=ExternalRepository(db);val control=repo.beginHandoff("a");repo.acceptHandoff("a",control.epoch)
            val blocks=JSONArray().put(JSONObject().put("topicKey",topic.id).put("type","metric").put("path","latest"))
            val card=JSONObject().put("title","体重").put("span",6).put("group","趋势").put("emphasis","high").put("collapsed",true).put("blocks",blocks)
            val proposal=JSONObject().put("type","archive").put("cards",JSONArray().put(card)).put("inputIds",JSONArray())
            repo.applyArchiveProposal(context,"a",control.epoch,"archive1",proposal)
            val display=archive.cards("accumulated").first { topic.id in it.topics }
            assertTrue(display.card.collapsed);assertEquals("趋势",display.card.groupTitle)
            archive.toggleCollapsed(display.card.id)
            assertFalse(archive.cards("accumulated").first { topic.id in it.topics }.card.collapsed)
            archive.placement(topic.id,"archive")
            try { repo.applyArchiveProposal(context,"a",control.epoch,"archive2",proposal);fail("archived topic") } catch (_: IllegalArgumentException) { }
            assertTrue(archive.cards("accumulated").none { topic.id in it.topics })
        } finally { db.close() }
    }
    @Test fun switchingDuringBuiltinInferenceQueuesInputAndRejectsLateCommit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val store = BoardStore(db); val id = store.open(listOf(DashboardCard(id="note", title="输入", span=CardSpan.LARGE)))
            store.edit(id) { cards -> cards.map { it.copy(annotationText="先前输入", isAnnotationMode=true) } }
            val submission = store.prepare(id)
            val catalog = NutritionBoardRepository.create(context, db).catalog
            AgentWorkspace.open(context, db, submission, catalog).use { work ->
                val proposal = JSONObject().put("contractVersion", "2").put("turnId", submission.turn.id).put("taskId", submission.task.id)
                    .put("operations", JSONArray()).put("summary", "旧的回复")
                val applier = ProposalApplier(work, 0); val cards = applier.finish(proposal)
                val repo = ExternalRepository(db); repo.beginHandoff("new-agent")
                assertEquals(submission.turn.submissionId, repo.pending().single().submissionId)
                try { applier.commit(cards, proposal); fail("late response") } catch (_: IllegalStateException) { }
                assertTrue(db.collaborationDao().allAnalyses().none { it.summaryText == "旧的回复" })
            }
        } finally { db.close() }
    }
    @Test fun builtinFailedInputMovesOutAndReturnsWithoutDuplicateFacts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val board = BoardStore(db); val boardId = board.open(emptyList())
            val nutrition = NutritionBoardRepository.create(context, db); nutrition.refresh(boardId)
            board.edit(boardId) { cards -> cards.map { if (it.id.endsWith(":training-input")) it.copy(inputValues=mapOf("name" to "卧推", "sets" to "3"), annotationText="最后一组很吃力") else it } }
            val original = board.prepare(boardId)
            board.fail(original, "network failed")
            val repo = ExternalRepository(db); val external = repo.beginHandoff("agent")
            assertEquals(original.turn.submissionId, repo.pending().single().submissionId)
            assertEquals(1, db.recordDao().allTrainings().size)
            assertEquals(external.epoch, repo.pending().single().epoch)
            repo.switchToBuiltin()
            val retry = requireNotNull(repo.prepareBuiltinPending(repo.pending().single().id))
            assertEquals(original.turn.submissionId, retry.turn.submissionId)
            val fake = AgentClient { _, _ ->
                val proposal = JSONObject().put("contractVersion", "2").put("turnId", retry.turn.id).put("taskId", retry.task.id)
                    .put("operations", JSONArray()).put("summary", "已接续这次训练反馈。")
                ResponseMessage("assistant", null, listOf(ToolCall("finish", "function", ToolCallFunction("finish_proposal", proposal.toString()))))
            }
            AgentRunner(context, db, nutrition.catalog, fake).run(retry)
            assertEquals(1, db.recordDao().allTrainings().size)
            assertTrue(repo.pending().isEmpty())
            assertEquals("completed", db.collaborationDao().turn(original.turn.id)!!.status)
        } finally { db.close() }
    }
    @Test fun planChangesRequireDecisionAndRequirementsMergeOncePerProposal() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            db.goalPhaseDao().insert(GoalPhase("goal", "gain", "2026-01-01", null, null, 1, 1, 1))
            val repo = ExternalRepository(db); val control = repo.beginHandoff("a"); repo.acceptHandoff("a", control.epoch)
            val plan = JSONObject().put("type", "plan").put("planId", "p1").put("goalId", "goal").put("goalRevision", 1)
                .put("content", "训练安排").put("reason", "近期反馈").put("effectiveAt", 1).put("activate", true)
                .put("changesGoal", false).put("breaksConstraints", false).put("inputIds", JSONArray())
            assertEquals("applied", repo.applyPlanProposal("a", control.epoch, "one", plan))
            assertEquals("p1", repo.currentPlan()!!.id)
            val decision = JSONObject(plan.toString()).put("planId", "p2").put("changesGoal", true)
            assertEquals("decision", repo.applyPlanProposal("a", control.epoch, "two", decision))
            assertEquals("decision", repo.applyPlanProposal("a", control.epoch, "two", decision))
            assertEquals("p1", repo.currentPlan()!!.id)
            assertEquals("gain", db.goalPhaseDao().getCurrentGoalPhase()!!.kind)
            val decisionItem = repo.requirements().single()
            repo.resolveDecision(decisionItem, false)
            assertTrue(db.recordDao().knowledgeById("external-decision:${decisionItem.id}")!!.text.contains("拒绝"))
            val need = JSONObject().put("type", "requirement").put("mergeKey", "timer").put("scenario", "训练计时")
                .put("limitation", "现有控件不足").put("workaround", "暂用普通计时").put("expectedInteraction", "分组计时").put("inputIds", JSONArray())
            repo.applyRequirementProposal("a", control.epoch, "need1", need)
            repo.applyRequirementProposal("a", control.epoch, "need1", need)
            repo.applyRequirementProposal("a", control.epoch, "need2", need)
            assertEquals(2, repo.requirements().single { it.mergeKey == "timer" }.occurrences)
        } finally { db.close() }
    }
    @Test fun restoresVersionFiveAndSixLogicalBackups() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            AppRepository(source).recordTraining(TrainingSession("old", "{}", "strength", "training", null, "[]", null, "user", 1, 1, 1))
            val original = ByteArrayOutputStream().also { BackupRepository(context, source).export(it) }.toByteArray()
            for (version in listOf(5, 6)) {
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(original)).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
            }
            val data = JSONObject(entries.getValue("data.json").toString(Charsets.UTF_8))
            listOf("external_control", "external_plan_versions", "external_plan_state", "training_plan_links", "external_pending_inputs", "external_requirements").forEach { data.remove(it) }
            if (version == 5) listOf("archive_methods", "archive_topics", "archive_placements", "archive_presentations", "archive_jobs").forEach { data.remove(it) }
            val payload = data.toString().toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) }
            val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8)).put("schemaVersion", version).put("dataSha256", hash)
            entries["data.json"] = payload; entries["manifest.json"] = manifest.toString().toByteArray()
            val oldBackup = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } } }.toByteArray()
            BackupRepository(context, target).restore(ByteArrayInputStream(oldBackup))
            assertNotNull(target.recordDao().training("old"))
            assertEquals("builtin", ExternalRepository(target).control().mode)
            }
        } finally { source.close(); target.close() }
    }
    @Test fun backupPreservesPlanLinksButRevokesRestoredConnection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = ExternalRepository(source)
            val c = repo.beginHandoff("a"); repo.acceptHandoff("a", c.epoch)
            repo.publishPlan("a", c.epoch, ExternalPlanVersionEntity("plan", "body", "reason", 1, 1, "a"), true, 2)
            AppRepository(source).recordTraining(TrainingSession("t", "{}", "strength", "training", null, "[]", null, "user", 1, 1, 1))
            assertNull(source.externalDao().trainingLink("t"))
            repo.linkTrainingToPlan("t", "plan")
            repo.enqueue("i", "s", "input", 1)
            val bytes = ByteArrayOutputStream().also { BackupRepository(context, source).export(it) }.toByteArray()
            val targetRepo = ExternalRepository(target)
            repeat(3) { targetRepo.beginHandoff("old") }
            BackupRepository(context, target).restore(ByteArrayInputStream(bytes))
            assertEquals("plan", target.externalDao().trainingLink("t")!!.planId)
            assertEquals("builtin", targetRepo.control().mode)
            assertEquals(4L, targetRepo.control().epoch)
            assertEquals("builtin", targetRepo.pending().single().ownerMode)
        } finally { source.close(); target.close() }
    }
    @Test fun handoffRevokesOldWriterAndMovesOnlyPending() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val repo = ExternalRepository(db)
            val a = repo.beginHandoff("a")
            repo.acceptHandoff("a", a.epoch)
            repo.enqueue("i1", "s1", "raw", 1)
            assertTrue(repo.complete("a", a.epoch, "i1", "record1") {})
            assertFalse(repo.complete("a", a.epoch, "i1", "record1") { fail("replayed") })
            repo.enqueue("i2", "s2", "pending", 2)
            val b = repo.beginHandoff("b")
            try { repo.complete("a", a.epoch, "i2", "bad") {}; fail("revoked") } catch (_: IllegalStateException) {}
            assertEquals("b", repo.pending().single().ownerAgentId)
            try { repo.complete("b", b.epoch, "i2", "bad") {}; fail("handoff not accepted") } catch (_: IllegalStateException) {}
            repo.acceptHandoff("b", b.epoch)
            assertTrue(repo.complete("b", b.epoch, "i2", "record2") {})
            assertEquals("record1", db.externalDao().input("i1")!!.resultRefs)
        } finally { db.close() }
    }
    @Test fun rollbackRetryPlansAndRequirementMerge() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val repo = ExternalRepository(db); val c = repo.beginHandoff("a"); repo.acceptHandoff("a", c.epoch)
            repo.enqueue("i", "submission", "original", 1)
            try { repo.enqueue("other", "submission", "changed", 1); fail("conflict") } catch (_: IllegalArgumentException) {}
            try { repo.complete("a", c.epoch, "i", "x") { error("injected") }; fail("failure") } catch (_: IllegalStateException) {}
            assertEquals("pending", repo.pending().single().status)
            val p = ExternalPlanVersionEntity("p", "plan", "reason", 10, 1, "a")
            try { repo.publishPlan("a", c.epoch, p, true, 9); fail("future") } catch (_: IllegalArgumentException) {}
            assertTrue(repo.plans().isEmpty())
            repo.publishPlan("a", c.epoch, p, true, 10)
            assertEquals("p", repo.currentPlan()!!.id)
            val requirement = ExternalRequirementEntity("r", "same", "scenario", "limitation", "workaround", "interaction", 1, 1)
            repo.submitRequirement(requirement); repo.submitRequirement(requirement.copy(id = "r2", updatedAt = 2))
            assertEquals(2, repo.requirements().single().occurrences)
            repo.switchToBuiltin()
            try { repo.publishPlan("a", c.epoch, p, true, 10); fail("revoked") } catch (_: IllegalStateException) {}
        } finally { db.close() }
    }
}
