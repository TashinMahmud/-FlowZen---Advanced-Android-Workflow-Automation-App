// File 1: FaceNetModel.kt
package com.google.ai.edge.gallery.ui.camflow
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class FaceNetModel(context: Context) {
    private var interpreter: Interpreter? = null
    private val imageProcessor: ImageProcessor
    private var embeddingSize: Int = 0
    private val modelName = "facenet.tflite"
    private val TAG = "FaceNetModel"
    private val lock = ReentrantReadWriteLock()
    private var isClosed = false

    init {
        try {
            val model = FileUtil.loadMappedFile(context, modelName)
            val options = Interpreter.Options()
            options.setNumThreads(2)
            options.setUseNNAPI(true)
            lock.write {
                if (isClosed) throw IllegalStateException("Model already closed")
                interpreter = Interpreter(model, options)
            }

            val inputTensor = lock.read { interpreter?.getInputTensor(0) }
            val inputShape = inputTensor?.shape()
            val outputTensor = lock.read { interpreter?.getOutputTensor(0) }
            val outputShape = outputTensor?.shape()

            if (inputShape != null && inputShape.size >= 4 &&
                outputShape != null && outputShape.size >= 2) {
                val inputHeight = inputShape[1]
                val inputWidth = inputShape[2]
                embeddingSize = outputShape[1]
                Log.d(TAG, "Model loaded successfully. Input shape: ${inputShape.contentToString()}, Output shape: ${outputShape.contentToString()}")
                imageProcessor = ImageProcessor.Builder()
                    .add(ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
                    .add(NormalizeOp(0f, 255f))
                    .build()
            } else {
                Log.e(TAG, "Invalid model tensor shapes. Input: ${inputShape?.contentToString()}, Output: ${outputShape?.contentToString()}")
                throw IllegalStateException("Invalid model tensor shapes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing FaceNet model", e)
            close()
            throw e
        }
    }

    fun getEmbedding(bitmap: Bitmap, faceRect: Rect): FloatArray? {
        if (isClosed) {
            Log.e(TAG, "Model already closed")
            return null
        }

        if (lock.read { interpreter == null }) {
            Log.e(TAG, "Interpreter not initialized")
            return null
        }

        if (faceRect.left < 0 || faceRect.top < 0 ||
            faceRect.right > bitmap.width || faceRect.bottom > bitmap.height) {
            Log.e(TAG, "Face rect out of bounds: $faceRect, bitmap: ${bitmap.width}x${bitmap.height}")
            return null
        }

        var faceBitmap: Bitmap? = null
        var tensorImage: TensorImage? = null
        var inputBuffer: java.nio.ByteBuffer? = null
        var outputBuffer: Array<FloatArray>? = null

        return try {
            // Check memory before creating bitmap
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()

            if (usedMemory > maxMemory * 0.7) {
                Log.w(TAG, "High memory usage: ${usedMemory / (1024 * 1024)}MB of ${maxMemory / (1024 * 1024)}MB")
                System.gc()
                Thread.sleep(50)
            }

            faceBitmap = try {
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                Bitmap.createBitmap(mutableBitmap, faceRect.left, faceRect.top, faceRect.width(), faceRect.height())
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Error cropping face bitmap", e)
                return null
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory when cropping face bitmap", e)
                System.gc()
                return null
            }

            tensorImage = TensorImage.fromBitmap(faceBitmap)
            tensorImage = imageProcessor.process(tensorImage)
            inputBuffer = tensorImage.buffer
            outputBuffer = Array(1) { FloatArray(embeddingSize) }

            lock.read {
                try {
                    interpreter?.run(inputBuffer, outputBuffer)
                    outputBuffer?.get(0)
                } catch (e: Exception) {
                    Log.e(TAG, "Error running TFLite inference", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getEmbedding", e)
            null
        } finally {
            faceBitmap?.recycle()
            tensorImage = null
            inputBuffer = null
            outputBuffer = null
        }
    }

    fun getEmbeddingSize(): Int {
        return embeddingSize
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        try {
            lock.write {
                interpreter?.close()
                interpreter = null
            }
            System.gc()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing interpreter", e)
        }
    }

    protected fun finalize() {
        close()
    }
}