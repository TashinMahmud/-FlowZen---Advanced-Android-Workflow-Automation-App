package com.google.ai.edge.gallery.ui.camflow
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class CamFlowForegroundService : Service() {
    companion object {
        private const val TAG = "CamFlowFgService"
        private const val CHANNEL_ID = "camflow_processing_channel"
        private const val NOTIF_ID = 7461
        private const val EXTRA_TASK_ID = "camflow_task_id"

        fun start(context: Context, taskId: String) {
            val i = Intent(context, CamFlowForegroundService::class.java)
                .putExtra(EXTRA_TASK_ID, taskId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var manager: CamflowImageManager

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        if (taskId.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIF_ID,
            buildNotification(progress = 0f, indeterminate = true, title = "Preparing…")
        )

        scope.launch(Dispatchers.IO) {
            var spec = CamflowTaskRegistry.get(taskId)
            if (spec == null) {
                Log.e(TAG, "No task spec found for id=$taskId")
                finishWithError("No task data found.")
                return@launch
            }

            try {
                manager = CamflowImageManager(applicationContext).apply {
                    spec.model?.let { setAiModel(it) }
                    setPrompt(spec.prompt)
                    setDestination(spec.destinationType, spec.destination)
                    setAttachImages(spec.attachImages)
                    spec.imageUris.forEach { addImage(it) }
                }

                // Check if this is an automation task (no model specified)
                if (spec.model == null) {
                    // Try to get the current model from the manager
                    val currentModel = manager.getCurrentModel()
                    if (currentModel != null) {
                        // Update the spec with the current model
                        spec = spec.copy(model = currentModel)
                        // Set the model in the manager
                        manager.setAiModel(currentModel)
                        Log.d(TAG, "Using current model for automation task: ${currentModel.name}")
                    } else {
                        // Try to load the model from automation settings
                        val automationPrefs = getSharedPreferences("camflow_automation", Context.MODE_PRIVATE)
                        val modelName = automationPrefs.getString("modelName", null)
                        if (modelName != null) {
                            // Find the model by name in the ModelManager
                            // Note: This requires access to ModelManager, which we don't have directly
                            // For now, we'll log an error and suggest selecting a model in the UI
                            Log.e(TAG, "Model name found in settings ($modelName) but couldn't load model instance")
                            finishWithError("Please select a model in the CamFlow settings for automation to work.")
                            return@launch
                        } else {
                            finishWithError("No AI model selected for automation.")
                            return@launch
                        }
                    }
                }

                if (spec.model == null) {
                    finishWithError("No AI model selected.")
                    return@launch
                }

                updateNotification(0f, true, "Initializing model…")
                val ok = manager.initializeAiModel()
                if (!ok) {
                    finishWithError("Failed to initialize model.")
                    return@launch
                }

                var lastP = 0f
                updateNotification(0.02f, true, "Starting analysis…")
                val success = manager.processAndSendResults { p ->
                    if ((p - lastP) >= 0.01f || p >= 1f) {
                        lastP = p
                        updateNotification(p, false, "Processing ${(p * 100).roundToInt()}%")
                        // Save progress to SharedPreferences for UI updates
                        Log.d(TAG, "🔍 DEBUG: Saving progress - taskId: $taskId, progress: $p")
                        getSharedPreferences("camflow_progress", Context.MODE_PRIVATE)
                            .edit()
                            .putFloat("progress_$taskId", p)
                            .apply()
                    }
                }

                // Remove task from registry only when completed
                CamflowTaskRegistry.take(taskId)
                if (success) {
                    completeWithSuccess("Analysis sent & saved.")
                } else {
                    finishWithError("Processing failed.")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Fatal error", t)
                // Remove task from registry on error too
                CamflowTaskRegistry.take(taskId)
                finishWithError("Unexpected error: ${t.message ?: "unknown"}")
            }
        }

        return START_NOT_STICKY
    }

    private fun completeWithSuccess(msg: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("CamFlow")
            .setContentText(msg)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notif)
        stopSelf()
    }

    private fun finishWithError(msg: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("CamFlow – Error")
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notif)
        stopSelf()
    }

    private fun updateNotification(progress: Float, indeterminate: Boolean, title: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("CamFlow")
            .setContentText(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            val pct = (progress.coerceIn(0f, 1f) * 100).roundToInt()
            builder.setProgress(100, pct, false)
        }
        startForeground(NOTIF_ID, builder.build())
    }

    private fun buildNotification(progress: Float, indeterminate: Boolean, title: String): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("CamFlow")
            .setContentText(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (indeterminate) b.setProgress(0, 0, true)
        else b.setProgress(100, (progress * 100).roundToInt(), false)
        return b.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CamFlow Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background image analysis and delivery"
            }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}