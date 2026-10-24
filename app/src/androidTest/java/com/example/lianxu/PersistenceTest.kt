package com.example.lianxu

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.model.*
import com.example.lianxu.data.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun open(name: String) = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
    private fun seed() = listOf(DashboardCard(id = "training", title = "训练", value = "待补充", span = CardSpan.LARGE))
    private suspend fun annotate(store: BoardStore, id: String, text: String) {
        store.edit(id) { cards -> cards.map { it.copy(annotationText = text, isAnnotationMode = true) } }
    }

    @Test fun versionOneMigrationKeepsExistingMeasurement() = runBlocking {
        val name = "migration-${UUID.randomUUID()}"
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.example.lianxu.data.db.AppDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            val tables = schema.getJSONArray("entities")
            for (i in 0 until tables.length()) {
                val table = tables.getJSONObject(i)
                sql.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
            }
            sql.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            sql.execSQL("INSERT INTO room_master_table VALUES (42,?)", arrayOf(schema.getString("identityHash")))
            sql.execSQL("INSERT INTO measurements VALUES ('old-weight','weight','2026-10-24',1,70,'kg','user',NULL,1,1,1,NULL)")
            sql.version = 1
        }
        val db = open(name)
        try {
            assertEquals(70f, db.measurementDao().getLatestMeasurement("weight")!!.value)
            assertEquals(5, db.openHelper.writableDatabase.version)
            assertNotNull(BoardStore(db).open(seed()))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun profileGoalAndUnknownWeightRemainConsistent() = runBlocking {
        val name = "archive-${UUID.randomUUID()}"; val db = open(name)
        try {
            val repo = AppRepository(db)
            repo.saveArchive(175f, "1996-01-01", null, 1.6f, null, "gain")
            assertNull(repo.getLatestWeight())
            assertNull(repo.getProfile()!!.formulaSex)
            repo.saveArchive(175f, "1996-01-01", "male", 1.6f, 70f, "cut")
            repo.saveArchive(176f, "1996-01-01", "male", 1.6f, null, "maintain")
            val data = repo.archiveFlow.first()
            assertEquals(176f, data.profile!!.heightCm)
            assertEquals(70f, data.weight!!.value)
            val phases = db.goalPhaseDao().getGoalPhasesFlow().first()
            assertEquals(1, phases.count { it.endsOn == null })
            assertEquals("maintain", phases.single { it.endsOn == null }.kind)
            assertTrue(phases.filter { it.endsOn != null }.all { it.endsOn == LocalDate.now().toString() })
            try { repo.saveArchive(-1f, null, null, 1.6f, 80f, "cut"); fail("Must reject invalid input") }
            catch (_: IllegalArgumentException) { }
            assertEquals(70f, repo.getLatestWeight()!!.value)
            assertEquals(176f, repo.getProfile()!!.heightCm)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun roughTrainingAndIntakeSurviveReopenWithoutInventedDetails() = runBlocking {
        val name = "records-${UUID.randomUUID()}"; var db = open(name)
        try {
            val repo = AppRepository(db)
            repo.recordTraining(TrainingSession("rough-training", "{}", "resistance", "", null, "[]",
                "今天练胸四组，重量没记", "user", 1, 1, 1))
            repo.recordIntake(IntakeEntry("rough-intake", "{}", "lunch", "item:demo", "description", "[]",
                null, "鸡肉饭，分量不清楚", "partial", "user", 1, 1, 1))
            db.close(); db = open(name)
            val data = AppRepository(db)
            assertEquals("[]", data.trainings.first().single().exercises)
            assertNull(data.trainings.first().single().durationS)
            assertNull(data.intakes.first().single().manualNutrients)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun attachmentAndDraftSurviveProcessEquivalentReopen() = runBlocking {
        val name = "attachment-${UUID.randomUUID()}"; var db = open(name)
        val source = File(context.cacheDir, "fixture-${UUID.randomUUID()}.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        var attachment: AttachmentEntity? = null
        try {
            attachment = AttachmentStore(context, db).import(Uri.fromFile(source))
            val store = BoardStore(db); val id = store.open(seed())
            store.edit(id) { cards -> cards.map { it.copy(annotationText = "实际三组", attachmentId = attachment!!.id, isAnnotationMode = true) } }
            source.delete()
            db.close(); db = open(name)
            val resumed = BoardStore(db).watchCards(id).first().single()
            assertEquals("实际三组", resumed.annotationText)
            assertEquals(attachment.id, resumed.attachmentId)
            val saved = db.collaborationDao().attachment(attachment.id)!!
            assertTrue(File(context.filesDir, saved.localPath).readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
            assertEquals(64, saved.sha256.length)
        } finally {
            db.close(); context.deleteDatabase(name); source.delete()
            attachment?.let { File(context.filesDir, it.localPath).delete() }
        }
    }

    @Test fun failedRoundKeepsInputAndRetryUsesSameTurn() = runBlocking {
        val name = "retry-${UUID.randomUUID()}"; val db = open(name)
        try {
            val store = BoardStore(db); val id = store.open(seed())
            annotate(store, id, "实际三组")
            val first = store.prepare(id)
            store.fail(first, "network unavailable")
            assertEquals("实际三组", store.watchCards(id).first().single().annotationText)
            val retry = store.prepare(id)
            assertEquals(first.turn.id, retry.turn.id)
            val input = db.collaborationDao().input(retry.turn.inputBundleId)!!
            assertTrue(input.annotationsJson.contains("待补充"))
            assertTrue(input.annotationsJson.contains("cardRevision"))
            store.complete(retry, retry.cards)
            store.complete(retry, retry.cards)
            assertEquals(1, db.collaborationDao().turns(id).size)
            assertEquals(retry.turn.id, db.collaborationDao().board(id)!!.latestCompletedTurnId)
            assertNull(store.watchCards(id).first().single().annotationText)
            assertNotNull(db.collaborationDao().input(retry.turn.inputBundleId))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun invalidCommitRollsBackAndNewDraftIsNotCleared() = runBlocking {
        val name = "atomic-${UUID.randomUUID()}"; val db = open(name)
        try {
            val store = BoardStore(db); val id = store.open(seed())
            annotate(store, id, "第一份补充")
            val submission = store.prepare(id)
            try { store.complete(submission, submission.cards + submission.cards); fail("Must reject duplicate card IDs") }
            catch (_: IllegalArgumentException) { }
            assertNull(db.collaborationDao().board(id)!!.latestCompletedTurnId)
            assertEquals(1, db.collaborationDao().board(id)!!.revision)
            // Force a failure after card replacement has started, checking a real rollback.
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_snapshot BEFORE INSERT ON board_snapshots BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
            try {
                store.complete(submission, submission.cards.map { it.copy(value = "不得残留") })
                fail("Injected storage failure must propagate")
            } catch (_: android.database.sqlite.SQLiteException) { }
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_snapshot")
            assertEquals("待补充", JSONObject(db.collaborationDao().cards(id).single().data).getString("value"))
            assertNull(db.collaborationDao().board(id)!!.latestCompletedTurnId)
            annotate(store, id, "等待期间的新补充")
            store.complete(submission, submission.cards.map { it.copy(value = "处理结果", revision = 2) })
            val next = store.watchCards(id).first().single()
            assertEquals("等待期间的新补充", next.annotationText)
            assertEquals("处理结果", next.value)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun interruptedTaskRetainsFrozenInputAndDraftOnReopen() = runBlocking {
        val name = "interrupted-${UUID.randomUUID()}"; var db = open(name)
        try {
            val store = BoardStore(db); val id = store.open(seed())
            annotate(store, id, "不要丢掉")
            val submission = store.prepare(id)
            db.close(); db = open(name)
            val resumed = BoardStore(db); resumed.recoverInterrupted()
            assertEquals("failed", db.collaborationDao().task(submission.task.id)!!.status)
            assertEquals("不要丢掉", resumed.watchCards(id).first().single().annotationText)
            assertNotNull(db.collaborationDao().input(submission.turn.inputBundleId))
            assertEquals(submission.turn.id, resumed.prepare(id).turn.id)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun boardsOnDifferentDatesDoNotOverwriteEachOthersCards() = runBlocking {
        val name = "dates-${UUID.randomUUID()}"; val db = open(name)
        try {
            val store = BoardStore(db)
            val first = store.open(seed(), LocalDate.of(2026, 10, 24), ZoneId.of("Asia/Shanghai"))
            val next = store.open(seed(), LocalDate.of(2026, 10, 25), ZoneId.of("Asia/Shanghai"))
            assertNotEquals(first, next)
            assertEquals(1, db.collaborationDao().cards(first).size)
            assertEquals(1, db.collaborationDao().cards(next).size)
            assertNotEquals(store.watchCards(first).first().single().id, store.watchCards(next).first().single().id)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
