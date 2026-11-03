package com.example.lianxu

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.prefs.SettingsManager
import com.example.lianxu.data.repository.ExternalConnectionCoordinator
import com.example.lianxu.data.repository.ExternalRepository
import com.example.lianxu.data.repository.ExternalSync
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalConnectionCoordinatorTest {
    @Test fun concurrentRetriesKeepEpochAndRealHandoffNeedsFreshToken() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(context.cacheDir, "connection-test-${UUID.randomUUID()}.preferences_pb")
        val settings = SettingsManager(context, PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        try {
            val coordinator = ExternalConnectionCoordinator(db, settings)
            val results = coroutineScope {
                (1..8).map { async(Dispatchers.Default) { coordinator.configure("http://127.0.0.1:8765/", "o".repeat(40), "a".repeat(40), "Grok Bot") } }.awaitAll()
            }
            assertEquals(1, results.count { !it.resumeActive }); assertEquals(7, results.count { it.resumeActive })
            val repo = ExternalRepository(db); val first = repo.control()
            assertEquals(1L, first.epoch); repo.acceptHandoff("Grok Bot", first.epoch)
            repo.enqueue("input", "submission", "pending", 1)
            val retry = coordinator.configure("http://127.0.0.1:8765", "o".repeat(40), "a".repeat(40), "Grok Bot")
            assertTrue(retry.resumeActive)
            assertEquals(first.epoch, repo.control().epoch); assertTrue(repo.control().handoffAccepted)
            settings.saveBridgeCredentials("http://127.0.0.1:8765", "z".repeat(40), "a".repeat(40))
            try { ExternalSync(db, settings, context).sync(retry); fail("stale owner") } catch (_: IllegalStateException) { }
            settings.saveBridgeCredentials("http://127.0.0.1:8765", "o".repeat(40), "a".repeat(40))
            try { coordinator.configure("http://127.0.0.1:8765", "o".repeat(40), "a".repeat(40), "different"); fail("same token different agent") } catch (_: IllegalArgumentException) { }
            ExternalSync.withConnectionLock { repo.switchToBuiltin() }
            assertNull(coordinator.prepareRetry())
            try { ExternalSync(db, settings, context).sync(retry); fail("stale retry") } catch (_: IllegalStateException) { }
            try { coordinator.configure("http://127.0.0.1:8765", "o".repeat(40), "a".repeat(40), "Grok Bot"); fail("revoked token") } catch (_: IllegalArgumentException) { }
            assertFalse(coordinator.configure("http://127.0.0.1:8765", "o".repeat(40), "b".repeat(40), "new agent").resumeActive)
            assertEquals(3L, repo.control().epoch); assertFalse(repo.control().handoffAccepted)
            assertEquals("new agent", repo.pending().single().ownerAgentId)
            try { repo.complete("Grok Bot", first.epoch, "input", "late") {}; fail("revoked writer") } catch (_: IllegalStateException) { }
        } finally { scope.cancel(); db.close(); file.delete() }
    }
}
