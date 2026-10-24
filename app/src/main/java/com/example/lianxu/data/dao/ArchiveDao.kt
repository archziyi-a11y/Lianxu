package com.example.lianxu.data.dao

import androidx.room.*
import com.example.lianxu.data.entity.*
import kotlinx.coroutines.flow.Flow

@Dao interface ArchiveDao {
 @Query("SELECT * FROM archive_methods ORDER BY id") suspend fun methods():List<ArchiveMethodEntity>
 @Query("SELECT * FROM archive_methods ORDER BY id") fun watchMethods():Flow<List<ArchiveMethodEntity>>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putMethod(value:ArchiveMethodEntity)
 @Query("SELECT * FROM archive_topics ORDER BY id") suspend fun topics():List<ArchiveTopicEntity>
 @Query("SELECT * FROM archive_topics ORDER BY id") fun watchTopics():Flow<List<ArchiveTopicEntity>>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putTopic(value:ArchiveTopicEntity)
 @Query("UPDATE archive_topics SET state='stale' WHERE state='current'") suspend fun staleTopics()
 @Query("SELECT * FROM archive_placements ORDER BY position") suspend fun placements():List<ArchivePlacementEntity>
 @Query("SELECT * FROM archive_placements ORDER BY position") fun watchPlacements():Flow<List<ArchivePlacementEntity>>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putPlacement(value:ArchivePlacementEntity)
 @Query("SELECT * FROM archive_presentations WHERE id='archive'") suspend fun presentation():ArchivePresentationEntity?
 @Query("SELECT * FROM archive_presentations WHERE id='archive'") fun watchPresentation():Flow<ArchivePresentationEntity?>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putPresentation(value:ArchivePresentationEntity)
 @Query("SELECT * FROM archive_jobs WHERE id='archive'") suspend fun job():ArchiveJobEntity?
 @Query("SELECT * FROM archive_jobs WHERE id='archive'") fun watchJob():Flow<ArchiveJobEntity?>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putJob(value:ArchiveJobEntity)
}
