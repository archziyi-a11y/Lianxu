package com.example.lianxu.data.dao

import androidx.room.*
import com.example.lianxu.data.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CollaborationDao {
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
    @Query("UPDATE analyses SET freshness = 'stale' WHERE freshness = 'current'") suspend fun invalidateAnalyses()
    @Query("UPDATE day_plans SET state = 'stale' WHERE state = 'current'") suspend fun invalidatePlans()
}

@Dao
interface RecordDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTraining(value: TrainingSession)
    @Query("SELECT * FROM training_sessions WHERE deletedAt IS NULL ORDER BY createdAt DESC") fun trainings(): Flow<List<TrainingSession>>
    @Query("SELECT * FROM training_sessions WHERE id = :id") suspend fun training(id: String): TrainingSession?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertIntake(value: IntakeEntry)
    @Query("SELECT * FROM intake_entries WHERE deletedAt IS NULL ORDER BY createdAt DESC") fun intakes(): Flow<List<IntakeEntry>>
    @Query("SELECT * FROM intake_entries WHERE id = :id") suspend fun intake(id: String): IntakeEntry?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertKnowledge(value: KnowledgeNote)
    @Query("SELECT * FROM knowledge_notes WHERE deletedAt IS NULL AND status = 'current'") fun knowledge(): Flow<List<KnowledgeNote>>
}
