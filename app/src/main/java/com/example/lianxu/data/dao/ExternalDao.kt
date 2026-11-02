package com.example.lianxu.data.dao

import androidx.room.*
import com.example.lianxu.data.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ExternalDao {
    @Query("SELECT * FROM external_control WHERE id = 'control'") suspend fun control(): ExternalControlEntity?
    @Query("SELECT * FROM external_control WHERE id = 'control'") fun watchControl(): Flow<ExternalControlEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putControl(value: ExternalControlEntity)
    @Query("SELECT * FROM external_plan_versions WHERE id = :id") suspend fun plan(id: String): ExternalPlanVersionEntity?
    @Query("SELECT * FROM external_plan_versions ORDER BY effectiveAt DESC, createdAt DESC") suspend fun plans(): List<ExternalPlanVersionEntity>
    @Query("SELECT * FROM external_plan_versions ORDER BY effectiveAt DESC, createdAt DESC") fun watchPlans(): Flow<List<ExternalPlanVersionEntity>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertPlan(value: ExternalPlanVersionEntity)
    @Query("SELECT * FROM external_plan_state WHERE id = 'current'") suspend fun currentPlan(): ExternalPlanStateEntity?
    @Query("SELECT * FROM external_plan_state WHERE id = 'current'") fun watchCurrentPlan(): Flow<ExternalPlanStateEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCurrentPlan(value: ExternalPlanStateEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTrainingLink(value: TrainingPlanLinkEntity)
    @Query("SELECT * FROM training_plan_links WHERE trainingId = :id") suspend fun trainingLink(id: String): TrainingPlanLinkEntity?
    @Query("SELECT * FROM training_plan_links ORDER BY trainingId") suspend fun trainingLinks(): List<TrainingPlanLinkEntity>
    @Query("SELECT * FROM external_pending_inputs WHERE submissionId = :id") suspend fun inputBySubmission(id: String): ExternalPendingInputEntity?
    @Query("SELECT * FROM external_pending_inputs WHERE id = :id") suspend fun input(id: String): ExternalPendingInputEntity?
    @Query("SELECT * FROM external_pending_inputs WHERE status = 'pending' ORDER BY createdAt") suspend fun pendingInputs(): List<ExternalPendingInputEntity>
    @Query("SELECT * FROM external_pending_inputs WHERE status = 'completed' ORDER BY id") suspend fun completedInputs(): List<ExternalPendingInputEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertInput(value: ExternalPendingInputEntity)
    @Update suspend fun updateInput(value: ExternalPendingInputEntity)
    @Query("UPDATE external_pending_inputs SET ownerMode = :mode, ownerAgentId = :agentId, epoch = :epoch WHERE status = 'pending'") suspend fun transferPending(mode: String, agentId: String?, epoch: Long)
    @Query("SELECT * FROM external_requirements WHERE mergeKey = :key") suspend fun requirement(key: String): ExternalRequirementEntity?
    @Query("SELECT * FROM external_requirements ORDER BY updatedAt DESC") suspend fun requirements(): List<ExternalRequirementEntity>
    @Query("SELECT * FROM external_requirements ORDER BY updatedAt DESC") fun watchRequirements(): Flow<List<ExternalRequirementEntity>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRequirement(value: ExternalRequirementEntity)
    @Update suspend fun updateRequirement(value: ExternalRequirementEntity)
}
