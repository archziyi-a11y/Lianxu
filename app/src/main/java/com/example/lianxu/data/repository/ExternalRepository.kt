package com.example.lianxu.data.repository

import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import org.json.JSONArray
import org.json.JSONObject
import com.example.lianxu.data.model.*
import com.example.lianxu.engine.DomainJson

/** Local coordination only. Authentication and network transport must enforce the same epoch. */
class ExternalRepository(private val db: AppDatabase) {
    private val dao = db.externalDao()
    private suspend fun state() = dao.control() ?: ExternalControlEntity(mode = "builtin", agentId = null, epoch = 0, handoffAccepted = false)
    private fun requireWriter(control: ExternalControlEntity, agentId: String, epoch: Long) {
        check(control.mode == "external" && control.agentId == agentId && control.epoch == epoch && control.handoffAccepted) { "EXTERNAL_WRITER_REVOKED" }
    }
    suspend fun control() = db.withTransaction { state() }
    suspend fun switchToBuiltin() = switch("builtin", null)
    suspend fun beginHandoff(agentId: String): ExternalControlEntity {
        require(agentId.isNotBlank())
        return switch("external", agentId)
    }
    private suspend fun switch(mode: String, agentId: String?) = db.withTransaction {
        val previous = state()
        check(previous.epoch < Long.MAX_VALUE)
        val next = ExternalControlEntity(mode = mode, agentId = agentId, epoch = previous.epoch + 1, handoffAccepted = false)
        dao.putControl(next)
        dao.transferPending(mode, agentId, next.epoch)
        next
    }
    /** Caller must obtain and review handoff data before accepting. Acceptance is epoch-bound. */
    suspend fun acceptHandoff(agentId: String, epoch: Long) = db.withTransaction {
        val control = state()
        check(control.mode == "external" && control.agentId == agentId && control.epoch == epoch)
        dao.putControl(control.copy(handoffAccepted = true))
    }
    suspend fun enqueue(id: String, submissionId: String, payload: String, createdAt: Long): ExternalPendingInputEntity = db.withTransaction {
        require(id.isNotBlank() && submissionId.isNotBlank())
        val old = dao.inputBySubmission(submissionId)
        if (old != null) {
            require(old.payload == payload) { "SUBMISSION_CONFLICT" }
            old
        } else {
            val control = state()
            ExternalPendingInputEntity(id, submissionId, payload, control.mode, control.agentId, control.epoch, "pending", null, createdAt).also { dao.insertInput(it) }
        }
    }
    suspend fun pending() = dao.pendingInputs()
    /** Freeze once and save explicit controls with the pending raw material atomically. */
    suspend fun queueSubmission(submission: Submission) = db.withTransaction {
        val control = state()
        check(control.mode == "external") { "当前不是外部智能体模式" }
        val input = requireNotNull(db.collaborationDao().input(submission.turn.inputBundleId))
        val automaticIds = ActiveInputRepository(db).consume(submission)
        val payload = JSONObject().put("turnId", submission.turn.id).put("submissionId", input.submissionId)
            .put("dateContext", input.dateContext).put("rawText", input.rawText)
            .put("annotations", JSONArray(input.annotationsJson)).put("automaticAnnotationIds", JSONArray(automaticIds.toList())).toString()
        enqueue(input.id, input.submissionId, payload, input.createdAt)
        BoardStore(db).complete(submission, submission.cards)
    }
    /** apply runs within the same Room transaction as the receipt. It must use this database only. */
    suspend fun complete(agentId: String, epoch: Long, inputId: String, resultRefs: String, apply: suspend () -> Unit): Boolean = db.withTransaction {
        requireWriter(state(), agentId, epoch)
        val input = requireNotNull(dao.input(inputId))
        if (input.status == "completed") {
            require(input.resultRefs == resultRefs) { "RESULT_CONFLICT" }
            false
        } else {
            check(input.status == "pending" && input.ownerMode == "external" && input.ownerAgentId == agentId && input.epoch == epoch)
            apply()
            dao.updateInput(input.copy(status = "completed", resultRefs = resultRefs))
            true
        }
    }
    suspend fun publishPlan(agentId: String, epoch: Long, plan: ExternalPlanVersionEntity, activate: Boolean, now: Long) = db.withTransaction {
        requireWriter(state(), agentId, epoch)
        require(plan.id.isNotBlank() && plan.content.isNotBlank() && plan.reason.isNotBlank() && plan.authorAgentId == agentId)
        val old = dao.plan(plan.id)
        if (old == null) dao.insertPlan(plan) else require(old == plan) { "PLAN_CONFLICT" }
        if (activate) {
            require(plan.effectiveAt <= now) { "PLAN_NOT_EFFECTIVE" }
            dao.putCurrentPlan(ExternalPlanStateEntity(planId = plan.id))
        }
    }
    suspend fun plans() = dao.plans()
    suspend fun currentPlan() = dao.currentPlan()?.let { dao.plan(it.planId) }
    /** Only link a version explicitly identified as the one executed; unknown stays unlinked. */
    suspend fun linkTrainingToPlan(trainingId: String, planId: String) = db.withTransaction {
        requireNotNull(db.recordDao().training(trainingId))
        requireNotNull(dao.plan(planId))
        val old = dao.trainingLink(trainingId)
        if (old == null) dao.insertTrainingLink(TrainingPlanLinkEntity(trainingId, planId))
        else check(old.planId == planId) { "TRAINING_ALREADY_LINKED" }
    }
    suspend fun submitRequirement(value: ExternalRequirementEntity) = db.withTransaction {
        require(value.mergeKey.isNotBlank() && value.scenario.isNotBlank() && value.limitation.isNotBlank() && value.expectedInteraction.isNotBlank())
        val old = dao.requirement(value.mergeKey)
        if (old == null) dao.insertRequirement(value.copy(occurrences = 1))
        else dao.updateRequirement(old.copy(occurrences = old.occurrences + 1, updatedAt = value.updatedAt))
    }
    suspend fun requirements() = dao.requirements()

    /** Restricted text cards only; arbitrary code, records and unsupported actions are rejected. */
    suspend fun applyTextProposal(agentId: String, epoch: Long, proposalId: String, proposal: JSONObject): String = db.withTransaction {
        requireWriter(state(), agentId, epoch)
        val collaboration = db.collaborationDao()
        require(proposalId.isNotBlank() && proposalId.length <= 120)
        if (collaboration.receipt("external:$proposalId", "apply") != null) return@withTransaction "applied"
        require(proposal.keys().asSequence().toSet() == setOf("boardId", "boardRevision", "cards", "inputIds")) { "不支持的提议内容" }
        val boardId = proposal.getString("boardId")
        val board = requireNotNull(collaboration.board(boardId))
        require(board.status == "active" && board.revision == proposal.getInt("boardRevision")) { "看板已经变化" }
        val draft = requireNotNull(collaboration.draft(boardId))
        val store = BoardStore(db)
        val current = store.decode(draft.cardsJson)
        if (current.any { it.isAnnotationMode || ActiveInputRepository.hasInput(it) }) return@withTransaction "deferred"
        val specs = proposal.getJSONArray("cards")
        require(specs.length() in 1..20)
        val now = System.currentTimeMillis()
        val added = mutableListOf<DashboardCard>()
        val ids = mutableSetOf<String>()
        for (i in 0 until specs.length()) {
            val spec = specs.getJSONObject(i)
            require(spec.keys().asSequence().toSet() == setOf("id", "title", "text", "span"))
            val cardId = spec.getString("id")
            val title = spec.getString("title")
            val text = spec.getString("text")
            require(cardId.startsWith("$boardId:external:") && cardId.length <= 240 && title.isNotBlank() && title.length <= 120 && text.isNotBlank() && text.length <= 4000 && ids.add(cardId))
            val existing = current.firstOrNull { it.id == cardId }
            require(existing == null || existing.managedBy == "external") { "不能覆盖用户卡片" }
            val span = when (spec.getInt("span")) { 2 -> CardSpan.SMALL; 3 -> CardSpan.MEDIUM; 6 -> CardSpan.LARGE; else -> error("不支持的宽度") }
            val analysisId = "external:$proposalId:$i"
            require(collaboration.analysis(analysisId) == null)
            collaboration.putAnalysis(AnalysisEntity(analysisId, 1, "reply", "", "", "[]", text, "[]", "[]", "current", now, now))
            val ref = EntityRef("analysis", analysisId, 1)
            val component = BoardComponent("external-text", "text", ComponentContent(contentRef = ContentRef(ref, "summaryText")))
            added.add(DashboardCard(id=cardId, title=title, span=span, revision=(existing?.revision ?: 0)+1, blocks=listOf(component), managedBy="external"))
        }
        val inputIds = proposal.getJSONArray("inputIds")
        require((0 until inputIds.length()).map { inputIds.getString(it) }.distinct().size == inputIds.length())
        for (i in 0 until inputIds.length()) complete(agentId, epoch, inputIds.getString(i), proposalId) {}
        val next = current.filterNot { it.id in ids } + added
        collaboration.removeCards(boardId)
        collaboration.putCards(next.map { Card(it.id, boardId, DomainJson.encode(it)) })
        collaboration.putBoard(board.copy(revision=board.revision+1, updatedAt=now))
        collaboration.putDraft(draft.copy(revision=draft.revision+1, cardsJson=store.encode(next), updatedAt=now))
        collaboration.putReceipt(MutationReceiptEntity(java.util.UUID.randomUUID().toString(), "external:$proposalId", "apply", "[]", null, null, now))
        "applied"
    }
}
