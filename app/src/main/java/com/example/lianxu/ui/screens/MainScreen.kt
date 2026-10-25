package com.example.lianxu.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.*
import kotlin.math.abs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen(todayModel: TodayViewModel = androidx.lifecycle.viewmodel.compose.viewModel(), archiveModel: ArchiveViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val scope=rememberCoroutineScope()
    var archiveSection by remember { mutableStateOf("首页") }
    val pagerState = rememberPagerState(pageCount = { 2 })

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            CustomBottomIndicator(
                pageCount = 2,
                currentPage = pagerState.currentPage,
                offset = pagerState.currentPageOffsetFraction,
                onPage = { scope.launch { pagerState.animateScrollToPage(it) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding().padding(vertical = 4.dp)
            )
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding).consumeWindowInsets(innerPadding)
        ) { page ->
            when (page) {
                0 -> TodayScreen(viewModel=todayModel,onManage={archiveSection="安排";scope.launch { pagerState.animateScrollToPage(1) }})
                1 -> ArchiveScreen(viewModel=archiveModel,initialSection=archiveSection,onSectionConsumed={archiveSection="首页"},onSettings={archiveSection="设置"},todayModel=todayModel,onRetry=todayModel::retryArchived,onShowToday={scope.launch { pagerState.animateScrollToPage(0) }})
            }
        }
    }
}

@Composable
fun CustomBottomIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
    offset: Float = 0f,
    onPage: (Int)->Unit = {}
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val selected = (1f-abs(index-(currentPage+offset))).coerceIn(0f,1f)
            val width = (8+24*selected).dp
            val color = lerp(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.primary,selected)

            Box(Modifier.width(52.dp).height(48.dp).clickable { onPage(index) }.semantics { contentDescription=if(index==0)"切换今日" else "切换档案" },contentAlignment=Alignment.Center) { Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .width(width)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(color)
            ) }
        }
    }
}
