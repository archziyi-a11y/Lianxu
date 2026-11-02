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
            if (!intent.getBooleanExtra("sparse", false)) seed()
            if (intent.getBooleanExtra("external_layout", false)) seedExternal()
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
            setContent { LianxuTheme(darkTheme=intent.getBooleanExtra("dark", false)) { MainScreen(today, archive, initialPage=if(intent.getBooleanExtra("archive", false))1 else 0) } }
            launch {
                today.isProcessing.collect { event("processing", JSONObject().put("value", it)) }
            }
            launch {
                today.cards.filter { cards -> cards.isNotEmpty() }.first()
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

    /** Synthetic data only; exercises the production component and archive validators. */
    private suspend fun seedExternal() {
        val board=DayLifecycle(db).ensureCurrent()
        NutritionBoardRepository.create(this,db).refresh(board.id)
        val archive=ArchiveRepository(this,db);archive.refresh()
        val topic=db.archiveDao().topics().first { it.methodId=="builtin.weight-series" && it.scopeKey=="accumulated" && it.state=="current" }
        val external=ExternalRepository(db);val control=external.beginHandoff("隔离样本智能体");external.acceptHandoff("隔离样本智能体",control.epoch)
        val now=System.currentTimeMillis()
        external.publishPlan("隔离样本智能体",control.epoch,ExternalPlanVersionEntity("sample-plan","隔离样本：按近期反馈安排训练","示例理由",now,now,"隔离样本智能体"),true,now)
        val cardId="${board.id}:external:main"
        val actions=JSONArray().put(JSONObject().put("kind","record_set").put("label","记下这一组").put("planId","sample-plan"))
            .put(JSONObject().put("kind","start_timer").put("label","休息 90 秒").put("seconds",90))
        val components=JSONArray().put(JSONObject().put("type","text").put("text","隔离样本：先完成当前动作，再记录实际感受。"))
            .put(JSONObject().put("type","input").put("kind","training").put("fields",JSONArray().put("name").put("repsMin").put("loadKg")).put("suggestedName","卧推").put("actions",actions))
            .put(JSONObject().put("type","metric").put("calculationId",topic.calculationId).put("path","latest").put("label","最近体重").put("unit","kg"))
        val card=JSONObject().put("id",cardId).put("title","当前动作").put("span",6).put("group","训练主项").put("emphasis","high").put("collapsed",false).put("components",components)
        external.applyTextProposal("隔离样本智能体",control.epoch,"sample-layout",JSONObject().put("boardId",board.id).put("boardRevision",db.collaborationDao().board(board.id)!!.revision)
            .put("cards",JSONArray().put(card)).put("order",JSONArray().put(cardId)).put("inputIds",JSONArray()))
        val blocks=JSONArray().put(JSONObject().put("topicKey",topic.id).put("type","metric").put("path","latest").put("label","最近体重"))
            .put(JSONObject().put("topicKey",topic.id).put("type","text").put("text","隔离样本：基于已记录体重展示变化。"))
        external.applyArchiveProposal(this,"隔离样本智能体",control.epoch,"sample-archive",JSONObject().put("type","archive").put("inputIds",JSONArray())
            .put("cards",JSONArray().put(JSONObject().put("title","身体变化").put("span",6).put("group","长期趋势").put("emphasis","high").put("blocks",blocks))))
        external.submitRequirement(ExternalRequirementEntity("sample-need","sample-group-timer","隔离样本：分组计时","现有控件未提供分组提示","先使用普通计时","完成每组后显示休息倒计时",1,now))
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::db.isInitialized) db.close()
    }
}
