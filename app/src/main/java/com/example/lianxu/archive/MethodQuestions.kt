package com.example.lianxu.archive

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class MethodQuestion(val key:String,val explanation:String)

/** Deliberately only actionable measurement gaps. Missing comparability and sample cohorts are
 * not interchangeable with one missing measurement. Eligibility remains in AnalysisExecutor. */
object MethodQuestions {
    fun candidates(m:AnalysisMethod,d:AnalysisData,today:LocalDate):List<MethodQuestion> {
        if(m.operator !in listOf("series","rolling_mean") || m.metric!="weight" || d.goals.none { it.startsOn<=today.toString() && (it.endsOn==null || it.endsOn>today.toString()) })return emptyList()
        if(AnalysisExecutor.execute(m,d,today).isNotEmpty())return emptyList()
        val dates=d.measurements.filter { it.metric==m.metric && AnalysisExecutor.day(it.observed)<=today }.map { AnalysisExecutor.day(it.observed) }.distinct().sorted()
        if(dates.isEmpty() || dates.last()==today || dates.size<m.minPoints-1 || ChronoUnit.DAYS.between(dates.first(),today)<m.minDays)return emptyList()
        // Check actual window/coverage rules using a hypothetical point only in memory. Never persist it.
        val sample=d.measurements.first { it.metric==m.metric }.copy(id="eligibility-only", observed=today.toString())
        if(AnalysisExecutor.execute(m,d.copy(measurements=d.measurements+sample),today).isEmpty())return emptyList()
        return listOf(MethodQuestion("${m.id}:${m.version}:${dates.last()}","${m.question}\n可选：补充今天体重，可让「${m.title}」形成可比较的记录。暂不填写也可以继续。"))
    }
}
