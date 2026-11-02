package com.example.lianxu

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.repository.ExternalRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalRepositoryTest {
    @Test fun handoffRevokesOldWriterAndMovesOnlyPending() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val repo = ExternalRepository(db)
            val a = repo.beginHandoff("a")
            repo.acceptHandoff("a", a.epoch)
            repo.enqueue("i1", "s1", "raw", 1)
            assertTrue(repo.complete("a", a.epoch, "i1", "record1") {})
            assertFalse(repo.complete("a", a.epoch, "i1", "record1") { fail("replayed") })
            repo.enqueue("i2", "s2", "pending", 2)
            val b = repo.beginHandoff("b")
            try { repo.complete("a", a.epoch, "i2", "bad") {}; fail("revoked") } catch (_: IllegalStateException) {}
            assertEquals("b", repo.pending().single().ownerAgentId)
            try { repo.complete("b", b.epoch, "i2", "bad") {}; fail("handoff not accepted") } catch (_: IllegalStateException) {}
            repo.acceptHandoff("b", b.epoch)
            assertTrue(repo.complete("b", b.epoch, "i2", "record2") {})
            assertEquals("record1", db.externalDao().input("i1")!!.resultRefs)
        } finally { db.close() }
    }
    @Test fun rollbackRetryPlansAndRequirementMerge() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val repo = ExternalRepository(db); val c = repo.beginHandoff("a"); repo.acceptHandoff("a", c.epoch)
            repo.enqueue("i", "submission", "original", 1)
            try { repo.enqueue("other", "submission", "changed", 1); fail("conflict") } catch (_: IllegalArgumentException) {}
            try { repo.complete("a", c.epoch, "i", "x") { error("injected") }; fail("failure") } catch (_: IllegalStateException) {}
            assertEquals("pending", repo.pending().single().status)
            val p = ExternalPlanVersionEntity("p", "plan", "reason", 10, 1, "a")
            try { repo.publishPlan("a", c.epoch, p, true, 9); fail("future") } catch (_: IllegalArgumentException) {}
            assertTrue(repo.plans().isEmpty())
            repo.publishPlan("a", c.epoch, p, true, 10)
            assertEquals("p", repo.currentPlan()!!.id)
            val requirement = ExternalRequirementEntity("r", "same", "scenario", "limitation", "workaround", "interaction", 1, 1)
            repo.submitRequirement(requirement); repo.submitRequirement(requirement.copy(id = "r2", updatedAt = 2))
            assertEquals(2, repo.requirements().single().occurrences)
            repo.switchToBuiltin()
            try { repo.publishPlan("a", c.epoch, p, true, 10); fail("revoked") } catch (_: IllegalStateException) {}
        } finally { db.close() }
    }
}
