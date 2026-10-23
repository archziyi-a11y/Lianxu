package com.example.lianxu.data.repository

import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.model.DashboardCard
import com.example.lianxu.data.model.AnnotationAnchor
import com.example.lianxu.engine.DomainJson
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class Submission(val turn: Turn, val task: TaskEntity, val cards: List<DashboardCard>, val boardRevision: Int)

/** Room owns the board and draft. StateFlows in the UI are only projections of these records. */
class BoardStore(private val db: AppDatabase) {
    private val dao = db.collaborationDao()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val cardAdapter = moshi.adapter(DashboardCard::class.java)
    private val cardsAdapter = moshi.adapter<List<DashboardCard>>(
        Types.newParameterizedType(List::class.java, DashboardCard::class.java))

    fun encode(cards: List<DashboardCard>): String = cardsAdapter.toJson(cards)
    fun decode(json: String): List<DashboardCard> = requireNotNull(cardsAdapter.fromJson(json))
    fun watchCards(boardId: String) = dao.watchDraft(boardId).map { it?.let { decode(it.cardsJson) } ?: emptyList() }
    fun watchDraft(boardId: String) = dao.watchDraft(boardId)

    suspend fun open(initial: List<DashboardCard>, date: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String = db.withTransaction {
        val context = JSONObject().put("date", date.toString()).put("zoneId", zone.id).put("basis", "operation_context").toString()
        val id = UUID.nameUUIDFromBytes("$date|${zone.id}".toByteArray()).toString()
        val now = System.currentTimeMillis()
        if (dao.board(id) == null) {
            dao.putBoard(Board(id, 1, context, null, "active", now, now))
            dao.putCards(initial.map { it.copy(id = "$id:${it.id}") }.map { Card(it.id, id, cardAdapter.toJson(it)) })
        }
        if (dao.draft(id) == null) {
            val cards = dao.cards(id).map { requireNotNull(cardAdapter.fromJson(it.data)) }
            dao.putDraft(DraftEntity(id, id, 1, context, encode(cards), now))
        }
        id
    }

    suspend fun edit(boardId: String, transform: (List<DashboardCard>) -> List<DashboardCard>) = db.withTransaction {
        val draft = requireNotNull(dao.draft(boardId))
        val before = decode(draft.cardsJson).associateBy { it.id }
        val board = requireNotNull(dao.board(boardId))
        val next = transform(before.values.toList()).map { card ->
            val hasInput = !card.annotationText.isNullOrBlank() || card.attachmentId != null
            if (!card.isAnnotationMode && !hasInput) card.copy(annotationAnchor = null)
            else if (card.annotationAnchor == null) {
                val original = before.getValue(card.id)
                card.copy(annotationAnchor = AnnotationAnchor(board.revision, original.revision,
                    ComponentResolver(db).readable(original), JSONArray(original.blocks.map { JSONObject(DomainJson.encode(it)) }).toString(), original.title))
            } else card
        }
        dao.putDraft(draft.copy(revision = draft.revision + 1,
            cardsJson = encode(next), updatedAt = System.currentTimeMillis()))
    }

    suspend fun prepare(boardId: String): Submission = db.withTransaction {
        val draft = requireNotNull(dao.draft(boardId))
        val board = requireNotNull(dao.board(boardId))
        val cards = decode(draft.cardsJson)
        val annotated = cards.filter { !it.annotationText.isNullOrBlank() || it.attachmentId != null }
        require(annotated.isNotEmpty()) { "请先补充文字或图片" }
        val existing = dao.taskForDraft(boardId, draft.revision)
        if (existing != null) {
            val turn = requireNotNull(dao.turn(existing.turnId))
            require(existing.status !in listOf("running", "applying", "queued")) { "本轮正在处理" }
            require(existing.status != "completed") { "本轮已完成" }
            val retry = existing.copy(status = "running", lastError = null, updatedAt = System.currentTimeMillis())
            dao.putTask(retry)
            dao.putTurn(turn.copy(status = "running"))
            return@withTransaction Submission(turn.copy(status = "running"), retry, cards, board.revision)
        }
        val now = System.currentTimeMillis()
        val submissionId = UUID.randomUUID().toString()
        val turnId = UUID.randomUUID().toString()
        val inputId = UUID.randomUUID().toString()
        val taskId = UUID.randomUUID().toString()
        val snapshotId = UUID.randomUUID().toString()
        val annotations = JSONArray()
        annotated.forEach { card ->
            val parts = JSONArray()
            card.annotationText?.takeIf { it.isNotBlank() }?.let { parts.put(JSONObject().put("type", "text").put("text", it)) }
            card.attachmentId?.let {
                requireNotNull(dao.attachment(it)) { "图片来源已丢失" }
                parts.put(JSONObject().put("type", "image").put("attachmentId", it))
            }
            val anchor = card.annotationAnchor
            annotations.put(JSONObject().put("annotationId", UUID.randomUUID().toString())
                .put("target", JSONObject().put("boardId", boardId).put("boardRevision", anchor?.boardRevision ?: board.revision)
                    .put("cardId", card.id).put("cardRevision", anchor?.cardRevision ?: card.revision))
                .put("targetSnapshot", JSONObject().put("title", anchor?.title?.takeIf { it.isNotBlank() } ?: card.title).put("readableContent", anchor?.readableContent ?: ComponentResolver(db).readable(card))
                    .put("components", JSONArray(anchor?.componentsJson ?: "[]")))
                .put("inputParts", parts))
        }
        dao.putSnapshot(BoardSnapshotEntity(snapshotId, boardId, board.revision, null,
            encode(dao.cards(boardId).map { requireNotNull(cardAdapter.fromJson(it.data)) }), now))
        val turn = Turn(turnId, submissionId, "daily", boardId, draft.dateContext,
            (dao.turns(boardId).maxOfOrNull { it.sequence } ?: 0) + 1, inputId, "[]", "running", taskId,
            "[]", "[]", snapshotId, null, now, null)
        val task = TaskEntity(taskId, turnId, submissionId, boardId, draft.revision, "running", null, now, now)
        dao.putInput(InputBundleEntity(inputId, turnId, submissionId, draft.dateContext,
            annotated.mapNotNull { it.annotationText }.joinToString("\n"), annotations.toString(), now))
        dao.putTurn(turn)
        dao.putTask(task)
        Submission(turn, task, cards, board.revision)
    }

    /** The legacy adapter only supplies render cards. Stage three adds validated business operations. */
    suspend fun complete(submission: Submission, result: List<DashboardCard>) = db.withTransaction {
        val task = requireNotNull(dao.task(submission.task.id))
        if (dao.receipt(task.submissionId, "apply-board") != null) return@withTransaction
        check(task.status == "running") { "任务状态已改变" }
        val board = requireNotNull(dao.board(task.boardId))
        check(board.revision == submission.boardRevision) { "看板已更新，请重新提交" }
        require(result.map { it.id }.distinct().size == result.size) { "卡片标识重复" }
        val clean = result.map { it.copy(isAnnotationMode = false, annotationText = null, annotationImageUri = null, attachmentId = null, annotationAnchor = null) }
        val now = System.currentTimeMillis()
        val snapshotId = UUID.randomUUID().toString()
        dao.removeCards(board.id)
        dao.putCards(clean.map { Card(it.id, board.id, cardAdapter.toJson(it)) })
        dao.putSnapshot(BoardSnapshotEntity(snapshotId, board.id, board.revision + 1, task.turnId, encode(clean), now))
        dao.putBoard(board.copy(revision = board.revision + 1, latestCompletedTurnId = task.turnId, updatedAt = now))
        val draft = requireNotNull(dao.draft(board.id))
        // Only the frozen revision is consumed. Edits made during processing survive.
        val next = if (draft.revision == task.draftRevision) clean else {
            val pending = decode(draft.cardsJson).associateBy { it.id }
            clean.map { card -> pending[card.id]?.let { old -> card.copy(isAnnotationMode = old.isAnnotationMode,
                annotationText = old.annotationText, annotationImageUri = old.annotationImageUri, attachmentId = old.attachmentId, annotationAnchor = old.annotationAnchor) } ?: card } +
                pending.values.filter { old -> clean.none { it.id == old.id } }
        }
        dao.putDraft(draft.copy(revision = draft.revision + 1, cardsJson = encode(next), updatedAt = now))
        val turn = requireNotNull(dao.turn(task.turnId))
        dao.putTurn(turn.copy(status = "completed", afterBoardSnapshotId = snapshotId, completedAt = now))
        dao.putTask(task.copy(status = "completed", lastError = null, updatedAt = now))
        dao.putReceipt(MutationReceiptEntity(UUID.randomUUID().toString(), task.submissionId, "apply-board", "[]",
            turn.beforeBoardSnapshotId, snapshotId, now))
    }

    suspend fun fail(submission: Submission, message: String) = db.withTransaction {
        val task = requireNotNull(dao.task(submission.task.id))
        if (task.status == "completed") return@withTransaction
        dao.putTask(task.copy(status = "failed", lastError = message, updatedAt = System.currentTimeMillis()))
        dao.turn(task.turnId)?.let { dao.putTurn(it.copy(status = "failed")) }
    }

    suspend fun recoverInterrupted() = db.withTransaction {
        dao.unfinishedTasks().forEach { task ->
            dao.putTask(task.copy(status = "failed", lastError = "处理被中断，输入已保留，可重新发送", updatedAt = System.currentTimeMillis()))
            dao.turn(task.turnId)?.let { dao.putTurn(it.copy(status = "failed")) }
        }
    }
}
