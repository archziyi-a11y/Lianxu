package com.example.lianxu.data.repository

import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.model.*
import com.example.lianxu.engine.DomainJson
import com.example.lianxu.archive.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/** Arrangements reuse versioned KnowledgeNote; facts remain in their original tables.
 * Reserved kinds are application-owned, never writable through upsert_knowledge. */
class ActiveInputRepository(private val db: AppDatabase) {
    companion object {
        val reserved = setOf("follow_up", "follow_up_progress", "input_habit", "method_invitation")
        fun id(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
        fun hasInput(c: DashboardCard) = !c.annotationText.isNullOrBlank() || c.attachmentId != null || c.inputValues.any { it.key!="metric" }
        fun input(kind: String, name: String? = null, fields: List<String>? = null) = BoardComponent("entry", "input",
            ComponentContent(inputKind = kind, suggestedName = name, inputFields = fields ?: when(kind) {
                "training" -> listOf("name", "sets", "repsMin", "repsMax")
                "measurement" -> listOf("metric", "value")
                else -> emptyList()
            }))
        fun followBlocks(note:KnowledgeNote):List<BoardComponent> {
            val options=JSONObject(note.origin).optJSONArray("options")?.rows().orEmpty().map { ChoiceOption(it.getString("key"),it.getString("label")) }
            return listOf(input("note"))+if(options.isEmpty())emptyList() else listOf(BoardComponent("follow-options","choice",ComponentContent(options=options)))
        }
        fun validate(c: ComponentContent) {
            require(c.inputKind in listOf("training", "measurement", "intake", "note")) { "不支持的输入类型" }
            val allowed = when(c.inputKind) { "training" -> setOf("name","sets","repsMin","repsMax","loadKg","equipment"); "measurement" -> setOf("metric","value"); else -> emptySet() }
            require(c.inputFields.distinct().size == c.inputFields.size && allowed.containsAll(c.inputFields)) { "不支持的输入字段" }
            require((c.suggestedName?.length ?: 0) <= 80)
        }
        fun validateValues(c: DashboardCard) {
            val control = c.blocks.firstOrNull { it.type == "input" }?.content ?: run { require(c.inputValues.isEmpty()); return }
            validate(control)
            require(control.inputFields.containsAll(c.inputValues.keys)) { "输入字段已变化，请重新编辑" }
            c.inputValues.forEach { (key,value) ->
                require(value.length <= 120)
                if (key in listOf("sets","repsMin","repsMax","loadKg","value")) {
                    val n = value.toDoubleOrNull(); require(n != null && n.isFinite() && n > 0 && n <= 10000) { "请填写有效的正数；不确定可留空" }
                    if (key in listOf("sets","repsMin","repsMax")) require(n % 1.0 == 0.0) { "组数和次数应为整数" }
                }
            }
            val min = c.inputValues["repsMin"]?.toInt(); val max = c.inputValues["repsMax"]?.toInt()
            require(max == null || min != null && max >= min) { "次数上限不能小于下限" }
            if (control.inputKind == "measurement" && c.inputValues.containsKey("value")) {
                val metric = c.inputValues["metric"] ?: "weight"
                val value = c.inputValues["value"]?.toFloatOrNull()
                require(value != null && when(metric) { "weight" -> value in 0.1f..500f; "body_fat" -> value > 0 && value < 100; "waist","height" -> value in 0.1f..300f; else -> false }) { "请填写有效测量值" }
            }
        }
    }

    suspend fun arrange(text: String, existing: KnowledgeNote? = null, status: String = "current"): String = db.withTransaction {
        require(text.isNotBlank() && text.length <= 1000)
        require(status in listOf("current", "cancelled"))
        val old = existing?.let { row -> db.recordDao().allKnowledge().single { it.id == row.id }.also { check(it.revision == row.revision) { "安排已更新，请重新打开" } } }
        val now = System.currentTimeMillis(); val key = old?.id ?: UUID.randomUUID().toString()
        old?.let { snapshot(it) }
        val origin = old?.origin?.let(::JSONObject) ?: JSONObject().put("kind", "user")
        origin.put("managedByUser", true)
        if(old!=null && old.text!=text.trim())origin.remove("options")
        db.recordDao().putKnowledge(KnowledgeNote(key, (old?.revision ?: 0) + 1, "follow_up", text.trim(), origin.toString(),
            old?.effectiveFrom ?: LocalDate.now(java.time.ZoneId.of(db.profileDao().getProfile()?.preferredZoneId ?: java.time.ZoneId.systemDefault().id)).toString(), null, status, old?.supersedesId, old?.createdAt ?: now, now))
        key
    }

    suspend fun editHabit(note: KnowledgeNote, name: String, format: String, enabled: Boolean) = db.withTransaction {
        val old = db.recordDao().allKnowledge().single { it.id == note.id }
        check(old.revision == note.revision); require(format in listOf("controls","text","image")); require(name.isNotBlank())
        snapshot(old)
        val o = JSONObject(old.origin).put("corrected", true).put("name", name.trim()).put("format",format)
        db.recordDao().putKnowledge(old.copy(text = name.trim(), origin = o.toString(), status = if(enabled) "current" else "superseded", revision = old.revision + 1, updatedAt = System.currentTimeMillis()))
    }

    private suspend fun snapshot(note: KnowledgeNote) {
        db.collaborationDao().putEntitySnapshot(EntitySnapshotEntity(id("knowledge:${note.id}:${note.revision}"), "knowledge", note.id, note.revision, DomainJson.encode(note), System.currentTimeMillis()))
    }

    suspend fun cards(boardId: String, date: LocalDate): List<DashboardCard> {
        // Bootstrap preferences from real pre-existing history as well as new submissions.
        val histories=db.recordDao().allTrainings().filter { HistoryRepository.day(it.dateContext)<=date.toString() }.take(100)
        for(t in histories) {
            val exercises=runCatching { JSONArray(t.exercises).rows() }.getOrDefault(emptyList())
            val origin=runCatching { JSONObject(t.origin) }.getOrNull()
            val bundle=origin?.optString("inputBundleId")?.takeIf { it.isNotBlank() }?.let { db.collaborationDao().input(it) }
            val related=origin?.optJSONArray("annotationIds")?.texts().orEmpty()
            val parts=bundle?.let { JSONArray(it.annotationsJson).rows().filter { a->a.optString("annotationId") in related }.flatMap { a->a.getJSONArray("inputParts").rows() } }.orEmpty()
            val controls=parts.any { it.optString("type")=="structured" && it.optJSONObject("values")?.length()?.let { n->n>0 }==true }
            val image=origin?.optString("attachmentId")?.isNotBlank()==true || parts.any { it.optString("type")=="image" }
            if(image && !controls)learnHabit("","image",emptyList(),t.id,HistoryRepository.day(t.dateContext))
            else if(bundle!=null && !controls)learnHabit(exercises.firstOrNull()?.optString("name").orEmpty(),"text",emptyList(),t.id,HistoryRepository.day(t.dateContext))
            else if(exercises.isNotEmpty())for(e in exercises.take(8))learnHabit(e.optString("name"),"controls",listOf("name","sets","repsMin","repsMax"),t.id,HistoryRepository.day(t.dateContext))
            else learnHabit("",if(image)"image" else "text",emptyList(),t.id,HistoryRepository.day(t.dateContext))
        }
        val notes = db.recordDao().allKnowledge()
        val validIds=histories.map { it.id }.toSet()
        val habits = notes.filter { it.kind == "input_habit" && it.status == "current" && (JSONObject(it.origin).optBoolean("corrected") || JSONObject(it.origin).optJSONArray("sources")?.texts()?.count { source->source in validIds } ?: 0 >= 2) }.sortedByDescending { JSONObject(it.origin).optInt("count") }.take(2)
        val training = if (habits.isEmpty()) listOf(DashboardCard("$boardId:training-input", "记录训练", span=CardSpan.LARGE,
            value="从一个动作开始，也可以只留一句话或图片。", blocks=listOf(input("training")), managedBy="input"))
        else habits.map { habit ->
            val o = JSONObject(habit.origin); val format = o.optString("format", "controls")
            DashboardCard("$boardId:habit:${habit.id}", habit.text, span=CardSpan.LARGE, value="沿用你的记录方式 · 尚未记录本次训练",
                blocks=listOf(input("training", o.optString("name"), if(format=="controls") o.optJSONArray("fields")?.texts()?.takeIf { it.isNotEmpty() } ?: listOf("name","sets","repsMin","repsMax") else emptyList())), managedBy="input")
        }
        val follow = notes.filter { it.kind=="follow_up" && it.status=="current" && it.effectiveFrom<=date.toString() }
            .filter { f -> notes.none { it.kind=="follow_up_progress" && it.effectiveFrom==date.toString() && JSONObject(it.origin).optString("followUpId")==f.id } }.take(2)
            .map { f -> DashboardCard("$boardId:follow:${f.id}", "接续上次", value=f.text, span=CardSpan.LARGE,
                blocks=followBlocks(f), managedBy="input", followUpId=f.id) }
        return training + follow + listOf(DashboardCard("$boardId:intake-input", "饮食补充", value="可选 · 描述或图片", span=CardSpan.MEDIUM,
            blocks=listOf(input("intake")), managedBy="input"), DashboardCard("$boardId:free-input", "自由补充", value="今天的新情况", span=CardSpan.MEDIUM,
            blocks=listOf(input("note")), managedBy="input")) + invitation(boardId,date,notes)
    }

    private suspend fun invitation(boardId:String,date:LocalDate,notes:List<KnowledgeNote>): List<DashboardCard> {
        val data=AnalysisData(db.measurementDao().all(),db.recordDao().allTrainings(),db.recordDao().allIntakes(),db.goalPhaseDao().getGoalPhasesFlow().first(),db.collaborationDao().allCalculations().filter { it.type=="nutrition_target" },db.profileDao().getProfile())
        val candidate=db.archiveDao().methods().filter { it.enabled }.map { MethodRegistry.parse(it.packageJson) }
            .flatMap { MethodQuestions.candidates(it,data,date) }.firstOrNull { q ->
                notes.none { it.kind=="method_invitation" && JSONObject(it.origin).optString("key")==q.key }
            } ?: return emptyList()
        return listOf(DashboardCard("$boardId:invitation:${candidate.key}", "多一个观察角度", value=candidate.explanation, span=CardSpan.LARGE,
            blocks=listOf(input("measurement")), managedBy="input", invitationId=candidate.key))
    }

    suspend fun dismissInvitation(key:String,date:String) {
        val now=System.currentTimeMillis()
        db.recordDao().putKnowledge(KnowledgeNote(id("invitation:$key"),1,"method_invitation","已略过可选补充",JSONObject().put("key",key).toString(),date,null,"current",null,now,now))
    }

    /** Called on disposable overlay before inference. Deterministic user controls are already facts there;
     * real DB receives them only in ProposalApplier's checked, idempotent final transaction. */
    suspend fun consume(submission: Submission, offline: Boolean = false): Set<String> {
        val input = requireNotNull(db.collaborationDao().input(submission.turn.inputBundleId))
        val annotations=JSONArray(input.annotationsJson).rows(); val created=mutableSetOf<String>()
        for (a in annotations) {
            val parts=a.getJSONArray("inputParts").rows()
            val p=parts.firstOrNull { it.optString("type")=="structured" } ?: continue
            val kind=p.getString("inputKind"); val values=p.getJSONObject("values")
            val text=parts.filter { it.optString("type")=="text" }.joinToString("\n") { it.getString("text") }
            val attachment=parts.firstOrNull { it.optString("type")=="image" }?.getString("attachmentId")
            val aid=a.getString("annotationId"); val key=id("${submission.turn.submissionId}:input:$aid")
            val now=submission.turn.createdAt; val day=HistoryRepository.day(submission.turn.dateContext)
            val origin=JSONObject().put("kind","user").put("turnId",submission.turn.id).put("inputBundleId",input.id).put("annotationIds",JSONArray(listOf(aid))).put("attachmentId",attachment)
            val followId=p.optString("followUpId").takeIf { it.isNotBlank() }
            if (followId!=null) {
                val follow=db.recordDao().allKnowledge().singleOrNull { it.id==followId && it.kind=="follow_up" }
                // Cancellation stops scheduling, never discards a user's submitted progress.
                requireNotNull(follow) { "跟进来源不存在" }
                db.recordDao().putKnowledge(KnowledgeNote(key,1,"follow_up_progress",text.ifBlank { "图片补充" },origin.put("followUpId",followId).toString(),day,null,"current",null,now,now))
                created.add(aid)
            } else if (kind=="training" && (values.length()>0 || offline && text.isNotBlank())) {
                fun spec(field:String,unit:String)=values.optString(field).toDoubleOrNull()?.let { JSONObject().put("kind","exact").put("value",it).put("unit",unit) } ?: JSONObject().put("kind","unknown")
                val name=values.optString("name")
                val exercise=JSONObject().put("entryId",id("$key:exercise")).put("name",name.ifBlank { "未命名动作" }).put("summarySets",spec("sets","count"))
                    .put("repsPerSet",if(values.has("repsMax"))JSONObject().put("kind","range").put("min",values.getInt("repsMin")).put("max",values.getInt("repsMax")).put("unit","count") else spec("repsMin","count"))
                    .put("loadKg",spec("loadKg","kg")).put("equipment",values.optString("equipment").takeIf { it.isNotBlank() }).put("sets",JSONArray())
                if(db.recordDao().training(key)==null) db.recordDao().insertTraining(TrainingSession(key,submission.turn.dateContext,"strength",name.ifBlank { "训练补充" },null,
                    if(values.length()>0)JSONArray().put(exercise).toString() else "[]",text.ifBlank { if(attachment!=null)"图片训练材料（未确认完成）" else null },origin.toString(),1,now,now))
                created.add(aid); learnHabit(name,if(values.length()>0)"controls" else if(attachment!=null)"image" else "text",p.optJSONArray("fields")?.texts().orEmpty(),key,day)
            } else if (kind=="measurement" && values.has("value")) {
                val metric=values.optString("metric","weight")
                db.measurementDao().insert(Measurement(key,metric,submission.turn.dateContext,null,values.getDouble("value").toFloat(),MethodRegistry.metrics.getValue(metric),origin.toString(),text.takeIf { it.isNotBlank() },1,now,now));created.add(aid)
            } else if (kind=="intake" && offline && text.isNotBlank()) {
                db.recordDao().insertIntake(IntakeEntry(key,submission.turn.dateContext,null,"item:$key","description","[]",null,text.ifBlank { "图片饮食材料（尚未核算）" },"partial",origin.toString(),1,now,now));created.add(aid)
            }
            p.optString("invitationId").takeIf { it.isNotBlank() && aid in created }?.let { dismissInvitation(it,day) }
        }
        if(created.isNotEmpty()) invalidateDerived(db)
        return created
    }

    suspend fun learnHabit(name:String,format:String,fields:List<String>,sourceId:String,day:String) {
        val key=id("input-habit:${name.ifBlank { format }}")
        val old=db.recordDao().allKnowledge().find { it.id==key }
        if (old!=null && (JSONObject(old.origin).optBoolean("corrected") || old.status=="superseded")) return
        val origin=old?.origin?.let(::JSONObject) ?: JSONObject().put("name",name).put("format",format).put("fields",JSONArray(fields)).put("sources",JSONArray())
        val sources=origin.getJSONArray("sources").texts(); if(sourceId in sources)return
        val next=sources+sourceId;origin.put("sources",JSONArray(next)).put("count",next.size)
        // Keep established format; one different input does not reorder all habits.
        val now=System.currentTimeMillis()
        db.recordDao().putKnowledge(KnowledgeNote(key,(old?.revision ?: 0)+1,"input_habit",name.ifBlank { if(format=="image")"用图片记训练" else "用一句话记训练" },origin.toString(),day,null,if(next.size>=2)"current" else "learning",null,old?.createdAt ?: now,now))
    }
}
