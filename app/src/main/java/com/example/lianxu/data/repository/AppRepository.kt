package com.example.lianxu.data.repository

import com.example.lianxu.data.dao.GoalPhaseDao
import com.example.lianxu.data.dao.MeasurementDao
import com.example.lianxu.data.dao.ProfileDao
import com.example.lianxu.data.entity.GoalPhase
import com.example.lianxu.data.entity.Measurement
import com.example.lianxu.data.entity.Profile
import kotlinx.coroutines.flow.Flow

class AppRepository(
    private val profileDao: ProfileDao,
    private val goalPhaseDao: GoalPhaseDao,
    private val measurementDao: MeasurementDao
) {
    val profileFlow: Flow<Profile?> = profileDao.getProfileFlow()
    val goalPhasesFlow: Flow<List<GoalPhase>> = goalPhaseDao.getGoalPhasesFlow()
    val measurementsFlow: Flow<List<Measurement>> = measurementDao.getMeasurementsFlow()

    suspend fun getProfile(): Profile? = profileDao.getProfile()
    suspend fun insertProfile(profile: Profile) = profileDao.insert(profile)

    suspend fun getCurrentGoalPhase(): GoalPhase? = goalPhaseDao.getCurrentGoalPhase()
    suspend fun insertGoalPhase(goalPhase: GoalPhase) = goalPhaseDao.insert(goalPhase)

    suspend fun getLatestWeight(): Measurement? = measurementDao.getLatestMeasurement("weight")
    suspend fun insertMeasurement(measurement: Measurement) = measurementDao.insert(measurement)
}
