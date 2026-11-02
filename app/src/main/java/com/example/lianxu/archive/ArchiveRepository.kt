package com.example.lianxu.archive

import android.content.Context
import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.model.*
import com.example.lianxu.data.repository.*
import com.example.lianxu.engine.DomainJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.time.LocalDate
import java.time.ZoneId

data class ArchiveDisplay(val topics:List<String>,val card:DashboardCard,val priority:Boolean=false)

fun archivePlacement(topic:ArchiveTopicEntity,allTopics:List<ArchiveTopicEntity>,preferences:List<ArchivePlacementEntity>):String? {
 val direct=preferences.firstOrNull { it.id==topic.id }?.placement
 if(direct!=null && direct!="auto")return direct
 return if(preferences.any { pref->pref.placement=="archive" && allTopics.any { old->old.id==pref.id && old.methodId==topic.methodId && old.subjectKey==topic.subjectKey } })"archive" else null
}

class ArchiveRepository(val context:Context,val db:AppDatabase){
 val methods=MethodRegistry(context,db)
 companion object { private val refreshLock=Mutex() }
 suspend fun today()=LocalDate.now(db.profileDao().getProfile()?.preferredZoneId?.let(ZoneId::of) ?: ZoneId.systemDefault())
 suspend fun refresh(onDate:LocalDate?=null):String=refreshLock.withLock { db.withTransaction {
  val date=onDate ?: today()
  val dao=db.archiveDao();val source=db.collaborationDao();val validator=SourceValidator(db)
  val nutrition=source.allCalculations().filter { it.type=="nutrition_target" && validator.refsUsable(it.inputRefs,true) }
  val profile=db.profileDao().getProfile()
  val data=AnalysisData(db.measurementDao().all(),db.recordDao().allTrainings(),db.recordDao().allIntakes(),db.goalPhaseDao().getGoalPhasesFlow().first(),nutrition,profile)
  val existing=dao.topics().associateBy { it.id };val ids=mutableSetOf<String>()
  for(method in methods.methods())for(result in AnalysisExecutor.execute(method,data,date)){
   val hash=fingerprint("${method.version}|${result.result}|${DomainJson.encode(result.refs)}")
   val old=existing[result.topicKey];ids.add(result.topicKey)
   if(old?.inputFingerprint==hash && old.state=="current"){
    old.analysisId?.let { source.analysis(it) }?.takeIf { it.freshness!="current" && validator.refsUsable(it.inputRefs,true) }?.let { source.putAnalysis(it.copy(freshness="current")) }
    continue
   }
   val cid="archive-calc:$hash";val now=System.currentTimeMillis()
   val refs=JSONArray(result.refs.map { JSONObject(DomainJson.encode(it)) }).toString()
   if(source.calculation(cid)==null)source.putCalculation(CalculationEntity(cid,"archive:${method.id}","${method.id}@${method.version}",JSONObject().put("methodPackage",JSONObject(method.raw)).put("scope",result.scope).put("start",result.start).put("end",result.end).toString(),refs,result.result.toString(),now))
   val aid="archive-local:$hash"
   source.putAnalysis(AnalysisEntity(aid,1,"archive_local",result.start.toString(),result.end.toString(),refs,result.result.getString("summary"),"[]",JSONArray().put(cid).toString(),"current",now,now))
   dao.putTopic(ArchiveTopicEntity(result.topicKey,method.id,result.subject,result.scope,method.category,result.title,cid,aid,method.version,hash,"current",now))
  }
  existing.values.filter { it.id !in ids && it.state=="current" }.forEach { dao.putTopic(it.copy(state="stale")) }
  val current=dao.topics().filter { it.state=="current" }
  val notes=db.recordDao().allKnowledge().filter { it.status=="current" }.filter { validator.usable(EntityRef("knowledge",it.id,it.revision)) }
  val basis=fingerprint(current.joinToString { it.id+it.inputFingerprint }+DomainJson.encode(profile)+DomainJson.encode(data.goals)+DomainJson.encode(notes))
  val oldJob=dao.job()
  if(oldJob?.desiredFingerprint!=basis)dao.putJob(ArchiveJobEntity(desiredFingerprint=basis,completedFingerprint=oldJob?.completedFingerprint,status=if(current.isEmpty())"empty" else "pending",lastError=null,updatedAt=System.currentTimeMillis()))
  basis
 } }
 suspend fun factFingerprint():String {
  val d=db.collaborationDao()
  return fingerprint(DomainJson.encode(db.profileDao().getProfile())+DomainJson.encode(db.goalPhaseDao().getGoalPhasesFlow().first())+DomainJson.encode(db.measurementDao().all())+DomainJson.encode(db.recordDao().allTrainings())+DomainJson.encode(db.recordDao().allIntakes())+DomainJson.encode(db.recordDao().allKnowledge())+DomainJson.encode(db.archiveDao().methods())+DomainJson.encode(d.allCalculations().filter { it.type=="nutrition_target" }.map { it.id }))
 }
 suspend fun placement(topicKey:String,placement:String)=db.withTransaction {
  require(placement in listOf("auto","priority","archive"));require(db.archiveDao().topics().any { it.id==topicKey })
  val all=db.archiveDao().topics();val topic=all.single { it.id==topicKey };val now=System.currentTimeMillis()
  db.archiveDao().placements().filter { pref->pref.id!=topicKey && all.any { it.id==pref.id && it.methodId==topic.methodId && it.subjectKey==topic.subjectKey } }.forEach { db.archiveDao().putPlacement(it.copy(placement="auto",updatedAt=now)) }
  db.archiveDao().putPlacement(ArchivePlacementEntity(topicKey,placement,now,now))
 }
 suspend fun toggleCollapsed(cardId:String)=db.withTransaction {
  val presentation=requireNotNull(db.archiveDao().presentation())
  val cards=decode(presentation.cardsJson)
  require(cards.any { it.card.id==cardId && it.card.managedBy=="external" })
  db.archiveDao().putPresentation(presentation.copy(revision=presentation.revision+1, cardsJson=encode(cards.map { if(it.card.id==cardId)it.copy(card=it.card.copy(collapsed=!it.card.collapsed,collapsedOverride=true)) else it }), updatedAt=System.currentTimeMillis()))
 }
 suspend fun cards(scope:String?,category:String?=null,all:Boolean=false):List<ArchiveDisplay>{
  val dao=db.archiveDao();val allTopics=dao.topics();val currentTopics=allTopics.filter { it.state=="current" };val topics=currentTopics.filter { (scope==null || it.scopeKey==scope) && (category==null || it.category==category) }
  val preferences=dao.placements().associateBy { it.id }
  val valid=SourceValidator(db)
  val allEligible=currentTopics.filter { valid.usable(EntityRef("calculation",it.calculationId),true) }.associateBy { it.id }
  val eligible=topics.filter { it.id in allEligible }.associateBy { it.id }
  val presentation=dao.presentation()
  val composed=if(all)emptyList() else presentation?.let { runCatching { decode(it.cardsJson) }.getOrDefault(emptyList()) }.orEmpty().filter { display->
   display.topics.isNotEmpty() && display.topics.any { it in eligible } && display.topics.all { key->key in allEligible && archivePlacement(allEligible.getValue(key),allTopics,preferences.values.toList()) !in listOf("priority","archive") } &&
    display.card.blocks.all { block->(block.content.calculationId?.let { id->display.topics.any { allEligible[it]?.calculationId==id } } ?: true) &&
     (block.content.contentRef?.let { valid.usable(it.entityRef) } ?: true) }
  }
  val priorities=(if(all)topics else currentTopics).filter { preferences[it.id]?.placement=="priority" && valid.usable(EntityRef("calculation",it.calculationId),true) }.sortedBy { preferences[it.id]!!.position }.map { localCard(it,true) }
  val used=composed.flatMap { it.topics }.toSet()+priorities.flatMap { it.topics }
  val remainder=eligible.values.filter { it.id !in used && (all || archivePlacement(it,allTopics,preferences.values.toList())!="archive") }
  val selected=if(all)remainder.toList() else remainder.sortedWith(compareByDescending<ArchiveTopicEntity> { it.category=="发现" }.thenByDescending { it.updatedAt }).distinctBy { it.category to it.subjectKey }.take((8-composed.size).coerceAtLeast(0))
  return priorities+composed+selected.map { localCard(it) }
 }
 suspend fun localCard(topic:ArchiveTopicEntity,priority:Boolean=false):ArchiveDisplay {
  val c=requireNotNull(db.collaborationDao().calculation(topic.calculationId));val r=JSONObject(c.result)
  val blocks=mutableListOf<BoardComponent>()
  if(r.getString("chartKind")=="none"){
   for(path in listOf("trainingCount","measurementCount","intakeCount"))if(r.optInt(path)>0)blocks.add(BoardComponent(path,"metric",ComponentContent(label=when(path){"trainingCount"->"训练";"measurementCount"->"测量";else->"饮食"},calculationId=c.id,resultPath=path,unit="条")))
  }else{
   if(r.getString("chartKind")=="line")blocks.add(BoardComponent("latest","metric",ComponentContent(label=if(topic.methodId=="builtin.nutrition")"当前目标" else if(JSONObject(c.inputSnapshot).optJSONObject("methodPackage")?.optJSONObject("manifest")?.optString("operator")=="rolling_mean")"最近滚动均值" else "最近记录",calculationId=c.id,resultPath="latest",unit=r.getString("unit"))))
   if(r.getJSONArray("points").length()>1 || r.getString("chartKind")!="line")blocks.add(BoardComponent("chart","trend",ComponentContent(label=topic.title,calculationId=c.id,unit=r.getString("unit"))))
  }
  // A local explanation is bound to the same calculation; the composer may provide a sourced replacement.
  if(topic.methodId=="builtin.nutrition")for(key in listOf("proteinG","carbohydrateG","fatG","fiberG"))if(r.opt(key) is Number)blocks.add(BoardComponent(key,"metric",ComponentContent(label=when(key){"proteinG"->"蛋白质";"carbohydrateG"->"碳水";"fatG"->"脂肪";else->"纤维"},calculationId=c.id,resultPath=key,unit="g")))
  val aid=topic.analysisId;val a=aid?.let { db.collaborationDao().analysis(it) }
  if(a!=null && a.freshness=="current" && a.summaryText.isNotBlank())blocks.add(BoardComponent("summary","text",ComponentContent(contentRef=ContentRef(EntityRef("analysis",a.id,a.revision),"summaryText"))))
  return ArchiveDisplay(listOf(topic.id),DashboardCard("archive:${topic.id}",topic.title,span=if(blocks.size==1 && blocks.first().type=="metric")CardSpan.MEDIUM else CardSpan.LARGE,blocks=blocks),priority)
 }
 fun decode(raw:String)=JSONArray(raw).rows().map { ArchiveDisplay(it.getJSONArray("topics").texts(),DomainJson.decode<DashboardCard>(it.getJSONObject("card").toString())) }
 fun encode(cards:List<ArchiveDisplay>)=JSONArray(cards.map { JSONObject().put("topics",JSONArray(it.topics)).put("card",JSONObject(DomainJson.encode(it.card))) }).toString()
}
