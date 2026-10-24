package com.example.lianxu.data.repository

import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.model.EntityRef
import com.example.lianxu.engine.DomainJson
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

class SourceValidator(private val db: AppDatabase) {
    suspend fun refsUsable(raw: String, historical: Boolean = false, visited: Set<String> = emptySet()): Boolean {
        val a = runCatching { JSONArray(raw) }.getOrNull() ?: return false
        for (i in 0 until a.length()) {
            val ref = runCatching { DomainJson.decode<EntityRef>(a.getJSONObject(i).toString()) }.getOrNull() ?: return false
            if (!usable(ref, historical, visited)) return false
        }
        return true
    }
    suspend fun usable(ref: EntityRef, historical: Boolean = false, visited: Set<String> = emptySet()): Boolean {
        val key = "${ref.type}:${ref.id}"; if (key in visited) return false
        val d = db.collaborationDao(); val seen = visited + key
        val version: Int? = when (ref.type) {
            "profile" -> db.profileDao().getProfile()?.takeIf { it.id == ref.id }?.revision
            "goal" -> db.goalPhaseDao().getGoalPhasesFlow().first().find { it.id == ref.id }?.revision
            "measurement" -> db.measurementDao().byId(ref.id)?.takeIf { it.deletedAt == null }?.revision
            "training" -> db.recordDao().training(ref.id)?.takeIf { it.deletedAt == null }?.revision
            "intake" -> db.recordDao().intake(ref.id)?.takeIf { it.deletedAt == null }?.revision
            "knowledge" -> {
                val note = db.recordDao().knowledgeById(ref.id)?.takeIf { it.deletedAt == null && it.status == "current" } ?: return false
                val dependencies=runCatching { JSONObject(note.origin).optJSONArray("inputRefs")?.toString() }.getOrNull()
                return note.revision == ref.revision && (dependencies==null || refsUsable(dependencies,false,seen))
            }
            "plan" -> d.plan(ref.id)?.revision
            "meal_progress" -> d.allMealProgress().find { it.id == ref.id }?.revision
            "policy" -> d.allPolicies().find { it.id == ref.id }?.revision
            "calculation" -> return d.calculation(ref.id)?.let { refsUsable(it.inputRefs, historical, seen) } ?: false
            "analysis" -> return d.analysis(ref.id)?.let { it.revision == ref.revision && it.freshness == "current" && refsUsable(it.inputRefs, historical, seen) } ?: false
            else -> return false
        }
        return version == ref.revision || historical && version != null && d.entitySnapshot(ref.type, ref.id, ref.revision) != null
    }
}


suspend fun invalidateDerived(db: AppDatabase) {
    val dao=db.collaborationDao()
    dao.invalidateAnalyses();dao.invalidatePlans()
    val validator=SourceValidator(db)
    db.recordDao().allKnowledge().filter { it.status=="current" }.forEach { note ->
        if(!validator.usable(EntityRef("knowledge",note.id,note.revision))) {
            if(dao.entitySnapshot("knowledge",note.id,note.revision)==null)dao.putEntitySnapshot(EntitySnapshotEntity(java.util.UUID.randomUUID().toString(),"knowledge",note.id,note.revision,DomainJson.encode(note),System.currentTimeMillis()))
            db.recordDao().putKnowledge(note.copy(status="stale",revision=note.revision+1,updatedAt=System.currentTimeMillis()))
        }
    }
}
