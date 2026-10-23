package com.example.lianxu.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.repository.AppRepository
import com.example.lianxu.data.repository.ArchiveData
import com.example.lianxu.engine.CalculationEngine
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
    private val repository = AppRepository(AppDatabase.getDatabase(application))
    private var dirty = false

    init {
        viewModelScope.launch {
            repository.archiveFlow.collect { if (!dirty) showSaved(it) }
        }
    }

    private fun showSaved(data: ArchiveData) {
        val p = data.profile
        val result = runCatching {
            val weight = data.weight?.value ?: return@runCatching null
            val height = p?.heightCm ?: return@runCatching null
            val birthDate = p.birthDate ?: return@runCatching null
            val sex = p.formulaSex ?: return@runCatching null
            if (sex !in listOf("male", "female")) return@runCatching null
            CalculationEngine.calculate(weight, height, LocalDate.parse(birthDate), sex,
                p.activityFactor ?: 1.6f, data.goal?.kind ?: "maintain")
        }.getOrNull()
        _uiState.value = ArchiveUiState(data.weight?.value?.toString() ?: "", p?.heightCm?.toString() ?: "",
            p?.birthDate ?: "", p?.formulaSex ?: "", p?.activityFactor?.toString() ?: "1.6",
            data.goal?.kind ?: "maintain", result)
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

    fun saveData() {
        if (_uiState.value.isSaving) return
        val state = _uiState.value
        _uiState.value = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            try {
                fun number(raw: String, label: String): Float? = if (raw.isBlank()) null else
                    requireNotNull(raw.trim().toFloatOrNull()) { "$label 格式不正确" }
                repository.saveArchive(number(state.heightStr, "身高"), state.birthDateStr.trim().ifEmpty { null },
                    state.formulaSex.trim().lowercase().ifEmpty { null }, number(state.activityFactorStr, "活动系数"),
                    number(state.weightStr, "体重"), state.goalKind.trim().lowercase())
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
    val nutritionResult: NutritionResult? = null, val isSaving: Boolean = false, val error: String? = null
)
