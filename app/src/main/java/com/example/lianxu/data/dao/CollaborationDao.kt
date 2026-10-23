package com.example.lianxu.data.dao

import androidx.room.*
import com.example.lianxu.data.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CollaborationDao {
    @Query("SELECT * FROM calculations") suspend fun allCalculations(): List<CalculationEntity>
    @Query("SELECT * FROM analyses") suspend fun allAnalyses(): List<AnalysisEntity>
    @Query("SELECT * FROM day_plans") suspend fun allPlans(): List<DayPlanEntity>
    @Query("SELECT * FROM nutrition_policies") suspend fun allPolicies(): List<NutritionPolicyEntity>
    @Query("SELECT * FROM entity_snapshots") suspend fun allEntitySnapshots(): List<EntitySnapshotEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAgentCache(value: AgentCacheEntity)
    @Query("SELECT * FROM agent_cache WHERE taskId = :id") suspend fun agentCache(id: String): AgentCacheEntity?

    @Query("SELECT * FROM boards WHERE id = :id") suspend fun board(id: String): Board?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBoard(value: Board)
    @Query("SELECT * FROM cards WHERE boardId = :boardId ORDER BY rowid") suspend fun cards(boardId: String): List<Card>
    @Query("DELETE FROM cards WHERE boardId = :boardId") suspend fun removeCards(boardId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCards(values: List<Card>)
    @Query("SELECT * FROM drafts WHERE boardId = :boardId") suspend fun draft(boardId: String): DraftEntity?
    @Query("SELECT * FROM drafts WHERE boardId = :boardId") fun watchDraft(boardId: String): Flow<DraftEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDraft(value: DraftEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun putAttachment(value: AttachmentEntity)
    @Query("SELECT * FROM attachments WHERE id = :id") suspend fun attachment(id: String): AttachmentEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun putInput(value: InputBundleEntity)
    @Query("SELECT * FROM input_bundles WHERE id = :id") suspend fun input(id: String): InputBundleEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTurn(value: Turn)
    @Query("SELECT * FROM turns WHERE id = :id") suspend fun turn(id: String): Turn?
    @Query("SELECT * FROM turns WHERE boardId = :boardId ORDER BY sequence") suspend fun turns(boardId: String): List<Turn>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTask(value: TaskEntity)
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun task(id: String): TaskEntity?
    @Query("SELECT * FROM tasks WHERE boardId = :boardId AND draftRevision = :revision ORDER BY createdAt DESC LIMIT 1")
    suspend fun taskForDraft(boardId: String, revision: Int): TaskEntity?
    @Query("SELECT * FROM tasks WHERE status IN ('queued','running','staged','applying')") suspend fun unfinishedTasks(): List<TaskEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun putSnapshot(value: BoardSnapshotEntity)
    @Query("SELECT * FROM board_snapshots WHERE id = :id") suspend fun snapshot(id: String): BoardSnapshotEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun putReceipt(value: MutationReceiptEntity)
    @Query("SELECT * FROM mutation_receipts WHERE submissionId = :submissionId AND operationId = :operationId")
    suspend fun receipt(submissionId: String, operationId: String): MutationReceiptEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun putCalculation(value: CalculationEntity)
    @Query("SELECT * FROM calculations WHERE id = :id") suspend fun calculation(id: String): CalculationEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAnalysis(value: AnalysisEntity)
    @Query("SELECT * FROM analyses WHERE id = :id") suspend fun analysis(id: String): AnalysisEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPlan(value: DayPlanEntity)
    @Query("SELECT * FROM day_plans WHERE id = :id") suspend fun plan(id: String): DayPlanEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun putPolicy(value: NutritionPolicyEntity)
    @Query("SELECT * FROM nutrition_policies ORDER BY startsOn DESC, createdAt DESC LIMIT 1") suspend fun latestPolicy(): NutritionPolicyEntity?
    @Query("SELECT * FROM nutrition_policies WHERE startsOn <= :date ORDER BY startsOn DESC, createdAt DESC LIMIT 1") suspend fun policyOn(date: String): NutritionPolicyEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun putEntitySnapshot(value: EntitySnapshotEntity)
    @Query("SELECT * FROM entity_snapshots WHERE entityType = :type AND entityId = :id AND revision = :revision")
    suspend fun entitySnapshot(type: String, id: String, revision: Int): EntitySnapshotEntity?
    @Query("SELECT * FROM day_plans WHERE dateContext = :context LIMIT 1") suspend fun planForDate(context: String): DayPlanEntity?
    @Query("SELECT * FROM day_plans WHERE dateContext = :context LIMIT 1") fun watchPlan(context: String): Flow<DayPlanEntity?>
    @Query("UPDATE analyses SET freshness = 'stale' WHERE freshness = 'current'") suspend fun invalidateAnalyses()
    @Query("UPDATE day_plans SET state = 'stale' WHERE state = 'current'") suspend fun invalidatePlans()
}

@Dao
interface RecordDao {
    @Update suspend fun updateTraining(value: TrainingSession)
    @Update suspend fun updateIntake(value: IntakeEntry)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putKnowledge(value: KnowledgeNote)
    @Query("SELECT * FROM knowledge_notes WHERE deletedAt IS NULL") suspend fun allKnowledge(): List<KnowledgeNote>

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTraining(value: TrainingSession)
    @Query("SELECT * FROM training_sessions WHERE deletedAt IS NULL ORDER BY createdAt DESC") fun trainings(): Flow<List<TrainingSession>>
    @Query("SELECT * FROM training_sessions WHERE id = :id") suspend fun training(id: String): TrainingSession?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertIntake(value: IntakeEntry)
    @Query("SELECT * FROM intake_entries WHERE deletedAt IS NULL ORDER BY createdAt DESC") fun intakes(): Flow<List<IntakeEntry>>
    @Query("SELECT * FROM intake_entries WHERE id = :id") suspend fun intake(id: String): IntakeEntry?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertKnowledge(value: KnowledgeNote)
    @Query("SELECT * FROM intake_entries WHERE deletedAt IS NULL ORDER BY createdAt DESC") suspend fun allIntakes(): List<IntakeEntry>
    @Query("SELECT * FROM training_sessions WHERE deletedAt IS NULL ORDER BY createdAt DESC") suspend fun allTrainings(): List<TrainingSession>
    @Query("SELECT * FROM knowledge_notes WHERE deletedAt IS NULL AND status = 'current'") fun knowledge(): Flow<List<KnowledgeNote>>
}
