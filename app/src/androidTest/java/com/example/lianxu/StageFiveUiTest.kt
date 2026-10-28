package com.example.lianxu

import android.app.Application
import android.graphics.Bitmap
import android.provider.MediaStore
import android.content.ContentValues
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.agent.*
import com.example.lianxu.archive.*
import com.example.lianxu.data.api.*
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.repository.*
import com.example.lianxu.ui.screens.*
import com.example.lianxu.ui.theme.LianxuTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.io.File

/** Isolated facts + production theme/screens. The transport below is explicitly an offline fixture. */
@RunWith(AndroidJUnit4::class)
class StageFiveUiTest {
 @get:Rule val rule=createComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private var calls=0
 private val client=AgentClient { messages,_ ->
  calls++;delay(1200)
  val content=messages.first { it.role=="user" }.content as List<*>
  val first=content.first() as Map<*,*>
  val c=JSONObject(first["text"].toString())
  val proposal=JSONObject().put("contractVersion","2").put("turnId",c.getString("turnId")).put("taskId",c.getString("taskId")).put("operations",JSONArray())
   .put("summary","隔离交互样本：本轮填写已保存，未填写负重保持未知。可以继续补充下一轮。")
  ResponseMessage("assistant",null,listOf(ToolCall("finish","function",ToolCallFunction("finish_proposal",proposal.toString()))))
 }
 private fun shot(name:String) {
  rule.onRoot().captureToImage().asAndroidBitmap().let { b->File(context.filesDir,"stage5-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } }
 }
 private suspend fun seed(db:AppDatabase) {
  val date=LocalDate.now()
  AppRepository(db).saveArchive(175f,"1996-01-01","male",1.6f,70.5f,"gain",date.minusDays(28))
  for(i in listOf(20,18,16,13,12,11,7,4,2,0))db.measurementDao().insert(Measurement("sample-weight-$i","weight",date.minusDays(i.toLong()).toString(),null,70f+(20-i)*.025f,"kg","isolated-stage5",null,1,i.toLong(),i.toLong()))
  for(i in listOf(18,15,12,9,6,3))db.recordDao().insertTraining(TrainingSession("sample-training-$i",date.minusDays(i.toLong()).toString(),"strength","卧推与划船",null,
   """[{"entryId":"bench","name":"卧推","equipment":"barbell","loadBasis":"total","summarySets":{"kind":"exact","value":3},"repsPerSet":{"kind":"exact","value":8},"loadKg":{"kind":"exact","value":60},"sets":[]}]""",
   "隔离样本：三组卧推，每组八次", "isolated-stage5",1,i.toLong(),i.toLong()))
  ActiveInputRepository(db).arrange("关于上次训练，今天恢复得怎么样？")
  ArchiveRepository(context,db).also { it.methods.installBuiltins();it.refresh(date) }
 }

 @Test fun inPlaceLongPressTextSwipeAndTwoRounds() {
  val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
  val today=TodayViewModel(context.applicationContext as Application,db,client)
  val archive=ArchiveViewModel(context.applicationContext as Application,db)
  val models=ViewModelStore().apply { put("today",today);put("archive",archive) }
  try {
   rule.setContent { LianxuTheme { MainScreen(today,archive) } }
   rule.waitUntil(15000){today.cards.value.any { it.id.endsWith(":training-input") }}
   val cid=today.cards.value.single { it.id.endsWith(":training-input") }.id
   shot("sparse-light")
   rule.onNodeWithTag("card:$cid").performTouchInput { longClick(center.copy(y=30f)) }
   rule.waitUntil(5000){today.cards.value.single { it.id==cid }.isAnnotationMode}
   rule.onNodeWithText("点按写文字\n上划添加图片 ↑").performClick()
   "今天卧推，感觉轻松".forEach { letter->rule.onNodeWithText("补充 · 记录训练").performTextInput(letter.toString()) }
   rule.waitUntil(5000){today.cards.value.single { it.id==cid }.annotationText=="今天卧推，感觉轻松"}
   shot("annotation-text")
   rule.onNodeWithContentDescription("收好这张 · 继续补充").performClick()
   rule.onNodeWithTag("card:$cid").performScrollTo()
   shot("after-close-editor")
   rule.onNodeWithText("点按写文字\n上划添加图片 ↑").performTouchInput { swipeUp() }
   rule.onNodeWithText("图片附在这张卡片").assertExists();shot("annotation-image-entry")
   rule.onNodeWithText("返回文字").performClick()
   rule.onNodeWithContentDescription("收起补充").performClick()
   rule.onNodeWithText("动作名称").performTextInput("卧推")
   rule.onNodeWithText("填写").performClick()
   // The actual slider participates in the same persisted draft, not an independent record write.
   rule.onNodeWithContentDescription("训练组数").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(3f)}
   val other=today.cards.value.single { it.id.endsWith(":free-input") }.id
   rule.runOnIdle { today.updateAnnotationText(other,"今天恢复得不错") }
   rule.waitUntil(5000){today.cards.value.count(ActiveInputRepository::hasInput)==2}
   rule.onNodeWithContentDescription("统一发送").performClick()
   rule.waitUntil(5000){today.isProcessing.value};shot("sending")
   rule.waitUntil(20000){!today.isProcessing.value && runBlocking { db.recordDao().allTrainings().isNotEmpty() }}
   shot("reply")
   assertEquals(1,runBlocking { db.recordDao().allTrainings().size })
   rule.runOnIdle { today.updateAnnotationText(other,"第二轮补充") }
   rule.waitUntil(5000){today.cards.value.any { it.annotationText=="第二轮补充" }}
   rule.onNodeWithContentDescription("统一发送").performClick()
   rule.waitUntil(20000){!today.isProcessing.value && runBlocking { db.collaborationDao().watchBoards().first().sumOf { db.collaborationDao().turns(it.id).count { turn->turn.status=="completed" } }>=2 }}
   assertEquals(1,runBlocking { db.recordDao().allTrainings().size })
   rule.onNodeWithContentDescription("切换档案").performClick()
   rule.onNodeWithText("习惯与跟进").assertExists()
   shot("archive-sparse")
  } finally { models.clear();db.close() }
 }

 @Test fun richLightAndDarkScreensAndArchiveFollowUpManagement() {
  val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();runBlocking { seed(db) }
  val today=TodayViewModel(context.applicationContext as Application,db,client);val archive=ArchiveViewModel(context.applicationContext as Application,db)
  val models=ViewModelStore().apply{put("today",today);put("archive",archive)}
  var dark by mutableStateOf(false)
  try {
   rule.setContent { LianxuTheme(darkTheme=dark) { MainScreen(today,archive) } }
   rule.waitUntil(20000){today.cards.value.any { it.id.contains(":target:") }}
   shot("today-rich-light")
   rule.runOnIdle { dark=true };shot("today-rich-dark")
   rule.onNodeWithContentDescription("切换档案").performClick();rule.waitUntil(20000){archive.dashboard.value.cards.isNotEmpty()}
   shot("archive-rich-dark");rule.runOnIdle { dark=false };shot("archive-rich-light")
   rule.onNodeWithText("习惯与跟进").performClick();rule.onNodeWithText("取消跟进").performScrollTo().performClick()
   rule.waitUntil(5000){runBlocking { db.recordDao().allKnowledge().any { it.kind=="follow_up" && it.status=="cancelled" } }}
   rule.onNodeWithText("已取消").assertExists();shot("cancelled-follow-up")
  }finally{models.clear();db.close()}
 }

 @Test fun richBoardKeepsNewReplyVisibleAfterSubmission() {
  val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();runBlocking { seed(db) }
  val today=TodayViewModel(context.applicationContext as Application,db,client);val archive=ArchiveViewModel(context.applicationContext as Application,db)
  val models=ViewModelStore().apply{put("today",today);put("archive",archive)}
  val boundaryScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val cardsWhenProcessingEnds=CompletableDeferred<List<com.example.lianxu.data.model.DashboardCard>>()
  val modelJobs=listOfNotNull(today.viewModelScope.coroutineContext[Job],archive.viewModelScope.coroutineContext[Job])
  var mounted by mutableStateOf(true)
  try {
   rule.setContent { if(mounted)LianxuTheme { MainScreen(today,archive) } }
   rule.waitUntil(20000){today.cards.value.any { it.title=="卧推" }}
   val card=today.cards.value.single { it.title=="卧推" }
   rule.onNodeWithTag("card:${card.id}").performScrollTo()
   rule.runOnIdle { today.updateInput(card.id,"name","卧推");today.updateInput(card.id,"sets","3") }
   rule.waitUntil(5000){today.cards.value.any { it.inputValues["sets"]=="3" }}
   boundaryScope.launch {
    today.isProcessing.first { it }
    today.isProcessing.first { !it }
    cardsWhenProcessingEnds.complete(today.cards.value)
   }
   rule.onNodeWithContentDescription("统一发送").performClick()
   rule.waitUntil(20000){calls>0 && !today.isProcessing.value && today.cards.value.firstOrNull()?.title=="本轮建议"}
   rule.waitUntil(5000){cardsWhenProcessingEnds.isCompleted}
   val entering=runBlocking { cardsWhenProcessingEnds.await() }
   assertEquals("Reply must be ready before the receive animation starts","本轮建议",entering.first().title)
   assertFalse("Submitted inputs must not flash back during entry",entering.any(ActiveInputRepository::hasInput))
   // Do not scroll to the assertion: receiving a reply must itself make it visible.
   rule.onNodeWithText("本轮建议").assertIsDisplayed()
   rule.onNodeWithText("隔离交互样本：本轮填写已保存，未填写负重保持未知。可以继续补充下一轮。").assertIsDisplayed()
  }finally{
   // Cancellation is asynchronous: let composition and model children stop using Room before closing it.
   rule.runOnIdle { mounted=false;models.clear() }
   rule.waitForIdle()
   runBlocking { boundaryScope.coroutineContext[Job]?.cancelAndJoin();modelJobs.forEach { it.join() } }
   db.close()
  }
 }

 @Test fun manualEvidenceSession() {
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("evidenceSession")=="true")
  val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build();runBlocking { seed(db) }
  val today=TodayViewModel(context.applicationContext as Application,db,client);val archive=ArchiveViewModel(context.applicationContext as Application,db)
  val models=ViewModelStore().apply{put("today",today);put("archive",archive)}
  val stop=File(context.cacheDir,"stage5-evidence-stop");stop.delete()
  val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
   put(MediaStore.Images.Media.DISPLAY_NAME,"Lianxu-isolated-sample.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
  })!!
  context.contentResolver.openOutputStream(uri)!!.use { output->InstrumentationRegistry.getInstrumentation().context.assets.open("training-chest.jpg").use { it.copyTo(output) } }
  try {
   rule.setContent { LianxuTheme { MainScreen(today,archive) } }
   rule.waitUntil(20000){today.cards.value.isNotEmpty()}
   rule.waitForIdle()
   rule.mainClock.autoAdvance=false
   File(context.cacheDir,"stage5-evidence-ready").writeText("isolated")
   val deadline=System.currentTimeMillis()+600000
   while(!stop.exists() && System.currentTimeMillis()<deadline){rule.mainClock.advanceTimeBy(16);Thread.sleep(16)}
   File(context.filesDir,"stage5-evidence-session.json").writeText(JSONObject().put("isolated",true).put("requests",calls).put("trainingCount",runBlocking { db.recordDao().allTrainings().size }).put("turns",runBlocking { db.collaborationDao().watchBoards().first().sumOf { db.collaborationDao().turns(it.id).size } }).toString())
  }finally{context.contentResolver.delete(uri,null,null);stop.delete();File(context.cacheDir,"stage5-evidence-ready").delete();models.clear();db.close()}
 }
}
