package com.example.lianxu.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName="archive_methods")
data class ArchiveMethodEntity(@PrimaryKey val id:String,val version:Int,val packageJson:String,val builtin:Boolean,val enabled:Boolean,val updatedAt:Long)
@Entity(tableName="archive_topics")
data class ArchiveTopicEntity(@PrimaryKey val id:String,val methodId:String,val subjectKey:String,val scopeKey:String,val category:String,val title:String,val calculationId:String,val analysisId:String?,val methodVersion:Int,val inputFingerprint:String,val state:String,val updatedAt:Long)
@Entity(tableName="archive_placements")
data class ArchivePlacementEntity(@PrimaryKey val id:String,val placement:String,val position:Long,val updatedAt:Long)
@Entity(tableName="archive_presentations")
data class ArchivePresentationEntity(@PrimaryKey val id:String="archive",val revision:Int,val cardsJson:String,val inputFingerprint:String,val updatedAt:Long)
@Entity(tableName="archive_jobs")
data class ArchiveJobEntity(@PrimaryKey val id:String="archive",val desiredFingerprint:String,val completedFingerprint:String?,val status:String,val lastError:String?,val updatedAt:Long)
