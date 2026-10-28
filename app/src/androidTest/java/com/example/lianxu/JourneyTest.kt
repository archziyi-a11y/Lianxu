package com.example.lianxu

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.agent.*
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.repository.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class JourneyTest {
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 @Test fun inputPrecedesTargetsAndDraftSurvivesNewFollowUp()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
  try {
   val day=LocalDate.now();AppRepository(db).saveArchive(175f,"1996-01-01","male",1.6f,70f,"gain",day)
   val store=BoardStore(db);val id=store.open(emptyList(),day);val repo=NutritionBoardRepository.create(context,db);repo.refresh(id)
   assertEquals("记录训练",store.watchCards(id).first().first().title)
   store.edit(id){it.map { c->if(c.title=="记录训练")c.copy(annotationText="今天卧推三组",isAnnotationMode=true) else c }}
   val before=store.watchCards(id).first().first()
   val order=store.watchCards(id).first().map { it.id }
   ActiveInputRepository(db).arrange("恢复如何？");repo.refresh(id)
   val after=store.watchCards(id).first().first();assertEquals(before.id,after.id);assertEquals(before.annotationText,after.annotationText)
   assertEquals(order,store.watchCards(id).first().filter { it.id in order }.map { it.id })
   assertTrue(db.recordDao().allTrainings().isEmpty())
  }finally{db.close()}
 }
 @Test fun newFollowUpSitsAfterReplyAndBeforeTargets()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
  try {
   val day=LocalDate.now();AppRepository(db).saveArchive(175f,"1996-01-01","male",1.6f,70f,"gain",day)
   val store=BoardStore(db);val id=store.open(emptyList(),day);val repo=NutritionBoardRepository.create(context,db);repo.refresh(id)
   store.edit(id){it.map { c->if(c.title=="记录训练")c.copy(annotationText="今天练了") else c }}
   val client=AgentClient { messages,_->
    val input=JSONObject(((messages.first { it.role=="user" }.content as List<*>).first() as Map<*,*>)["text"].toString())
    val proposal=JSONObject().put("contractVersion","2").put("turnId",input.getString("turnId")).put("taskId",input.getString("taskId")).put("operations",JSONArray()).put("summary","本轮隔离回复")
    com.example.lianxu.data.api.ResponseMessage("assistant",null,listOf(com.example.lianxu.data.api.ToolCall("finish","function",com.example.lianxu.data.api.ToolCallFunction("finish_proposal",proposal.toString()))))
   }
   AgentRunner(context,db,repo.catalog,client).run(store.prepare(id))
   val reply=store.watchCards(id).first().first().id
   val fid=ActiveInputRepository(db).arrange("恢复如何？");repo.refresh(id)
   val cards=store.watchCards(id).first();assertEquals(reply,cards.first().id)
   assertTrue(cards.indexOfFirst { it.followUpId==fid } < cards.indexOfFirst { it.id.contains(":target:") })
  }finally{db.close()}
 }
 @Test fun liveSparseTwoRounds()=runBlocking {
  val label=InstrumentationRegistry.getArguments().getString("validationLabel") ?: "journey"
  val keyFile=File(context.cacheDir,"$label-live-key");assumeTrue(keyFile.exists())
  val key=keyFile.readText().trim();keyFile.delete()
  val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
  var activeTask:String?=null
  val responses=JSONArray()
  val transport=DeepSeekAgentClient(key)
  val client=AgentClient { messages,tools->transport.request(messages,tools).also { responses.put(JSONObject().put("content",it.content).put("toolNames",JSONArray(it.tool_calls.orEmpty().map { call->call.function.name }))) } }
  try {
   val store=BoardStore(db);val day=LocalDate.now();val id=store.open(emptyList(),day)
   val repo=NutritionBoardRepository.create(context,db);repo.refresh(id)
   val reports=JSONArray()
   for(text in listOf("今天卧推三组，最后一组挺吃力。次数和重量没记，请保存这次训练。","接着补记今天新做的坐姿划船两组。次数和重量也没记。这是另一个动作，不要再次记录刚才的卧推。")) {
    store.edit(id){cards->cards.map { if(it.id.endsWith(":free-input"))it.copy(annotationText=text) else it }}
    val submission=store.prepare(id);val runner=AgentRunner(context,db,repo.catalog,client)
    activeTask=submission.task.id
    val summary=runner.run(submission)
    assertEquals("completed",db.collaborationDao().turn(submission.turn.id)!!.status)
    reports.put(JSONObject().put("summary",summary).put("requests",runner.requestCount).put("tools",runner.toolCount)
     .put("cards",JSONArray(store.watchCards(id).first().map { JSONObject(com.example.lianxu.engine.DomainJson.encode(it)) })))
   }
   val rows=db.recordDao().allTrainings();assertEquals("two distinct records, not duplicated bench",2,rows.size)
   assertEquals(1,rows.count { it.exercises.contains("卧推") });assertEquals(1,rows.count { it.exercises.contains("划船") })
   for(row in rows){val exercises=JSONArray(row.exercises);for(i in 0 until exercises.length())assertTrue(exercises.getJSONObject(i).optJSONObject("loadKg")?.opt("value") in listOf(null,JSONObject.NULL))}
   assertNull(db.profileDao().getProfile())
   File(context.filesDir,"$label-live-result.json").writeText(JSONObject().put("status","passed").put("model","deepseek-flash").put("realApi",true).put("trainingCount",rows.size).put("rounds",reports).toString(2))
  }catch(error:Throwable){
   File(context.filesDir,"$label-live-result.json").writeText(JSONObject().put("status","failed").put("error",error.message)
    .put("responses",responses).put("trace",activeTask?.let { db.collaborationDao().agentCache(it)?.entriesJson }?.let(::JSONArray)).toString(2))
   throw error
  }finally{db.close()}
 }
}
