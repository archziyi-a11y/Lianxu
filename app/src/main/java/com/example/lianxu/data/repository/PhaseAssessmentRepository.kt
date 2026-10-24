package com.example.lianxu.data.repository

import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.model.EntityRef
import com.example.lianxu.engine.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/** An offline, sourced phase summary. Qualitative AI assessments remain separate persisted analyses. */
class PhaseAssessmentRepository(private val db: AppDatabase) {
    suspend fun refresh(date: LocalDate): AnalysisEntity? = db.withTransaction {
        val start=date.minusDays(27).toString(); val end=date.toString(); val d=db.collaborationDao()
        val m=db.measurementDao().all().filter { HistoryRepository.day(it.observed) in start..end }
        val t=db.recordDao().allTrainings().filter { HistoryRepository.day(it.dateContext) in start..end }
        val i=db.recordDao().allIntakes().filter { HistoryRepository.day(it.dateContext) in start..end }
        if(m.isEmpty() && t.isEmpty() && i.isEmpty())return@withTransaction null
        val goal=db.goalPhaseDao().getGoalPhasesFlow().first().firstOrNull { it.startsOn<=end && (it.endsOn==null || it.endsOn>end) }
        val notes=db.recordDao().allKnowledge().filter { it.status=="current" && it.effectiveFrom<=end && (it.effectiveUntil==null || it.effectiveUntil>=end) }
        val refs=m.map { EntityRef("measurement",it.id,it.revision) }+t.map { EntityRef("training",it.id,it.revision) }+
            i.map { EntityRef("intake",it.id,it.revision) }+notes.map { EntityRef("knowledge",it.id,it.revision) }+listOfNotNull(goal?.let { EntityRef("goal",it.id,it.revision) })
        val refsRaw=JSONArray(refs.map { JSONObject(DomainJson.encode(it)) }).toString()
        val frequency=trainingFrequency(t.map { LocalDate.parse(HistoryRepository.day(it.dateContext)) to it.kind },date)
        val trend=weightTrend(m.filter { it.metric=="weight" }.map { WeightPoint(LocalDate.parse(HistoryRepository.day(it.observed)),it.value.toDouble()) },date)
        val result=JSONObject().put("frequency",JSONObject(DomainJson.encode(frequency)))
            .put("weight",JSONObject().put("points",JSONArray(trend.points.map { JSONObject().put("date",it.date.toString()).put("value",it.value) }))
                .put("recentMean",trend.recentMean).put("previousMean",trend.previousMean).put("weeklyChangeKg",trend.weeklyChangeKg))
            .put("intakeRecordedDays",i.map { HistoryRepository.day(it.dateContext) }.distinct().size)
        val text=buildString {
            append("${start} 至 ${end}，")
            goal?.let { append("阶段目标：${when(it.kind){"gain"->"增肌";"cut"->"减脂";else->"维持"}}。") }
            if(t.isNotEmpty()) {
                append("\n已记录${frequency.trainingDays}个训练日、${frequency.sessionCount}次训练。")
                frequency.weeklyRecordedDays?.let { append("近28天平均每周已记录${ComponentResolver.displayNumber(it,"次")}天。") }
                val names=t.groupingBy { it.kind }.eachCount().entries.joinToString("、") { "${when(it.key){"strength","resistance"->"力量";"cardio"->"有氧";"rest"->"休息";else->"其他"}}${it.value}次" }
                append(names)
            }
            if(trend.points.isNotEmpty())append("\n有${trend.points.size}个体重测量日。")
            trend.weeklyChangeKg?.let { append("两段周均值变化${ComponentResolver.displayNumber(it,"kg")}kg。") }
            m.filter { it.metric=="body_fat" }.maxByOrNull { HistoryRepository.day(it.observed) }?.let { append("\n最近录入体脂率${it.value}%。") }
            if(i.isNotEmpty())append("\n${i.map { HistoryRepository.day(it.dateContext) }.distinct().size}天有饮食记录，可结合已记录部分分析习惯。")
            if(notes.isNotEmpty())append("\n后续分析采用你当前保留的长期认识与纠正。")
        }.trim()
        val cid=UUID.nameUUIDFromBytes("phase:$end:$refsRaw".toByteArray()).toString(); val aid="assessment:$cid"; val now=System.currentTimeMillis()
        if(d.calculation(cid)==null)d.putCalculation(CalculationEntity(cid,"phase_assessment",METHOD_VERSION,
            JSONObject().put("measurements",JSONArray(m.map { JSONObject(DomainJson.encode(it)) })).put("trainings",JSONArray(t.map { JSONObject(DomainJson.encode(it)) }))
                .put("intakes",JSONArray(i.map { JSONObject(DomainJson.encode(it)) })).put("knowledge",JSONArray(notes.map { JSONObject(DomainJson.encode(it)) })).toString(),refsRaw,result.toString(),now))
        val analysis=AnalysisEntity(aid,1,"assessment",start,end,refsRaw,text,"[]",JSONArray(listOf(cid)).toString(),"current",now,now)
        val old=d.analysis(aid)
        if(old==null || old.freshness!="current")d.putAnalysis(analysis)
        analysis
    }
}
