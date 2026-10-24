package com.example.lianxu.data.repository

import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.model.EntityRef
import com.example.lianxu.engine.DomainJson
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class HistoryItem(val type: String, val id: String, val revision: Int, val date: String, val title: String, val detail: String, val raw: String)
data class MutationToken(val submissionId: String, val operationId: String)

class HistoryRepository(private val db: AppDatabase) {
    val items: Flow<List<HistoryItem>> = combine(db.recordDao().trainings(), db.recordDao().intakes(), db.measurementDao().getMeasurementsFlow(), db.recordDao().knowledge()) { t,i,m,k ->
        (t.map { HistoryItem("training",it.id,it.revision,day(it.dateContext),it.title.ifBlank { "训练" },it.rawSummary.orEmpty(),DomainJson.encode(it)) } +
            i.map { HistoryItem("intake",it.id,it.revision,day(it.dateContext),"实际摄入",it.rawDescription.orEmpty(),DomainJson.encode(it)) } +
            m.map { HistoryItem("measurement",it.id,it.revision,day(it.observed),label(it.metric),"${it.value} ${it.unit}",DomainJson.encode(it)) } +
            k.map { HistoryItem("knowledge",it.id,it.revision,it.effectiveFrom,"长期认识 · ${it.kind}",it.text,DomainJson.encode(it)) }).sortedByDescending { it.date }
    }
    suspend fun raw(type: String,id: String): JSONObject? = when(type) {
        "training" -> db.recordDao().training(id)?.let { JSONObject(DomainJson.encode(it)) }
        "intake" -> db.recordDao().intake(id)?.let { JSONObject(DomainJson.encode(it)) }
        "measurement" -> db.measurementDao().byId(id)?.let { JSONObject(DomainJson.encode(it)) }
        "knowledge" -> db.recordDao().knowledgeById(id)?.let { JSONObject(DomainJson.encode(it)) }
        else -> null
    }
    private suspend fun put(type: String, p: JSONObject) {
        when(type) {
            "training" -> { val x=DomainJson.decode<TrainingSession>(p.toString()); if(db.recordDao().training(x.id)==null)db.recordDao().insertTraining(x) else db.recordDao().updateTraining(x) }
            "intake" -> { val x=DomainJson.decode<IntakeEntry>(p.toString()); if(db.recordDao().intake(x.id)==null)db.recordDao().insertIntake(x) else db.recordDao().updateIntake(x) }
            "measurement" -> db.measurementDao().insert(DomainJson.decode(p.toString()))
            "knowledge" -> db.recordDao().putKnowledge(DomainJson.decode(p.toString()))
            else -> error("不支持的记录类型")
        }
    }
    private suspend fun snapshot(type: String,p: JSONObject): String {
        val d=db.collaborationDao(); val id=p.getString("id"); val rev=p.getInt("revision")
        d.entitySnapshot(type,id,rev)?.let { return it.id }
        val sid=UUID.randomUUID().toString(); d.putEntitySnapshot(EntitySnapshotEntity(sid,type,id,rev,p.toString(),System.currentTimeMillis())); return sid
    }
    suspend fun change(item: HistoryItem, text: String? = null, value: Float? = null, delete: Boolean = false): MutationToken = db.withTransaction {
        val p=requireNotNull(raw(item.type,item.id)); check(p.getInt("revision")==item.revision && p.isNull("deletedAt")) { "记录已更新，请重新打开" }
        val before=snapshot(item.type,p); val now=System.currentTimeMillis()
        if(delete)p.put("deletedAt",now) else when(item.type) {
            "training" -> { require(!text.isNullOrBlank()); p.put("rawSummary",text) }
            "intake" -> { require(!text.isNullOrBlank()); p.put("rawDescription",text) }
            "measurement" -> { validateMeasurement(p.getString("metric"),value,p.getString("unit")); p.put("value",value) }
            "knowledge" -> { require(!text.isNullOrBlank()); p.put("text",text).put("origin",JSONObject().put("kind","user").put("corrected",true).put("inputRefs",org.json.JSONArray()).toString()) }
        }
        p.put("revision",item.revision+1).put("updatedAt",now); put(item.type,p); invalidate()
        val token=MutationToken(UUID.randomUUID().toString(),"local-change")
        db.collaborationDao().putReceipt(MutationReceiptEntity(UUID.randomUUID().toString(),token.submissionId,token.operationId,
            "[${DomainJson.encode(EntityRef(item.type,item.id,item.revision+1))}]",before,null,now)); token
    }
    suspend fun add(type: String,text: String,date: LocalDate,metric: String="weight",value: Float?=null): MutationToken = db.withTransaction {
        val zone=db.profileDao().getProfile()?.preferredZoneId ?: ZoneId.systemDefault().id
        require(!date.isAfter(LocalDate.now(ZoneId.of(zone)))) { "记录日期不能晚于今天" }
        val dc=JSONObject().put("date",date.toString()).put("zoneId",zone).put("basis","operation_context").toString()
        val id=UUID.randomUUID().toString(); val now=System.currentTimeMillis()
        val p=when(type) {
            "training" -> { require(text.isNotBlank()); JSONObject(DomainJson.encode(TrainingSession(id,dc,"other","训练补记",null,"[]",text,"user",1,now,now))) }
            "intake" -> { require(text.isNotBlank()); JSONObject(DomainJson.encode(IntakeEntry(id,dc,null,"item:$id","description","[]",null,text,"partial","user",1,now,now))) }
            "measurement" -> { val unit=if(metric=="weight")"kg" else if(metric=="body_fat")"%" else "cm"; validateMeasurement(metric,value,unit)
                JSONObject(DomainJson.encode(Measurement(id,metric,dc,now,value!!,unit,"user",text.takeIf(String::isNotBlank),1,now,now))) }
            "knowledge" -> { require(text.isNotBlank()); JSONObject(DomainJson.encode(KnowledgeNote(id,1,"user_note",text,JSONObject().put("kind","user").put("corrected",true).toString(),date.toString(),null,"current",null,now,now))) }
            else -> error("不支持的记录类型")
        }
        put(type,p); invalidate()
        val token=MutationToken(UUID.randomUUID().toString(),"local-add")
        db.collaborationDao().putReceipt(MutationReceiptEntity(UUID.randomUUID().toString(),token.submissionId,token.operationId,"[${DomainJson.encode(EntityRef(type,id))}]",null,null,now)); token
    }
    suspend fun undo(token: MutationToken) = db.withTransaction {
        val d=db.collaborationDao(); val receipt=requireNotNull(d.receipt(token.submissionId,token.operationId))
        if(d.receipt(token.submissionId,"undo")!=null)return@withTransaction
        val ref=DomainJson.decode<EntityRef>(org.json.JSONArray(receipt.resultRefs).getJSONObject(0).toString())
        val current=requireNotNull(raw(ref.type,ref.id)); check(current.getInt("revision")==ref.revision) { "记录已有后续修改，不能覆盖撤销" }
        snapshot(ref.type,current)
        val restored=receipt.beforeSnapshotId?.let { JSONObject(requireNotNull(d.snapshotById(it)).dataJson) }
            ?: JSONObject(current.toString()).put("deletedAt",System.currentTimeMillis())
        restored.put("revision",ref.revision+1).put("updatedAt",System.currentTimeMillis()); put(ref.type,restored); invalidate()
        d.putReceipt(receipt.copy(id=UUID.randomUUID().toString(),operationId="undo"))
    }
    private suspend fun invalidate() { invalidateDerived(db) }
    companion object {
        fun day(raw: String)=if(raw.startsWith("{"))JSONObject(raw).getString("date") else raw.take(10)
        fun label(metric: String)=mapOf("weight" to "体重","body_fat" to "体脂率","waist" to "腰围","height" to "身高")[metric] ?: metric
        fun validateMeasurement(metric: String,value: Float?,unit: String) {
            require(value!=null && value.isFinite() && when(metric){"weight" -> unit=="kg" && value>0 && value<=500;"body_fat" -> unit=="%" && value>0 && value<100;"waist","height" -> unit=="cm" && value>0 && value<=300;else -> false}) { "请输入有效数值与单位" }
        }
    }
}
