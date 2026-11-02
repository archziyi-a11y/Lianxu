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
import kotlinx.coroutines.flow.*
import android.net.Uri
import com.example.lianxu.data.repository.*
import com.example.lianxu.data.entity.*
import org.json.JSONObject
import androidx.room.withTransaction

import com.example.lianxu.archive.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(FlowPreview::class)
class ArchiveViewModel @JvmOverloads constructor(application: Application, database: AppDatabase? = null) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ArchiveUiState())
    val uiState = _uiState.asStateFlow()
    private val db = database ?: AppDatabase.getDatabase(application)
    private val repository = AppRepository(db)
    private val archive=ArchiveRepository(application,db)
    val arrangements=db.recordDao().watchArrangements().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val externalRequirements = db.externalDao().watchRequirements().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val externalPlans = db.externalDao().watchPlans().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val currentExternalPlan = db.externalDao().watchCurrentPlan().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    fun resolveExternalDecision(requirement: ExternalRequirementEntity, accepted: Boolean) = work {
        ExternalRepository(db).resolveDecision(requirement, accepted)
        ExternalSyncWorker.now(getApplication())
        _message.value = if (accepted) "决定已保存；如需变更目标，请在资料中更新" else "已拒绝该建议"
    }
    fun saveFollowUp(text:String,old:KnowledgeNote?=null,status:String="current",done:()->Unit={})=work {
        ActiveInputRepository(db).arrange(text,old,status);_message.value=if(status=="cancelled")"已取消主动跟进，历史进展保留" else "跟进安排已保存";done()
    }
    fun saveHabit(note:KnowledgeNote,name:String,format:String,enabled:Boolean,done:()->Unit={})=work { ActiveInputRepository(db).editHabit(note,name,format,enabled);_message.value="记录习惯已更新";done() }
    private val selectedScope=MutableStateFlow("current_phase")
    private val _dashboard=MutableStateFlow(ArchiveDashboardState())
    val dashboard=_dashboard.asStateFlow()
    fun clearMessage(){_message.value=null}
    fun selectScope(scope:String){selectedScope.value=scope}
    fun place(key:String,placement:String)=work { archive.placement(key,placement) }
    fun toggleArchiveCard(id:String)=work { archive.toggleCollapsed(id) }
    fun importMethod(raw:String,onSaved:()->Unit={})=work { archive.methods.importPackage(raw);archive.refresh();_message.value="方法已导入，将按适用条件运行";onSaved() }
    fun copyMethodPrompt(onReady:(String)->Unit)=work { onReady(archive.methods.authoringPrompt());_message.value="制作提示词已复制" }
    fun enableMethod(id:String,enabled:Boolean)=work {
        val old=archive.db.archiveDao().methods().single { it.id==id };archive.db.archiveDao().putMethod(old.copy(enabled=enabled,updatedAt=System.currentTimeMillis()));archive.refresh()
    }
    private suspend fun showDashboard(){
        val topics=db.archiveDao().topics().filter { it.state=="current" }
        val scopes=topics.map { it.scopeKey }.distinct().sortedBy { listOf("current_phase","accumulated","7d","28d","90d").indexOf(it) }
        val scope=selectedScope.value.takeIf { it in scopes } ?: scopes.firstOrNull()
        val cards=archive.cards(scope);val catalog=archive.cards(scope,all=true)
        val all=(cards+catalog).distinctBy { it.card.id };val resolver=ComponentResolver(db)
        val sources=topics.associate { topic->
            val c=db.collaborationDao().calculation(topic.calculationId);val r=c?.let { JSONObject(it.result) }
            val refs=c?.let { org.json.JSONArray(it.inputRefs) }
            topic.id to "${topic.title} · 方法版本${topic.methodVersion}\n${r?.optString("start")} 至 ${r?.optString("end")}\n依据${refs?.length() ?: 0}条来源，${r?.optInt("sampleCount") ?: 0}个观测\n${r?.optString("summary").orEmpty()}"
        }
        val data=repository.archiveFlow.first()
        val overview=listOfNotNull(data.goal?.let { when(it.kind){"gain"->"增肌阶段";"cut"->"减脂阶段";else->"维持阶段"} },data.weight?.let { "${it.value} kg" }).joinToString(" · ")
        _dashboard.value=ArchiveDashboardState(cards,catalog,topics,db.archiveDao().methods(),scopes,scope ?: "accumulated",all.associate { it.card.id to resolver.resolve(it.card) },sources,overview,topics.maxOfOrNull { it.updatedAt } ?: 0)
    }
    private var nutrition: NutritionBoardRepository? = null
    private var dirty = false
    private var weightDirty = false
    private val history = HistoryRepository(db)
    val records = history.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val assessments = db.collaborationDao().watchAnalyses().map { rows -> rows.filter { it.kind in listOf("assessment","archive_local","archive_ai") } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val days = combine(db.collaborationDao().watchBoards(),db.collaborationDao().watchDrafts(),db.collaborationDao().watchTasks(),db.collaborationDao().watchPlans()) { boards,drafts,tasks,plans ->
        boards.sortedByDescending { HistoryRepository.day(it.dateContext) }.map { board ->
            val draft=drafts.find { it.boardId==board.id }
            val pending=draft?.let { BoardStore(db).decode(it.cardsJson).filter { card -> !card.annotationText.isNullOrBlank() || card.attachmentId!=null } }.orEmpty()
            val plan=plans.find { it.dateContext==board.dateContext }
            ArchivedDay(board,pending.joinToString("\n") { it.annotationText ?: "图片补充" },tasks.firstOrNull { it.boardId==board.id }?.status,
                plan?.let { DomainJson.decode<MealsDocument>(it.meals).meals.joinToString("\n") { meal -> "${meal.label} · ${meal.title}\n${meal.items.joinToString("、") { "${it.name} ${ComponentResolver.displayNumber(it.grams,"g")}g" }}" } }.orEmpty())
        }
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val _message = MutableStateFlow<String?>(null)
    val message=_message.asStateFlow()
    private val _busy=MutableStateFlow(false)
    val busy=_busy.asStateFlow()
    private var lastMutation: MutationToken?=null
    val canUndo=MutableStateFlow(false)

    fun bringToBoard(item: HistoryItem?=null,onReady:()->Unit={})=work {
        val board=DayLifecycle(db).ensureCurrent()
        val service=nutrition ?: NutritionBoardRepository.create(getApplication(),db).also { nutrition=it }
        service.refresh(board.id)
        val analysis=if(item==null)db.collaborationDao().allAnalyses().firstOrNull { it.kind=="archive_local" && it.freshness=="current" } else null
        val ref=item?.let { com.example.lianxu.data.model.EntityRef(it.type,it.id,it.revision) }
            ?: analysis?.let { com.example.lianxu.data.model.EntityRef("analysis",it.id,it.revision) }
        val cardId="${board.id}:archive:${item?.id ?: "phase"}"
        val blocks=if(ref==null)emptyList() else if(ref.type=="analysis")listOf(com.example.lianxu.data.model.BoardComponent("archive-reference","text",
            com.example.lianxu.data.model.ComponentContent(contentRef=com.example.lianxu.data.model.ContentRef(ref,"summaryText"))))
            else if(ref.type in listOf("training","intake"))listOf(com.example.lianxu.data.model.BoardComponent("archive-reference","list",com.example.lianxu.data.model.ComponentContent(entityRefs=listOf(ref)))) else emptyList()
        val card=com.example.lianxu.data.model.DashboardCard(cardId,item?.title ?: "阶段分析",value=item?.detail ?: "结合长期记录与当前目标分析",span=com.example.lianxu.data.model.CardSpan.LARGE,
            blocks=blocks,entityRefs=listOfNotNull(ref),managedBy="assistant")
        db.withTransaction {
            val dao=db.collaborationDao();val current=requireNotNull(dao.board(board.id));val draft=requireNotNull(dao.draft(board.id))
            val existing=BoardStore(db).decode(draft.cardsJson)
            val old=existing.find { it.id==cardId }
            val hasPending=old?.let { !it.annotationText.isNullOrBlank() || it.attachmentId!=null }==true
            // Preserve an in-progress annotation and its original anchor. Refresh a clean reference.
            if(!hasPending) {
                val fresh=card.copy(revision=(old?.revision ?: 0)+1)
                val updated=if(old==null)existing+fresh else existing.map { if(it.id==cardId)fresh else it }
                dao.putCards(listOf(Card(cardId,board.id,DomainJson.encode(fresh))))
                dao.putDraft(draft.copy(revision=draft.revision+1,cardsJson=BoardStore(db).encode(updated),updatedAt=System.currentTimeMillis()))
                dao.putBoard(current.copy(revision=current.revision+1))
                BoardStore(db).edit(board.id) { cards -> cards.map { if(it.id==cardId)it.copy(isAnnotationMode=true,
                    annotationText=if(item==null)"结合阶段记录、当前目标和习惯，给出阶段评价与建议。" else "请结合这条历史记录分析我今天的新进展。旧记录纠正在档案完成。") else it } }
            }
        }
        onReady()
    }
    fun refreshAssessment()=work { archive.refresh(); _message.value="阶段记录已整理" }
    fun addRecord(type: String,text: String,date: String,metric: String,value: String,onSaved:()->Unit={})=work {
        require(type=="knowledge" || LocalDate.parse(date)<today()) { "今天的信息请到今日添加；历史补记请选择早于今天的日期" }
        lastMutation=history.add(type,text,LocalDate.parse(date),metric,value.takeIf(String::isNotBlank)?.toFloatOrNull());canUndo.value=true
        archive.refresh();_message.value="已保存在本机";onSaved()
    }
    fun changeRecord(item: HistoryItem,text: String,value: String,delete: Boolean=false,onSaved:()->Unit={})=work {
        lastMutation=history.change(item,text.takeIf(String::isNotBlank),value.toFloatOrNull(),delete);canUndo.value=true
        archive.refresh();_message.value=if(delete)"记录已移除，可撤销" else "已更新";onSaved()
    }
    fun undo()=work { lastMutation?.let { history.undo(it) };lastMutation=null;canUndo.value=false;archive.refresh();_message.value="已撤销" }
    fun exportBackup(uri: Uri)=work { requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri)).use { BackupRepository(getApplication(),db).export(it) };_message.value="备份已导出，未包含密钥" }
    fun restoreBackup(uri: Uri)=work { requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use { BackupRepository(getApplication(),db).restore(it) };archive.methods.installBuiltins();archive.refresh();lastMutation=null;canUndo.value=false;dirty=false;weightDirty=false;showSaved(repository.archiveFlow.first());_message.value="记录与附件已恢复，密钥设置保留" }
    private fun work(block: suspend () -> Unit) {
        if(_busy.value)return
        _busy.value=true
        viewModelScope.launch { try { block() } catch(e:CancellationException){throw e} catch(e:Exception){_message.value=e.message ?: "未完成，请重试"} finally{_busy.value=false} }
    }
    private suspend fun today(): LocalDate = LocalDate.now(repository.getProfile()?.preferredZoneId?.let(java.time.ZoneId::of) ?: java.time.ZoneId.systemDefault())

    init {
        viewModelScope.launch {
            archive.methods.installBuiltins()
            db.invalidationTracker.createFlow("profiles","goal_phases","measurements","training_sessions","intake_entries","knowledge_notes","calculations","archive_methods",emitInitialState=true).debounce(150).collect { archive.refresh() }
        }
        viewModelScope.launch {
            combine(db.archiveDao().watchTopics(),db.archiveDao().watchPlacements(),db.archiveDao().watchPresentation(),selectedScope,db.archiveDao().watchMethods()){_,_,_,_,_->Unit}.collect { showDashboard() }
        }
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
        val boardId = DayLifecycle(db).ensureCurrent().id
        val result = service.refresh(boardId)
        archive.refresh()
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
    fun onWeightChange(value: String) { weightDirty=true; edit { it.copy(weightStr=value) } }
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
                    if(weightDirty) number(state.weightStr, "体重") else null, state.goalKind.trim().lowercase(), scene = state.scene,
                    exclusions = state.excluded.split('、', ',', '，').map { it.trim() }.filter { it.isNotEmpty() },
                    nutritionMode = if (state.manualMode) "manual" else "standard_adult",
                    manualTargets = if (state.manualMode) NutrientValues(precise(state.manualEnergy, "能量"),
                        precise(state.manualProtein, "蛋白质"), precise(state.manualCarbs, "碳水"),
                        precise(state.manualFat, "脂肪"), precise(state.manualFiber, "纤维")) else null)
                dirty = false; weightDirty=false
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

data class ArchivedDay(val board: Board,val pending: String,val taskStatus: String?,val meals: String)

data class ArchiveDashboardState(
 val cards:List<ArchiveDisplay> = emptyList(),val catalog:List<ArchiveDisplay> = emptyList(),
 val topics:List<ArchiveTopicEntity> = emptyList(),val methods:List<ArchiveMethodEntity> = emptyList(),
 val scopes:List<String> = emptyList(),val scope:String="accumulated",
 val resolved:Map<String,List<ResolvedComponent>> = emptyMap(),val sources:Map<String,String> = emptyMap(),val overview:String="",val updatedAt:Long=0
)
