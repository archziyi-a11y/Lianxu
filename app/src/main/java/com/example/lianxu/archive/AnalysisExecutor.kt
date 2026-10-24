package com.example.lianxu.archive

import com.example.lianxu.data.entity.*
import com.example.lianxu.data.model.EntityRef
import com.example.lianxu.data.repository.*
import com.example.lianxu.engine.*
import org.json.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class AnalysisData(val measurements:List<Measurement>,val trainings:List<TrainingSession>,val intakes:List<IntakeEntry>,val goals:List<GoalPhase>,val nutrition:List<CalculationEntity>,val profile:Profile?)
data class Observation(val date:LocalDate,val value:Double,val label:String,val subject:String,val unit:String,val ref:EntityRef,val extra:String="")
data class MethodResult(val topicKey:String,val subject:String,val scope:String,val title:String,val result:JSONObject,val refs:List<EntityRef>,val start:LocalDate,val end:LocalDate)

/** Pure, bounded registered operations over a captured fact snapshot. No model-supplied numbers. */
object AnalysisExecutor {
 fun execute(m:AnalysisMethod,d:AnalysisData,end:LocalDate):List<MethodResult> {
  val allDates=d.measurements.map { day(it.observed) }+d.trainings.map { day(it.dateContext) }+d.intakes.map { day(it.dateContext) }
  val oldest=allDates.filter { it<=end }.minOrNull() ?: end
  val goal=d.goals.firstOrNull { it.startsOn<=end.toString() && (it.endsOn==null || it.endsOn>end.toString()) }
  val out=mutableListOf<MethodResult>()
  for(scope in m.windows){
   val start=when(scope){"current_phase"->goal?.startsOn?.let(LocalDate::parse) ?: continue;"7d"->end.minusDays(6);"28d"->end.minusDays(27);"90d"->end.minusDays(89);else->oldest}
   if(start>end)continue
   if(scope.endsWith("d") && oldest>start)continue
   val scoped=d.copy(measurements=d.measurements.filter { day(it.observed) in start..end },trainings=d.trainings.filter { day(it.dateContext) in start..end && it.kind!="rest" },intakes=d.intakes.filter { day(it.dateContext) in start..end })
   fun emit(subject:String,title:String,points:List<Observation>,kind:String,summary:String,more:JSONObject=JSONObject()) {
    val dates=points.map { it.date }.distinct();if(points.size<m.minPoints || dates.isEmpty() || ChronoUnit.DAYS.between(dates.min(),dates.max())<m.minDays)return
    val sorted=points.sortedBy { it.date };val unit=sorted.first().unit
    val result=JSONObject().put("summary",summary).put("chartKind",kind).put("unit",unit).put("sampleCount",points.size).put("start",start.toString()).put("end",end.toString())
     .put("points",JSONArray(sorted.map { JSONObject().put("date",it.date.toString()).put("label",it.label).put("value",it.value) }))
     .put("latest",sorted.last().value).put("change",sorted.last().value-sorted.first().value).put("mean",points.map { it.value }.average())
    if(kind!="line"){result.remove("latest");result.remove("change");result.remove("mean")}
    more.keys().forEach { result.put(it,more.get(it)) }
    val refs=(points.map { it.ref }+listOfNotNull(if(scope=="current_phase")goal?.let { EntityRef("goal",it.id,it.revision) } else null)).distinct()
    out.add(MethodResult("${m.id}|$subject|$scope",subject,scope,title,result,refs,start,end))
   }
   when(m.operator){
    "series","rolling_mean" -> {
     val rows=scoped.measurements.filter { it.metric==m.metric }.groupBy { day(it.observed) }.map { (_,rows)->rows.maxWith(compareBy<Measurement> { it.measuredAt ?: it.createdAt }.thenBy { it.createdAt }) }.sortedBy { day(it.observed) }
     val raw=rows.map { Observation(day(it.observed),it.value.toDouble(),day(it.observed).toString(),it.metric,it.unit,EntityRef("measurement",it.id,it.revision)) }
     if(m.operator=="series")emit(m.metric!!,m.title,raw,"line","已记录${raw.size}个测量日")
     else {
      val recent=raw.filter { it.date>end.minusDays(m.rollingDays.toLong()) };val previous=raw.filter { it.date>end.minusDays(2*m.rollingDays.toLong()) && it.date<=end.minusDays(m.rollingDays.toLong()) }
      if(recent.size>=3 && previous.size>=3){
       val smooth=raw.map { point->point.copy(value=raw.filter { it.date<=point.date && it.date>point.date.minusDays(m.rollingDays.toLong()) }.map { it.value }.average()) }
       emit(m.metric!!,m.title,smooth,"line","${m.rollingDays}日滚动均值；相邻两段均值对比",JSONObject().put("change",recent.map { it.value }.average()-previous.map { it.value }.average()))
      }
     }
    }
    "calendar" -> {
     val points=scoped.trainings.map { Observation(day(it.dateContext),1.0,day(it.dateContext).toString(),"sessions","次",EntityRef("training",it.id,it.revision)) }
     emit("sessions",m.title,points,"calendar","${points.map { it.date }.distinct().size}个训练日 · ${points.size}次记录",JSONObject().put("trainingDays",points.map { it.date }.distinct().size))
    }
    "distribution" -> {
     val groups=mutableMapOf<String,MutableList<Observation>>()
     for(session in scoped.trainings)for(e in exercises(session)){
      val sets=if(e.sets.isNotEmpty())e.sets.size.toDouble() else e.summarySets.takeIf { it.kind=="exact" }?.value ?: continue
      val name=muscle(e) ?: e.name
      groups.getOrPut(name){mutableListOf()}.add(Observation(day(session.dateContext),sets,name,name,"组",EntityRef("training",session.id,session.revision)))
     }
     val all=groups.values.flatten()
     emit("sets",m.title,all,"bars","按已知组数统计；无法可靠映射的动作保留动作名",JSONObject().put("points",JSONArray(groups.map { (name,rows)->JSONObject().put("label",name).put("value",rows.sumOf { it.value }).put("date",rows.maxOf { it.date }.toString()) })))
    }
    "exercise_progress","interval_compare" -> {
     for((key,points) in performance(scoped.trainings).groupBy { it.subject }){
      val daily=points.groupBy { it.date }.values.map { rows->rows.maxBy { it.value } }.sortedBy { it.date }
      if(m.operator=="exercise_progress")emit(key,"${points.first().extra} · 进展",daily,"line","同动作、器械、侧别、次数和负重口径比较")
      else {
       val pairs=daily.zipWithNext().filter { it.first.value>0 }.map { (a,b)->b.copy(value=(b.value-a.value)/a.value*100,unit="%",label=if(ChronoUnit.DAYS.between(a.date,b.date)<=2)"间隔1–2天" else "间隔3天以上") }
       stableGroups(pairs,m)?.let { (a,b)->emit(key,"${points.first().extra} · 间隔与表现",pairs,"groups","比较相邻可比训练的表现变化；两个时间段方向一致，属于记录关联",JSONObject().put("points",groupPoints(pairs)).put("difference",b-a)) }
      }
     }
    }
    "phase_summary" -> {
     val points=scoped.trainings.map { Observation(day(it.dateContext),1.0,"训练", "phase","次",EntityRef("training",it.id,it.revision)) }+
      scoped.measurements.map { Observation(day(it.observed),0.0,"测量","phase","条",EntityRef("measurement",it.id,it.revision)) }+
      scoped.intakes.map { Observation(day(it.dateContext),0.0,"饮食","phase","条",EntityRef("intake",it.id,it.revision)) }
     emit("phase",m.title,points,"none","${scoped.trainings.size}次训练、${scoped.measurements.size}次测量、${scoped.intakes.size}条饮食记录",JSONObject().put("trainingCount",scoped.trainings.size).put("measurementCount",scoped.measurements.size).put("intakeCount",scoped.intakes.size))
    }
    "phase_compare" -> {
     val phases=d.goals.filter { it.startsOn<=end.toString() }.sortedBy { it.startsOn }.takeLast(2)
     if(phases.size==2){
      val p=phases.map { g->val a=LocalDate.parse(g.startsOn);val b=g.endsOn?.let(LocalDate::parse)?.minusDays(1) ?: end;g to d.measurements.filter { it.metric=="weight" && day(it.observed) in a..b } }
      if(p.all { (_,rows)->rows.map { day(it.observed) }.distinct().size>=3 }){
       val obs=p.flatMap { (g,rows)->rows.map { Observation(day(it.observed),it.value.toDouble(),g.startsOn,"phases","kg",EntityRef("measurement",it.id,it.revision)) } }
       val more=JSONObject().put("points",groupPoints(obs)).put("goalRefs",JSONArray(phases.map { JSONObject(DomainJson.encode(EntityRef("goal",it.id,it.revision))) }))
       emit("weight",m.title,obs,"groups","相邻阶段已记录体重均值；不能据此测定肌肉增长",more)
       if(out.lastOrNull()?.topicKey=="${m.id}|weight|$scope"){ val old=out.removeAt(out.lastIndex);out.add(old.copy(refs=(old.refs+phases.map { EntityRef("goal",it.id,it.revision) }).distinct())) }
      }
     }
    }
    "training_weight" -> {
     val weights=scoped.measurements.filter { it.metric=="weight" }.groupBy { day(it.observed) }.mapValues { it.value.map { p->p.value.toDouble() }.average() }
     val obs=mutableListOf<Observation>();var cursor=start
     while(cursor.plusDays(13)<=end){
      val a=weights.filterKeys { it in cursor..cursor.plusDays(6) };val b=weights.filterKeys { it in cursor.plusDays(7)..cursor.plusDays(13) }
      if(a.size>=3 && b.size>=3){
       val ts=scoped.trainings.filter { day(it.dateContext) in cursor..cursor.plusDays(6) }
       val count=ts.map { day(it.dateContext) }.distinct().size
       val ref=scoped.measurements.first { day(it.observed) in b.keys && it.metric=="weight" }.let { EntityRef("measurement",it.id,it.revision) }
       obs.add(Observation(cursor.plusDays(13),b.values.average()-a.values.average(),if(count<=2)"每周0–2个训练日" else "每周3个以上训练日","training-weight","kg",ref))
      };cursor=cursor.plusDays(14)
     }
     stableGroups(obs,m)?.let { emit("weight",m.title,obs,"groups","不重叠双周配对中的训练频率与后续周均体重变化，仅描述关联",JSONObject().put("points",groupPoints(obs)))
      val old=out.removeAt(out.lastIndex);out.add(old.copy(refs=(old.refs+scoped.measurements.filter { it.metric=="weight" }.map { EntityRef("measurement",it.id,it.revision) }+scoped.trainings.map { EntityRef("training",it.id,it.revision) }).distinct())) }
    }
    "intake_summary","scene_compare" -> {
     val entries=scoped.intakes.groupBy { day(it.dateContext) }.values.flatMap { dayEntries ->
      val known=dayEntries.mapNotNull { e->intakeNutrients(e)?.proteinG?.let { e } }.sortedByDescending { it.createdAt }
      val total=known.firstOrNull { it.coverageKey=="daily_total" }
      if(total!=null)listOf(total) else dayEntries.filter { it.coverageKey!="daily_total" }.groupBy { it.mealKey }.values.flatMap { meal->
       val covered=meal.filter { it.mealKey!=null && it.coverageKey=="meal:${it.mealKey}" && intakeNutrients(it)?.proteinG!=null }.maxByOrNull { it.createdAt }
       if(covered!=null)listOf(covered) else meal.filter { !it.coverageKey.startsWith("meal:") }
      }
     }
     val obs=entries.mapNotNull { e ->
      val nutrients=intakeNutrients(e) ?: return@mapNotNull null
      val label=if(m.operator=="scene_compare")runCatching { JSONObject(e.origin).optString("scene").takeIf { it in listOf("home","canteen","takeout") } }.getOrNull() ?: return@mapNotNull null else e.mealKey ?: "其他记录"
      Observation(day(e.dateContext),nutrients.proteinG ?: return@mapNotNull null,label,"intake","g",EntityRef("intake",e.id,e.revision))
     }
     if(m.operator=="intake_summary"){
      var any=false
      for(key in NUTRIENT_KEYS){
       val daily=scoped.intakes.groupBy { day(it.dateContext) }.mapNotNull { (date,rows)->
        val values=rows.map { e->IntakeValue(e.id,e.mealKey,e.coverageKey,intakeNutrients(e) ?: NutrientValues(),e.createdAt,e.completeness) }
        if(values.none { it.values[key]!=null })return@mapNotNull null
        val totals=effectiveIntake(values);val unit=if(key=="energyKcal")"kcal" else "g"
        Observation(date,totals.knownSubtotal[key] ?: return@mapNotNull null,date.toString(),key,unit,EntityRef("intake",rows.first().id,rows.first().revision))
       }
       val name=when(key){"energyKcal"->"已记录能量";"proteinG"->"已记录蛋白质";"carbohydrateG"->"已记录碳水";"fatG"->"已记录脂肪";else->"已记录纤维"}
       val count=out.size;emit(key,name,daily,"line","按每日记录去重核算；部分记录仅为已知小计，不代表全天摄入")
       if(out.size>count){any=true;val old=out.removeAt(out.lastIndex);out.add(old.copy(refs=scoped.intakes.map { EntityRef("intake",it.id,it.revision) }))}
      }
      if(!any)emit("entries",m.title,scoped.intakes.map { Observation(day(it.dateContext),1.0,it.mealKey ?: "未分类","entries","条",EntityRef("intake",it.id,it.revision)) },"bars","保留饮食描述，当前没有可核算营养值")
     } else {
      // A day contributes once per explicitly recorded scene; avoid pseudo-replicating entries.
      val daily=obs.groupBy { it.date to it.label }.map { (_,rows)->rows.first().copy(value=rows.map { it.value }.average()) }
      stableGroups(daily,m)?.let { emit("protein",m.title,daily,"groups","明确场景下已记录蛋白质均值，非全天总量",JSONObject().put("points",groupPoints(daily)))
       val old=out.removeAt(out.lastIndex);out.add(old.copy(refs=obs.map { it.ref }.distinct())) }
     }
    }
    "nutrition_history" -> {
     val calcs=d.nutrition.filter { runCatching { LocalDate.parse(JSONObject(it.inputSnapshot).getString("date")) in start..end }.getOrDefault(false) }.groupBy { JSONObject(it.inputSnapshot).getString("date") }.values.map { it.sortedBy { c->c.createdAt }.last() }
     val points=calcs.mapNotNull { c->val target=JSONObject(c.result).optJSONObject("targets") ?: return@mapNotNull null;if(!target.has("energyKcal") || target.isNull("energyKcal"))return@mapNotNull null
      val date=LocalDate.parse(JSONObject(c.inputSnapshot).getString("date"));Observation(date,target.getDouble("energyKcal"),date.toString(),"target","kcal",EntityRef("calculation",c.id)) }
     val latest=calcs.maxByOrNull { JSONObject(it.inputSnapshot).getString("date") }
     val targets=latest?.let { JSONObject(it.result).optJSONObject("targets") }
     val more=JSONObject().put("units",JSONObject(NUTRIENT_KEYS.associateWith { if(it=="energyKcal")"kcal" else "g" }))
     targets?.keys()?.forEach { key->if(!targets.isNull(key))more.put(key,targets.get(key)) }
     emit("energy",m.title,points,"line","已保存的每日营养目标；属于规划，不是实际摄入",more)
    }
   }
  }
  return out
 }
 fun day(raw:String)=LocalDate.parse(HistoryRepository.day(raw))
 private fun exercises(t:TrainingSession)=runCatching { JSONArray(t.exercises).rows().map { DomainJson.decode<RecordedExercise>(it.toString()) } }.getOrDefault(emptyList())
 private fun muscle(e:RecordedExercise):String? {
  val key=e.canonicalKey ?: e.name
  val groups=mapOf(
   "胸部" to setOf("bench_press","barbell_bench_press","incline_bench_press","incline_dumbbell_press","pec_deck_fly","dumbbell_fly","push_up","杠铃卧推","上斜哑铃卧推","夹胸器械夹胸","哑铃飞鸟","俯卧撑"),
   "腿部" to setOf("squat","barbell_squat","back_squat","leg_press","深蹲","杠铃深蹲","腿举"),
   "背部" to setOf("lat_pulldown","pull_up","barbell_row","dumbbell_row","seated_cable_row","杠铃划船","哑铃划船","引体向上","高位下拉"),
   "肩部" to setOf("shoulder_press","dumbbell_shoulder_press","overhead_press","lateral_raise","推肩","哑铃推肩","侧平举")
  )
  return groups.entries.firstOrNull { key in it.value }?.key
 }
 private fun performance(rows:List<TrainingSession>):List<Observation> = rows.flatMap { t->exercises(t).flatMap { e->
  val sets=if(e.sets.isNotEmpty())e.sets.filter { it.isWarmup!=true } else if(e.repsPerSet.kind=="exact" && e.repsPerSet.value?.rem(1.0)==0.0 && e.loadKg.kind=="exact")listOf(RecordedSet(e.entryId,e.repsPerSet.value?.toInt(),e.loadKg.value,e.loadBasis)) else emptyList()
  sets.mapNotNull { s->if(e.equipment.isNullOrBlank())return@mapNotNull null;val load=s.loadKg?.takeIf { it>0 && it.isFinite() } ?: return@mapNotNull null;val reps=s.reps?.takeIf { it>0 } ?: return@mapNotNull null
   if(s.loadBasis !in listOf("total","per_hand","machine_stack"))return@mapNotNull null
   val key="${e.canonicalKey ?: e.name}|${e.equipment ?: "unspecified"}|${s.loadBasis}|${s.side}|$reps"
   Observation(day(t.dateContext),load,day(t.dateContext).toString(),key,"kg",EntityRef("training",t.id,t.revision),"${e.name} · ${reps}次 · ${s.loadBasis}") }
 } }
 private fun groupPoints(rows:List<Observation>,sum:Boolean=false)=JSONArray(rows.groupBy { it.label }.map { (label,p)->JSONObject().put("label",label).put("value",if(sum)p.sumOf { it.value } else p.map { it.value }.average()).put("date",p.maxOf { it.date }.toString()).put("n",p.size) })
 private fun stableGroups(rows:List<Observation>,m:AnalysisMethod):Pair<Double,Double>? {
  val groups=rows.groupBy { it.label }.toSortedMap();if(groups.size!=2 || rows.size<m.minPoints || rows.map { it.date }.distinct().size<m.minPoints)return null
  if(ChronoUnit.DAYS.between(rows.minOf { it.date },rows.maxOf { it.date })<m.minDays)return null
  if(groups.values.any { it.size<m.minPerGroup })return null
  val keys=groups.keys.toList();val a=groups[keys[0]]!!.map { it.value }.average();val b=groups[keys[1]]!!.map { it.value }.average()
  val scale=rows.maxOf { abs(it.value) }.coerceAtLeast(.1);if(abs(b-a)<m.minEffect*scale)return null
  val midpoint=rows.sortedBy { it.date }[rows.size/2].date
  val differences=listOf(rows.filter { it.date<midpoint },rows.filter { it.date>=midpoint }).map { half->val g=half.groupBy { it.label };if(keys.any { g[it].orEmpty().size<2 })return null
   g[keys[1]]!!.map { it.value }.average()-g[keys[0]]!!.map { it.value }.average() }
  if(differences.any { it*(b-a)<=0 })return null
  return a to b
 }
 private fun intakeNutrients(e:IntakeEntry):NutrientValues? = when(e.mode){
  "nutrient_total"->e.manualNutrients?.let { DomainJson.decode<NutrientValues>(it) }
  "items"->runCatching {
   val items=JSONArray(e.items).rows();if(items.isEmpty())return@runCatching null
   val totals=items.map { item->
    val food=item.optJSONObject("foodSnapshot");val n=food?.optJSONObject("nutrients");val grams=item.optDouble("grams",Double.NaN)
    if(n!=null && grams.isFinite())DomainJson.decode<NutrientValues>(n.toString()).scale(grams/100)
    else item.optJSONObject("estimatedNutrients")?.let { DomainJson.decode<NutrientValues>(it.toString()) } ?: NutrientValues()
   }
   NutrientValues.from(NUTRIENT_KEYS.associateWith { key->totals.mapNotNull { it[key] }.takeIf { it.isNotEmpty() }?.sum() })
  }.getOrNull()
  else->null
 }
}
