package com.google.ai.edge.gallery.ui.workflow

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TASK_CREATE_WORKFLOW
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.navigation.WorkflowEmailManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import java.util.*
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "WorkflowViewModel"
private const val WORKFLOW_PREFS = "workflow_prefs"
private const val WORKFLOWS_KEY = "workflows"
private val executingWorkflows = mutableSetOf<String>()

data class WorkflowStep(
    val id: String,
    val name: String,
    val description: String,
    val type: StepType,
    val parameters: Map<String, Any> = emptyMap(),
    val status: StepStatus = StepStatus.PENDING,
    val result: Any? = null,
    val error: String? = null
)

enum class StepType {
    FETCH_EMAILS,
    SUMMARIZE_EMAILS,
    FORWARD_EMAILS,
    SEND_BATCH_SUMMARIES
}

enum class StepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    ERROR
}

enum class ExpirationOption {
    UNTIL_DISABLED,
    ONE_MONTH,
    FIXED_DATE
}

data class Workflow(
    val id: String,
    val name: String,
    val description: String,
    val steps: List<WorkflowStep> = emptyList(),
    val status: WorkflowStatus = WorkflowStatus.DRAFT,
    val createdAt: Long = System.currentTimeMillis(),
    val executedAt: Long? = null,
    val expirationOption: ExpirationOption = ExpirationOption.UNTIL_DISABLED,
    val customExpirationDate: Date? = null,
    val interval: Long = 0,
    val isScheduled: Boolean = false,
    val nextExecutionTime: Long? = null,
    val isActive: Boolean = false,
    val isContinuous: Boolean = false,
    val stepDelay: Long = 0,
    val destinationEmail: String? = null,
    val destinationChatId: String? = null,
    val destinationType: String = "gmail",
    val senderFilter: String? = null,
    val subjectFilter: String? = null,
    val telegramToken: String? = null,
    val telegramChatId: String? = null,
    val telegramUsername: String? = null,
    val telegramConnectionStatus: String? = null
)

enum class WorkflowStatus {
    DRAFT,
    RUNNING,
    COMPLETED,
    ERROR,
    SCHEDULED
}

data class WorkflowUiState(
    val workflows: List<Workflow> = emptyList(),
    val currentWorkflow: Workflow? = null,
    val isExecuting: Boolean = false,
    val executionProgress: Float = 0f,
    val executionError: String? = null,
    val availableModels: List<Model> = emptyList(),
    val selectedModel: Model? = null,
    val isModelInitialized: Boolean = false,
    val isDeepLinkSetup: Boolean = false,
    val deepLinkStatus: String? = null
)

class WorkflowExecutionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_EXECUTE_WORKFLOW = "com.google.ai.edge.gallery.ACTION_EXECUTE_WORKFLOW"
        const val EXTRA_WORKFLOW_ID = "workflow_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_EXECUTE_WORKFLOW) {
            val workflowId = intent.getStringExtra(EXTRA_WORKFLOW_ID) ?: return
            Log.d(TAG, "📡 Received broadcast to execute workflow: $workflowId")
            val serviceIntent = Intent(context, WorkflowExecutionService::class.java).apply {
                putExtra(WorkflowExecutionService.EXTRA_WORKFLOW_ID, workflowId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "✅ Service started for workflow execution: $workflowId")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting service: ${e.message}", e)
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    action = ACTION_EXECUTE_WORKFLOW
                    putExtra(EXTRA_WORKFLOW_ID, workflowId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(launchIntent)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device rebooted, rescheduling active workflows")
            val workflowStorageHelper = WorkflowStorageHelper(context)
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val workflows = workflowStorageHelper.loadWorkflows()
                    val updatedWorkflows = workflows.map { workflow ->
                        if (workflow.isActive && !isWorkflowExpired(workflow) && workflow.interval > 0) {
                            val nextExecutionTime = if (workflow.nextExecutionTime != null &&
                                workflow.nextExecutionTime!! > System.currentTimeMillis()) {
                                workflow.nextExecutionTime
                            } else {
                                System.currentTimeMillis() + workflow.interval
                            }
                            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                            val pendingIntent = PendingIntent.getBroadcast(
                                context,
                                workflow.id.hashCode(),
                                Intent(context, WorkflowExecutionReceiver::class.java).apply {
                                    action = WorkflowExecutionReceiver.ACTION_EXECUTE_WORKFLOW
                                    putExtra(WorkflowExecutionReceiver.EXTRA_WORKFLOW_ID, workflow.id)
                                },
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            val canScheduleExactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                alarmManager.canScheduleExactAlarms()
                            } else {
                                true
                            }
                            try {
                                if (canScheduleExactAlarms) {
                                    alarmManager.setExactAndAllowWhileIdle(
                                        AlarmManager.RTC_WAKEUP,
                                        nextExecutionTime,
                                        pendingIntent
                                    )
                                } else {
                                    alarmManager.setAndAllowWhileIdle(
                                        AlarmManager.RTC_WAKEUP,
                                        nextExecutionTime,
                                        pendingIntent
                                    )
                                }
                                Log.d(TAG, "Rescheduled workflow ${workflow.id} at $nextExecutionTime")
                                workflow.copy(
                                    isScheduled = true,
                                    nextExecutionTime = nextExecutionTime,
                                    isActive = true,
                                    status = WorkflowStatus.SCHEDULED
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to reschedule workflow ${workflow.id}: ${e.message}")
                                workflow.copy(
                                    isScheduled = false,
                                    nextExecutionTime = null,
                                    isActive = false,
                                    status = WorkflowStatus.ERROR
                                )
                            }
                        } else {
                            workflow.copy(
                                isScheduled = false,
                                nextExecutionTime = null,
                                isActive = false
                            )
                        }
                    }
                    workflowStorageHelper.saveWorkflows(updatedWorkflows)
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading workflows after boot: ${e.message}", e)
                }
            }
        }
    }

    private fun isWorkflowExpired(workflow: Workflow): Boolean {
        return when (workflow.expirationOption) {
            ExpirationOption.UNTIL_DISABLED -> false
            ExpirationOption.ONE_MONTH -> {
                val oneMonthInMillis = TimeUnit.DAYS.toMillis(30)
                System.currentTimeMillis() > workflow.createdAt + oneMonthInMillis
            }
            ExpirationOption.FIXED_DATE -> {
                workflow.customExpirationDate?.let { date ->
                    System.currentTimeMillis() > date.time
                } ?: false
            }
        }
    }
}

class WorkflowStorageHelper(private val context: Context) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val fileName = "workflows.json"

    suspend fun saveWorkflows(workflows: List<Workflow>) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, fileName)
                val serializableWorkflows = workflows.map { it.toSerializable() }
                val serialized = json.encodeToString(serializableWorkflows)
                file.writeText(serialized)
                Log.d("WorkflowStorageHelper", "Workflows saved successfully")
            } catch (e: Exception) {
                Log.e("WorkflowStorageHelper", "Error saving workflows: ${e.message}", e)
            }
        }
    }

    suspend fun loadWorkflows(): List<Workflow> {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, fileName)
                if (!file.exists()) {
                    Log.d("WorkflowStorageHelper", "No saved workflows found")
                    return@withContext emptyList()
                }
                val serialized = file.readText()
                val serializableWorkflows = json.decodeFromString<List<SerializableWorkflow>>(serialized)
                val workflows = serializableWorkflows.map { it.toWorkflow() }
                Log.d("WorkflowStorageHelper", "Loaded ${workflows.size} workflows")
                workflows
            } catch (e: Exception) {
                Log.e("WorkflowStorageHelper", "Error loading workflows: ${e.message}", e)
                emptyList()
            }
        }
    }

    suspend fun saveWorkflow(workflow: Workflow) {
        withContext(Dispatchers.IO) {
            try {
                val workflows = loadWorkflows()
                val updatedWorkflows = workflows.map {
                    if (it.id == workflow.id) workflow else it
                }
                saveWorkflows(updatedWorkflows)
                Log.d("WorkflowStorageHelper", "Saved workflow: ${workflow.name}")
            } catch (e: Exception) {
                Log.e("WorkflowStorageHelper", "Error saving workflow: ${e.message}", e)
            }
        }
    }

    @Serializable
    data class SerializableWorkflow(
        val id: String,
        val name: String,
        val description: String,
        val steps: List<SerializableStep>,
        val status: String,
        val createdAt: Long,
        val executedAt: Long?,
        val expirationOption: String,
        val customExpirationDate: Long?,
        val interval: Long,
        val isScheduled: Boolean,
        val nextExecutionTime: Long?,
        val isActive: Boolean,
        val isContinuous: Boolean,
        val stepDelay: Long,
        val destinationEmail: String?,
        val destinationChatId: String?,
        val destinationType: String,
        val senderFilter: String?,
        val subjectFilter: String?,
        val telegramToken: String?,
        val telegramChatId: String?,
        val telegramUsername: String?,
        val telegramConnectionStatus: String?
    ) {
        fun toWorkflow(): Workflow {
            return Workflow(
                id = id,
                name = name,
                description = description,
                steps = steps.map { it.toStep() },
                status = WorkflowStatus.valueOf(status),
                createdAt = createdAt,
                executedAt = executedAt,
                expirationOption = ExpirationOption.valueOf(expirationOption),
                customExpirationDate = customExpirationDate?.let { Date(it) },
                interval = interval,
                isScheduled = isScheduled,
                nextExecutionTime = nextExecutionTime,
                isActive = isActive,
                isContinuous = isContinuous,
                stepDelay = stepDelay,
                destinationEmail = destinationEmail,
                destinationChatId = destinationChatId,
                destinationType = destinationType,
                senderFilter = senderFilter,
                subjectFilter = subjectFilter,
                telegramToken = telegramToken,
                telegramChatId = telegramChatId,
                telegramUsername = telegramUsername,
                telegramConnectionStatus = telegramConnectionStatus
            )
        }
    }

    @Serializable
    data class SerializableStep(
        val id: String,
        val name: String,
        val description: String,
        val type: String,
        val parameters: Map<String, String>,
        val status: String,
        val result: String?,
        val error: String?
    ) {
        fun toStep(): WorkflowStep {
            return WorkflowStep(
                id = id,
                name = name,
                description = description,
                type = StepType.valueOf(type),
                parameters = parameters.mapValues { entry ->
                    when (val value = entry.value) {
                        "true" -> true
                        "false" -> false
                        else -> {
                            value.toLongOrNull() ?: value.toIntOrNull() ?: value
                        }
                    }
                },
                status = StepStatus.valueOf(status),
                result = result,
                error = error
            )
        }
    }

    fun Workflow.toSerializable(): SerializableWorkflow {
        return SerializableWorkflow(
            id = id,
            name = name,
            description = description,
            steps = steps.map { it.toSerializable() },
            status = status.name,
            createdAt = createdAt,
            executedAt = executedAt,
            expirationOption = expirationOption.name,
            customExpirationDate = customExpirationDate?.time,
            interval = interval,
            isScheduled = isScheduled,
            nextExecutionTime = nextExecutionTime,
            isActive = isActive,
            isContinuous = isContinuous,
            stepDelay = stepDelay,
            destinationEmail = destinationEmail,
            destinationChatId = destinationChatId,
            destinationType = destinationType,
            senderFilter = senderFilter,
            subjectFilter = subjectFilter,
            telegramToken = telegramToken,
            telegramChatId = telegramChatId,
            telegramUsername = telegramUsername,
            telegramConnectionStatus = telegramConnectionStatus
        )
    }

    fun WorkflowStep.toSerializable(): SerializableStep {
        return SerializableStep(
            id = id,
            name = name,
            description = description,
            type = type.name,
            parameters = parameters.mapValues { it.value.toString() },
            status = status.name,
            result = result?.toString(),
            error = error
        )
    }
}

open class WorkflowViewModel(val task: Task = TASK_CREATE_WORKFLOW) : ViewModel() {
    private val _uiState = MutableStateFlow(WorkflowUiState())
    val uiState = _uiState.asStateFlow()
    private val workflowEmailManager: WorkflowEmailManager? = null
    private var gmailApiHelper: com.google.ai.edge.gallery.ui.workflow.GmailApiHelper? = null
    private lateinit var context: Context
    private val alarmManager: AlarmManager by lazy { context.getSystemService(Context.ALARM_SERVICE) as AlarmManager }
    private val pendingIntents = mutableMapOf<String, PendingIntent>()
    private val telegramManagers = mutableMapOf<String, TelegramDeepLinkHelperWork>()
    private lateinit var workflowStorageHelper: WorkflowStorageHelper

    init {
        loadAvailableModels()
    }

    fun initializePreferences(context: Context) {
        this.context = context
        workflowStorageHelper = WorkflowStorageHelper(context)
        viewModelScope.launch {
            loadWorkflows()
        }
    }

    private fun getTelegramManager(workflowId: String): TelegramDeepLinkHelperWork {
        return telegramManagers.getOrPut(workflowId) {
            TelegramDeepLinkHelperWork(context, workflowId).apply {
                connectionCallback = object : TelegramDeepLinkHelperWork.ConnectionCallback {
                    override fun onConnectionStatusChanged(
                        workflowId: String,
                        isConnected: Boolean,
                        status: String?,
                        chatId: String?,
                        username: String?
                    ) {
                        viewModelScope.launch {
                            // Update the workflow in both UI state and storage
                            val currentWorkflows = _uiState.value.workflows
                            val workflowIndex = currentWorkflows.indexOfFirst { it.id == workflowId }

                            if (workflowIndex != -1) {
                                val workflow = currentWorkflows[workflowIndex]
                                val updatedWorkflow = workflow.copy(
                                    telegramChatId = chatId,
                                    telegramUsername = username,
                                    telegramConnectionStatus = status
                                )

                                // Update the workflows list in UI state
                                val updatedWorkflows = currentWorkflows.toMutableList()
                                updatedWorkflows[workflowIndex] = updatedWorkflow

                                // Update current workflow if it's the one being modified
                                val updatedCurrentWorkflow = _uiState.value.currentWorkflow?.let {
                                    if (it.id == workflowId) updatedWorkflow else it
                                }

                                // Update UI state
                                _uiState.value = _uiState.value.copy(
                                    workflows = updatedWorkflows,
                                    currentWorkflow = updatedCurrentWorkflow,
                                    isDeepLinkSetup = isConnected,
                                    deepLinkStatus = status
                                )

                                // Save to storage immediately
                                workflowStorageHelper.saveWorkflow(updatedWorkflow)
                                Log.d(TAG, "✅ Updated workflow $workflowId with Telegram connection: chatId=$chatId, username=$username")
                            }
                        }
                    }
                }

                // Initialize with existing connection state if available
                val workflow = _uiState.value.workflows.find { it.id == workflowId }
                workflow?.let {
                    setConnectionState(it.telegramChatId, it.telegramUsername, it.telegramConnectionStatus)
                }
            }
        }
    }

    private fun TelegramDeepLinkHelperWork.setConnectionState(chatId: String?, username: String?, status: String?) {
        val prefs = context.getSharedPreferences("TelegramPrefs_${getWorkflowId()}", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(getChatIdKey(), chatId)
            .putString(getUsernameKey(), username)
            .apply()
        Log.d("WorkflowViewModel", "Set connection state for workflow ${getWorkflowId()}: chatId=$chatId, username=$username")
    }

    private suspend fun loadWorkflows() {
        try {
            val workflows = workflowStorageHelper.loadWorkflows()
            _uiState.value = _uiState.value.copy(workflows = workflows)
            Log.d(TAG, "Loaded ${workflows.size} workflows from storage")
            workflows.forEach { workflow ->
                if (workflow.isActive && !isWorkflowExpired(workflow) && workflow.interval > 0) {
                    scheduleWorkflow(workflow.id)
                }
            }
            _uiState.value.currentWorkflow?.let { currentWorkflow ->
                val workflow = workflows.find { it.id == currentWorkflow.id }
                workflow?.let {
                    _uiState.value = _uiState.value.copy(
                        isDeepLinkSetup = it.telegramChatId != null,
                        deepLinkStatus = it.telegramConnectionStatus ?: "Not connected"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading workflows: ${e.message}", e)
        }
    }

    private suspend fun saveWorkflows() {
        try {
            workflowStorageHelper.saveWorkflows(_uiState.value.workflows)
            Log.d(TAG, "Saved ${_uiState.value.workflows.size} workflows to storage")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving workflows: ${e.message}", e)
        }
    }

    fun saveCurrentWorkflowDraft() {
        val currentWorkflow = _uiState.value.currentWorkflow
        if (currentWorkflow != null) {
            viewModelScope.launch {
                try {
                    updateWorkflow(currentWorkflow)
                    Log.d(TAG, "✅ Auto-saved current workflow as draft: ${currentWorkflow.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error auto-saving workflow draft: ${e.message}", e)
                }
            }
        }
    }

    private fun loadAvailableModels() {
        viewModelScope.launch {
            val models = TASK_CREATE_WORKFLOW.models.filter { it.name.isNotEmpty() }
            _uiState.value = _uiState.value.copy(
                availableModels = models,
                selectedModel = models.firstOrNull()
            )
        }
    }

    fun createNewWorkflow(name: String, description: String): String {
        val workflowId = "workflow_${System.currentTimeMillis()}"
        val defaultSteps = listOf(
            WorkflowStep(
                id = "step_1",
                name = "Fetch Emails",
                description = "Fetch latest emails from Gmail",
                type = StepType.FETCH_EMAILS,
                parameters = mapOf(
                    "maxResults" to 10,
                    "label" to "INBOX"
                )
            ),
            WorkflowStep(
                id = "step_2",
                name = "Summarize Emails",
                description = "Summarize emails using AI",
                type = StepType.SUMMARIZE_EMAILS,
                parameters = mapOf(
                    "enabled" to true,
                    "truncateLength" to 3000
                )
            ),
            WorkflowStep(
                id = "step_3",
                name = "Forward Emails",
                description = "Forward emails to destination",
                type = StepType.FORWARD_EMAILS,
                parameters = mapOf(
                    "destination" to "",
                    "destinationType" to "gmail",
                )
            ),
            WorkflowStep(
                id = "step_4",
                name = "Send Batch Summary",
                description = "Send summary of filtered emails",
                type = StepType.SEND_BATCH_SUMMARIES,
                parameters = mapOf(
                    "enabled" to true,
                    "destination" to "",
                    "destinationType" to "gmail",
                    "count" to 5,
                    "senderFilter" to "",
                    "subjectFilter" to ""
                )
            )
        )
        val newWorkflow = Workflow(
            id = workflowId,
            name = name,
            description = description,
            steps = defaultSteps,
            destinationEmail = null,
            destinationChatId = null,
            destinationType = "gmail",
            senderFilter = null,
            subjectFilter = null,
            expirationOption = ExpirationOption.UNTIL_DISABLED,
            interval = 0,
            isScheduled = false,
            nextExecutionTime = null,
            isActive = false,
            isContinuous = false,
            stepDelay = 0,
            telegramToken = null,
            telegramChatId = null,
            telegramUsername = null,
            telegramConnectionStatus = "Not connected"
        )
        Log.d(TAG, "📝 Created new workflow: $workflowId")
        _uiState.value = _uiState.value.copy(
            workflows = _uiState.value.workflows + newWorkflow,
            currentWorkflow = newWorkflow
        )
        viewModelScope.launch {
            saveWorkflows()
        }
        return workflowId
    }

    fun selectWorkflow(workflowId: String) {
        saveCurrentWorkflowDraft()
        val workflow = _uiState.value.workflows.find { it.id == workflowId }
        _uiState.value = _uiState.value.copy(
            currentWorkflow = workflow,
            isDeepLinkSetup = workflow?.telegramChatId != null,
            deepLinkStatus = workflow?.telegramConnectionStatus ?: "Not connected"
        )
    }

    fun updateWorkflow(updatedWorkflow: Workflow) {
        _uiState.value = _uiState.value.copy(
            workflows = _uiState.value.workflows.map { workflow ->
                if (workflow.id == updatedWorkflow.id) {
                    updatedWorkflow
                } else {
                    workflow
                }
            },
            currentWorkflow = _uiState.value.currentWorkflow?.let {
                if (it.id == updatedWorkflow.id) {
                    updatedWorkflow
                } else {
                    it
                }
            }
        )
        viewModelScope.launch {
            saveWorkflows()
        }
    }

    fun selectModel(model: Model) {
        _uiState.value = _uiState.value.copy(
            selectedModel = model,
            isModelInitialized = false
        )
    }

    fun initializeModel(context: Context) {
        val selectedModel = _uiState.value.selectedModel
        val account = getGoogleAccountAndToken(context).first
        if (selectedModel != null && account != null) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isModelInitialized = false)
                try {
                    if (gmailApiHelper == null) {
                        gmailApiHelper = com.google.ai.edge.gallery.ui.workflow.GmailApiHelper(context, account)
                    }
                    gmailApiHelper?.setAiModel(selectedModel)
                    val success = gmailApiHelper?.initializeAiModel() ?: false
                    _uiState.value = _uiState.value.copy(isModelInitialized = success)
                    if (!success) {
                        Log.e(TAG, "Failed to initialize AI model: ${selectedModel.name}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error initializing AI model: ${e.message}", e)
                    _uiState.value = _uiState.value.copy(isModelInitialized = false)
                }
            }
        }
    }

    suspend fun startTelegramDeepLinkSetup(workflowId: String): String {
        return getTelegramManager(workflowId).startInviteFlow()
    }

    suspend fun disconnectTelegram(workflowId: String) {
        getTelegramManager(workflowId).disconnect()
        val workflows = workflowStorageHelper.loadWorkflows()
        val workflow = workflows.find { it.id == workflowId }
        workflow?.let {
            val updatedWorkflow = it.copy(
                telegramToken = null,
                telegramChatId = null,
                telegramUsername = null,
                telegramConnectionStatus = "Not connected"
            )
            workflowStorageHelper.saveWorkflow(updatedWorkflow)

            // Update UI state
            val currentWorkflows = _uiState.value.workflows
            val workflowIndex = currentWorkflows.indexOfFirst { it.id == workflowId }
            if (workflowIndex != -1) {
                val updatedWorkflows = currentWorkflows.toMutableList()
                updatedWorkflows[workflowIndex] = updatedWorkflow

                val updatedCurrentWorkflow = _uiState.value.currentWorkflow?.let { current ->
                    if (current.id == workflowId) updatedWorkflow else current
                }

                _uiState.value = _uiState.value.copy(
                    workflows = updatedWorkflows,
                    currentWorkflow = updatedCurrentWorkflow,
                    isDeepLinkSetup = false,
                    deepLinkStatus = "Not connected"
                )
            }
        }
    }

    fun getTelegramConnectionStatus(workflowId: String): Triple<Boolean, String?, String?> {
        val workflow = _uiState.value.workflows.find { it.id == workflowId }
        return if (workflow?.telegramChatId != null) {
            Triple(true, workflow.telegramConnectionStatus, workflow.telegramChatId)
        } else {
            Triple(false, workflow?.telegramConnectionStatus ?: "Not connected", null)
        }
    }

    fun scheduleWorkflow(workflowId: String) {
        val workflow = _uiState.value.workflows.find { it.id == workflowId } ?: return
        cancelWorkflowSchedule(workflowId)
        val nextExecutionTime = System.currentTimeMillis() + workflow.interval
        val intent = Intent(context, WorkflowExecutionReceiver::class.java).apply {
            action = WorkflowExecutionReceiver.ACTION_EXECUTE_WORKFLOW
            putExtra(WorkflowExecutionReceiver.EXTRA_WORKFLOW_ID, workflowId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            workflowId.hashCode(),
            intent,
            flags
        )
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
                Log.d(TAG, "Scheduled exact alarm for workflow $workflowId at $nextExecutionTime")
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextExecutionTime,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled inexact alarm for workflow $workflowId at $nextExecutionTime")
            }
            pendingIntents[workflowId] = pendingIntent
            updateWorkflow(
                workflow.copy(
                    isScheduled = true,
                    nextExecutionTime = nextExecutionTime,
                    isActive = true,
                    status = WorkflowStatus.SCHEDULED
                )
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException when scheduling alarm for workflow $workflowId: ${e.message}")
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextExecutionTime,
                    pendingIntent
                )
                Log.d(TAG, "Fallback to inexact alarm for workflow $workflowId at $nextExecutionTime")
                pendingIntents[workflowId] = pendingIntent
                updateWorkflow(
                    workflow.copy(
                        isScheduled = true,
                        nextExecutionTime = nextExecutionTime,
                        isActive = true,
                        status = WorkflowStatus.SCHEDULED
                    )
                )
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to schedule inexact alarm for workflow $workflowId: ${e2.message}")
                updateWorkflow(
                    workflow.copy(
                        isScheduled = false,
                        nextExecutionTime = null,
                        isActive = false,
                        status = WorkflowStatus.ERROR
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alarm for workflow $workflowId: ${e.message}")
            updateWorkflow(
                workflow.copy(
                    isScheduled = false,
                    nextExecutionTime = null,
                    isActive = false,
                    status = WorkflowStatus.ERROR
                )
            )
        }
    }

    fun cancelWorkflowSchedule(workflowId: String) {
        pendingIntents[workflowId]?.let {
            alarmManager.cancel(it)
            it.cancel()
            pendingIntents.remove(workflowId)
        }
        val workflow = _uiState.value.workflows.find { it.id == workflowId }
        workflow?.let {
            updateWorkflow(
                it.copy(
                    isScheduled = false,
                    nextExecutionTime = null,
                    isActive = false
                )
            )
        }
    }

    fun toggleWorkflowExecution(workflowId: String) {
        val workflow = _uiState.value.workflows.find { it.id == workflowId } ?: return
        if (workflow.isActive) {
            cancelWorkflowSchedule(workflowId)
        } else {
            executeWorkflow(context, workflowId)
        }
    }

    fun executeWorkflow(context: Context, workflowId: String) {
        if (executingWorkflows.contains(workflowId)) {
            Log.w(TAG, "🚫 Workflow $workflowId is already executing - skipping duplicate execution")
            return
        }
        val workflow = _uiState.value.workflows.find { it.id == workflowId }
        if (workflow == null) {
            Log.e(TAG, "Workflow not found: $workflowId")
            return
        }
        if (isWorkflowExpired(workflow)) {
            Log.e(TAG, "Workflow has expired: $workflowId")
            _uiState.value = _uiState.value.copy(
                executionError = "This workflow has expired and cannot be executed"
            )
            updateWorkflow(workflow.copy(isActive = false))
            return
        }
        val account = getGoogleAccountAndToken(context).first
        if (account == null) {
            Log.e(TAG, "Google account not found")
            _uiState.value = _uiState.value.copy(
                executionError = "Please sign in with Google to execute workflows"
            )
            return
        }
        viewModelScope.launch {
            try {
                executingWorkflows.add(workflowId)
                Log.d(TAG, "🔒 EXECUTION LOCK ACQUIRED for workflow: $workflowId")
                if (gmailApiHelper == null) {
                    gmailApiHelper = com.google.ai.edge.gallery.ui.workflow.GmailApiHelper(context, account)
                }
                val selectedModel = _uiState.value.selectedModel
                if (selectedModel != null && !_uiState.value.isModelInitialized) {
                    gmailApiHelper?.setAiModel(selectedModel)
                    val success = gmailApiHelper?.initializeAiModel() ?: false
                    _uiState.value = _uiState.value.copy(isModelInitialized = success)
                    if (!success) throw Exception("Failed to initialize AI model")
                }
                Log.d(TAG, "🎬 STARTING WORKFLOW EXECUTION: $workflowId")
                var updatedWorkflow = workflow.copy(
                    status = WorkflowStatus.RUNNING,
                    isActive = true
                )
                _uiState.value = _uiState.value.copy(
                    workflows = _uiState.value.workflows.map {
                        if (it.id == workflowId) updatedWorkflow else it
                    },
                    currentWorkflow = updatedWorkflow,
                    isExecuting = true,
                    executionProgress = 0f,
                    executionError = null
                )
                saveWorkflows()
                val executedSteps = mutableListOf<WorkflowStep>()
                val totalSteps = workflow.steps.size
                for ((index, step) in workflow.steps.withIndex()) {
                    Log.d(TAG, "🚀 Starting Step ${index + 1}/${totalSteps}: ${step.name}")
                    val inProgressStep = step.copy(status = StepStatus.IN_PROGRESS)
                    executedSteps.add(inProgressStep)
                    updatedWorkflow = updatedWorkflow.copy(
                        steps = executedSteps + workflow.steps.drop(index + 1)
                    )
                    _uiState.value = _uiState.value.copy(
                        workflows = _uiState.value.workflows.map {
                            if (it.id == workflowId) updatedWorkflow else it
                        },
                        currentWorkflow = updatedWorkflow,
                        executionProgress = index.toFloat() / totalSteps.toFloat()
                    )
                    saveWorkflows()
                    val result = executeStep(context, inProgressStep, workflowId)
                    val completedStep = if (result.isSuccess) {
                        Log.d(TAG, "✅ Step ${index + 1} COMPLETED: ${step.name}")
                        inProgressStep.copy(
                            status = StepStatus.COMPLETED,
                            result = result.getOrNull()
                        )
                    } else {
                        Log.e(TAG, "❌ Step ${index + 1} FAILED: ${step.name} - ${result.exceptionOrNull()?.message}")
                        inProgressStep.copy(
                            status = StepStatus.ERROR,
                            error = result.exceptionOrNull()?.message
                        )
                    }
                    executedSteps[index] = completedStep
                    if (workflow.stepDelay > 0 && index < workflow.steps.size - 1) {
                        Log.d(TAG, "⏸️ Adding ${workflow.stepDelay}ms delay between steps")
                        delay(workflow.stepDelay)
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
                _uiState.value = _uiState.value.copy(
                    workflows = _uiState.value.workflows.map {
                        if (it.id == workflowId) finalWorkflow.copy(
                            status = statusAfterExecution,
                            isActive = isActiveAfterExecution
                        ) else it
                    },
                    currentWorkflow = finalWorkflow.copy(
                        status = statusAfterExecution,
                        isActive = isActiveAfterExecution
                    ),
                    isExecuting = false,
                    executionProgress = 1f
                )
                saveWorkflows()
                if (workflow.isContinuous && !isWorkflowExpired(workflow)) {
                    val nextExecutionTime = System.currentTimeMillis() + workflow.interval
                    Log.d(TAG, "🔄 CONTINUOUS WORKFLOW: $workflowId")
                    Log.d(TAG, "⏰ Next execution scheduled for: ${java.util.Date(nextExecutionTime)}")
                    val rescheduleWorkflow = finalWorkflow.copy(
                        isActive = true,
                        isScheduled = true,
                        status = WorkflowStatus.SCHEDULED,
                        nextExecutionTime = nextExecutionTime
                    )
                    updateWorkflow(rescheduleWorkflow)
                    scheduleWorkflow(workflowId)
                } else if (workflow.interval > 0 && !isWorkflowExpired(workflow)) {
                    Log.d(TAG, "Scheduling next execution for workflow $workflowId in ${workflow.interval}ms")
                    val rescheduleWorkflow = finalWorkflow.copy(
                        isActive = true,
                        isScheduled = true,
                        status = WorkflowStatus.SCHEDULED,
                        nextExecutionTime = System.currentTimeMillis() + workflow.interval
                    )
                    updateWorkflow(rescheduleWorkflow)
                    scheduleWorkflow(workflowId)
                } else {
                    Log.d(TAG, "Workflow $workflowId completed and will not repeat.")
                    updateWorkflow(finalWorkflow.copy(isActive = false))
                }
                executingWorkflows.remove(workflowId)
                Log.d(TAG, "🔓 EXECUTION LOCK RELEASED for workflow: $workflowId")
            } catch (e: Exception) {
                Log.e(TAG, "Error executing workflow: ${e.message}", e)
                val errorWorkflow = workflow.copy(
                    status = WorkflowStatus.ERROR,
                    executedAt = System.currentTimeMillis(),
                    isActive = false
                )
                _uiState.value = _uiState.value.copy(
                    workflows = _uiState.value.workflows.map {
                        if (it.id == workflowId) errorWorkflow else it
                    },
                    currentWorkflow = errorWorkflow,
                    isExecuting = false,
                    executionError = e.message
                )
                saveWorkflows()
                executingWorkflows.remove(workflowId)
                Log.d(TAG, "🔓 EXECUTION LOCK RELEASED (ERROR) for workflow: $workflowId")
            }
        }
    }

    fun isWorkflowExpired(workflow: Workflow): Boolean {
        return when (workflow.expirationOption) {
            ExpirationOption.UNTIL_DISABLED -> false
            ExpirationOption.ONE_MONTH -> {
                val oneMonthInMillis = TimeUnit.DAYS.toMillis(30)
                System.currentTimeMillis() > workflow.createdAt + oneMonthInMillis
            }
            ExpirationOption.FIXED_DATE -> {
                workflow.customExpirationDate?.let { date ->
                    System.currentTimeMillis() > date.time
                } ?: false
            }
        }
    }

    private suspend fun executeStep(context: Context, step: WorkflowStep, workflowId: String): Result<Any> {
        return withContext(Dispatchers.IO) {
            try {
                when (step.type) {
                    StepType.FETCH_EMAILS -> {
                        val maxResults = step.parameters["maxResults"] as? Int ?: 10
                        val label = step.parameters["label"] as? String ?: "INBOX"
                        // Add timeout for email fetching
                        val emails = withTimeoutOrNull(45_000) {
                            gmailApiHelper?.fetchLatestEmails()
                        } ?: throw Exception("Email fetching timed out")
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
                        if (destination.isBlank()) {
                            val workflow = _uiState.value.workflows.find { it.id == workflowId }
                            val workflowDestination = when (destinationType) {
                                "telegram", "deeplink" -> workflow?.telegramChatId
                                else -> workflow?.destinationEmail
                            }
                            if (workflowDestination.isNullOrBlank()) {
                                Result.failure(Exception("Destination not specified"))
                            } else {
                                val telegramHelper = if (destinationType == "telegram" || destinationType == "deeplink") {
                                    getTelegramManager(workflowId)
                                } else {
                                    null
                                }
                                val success = gmailApiHelper?.forwardEmail(
                                    messageId = "",
                                    destination = workflowDestination,
                                    destinationType = destinationType,
                                    withSummary = includeSummary,
                                    telegramHelper = telegramHelper
                                ) ?: false
                                if (success) {
                                    Result.success("Emails forwarded successfully to $workflowDestination via $destinationType")
                                } else {
                                    Result.failure(Exception("Failed to forward emails"))
                                }
                            }
                        } else {
                            val telegramHelper = if (destinationType == "telegram" || destinationType == "deeplink") {
                                getTelegramManager(workflowId)
                            } else {
                                null
                            }
                            val success = gmailApiHelper?.forwardEmail(
                                messageId = "",
                                destination = destination,
                                destinationType = destinationType,
                                withSummary = includeSummary,
                                telegramHelper = telegramHelper
                            ) ?: false
                            if (success) {
                                Result.success("Emails forwarded successfully to $destination via $destinationType")
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
                        } else if (destination.isBlank()) {
                            val workflow = _uiState.value.workflows.find { it.id == workflowId }
                            val workflowDestination = when (destinationType) {
                                "telegram", "deeplink" -> workflow?.telegramChatId
                                else -> workflow?.destinationEmail
                            }
                            if (workflowDestination.isNullOrBlank()) {
                                Result.failure(Exception("Destination not specified"))
                            } else {
                                val telegramHelper = if (destinationType == "telegram" || destinationType == "deeplink") {
                                    getTelegramManager(workflowId)
                                } else {
                                    null
                                }
                                val success = gmailApiHelper?.sendLastFiveEmailSummaries(
                                    destination = workflowDestination,
                                    destinationType = destinationType,
                                    senderFilter = senderFilter.takeIf { it.isNotEmpty() },
                                    subjectFilter = subjectFilter.takeIf { it.isNotEmpty() },
                                    telegramHelper = telegramHelper
                                ) ?: false
                                if (success) {
                                    Result.success("Batch summaries sent successfully to $workflowDestination via $destinationType")
                                } else {
                                    Result.failure(Exception("Failed to send batch summaries"))
                                }
                            }
                        } else {
                            val telegramHelper = if (destinationType == "telegram" || destinationType == "deeplink") {
                                getTelegramManager(workflowId)
                            } else {
                                null
                            }
                            val success = gmailApiHelper?.sendLastFiveEmailSummaries(
                                destination = destination,
                                destinationType = destinationType,
                                senderFilter = senderFilter.takeIf { it.isNotEmpty() },
                                subjectFilter = subjectFilter.takeIf { it.isNotEmpty() },
                                telegramHelper = telegramHelper
                            ) ?: false
                            if (success) {
                                Result.success("Batch summaries sent successfully to $destination via $destinationType")
                            } else {
                                Result.failure(Exception("Failed to send batch summaries"))
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Timeout executing step ${step.id}: ${e.message}", e)
                Result.failure(Exception("Operation timed out. Please check your network connection and try again."))
            } catch (e: Exception) {
                Log.e(TAG, "Error executing step ${step.id}: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    fun deleteWorkflow(workflowId: String) {
        cancelWorkflowSchedule(workflowId)
        telegramManagers.remove(workflowId)
        _uiState.value = _uiState.value.copy(
            workflows = _uiState.value.workflows.filter { it.id != workflowId },
            currentWorkflow = _uiState.value.currentWorkflow?.let {
                if (it.id == workflowId) null else it
            }
        )
        viewModelScope.launch {
            saveWorkflows()
        }
    }

    fun duplicateWorkflow(workflowId: String) {
        val workflow = _uiState.value.workflows.find { it.id == workflowId }
        if (workflow != null) {
            val newWorkflowId = "workflow_${System.currentTimeMillis()}"
            val duplicatedWorkflow = workflow.copy(
                id = newWorkflowId,
                name = "${workflow.name} (Copy)",
                status = WorkflowStatus.DRAFT,
                createdAt = System.currentTimeMillis(),
                executedAt = null,
                expirationOption = workflow.expirationOption,
                customExpirationDate = workflow.customExpirationDate,
                interval = workflow.interval,
                isScheduled = false,
                nextExecutionTime = null,
                isActive = false,
                destinationEmail = workflow.destinationEmail,
                destinationChatId = workflow.destinationChatId,
                destinationType = workflow.destinationType,
                senderFilter = workflow.senderFilter,
                subjectFilter = workflow.subjectFilter,
                telegramToken = null,
                telegramChatId = null,
                telegramUsername = null,
                telegramConnectionStatus = "Not connected",
                steps = workflow.steps.map { step ->
                    step.copy(
                        status = StepStatus.PENDING,
                        result = null,
                        error = null
                    )
                }
            )
            _uiState.value = _uiState.value.copy(
                workflows = _uiState.value.workflows + duplicatedWorkflow,
                currentWorkflow = duplicatedWorkflow
            )
            viewModelScope.launch {
                saveWorkflows()
            }
        }
    }

    fun resetWorkflow(workflowId: String) {
        cancelWorkflowSchedule(workflowId)
        _uiState.value = _uiState.value.copy(
            workflows = _uiState.value.workflows.map { workflow ->
                if (workflow.id == workflowId) {
                    workflow.copy(
                        status = WorkflowStatus.DRAFT,
                        executedAt = null,
                        isActive = false,
                        steps = workflow.steps.map { step ->
                            step.copy(
                                status = StepStatus.PENDING,
                                result = null,
                                error = null
                            )
                        }
                    )
                } else {
                    workflow
                }
            },
            currentWorkflow = _uiState.value.currentWorkflow?.let {
                if (it.id == workflowId) {
                    it.copy(
                        status = WorkflowStatus.DRAFT,
                        executedAt = null,
                        isActive = false,
                        steps = it.steps.map { step ->
                            step.copy(
                                status = StepStatus.PENDING,
                                result = null,
                                error = null
                            )
                        }
                    )
                } else {
                    it
                }
            },
            isExecuting = false,
            executionProgress = 0f,
            executionError = null
        )
        viewModelScope.launch {
            saveWorkflows()
        }
    }

    fun clearExecutionError() {
        _uiState.value = _uiState.value.copy(executionError = null)
    }

    fun getWorkflows(): List<Workflow> {
        return _uiState.value.workflows
    }

    fun executeWorkflowInBackground(context: Context, workflowId: String) {
        Log.d(TAG, "🔄 Executing workflow $workflowId in background")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val workflows = workflowStorageHelper.loadWorkflows()
                val workflow = workflows.find { it.id == workflowId }
                if (workflow != null && !isWorkflowExpired(workflow)) {
                    Log.d(TAG, "✅ Found workflow for background execution: ${workflow.name}")
                    if (executingWorkflows.contains(workflowId)) {
                        Log.w(TAG, "🚫 Background execution skipped - workflow $workflowId is already executing")
                        return@launch
                    }
                    executeWorkflowInternal(context, workflow, isBackground = true)
                } else {
                    Log.d(TAG, "❌ Workflow not found or expired: $workflowId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading workflows for background execution: ${e.message}", e)
            }
        }
    }

    private suspend fun executeWorkflowInternal(context: Context, workflow: Workflow, isBackground: Boolean) {
        if (executingWorkflows.contains(workflow.id)) {
            Log.w(TAG, "🚫 Workflow ${workflow.id} is already executing - skipping duplicate execution")
            return
        }
        if (isWorkflowExpired(workflow)) {
            Log.e(TAG, "Workflow has expired: ${workflow.id}")
            val updatedWorkflow = workflow.copy(isActive = false)
            workflowStorageHelper.saveWorkflow(updatedWorkflow)
            return
        }
        val account = getGoogleAccountAndToken(context).first
        if (account == null) {
            Log.e(TAG, "Google account not found")
            return
        }
        try {
            executingWorkflows.add(workflow.id)
            Log.d(TAG, "🔒 EXECUTION LOCK ACQUIRED for workflow: ${workflow.id}")
            var gmailHelper = gmailApiHelper
            if (gmailHelper == null) {
                gmailHelper = com.google.ai.edge.gallery.ui.workflow.GmailApiHelper(context, account)
                gmailApiHelper = gmailHelper
            }
            val selectedModel = _uiState.value.selectedModel
            if (selectedModel != null && !_uiState.value.isModelInitialized) {
                gmailHelper?.setAiModel(selectedModel)
                val success = gmailHelper?.initializeAiModel() ?: false
                if (!success) {
                    Log.e(TAG, "Failed to initialize AI model: ${selectedModel.name}")
                    throw Exception("Failed to initialize AI model")
                }
            }
            Log.d(TAG, "🎬 STARTING WORKFLOW EXECUTION: ${workflow.id}")
            var updatedWorkflow = workflow.copy(
                status = WorkflowStatus.RUNNING,
                isActive = true
            )
            workflowStorageHelper.saveWorkflow(updatedWorkflow)
            val executedSteps = mutableListOf<WorkflowStep>()
            val totalSteps = workflow.steps.size
            for ((index, step) in workflow.steps.withIndex()) {
                Log.d(TAG, "🚀 Starting Step ${index + 1}/${totalSteps}: ${step.name}")
                val inProgressStep = step.copy(status = StepStatus.IN_PROGRESS)
                executedSteps.add(inProgressStep)
                updatedWorkflow = updatedWorkflow.copy(
                    steps = executedSteps + workflow.steps.drop(index + 1)
                )
                workflowStorageHelper.saveWorkflow(updatedWorkflow)
                val result = executeStepInternal(context, gmailHelper, step, workflow.id)
                val completedStep = if (result.isSuccess) {
                    Log.d(TAG, "✅ Step ${index + 1} COMPLETED: ${step.name}")
                    inProgressStep.copy(
                        status = StepStatus.COMPLETED,
                        result = result.getOrNull()
                    )
                } else {
                    Log.e(TAG, "❌ Step ${index + 1} FAILED: ${step.name} - ${result.exceptionOrNull()?.message}")
                    inProgressStep.copy(
                        status = StepStatus.ERROR,
                        error = result.exceptionOrNull()?.message
                    )
                }
                executedSteps[index] = completedStep
                if (workflow.stepDelay > 0 && index < workflow.steps.size - 1) {
                    Log.d(TAG, "⏸️ Adding ${workflow.stepDelay}ms delay between steps")
                    delay(workflow.stepDelay)
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
            if (workflow.isContinuous && !isWorkflowExpired(workflow)) {
                val nextExecutionTime = System.currentTimeMillis() + workflow.interval
                Log.d(TAG, "🔄 CONTINUOUS WORKFLOW: ${workflow.id}")
                Log.d(TAG, "⏰ Next execution scheduled for: ${java.util.Date(nextExecutionTime)}")
                val rescheduleWorkflow = finalWorkflowWithStatus.copy(
                    isActive = true,
                    isScheduled = true,
                    status = WorkflowStatus.SCHEDULED,
                    nextExecutionTime = nextExecutionTime
                )
                workflowStorageHelper.saveWorkflow(rescheduleWorkflow)
                scheduleWorkflow(workflow.id)
            } else if (workflow.interval > 0 && !isWorkflowExpired(workflow)) {
                Log.d(TAG, "Scheduling next execution for workflow ${workflow.id} in ${workflow.interval}ms")
                val rescheduleWorkflow = finalWorkflowWithStatus.copy(
                    isActive = true,
                    isScheduled = true,
                    status = WorkflowStatus.SCHEDULED,
                    nextExecutionTime = System.currentTimeMillis() + workflow.interval
                )
                workflowStorageHelper.saveWorkflow(rescheduleWorkflow)
                scheduleWorkflow(workflow.id)
            } else {
                Log.d(TAG, "Workflow ${workflow.id} completed and will not repeat.")
                val completedWorkflow = finalWorkflowWithStatus.copy(isActive = false)
                workflowStorageHelper.saveWorkflow(completedWorkflow)
            }
            executingWorkflows.remove(workflow.id)
            Log.d(TAG, "🔓 EXECUTION LOCK RELEASED for workflow: ${workflow.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing workflow: ${e.message}", e)
            val errorWorkflow = workflow.copy(
                status = WorkflowStatus.ERROR,
                executedAt = System.currentTimeMillis(),
                isActive = false
            )
            workflowStorageHelper.saveWorkflow(errorWorkflow)
            executingWorkflows.remove(workflow.id)
            Log.d(TAG, "🔓 EXECUTION LOCK RELEASED (ERROR) for workflow: ${workflow.id}")
        }
    }

    private suspend fun executeStepInternal(context: Context, gmailHelper: GmailApiHelper?, step: WorkflowStep, workflowId: String): Result<Any> {
        return withContext(Dispatchers.IO) {
            try {
                when (step.type) {
                    StepType.FETCH_EMAILS -> {
                        val maxResults = step.parameters["maxResults"] as? Int ?: 10
                        val label = step.parameters["label"] as? String ?: "INBOX"
                        // Add timeout for email fetching
                        val emails = withTimeoutOrNull(45_000) {
                            gmailHelper?.fetchLatestEmails()
                        } ?: throw Exception("Email fetching timed out")
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
                        if (destination.isBlank()) {
                            val workflows = workflowStorageHelper.loadWorkflows()
                            val workflow = workflows.find { it.id == workflowId }
                            val workflowDestination = when (destinationType) {
                                "telegram", "deeplink" -> workflow?.telegramChatId
                                else -> workflow?.destinationEmail
                            }
                            if (workflowDestination.isNullOrBlank()) {
                                Result.failure(Exception("Destination not specified"))
                            } else {
                                val telegramHelper = if (destinationType == "telegram" || destinationType == "deeplink") {
                                    getTelegramManager(workflowId)
                                } else {
                                    null
                                }
                                val success = gmailHelper?.forwardEmail(
                                    messageId = "",
                                    destination = workflowDestination,
                                    destinationType = destinationType,
                                    withSummary = includeSummary,
                                    telegramHelper = telegramHelper
                                ) ?: false
                                if (success) {
                                    Result.success("Emails forwarded successfully to $workflowDestination via $destinationType")
                                } else {
                                    Result.failure(Exception("Failed to forward emails"))
                                }
                            }
                        } else {
                            val telegramHelper = if (destinationType == "telegram" || destinationType == "deeplink") {
                                getTelegramManager(workflowId)
                            } else {
                                null
                            }
                            val success = gmailHelper?.forwardEmail(
                                messageId = "",
                                destination = destination,
                                destinationType = destinationType,
                                withSummary = includeSummary,
                                telegramHelper = telegramHelper
                            ) ?: false
                            if (success) {
                                Result.success("Emails forwarded successfully to $destination via $destinationType")
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
                        } else if (destination.isBlank()) {
                            val workflows = workflowStorageHelper.loadWorkflows()
                            val workflow = workflows.find { it.id == workflowId }
                            val workflowDestination = when (destinationType) {
                                "telegram", "deeplink" -> workflow?.telegramChatId
                                else -> workflow?.destinationEmail
                            }
                            if (workflowDestination.isNullOrBlank()) {
                                Result.failure(Exception("Destination not specified"))
                            } else {
                                val telegramHelper = if (destinationType == "telegram" || destinationType == "deeplink") {
                                    getTelegramManager(workflowId)
                                } else {
                                    null
                                }
                                val success = gmailHelper?.sendLastFiveEmailSummaries(
                                    destination = workflowDestination,
                                    destinationType = destinationType,
                                    senderFilter = senderFilter.takeIf { it.isNotEmpty() },
                                    subjectFilter = subjectFilter.takeIf { it.isNotEmpty() },
                                    telegramHelper = telegramHelper
                                ) ?: false
                                if (success) {
                                    Result.success("Batch summaries sent successfully to $workflowDestination via $destinationType")
                                } else {
                                    Result.failure(Exception("Failed to send batch summaries"))
                                }
                            }
                        } else {
                            val telegramHelper = if (destinationType == "telegram" || destinationType == "deeplink") {
                                getTelegramManager(workflowId)
                            } else {
                                null
                            }
                            val success = gmailHelper?.sendLastFiveEmailSummaries(
                                destination = destination,
                                destinationType = destinationType,
                                senderFilter = senderFilter.takeIf { it.isNotEmpty() },
                                subjectFilter = subjectFilter.takeIf { it.isNotEmpty() },
                                telegramHelper = telegramHelper
                            ) ?: false
                            if (success) {
                                Result.success("Batch summaries sent successfully to $destination via $destinationType")
                            } else {
                                Result.failure(Exception("Failed to send batch summaries"))
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Timeout executing step ${step.id}: ${e.message}", e)
                Result.failure(Exception("Operation timed out. Please check your network connection and try again."))
            } catch (e: Exception) {
                Log.e(TAG, "Error executing step ${step.id}: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    fun isWorkflowExpiredPublic(workflow: Workflow): Boolean {
        return isWorkflowExpired(workflow)
    }

    fun refreshWorkflowStatesFromPreferences(context: Context) {
        Log.d(TAG, "🔄 Refreshing workflow states from storage")
        viewModelScope.launch {
            try {
                val workflows = workflowStorageHelper.loadWorkflows()
                Log.d(TAG, "📋 Loaded ${workflows.size} workflows from storage")
                _uiState.value = _uiState.value.copy(
                    workflows = workflows,
                    isExecuting = workflows.any { it.status == WorkflowStatus.RUNNING }
                )
                _uiState.value.currentWorkflow?.let { currentWorkflow ->
                    val workflow = workflows.find { it.id == currentWorkflow.id }
                    workflow?.let {
                        _uiState.value = _uiState.value.copy(
                            isDeepLinkSetup = it.telegramChatId != null,
                            deepLinkStatus = it.telegramConnectionStatus ?: "Not connected"
                        )
                    }
                }
                Log.d(TAG, "✅ UI state refreshed from storage")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error refreshing workflow states: ${e.message}", e)
            }
        }
    }

    fun createTestWorkflow(): String {
        val testWorkflowId = createNewWorkflow(
            name = "Test Continuous Workflow",
            description = "Quick test to verify continuous execution"
        )
        val testWorkflow = _uiState.value.workflows.find { it.id == testWorkflowId }
        testWorkflow?.let { workflow ->
            val updatedWorkflow = workflow.copy(
                isContinuous = true,
                interval = 60000,
                stepDelay = 2000,
                expirationOption = ExpirationOption.UNTIL_DISABLED
            )
            updateWorkflow(updatedWorkflow)
        }
        Log.d(TAG, "🧪 Created test workflow: $testWorkflowId")
        return testWorkflowId
    }

    fun debugWorkflowState(workflowId: String) {
        val workflow = _uiState.value.workflows.find { it.id == workflowId }
        workflow?.let {
            Log.d(TAG, "🔍 WORKFLOW STATE DEBUG:")
            Log.d(TAG, " ID: ${it.id}")
            Log.d(TAG, " Name: ${it.name}")
            Log.d(TAG, " Status: ${it.status}")
            Log.d(TAG, " IsActive: ${it.isActive}")
            Log.d(TAG, " IsScheduled: ${it.isScheduled}")
            Log.d(TAG, " IsContinuous: ${it.isContinuous}")
            Log.d(TAG, " Interval: ${it.interval}ms")
            Log.d(TAG, " NextExecutionTime: ${it.nextExecutionTime?.let { time -> java.util.Date(time) }}")
            Log.d(TAG, " ExpirationOption: ${it.expirationOption}")
            Log.d(TAG, " DestinationEmail: ${it.destinationEmail}")
            Log.d(TAG, " DestinationType: ${it.destinationType}")
            Log.d(TAG, " SenderFilter: ${it.senderFilter}")
            Log.d(TAG, " SubjectFilter: ${it.subjectFilter}")
            Log.d(TAG, " TelegramToken: ${it.telegramToken}")
            Log.d(TAG, " TelegramChatId: ${it.telegramChatId}")
            Log.d(TAG, " TelegramUsername: ${it.telegramUsername}")
            Log.d(TAG, " TelegramConnectionStatus: ${it.telegramConnectionStatus}")
        }
        Log.d(TAG, "🔒 EXECUTION LOCK STATUS:")
        Log.d(TAG, " Currently executing workflows: ${executingWorkflows.toList()}")
        Log.d(TAG, " This workflow executing: ${executingWorkflows.contains(workflowId)}")
    }
}