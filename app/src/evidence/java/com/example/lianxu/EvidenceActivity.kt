package com.example.lianxu

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.example.lianxu.agent.AgentClient
import com.example.lianxu.archive.ArchiveRepository
import com.example.lianxu.data.api.*
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.repository.*
import com.example.lianxu.ui.screens.*
import com.example.lianxu.ui.theme.LianxuTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Separate APK/data sandbox, in-memory facts, offline transport, real Android frame clock. */
class EvidenceActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private lateinit var output: File
    private var calls = 0
    private lateinit var runId: String

    private fun event(name: String, data: JSONObject = JSONObject()) {
        data.put("event", name).put("runId", runId)
            .put("elapsedMs", SystemClock.elapsedRealtime()).put("wallMs", System.currentTimeMillis())
        File(output, "events.jsonl").appendText(data.toString() + "\n")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runId = intent.getStringExtra("runId") ?: error("runId required")
        require(runId.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        output = File(filesDir, "evidence/$runId")
        require(!output.exists()) { "Evidence run must be unique" }
        output.mkdirs()
        enableEdgeToEdge()
        db = Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java).build()
        lifecycleScope.launch {
            seed()
            val client = AgentClient { messages, _ ->
                calls++
                event("request", JSONObject().put("requests", calls))
                delay(1200)
                val content = messages.first { it.role == "user" }.content as List<*>
                val context = JSONObject((content.first() as Map<*,*>)["text"].toString())
                val proposal = JSONObject().put("contractVersion", "2")
                    .put("turnId", context.getString("turnId")).put("taskId", context.getString("taskId"))
                    .put("operations", JSONArray())
                    .put("summary", "隔离交互样本：两卡补充已收到。这是离线固定回复，不代表真实 AI 分析。")
                event("response")
                ResponseMessage("assistant", null, listOf(ToolCall("finish", "function",
                    ToolCallFunction("finish_proposal", proposal.toString()))))
            }
            val today = TodayViewModel(application, db, client)
            val archive = ArchiveViewModel(application, db)
            viewModelStore.put("evidence-today", today)
            viewModelStore.put("evidence-archive", archive)
            setContent { LianxuTheme { MainScreen(today, archive) } }
            launch {
                today.isProcessing.collect { event("processing", JSONObject().put("value", it)) }
            }
            launch {
                today.cards.filter { cards -> cards.any { it.title == "卧推" } }.first()
                File(output, "ready.json").writeText(JSONObject().put("runId", runId)
                    .put("isolated", true).put("transport", "offline-fixture")
                    .put("clock", "android-real-time").toString())
                event("ready")
            }
            launch {
                combine(today.cards, today.isProcessing) { cards, processing -> cards to processing }
                    .collect { (cards, processing) ->
                        val turns = db.collaborationDao().watchBoards().first()
                            .flatMap { db.collaborationDao().turns(it.id) }
                        val inputs = JSONArray()
                        for (turn in turns) {
                            val input = db.collaborationDao().input(turn.inputBundleId)
                            inputs.put(JSONObject().put("status", turn.status)
                                .put("annotations", input?.let { JSONArray(it.annotationsJson) }))
                        }
                        val state = JSONObject().put("runId", runId).put("requests", calls)
                            .put("processing", processing).put("turns", inputs)
                            .put("trainingCount", db.recordDao().allTrainings().size)
                            .put("cards", JSONArray(cards.map { JSONObject().put("title", it.title)
                                .put("annotation", it.annotationText) }))
                        val tmp = File(output, "state.tmp")
                        tmp.writeText(state.toString())
                        check(tmp.renameTo(File(output, "state.json")))
                    }
            }
        }
    }

    private suspend fun seed() {
        val date = LocalDate.now()
        AppRepository(db).saveArchive(175f, "1996-01-01", "male", 1.6f, 70.5f, "gain", date.minusDays(28))
        for (i in listOf(20,18,16,13,12,11,7,4,2,0)) db.measurementDao().insert(
            Measurement("sample-weight-$i", "weight", date.minusDays(i.toLong()).toString(), null,
                70f+(20-i)*.025f, "kg", "isolated-workflow", null, 1, i.toLong(), i.toLong()))
        for (i in listOf(18,15,12,9,6,3)) db.recordDao().insertTraining(
            TrainingSession("sample-training-$i", date.minusDays(i.toLong()).toString(), "strength", "卧推与划船", null,
                """[{"entryId":"bench","name":"卧推","equipment":"barbell","loadBasis":"total","summarySets":{"kind":"exact","value":3},"repsPerSet":{"kind":"exact","value":8},"loadKg":{"kind":"exact","value":60},"sets":[]}]""",
                "隔离样本：三组卧推，每组八次", "isolated-workflow", 1, i.toLong(), i.toLong()))
        ActiveInputRepository(db).arrange("关于上次训练，今天恢复得怎么样？")
        ArchiveRepository(this, db).also { it.methods.installBuiltins(); it.refresh(date) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::db.isInitialized) db.close()
    }
}
