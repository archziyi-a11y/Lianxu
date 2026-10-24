package com.example.lianxu

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
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
        enableEdgeToEdge()
        setContent {
            LianxuTheme {
                MainScreen()
            }
        }
    }
}
