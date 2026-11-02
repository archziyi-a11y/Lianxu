package com.example.lianxu.data.prefs

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsManager(context: Context, private val store: DataStore<Preferences> = context.dataStore) {
    private val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val legacy = stringPreferencesKey("deepseek_api_key")
    private val encrypted = stringPreferencesKey("deepseek_api_key_encrypted_v1")
    private val bridgeUrl = stringPreferencesKey("external_bridge_url")
    private val bridgeOwner = stringPreferencesKey("external_bridge_owner_encrypted_v1")
    private val bridgeAgent = stringPreferencesKey("external_bridge_agent_encrypted_v1")
    private val alias = "lianxu.api.credentials.v1"
    private fun key(): SecretKey = synchronized(SettingsManager::class.java) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val payload = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + payload, Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    val apiKeyFlow = flow {
        withContext(Dispatchers.IO) {
            store.edit { values ->
                values[legacy]?.let { plain ->
                    if (values[encrypted] == null && plain.isNotBlank()) values[encrypted] = encrypt(plain)
                    values.remove(legacy)
                }
            }
        }
        emitAll(store.data.map { values -> values[encrypted]?.let { decrypt(it) } })
    }
    suspend fun saveApiKey(apiKey: String) = withContext(Dispatchers.IO) {
        store.edit { values ->
            if (apiKey.isBlank()) values.remove(encrypted) else values[encrypted] = encrypt(apiKey.trim())
            values.remove(legacy)
        }
    }
    data class BridgeCredentials(val url: String, val ownerToken: String, val agentToken: String)
    val bridgeCredentials = store.data.map { values ->
        val url = values[bridgeUrl]
        val owner = values[bridgeOwner]
        val agent = values[bridgeAgent]
        if (url == null || owner == null || agent == null) null else BridgeCredentials(url, decrypt(owner), decrypt(agent))
    }
    suspend fun saveBridgeCredentials(url: String, ownerToken: String, agentToken: String) = withContext(Dispatchers.IO) {
        require(ownerToken.length >= 32 && agentToken.length >= 32 && ownerToken != agentToken)
        val parsed = url.trim().toHttpUrlOrNull() ?: error("服务地址无效")
        require(parsed.isHttps || debuggable && parsed.host in listOf("10.0.2.2", "127.0.0.1") && parsed.scheme == "http") { "请使用 HTTPS 服务地址" }
        store.edit { values ->
            values[bridgeUrl] = parsed.toString().trimEnd('/')
            values[bridgeOwner] = encrypt(ownerToken)
            values[bridgeAgent] = encrypt(agentToken)
        }
    }
}
