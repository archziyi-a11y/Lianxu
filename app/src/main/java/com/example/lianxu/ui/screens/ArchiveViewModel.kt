package com.example.lianxu.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.repository.AppRepository
import com.example.lianxu.data.repository.ArchiveData
import com.example.lianxu.data.repository.BoardStore
import com.example.lianxu.data.repository.NutritionBoardRepository
import com.example.lianxu.engine.*
import com.example.lianxu.engine.NutritionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

class ArchiveViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ArchiveUiState())
    val uiState = _uiState.asStateFlow()
    private val db = AppDatabase.getDatabase(application)
    private val repository = AppRepository(db)
    private var nutrition: NutritionBoardRepository? = null
    private var dirty = false

    init {
        viewModelScope.launch {
            try { repository.archiveFlow.collect { if (!dirty) showSaved(it) } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { _uiState.value = _uiState.value.copy(error = "档案暂未载入，请重新打开：${error.message}") }
        }
    }

    private suspend fun showSaved(data: ArchiveData) {
        val p = data.profile
        val zone = p?.preferredZoneId?.let { java.time.ZoneId.of(it) } ?: java.time.ZoneId.systemDefault()
        val service = nutrition ?: NutritionBoardRepository.create(getApplication(), db).also { nutrition = it }
        val boardId = BoardStore(db).open(emptyList(), LocalDate.now(zone), zone)
        val result = service.refresh(boardId)
        val manual = p?.manualTargets?.let { DomainJson.decode<NutrientValues>(it) }
        _uiState.value = ArchiveUiState(data.weight?.value?.toString() ?: "", p?.heightCm?.toString() ?: "",
            p?.birthDate ?: "", p?.formulaSex ?: "", p?.activityFactor?.toString() ?: "1.6",
            data.goal?.kind ?: "maintain", result, scene = p?.defaultScene ?: "home",
            excluded = p?.excludedIngredients?.let { raw -> org.json.JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) }.joinToString("、") } } ?: "",
            manualMode = p?.nutritionMode == "manual", manualEnergy = manual?.energyKcal?.toString() ?: "",
            manualProtein = manual?.proteinG?.toString() ?: "", manualCarbs = manual?.carbohydrateG?.toString() ?: "",
            manualFat = manual?.fatG?.toString() ?: "", manualFiber = manual?.fiberG?.toString() ?: "")
    }

    private fun edit(transform: (ArchiveUiState) -> ArchiveUiState) {
        if (_uiState.value.isSaving) return
        dirty = true
        _uiState.value = transform(_uiState.value).copy(error = null, nutritionResult = null)
    }
    fun onWeightChange(value: String) = edit { it.copy(weightStr = value) }
    fun onHeightChange(value: String) = edit { it.copy(heightStr = value) }
    fun onBirthDateChange(value: String) = edit { it.copy(birthDateStr = value) }
    fun onFormulaSexChange(value: String) = edit { it.copy(formulaSex = value) }
    fun onActivityFactorChange(value: String) = edit { it.copy(activityFactorStr = value) }
    fun onGoalKindChange(value: String) = edit { it.copy(goalKind = value) }

    fun onScene(value: String) = edit { it.copy(scene = value) }
    fun onExcluded(value: String) = edit { it.copy(excluded = value) }
    fun onManualMode(value: Boolean) = edit { it.copy(manualMode = value) }
    fun onManual(key: String, value: String) = edit {
        when(key) { "energyKcal" -> it.copy(manualEnergy = value); "proteinG" -> it.copy(manualProtein = value)
            "carbohydrateG" -> it.copy(manualCarbs = value); "fatG" -> it.copy(manualFat = value); else -> it.copy(manualFiber = value) }
    }

    fun saveData() {
        if (_uiState.value.isSaving) return
        val state = _uiState.value
        _uiState.value = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            try {
                fun number(raw: String, label: String): Float? = if (raw.isBlank()) null else
                    requireNotNull(raw.trim().toFloatOrNull()) { "$label 格式不正确" }
                fun precise(raw: String, label: String): Double? = if (raw.isBlank()) null else
                    requireNotNull(raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }) { "$label 必须是有效的非负数" }
                repository.saveArchive(number(state.heightStr, "身高"), state.birthDateStr.trim().ifEmpty { null },
                    state.formulaSex.trim().lowercase().ifEmpty { null }, number(state.activityFactorStr, "活动系数"),
                    number(state.weightStr, "体重"), state.goalKind.trim().lowercase(), scene = state.scene,
                    exclusions = state.excluded.split('、', ',', '，').map { it.trim() }.filter { it.isNotEmpty() },
                    nutritionMode = if (state.manualMode) "manual" else "standard_adult",
                    manualTargets = if (state.manualMode) NutrientValues(precise(state.manualEnergy, "能量"),
                        precise(state.manualProtein, "蛋白质"), precise(state.manualCarbs, "碳水"),
                        precise(state.manualFat, "脂肪"), precise(state.manualFiber, "纤维")) else null)
                dirty = false
                showSaved(repository.archiveFlow.first())
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = error.message ?: "保存失败，输入已保留")
            }
        }
    }
}

data class ArchiveUiState(
    val weightStr: String = "", val heightStr: String = "", val birthDateStr: String = "",
    val formulaSex: String = "", val activityFactorStr: String = "1.6", val goalKind: String = "maintain",
    val nutritionResult: NutritionResult? = null, val isSaving: Boolean = false, val error: String? = null,
    val scene: String = "home", val excluded: String = "", val manualMode: Boolean = false,
    val manualEnergy: String = "", val manualProtein: String = "", val manualCarbs: String = "", val manualFat: String = "", val manualFiber: String = ""
)
