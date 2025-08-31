// File 3: CamflowImageManager.kt
package com.google.ai.edge.gallery.ui.camflow
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private const val TAG = "CamflowImageManager"

object DestinationType {
    const val GMAIL = "gmail"
    const val TELEGRAM = "telegram"
}

data class ImageAnalysisResult(
    val imageUri: Uri,
    val analysis: String,
    val timestamp: Long
)

data class CamFlowSession(
    val id: String,
    val timestamp: Long,
    val modelName: String,
    val prompt: String,
    val destinationType: String,
    val destination: String,
    val attachImages: Boolean,
    val imageUris: List<String>,
    val analyses: List<String>
)

data class GalleryImage(
    val uri: Uri,
    val name: String,
    val dateAdded: Long,
    val size: Long,
    val hasFaces: Boolean = false,
    val matchedPerson: String? = null
)

class CamflowImageManager(private val context: Context) {
    companion object {
        private const val MAX_IMAGES = 15
        private const val MAX_ANALYSIS_WORDS = 180
        private const val TELEGRAM_BOT_TOKEN = "8123513934:AAHybG4oY02mdAwcr8odWwjtD_X5eoOcpvA"
        private const val SESSIONS_DIR = "camflow_sessions"
        private const val MAX_IMAGE_SIZE_KB = 1024
        private const val LAST_IMAGE_PREFS = "last_image_prefs"
        private const val KEY_LAST_IMAGE_URI = "last_image_uri"
    }

    private val imageUris = mutableListOf<Uri>()
    private var destinationType: String = DestinationType.GMAIL
    private var destination: String = ""
    private var prompt: String = ""
    private var attachImages: Boolean = true
    private val analysisResults = mutableListOf<ImageAnalysisResult>()
    private var aiModel: Model? = null
    private var aiModelInstance: Any? = null
    private val inferenceMutex = Mutex()
    private var isInitialized = false
    private var gmailService: Gmail? = null
    private var isClosed = false

    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        FaceDetection.getClient(options)
    }

    public val faceGroupManager: FaceGroupManager by lazy { FaceGroupManager(context) }

    fun getCurrentModel(): Model? = aiModel

    fun setAiModel(model: Model) {
        if (isClosed) return
        aiModel = model
        isInitialized = false
        aiModelInstance = null
        Log.d(TAG, "AI Model set for image analysis: ${model.name}")
    }

    @WorkerThread
    suspend fun initializeAiModel(): Boolean = withContext(Dispatchers.IO) {
        if (isClosed) {
            Log.e(TAG, "Cannot initialize model: resources already released")
            return@withContext false
        }

        try {
            val model = aiModel ?: run {
                Log.e(TAG, "No AI model set for image analysis")
                return@withContext false
            }

            if (isInitialized && aiModelInstance != null) {
                Log.d(TAG, "AI model already initialized")
                return@withContext true
            }

            Log.d(TAG, "Initializing AI model: ${model.name}")
            var initError: String? = null
            LlmChatModelHelper.initialize(context, model) { error ->
                initError = error
                if (error.isNotEmpty()) Log.e(TAG, "Model initialization error: $error")
            }

            val timeoutMs = 15000L
            var waited = 0L
            val step = 150L
            while (model.instance == null && waited < timeoutMs) {
                delay(step)
                waited += step
            }

            if (model.instance == null) {
                Log.e(TAG, "Failed to initialize AI model within timeout. Last error: ${initError ?: "unknown"}")
                return@withContext false
            }

            aiModelInstance = model.instance
            isInitialized = true
            Log.d(TAG, "AI model initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AI model: ${e.message}", e)
            false
        }
    }

    fun addImage(uri: Uri, fromCamera: Boolean = false): Boolean {
        if (isClosed) {
            Log.e(TAG, "Cannot add image: resources already released")
            return false
        }

        if (imageUris.size >= MAX_IMAGES) {
            Log.w(TAG, "Maximum number of images ($MAX_IMAGES) reached")
            return false
        }

        val persistentUri = createPersistentCopy(uri) ?: run {
            Log.e(TAG, "Failed to create persistent copy for image")
            return false
        }

        imageUris.add(persistentUri)
        if (fromCamera) saveImageToGallery(persistentUri)
        Log.d(TAG, "Added image (persistent): $persistentUri, total images: ${imageUris.size}")
        return true
    }

    fun removeImage(index: Int) {
        if (isClosed) return

        if (index in imageUris.indices) {
            val removedUri = imageUris.removeAt(index)
            try {
                val path = removedUri.path
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting persistent file: ${e.message}", e)
            }
            Log.d(TAG, "Removed image: $removedUri, remaining images: ${imageUris.size}")
        }
    }

    fun reorderImages(fromIndex: Int, toIndex: Int) {
        if (isClosed) return

        if (fromIndex in imageUris.indices && toIndex in imageUris.indices) {
            val uri = imageUris.removeAt(fromIndex)
            imageUris.add(toIndex, uri)
            Log.d(TAG, "Reordered image from $fromIndex to $toIndex")
        }
    }

    fun setDestination(type: String, address: String) {
        if (isClosed) return
        destinationType = type
        destination = address
        Log.d(TAG, "Set destination: $type - $address")
    }

    fun setPrompt(text: String) {
        if (isClosed) return
        prompt = text
        Log.d(TAG, "Set prompt: $text")
    }

    fun setAttachImages(attach: Boolean) {
        if (isClosed) return
        attachImages = attach
        Log.d(TAG, "Set attach images: $attach")
    }

    fun getImages(): List<Uri> = imageUris.toList()

    fun getAnalysisResults(): List<ImageAnalysisResult> = analysisResults.toList()

    fun clearImages() {
        if (isClosed) return

        imageUris.forEach { uri ->
            try {
                val path = uri.path
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting persistent file: ${e.message}", e)
            }
        }
        imageUris.clear()
        analysisResults.clear()
        Log.d(TAG, "Cleared all images")
    }

    private suspend fun imageContainsFaces(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext false

        var bitmap: Bitmap? = null
        var resizedBitmap: Bitmap? = null

        try {
            bitmap = loadBitmapSafely(uri) ?: return@withContext false
            resizedBitmap = resizeBitmapForFaceDetection(bitmap)
            val image = InputImage.fromBitmap(resizedBitmap, 0)
            val faces = faceDetector.process(image).await()
            faces.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting faces in image: ${e.message}", e)
            false
        } finally {
            resizedBitmap?.recycle()
            if (resizedBitmap != bitmap) {
                bitmap?.recycle()
            }
        }
    }

    private fun resizeBitmapForFaceDetection(bitmap: Bitmap): Bitmap {
        val maxSize = 1024
        val width = bitmap.width
        val height = bitmap.height
        val ratio: Float = width.toFloat() / height.toFloat()
        var newWidth = maxSize
        var newHeight = maxSize
        if (width > height) newHeight = (maxSize / ratio).toInt() else newWidth = (maxSize * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    suspend fun getLastImageFromGallery(): Uri? = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext null

        try {
            Log.d(TAG, "Getting last image from gallery")
            var latestUri: Uri? = null
            var latestDate: Long = 0

            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATA
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

            val cursor = context.contentResolver.query(uri, projection, null, null, sortOrder)
            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateModifiedColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val dataColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                if (it.moveToFirst()) {
                    do {
                        val id = it.getLong(idColumn)
                        val dateModified = it.getLong(dateModifiedColumn) * 1000
                        val dataPath = it.getString(dataColumn)
                        Log.d(TAG, "Found image: ID=$id, DateModified=$dateModified, Path=$dataPath")

                        if (dateModified > latestDate) {
                            latestDate = dateModified
                            latestUri = ContentUris.withAppendedId(uri, id)
                            Log.d(TAG, "New latest image: $latestUri")
                        }
                    } while (it.moveToNext())
                }
            }

            if (latestUri != null) {
                Log.d(TAG, "Returning latest image URI: $latestUri")
                return@withContext latestUri
            } else {
                Log.d(TAG, "No images found in gallery")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last image from gallery: ${e.message}", e)
            return@withContext null
        }
    }

    suspend fun getTodayImages(): List<GalleryImage> = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext emptyList()

        try {
            Log.d(TAG, "Getting today's images with faces from gallery")
            return@withContext faceGroupManager.processTodayImagesForRecognition(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting today's images: ${e.message}", e)
            return@withContext emptyList()
        }
    }

    fun saveLastImageUri(uri: Uri) {
        if (isClosed) return

        try {
            Log.d(TAG, "Saving last image URI: $uri")
            val prefs = context.getSharedPreferences(LAST_IMAGE_PREFS, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_LAST_IMAGE_URI, uri.toString()).apply()
            Log.d(TAG, "Last image URI saved successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving last image URI: ${e.message}", e)
        }
    }

    fun loadLastImageUri(): Uri? {
        if (isClosed) return null

        return try {
            Log.d(TAG, "Loading last image URI")
            val prefs = context.getSharedPreferences(LAST_IMAGE_PREFS, Context.MODE_PRIVATE)
            val uriString = prefs.getString(KEY_LAST_IMAGE_URI, null)
            if (uriString != null) {
                val uri = Uri.parse(uriString)
                Log.d(TAG, "Loaded last image URI: $uri")
                uri
            } else {
                Log.d(TAG, "No last image URI found")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading last image URI: ${e.message}", e)
            null
        }
    }

    suspend fun addReferenceImage(uri: Uri, personName: String): Boolean = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext false

        return@withContext try {
            faceGroupManager.addReferenceImage(uri, personName)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding reference image: ${e.message}", e)
            false
        }
    }

    @WorkerThread
    suspend fun processAndSendResults(skipAnalysis: Boolean = false, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (isClosed) {
            Log.e(TAG, "Cannot process images: resources already released")
            return@withContext false
        }

        try {
            if (imageUris.isEmpty()) {
                Log.e(TAG, "No images to process")
                return@withContext false
            }

            if (!skipAnalysis && prompt.isEmpty()) {
                Log.e(TAG, "No prompt provided")
                return@withContext false
            }

            if (destination.isEmpty()) {
                Log.e(TAG, "No destination provided")
                return@withContext false
            }

            if (!skipAnalysis && (!isInitialized || aiModelInstance == null)) {
                Log.w(TAG, "Model not initialized; attempting initialization")
                if (!initializeAiModel()) return@withContext false
            }

            if (destinationType == DestinationType.GMAIL && gmailService == null) {
                if (!initializeGmailService()) {
                    Log.e(TAG, "Failed to initialize Gmail service")
                    return@withContext false
                }
            }

            Log.d(TAG, "Starting image processing for ${imageUris.size} images")
            analysisResults.clear()
            val totalImages = imageUris.size

            for ((index, uri) in imageUris.withIndex()) {
                try {
                    Log.d(TAG, "Processing image ${index + 1}/$totalImages")
                    val analysis = if (skipAnalysis) {
                        "Scheduled image from gallery"
                    } else {
                        analyzeImage(uri)
                    }
                    analysisResults.add(ImageAnalysisResult(uri, analysis, System.currentTimeMillis()))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process image ${index + 1}: ${e.message}", e)
                    analysisResults.add(ImageAnalysisResult(uri, "Analysis failed: ${e.message}", System.currentTimeMillis()))
                } finally {
                    val analysisProgress = (index + 1) / totalImages.toFloat()
                    onProgress(analysisProgress * 0.8f)
                }
                if (index < totalImages - 1) delay(300)
            }

            val sent = when (destinationType) {
                DestinationType.GMAIL -> sendToGmail { progress ->
                    onProgress(0.8f + progress * 0.2f)
                }
                DestinationType.TELEGRAM -> sendToTelegram { progress ->
                    onProgress(0.8f + progress * 0.2f)
                }
                else -> false
            }

            onProgress(1f)
            if (sent) saveCurrentSession()
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Error in processAndSendResults: ${e.message}", e)
            false
        }
    }

    @WorkerThread
    private suspend fun analyzeImage(imageUri: Uri): String = inferenceMutex.withLock {
        if (isClosed) return@withLock "Error: Resources already released"

        var bitmap: Bitmap? = null
        var resizedBitmap: Bitmap? = null

        return try {
            Log.d(TAG, "Starting image analysis for URI: $imageUri")
            bitmap = loadBitmapSafely(imageUri) ?: return@withLock "Error: Could not load image"
            Log.d(TAG, "Bitmap loaded successfully, size: ${bitmap.width}x${bitmap.height}")

            resizedBitmap = resizeBitmap(bitmap, 1024)
            Log.d(TAG, "Bitmap resized to: ${resizedBitmap.width}x${resizedBitmap.height}")

            val fullPrompt = """
        Analyze this image based on the following instruction: $prompt
        Provide a concise analysis in $MAX_ANALYSIS_WORDS words or less.
        Focus on the key elements relevant to the instruction.
      """.trimIndent()

            Log.d(TAG, "Full prompt prepared: '$fullPrompt'")
            var analysis = ""
            var inferenceCompleted = false

            suspendCancellableCoroutine<String> { continuation ->
                continuation.invokeOnCancellation {
                    Log.d(TAG, "Inference cancelled")
                    inferenceCompleted = true
                }

                Log.d(TAG, "Starting LLM inference...")
                LlmChatModelHelper.runInference(
                    model = aiModel!!,
                    input = fullPrompt,
                    images = listOf(resizedBitmap),
                    resultListener = { partial, done ->
                        Log.d(TAG, "Result listener called - partial: '${partial.take(50)}...', done: $done")
                        analysis += partial
                        if (done) {
                            Log.d(TAG, "Inference completed, final analysis length: ${analysis.length}")
                            inferenceCompleted = true
                            if (continuation.isActive) {
                                val cleaned = cleanAnalysis(analysis)
                                Log.d(TAG, "Cleaned analysis: '${cleaned.take(100)}...'")
                                continuation.resume(cleaned)
                            }
                        }
                    },
                    cleanUpListener = {
                        Log.d(TAG, "Cleanup listener called, inferenceCompleted: $inferenceCompleted")
                        if (!inferenceCompleted && continuation.isActive) {
                            Log.d(TAG, "Resuming with error due to cleanup")
                            continuation.resume("Error: Inference was interrupted")
                        }
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing image: ${e.message}", e)
            "Error: ${e.message}"
        } finally {
            resizedBitmap?.recycle()
            if (resizedBitmap != bitmap) {
                bitmap?.recycle()
            }
        }
    }

    private fun loadBitmapSafely(uri: Uri): Bitmap? {
        if (isClosed) return null

        var inputStream: InputStream? = null
        var exifInputStream: InputStream? = null
        var bitmap: Bitmap? = null
        var rotatedBitmap: Bitmap? = null

        try {
            inputStream = context.contentResolver.openInputStream(uri)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)

            val sampleSize = calculateInSampleSize(options, 2048, 2048)
            options.inSampleSize = sampleSize
            options.inJustDecodeBounds = false

            inputStream?.close()
            inputStream = null

            inputStream = context.contentResolver.openInputStream(uri)
            bitmap = BitmapFactory.decodeStream(inputStream, null, options)

            if (bitmap == null) {
                return null
            }

            inputStream?.close()
            inputStream = null

            exifInputStream = context.contentResolver.openInputStream(uri)
            if (exifInputStream != null) {
                try {
                    val exif = ExifInterface(exifInputStream)
                    val orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                    rotatedBitmap = when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 ->
                            rotateBitmap(bitmap, 90)
                        ExifInterface.ORIENTATION_ROTATE_180 ->
                            rotateBitmap(bitmap, 180)
                        ExifInterface.ORIENTATION_ROTATE_270 ->
                            rotateBitmap(bitmap, 270)
                        else -> bitmap
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading EXIF data", e)
                    rotatedBitmap = bitmap
                } finally {
                    exifInputStream.close()
                    exifInputStream = null
                }
            } else {
                rotatedBitmap = bitmap
            }

            return rotatedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap: ${e.message}", e)
            return null
        } finally {
            try {
                inputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing input stream", e)
            }
            try {
                exifInputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing EXIF input stream", e)
            }
            if (rotatedBitmap != null && rotatedBitmap != bitmap) {
                bitmap?.recycle()
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

    private fun rotateBitmap(source: Bitmap, angle: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle.toFloat())
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val ratio: Float = w.toFloat() / h.toFloat()
        var newW = maxSize
        var newH = maxSize
        if (w > h) newH = (maxSize / ratio).toInt() else newW = (maxSize * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun cleanAnalysis(analysis: String): String {
        return analysis
            .replace(Regex("^(Analysis:|Result:|Summary:)\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^The image shows?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^This image contains?\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf {
                it.isNotEmpty() && !it.contains("Error:", true) && !it.contains("OUT_OF_RANGE", true)
            } ?: "No meaningful analysis generated"
    }

    private fun initializeGmailService(): Boolean {
        if (isClosed) return false

        return try {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: run {
                Log.e(TAG, "No Google account signed in")
                return false
            }

            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf("https://www.googleapis.com/auth/gmail.send")
            ).apply { selectedAccount = account.account }

            gmailService = Gmail.Builder(
                com.google.api.client.http.javanet.NetHttpTransport(),
                com.google.api.client.json.jackson2.JacksonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("Camflow").build()

            Log.d(TAG, "Gmail service initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Gmail service: ${e.message}", e)
            false
        }
    }

    @WorkerThread
    private suspend fun sendToGmail(onAttachmentProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext false

        try {
            if (gmailService == null) return@withContext false

            val to = destination
            val subject = "Image Analysis Results"
            val body = buildString {
                append("IMAGE ANALYSIS RESULTS\n")
                append("=".repeat(50)).append("\n")
                append("Generated on: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
                append("Instruction: $prompt\n")
                append("Total images processed: ${analysisResults.size}\n\n")
                analysisResults.forEachIndexed { index, result ->
                    append("Image ${index + 1}:\n")
                    append("Analysis: ${result.analysis}\n")
                    append("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(result.timestamp))}\n")
                    append("-".repeat(30)).append("\n\n")
                }
                append("Sent via Camflow App\n")
            }

            val boundary = "CamflowBoundary${System.currentTimeMillis()}"
            val newLine = "\r\n"
            val messageBuilder = StringBuilder()
            messageBuilder.append("To: $to$newLine")
            messageBuilder.append("Subject: $subject$newLine")
            messageBuilder.append("MIME-Version: 1.0$newLine")
            messageBuilder.append("Content-Type: multipart/mixed; boundary=\"$boundary\"$newLine")
            messageBuilder.append(newLine)

            messageBuilder.append("--$boundary$newLine")
            messageBuilder.append("Content-Type: text/plain; charset=UTF-8$newLine")
            messageBuilder.append("Content-Transfer-Encoding: 7bit$newLine")
            messageBuilder.append(newLine)
            messageBuilder.append(body)
            messageBuilder.append("$newLine$newLine")

            if (attachImages) {
                val totalAttachments = analysisResults.size
                var attachmentsCompleted = 0

                analysisResults.forEachIndexed { index, result ->
                    try {
                        val compressedBytes = compressImage(result.imageUri, MAX_IMAGE_SIZE_KB)
                        if (compressedBytes != null) {
                            val encodedImage = Base64.encodeToString(compressedBytes, Base64.DEFAULT)
                            messageBuilder.append("--$boundary$newLine")
                            messageBuilder.append("Content-Type: image/jpeg$newLine")
                            messageBuilder.append("Content-Transfer-Encoding: base64$newLine")
                            messageBuilder.append("Content-Disposition: attachment; filename=\"image_${index + 1}.jpg\"$newLine")
                            messageBuilder.append(newLine)
                            encodedImage.chunked(76).forEach { line -> messageBuilder.append(line).append(newLine) }
                            messageBuilder.append(newLine)
                        } else {
                            Log.w(TAG, "Skipping attachment for image $index due to compression failure")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error attaching image ${index + 1}: ${e.message}", e)
                    } finally {
                        attachmentsCompleted++
                        onAttachmentProgress(attachmentsCompleted / totalAttachments.toFloat())
                    }
                }
            }

            messageBuilder.append("--$boundary--$newLine")

            val encodedMessage = Base64.encodeToString(
                messageBuilder.toString().toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP
            )

            val message = Message().apply { raw = encodedMessage }
            gmailService!!.users().messages().send("me", message).execute()
            Log.d(TAG, "Results sent to Gmail successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send results to Gmail: ${e.message}", e)
            false
        }
    }

    @WorkerThread
    private suspend fun sendToTelegram(onAttachmentProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext false

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        suspend fun sendWithRetry(request: Request, maxRetries: Int = 3): Boolean {
            var retryCount = 0
            while (retryCount < maxRetries) {
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) return true
                        else {
                            Log.w(TAG, "Attempt $retryCount failed: ${response.code} ${response.message}")
                            if (response.code in 400..499) return false
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Attempt $retryCount error: ${e.message}")
                }
                retryCount++
                if (retryCount < maxRetries) {
                    delay(1000L * retryCount)
                }
            }
            return false
        }

        try {
            val messageBuilder = StringBuilder()
                .append("<b>IMAGE ANALYSIS RESULTS</b>\n")
                .append("=".repeat(50)).append("\n")
                .append("Generated on: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
                .append("Instruction: ").append(escapeHtml(prompt)).append("\n")
                .append("Total images processed: ${analysisResults.size}\n\n")
            analysisResults.forEachIndexed { index, result ->
                messageBuilder.append("<b>Image ${index + 1}:</b>\n")
                    .append(escapeHtml(result.analysis)).append("\n")
                    .append("<i>Processed: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(result.timestamp))}</i>\n")
                    .append("-".repeat(30)).append("\n\n")
            }
            messageBuilder.append("<i>Sent via Camflow App</i>")

            val textUrl = "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage"
            val textBody = FormBody.Builder()
                .add("chat_id", destination)
                .add("text", messageBuilder.toString())
                .add("parse_mode", "HTML")
                .build()
            val textRequest = Request.Builder().url(textUrl).post(textBody).build()

            if (!sendWithRetry(textRequest)) {
                Log.e(TAG, "Failed to send text message to Telegram")
                return@withContext false
            }

            if (attachImages) {
                val totalAttachments = analysisResults.size
                var attachmentsCompleted = 0

                analysisResults.forEachIndexed { index, result ->
                    try {
                        val compressedBytes = compressImage(result.imageUri, MAX_IMAGE_SIZE_KB)
                        if (compressedBytes != null) {
                            val imageBody = MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addFormDataPart("chat_id", destination)
                                .addFormDataPart(
                                    "photo",
                                    "image_${index + 1}.jpg",
                                    compressedBytes.toRequestBody("image/jpeg".toMediaType())
                                )
                                .addFormDataPart("caption", "Analysis: ${escapeHtml(result.analysis)}")
                                .build()
                            val imageRequest = Request.Builder()
                                .url("https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendPhoto")
                                .post(imageBody)
                                .build()
                            if (!sendWithRetry(imageRequest)) {
                                Log.e(TAG, "Failed to send image $index to Telegram")
                            } else {
                                Log.d(TAG, "Image $index sent to Telegram successfully")
                            }
                            delay(300)
                        } else {
                            Log.w(TAG, "Skipping image $index due to compression failure")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending image $index to Telegram: ${e.message}", e)
                    } finally {
                        attachmentsCompleted++
                        onAttachmentProgress(attachmentsCompleted / totalAttachments.toFloat())
                    }
                }
            }

            Log.d(TAG, "Results sent to Telegram successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send results to Telegram: ${e.message}", e)
            false
        }
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun saveImageToGallery(uri: Uri): Boolean {
        if (isClosed) return false

        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        var bitmap: Bitmap? = null

        return try {
            inputStream = context.contentResolver.openInputStream(uri)
            bitmap = BitmapFactory.decodeStream(inputStream)
            if (bitmap != null) {
                val filename = "CAMFLOW_${System.currentTimeMillis()}.jpg"
                outputStream =
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val cr = context.contentResolver
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Camflow")
                        }
                        val imageUri = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        cr.openOutputStream(imageUri!!) as FileOutputStream
                    } else {
                        val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        val camflowDir = File(imagesDir, "Camflow")
                        camflowDir.mkdirs()
                        val file = File(camflowDir, filename)
                        FileOutputStream(file)
                    }
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                outputStream?.close()
                outputStream = null
                Log.d(TAG, "Image saved to gallery: $filename")

                kotlinx.coroutines.GlobalScope.launch {
                    try {
                        faceGroupManager.processImageAndSaveToAlbum(uri)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing image for face grouping: ${e.message}", e)
                    }
                }
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Error saving image to gallery: ${e.message}", e)
            false
        } finally {
            try {
                inputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing input stream", e)
            }
            try {
                outputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing output stream", e)
            }
            bitmap?.recycle()
        }
    }

    fun createImageFile(): File {
        if (isClosed) return File(context.cacheDir, "temp.jpg")

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("CAMFLOW_${timeStamp}_", ".jpg", storageDir)
    }

    fun getUriForFile(file: File): Uri {
        if (isClosed) return Uri.EMPTY
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun compressImage(uri: Uri, maxSizeKB: Int = 1024): ByteArray? {
        if (isClosed) return null

        var inputStream: InputStream? = null
        var originalBitmap: Bitmap? = null
        var outputStream: ByteArrayOutputStream? = null

        return try {
            inputStream = context.contentResolver.openInputStream(uri)
            originalBitmap = BitmapFactory.decodeStream(inputStream)
            outputStream = ByteArrayOutputStream()

            var quality = 90
            originalBitmap?.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

            while (outputStream?.size() ?: 0 > maxSizeKB * 1024 && quality > 10) {
                outputStream?.reset()
                quality -= 10
                originalBitmap?.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            }

            outputStream?.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Image compression failed for $uri: ${e.message}", e)
            null
        } finally {
            try {
                inputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing input stream", e)
            }
            try {
                outputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing output stream", e)
            }
            originalBitmap?.recycle()
        }
    }

    private fun createPersistentCopy(uri: Uri): Uri? {
        if (isClosed) return null

        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        return try {
            val fileName = "camflow_${System.currentTimeMillis()}.jpg"
            val outputFile = File(context.cacheDir, fileName)
            inputStream = context.contentResolver.openInputStream(uri)
            outputStream = FileOutputStream(outputFile)
            inputStream?.copyTo(outputStream)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outputFile
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create persistent copy for $uri: ${e.message}", e)
            null
        } finally {
            try {
                inputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing input stream", e)
            }
            try {
                outputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing output stream", e)
            }
        }
    }

    private fun sessionsDir(): File {
        val dir = File(context.filesDir, SESSIONS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveCurrentSession(): Boolean {
        if (isClosed) return false

        return try {
            val id = UUID.randomUUID().toString()
            val session = CamFlowSession(
                id = id,
                timestamp = System.currentTimeMillis(),
                modelName = aiModel?.name ?: "Unknown",
                prompt = prompt,
                destinationType = destinationType,
                destination = destination,
                attachImages = attachImages,
                imageUris = imageUris.map { it.toString() },
                analyses = analysisResults.map { it.analysis }
            )
            val file = File(sessionsDir(), "$id.json")
            file.writeText(sessionToJson(session).toString())
            Log.d(TAG, "Saved session: ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveCurrentSession error: ${e.message}", e)
            false
        }
    }

    fun loadAllSessions(): List<CamFlowSession> {
        if (isClosed) return emptyList()

        return try {
            sessionsDir().listFiles { f -> f.extension.equals("json", true) }?.mapNotNull { f ->
                runCatching { jsonToSession(JSONObject(f.readText())) }.getOrNull()
            }?.sortedByDescending { it.timestamp } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "loadAllSessions error: ${e.message}", e)
            emptyList()
        }
    }

    fun deleteSession(id: String): Boolean {
        if (isClosed) return false

        return try {
            val f = File(sessionsDir(), "$id.json")
            f.exists() && f.delete()
        } catch (e: Exception) {
            false
        }
    }

    fun clearAllSessions() {
        if (isClosed) return
        sessionsDir().listFiles()?.forEach { it.delete() }
    }

    private fun sessionToJson(s: CamFlowSession): JSONObject =
        JSONObject().apply {
            put("id", s.id)
            put("timestamp", s.timestamp)
            put("modelName", s.modelName)
            put("prompt", s.prompt)
            put("destinationType", s.destinationType)
            put("destination", s.destination)
            put("attachImages", s.attachImages)
            put("imageUris", JSONArray(s.imageUris))
            put("analyses", JSONArray(s.analyses))
        }

    private fun jsonToSession(o: JSONObject): CamFlowSession =
        CamFlowSession(
            id = o.getString("id"),
            timestamp = o.getLong("timestamp"),
            modelName = o.optString("modelName", "Unknown"),
            prompt = o.optString("prompt", ""),
            destinationType = o.optString("destinationType", DestinationType.GMAIL),
            destination = o.optString("destination", ""),
            attachImages = o.optBoolean("attachImages", true),
            imageUris = (o.optJSONArray("imageUris") ?: JSONArray()).let { arr ->
                List(arr.length()) { idx -> arr.getString(idx) }
            },
            analyses = (o.optJSONArray("analyses") ?: JSONArray()).let { arr ->
                List(arr.length()) { idx -> arr.getString(idx) }
            }
        )

    fun close() {
        if (isClosed) return
        try {
            faceGroupManager.close()
            clearImages()
            gmailService = null
            aiModelInstance = null
            isInitialized = false
            isClosed = true
            System.gc()
            Log.d(TAG, "CamflowImageManager resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing CamflowImageManager: ${e.message}", e)
        }
    }
}