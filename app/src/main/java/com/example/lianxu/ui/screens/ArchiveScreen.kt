package com.example.lianxu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.lianxu.engine.*
import com.example.lianxu.data.repository.ComponentResolver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    modifier: Modifier = Modifier,
    viewModel: ArchiveViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("档案与目标设置") })
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = uiState.weightStr,
                onValueChange = { viewModel.onWeightChange(it) },
                label = { Text("体重 (kg)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                value = uiState.heightStr,
                onValueChange = { viewModel.onHeightChange(it) },
                label = { Text("身高 (cm)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = uiState.birthDateStr,
                onValueChange = { viewModel.onBirthDateChange(it) },
                label = { Text("出生日期 (YYYY-MM-DD)") },
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                value = uiState.formulaSex,
                onValueChange = { viewModel.onFormulaSexChange(it) },
                label = { Text("生理性别公式参数 (male / female)") },
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                value = uiState.activityFactorStr,
                onValueChange = { viewModel.onActivityFactorChange(it) },
                label = { Text("活动系数 (默认1.6)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            
            OutlinedTextField(
                value = uiState.goalKind,
                onValueChange = { viewModel.onGoalKindChange(it) },
                label = { Text("阶段目标 (gain / cut / maintain)") },
                modifier = Modifier.fillMaxWidth()
            )

            Text("默认用餐场景", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("home" to "自己做", "canteen" to "食堂", "takeout" to "外食").forEach { (key, label) ->
                    FilterChip(selected = uiState.scene == key, onClick = { viewModel.onScene(key) }, label = { Text(label) })
                }
            }
            OutlinedTextField(value = uiState.excluded, onValueChange = viewModel::onExcluded,
                label = { Text("不吃的食物（用顿号分隔，可留空）") }, modifier = Modifier.fillMaxWidth())
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("手动设置营养目标")
                Switch(checked = uiState.manualMode, onCheckedChange = viewModel::onManualMode)
            }
            if (uiState.manualMode) {
                listOf(Triple("energyKcal", "能量 kcal", uiState.manualEnergy), Triple("proteinG", "蛋白质 g", uiState.manualProtein),
                    Triple("carbohydrateG", "碳水 g", uiState.manualCarbs), Triple("fatG", "脂肪 g", uiState.manualFat),
                    Triple("fiberG", "纤维 g（可留空）", uiState.manualFiber)).forEach { (key, label, value) ->
                    OutlinedTextField(value = value, onValueChange = { viewModel.onManual(key, it) }, label = { Text(label) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
            }

            uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = { viewModel.saveData() },
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (uiState.isSaving) "正在保存…" else "保存并重新计算")
            }

            uiState.nutritionResult?.let { result ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("每日营养参考", style = MaterialTheme.typography.titleMedium)
                        if (result.status in listOf("ready", "manual")) {
                            listOf("energyKcal" to "能量", "proteinG" to "蛋白质", "carbohydrateG" to "碳水", "fatG" to "脂肪", "fiberG" to "膳食纤维").forEach { (key, name) ->
                                result.targets[key]?.let { value ->
                                    val unit = if (key == "energyKcal") "kcal" else "g"
                                    Text("$name：约 ${ComponentResolver.displayNumber(value, unit)} $unit")
                                }
                            }
                            result.manualEnergyDifferenceKcal?.let { Text("与4/4/9换算差额：${ComponentResolver.displayNumber(it, "kcal")} kcal") }
                        } else if (result.status == "needs_input") Text("资料尚未完整，可继续补充；已有记录会保留。")
                        result.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
