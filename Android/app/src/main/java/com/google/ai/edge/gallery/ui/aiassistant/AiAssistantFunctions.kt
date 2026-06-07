package com.google.ai.edge.gallery.ui.aiassistant

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.workflow.Workflow
import com.google.ai.edge.gallery.ui.workflow.WorkflowStep
import com.google.ai.edge.gallery.ui.workflow.StepType
import com.google.ai.edge.gallery.ui.workflow.ExpirationOption
import com.google.ai.edge.gallery.ui.workflow.WorkflowExecutionReceiver
import com.google.ai.edge.gallery.ui.workflow.TelegramDeepLinkHelperWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile

/**
 * AI Assistant Functions - Handles alarms, reminders, and workflow automation
 */
class AiAssistantFunctions(private val context: Context) {

    companion object {
        private const val TAG = "AiAssistantFunctions"

        // Function definitions for the AI to understand available capabilities
        val AVAILABLE_FUNCTIONS = """
            Available Functions:
            1. email_summarize() - Create workflow to summarize recent emails using AI
            2. geofence_setup(location: String, radius: Int, action: String) - Setup location-based triggers
            3. camflow_analysis() - Open CamFlow for image analysis and text extraction
            
            Examples:
            - "Summarize my emails"
            - "Setup geofence for home with 100m radius to send notification"
            - "Open CamFlow to analyze images"
            - "Create email workflow with Telegram deep link"
        """.trimIndent()
    }

    // Conversation state for workflow creation
    data class WorkflowCreationState(
        val isCreating: Boolean = false,
        val workflowType: String? = null,
        val workflowName: String? = null,
        val workflowDescription: String? = null,
        val destinationType: String? = null, // "gmail" or "telegram"
        val destinationEmail: String? = null,
        val destinationChatId: String? = null,
        val senderFilter: String? = null,
        val subjectFilter: String? = null,
        val expirationOption: ExpirationOption? = null,
        val customExpirationDate: Date? = null,
        val executionInterval: Long? = null, // in milliseconds
        val currentStep: String? = null
    )

    // Intent tracking state to make AI stick to identified intent until satisfied
    data class IntentState(
        val activeIntent: String? = null,
        val intentData: MutableMap<String, Any> = mutableMapOf(),
        val isCompleted: Boolean = false,
        val stepCount: Int = 0,
        val maxSteps: Int = 5, // Prevent infinite loops
        val lastUserInput: String? = null
    )

    private var workflowCreationState = WorkflowCreationState()
    private var intentState = IntentState()

    // Workflow storage helper
    private val workflowStorageHelper = WorkflowStorageHelper(context)

    // Cache for workflows to avoid suspend function calls from non-suspend contexts
    @Volatile
    private var cachedWorkflows: List<Workflow>? = null
    private val cacheLock = Any()

    // Initialize cache when AiAssistantFunctions is created
    init {
        // Preload cache in background
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val workflows = workflowStorageHelper.loadWorkflows()
                synchronized(cacheLock) {
                    cachedWorkflows = workflows
                }
                Log.d(TAG, "Preloaded ${workflows.size} workflows into cache")
            } catch (e: Exception) {
                Log.e(TAG, "Error preloading workflows cache: ${e.message}", e)
            }
        }
    }

    /**
     * Get existing workflows from cache or storage
     */
    private fun getExistingWorkflows(): List<Workflow> {
        synchronized(cacheLock) {
            if (cachedWorkflows != null) {
                return cachedWorkflows!!
            }

            // If cache is not available, try to load synchronously with a timeout
            return try {
                val workflows = runBlocking {
                    withTimeout(500) { // 500ms timeout
                        workflowStorageHelper.loadWorkflows()
                    }
                }
                cachedWorkflows = workflows
                workflows
            } catch (e: Exception) {
                Log.e(TAG, "Error loading existing workflows: ${e.message}", e)
                emptyList()
            }
        }
    }

    /**
     * Update the workflows cache
     */
    private fun updateWorkflowsCache(workflows: List<Workflow>) {
        synchronized(cacheLock) {
            cachedWorkflows = workflows
        }
    }

    /**
     * Generate automatic workflow name with numbering
     */
    private fun generateAutoWorkflowName(): String {
        return generateAutoWorkflowName("Email Summary")
    }

    /**
     * Generate automatic workflow name based on destination type
     */
    private fun generateAutoWorkflowNameByDestination(destinationType: String?): String {
        val baseName = when (destinationType) {
            "telegram" -> "Telegram Summary"
            "gmail" -> "Email Summary"
            "deeplink" -> "Telegram Deep Link Summary"
            else -> "Email Summary" // Default fallback
        }
        return generateAutoWorkflowName(baseName)
    }

    /**
     * Generate automatic workflow name with numbering for any base name
     */
    private fun generateAutoWorkflowName(baseName: String): String {
        val existingWorkflows = getExistingWorkflows()

        Log.d(TAG, "🔍 Checking existing workflows for auto-naming...")
        Log.d(TAG, "📋 Found ${existingWorkflows.size} existing workflows")

        // Collect all existing numbers for this base name
        val existingNumbers = mutableSetOf<Int>()
        val namePattern = Regex("^${Regex.escape(baseName)}(?:\\s+#(\\d+))?$")

        for (workflow in existingWorkflows) {
            Log.d(TAG, "🔍 Checking workflow: '${workflow.name}'")
            val match = namePattern.find(workflow.name)
            if (match != null) {
                val numberStr = match.groupValues.getOrNull(1)
                if (numberStr != null) {
                    val number = numberStr.toIntOrNull() ?: 0
                    Log.d(TAG, "📊 Found numbered workflow: $baseName #$number")
                    existingNumbers.add(number)
                } else {
                    // If no number, it's the first one (base name only)
                    Log.d(TAG, "📊 Found base workflow: $baseName (no number)")
                    existingNumbers.add(1) // Treat base name as number 1
                }
            }
        }

        // Find the first available number (fill gaps)
        var nextNumber = 1
        while (existingNumbers.contains(nextNumber)) {
            nextNumber++
        }

        val generatedName = if (nextNumber == 1) {
            baseName // No number for the first one
        } else {
            "$baseName #$nextNumber"
        }

        Log.d(TAG, "✅ Generated auto-name: '$generatedName' (existing numbers: ${existingNumbers.sorted()}, next: $nextNumber)")
        return generatedName
    }

    /**
     * Process user input and execute appropriate functions
     */
    fun processUserInput(input: String): String {
        val lowerInput = input.lowercase()

        // Check if we're in the middle of workflow creation
        if (workflowCreationState.isCreating) {
            return handleWorkflowCreationConversation(input)
        }

        // Check if we have an active intent that needs to be completed
        if (intentState.activeIntent != null && !intentState.isCompleted) {
            return continueActiveIntent(input)
        }

        return when {
            // Email summarization - More flexible patterns
            (lowerInput.contains("email") && (lowerInput.contains("summar") || lowerInput.contains("summarize"))) ||
                    (lowerInput.contains("summar") || lowerInput.contains("summarize")) ||
                    lowerInput.contains("email digest") ||
                    lowerInput.contains("email summary") ||
                    lowerInput.contains("email automation") ||
                    lowerInput.contains("email workflow") ||
                    lowerInput.contains("create email") ||
                    lowerInput.contains("setup email") ||
                    lowerInput.contains("make email") -> {
                startIntent("email_summarize", input)
                // Check if this is a comprehensive request with all details
                if (isComprehensiveEmailRequest(input)) {
                    handleComprehensiveEmailSummarization(input)
                } else {
                    handleEmailSummarization()
                }
            }

            // Camera text extraction
            lowerInput.contains("camera") || lowerInput.contains("text") && lowerInput.contains("extract") -> {
                startIntent("camera_text", input)
                handleCameraTextExtraction()
            }

            // Geofencing
            lowerInput.contains("geofence") || lowerInput.contains("location") -> {
                startIntent("geofencing", input)
                handleGeofencingRequest(input)
            }

            // Help
            lowerInput.contains("help") || lowerInput.contains("functions") -> {
                AVAILABLE_FUNCTIONS
            }

            else -> {
                "I can help you with:\n" +
                        "• Email summarization\n" +
                        "• Camera text extraction\n" +
                        "• Geofencing setup\n\n" +
                        "Just ask me to do any of these tasks!"
            }
        }
    }

    /**
     * Process user input with LLM-identified intent
     */
    fun processUserInputWithIntent(input: String, intent: String): String? {
        // Check if we're in the middle of workflow creation
        if (workflowCreationState.isCreating) {
            return handleWorkflowCreationConversation(input)
        }

        // Check if we have an active intent that needs to be completed
        if (intentState.activeIntent != null && !intentState.isCompleted) {
            return continueActiveIntent(input)
        }

        // Handle workflow continuation intent
        if (intent == "workflow_continue") {
            // Check if we're in workflow creation state
            if (workflowCreationState.isCreating) {
                return handleWorkflowCreationConversation(input)
            }
            // Check if we have an active intent
            if (intentState.activeIntent != null && !intentState.isCompleted) {
                return continueActiveIntent(input)
            }
            // No active workflow, return null to let normal processing continue
            return null
        }

        // Start new intent if this is a new request
        if (intent != "general_chat" && intent != "help") {
            startIntent(intent, input)
        }

        return when (intent) {
            "email_summarize" -> {
                if (isComprehensiveEmailRequest(input)) {
                    handleComprehensiveEmailSummarization(input)
                } else {
                    handleEmailSummarization()
                }
            }

            "camflow" -> handleCamFlowRequest(input)
            "geofencing" -> handleGeofencingRequest(input)
            "help" -> AVAILABLE_FUNCTIONS
            else -> null // Let the LLM handle general chat
        }
    }

    /**
     * Handle conversational workflow creation
     */
    private fun handleWorkflowCreationConversation(input: String): String {
        return when (workflowCreationState.currentStep) {
            "name" -> handleWorkflowNameStep(input)
            "description" -> handleWorkflowDescriptionStep(input)
            "destination_type" -> handleDestinationTypeStep(input)
            "destination_email" -> handleDestinationEmailStep(input)
            "destination_chat_id" -> handleDestinationChatIdStep(input)
            "deeplink_setup" -> handleDeeplinkSetupStep(input)
            "deeplink_waiting" -> handleDeeplinkWaitingStep(input)
            "sender_filter" -> handleSenderFilterStep(input)
            "subject_filter" -> handleSubjectFilterStep(input)
            "expiration" -> handleExpirationStep(input)
            "execution_interval" -> handleExecutionIntervalStep(input)
            "custom_interval" -> handleCustomIntervalStep(input)
            "confirm" -> handleWorkflowConfirmation(input)
            else -> "❌ Error: Unknown workflow creation step"
        }
    }

    /**
     * Handle email summarization
     */
    private fun handleEmailSummarization(): String {
        // Start conversational workflow creation
        workflowCreationState = WorkflowCreationState(
            isCreating = true,
            workflowType = "email_summarization",
            currentStep = "name"
        )

        return "📧 Great! I'll help you create an email summarization workflow.\n\n" +
                "Let's start by giving your workflow a name.\n\n" +
                "**What would you like to call this workflow?**\n" +
                "(e.g., 'Daily Email Summary', 'Weekly Digest', 'Important Emails Only')"
    }

    /**
     * Handle comprehensive email summarization with all details in one prompt
     */
    private fun handleComprehensiveEmailSummarization(input: String): String {
        try {
            // Extract all information from the input
            val workflowDescription = extractWorkflowDescription(input)
            val destinationType = extractDestinationType(input)
            val destinationEmail = extractDestinationEmail(input)
            val destinationChatId = extractDestinationChatId(input)
            val senderFilter = extractSenderFilter(input)
            val subjectFilter = extractSubjectFilter(input)
            val expirationOption = extractExpirationOption(input)
            val executionInterval = extractExecutionInterval(input)

            // Extract name after destination type is known for better auto-naming
            val extractedName = extractWorkflowName(input)
            val workflowName = if (extractedName.isNullOrBlank() ||
                extractedName.lowercase() in listOf("workflow", "email", "summary", "summarize", "digest", "auto", "default")) {
                generateAutoWorkflowNameByDestination(destinationType)
            } else {
                extractedName
            }

            // Check what information is missing and ask for it
            val missingInfo = mutableListOf<String>()

            // Check destination
            if (destinationType == "gmail" && destinationEmail.isNullOrBlank()) {
                missingInfo.add("destination email")
            } else if (destinationType == "telegram" && destinationChatId.isNullOrBlank()) {
                missingInfo.add("Telegram chat ID")
            }

            // Check if interval was provided (not default)
            val hasCustomInterval = !isDefaultInterval(executionInterval) && hasTimeInformation(input)
            if (!hasCustomInterval) {
                missingInfo.add("execution interval")
            }

            // If we have missing information, start a smart conversation
            if (missingInfo.isNotEmpty()) {
                Log.d(TAG, "🔍 Smart conversation: Missing info: ${missingInfo.joinToString(", ")}")

                // Initialize workflow creation state with what we already have
                workflowCreationState = WorkflowCreationState(
                    isCreating = true,
                    workflowType = "email_summarization",
                    workflowName = workflowName,
                    workflowDescription = workflowDescription,
                    destinationType = destinationType,
                    destinationEmail = destinationEmail,
                    destinationChatId = destinationChatId,
                    senderFilter = senderFilter,
                    subjectFilter = subjectFilter,
                    expirationOption = expirationOption,
                    executionInterval = if (hasCustomInterval) executionInterval else null
                )

                // Ask for the first missing piece of information
                return askForMissingInformation(missingInfo)
            }

            // All information is available, create workflow directly
            val workflow = createComprehensiveWorkflow(
                name = workflowName,
                description = workflowDescription,
                destinationType = destinationType,
                destinationEmail = destinationEmail,
                destinationChatId = destinationChatId,
                senderFilter = senderFilter,
                subjectFilter = subjectFilter,
                expirationOption = expirationOption,
                executionInterval = executionInterval
            )

            saveWorkflow(workflow)

            // Set flag to show navigation dialog
            val prefs = context.getSharedPreferences("AI_ASSISTANT_PREFS", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("WORKFLOW_CREATED", true).apply()

            return "🎉 **Workflow Created Successfully!**\n\n" +
                    "✅ **${workflow.name}** has been created and is starting immediately!\n\n" +
                    "**Configuration:**\n" +
                    "• **Destination:** ${if (destinationType == "gmail") "📧 $destinationEmail" else "📱 Telegram: $destinationChatId"}\n" +
                    "• **Filters:** ${if (senderFilter != null || subjectFilter != null) "${if (senderFilter != null) "👤 Sender: $senderFilter " else ""}${if (subjectFilter != null) "📝 Subject: $subjectFilter" else ""}" else "🔍 No filters"}\n" +
                    "• **Schedule:** ${formatInterval(executionInterval)}\n" +
                    "• **Expiration:** ${formatExpiration(expirationOption)}\n\n" +
                    "**What it does:**\n" +
                    "• Fetches your emails\n" +
                    "• Summarizes them using AI\n" +
                    "• Sends summaries to your destination\n\n" +
                    "**Status:** 🟢 **STARTING NOW** - The workflow is executing immediately and will then run according to the schedule.\n\n" +
                    "**Next steps:**\n" +
                    "• Go to 'Create Workflow' section to view the running workflow\n" +
                    "• The workflow will start fetching emails right away\n" +
                    "• Click 'Edit' to modify settings if needed\n\n" +
                    "Is there anything else I can help you with?"

        } catch (e: Exception) {
            Log.e(TAG, "Error creating comprehensive workflow", e)
            return "❌ Error creating workflow: ${e.message}\n\n" +
                    "Let's try the step-by-step approach. Say 'email summarize' to start over."
        }
    }

    /**
     * Handle workflow name step
     */
    private fun handleWorkflowNameStep(input: String): String {
        val trimmedInput = input.trim()

        // If input is blank or generic, generate an automatic name
        if (trimmedInput.isBlank() ||
            trimmedInput.lowercase() in listOf("workflow", "email", "summary", "summarize", "digest", "auto", "default")) {

            // For interactive creation, we don't know the destination type yet, so use default
            val autoName = generateAutoWorkflowName()
            workflowCreationState = workflowCreationState.copy(
                workflowName = autoName,
                currentStep = "description"
            )

            return "✅ I've named your workflow: **$autoName**\n\n" +
                    "**Would you like to add a description?** (optional)\n" +
                    "You can say 'skip' to continue without a description."
        }

        workflowCreationState = workflowCreationState.copy(
            workflowName = trimmedInput,
            currentStep = "description"
        )

        return "✅ Workflow name set to: **$trimmedInput**\n\n" +
                "**Would you like to add a description?** (optional)\n" +
                "You can say 'skip' to continue without a description."
    }

    /**
     * Handle workflow description step
     */
    private fun handleWorkflowDescriptionStep(input: String): String {
        if (input.lowercase().contains("skip")) {
            workflowCreationState = workflowCreationState.copy(
                currentStep = "destination_type"
            )
        } else {
            workflowCreationState = workflowCreationState.copy(
                workflowDescription = input.trim(),
                currentStep = "destination_type"
            )
        }

        return "**Where would you like to send the email summaries?**\n\n" +
                "Choose one:\n" +
                "• **Gmail** - Send to an email address\n" +
                "• **Telegram** - Send to a Telegram chat (requires chat ID)\n" +
                "• **Deep Link** - Easy Telegram setup with invite link\n\n" +
                "Reply with 'gmail', 'telegram', or 'deeplink'"
    }

    /**
     * Handle destination type step
     */
    private fun handleDestinationTypeStep(input: String): String {
        val lowerInput = input.lowercase()

        when {
            lowerInput.contains("gmail") || lowerInput.contains("email") -> {
                // Update workflow name if it was auto-generated to be destination-specific
                val currentName = workflowCreationState.workflowName
                val newName = if (currentName == "Email Summary" || (currentName?.startsWith("Email Summary #") == true)) {
                    generateAutoWorkflowNameByDestination("gmail")
                } else {
                    currentName ?: generateAutoWorkflowNameByDestination("gmail")
                }

                workflowCreationState = workflowCreationState.copy(
                    destinationType = "gmail",
                    workflowName = newName,
                    currentStep = "destination_email"
                )

                val nameUpdateMessage = if (newName != currentName) {
                    "\n\n✅ Updated workflow name to: **$newName**"
                } else ""

                return "**What email address should receive the summaries?**\n\n" +
                        "Enter the email address where you want to receive the summaries.$nameUpdateMessage"
            }
            lowerInput.contains("telegram") -> {
                // Update workflow name if it was auto-generated to be destination-specific
                val currentName = workflowCreationState.workflowName
                val newName = if (currentName == "Email Summary" || (currentName?.startsWith("Email Summary #") == true)) {
                    generateAutoWorkflowNameByDestination("telegram")
                } else {
                    currentName ?: generateAutoWorkflowNameByDestination("telegram")
                }

                workflowCreationState = workflowCreationState.copy(
                    destinationType = "telegram",
                    workflowName = newName,
                    currentStep = "destination_chat_id"
                )

                val nameUpdateMessage = if (newName != currentName) {
                    "\n\n✅ Updated workflow name to: **$newName**"
                } else ""

                return "**What's your Telegram chat ID?**\n\n" +
                        "Enter your Telegram chat ID where you want to receive the summaries.$nameUpdateMessage"
            }
            lowerInput.contains("deeplink") || lowerInput.contains("deep link") || lowerInput.contains("deep") -> {
                // Update workflow name if it was auto-generated to be destination-specific
                val currentName = workflowCreationState.workflowName
                val newName = if (currentName == "Email Summary" || (currentName?.startsWith("Email Summary #") == true)) {
                    generateAutoWorkflowNameByDestination("deeplink")
                } else {
                    currentName ?: generateAutoWorkflowNameByDestination("deeplink")
                }

                workflowCreationState = workflowCreationState.copy(
                    destinationType = "deeplink",
                    workflowName = newName,
                    currentStep = "deeplink_setup"
                )

                val nameUpdateMessage = if (newName != currentName) {
                    "\n\n✅ Updated workflow name to: **$newName**"
                } else ""

                return "**📱 Deep Link Telegram Setup**\n\n" +
                        "I'll help you set up Telegram using a simple invite link!\n\n" +
                        "**How it works:**\n" +
                        "• I'll generate a unique invite link\n" +
                        "• You'll share it with the person who should receive summaries\n" +
                        "• They'll click the link and press 'Start' in Telegram\n" +
                        "• The connection will be established automatically\n\n" +
                        "**Ready to proceed?**\n" +
                        "Reply with 'yes' to start the deep link setup.$nameUpdateMessage"
            }
            else -> {
                return "❌ Please choose either 'gmail', 'telegram', or 'deeplink'"
            }
        }
    }

    /**
     * Handle destination email step
     */
    private fun handleDestinationEmailStep(input: String): String {
        if (!input.contains("@")) {
            return "❌ Please enter a valid email address (e.g., user@example.com)"
        }

        workflowCreationState = workflowCreationState.copy(
            destinationEmail = input.trim()
        )

        // Check what's missing and ask for the next required information
        return getNextRequiredStep()
    }

    /**
     * Handle destination chat ID step
     */
    private fun handleDestinationChatIdStep(input: String): String {
        val chatId = input.trim()

        // Validate Telegram chat ID format (should be numeric, 9+ digits)
        if (!chatId.matches(Regex("^\\d{9,}$"))) {
            return "❌ Please enter a valid Telegram chat ID.\n\n" +
                    "Telegram chat IDs are numeric and usually 9+ digits long.\n" +
                    "You can find your chat ID by:\n" +
                    "1. Sending a message to @userinfobot\n" +
                    "2. Or using @RawDataBot\n" +
                    "3. Or checking with @getidsbot\n\n" +
                    "Enter your Telegram chat ID:"
        }

        workflowCreationState = workflowCreationState.copy(
            destinationChatId = chatId
        )

        // Check what's missing and ask for the next required information
        return getNextRequiredStep()
    }

        /**
     * Handle deep link setup step
     */
    private fun handleDeeplinkSetupStep(input: String): String {
        val lowerInput = input.lowercase()

        if (lowerInput.contains("yes") || lowerInput.contains("proceed") || lowerInput.contains("start")) {
            try {
                // Initialize deep link manager if not already done
                val deepLinkManager = TelegramDeepLinkHelperWork(context, "temp_workflow")
                
                // Start the invite flow
                deepLinkManager.startInviteFlow()
                
                // Set a temporary chat ID for now (will be updated when connection is established)
                workflowCreationState = workflowCreationState.copy(
                    destinationChatId = "deeplink_pending",
                    currentStep = "deeplink_waiting"
                )

                return "**🔗 Deep Link Setup Started!**\n\n" +
                        "✅ I've generated a unique invite link for you.\n\n" +
                        "**Next Steps:**\n" +
                        "1. **Share the link** with the person who should receive summaries\n" +
                        "2. **They click the link** and open it in Telegram\n" +
                        "3. **They press 'Start'** in the Telegram chat\n" +
                        "4. **They see 'Connected!' message**\n\n" +
                        "**Status:** ⏳ Waiting for connection...\n\n" +
                        "**I'll try to detect the connection automatically.**\n" +
                        "If automatic detection doesn't work, you can:\n" +
                        "• **Say 'yes'** when they tell you they're connected\n" +
                        "• **Say 'connected'** or 'done' when ready\n" +
                        "• **Say 'skip'** to continue without waiting\n\n" +
                        "Let me know when they've connected!"
            } catch (e: Exception) {
                Log.e(TAG, "Error starting deep link setup", e)
                return "❌ Error starting deep link setup: ${e.message}\n\n" +
                        "Let's try a different approach. Would you like to:\n" +
                        "• Use regular Telegram (requires chat ID)\n" +
                        "• Use Gmail instead\n" +
                        "• Try deep link setup again"
            }
        } else {
            return "**📱 Deep Link Telegram Setup**\n\n" +
                    "I'll help you set up Telegram using a simple invite link!\n\n" +
                    "**How it works:**\n" +
                    "• I'll generate a unique invite link\n" +
                    "• You'll share it with the person who should receive summaries\n" +
                    "• They'll click the link and press 'Start' in Telegram\n" +
                    "• The connection will be established automatically\n\n" +
                    "**Ready to proceed?**\n" +
                    "Reply with 'yes' to start the deep link setup."
        }
    }

    /**
     * Handle deep link waiting step
     */
    private fun handleDeeplinkWaitingStep(input: String): String {
        val lowerInput = input.lowercase()

        if (lowerInput.contains("skip")) {
            // User wants to skip waiting and continue with workflow setup
            workflowCreationState = workflowCreationState.copy(
                destinationChatId = "deeplink_skipped",
                currentStep = "sender_filter"
            )

            return "**⏭️ Skipped Deep Link Setup**\n\n" +
                    "✅ Continuing with workflow setup. The deep link can be configured later in the workflow settings.\n\n" +
                    "**Do you want to filter emails by sender?** (optional)\n" +
                    "Enter email addresses or domains to filter by (e.g., 'boss@company.com', '@important.com')\n" +
                    "Or say 'skip' to include all senders."
        }

        // Check if user is confirming connection
        if (lowerInput.contains("yes") || lowerInput.contains("connected") || lowerInput.contains("done") || lowerInput.contains("ready")) {
            // Check if deep link connection has been established
            try {
                val deepLinkManager = TelegramDeepLinkHelperWork(context, "temp_workflow")
                val (isConnected, status, chatId) = deepLinkManager.checkConnectionStatus()
                
                if (isConnected && chatId != null) {
                    if (chatId != null && chatId.isNotEmpty()) {
                        workflowCreationState = workflowCreationState.copy(
                            destinationChatId = chatId,
                            currentStep = "sender_filter"
                        )

                        return "**✅ Deep Link Connected!**\n\n" +
                                "🎉 The Telegram connection has been established successfully!\n\n" +
                                "**Chat ID:** $chatId\n" +
                                "**Status:** Connected and ready\n\n" +
                                "**Do you want to filter emails by sender?** (optional)\n" +
                                "Enter email addresses or domains to filter by (e.g., 'boss@company.com', '@important.com')\n" +
                                "Or say 'skip' to include all senders."
                    } else {
                        return "**❌ Connection Issue**\n\n" +
                                "I can see the connection was established, but there's an issue retrieving the chat ID.\n\n" +
                                "**What you can do:**\n" +
                                "• **Try again** - Say 'yes' again to retry\n" +
                                "• **Say 'skip'** - Continue without deep link (can configure later)\n" +
                                "• **Check Telegram** - Make sure the person pressed 'Start' in the bot chat"
                    }
                } else {
                    return "**❌ Not Connected Yet**\n\n" +
                            "I don't see an active connection yet. Please make sure:\n\n" +
                            "1. **The person clicked the invite link**\n" +
                            "2. **They opened it in Telegram**\n" +
                            "3. **They pressed the 'Start' button**\n" +
                            "4. **They saw a 'Connected!' message**\n\n" +
                            "**What you can do:**\n" +
                            "• **Wait and try again** - Say 'yes' when they've connected\n" +
                            "• **Say 'skip'** - Continue without deep link (can configure later)\n" +
                            "• **Check the link** - Make sure they received the correct invite link"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking deep link status", e)
                return "**❌ Error Checking Connection**\n\n" +
                        "There was an error checking the connection status.\n\n" +
                        "**What you can do:**\n" +
                        "• **Try again** - Say 'yes' to retry\n" +
                        "• **Say 'skip'** - Continue without deep link (can configure later)\n" +
                        "• **Restart the app** - If the issue persists"
            }
        }

        // Try automatic detection first
        try {
            val deepLinkManager = TelegramDeepLinkHelperWork(context, "temp_workflow")
            val (isConnected, status, chatId) = deepLinkManager.checkConnectionStatus()
            
            if (isConnected && chatId != null) {
                if (chatId != null && chatId.isNotEmpty()) {
                    workflowCreationState = workflowCreationState.copy(
                        destinationChatId = chatId,
                        currentStep = "sender_filter"
                    )

                    return "**✅ Deep Link Connected Automatically!**\n\n" +
                            "🎉 I detected the Telegram connection automatically!\n\n" +
                            "**Chat ID:** $chatId\n" +
                            "**Status:** Connected and ready\n\n" +
                            "**Do you want to filter emails by sender?** (optional)\n" +
                            "Enter email addresses or domains to filter by (e.g., 'boss@company.com', '@important.com')\n" +
                            "Or say 'skip' to include all senders."
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in automatic connection detection", e)
        }

        // If automatic detection failed, ask for user confirmation
        return "**⏳ Waiting for Connection...**\n\n" +
                "I've generated the invite link and shared it with you.\n\n" +
                "**Next Steps:**\n" +
                "1. **Share the link** with the person who should receive summaries\n" +
                "2. **They click the link** and open it in Telegram\n" +
                "3. **They press 'Start'** in the Telegram chat\n" +
                "4. **They see 'Connected!' message**\n\n" +
                "**I'm trying to detect the connection automatically...**\n" +
                "If automatic detection doesn't work, you can:\n" +
                "• **Say 'yes'** when they tell you they're connected\n" +
                "• **Say 'connected'** or 'done' when ready\n" +
                "• **Say 'skip'** to continue without waiting\n\n" +
                "**Status:** Waiting for connection (automatic detection active)..."
    }

    /**
     * Handle sender filter step
     */
    private fun handleSenderFilterStep(input: String): String {
        if (input.lowercase().contains("skip")) {
            workflowCreationState = workflowCreationState.copy(
                currentStep = "subject_filter"
            )
        } else {
            workflowCreationState = workflowCreationState.copy(
                senderFilter = input.trim(),
                currentStep = "subject_filter"
            )
        }

        return "**Do you want to filter emails by subject?** (optional)\n" +
                "Enter keywords to filter by (e.g., 'urgent', 'meeting', 'report')\n" +
                "Or say 'skip' to include all subjects."
    }

    /**
     * Handle subject filter step
     */
    private fun handleSubjectFilterStep(input: String): String {
        if (input.lowercase().contains("skip")) {
            workflowCreationState = workflowCreationState.copy(
                currentStep = "expiration"
            )
        } else {
            workflowCreationState = workflowCreationState.copy(
                subjectFilter = input.trim(),
                currentStep = "expiration"
            )
        }

        return "**When should this workflow expire?**\n\n" +
                "Choose one:\n" +
                "• **Never** - Run until you disable it\n" +
                "• **1 Month** - Stop after 1 month\n" +
                "• **Custom Date** - Specify a custom end date\n\n" +
                "Reply with 'never', '1 month', or 'custom'"
    }

    /**
     * Handle expiration step
     */
    private fun handleExpirationStep(input: String): String {
        val lowerInput = input.lowercase()

        when {
            lowerInput.contains("never") -> {
                workflowCreationState = workflowCreationState.copy(
                    expirationOption = ExpirationOption.UNTIL_DISABLED,
                    currentStep = "execution_interval"
                )
            }
            lowerInput.contains("1 month") || lowerInput.contains("month") -> {
                workflowCreationState = workflowCreationState.copy(
                    expirationOption = ExpirationOption.ONE_MONTH,
                    currentStep = "execution_interval"
                )
            }
            lowerInput.contains("custom") -> {
                workflowCreationState = workflowCreationState.copy(
                    expirationOption = ExpirationOption.FIXED_DATE,
                    currentStep = "custom_date"
                )
                return "**What date should the workflow expire?**\n\n" +
                        "Enter the date in format: YYYY-MM-DD (e.g., 2024-12-31)"
            }
            else -> {
                return "❌ Please choose 'never', '1 month', or 'custom'"
            }
        }

        return "**How often should this workflow run?**\n\n" +
                "Choose one:\n" +
                "• **Daily** - Run once every day\n" +
                "• **Weekly** - Run once every week\n" +
                "• **Every 6 hours** - Run 4 times per day\n" +
                "• **Custom** - Specify custom interval\n\n" +
                "Reply with 'daily', 'weekly', '6 hours', or 'custom'"
    }

    /**
     * Handle execution interval step
     */
    private fun handleExecutionIntervalStep(input: String): String {
        val lowerInput = input.lowercase()

        val interval = when {
            lowerInput.contains("daily") -> TimeUnit.DAYS.toMillis(1)
            lowerInput.contains("weekly") -> TimeUnit.DAYS.toMillis(7)
            lowerInput.contains("6 hours") -> TimeUnit.HOURS.toMillis(6)
            lowerInput.contains("custom") -> {
                workflowCreationState = workflowCreationState.copy(
                    currentStep = "custom_interval"
                )
                return "**How many hours between executions?**\n\n" +
                        "Enter a number (e.g., 12 for every 12 hours)"
            }
            else -> {
                return "❌ Please choose 'daily', 'weekly', '6 hours', or 'custom'"
            }
        }

        workflowCreationState = workflowCreationState.copy(
            executionInterval = interval
        )

        // Check what's missing and ask for the next required information
        return getNextRequiredStep()
    }

    /**
     * Handle custom interval step
     */
    private fun handleCustomIntervalStep(input: String): String {
        try {
            // Pattern to match numbers followed by optional time units (h, hours, m, minutes)
            val timePattern = Regex("""(\d+)\s*(h|hours?|m|minutes?)?""", RegexOption.IGNORE_CASE)
            val match = timePattern.find(input.trim())
            if (match != null) {
                val number = match.groupValues[1].toInt()
                val unit = match.groupValues[2].lowercase()
                if (number <= 0) {
                    return "❌ Please enter a positive number (e.g., 30m, 2h, 90 minutes)"
                }
                val interval = when {
                    unit.isEmpty() || unit == "h" || unit == "hour" || unit == "hours" -> {
                        // Default to hours if no unit specified or hours specified
                        if (number > 8760) { // More than 1 year
                            return "❌ Please enter a reasonable number of hours (max 8760 = 1 year)"
                        }
                        TimeUnit.HOURS.toMillis(number.toLong())
                    }
                    unit == "m" || unit == "minute" || unit == "minutes" -> {
                        if (number > 525600) { // More than 1 year in minutes
                            return "❌ Please enter a reasonable number of minutes (max 525600 = 1 year)"
                        }
                        TimeUnit.MINUTES.toMillis(number.toLong())
                    }
                    else -> {
                        // Default to hours for any other case
                        if (number > 8760) {
                            return "❌ Please enter a reasonable number of hours (max 8760 = 1 year)"
                        }
                        TimeUnit.HOURS.toMillis(number.toLong())
                    }
                }
                workflowCreationState = workflowCreationState.copy(
                    executionInterval = interval
                )
                // Check what's missing and ask for the next required information
                return getNextRequiredStep()
            } else {
                return "❌ Please enter a number with optional time unit (e.g., 30m, 2h, 90 minutes, 12 hours)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing custom interval", e)
            return "❌ Error parsing interval. Please enter a valid number with time unit (e.g., 30m, 2h)."
        }
    }

    /**
     * Generate workflow preview
     */
    private fun generateWorkflowPreview(): String {
        val state = workflowCreationState

        val destination = when (state.destinationType) {
            "gmail" -> "📧 Email: ${state.destinationEmail}"
            "telegram" -> "📱 Telegram: ${state.destinationChatId}"
            else -> "❓ Not set"
        }

        val filters = buildString {
            if (state.senderFilter != null) append("👤 Sender: ${state.senderFilter}\n")
            if (state.subjectFilter != null) append("📝 Subject: ${state.subjectFilter}\n")
            if (state.senderFilter == null && state.subjectFilter == null) append("🔍 No filters\n")
        }

        val expiration = when (state.expirationOption) {
            ExpirationOption.UNTIL_DISABLED -> "♾️ Never expire"
            ExpirationOption.ONE_MONTH -> "📅 Expire in 1 month"
            ExpirationOption.FIXED_DATE -> "📅 Custom expiration"
            else -> "❓ Not set"
        }

        val interval = when (state.executionInterval) {
            TimeUnit.DAYS.toMillis(1) -> "📅 Daily"
            TimeUnit.DAYS.toMillis(7) -> "📅 Weekly"
            TimeUnit.HOURS.toMillis(6) -> "⏰ Every 6 hours"
            TimeUnit.HOURS.toMillis(12) -> "⏰ Every 12 hours"
            else -> {
                // For custom intervals, calculate and display the appropriate time unit
                val intervalMs = state.executionInterval ?: 0
                val hours = TimeUnit.MILLISECONDS.toHours(intervalMs)
                val minutes = TimeUnit.MILLISECONDS.toMinutes(intervalMs)

                when {
                    hours >= 1 -> "⏰ Every $hours hours"
                    minutes >= 1 -> "⏰ Every $minutes minutes"
                    else -> "⏰ Every ${TimeUnit.MILLISECONDS.toSeconds(intervalMs)} seconds"
                }
            }
        }

        return "📋 **Workflow Preview**\n\n" +
                "**Name:** ${state.workflowName}\n" +
                "**Description:** ${state.workflowDescription ?: "None"}\n" +
                "**Destination:** $destination\n" +
                "**Filters:**\n$filters" +
                "**Expiration:** $expiration\n" +
                "**Schedule:** $interval\n\n" +
                "**Does this look correct?**\n" +
                "Reply with 'yes' to create the workflow, or 'no' to start over."
    }

    /**
     * Handle workflow confirmation
     */
    private fun handleWorkflowConfirmation(input: String): String {
        val lowerInput = input.lowercase()

        if (lowerInput.contains("yes") || lowerInput.contains("create")) {
            try {
                val workflow = createWorkflowFromState()
                saveWorkflow(workflow)

                // Set flag to show navigation dialog
                val prefs = context.getSharedPreferences("AI_ASSISTANT_PREFS", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("WORKFLOW_CREATED", true).apply()

                // Reset workflow creation state
                workflowCreationState = WorkflowCreationState()

                return "🎉 **Workflow Created Successfully!**\n\n" +
                        "✅ **${workflow.name}** has been created and is starting immediately!\n\n" +
                        "**What it does:**\n" +
                        "• Fetches your emails\n" +
                        "• Summarizes them using AI\n" +
                        "• Sends summaries to your destination\n\n" +
                        "**Next steps:**\n" +
                        "• The workflow is executing right now and will start fetching emails\n" +
                        "• Go to 'Create Workflow' section to view the running workflow\n" +
                        "• You can edit, pause, or delete it anytime\n\n" +
                        "Is there anything else I can help you with?"
            } catch (e: Exception) {
                Log.e(TAG, "Error creating workflow", e)
                return "❌ Error creating workflow: ${e.message}\n\n" +
                        "Let's try again. Say 'email summarize' to start over."
            }
        } else if (lowerInput.contains("no")) {
            // Reset workflow creation state
            workflowCreationState = WorkflowCreationState()
            return "🔄 No problem! Let's start over.\n\n" +
                    "Say 'email summarize' to create a new workflow."
        } else {
            return "❓ Please reply with 'yes' to create the workflow, or 'no' to start over."
        }
    }

    /**
     * Create workflow from conversation state
     */
    private fun createWorkflowFromState(): Workflow {
        val state = workflowCreationState
        val workflowId = "email_summarization_${System.currentTimeMillis()}"

        val steps = listOf(
            WorkflowStep(
                id = "fetch_emails",
                name = "Fetch Emails",
                description = "Fetch recent emails from Gmail",
                type = StepType.FETCH_EMAILS,
                parameters = mapOf(
                    "maxEmails" to 10,
                    "includeAttachments" to false,
                    "senderFilter" to (state.senderFilter ?: ""),
                    "subjectFilter" to (state.subjectFilter ?: "")
                )
            ),
            WorkflowStep(
                id = "summarize_emails",
                name = "Summarize Emails",
                description = "Use AI to summarize email content",
                type = StepType.SUMMARIZE_EMAILS,
                parameters = mapOf(
                    "modelName" to "Gemma-3n-E2B-it-int4",
                    "maxSummaryLength" to 200
                )
            ),
            WorkflowStep(
                id = "forward_summaries",
                name = "Forward Summaries",
                description = "Forward summaries to specified destination",
                type = StepType.FORWARD_EMAILS,
                parameters = mapOf(
                    "destinationType" to (state.destinationType ?: "gmail"),
                    "destination" to (if (state.destinationType == "telegram") (state.destinationChatId ?: "") else (state.destinationEmail ?: "")),
                    "destinationEmail" to (state.destinationEmail ?: ""),
                    "destinationChatId" to (state.destinationChatId ?: ""),
                    "includeSummary" to true,
                    "subject" to "Email Summaries - ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}"
                )
            ),
            WorkflowStep(
                id = "send_batch_summaries",
                name = "Send Batch Summaries",
                description = "Send email summaries to destination",
                type = StepType.SEND_BATCH_SUMMARIES,
                parameters = mapOf(
                    "enabled" to true,
                    "destination" to (if (state.destinationType == "telegram") (state.destinationChatId ?: "") else (state.destinationEmail ?: "")),
                    "destinationEmail" to (state.destinationEmail ?: ""),
                    "destinationChatId" to (state.destinationChatId ?: ""),
                    "destinationType" to (state.destinationType ?: "gmail"),
                    "count" to 5,
                    "senderFilter" to (state.senderFilter ?: ""),
                    "subjectFilter" to (state.subjectFilter ?: "")
                )
            )
        )

        return Workflow(
            id = workflowId,
            name = state.workflowName ?: generateAutoWorkflowName(),
            description = state.workflowDescription ?: "Automatically fetch, summarize, and forward emails",
            steps = steps,
            status = com.google.ai.edge.gallery.ui.workflow.WorkflowStatus.RUNNING,
            interval = state.executionInterval ?: TimeUnit.HOURS.toMillis(6),
            isScheduled = false, // Don't schedule, run immediately
            isActive = true,
            stepDelay = 5000,
            expirationOption = state.expirationOption ?: ExpirationOption.UNTIL_DISABLED,
            customExpirationDate = state.customExpirationDate
        )
    }

    /**
     * Save workflow to storage
     */
    private fun saveWorkflow(workflow: Workflow) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Get existing workflows
                val existingWorkflows = workflowStorageHelper.loadWorkflows()

                Log.d(TAG, "🔍 Checking for duplicates before saving workflow: ${workflow.name} (ID: ${workflow.id})")
                Log.d(TAG, "📋 Existing workflows: ${existingWorkflows.size}")
                existingWorkflows.forEach { existing ->
                    Log.d(TAG, "   - ${existing.name} (ID: ${existing.id})")
                }

                // Check for duplicate ID
                val duplicateById = existingWorkflows.find { it.id == workflow.id }
                if (duplicateById != null) {
                    Log.w(TAG, "⚠️ Duplicate workflow ID found! Skipping save.")
                    Log.w(TAG, "   Existing: ${duplicateById.name} (ID: ${duplicateById.id})")
                    Log.w(TAG, "   New: ${workflow.name} (ID: ${workflow.id})")
                    return@launch
                }

                // Check for duplicate name (optional - you might want to allow this)
                val duplicateByName = existingWorkflows.find { it.name == workflow.name }
                if (duplicateByName != null) {
                    Log.w(TAG, "⚠️ Workflow with same name already exists!")
                    Log.w(TAG, "   Existing: ${duplicateByName.name} (ID: ${duplicateByName.id})")
                    Log.w(TAG, "   New: ${workflow.name} (ID: ${workflow.id})")
                    // Continue anyway - this might be intentional
                }

                // Add new workflow
                val updatedWorkflows = existingWorkflows + workflow

                // Save back to storage
                workflowStorageHelper.saveWorkflows(updatedWorkflows)

                // Update cache
                updateWorkflowsCache(updatedWorkflows)

                Log.d(TAG, "✅ Saved workflow: ${workflow.name} with ID: ${workflow.id}")
                Log.d(TAG, "📊 Total workflows after save: ${updatedWorkflows.size}")

                // Trigger immediate execution for AI-created workflows
                if (workflow.isActive && workflow.status == com.google.ai.edge.gallery.ui.workflow.WorkflowStatus.RUNNING) {
                    triggerImmediateExecution(workflow)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving workflow: ${e.message}", e)
            }
        }
    }

    /**
     * Trigger immediate execution of a workflow
     */
    private fun triggerImmediateExecution(workflow: Workflow) {
        try {
            Log.d(TAG, "🚀 Triggering immediate execution for workflow: ${workflow.name} (${workflow.id})")

            // Create intent to execute workflow immediately
            val intent = Intent(context, WorkflowExecutionReceiver::class.java).apply {
                action = WorkflowExecutionReceiver.ACTION_EXECUTE_WORKFLOW
                putExtra(WorkflowExecutionReceiver.EXTRA_WORKFLOW_ID, workflow.id)
            }

            // Send broadcast to trigger immediate execution
            context.sendBroadcast(intent)

            Log.d(TAG, "✅ Immediate execution triggered for workflow: ${workflow.id}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to trigger immediate execution for workflow ${workflow.id}: ${e.message}", e)
        }
    }

    /**
     * Handle camera text extraction
     */
    private fun handleCameraTextExtraction(): String {
        try {
            // Create camera text extraction workflow
            val workflow = createCameraTextExtractionWorkflow()
            saveWorkflow(workflow)

            return "📷 Camera text extraction workflow created!\n\n" +
                    "I've created a workflow that will:\n" +
                    "• Capture images from camera\n" +
                    "• Extract text using OCR\n" +
                    "• Save extracted text to file\n\n" +
                    "✅ Workflow: '${workflow.name}'\n" +
                    "📋 Steps: Capture → Extract → Save\n" +
                    "⏰ Schedule: Runs on demand\n\n" +
                    "You can manage this workflow in the 'Create Workflow' section!"
        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera text extraction workflow", e)
            return "❌ Error creating workflow: ${e.message}"
        }
    }

    /**
     * Create camera text extraction workflow
     */
    private fun createCameraTextExtractionWorkflow(): Workflow {
        val workflowId = "camera_text_extraction_${System.currentTimeMillis()}"

        val steps = listOf(
            WorkflowStep(
                id = "capture_image",
                name = "Capture Image",
                description = "Capture image from camera",
                type = StepType.FETCH_EMAILS, // Reusing for now
                parameters = mapOf(
                    "cameraMode" to "auto",
                    "imageQuality" to "high"
                )
            ),
            WorkflowStep(
                id = "extract_text",
                name = "Extract Text",
                description = "Extract text from captured image",
                type = StepType.SUMMARIZE_EMAILS, // Reusing for now
                parameters = mapOf(
                    "ocrEngine" to "tesseract",
                    "language" to "eng"
                )
            ),
            WorkflowStep(
                id = "save_text",
                name = "Save Text",
                description = "Save extracted text to file",
                type = StepType.FORWARD_EMAILS, // Reusing for now
                parameters = mapOf(
                    "outputFormat" to "txt",
                    "saveLocation" to "documents/extracted_text"
                )
            )
        )

        return Workflow(
            id = workflowId,
            name = "Camera Text Extraction",
            description = "Automatically capture images and extract text",
            steps = steps,
            status = com.google.ai.edge.gallery.ui.workflow.WorkflowStatus.DRAFT,
            interval = 0, // Run on demand
            isScheduled = false,
            isActive = true,
            stepDelay = 3000 // 3 second delay between steps
        )
    }

    /**
     * Handle geofencing requests
     */
    private fun handleGeofencingRequest(input: String): String {
        try {
            // Set a flag in SharedPreferences to indicate geofencing should be opened
            val prefs = context.getSharedPreferences("AI_ASSISTANT_PREFS", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("OPEN_GEOFENCING_MAP", true).apply()

            Log.d(TAG, "📍 Set flag to open geofencing map")

            return "📍 **Opening Geofencing Map!**\n\n" +
                    "I'm opening the geofencing map where you can:\n" +
                    "• 📍 **Select a location** on the map\n" +
                    "• 📏 **Set the radius** for your geofence\n" +
                    "• 🔗 **Connect to Telegram** for notifications\n" +
                    "• ⚡ **Activate geofencing** monitoring\n\n" +
                    "The map will open in a new screen where you can configure your geofence settings!"
        } catch (e: Exception) {
            Log.e(TAG, "Error setting geofencing flag", e)
            return "❌ Error opening geofencing map: ${e.message}\n\n" +
                    "You can manually navigate to the Maps section to set up geofencing."
        }
    }

    /**
     * Handle CamFlow request and navigate to CamFlow screen
     */
    private fun handleCamFlowRequest(input: String): String {
        try {
            // Set a flag in SharedPreferences to indicate CamFlow should be opened
            val prefs = context.getSharedPreferences("AI_ASSISTANT_PREFS", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("OPEN_CAMFLOW", true).apply()

            Log.d(TAG, "📷 Set flag to open CamFlow")

            return "📷 **Opening CamFlow!**\n\n" +
                    "I'm opening CamFlow where you can:\n" +
                    "• 📸 **Take photos** or select images from gallery\n" +
                    "• 🔍 **Analyze images** with AI for text extraction\n" +
                    "• 📤 **Send results** to Gmail or Telegram\n" +
                    "• 💾 **Save sessions** for future reference\n\n" +
                    "CamFlow will open in a new screen where you can start analyzing your images!"
        } catch (e: Exception) {
            Log.e(TAG, "Error setting CamFlow flag", e)
            return "❌ Error opening CamFlow: ${e.message}\n\n" +
                    "You can manually navigate to the CamFlow section to analyze images."
        }
    }

    /**
     * Create geofencing workflow
     */
    private fun createGeofencingWorkflow(location: String, radius: Int): Workflow {
        val workflowId = "geofencing_${System.currentTimeMillis()}"

        val steps = listOf(
            WorkflowStep(
                id = "monitor_location",
                name = "Monitor Location",
                description = "Monitor user location continuously",
                type = StepType.FETCH_EMAILS, // Reusing for now
                parameters = mapOf(
                    "location" to location,
                    "radius" to radius,
                    "triggerType" to "enter_exit"
                )
            ),
            WorkflowStep(
                id = "send_notification",
                name = "Send Notification",
                description = "Send notification when location triggers",
                type = StepType.SUMMARIZE_EMAILS, // Reusing for now
                parameters = mapOf(
                    "notificationType" to "location_alert",
                    "message" to "You have entered/left $location"
                )
            )
        )

        return Workflow(
            id = workflowId,
            name = "Geofencing - $location",
            description = "Location-based triggers for $location",
            steps = steps,
            status = com.google.ai.edge.gallery.ui.workflow.WorkflowStatus.DRAFT,
            interval = 0, // Continuous monitoring
            isScheduled = false,
            isActive = true,
            stepDelay = 1000 // 1 second delay between steps
        )
    }

    /**
     * Extract location from input
     */
    private fun extractLocation(input: String): String {
        val locationPatterns = listOf(
            Regex("""for ['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""at ['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""location ['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        )

        for (pattern in locationPatterns) {
            val match = pattern.find(input)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return "Home" // Default location
    }

    /**
     * Extract radius from input
     */
    private fun extractRadius(input: String): Int {
        val radiusPattern = Regex("""(\d+)\s*m""", RegexOption.IGNORE_CASE)
        val match = radiusPattern.find(input)

        return if (match != null) {
            match.groupValues[1].toInt()
        } else {
            100 // Default radius
        }
    }

    /**
     * Check if the request contains comprehensive information
     */
    private fun isComprehensiveEmailRequest(input: String): Boolean {
        val lowerInput = input.lowercase()

        // Check for destination information
        val hasDestination = lowerInput.contains("send to") ||
                lowerInput.contains("email") && lowerInput.contains("@") ||
                lowerInput.contains("telegram") ||
                lowerInput.contains("chat") ||
                lowerInput.contains("forward to") ||
                lowerInput.contains("deliver to") ||
                lowerInput.contains("send") && lowerInput.contains("@")

        // Check for schedule information
        val hasSchedule = lowerInput.contains("daily") ||
                lowerInput.contains("weekly") ||
                lowerInput.contains("hour") ||
                lowerInput.contains("every") ||
                lowerInput.contains("morning") ||
                lowerInput.contains("evening") ||
                lowerInput.contains("night") ||
                lowerInput.contains("day") ||
                lowerInput.contains("week")

        // Check for filters
        val hasFilters = lowerInput.contains("from") ||
                lowerInput.contains("sender") ||
                lowerInput.contains("subject") ||
                lowerInput.contains("filter") ||
                lowerInput.contains("only") ||
                lowerInput.contains("just") ||
                lowerInput.contains("specific")

        return hasDestination || hasSchedule || hasFilters
    }

    /**
     * Extract workflow name from input
     */
    private fun extractWorkflowName(input: String): String? {
        val patterns = listOf(
            Regex("""call(?:ed)?\s+(?:it\s+)?['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""name(?:d)?\s+(?:it\s+)?['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""workflow\s+(?:called\s+)?['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(input)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return null
    }

    /**
     * Extract workflow description from input
     */
    private fun extractWorkflowDescription(input: String): String? {
        val patterns = listOf(
            Regex("""description['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""for\s+['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(input)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return null
    }

    /**
     * Extract destination type from input
     */
    private fun extractDestinationType(input: String): String {
        val lowerInput = input.lowercase()
        return when {
            lowerInput.contains("telegram") -> "telegram"
            else -> "gmail" // Default to gmail
        }
    }

    /**
     * Extract destination email from input
     */
    private fun extractDestinationEmail(input: String): String? {
        val emailPattern = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b""")
        val match = emailPattern.find(input)
        return match?.value
    }

    /**
     * Extract destination chat ID from input
     */
    private fun extractDestinationChatId(input: String): String? {
        val patterns = listOf(
            Regex("""telegram\s+(?:chat\s+)?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""chat\s+(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""(\d{9,})""") // Telegram chat IDs are usually 9+ digits
        )

        for (pattern in patterns) {
            val match = pattern.find(input)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return null
    }

    /**
     * Extract sender filter from input
     */
    private fun extractSenderFilter(input: String): String? {
        val patterns = listOf(
            Regex("""from\s+['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""sender\s+['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""emails?\s+from\s+([^\s]+)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(input)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return null
    }

    /**
     * Extract subject filter from input
     */
    private fun extractSubjectFilter(input: String): String? {
        val patterns = listOf(
            Regex("""subject\s+['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""about\s+['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""containing\s+['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(input)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return null
    }

    /**
     * Extract expiration option from input
     */
    private fun extractExpirationOption(input: String): ExpirationOption {
        val lowerInput = input.lowercase()
        return when {
            lowerInput.contains("never") || lowerInput.contains("until disabled") -> ExpirationOption.UNTIL_DISABLED
            lowerInput.contains("1 month") || lowerInput.contains("month") -> ExpirationOption.ONE_MONTH
            lowerInput.contains("custom") || lowerInput.contains("specific date") -> ExpirationOption.FIXED_DATE
            else -> ExpirationOption.UNTIL_DISABLED // Default
        }
    }

    /**
     * Extract execution interval from input
     */
    private fun extractExecutionInterval(input: String): Long {
        val lowerInput = input.lowercase()
        return when {
            lowerInput.contains("daily") || lowerInput.contains("every day") -> TimeUnit.DAYS.toMillis(1)
            lowerInput.contains("weekly") || lowerInput.contains("every week") -> TimeUnit.DAYS.toMillis(7)
            lowerInput.contains("6 hours") || lowerInput.contains("every 6 hours") -> TimeUnit.HOURS.toMillis(6)
            lowerInput.contains("12 hours") || lowerInput.contains("every 12 hours") -> TimeUnit.HOURS.toMillis(12)
            lowerInput.contains("hour") -> {
                val hourPattern = Regex("""(\d+)\s*hours?""", RegexOption.IGNORE_CASE)
                val match = hourPattern.find(input)
                if (match != null) {
                    TimeUnit.HOURS.toMillis(match.groupValues[1].toLong())
                } else {
                    TimeUnit.HOURS.toMillis(6) // Default
                }
            }
            lowerInput.contains("minute") -> {
                val minutePattern = Regex("""(\d+)\s*minutes?""", RegexOption.IGNORE_CASE)
                val match = minutePattern.find(input)
                if (match != null) {
                    TimeUnit.MINUTES.toMillis(match.groupValues[1].toLong())
                } else {
                    TimeUnit.HOURS.toMillis(6) // Default
                }
            }
            else -> TimeUnit.HOURS.toMillis(6) // Default to 6 hours
        }
    }

    /**
     * Check if the interval is the default (6 hours)
     */
    private fun isDefaultInterval(interval: Long): Boolean {
        return interval == TimeUnit.HOURS.toMillis(6)
    }

    /**
     * Check if the input contains any time-related information
     */
    private fun hasTimeInformation(input: String): Boolean {
        val lowerInput = input.lowercase()
        return lowerInput.contains("daily") ||
                lowerInput.contains("weekly") ||
                lowerInput.contains("hour") ||
                lowerInput.contains("minute") ||
                lowerInput.contains("every") ||
                lowerInput.contains("morning") ||
                lowerInput.contains("evening") ||
                lowerInput.contains("night") ||
                lowerInput.contains("day") ||
                lowerInput.contains("week") ||
                Regex("""\d+\s*(h|hours?|m|minutes?)""", RegexOption.IGNORE_CASE).containsMatchIn(input)
    }

    /**
     * Ask for missing information in a smart way
     */
    private fun askForMissingInformation(missingInfo: List<String>): String {
        val firstMissing = missingInfo.first()

        return when (firstMissing) {
            "destination email" -> {
                workflowCreationState = workflowCreationState.copy(currentStep = "destination_email")
                "📧 **What email address should receive the summaries?**\n\n" +
                        "Enter the email address where you want to receive the summaries."
            }
            "Telegram chat ID" -> {
                workflowCreationState = workflowCreationState.copy(currentStep = "destination_chat_id")
                "📱 **What's your Telegram chat ID?**\n\n" +
                        "Enter your Telegram chat ID where you want to receive the summaries."
            }
            "execution interval" -> {
                workflowCreationState = workflowCreationState.copy(currentStep = "execution_interval")
                "⏰ **How often should this workflow run?**\n\n" +
                        "Choose one:\n" +
                        "• **Daily** - Run once every day\n" +
                        "• **Weekly** - Run once every week\n" +
                        "• **Every 6 hours** - Run 4 times per day\n" +
                        "• **Custom** - Specify custom interval\n\n" +
                        "Reply with 'daily', 'weekly', '6 hours', or 'custom'"
            }
            else -> {
                workflowCreationState = workflowCreationState.copy(currentStep = "name")
                "❓ I need more information. Let's start with the workflow name.\n\n" +
                        "**What would you like to call this workflow?**"
            }
        }
    }

    /**
     * Get the next required step based on what information is missing
     */
    private fun getNextRequiredStep(): String {
        val state = workflowCreationState

        // Check what's missing
        val missingInfo = mutableListOf<String>()

        // Check if we have a destination
        if (state.destinationType == "gmail" && state.destinationEmail.isNullOrBlank()) {
            missingInfo.add("destination email")
        } else if (state.destinationType == "telegram" && state.destinationChatId.isNullOrBlank()) {
            missingInfo.add("Telegram chat ID")
        } else if (state.destinationType == "deeplink" && (state.destinationChatId.isNullOrBlank() || state.destinationChatId == "deeplink_pending")) {
            missingInfo.add("Telegram deep link connection")
        }

        // Check if we have an interval
        if (state.executionInterval == null || isDefaultInterval(state.executionInterval)) {
            missingInfo.add("execution interval")
        }

        // If we have all required info, go to confirmation
        if (missingInfo.isEmpty()) {
            Log.d(TAG, "✅ All required info collected, showing preview")
            workflowCreationState = workflowCreationState.copy(currentStep = "confirm")
            return generateWorkflowPreview()
        }

        // Ask for the first missing piece
        Log.d(TAG, "🔍 Next required step: ${missingInfo.first()}")
        return askForMissingInformation(missingInfo)
    }

    /**
     * Create comprehensive workflow with all details
     */
    private fun createComprehensiveWorkflow(
        name: String,
        description: String?,
        destinationType: String,
        destinationEmail: String?,
        destinationChatId: String?,
        senderFilter: String?,
        subjectFilter: String?,
        expirationOption: ExpirationOption,
        executionInterval: Long
    ): Workflow {
        val workflowId = "email_summarization_${System.currentTimeMillis()}"

        val steps = listOf(
            WorkflowStep(
                id = "fetch_emails",
                name = "Fetch Emails",
                description = "Fetch recent emails from Gmail",
                type = StepType.FETCH_EMAILS,
                parameters = mapOf(
                    "maxEmails" to 10,
                    "includeAttachments" to false,
                    "senderFilter" to (senderFilter ?: ""),
                    "subjectFilter" to (subjectFilter ?: "")
                )
            ),
            WorkflowStep(
                id = "summarize_emails",
                name = "Summarize Emails",
                description = "Use AI to summarize email content",
                type = StepType.SUMMARIZE_EMAILS,
                parameters = mapOf(
                    "modelName" to "Gemma-3n-E2B-it-int4",
                    "maxSummaryLength" to 200
                )
            ),
            WorkflowStep(
                id = "forward_summaries",
                name = "Forward Summaries",
                description = "Forward summaries to specified destination",
                type = StepType.FORWARD_EMAILS,
                parameters = mapOf(
                    "destinationType" to destinationType,
                    "destination" to (if (destinationType == "telegram" || destinationType == "deeplink") (destinationChatId ?: "") else (destinationEmail ?: "")),
                    "includeSummary" to true,
                    "subject" to "Email Summaries - ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}"
                )
            ),
            WorkflowStep(
                id = "send_batch_summaries",
                name = "Send Batch Summaries",
                description = "Send email summaries to destination",
                type = StepType.SEND_BATCH_SUMMARIES,
                parameters = mapOf(
                    "enabled" to true,
                    "destination" to (if (destinationType == "telegram" || destinationType == "deeplink") (destinationChatId ?: "") else (destinationEmail ?: "")),
                    "destinationType" to destinationType,
                    "count" to 5,
                    "senderFilter" to (senderFilter ?: ""),
                    "subjectFilter" to (subjectFilter ?: "")
                )
            )
        )

        return Workflow(
            id = workflowId,
            name = name,
            description = description ?: "Automatically fetch, summarize, and forward emails",
            steps = steps,
            status = com.google.ai.edge.gallery.ui.workflow.WorkflowStatus.RUNNING,
            interval = executionInterval,
            isScheduled = false, // Don't schedule, run immediately
            isActive = true,
            stepDelay = 5000,
            expirationOption = expirationOption,
            customExpirationDate = null
        )
    }

    /**
     * Format interval for display
     */
    private fun formatInterval(interval: Long): String {
        return when (interval) {
            TimeUnit.DAYS.toMillis(1) -> "📅 Daily"
            TimeUnit.DAYS.toMillis(7) -> "📅 Weekly"
            TimeUnit.HOURS.toMillis(6) -> "⏰ Every 6 hours"
            TimeUnit.HOURS.toMillis(12) -> "⏰ Every 12 hours"
            else -> "⏰ Every ${interval / TimeUnit.HOURS.toMillis(1)} hours"
        }
    }

    /**
     * Format expiration for display
     */
    private fun formatExpiration(expirationOption: ExpirationOption): String {
        return when (expirationOption) {
            ExpirationOption.UNTIL_DISABLED -> "♾️ Never expire"
            ExpirationOption.ONE_MONTH -> "📅 Expire in 1 month"
            ExpirationOption.FIXED_DATE -> "📅 Custom expiration"
        }
    }

    // ==================== INTENT STATE MANAGEMENT ====================

    /**
     * Start tracking a new intent
     */
    private fun startIntent(intent: String, userInput: String) {
        intentState = IntentState(
            activeIntent = intent,
            intentData = mutableMapOf("originalInput" to userInput),
            isCompleted = false,
            stepCount = 0,
            maxSteps = 5,
            lastUserInput = userInput
        )
        Log.d(TAG, "Started intent: $intent")
    }

    /**
     * Continue processing the active intent
     */
    private fun continueActiveIntent(input: String): String {
        intentState = intentState.copy(
            stepCount = intentState.stepCount + 1,
            lastUserInput = input
        )

        // Prevent infinite loops
        if (intentState.stepCount >= intentState.maxSteps) {
            completeIntent("Maximum steps reached. Please try again with a more specific request.")
            return "I've reached the maximum number of steps for this task. Please try again with a more specific request or start over."
        }

        return when (intentState.activeIntent) {
            "email_summarize" -> continueEmailSummarizationIntent(input)
            "camera_text" -> continueCameraTextIntent(input)
            "geofencing" -> continueGeofencingIntent(input)
            else -> {
                completeIntent("Unknown intent")
                "I'm not sure how to continue with this task. Please try again."
            }
        }
    }

    /**
     * Complete the current intent
     */
    private fun completeIntent(result: String) {
        intentState = intentState.copy(
            isCompleted = true,
            activeIntent = null
        )
        Log.d(TAG, "Completed intent: ${intentState.activeIntent} with result: $result")
    }

    /**
     * Reset intent state
     */
    fun resetIntentState() {
        intentState = IntentState()
        Log.d(TAG, "Reset intent state")
    }

    /**
     * Continue email summarization intent processing
     */
    private fun continueEmailSummarizationIntent(input: String): String {
        // If we're already in workflow creation, let it handle the conversation
        if (workflowCreationState.isCreating) {
            return handleWorkflowCreationConversation(input)
        }

        // Check if this input contains comprehensive information
        if (isComprehensiveEmailRequest(input)) {
            val result = handleComprehensiveEmailSummarization(input)
            completeIntent(result)
            return result
        }

        // Start workflow creation process
        return handleEmailSummarization()
    }

    /**
     * Continue camera text intent processing
     */
    private fun continueCameraTextIntent(input: String): String {
        // Camera text extraction is usually a one-step process
        val result = handleCameraTextExtraction()
        completeIntent(result)
        return result
    }

    /**
     * Continue geofencing intent processing
     */
    private fun continueGeofencingIntent(input: String): String {
        // Geofencing setup is usually a one-step process
        val result = handleGeofencingRequest(input)
        completeIntent(result)
        return result
    }

    /**
     * Extract time information from user input
     */
    private fun extractTimeFromInput(input: String): String? {
        val lowerInput = input.lowercase()

        // Look for time patterns
        val timePatterns = listOf(
            "\\d{1,2}:\\d{2}\\s*(am|pm)".toRegex(),
            "\\d{1,2}\\s*(am|pm)".toRegex(),
            "\\d{1,2}:\\d{2}".toRegex(),
            "tomorrow".toRegex(),
            "today".toRegex(),
            "morning".toRegex(),
            "afternoon".toRegex(),
            "evening".toRegex(),
            "night".toRegex()
        )

        for (pattern in timePatterns) {
            val match = pattern.find(lowerInput)
            if (match != null) {
                return match.value
            }
        }

        return null
    }

    /**
     * Extract message from user input
     */
    private fun extractMessageFromInput(input: String): String? {
        val lowerInput = input.lowercase()

        // Look for message patterns (text in quotes or after "message")
        val messagePatterns = listOf(
            "message\\s+['\"]([^'\"]+)['\"]".toRegex(),
            "['\"]([^'\"]+)['\"]".toRegex(),
            "with\\s+message\\s+(.+)".toRegex()
        )

        for (pattern in messagePatterns) {
            val match = pattern.find(lowerInput)
            if (match != null) {
                return match.groupValues.lastOrNull()
            }
        }

        return null
    }

    /**
     * Workflow Storage Helper - Duplicated from WorkflowViewModel to ensure consistent storage
     */
    private class WorkflowStorageHelper(private val context: Context) {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
        private val fileName = "workflows.json"

        suspend fun saveWorkflows(workflows: List<Workflow>) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
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
            return kotlinx.coroutines.withContext(Dispatchers.IO) {
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

        // Serializable versions of data classes
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
            val subjectFilter: String?
        ) {
            fun toWorkflow(): Workflow {
                return Workflow(
                    id = id,
                    name = name,
                    description = description,
                    steps = steps.map { it.toStep() },
                    status = com.google.ai.edge.gallery.ui.workflow.WorkflowStatus.valueOf(status),
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
                    subjectFilter = subjectFilter
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
                        // Convert string values back to appropriate types
                        when (val value = entry.value) {
                            "true" -> true
                            "false" -> false
                            else -> {
                                // Try to parse as Long first (for timestamps), then Int, then keep as String
                                value.toLongOrNull() ?: value.toIntOrNull() ?: value
                            }
                        }
                    },
                    status = com.google.ai.edge.gallery.ui.workflow.StepStatus.valueOf(status),
                    result = result,
                    error = error
                )
            }
        }

        // Extension functions to convert to serializable versions
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
                subjectFilter = subjectFilter
            )
        }

        fun WorkflowStep.toSerializable(): SerializableStep {
            return SerializableStep(
                id = id,
                name = name,
                description = description,
                type = type.name,
                parameters = parameters.mapValues {
                    // Convert all parameter values to String for serialization
                    it.value.toString()
                },
                status = status.name,
                result = result?.toString(),
                error = error
            )
        }
    }
}