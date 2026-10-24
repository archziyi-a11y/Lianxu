package com.example.lianxu.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.example.lianxu.data.repository.ResolvedComponent
import com.example.lianxu.data.model.CardAction
import coil.compose.AsyncImage
import java.io.File
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.lianxu.data.model.CardSpan
import com.example.lianxu.data.model.DashboardCard
import androidx.compose.ui.text.input.PasswordVisualTransformation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(modifier: Modifier = Modifier, viewModel: TodayViewModel = viewModel()) {
    val cards by viewModel.cards.collectAsState()
    val resolved by viewModel.resolved.collectAsState()
    val sourceDetails by viewModel.sourceDetails.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val taskProgress by viewModel.taskProgress.collectAsState()
    val isPlanning by viewModel.isPlanning.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val boardDate by viewModel.boardDate.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val demoMode by viewModel.demoMode.collectAsState()
    val profileSummary by viewModel.profileSummary.collectAsState()

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showTextInputDialog by remember { mutableStateOf(false) }
    var currentCardIdForText by remember { mutableStateOf<String?>(null) }
    var currentCardIdForImage by remember { mutableStateOf<String?>(null) }
    var currentTextInput by remember { mutableStateOf("") }
    
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> 
            uri?.let { 
                currentCardIdForImage?.let { id ->
                    viewModel.updateAnnotationImage(id, it.toString()) 
                }
            }
        }
    )

    sourceDetails?.let { details ->
        AlertDialog(onDismissRequest = viewModel::dismissSource, title = { Text("食物与计算依据") },
            text = { Text(details, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = viewModel::dismissSource) { Text("关闭") } })
    }

    if (showSettingsDialog) {
        var apiKeyInput by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            viewModel.getApiKey { key -> apiKeyInput = key ?: "" }
        }
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Settings") },
            text = {
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("DeepSeek API Key (leave empty for Demo)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveApiKey(apiKeyInput)
                    showSettingsDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showTextInputDialog) {
        AlertDialog(
            onDismissRequest = { showTextInputDialog = false },
            title = { Text("Add Annotation") },
            text = {
                OutlinedTextField(
                    value = currentTextInput,
                    onValueChange = { currentTextInput = it },
                    label = { Text("Annotation Note") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    currentCardIdForText?.let { id ->
                        viewModel.updateAnnotationText(id, currentTextInput)
                    }
                    showTextInputDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextInputDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("今日") },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            val hasAnnotations = cards.any { !it.annotationText.isNullOrEmpty() || it.attachmentId != null }
            if (hasAnnotations && !isProcessing && !isImporting) {
                FloatingActionButton(
                    onClick = { viewModel.submitAnnotations() },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.width(120.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Submit Annotations")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Submit")
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(profileSummary, style = MaterialTheme.typography.bodyMedium)
                if (demoMode) Text("AI未配置 · 本地营养规划可用", color = MaterialTheme.colorScheme.secondary)
                if (isPlanning) Text("正在更新营养与餐食…")
                if (isImporting) Text("正在保存图片…")
                if(boardDate.isNotBlank())Text(boardDate,style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=16.dp))
                RestTimerControls()
                notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            Box(modifier = Modifier.weight(1f)) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isProcessing,
                    enter = slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(500)
                    ) + fadeIn(animationSpec = tween(500)),
                    exit = slideOutVertically(
                        targetOffsetY = { -it },
                        animationSpec = tween(500)
                    ) + fadeOut(animationSpec = tween(500)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = cards,
                            key = { it.id },
                            span = { card -> GridItemSpan(card.span.span) }
                        ) { card ->
                            DashboardCardItem(
                                card = card,
                                resolved = resolved[card.id].orEmpty(),
                                onAction = { action, value -> viewModel.performAction(action, value) },
                                onChoice = { componentId, key -> viewModel.selectAssistantChoice(card.id, componentId, key) },
                                onLongPress = { viewModel.toggleAnnotationMode(card.id) },
                                onTap = {
                                    currentCardIdForText = card.id
                                    currentTextInput = card.annotationText ?: ""
                                    showTextInputDialog = true
                                },
                                onSwipeUp = {
                                    currentCardIdForImage = card.id
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            )
                        }
                    }
                }
            
                if (isProcessing) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = taskProgress ?: "正在处理本轮补充",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = viewModel::cancelSubmission) { Text("取消 · 保留输入") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardCardItem(
    card: DashboardCard,
    onLongPress: () -> Unit,
    onTap: () -> Unit,
    onSwipeUp: () -> Unit,
    resolved: List<ResolvedComponent> = emptyList(),
    onAction: (CardAction, String?) -> Unit = { _, _ -> },
    onChoice: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .combinedClickable(
                onLongClick = onLongPress,
                onClick = {}
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            if (card.isAnnotationMode) 3.dp else 2.dp,
            if (card.isAnnotationMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = card.title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
                if (card.blocks.isEmpty()) Text(card.value, style = MaterialTheme.typography.bodyLarge)
                else resolved.forEach { block -> ComponentView(block, resolved.size == 1, onAction) { key -> onChoice(block.id, key) } }
            }
            if (card.isAnnotationMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { onTap() }
                            )
                        }
                        .pointerInput(Unit) {
                            var swipeUpTriggered = false
                            detectVerticalDragGestures(
                                onDragEnd = { swipeUpTriggered = false }
                            ) { _, dragAmount ->
                                if (!swipeUpTriggered && dragAmount < -20) {
                                    swipeUpTriggered = true
                                    onSwipeUp()
                                }
                            }
                        }
                )
                if (!card.annotationText.isNullOrEmpty()) {
                    Text(
                        text = "📝",
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    )
                }
                if (!card.annotationImageUri.isNullOrEmpty()) {
                    Text(
                        text = "🖼️",
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComponentView(block: ResolvedComponent, largeMetric: Boolean, onAction: (CardAction, String?) -> Unit, onChoice: (String) -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (block.type) {
            "metric" -> {
                if (block.label.isNotBlank() && !largeMetric) Text(block.label, style = MaterialTheme.typography.labelMedium)
                Text(block.text, style = if (largeMetric) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold)
            }
            "choice" -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.options.forEach { option ->
                        FilterChip(selected = block.selectedKey == option.key, enabled = true,
                            onClick = { block.actions.firstOrNull()?.let { onAction(it, option.key) } ?: onChoice(option.key) }, label = { Text(option.label) })
                    }
                }
            }
            "trend" -> {
                val color = MaterialTheme.colorScheme.primary
                if (block.points.isNotEmpty()) {
                    Canvas(modifier = Modifier.fillMaxWidth().height(100.dp).semantics {
                        contentDescription = block.points.joinToString { "${it.label}: ${it.value}kg" }
                    }) {
                        val low = block.points.minOf { it.value }; val high = block.points.maxOf { it.value }
                        val spread = (high - low).coerceAtLeast(.2)
                        val coordinates = block.points.mapIndexed { i, point -> Offset(
                            if (block.points.size == 1) size.width / 2 else i.toFloat() / (block.points.size - 1) * size.width,
                            size.height - 8f - ((point.value - low) / spread).toFloat() * (size.height - 16f)) }
                        coordinates.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, 3f) }
                        coordinates.forEach { drawCircle(color, 4f, it) }
                    }
                    Text("${block.points.first().label} — ${block.points.last().label}", style = MaterialTheme.typography.labelSmall)
                }
                Text(block.text, style = MaterialTheme.typography.bodyMedium)
            }
            "image" -> {
                block.attachmentPath?.let { AsyncImage(model = File(context.filesDir, it), contentDescription = block.text,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) }
                if (block.text.isNotBlank()) Text(block.text)
            }
            else -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
        }
        if (block.type != "choice") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            block.actions.forEach { action -> OutlinedButton(onClick = { onAction(action, null) }) { Text(action.label) } }
        }
    }
}
