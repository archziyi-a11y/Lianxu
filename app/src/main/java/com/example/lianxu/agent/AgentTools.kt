package com.example.lianxu.agent

import com.example.lianxu.data.entity.CalculationEntity
import com.example.lianxu.data.model.EntityRef
import com.example.lianxu.engine.*
import com.example.lianxu.engine.MealSlot
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class AgentTools(val work: AgentWorkspace) {
    private var readCount = 0
    suspend fun execute(name: String, a: JSONObject): JSONObject = when (name) {
        "read_records" -> read(a)
        "search_foods" -> search(a)
        "stage_records" -> { a.shape("operations"); work.stage(a.getJSONArray("operations")) }
        "stage_estimated_food" -> estimate(a)
        "calculate" -> calculate(a)
        else -> error("OUT_OF_SCOPE: unknown tool $name")
    }
    private suspend fun read(a: JSONObject): JSONObject {
        a.shape("entityType", "startDate", "endDate", "ids", "query", "limit", "cursor")
        val limit = a.getInt("limit"); require(limit in 1..50 && work.detailCount + readCount + limit <= 200) { "MODEL_LIMIT: records" }
        val type = a.getString("entityType")
        val db = work.overlay; val d = work.dao
        val items = when (type) {
            "measurement" -> db.measurementDao().all().map { JSONObject(DomainJson.encode(it)) }
            "training" -> db.recordDao().allTrainings().map { JSONObject(DomainJson.encode(it)) }
            "intake" -> db.recordDao().allIntakes().map { JSONObject(DomainJson.encode(it)) }
            "goal" -> db.goalPhaseDao().getGoalPhasesFlow().first().map { JSONObject(DomainJson.encode(it)) }
            "plan" -> d.allPlans().map { JSONObject(DomainJson.encode(it)) }
            "knowledge" -> db.recordDao().allKnowledge().map { JSONObject(DomainJson.encode(it)) }
            "analysis" -> d.allAnalyses().map { JSONObject(DomainJson.encode(it)) }
            "turn" -> work.real.collaborationDao().turns(work.submission.task.boardId).map { JSONObject(DomainJson.encode(it)).also { t -> t.put("dateContext", it.dateContext) } }
            else -> error("OUT_OF_SCOPE: entityType")
        }
        val ids = a.optJSONArray("ids")?.strings().orEmpty()
        val start = a.nullString("startDate"); val end = a.nullString("endDate")
        start?.let { LocalDate.parse(it) }; end?.let { LocalDate.parse(it) }
        val query = a.nullString("query")
        val filtered = items.filter { item ->
            val date = listOf("dateContext", "observed", "startsOn", "effectiveFrom", "rangeEnd").firstNotNullOfOrNull { k -> item.nullString(k) }?.let { AgentWorkspace.recordDate(it) }
            (ids.isEmpty() || item.getString("id") in ids) && (start == null || date != null && date >= start) && (end == null || date != null && date <= end) &&
                (query.isNullOrBlank() || listOf("title", "text", "rawSummary", "rawDescription", "summaryText", "metric", "kind").any { item.optString(it).contains(query, true) })
        }
        val offset = a.nullString("cursor")?.toIntOrNull() ?: 0; require(offset >= 0)
        val page = filtered.drop(offset).take(limit); readCount += page.size
        return JSONObject().put("items", JSONArray(page)).put("nextCursor", if (offset + page.size < filtered.size) (offset + page.size).toString() else JSONObject.NULL)
            .put("refs", JSONArray(page.map { JSONObject().put("type", type).put("id", it.getString("id")).put("revision", it.optInt("revision", 1)) }))
    }
    private suspend fun search(a: JSONObject): JSONObject {
        a.shape("query", "state", "scene", "excludeIngredients", "limit")
        val limit = a.getInt("limit"); require(limit in 1..20)
        val state = a.nullString("state"); require(state == null || state in listOf("raw", "cooked", "as_sold", "prepared"))
        val scene = a.nullString("scene"); require(scene == null || scene in listOf("home", "canteen", "takeout"))
        val excluded = exclusions() + a.optJSONArray("excludeIngredients")?.strings().orEmpty()
        val cat = work.extendedCatalog()
        val foods = cat.search(a.getString("query"), state).filter { f -> cat.permits(CatalogRecipe("check", 1, "", "home", emptyList(), listOf(FoodAmount(f.id, 100.0)), 1.0, 1.0, 1.0), excluded) }.take(limit)
        return JSONObject().put("foods", JSONArray(foods.map { JSONObject(DomainJson.encode(it)) })).put("excludedIngredients", JSONArray(excluded.distinct()))
    }
    private fun estimate(a: JSONObject): JSONObject {
        a.shape("clientRef", "name", "state", "ingredients", "allergens", "allergenStatus", "basisG", "nutrients", "carbohydrateBasis", "estimationNote")
        require(a.getDouble("basisG") == 100.0 && a.getString("name").isNotBlank() && a.getString("estimationNote").isNotBlank())
        require(a.getString("state") in listOf("raw", "cooked", "as_sold", "prepared"))
        val n = a.getJSONObject("nutrients"); n.shape(*NUTRIENT_KEYS.toTypedArray())
        NUTRIENT_KEYS.forEach { if (!n.isNull(it)) require(n.getDouble(it).isFinite() && n.getDouble(it) >= 0) }
        val id = work.allocated(a.getString("clientRef"))
        val snapshot = JSONObject(a.toString()).put("foodId", id).put("source", "model_estimate").put("turnId", work.submission.turn.id)
        require(work.estimates[id]?.toString()?.let { it == snapshot.toString() } != false) { "DUPLICATE_OPERATION: estimate clientRef" }
        work.estimates[id] = snapshot
        return snapshot
    }
    private suspend fun exclusions() = work.overlay.profileDao().getProfile()?.excludedIngredients?.let { JSONArray(it).strings() }.orEmpty()
    private fun requireDate(date: String) { LocalDate.parse(date); require(date == work.date) { "OUT_OF_SCOPE: calculation date" } }
    suspend fun calculate(a: JSONObject): JSONObject {
        a.shape("operation", "input", "stageId")
        require(a.nullString("stageId") == work.stageId) { "VERSION_CONFLICT: stageId expected=${work.stageId}, received=${a.nullString("stageId")}" }
        val operation = a.getString("operation")
        val input = work.resolve(a.getJSONObject("input")) as JSONObject
        val d = work.dao; val db = work.overlay; val cat = work.extendedCatalog()
        val result: JSONObject
        val refs = work.currentRefs()
        when (operation) {
            "nutrition_target" -> {
                input.shape("date", "profileRef", "goalRef", "measurementRefs"); requireDate(input.getString("date"))
                input.optJSONObject("profileRef")?.let { work.validateRefs(JSONArray().put(it)) }
                input.optJSONObject("goalRef")?.let { work.validateRefs(JSONArray().put(it)) }
                input.optJSONArray("measurementRefs")?.let { work.validateRefs(it) }
                result = JSONObject(DomainJson.encode(work.nutrition.refresh(work.submission.task.boardId, false)))
            }
            "intake_totals" -> {
                input.shape("date"); requireDate(input.getString("date"))
                result = JSONObject(DomainJson.encode(work.nutrition.intakeTotals(db.recordDao().allIntakes().filter { AgentWorkspace.recordDate(it.dateContext) == work.date })))
            }
            "food_totals" -> {
                input.shape("items", "mealId")
                val items = input.optJSONArray("items")?.objects()?.map { item -> item.shape("foodId", "grams"); FoodAmount(item.getString("foodId"), item.getDouble("grams")) }
                    ?: d.planForDate(work.dateContext)?.let { DomainJson.decode<MealsDocument>(it.meals).meals.single { meal -> meal.mealId == input.getString("mealId") }.items.map { FoodAmount(it.foodId, it.grams) } }
                    ?: error("MISSING_INPUT: items or mealId")
                result = JSONObject(DomainJson.encode(cat.totals(items)))
            }
            "training_summary" -> {
                input.shape("startDate", "endDate", "exerciseKey")
                val start = LocalDate.parse(input.getString("startDate")); val end = LocalDate.parse(input.getString("endDate"))
                require(start <= end && end <= LocalDate.parse(work.date))
                val sessions = db.recordDao().allTrainings().filter { LocalDate.parse(AgentWorkspace.recordDate(it.dateContext)) in start..end }
                val key = input.nullString("exerciseKey")
                result = JSONObject().put("frequency", JSONObject(DomainJson.encode(trainingFrequency(sessions.map { LocalDate.parse(AgentWorkspace.recordDate(it.dateContext)) to it.kind }, end))))
                    .put("exercises", JSONArray(sessions.flatMap { JSONArray(it.exercises).objects() }.map { DomainJson.decode<RecordedExercise>(it.toString()) }
                        .filter { key == null || it.canonicalKey == key || it.name == key }.map { JSONObject(DomainJson.encode(summarizeExercise(it))) }))
            }
            "weight_trend" -> {
                input.shape("endDate", "windowDays"); requireDate(input.getString("endDate")); require(input.getInt("windowDays") in listOf(7, 14, 28))
                val end = LocalDate.parse(work.date)
                val trend = weightTrend(db.measurementDao().all().filter { it.metric == "weight" }.map { WeightPoint(LocalDate.parse(AgentWorkspace.recordDate(it.observed)), it.value.toDouble()) }
                    .filter { it.date >= end.minusDays(input.getInt("windowDays").toLong() - 1) }, end)
                result = JSONObject().put("points", JSONArray(trend.points.map { JSONObject().put("date", it.date.toString()).put("value", it.value) }))
                    .put("recentMean", trend.recentMean).put("previousMean", trend.previousMean).put("weeklyChangeKg", trend.weeklyChangeKg).put("weeklyChangePct", trend.weeklyChangePct)
                    .put("summary", if (trend.weeklyChangeKg == null) "记录覆盖不足，保留测量趋势" else "按两段周均体重计算")
            }
            "meal_candidates" -> {
                input.shape("date", "planRef", "scope", "mealKey", "scene", "constraints"); requireDate(input.getString("date"))
                val plan = requireNotNull(d.planForDate(work.dateContext)) { "MISSING_INPUT: nutrition profile/plan" }
                input.optJSONObject("planRef")?.let { work.validateRefs(JSONArray().put(it)) }
                val target = DomainJson.decode<NutritionResult>(requireNotNull(d.calculation(plan.targetCalculationId)).result)
                val meals = DomainJson.decode<MealsDocument>(plan.meals).meals
                val intake = db.recordDao().allIntakes().filter { AgentWorkspace.recordDate(it.dateContext) == work.date }
                val eaten = meals.filter { meal -> intake.any { it.mealKey == meal.slotKey } }.map { it.mealId }.toSet()
                val scope = input.getString("scope"); require(scope in listOf("day", "meal"))
                val key = input.nullString("mealKey")
                val mid = if (scope == "meal") meals.firstOrNull { it.slotKey == key || it.mealId == key }?.mealId ?: error("MISSING_INPUT: mealKey must be one of ${meals.map { it.slotKey }}") else null
                val scene = input.getString("scene"); require(scene in listOf("home", "canteen", "takeout"))
                val constraints = input.optJSONObject("constraints") ?: JSONObject(); constraints.shape("excludeIngredients")
                val exclusions = exclusions() + constraints.optJSONArray("excludeIngredients")?.strings().orEmpty()
                val profile = db.profileDao().getProfile()
                val planner = MealPlanner(cat)
                val slots = profile?.mealSlots?.let { JSONArray(it) }?.objects()?.map { MealSlot(it.getString("key"), it.getString("label"), it.getDouble("ratio")) }?.takeIf { it.isNotEmpty() } ?: planner.defaultSlots
                val proposed = meals.map { if (!it.locked && it.mealId !in eaten && (mid == null || mid == it.mealId)) it.copy(scene = scene) else it }
                val search = planner.search(target.targets, slots, scene, exclusions, proposed, mid, mid?.let { id -> meals.single { it.mealId == id }.recipeId }, eaten, work.nutrition.intakeTotals(intake).knownSubtotal)
                result = JSONObject(DomainJson.encode(search)).put("planRef", JSONObject(DomainJson.encode(EntityRef("plan", plan.id, plan.revision))))
            }
            else -> error("OUT_OF_SCOPE: calculation operation")
        }
        val id = refId("${work.submission.task.id}:${work.stageId}:$operation:$input:$refs:$result")
        val snapshot = JSONObject().put("input", input).put("stageId", work.stageId).put("records", JSONObject().put("profile", work.overlay.profileDao().getProfile()?.let { JSONObject(DomainJson.encode(it)) })
            .put("goals", JSONArray(work.overlay.goalPhaseDao().getGoalPhasesFlow().first().map { JSONObject(DomainJson.encode(it)) }))
            .put("policies", JSONArray(work.dao.allPolicies().map { JSONObject(DomainJson.encode(it)) }))
            .put("plans", JSONArray(work.dao.allPlans().map { JSONObject(DomainJson.encode(it)) }))
            .put("measurements", JSONArray(work.overlay.measurementDao().all().map { JSONObject(DomainJson.encode(it)) }))
            .put("trainings", JSONArray(work.overlay.recordDao().allTrainings().map { JSONObject(DomainJson.encode(it)) }))
            .put("intakes", JSONArray(work.overlay.recordDao().allIntakes().map { JSONObject(DomainJson.encode(it)) })))
        if (operation == "food_totals") {
            val foodIds = input.optJSONArray("items")?.objects()?.map { it.getString("foodId") }
                ?: d.planForDate(work.dateContext)?.let { DomainJson.decode<MealsDocument>(it.meals).meals.firstOrNull { meal -> meal.mealId == input.nullString("mealId") }?.items?.map { it.foodId } }.orEmpty()
            snapshot.put("foodSources", JSONArray(foodIds.distinct().map { foodId ->
                JSONObject(DomainJson.encode(requireNotNull(cat.byId[foodId]))).also { source -> work.estimates[foodId]?.let { source.put("estimationNote", it.getString("estimationNote")) } }
            }))
        }
        if (d.calculation(id) == null) d.putCalculation(CalculationEntity(id, operation, METHOD_VERSION, snapshot.toString(), refs.toString(), result.toString(), System.currentTimeMillis()))
        work.toolCalculations.add(id)
        return JSONObject().put("calculationId", id).put("result", result).put("inputRefs", refs).put("methodVersion", METHOD_VERSION).put("stageId", work.stageId)
    }
}
