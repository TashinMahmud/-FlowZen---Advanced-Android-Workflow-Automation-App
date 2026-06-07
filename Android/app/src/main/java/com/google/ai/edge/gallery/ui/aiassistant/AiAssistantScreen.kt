package com.google.ai.edge.gallery.ui.aiassistant
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.ai.edge.gallery.ui.common.chat.ChatView
import kotlinx.coroutines.delay
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.google.ai.edge.gallery.data.processTasks
import kotlinx.coroutines.launch

@Composable
fun AiAssistantScreen(
    modelManagerViewModel: ModelManagerViewModel,
    navigateUp: () -> Unit,
    navigateToGeofencing: () -> Unit = {},
    navigateToCamFlow: () -> Unit = {},
    navigateToWorkflow: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel = remember {
        Log.d("AiAssistantScreen", "Creating AiAssistantViewModel")
        AiAssistantViewModel()
    }
    var shouldNavigateToGeofencing by remember { mutableStateOf(false) }
    var shouldShowWorkflowNavigationDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Collect state flows instead of accessing .value directly
    val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
    val selectedModel = modelManagerUiState.selectedModel
    val modelDownloadStatus = selectedModel?.let { model ->
        modelManagerUiState.modelDownloadStatus[model.name]
    }
    val modelInitializationStatus = selectedModel?.let { model ->
        modelManagerUiState.modelInitializationStatus[model.name]
    }

    // Check for geofencing and CamFlow flags and navigate if needed
    LaunchedEffect(Unit) {
        // Clear any existing navigation flags when AI Assistant opens
        val prefs = context.getSharedPreferences("AI_ASSISTANT_PREFS", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("OPEN_GEOFENCING", false)
            .putBoolean("OPEN_GEOFENCING_MAP", false)
            .putBoolean("OPEN_CAMFLOW", false)
            .apply()
        Log.d("AiAssistantScreen", "📍 Cleared navigation flags on AI Assistant open")
    }

    // Monitor for geofencing map requests
    LaunchedEffect(shouldNavigateToGeofencing) {
        if (shouldNavigateToGeofencing) {
            Log.d("AiAssistantScreen", "📍 Navigating to geofencing map")
            navigateToGeofencing()
            shouldNavigateToGeofencing = false
        }
    }

    // Check for geofencing flag periodically
    LaunchedEffect(Unit) {
        while (true) {
            val prefs = context.getSharedPreferences("AI_ASSISTANT_PREFS", Context.MODE_PRIVATE)
            val shouldOpenGeofencingMap = prefs.getBoolean("OPEN_GEOFENCING_MAP", false)
            if (shouldOpenGeofencingMap) {
                Log.d("AiAssistantScreen", "📍 AI Assistant requested geofencing map")
                // Clear the flag
                prefs.edit().putBoolean("OPEN_GEOFENCING_MAP", false).apply()
                // Add delay so user can see the message
                delay(2000) // 2 second delay
                // Set state to trigger navigation
                shouldNavigateToGeofencing = true
                break
            }
            delay(500) // Check every 500ms for faster response
        }
    }

    // Check for CamFlow flag periodically
    LaunchedEffect(Unit) {
        while (true) {
            val prefs = context.getSharedPreferences("AI_ASSISTANT_PREFS", Context.MODE_PRIVATE)
            val shouldOpenCamFlow = prefs.getBoolean("OPEN_CAMFLOW", false)
            if (shouldOpenCamFlow) {
                Log.d("AiAssistantScreen", "📷 AI Assistant requested CamFlow")
                // Clear the flag
                prefs.edit().putBoolean("OPEN_CAMFLOW", false).apply()
                // Add delay so user can see the message
                delay(2000) // 2 second delay
                // Navigate to CamFlow
                navigateToCamFlow()
                break
            }
            delay(500) // Check every 500ms for faster response
        }
    }

    // Initialize AI Assistant and ensure models are loaded
    LaunchedEffect(Unit) {
        Log.d("AiAssistantScreen", "🔍 DEBUG: Starting AI Assistant initialization")
        Log.d("AiAssistantScreen", "🔍 DEBUG: Initializing AiAssistantFunctions")
        viewModel.initializeAiAssistantFunctions(context)
        Log.d("AiAssistantScreen", "✅ DEBUG: AiAssistantFunctions initialized")

        // Process tasks to ensure models are properly assigned
        processTasks()

        // Wait for models to be loaded
        Log.d("AiAssistantScreen", "🔍 DEBUG: Waiting for models to be loaded...")
        var attempts = 0
        while (viewModel.task.models.isEmpty() && attempts < 50) {
            delay(100)
            attempts++
            // Try processing tasks again if models are still empty
            if (attempts % 10 == 0) {
                processTasks()
            }
        }
        Log.d("AiAssistantScreen", "🔍 DEBUG: Models loaded after $attempts attempts")
        Log.d("AiAssistantScreen", "🔍 DEBUG: Number of models: ${viewModel.task.models.size}")

        // Initialize the first available model for AI Assistant
        val firstModel = viewModel.task.models.firstOrNull()
        if (firstModel != null) {
            Log.d("AiAssistantScreen", "✅ DEBUG: Found first model: ${firstModel.name}")
            modelManagerViewModel.selectModel(firstModel)
        } else {
            Log.w("AiAssistantScreen", "No models available for AI Assistant after waiting")
        }

        // Add welcome message when AI Assistant first opens
        val welcomeMessage = """
            🤖 **Welcome to AI Assistant!**
            
            I'm your personal AI companion that can help you with both general conversation and specific tasks.
            
            **💬 General Chat:**
            • Ask me anything - questions, advice, jokes, or just chat
            • I'll respond naturally like a regular AI assistant
            
            **⚡ Special Functions:**
            For these specific requests, I'll help you create workflows:
            
            • **📧 Email Summarization:** "summarize emails", "send email summaries to email@example.com", "create email workflow"
            • **📍 Geofencing:** "open geofencing", "create location reminder", "set up geofence"
            • **📷 CamFlow:** "open camflow", "use camera analysis", "analyze images"
            
            **💡 Tip:** Just chat normally! I'll only activate special functions for very clear requests.
            
            How can I help you today?
            """.trimIndent()

        // Add the welcome message to the chat
        if (firstModel != null) {
            viewModel.addMessage(
                model = firstModel,
                message = ChatMessageText(
                    content = welcomeMessage,
                    side = ChatSide.AGENT,
                ),
            )
        }
    }

    // Monitor model download status and initialize when ready
    LaunchedEffect(modelDownloadStatus, selectedModel) {
        if (selectedModel != null && modelDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
            // Check if model is already initialized or initializing using collected state
            val initStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
            if (initStatus?.status != ModelInitializationStatusType.INITIALIZED &&
                initStatus?.status != ModelInitializationStatusType.INITIALIZING) {
                Log.d("AiAssistantScreen", "Model ${selectedModel.name} is ready, initializing...")
                modelManagerViewModel.initializeModel(context, task = viewModel.task, model = selectedModel)
            } else {
                Log.d("AiAssistantScreen", "Model ${selectedModel.name} is already ${initStatus?.status}, skipping initialization")
            }
        }
    }

    // Monitor model initialization status but don't block UI
    LaunchedEffect(modelInitializationStatus, selectedModel) {
        if (selectedModel != null) {
            when (modelInitializationStatus?.status) {
                ModelInitializationStatusType.INITIALIZED -> {
                    Log.d("AiAssistantScreen", "✅ Model ${selectedModel.name} is fully initialized and ready")
                }
                ModelInitializationStatusType.ERROR -> {
                    Log.e("AiAssistantScreen", "❌ Model ${selectedModel.name} initialization failed: ${modelInitializationStatus.error}")
                }
                ModelInitializationStatusType.INITIALIZING -> {
                    Log.d("AiAssistantScreen", "🔄 Model ${selectedModel.name} is initializing...")
                }
                else -> {
                    Log.d("AiAssistantScreen", "⏳ Model ${selectedModel.name} initialization status: ${modelInitializationStatus?.status}")
                }
            }
        }
    }

    // Workflow navigation confirmation dialog
    if (shouldShowWorkflowNavigationDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { shouldShowWorkflowNavigationDialog = false },
            title = { androidx.compose.material3.Text("🎉 Workflow Created Successfully!") },
            text = { androidx.compose.material3.Text("Your workflow has been created and saved. Would you like to go to the Workflow screen to view and manage your workflows?") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        shouldShowWorkflowNavigationDialog = false
                        navigateToWorkflow()
                    }
                ) {
                    androidx.compose.material3.Text("Yes, Go to Workflows")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { shouldShowWorkflowNavigationDialog = false }
                ) {
                    androidx.compose.material3.Text("Stay Here")
                }
            }
        )
    }

    Log.d("AiAssistantScreen", "Rendering ChatView")

    // Safety check: Ensure AI Assistant task has models before rendering
    if (viewModel.task.models.isEmpty()) {
        Log.e("AiAssistantScreen", "❌ CRITICAL: AI Assistant task has no models! Cannot render ChatView")
        // Try processing tasks one more time as a last resort
        processTasks()
        // Show a simple error message instead of crashing
        return
    }

    Log.d("AiAssistantScreen", "✅ DEBUG: AI Assistant task has ${viewModel.task.models.size} models")
    Log.d("AiAssistantScreen", "✅ DEBUG: Models: ${viewModel.task.models.map { it.name }}")

    // Check if selectedModel is properly set and ensure it's valid
    Log.d("AiAssistantScreen", "🔍 DEBUG: Selected model: ${selectedModel?.name ?: "NULL"}")
    Log.d("AiAssistantScreen", "🔍 DEBUG: Selected model in task models: ${selectedModel?.let { viewModel.task.models.contains(it) } ?: false}")

    // Ensure selectedModel is set to the first model if it's null or not in the task
    if (selectedModel == null || !viewModel.task.models.contains(selectedModel)) {
        val firstModel = viewModel.task.models.firstOrNull()
        if (firstModel != null) {
            Log.d("AiAssistantScreen", "🔍 DEBUG: Setting selected model to first model: ${firstModel.name}")
            modelManagerViewModel.selectModel(firstModel)
        } else {
            Log.e("AiAssistantScreen", "❌ CRITICAL: No models available in task!")
            return
        }
    }

    // Final safety check - ensure we have a valid selectedModel
    val finalSelectedModel = modelManagerUiState.selectedModel
    if (finalSelectedModel == null || !viewModel.task.models.contains(finalSelectedModel)) {
        Log.e("AiAssistantScreen", "❌ CRITICAL: Selected model is still invalid after setting!")
        return
    }

    Log.d("AiAssistantScreen", "✅ DEBUG: Final selected model: ${finalSelectedModel.name}")
    Log.d("AiAssistantScreen", "✅ DEBUG: Model index in task: ${viewModel.task.models.indexOf(finalSelectedModel)}")

    // Check if model is ready for interaction using collected state
    val isModelReady = finalSelectedModel.instance != null &&
            modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZED
    Log.d("AiAssistantScreen", "🔍 DEBUG: Model ready for interaction: $isModelReady")
    Log.d("AiAssistantScreen", "🔍 DEBUG: Model instance null: ${finalSelectedModel.instance == null}")
    Log.d("AiAssistantScreen", "🔍 DEBUG: Initialization status: ${modelInitializationStatus?.status}")

    ChatView(
        task = viewModel.task,
        viewModel = viewModel,
        modelManagerViewModel = modelManagerViewModel,
        onSendMessage = { model, messages ->
            Log.d("AiAssistantScreen", "onSendMessage called with ${messages.size} messages")
            // Check if model is ready for processing using collected state
            val currentModelReady = model.instance != null &&
                    modelManagerUiState.modelInitializationStatus[model.name]?.status == ModelInitializationStatusType.INITIALIZED
            if (!currentModelReady) {
                Log.w("AiAssistantScreen", "⚠️ Model ${model.name} is not ready yet. Initialization status: ${modelManagerUiState.modelInitializationStatus[model.name]?.status}")
                // Add a message to inform the user that the model is still loading
                viewModel.addMessage(
                    model = model,
                    message = ChatMessageText(
                        content = "🤖 I'm still loading my AI model. Please wait a moment and try again...",
                        side = ChatSide.AGENT,
                    ),
                )
                return@ChatView
            }
            for (message in messages) {
                viewModel.addMessage(model = model, message = message)
            }
            var text = ""
            val images: MutableList<Bitmap> = mutableListOf()
            val audioMessages: MutableList<ChatMessageAudioClip> = mutableListOf()
            var chatMessageText: ChatMessageText? = null
            for (message in messages) {
                if (message is ChatMessageText) {
                    chatMessageText = message
                    text = message.content
                } else if (message is ChatMessageImage) {
                    images.add(message.bitmap)
                } else if (message is ChatMessageAudioClip) {
                    audioMessages.add(message)
                }
            }
            if ((text.isNotEmpty() && chatMessageText != null) || audioMessages.isNotEmpty()) {
                Log.d("AiAssistantScreen", "🔍 DEBUG: Starting message processing")
                Log.d("AiAssistantScreen", "🔍 DEBUG: Text: '$text'")
                Log.d("AiAssistantScreen", "🔍 DEBUG: Model: ${model.name}")
                Log.d("AiAssistantScreen", "🔍 DEBUG: Model instance null: ${model.instance == null}")
                Log.d("AiAssistantScreen", "🔍 DEBUG: Images count: ${images.size}")
                Log.d("AiAssistantScreen", "🔍 DEBUG: Audio messages count: ${audioMessages.size}")
                modelManagerViewModel.addTextInputHistory(text)
                // Try AI Assistant processing first, fallback to normal chat
                try {
                    Log.d("AiAssistantScreen", "🔍 DEBUG: Calling processAiAssistantInput...")
                    viewModel.processAiAssistantInput(
                        model = model,
                        input = text,
                        images = images,
                        audioMessages = audioMessages,
                        onError = {
                            Log.e("AiAssistantScreen", "❌ ERROR: Error in AI Assistant processing, falling back to normal chat")
                            Log.d("AiAssistantScreen", "🔍 DEBUG: Starting fallback to generateResponse...")
                            // Fallback to normal generateResponse
                            viewModel.generateResponse(
                                model = model,
                                input = text,
                                images = images,
                                audioMessages = audioMessages,
                                onError = {
                                    Log.e("AiAssistantScreen", "❌ ERROR: Even fallback generateResponse failed")
                                    viewModel.handleError(
                                        context = context,
                                        model = model,
                                        modelManagerViewModel = modelManagerViewModel,
                                        triggeredMessage = chatMessageText,
                                    )
                                },
                            )
                        },
                    )
                    Log.d("AiAssistantScreen", "✅ DEBUG: processAiAssistantInput called successfully")
                } catch (e: Exception) {
                    Log.e("AiAssistantScreen", "❌ EXCEPTION: Exception in AI Assistant processing, falling back to normal chat", e)
                    Log.d("AiAssistantScreen", "🔍 DEBUG: Starting exception fallback to generateResponse...")
                    // Fallback to normal generateResponse
                    viewModel.generateResponse(
                        model = model,
                        input = text,
                        images = images,
                        audioMessages = audioMessages,
                        onError = {
                            Log.e("AiAssistantScreen", "❌ ERROR: Even exception fallback generateResponse failed")
                            viewModel.handleError(
                                context = context,
                                model = model,
                                modelManagerViewModel = modelManagerViewModel,
                                triggeredMessage = chatMessageText,
                            )
                        },
                    )
                }
            } else {
                Log.d("AiAssistantScreen", "🔍 DEBUG: Skipping processing - no text or audio messages")
            }
        },
        onRunAgainClicked = { model, message ->
            if (message is ChatMessageText) {
                viewModel.runAgain(
                    model = model,
                    message = message,
                    onError = {
                        viewModel.handleError(
                            context = context,
                            model = model,
                            modelManagerViewModel = modelManagerViewModel,
                            triggeredMessage = message,
                        )
                    },
                )
            }
        },
        onBenchmarkClicked = { _, _, _, _ -> },
        onResetSessionClicked = { model -> viewModel.resetSession(model = model) },
        showStopButtonInInputWhenInProgress = true,
        onStopButtonClicked = { model -> viewModel.stopResponse(model = model) },
        navigateUp = navigateUp,
        modifier = modifier.fillMaxSize()
    )
}