package com.example.lianxu.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.model.*
import com.example.lianxu.engine.*
import com.example.lianxu.engine.MealSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

class NutritionBoardRepository(private val db: AppDatabase, val catalog: FoodCatalog) {
    private val dao = db.collaborationDao()
    private val planner = MealPlanner(catalog)
    private val store = BoardStore(db)
    private fun id(value: String) = UUID.nameUUIDFromBytes(value.toByteArray()).toString()
    private fun fingerprint(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun date(raw: String): LocalDate? = runCatching {
        LocalDate.parse(if (raw.startsWith("{")) JSONObject(raw).getString("date") else raw.take(10))
    }.getOrNull()

    suspend fun refresh(boardId: String, preserveComposition: Boolean = true) = withContext(Dispatchers.IO) { db.withTransaction {
        val board = requireNotNull(dao.board(boardId)); val onDate = date(board.dateContext)!!
        val profile = db.profileDao().getProfile()
        val goals = db.goalPhaseDao().getGoalPhasesFlow().first()
        val goal = goals.filter { it.startsOn <= onDate.toString() && (it.endsOn == null || it.endsOn > onDate.toString()) }
            .maxWithOrNull(compareBy<GoalPhase> { it.startsOn }.thenBy { it.createdAt })
        val measurements = db.measurementDao().all().filter { date(it.observed)?.let { d -> !d.isAfter(onDate) } == true }
        val weight = measurements.filter { it.metric == "weight" }.maxWithOrNull(compareBy<Measurement> { date(it.observed) }
            .thenBy { it.measuredAt ?: it.createdAt }.thenBy { it.createdAt })
        val intakes = db.recordDao().allIntakes().filter { date(it.dateContext) == onDate }
        val trainings = db.recordDao().allTrainings().filter { date(it.dateContext)?.isAfter(onDate) == false }
        var policyEntity = dao.policyOn(onDate.toString())
        if (policyEntity == null) {
            policyEntity = NutritionPolicyEntity(id("default-policy"), 1, METHOD_VERSION, "1970-01-01", DomainJson.encode(NutritionPolicy()), System.currentTimeMillis())
            dao.putPolicy(policyEntity)
        }
        val policy = DomainJson.decode<NutritionPolicy>(policyEntity.parametersJson)
        val input = NutritionInput(onDate, weight?.value?.toString()?.toDouble(), profile?.heightCm?.toString()?.toDouble(),
            profile?.birthDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, profile?.formulaSex, goal?.kind,
            profile?.activityFactor?.toString()?.toDouble(), profile?.nutritionMode ?: "standard_adult",
            profile?.manualTargets?.let { DomainJson.decode<NutrientValues>(it) }, policy)
        val refs = listOfNotNull(profile?.let { EntityRef("profile", it.id, it.revision) }, goal?.let { EntityRef("goal", it.id, it.revision) },
            weight?.let { EntityRef("measurement", it.id, it.revision) }, EntityRef("policy", policyEntity.id, policyEntity.revision))
        val snapshot = JSONObject().put("date", onDate.toString()).put("profile", profile?.let { JSONObject(DomainJson.encode(it)) })
            .put("goal", goal?.let { JSONObject(DomainJson.encode(it)) }).put("weight", weight?.let { JSONObject(DomainJson.encode(it)) })
            .put("policy", JSONObject(policyEntity.parametersJson)).toString()
        val progress = dao.allMealProgress().filter { it.dateContext == board.dateContext }
        val signature = fingerprint(snapshot + progress.joinToString { "${it.id}@${it.revision}" } + intakes.joinToString { "${it.id}@${it.revision}" } + trainings.joinToString { "${it.id}@${it.revision}" })
        val targetId = id("target:$boardId:${fingerprint(snapshot)}")
        val target = CalculationEngine.calculate(input)
        if (dao.calculation(targetId) == null) dao.putCalculation(CalculationEntity(targetId, "nutrition_target", METHOD_VERSION,
            snapshot, refsJson(refs), DomainJson.encode(target), System.currentTimeMillis()))
        val previous = dao.planForDate(board.dateContext)
        val oldMeals = previous?.let { DomainJson.decode<MealsDocument>(it.meals).meals } ?: emptyList()
        val chronology = oldMeals.ifEmpty { slots(profile).map { PlannedMeal(it.key,it.key,it.label,profile?.defaultScene ?: "home","","",1.0,emptyList()) } }
        val eaten = closedMealIds(chronology, intakes.mapNotNull { it.mealKey }.toSet(), progress.associate { it.mealId to it.state })
        val intake = intakeTotals(intakes)
        var plan = previous?.takeIf { it.state == "current" && JSONObject(it.inputRefs).optString("signature") == signature }
        val notes = target.notes.toMutableList()
        if (target.status in listOf("ready", "manual") && plan == null) {
            try {
                val sameScene = previous?.let { JSONObject(it.inputRefs).optString("defaultScene") == profile?.defaultScene } ?: true
                val preserved = oldMeals.map { if (!sameScene && !it.locked && it.mealId !in eaten) it.copy(scene = profile?.defaultScene ?: "home") else it }
                val result = planner.search(target.targets, slots(profile), profile?.defaultScene ?: "home", excluded(profile),
                    preserved, eatenMealIds = eaten, intake = intake.knownSubtotal)
                val now = System.currentTimeMillis()
                plan = DayPlanEntity(previous?.id ?: id("plan:$boardId"), (previous?.revision ?: 0) + 1, board.dateContext,
                    goal?.let { DomainJson.encode(EntityRef("goal", it.id, it.revision)) } ?: "{}", targetId, DomainJson.encode(MealsDocument(result.meals)),
                    JSONObject().put("signature", signature).put("refs", JSONArray(refsJson(refs))).put("defaultScene", profile?.defaultScene ?: "home").toString(), "current", now)
                if (previous != null) archivePlan(previous)
                dao.putPlan(plan)

            } catch (error: IllegalArgumentException) {
                notes.add(error.message ?: "当前条件下没有可用餐食组合")
                plan = null
            }
        }
        if (target.status !in listOf("ready", "manual")) plan = null
        if (plan != null) {
            val effective = aggregate(listOf("remaining" to planner.totals(DomainJson.decode<MealsDocument>(plan.meals).meals.filter { it.mealId !in eaten }).total, "intake" to intake.knownSubtotal)).total
            val close = kotlin.math.abs(effective.energyKcal!! - target.targets.energyKcal!!) <= target.targets.energyKcal * .10 &&
                kotlin.math.abs(effective.proteinG!! - target.targets.proteinG!!) <= target.targets.proteinG * .15
            notes.add(if (close) "当前组合接近目标（能量±10%、蛋白±15%的匹配标签）；份量按5g核算" else "当前可行组合仍有差额，可更换餐食或调整份量")
        }
        if (oldMeals.any { it.mealId in eaten && intakes.none { entry -> entry.mealKey == it.slotKey } }) notes.add("已过的前餐不再纳入剩余安排；这不会新增实际摄入，可用「仍需安排这餐」调整。")
        if (intakes.isNotEmpty() && (intakes.any { it.completeness != "complete" } || !intake.complete))
            notes.add("摄入展示为已记录量；缺项保留，不能据此判断全天是否吃够")
        val summary = buildString {
            if (target.status == "needs_input") {
                val labels = mapOf("weightKg" to "体重", "heightCm" to "身高", "birthDate" to "出生日期", "formulaSex" to "公式参数", "goalKind" to "阶段目标", "manualTargets" to "手动营养目标")
                append("补充${target.missingFields.joinToString("、") { labels[it] ?: it }}后，可计算每日营养目标。")
                append("现在也可以记录训练，饮食记录可选。")
            } else if (target.status == "manual") append("当前采用你设置的每日目标。")
            else if (target.status == "ready") append("按当前资料与阶段目标生成初始规划，不需要先记录饮食。")
            append(notes.distinct().joinToString("\n", prefix = "\n"))
        }.trim()
        val analysisRefs = refs + progress.map { EntityRef("meal_progress", it.id, it.revision) } + intakes.map { EntityRef("intake", it.id, it.revision) }
        val analysisId = id("guidance:$boardId:${fingerprint(summary + refsJson(analysisRefs))}")
        if (dao.analysis(analysisId) == null) dao.putAnalysis(AnalysisEntity(analysisId, 1, "reply", onDate.toString(), onDate.toString(),
            refsJson(analysisRefs), summary, "[]", "[]", "current", System.currentTimeMillis(), System.currentTimeMillis()))
        else dao.analysis(analysisId)?.takeIf { it.freshness != "current" }?.let { dao.putAnalysis(it.copy(freshness = "current")) }
        val cards = mutableListOf<DashboardCard>()
        if (target.status in listOf("ready", "manual")) {
            NUTRIENT_KEYS.filter { target.targets[it] != null }.forEachIndexed { index, key ->
                cards.add(DashboardCard(id = "$boardId:target:$key", title = nutrientLabel(key), span = if (index < 3) CardSpan.SMALL else CardSpan.MEDIUM,
                    blocks = listOf(metric("target-$key", targetId, "targets.$key", nutrientLabel(key), unit(key)).let { block ->
                        if (key == "energyKcal") block.copy(actions = listOf(CardAction("target-source", "依据", "open_source", EntityRef("calculation", targetId)))) else block
                    })))
            }
        }
        cards.add(DashboardCard(id = "$boardId:guidance", title = "今日参考", span = CardSpan.LARGE,
            blocks = listOf(BoardComponent("guidance", "text", ComponentContent(contentRef = ContentRef(EntityRef("analysis", analysisId), "summaryText")))),
            entityRefs = listOf(EntityRef("analysis", analysisId))))
        if (plan != null) {
            val meals = DomainJson.decode<MealsDocument>(plan.meals).meals
            val total = planner.totals(meals)
            val calculationId = id("meals:${plan.id}:${plan.revision}:${fingerprint(intakes.joinToString { it.id + it.revision })}")
            val remaining = NutrientValues.from(NUTRIENT_KEYS.associateWith { key -> target.targets[key]?.minus(intake.knownSubtotal[key] ?: 0.0) })
            val result = JSONObject().put("planned", JSONObject(DomainJson.encode(total))).put("intake", JSONObject(DomainJson.encode(intake)))
                .put("remaining", JSONObject(DomainJson.encode(remaining))).toString()
            if (dao.calculation(calculationId) == null) dao.putCalculation(CalculationEntity(calculationId, "food_totals", METHOD_VERSION,
                JSONObject().put("meals", JSONObject(plan.meals).getJSONArray("meals"))
                    .put("intakes", JSONArray(intakes.map { JSONObject(DomainJson.encode(it)) })).put("targetCalculationId", targetId).toString(),
                refsJson(analysisRefs + EntityRef("plan", plan.id, plan.revision) + EntityRef("calculation", targetId)), result, System.currentTimeMillis()))
            val ref = EntityRef("plan", plan.id, plan.revision)
            meals.forEach { meal ->
                val recorded = intakes.any { it.mealKey == meal.slotKey }
                val consumed = meal.mealId in eaten
                val actions = if (recorded) emptyList() else if (consumed) listOf(CardAction("reopen-${meal.mealId}", "仍需安排这餐", "reopen_meal", ref, meal.mealId)) else listOf(
                    CardAction("skip-${meal.mealId}", "这餐不再安排", "skip_meal", ref, meal.mealId),
                    CardAction("replace-${meal.mealId}", "换一餐", "replace_meal", ref, meal.mealId),
                    CardAction("less-${meal.mealId}", "少一档", "scale_meal", ref, meal.mealId, "-0.25"),
                    CardAction("more-${meal.mealId}", "多一档", "scale_meal", ref, meal.mealId, "0.25"),
                    CardAction("eat-${meal.mealId}", "记为已吃", "mark_eaten", ref, meal.mealId),
                    CardAction("lock-${meal.mealId}", if (meal.locked) "解锁" else "保留这餐", "lock_meal", ref, meal.mealId))
                    .filter { !meal.locked || it.kind in listOf("mark_eaten", "lock_meal") }
                cards.add(DashboardCard(id = "$boardId:meal:${meal.mealId}", title = "${meal.label} · ${meal.title}${if (recorded) " · 已记录" else if (consumed) " · 不再安排" else ""}", span = CardSpan.LARGE,
                    blocks = listOf(BoardComponent("foods-${meal.mealId}", "list", ComponentContent(entityRefs = listOf(ref), mealId = meal.mealId), ref, actions),
                        BoardComponent("scene-${meal.mealId}", "choice", ComponentContent(label = "用餐场景", selectedKey = meal.scene,
                            options = listOf(ChoiceOption("home", "自己做"), ChoiceOption("canteen", "食堂"), ChoiceOption("takeout", "外食"))), ref,
                            if (consumed || meal.locked) emptyList() else listOf(CardAction("scene-${meal.mealId}", "场景", "change_scene", ref, meal.mealId))),
                        BoardComponent("source-${meal.mealId}", "text", ComponentContent(text = "按所列生熟状态与可食重量核算；额外调味及用油另计。"), ref,
                            listOf(CardAction("source-${meal.mealId}", "查看依据", "open_source", ref, meal.mealId)))), entityRefs = listOf(ref)))
            }
            cards.add(DashboardCard(id = "$boardId:plan-total", title = "餐食规划合计", span = CardSpan.LARGE,
                blocks = NUTRIENT_KEYS.map { key -> metric("plan-$key", calculationId, "planned.total.$key", nutrientLabel(key), unit(key)) }, entityRefs = listOf(ref)))
            if (intakes.isNotEmpty()) cards.add(DashboardCard(id = "$boardId:intake-total", title = "已记录与目标差额", span = CardSpan.LARGE,
                blocks = NUTRIENT_KEYS.flatMap { key -> listOf(metric("intake-$key", calculationId, "intake.knownSubtotal.$key", "已核算${nutrientLabel(key)}", unit(key)),
                    metric("remaining-$key", calculationId, "remaining.$key", "目标减已知${nutrientLabel(key)}", unit(key))) }))
        }
        cards.addAll(if(target.status in listOf("ready","manual")) cards.indexOfFirst { it.id.endsWith(":guidance") }.coerceAtLeast(0) else 0, ActiveInputRepository(db).cards(boardId, onDate))
        if (measurements.any { it.metric == "weight" }) {
            val trend = weightTrend(measurements.filter { it.metric == "weight" }.mapNotNull { m -> date(m.observed)?.let { WeightPoint(it, m.value.toString().toDouble()) } }, onDate)
            val series = JSONObject().put("points", JSONArray(trend.points.map { JSONObject().put("date", it.date.toString()).put("value", it.value) }))
                .put("summary", trend.weeklyChangeKg?.let { "两段周均值变化：${ComponentResolver.displayNumber(it, "kg")} kg" } ?: "已有测量日均值；数据不足时不判定周变化")
            val weightInputs = measurements.filter { it.metric == "weight" }
            val weightRefs = weightInputs.map { EntityRef("measurement", it.id, it.revision) }
            val cid = id("weight:$boardId:${fingerprint(series.toString() + refsJson(weightRefs))}")
            if (dao.calculation(cid) == null) dao.putCalculation(CalculationEntity(cid, "weight_trend", METHOD_VERSION,
                DomainJson.encode(MeasurementList(weightInputs)), refsJson(weightRefs), series.toString(), System.currentTimeMillis()))
            cards.add(DashboardCard(id = "$boardId:weight-trend", title = "体重记录", span = CardSpan.LARGE,
                blocks = listOf(BoardComponent("weight-series", "trend", ComponentContent(calculationId = cid)))))
        }
        if (trainings.isNotEmpty()) {
            val observed = trainings.mapNotNull { t -> date(t.dateContext)?.let { it to t.kind } }
            val frequency = trainingFrequency(observed, onDate)
            val recent = trainings.filter { date(it.dateContext)?.let { d -> !d.isBefore(onDate.minusDays(27)) } == true }
            val summaries = recent.flatMap { session -> JSONArray(session.exercises).let { a -> (0 until a.length()).map { i ->
                summarizeExercise(DomainJson.decode<RecordedExercise>(a.getJSONObject(i).toString()))
            } } }
            val sourceRefs = recent.map { EntityRef("training", it.id, it.revision) }
            val cid = id("training:$boardId:${fingerprint(refsJson(sourceRefs))}")
            val text = buildString {
                append("观察期内已记录${frequency.trainingDays}个训练日、${frequency.sessionCount}次训练。")
                frequency.weeklyRecordedDays?.let { append("近28天每周平均已记录${it}天。") }
                    ?: append("记录覆盖不足28天，不推算完整月频率。")
                recent.filter { date(it.dateContext) == onDate }.forEach { append("\n${it.title.ifBlank { it.rawSummary.orEmpty() }}") }
                if (summaries.any { it.missingSetIds.isNotEmpty() }) append("\n部分负重或次数未知，摘要不填零，也不由训练量推算消耗。")
            }
            if (dao.calculation(cid) == null) dao.putCalculation(CalculationEntity(cid, "training_summary", METHOD_VERSION,
                DomainJson.encode(TrainingList(recent)), refsJson(sourceRefs), JSONObject().put("frequency", JSONObject(DomainJson.encode(frequency)))
                    .put("exercises", JSONArray(summaries.map { JSONObject(DomainJson.encode(it)) })).toString(), System.currentTimeMillis()))
            val aid = id("training-text:$cid")
            val analysis = dao.analysis(aid)
            if (analysis == null) dao.putAnalysis(AnalysisEntity(aid, 1, "reply", onDate.minusDays(27).toString(), onDate.toString(), refsJson(sourceRefs),
                text, "[]", JSONArray(listOf(cid)).toString(), "current", System.currentTimeMillis(), System.currentTimeMillis()))
            else if (analysis.freshness != "current") dao.putAnalysis(analysis.copy(freshness = "current"))
            cards.add(DashboardCard(id = "$boardId:training-summary", title = "训练摘要", span = CardSpan.LARGE,
                blocks = listOf(BoardComponent("training-summary", "text", ComponentContent(contentRef = ContentRef(EntityRef("analysis", aid), "summaryText"))))))
        }
        publish(board, cards, preserveComposition)
        target
    } }

    private fun refsJson(refs: List<EntityRef>) = JSONArray(refs.map { JSONObject(DomainJson.encode(it)) }).toString()
    private fun metric(id: String, calc: String, path: String, label: String, unit: String) = BoardComponent(id, "metric",
        ComponentContent(label = label, calculationId = calc, resultPath = path, unit = unit))
    private fun nutrientLabel(key: String) = mapOf("energyKcal" to "能量", "proteinG" to "蛋白质", "carbohydrateG" to "碳水", "fatG" to "脂肪", "fiberG" to "膳食纤维").getValue(key)
    private fun unit(key: String) = if (key == "energyKcal") "kcal" else "g"
    private fun excluded(profile: Profile?): List<String> = profile?.excludedIngredients?.let { raw -> JSONArray(raw).let { array -> (0 until array.length()).map { array.getString(it) }.filter { it.isNotBlank() } } } ?: emptyList()
    private fun slots(profile: Profile?): List<MealSlot> {
        val array = profile?.mealSlots?.let { JSONArray(it) }
        return if (array == null || array.length() == 0) planner.defaultSlots else (0 until array.length()).map { i -> array.getJSONObject(i).let { MealSlot(it.getString("key"), it.getString("label"), it.getDouble("ratio")) } }
    }
    private fun consumedMeals(meals: List<PlannedMeal>, intakes: List<IntakeEntry>) = meals.filter { meal -> intakes.any { it.mealKey == meal.slotKey } }.map { it.mealId }.toSet()
    fun intakeTotals(entries: List<IntakeEntry>): NutrientAggregate = effectiveIntake(entries.map { entry ->
        val nutrients = when (entry.mode) {
            "nutrient_total" -> entry.manualNutrients?.let { DomainJson.decode<NutrientValues>(it) } ?: NutrientValues()
            "items" -> {
                val items = JSONArray(entry.items)
                if (items.length() == 0) NutrientValues() else aggregate((0 until items.length()).map { index ->
                    val item = items.getJSONObject(index)
                    val grams = if (item.isNull("grams")) null else item.optDouble("grams", Double.NaN).takeIf { it.isFinite() }
                    val snap = item.optJSONObject("foodSnapshot")
                    val per100 = snap?.optJSONObject("nutrients")?.let { DomainJson.decode<NutrientValues>(it.toString()) }
                        ?: catalog.byId[item.optString("foodRef")]?.nutrients
                    val value = if (grams != null && per100 != null) per100.scale(grams / 100) else
                        item.optJSONObject("estimatedNutrients")?.let { DomainJson.decode<NutrientValues>(it.toString()) } ?: NutrientValues()
                    "${entry.id}:$index" to value
                }).total
            }
            else -> NutrientValues()
        }
        IntakeValue(entry.id, entry.mealKey, entry.coverageKey, nutrients, entry.createdAt, entry.completeness)
    })
    private suspend fun archivePlan(plan: DayPlanEntity) {
        if (dao.entitySnapshot("plan", plan.id, plan.revision) == null)
            dao.putEntitySnapshot(EntitySnapshotEntity(id("plan:${plan.id}:${plan.revision}"), "plan", plan.id, plan.revision, DomainJson.encode(plan), System.currentTimeMillis()))
    }
    private suspend fun publish(board: Board, generated: List<DashboardCard>, preserveComposition: Boolean) {
        val current = dao.cards(board.id).associate { it.id to DomainJson.decode<DashboardCard>(it.data) }
        val draft = dao.draft(board.id)
        val pending = draft?.let { store.decode(it.cardsJson).associateBy { c -> c.id } } ?: emptyMap()
        val composed = if (preserveComposition && board.latestCompletedTurnId != null) {
            val byId = generated.associateBy { it.id }
            val plan = dao.planForDate(board.dateContext)
            val latestTurn = board.latestCompletedTurnId?.let { dao.turn(it) }
            val cache = latestTurn?.let { dao.agentCache(it.taskId) }
            val removed = cache?.let { JSONArray(it.entriesJson) }?.let { entries -> (0 until entries.length()).map { entries.getJSONObject(it) }.lastOrNull { it.optString("name") == "finish_proposal" } }
                ?.optString("arguments")?.let { JSONObject(it).optJSONObject("boardPatch")?.optJSONArray("removeCardIds") }
                ?.let { ids -> (0 until ids.length()).map { ids.getString(it) }.toSet() }.orEmpty()
            val localBlocks = generated.flatMap { it.blocks }
            current.values.map { old ->
                if (old.managedBy != "assistant") byId[old.id] ?: old else old.copy(blocks = old.blocks.map { block ->
                    fun currentRef(ref: EntityRef) = if (ref.type == "plan" && ref.id == plan?.id) ref.copy(revision = plan.revision) else ref
                    val c = block.content
                    var calculationId = c.calculationId; var path = c.resultPath
                    val oldCalc = calculationId?.let { dao.calculation(it) }
                    if (oldCalc != null) {
                        if (oldCalc.type == "intake_totals") {
                            localBlocks.firstOrNull { it.type == "metric" && it.content.resultPath == "intake.${c.resultPath}" }?.let { replacement -> calculationId = replacement.content.calculationId; path = replacement.content.resultPath }
                        } else if (oldCalc.type in listOf("nutrition_target", "weight_trend", "training_summary")) {
                            val replacement = localBlocks.firstOrNull { candidate -> candidate.type == block.type && candidate.content.resultPath == c.resultPath &&
                                candidate.content.calculationId?.let { dao.calculation(it)?.type } == oldCalc.type }
                            replacement?.let { calculationId = it.content.calculationId }
                        }
                    }
                    block.copy(content = c.copy(entityRefs = c.entityRefs.map(::currentRef), calculationId = calculationId, resultPath = path),
                        entityRef = block.entityRef?.let(::currentRef), actions = block.actions.map { it.copy(targetRef = currentRef(it.targetRef)) })
                }, entityRefs = old.entityRefs.map { ref -> if (ref.type == "plan" && ref.id == plan?.id) ref.copy(revision = plan.revision) else ref })
            } + generated.filter { it.id !in current && it.id !in removed && (it.id.endsWith(":intake-total") || it.id.endsWith(":training-summary") || it.id.endsWith(":weight-trend")) }

        } else generated
        val prepared = generated.filter { it.managedBy == "input" }.associateBy { it.id }
        val stable = composed.mapNotNull { old->if(old.managedBy=="input")prepared[old.id] else old }
        val added = prepared.values.filter { p->stable.none { it.id==p.id } }
        val arranged = stable + added
        val withCustom = arranged + pending.values.filter { it.managedBy=="user_input" && arranged.none { c->c.id==it.id } }
        val cancelled = db.recordDao().allKnowledge().filter { it.kind=="follow_up" && it.status=="cancelled" }.map { it.id }.toSet()
        val retained = current.values.filter { old -> withCustom.none { it.id == old.id } && pending[old.id]?.let { ActiveInputRepository.hasInput(it) } == true }
        val result = (withCustom + retained).map { candidate ->
            val card=if(candidate.followUpId in cancelled)candidate.copy(title="已取消跟进 · 保留你的补充",value="这项跟进已停止。已写的进展仍可保存，也可以撤掉补充。",blocks=candidate.blocks.filterNot { it.type=="choice" }) else candidate
            val old = current[card.id]
            card.copy(revision = if (old == null) 1 else if (card.copy(revision = old.revision) == old) old.revision else old.revision + 1)
        }
        if (current.values.toList() == result) return
        dao.removeCards(board.id); dao.putCards(result.map { Card(it.id, board.id, DomainJson.encode(it)) })
        dao.putBoard(board.copy(revision = board.revision + 1, updatedAt = System.currentTimeMillis()))
        if (draft != null) dao.putDraft(draft.copy(revision = draft.revision + 1, updatedAt = System.currentTimeMillis(), cardsJson = store.encode(result.map { card ->
            pending[card.id]?.let { old -> card.copy(isAnnotationMode = old.isAnnotationMode, annotationText = old.annotationText,
                annotationImageUri = old.annotationImageUri, attachmentId = old.attachmentId, annotationAnchor = old.annotationAnchor, inputValues = old.inputValues) } ?: card
        })))
    }

    suspend fun act(action: CardAction, value: String? = null) = withContext(Dispatchers.IO) { db.withTransaction {
        val plan = requireNotNull(dao.plan(action.targetRef.id))
        if (action.kind == "mark_eaten" && db.recordDao().intake(id("eaten:${plan.id}:${action.mealId}")) != null) return@withTransaction
        check(plan.revision == action.targetRef.revision && plan.state == "current") { "方案已更新，请使用最新卡片" }
        val meals = DomainJson.decode<MealsDocument>(plan.meals).meals
        val meal = requireNotNull(meals.firstOrNull { it.mealId == action.mealId })
        val allIntakes = db.recordDao().allIntakes().filter { date(it.dateContext) == date(plan.dateContext) }
        val progress = dao.allMealProgress().filter { it.planId == plan.id }
        val eaten = closedMealIds(meals, allIntakes.mapNotNull { it.mealKey }.toSet(), progress.associate { it.mealId to it.state })
        if (action.kind in listOf("skip_meal", "reopen_meal")) {
            check(allIntakes.none { it.mealKey == meal.slotKey }) { "已有摄入记录，请从档案纠正实际记录" }
            val old = progress.firstOrNull { it.mealId == meal.mealId }
            dao.putMealProgress(MealProgressEntity(old?.id ?: id("scope:${plan.id}:${meal.mealId}"), plan.id, meal.mealId, plan.dateContext,
                if (action.kind == "skip_meal") "skipped" else "pending", (old?.revision ?: 0) + 1, System.currentTimeMillis()))
            dao.invalidatePlans(); return@withTransaction
        }
        if (action.kind == "mark_eaten") {
            val intakeId = id("eaten:${plan.id}:${meal.mealId}")
            if (db.recordDao().intake(intakeId) == null) {
                val items = JSONArray(meal.items.map { food -> JSONObject().put("itemId", id("$intakeId:${food.foodId}"))
                    .put("foodRef", food.foodId).put("grams", food.grams).put("foodSnapshot", JSONObject().put("id", food.foodId)
                        .put("nameZh", food.name).put("state", food.state).put("sourceId", food.sourceId).put("sourceVersion", food.sourceVersion)
                        .put("sourceUrl", food.sourceUrl).put("nutrients", JSONObject(DomainJson.encode(food.nutrientsPer100g)))) })
                db.recordDao().insertIntake(IntakeEntry(intakeId, plan.dateContext, meal.slotKey, "item:$intakeId", "items", items.toString(), null,
                    meal.title, "complete", JSONObject().put("kind", "user").put("planId", plan.id).put("planRevision", plan.revision).toString(), 1,
                    System.currentTimeMillis(), System.currentTimeMillis()))
                dao.invalidatePlans(); dao.invalidateAnalyses()
            }
            return@withTransaction
        }
        check(meal.mealId !in eaten) { "这餐已记录，规划调整不会改动实际摄入" }
        if (action.kind != "lock_meal") check(!meal.locked) { "这餐已保留，请先解锁" }
        val profile = db.profileDao().getProfile()
        val target = DomainJson.decode<NutritionResult>(requireNotNull(dao.calculation(plan.targetCalculationId)).result)
        val updated = when (action.kind) {
            "lock_meal" -> meals.map { if (it.mealId == meal.mealId) it.copy(locked = !it.locked) else it }
            "scale_meal" -> {
                val recipe = catalog.recipes.single { it.id == meal.recipeId }
                val factor = meal.factor + requireNotNull(action.value).toDouble()
                require(factor in recipe.portionMinFactor..recipe.portionMaxFactor) { "已到这餐的份量范围，可换一餐" }
                meals.map { if (it.mealId == meal.mealId) planner.meal(recipe, MealSlot(meal.slotKey, meal.label, 1.0), factor, meal.mealId) else it }
            }
            "replace_meal", "change_scene" -> {
                val scene = value ?: meal.scene
                require(scene in listOf("home", "canteen", "takeout"))
                val current = meals.map { if (it.mealId == meal.mealId) it.copy(scene = scene) else it }
                planner.search(target.targets, slots(profile), profile?.defaultScene ?: "home", excluded(profile), current, meal.mealId,
                    if (action.kind == "replace_meal") meal.recipeId else null, eaten, intakeTotals(allIntakes).knownSubtotal).meals
            }
            else -> error("不支持的本地操作")
        }
        archivePlan(plan)
        dao.putPlan(plan.copy(revision = plan.revision + 1, meals = DomainJson.encode(MealsDocument(updated)), updatedAt = System.currentTimeMillis()))
    } }

    companion object {
        suspend fun create(context: Context, db: AppDatabase): NutritionBoardRepository = withContext(Dispatchers.IO) {
            val foods = context.assets.open("food/foods.v1.json").bufferedReader().use { it.readText() }
            val recipes = context.assets.open("food/recipes.v1.json").bufferedReader().use { it.readText() }
            val manifest = JSONObject(context.assets.open("food/manifest.json").bufferedReader().use { it.readText() })
            for ((name, contents) in listOf("foods.v1.json" to foods, "recipes.v1.json" to recipes)) {
                val sha = MessageDigest.getInstance("SHA-256").digest(contents.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                require(sha == manifest.getJSONObject("outputs").getString(name)) { "食物资产校验失败" }
            }
            NutritionBoardRepository(db, FoodCatalog.fromJson(foods, recipes))
        }
    }
}
data class MeasurementList(val measurements: List<Measurement>)

data class TrainingList(val sessions: List<TrainingSession>)
