package com.example.lianxu.agent

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.model.*
import com.example.lianxu.data.repository.*
import com.example.lianxu.engine.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Immutable read basis plus a disposable Room overlay. Nothing staged here mutates the real database. */
class AgentWorkspace private constructor(val context: Context, val real: AppDatabase, val submission: Submission,
    val catalog: FoodCatalog, val basis: JSONObject) : AutoCloseable {
    lateinit var overlay: AppDatabase; private set
    val estimates = linkedMapOf<String, JSONObject>()
    val mapping = linkedMapOf<String, String>()
    var detailCount: Int = 0
    var stageId: String? = null; private set
    var staged = JSONArray(); private set
    val toolCalculations = linkedSetOf<String>()
    val dateContext = submission.turn.dateContext
    val date: String = JSONObject(dateContext).getString("date")
    val annotations = JSONArray(basis.getJSONObject("input").getString("annotationsJson"))
    private val annotationIds = annotations.objects().map { it.getString("annotationId") }.toSet()
    val dao get() = overlay.collaborationDao()
    val nutrition get() = NutritionBoardRepository(overlay, extendedCatalog())
    fun extendedCatalog() = FoodCatalog(catalog.foods + estimates.values.map { e ->
        CatalogFood(e.getString("foodId"), e.getString("name"), e.getString("state"), DomainJson.decode(e.getJSONObject("nutrients").toString()),
            "model_estimate", submission.turn.id, "", ingredients = e.optJSONArray("ingredients")?.strings() ?: emptyList(),
            allergens = e.optJSONArray("allergens")?.strings() ?: emptyList(), allergenStatus = e.optString("allergenStatus", "unknown"),
            carbohydrateBasis = e.optString("carbohydrateBasis", "unknown"))
    }, catalog.recipes)
    fun resolve(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { out -> value.keys().forEach { k -> out.put(k, resolve(value.get(k))) } }
        is JSONArray -> JSONArray().also { out -> (0 until value.length()).forEach { out.put(resolve(value.get(it))) } }
        is String -> mapping[value] ?: value
        else -> value
    }
    fun allocated(client: String) = mapping.getOrPut(client) { refId("${submission.turn.submissionId}:$client") }
    suspend fun reset() {
        if (::overlay.isInitialized) overlay.close()
        overlay = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val d = dao
        basis.optJSONObject("profile")?.let { overlay.profileDao().insert(DomainJson.decode(it.toString())) }
        basis.getJSONArray("goals").objects().forEach { overlay.goalPhaseDao().insert(DomainJson.decode(it.toString())) }
        basis.getJSONArray("measurements").objects().forEach { overlay.measurementDao().insert(DomainJson.decode(it.toString())) }
        basis.getJSONArray("trainings").objects().forEach { overlay.recordDao().insertTraining(DomainJson.decode(it.toString())) }
        basis.getJSONArray("intakes").objects().forEach { overlay.recordDao().insertIntake(DomainJson.decode(it.toString())) }
        basis.getJSONArray("knowledge").objects().forEach { overlay.recordDao().insertKnowledge(DomainJson.decode(it.toString())) }
        basis.getJSONArray("plans").objects().forEach { d.putPlan(DomainJson.decode(it.toString())) }
        basis.getJSONArray("calculations").objects().forEach { d.putCalculation(DomainJson.decode(it.toString())) }
        basis.getJSONArray("analyses").objects().forEach { d.putAnalysis(DomainJson.decode(it.toString())) }
        basis.getJSONArray("policies").objects().forEach { d.putPolicy(DomainJson.decode(it.toString())) }
        basis.getJSONArray("snapshots").objects().forEach { d.putEntitySnapshot(DomainJson.decode(it.toString())) }
        basis.getJSONArray("attachments").objects().forEach { d.putAttachment(DomainJson.decode(it.toString())) }
        d.putBoard(DomainJson.decode(basis.getJSONObject("board").toString()))
        d.putCards(submission.cards.map { Card(it.id, submission.task.boardId, DomainJson.encode(it.copy(annotationText = null, attachmentId = null, annotationImageUri = null, isAnnotationMode = false, annotationAnchor = null))) })
        toolCalculations.clear()
    }
    suspend fun stage(operations: JSONArray): JSONObject {
        require(operations.length() <= 20) { "INVALID_RANGE: operations>20" }
        val previous = staged
        val previousCalculations = toolCalculations.mapNotNull { dao.calculation(it) }
        try {
            reset()
            applyOperations(operations)
            nutrition.refresh(submission.task.boardId, preserveComposition = false)
            staged = JSONArray(operations.toString())
            stageId = refId("${submission.task.id}:${staged}")
            return JSONObject().put("stageId", stageId).put("previewRefs", currentRefs()).put("clientRefs", JSONObject(mapping as Map<*, *>))
                .put("board", JSONArray(dao.cards(submission.task.boardId).map { JSONObject(it.data) })).put("errors", JSONArray())
                .put("nextAction", "事实已暂存。calculate之后直接finish_proposal(stageId)，operations只需新增分析或餐食替换，应用原样展开本stage全部记录。不要为了加入分析而重新stage_records。")
        } catch (e: Exception) {
            reset(); if (previous.length() > 0) { applyOperations(previous); nutrition.refresh(submission.task.boardId, false) }
            previousCalculations.forEach { calc -> if (dao.calculation(calc.id) == null) dao.putCalculation(calc); toolCalculations.add(calc.id) }
            throw e
        }
    }
    private fun allowed(p: JSONObject, fields: Set<String>) { require((p.keysSet() - fields).isEmpty()) { "INVALID_SHAPE: payload ${p.keysSet() - fields}" } }
    private fun checkedDate(raw: Any?): String {
        val dc = when (raw) { is JSONObject -> raw; is String -> if (raw.startsWith("{")) JSONObject(raw) else JSONObject(dateContext).put("date", raw); else -> JSONObject(dateContext) }
        LocalDate.parse(dc.getString("date")); require(dc.getString("date") <= date) { "OUT_OF_SCOPE: future record" }
        val zone = dc.optString("zoneId", JSONObject(dateContext).getString("zoneId")); java.time.ZoneId.of(zone)
        return JSONObject().put("date", dc.getString("date")).put("zoneId", zone).put("basis", "operation_context").toString()
    }
    private fun nutrients(p: JSONObject?) {
        p ?: return
        p.shape(*NUTRIENT_KEYS.toTypedArray())
        NUTRIENT_KEYS.forEach { if (!p.isNull(it)) require(p.getDouble(it).isFinite() && p.getDouble(it) >= 0) { "INVALID_RANGE: $it" } }
    }
    private suspend fun snapshot(type: String, old: JSONObject) {
        if (dao.entitySnapshot(type, old.getString("id"), old.getInt("revision")) == null)
            dao.putEntitySnapshot(EntitySnapshotEntity(refId("$type:${old.getString("id")}:${old.getInt("revision")}"), type, old.getString("id"), old.getInt("revision"), old.toString(), System.currentTimeMillis()))
    }
    suspend fun applyOperations(operations: JSONArray) {
        val ops = operations.objects()
        require(ops.size <= 20 && ops.map { it.getString("operationId") }.distinct().size == ops.size) { "DUPLICATE_OPERATION" }
        require(ops.none { it.getString("operationId") == "apply-board" }) { "INVALID_SHAPE: reserved operationId" }
        val targets = ops.mapNotNull { it.optJSONObject("targetRef")?.let { t -> "${t.getString("type")}:${t.getString("id")}" } }
        require(targets.distinct().size == targets.size) { "DUPLICATE_OPERATION: target" }
        ops.mapNotNull { it.nullString("clientRef") }.also { require(it.distinct().size == it.size) }.forEach { allocated(it) }
        // Resolve the dependency graph before execution; cycles never fall through to partial ordering.
        fun references(value: Any?): Set<String> = when (value) {
            is JSONObject -> (value.keys().asSequence().flatMap { key ->
                val direct = if (key in listOf("id", "foodRef", "supersedesId") && value.opt(key) is String) sequenceOf(value.getString(key)) else emptySequence()
                direct + references(value.opt(key)).asSequence()
            }).toSet()
            is JSONArray -> (0 until value.length()).flatMap { references(value.get(it)) }.toSet()
            else -> emptySet()
        }
        val clients = ops.mapNotNull { op -> op.nullString("clientRef")?.let { it to op.getString("operationId") } }.toMap()
        val dependencies = ops.associate { op -> op.getString("operationId") to references(op.getJSONObject("payload")).mapNotNull { clients[it] }.toSet() }
        val ordered = mutableListOf<JSONObject>(); val done = mutableSetOf<String>()
        while (ordered.size < ops.size) {
            val ready = ops.filter { it.getString("operationId") !in done && dependencies.getValue(it.getString("operationId")).all { dependency -> dependency in done } }
                .sortedBy { when (it.getString("kind")) { "create_day_plan", "replace_plan_meal" -> 1; "create_analysis", "upsert_knowledge" -> 2; else -> 0 } }
            require(ready.isNotEmpty()) { "INVALID_SHAPE: cyclic clientRef dependency" }
            ready.forEach { ordered.add(it); done.add(it.getString("operationId")) }
        }
        for (original in ordered) {
            original.shape("operationId", "kind", "targetRef", "clientRef", "payload", "relatedAnnotationIds", "reason")
            val related = original.optJSONArray("relatedAnnotationIds")?.strings().orEmpty()
            require(related.isNotEmpty() && related.all { it in annotationIds } && original.optString("reason").isNotBlank()) { "OUT_OF_SCOPE: operation requires relatedAnnotationIds and reason" }
            val op = resolve(original) as JSONObject
            val kind = op.getString("kind")
            val target = op.optJSONObject("targetRef")
            val expectedType = when (kind.substringAfter("create_").substringAfter("update_")) {
                "training" -> "training"; "intake" -> "intake"; "measurement" -> "measurement"; "profile" -> "profile"
                else -> when (kind) { "replace_plan_meal" -> "plan"; "upsert_knowledge" -> "knowledge"; else -> null }
            }
            if (target != null) require(expectedType != null && target.getString("type") == expectedType) { "INVALID_SHAPE: targetRef.type" }
            if (kind.startsWith("create_") || kind == "set_goal") require(target == null) { "INVALID_SHAPE: creates allocate new IDs" }
            val client = original.nullString("clientRef")
            val objectId = target?.getString("id") ?: client?.let { allocated(it) } ?: refId("${submission.turn.submissionId}:${op.getString("operationId")}")
            val payload = op.getJSONObject("payload")
            val update = kind.startsWith("update_")
            val p = if (update) { payload.shape("set"); payload.getJSONObject("set") } else payload
            val now = submission.turn.createdAt
            val origin = JSONObject().put("kind", "user").put("inputBundleId", submission.turn.inputBundleId).put("turnId", submission.turn.id).put("annotationIds", JSONArray(related)).toString()
            var type: String
            var old: JSONObject? = null
            when (kind) {
                "create_training", "update_training" -> {
                    type = "training"
                    allowed(p, setOf("dateContext", "kind", "title", "durationS", "exercises", "rawSummary"))
                    old = overlay.recordDao().training(objectId)?.let { JSONObject(DomainJson.encode(it)) }
                    val fields = mergeRecord(type, objectId, p, old, target, update, now, origin)
                    fields.put("dateContext", checkedDate(fields.opt("dateContext")))
                    require(fields.getString("kind") in listOf("strength", "cardio", "mixed", "rest", "other", "resistance"))
                    fields.optJSONArray("exercises")?.objects()?.forEach { DomainJson.decode<RecordedExercise>(it.toString()).let { exercise ->
                        require(exercise.entryId.isNotBlank() && exercise.name.isNotBlank())
                        listOf(exercise.summarySets, exercise.repsPerSet, exercise.loadKg).forEach { number ->
                            require(number.kind in listOf("unknown", "exact", "range")) { "INVALID_SHAPE: NumberSpec.kind" }
                            if (number.kind != "unknown") {
                                val bounds = requireNotNull(number.bounds()) { "INVALID_RANGE: incomplete number" }
                                require(bounds.first.isFinite() && bounds.second.isFinite() && bounds.first >= 0 && bounds.second >= bounds.first)
                            }
                        }
                        require(exercise.loadBasis in listOf("unknown", "total", "per_hand", "machine_stack", "bodyweight")) { "INVALID_SHAPE: loadBasis" }
                        require(exercise.sets.map { it.setId }.distinct().size == exercise.sets.size)
                        exercise.sets.forEach { set ->
                            require(set.loadBasis in listOf("unknown", "total", "per_hand", "machine_stack", "bodyweight"))
                            require(set.side in listOf("unspecified", "both", "left", "right"))
                            require(set.reps == null || set.reps >= 0)
                            require(set.loadKg == null || set.loadKg.isFinite() && set.loadKg >= 0)
                        }
                        summarizeExercise(exercise)
                    } }
                    if (!fields.has("exercises")) fields.put("exercises", "[]") else if (fields.opt("exercises") is JSONArray) fields.put("exercises", fields.getJSONArray("exercises").toString())
                    val record = DomainJson.decode<TrainingSession>(fields.toString())
                    require(record.durationS == null || record.durationS >= 0)
                    if (update) overlay.recordDao().updateTraining(record) else overlay.recordDao().insertTraining(record)
                }
                "create_intake", "update_intake" -> {
                    type = "intake"; allowed(p, setOf("dateContext", "mealKey", "coverageKey", "mode", "items", "manualNutrients", "rawDescription", "completeness"))
                    old = overlay.recordDao().intake(objectId)?.let { JSONObject(DomainJson.encode(it)) }
                    val fields = mergeRecord(type, objectId, p, old, target, update, now, origin)
                    fields.put("dateContext", checkedDate(fields.opt("dateContext")))
                    require(fields.getString("mode") in listOf("items", "nutrient_total", "description"))
                    require(fields.optString("completeness", "partial") in listOf("partial", "complete"))
                    if (!fields.has("completeness")) fields.put("completeness", "partial")
                    // Coverage is an application-owned key, never a model-invented label.
                    fields.put("coverageKey", if (fields.getString("mode") == "nutrient_total") fields.nullString("mealKey")?.let { "meal:$it" } ?: "daily_total" else "item:$objectId")
                    val items = when (val raw = fields.opt("items")) { is JSONArray -> raw; is String -> JSONArray(raw); else -> JSONArray() }
                    items.objects().forEach { item ->
                        item.shape("itemId", "foodRef", "grams", "foodSnapshot")
                        if (!item.isNull("grams")) require(item.getDouble("grams").isFinite() && item.getDouble("grams") >= 0)
                        val foodId = item.getString("foodRef")
                        val food = extendedCatalog().byId[foodId] ?: error("UNKNOWN_FOOD: $foodId")
                        item.put("foodSnapshot", JSONObject(DomainJson.encode(food)).also { snap -> estimates[foodId]?.let { snap.put("estimationNote", it.getString("estimationNote")) } })
                    }
                    fields.put("items", items.toString())
                    val n = fields.optJSONObject("manualNutrients")
                    if (n != null) { nutrients(n); fields.put("manualNutrients", n.toString()) }
                    if (fields.getString("mode") == "nutrient_total") require(n != null || fields.opt("manualNutrients") is String)
                    val record = DomainJson.decode<IntakeEntry>(fields.toString())
                    if (update) overlay.recordDao().updateIntake(record) else overlay.recordDao().insertIntake(record)
                }
                "create_measurement", "update_measurement" -> {
                    type = "measurement"; allowed(p, setOf("metric", "observed", "measuredAt", "value", "unit", "note"))
                    old = overlay.measurementDao().all().find { it.id == objectId }?.let { JSONObject(DomainJson.encode(it)) }
                    val fields = mergeRecord(type, objectId, p, old, target, update, now, origin)
                    fields.put("observed", checkedDate(fields.opt("observed")))
                    val value = fields.getDouble("value")
                    val unit = fields.getString("unit")
                    require(value.isFinite() && when (fields.getString("metric")) { "weight" -> unit == "kg" && value > 0 && value <= 500; "body_fat" -> unit == "%" && value > 0 && value < 100; "waist", "height" -> unit == "cm" && value > 0 && value <= 300; else -> false }) { "INVALID_UNIT or INVALID_RANGE: measurement" }
                    overlay.measurementDao().insert(DomainJson.decode(fields.toString()))
                }
                "update_profile" -> {
                    type = "profile"; allowed(p, setOf("heightCm", "birthDate", "formulaSex", "activityFactor", "preferredZoneId", "mealSlots", "defaultScene", "excludedIngredients", "preferredFoods", "nutritionMode", "manualTargets"))
                    val profile = overlay.profileDao().getProfile()
                    old = profile?.let { JSONObject(DomainJson.encode(it)) }
                    val defaults = Profile(objectId, null, null, null, null, JSONObject(dateContext).getString("zoneId"), "[]", "home", "[]", "[]", "standard_adult", null, 0, now, now)
                    val fields = mergeRecord(type, profile?.id ?: objectId, p, old ?: JSONObject(DomainJson.encode(defaults)), target, profile != null, now, origin, profile = true)
                    listOf("mealSlots", "excludedIngredients", "preferredFoods").forEach { if (fields.opt(it) is JSONArray) fields.put(it, fields.getJSONArray(it).toString()) }
                    fields.optJSONObject("manualTargets")?.let { nutrients(it); fields.put("manualTargets", it.toString()) }
                    val record = DomainJson.decode<Profile>(fields.toString())
                    require(record.heightCm == null || record.heightCm in 80f..260f)
                    require(record.formulaSex == null || record.formulaSex in listOf("male", "female"))
                    require(record.activityFactor == null || record.activityFactor in 1.0f..3.0f)
                    record.birthDate?.let { require(LocalDate.parse(it) <= LocalDate.parse(date)) }
                    java.time.ZoneId.of(record.preferredZoneId)
                    require(record.defaultScene in listOf("home", "canteen", "takeout"))
                    require(record.nutritionMode in listOf("standard_adult", "manual"))
                    overlay.profileDao().insert(record)
                }
                "set_goal" -> {
                    type = "goal"; payload.shape("kind", "startsOn", "note")
                    require(payload.getString("kind") in listOf("gain", "cut", "maintain"))
                    val starts = payload.optString("startsOn", date); LocalDate.parse(starts)
                    require(starts == date) { "OUT_OF_SCOPE: goal begins today" }
                    overlay.goalPhaseDao().getGoalPhasesFlow().first().filter { it.endsOn == null }.forEach { snapshot("goal", JSONObject(DomainJson.encode(it))) }
                    overlay.goalPhaseDao().endOpenPhases(starts, now)
                    overlay.goalPhaseDao().insert(GoalPhase(objectId, payload.getString("kind"), starts, null, payload.nullString("note"), 1, now, now))
                }
                "create_analysis" -> {
                    type = "analysis"; payload.shape("kind", "rangeStart", "rangeEnd", "summaryText", "blocks", "calculationIds", "inputRefs")
                    val start = payload.optString("rangeStart", date); val end = payload.optString("rangeEnd", date)
                    require(LocalDate.parse(start) <= LocalDate.parse(end) && end <= date)
                    val refs = payload.optJSONArray("inputRefs") ?: currentRefs()
                    validateRefs(refs, allowHistorical = true)
                    val calcs = payload.optJSONArray("calculationIds") ?: JSONArray()
                    calcs.strings().forEach { require(dao.calculation(it) != null) { "NOT_FOUND: calculation" } }
                    require(payload.getString("summaryText").isNotBlank())
                    require(payload.optString("kind", "reply") in listOf("reply", "assessment"))
                    dao.putAnalysis(AnalysisEntity(objectId, 1, payload.optString("kind", "reply"), start, end, refs.toString(), payload.getString("summaryText"),
                        (payload.optJSONArray("blocks") ?: JSONArray()).toString(), calcs.toString(), "current", now, now))
                }
                "upsert_knowledge" -> {
                    type = "knowledge"; payload.shape("kind", "text", "effectiveFrom", "effectiveUntil", "status", "expectedRevision")
                    val existing = overlay.recordDao().allKnowledge().find { it.id == objectId }
                    old = existing?.let { JSONObject(DomainJson.encode(it)) }
                    if (existing != null) require(target?.getInt("revision") == existing.revision)
                    val start = payload.optString("effectiveFrom", date); require(start <= date); LocalDate.parse(start)
                    val end = payload.nullString("effectiveUntil"); end?.let { require(LocalDate.parse(it) >= LocalDate.parse(start)) }
                    require(payload.getString("text").isNotBlank())
                    overlay.recordDao().putKnowledge(KnowledgeNote(objectId, (existing?.revision ?: 0) + 1, payload.getString("kind"), payload.getString("text"),
                        JSONObject(origin).put("kind", "assistant").put("inputRefs", currentRefs()).toString(), start, end, payload.optString("status", "current"), existing?.supersedesId, existing?.createdAt ?: now, now))
                }
                "replace_plan_meal" -> {
                    type = "plan"; payload.shape("mealId", "meal", "calculationId", "meals")
                    val plan = requireNotNull(dao.plan(objectId)); require(target?.getInt("revision") == plan.revision) { "VERSION_CONFLICT: plan expected=${plan.revision}, received=${target?.optInt("revision")}" }
                    require(plan.dateContext == dateContext && plan.state == "current")
                    old = JSONObject(DomainJson.encode(plan))
                    val meals = DomainJson.decode<MealsDocument>(plan.meals).meals
                    val eatenSlots = overlay.recordDao().allIntakes().filter { recordDate(it.dateContext) == date }.mapNotNull { it.mealKey }.toSet()
                    val changes = payload.optJSONArray("meals")?.objects() ?: listOf(payload)
                    require(changes.map { it.getString("mealId") }.distinct().size == changes.size)
                    val replacements = changes.associate { item ->
                        val mid = item.getString("mealId")
                        val before = meals.single { it.mealId == mid }; require(!before.locked && before.slotKey !in eatenSlots) { "OUT_OF_SCOPE: preserved/eaten meal" }
                        val calc = requireNotNull(dao.calculation(item.getString("calculationId")))
                        require(calc.type == "meal_candidates" && calc.id in toolCalculations)
                        val exact = JSONObject(calc.result).getJSONArray("meals").objects().single { it.getString("mealId") == mid }
                        val meal = DomainJson.decode<PlannedMeal>(item.getJSONObject("meal").toString())
                        require(DomainJson.decode<PlannedMeal>(exact.toString()) == meal) { "INVALID_RANGE: meal differs from calculated result" }
                        mid to meal
                    }
                    dao.putPlan(plan.copy(revision = plan.revision + 1, meals = DomainJson.encode(MealsDocument(meals.map { replacements[it.mealId] ?: it })), updatedAt = now))
                }
                "create_day_plan" -> {
                    type = "plan"; payload.shape("dateContext", "goalRef", "targetCalculationId", "meals", "inputRefs")
                    require(checkedDate(payload.opt("dateContext")) == dateContext) { "OUT_OF_SCOPE: plan date" }
                    require(dao.planForDate(dateContext) == null) { "VERSION_CONFLICT: same-day plan already exists; use replace_plan_meal" }
                    val calc = requireNotNull(dao.calculation(payload.getString("targetCalculationId")))
                    require(calc.type == "nutrition_target")
                    val mealsRaw = payload.getJSONArray("meals")
                    val meals = mealsRaw.objects().map { DomainJson.decode<PlannedMeal>(it.toString()) }
                    require(meals.isNotEmpty() && meals.map { it.mealId }.distinct().size == meals.size)
                    // Persist only exact locally calculated candidates, never hand-built numerical plans.
                    val candidates = toolCalculations.mapNotNull { dao.calculation(it) }.filter { it.type == "meal_candidates" }
                        .flatMap { JSONObject(it.result).getJSONArray("meals").objects() }.map { DomainJson.decode<PlannedMeal>(it.toString()) }
                    require(meals.all { it in candidates }) { "INVALID_RANGE: plan must use calculated meals" }
                    val refs = payload.optJSONArray("inputRefs") ?: currentRefs(); validateRefs(refs)
                    dao.putPlan(DayPlanEntity(objectId, 1, dateContext, payload.optJSONObject("goalRef")?.toString() ?: "{}", calc.id,
                        DomainJson.encode(MealsDocument(meals)), JSONObject().put("refs", refs).toString(), "current", now))
                }
                else -> error("OUT_OF_SCOPE: unknown operation $kind")
            }
            old?.let { snapshot(type, it) }
            if (type !in listOf("analysis", "plan")) { dao.invalidateAnalyses(); dao.invalidatePlans() }
        }
    }
    private fun mergeRecord(type: String, id: String, fields: JSONObject, old: JSONObject?, target: JSONObject?, update: Boolean, now: Long, origin: String, profile: Boolean = false): JSONObject {
        if (update) require(old != null && target != null && target.getString("type") == type && target.getString("id") == id && target.getInt("revision") == old.getInt("revision")) { "VERSION_CONFLICT: $type" }
        else require(old == null || profile) { "DUPLICATE_OPERATION: id" }
        val out = old?.let { JSONObject(it.toString()) } ?: JSONObject()
        fields.keys().forEach { out.put(it, fields.get(it)) }
        out.put("id", id).put("revision", (old?.optInt("revision") ?: 0) + 1).put("createdAt", old?.optLong("createdAt") ?: now).put("updatedAt", now)
        if (!profile) out.put("origin", old?.optString("origin") ?: origin)
        return out
    }
    suspend fun currentRefs(): JSONArray = JSONArray().also { out ->
        suspend fun add(type: String, items: List<JSONObject>) { items.forEach { out.put(JSONObject().put("type", type).put("id", it.getString("id")).put("revision", it.getInt("revision"))) } }
        add("profile", listOfNotNull(overlay.profileDao().getProfile()?.let { JSONObject(DomainJson.encode(it)) }))
        add("goal", overlay.goalPhaseDao().getGoalPhasesFlow().first().map { JSONObject(DomainJson.encode(it)) })
        add("measurement", overlay.measurementDao().all().map { JSONObject(DomainJson.encode(it)) })
        add("training", overlay.recordDao().allTrainings().map { JSONObject(DomainJson.encode(it)) })
        add("intake", overlay.recordDao().allIntakes().map { JSONObject(DomainJson.encode(it)) })
        add("plan", dao.allPlans().map { JSONObject(DomainJson.encode(it)) })
        add("knowledge", overlay.recordDao().allKnowledge().map { JSONObject(DomainJson.encode(it)) })
    }
    suspend fun validateRefs(refs: JSONArray, allowHistorical: Boolean = false) {
        val available = currentRefs().objects().associateBy { "${it.getString("type")}:${it.getString("id")}" }
        refs.objects().forEach { ref ->
            val type = ref.getString("type"); val id = ref.getString("id")
            when (type) {
                "calculation" -> require(dao.calculation(id) != null)
                "analysis" -> require(dao.analysis(id)?.revision == ref.optInt("revision", 1))
                "policy" -> require(dao.allPolicies().any { it.id == id && it.revision == ref.optInt("revision", 1) })
                else -> require(available["$type:$id"]?.getInt("revision") == ref.optInt("revision", 1) || allowHistorical && dao.entitySnapshot(type, id, ref.optInt("revision", 1)) != null) { "VERSION_CONFLICT: $type/$id" }
            }
        }
    }
    override fun close() { if (::overlay.isInitialized) overlay.close() }
    companion object {
        internal fun recordDate(raw: String): String = if (raw.startsWith("{")) JSONObject(raw).getString("date") else raw.take(10)
        suspend fun capture(db: AppDatabase, submission: Submission): JSONObject = db.withTransaction {
            val dao = db.collaborationDao()
            val input = requireNotNull(dao.input(submission.turn.inputBundleId))
            val attachmentIds = JSONArray(input.annotationsJson).objects().flatMap { annotation ->
                annotation.getJSONArray("inputParts").objects().mapNotNull { it.nullString("attachmentId") }
            }.distinct()
            JSONObject().put("profile", db.profileDao().getProfile()?.let { JSONObject(DomainJson.encode(it)) })
                .put("goals", JSONArray(db.goalPhaseDao().getGoalPhasesFlow().first().map { JSONObject(DomainJson.encode(it)) }))
                .put("measurements", JSONArray(db.measurementDao().all().map { JSONObject(DomainJson.encode(it)) }))
                .put("trainings", JSONArray(db.recordDao().allTrainings().map { JSONObject(DomainJson.encode(it)) }))
                .put("intakes", JSONArray(db.recordDao().allIntakes().map { JSONObject(DomainJson.encode(it)) }))
                .put("knowledge", JSONArray(db.recordDao().allKnowledge().map { JSONObject(DomainJson.encode(it)) }))
                .put("plans", JSONArray(dao.allPlans().map { JSONObject(DomainJson.encode(it)) }))
                .put("calculations", JSONArray(dao.allCalculations().map { JSONObject(DomainJson.encode(it)) }))
                .put("analyses", JSONArray(dao.allAnalyses().map { JSONObject(DomainJson.encode(it)) }))
                .put("policies", JSONArray(dao.allPolicies().map { JSONObject(DomainJson.encode(it)) }))
                .put("snapshots", JSONArray(dao.allEntitySnapshots().map { JSONObject(DomainJson.encode(it)) }))
                .put("board", JSONObject(DomainJson.encode(requireNotNull(dao.board(submission.task.boardId)))))
                .put("input", JSONObject(DomainJson.encode(input)))
                .put("attachments", JSONArray(attachmentIds.mapNotNull { dao.attachment(it) }.map { JSONObject(DomainJson.encode(it)) }))
        }
        suspend fun open(context: Context, db: AppDatabase, submission: Submission, catalog: FoodCatalog): AgentWorkspace {
            val work = AgentWorkspace(context, db, submission, catalog, capture(db, submission))
            work.reset(); return work
        }
    }
}
