package com.example.lianxu.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "drafts", indices = [Index(value = ["boardId"], unique = true)])
data class DraftEntity(@PrimaryKey val id: String, val boardId: String, val revision: Int,
    val dateContext: String, val cardsJson: String, val updatedAt: Long)

@Entity(tableName = "attachments")
data class AttachmentEntity(@PrimaryKey val id: String, val localPath: String, val mimeType: String,
    val sha256: String, val byteSize: Long, val createdAt: Long)

@Entity(tableName = "input_bundles", indices = [Index(value = ["submissionId"], unique = true)])
data class InputBundleEntity(@PrimaryKey val id: String, val turnId: String, val submissionId: String,
    val dateContext: String, val rawText: String, val annotationsJson: String, val createdAt: Long)

@Entity(tableName = "tasks", indices = [Index(value = ["submissionId"], unique = true)])
data class TaskEntity(@PrimaryKey val id: String, val turnId: String, val submissionId: String,
    val boardId: String, val draftRevision: Int, val status: String,
    val lastError: String?, val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "board_snapshots")
data class BoardSnapshotEntity(@PrimaryKey val id: String, val boardId: String, val boardRevision: Int,
    val turnId: String?, val cardsJson: String, val createdAt: Long)

@Entity(tableName = "calculations")
data class CalculationEntity(@PrimaryKey val id: String, val type: String, val methodVersion: String,
    val inputSnapshot: String, val inputRefs: String, val result: String, val createdAt: Long)

@Entity(tableName = "analyses")
data class AnalysisEntity(@PrimaryKey val id: String, val revision: Int, val kind: String,
    val rangeStart: String, val rangeEnd: String, val inputRefs: String, val summaryText: String,
    val blocks: String, val calculationIds: String, val freshness: String,
    val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "day_plans")
data class DayPlanEntity(@PrimaryKey val id: String, val revision: Int, val dateContext: String,
    val goalRef: String, val targetCalculationId: String, val meals: String,
    val inputRefs: String, val state: String, val updatedAt: Long)

@Entity(tableName = "nutrition_policies")
data class NutritionPolicyEntity(@PrimaryKey val id: String, val revision: Int, val version: String,
    val startsOn: String, val parametersJson: String, val createdAt: Long)

@Entity(tableName = "mutation_receipts", indices = [Index(value = ["submissionId", "operationId"], unique = true)])
data class MutationReceiptEntity(@PrimaryKey val id: String, val submissionId: String,
    val operationId: String, val resultRefs: String, val beforeSnapshotId: String?,
    val afterSnapshotId: String?, val createdAt: Long)
