// File: CamFlowDocumentScreen.kt (Fixed)
package com.google.ai.edge.gallery.ui.camflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

object CamFlowDocumentDestination {
    const val route = "cam_flow_document"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamFlowDocumentScreen(
    camFlowViewModel: CamFlowViewModel,
    modelManagerViewModel: ModelManagerViewModel,
    navigateUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val documentManager = remember { CamFlowDocumentManager(context) }

    var todayImages by remember { mutableStateOf<List<DocumentImage>>(emptyList()) }
    var documentImages by remember { mutableStateOf<List<DocumentImage>>(emptyList()) }
    var nonDocumentImages by remember { mutableStateOf<List<DocumentImage>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }
    var processingProgress by remember { mutableStateOf(0f) }
    var showSnackbar by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf("") }
    var selectedDocument by remember { mutableStateOf<DocumentImage?>(null) }

    // Function to load today's images
    fun loadTodayImages() {
        scope.launch {
            isProcessing = true
            try {
                todayImages = documentManager.getTodayImages()
                if (todayImages.isEmpty()) {
                    snackbarMessage = "No images found for today"
                    showSnackbar = true
                } else {
                    // Process images directly without calling another function
                    scope.launch {
                        isProcessing = true
                        processingProgress = 0f
                        try {
                            val (documents, nonDocuments) = documentManager.processImagesForDocuments(todayImages) { progress ->
                                processingProgress = progress
                            }
                            documentImages = documents
                            nonDocumentImages = nonDocuments
                        } catch (e: Exception) {
                            Log.e("CamFlowDocumentScreen", "Error processing images", e)
                            snackbarMessage = "Error processing images: ${e.message}"
                            showSnackbar = true
                        } finally {
                            isProcessing = false
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CamFlowDocumentScreen", "Error loading images", e)
                snackbarMessage = "Error loading images: ${e.message}"
                showSnackbar = true
                isProcessing = false
            }
        }
    }

    // Function to process a single document image and extract text
    fun processDocumentImage(image: DocumentImage) {
        scope.launch {
            selectedDocument = image
            isProcessing = true
            try {
                if (image.extractedText.isEmpty()) {
                    val text = documentManager.extractTextFromImage(image.uri)
                    selectedDocument = image.copy(extractedText = text)
                }
            } catch (e: Exception) {
                Log.e("CamFlowDocumentScreen", "Error processing document image", e)
                snackbarMessage = "Error processing document: ${e.message}"
                showSnackbar = true
            } finally {
                isProcessing = false
            }
        }
    }

    // Load today's images when the screen is first opened
    LaunchedEffect(Unit) {
        loadTodayImages()
    }

    // Image picker launcher for manual selection
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val name = "Manual Selection"
            val image = DocumentImage(
                uri = it,
                name = name,
                dateAdded = System.currentTimeMillis(),
                size = 0,
                isDocument = false
            )
            processDocumentImage(image)
        }
    }

    // Camera launcher for manual capture
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            selectedDocument?.let { image ->
                processDocumentImage(image)
            }
        }
    }

    // Function to create a temporary image URI for camera
    fun createImageUri(context: Context): Uri {
        val storageDir = context.getExternalFilesDir("documents")
        val file = java.io.File.createTempFile("doc_", ".jpg", storageDir)
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    // Clean up when the screen is disposed
    DisposableEffect(Unit) {
        onDispose {
            documentManager.close()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Document Detection") },
                navigationIcon = {
                    IconButton(onClick = navigateUp) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { loadTodayImages() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = {
            if (showSnackbar) {
                Snackbar(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(snackbarMessage)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Manual selection buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = {
                        imagePickerLauncher.launch("image/*")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pick Image")
                }
                Button(
                    onClick = {
                        val photoUri = createImageUri(context)
                        selectedDocument = DocumentImage(
                            uri = photoUri,
                            name = "Camera Capture",
                            dateAdded = System.currentTimeMillis(),
                            size = 0,
                            isDocument = false
                        )
                        cameraLauncher.launch(photoUri)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Take Photo")
                }
            }

            // Processing indicator
            if (isProcessing) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(progress = processingProgress)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Processing images... ${(processingProgress * 100).toInt()}%")
                }
            }

            // Summary section
            if (!isProcessing && todayImages.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Total Images",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${todayImages.size}",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Documents",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${documentImages.size}",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Non-Documents",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${nonDocumentImages.size}",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Document images section
            if (documentImages.isNotEmpty()) {
                Text(
                    text = "Documents (${documentImages.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(200.dp)
                ) {
                    items(documentImages) { image ->
                        DocumentImageItem(
                            image = image,
                            onClick = { processDocumentImage(image) },
                            context = context
                        )
                    }
                }
            }

            // Non-document images section
            if (nonDocumentImages.isNotEmpty()) {
                Text(
                    text = "Non-Documents (${nonDocumentImages.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(200.dp)
                ) {
                    items(nonDocumentImages) { image ->
                        DocumentImageItem(
                            image = image,
                            onClick = { processDocumentImage(image) },
                            context = context
                        )
                    }
                }
            }

            // Selected document preview and extracted text
            selectedDocument?.let { image ->
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Selected Document: ${image.name}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        val bitmap = remember(image.uri) {
                            try {
                                val inputStream = context.contentResolver.openInputStream(image.uri)
                                val options = BitmapFactory.Options().apply {
                                    inJustDecodeBounds = true
                                }
                                BitmapFactory.decodeStream(inputStream, null, options)
                                val sampleSize = calculateInSampleSize(options, 400, 400)
                                options.inSampleSize = sampleSize
                                options.inJustDecodeBounds = false
                                inputStream?.close()
                                val newStream = context.contentResolver.openInputStream(image.uri)
                                val bmp = BitmapFactory.decodeStream(newStream, null, options)
                                newStream?.close()
                                bmp
                            } catch (e: Exception) {
                                null
                            }
                        }
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Selected document",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        if (image.extractedText.isNotEmpty()) {
                            Text(
                                text = "Extracted Text:",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.height(150.dp)
                            ) {
                                items(image.extractedText) { textBlock ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Text(
                                            text = textBlock,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }
                            }
                        } else if (!isProcessing) {
                            Text(
                                text = "No text detected in the document",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DocumentImageItem(
    image: DocumentImage,
    onClick: () -> Unit,
    context: Context,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(image.uri) {
        try {
            val inputStream = context.contentResolver.openInputStream(image.uri)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            val sampleSize = calculateInSampleSize(options, 200, 200)
            options.inSampleSize = sampleSize
            options.inJustDecodeBounds = false
            inputStream?.close()
            val newStream = context.contentResolver.openInputStream(image.uri)
            val bmp = BitmapFactory.decodeStream(newStream, null, options)
            newStream?.close()
            bmp
        } catch (e: Exception) {
            null
        }
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Document thumbnail",
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.LightGray),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (image.isDocument) Icons.Default.DocumentScanner else Icons.Default.Image,
                        contentDescription = null
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = image.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(image.dateAdded)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (image.isDocument) {
                    Text(
                        text = "${image.extractedText.size} text blocks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = "View details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun calculateInSampleSize(
    options: BitmapFactory.Options,
    reqWidth: Int,
    reqHeight: Int
): Int {
    val (height: Int, width: Int) = options.run { outHeight to outWidth }
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2
        while (halfHeight / inSampleSize >= reqHeight &&
            halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}