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

            uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = { viewModel.saveData() },
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (uiState.isSaving) "正在保存…" else "保存并重新计算")
            }

            uiState.nutritionResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("计算结果", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("静息能量 (REE): ${result.restingKcal} kcal")
                        Text("维持能量: ${result.maintenanceKcal} kcal")
                        Text("目标能量: ${result.energyKcal} kcal")
                        Text("蛋白质: ${result.proteinG} g")
                        Text("碳水化合物: ${result.carbohydrateG} g")
                        Text("脂肪: ${result.fatG} g")
                        Text("膳食纤维: ${result.fiberG} g")
                    }
                }
            }
        }
    }
}
