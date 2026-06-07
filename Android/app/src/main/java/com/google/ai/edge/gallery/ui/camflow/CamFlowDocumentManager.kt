// File: CamFlowDocumentManager.kt
package com.google.ai.edge.gallery.ui.camflow

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class DocumentImage(
    val uri: Uri,
    val name: String,
    val dateAdded: Long,
    val size: Long,
    val isDocument: Boolean,
    val extractedText: List<String> = emptyList()
)

class CamFlowDocumentManager(private val context: Context) {
    private val TAG = "CamFlowDocumentManager"
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var isClosed = false

    suspend fun getTodayImages(): List<DocumentImage> = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext emptyList()

        val images = mutableListOf<DocumentImage>()

        try {
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startOfDay = calendar.timeInMillis / 1000

            Log.d(TAG, "Looking for images since: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(startOfDay * 1000))}")

            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE
            )

            // Try DATE_ADDED first
            var selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            var selectionArgs = arrayOf(startOfDay.toString())
            var sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            var cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            var imageCount = 0

            cursor?.use {
                imageCount = it.count
                Log.d(TAG, "Found $imageCount images with DATE_ADDED")

                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateAddedColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val name = it.getString(nameColumn)
                    val dateAdded = it.getLong(dateAddedColumn) * 1000
                    val size = it.getLong(sizeColumn)
                    val imageUri = ContentUris.withAppendedId(uri, id)

                    images.add(DocumentImage(
                        uri = imageUri,
                        name = name,
                        dateAdded = dateAdded,
                        size = size,
                        isDocument = false // Will be determined later
                    ))
                }
            }

            // If no images found with DATE_ADDED, try DATE_MODIFIED
            if (images.isEmpty()) {
                Log.d(TAG, "No images found with DATE_ADDED, trying DATE_MODIFIED")
                selection = "${MediaStore.Images.Media.DATE_MODIFIED} >= ?"
                selectionArgs = arrayOf(startOfDay.toString())
                sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

                cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
                cursor?.use {
                    imageCount = it.count
                    Log.d(TAG, "Found $imageCount images with DATE_MODIFIED")

                    val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateModifiedColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                    while (it.moveToNext()) {
                        val id = it.getLong(idColumn)
                        val name = it.getString(nameColumn)
                        val dateAdded = it.getLong(dateModifiedColumn) * 1000
                        val size = it.getLong(sizeColumn)
                        val imageUri = ContentUris.withAppendedId(uri, id)

                        images.add(DocumentImage(
                            uri = imageUri,
                            name = name,
                            dateAdded = dateAdded,
                            size = size,
                            isDocument = false
                        ))
                    }
                }
            }

            Log.d(TAG, "Total images loaded: ${images.size}")
            images
        } catch (e: Exception) {
            Log.e(TAG, "Error getting today's images", e)
            emptyList()
        }
    }

    suspend fun processImagesForDocuments(
        images: List<DocumentImage>,
        onProgress: (Float) -> Unit = {}
    ): Pair<List<DocumentImage>, List<DocumentImage>> = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext Pair(emptyList(), emptyList())

        val documents = mutableListOf<DocumentImage>()
        val nonDocuments = mutableListOf<DocumentImage>()

        try {
            Log.d(TAG, "Processing ${images.size} images for document detection")

            images.forEachIndexed { index, image ->
                if (isClosed) return@withContext Pair(documents, nonDocuments)

                try {
                    val bitmap = loadBitmapSafely(image.uri)
                    if (bitmap != null) {
                        val inputImage = InputImage.fromBitmap(bitmap, 0)
                        val result = textRecognizer.process(inputImage).await()

                        val textBlocks = mutableListOf<String>()
                        for (block in result.textBlocks) {
                            textBlocks.add(block.text)
                        }

                        val isDocument = textBlocks.isNotEmpty()
                        val updatedImage = image.copy(
                            isDocument = isDocument,
                            extractedText = textBlocks
                        )

                        if (isDocument) {
                            documents.add(updatedImage)
                        } else {
                            nonDocuments.add(updatedImage)
                        }

                        bitmap.recycle()
                    } else {
                        nonDocuments.add(image)
                    }

                    // Update progress
                    val progress = (index + 1) / images.size.toFloat()
                    withContext(Dispatchers.Main) {
                        onProgress(progress)
                    }

                    // Small delay to prevent overloading
                    kotlinx.coroutines.delay(50)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image: ${image.name}", e)
                    nonDocuments.add(image)
                }
            }

            Log.d(TAG, "Processing complete. Documents: ${documents.size}, Non-documents: ${nonDocuments.size}")
            Pair(documents, nonDocuments)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing images for documents", e)
            Pair(emptyList(), emptyList())
        }
    }

    suspend fun extractTextFromImage(imageUri: Uri): List<String> = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext emptyList()

        try {
            val bitmap = loadBitmapSafely(imageUri)
            if (bitmap != null) {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val result = textRecognizer.process(inputImage).await()

                val textBlocks = mutableListOf<String>()
                for (block in result.textBlocks) {
                    textBlocks.add(block.text)
                }

                bitmap.recycle()
                textBlocks
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text from image", e)
            emptyList()
        }
    }

    private suspend fun loadBitmapSafely(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext null

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)

                // Check if we got valid dimensions
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    Log.w(TAG, "Invalid image dimensions for URI: $uri")
                    return@use null
                }

                // Calculate sample size
                val sampleSize = calculateInSampleSize(options, 1024, 1024)
                options.inSampleSize = sampleSize
                options.inJustDecodeBounds = false

                // Load the bitmap with sampling
                context.contentResolver.openInputStream(uri)?.use { newStream ->
                    BitmapFactory.decodeStream(newStream, null, options)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URI: $uri", e)
            null
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

    fun close() {
        if (isClosed) return
        isClosed = true
        try {
            textRecognizer.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing text recognizer", e)
        }
    }

    protected fun finalize() {
        close()
    }
}