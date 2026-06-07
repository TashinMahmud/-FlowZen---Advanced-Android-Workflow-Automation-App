/*
 * Copyright 2025 Google LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 */
package com.google.ai.edge.gallery.ui.camflow

import android.net.Uri
import android.util.Log
import android.content.Intent
import android.provider.MediaStore
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.ui.common.chat.ModelDownloadStatusInfoPanel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.TASK_CAMFLOW
import android.widget.Toast
import android.media.MediaScannerConnection
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.content.Context
import android.database.Cursor
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController

object CamFlowDestination {
    const val route = "cam_flow"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamFlowScreen(
    camFlowViewModel: CamFlowViewModel,
    modelManagerViewModel: ModelManagerViewModel,
    navigateUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Log.d("CamFlowScreen", "🔍 DEBUG: CamFlowScreen composable started")
    val context = LocalContext.current
    val navController = rememberNavController()
    val uiState by camFlowViewModel.uiState.collectAsState()
    var currentPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
    val selectedModel = modelManagerUiState.selectedModel
    val modelDownloadStatus = selectedModel?.let { model ->
        modelManagerUiState.modelDownloadStatus[model.name]
    }
    val isModelDownloaded = modelDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED

    LaunchedEffect(selectedModel, isModelDownloaded) {
        if (selectedModel != null && isModelDownloaded) {
            camFlowViewModel.setAiModel(selectedModel)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            currentPhotoUri?.let { uri ->
                camFlowViewModel.addImage(uri, fromCamera = true)
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            camFlowViewModel.addImage(uri)
        }
    }

    LaunchedEffect(Unit) {
        camFlowViewModel.initializePreferences(context)
        camFlowViewModel.clearImages(keepDestination = true)
        camFlowViewModel.setPrompt("")
        camFlowViewModel.ensureRestoredDestination()
    }

    CamFlowViewWrapper(
        viewModel = camFlowViewModel,
        modelManagerViewModel = modelManagerViewModel,
        navigateUp = navigateUp,
        modifier = modifier,
        uiState = uiState,
        isModelDownloaded = isModelDownloaded,
        selectedModel = selectedModel,
        cameraLauncher = cameraLauncher,
        galleryLauncher = galleryLauncher,
        onCameraClick = {
            try {
                val photoFile = camFlowViewModel.createImageFile()
                val photoUri = camFlowViewModel.getUriForFile(photoFile)
                currentPhotoUri = photoUri
                cameraLauncher.launch(photoUri)
            } catch (e: Exception) {
                Log.e("CamFlowScreen", "Error launching camera", e)
            }
        },
        navController = navController
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamFlowViewWrapper(
    viewModel: CamFlowViewModel,
    modelManagerViewModel: ModelManagerViewModel,
    navigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    uiState: CamFlowUiState,
    isModelDownloaded: Boolean,
    selectedModel: com.google.ai.edge.gallery.data.Model?,
    cameraLauncher: androidx.activity.result.ActivityResultLauncher<Uri>,
    galleryLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    onCameraClick: () -> Unit,
    navController: NavController
) {
    val context = LocalContext.current
    var analysisExpanded by remember { mutableStateOf(true) } // Analysis section expanded by default

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CamFlow Analysis") },
                navigationIcon = {
                    IconButton(onClick = navigateUp) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                selectedModel == null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = "No Model Selected",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Please select a model from the Model Manager to use CamFlow features.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Button(
                                    onClick = navigateUp,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    )
                                ) { Text("Go to Model Manager") }
                            }
                        }
                    }
                }
                !isModelDownloaded -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        ModelDownloadStatusInfoPanel(
                            model = selectedModel,
                            task = TASK_CAMFLOW,
                            modelManagerViewModel = modelManagerViewModel,
                        )
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Selected model card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = "Selected Model",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = selectedModel?.name ?: "No model selected",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        // Analysis Section (Expandable)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // Header with expand/collapse icon
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { analysisExpanded = !analysisExpanded },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Analytics,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = "Analysis",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Icon(
                                        imageVector = if (analysisExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = if (analysisExpanded) "Collapse" else "Expand",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                // Expandable content
                                if (analysisExpanded) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    // Model initialization status card
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
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
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
                                                Spacer(modifier = Modifier.width(16.dp))
                                                Column {
                                                    Text(
                                                        text = "Model Status",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = tint
                                                    )
                                                    Text(
                                                        text = when {
                                                            uiState.isInitializingModel -> "Initializing..."
                                                            uiState.modelInitialized -> "Ready to process"
                                                            else -> "Not initialized"
                                                        },
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = tint
                                                    )
                                                }
                                            }
                                            if (!uiState.modelInitialized && !uiState.isInitializingModel) {
                                                Button(
                                                    onClick = { viewModel.initializeModel() },
                                                    enabled = !uiState.isProcessing
                                                ) { Text("Initialize Model") }
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    // Image Selection Card
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Text("Select Images", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                                            ) {
                                                Button(onClick = onCameraClick, modifier = Modifier.weight(1f)) {
                                                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("Camera")
                                                }
                                                Button(
                                                    onClick = { galleryLauncher.launch("image/*") },
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("Gallery")
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(16.dp))
                                            if (uiState.selectedImages.isNotEmpty()) {
                                                Text(
                                                    text = "Selected Images (${uiState.selectedImages.size}/15)",
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                LazyVerticalGrid(
                                                    columns = GridCells.Adaptive(minSize = 100.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                                    modifier = Modifier.heightIn(max = 300.dp)
                                                ) {
                                                    itemsIndexed(uiState.selectedImages) { index, uri ->
                                                        ImagePreviewItem(
                                                            uri = uri,
                                                            onRemove = { viewModel.removeImage(index) }
                                                        )
                                                    }
                                                }
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(120.dp)
                                                        .border(
                                                            1.dp,
                                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                                            RoundedCornerShape(8.dp)
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = "No images selected. You can select images from the gallery.",
                                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    // Prompt Card
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Text("Analysis Prompt", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            OutlinedTextField(
                                                value = uiState.prompt,
                                                onValueChange = { viewModel.setPrompt(it) },
                                                placeholder = { Text("Describe what you want the AI to analyze...") },
                                                modifier = Modifier.fillMaxWidth(),
                                                minLines = 3,
                                                maxLines = 5,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                                                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "Example: Extract store names and total amounts from these receipts",
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        // Destination Card - Improved Design
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                // Header with icon
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Send,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Destination",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                // Destination type selection
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = uiState.destinationType == DestinationType.GMAIL,
                                        onClick = {
                                            viewModel.setDestination(DestinationType.GMAIL, "")
                                        }
                                    )
                                    Text(
                                        text = "Gmail",
                                        modifier = Modifier.padding(start = 8.dp, end = 24.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    RadioButton(
                                        selected = uiState.destinationType == DestinationType.TELEGRAM,
                                        onClick = {
                                            viewModel.setDestination(DestinationType.TELEGRAM, uiState.destination)
                                        }
                                    )
                                    Text(
                                        text = "Telegram",
                                        modifier = Modifier.padding(start = 8.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                // Gmail section
                                if (uiState.destinationType == DestinationType.GMAIL) {
                                    OutlinedTextField(
                                        value = uiState.destination,
                                        onValueChange = { viewModel.setDestination(DestinationType.GMAIL, it) },
                                        placeholder = { Text("Enter email address") },
                                        modifier = Modifier.fillMaxWidth(),
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Email,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                                // Telegram section - Improved layout
                                else {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        // Status display
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.Info,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Text(
                                                    text = uiState.tgStatus,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                        // Progress indicator
                                        if (uiState.tgConnecting) {
                                            LinearProgressIndicator(
                                                modifier = Modifier.fillMaxWidth(),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        // Connected chat ID display
                                        if (uiState.destination.isNotBlank()) {
                                            Card(
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                                )
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        Icons.Default.CheckCircle,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                                    )
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Column {
                                                        Text(
                                                            text = "Connected to Telegram",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                                        )
                                                        Text(
                                                            text = "Chat ID: ${uiState.destination}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        // Action buttons - Improved layout
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                enabled = !uiState.tgConnecting,
                                                onClick = {
                                                    val link = viewModel.beginTelegramInvite()
                                                    TelegramDeepLinkHelper.shareText(
                                                        context,
                                                        "Tap to connect with my app: $link"
                                                    )
                                                    viewModel.pollTelegramForStart()
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Default.Send, contentDescription = null)
                                                Spacer(Modifier.width(8.dp))
                                                Text("Share Telegram Invite")
                                            }
                                            OutlinedButton(
                                                onClick = { viewModel.disconnectTelegram() },
                                                enabled = uiState.destination.isNotBlank(),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = null)
                                                Spacer(Modifier.width(8.dp))
                                                Text("Disconnect")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        // Options Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "Attach original images", fontSize = 16.sp)
                                Switch(
                                    checked = uiState.attachImages,
                                    onCheckedChange = { viewModel.setAttachImages(it) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        // Actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.clearImages(keepDestination = true) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Clear All")
                            }
                            val canProcess = uiState.selectedImages.isNotEmpty() &&
                                    uiState.prompt.isNotBlank() &&
                                    uiState.destination.isNotBlank() &&
                                    uiState.modelInitialized &&
                                    !uiState.isProcessing &&
                                    !uiState.isInitializingModel
                            Button(
                                onClick = { viewModel.processImages() },
                                modifier = Modifier.weight(1f),
                                enabled = canProcess
                            ) {
                                if (uiState.isProcessing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("Process & Send")
                                }
                            }
                        }
                        // Error / Success Messages
                        uiState.errorMessage?.let { error ->
                            Spacer(modifier = Modifier.height(16.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Error,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(text = error, color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                        uiState.successMessage?.let { success ->
                            Spacer(modifier = Modifier.height(16.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(text = success, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                        // Processing Progress
                        if (uiState.isProcessing) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Sync,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column {
                                            Text(
                                                text = "Processing Images",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            Text(
                                                text = "Analyzing and sending results...",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = uiState.processingProgress.coerceIn(0f, 1f),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Progress: ${(uiState.processingProgress.coerceIn(0f, 1f) * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ImagePreviewItem(
    uri: Uri,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap = remember(uri) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                android.graphics.BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) { null }
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Selected image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(
                    MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    RoundedCornerShape(50)
                )
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove image",
                tint = MaterialTheme.colorScheme.onError
            )
        }
    }
}