package com.example.lianxu

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.agent.*
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.AttachmentEntity
import com.example.lianxu.data.prefs.SettingsManager
import com.example.lianxu.data.repository.*
import com.example.lianxu.engine.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/** Explicitly opt-in via a private cache file; no key in test arguments, APK, output or repository. */
@RunWith(AndroidJUnit4::class)
class LiveAgentTest {
    @Test fun actualFlashImageAndMultiCardRoundTrip() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        val stage = InstrumentationRegistry.getArguments().getString("validationStage") ?: "3"
        val keyFile = File(context.cacheDir,"stage$stage-live-key")
        assumeTrue("Live API test requires private opt-in key file", keyFile.isFile)
        val key = keyFile.readText().trim(); keyFile.delete(); require(key.isNotEmpty())
        val db = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val image = File(context.filesDir,"attachments/stage3-live-photo").apply { parentFile!!.mkdirs() }
        var taskId: String? = null
        try {
            val date=LocalDate.of(2026,10,24)
            AppRepository(db).saveArchive(175f,"1996-01-01","male",1.6f,70f,"gain",date)
            val store=BoardStore(db); val id=store.open(emptyList(),date,ZoneId.of("Asia/Shanghai"))
            val repo=NutritionBoardRepository.create(context,db); repo.refresh(id)
            val originalMeals = DomainJson.decode<MealsDocument>(requireNotNull(db.collaborationDao().planForDate(requireNotNull(db.collaborationDao().board(id)).dateContext)).meals).meals
            val data=inst.context.assets.open("training-chest.jpg").use { it.readBytes() }; image.writeBytes(data)
            val sha=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
            db.collaborationDao().putAttachment(AttachmentEntity("live-photo","attachments/stage3-live-photo","image/jpeg",sha,data.size.toLong(),System.currentTimeMillis()))
            store.edit(id) { cards -> cards.map { when { it.id.endsWith(":training-input") -> it.copy(isAnnotationMode=true,attachmentId="live-photo",annotationText="今天按这张图完成胸部训练，上斜哑铃卧推实际只有3组，其余组数和次数范围按图，重量没记。请直接保存这些实际训练信息。")
                it.id.endsWith(":intake-input") -> it.copy(isAnnotationMode=true,annotationText="午餐吃了200g熟米饭，只记录了这部分，不表示全天吃够。根据这些训练和摄入更新今天剩余饮食参考，只调整还没过去的晚餐，不改早餐或已吃的午餐。做一张解释卡和营养指标组合。")
                else -> it } } }
            val submission=store.prepare(id); taskId = submission.task.id
            val runner=AgentRunner(context,db,repo.catalog,DeepSeekAgentClient(key))
            val summary=runner.run(submission)
            val trainings=db.recordDao().allTrainings(); val intakes=db.recordDao().allIntakes()
            assertTrue("training must be saved from the photo",trainings.isNotEmpty())
            assertTrue(trainings.any { it.exercises.contains("卧推") })
            assertTrue(trainings.any { it.exercises.contains("上斜") })
            assertTrue(trainings.flatMap { JSONArray(it.exercises).let { a -> (0 until a.length()).map { i -> DomainJson.decode<RecordedExercise>(a.getJSONObject(i).toString()) } } }.all { it.loadKg.value==null })
            assertTrue(intakes.isNotEmpty()); assertTrue(repo.intakeTotals(intakes).knownSubtotal.energyKcal!!>0)
            assertEquals("completed",db.collaborationDao().turn(submission.turn.id)!!.status)
            val cards=store.watchCards(id).first()
            assertTrue(cards.any { it.blocks.any { b -> b.content.contentRef?.entityRef?.type=="analysis" } })
            val finalMeals=DomainJson.decode<MealsDocument>(requireNotNull(db.collaborationDao().planForDate(requireNotNull(db.collaborationDao().board(id)).dateContext)).meals).meals
            val breakfast=originalMeals.first { it.slotKey=="breakfast" }
            assertEquals("Past breakfast must remain unchanged",breakfast,finalMeals.first { it.mealId==breakfast.mealId })
            assertEquals(1,trainings.size); assertEquals(1,intakes.size)
            val report=JSONObject().put("status","passed").put("pastBreakfastUnchanged",true).put("model","deepseek-flash").put("realApi",true).put("requests",runner.requestCount).put("tools",runner.toolCount)
                .put("imageSha256",sha).put("summary",summary).put("trainingCount",trainings.size).put("intakeCount",intakes.size)
                .put("trainings",JSONArray(trainings.map { JSONObject(DomainJson.encode(it)) })).put("intakes",JSONArray(intakes.map { JSONObject(DomainJson.encode(it)) }))
                .put("cards",JSONArray(cards.map { JSONObject(DomainJson.encode(it)) }))
                .put("trace",db.collaborationDao().agentCache(submission.task.id)?.let { JSONArray(it.entriesJson) })
            File(context.filesDir,"stage$stage-live-result.json").writeText(report.toString(2))
            // Configure the actual app through the same encrypted settings path, never a build constant.
            SettingsManager(context).saveApiKey(key)
            Unit
        } catch (error: Throwable) {
            val report=JSONObject().put("status","failed").put("error",error.message)
                .put("trace",taskId?.let { db.collaborationDao().agentCache(it)?.entriesJson }?.let { JSONArray(it) })
            File(context.filesDir,"stage$stage-live-result.json").writeText(report.toString(2))
            throw error
        } finally { image.delete(); db.close() }
    }
}
