package com.example.lianxu

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.agent.*
import com.example.lianxu.data.api.*
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.model.*
import com.example.lianxu.data.repository.*
import com.example.lianxu.engine.*
import com.example.lianxu.timer.RestTimerRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*

@RunWith(AndroidJUnit4::class)
class StageFourTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val date=LocalDate.of(2026,10,24)
    private fun memory()=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
    private suspend fun seed(db: AppDatabase) { AppRepository(db).saveArchive(175f,"1996-01-01","male",1.6f,70f,"gain",date)
        val p=db.profileDao().getProfile()!!;db.profileDao().insert(p.copy(preferredZoneId="Asia/Shanghai")) }
    private suspend fun board(db: AppDatabase)=BoardStore(db).open(emptyList(),date,ZoneId.of("Asia/Shanghai"))
    private suspend fun prepare(db: AppDatabase,id: String,note: String="分析当前记录"): Submission {
        BoardStore(db).edit(id) { cards -> cards.mapIndexed { index,c -> if(index==0)c.copy(isAnnotationMode=true,annotationText=note) else c } }
        return BoardStore(db).prepare(id)
    }
    private suspend fun failure(block: suspend ()->Unit) { var failed=false;try{block()}catch(e:Exception){failed=true};assertTrue("must reject",failed) }
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun midnightArchivesWithoutMovingDraftAndOldTaskCannotOverwriteNewDay()=runBlocking {
        val db=memory()
        try {
            seed(db);val service=NutritionBoardRepository.create(context,db)
            val before=DayLifecycle(db,Clock.fixed(Instant.parse("2026-10-24T15:59:59Z"),ZoneOffset.UTC)).ensureCurrent();service.refresh(before.id)
            val frozen=prepare(db,before.id,"原日补充")
            val next=DayLifecycle(db,Clock.fixed(Instant.parse("2026-10-24T16:00:01Z"),ZoneOffset.UTC)).ensureCurrent();service.refresh(next.id)
            assertNotEquals(before.id,next.id);assertEquals("archived",db.collaborationDao().board(before.id)!!.status)
            assertTrue(db.collaborationDao().input(frozen.turn.inputBundleId)!!.dateContext.contains("2026-10-24"))
            val currentCards=BoardStore(db).watchCards(next.id).first()
            AgentRunner(context,db,service.catalog,null).run(frozen)
            assertEquals(currentCards,BoardStore(db).watchCards(next.id).first());assertNull(db.collaborationDao().board(next.id)!!.latestCompletedTurnId)
            assertEquals(frozen.turn.id,db.collaborationDao().board(before.id)!!.latestCompletedTurnId)
            assertEquals("archived",db.collaborationDao().board(before.id)!!.status)
            val same=DayLifecycle(db,Clock.fixed(Instant.parse("2026-10-24T16:00:20Z"),ZoneOffset.UTC)).ensureCurrent()
            assertEquals(next.id,same.id)
        }finally{db.close()}
    }
    @Test fun twoTurnsAndFailedThirdKeepLatestSuccessAndRetryIdentity()=runBlocking {
        val db=memory()
        try { seed(db);val id=board(db);val service=NutritionBoardRepository.create(context,db);service.refresh(id)
            val first=prepare(db,id,"第一轮");AgentRunner(context,db,service.catalog,null).run(first)
            val second=prepare(db,id,"第二轮");AgentRunner(context,db,service.catalog,null).run(second)
            assertEquals(2,db.collaborationDao().turns(id).size);assertEquals(second.turn.id,db.collaborationDao().board(id)!!.latestCompletedTurnId)
            val third=prepare(db,id,"断网后仍保留");BoardStore(db).fail(third,"断网")
            assertEquals(second.turn.id,db.collaborationDao().board(id)!!.latestCompletedTurnId)
            val retried=BoardStore(db).prepare(id);assertEquals(third.turn.submissionId,retried.turn.submissionId)
            assertTrue(db.collaborationDao().draft(id)!!.cardsJson.contains("断网后仍保留"))
        }finally{db.close()}
    }
    @Test fun lunchClosesEarlierPlanningButDoesNotFabricateBreakfastAndCanBeReopened()=runBlocking {
        val db=memory()
        try { seed(db);val id=board(db);val service=NutritionBoardRepository.create(context,db);service.refresh(id)
            val p=db.collaborationDao().planForDate(db.collaborationDao().board(id)!!.dateContext)!!
            val original=DomainJson.decode<MealsDocument>(p.meals).meals;val breakfast=original.first()
            val now=System.currentTimeMillis()
            AppRepository(db).recordIntake(IntakeEntry("partial-lunch",p.dateContext,"lunch","item:partial-lunch","description","[]",null,"只记了一点午餐","partial","user",1,now,now))
            service.refresh(id)
            val current=db.collaborationDao().plan(p.id)!!;val meals=DomainJson.decode<MealsDocument>(current.meals).meals
            assertEquals(breakfast,meals.first());assertEquals(1,db.recordDao().allIntakes().size)
            assertTrue(BoardStore(db).watchCards(id).first().single { it.id=="$id:meal:${breakfast.mealId}" }.title.contains("不再安排"))
            service.act(CardAction("reopen","仍需安排","reopen_meal",EntityRef("plan",current.id,current.revision),breakfast.mealId));service.refresh(id)
            assertEquals("pending",db.collaborationDao().allMealProgress().single().state)
            assertFalse(closedMealIds(meals,setOf("lunch"),mapOf(breakfast.mealId to "pending")).contains(breakfast.mealId))
        }finally{db.close()}
    }
    @Test fun deletedSourcesInvalidateAssessmentAndNeverEnterNextModelContext()=runBlocking {
        val db=memory()
        try { seed(db);val history=HistoryRepository(db);history.add("training","REMOVED-SOURCE-TRAINING",date)
            val phase=PhaseAssessmentRepository(db).refresh(date)!!;val item=history.items.first().first { it.type=="training" }
            val cid=JSONArray(phase.calculationIds).getString(0)
            history.change(item,delete=true)
            assertEquals("stale",db.collaborationDao().analysis(phase.id)!!.freshness);assertFalse(SourceValidator(db).usable(EntityRef("calculation",cid),true))
            val id=board(db);val service=NutritionBoardRepository.create(context,db);service.refresh(id);val s=prepare(db,id)
            var contextText=""
            val client=AgentClient { messages,_ -> contextText=messages[1].content.toString();
                val p=JSONObject().put("contractVersion","2").put("turnId",s.turn.id).put("taskId",s.task.id).put("operations",JSONArray()).put("summary","依据当前记录整理")
                ResponseMessage("assistant",null,listOf(ToolCall("finish","function",ToolCallFunction("finish_proposal",p.toString())))) }
            AgentRunner(context,db,service.catalog,client).run(s)
            assertFalse(contextText.contains("REMOVED-SOURCE-TRAINING"));assertFalse(contextText.contains(item.id))
        }finally{db.close()}
    }
    @Test fun correctedKnowledgeIsCurrentAndCannotBeOverwrittenWithOldEvidence()=runBlocking {
        val db=memory()
        try { seed(db);val now=System.currentTimeMillis();db.recordDao().putKnowledge(KnowledgeNote("habit",1,"frequency","每天训练","assistant",date.toString(),null,"current",null,now,now))
            val history=HistoryRepository(db);history.change(history.items.first().first { it.type=="knowledge" },text="每周两次，按实际记录分析")
            val id=board(db);val service=NutritionBoardRepository.create(context,db);service.refresh(id);val s=prepare(db,id)
            AgentWorkspace.open(context,db,s,service.catalog).use { w ->
                val annotation=JSONArray(db.collaborationDao().input(s.turn.inputBundleId)!!.annotationsJson).getJSONObject(0).getString("annotationId")
                val op=JSONObject().put("operationId","overwrite").put("kind","upsert_knowledge").put("targetRef",JSONObject().put("type","knowledge").put("id","habit").put("revision",2))
                    .put("payload",JSONObject().put("kind","frequency").put("text","每天训练")).put("relatedAnnotationIds",JSONArray().put(annotation)).put("reason","旧依据重算")
                failure { w.stage(JSONArray().put(op)) }
            }
            var text="";val client=AgentClient { messages,_ -> text=messages[1].content.toString()
                val p=JSONObject().put("contractVersion","2").put("turnId",s.turn.id).put("taskId",s.task.id).put("operations",JSONArray()).put("summary","使用已纠正的习惯")
                ResponseMessage("assistant",null,listOf(ToolCall("f","function",ToolCallFunction("finish_proposal",p.toString())))) }
            AgentRunner(context,db,service.catalog,client).run(s);assertTrue(text.contains("每周两次"));assertFalse(text.contains("每天训练"))
        }finally{db.close()}
    }
    @Test fun localMeasurementCorrectionDeleteAndUndoPreserveVersions()=runBlocking {
        val db=memory()
        try { val history=HistoryRepository(db);val added=history.add("measurement","",date,"body_fat",18f)
            val original=history.items.first().single();val edited=history.change(original,value=17f)
            assertEquals(2,db.measurementDao().all().single().revision)
            history.undo(edited);assertEquals(18f,db.measurementDao().all().single().value);assertEquals(3,db.measurementDao().all().single().revision)
            failure { history.undo(added) }
            val deleted=history.change(history.items.first().single(),delete=true);assertTrue(db.measurementDao().all().isEmpty())
            history.undo(deleted);assertEquals(5,db.measurementDao().all().single().revision)
        }finally{db.close()}
    }
    @Test fun backupRestoresAttachmentsDraftsSnapshotsAndRejectsCorruptionAtomically()=runBlocking {
        val source=memory();val target=memory();val image=File(context.filesDir,"attachments/backup-fixture-${UUID.randomUUID()}").apply { parentFile!!.mkdirs();writeBytes(byteArrayOf(1,2,3,4)) }
        try { seed(source);val id=board(source);val service=NutritionBoardRepository.create(context,source);service.refresh(id)
            source.collaborationDao().putAttachment(AttachmentEntity("backup-image",image.relativeTo(context.filesDir).invariantSeparatorsPath,"image/jpeg",sha(image.readBytes()),image.length(),System.currentTimeMillis()))
            BoardStore(source).edit(id) { it.mapIndexed { n,c -> if(n==0)c.copy(attachmentId="backup-image",annotationText="图片和文字草稿",isAnnotationMode=true) else c } }
            val s=BoardStore(source).prepare(id);BoardStore(source).fail(s,"保留失败轮次")
            val out=ByteArrayOutputStream();BackupRepository(context,source).export(out);val bytes=out.toByteArray()
            BackupRepository(context,target).restore(ByteArrayInputStream(bytes))
            assertEquals(source.profileDao().getProfile(),target.profileDao().getProfile())
            assertTrue(target.collaborationDao().draft(id)!!.cardsJson.contains("图片和文字草稿"));assertNotNull(target.collaborationDao().snapshot(s.turn.beforeBoardSnapshotId!!))
            val attachment=target.collaborationDao().attachment("backup-image")!!;assertArrayEquals(image.readBytes(),File(context.filesDir,attachment.localPath).readBytes())
            val zipped=ByteArrayOutputStream();ZipOutputStream(zipped).use { zip -> ZipInputStream(ByteArrayInputStream(bytes)).use { input -> while(true) { val e=input.nextEntry ?: break;zip.putNextEntry(ZipEntry(e.name));val data=input.readBytes();zip.write(if(e.name=="data.json")"corrupt".toByteArray() else data);zip.closeEntry() } } }
            failure { BackupRepository(context,target).restore(ByteArrayInputStream(zipped.toByteArray())) }
            assertEquals(source.profileDao().getProfile(),target.profileDao().getProfile())
            File(context.filesDir,attachment.localPath).delete();File(context.filesDir,attachment.localPath).parentFile?.delete()
            Unit
        }finally{source.close();target.close();image.delete()}
    }
    @Test fun timerUsesMonotonicDeadlineUntilBootChanges()=runBlocking {
        val db=memory()
        try { val timer=RestTimerEntity(deadlineWallMs=10000,deadlineElapsedMs=8000,bootCount=1,status="running",updatedAt=1)
            val repo=RestTimerRepository(context,db)
            assertEquals(3000L,repo.remaining(timer,wall=900000,elapsed=5000,currentBoot=1))
            assertEquals(1000L,repo.remaining(timer,wall=9000,elapsed=5000,currentBoot=2))
            assertEquals(0L,repo.remaining(timer,wall=11000,elapsed=9000,currentBoot=2))
        }finally{db.close()}
    }
}
