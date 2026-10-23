package com.example.lianxu.data.repository

import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import kotlinx.coroutines.flow.combine
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class ArchiveData(val profile: Profile?, val goal: GoalPhase?, val measurements: List<Measurement>) {
    val weight: Measurement? get() = measurements.firstOrNull { it.metric == "weight" }
}

class AppRepository(private val db: AppDatabase) {
    private val profiles = db.profileDao()
    private val goals = db.goalPhaseDao()
    private val measurements = db.measurementDao()
    val archiveFlow = combine(profiles.getProfileFlow(), goals.getGoalPhasesFlow(), measurements.getMeasurementsFlow()) { p, g, m ->
        ArchiveData(p, g.firstOrNull { it.endsOn == null }, m)
    }
    val trainings = db.recordDao().trainings()
    val intakes = db.recordDao().intakes()
    val knowledge = db.recordDao().knowledge()
    suspend fun getProfile() = profiles.getProfile()
    suspend fun getLatestWeight() = measurements.getLatestMeasurement("weight")

    suspend fun saveArchive(height: Float?, birthDate: String?, sex: String?, activity: Float?,
        weight: Float?, goal: String, date: LocalDate = LocalDate.now()) = db.withTransaction {
        require(height == null || height.isFinite() && height > 0) { "身高必须大于0" }
        require(weight == null || weight.isFinite() && weight > 0) { "体重必须大于0" }
        require(sex == null || sex in listOf("male", "female")) { "公式参数请填写male、female或留空" }
        require(birthDate == null || !LocalDate.parse(birthDate).isAfter(date)) { "生日不能晚于今天" }
        require(activity == null || activity.isFinite() && activity in 1.4f..2.5f) { "活动系数应为1.4至2.5" }
        require(goal in listOf("gain", "cut", "maintain")) { "目标请填写gain、cut或maintain" }
        val now = System.currentTimeMillis()
        val existing = profiles.getProfile()
        val profile = existing?.copy(heightCm = height, birthDate = birthDate, formulaSex = sex,
            activityFactor = activity, revision = existing.revision + 1, updatedAt = now)
            ?: Profile(UUID.randomUUID().toString(), height, birthDate, sex, activity, ZoneId.systemDefault().id,
                "[]", "home", "[]", "[]", "standard_adult", null, 1, now, now)
        profiles.insert(profile)
        if (weight != null) {
            val context = JSONObject().put("date", date.toString()).put("zoneId", profile.preferredZoneId)
                .put("basis", "operation_context").toString()
            measurements.insert(Measurement(UUID.randomUUID().toString(), "weight", context, now,
                weight, "kg", "user", null, 1, now, now))
        }
        val current = goals.getCurrentGoalPhase()
        if (current?.kind != goal) {
            goals.endOpenPhases(date.toString(), now)
            goals.insert(GoalPhase(UUID.randomUUID().toString(), goal, date.toString(), null, null, 1, now, now))
        } else {
            goals.endOtherPhases(current.id, date.toString(), now)
        }
        db.collaborationDao().invalidateAnalyses()
        db.collaborationDao().invalidatePlans()
    }

    suspend fun recordTraining(value: TrainingSession) = db.withTransaction {
        require(!value.title.isBlank() || !value.rawSummary.isNullOrBlank() || value.exercises != "[]")
        db.recordDao().insertTraining(value)
        db.collaborationDao().invalidateAnalyses()
    }
    suspend fun recordIntake(value: IntakeEntry) = db.withTransaction {
        db.recordDao().insertIntake(value)
        db.collaborationDao().invalidateAnalyses()
        db.collaborationDao().invalidatePlans()
    }
}
