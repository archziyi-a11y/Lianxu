package com.example.lianxu.data.repository

import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.Board
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** The date comes from the user's zone; archived drafts and tasks keep their own board identity. */
class DayLifecycle(private val db: AppDatabase, private val clock: Clock = Clock.systemUTC()) {
    suspend fun ensureCurrent(): Board = db.withTransaction {
        val zone = db.profileDao().getProfile()?.preferredZoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()
        val date = LocalDate.now(clock.withZone(zone))
        val id = BoardStore(db).open(emptyList(), date, zone)
        val dao = db.collaborationDao()
        dao.allBoards().filter { it.id != id && it.status == "active" }.forEach { dao.putBoard(it.copy(status = "archived")) }
        val board = requireNotNull(dao.board(id))
        if (board.status != "active") dao.putBoard(board.copy(status = "active"))
        board.copy(status = "active")
    }
    fun watch(): Flow<Board> = combine(db.profileDao().getProfileFlow().map { it?.preferredZoneId }.distinctUntilChanged(), flow {
        while (currentCoroutineContext().isActive) {
            emit(Unit)
            val zone=db.profileDao().getProfile()?.preferredZoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()
            val now=clock.instant();val midnight=now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
            delay(java.time.Duration.between(now,midnight).toMillis().coerceIn(1,60_000))
        }
    }) { _, _ -> Unit }.map { ensureCurrent() }.distinctUntilChangedBy { it.id }
}
