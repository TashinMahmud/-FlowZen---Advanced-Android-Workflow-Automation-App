package com.google.ai.edge.gallery.ui.workflow
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.gallery.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
class WorkflowExecutionService : LifecycleService() {
    private lateinit var workflowStorageHelper: WorkflowStorageHelper
    private lateinit var wakeLock: PowerManager.WakeLock
    private var currentWorkflowId: String? = null
    private var gmailApiHelper: GmailApiHelper? = null
    override fun onCreate() {
        super.onCreate()
        workflowStorageHelper = WorkflowStorageHelper(this)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WorkflowExecutionService:WakeLock"
        )
        wakeLock.acquire(10 * 60 * 1000L) // 10 minutes timeout
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val workflowId = intent?.getStringExtra(EXTRA_WORKFLOW_ID) ?: return START_NOT_STICKY
        currentWorkflowId = workflowId
        Log.d("WorkflowExecutionService", "🔧 Service executing workflow: $workflowId")
        val notification = createNotification("Executing workflow...")
        startForeground(NOTIFICATION_ID, notification)
        lifecycleScope.launch {
            try {
                executeWorkflow(workflowId)
                Log.d("WorkflowExecutionService", "✅ Service completed workflow execution: $workflowId")
                val workflows = workflowStorageHelper.loadWorkflows()
                val workflow = workflows.find { it.id == workflowId }
                if (workflow != null && workflow.interval > 0 && !isWorkflowExpired(workflow)) {
                    Log.d("WorkflowExecutionService", "🔄 Rescheduling recurring workflow: $workflowId")
                    scheduleWorkflow(workflow)
                }
            } catch (e: Exception) {
                Log.e("WorkflowExecutionService", "❌ Service error executing workflow: ${e.message}", e)
                val workflows = workflowStorageHelper.loadWorkflows()
                val workflow = workflows.find { it.id == workflowId }
                if (workflow != null && workflow.interval > 0 && !isWorkflowExpired(workflow)) {
                    Log.d("WorkflowExecutionService", "🔄 Rescheduling after error: $workflowId")
                    scheduleWorkflow(workflow)
                }
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                stopSelf()
            }
        }
        return START_STICKY
    }
    private suspend fun executeWorkflow(workflowId: String) {
        val workflows = workflowStorageHelper.loadWorkflows()
        val workflow = workflows.find { it.id == workflowId }
            ?: throw Exception("Workflow not found: $workflowId")
        if (isWorkflowExpired(workflow)) {
            throw Exception("Workflow has expired: $workflowId")
        }
        val account = getGoogleAccountAndToken(this).first
            ?: throw Exception("Google account not found")
        if (gmailApiHelper == null) {
            gmailApiHelper = GmailApiHelper(this, account)
        }
        val selectedModel = com.google.ai.edge.gallery.data.TASK_CREATE_WORKFLOW.models.firstOrNull()
        if (selectedModel != null) {
            gmailApiHelper?.setAiModel(selectedModel)
            val success = gmailApiHelper?.initializeAiModel() ?: false
            if (!success) {
                throw Exception("Failed to initialize AI model")
            }
        }
        var updatedWorkflow = workflow.copy(
            status = WorkflowStatus.RUNNING,
            isActive = true
        )
        workflowStorageHelper.saveWorkflow(updatedWorkflow)
        val executedSteps = mutableListOf<WorkflowStep>()
        val totalSteps = workflow.steps.size
        for ((index, step) in workflow.steps.withIndex()) {
            Log.d("WorkflowExecutionService", "🚀 Starting Step ${index + 1}/${totalSteps}: ${step.name}")
            val inProgressStep = step.copy(status = StepStatus.IN_PROGRESS)
            executedSteps.add(inProgressStep)
            updatedWorkflow = updatedWorkflow.copy(
                steps = executedSteps + workflow.steps.drop(index + 1)
            )
            workflowStorageHelper.saveWorkflow(updatedWorkflow)
            val result = executeStep(inProgressStep, workflow)
            val completedStep = if (result.isSuccess) {
                Log.d("WorkflowExecutionService", "✅ Step ${index + 1} COMPLETED: ${step.name}")
                inProgressStep.copy(
                    status = StepStatus.COMPLETED,
                    result = result.getOrNull()
                )
            } else {
                Log.e("WorkflowExecutionService", "❌ Step ${index + 1} FAILED: ${step.name} - ${result.exceptionOrNull()?.message}")
                inProgressStep.copy(
                    status = StepStatus.ERROR,
                    error = result.exceptionOrNull()?.message
                )
            }
            executedSteps[index] = completedStep
            if (workflow.stepDelay > 0 && index < workflow.steps.size - 1) {
                Log.d("WorkflowExecutionService", "⏸️ Adding ${workflow.stepDelay}ms delay between steps")
                kotlinx.coroutines.delay(workflow.stepDelay)
            }
        }
        val finalWorkflow = updatedWorkflow.copy(
            steps = executedSteps,
            executedAt = System.currentTimeMillis()
        )
        val (statusAfterExecution, isActiveAfterExecution) = if (workflow.isContinuous || workflow.interval > 0) {
            WorkflowStatus.SCHEDULED to true
        } else {
            WorkflowStatus.COMPLETED to false
        }
        val finalWorkflowWithStatus = finalWorkflow.copy(
            status = statusAfterExecution,
            isActive = isActiveAfterExecution
        )
        workflowStorageHelper.saveWorkflow(finalWorkflowWithStatus)
    }
    private suspend fun executeStep(step: WorkflowStep, workflow: Workflow): Result<Any> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                when (step.type) {
                    StepType.FETCH_EMAILS -> {
                        val maxResults = step.parameters["maxResults"] as? Int ?: 10
                        val label = step.parameters["label"] as? String ?: "INBOX"
                        val emails = gmailApiHelper?.fetchLatestEmails() ?: emptyList()
                        Result.success(emails)
                    }
                    StepType.SUMMARIZE_EMAILS -> {
                        val enabled = step.parameters["enabled"] as? Boolean ?: false
                        val truncateLength = step.parameters["truncateLength"] as? Int ?: 3000
                        if (!enabled) {
                            Result.success("Summarization disabled")
                        } else {
                            Result.success("Emails summarized successfully")
                        }
                    }
                    StepType.FORWARD_EMAILS -> {
                        val destination = step.parameters["destination"] as? String ?: ""
                        val destinationType = step.parameters["destinationType"] as? String ?: "gmail"
                        val includeSummary = step.parameters["includeSummary"] as? Boolean ?: false
                        val actualDestination = if (destination.isBlank()) {
                            when (destinationType) {
                                "telegram", "deeplink" -> workflow.destinationChatId
                                else -> workflow.destinationEmail
                            }
                        } else {
                            destination
                        }
                        if (actualDestination.isNullOrBlank()) {
                            Result.failure(Exception("Destination not specified"))
                        } else {
                            val telegramHelper = if (destinationType == "telegram" || destinationType == "deeplink") {
                                TelegramDeepLinkHelperWork(this@WorkflowExecutionService, workflow.id).apply {
                                    setConnectionState(workflow.telegramChatId, workflow.telegramUsername, workflow.telegramConnectionStatus)
                                }
                            } else {
                                null
                            }
                            val success = gmailApiHelper?.forwardEmail(
                                messageId = "",
                                destination = actualDestination,
                                destinationType = destinationType,
                                withSummary = includeSummary,
                                telegramHelper = telegramHelper
                            ) ?: false
                            if (success) {
                                Result.success("Emails forwarded successfully to $actualDestination via $destinationType")
                            } else {
                                Result.failure(Exception("Failed to forward emails"))
                            }
                        }
                    }
                    StepType.SEND_BATCH_SUMMARIES -> {
                        val enabled = step.parameters["enabled"] as? Boolean ?: false
                        val destination = step.parameters["destination"] as? String ?: ""
                        val destinationType = step.parameters["destinationType"] as? String ?: "gmail"
                        val count = step.parameters["count"] as? Int ?: 5
                        val senderFilter = step.parameters["senderFilter"] as? String ?: ""
                        val subjectFilter = step.parameters["subjectFilter"] as? String ?: ""
                        if (!enabled) {
                            Result.success("Batch summary disabled")
                        } else {
                            val actualDestination = if (destination.isBlank()) {
                                when (destinationType) {
                                    "telegram", "deeplink" -> workflow.destinationChatId
                                    else -> workflow.destinationEmail
                                }
                            } else {
                                destination
                            }
                            if (actualDestination.isNullOrBlank()) {
                                Result.failure(Exception("Destination not specified"))
                            } else {
                                val telegramHelper = if (destinationType == "telegram" || destinationType == "deeplink") {
                                    TelegramDeepLinkHelperWork(this@WorkflowExecutionService, workflow.id).apply {
                                        setConnectionState(workflow.telegramChatId, workflow.telegramUsername, workflow.telegramConnectionStatus)
                                    }
                                } else {
                                    null
                                }
                                val success = gmailApiHelper?.sendLastFiveEmailSummaries(
                                    destination = actualDestination,
                                    destinationType = destinationType,
                                    senderFilter = senderFilter.takeIf { it.isNotEmpty() },
                                    subjectFilter = subjectFilter.takeIf { it.isNotEmpty() },
                                    telegramHelper = telegramHelper
                                ) ?: false
                                if (success) {
                                    Result.success("Batch summaries sent successfully to $actualDestination via $destinationType")
                                } else {
                                    Result.failure(Exception("Failed to send batch summaries"))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WorkflowExecutionService", "Error executing step ${step.id}: ${e.message}", e)
                Result.failure(e)
            }
        }
    }
    private fun isWorkflowExpired(workflow: Workflow): Boolean {
        return when (workflow.expirationOption) {
            ExpirationOption.UNTIL_DISABLED -> false
            ExpirationOption.ONE_MONTH -> {
                val oneMonthInMillis = java.util.concurrent.TimeUnit.DAYS.toMillis(30)
                System.currentTimeMillis() > workflow.createdAt + oneMonthInMillis
            }
            ExpirationOption.FIXED_DATE -> {
                workflow.customExpirationDate?.let { date ->
                    System.currentTimeMillis() > date.time
                } ?: false
            }
        }
    }
    private fun scheduleWorkflow(workflow: Workflow) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, WorkflowExecutionReceiver::class.java).apply {
            action = WorkflowExecutionReceiver.ACTION_EXECUTE_WORKFLOW
            putExtra(WorkflowExecutionReceiver.EXTRA_WORKFLOW_ID, workflow.id)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            workflow.id.hashCode(),
            intent,
            flags
        )
        val nextExecutionTime = System.currentTimeMillis() + workflow.interval
        val canScheduleExactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
        try {
            if (canScheduleExactAlarms) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextExecutionTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        nextExecutionTime,
                        pendingIntent
                    )
                }
                Log.d("WorkflowExecutionService", "Scheduled exact alarm for workflow ${workflow.id} at $nextExecutionTime")
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextExecutionTime,
                    pendingIntent
                )
                Log.d("WorkflowExecutionService", "Scheduled inexact alarm for workflow ${workflow.id} at $nextExecutionTime")
            }
        } catch (e: SecurityException) {
            Log.e("WorkflowExecutionService", "SecurityException when scheduling alarm for workflow ${workflow.id}: ${e.message}")
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextExecutionTime,
                    pendingIntent
                )
                Log.d("WorkflowExecutionService", "Fallback to inexact alarm for workflow ${workflow.id} at $nextExecutionTime")
            } catch (e2: Exception) {
                Log.e("WorkflowExecutionService", "Failed to schedule inexact alarm for workflow ${workflow.id}: ${e2.message}")
            }
        } catch (e: Exception) {
            Log.e("WorkflowExecutionService", "Failed to schedule alarm for workflow ${workflow.id}: ${e.message}")
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
    }
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
    private fun createNotification(content: String): Notification {
        val channelId = "workflow_execution_channel"
        val channel = NotificationChannel(
            channelId,
            "Workflow Execution",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Notifications for workflow execution"
        channel.setShowBadge(false)
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Workflow Execution")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    companion object {
        const val NOTIFICATION_ID = 1
        const val EXTRA_WORKFLOW_ID = "workflow_id"
    }
}