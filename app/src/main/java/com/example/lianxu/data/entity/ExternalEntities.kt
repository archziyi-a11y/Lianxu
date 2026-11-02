package com.example.lianxu.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "external_control")
data class ExternalControlEntity(@PrimaryKey val id: String = "control", val mode: String, val agentId: String?, val epoch: Long, val handoffAccepted: Boolean)

@Entity(tableName = "external_plan_versions")
data class ExternalPlanVersionEntity(@PrimaryKey val id: String, val content: String, val reason: String, val effectiveAt: Long, val createdAt: Long, val authorAgentId: String)

@Entity(tableName = "external_plan_state")
data class ExternalPlanStateEntity(@PrimaryKey val id: String = "current", val planId: String)

@Entity(tableName = "training_plan_links")
data class TrainingPlanLinkEntity(@PrimaryKey val trainingId: String, val planId: String)

@Entity(tableName = "external_pending_inputs", indices = [Index(value = ["submissionId"], unique = true)])
data class ExternalPendingInputEntity(@PrimaryKey val id: String, val submissionId: String, val payload: String, val ownerMode: String, val ownerAgentId: String?, val epoch: Long, val status: String, val resultRefs: String?, val createdAt: Long)

@Entity(tableName = "external_requirements", indices = [Index(value = ["mergeKey"], unique = true)])
data class ExternalRequirementEntity(@PrimaryKey val id: String, val mergeKey: String, val scenario: String, val limitation: String, val workaround: String, val expectedInteraction: String, val occurrences: Int, val updatedAt: Long)
