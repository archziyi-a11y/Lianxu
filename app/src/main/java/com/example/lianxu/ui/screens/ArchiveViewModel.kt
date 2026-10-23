package com.example.lianxu.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.GoalPhase
import com.example.lianxu.data.entity.Measurement
import com.example.lianxu.data.entity.Profile
import com.example.lianxu.data.repository.AppRepository
import com.example.lianxu.engine.CalculationEngine
import com.example.lianxu.engine.NutritionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

class ArchiveViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: AppRepository

    init {
        val database = AppDatabase.getDatabase(application)
        repository = AppRepository(
            database.profileDao(),
            database.goalPhaseDao(),
            database.measurementDao()
        )
        loadData()
    }

    private val _uiState = MutableStateFlow(ArchiveUiState())
    val uiState: StateFlow<ArchiveUiState> = _uiState.asStateFlow()

    private fun loadData() {
        viewModelScope.launch {
            val profile = repository.getProfile()
            val goalPhase = repository.getCurrentGoalPhase()
            val weightMeasurement = repository.getLatestWeight()

            _uiState.value = _uiState.value.copy(
                weightStr = weightMeasurement?.value?.toString() ?: "",
                heightStr = profile?.heightCm?.toString() ?: "",
                birthDateStr = profile?.birthDate ?: "",
                formulaSex = profile?.formulaSex ?: "male",
                activityFactorStr = profile?.activityFactor?.toString() ?: "1.6",
                goalKind = goalPhase?.kind ?: "maintain"
            )
            recalculate(profile, goalPhase, weightMeasurement)
        }
    }

    fun onWeightChange(value: String) { _uiState.value = _uiState.value.copy(weightStr = value) }
    fun onHeightChange(value: String) { _uiState.value = _uiState.value.copy(heightStr = value) }
    fun onBirthDateChange(value: String) { _uiState.value = _uiState.value.copy(birthDateStr = value) }
    fun onFormulaSexChange(value: String) { _uiState.value = _uiState.value.copy(formulaSex = value) }
    fun onActivityFactorChange(value: String) { _uiState.value = _uiState.value.copy(activityFactorStr = value) }
    fun onGoalKindChange(value: String) { _uiState.value = _uiState.value.copy(goalKind = value) }

    fun saveData() {
        viewModelScope.launch {
            val state = _uiState.value
            val weight = state.weightStr.toFloatOrNull()
            val height = state.heightStr.toFloatOrNull()
            val birthDate = state.birthDateStr // Should be YYYY-MM-DD
            val activityFactor = state.activityFactorStr.toFloatOrNull() ?: 1.6f
            
            val nowStr = LocalDate.now().toString()
            val nowMs = System.currentTimeMillis()
            
            if (weight != null) {
                repository.insertMeasurement(
                    Measurement(
                        id = UUID.randomUUID().toString(),
                        metric = "weight",
                        observed = "{\"date\":\"$nowStr\",\"basis\":\"operation_context\"}", // simplified DateContext
                        measuredAt = nowMs,
                        value = weight,
                        unit = "kg",
                        origin = "user",
                        note = null,
                        revision = 1,
                        createdAt = nowMs,
                        updatedAt = nowMs
                    )
                )
            }
            
            var existingProfile = repository.getProfile()
            if (existingProfile == null) {
                existingProfile = Profile(
                    id = UUID.randomUUID().toString(),
                    heightCm = height,
                    birthDate = birthDate,
                    formulaSex = state.formulaSex,
                    activityFactor = activityFactor,
                    preferredZoneId = "Asia/Shanghai",
                    mealSlots = "[]",
                    defaultScene = "home",
                    excludedIngredients = "[]",
                    preferredFoods = "[]",
                    nutritionMode = "standard_adult",
                    manualTargets = null,
                    revision = 1,
                    createdAt = nowMs,
                    updatedAt = nowMs
                )
            } else {
                existingProfile = existingProfile.copy(
                    heightCm = height,
                    birthDate = birthDate,
                    formulaSex = state.formulaSex,
                    activityFactor = activityFactor,
                    revision = existingProfile.revision + 1,
                    updatedAt = nowMs
                )
            }
            repository.insertProfile(existingProfile)
            
            val existingGoal = repository.getCurrentGoalPhase()
            if (existingGoal?.kind != state.goalKind) {
                // Terminate old one, start new one (simplified here)
                val newGoal = GoalPhase(
                    id = UUID.randomUUID().toString(),
                    kind = state.goalKind,
                    startsOn = nowStr,
                    endsOn = null,
                    note = null,
                    revision = 1,
                    createdAt = nowMs,
                    updatedAt = nowMs
                )
                repository.insertGoalPhase(newGoal)
                recalculate(existingProfile, newGoal, Measurement(id="", metric="weight", observed="", measuredAt=null, value=weight ?: 0f, unit="kg", origin="", note="", revision=1, createdAt=nowMs, updatedAt=nowMs))
            } else {
                recalculate(existingProfile, existingGoal, Measurement(id="", metric="weight", observed="", measuredAt=null, value=weight ?: 0f, unit="kg", origin="", note="", revision=1, createdAt=nowMs, updatedAt=nowMs))
            }
        }
    }

    private fun recalculate(profile: Profile?, goalPhase: GoalPhase?, weightMeasurement: Measurement?) {
        val weight = weightMeasurement?.value ?: return
        val height = profile?.heightCm ?: return
        val birthDateStr = profile.birthDate ?: return
        val formulaSex = profile.formulaSex ?: return
        val activityFactor = profile.activityFactor ?: 1.6f
        val goalKind = goalPhase?.kind ?: "maintain"

        try {
            val birthDate = LocalDate.parse(birthDateStr)
            val result = CalculationEngine.calculate(
                weightKg = weight,
                heightCm = height,
                birthDate = birthDate,
                formulaSex = formulaSex,
                activityFactor = activityFactor,
                goalKind = goalKind
            )
            _uiState.value = _uiState.value.copy(nutritionResult = result)
        } catch (e: Exception) {
            // handle parse error
            _uiState.value = _uiState.value.copy(nutritionResult = null)
        }
    }
}

data class ArchiveUiState(
    val weightStr: String = "",
    val heightStr: String = "",
    val birthDateStr: String = "",
    val formulaSex: String = "male",
    val activityFactorStr: String = "1.6",
    val goalKind: String = "maintain",
    val nutritionResult: NutritionResult? = null
)
