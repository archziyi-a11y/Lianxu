package com.example.lianxu.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.model.CardAction
import com.example.lianxu.data.model.CardSpan
import com.example.lianxu.data.model.DashboardCard
import com.example.lianxu.data.prefs.SettingsManager
import com.example.lianxu.data.repository.NutritionBoardRepository
import com.example.lianxu.data.repository.ComponentResolver
import com.example.lianxu.data.repository.ResolvedComponent
import com.example.lianxu.data.repository.AppRepository
import com.example.lianxu.data.repository.AttachmentStore
import com.example.lianxu.data.repository.BoardStore
import com.example.lianxu.data.repository.Submission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class TodayViewModel @JvmOverloads constructor(application: Application, database: AppDatabase? = null, private val testClient: com.example.lianxu.agent.AgentClient? = null) : AndroidViewModel(application) {
    private val db = database ?: AppDatabase.getDatabase(application)
    private val settings = SettingsManager(application)
    private val store = BoardStore(db)
    private val attachments = AttachmentStore(application, db)
    private val writes = Mutex()
    private val builtinHandoff = Mutex()
    private val projection = Mutex()
    private var projectedBoard: String? = null
    private var projectedRevision = -1
    private var boardId: String? = null
    private var nutrition: NutritionBoardRepository? = null
    private val _resolved = MutableStateFlow<Map<String, List<ResolvedComponent>>>(emptyMap())
    val resolved = _resolved.asStateFlow()
    private val _sourceDetails = MutableStateFlow<String?>(null)
    val sourceDetails = _sourceDetails.asStateFlow()
    fun dismissSource() { _sourceDetails.value = null }
    private val _cards = MutableStateFlow<List<DashboardCard>>(emptyList())
    val cards = _cards.asStateFlow()
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()
    private val _isPlanning = MutableStateFlow(false)
    val isPlanning = _isPlanning.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private val _externalStatus = MutableStateFlow("正在检查连接")
    val externalStatus = _externalStatus.asStateFlow()
    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()
    private val _demoMode = MutableStateFlow(true)
    val demoMode = _demoMode.asStateFlow()
    private val _externalMode = MutableStateFlow(false)
    val externalMode = _externalMode.asStateFlow()
    private val _profileSummary = MutableStateFlow("尚未补充身体资料")
    val profileSummary = _profileSummary.asStateFlow()
    private val tasks = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val _boardDate = MutableStateFlow("")
    val boardDate = _boardDate.asStateFlow()
    private val _taskProgress = MutableStateFlow<String?>(null)
    val taskProgress = _taskProgress.asStateFlow()
    fun cancelSubmission() { tasks[boardId]?.cancel() }

    init {
        viewModelScope.launch {
            val control = com.example.lianxu.data.repository.ExternalRepository(db).control()
            _externalStatus.value = if (control.mode == "external") "外部智能体 · ${if (control.handoffAccepted) "已接手" else "等待接手"}" else "内置助手"
            if (settings.bridgeCredentials.first() != null) com.example.lianxu.data.repository.ExternalSyncWorker.schedule(getApplication())
        }
        viewModelScope.launch {
            try {
                store.recoverInterrupted()
                val repository = NutritionBoardRepository.create(application, db)
                nutrition = repository
                resumeBuiltinPending()
                com.example.lianxu.data.repository.DayLifecycle(db).watch().collectLatest { current ->
                    boardId = current.id
                    _boardDate.value = JSONObject(current.dateContext).getString("date")
                    _isProcessing.value = tasks[current.id]?.isActive == true
                    _notice.value = null
                    kotlinx.coroutines.coroutineScope {
                        launch {
                            val records = AppRepository(db)
                            combine(records.archiveFlow, records.trainings, records.intakes, records.knowledge, db.archiveDao().watchMethods()) { _, _, _, _, _ -> Unit }.collect {
                                _isPlanning.value = true
                                try { writes.withLock { repository.refresh(current.id) } }
                                catch (error: CancellationException) { throw error }
                                catch (error: Exception) { _notice.value = "今日规划未完成：${error.message}" }
                                finally { _isPlanning.value = false }
                            }
                        }
                        combine(store.watchDraft(current.id), db.collaborationDao().watchAnalyses()) { draft, _ -> draft }.collectLatest { draft ->
                            draft?.let { publishDraft(it) }
                        }
                    }
                }

            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { _notice.value = "载入失败，原数据保留：${error.message}" }
        }
        viewModelScope.launch {
            AppRepository(db).archiveFlow.collect { data ->
                _profileSummary.value = listOfNotNull(data.weight?.let { "${it.value} kg" },
                    data.goal?.let { when (it.kind) { "gain" -> "增肌"; "cut" -> "减脂"; else -> "维持" } }).joinToString(" · ")
                    .ifEmpty { "尚未补充身体资料" }
            }
        }
        viewModelScope.launch {
            combine(db.externalDao().watchControl(), com.example.lianxu.data.repository.ExternalSync.syncStatus) { control, connected -> control to connected }
                .collect { (control, connected) ->
                    _externalStatus.value = if (control?.mode != "external") "内置助手"
                        else if (connected == false) "外部智能体 · 连接中断，正在重试"
                        else "外部智能体 · ${if (control.handoffAccepted) "已接手" else "等待接手"}"
                }
        }
        viewModelScope.launch {
            try { combine(settings.apiKeyFlow, db.externalDao().watchControl()) { key, control -> key to (control?.mode == "external") }.collect { (key, external) -> _externalMode.value = external; _demoMode.value = !external && key.isNullOrBlank() } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { _notice.value = "无法读取密钥，请在设置中重新保存" }
        }
    }

    private suspend fun publishDraft(draft: com.example.lianxu.data.entity.DraftEntity, refreshResolved: Boolean = true) = projection.withLock {
        if (boardId != draft.boardId || projectedBoard == draft.boardId && draft.revision < projectedRevision) return@withLock
        if (!refreshResolved && projectedBoard == draft.boardId && draft.revision == projectedRevision) return@withLock
        val cards = store.decode(draft.cardsJson)
        val resolver = ComponentResolver(db)
        val resolved = cards.associate { it.id to resolver.resolve(it) }
        if (boardId != draft.boardId) return@withLock
        _resolved.value = resolved
        _cards.value = cards
        projectedBoard = draft.boardId
        projectedRevision = draft.revision
    }

    private fun edit(transform: (List<DashboardCard>) -> List<DashboardCard>) {
        val id = boardId ?: return
        viewModelScope.launch {
            try {
                val ready = writes.withLock {
                    store.edit(id, transform)
                    val draft = db.collaborationDao().draft(id)
                    draft != null && store.decode(draft.cardsJson).none { it.isAnnotationMode || com.example.lianxu.data.repository.ActiveInputRepository.hasInput(it) }
                }
                if (ready && db.externalDao().pendingInputs().any { it.ownerMode == "builtin" }) resumeBuiltinPending()
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { _notice.value = "保存补充失败，请重试：${error.message}" }
        }
    }
    fun toggleAnnotationMode(cardId: String) = edit { cards -> cards.map {
        if (it.id == cardId) it.copy(isAnnotationMode = !it.isAnnotationMode) else it } }
    fun toggleCardCollapsed(cardId: String) = edit { cards -> cards.map { if (it.id == cardId) it.copy(collapsed = !it.collapsed, collapsedOverride = true) else it } }
    fun updateAnnotationText(cardId: String, text: String) = edit { cards -> cards.map {
        if (it.id == cardId) it.copy(annotationText = text, isAnnotationMode = true) else it } }
    fun selectAssistantChoice(cardId: String, componentId: String, key: String) = edit { cards -> cards.map { card ->
        if (card.id != cardId) card else {
            val block = card.blocks.firstOrNull { it.componentId == componentId }
            val option = block?.content?.options?.firstOrNull { it.key == key }
            if (option == null) card else card.copy(isAnnotationMode = true,
                annotationText = listOfNotNull(card.annotationText?.takeIf { it.isNotBlank() }, "选择：${option.label}（${option.key}）").joinToString("\n"),
                blocks = card.blocks.map { if (it.componentId == componentId) it.copy(content = it.content.copy(selectedKey = key)) else it })
        }
    } }
    fun updateAnnotationImage(cardId: String, uri: String) {
        val id = boardId ?: return
        if (_isImporting.value) return
        _isImporting.value = true
        viewModelScope.launch {
            try {
                val attachment = attachments.import(Uri.parse(uri))
                writes.withLock {
                    store.edit(id) { cards -> cards.map { if (it.id == cardId) it.copy(attachmentId = attachment.id,
                        annotationImageUri = Uri.fromFile(File(getApplication<Application>().filesDir, attachment.localPath)).toString(),
                        isAnnotationMode = true) else it } }
                }
                _notice.value = "图片已保存在本机"
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { _notice.value = "图片未保存，请重新选择：${error.message}"
            } finally { _isImporting.value = false }
        }
    }
    fun updateInput(cardId:String,key:String,value:String)=edit { cards->cards.map { c->if(c.id==cardId)c.copy(inputValues=if(value.isBlank())c.inputValues-key else c.inputValues+(key to value)) else c } }
    fun clearInput(cardId:String)=edit { cards->cards.map { if(it.id==cardId)it.copy(annotationText=null,attachmentId=null,annotationImageUri=null,inputValues=emptyMap(),annotationAnchor=null,isAnnotationMode=false) else it } }
    fun removeImage(cardId:String)=edit { cards->cards.map { if(it.id==cardId)it.copy(attachmentId=null,annotationImageUri=null) else it } }
    fun closeAnnotations()=edit { cards->cards.map { it.copy(isAnnotationMode=false) } }
    fun addInput(kind:String):String { val card=DashboardCard(title=when(kind){"training"->"记录训练";"measurement"->"身体测量";"intake"->"饮食补充";else->"自由补充"},span=CardSpan.LARGE,
        blocks=listOf(com.example.lianxu.data.repository.ActiveInputRepository.input(kind)),managedBy="user_input");edit { it+card };return card.id }
    fun skipQuestion(cardId:String,key:String){viewModelScope.launch { com.example.lianxu.data.repository.ActiveInputRepository(db).dismissInvitation(key,_boardDate.value) };edit { cards->cards.filterNot { it.id==cardId } } }
    fun getApiKey(callback: (String?) -> Unit) {
        viewModelScope.launch {
            try { callback(settings.apiKeyFlow.first()) }
            catch (error: Exception) { _notice.value = "读取密钥失败，请重新填写" }
        }
    }
    fun saveApiKey(key: String) {
        viewModelScope.launch {
            try { settings.saveApiKey(key); com.example.lianxu.archive.ArchiveRuntime.resume(getApplication()); _notice.value = "设置已保存"; resumeBuiltinPending() }
            catch (error: Exception) { _notice.value = "密钥保存失败，请重试" }
        }
    }
    fun connectExternal(url: String, ownerToken: String, agentToken: String, agentName: String) {
        viewModelScope.launch {
            try {
                val attempt = com.example.lianxu.data.repository.ExternalConnectionCoordinator(db, settings)
                    .configure(url, ownerToken, agentToken, agentName)
                _externalStatus.value = "外部智能体 · 正在同步"
                com.example.lianxu.data.repository.ExternalSync(db, settings, getApplication()).sync(attempt)
                com.example.lianxu.data.repository.ExternalSyncWorker.schedule(getApplication())
                val control = com.example.lianxu.data.repository.ExternalRepository(db).control()
                _externalStatus.value = "外部智能体 · ${if (control.handoffAccepted) "已接手" else "等待接手"}"
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                _externalStatus.value = "连接尚未同步：${error.message ?: "请重试"}"
            }
        }
    }
    fun syncExternal() {
        viewModelScope.launch {
            try {
                val attempt = com.example.lianxu.data.repository.ExternalConnectionCoordinator(db, settings).prepareRetry()
                com.example.lianxu.data.repository.ExternalSync(db, settings, getApplication()).sync(attempt)
                val control = com.example.lianxu.data.repository.ExternalRepository(db).control()
                _externalStatus.value = if (control.mode == "builtin") "内置助手 · 已同步" else "外部智能体 · ${if (control.handoffAccepted) "已接手" else "等待接手"}"
                if (control.mode == "builtin") resumeBuiltinPending()
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                val control = com.example.lianxu.data.repository.ExternalRepository(db).control()
                _externalStatus.value = if (control.mode == "builtin") "已切回内置助手，远端撤权尚未同步" else "同步未完成：${error.message ?: "请重试"}"
            }
        }
    }
    fun useBuiltin() {
        viewModelScope.launch {
            com.example.lianxu.data.repository.ExternalSync.withConnectionLock {
                com.example.lianxu.data.repository.ExternalRepository(db).switchToBuiltin()
            }
            resumeBuiltinPending()
            if (settings.bridgeCredentials.first() == null) {
                _externalStatus.value = "内置助手"
                return@launch
            }
            _externalStatus.value = "内置助手 · 正在撤销外部连接"
            com.example.lianxu.data.repository.ExternalSyncWorker.schedule(getApplication())
            syncExternal()
        }
    }
    private fun resumeBuiltinPending() {
        viewModelScope.launch {
            builtinHandoff.withLock {
                val repository = nutrition ?: return@withLock
                val external = com.example.lianxu.data.repository.ExternalRepository(db)
                if (external.control().mode != "builtin") return@withLock
                val pending = external.pending().filter { it.ownerMode == "builtin" }
                if (pending.isEmpty()) return@withLock
                val key = settings.apiKeyFlow.first()
                val client = testClient ?: key?.takeIf { it.isNotBlank() }?.let { com.example.lianxu.agent.DeepSeekAgentClient(it) }
                if (client == null) { _notice.value = "有待处理输入，连接内置助手后继续"; return@withLock }
                for (item in pending) {
                    if (external.control().mode != "builtin") break
                    val submission = external.prepareBuiltinPending(item.id) ?: continue
                    try {
                        _notice.value = "正在接续待处理输入"
                        com.example.lianxu.agent.AgentRunner(getApplication(), db, repository.catalog, client).run(submission)
                        _notice.value = "待处理输入已接续"
                        com.example.lianxu.data.repository.ExternalSyncWorker.now(getApplication())
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        store.fail(submission, error.message ?: "输入接续失败")
                        _notice.value = "输入仍待处理：${error.message ?: "请重试"}"
                        break
                    }
                }
            }
        }
    }
    fun submitAnnotations(localOnly:Boolean=false) {
        val id = boardId ?: return
        if (_isProcessing.value || _isImporting.value) return
        _isProcessing.value = true
        _notice.value = null
        tasks[id] = viewModelScope.launch {
            var submission: Submission? = null
            try {
                val frozen = writes.withLock { store.prepare(id) }
                submission = frozen
                if (com.example.lianxu.data.repository.ExternalRepository(db).control().mode == "external") {
                    com.example.lianxu.data.repository.ExternalRepository(db).queueSubmission(frozen)
                    com.example.lianxu.data.repository.ExternalSyncWorker.now(getApplication())
                    if (boardId == id) _notice.value = "已保存，等待外部智能体处理"
                    return@launch
                }
                val key = settings.apiKeyFlow.first()
                val repository = requireNotNull(nutrition)
                val runner = com.example.lianxu.agent.AgentRunner(getApplication(), db, repository.catalog,
                    if(localOnly)null else testClient ?: key?.takeIf { it.isNotBlank() }?.let { com.example.lianxu.agent.DeepSeekAgentClient(it) }) { if (boardId == id) _taskProgress.value = it }
                runner.run(frozen)
                if (boardId == id) {
                    // Publish the committed reply before allowing the receive animation to begin.
                    // The revision guard also prevents a delayed Room emission restoring the old draft.
                    writes.withLock { db.collaborationDao().draft(id)?.let { publishDraft(it, refreshResolved = false) } }
                    _notice.value = null
                }

            } catch (error: CancellationException) {
                val completed = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val applied = submission?.let { db.collaborationDao().task(it.task.id)?.status == "completed" } == true
                    if (!applied) submission?.let { store.fail(it, "处理已停止，输入已保留") }
                    applied
                }
                if (boardId == id) _notice.value = if (completed) "本轮已保存完成" else if (error is kotlinx.coroutines.TimeoutCancellationException) "处理超时，输入已保留，可重试" else "处理已取消，输入已保留"
                if (error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            } catch (error: Exception) {
                val message = if (error is retrofit2.HttpException) "服务请求失败（${error.code()}），输入已保留，可重试"
                    else "处理未完成，输入已保留：${error.message ?: "请重试"}"
                submission?.let { runCatching { store.fail(it, message) } }
                if (boardId == id) _notice.value = message
            } finally { tasks.remove(id); if (boardId == id) { _isProcessing.value = false; _taskProgress.value = null } }
        }
    }

    fun retryArchived(board: String) {
        if (tasks[board]?.isActive == true) return
        tasks[board] = viewModelScope.launch {
            var frozen: Submission? = null
            try {
                frozen = writes.withLock { store.prepare(board) }
                val key = settings.apiKeyFlow.first()
                com.example.lianxu.agent.AgentRunner(getApplication(), db, requireNotNull(nutrition).catalog,
                    key?.takeIf(String::isNotBlank)?.let { com.example.lianxu.agent.DeepSeekAgentClient(it) }).run(frozen)
                _notice.value = "旧日补充已按原日期处理，可在档案查看"
            } catch (error: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { frozen?.let { store.fail(it, error.message ?: "已停止") } }
                if (error is CancellationException) throw error
                _notice.value = "旧日输入已保留：${error.message}"
            } finally { tasks.remove(board) }
        }
    }
    fun performAction(action: CardAction, value: String? = null) {
        val id = boardId ?: return
        val repository = nutrition ?: return
        viewModelScope.launch {
            try {
                if (action.kind == "record_set") {
                    writes.withLock { com.example.lianxu.data.repository.ExternalRepository(db).recordSet(id, action) }
                    com.example.lianxu.data.repository.ExternalSyncWorker.now(getApplication())
                    _notice.value = "已记下这一组"
                } else if (action.kind == "start_timer") {
                    com.example.lianxu.timer.RestTimerRepository(getApplication(), db).start(requireNotNull(action.value).toInt())
                    _notice.value = "休息计时已开始"
                } else if (action.kind == "open_source") {
                    if (action.targetRef.type == "calculation") {
                        val calc = db.collaborationDao().calculation(action.targetRef.id) ?: return@launch
                        val snapshot = JSONObject(calc.inputSnapshot)
                        val profile = snapshot.optJSONObject("profile")
                        val weight = snapshot.optJSONObject("weight")
                        val goal = snapshot.optJSONObject("goal")
                        val result = com.example.lianxu.engine.DomainJson.decode<com.example.lianxu.engine.NutritionResult>(calc.result)
                        _sourceDetails.value = buildString {
                            append("计算日期：${snapshot.optString("date")}\n")
                            if (result.status == "manual") append("采用你的手动目标，保留与4/4/9换算的差额。") else {
                                append("体重：${weight?.optDouble("value")}kg\n身高：${profile?.optDouble("heightCm")}cm\n")
                                append("阶段：${when(goal?.optString("kind")) { "gain" -> "增肌"; "cut" -> "减脂"; else -> "维持" }}\n")
                                append("活动系数：${profile?.optDouble("activityFactor", 1.6)}\n")
                                result.restingKcal?.let { append("静息能量估计：约${ComponentResolver.displayNumber(it, "kcal")}kcal\n") }
                                result.maintenanceKcal?.let { append("维持能量估计：约${ComponentResolver.displayNumber(it, "kcal")}kcal\n") }
                                append("公式：Mifflin–St Jeor；当前策略：${result.policyVersion}\nhttps://pubmed.ncbi.nlm.nih.gov/2305711/")
                            }
                        }
                        return@launch
                    }
                    val plan = db.collaborationDao().plan(action.targetRef.id) ?: return@launch
                    val meals = com.example.lianxu.engine.DomainJson.decode<com.example.lianxu.engine.MealsDocument>(plan.meals).meals
                    val meal = meals.firstOrNull { it.mealId == action.mealId } ?: return@launch
                    _sourceDetails.value = meal.items.joinToString("\n\n") { "${it.name} · ${it.grams}g\nUSDA ${it.sourceId} · ${it.sourceVersion}\n${it.sourceUrl}" }
                } else writes.withLock {
                    repository.act(action, value)
                    repository.refresh(id)
                    _notice.value = if (action.kind == "mark_eaten") "已记为摄入" else "已更新餐食与合计"
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { _notice.value = error.message ?: "未完成操作，请重试" }
        }
    }

}
