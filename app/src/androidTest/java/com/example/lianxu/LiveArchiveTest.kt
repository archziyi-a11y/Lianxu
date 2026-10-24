package com.example.lianxu

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.archive.*
import com.example.lianxu.agent.DeepSeekAgentClient
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.repository.*
import com.example.lianxu.engine.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class LiveArchiveTest {
 @Test fun actualFlashOrganizesOnlyApplicableSourcedResults()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val label=InstrumentationRegistry.getArguments().getString("validationLabel") ?: "archive"
  val keyFile=File(context.cacheDir,"$label-live-key");assumeTrue("Explicit private API opt-in required",keyFile.isFile)
  val key=keyFile.readText().trim();keyFile.delete()
  val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();var composer:ArchiveComposer?=null
  try {
   val today=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
   AppRepository(db).saveArchive(175f,"1996-01-01","male",1.6f,70f,"gain",today.minusDays(20))
   val profile=db.profileDao().getProfile()!!;db.profileDao().insert(profile.copy(preferredZoneId="Asia/Shanghai"))
   for(i in listOf(13,12,11,4,2,0)){
    val date=today.minusDays(i.toLong());db.measurementDao().insert(Measurement("weight$i","weight",date.toString(),null,70f+(13-i)*.03f,"kg","user",null,1,i.toLong(),i.toLong()))
    if(i%2==0){val exercise=RecordedExercise("bench$i","杠铃卧推","bench_press","barbell",NumberSpec("exact",3.0),NumberSpec("exact",8.0),NumberSpec("exact",60.0+(13-i)),"total")
     db.recordDao().insertTraining(TrainingSession("t$i",date.toString(),"strength","胸部训练",null,DomainJson.encode(listOf(exercise)),"三组卧推","user",1,i.toLong(),i.toLong()))}
   }
   val board=DayLifecycle(db).ensureCurrent();NutritionBoardRepository.create(context,db).refresh(board.id)
   val repo=ArchiveRepository(context,db);repo.methods.installBuiltins();repo.refresh(today)
   val source=repo.factFingerprint();val beforeRecords=db.recordDao().allTrainings()
   composer=ArchiveComposer(repo,DeepSeekAgentClient(key));assertTrue(composer.run())
   assertEquals(source,repo.factFingerprint());assertEquals(beforeRecords,db.recordDao().allTrainings())
   val presentation=requireNotNull(db.archiveDao().presentation());val displays=repo.decode(presentation.cardsJson)
   assertTrue(displays.isNotEmpty());assertTrue(repo.cards("current_phase").any { it.card.managedBy=="assistant" });assertTrue(displays.flatMap { it.card.blocks }.any { it.type=="text" })
   assertEquals("completed",db.archiveDao().job()!!.status)
   val report=JSONObject().put("status","passed").put("realApi",true).put("model","deepseek-flash").put("requests",composer.requests).put("tools",composer.calls)
    .put("factSourcesUnchanged",true).put("cardCount",displays.size).put("cards",JSONArray(presentation.cardsJson))
    .put("analyses",JSONArray(db.collaborationDao().allAnalyses().filter { it.kind=="archive_ai" }.map { JSONObject(DomainJson.encode(it)) }))
   File(context.filesDir,"$label-live-result.json").writeText(report.toString(2))
  }catch(e:Throwable){File(context.filesDir,"$label-live-result.json").writeText(JSONObject().put("status","failed").put("error",e.message).put("requests",composer?.requests).put("tools",composer?.calls).toString(2));throw e}
  finally{db.close();keyFile.delete()}
 }
}
