package com.example.lianxu.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lianxu.data.api.*
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
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.time.ZoneId

class TodayViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val settings = SettingsManager(application)
    private val store = BoardStore(db)
    private val attachments = AttachmentStore(application, db)
    private val writes = Mutex()
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
    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()
    private val _demoMode = MutableStateFlow(true)
    val demoMode = _demoMode.asStateFlow()
    private val _profileSummary = MutableStateFlow("尚未补充身体资料")
    val profileSummary = _profileSummary.asStateFlow()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val api = Retrofit.Builder().baseUrl("https://api.deepseek.com/")
        .client(OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build())
        .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(DeepSeekApi::class.java)

    init {
        viewModelScope.launch {
            try {
                store.recoverInterrupted()
                // Transitional placeholders carry no fabricated health metrics.
                val zone = AppRepository(db).getProfile()?.preferredZoneId?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
                val id = store.open(listOf(
                    DashboardCard(id = "training-input", title = "训练记录", value = "长按补充今天的训练", span = CardSpan.MEDIUM),
                    DashboardCard(id = "intake-input", title = "饮食补充", value = "可选：补充实际摄入", span = CardSpan.MEDIUM),
                    DashboardCard(id = "assistant-input", title = "当日建议", value = "正在整理今日信息", span = CardSpan.LARGE)), LocalDate.now(zone), zone)
                boardId = id
                val repository = NutritionBoardRepository.create(application, db)
                nutrition = repository
                launch {
                    val records = AppRepository(db)
                    combine(records.archiveFlow, records.trainings, records.intakes) { _, _, _ -> Unit }.collect {
                        _isPlanning.value = true
                        try { writes.withLock { repository.refresh(id) } }
                        catch (error: CancellationException) { throw error }
                        catch (error: Exception) { _notice.value = "今日规划未完成，原方案保留为上次结果：${error.message}" }
                        finally { _isPlanning.value = false }
                    }
                }
                store.watchCards(id).collect { cards ->
                    val resolver = ComponentResolver(db)
                    _resolved.value = cards.associate { it.id to resolver.resolve(it) }
                    _cards.value = cards
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
            try { settings.apiKeyFlow.collect { _demoMode.value = it.isNullOrBlank() } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { _notice.value = "无法读取密钥，请在设置中重新保存" }
        }
    }

    private fun edit(transform: (List<DashboardCard>) -> List<DashboardCard>) {
        val id = boardId ?: return
        viewModelScope.launch {
            try { writes.withLock { store.edit(id, transform) } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { _notice.value = "保存补充失败，请重试：${error.message}" }
        }
    }
    fun toggleAnnotationMode(cardId: String) = edit { cards -> cards.map {
        if (it.id == cardId) it.copy(isAnnotationMode = !it.isAnnotationMode) else it } }
    fun updateAnnotationText(cardId: String, text: String) = edit { cards -> cards.map {
        if (it.id == cardId) it.copy(annotationText = text, isAnnotationMode = true) else it } }
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
    fun getApiKey(callback: (String?) -> Unit) {
        viewModelScope.launch {
            try { callback(settings.apiKeyFlow.first()) }
            catch (error: Exception) { _notice.value = "读取密钥失败，请重新填写" }
        }
    }
    fun saveApiKey(key: String) {
        viewModelScope.launch {
            try { settings.saveApiKey(key); _notice.value = "设置已保存" }
            catch (error: Exception) { _notice.value = "密钥保存失败，请重试" }
        }
    }
    fun submitAnnotations() {
        val id = boardId ?: return
        if (_isProcessing.value || _isImporting.value) return
        _isProcessing.value = true
        _notice.value = null
        viewModelScope.launch {
            var submission: Submission? = null
            try {
                val frozen = writes.withLock { store.prepare(id) }
                submission = frozen
                val key = settings.apiKeyFlow.first()
                val result = if (key.isNullOrBlank()) {
                    delay(600)
                    _notice.value = "本地演示：补充已保存，尚未进行AI分析"
                    frozen.cards // No random health values and no fabricated analysis.
                } else {
                    check(frozen.cards.none { (!it.annotationText.isNullOrBlank() || it.attachmentId != null) && it.blocks.isNotEmpty() }) { "这项智能分析尚未启用，补充已保存；餐食可先用卡片按钮调整" }
                    check(frozen.cards.none { it.attachmentId != null }) { "图片已保存；本阶段尚未接通视觉请求，请保留批注稍后发送" }
                    legacyTextUpdate(key, frozen.cards)
                }
                store.complete(frozen, result)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                val message = if (error is retrofit2.HttpException) "服务请求失败（${error.code()}），输入已保留，可重试"
                    else "处理未完成，输入已保留：${error.message ?: "请重试"}"
                submission?.let { runCatching { store.fail(it, message) } }
                _notice.value = message
            } finally { _isProcessing.value = false }
        }
    }

    fun performAction(action: CardAction, value: String? = null) {
        val id = boardId ?: return
        val repository = nutrition ?: return
        viewModelScope.launch {
            try {
                if (action.kind == "open_source") {
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
                    _notice.value = if (action.kind == "mark_eaten") "已保存实际摄入，规划调整不会改写这条记录" else "已更新餐食与合计"
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { _notice.value = error.message ?: "未完成操作，请重试" }
        }
    }

    // Kept only for the handover's text adapter. Full tool/vision orchestration is stage three.
    private suspend fun legacyTextUpdate(key: String, cards: List<DashboardCard>): List<DashboardCard> {
        val input = cards.filter { !it.annotationText.isNullOrBlank() }.joinToString("\n") {
            "cardId=${it.id}; revision=${it.revision}; title=${it.title}; snapshot=${it.value}; note=${it.annotationText}"
        }
        val response = api.createChatCompletion("Bearer $key", ChatCompletionRequest(messages = listOf(
            Message("system", "Respond to the user's card annotations. Do not invent measurements or nutritional numbers. Use update_cards for text-only card responses."),
            Message("user", input)), tools = listOf(Tool(function = FunctionDefinition("update_cards", "Update card text", Parameters(
                properties = mapOf("cards" to PropertyDefinition("array", "Card updates", ItemDefinition("object", mapOf(
                    "cardId" to PropertyDefinition("string", "Existing card ID"),
                    "value" to PropertyDefinition("string", "Response text")), listOf("cardId", "value")))), required = listOf("cards")))))))
        val call = response.choices.firstOrNull()?.message?.tool_calls?.singleOrNull { it.function.name == "update_cards" }
            ?: error("服务未返回有效结果")
        val list = JSONObject(call.function.arguments).getJSONArray("cards")
        require(list.length() > 0) { "服务返回空结果" }
        val updates = mutableMapOf<String, String>()
        for (index in 0 until list.length()) {
            val item = list.getJSONObject(index)
            val id = item.getString("cardId")
            require(cards.any { it.id == id } && !updates.containsKey(id)) { "返回卡片标识无效" }
            val value = item.getString("value")
            require(value.isNotBlank()) { "返回内容为空" }
            updates[id] = value
        }
        return cards.map { card -> updates[card.id]?.let { card.copy(value = it, revision = card.revision + 1) } ?: card }
    }
}
