package com.example.lianxu.agent

import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.model.*
import com.example.lianxu.data.repository.BoardStore
import com.example.lianxu.data.repository.ComponentResolver
import com.example.lianxu.engine.DomainJson
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

class SourceConflict(message: String) : IllegalStateException(message)

class ProposalApplier(private val work: AgentWorkspace) {
    suspend fun finish(proposal: JSONObject): List<DashboardCard> = work.overlay.withTransaction {
        proposal.shape("contractVersion", "turnId", "taskId", "baseRefs", "operations", "blocks", "summary", "followUp", "stageId", "boardPatch")
        require(proposal.getString("contractVersion") == "2" && proposal.getString("turnId") == work.submission.turn.id && proposal.getString("taskId") == work.submission.task.id) { "INVALID_SHAPE: contract/turn/task" }
        require(proposal.getString("summary").isNotBlank())
        require(proposal.nullString("stageId") == work.stageId) { "VERSION_CONFLICT: stageId expected=${work.stageId}, received=${proposal.nullString("stageId")}" }
        proposal.optJSONArray("baseRefs")?.let { validateBaseRefs(it) }
        val operations = proposal.getJSONArray("operations")
        val staged = work.staged.objects().associateBy { it.getString("operationId") }
        // Transport shorthand: stageId already names immutable original operations.
        // Expand omitted staged operations before validating/persisting the canonical proposal.
        staged.forEach { (id, original) -> if (operations.objects().none { it.getString("operationId") == id }) operations.put(JSONObject(original.toString())) }
        require(operations.length() <= 20)
        staged.forEach { (id, value) ->
            val original = operations.objects().singleOrNull { it.getString("operationId") == id }
            require(original != null && canonical(original) == canonical(value)) { "INVALID_SHAPE: staged operation changed/omitted" }
        }
        val extra = operations.objects().filter { it.getString("operationId") !in staged }
        if (staged.isNotEmpty()) require(extra.all { it.getString("kind") in listOf("create_analysis", "upsert_knowledge", "replace_plan_meal") }) { "INVALID_SHAPE: new primary facts require restaging" }
        if (staged.isEmpty() && extra.any { it.getString("kind") !in listOf("create_analysis", "upsert_knowledge", "replace_plan_meal") }) {
            require(work.toolCalculations.isEmpty()) { "INVALID_SHAPE: stage primary facts before calculation" }
        }
        // Disjoint targets and identifiers across staged and final derived operations.
        require(operations.objects().map { it.getString("operationId") }.distinct().size == operations.length())
        val targets = operations.objects().mapNotNull { it.optJSONObject("targetRef")?.let { t -> "${t.getString("type")}:${t.getString("id")}" } }
        require(targets.distinct().size == targets.size) { "DUPLICATE_OPERATION: target" }
        val observedCardVersions = work.dao.cards(work.submission.task.boardId).associate { row -> row.id to DomainJson.decode<DashboardCard>(row.data).revision }
        work.applyOperations(JSONArray(extra))
        work.nutrition.refresh(work.submission.task.boardId, preserveComposition = false)
        val now = System.currentTimeMillis()
        val summaryId = refId("${work.submission.turn.id}:summary")
        val summaryRef = EntityRef("analysis", summaryId)
        work.dao.putAnalysis(AnalysisEntity(summaryId, 1, "reply", work.date, work.date, work.currentRefs().toString(), proposal.getString("summary"), "[]", JSONArray(work.toolCalculations.toList()).toString(), "current", now, now))
        var cards = work.dao.cards(work.submission.task.boardId).map { DomainJson.decode<DashboardCard>(it.data) }
        // Preserve all original unrelated custom cards and composition. Recomputed local cards replace their stable IDs.
        val generated = cards.associateBy { it.id }
        cards = work.submission.cards.map { generated[it.id] ?: it.copy(annotationText = null, annotationImageUri = null, attachmentId = null, isAnnotationMode = false, annotationAnchor = null) } + cards.filter { c -> work.submission.cards.none { it.id == c.id } }
        val patch = proposal.optJSONObject("boardPatch")
        if (patch != null) cards = patch(cards, patch, observedCardVersions)
        val blocks = proposal.optJSONArray("blocks")
        var replyBlocks = emptyList<BoardComponent>()
        val defaultReply = patch == null || patch.optJSONArray("upsertCards")?.length() == 0
        if (blocks != null && blocks.length() > 0) {
            val parsed = blocks.objects().map { parseBlock(bindCardRefs(work.resolve(it)) as JSONObject) }
            parsed.forEach { validateBlock(it) }
            if (defaultReply) replyBlocks = parsed
            else cards = cards + DashboardCard(refId("${work.submission.turn.id}:blocks"), "本轮建议", span = CardSpan.LARGE, blocks = parsed)
        }
        if (defaultReply) {
            cards = listOf(DashboardCard(refId("${work.submission.task.boardId}:reply"), "本轮建议", span = CardSpan.LARGE,
                blocks = replyBlocks.ifEmpty { listOf(BoardComponent("reply", "text", ComponentContent(contentRef = ContentRef(summaryRef, "summaryText")))) }, entityRefs = listOf(summaryRef))) + cards.filterNot { it.id == refId("${work.submission.task.boardId}:reply") }
        }
        proposal.optJSONObject("followUp")?.let { follow ->
            follow.shape("question", "options", "missingFields", "topicId")
            val question = follow.getString("question"); require(question.isNotBlank() && question.length<=1000)
            val notes = work.overlay.recordDao().allKnowledge()
            val requestedId=follow.nullString("topicId")
            val existing=requestedId?.let { key->notes.singleOrNull { it.id==key && it.kind=="follow_up" } }
            if(requestedId!=null) require(existing?.status=="current") { "OUT_OF_SCOPE: follow-up cancelled or unknown" }
            // No semantic matching can prove that a renamed question is a new arrangement. Once a
            // user cancels, new schedules require the explicit archive 'new follow-up' action.
            val mayCreate=requestedId==null && notes.none { it.kind=="follow_up" && it.status=="cancelled" }
            if(existing!=null || mayCreate) {
                val fid=existing?.id ?: refId("${work.submission.turn.id}:follow-up")
                val options=follow.optJSONArray("options") ?: JSONArray()
                require(options.length()<=8)
                options.objects().forEach { it.shape("key","label");require(it.getString("key").length in 1..80 && it.getString("label").length in 1..160) }
                require(options.objects().map { it.getString("key") }.distinct().size==options.length())
                val arrangement=existing ?: KnowledgeNote(fid,1,"follow_up",question,
                    JSONObject().put("kind","assistant").put("turnId",work.submission.turn.id).put("inputBundleId",work.submission.turn.inputBundleId).put("options",options).toString(),work.date,null,"current",null,now,now)
                if(existing==null)work.overlay.recordDao().putKnowledge(arrangement)
                val content=existing?.text ?: question
                cards=cards.filterNot { it.followUpId==fid } + DashboardCard("${work.submission.task.boardId}:follow:$fid","接续上次",value=content,span=CardSpan.LARGE,
                    blocks=com.example.lianxu.data.repository.ActiveInputRepository.followBlocks(arrangement),managedBy="input",followUpId=fid)
            }
        }
        require(cards.size in 1..60 && cards.map { it.id }.distinct().size == cards.size) { "INVALID_RANGE: cards" }
        cards.forEach { card ->
            require(card.title.length <= 120 && card.blocks.size <= 20)
            card.blocks.forEach { block -> if (!(block.type == "text" && block.content.contentRef == null && generated[card.id]?.blocks?.contains(block) == true)) {
                val inherited = work.submission.cards.firstOrNull { it.id == card.id }?.blocks?.contains(block) == true
                validateBlock(block, allowStaleAnalysis = inherited)
            } }
            work.validateRefs(JSONArray(card.entityRefs.map { JSONObject(DomainJson.encode(it)) }))
        }
        cards
    }
    private suspend fun validateBaseRefs(refs: JSONArray) {
        val basis = work.basis
        val types = mapOf("profile" to "profile", "goal" to "goals", "measurement" to "measurements", "training" to "trainings", "intake" to "intakes", "knowledge" to "knowledge", "plan" to "plans", "meal_progress" to "mealProgress", "analysis" to "analyses", "calculation" to "calculations", "policy" to "policies")
        refs.objects().forEach { ref ->
            val key = requireNotNull(types[ref.getString("type")])
            val items = if (key == "profile") listOfNotNull(basis.optJSONObject(key)) else basis.getJSONArray(key).objects()
            val inBasis = items.any { it.getString("id") == ref.getString("id") && it.optInt("revision", 1) == ref.optInt("revision", 1) }
            if (!inBasis) work.validateRefs(JSONArray().put(work.resolve(ref)), allowHistorical = true)
        }
    }
    private suspend fun patch(initial: List<DashboardCard>, patch: JSONObject, observed: Map<String, Int>): List<DashboardCard> {
        patch.shape("upsertCards", "removeCardIds", "orderedCardIds")
        val upserts = patch.optJSONArray("upsertCards")?.objects().orEmpty()
        val removals = patch.optJSONArray("removeCardIds")?.strings().orEmpty()
        require(removals.distinct().size == removals.size && removals.all { id -> initial.any { it.id == id } }) { "NOT_FOUND: remove card" }
        val cards = initial.filterNot { it.id in removals }.toMutableList()
        val ids = mutableSetOf<String>()
        for (raw in upserts) {
            raw.shape("cardId", "clientRef", "revision", "title", "span", "blocks", "entityRefs", "order")
            val existingId = raw.nullString("cardId")
            val id = existingId ?: work.allocated(raw.getString("clientRef"))
            require(ids.add(id) && id !in removals) { "DUPLICATE_OPERATION: card" }
            val original = work.submission.cards.find { it.id == id }
            val preview = initial.find { it.id == id }
            val old = preview ?: original
            if (existingId != null) require(old != null && raw.optInt("revision", -1) in listOfNotNull(original?.revision, observed[id], preview?.revision)) { "VERSION_CONFLICT: card/$id expected ${listOfNotNull(original?.revision, observed[id], preview?.revision)}" }
            val value = bindCardRefs(work.resolve(raw)) as JSONObject
            val card = DashboardCard(id, value.getString("title"), span = when (value.getInt("span")) { 2 -> CardSpan.SMALL; 3 -> CardSpan.MEDIUM; 6 -> CardSpan.LARGE; else -> error("INVALID_RANGE: span") },
                revision = (old?.revision ?: 0) + 1, managedBy = "assistant", blocks = value.getJSONArray("blocks").objects().map { parseBlock(it) },
                entityRefs = value.optJSONArray("entityRefs")?.objects()?.map { DomainJson.decode<EntityRef>(it.toString()) }.orEmpty())
            card.blocks.forEach { validateBlock(it) }
            val index = cards.indexOfFirst { it.id == id }; if (index >= 0) cards[index] = card else cards.add(card)
        }
        val ordered = patch.optJSONArray("orderedCardIds")?.strings().orEmpty().map { work.mapping[it] ?: it }
        if (ordered.isEmpty()) return cards
        require(ordered.distinct().size == ordered.size && cards.map { it.id }.containsAll(ordered)) { "INVALID_SHAPE: unknown or duplicate card order" }
        return ordered.map { id -> cards.single { it.id == id } } + cards.filter { it.id !in ordered }
    }
    private suspend fun bindCardRefs(value: Any?): Any? = when (value) {
        is JSONArray -> JSONArray().also { out -> (0 until value.length()).forEach { out.put(bindCardRefs(value.get(it))) } }
        is JSONObject -> {
            val out = JSONObject().also { obj -> value.keys().forEach { key -> obj.put(key, bindCardRefs(value.get(key))) } }
            if (out.keysSet() == setOf("type", "id", "revision")) {
                val type = out.getString("type"); val id = out.getString("id"); val revision = out.getInt("revision")
                val current = work.currentRefs().objects().firstOrNull { it.getString("type") == type && it.getString("id") == id }
                // Bind presentation to the final version of an object changed by this very transaction.
                if (current != null && current.getInt("revision") != revision && work.dao.entitySnapshot(type, id, revision) != null)
                    out.put("revision", current.getInt("revision"))
            }
            out
        }
        else -> value
    }
    private fun parseBlock(raw: JSONObject): BoardComponent {
        raw.shape("componentId", "type", "content", "entityRef", "actions", "order")
        raw.optJSONObject("content")?.shape("label", "text", "contentRef", "calculationId", "resultPath", "unit", "entityRefs", "mealId", "options", "selectedKey", "attachmentId", "inputKind", "inputFields", "suggestedName")
        return DomainJson.decode(raw.toString())
    }
    private suspend fun validateBlock(block: BoardComponent, allowStaleAnalysis: Boolean = false) {
        require(block.componentId.isNotBlank())
        val c = block.content
        when (block.type) {
            "text" -> {
                val ref = requireNotNull(c.contentRef) { "INVALID_SHAPE: assistant text needs analysis contentRef" }
                require(ref.entityRef.type == "analysis" && ref.fieldPath == "summaryText")
                val analysis = requireNotNull(work.dao.analysis(ref.entityRef.id))
                require(analysis.revision == ref.entityRef.revision && (analysis.freshness == "current" || allowStaleAnalysis)) { "VERSION_CONFLICT: analysis" }
                require(!analysis.summaryText.contains("{{")) { "INVALID_SHAPE: unresolved placeholder" }
            }
            "metric" -> {
                val calc = requireNotNull(c.calculationId?.let { work.dao.calculation(it) }) { "NOT_FOUND: calculation" }
                val number = ComponentResolver.path(JSONObject(calc.result), c.resultPath.orEmpty())
                require(number is Number || number == null) { "INVALID_SHAPE: numeric resultPath" }
                require(pathExists(JSONObject(calc.result), c.resultPath.orEmpty())) { "NOT_FOUND: resultPath" }
                require(c.text == null) { "INVALID_SHAPE: metric text override" }
            }
            "trend" -> require(c.calculationId?.let { work.dao.calculation(it) }?.let { JSONObject(it.result).optJSONArray("points") } != null) { "INVALID_SHAPE: trend series" }
            "image" -> require(c.attachmentId != null && c.attachmentId in work.basis.getJSONArray("attachments").objects().map { it.getString("id") }) { "OUT_OF_SCOPE: image" }
            "list" -> {
                require(c.entityRefs.isNotEmpty()); work.validateRefs(JSONArray(c.entityRefs.map { JSONObject(DomainJson.encode(it)) }))
                require(c.entityRefs.all { it.type in listOf("plan", "training", "intake") })
            }
            "input" -> com.example.lianxu.data.repository.ActiveInputRepository.validate(c)
            "choice" -> require(c.options.isNotEmpty() && c.options.map { it.key }.distinct().size == c.options.size)
            else -> error("INVALID_SHAPE: component type")
        }
        block.entityRef?.let { work.validateRefs(JSONArray().put(JSONObject(DomainJson.encode(it)))) }
        block.actions.forEach { action ->
            require(action.kind in listOf("replace_meal", "scale_meal", "mark_eaten", "lock_meal", "change_scene", "open_source", "skip_meal", "reopen_meal")) { "OUT_OF_SCOPE: action" }
            work.validateRefs(JSONArray().put(JSONObject(DomainJson.encode(action.targetRef))))
        }
    }
    private fun pathExists(result: JSONObject, path: String): Boolean {
        if (path.isBlank()) return false
        var current = result
        val parts = path.split('.')
        parts.dropLast(1).forEach { current = current.optJSONObject(it) ?: return false }
        return current.has(parts.last())
    }
    suspend fun commit(cards: List<DashboardCard>, proposal: JSONObject) = work.real.withTransaction {
        val db = work.real; val d = db.collaborationDao()
        if (d.receipt(work.submission.turn.submissionId, "apply-board") != null) return@withTransaction
        val latest = AgentWorkspace.capture(db, work.submission)
        for (key in listOf("profile", "goals", "measurements", "trainings", "intakes", "knowledge", "plans", "policies", "mealProgress"))
            if (canonical(latest.opt(key)) != canonical(work.basis.opt(key))) throw SourceConflict("VERSION_CONFLICT: $key changed during processing")
        if (d.board(work.submission.task.boardId)?.revision != work.submission.boardRevision) throw SourceConflict("VERSION_CONFLICT: board")
        val overlay = work.overlay; val od = work.dao
        overlay.profileDao().getProfile()?.let { db.profileDao().insert(it) }
        overlay.goalPhaseDao().getGoalPhasesFlow().first().forEach { db.goalPhaseDao().insert(it) }
        overlay.measurementDao().all().forEach { db.measurementDao().insert(it) }
        overlay.recordDao().allTrainings().forEach {
            if (db.recordDao().training(it.id) == null) {
                db.recordDao().insertTraining(it)
            } else db.recordDao().updateTraining(it)
        }
        overlay.recordDao().allIntakes().forEach { if (db.recordDao().intake(it.id) == null) db.recordDao().insertIntake(it) else db.recordDao().updateIntake(it) }
        overlay.recordDao().allKnowledge().forEach { db.recordDao().putKnowledge(it) }
        od.allCalculations().forEach { if (d.calculation(it.id) == null) d.putCalculation(it) }
        od.allPolicies().forEach { if (d.allPolicies().none { p -> p.id == it.id }) d.putPolicy(it) }
        od.allPlans().forEach { d.putPlan(it) }
        od.allAnalyses().forEach { d.putAnalysis(it) }
        od.allEntitySnapshots().forEach { if (d.entitySnapshot(it.entityType, it.entityId, it.revision) == null) d.putEntitySnapshot(it) }
        val refs = work.currentRefs().toString()
        val operations = proposal.getJSONArray("operations").objects()
        val turn = requireNotNull(d.turn(work.submission.turn.id))
        d.putTurn(turn.copy(contextRefs = work.basisRefs().toString(), resultRefs = refs, mutationIds = JSONArray(operations.map { it.getString("operationId") }).toString()))
        operations.forEach { op ->
            d.putReceipt(MutationReceiptEntity(refId("${turn.submissionId}:${op.getString("operationId")}"), turn.submissionId, op.getString("operationId"), refs, turn.beforeBoardSnapshotId, null, System.currentTimeMillis()))
        }
        // Learn formats from successful model extraction too, never from prepared values.
        operations.filter { it.getString("kind")=="create_training" }.forEach { op ->
            val payload=op.getJSONObject("payload")
            val exercises=payload.optJSONArray("exercises")?.objects().orEmpty()
            val source=op.nullString("clientRef")?.let { work.mapping[it] } ?: refId("${turn.submissionId}:${op.getString("operationId")}")
            val related=op.getJSONArray("relatedAnnotationIds").strings()
            val imageInput=work.annotations.objects().filter { it.getString("annotationId") in related }.any { annotation->annotation.getJSONArray("inputParts").objects().any { it.optString("type")=="image" } }
            com.example.lianxu.data.repository.ActiveInputRepository(db).learnHabit(if(imageInput)"" else exercises.firstOrNull()?.optString("name").orEmpty(),if(imageInput)"image" else "text",emptyList(),source,work.date)
        }
        BoardStore(db).complete(work.submission, cards)
    }
    companion object {
        internal fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keysSet().sorted().joinToString(prefix = "{", postfix = "}") { "${JSONObject.quote(it)}:${canonical(value.get(it))}" }
            is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
            else -> JSONArray().put(value).toString().removePrefix("[").removeSuffix("]")
        }
    }
}

internal fun AgentWorkspace.basisRefs(): JSONArray = JSONArray().also { refs ->
    val keys = mapOf("goals" to "goal", "measurements" to "measurement", "trainings" to "training", "intakes" to "intake", "knowledge" to "knowledge", "plans" to "plan", "mealProgress" to "meal_progress", "policies" to "policy")
    basis.optJSONObject("profile")?.let { refs.put(JSONObject().put("type", "profile").put("id", it.getString("id")).put("revision", it.getInt("revision"))) }
    keys.forEach { (key, type) -> basis.getJSONArray(key).objects().forEach { refs.put(JSONObject().put("type", type).put("id", it.getString("id")).put("revision", it.getInt("revision"))) } }
}
