package com.example.lianxu.data.repository

import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.prefs.SettingsManager
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

/** A retry is valid only while its complete connection identity and epoch remain current. */
class ConnectionAttempt(val epoch: Long, val agentName: String, val url: String,
    val agentFingerprint: String, val ownerFingerprint: String, val resumeActive: Boolean) {
    companion object {
        fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Serializes connection changes against workers and identifies a deliberate same-writer retry. */
class ExternalConnectionCoordinator(private val db: AppDatabase, private val settings: SettingsManager) {
    /** Explicit user sync may retry only the current saved external connection. */
    suspend fun prepareRetry(): ConnectionAttempt? = ExternalSync.withConnectionLock {
        val current = ExternalRepository(db).control()
        if (current.mode != "external") return@withConnectionLock null
        val credentials = requireNotNull(settings.bridgeCredentials.first()) { "请先设置外部连接" }
        val name = requireNotNull(current.agentId) { "当前智能体信息不完整" }
        ConnectionAttempt(current.epoch, name, credentials.url, ConnectionAttempt.fingerprint(credentials.agentToken),
            ConnectionAttempt.fingerprint(credentials.ownerToken), true)
    }

    suspend fun configure(url: String, ownerToken: String, agentToken: String, agentName: String): ConnectionAttempt =
        ExternalSync.withConnectionLock {
            val name = agentName.trim()
            require(name.isNotBlank()) { "请填写智能体名称" }
            val normalized = settings.normalizeBridgeUrl(url)
            val previous = settings.bridgeCredentials.first()
            val repo = ExternalRepository(db)
            val current = repo.control()
            if (current.mode == "external" && previous != null) {
                require(previous.url == normalized) { "请先切回内置助手并同步撤销，再更换服务地址" }
            }
            val retry = current.mode == "external" && previous != null && previous.url == normalized &&
                current.agentId == name && previous.agentToken == agentToken
            if (!retry && previous != null) {
                require(previous.agentToken != agentToken) { "重新交接需要新的智能体连接密钥" }
            }
            settings.saveBridgeCredentials(normalized, ownerToken, agentToken)
            val selected = if (retry) current else repo.beginHandoff(name)
            ConnectionAttempt(selected.epoch, name, normalized, ConnectionAttempt.fingerprint(agentToken),
                ConnectionAttempt.fingerprint(ownerToken), retry)
        }
}
