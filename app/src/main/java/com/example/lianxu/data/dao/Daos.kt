package com.example.lianxu.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.lianxu.data.entity.Profile
import com.example.lianxu.data.entity.GoalPhase
import com.example.lianxu.data.entity.Measurement
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE deletedAt IS NULL LIMIT 1")
    fun getProfileFlow(): Flow<Profile?>

    @Query("SELECT * FROM profiles WHERE deletedAt IS NULL LIMIT 1")
    suspend fun getProfile(): Profile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: Profile)
}

@Dao
interface GoalPhaseDao {
    @Query("SELECT * FROM goal_phases WHERE deletedAt IS NULL ORDER BY startsOn DESC")
    fun getGoalPhasesFlow(): Flow<List<GoalPhase>>

    @Query("SELECT * FROM goal_phases WHERE deletedAt IS NULL AND endsOn IS NULL ORDER BY startsOn DESC LIMIT 1")
    suspend fun getCurrentGoalPhase(): GoalPhase?

    @Query("UPDATE goal_phases SET endsOn = :date, updatedAt = :now, revision = revision + 1 WHERE endsOn IS NULL AND deletedAt IS NULL")
    suspend fun endOpenPhases(date: String, now: Long)

    @Query("UPDATE goal_phases SET endsOn = :date, updatedAt = :now, revision = revision + 1 WHERE id != :keepId AND endsOn IS NULL AND deletedAt IS NULL")
    suspend fun endOtherPhases(keepId: String, date: String, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(goalPhase: GoalPhase)
}

@Dao
interface MeasurementDao {
    @Query("SELECT * FROM measurements WHERE deletedAt IS NULL ORDER BY observed DESC, measuredAt DESC")
    fun getMeasurementsFlow(): Flow<List<Measurement>>
    
    @Query("SELECT * FROM measurements WHERE deletedAt IS NULL AND metric = :metric ORDER BY observed DESC, measuredAt DESC LIMIT 1")
    suspend fun getLatestMeasurement(metric: String): Measurement?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(measurement: Measurement)
}
