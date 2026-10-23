package com.example.lianxu

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.repository.*
import com.example.lianxu.ui.screens.DashboardCardItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NutritionCardUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun eatingFromRealCardPersistsAndUpdatesTheCard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "card-ui-fixture-${UUID.randomUUID()}"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val date = LocalDate.of(2026, 10, 24)
            val service = runBlocking { NutritionBoardRepository.create(context, db) }
            val boardId = runBlocking {
                AppRepository(db).saveArchive(175f, "1996-01-01", "male", 1.6f, 70f, "gain", date)
                BoardStore(db).open(emptyList(), date, ZoneId.of("Asia/Shanghai")).also { service.refresh(it) }
            }
            val initial = runBlocking { BoardStore(db).watchCards(boardId).first().first { it.blocks.any { b -> b.actions.any { it.kind == "mark_eaten" } } } }
            var card by mutableStateOf(initial)
            var content by mutableStateOf(runBlocking { ComponentResolver(db).resolve(card) })
            val done = AtomicBoolean(false)
            val failure = AtomicReference<Throwable?>(null)
            rule.setContent {
                MaterialTheme {
                    DashboardCardItem(card, {}, {}, {}, resolved = content, onAction = { action, value ->
                        scope.launch {
                            try {
                                service.act(action, value); service.refresh(boardId)
                                val next = BoardStore(db).watchCards(boardId).first().first { it.id == initial.id }
                                content = ComponentResolver(db).resolve(next); card = next
                            } catch (error: Throwable) { failure.set(error) }
                            finally { done.set(true) }
                        }
                    })
                }
            }
            rule.onNodeWithText("记为已吃").assertExists().performClick()
            rule.waitUntil(15000) { done.get() }
            failure.get()?.let { throw AssertionError("Card action failed", it) }
            rule.waitForIdle()
            rule.onNodeWithText("记为已吃").assertDoesNotExist()
            assertTrue(card.title.contains("已记录"))
            assertEquals(1, runBlocking { db.recordDao().allIntakes().size })
        } finally { scope.cancel(); db.close(); context.deleteDatabase(name) }
    }
}
