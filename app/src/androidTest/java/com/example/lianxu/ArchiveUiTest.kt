package com.example.lianxu

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.ui.screens.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveUiTest {
    @get:Rule val rule=createComposeRule()
    @Test fun offlineEntryAndCorrectionAreAvailableThroughArchive() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val vm=ArchiveViewModel(context.applicationContext as Application,db)
        val store=ViewModelStore().apply { put("archive",vm) }
        try {
            rule.setContent { MaterialTheme { ArchiveScreen(viewModel=vm) } }
            rule.onNodeWithText("补记训练、饮食或测量").performClick()
            rule.onNodeWithText("记录说明").performTextInput("今天练胸四组，状态一般")
            rule.onNodeWithText("保存").performClick()
            rule.waitUntil(20000) { runBlocking { db.recordDao().allTrainings().isNotEmpty() } }
            rule.waitUntil(10000) { rule.onAllNodesWithText("修改说明").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("[]",runBlocking { db.recordDao().allTrainings().single().exercises })
            rule.onNodeWithText("修改说明").performClick()
            rule.onNodeWithText("记录说明").performTextReplacement("三组，后来补充了说明")
            rule.onNodeWithText("保存").performClick()
            rule.waitUntil(10000) { runBlocking { db.recordDao().allTrainings().single().revision==2 } }
            assertEquals("三组，后来补充了说明",runBlocking { db.recordDao().allTrainings().single().rawSummary })
        }finally{store.clear();db.close()}
    }
}
