package com.example.lianxu.agent

import android.content.Context
import android.util.Base64
import com.example.lianxu.data.api.Message
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.AgentCacheEntity
import com.example.lianxu.data.model.DashboardCard
import com.example.lianxu.data.repository.*
import com.example.lianxu.engine.DomainJson
import com.example.lianxu.engine.FoodCatalog
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A bounded agent loop. All tool effects are isolated until one validated commit. */
class AgentRunner(private val context: Context, private val db: AppDatabase, private val catalog: FoodCatalog,
    private val client: AgentClient?, private val progress: (String) -> Unit = {}) {
    var requestCount = 0; private set
    var toolCount = 0; private set
    suspend fun run(initial: Submission): String = withContext(Dispatchers.IO) { withTimeout(180_000) {
        var submission = initial
        var repairUsed = false
        repeat(2) { attempt ->
            AgentWorkspace.open(context, db, submission, catalog).use { work ->
                try {
                    val tools = AgentTools(work)
                    val applier = ProposalApplier(work)
                    if (client == null) {
                        val demo = JSONObject().put("contractVersion", "2").put("turnId", submission.turn.id).put("taskId", submission.task.id)
                            .put("operations", JSONArray()).put("summary", "本地演示：本轮文字和图片已保留。当前营养与餐食由本地规则计算，尚未进行AI理解。")
                        val cards = applier.finish(demo); applier.commit(cards, demo)
                        return@withTimeout "本地演示：补充已保留，尚未进行AI分析"
                    }
                    val messages = mutableListOf(Message("system", AgentProtocol.instructions + "\n表达指导：" + presentationGuide()))
                    messages.add(Message("user", inputContent(work)))
                    val trace = JSONArray()
                    val basisKey = hash(work.basisRefs().toString() + submission.boardRevision)
                    val cached = db.collaborationDao().agentCache(submission.task.id)?.takeIf { it.basis == basisKey }?.let { JSONArray(it.entriesJson).objects() }.orEmpty()
                    while (requestCount < 6) {
                        currentCoroutineContext().ensureActive()
                        progress("正在综合本轮补充 · ${requestCount + 1}/6")
                        requestCount++
                        if (requestCount == 5) messages.add(Message("user", "现在提交finish_proposal，采用现有计算和资料，不再展开查询。最后一次请求保留用于结构修复。缺失信息集中放followUp。"))
                        if (requestCount == 6) messages.add(Message("user", "这是本轮最后一次请求，立即用现有资料调用finish_proposal；缺失信息集中放followUp。"))
                        val response = client.request(messages, if (requestCount >= 5) AgentProtocol.tools.filter { it.function.name == "finish_proposal" } else AgentProtocol.tools)
                        val calls = response.tool_calls.orEmpty()
                        messages.add(Message("assistant", response.content, calls.takeIf { it.isNotEmpty() }, reasoning_content = response.reasoning_content))
                        if (calls.isEmpty()) {
                            if (repairUsed) error("INVALID_SHAPE: 未返回可应用提议")
                            repairUsed = true; messages.add(Message("user", "请调用finish_proposal提交结构化结果，不能只输出自然语言。")); continue
                        }
                        require(calls.count { it.function.name == "finish_proposal" } <= 1 && calls.indexOfFirst { it.function.name == "finish_proposal" }.let { it < 0 || it == calls.lastIndex }) { "INVALID_SHAPE: finish must be last" }
                        for (call in calls) {
                            currentCoroutineContext().ensureActive()
                            toolCount++; check(toolCount <= 12) { "MODEL_LIMIT: 已达本轮工具调用上限" }
                            val name = call.function.name
                            progress(when (name) { "stage_records" -> "正在整理训练与摄入"; "calculate" -> "正在计算并调整餐食"; "finish_proposal" -> "正在核对并更新看板"; else -> "正在查阅相关资料" })
                            val result = try {
                                val parsed = DomainJson.moshi.adapter(Any::class.java).fromJson(call.function.arguments)
                                require(parsed is Map<*, *>) { "INVALID_SHAPE: tool arguments must be one JSON object" }
                                val args = JSONObject(call.function.arguments)
                                if (name == "finish_proposal") {
                                    val cards = applier.finish(args)
                                    currentCoroutineContext().ensureActive()
                                    trace.put(JSONObject().put("name", name).put("arguments", args.toString()).put("result", JSONObject().put("ok", true).put("data", JSONObject().put("status", "validated"))))
                                    db.collaborationDao().putAgentCache(AgentCacheEntity(submission.task.id, basisKey, trace.toString(), System.currentTimeMillis()))
                                    applier.commit(cards, args)
                                    return@withTimeout args.getString("summary")
                                }
                                val existing = cached.firstOrNull { it.optString("name") == name && it.optString("arguments") == call.function.arguments && name == "search_foods" }
                                val data = existing?.getJSONObject("result")?.optJSONObject("data") ?: tools.execute(name, args)
                                if (name == "stage_records") db.collaborationDao().task(submission.task.id)?.let { task -> db.collaborationDao().putTask(task.copy(status = "staged", updatedAt = System.currentTimeMillis())) }
                                JSONObject().put("ok", true).put("data", data).put("error", JSONObject.NULL)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                if (e is SourceConflict) throw e
                                if (name == "finish_proposal") { if (repairUsed) throw e; repairUsed = true }
                                JSONObject().put("ok", false).put("data", JSONObject.NULL).put("error", JSONObject().put("code", e.message?.substringBefore(':') ?: "INVALID_SHAPE")
                                    .put("message", e.message?.take(800)).put("retryable", false))
                            }
                            val entry = JSONObject().put("name", name).put("arguments", call.function.arguments).put("result", result)
                            trace.put(entry)
                            db.collaborationDao().putAgentCache(AgentCacheEntity(submission.task.id, basisKey, trace.toString(), System.currentTimeMillis()))
                            messages.add(Message("tool", result.toString(), tool_call_id = call.id))
                        }
                    }
                    error("MODEL_LIMIT: 已达本轮模型调用上限，输入已保留")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (attempt == 0 && e is SourceConflict && requestCount < 6) {
                        progress("资料刚有更新，正在按最新数据重新计算")
                        val board = requireNotNull(db.collaborationDao().board(submission.task.boardId))
                        submission = submission.copy(boardRevision = board.revision, cards = db.collaborationDao().cards(board.id).map { DomainJson.decode<DashboardCard>(it.data) })
                    } else throw e
                }
            }
        }
        error("VERSION_CONFLICT: 资料再次更新，请重新发送")
    } }
    private fun presentationGuide(): String = runCatching { context.assets.open("assistant/intent-presentation.v1.json").bufferedReader().use { it.readText() } }.getOrDefault("根据问题自由组合文本、指标、清单与趋势；不强制展示缺数据卡片。")
    private suspend fun inputContent(work: AgentWorkspace): List<Map<String, Any>> {
        val b = work.basis
        val date = java.time.LocalDate.parse(work.date)
        val relevant = JSONObject()
        var remaining = 200
        for (key in listOf("trainings", "measurements", "intakes")) {
            val items = b.getJSONArray(key).objects().filter { item ->
                val raw = item.nullString("dateContext") ?: item.getString("observed")
                val d = java.time.LocalDate.parse(AgentWorkspace.recordDate(raw)); d <= date && d >= date.minusDays(27)
            }.take(remaining)
            remaining -= items.size; relevant.put(key, JSONArray(items))
        }
        work.detailCount = 200 - remaining
        val validity = SourceValidator(db)
        val usableCards = work.submission.cards.filter { card ->
            card.entityRefs.all { validity.usable(it) } && card.blocks.all { block ->
                (block.content.calculationId?.let { validity.usable(com.example.lianxu.data.model.EntityRef("calculation", it)) } ?: true) &&
                (block.content.contentRef?.let { validity.usable(it.entityRef) } ?: true) && block.content.entityRefs.all { validity.usable(it) }
            }
        }
        val board = JSONArray(usableCards.map { card ->
            JSONObject(DomainJson.encode(card.copy(annotationText = null, attachmentId = null, annotationImageUri = null, annotationAnchor = null)))
                .put("readableContent", ComponentResolver(db).readable(card))
        })
        val context = JSONObject().put("contractVersion", "2").put("turnId", work.submission.turn.id).put("taskId", work.submission.task.id)
            .put("submissionId", work.submission.turn.submissionId).put("dateContext", JSONObject(work.dateContext)).put("focus", work.annotations.objects().firstOrNull()?.opt("target"))
            .put("annotations", work.annotations).put("inputParts", b.getJSONObject("input").getString("rawText"))
            .put("currentProfile", b.optJSONObject("profile")).put("activeGoal", b.getJSONArray("goals").objects().firstOrNull { it.getString("startsOn") <= work.date && (it.isNull("endsOn") || it.getString("endsOn") > work.date) })
            .put("activeNutritionPolicy", b.getJSONArray("policies").objects().filter { it.getString("startsOn") <= work.date }.maxByOrNull { it.getString("startsOn") })
            .put("activeKnowledge", JSONArray(b.getJSONArray("knowledge").objects().filter { it.getString("status") == "current" && it.getString("effectiveFrom") <= work.date && (it.isNull("effectiveUntil") || it.getString("effectiveUntil") >= work.date) &&
                runCatching { JSONObject(it.getString("origin")).optJSONArray("inputRefs")?.toString() }.getOrNull()?.let { refs -> validity.refsUsable(refs) } != false }))
            .put("relevantRecords", relevant).put("currentBoard", board).put("baseRevisions", work.basisRefs())
            .put("currentPlan", b.getJSONArray("plans").objects().firstOrNull { it.getString("dateContext") == work.dateContext })
            .put("mealProgress", b.optJSONArray("mealProgress"))
            .put("phaseAssessments", JSONArray(b.getJSONArray("analyses").objects().filter { it.getString("kind")=="assessment" && it.getString("freshness")=="current" && validity.refsUsable(it.getString("inputRefs")) }.takeLast(4)))
            .put("calculations", JSONArray(b.getJSONArray("calculations").objects().filter { validity.refsUsable(it.getString("inputRefs")) }.takeLast(12).map { JSONObject(it.toString()).also { item -> item.remove("inputSnapshot") } })).put("constraints", JSONObject().put("date", work.date).put("preserveLockedAndEaten", true))
        val parts = mutableListOf<Map<String, Any>>(mapOf("type" to "text", "text" to context.toString()))
        var bytes = 0L
        for (item in b.getJSONArray("attachments").objects()) {
            val attachmentId = item.getString("id")
            val file = File(this.context.filesDir, item.getString("localPath")).canonicalFile
            require(file.toPath().startsWith(this.context.filesDir.canonicalFile.toPath()) && file.isFile) { "NOT_FOUND: attachment" }
            bytes += file.length(); require(bytes <= 24L * 1024 * 1024) { "INVALID_RANGE: 本轮图片超过24MB，请减少图片后重试" }
            val data = file.readBytes()
            require(hashBytes(data) == item.getString("sha256")) { "INVALID_SHAPE: 图片文件校验失败" }
            parts.add(mapOf("type" to "text", "text" to "下张图片的attachmentId=$attachmentId；按对应annotation解释。"))
            parts.add(mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:${item.getString("mimeType")};base64,${Base64.encodeToString(data, Base64.NO_WRAP)}")))
        }
        return parts
    }
    private fun hash(value: String) = hashBytes(value.toByteArray())
    private fun hashBytes(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 255) }
}
