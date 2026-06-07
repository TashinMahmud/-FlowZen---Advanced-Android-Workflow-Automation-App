// File 2: FaceGroupManager.kt
package com.google.ai.edge.gallery.ui.camflow
import android.content.Context
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.ai.edge.gallery.ui.maps.await
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

data class RecognitionResult(
    val personName: String?,
    val confidence: Float,
    val faceRect: Rect
)

data class PersonGroup(
    val id: String,
    val name: String,
    val referenceEmbeddings: MutableList<FloatArray> = mutableListOf()
)

class FaceGroupManager(private val context: Context) {
    val faceNetModel = FaceNetModel(context)
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
    )
    private val personGroups = mutableListOf<PersonGroup>()
    private val similarityThreshold = 0.7f
    private val prefs = context.getSharedPreferences("FaceGroupPrefs", Context.MODE_PRIVATE)
    private var isClosed = false

    init {
        loadPersonGroups()
    }

    suspend fun addReferenceImage(uri: Uri, personName: String): Boolean = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext false

        try {
            val bitmap = loadBitmapSafely(uri) ?: return@withContext false
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = faceDetector.process(image).await()

            if (faces.isEmpty()) {
                Log.e(TAG, "No faces found in reference image")
                bitmap.recycle()
                return@withContext false
            }

            val embeddings = mutableListOf<FloatArray>()
            for (face in faces) {
                try {
                    val embedding = faceNetModel.getEmbedding(bitmap, face.boundingBox)
                    if (embedding != null) {
                        Log.d(TAG, "Generated embedding for ${personName}: size=${embedding.size}, first 5 values: ${embedding.take(5).joinToString()}")
                        embeddings.add(embedding)
                    } else {
                        Log.e(TAG, "Failed to generate embedding for face in ${personName}")
                    }
                    kotlinx.coroutines.delay(50)
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting embedding for face", e)
                }
            }

            bitmap.recycle()

            if (embeddings.isEmpty()) {
                Log.e(TAG, "No valid embeddings found in reference image")
                return@withContext false
            }

            val existingGroup = personGroups.find { it.name == personName }
            if (existingGroup != null) {
                existingGroup.referenceEmbeddings.addAll(embeddings)
            } else {
                val newGroup = PersonGroup(
                    id = UUID.randomUUID().toString(),
                    name = personName,
                    referenceEmbeddings = embeddings.toMutableList()
                )
                personGroups.add(newGroup)
            }

            savePersonGroups()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding reference image", e)
            false
        } finally {
            System.gc()
        }
    }

    suspend fun processImageAndSaveToAlbum(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext false

        try {
            val bitmap = loadBitmapSafely(uri) ?: return@withContext false
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = faceDetector.process(image).await()

            if (faces.isEmpty()) {
                Log.d(TAG, "No faces found in image")
                bitmap.recycle()
                return@withContext false
            }

            var savedToAlbum = false
            for (face in faces) {
                try {
                    val embedding = faceNetModel.getEmbedding(bitmap, face.boundingBox)
                    if (embedding != null) {
                        val match = findBestMatch(embedding)
                        if (match != null) {
                            saveImageToAlbum(uri, match.name)
                            savedToAlbum = true
                        }
                    }
                    kotlinx.coroutines.delay(50)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing face", e)
                }
            }

            bitmap.recycle()
            savedToAlbum
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
            false
        } finally {
            System.gc()
        }
    }

    suspend fun findBestMatchForImage(uri: Uri): PersonGroup? = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext null

        try {
            val bitmap = loadBitmapSafely(uri) ?: return@withContext null
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = faceDetector.process(image).await()

            if (faces.isEmpty()) {
                bitmap.recycle()
                return@withContext null
            }

            val face = faces.first()
            try {
                val embedding = faceNetModel.getEmbedding(bitmap, face.boundingBox)
                bitmap.recycle()
                if (embedding != null) {
                    findBestMatch(embedding)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting embedding for face", e)
                bitmap.recycle()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding match for image", e)
            null
        } finally {
            System.gc()
        }
    }

    suspend fun recognizeFacesInImage(uri: Uri): List<RecognitionResult> = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext emptyList()

        try {
            val bitmap = loadBitmapSafely(uri) ?: return@withContext emptyList()
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = faceDetector.process(image).await()

            if (faces.isEmpty()) {
                bitmap.recycle()
                return@withContext emptyList()
            }

            val results = mutableListOf<RecognitionResult>()
            for (face in faces) {
                try {
                    val embedding = faceNetModel.getEmbedding(bitmap, face.boundingBox)
                    if (embedding != null) {
                        Log.d(TAG, "Recognition: Generated embedding: size=${embedding.size}, first 5 values: ${embedding.take(5).joinToString()}")
                        val match = findBestMatchWithConfidence(embedding)
                        Log.d(TAG, "Recognition: Match result: $match")
                        results.add(RecognitionResult(
                            personName = match?.first,
                            confidence = match?.second ?: 0f,
                            faceRect = face.boundingBox
                        ))
                    } else {
                        Log.e(TAG, "Recognition: Failed to generate embedding for face")
                    }
                    kotlinx.coroutines.delay(50)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing face", e)
                }
            }

            bitmap.recycle()
            results
        } catch (e: Exception) {
            Log.e(TAG, "Error recognizing faces in image", e)
            emptyList()
        } finally {
            System.gc()
        }
    }

    private fun findBestMatchWithConfidence(embedding: FloatArray): Pair<String?, Float>? {
        var bestMatch: String? = null
        var highestSimilarity = 0f

        Log.d(TAG, "Finding best match for embedding of size: ${embedding.size}")
        Log.d(TAG, "Number of person groups: ${personGroups.size}")

        for (group in personGroups) {
            Log.d(TAG, "Checking group: ${group.name} with ${group.referenceEmbeddings.size} reference embeddings")
            for (referenceEmbedding in group.referenceEmbeddings) {
                try {
                    val similarity = cosineSimilarity(embedding, referenceEmbedding)
                    Log.d(TAG, "Similarity with ${group.name}: $similarity")
                    if (similarity > highestSimilarity && similarity >= similarityThreshold) {
                        highestSimilarity = similarity
                        bestMatch = group.name
                        Log.d(TAG, "New best match: ${group.name} with similarity: $similarity")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error calculating similarity", e)
                }
            }
        }

        Log.d(TAG, "Final best match: $bestMatch with similarity: $highestSimilarity")
        return if (bestMatch != null) Pair(bestMatch, highestSimilarity) else null
    }

    suspend fun processTodayImagesForRecognition(context: Context): List<GalleryImage> = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext emptyList()

        try {
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startOfDay = calendar.timeInMillis

            val todayImages = mutableListOf<GalleryImage>()
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE
            )
            val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            val selectionArgs = arrayOf((startOfDay / 1000).toString())
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            cursor?.use {
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

                    val hasFaces = imageContainsFaces(imageUri)
                    if (hasFaces) {
                        val recognitionResults = recognizeFacesInImage(imageUri)
                        val matchedPerson = recognitionResults
                            .maxByOrNull { it.confidence }
                            ?.personName
                        todayImages.add(GalleryImage(
                            uri = imageUri,
                            name = name,
                            dateAdded = dateAdded,
                            size = size,
                            hasFaces = true,
                            matchedPerson = matchedPerson
                        ))
                    }
                    kotlinx.coroutines.delay(100)
                }
            }

            todayImages
        } catch (e: Exception) {
            Log.e(TAG, "Error processing today's images for recognition", e)
            emptyList()
        } finally {
            System.gc()
        }
    }

    private suspend fun imageContainsFaces(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext false

        try {
            val bitmap = loadBitmapSafely(uri) ?: return@withContext false
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = faceDetector.process(image).await()
            bitmap.recycle()
            faces.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting faces in image", e)
            false
        }
    }

    private fun findBestMatch(embedding: FloatArray): PersonGroup? {
        var bestMatch: PersonGroup? = null
        var highestSimilarity = 0f

        for (group in personGroups) {
            for (referenceEmbedding in group.referenceEmbeddings) {
                try {
                    val similarity = cosineSimilarity(embedding, referenceEmbedding)
                    if (similarity > highestSimilarity && similarity >= similarityThreshold) {
                        highestSimilarity = similarity
                        bestMatch = group
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error calculating similarity", e)
                }
            }
        }

        return bestMatch
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) {
            Log.e(TAG, "Embedding size mismatch: ${a.size} vs ${b.size}")
            return 0f
        }

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator == 0f) 0f else dotProduct / denominator
    }

    private suspend fun saveImageToAlbum(uri: Uri, albumName: String) = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext

        try {
            val albumDir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "FaceAlbums/$albumName"
            )
            if (!albumDir.exists()) {
                albumDir.mkdirs()
            }

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "IMG_${timeStamp}.jpg"
            val outputFile = File(albumDir, fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "Saved image to album: $albumName")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving image to album", e)
        }
    }

    private suspend fun loadBitmapSafely(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        if (isClosed) return@withContext null

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(input, null, options)

                val sampleSize = calculateInSampleSize(options, 1024, 1024)
                options.inSampleSize = sampleSize
                options.inJustDecodeBounds = false

                context.contentResolver.openInputStream(uri)?.use { newStream ->
                    BitmapFactory.decodeStream(newStream, null, options)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URI", e)
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

    private fun loadPersonGroups() {
        if (isClosed) return

        try {
            val json = prefs.getString("person_groups", "[]")
            val jsonArray = JSONArray(json)
            personGroups.clear()

            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val group = PersonGroup(
                    id = jsonObject.getString("id"),
                    name = jsonObject.getString("name"),
                    referenceEmbeddings = mutableListOf<FloatArray>().apply {
                        val embeddingsArray = jsonObject.getJSONArray("referenceEmbeddings")
                        for (j in 0 until embeddingsArray.length()) {
                            val embeddingArray = embeddingsArray.getJSONArray(j)
                            val embedding = FloatArray(embeddingArray.length())
                            for (k in 0 until embeddingArray.length()) {
                                embedding[k] = embeddingArray.getDouble(k).toFloat()
                            }
                            add(embedding)
                        }
                    }
                )
                personGroups.add(group)
            }

            Log.d(TAG, "Loaded ${personGroups.size} person groups from storage")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading person groups", e)
        }
    }

    private fun savePersonGroups() {
        if (isClosed) return

        try {
            val jsonArray = JSONArray()
            for (group in personGroups) {
                val jsonObject = JSONObject().apply {
                    put("id", group.id)
                    put("name", group.name)
                    val embeddingsArray = JSONArray()
                    for (embedding in group.referenceEmbeddings) {
                        val embeddingArray = JSONArray()
                        for (value in embedding) {
                            embeddingArray.put(value)
                        }
                        embeddingsArray.put(embeddingArray)
                    }
                    put("referenceEmbeddings", embeddingsArray)
                }
                jsonArray.put(jsonObject)
            }
            prefs.edit().putString("person_groups", jsonArray.toString()).apply()
            Log.d(TAG, "Saved ${personGroups.size} person groups to storage")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving person groups", e)
        }
    }

    fun getPersonGroups(): List<PersonGroup> = personGroups

    fun deletePersonGroup(groupId: String): Boolean {
        if (isClosed) return false

        val removed = personGroups.removeIf { it.id == groupId }
        if (removed) {
            savePersonGroups()
            Log.d(TAG, "Deleted person group with ID: $groupId")
            return true
        }
        return false
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        faceNetModel.close()
    }

    companion object {
        private const val TAG = "FaceGroupManager"
    }
}