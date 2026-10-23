package com.example.lianxu.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey val id: String,
    val heightCm: Float?,
    val birthDate: String?, // LocalDate string YYYY-MM-DD
    val formulaSex: String?, // male, female, null
    val activityFactor: Float?,
    val preferredZoneId: String,
    val mealSlots: String, // JSON of List<MealSlot>
    val defaultScene: String,
    val excludedIngredients: String, // JSON of List<String>
    val preferredFoods: String, // JSON of List<String>
    val nutritionMode: String,
    val manualTargets: String?, // JSON of Nutrients
    val revision: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

@Serializable
data class MealSlot(val key: String, val label: String, val ratio: Float)

@Serializable
data class Nutrients(
    val energyKcal: Float? = null,
    val proteinG: Float? = null,
    val carbohydrateG: Float? = null,
    val fatG: Float? = null,
    val fiberG: Float? = null
)

@Entity(tableName = "goal_phases")
data class GoalPhase(
    @PrimaryKey val id: String,
    val kind: String, // gain, cut, maintain
    val startsOn: String, // LocalDate YYYY-MM-DD
    val endsOn: String?,
    val note: String?,
    val revision: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

@Entity(tableName = "knowledge_notes")
data class KnowledgeNote(
    @PrimaryKey val id: String,
    val revision: Int,
    val kind: String,
    val text: String,
    val origin: String,
    val effectiveFrom: String,
    val effectiveUntil: String?,
    val status: String,
    val supersedesId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

@Entity(tableName = "training_sessions")
data class TrainingSession(
    @PrimaryKey val id: String,
    val dateContext: String, // JSON of DateContext
    val kind: String,
    val title: String,
    val durationS: Int?,
    val exercises: String, // JSON of List<ExerciseEntry>
    val rawSummary: String?,
    val origin: String, // JSON of Origin
    val revision: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

@Entity(tableName = "intake_entries")
data class IntakeEntry(
    @PrimaryKey val id: String,
    val dateContext: String,
    val mealKey: String?,
    val coverageKey: String,
    val mode: String,
    val items: String, // JSON of List<IntakeItem>
    val manualNutrients: String?, // JSON of Nutrients
    val rawDescription: String?,
    val completeness: String,
    val origin: String,
    val revision: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

@Entity(tableName = "measurements")
data class Measurement(
    @PrimaryKey val id: String,
    val metric: String,
    val observed: String, // JSON of DateContext
    val measuredAt: Long?,
    val value: Float,
    val unit: String,
    val origin: String,
    val note: String?,
    val revision: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

@Entity(tableName = "turns")
data class Turn(
    @PrimaryKey val id: String,
    val submissionId: String,
    val entry: String,
    val boardId: String?,
    val dateContext: String,
    val sequence: Int,
    val inputBundleId: String,
    val contextRefs: String, // JSON
    val status: String,
    val taskId: String,
    val resultRefs: String, // JSON
    val mutationIds: String, // JSON
    val beforeBoardSnapshotId: String?,
    val afterBoardSnapshotId: String?,
    val createdAt: Long,
    val completedAt: Long?,
    val deletedAt: Long? = null
)

@Entity(tableName = "boards")
data class Board(
    @PrimaryKey val id: String,
    val revision: Int,
    val dateContext: String,
    val latestCompletedTurnId: String?,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

@Entity(tableName = "cards")
data class Card(
    @PrimaryKey val id: String,
    val boardId: String,
    val data: String // JSON of the card content
)
