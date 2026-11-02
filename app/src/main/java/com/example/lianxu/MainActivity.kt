package com.example.lianxu

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.lianxu.ui.screens.MainScreen
import com.example.lianxu.ui.theme.LianxuTheme

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        com.example.lianxu.archive.ArchiveRuntime.resume(this)
        lifecycleScope.launch {
            runCatching { com.example.lianxu.timer.RestTimerRepository(this@MainActivity).resumeIfNeeded() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val db = com.example.lianxu.data.db.AppDatabase.getDatabase(this@MainActivity)
                val settings = com.example.lianxu.data.prefs.SettingsManager(this@MainActivity)
                com.example.lianxu.data.repository.ForegroundSyncLoop {
                    if (com.example.lianxu.data.repository.ExternalRepository(db).control().mode == "external")
                        com.example.lianxu.data.repository.ExternalSync(db, settings, this@MainActivity).sync()
                }.run()
            }
        }
        enableEdgeToEdge()
        setContent {
            LianxuTheme {
                MainScreen()
            }
        }
    }
}
