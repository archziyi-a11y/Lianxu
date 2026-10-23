package com.example.lianxu

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.data.prefs.SettingsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CredentialStorageTest {
    @Test fun legacyPlaintextMigratesToEncryptedStorageAndCanBeCleared() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "key-test-${UUID.randomUUID()}.preferences_pb")
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val fake = "not-a-real-api-key"
        val legacy = stringPreferencesKey("deepseek_api_key")
        val encrypted = stringPreferencesKey("deepseek_api_key_encrypted_v1")
        try {
            data.edit { it[legacy] = fake }
            val settings = SettingsManager(context, data)
            assertEquals(fake, settings.apiKeyFlow.first())
            val preferences = data.data.first()
            assertNull(preferences[legacy])
            assertNotEquals(fake, preferences[encrypted])
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(fake))
            settings.saveApiKey("another-test-value")
            assertEquals("another-test-value", settings.apiKeyFlow.first())
            settings.saveApiKey("")
            assertNull(settings.apiKeyFlow.first())
            assertNull(data.data.first()[encrypted])
        } finally { job.cancelAndJoin(); file.delete() }
    }
}
