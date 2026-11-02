package com.example.lianxu.data.repository

import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*

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
    /** Call in the same transaction that inserts the training record. */
    suspend fun linkTrainingToCurrentPlan(trainingId: String) = db.withTransaction {
        requireNotNull(db.recordDao().training(trainingId))
        val current = dao.currentPlan() ?: return@withTransaction
        val old = dao.trainingLink(trainingId)
        if (old == null) dao.insertTrainingLink(TrainingPlanLinkEntity(trainingId, current.planId))
        else check(old.planId == current.planId) { "TRAINING_ALREADY_LINKED" }
    }
    suspend fun submitRequirement(value: ExternalRequirementEntity) = db.withTransaction {
        require(value.mergeKey.isNotBlank() && value.scenario.isNotBlank() && value.limitation.isNotBlank() && value.expectedInteraction.isNotBlank())
        val old = dao.requirement(value.mergeKey)
        if (old == null) dao.insertRequirement(value.copy(occurrences = 1))
        else dao.updateRequirement(old.copy(occurrences = old.occurrences + 1, updatedAt = value.updatedAt))
    }
    suspend fun requirements() = dao.requirements()
}
