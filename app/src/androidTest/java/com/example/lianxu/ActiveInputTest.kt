package com.example.lianxu

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.agent.*
import com.example.lianxu.archive.*
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.model.*
import com.example.lianxu.data.repository.*
import com.example.lianxu.engine.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.io.*

@RunWith(AndroidJUnit4::class)
class ActiveInputTest {
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val date=LocalDate.now(ZoneId.of("Asia/Shanghai"))
 private fun db()=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
 private suspend fun reject(block:suspend()->Unit){var rejected=false;try{block()}catch(_:Exception){rejected=true};assertTrue("expected rejection",rejected)}
 private suspend fun board(db:AppDatabase,day:LocalDate=date):String {
  val id=BoardStore(db).open(emptyList(),day,ZoneId.of("Asia/Shanghai"));NutritionBoardRepository.create(context,db).refresh(id);return id
 }
 private fun proposal(w:AgentWorkspace)=JSONObject().put("contractVersion","2").put("turnId",w.submission.turn.id).put("taskId",w.submission.task.id).put("operations",JSONArray()).put("summary","已保存本次填写，未知字段保持未知。")
 private suspend fun finish(db:AppDatabase,id:String):Submission {
  val s=BoardStore(db).prepare(id);val catalog=NutritionBoardRepository.create(context,db).catalog
  AgentWorkspace.open(context,db,s,catalog).use { w->val p=proposal(w);val a=ProposalApplier(w);val cards=a.finish(p);a.commit(cards,p);a.commit(cards,p) };return s
 }

 @Test fun emptyPreparedControlsNeverBecomeFactsAndTypedRangesCommitOnce()=runBlocking {
  val db=db();try {
   val id=board(db);assertTrue(db.recordDao().allTrainings().isEmpty());reject { BoardStore(db).prepare(id) }
   BoardStore(db).edit(id){cards->cards.map { if(it.id.endsWith(":training-input"))it.copy(inputValues=mapOf("name" to "卧推","sets" to "3","repsMin" to "8","repsMax" to "12")) else it }}
   val s=finish(db,id);assertEquals(1,db.recordDao().allTrainings().size)
   val e=DomainJson.decode<RecordedExercise>(JSONArray(db.recordDao().allTrainings().single().exercises).getJSONObject(0).toString())
   assertEquals("range",e.repsPerSet.kind);assertEquals(24.0,summarizeExercise(e).repetitionsMin!!,0.0);assertNull(e.loadKg.value)
   assertTrue(BoardStore(db).watchCards(id).first().none(ActiveInputRepository::hasInput));assertEquals("completed",db.collaborationDao().turn(s.turn.id)!!.status)
   val raw=JSONArray(db.collaborationDao().input(s.turn.inputBundleId)!!.annotationsJson);assertTrue(raw.toString().contains("structured"))
  }finally{db.close()}
 }

 @Test fun failedMixedSubmissionKeepsAllInputsAndRetryIdentity()=runBlocking {
  val db=db();try {
   val id=board(db);val image=File(context.filesDir,"stage5-test-image");image.writeText("fixture")
   db.collaborationDao().putAttachment(AttachmentEntity("photo","stage5-test-image","image/png","fixture",7,1))
   BoardStore(db).edit(id){cards->cards.map { when {
    it.id.endsWith(":training-input")->it.copy(inputValues=mapOf("name" to "卧推","sets" to "3"))
    it.id.endsWith(":intake-input")->it.copy(annotationText="午餐图片，晚餐再调整",attachmentId="photo")
    else->it
   } }}
   val store=BoardStore(db);val s=store.prepare(id)
   AgentWorkspace.open(context,db,s,NutritionBoardRepository.create(context,db).catalog).use { w->assertEquals(1,w.overlay.recordDao().allTrainings().size);assertTrue(db.recordDao().allTrainings().isEmpty()) }
   store.fail(s,"网络失败夹具");val retry=store.prepare(id);assertEquals(s.turn.submissionId,retry.turn.submissionId)
   assertEquals(2,store.watchCards(id).first().count(ActiveInputRepository::hasInput));assertTrue(db.recordDao().allTrainings().isEmpty());image.delete();Unit
  }finally{db.close()}
 }

 @Test fun followUpCrossesDaysProgressAppendsAndCancellationRejectsOldPublication()=runBlocking {
  val db=db();try {
   val active=ActiveInputRepository(db);val fid=active.arrange("上次训练后的恢复感觉怎么样？")
   val id=board(db,date.plusDays(1));val follow=BoardStore(db).watchCards(id).first().single { it.followUpId==fid }
   BoardStore(db).edit(id){cards->cards.map { if(it.id==follow.id)it.copy(annotationText="今天恢复好多了") else it }}
   finish(db,id)
   assertEquals(1,db.recordDao().allKnowledge().count { it.kind=="follow_up_progress" });assertEquals("上次训练后的恢复感觉怎么样？",db.recordDao().knowledgeById(fid)!!.text)
   val next=board(db,date.plusDays(2));assertTrue(BoardStore(db).watchCards(next).first().any { it.followUpId==fid })
   BoardStore(db).edit(next){cards->cards.map { if(it.followUpId==fid)it.copy(annotationText="今天的新情况") else it }}
   val s=BoardStore(db).prepare(next)
   AgentWorkspace.open(context,db,s,NutritionBoardRepository.create(context,db).catalog).use { w->
    val p=proposal(w).put("followUp",JSONObject().put("topicId",fid).put("question","继续关注"))
    val a=ProposalApplier(w);val cards=a.finish(p)
    active.arrange(db.recordDao().knowledgeById(fid)!!.text,db.recordDao().knowledgeById(fid),"cancelled")
    reject { a.commit(cards,p) }
   }
   NutritionBoardRepository.create(context,db).refresh(next)
   assertTrue(BoardStore(db).watchCards(next).first().single { it.followUpId==fid }.title.contains("已取消跟进"))
   val after=board(db,date.plusDays(3));assertTrue(BoardStore(db).watchCards(after).first().none { it.followUpId==fid })
   assertEquals(1,db.recordDao().allKnowledge().count { it.kind=="follow_up_progress" })
   val backup=ByteArrayOutputStream();BackupRepository(context,db).export(backup);val restored=db()
   try { BackupRepository(context,restored).restore(ByteArrayInputStream(backup.toByteArray()));val fresh=board(restored,date.plusDays(4));assertTrue(BoardStore(restored).watchCards(fresh).first().none { it.followUpId==fid });assertEquals("cancelled",restored.recordDao().knowledgeById(fid)!!.status) }finally{restored.close()}
  }finally{db.close()}
 }

 @Test fun cancelledArrangementCannotBeRenamedOrWrittenThroughKnowledgeTool()=runBlocking {
  val db=db();try {
   val active=ActiveInputRepository(db);val fid=active.arrange("跟进恢复");active.arrange("跟进恢复",db.recordDao().knowledgeById(fid),"cancelled")
   val id=board(db);BoardStore(db).edit(id){cards->cards.map { if(it.id.endsWith(":free-input"))it.copy(annotationText="今天状态不错") else it }}
   val s=BoardStore(db).prepare(id)
   AgentWorkspace.open(context,db,s,NutritionBoardRepository.create(context,db).catalog).use { w->
    val p=proposal(w).put("followUp",JSONObject().put("question","换一个名字继续问恢复"));val a=ProposalApplier(w);a.commit(a.finish(p),p)
    assertEquals(1,db.recordDao().allKnowledge().count { it.kind=="follow_up" });assertEquals("cancelled",db.recordDao().knowledgeById(fid)!!.status)
    val operation=JSONObject().put("operationId","bad").put("kind","upsert_knowledge").put("payload",JSONObject().put("kind","follow_up").put("text","复活"))
     .put("relatedAnnotationIds",JSONArray().put(w.annotations.getJSONObject(0).getString("annotationId"))).put("reason","attempt")
    reject { w.applyOperations(JSONArray().put(operation)) }
   }
  }finally{db.close()}
 }

 @Test fun historyPreparesFormatWithoutCopyingPastValuesAndUserCorrectionWins()=runBlocking {
  val db=db();try {
   val origin="user"
   repeat(2){i->db.recordDao().insertTraining(TrainingSession("t$i",date.minusDays(i+1L).toString(),"strength","卧推",null,"""[{"entryId":"e","name":"卧推","summarySets":{"kind":"exact","value":4},"repsPerSet":{"kind":"range","min":8,"max":12}}]""",null,origin,1,i.toLong(),i.toLong()))}
   val id=board(db);val card=BoardStore(db).watchCards(id).first().single { it.id.contains(":habit:") }
   assertEquals("卧推",card.blocks.first().content.suggestedName);assertTrue(card.inputValues.isEmpty());assertEquals(2,db.recordDao().allTrainings().size)
   val note=db.recordDao().allKnowledge().single { it.kind=="input_habit" };ActiveInputRepository(db).editHabit(note,"我的卧推记录","image",true)
   val prepared=ActiveInputRepository(db).cards(id,date).single { it.id.contains(":habit:") };assertTrue(prepared.blocks.first().content.inputFields.isEmpty());assertEquals("我的卧推记录",prepared.title)
  }finally{db.close()}
 }

 @Test fun optionalRegisteredQuestionRespectsEligibilityAndDismissal()=runBlocking {
  val db=db();try {
   MethodRegistry(context,db).installBuiltins();db.goalPhaseDao().insert(GoalPhase("goal","gain",date.minusDays(30).toString(),null,null,1,1,1))
   for(i in listOf(13,12,11,4,2))db.measurementDao().insert(Measurement("w$i","weight",date.minusDays(i.toLong()).toString(),null,70f,"kg","user",null,1,1,1))
   val repo=ActiveInputRepository(db);val id=board(db);val question=BoardStore(db).watchCards(id).first().single { it.invitationId!=null }
   ArchiveRepository(context,db).refresh(date);assertFalse(db.archiveDao().topics().any { it.methodId=="builtin.weight-trend" && it.state=="current" })
   repo.dismissInvitation(question.invitationId!!,date.toString());assertTrue(repo.cards(id,date).none { it.invitationId==question.invitationId })
   db.measurementDao().insert(Measurement("actual","weight",date.toString(),null,70.5f,"kg","user",null,1,1,1));ArchiveRepository(context,db).refresh(date)
   assertTrue(db.archiveDao().topics().any { it.methodId=="builtin.weight-trend" && it.state=="current" });assertEquals(6,db.measurementDao().all().size)
  }finally{db.close()}
 }

 @Test fun offlineRoughTrainingRemainsTraceableWithoutInventedSets()=runBlocking {
  val db=db();try {
   val id=board(db);BoardStore(db).edit(id){cards->cards.map { if(it.id.endsWith(":training-input"))it.copy(annotationText="练胸，状态一般，没记组数") else it }}
   val s=BoardStore(db).prepare(id);AgentRunner(context,db,NutritionBoardRepository.create(context,db).catalog,null).run(s)
   val record=db.recordDao().allTrainings().single();assertEquals("[]",record.exercises);assertTrue(record.rawSummary!!.contains("没记组数"));assertTrue(record.origin.contains(s.turn.inputBundleId))
  }finally{db.close()}
 }
 @Test fun photoOnlyOfflinePreservesMaterialWithoutClaimingCompletedTraining()=runBlocking {
  val db=db();val image=File(context.filesDir,"stage5-only-image")
  try {
   image.writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("training-chest.jpg").use { it.readBytes() })
   val sha=java.security.MessageDigest.getInstance("SHA-256").digest(image.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
   db.collaborationDao().putAttachment(AttachmentEntity("photo-only",image.name,"image/jpeg",sha,image.length(),1))
   val id=board(db);BoardStore(db).edit(id){cards->cards.map { if(it.id.endsWith(":training-input"))it.copy(attachmentId="photo-only") else it }}
   val s=BoardStore(db).prepare(id);AgentRunner(context,db,NutritionBoardRepository.create(context,db).catalog,null).run(s)
   assertTrue(db.recordDao().allTrainings().isEmpty());assertTrue(db.collaborationDao().input(s.turn.inputBundleId)!!.annotationsJson.contains("photo-only"))
   assertEquals("completed",db.collaborationDao().turn(s.turn.id)!!.status)
  }finally{image.delete();db.close()}
 }

 @Test fun dailyToolRejectsHistoricalDateAndLongTermProfileChanges()=runBlocking {
  val db=db();try {
   val id=board(db);BoardStore(db).edit(id){cards->cards.map { if(it.id.endsWith(":free-input"))it.copy(annotationText="一条待整理的情况") else it }}
   val s=BoardStore(db).prepare(id)
   AgentWorkspace.open(context,db,s,NutritionBoardRepository.create(context,db).catalog).use { w->
    fun op(kind:String,payload:JSONObject)=JSONArray().put(JSONObject().put("operationId","scope").put("kind",kind).put("clientRef","scope")
     .put("payload",payload).put("reason","测试页面职责").put("relatedAnnotationIds",JSONArray().put(w.annotations.getJSONObject(0).getString("annotationId"))))
    reject { w.applyOperations(op("create_measurement",JSONObject().put("metric","weight").put("value",70).put("unit","kg").put("observed",date.minusDays(7).toString()))) }
    reject { w.applyOperations(op("set_goal",JSONObject().put("kind","cut").put("startsOn",date.toString()))) }
    assertTrue(db.measurementDao().all().isEmpty())
   }
  }finally{db.close()}
 }

 @Test fun extractedImageHistoryPreparesImageInsteadOfAssumingControlPreference()=runBlocking {
  val db=db();try {
   repeat(2){i->
    db.collaborationDao().putInput(InputBundleEntity("input-$i","turn-$i","submission-$i",date.toString(),"", """[{"annotationId":"a","inputParts":[{"type":"image","attachmentId":"sample"}]}]""",1))
    db.recordDao().insertTraining(TrainingSession("image-t$i",date.minusDays(i+1L).toString(),"strength","图片训练",null,"""[{"entryId":"e","name":"卧推"},{"entryId":"e2","name":"划船"}]""",null,
      """{"inputBundleId":"input-$i","annotationIds":["a"]}""",1,1,1))
   }
   val cards=ActiveInputRepository(db).cards("sample",date)
   val habit=cards.single { it.id.contains(":habit:") }
   assertEquals("用图片记训练",habit.title);assertTrue(habit.blocks.single().content.inputFields.isEmpty())
   assertEquals(2,db.recordDao().allTrainings().size)
  }finally{db.close()}
 }

 @Test fun assistantFollowUpChoicesPersistAcrossDaysAndProgressRemainsSeparate()=runBlocking {
  val db=db();try {
   val id=board(db);BoardStore(db).edit(id){cards->cards.map { if(it.id.endsWith(":free-input"))it.copy(annotationText="后续问一下恢复") else it }}
   val submission=BoardStore(db).prepare(id)
   AgentWorkspace.open(context,db,submission,NutritionBoardRepository.create(context,db).catalog).use { w->
    val p=proposal(w).put("followUp",JSONObject().put("question","今天恢复如何？").put("options",JSONArray("""[{"key":"good","label":"感觉不错"},{"key":"rest","label":"还需休息"}]""")))
    val a=ProposalApplier(w);a.commit(a.finish(p),p)
   }
   val next=board(db,date.plusDays(1));val c=BoardStore(db).watchCards(next).first().single { it.followUpId!=null }
   assertEquals(2,c.blocks.single { it.type=="choice" }.content.options.size)
   BoardStore(db).edit(next){cards->cards.map { if(it.id==c.id)it.copy(annotationText="感觉不错") else it }};finish(db,next)
   assertEquals("感觉不错",db.recordDao().allKnowledge().single { it.kind=="follow_up_progress" }.text)
   assertEquals("今天恢复如何？",db.recordDao().allKnowledge().single { it.kind=="follow_up" }.text)
  }finally{db.close()}
 }

}
