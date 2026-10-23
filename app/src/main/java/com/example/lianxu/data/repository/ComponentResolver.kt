package com.example.lianxu.data.repository

import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.model.*
import com.example.lianxu.engine.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class ChartPoint(val label: String, val value: Double)
data class ResolvedComponent(val id: String, val type: String, val label: String, val text: String,
    val actions: List<CardAction> = emptyList(), val options: List<ChoiceOption> = emptyList(),
    val selectedKey: String? = null, val points: List<ChartPoint> = emptyList(), val attachmentPath: String? = null)

class ComponentResolver(private val db: AppDatabase) {
    suspend fun resolve(card: DashboardCard): List<ResolvedComponent> = card.blocks.map { block ->
        val c = block.content
        var points = emptyList<ChartPoint>()
        var attachment: String? = null
        val text = when (block.type) {
            "metric" -> {
                val calc = c.calculationId?.let { db.collaborationDao().calculation(it) }
                val number = calc?.let { path(JSONObject(it.result), c.resultPath.orEmpty()) as? Number }?.toDouble()
                if (number == null) "尚未完整核算" else {
                    val key = c.resultPath.orEmpty().substringAfterLast('.')
                    val missing = calc?.let { JSONObject(it.result).optJSONObject("intake")?.optJSONObject("missingItemIds")?.optJSONArray(key)?.length() ?: 0 } ?: 0
                    if (missing > 0 && c.resultPath.orEmpty().startsWith("intake.") && number == 0.0) "尚未核算"
                    else displayNumber(number, c.unit.orEmpty()) + " " + c.unit.orEmpty() +
                        if (missing > 0 && c.resultPath.orEmpty().startsWith("intake.")) "（已知小计）"
                        else if (missing > 0 && c.resultPath.orEmpty().startsWith("remaining.")) "（按已知量）" else ""
                }
            }
            "text" -> {
                val ref = c.contentRef
                if (ref == null) c.text.orEmpty() else {
                    val analysis = db.collaborationDao().analysis(ref.entityRef.id)
                    if (analysis?.revision == ref.entityRef.revision && analysis.freshness == "current") analysis.summaryText
                    else "依据已更新，正在刷新建议"
                }
            }
            "list" -> {
                c.entityRefs.map { ref -> when (ref.type) {
                    "training" -> db.recordDao().training(ref.id)?.takeIf { it.revision == ref.revision }?.let { session ->
                        listOf(session.title, session.rawSummary.orEmpty()).filter(String::isNotBlank).joinToString("\n")
                    } ?: "记录已更新"
                    "intake" -> db.recordDao().intake(ref.id)?.takeIf { it.revision == ref.revision }?.rawDescription ?: "摄入记录已更新"
                    "plan" -> {
                        val plan = db.collaborationDao().plan(ref.id)
                        if (plan == null || plan.revision != ref.revision || plan.state != "current") "方案已更新，请刷新" else {
                            val meals = DomainJson.decode<MealsDocument>(plan.meals).meals.filter { c.mealId == null || it.mealId == c.mealId }
                            meals.joinToString("\n") { meal -> meal.items.joinToString("\n") { "${it.name} ${displayNumber(it.grams, "g")}g" } }
                        }
                    }
                    else -> "暂无条目"
                } }.joinToString("\n")
            }

            "choice" -> c.options.firstOrNull { it.key == c.selectedKey }?.label.orEmpty()
            "trend" -> {
                val result = c.calculationId?.let { db.collaborationDao().calculation(it) }?.let { JSONObject(it.result) }
                val array = result?.optJSONArray("points") ?: JSONArray()
                points = (0 until array.length()).map { i -> array.getJSONObject(i).let { ChartPoint(it.getString("date"), it.getDouble("value")) } }
                result?.optString("summary").orEmpty()
            }
            "image" -> {
                attachment = c.attachmentId?.let { db.collaborationDao().attachment(it)?.localPath }
                c.text.orEmpty()
            }
            else -> "不支持的组件"
        }
        ResolvedComponent(block.componentId, block.type, c.label.orEmpty(), text, block.actions, c.options,
            c.selectedKey, points, attachment)
    }
    suspend fun readable(card: DashboardCard): String = if (card.blocks.isEmpty()) card.value else
        resolve(card).joinToString("\n") { listOf(it.label, it.text).filter(String::isNotBlank).joinToString(": ") }
    companion object {
        fun displayNumber(value: Double, unit: String): String {
            val n = if (unit == "kg") roundStep(value, .1) else if (unit == "kcal") roundStep(value, 10.0) else roundStep(value, 1.0)
            return if (unit == "kg") String.format(Locale.US, "%.1f", n) else String.format(Locale.US, "%.0f", n)
        }
        fun path(json: JSONObject, field: String): Any? {
            var current: Any? = json
            field.split('.').forEach { segment -> current = (current as? JSONObject)?.opt(segment) }
            return current?.takeUnless { it == JSONObject.NULL }
        }
    }
}
