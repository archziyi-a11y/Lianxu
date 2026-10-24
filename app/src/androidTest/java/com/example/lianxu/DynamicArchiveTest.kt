package com.example.lianxu

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lianxu.archive.*
import com.example.lianxu.agent.*
import com.example.lianxu.data.api.*
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.repository.*
import com.example.lianxu.engine.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.io.*

@RunWith(AndroidJUnit4::class)
class DynamicArchiveTest {
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val date=LocalDate.of(2026,10,24)
 private fun memory()=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
 private suspend fun weight(db:AppDatabase,id:String,day:LocalDate,value:Float=70f)=db.measurementDao().insert(Measurement(id,"weight",day.toString(),null,value,"kg","user",null,1,1,1))
 private suspend fun reject(block:suspend()->Unit){var failed=false;try{block()}catch(_:Exception){failed=true};assertTrue("must reject",failed)}
 private suspend fun withRepo(block:suspend(ArchiveRepository)->Unit){val db=memory();try{val repo=ArchiveRepository(context,db);repo.methods.installBuiltins();block(repo)}finally{db.close()}}
 @Test fun roughDataProducesOnlySupportedMethodsAndWindows()=runBlocking { withRepo { repo->
  val db=repo.db;db.recordDao().insertTraining(TrainingSession("t",date.toString(),"strength","练胸",null,"[]","练胸","user",1,1,1));weight(db,"w",date)
  repo.refresh(date);val topics=db.archiveDao().topics().filter { it.state=="current" }
  assertTrue(topics.any { it.methodId=="builtin.calendar" });assertTrue(topics.any { it.methodId=="builtin.weight-series" })
  assertFalse(topics.any { it.methodId in listOf("builtin.weight-trend","builtin.progress","builtin.interval") });assertEquals(setOf("accumulated"),topics.map { it.scopeKey }.toSet())
  val before=db.archiveDao().topics().map { it.calculationId };repo.refresh(date);assertEquals(before,db.archiveDao().topics().map { it.calculationId })
 } }
 @Test fun samplingGatesDependOnActualCoverageNotAccountAge()=runBlocking { withRepo { repo->
  weight(repo.db,"old",date.minusDays(100));weight(repo.db,"new",date)
  repo.refresh(date);assertFalse(repo.db.archiveDao().topics().any { it.methodId=="builtin.weight-trend" && it.state=="current" })
  for(i in listOf(13,12,11,4,2,0))weight(repo.db,"dense$i",date.minusDays(i.toLong()),70f+i/10f)
  repo.refresh(date);assertTrue(repo.db.archiveDao().topics().any { it.methodId=="builtin.weight-trend" && it.state=="current" })
 } }
 @Test fun importedMethodUpgradeKeepsPriorityTopicAndArchiveSurvivesBackup()=runBlocking { withRepo { repo->
  weight(repo.db,"w",date);val raw=JSONObject(repo.db.archiveDao().methods().first { it.id=="builtin.weight-series" }.packageJson)
  raw.getJSONObject("manifest").put("id","user.weight").put("windows",JSONArray().put("accumulated"))
  repo.methods.importPackage(raw.toString());repo.refresh(date)
  val old=repo.db.archiveDao().topics().single { it.methodId=="user.weight" };repo.placement(old.id,"priority")
  raw.getJSONObject("manifest").put("version",2);repo.methods.importPackage(raw.toString());repo.refresh(date)
  val newer=repo.db.archiveDao().topics().single { it.methodId=="user.weight" };assertEquals(old.id,newer.id);assertNotEquals(old.calculationId,newer.calculationId)
  assertTrue(repo.cards("accumulated").any { it.priority && newer.id in it.topics })
  repo.placement(newer.id,"archive");assertTrue(repo.cards("accumulated").none { newer.id in it.topics });assertTrue(repo.cards("accumulated",all=true).any { newer.id in it.topics })
  val out=ByteArrayOutputStream();BackupRepository(context,repo.db).export(out);val restored=memory()
  try{BackupRepository(context,restored).restore(ByteArrayInputStream(out.toByteArray()));val again=ArchiveRepository(context,restored);again.refresh(date)
   assertEquals("archive",restored.archiveDao().placements().single().placement);assertTrue(again.cards("accumulated").none { newer.id in it.topics });assertTrue(restored.archiveDao().methods().any { it.id=="user.weight" && it.version==2 })
  }finally{restored.close()}
 } }
 @Test fun invalidMethodPackagesFailWithoutPartialInstall()=runBlocking { withRepo { repo->
  val base=JSONObject(repo.db.archiveDao().methods().first().packageJson);base.getJSONObject("manifest").put("id","user.invalid").put("operator","execute_sql")
  reject { repo.methods.importPackage(base.toString()) };assertFalse(repo.db.archiveDao().methods().any { it.id=="user.invalid" })
  reject { repo.methods.importPackage(base.toString()+" trailing") }
  val fractional=JSONObject(repo.db.archiveDao().methods().first { it.id=="builtin.weight-series" }.packageJson);fractional.getJSONObject("manifest").getJSONObject("parameters").put("rollingDays",2.5)
  reject { MethodRegistry.parse(fractional.toString()) }
  val prompt=repo.methods.authoringPrompt();assertTrue(prompt.contains("schemaVersion=1"));assertTrue(prompt.contains("user.my-weight-series"))
  File(context.filesDir,"archive-authoring-prompt.txt").writeText(prompt)
 } }
 private suspend fun publication(repo:ArchiveRepository):JSONObject {
  val t=repo.db.archiveDao().topics().first { it.methodId=="builtin.weight-series" && it.scopeKey=="accumulated" }
  return JSONObject().put("cards",JSONArray().put(JSONObject().put("title","身体变化").put("span",6).put("blocks",JSONArray()
   .put(JSONObject().put("topicKey",t.id).put("type","metric").put("path","latest").put("label","最近体重"))
   .put(JSONObject().put("topicKey",t.id).put("type","text").put("text","根据已记录体重观察变化。")))))
 }
 @Test fun composerPublishesOnceAndCannotChangeFactsOrArchivedPlacement()=runBlocking { withRepo { repo->
  weight(repo.db,"w",date);repo.refresh(date);val proposal=publication(repo);var requests=0
  val composer=ArchiveComposer(repo,AgentClient { _,tools->requests++;assertEquals(setOf("list_available_results","read_result","run_registered_method","publish_archive"),tools.map { it.function.name }.toSet())
   ResponseMessage("assistant",null,listOf(ToolCall("p","function",ToolCallFunction("publish_archive",proposal.toString())))) })
  assertTrue(composer.run());assertEquals(1,requests);assertFalse(composer.run());assertEquals(1,requests)
  assertEquals(1,repo.db.measurementDao().all().size);assertTrue(repo.db.recordDao().allTrainings().isEmpty())
  val topic=proposal.getJSONArray("cards").getJSONObject(0).getJSONArray("blocks").getJSONObject(0).getString("topicKey")
  repo.placement(topic,"archive");reject { composer.publish(proposal,repo.db.archiveDao().job()!!.desiredFingerprint) }
  assertEquals("archive",repo.db.archiveDao().placements().single().placement)
 } }
 @Test fun staleResponseAndUnboundNumbersRollbackPublication()=runBlocking { withRepo { repo->
  weight(repo.db,"w",date);repo.refresh(date);val basis=repo.db.archiveDao().job()!!.desiredFingerprint;val facts=repo.factFingerprint();val p=publication(repo)
  weight(repo.db,"new",date.minusDays(1));val composer=ArchiveComposer(repo,AgentClient { _,_->error("not called") })
  reject { composer.publish(p,basis,facts) };assertNull(repo.db.archiveDao().presentation())
  repo.refresh(date);p.getJSONArray("cards").getJSONObject(0).getJSONArray("blocks").getJSONObject(0).put("path","fabricatedNumber")
  reject { composer.publish(p,repo.db.archiveDao().job()!!.desiredFingerprint) };assertNull(repo.db.archiveDao().presentation());assertTrue(repo.db.collaborationDao().allAnalyses().none { it.kind=="archive_ai" })
 } }
 @Test fun deletionRemovesCurrentTopicWithoutLosingPriorityPreference()=runBlocking { withRepo { repo->
  weight(repo.db,"w",date);repo.refresh(date);val key=repo.db.archiveDao().topics().first { it.methodId=="builtin.weight-series" }.id;repo.placement(key,"priority")
  repo.db.measurementDao().insert(repo.db.measurementDao().byId("w")!!.copy(deletedAt=2,revision=2));repo.refresh(date)
  assertTrue(repo.cards("accumulated").isEmpty());assertEquals("priority",repo.db.archiveDao().placements().single().placement)
  weight(repo.db,"next",date,71f);repo.refresh(date);assertTrue(repo.cards("accumulated").any { it.priority && key in it.topics })
 } }
 @Test fun intervalDiscoveryRequiresComparableIndependentAndStablePairs()=runBlocking { withRepo { repo->
  val db=repo.db;var d=date.minusDays(40);var load=50.0
  for(i in 0..16){
   if(i>0){val gap=if(i%2==0)3L else 1L;d=d.plusDays(gap);if(gap==3L)load*=1.10}
   val ex=RecordedExercise("e$i","卧推","bench_press","barbell",NumberSpec("exact",3.0),NumberSpec("exact",8.0),NumberSpec("exact",load),"total")
   db.recordDao().insertTraining(TrainingSession("t$i",d.toString(),"strength","卧推",null,DomainJson.encode(listOf(ex)),null,"user",1,i.toLong(),i.toLong()))
  }
  repo.refresh(date);val discoveries=db.archiveDao().topics().filter { it.methodId=="builtin.interval" && it.scopeKey=="accumulated" && it.state=="current" }
  assertTrue(discoveries.isNotEmpty());val r=JSONObject(db.collaborationDao().calculation(discoveries.first().calculationId)!!.result)
  assertEquals(16,r.getInt("sampleCount"));assertEquals(2,r.getJSONArray("points").length())
  // Same dates with incompatible equipment split the history; neither group has enough pairs.
  db.recordDao().allTrainings().forEachIndexed { i,t->val e=JSONArray(t.exercises).getJSONObject(0).put("equipment","machine$i")
   db.recordDao().updateTraining(t.copy(exercises=JSONArray().put(e).toString(),revision=2)) }
  repo.refresh(date);assertFalse(db.archiveDao().topics().any { it.methodId=="builtin.interval" && it.state=="current" })
 } }
 @Test fun dietaryTotalsDoNotDoubleCountDailyAndMealCoverage()=runBlocking { withRepo { repo->
  val now=System.currentTimeMillis()
  for((id,coverage,energy) in listOf(Triple("item","item:rice",200.0),Triple("meal","meal:lunch",400.0),Triple("daily","daily_total",2000.0))){
   repo.db.recordDao().insertIntake(IntakeEntry(id,date.toString(),"lunch",coverage,"nutrient_total","[]",DomainJson.encode(NutrientValues(energy,20.0)),"已记录","partial","user",1,now,now))
  }
  repo.refresh(date);val topic=repo.db.archiveDao().topics().single { it.methodId=="builtin.intake" && it.subjectKey=="energyKcal" && it.scopeKey=="accumulated" }
  assertEquals(2000.0,JSONObject(repo.db.collaborationDao().calculation(topic.calculationId)!!.result).getDouble("latest"),.0001)
 } }

 @Test fun archivedInformationDoesNotReappearThroughAnotherWindow()=runBlocking { withRepo { repo->
  weight(repo.db,"w",date);weight(repo.db,"older",date.minusDays(40));repo.refresh(date)
  val key=repo.db.archiveDao().topics().single { it.methodId=="builtin.weight-series" && it.scopeKey=="accumulated" }.id
  repo.placement(key,"priority");assertTrue(repo.cards("28d").any { key in it.topics && it.priority })
  repo.placement(key,"archive");assertTrue(repo.cards("28d").none { c->c.topics.any { it.startsWith("builtin.weight-series|") } })
  assertTrue(repo.cards("28d",all=true).any { c->c.topics.any { it.startsWith("builtin.weight-series|") } })
 } }
 @Test fun externalAssistantCanImportACollectionAtomically()=runBlocking { withRepo { repo->
  val source=repo.db.archiveDao().methods().first { it.id=="builtin.weight-series" }.packageJson
  val a=JSONObject(source);a.getJSONObject("manifest").put("id","user.a")
  val b=JSONObject(source);b.getJSONObject("manifest").put("id","user.b").put("operator","unknown")
  val bundle=JSONObject().put("schemaVersion",1).put("packages",JSONArray().put(a).put(b))
  reject { repo.methods.importPackage(bundle.toString()) };assertFalse(repo.db.archiveDao().methods().any { it.id=="user.a" })
  b.getJSONObject("manifest").put("operator","series");repo.methods.importPackage(bundle.toString())
  assertEquals(2,repo.db.archiveDao().methods().count { it.id.startsWith("user.") })
 } }

 @Test fun mixedWindowCardsRemainVisibleAndCatalogRetainsEveryResult()=runBlocking { withRepo { repo->
  weight(repo.db,"old",date.minusDays(40));weight(repo.db,"now",date);repo.refresh(date)
  val topics=repo.db.archiveDao().topics().filter { it.methodId=="builtin.weight-series" && it.scopeKey in listOf("accumulated","28d") }
  assertEquals(2,topics.size)
  val blocks=JSONArray(topics.map { JSONObject().put("topicKey",it.id).put("type","metric").put("path","latest").put("label",it.scopeKey) })
  val p=JSONObject().put("cards",JSONArray().put(JSONObject().put("title","不同窗口对照").put("span",6).put("blocks",blocks)))
  ArchiveComposer(repo,AgentClient { _,_->error("not called") }).publish(p,repo.db.archiveDao().job()!!.desiredFingerprint)
  assertTrue(repo.cards("28d").any { it.card.managedBy=="assistant" })
  val catalog=repo.cards("28d",all=true);assertTrue(catalog.any { topics.last { it.scopeKey=="28d" }.id in it.topics });assertTrue(catalog.all { it.card.managedBy!="assistant" })
 } }

 @Test fun previousSchemaFiveBackupCanRestoreIntoSix()=runBlocking { withRepo { repo->
  weight(repo.db,"w",date);val output=ByteArrayOutputStream();BackupRepository(context,repo.db).export(output)
  val entries=linkedMapOf<String,ByteArray>();java.util.zip.ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip->while(true){val e=zip.nextEntry ?: break;entries[e.name]=zip.readBytes()} }
  val data=JSONObject(entries.getValue("data.json").toString(Charsets.UTF_8));for(key in listOf("archive_methods","archive_topics","archive_placements","archive_presentations","archive_jobs"))data.remove(key)
  val manifest=JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8)).put("schemaVersion",5).put("dataSha256",fingerprint(data.toString()))
  entries["manifest.json"]=manifest.toString().toByteArray();entries["data.json"]=data.toString().toByteArray()
  val legacy=ByteArrayOutputStream();java.util.zip.ZipOutputStream(legacy).use { zip->entries.forEach { (name,bytes)->zip.putNextEntry(java.util.zip.ZipEntry(name));zip.write(bytes);zip.closeEntry() } }
  val target=memory();try{BackupRepository(context,target).restore(ByteArrayInputStream(legacy.toByteArray()));assertEquals("w",target.measurementDao().all().single().id);assertTrue(target.archiveDao().topics().isEmpty())}finally{target.close()}
 } }

}
