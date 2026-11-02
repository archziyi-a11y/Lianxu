package com.example.lianxu.data.repository

import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.engine.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class ArchiveData(val profile: Profile?, val goal: GoalPhase?, val measurements: List<Measurement>) {
    val weight: Measurement? get() = latestWeightForDate(measurements, LocalDate.now(profile?.preferredZoneId?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()))
}

fun latestWeightForDate(measurements: List<Measurement>, date: LocalDate): Measurement? {
    fun day(m: Measurement) = runCatching { LocalDate.parse(if (m.observed.startsWith("{")) JSONObject(m.observed).getString("date") else m.observed.take(10)) }.getOrNull()
    return measurements.filter { it.metric == "weight" && day(it)?.let { d -> !d.isAfter(date) } == true }
        .maxWithOrNull(compareBy<Measurement> { day(it) }.thenBy { it.measuredAt ?: it.createdAt }.thenBy { it.createdAt })
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
    suspend fun getLatestWeight() = latestWeightForDate(measurements.all(), LocalDate.now())

    suspend fun saveArchive(height: Float?, birthDate: String?, sex: String?, activity: Float?,
        weight: Float?, goal: String, date: LocalDate = LocalDate.now(), scene: String? = null,
        exclusions: List<String>? = null, nutritionMode: String? = null, manualTargets: NutrientValues? = null) = db.withTransaction {
        require(height == null || height.isFinite() && height > 0) { "身高必须大于0" }
        require(weight == null || weight.isFinite() && weight > 0) { "体重必须大于0" }
        require(sex == null || sex in listOf("male", "female")) { "公式参数请填写male、female或留空" }
        require(birthDate == null || !LocalDate.parse(birthDate).isAfter(date)) { "生日不能晚于今天" }
        require(activity == null || activity.isFinite() && activity in 1.4f..2.5f) { "活动系数应为1.4至2.5" }
        require(goal in listOf("gain", "cut", "maintain")) { "目标请填写gain、cut或maintain" }
        require(scene == null || scene in listOf("home", "canteen", "takeout")) { "用餐场景不正确" }
        require(nutritionMode == null || nutritionMode in listOf("standard_adult", "manual"))
        val now = System.currentTimeMillis()
        val existing = profiles.getProfile()
        var profile = existing?.copy(heightCm = height, birthDate = birthDate, formulaSex = sex,
            activityFactor = activity, revision = existing.revision + 1, updatedAt = now)
            ?: Profile(UUID.randomUUID().toString(), height, birthDate, sex, activity, ZoneId.systemDefault().id,
                "[]", "home", "[]", "[]", "standard_adult", null, 1, now, now)
        if (existing != null) snapshot("profile", existing.id, existing.revision, DomainJson.encode(existing))
        profile = profile.copy(defaultScene = scene ?: profile.defaultScene,
            excludedIngredients = exclusions?.let { org.json.JSONArray(it.filter(String::isNotBlank).distinct()).toString() } ?: profile.excludedIngredients,
            nutritionMode = nutritionMode ?: profile.nutritionMode,
            manualTargets = if (nutritionMode == "manual") manualTargets?.let { DomainJson.encode(it) } else profile.manualTargets)
        profiles.insert(profile)
        snapshot("profile", profile.id, profile.revision, DomainJson.encode(profile))
        if (weight != null) {
            val context = JSONObject().put("date", date.toString()).put("zoneId", profile.preferredZoneId)
                .put("basis", "operation_context").toString()
            measurements.insert(Measurement(UUID.randomUUID().toString(), "weight", context, now,
                weight, "kg", "user", null, 1, now, now))
        }
        val oldGoals = goals.getGoalPhasesFlow().first()
        oldGoals.forEach { snapshot("goal", it.id, it.revision, DomainJson.encode(it)) }
        val current = goals.getCurrentGoalPhase()
        if (current?.kind != goal) {
            goals.endOpenPhases(date.toString(), now)
            goals.insert(GoalPhase(UUID.randomUUID().toString(), goal, date.toString(), null, null, 1, now, now))
        } else {
            goals.endOtherPhases(current.id, date.toString(), now)
        }
        invalidateDerived(db)
    }

    private suspend fun snapshot(type: String, id: String, revision: Int, json: String) {
        val dao = db.collaborationDao()
        if (dao.entitySnapshot(type, id, revision) == null) dao.putEntitySnapshot(EntitySnapshotEntity(
            UUID.nameUUIDFromBytes("$type:$id:$revision".toByteArray()).toString(), type, id, revision, json, System.currentTimeMillis()))
    }

    suspend fun recordTraining(value: TrainingSession) = db.withTransaction {
        require(!value.title.isBlank() || !value.rawSummary.isNullOrBlank() || value.exercises != "[]")
        db.recordDao().insertTraining(value)
        invalidateDerived(db)
    }
    suspend fun recordIntake(value: IntakeEntry) = db.withTransaction {
        db.recordDao().insertIntake(value)
        invalidateDerived(db)
    }
}
