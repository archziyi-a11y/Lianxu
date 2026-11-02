package com.example.lianxu.data.repository

import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import org.json.JSONArray
import org.json.JSONObject
import com.example.lianxu.data.model.*
import com.example.lianxu.engine.DomainJson
import android.content.Context
import com.example.lianxu.archive.ArchiveComposer
import com.example.lianxu.archive.ArchiveRepository
import com.example.lianxu.agent.AgentClient
import com.example.lianxu.agent.AgentWorkspace
import com.example.lianxu.agent.refId
import com.example.lianxu.engine.RecordedExercise
import com.example.lianxu.engine.RecordedSet

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
        if (mode == "external") transferBuiltinInputs()
        next
    }
    private suspend fun transferBuiltinInputs() {
        val collaboration = db.collaborationDao()
        for (task in collaboration.outstandingTasks()) {
            if (dao.inputBySubmission(task.submissionId) != null) continue
            val turn = collaboration.turn(task.turnId) ?: continue
            val input = collaboration.input(turn.inputBundleId) ?: continue
            val board = collaboration.board(task.boardId) ?: continue
            val snapshot = turn.beforeBoardSnapshotId?.let { collaboration.snapshot(it) }
            val cards = collaboration.cards(board.id).map { DomainJson.decode<DashboardCard>(it.data) }
            val submission = Submission(turn, task, cards, board.revision)
            val ids = ActiveInputRepository(db).consume(submission)
            val payload = JSONObject().put("turnId", turn.id).put("submissionId", input.submissionId)
                .put("dateContext", input.dateContext).put("rawText", input.rawText)
                .put("annotations", JSONArray(input.annotationsJson)).put("automaticAnnotationIds", JSONArray(ids.toList())).toString()
            enqueue(input.id, input.submissionId, payload, input.createdAt)
            if (snapshot != null && snapshot.boardRevision == board.revision) {
                collaboration.putTask(task.copy(status = "running", updatedAt = System.currentTimeMillis()))
                BoardStore(db).complete(submission, cards)
            } else {
                collaboration.putTask(task.copy(status = "failed", lastError = "输入已转交，原草稿保留", updatedAt = System.currentTimeMillis()))
            }
        }
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
        if (dao.inputBySubmission(input.submissionId) != null) {
            BoardStore(db).complete(submission, submission.cards)
            return@withTransaction
        }
        val automaticIds = ActiveInputRepository(db).consume(submission)
        val payload = JSONObject().put("turnId", submission.turn.id).put("submissionId", input.submissionId)
            .put("dateContext", input.dateContext).put("rawText", input.rawText)
            .put("annotations", JSONArray(input.annotationsJson)).put("automaticAnnotationIds", JSONArray(automaticIds.toList())).toString()
        enqueue(input.id, input.submissionId, payload, input.createdAt)
        BoardStore(db).complete(submission, submission.cards)
    }
    /** Resume the same input and submission, retaining deterministic fact IDs. */
    suspend fun prepareBuiltinPending(inputId: String): Submission? = db.withTransaction {
        val control = state()
        check(control.mode == "builtin")
        val pending = requireNotNull(dao.input(inputId))
        if (pending.status != "pending" || pending.ownerMode != "builtin" || pending.epoch != control.epoch) return@withTransaction null
        val input = requireNotNull(db.collaborationDao().input(inputId))
        val collaboration = db.collaborationDao()
        val turn = requireNotNull(collaboration.turn(input.turnId))
        val oldTask = requireNotNull(collaboration.task(turn.taskId))
        if (oldTask.status in listOf("running", "staged", "applying", "queued")) return@withTransaction null
        val board = requireNotNull(collaboration.board(oldTask.boardId))
        val draft = requireNotNull(collaboration.draft(board.id))
        val cards = BoardStore(db).decode(draft.cardsJson)
        if (cards.any { it.isAnnotationMode || ActiveInputRepository.hasInput(it) }) return@withTransaction null
        collaboration.removeReceipt(turn.submissionId, "apply-board")
        val task = oldTask.copy(status = "running", draftRevision = draft.revision, lastError = null, updatedAt = System.currentTimeMillis())
        collaboration.putTask(task)
        collaboration.putTurn(turn.copy(status = "running", completedAt = null))
        Submission(turn.copy(status = "running", completedAt = null), task, cards, board.revision)
    }
    suspend fun completeBuiltinPending(submissionId: String, epoch: Long) {
        val control = state()
        check(control.mode == "builtin" && control.epoch == epoch)
        dao.inputBySubmission(submissionId)?.takeIf { it.status == "pending" && it.ownerMode == "builtin" && it.epoch == epoch }?.let {
            dao.updateInput(it.copy(status = "completed", resultRefs = "builtin:${submissionId}"))
        }
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
        if (old == null) dao.insertPlan(plan) else require(old.copy(createdAt = plan.createdAt) == plan) { "PLAN_CONFLICT" }
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
    /** Apply only validated facts tied to one original pending user input. */
    suspend fun applyFactProposal(context: Context, agentId: String, epoch: Long, proposalId: String, proposal: JSONObject) = db.withTransaction {
        requireWriter(state(), agentId, epoch)
        require(proposalId.isNotBlank() && proposalId.length <= 120)
        val collaboration = db.collaborationDao()
        if (collaboration.receipt("external:$proposalId", "apply") != null) return@withTransaction
        require(proposal.keys().asSequence().toSet() == setOf("type", "inputIds", "operations") && proposal.getString("type") == "facts")
        val inputIds = proposal.getJSONArray("inputIds"); require(inputIds.length() == 1)
        val inputId = inputIds.getString(0)
        val pending = requireNotNull(dao.input(inputId))
        require(pending.status == "pending" && pending.ownerMode == "external" && pending.ownerAgentId == agentId && pending.epoch == epoch)
        val input = requireNotNull(collaboration.input(inputId))
        val turn = requireNotNull(collaboration.turn(input.turnId))
        val task = requireNotNull(collaboration.task(turn.taskId))
        val board = requireNotNull(collaboration.board(task.boardId))
        val draft = requireNotNull(collaboration.draft(board.id))
        val operations = proposal.getJSONArray("operations")
        require(operations.length() in 1..20)
        for (i in 0 until operations.length()) require(operations.getJSONObject(i).getString("kind") in setOf("create_training", "create_intake", "create_measurement"))
        val submission = Submission(turn, task, BoardStore(db).decode(draft.cardsJson), board.revision)
        val catalog = NutritionBoardRepository.create(context, db).catalog
        AgentWorkspace.open(context, db, submission, catalog).use { work ->
            work.applyOperations(operations)
            for (i in 0 until operations.length()) {
                val op = operations.getJSONObject(i)
                val ref = op.optString("clientRef").takeIf { it.isNotBlank() }
                val id = ref?.let { work.allocated(it) } ?: refId("${turn.submissionId}:${op.getString("operationId")}")
                when (op.getString("kind")) {
                    "create_training" -> AppRepository(db).recordTraining(requireNotNull(work.overlay.recordDao().training(id)))
                    "create_intake" -> AppRepository(db).recordIntake(requireNotNull(work.overlay.recordDao().intake(id)))
                    "create_measurement" -> {
                        require(db.measurementDao().byId(id) == null)
                        db.measurementDao().insert(requireNotNull(work.overlay.measurementDao().byId(id)))
                        invalidateDerived(db)
                    }
                }
            }
        }
        complete(agentId, epoch, inputId, proposalId) {}
        collaboration.putReceipt(MutationReceiptEntity(java.util.UUID.randomUUID().toString(), "external:$proposalId", "apply", "[]", null, null, System.currentTimeMillis()))
    }
    /** A user tap records exactly one set from the current form, never from suggested values. */
    suspend fun recordSet(boardId: String, action: CardAction) = db.withTransaction {
        require(action.kind == "record_set" && action.targetRef.type == "local")
        val collaboration = db.collaborationDao()
        val board = requireNotNull(collaboration.board(boardId)); require(board.status == "active")
        val draft = requireNotNull(collaboration.draft(boardId))
        val store = BoardStore(db)
        val card = store.decode(draft.cardsJson).single { it.id == action.targetRef.id }
        require(card.blocks.flatMap { it.actions }.any { it == action })
        ActiveInputRepository.validateValues(card)
        val values = card.inputValues
        val name = values["name"]?.trim().orEmpty()
        require(name.isNotBlank() && name.length <= 80) { "请先填写实际动作名称" }
        require("sets" !in values && "repsMax" !in values)
        val reps = values["repsMin"]?.toInt()
        val load = values["loadKg"]?.toDouble()
        val now = System.currentTimeMillis()
        val control = state()
        val submissionId = java.util.UUID.randomUUID().toString()
        val turnId = java.util.UUID.randomUUID().toString()
        val inputId = java.util.UUID.randomUUID().toString()
        val taskId = java.util.UUID.randomUUID().toString()
        val annotationId = java.util.UUID.randomUUID().toString()
        val recordId = ActiveInputRepository.id("$submissionId:input:$annotationId")
        val exercise = RecordedExercise(entryId=java.util.UUID.randomUUID().toString(), name=name,
            equipment=values["equipment"], sets=listOf(RecordedSet(setId=java.util.UUID.randomUUID().toString(), reps=reps, loadKg=load)))
        val origin = JSONObject().put("kind", "user").put("cardId", card.id)
        if (control.mode == "external") origin.put("inputBundleId", inputId).put("turnId", turnId).put("annotationIds", JSONArray().put(annotationId))
        AppRepository(db).recordTraining(TrainingSession(recordId, board.dateContext, "strength", name, null,
            DomainJson.encode(listOf(exercise)), null, origin.toString(), 1, now, now))
        action.value?.let { planId ->
            require(dao.currentPlan()?.planId == planId) { "计划已变化，请先刷新" }
            linkTrainingToPlan(recordId, planId)
        }
        if (control.mode == "external") {
            val inputBlock = requireNotNull(card.blocks.firstOrNull { it.type == "input" })
            val part = JSONObject().put("type", "structured").put("inputKind", "training").put("values", JSONObject(values))
                .put("fields", JSONArray(inputBlock.content.inputFields))
            val annotation = JSONObject().put("annotationId", annotationId)
                .put("target", JSONObject().put("boardId", boardId).put("boardRevision", board.revision).put("cardId", card.id).put("cardRevision", card.revision))
                .put("targetSnapshot", JSONObject().put("title", card.title).put("readableContent", "用户刚记录一组").put("components", JSONArray()))
                .put("inputParts", JSONArray().put(part))
            val annotations = JSONArray().put(annotation)
            collaboration.putInput(InputBundleEntity(inputId, turnId, submissionId, board.dateContext, "", annotations.toString(), now))
            collaboration.putTask(TaskEntity(taskId, turnId, submissionId, boardId, draft.revision, "completed", null, now, now))
            collaboration.putTurn(Turn(turnId, submissionId, "daily", boardId, board.dateContext,
                (collaboration.turns(boardId).maxOfOrNull { it.sequence } ?: 0) + 1, inputId, "[]", "completed", taskId, "[]", "[]", null, null, now, now))
            val payload = JSONObject().put("eventType", "recorded_set").put("recordId", recordId).put("turnId", turnId)
                .put("submissionId", submissionId).put("dateContext", board.dateContext).put("rawText", "")
                .put("annotations", annotations).put("automaticAnnotationIds", JSONArray().put(annotationId)).toString()
            enqueue(inputId, submissionId, payload, now)
        }
        store.edit(boardId) { cards -> cards.map { if (it.id == card.id) it.copy(inputValues=emptyMap()) else it } }
    }
    suspend fun applyArchiveProposal(context: Context, agentId: String, epoch: Long, proposalId: String, proposal: JSONObject) = db.withTransaction {
        requireWriter(state(), agentId, epoch)
        require(proposalId.isNotBlank() && proposalId.length <= 120)
        val collaboration = db.collaborationDao()
        if (collaboration.receipt("external:$proposalId", "apply") != null) return@withTransaction
        require(proposal.keys().asSequence().toSet() == setOf("type", "cards", "inputIds") && proposal.getString("type") == "archive")
        val archive = ArchiveRepository(context, db)
        val job = requireNotNull(db.archiveDao().job())
        ArchiveComposer(archive, AgentClient { _, _ -> error("外部档案不调用内置助手") })
            .publish(JSONObject().put("cards", proposal.getJSONArray("cards")), job.desiredFingerprint, archive.factFingerprint(), externalEpoch = epoch)
        val ids = proposal.getJSONArray("inputIds")
        require((0 until ids.length()).map { ids.getString(it) }.distinct().size == ids.length())
        for (i in 0 until ids.length()) complete(agentId, epoch, ids.getString(i), proposalId) {}
        collaboration.putReceipt(MutationReceiptEntity(java.util.UUID.randomUUID().toString(), "external:$proposalId", "apply", "[]", null, null, System.currentTimeMillis()))
    }

    suspend fun applyRequirementProposal(agentId: String, epoch: Long, proposalId: String, proposal: JSONObject) = db.withTransaction {
        requireWriter(state(), agentId, epoch)
        require(proposalId.isNotBlank() && proposalId.length <= 120)
        val collaboration = db.collaborationDao()
        if (collaboration.receipt("external:$proposalId", "apply") != null) return@withTransaction
        require(proposal.keys().asSequence().toSet() == setOf("type", "mergeKey", "scenario", "limitation", "workaround", "expectedInteraction", "inputIds"))
        require(proposal.getString("type") == "requirement")
        val fields = listOf("mergeKey", "scenario", "limitation", "workaround", "expectedInteraction").associateWith { proposal.getString(it) }
        require(fields.values.all { it.length <= 4000 })
        submitRequirement(ExternalRequirementEntity("external:$proposalId", fields.getValue("mergeKey"), fields.getValue("scenario"), fields.getValue("limitation"), fields.getValue("workaround"), fields.getValue("expectedInteraction"), 1, System.currentTimeMillis()))
        val ids = proposal.getJSONArray("inputIds")
        require((0 until ids.length()).map { ids.getString(it) }.distinct().size == ids.length())
        for (i in 0 until ids.length()) complete(agentId, epoch, ids.getString(i), proposalId) {}
        collaboration.putReceipt(MutationReceiptEntity(java.util.UUID.randomUUID().toString(), "external:$proposalId", "apply", "[]", null, null, System.currentTimeMillis()))
    }

    suspend fun applyPlanProposal(agentId: String, epoch: Long, proposalId: String, proposal: JSONObject): String = db.withTransaction {
        requireWriter(state(), agentId, epoch)
        require(proposalId.isNotBlank() && proposalId.length <= 120)
        val collaboration = db.collaborationDao()
        collaboration.receipt("external:$proposalId", "apply")?.let { return@withTransaction if (it.resultRefs == "decision") "decision" else "applied" }
        require(proposal.keys().asSequence().toSet() == setOf("type", "planId", "goalId", "goalRevision", "content", "reason", "effectiveAt", "activate", "inputIds", "changesGoal", "breaksConstraints"))
        require(proposal.getString("type") == "plan")
        val goal = requireNotNull(db.goalPhaseDao().getCurrentGoalPhase()) { "请先设置目标" }
        require(goal.id == proposal.getString("goalId") && goal.revision == proposal.getInt("goalRevision")) { "目标已变化，需要重新决策" }
        val content = proposal.getString("content")
        val reason = proposal.getString("reason")
        require(content.isNotBlank() && content.length <= 16000 && reason.isNotBlank() && reason.length <= 4000)
        if (proposal.getBoolean("changesGoal") || proposal.getBoolean("breaksConstraints")) {
            val kind = if (proposal.getBoolean("changesGoal")) "目标变更" else "超出约束"
            submitRequirement(ExternalRequirementEntity("decision:$proposalId", "decision:$proposalId", "需要决定：$kind", reason,
                "请在档案中明确修改目标或约束，或拒绝这项建议。", content, 1, System.currentTimeMillis()))
            collaboration.putReceipt(MutationReceiptEntity(java.util.UUID.randomUUID().toString(), "external:$proposalId", "apply", "decision", null, null, System.currentTimeMillis()))
            return@withTransaction "decision"
        }
        val refs = JSONArray(db.recordDao().allKnowledge().filter { it.status == "current" }.map { JSONObject().put("id", it.id).put("revision", it.revision) })
        val document = JSONObject().put("goalId", goal.id).put("goalRevision", goal.revision).put("knowledgeRefs", refs).put("plan", content).toString()
        val version = ExternalPlanVersionEntity(proposal.getString("planId"), document, reason, proposal.getLong("effectiveAt"), System.currentTimeMillis(), agentId)
        publishPlan(agentId, epoch, version, proposal.getBoolean("activate"), System.currentTimeMillis())
        val ids = proposal.getJSONArray("inputIds")
        require((0 until ids.length()).map { ids.getString(it) }.distinct().size == ids.length())
        for (i in 0 until ids.length()) complete(agentId, epoch, ids.getString(i), proposalId) {}
        collaboration.putReceipt(MutationReceiptEntity(java.util.UUID.randomUUID().toString(), "external:$proposalId", "apply", "[]", null, null, System.currentTimeMillis()))
        "applied"
    }
    suspend fun resolveDecision(requirement: ExternalRequirementEntity, accepted: Boolean) = db.withTransaction {
        require(requirement.mergeKey.startsWith("decision:"))
        val id = "external-decision:${requirement.id}"
        val existing = db.recordDao().knowledgeById(id)
        val decision = if (accepted) "accepted" else "rejected"
        if (existing != null && runCatching { JSONObject(existing.origin).optString("decision") }.getOrNull() == decision) return@withTransaction
        val now = System.currentTimeMillis()
        val revision = (existing?.revision ?: 0) + 1
        val text = (if (accepted) "用户同意此建议，目标和约束仍以资料中的当前设置为准：" else "用户拒绝：") + requirement.scenario + "；" + requirement.expectedInteraction
        db.recordDao().putKnowledge(KnowledgeNote(id, revision, "external_decision", text,
            JSONObject().put("kind", "user").put("decisionId", requirement.id).put("decision", decision).toString(), java.time.LocalDate.now().toString(), null, "current", null, existing?.createdAt ?: now, now))
        if (state().mode == "external") {
            val collaboration = db.collaborationDao()
            val board = collaboration.allBoards().firstOrNull { it.status == "active" }
                ?: requireNotNull(collaboration.board(BoardStore(db).open(emptyList())))
            val draft = requireNotNull(collaboration.draft(board.id))
            val seed = "decision:${requirement.id}:$revision"
            val submissionId = refId(seed); val inputId = refId("$seed:input"); val turnId = refId("$seed:turn")
            val taskId = refId("$seed:task"); val annotationId = refId("$seed:annotation")
            val annotation = JSONObject().put("annotationId", annotationId)
                .put("target", JSONObject().put("boardId", board.id).put("boardRevision", board.revision).put("cardId", "decision").put("cardRevision", 1))
                .put("targetSnapshot", JSONObject().put("title", "用户决定").put("readableContent", text).put("components", JSONArray()))
                .put("inputParts", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            val annotations = JSONArray().put(annotation)
            collaboration.putInput(InputBundleEntity(inputId, turnId, submissionId, board.dateContext, text, annotations.toString(), now))
            collaboration.putTask(TaskEntity(taskId, turnId, submissionId, board.id, draft.revision, "completed", null, now, now))
            collaboration.putTurn(Turn(turnId, submissionId, "daily", board.id, board.dateContext,
                (collaboration.turns(board.id).maxOfOrNull { it.sequence } ?: 0) + 1, inputId, "[]", "completed", taskId, "[]", "[]", null, null, now, now))
            val payload = JSONObject().put("eventType", "decision_resolved").put("decisionId", requirement.id).put("decision", decision)
                .put("turnId", turnId).put("submissionId", submissionId).put("dateContext", board.dateContext)
                .put("rawText", text).put("annotations", annotations).put("automaticAnnotationIds", JSONArray()).toString()
            enqueue(inputId, submissionId, payload, now)
        }
    }

    /** Declarative native components only; all referenced data and actions are validated locally. */
    suspend fun applyTextProposal(agentId: String, epoch: Long, proposalId: String, proposal: JSONObject): String = db.withTransaction {
        requireWriter(state(), agentId, epoch)
        val collaboration = db.collaborationDao()
        require(proposalId.isNotBlank() && proposalId.length <= 120)
        if (collaboration.receipt("external:$proposalId", "apply") != null) return@withTransaction "applied"
        require(proposal.keys().asSequence().toSet().let { it.containsAll(setOf("boardId", "boardRevision", "cards", "inputIds")) && (it - setOf("boardId", "boardRevision", "cards", "inputIds", "removeIds", "order")).isEmpty() }) { "不支持的提议内容" }
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
            val fields = spec.keys().asSequence().toSet()
            require(fields.containsAll(setOf("id", "title", "span")) && (fields - setOf("id", "title", "text", "span", "components", "group", "emphasis", "collapsed", "followUpId")).isEmpty())
            val cardId = spec.getString("id")
            val title = spec.getString("title")
            require(cardId.startsWith("$boardId:external:") && cardId.length <= 240 && title.isNotBlank() && title.length <= 120 && ids.add(cardId))
            val existing = current.firstOrNull { it.id == cardId }
            require(existing == null || existing.managedBy == "external") { "不能覆盖用户卡片" }
            val span = when (spec.getInt("span")) { 2 -> CardSpan.SMALL; 3 -> CardSpan.MEDIUM; 6 -> CardSpan.LARGE; else -> throw IllegalArgumentException("不支持的宽度") }
            val group = spec.optString("group").takeIf { it.isNotBlank() }
            require(group == null || group.length <= 60)
            val followUpId = spec.optString("followUpId").takeIf { it.isNotBlank() }
            followUpId?.let { id ->
                val note = requireNotNull(db.recordDao().knowledgeById(id))
                require(note.kind == "follow_up" && note.status == "current" && note.deletedAt == null) { "跟进已取消或不存在" }
            }
            val emphasis = spec.optString("emphasis", "normal")
            require(emphasis in listOf("high", "normal", "low"))
            require(spec.has("text") xor spec.has("components"))
            val components = if (spec.has("text")) JSONArray().put(JSONObject().put("type", "text").put("text", spec.getString("text"))) else spec.getJSONArray("components")
            require(components.length() in 1..8)
            val blocks = mutableListOf<BoardComponent>()
            for (j in 0 until components.length()) {
                val component = components.getJSONObject(j)
                val type = component.getString("type")
                val allowed = when (type) {
                    "text" -> setOf("type", "text", "actions")
                    "input" -> setOf("type", "kind", "fields", "suggestedName", "actions")
                    "metric" -> setOf("type", "calculationId", "path", "label", "unit", "actions")
                    "trend" -> setOf("type", "calculationId", "label", "unit", "actions")
                    "choice" -> setOf("type", "options")
                    else -> throw IllegalArgumentException("不支持的组件")
                }
                require((component.keys().asSequence().toSet() - allowed).isEmpty())
                val actionsJson = component.optJSONArray("actions") ?: JSONArray()
                require(actionsJson.length() <= 2)
                val actions = (0 until actionsJson.length()).map { n ->
                    val action = actionsJson.getJSONObject(n); val kind = action.getString("kind"); val label = action.getString("label")
                    require((action.keys().asSequence().toSet() - setOf("kind", "label", "seconds", "planId")).isEmpty())
                    require(label.isNotBlank() && label.length <= 40)
                    val value = when (kind) {
                        "start_timer" -> action.getInt("seconds").also { require(it in 1..3600) }.toString()
                        "record_set" -> { require(type == "input"); action.optString("planId").takeIf { it.isNotBlank() } }
                        else -> throw IllegalArgumentException("不支持的操作")
                    }
                    CardAction("$cardId:$j:$n", label, kind, EntityRef("local", cardId), value=value)
                }
                val blockId = "external-$j"
                val block = when (type) {
                    "text" -> {
                        val text = component.getString("text"); require(text.isNotBlank() && text.length <= 4000)
                        val analysisId = "external:$proposalId:$i:$j"; require(collaboration.analysis(analysisId) == null)
                        collaboration.putAnalysis(AnalysisEntity(analysisId, 1, "reply", "", "", "[]", text, "[]", "[]", "current", now, now))
                        BoardComponent(blockId, type, ComponentContent(contentRef=ContentRef(EntityRef("analysis", analysisId), "summaryText")), actions=actions)
                    }
                    "input" -> {
                        require(blocks.none { it.type == "input" })
                        val fieldsJson = component.getJSONArray("fields")
                        val content = ComponentContent(inputKind=component.getString("kind"), inputFields=(0 until fieldsJson.length()).map { fieldsJson.getString(it) }, suggestedName=component.optString("suggestedName").takeIf { it.isNotBlank() })
                        ActiveInputRepository.validate(content)
                        if (actions.any { it.kind == "record_set" }) require(content.inputKind == "training" && "name" in content.inputFields && "sets" !in content.inputFields && "repsMax" !in content.inputFields)
                        BoardComponent(blockId, type, content, actions=actions)
                    }
                    "metric", "trend" -> {
                        val calcId = component.getString("calculationId"); val calc = requireNotNull(collaboration.calculation(calcId))
                        require(SourceValidator(db).refsUsable(calc.inputRefs, true))
                        val result = JSONObject(calc.result)
                        val path = component.optString("path").takeIf { it.isNotBlank() }
                        if (type == "metric") require(path != null && ComponentResolver.path(result, path) is Number)
                        else require(result.optJSONArray("points") != null)
                        val unit = component.optString("unit")
                        val expectedUnit = path?.let { result.optJSONObject("units")?.optString(it.substringAfterLast('.'))?.takeIf(String::isNotBlank) }
                            ?: result.optString("unit").takeIf(String::isNotBlank)
                        require(if (expectedUnit == null) unit.isEmpty() else unit == expectedUnit) { "单位与计算来源不一致" }
                        BoardComponent(blockId, type, ComponentContent(label=component.optString("label"), calculationId=calcId, resultPath=path, unit=unit), actions=actions)
                    }
                    "choice" -> {
                        val options = component.getJSONArray("options"); require(options.length() in 1..8 && actions.isEmpty())
                        val choices = (0 until options.length()).map { k -> options.getJSONObject(k).let { ChoiceOption(it.getString("key"), it.getString("label")) } }
                        require(choices.all { it.key.isNotBlank() && it.key.length <= 60 && it.label.isNotBlank() && it.label.length <= 120 } && choices.map { it.key }.distinct().size == choices.size)
                        BoardComponent(blockId, type, ComponentContent(options=choices))
                    }
                    else -> throw IllegalArgumentException("不支持的组件")
                }
                blocks.add(block)
            }
            val collapsed = if (existing?.collapsedOverride == true) existing.collapsed else spec.optBoolean("collapsed", existing?.collapsed ?: false)
            added.add(DashboardCard(id=cardId, title=title, span=span, revision=(existing?.revision ?: 0)+1, blocks=blocks, managedBy="external", followUpId=followUpId, groupTitle=group, emphasis=emphasis, collapsed=collapsed, collapsedOverride=existing?.collapsedOverride == true))
        }
        val inputIds = proposal.getJSONArray("inputIds")
        require((0 until inputIds.length()).map { inputIds.getString(it) }.distinct().size == inputIds.length())
        for (i in 0 until inputIds.length()) complete(agentId, epoch, inputIds.getString(i), proposalId) {}
        val removed = proposal.optJSONArray("removeIds") ?: JSONArray()
        val removedIds = (0 until removed.length()).map { removed.getString(it) }
        require(removedIds.distinct().size == removedIds.size && removedIds.none { it in ids } && removedIds.all { id -> current.any { it.id == id && it.managedBy == "external" } })
        val combined = current.filterNot { it.id in ids || it.id in removedIds } + added
        val order = proposal.optJSONArray("order") ?: JSONArray()
        val orderedIds = (0 until order.length()).map { order.getString(it) }
        require(orderedIds.distinct().size == orderedIds.size && orderedIds.all { id -> combined.any { it.id == id } })
        val next = orderedIds.map { id -> combined.single { it.id == id } } + combined.filterNot { it.id in orderedIds }
        collaboration.removeCards(boardId)
        collaboration.putCards(next.map { Card(it.id, boardId, DomainJson.encode(it)) })
        collaboration.putBoard(board.copy(revision=board.revision+1, updatedAt=now))
        collaboration.putDraft(draft.copy(revision=draft.revision+1, cardsJson=store.encode(next), updatedAt=now))
        collaboration.putReceipt(MutationReceiptEntity(java.util.UUID.randomUUID().toString(), "external:$proposalId", "apply", "[]", null, null, now))
        "applied"
    }
}
