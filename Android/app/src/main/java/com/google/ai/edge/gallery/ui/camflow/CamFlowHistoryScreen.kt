/*
 * Copyright 2025 Google LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 */
package com.google.ai.edge.gallery.ui.camflow
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.animation.core.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.ui.common.chat.ModelDownloadStatusInfoPanel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.TASK_CAMFLOW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
object CamFlowHistoryDestination {
    const val route = "cam_flow_history"
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamFlowHistoryScreen(
    camFlowViewModel: CamFlowViewModel,          // <-- shared VM injected
    modelManagerViewModel: ModelManagerViewModel,
    navigateUp: () -> Unit,
    navigateToCreate: () -> Unit,
    navigateToFaceId: () -> Unit,  // Added navigation parameter for Face ID
    navigateToDocument: () -> Unit,  // Added navigation parameter for Document Detection
    modifier: Modifier = Modifier
) {
    val sessions by camFlowViewModel.sessions.collectAsState()
    val uiState by camFlowViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope() // Added for proper coroutine scope
    // model selection / download state
    val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
    val selectedModel = modelManagerUiState.selectedModel
    val modelDownloadStatus = selectedModel?.let { model ->
        modelManagerUiState.modelDownloadStatus[model.name]
    }
    val isModelDownloaded = modelDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
    // Gallery monitoring state - using remember to persist across recompositions
    var isMonitoringGallery by remember { mutableStateOf(GalleryMonitorService.isMonitoring(context)) }
    // Today's image count - now with proper state updates
    var todayImageCount by remember { mutableStateOf(GalleryMonitorService.getTodayImageCount(context)) }
    // State for monitoring updates
    val monitoringUpdateTrigger = remember { MutableStateFlow(System.currentTimeMillis()) }
    // Snackbar host state for showing messages
    val snackbarHostState = remember { SnackbarHostState() }
    // State for settings dialog
    var showSettingsDialog by remember { mutableStateOf(false) }
    // State for history section expansion - using rememberSaveable to persist across recompositions
    var historyExpanded by rememberSaveable { mutableStateOf(false) }
    // Animation for FABs
    val infiniteTransition = rememberInfiniteTransition()
    val colorOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    // Gradient colors for FABs
    val gradientColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary
    )
    // Calculate current gradient offset
    val gradientOffset = colorOffset * 100
    // Create gradient brush
    val fabGradient = Brush.linearGradient(
        colors = gradientColors,
        start = Offset(gradientOffset, gradientOffset),
        end = Offset(gradientOffset + 100f, gradientOffset + 100f),
        tileMode = TileMode.Mirror
    )
    // Secondary FAB gradient
    val secondaryFabGradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer
        ),
        start = Offset(gradientOffset, gradientOffset),
        end = Offset(gradientOffset + 100f, gradientOffset + 100f),
        tileMode = TileMode.Mirror
    )
    // Function to trigger monitoring state update
    fun refreshMonitoringState() {
        monitoringUpdateTrigger.value = System.currentTimeMillis()
    }
    // Function to initialize model and show feedback
    fun initializeModelAndShowFeedback() {
        camFlowViewModel.initializeModel()
        scope.launch {
            snackbarHostState.showSnackbar("Initializing model...")
        }
    }
    // Function to refresh history and show feedback
    fun refreshHistoryAndShowFeedback() {
        camFlowViewModel.loadHistory()
        refreshMonitoringState()
        scope.launch {
            snackbarHostState.showSnackbar("History refreshed")
        }
    }
    // Function to toggle gallery monitoring
    fun toggleGalleryMonitoring(isChecked: Boolean) {
        if (isChecked) {
            // Save current settings for automation
            camFlowViewModel.saveAutomationSettings()
            GalleryMonitorService.start(context)
            // Refresh state after a short delay to allow service to start
            scope.launch {
                delay(1000)
                refreshMonitoringState()
            }
        } else {
            GalleryMonitorService.stop(context)
            refreshMonitoringState()
        }
        isMonitoringGallery = isChecked
    }
    // Function to update settings and show feedback
    fun updateSettingsAndShowFeedback() {
        // Update automation settings with current values
        camFlowViewModel.saveAutomationSettings()
        // Show a message that settings were updated
        scope.launch {
            snackbarHostState.showSnackbar("Settings updated")
        }
    }
    // Ensure we load history once
    LaunchedEffect(Unit) {
        camFlowViewModel.loadHistory()
    }
    // If a model is selected & downloaded, set and initialize once here (so History is the first stop)
    LaunchedEffect(selectedModel, isModelDownloaded) {
        if (selectedModel != null && isModelDownloaded) {
            Log.d("CamFlowHistory", "Setting model: ${selectedModel.name}")
            camFlowViewModel.setAiModel(selectedModel)
            camFlowViewModel.initializeModel()
        }
    }
    // Update monitoring state and image count when service state changes
    LaunchedEffect(monitoringUpdateTrigger) {
        monitoringUpdateTrigger.collect {
            isMonitoringGallery = GalleryMonitorService.isMonitoring(context)
            todayImageCount = GalleryMonitorService.getTodayImageCount(context)
        }
    }
    // Periodic refresh of monitoring state (more efficient than infinite loop)
    LaunchedEffect(Unit) {
        while (true) {
            refreshMonitoringState()
            kotlinx.coroutines.delay(5000) // Check every 5 seconds
        }
    }
    // Refresh monitoring state when returning to this screen
    DisposableEffect(Unit) {
        onDispose {
            // Clean up if needed
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CamFlow History") },
                navigationIcon = {
                    IconButton(onClick = navigateUp) {
                        Icon(Icons.Default.Sync, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            // Container for multiple FABs
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Document Detection FAB
                FloatingActionButton(
                    onClick = navigateToDocument,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Icon(Icons.Default.DocumentScanner, contentDescription = "Document Detection")
                }
                // Face ID FAB
                FloatingActionButton(
                    onClick = navigateToFaceId,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Icon(Icons.Default.Image, contentDescription = "Image Sender")
                }
                // Main CamFlow FAB - always navigates regardless of model state
                FloatingActionButton(
                    onClick = navigateToCreate,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New CamFlow")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {
            // Selected Model + Init status
            if (selectedModel == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "No Model Selected",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Text(
                            "Open Model Manager and pick a model for CamFlow to enable analysis and the + button.",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            } else if (!isModelDownloaded) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        ModelDownloadStatusInfoPanel(
                            model = selectedModel,
                            task = TASK_CAMFLOW,
                            modelManagerViewModel = modelManagerViewModel
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            } else {
                // Model status card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            uiState.isInitializingModel -> MaterialTheme.colorScheme.secondaryContainer
                            uiState.modelInitialized -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val icon = when {
                                uiState.isInitializingModel -> Icons.Default.Sync
                                uiState.modelInitialized -> Icons.Default.CheckCircle
                                else -> Icons.Default.Error
                            }
                            val tint = when {
                                uiState.isInitializingModel -> MaterialTheme.colorScheme.onSecondaryContainer
                                uiState.modelInitialized -> MaterialTheme.colorScheme.onPrimaryContainer
                                else -> MaterialTheme.colorScheme.onErrorContainer
                            }
                            Icon(icon, contentDescription = null, tint = tint)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "Model: ${selectedModel.name}",
                                    color = tint,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    when {
                                        uiState.isInitializingModel -> "Initializing..."
                                        uiState.modelInitialized -> "Ready"
                                        else -> "Not initialized"
                                    },
                                    color = tint
                                )
                            }
                        }
                        if (!uiState.modelInitialized && !uiState.isInitializingModel) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { initializeModelAndShowFeedback() }) {
                                Text("Initialize")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            // History Section - Now expandable
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    // Header with expand/collapse
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { historyExpanded = !historyExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Saved CamFlows (${sessions.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Refresh button
                            IconButton(
                                onClick = { refreshHistoryAndShowFeedback() }
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = "Refresh")
                            }
                            // Expand/collapse icon
                            Icon(
                                imageVector = if (historyExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (historyExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    // Content - shown only when expanded
                    if (historyExpanded) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (sessions.isEmpty()) {
                            Surface(
                                tonalElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("No CamFlow sessions yet.")
                                    Text(
                                        "Tap the + button to create a new one.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            // Scrollable history cards
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 400.dp), // Limit height to prevent taking too much space
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(sessions, key = { it.id }) { session ->
                                    CamFlowSessionCard(
                                        session = session,
                                        onDelete = { camFlowViewModel.deleteSession(session.id) },
                                        onOpen = { /* no-op; expansion via button */ },
                                        snackbarHostState = snackbarHostState,
                                        scope = scope
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // Settings Dialog
    if (showSettingsDialog) {
        Dialog(onDismissRequest = { showSettingsDialog = false }) {
            val density = LocalDensity.current
            val maxHeight = with(density) { 600.dp }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .padding(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .heightIn(max = maxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Dialog header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Gallery Settings",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { showSettingsDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                    // Gallery automation card - moved here
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isMonitoringGallery)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Gallery automation title and description
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isMonitoringGallery) Icons.Default.CheckCircle else Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = if (isMonitoringGallery)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Gallery Automation",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isMonitoringGallery)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (isMonitoringGallery)
                                            "Monitoring for new images"
                                        else
                                            "Process new gallery images automatically",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isMonitoringGallery)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            // Toggle switch on its own line
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Switch(
                                    checked = isMonitoringGallery,
                                    onCheckedChange = { toggleGalleryMonitoring(it) }
                                )
                            }
                            if (isMonitoringGallery) {
                                Button(
                                    onClick = { updateSettingsAndShowFeedback() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Update Settings")
                                }
                            }
                        }
                    }
                    // Gallery image counter card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Today's Gallery Images",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Text(
                                text = if (isMonitoringGallery) {
                                    "$todayImageCount new images added to gallery today"
                                } else {
                                    "Gallery monitoring is off"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            if (isMonitoringGallery) {
                                Text(
                                    text = "Counting started when monitoring began",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                    // Additional settings can be added here in the future
                    // Close button
                    Button(
                        onClick = { showSettingsDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
@Composable
private fun CamFlowSessionCard(
    session: CamFlowSession,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope
) {
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var expanded by rememberSaveable(session.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Date
            Text(
                sdf.format(Date(session.timestamp)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Prompt — truncated when collapsed, full when expanded
            Text(
                "Prompt: ${session.prompt}",
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            // Destination (simple, small, left-aligned)
            Text(
                text = if (session.destinationType == DestinationType.GMAIL)
                    "Destination: Gmail → ${session.destination}"
                else
                    "Destination: Telegram → ${session.destination}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Thumbnails directly under destination
            if (session.imageUris.isNotEmpty()) {
                ImageThumbRow(uris = session.imageUris)
                Text(
                    "${session.imageUris.size} image(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Analysis — highlighted container with subtle background and border
            if (session.analyses.isNotEmpty()) {
                Text(
                    "Analysis",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface, // match card but lighter
                    tonalElevation = 1.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (expanded) {
                            session.analyses.forEachIndexed { idx, a ->
                                Text("${idx + 1}. $a", style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            Text(
                                session.analyses.firstOrNull().orEmpty(),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
            // Footer: expand/collapse button on the left, delete on the right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { expanded = !expanded }
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.rotate(if (expanded) 180f else 0f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (expanded) "Collapse" else "Expand")
                }
                TextButton(onClick = {
                    onDelete()
                    scope.launch {
                        snackbarHostState.showSnackbar("Session deleted")
                    }
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                    Spacer(Modifier.width(4.dp))
                    Text("Delete")
                }
            }
        }
    }
}
/**
 * Displays a compact, horizontally scrollable row of thumbnails.
 * Safly decodes URIs; works whether list elements are Uri or String.
 */
@Composable
private fun ImageThumbRow(uris: List<Any>) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        uris.take(8).forEach { anyUri ->
            val uri: Uri = when (anyUri) {
                is Uri -> anyUri
                else -> Uri.parse(anyUri.toString())
            }
            // Decode a small thumbnail on-demand (no Coil dependency)
            val bitmap by produceState(initialValue = null as android.graphics.Bitmap?) {
                // Use the coroutine context provided by produceState
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val opts = BitmapFactory.Options().apply {
                            inJustDecodeBounds = false
                            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                            // Sample size to reduce memory usage
                            inSampleSize = 4
                        }
                        value = BitmapFactory.decodeStream(input, null, opts)
                    }
                } catch (_: Throwable) {
                    value = null
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "attachment",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "N/A",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}