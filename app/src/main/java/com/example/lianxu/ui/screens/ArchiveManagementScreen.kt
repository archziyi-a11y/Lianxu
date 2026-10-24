package com.example.lianxu.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.lianxu.data.repository.*
import com.example.lianxu.engine.*
import android.net.Uri
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
fun ArchiveManagementScreen(modifier: Modifier=Modifier,viewModel: ArchiveViewModel=viewModel(),onRetry: (String)->Unit={},onShowToday:()->Unit={},initialSection:String="记录",onBack:()->Unit={}) {
    val uiState by viewModel.uiState.collectAsState()
    val records by viewModel.records.collectAsState()
    val assessments by viewModel.assessments.collectAsState()
    val days by viewModel.days.collectAsState()
    val message by viewModel.message.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    var section by rememberSaveable { mutableStateOf(initialSection) }
    var add by remember { mutableStateOf(false) }
    var addType by remember { mutableStateOf("training") }
    var editing by remember { mutableStateOf<HistoryItem?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var recordLimit by rememberSaveable { mutableIntStateOf(100) }
    var recordDate by rememberSaveable { mutableStateOf("") }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { viewModel.exportBackup(it) } }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreUri=it }
    restoreUri?.let { uri -> AlertDialog(onDismissRequest={restoreUri=null},title={Text("恢复这份备份？")},text={Text("这会替换本机的记录、附件和看板。当前密钥保留，正在处理的任务或计时需先结束。")},
        confirmButton={TextButton(onClick={restoreUri=null;viewModel.restoreBackup(uri)}){Text("恢复备份")}},dismissButton={TextButton(onClick={restoreUri=null}){Text("取消")}}) }
    if(add || editing!=null) RecordDialog(editing,busy,message,addType,onDismiss={add=false;editing=null},onSave={type,text,date,metric,value ->
        val selected=editing
        if(selected==null)viewModel.addRecord(type,text,date,metric,value) { add=false;editing=null } else viewModel.changeRecord(selected,text,value,onSaved={add=false;editing=null})
    })
    Scaffold(topBar={TopAppBar(title={Text(if(section=="阶段")"分析历史" else section)},navigationIcon={TextButton(onClick=onBack){Text("返回")}})}) { padding ->
        LazyColumn(modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("记录","阶段","历日","资料","备份").forEach { label ->
                FilterChip(selected=section==label,onClick={section=label},label={Text(label)})
            } } }
            message?.let { item { Text(it,style=MaterialTheme.typography.bodyMedium) } }
            if(canUndo)item { TextButton(onClick=viewModel::undo,enabled=!busy) { Text("撤销上次修改") } }
            when(section) {
                "资料" -> item { Column(verticalArrangement=Arrangement.spacedBy(16.dp)) { ProfileForm(viewModel,uiState) } }
                "记录" -> {
                    item { Button(onClick={addType="training";add=true},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("补记训练、饮食或测量") } }
                    if(records.isEmpty())item { Text("已有的训练、摄入和测量会保存在这里，可离线补记。") }
                    item { OutlinedTextField(value=recordDate,onValueChange={recordDate=it},label={Text("按日期查找，可留空")},singleLine=true,modifier=Modifier.fillMaxWidth()) }
                    items(records.filter { recordDate.isBlank() || it.date.startsWith(recordDate) }.take(recordLimit),key={"${it.type}:${it.id}"}) { item -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("${item.date} · ${item.title}",style=MaterialTheme.typography.titleSmall)
                        Text(item.detail)
                        Row { TextButton(onClick={editing=item},enabled=!busy){Text(if(item.type in listOf("training","intake"))"修改说明" else "修改")}

                            TextButton(onClick={viewModel.changeRecord(item,"","",true)},enabled=!busy){Text("删除")}
                        }
                    } } }
                    if(records.count { recordDate.isBlank() || it.date.startsWith(recordDate) }>recordLimit)item { TextButton(onClick={recordLimit+=100}){Text("加载更早记录")} }
                }
                "阶段" -> {

                    if(assessments.isEmpty())item { Text("录入训练、体重或摄入后，按实际资料整理阶段变化。") }
                    items(assessments.take(recordLimit),key={it.id}) { analysis -> Card { Column(Modifier.padding(16.dp)) { Text("${analysis.rangeStart} — ${analysis.rangeEnd}",style=MaterialTheme.typography.labelSmall);Text(analysis.summaryText);Text(if(analysis.freshness=="current")"当前依据" else "历史评价 · 依据已有更新",style=MaterialTheme.typography.labelSmall) } } }
                    item { if(assessments.size>recordLimit)TextButton(onClick={recordLimit+=100}){Text("加载更早分析")};Text("长期认识",style=MaterialTheme.typography.titleMedium);TextButton(onClick={addType="knowledge";add=true}) { Text("补充长期习惯或纠正") } }
                    items(records.filter { it.type=="knowledge" },key={"note:${it.id}"}) { note -> Card { Column(Modifier.padding(16.dp)) { Text(note.detail);TextButton(onClick={editing=note}){Text("纠正这项认识")};TextButton(onClick={viewModel.changeRecord(note,"","",true)}){Text("移除")} } } }
                }
                "历日" -> {
                    if(days.isEmpty())item { Text("每天的看板和未完成补充会保存在这里。") }
                    items(days,key={it.board.id}) { day -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("${HistoryRepository.day(day.board.dateContext)} · ${if(day.board.status=="active")"今日" else "已归档"}",style=MaterialTheme.typography.titleSmall)
                        if(day.pending.isNotBlank()) { Text("待处理补充：${day.pending}");TextButton(onClick={onRetry(day.board.id)},enabled=!busy && day.taskStatus !in listOf("running","staged","applying")){Text("按原日期处理补充")} }
                        TextButton(onClick={expanded=if(expanded==day.board.id)null else day.board.id}) { Text(if(expanded==day.board.id)"收起方案" else "查看当日方案") }
                        if(expanded==day.board.id)Text(day.meals.ifEmpty { "当天未生成餐食方案" })
                    } } }
                }
                "备份" -> item { Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("导出包含记录、轮次、看板与原始附件，密钥单独保存在设备中。")
                    Button(onClick={export.launch("Lianxu-${LocalDate.now()}.zip")},enabled=!busy){Text("导出本地备份")}
                    OutlinedButton(onClick={restore.launch(arrayOf("application/zip","application/octet-stream"))},enabled=!busy){Text("从备份恢复")}
                } }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordDialog(item: HistoryItem?,busy: Boolean,message: String?,defaultType: String,onDismiss:()->Unit,onSave:(String,String,String,String,String)->Unit) {
    var type by remember { mutableStateOf(item?.type ?: defaultType) }
    var text by remember { mutableStateOf(if(item?.type=="measurement")"" else item?.detail.orEmpty()) }
    var date by remember { mutableStateOf(item?.date ?: LocalDate.now().toString()) }
    var metric by remember { mutableStateOf(item?.takeIf { it.type=="measurement" }?.let { org.json.JSONObject(it.raw).getString("metric") } ?: "weight") }
    var value by remember { mutableStateOf(item?.takeIf { it.type=="measurement" }?.let { org.json.JSONObject(it.raw).getDouble("value").toString() }.orEmpty()) }
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(item==null)"离线补记" else "修改")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(item==null)FlowRow { listOf("training" to "训练","intake" to "饮食","measurement" to "测量","knowledge" to "长期认识").forEach { (key,label) -> FilterChip(selected=type==key,onClick={type=key},label={Text(label)}) } }
        if(item==null)OutlinedTextField(value=date,onValueChange={date=it},label={Text("记录日期 YYYY-MM-DD")})
        if(type=="measurement") {
            if(item==null)FlowRow { listOf("weight" to "体重 kg","body_fat" to "体脂 %","waist" to "腰围 cm").forEach { (key,label) -> FilterChip(selected=metric==key,onClick={metric=key},label={Text(label)}) } }
            OutlinedTextField(value=value,onValueChange={value=it},label={Text("${HistoryRepository.label(metric)}数值")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
        } else OutlinedTextField(value=text,onValueChange={text=it},label={Text(if(type=="knowledge")"长期习惯或纠正" else "记录说明")},minLines=3)
        message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
    }},confirmButton={TextButton(enabled=!busy,onClick={onSave(type,text,date,metric,value)}){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

@Composable
private fun ProfileForm(viewModel: ArchiveViewModel,uiState: ArchiveUiState) {
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
