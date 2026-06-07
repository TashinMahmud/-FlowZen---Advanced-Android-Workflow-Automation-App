// File 4: CamFlowViewModel.kt
package com.google.ai.edge.gallery.ui.camflow
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.File
import java.lang.reflect.Type
import java.util.*
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.cancel
import kotlin.coroutines.cancellation.CancellationException

data class CamFlowUiState(
    val selectedImages: List<android.net.Uri> = emptyList(),
    val prompt: String = "",
    val destinationType: String = DestinationType.GMAIL,
    val destination: String = "",
    val attachImages: Boolean = true,
    val isProcessing: Boolean = false,
    val processingProgress: Float = 0f,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val modelInitialized: Boolean = false,
    val isInitializingModel: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val tgStatus: String = "Not connected",
    val tgConnecting: Boolean = false,
    val tgConnectedUsername: String? = null,
    val lastImageUri: Uri? = null,
    val isLoadingLastImage: Boolean = false,
    val lastImageError: String? = null,
    val todayImages: List<GalleryImage> = emptyList(),
    val todayImagesGrouped: Map<String?, List<GalleryImage>> = emptyMap(),
    val usingCachedResults: Boolean = false,
    val isLoadingTodayImages: Boolean = false,
    val todayImagesError: String? = null,
    val selectedTodayImages: Set<Uri> = emptySet(),
    val personGroups: List<PersonGroup> = emptyList(),
    val showAddReferenceDialog: Boolean = false,
    val referencePersonName: String = "",
    val tempReferenceImages: List<Uri> = emptyList()
)

class CamFlowViewModel(
    private val context: Context,
    private val modelManagerViewModel: ModelManagerViewModel
) : ViewModel() {
    private val BOT_TOKEN = "YOUR_TELEGRAM_BOT_TOKEN_HERE"
    private val BOT_USERNAME = "flow_aibot"
    private val camManager = CamflowImageManager(context)
    private val _uiState = MutableStateFlow(CamFlowUiState())
    val uiState = _uiState.asStateFlow()
    private val _sessions = MutableStateFlow<List<CamFlowSession>>(emptyList())
    val sessions = _sessions.asStateFlow()
    private var pendingToken: String? = null
    private val automationPrefs = context.getSharedPreferences("camflow_automation", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private var lastUpdateTime = 0L
    private val debounceDelay = 1000L
    private var isDestroyed = false

    private val contentObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            if (isDestroyed) return
            val now = System.currentTimeMillis()
            if (now - lastUpdateTime > debounceDelay) {
                lastUpdateTime = now
                autoUpdateLastImage()
                autoUpdateTodayImages()
            }
        }
    }

    init {
        Log.d("CamFlowViewModel", "Initializing CamFlowViewModel")
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )

        val selectedModel = modelManagerViewModel.uiState.value.selectedModel
        Log.d("CamFlowViewModel", "Selected model from ModelManager: ${selectedModel?.name ?: "NULL"}")
        selectedModel?.let { model ->
            Log.d("CamFlowViewModel", "Setting model preset on init: ${model.name}")
            camManager.setAiModel(model)
            Log.d("CamFlowViewModel", "Model preset successfully set: ${model.name}")
        } ?: run {
            Log.w("CamFlowViewModel", "No model selected in ModelManager")
        }

        Log.d("CamFlowViewModel", "Applying saved Telegram settings...")
        applySavedTelegramIfAny()
        updateLastImageUri(showLoading = false)
        updateTodayImages(showLoading = false)
        updatePersonGroups()
        Log.d("CamFlowViewModel", "CamFlowViewModel initialization complete")
    }

    override fun onCleared() {
        super.onCleared()
        Log.d("CamFlowViewModel", "CamFlowViewModel onCleared called")
        isDestroyed = true
        viewModelScope.cancel()
        try {
            context.contentResolver.unregisterContentObserver(contentObserver)
        } catch (e: Exception) {
            Log.e("CamFlowViewModel", "Error unregistering content observer", e)
        }
        try {
            camManager.close()
        } catch (e: Exception) {
            Log.e("CamFlowViewModel", "Error closing CamflowImageManager", e)
        }
        pendingToken = null
        System.gc()
        Log.d("CamFlowViewModel", "CamFlowViewModel resources released")
    }

    private fun applySavedTelegramIfAny() {
        if (isDestroyed) return
        try {
            val restoredChatId = TelegramDeepLinkHelper.loadChatId(context)
            val restoredUser = TelegramDeepLinkHelper.loadUsername(context)
            if (restoredChatId != null) {
                setDestination(DestinationType.TELEGRAM, restoredChatId.toString())
                _uiState.update {
                    it.copy(
                        tgStatus = "Connected to @${restoredUser ?: "user"} (id=$restoredChatId)",
                        tgConnectedUsername = restoredUser
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("CamFlowViewModel", "Error applying saved Telegram settings", e)
        }
    }

    private fun updateLastImageUri(showLoading: Boolean = false) {
        if (isDestroyed) return
        if (showLoading) {
            _uiState.update { it.copy(isLoadingLastImage = true, lastImageError = null) }
        }
        viewModelScope.launch {
            if (isDestroyed) return@launch
            try {
                val lastImageUri = camManager.getLastImageFromGallery()
                if (lastImageUri != null) {
                    camManager.saveLastImageUri(lastImageUri)
                }
                _uiState.update {
                    it.copy(
                        lastImageUri = lastImageUri,
                        isLoadingLastImage = false,
                        lastImageError = if (lastImageUri == null) "No image found" else null
                    )
                }
            } catch (e: Exception) {
                Log.e("CamFlowViewModel", "Error updating last image URI", e)
                _uiState.update {
                    it.copy(
                        isLoadingLastImage = false,
                        lastImageError = "Error loading image: ${e.message}"
                    )
                }
            }
        }
    }

    private fun autoUpdateLastImage() {
        if (isDestroyed) return
        updateLastImageUri(showLoading = false)
    }

    private fun updateTodayImages(showLoading: Boolean = false) {
        if (isDestroyed) return
        if (showLoading) {
            _uiState.update { it.copy(isLoadingTodayImages = true, todayImagesError = null) }
        }
        viewModelScope.launch {
            if (isDestroyed) return@launch
            try {
                val todayImages = camManager.getTodayImages()
                val groupedImages = todayImages.groupBy { it.matchedPerson }
                _uiState.update {
                    it.copy(
                        todayImages = todayImages,
                        todayImagesGrouped = groupedImages,
                        isLoadingTodayImages = false,
                        todayImagesError = if (todayImages.isEmpty()) "No images found for today" else null
                    )
                }
            } catch (e: Exception) {
                Log.e("CamFlowViewModel", "Error updating today's images", e)
                _uiState.update {
                    it.copy(
                        isLoadingTodayImages = false,
                        todayImagesError = "Error loading today's images: ${e.message}"
                    )
                }
            }
        }
    }

    private fun autoUpdateTodayImages() {
        if (isDestroyed) return
        viewModelScope.launch {
            if (isDestroyed) return@launch
            try {
                val todayImages = camManager.getTodayImages()
                val groupedImages = todayImages.groupBy { it.matchedPerson }
                _uiState.update {
                    it.copy(
                        todayImages = todayImages,
                        todayImagesGrouped = groupedImages,
                        isLoadingTodayImages = false,
                        todayImagesError = if (todayImages.isEmpty()) "No images found for today" else null
                    )
                }
            } catch (e: Exception) {
                Log.e("CamFlowViewModel", "Error auto-updating today's images", e)
                _uiState.update {
                    it.copy(
                        isLoadingTodayImages = false,
                        todayImagesError = "Error loading today's images: ${e.message}"
                    )
                }
            }
        }
    }

    private fun updatePersonGroups() {
        if (isDestroyed) return
        viewModelScope.launch {
            if (isDestroyed) return@launch
            try {
                val personGroups = camManager.faceGroupManager.getPersonGroups()
                _uiState.update {
                    it.copy(personGroups = personGroups)
                }
                updateTodayImagesGrouping()
            } catch (e: Exception) {
                Log.e("CamFlowViewModel", "Error updating person groups", e)
            }
        }
    }

    private fun updateTodayImagesGrouping() {
        if (isDestroyed) return
        viewModelScope.launch {
            if (isDestroyed) return@launch
            try {
                val todayImages = camManager.getTodayImages()
                val groupedImages = todayImages.groupBy { it.matchedPerson }
                _uiState.update {
                    it.copy(
                        todayImages = todayImages,
                        todayImagesGrouped = groupedImages,
                        isLoadingTodayImages = false,
                        todayImagesError = if (todayImages.isEmpty()) "No images found for today" else null
                    )
                }
            } catch (e: Exception) {
                Log.e("CamFlowViewModel", "Error updating today's images grouping", e)
                _uiState.update {
                    it.copy(
                        isLoadingTodayImages = false,
                        todayImagesError = "Error loading today's images: ${e.message}"
                    )
                }
            }
        }
    }

    fun toggleTodayImageSelection(uri: Uri) {
        if (isDestroyed) return
        _uiState.update { currentState ->
            val selectedSet = currentState.selectedTodayImages.toMutableSet()
            if (selectedSet.contains(uri)) {
                selectedSet.remove(uri)
            } else {
                selectedSet.add(uri)
            }
            currentState.copy(selectedTodayImages = selectedSet)
        }
    }

    fun clearTodayImageSelection() {
        if (isDestroyed) return
        _uiState.update { it.copy(selectedTodayImages = emptySet()) }
    }

    fun selectAllTodayImages() {
        if (isDestroyed) return
        _uiState.update { currentState ->
            currentState.copy(selectedTodayImages = currentState.todayImages.map { it.uri }.toSet())
        }
    }

    fun sendSelectedTodayImages() {
        if (isDestroyed) return
        viewModelScope.launch {
            if (isDestroyed) return@launch
            val selectedUris = _uiState.value.selectedTodayImages.toList()
            if (selectedUris.isEmpty()) {
                _uiState.update { it.copy(errorMessage = "No images selected") }
                return@launch
            }

            val currentDestination = _uiState.value.destination
            val currentDestinationType = _uiState.value.destinationType
            if (currentDestination.isBlank()) {
                _uiState.update { it.copy(errorMessage = "No destination set. Please configure Gmail or Telegram first.") }
                return@launch
            }

            camManager.clearImages()
            selectedUris.forEach { uri ->
                camManager.addImage(uri)
            }
            _uiState.update { it.copy(selectedImages = selectedUris) }
            camManager.setPrompt("Selected images from today's gallery")

            _uiState.update {
                it.copy(
                    isProcessing = true,
                    processingProgress = 0f,
                    errorMessage = null,
                    successMessage = null
                )
            }

            try {
                val sent = camManager.processAndSendResults(skipAnalysis = true) { progress ->
                    if (!isDestroyed) {
                        _uiState.update { it.copy(processingProgress = progress) }
                    }
                }
                if (!isDestroyed) {
                    if (sent) {
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                processingProgress = 1f,
                                successMessage = "Images sent successfully!",
                                errorMessage = null,
                                selectedTodayImages = emptySet()
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                errorMessage = "Failed to send images. Please check your connection and try again."
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CamFlowViewModel", "Error sending selected images: ${e.message}", e)
                if (!isDestroyed) {
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            errorMessage = "Error sending images: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    fun refreshLastImage() {
        if (isDestroyed) return
        updateLastImageUri(showLoading = true)
    }

    fun refreshTodayImages() {
        if (isDestroyed) return
        updateTodayImages(showLoading = true)
    }

    fun processTodayImagesForFaceRecognition() {
        if (isDestroyed) return
        viewModelScope.launch {
            if (isDestroyed) return@launch
            _uiState.update {
                it.copy(
                    isLoadingTodayImages = true,
                    todayImagesError = null
                )
            }
            try {
                val todayImages = camManager.getTodayImages()
                val groupedImages = todayImages.groupBy { it.matchedPerson }
                if (!isDestroyed) {
                    _uiState.update {
                        it.copy(
                            todayImages = todayImages,
                            todayImagesGrouped = groupedImages,
                            isLoadingTodayImages = false,
                            todayImagesError = if (todayImages.isEmpty()) "No images found for today" else null,
                            successMessage = "Face recognition completed for ${todayImages.size} images"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("CamFlowViewModel", "Error processing today's images for face recognition", e)
                if (!isDestroyed) {
                    _uiState.update {
                        it.copy(
                            isLoadingTodayImages = false,
                            todayImagesError = "Error processing images for face recognition: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    fun sendLastImage() {
        if (isDestroyed) return
        viewModelScope.launch {
            if (isDestroyed) return@launch
            val lastImageUri = _uiState.value.lastImageUri
            if (lastImageUri == null) {
                _uiState.update { it.copy(errorMessage = "No image available to send") }
                return@launch
            }

            val currentDestination = _uiState.value.destination
            val currentDestinationType = _uiState.value.destinationType
            if (currentDestination.isBlank()) {
                _uiState.update { it.copy(errorMessage = "No destination set. Please configure Gmail or Telegram first.") }
                return@launch
            }

            camManager.clearImages()
            camManager.addImage(lastImageUri)
            _uiState.update { it.copy(selectedImages = listOf(lastImageUri)) }
            camManager.setPrompt("Latest image from gallery")

            _uiState.update {
                it.copy(
                    isProcessing = true,
                    processingProgress = 0f,
                    errorMessage = null,
                    successMessage = null
                )
            }

            try {
                val sent = camManager.processAndSendResults(skipAnalysis = true) { progress ->
                    if (!isDestroyed) {
                        _uiState.update { it.copy(processingProgress = progress) }
                    }
                }
                if (!isDestroyed) {
                    if (sent) {
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                processingProgress = 1f,
                                successMessage = "Image sent successfully!",
                                errorMessage = null
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                errorMessage = "Failed to send image. Please check your connection and try again."
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CamFlowViewModel", "Error sending last image: ${e.message}", e)
                if (!isDestroyed) {
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            errorMessage = "Error sending image: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    fun addReferenceImage(uri: Uri, personName: String) {
        if (isDestroyed) return
        viewModelScope.launch {
            if (isDestroyed) return@launch
            val success = camManager.addReferenceImage(uri, personName)
            if (!isDestroyed) {
                if (success) {
                    _uiState.update {
                        it.copy(
                            successMessage = "Reference image added for $personName",
                            errorMessage = null,
                            showAddReferenceDialog = false,
                            referencePersonName = ""
                        )
                    }
                    updatePersonGroups()
                } else {
                    _uiState.update {
                        it.copy(
                            errorMessage = "Failed to add reference image for $personName",
                            successMessage = null
                        )
                    }
                }
            }
        }
    }

    fun addTempReferenceImage(uri: Uri) {
        if (isDestroyed) return
        _uiState.update { it.copy(tempReferenceImages = it.tempReferenceImages + uri) }
    }

    fun removeTempReferenceImage(uri: Uri) {
        if (isDestroyed) return
        _uiState.update { it.copy(tempReferenceImages = it.tempReferenceImages - uri) }
    }

    fun addReferenceImages(uris: List<Uri>, personName: String) {
        if (isDestroyed) return
        viewModelScope.launch {
            if (isDestroyed) return@launch
            var successCount = 0
            for (uri in uris) {
                if (isDestroyed) break
                val success = camManager.addReferenceImage(uri, personName)
                if (success) {
                    successCount++
                }
            }
            if (!isDestroyed) {
                if (successCount > 0) {
                    _uiState.update {
                        it.copy(
                            successMessage = "$successCount reference images added for $personName",
                            errorMessage = null,
                            showAddReferenceDialog = false,
                            referencePersonName = "",
                            tempReferenceImages = emptyList()
                        )
                    }
                    updatePersonGroups()
                } else {
                    _uiState.update {
                        it.copy(
                            errorMessage = "Failed to add reference images for $personName",
                            successMessage = null
                        )
                    }
                }
            }
        }
    }

    fun showAddReferenceDialog() {
        if (isDestroyed) return
        _uiState.update { it.copy(showAddReferenceDialog = true) }
    }

    fun hideAddReferenceDialog() {
        if (isDestroyed) return
        _uiState.update {
            it.copy(
                showAddReferenceDialog = false,
                referencePersonName = "",
                tempReferenceImages = emptyList()
            )
        }
    }

    fun updateReferencePersonName(name: String) {
        if (isDestroyed) return
        _uiState.update { it.copy(referencePersonName = name) }
    }

    fun deletePersonGroup(groupId: String) {
        if (isDestroyed) return
        viewModelScope.launch {
            if (isDestroyed) return@launch
            val success = camManager.faceGroupManager.deletePersonGroup(groupId)
            if (!isDestroyed) {
                if (success) {
                    _uiState.update {
                        it.copy(
                            successMessage = "Person deleted successfully",
                            errorMessage = null
                        )
                    }
                    updatePersonGroups()
                } else {
                    _uiState.update {
                        it.copy(
                            errorMessage = "Failed to delete person",
                            successMessage = null
                        )
                    }
                }
            }
        }
    }

    fun clearMessages() {
        if (isDestroyed) return
        _uiState.update {
            it.copy(
                errorMessage = null,
                successMessage = null
            )
        }
    }

    fun ensureRestoredDestination() {
        if (isDestroyed) return
        if (_uiState.value.destination.isBlank()) {
            applySavedTelegramIfAny()
        }
    }

    fun setAiModel(model: com.google.ai.edge.gallery.data.Model) {
        if (isDestroyed) return
        Log.d("CamFlowViewModel", "setAiModel called with model: ${model.name}")
        val currentModel = camManager.getCurrentModel()
        if (currentModel?.name == model.name) {
            Log.d("CamFlowViewModel", "Same model already set, skipping")
            return
        }
        if (currentModel == null || currentModel.name != model.name) {
            camManager.setAiModel(model)
            _uiState.update { it.copy(modelInitialized = false) }
            Log.d("CamFlowViewModel", "AI model set: ${model.name}")
        } else {
            Log.d("CamFlowViewModel", "AI model unchanged: ${model.name}")
        }
    }

    fun initializeModel() {
        if (isDestroyed) return
        Log.d("CamFlowViewModel", "initializeModel called")
        viewModelScope.launch {
            if (isDestroyed) return@launch
            if (_uiState.value.isInitializingModel) {
                Log.d("CamFlowViewModel", "Model initialization already in progress, skipping")
                return@launch
            }
            Log.d("CamFlowViewModel", "Starting model initialization...")
            _uiState.update { it.copy(isInitializingModel = true, errorMessage = null) }
            val initialized = camManager.initializeAiModel()
            Log.d("CamFlowViewModel", "camManager.initializeAiModel() returned: $initialized")
            if (!isDestroyed) {
                _uiState.update {
                    it.copy(
                        modelInitialized = initialized,
                        isInitializingModel = false,
                        errorMessage = if (!initialized) "Failed to initialize model" else null
                    )
                }
                if (initialized) {
                    Log.d("CamFlowViewModel", "Model initialized successfully")
                } else {
                    Log.e("CamFlowViewModel", "Model initialization failed")
                }
            }
        }
    }

    fun initializePreferences(context: Context) {
        if (isDestroyed) return
        viewModelScope.launch {
            if (isDestroyed) return@launch
            _uiState.update { it.copy(isLoading = true) }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun refreshWorkflowStatesFromPreferences(context: Context) {
        if (isDestroyed) return
        viewModelScope.launch {
            if (isDestroyed) return@launch
            _uiState.update { it.copy(isLoading = true) }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun addImage(uri: android.net.Uri, fromCamera: Boolean = false) {
        if (isDestroyed) return
        Log.d("CamFlowViewModel", "addImage called with URI: $uri, fromCamera: $fromCamera")
        val success = camManager.addImage(uri, fromCamera)
        Log.d("CamFlowViewModel", "camManager.addImage() returned: $success")
        if (success) {
            val images = camManager.getImages()
            Log.d("CamFlowViewModel", "Updated images list, count: ${images.size}")
            _uiState.update { it.copy(selectedImages = images) }
        } else {
            Log.e("CamFlowViewModel", "Failed to add image")
        }
    }

    fun removeImage(index: Int) {
        if (isDestroyed) return
        Log.d("CamFlowViewModel", "removeImage called with index: $index")
        camManager.removeImage(index)
        val images = camManager.getImages()
        Log.d("CamFlowViewModel", "Images after removal, count: ${images.size}")
        _uiState.update { it.copy(selectedImages = images) }
    }

    fun reorderImages(fromIndex: Int, toIndex: Int) {
        if (isDestroyed) return
        Log.d("CamFlowViewModel", "reorderImages called: fromIndex=$fromIndex, toIndex=$toIndex")
        camManager.reorderImages(fromIndex, toIndex)
        val images = camManager.getImages()
        Log.d("CamFlowViewModel", "Images after reorder, count: ${images.size}")
        _uiState.update { it.copy(selectedImages = images) }
    }

    fun setPrompt(prompt: String) {
        if (isDestroyed) return
        Log.d("CamFlowViewModel", "setPrompt called with: '$prompt'")
        camManager.setPrompt(prompt)
        _uiState.update { it.copy(prompt = prompt) }
        Log.d("CamFlowViewModel", "Prompt updated successfully")
    }

    fun setDestination(type: String, address: String) {
        if (isDestroyed) return
        Log.d("CamFlowViewModel", "setDestination called with type: $type, address: '$address'")
        var dest = address
        if (type == DestinationType.TELEGRAM && dest.isBlank()) {
            TelegramDeepLinkHelper.loadChatId(context)?.let { saved ->
                dest = saved.toString()
                Log.d("CamFlowViewModel", "Auto-filled Telegram chat_id from saved prefs: $dest")
            }
        }
        Log.d("CamFlowViewModel", "Setting destination in camManager: type=$type, dest='$dest'")
        camManager.setDestination(type, dest)
        _uiState.update { it.copy(destinationType = type, destination = dest) }
        Log.d("CamFlowViewModel", "Destination updated successfully")
    }

    fun setAttachImages(attach: Boolean) {
        if (isDestroyed) return
        camManager.setAttachImages(attach)
        _uiState.update { it.copy(attachImages = attach) }
    }

    fun beginTelegramInvite(): String {
        if (isDestroyed) return ""
        TelegramDeepLinkHelper.deleteWebhookIfNeeded(context, BOT_TOKEN)
        pendingToken = TelegramDeepLinkHelper.newToken()
        TelegramDeepLinkHelper.clearUpdateOffset(context)
        val link = TelegramDeepLinkHelper.buildInviteLink(BOT_USERNAME, pendingToken!!)
        _uiState.update {
            it.copy(
                tgStatus = "Waiting for recipient to press Start…",
                tgConnecting = true,
                destinationType = DestinationType.TELEGRAM
            )
        }
        return link
    }

    fun pollTelegramForStart() {
        if (isDestroyed) return
        val token = pendingToken ?: return
        if (_uiState.value.tgConnecting.not()) {
            _uiState.update { it.copy(tgConnecting = true) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (isDestroyed) return@launch
            var attempts = 0
            var connected: TelegramDeepLinkHelper.StartEvent? = null
            while (attempts < 180 && isActive && !isDestroyed) {
                runCatching {
                    val upd = TelegramDeepLinkHelper.getUpdates(context, BOT_TOKEN)
                    val found = TelegramDeepLinkHelper.findStartForToken(context, upd, token)
                    if (found != null) {
                        connected = found
                    }
                }.onFailure {
                    Log.d("CamFlowViewModel", "Polling error: ${it.message}")
                }
                if (connected != null) break
                attempts++
                delay(1000)
            }
            if (!isDestroyed && connected != null) {
                TelegramDeepLinkHelper.saveChatId(context, connected!!.chatId, connected!!.username)
                val chatIdStr = connected!!.chatId.toString()
                setDestination(DestinationType.TELEGRAM, chatIdStr)
                _uiState.update {
                    it.copy(
                        tgStatus = "Connected to @${connected!!.username ?: "user"} (id=${connected!!.chatId})",
                        tgConnecting = false,
                        tgConnectedUsername = connected!!.username,
                        successMessage = "Telegram connected!"
                    )
                }
            } else if (!isDestroyed) {
                _uiState.update {
                    it.copy(
                        tgStatus = "No response. Ask them to tap Start in @$BOT_USERNAME",
                        tgConnecting = false,
                        errorMessage = "Could not connect within the time window."
                    )
                }
            }
        }
    }

    fun disconnectTelegram() {
        if (isDestroyed) return
        TelegramDeepLinkHelper.clearReceiver(context)
        _uiState.update {
            it.copy(
                destination = "",
                destinationType = DestinationType.TELEGRAM,
                tgStatus = "Not connected",
                tgConnecting = false,
                tgConnectedUsername = null
            )
        }
    }

    fun saveAutomationSettings() {
        if (isDestroyed) return
        Log.d("CamFlowViewModel", "Saving automation settings")
        automationPrefs.edit()
            .putString("prompt", _uiState.value.prompt)
            .putString("destinationType", _uiState.value.destinationType)
            .putString("destination", _uiState.value.destination)
            .putBoolean("attachImages", _uiState.value.attachImages)
            .apply()

        val currentModel = camManager.getCurrentModel()
        if (currentModel != null) {
            automationPrefs.edit()
                .putString("modelName", currentModel.name)
                .apply()
            Log.d("CamFlowViewModel", "Automation settings saved with model: ${currentModel.name}")
        } else {
            automationPrefs.edit()
                .remove("modelName")
                .apply()
            Log.d("CamFlowViewModel", "Automation settings saved without model")
        }
    }

    fun loadAutomationSettings(): GalleryMonitorService.AutomationSettings {
        if (isDestroyed) {
            return GalleryMonitorService.AutomationSettings(
                prompt = "Analyze this image",
                destinationType = DestinationType.GMAIL,
                destination = "",
                attachImages = true,
                model = null
            )
        }
        Log.d("CamFlowViewModel", "Loading automation settings")
        val modelName = automationPrefs.getString("modelName", null)
        var model: com.google.ai.edge.gallery.data.Model? = null

        if (modelName != null) {
            model = modelManagerViewModel.uiState.value.selectedModel?.takeIf { it.name == modelName }
            if (model == null) {
                val modelManagerState = modelManagerViewModel.uiState.value
                val availableModels = try {
                    val modelsField = modelManagerState::class.java.getDeclaredField("models")
                    modelsField.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    modelsField.get(modelManagerState) as? List<com.google.ai.edge.gallery.data.Model> ?: emptyList()
                } catch (e: Exception) {
                    try {
                        val getModelsMethod = modelManagerState::class.java.getDeclaredMethod("getModels")
                        getModelsMethod.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        getModelsMethod.invoke(modelManagerState) as? List<com.google.ai.edge.gallery.data.Model> ?: emptyList()
                    } catch (e2: Exception) {
                        Log.e("CamFlowViewModel", "Could not access models list", e2)
                        emptyList()
                    }
                }
                model = availableModels.find { availableModel -> availableModel.name == modelName }
            }
        }

        return GalleryMonitorService.AutomationSettings(
            prompt = automationPrefs.getString("prompt", "Analyze this image") ?: "Analyze this image",
            destinationType = automationPrefs.getString("destinationType", DestinationType.GMAIL) ?: DestinationType.GMAIL,
            destination = automationPrefs.getString("destination", "") ?: "",
            attachImages = automationPrefs.getBoolean("attachImages", true),
            model = model
        )
    }

    fun processImages() {
        if (isDestroyed) return
        viewModelScope.launch {
            if (isDestroyed) return@launch
            val s = _uiState.value
            if (s.selectedImages.isEmpty()) {
                _uiState.update { it.copy(errorMessage = "Please select at least one image.") }
                return@launch
            }
            if (s.prompt.isBlank()) {
                _uiState.update { it.copy(errorMessage = "Please enter an analysis prompt.") }
                return@launch
            }
            if (s.destinationType == DestinationType.TELEGRAM && s.destination.isBlank()) {
                _uiState.update { it.copy(errorMessage = "Connect Telegram first (share invite).") }
                return@launch
            }
            if (s.destinationType == DestinationType.GMAIL && s.destination.isBlank()) {
                _uiState.update { it.copy(errorMessage = "Please enter a destination email.") }
                return@launch
            }
            val currentModel = camManager.getCurrentModel()
            if (currentModel == null) {
                _uiState.update { it.copy(errorMessage = "No model selected.") }
                return@launch
            }

            val taskId = CamflowTaskRegistry.register(
                CamflowTaskRegistry.TaskSpec(
                    model = currentModel,
                    imageUris = camManager.getImages(),
                    prompt = s.prompt,
                    destinationType = s.destinationType,
                    destination = s.destination,
                    attachImages = s.attachImages
                )
            )

            try {
                CamFlowForegroundService.start(context, taskId)
                _uiState.update {
                    it.copy(
                        isProcessing = true,
                        processingProgress = 0f,
                        successMessage = "Processing started in background.",
                        errorMessage = null
                    )
                }
                monitorProcessingCompletion(taskId)
            } catch (t: Throwable) {
                _uiState.update { it.copy(errorMessage = "Failed to start background task: ${t.message}") }
            }
        }
    }

    private fun monitorProcessingCompletion(taskId: String) {
        if (isDestroyed) return
        viewModelScope.launch {
            try {
                while (isActive && !isDestroyed) {
                    delay(1000)
                    val progress = context.getSharedPreferences("camflow_progress", Context.MODE_PRIVATE)
                        .getFloat("progress_$taskId", 0f)
                    Log.d("CamFlowViewModel", "Progress update - taskId: $taskId, progress: $progress")

                    _uiState.update { it.copy(processingProgress = progress) }

                    val task = CamflowTaskRegistry.get(taskId)
                    if (task == null) {
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                processingProgress = 1f,
                                successMessage = "Processing completed successfully! Check your email for results.",
                                errorMessage = null
                            )
                        }
                        context.getSharedPreferences("camflow_progress", Context.MODE_PRIVATE)
                            .edit().remove("progress_$taskId").apply()
                        break
                    }
                }
            } catch (e: CancellationException) {
                Log.d("CamFlowViewModel", "Processing monitoring cancelled")
            } catch (e: Exception) {
                Log.e("CamFlowViewModel", "Error monitoring processing", e)
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = "Error monitoring processing: ${e.message}"
                    )
                }
            }
        }
    }

    fun loadHistory() {
        if (isDestroyed) return
        viewModelScope.launch {
            if (isDestroyed) return@launch
            val loaded = camManager.loadAllSessions()
            _sessions.value = loaded
        }
    }

    fun deleteSession(id: String) {
        if (isDestroyed) return
        viewModelScope.launch {
            if (isDestroyed) return@launch
            if (camManager.deleteSession(id)) {
                _sessions.value = _sessions.value.filterNot { it.id == id }
            }
        }
    }

    fun clearHistory() {
        if (isDestroyed) return
        viewModelScope.launch {
            if (isDestroyed) return@launch
            camManager.clearAllSessions()
            _sessions.value = emptyList()
        }
    }

    fun clearImages(keepDestination: Boolean = false) {
        if (isDestroyed) return
        camManager.clearImages()
        _uiState.update { prev ->
            prev.copy(
                selectedImages = emptyList(),
                prompt = "",
                successMessage = null,
                errorMessage = null,
                processingProgress = 0f,
                destination = if (keepDestination) prev.destination else "",
                destinationType = if (keepDestination) prev.destinationType else DestinationType.GMAIL
            )
        }
    }

    fun createImageFile(): File {
        if (isDestroyed) return File(context.cacheDir, "temp.jpg")
        return camManager.createImageFile()
    }

    fun getUriForFile(file: File): android.net.Uri {
        if (isDestroyed) return Uri.EMPTY
        return camManager.getUriForFile(file)
    }

    companion object {
        fun provideFactory(
            context: Context,
            modelManagerViewModel: ModelManagerViewModel
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return CamFlowViewModel(context, modelManagerViewModel) as T
            }
        }
    }
}