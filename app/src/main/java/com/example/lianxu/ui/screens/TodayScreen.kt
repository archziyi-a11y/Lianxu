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
    val isProcessing by viewModel.isProcessing.collectAsState()
    val notice by viewModel.notice.collectAsState()
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
                title = { Text("Dashboard") },
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
                if (demoMode) Text("演示模式 · 尚未连接AI", color = MaterialTheme.colorScheme.secondary)
                if (isImporting) Text("正在保存图片…")
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
                        Text(
                            text = "PROCESSING...",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
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
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
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
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = card.title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = card.value,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Black
                )
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
