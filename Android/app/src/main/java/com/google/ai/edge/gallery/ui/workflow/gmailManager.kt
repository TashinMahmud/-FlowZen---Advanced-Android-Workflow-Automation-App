package com.google.ai.edge.gallery.ui.workflow
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.WorkerThread
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.ListMessagesResponse
import com.google.api.services.gmail.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Environment
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.data.Model
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
private const val TAG = "AGGalleryNavGraph"
object DestinationType {
    const val GMAIL = "gmail"
    const val TELEGRAM = "telegram"
    const val DEEPLINK = "deeplink"
}
data class EmailData(
    val id: String,
    val subject: String,
    val from: String,
    val to: String,
    val date: String,
    val timestamp: Long,
    val snippet: String,
    val body: String
)
class WorkflowEmailManager(private val context: Context) {
    companion object {
        private const val TAG = "WorkflowEmailManager"
    }
    suspend fun saveEmailsToFileAsync(emails: List<EmailData>, accountEmail: String) = withContext(Dispatchers.IO) {
        try {
// Fixed: Use app-specific internal storage instead of external storage
            val fileName = "gmail_emails_${accountEmail.replace("@", "_at_").replace(".", "_")}.txt"
            val file = File(context.filesDir, fileName)
            file.bufferedWriter().use { writer ->
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val currentTime = dateFormat.format(Date())
                writer.appendLine("=== GMAIL EMAILS FOR ${accountEmail.uppercase()} ===")
                writer.appendLine("File updated: $currentTime")
                writer.appendLine("Latest 10 emails from inbox")
                writer.appendLine()
                emails.forEach { email ->
                    writer.appendLine("EMAIL ID: ${email.id}")
                    writer.appendLine("SUBJECT: ${email.subject}")
                    writer.appendLine("FROM: ${email.from}")
                    writer.appendLine("TO: ${email.to}")
                    writer.appendLine("DATE: ${email.date}")
                    writer.appendLine("TIMESTAMP: ${email.timestamp}")
                    writer.appendLine("SNIPPET: ${email.snippet}")
                    writer.appendLine("BODY:")
                    writer.appendLine(email.body)
                    writer.appendLine("---")
                    writer.appendLine()
                }
            }
            Log.d(TAG, "Saved ${emails.size} emails to file: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving emails to file: ${e.message}", e)
// Don't throw the exception - just log it and continue
// This prevents the workflow from failing due to file saving issues
        }
    }
    fun loadExistingEmailIds(accountEmail: String): Set<String> {
        return try {
// Fixed: Use app-specific internal storage
            val fileName = "gmail_emails_${accountEmail.replace("@", "_at_").replace(".", "_")}.txt"
            val file = File(context.filesDir, fileName)
            if (!file.exists()) return emptySet()
            file.readLines()
                .filter { it.startsWith("EMAIL ID: ") }
                .mapTo(mutableSetOf()) { it.substringAfter("EMAIL ID: ") }
                .also {
                    Log.d(TAG, "Loaded ${it.size} email IDs from file")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading email IDs: ${e.message}", e)
            emptySet()
        }
    }
    fun getSavedEmailsContent(accountEmail: String): String {
        return try {
// Fixed: Use app-specific internal storage
            val fileName = "gmail_emails_${accountEmail.replace("@", "_at_").replace(".", "_")}.txt"
            val file = File(context.filesDir, fileName)
            if (!file.exists()) "No saved emails found." else file.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading saved emails: ${e.message}", e)
            "Error reading saved emails: ${e.message}"
        }
    }
}
fun getGoogleAccountAndToken(context: Context): Pair<com.google.android.gms.auth.api.signin.GoogleSignInAccount?, String?> {
    val account = GoogleSignIn.getLastSignedInAccount(context)
    val token = account?.idToken
    return Pair(account, token)
}
class GmailApiHelper(private val context: Context, private val account: com.google.android.gms.auth.api.signin.GoogleSignInAccount) {
    private val credential: GoogleAccountCredential = GoogleAccountCredential.usingOAuth2(
        context,
        listOf(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.send"
        )
    ).apply {
        selectedAccount = account.account
        Log.d(TAG, "Setting up Gmail API with account: ${account.email}")
    }
    private val gmailService: Gmail = Gmail.Builder(
        com.google.api.client.http.javanet.NetHttpTransport(),
        com.google.api.client.json.jackson2.JacksonFactory.getDefaultInstance(),
        credential
    ).setApplicationName("FlowZen").build()
    private var aiModel: Model? = null
    private var aiModelInstance: Any? = null
    private val emailManager = WorkflowEmailManager(context)
    private val inferenceMutex = Mutex()
    fun setAiModel(model: Model) {
        aiModel = model
        Log.d(TAG, "AI Model set for summarization: ${model.name}")
    }
    @WorkerThread
    suspend fun initializeAiModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (aiModel == null) {
                Log.e(TAG, "No AI model set for summarization")
                return@withContext false
            }
            Log.d(TAG, "Initializing AI model: ${aiModel!!.name}")
            var initializationError = ""
            LlmChatModelHelper.initialize(context, aiModel!!) { error ->
                initializationError = error
            }
            delay(1000)
            if (aiModel!!.instance != null) {
                aiModelInstance = aiModel!!.instance
                Log.d(TAG, "AI model initialized successfully")
                return@withContext true
            } else {
                Log.e(TAG, "Failed to initialize AI model: $initializationError")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AI model: ${e.message}", e)
            return@withContext false
        }
    }
    @WorkerThread
    suspend fun summarizeEmail(emailContent: String): String = withContext(Dispatchers.IO) {
        try {
            if (aiModelInstance == null) {
                Log.e(TAG, "AI model not initialized")
                return@withContext "Error: AI model not initialized"
            }
            Log.d(TAG, "Starting email summarization...")
            val maxContentLength = if (aiModel?.name?.contains("1B") == true || aiModel?.name?.contains("q4") == true) {
                1500
            } else {
                3000
            }
            val truncatedContent = if (emailContent.length > maxContentLength) {
                Log.d(TAG, "Email content too long (${emailContent.length} chars), truncating to $maxContentLength chars")
                emailContent.take(maxContentLength) + "\n\n[Content truncated due to length...]"
            } else {
                emailContent
            }
            val prompt = if (aiModel?.name?.contains("1B") == true || aiModel?.name?.contains("q4") == true) {
                """
Please summarize this email in 2-3 clear sentences:
Email content:
$truncatedContent
Summary:
""".trimIndent()
            } else {
                """
Summarize this email:
$truncatedContent
""".trimIndent()
            }
            Log.d(TAG, "Sending prompt to AI model (${prompt.length} chars)")
            return@withContext summarizeWithModel(prompt)
        } catch (e: Exception) {
            Log.e(TAG, "Error summarizing email: ${e.message}", e)
            return@withContext "Error: ${e.message}"
        }
    }
    @WorkerThread
    private suspend fun summarizeWithModel(prompt: String): String = inferenceMutex.withLock {
        try {
            val estimatedTokens = prompt.length / 4
            if (estimatedTokens > 2500) {
                Log.w(TAG, "Prompt too long for model (estimated $estimatedTokens tokens)")
                return@withLock "Error: Email content too long for summarization"
            }
            var summary = ""
            var inferenceCompleted = false
            suspendCancellableCoroutine<String> { continuation ->
                continuation.invokeOnCancellation {
                    inferenceCompleted = true
                }
                LlmChatModelHelper.runInference(
                    model = aiModel!!,
                    input = prompt,
                    resultListener = { partialResult, done ->
                        summary += partialResult
                        if (done) {
                            inferenceCompleted = true
                            if (continuation.isActive) {
                                val cleanedSummary = cleanSummary(summary)
                                continuation.resume(if (cleanedSummary.isNotEmpty()) cleanedSummary else "No meaningful summary generated")
                            }
                        }
                    },
                    cleanUpListener = {
                        if (!inferenceCompleted) {
                            if (continuation.isActive) {
                                continuation.resume("Error: Inference was interrupted")
                            }
                        }
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in summarizeWithModel: ${e.message}", e)
            "Error: ${e.message}"
        }
    }
    private fun cleanSummary(summary: String): String {
        return summary
            .replace(Regex("^Summary:\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^The email is about\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^This email discusses\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^This email is\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^Email summary:\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf {
                it.isNotEmpty() && !it.contains("Error:", ignoreCase = true) && !it.contains("OUT_OF_RANGE", ignoreCase = true)
            }
            ?: "No meaningful summary generated"
    }
    @WorkerThread
    suspend fun fetchLatestEmails(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting to fetch emails from inbox...")
            val user = "me"
// Add timeout for the list request
            val messagesResponse: ListMessagesResponse = withTimeoutOrNull(30_000) {
                gmailService.users().messages().list(user)
                    .setLabelIds(listOf("INBOX"))
                    .setMaxResults(10)
                    .execute()
            } ?: throw Exception("Gmail API request timed out")
            val messages = messagesResponse.messages ?: return@withContext emptyList()
            Log.d(TAG, "Found ${messages.size} messages in inbox")
            val allEmails = mutableListOf<EmailData>()
            val emailPairs = mutableListOf<Pair<String, String>>()
// Process messages with individual timeouts
            for ((index, msg) in messages.withIndex()) {
                try {
                    Log.d(TAG, "Processing message ${index + 1}/${messages.size}")
// Add timeout for individual message retrieval
                    val message: Message = withTimeoutOrNull(15_000) {
                        gmailService.users().messages().get(user, msg.id).setFormat("full").execute()
                    } ?: throw Exception("Message retrieval timed out")
                    val subject = decodeEmailHeader(message.payload.headers.firstOrNull { it.name == "Subject" }?.value)
                    val from = decodeEmailHeader(message.payload.headers.firstOrNull { it.name == "From" }?.value)
                    val to = decodeEmailHeader(message.payload.headers.firstOrNull { it.name == "To" }?.value)
                    val date = message.payload.headers.firstOrNull { it.name == "Date" }?.value ?: ""
                    val snippet = message.snippet ?: ""
                    val body = getMessageBody(message)
                    Log.d(TAG, "Fetched email: $subject")
                    val emailData = EmailData(
                        id = msg.id,
                        subject = subject,
                        from = from,
                        to = to,
                        date = date,
                        timestamp = message.internalDate ?: System.currentTimeMillis(),
                        snippet = snippet,
                        body = body
                    )
                    allEmails.add(emailData)
                    emailPairs.add(Pair(subject, msg.id))
                    Log.d(TAG, "Email processed: $subject")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process message ${msg.id}: ${e.message}", e)
// Continue with next message instead of failing completely
                }
            }
// Save emails asynchronously to avoid blocking
            if (allEmails.isNotEmpty()) {
// Use coroutineScope to launch a new coroutine
                coroutineScope {
                    launch(Dispatchers.IO) {
                        try {
                            emailManager.saveEmailsToFileAsync(allEmails, account.email ?: "")
                            Log.d(TAG, "Saved ${allEmails.size} emails to file")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save emails to file: ${e.message}", e)
                        }
                    }
                }
            }
            emailPairs
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Timeout fetching emails: ${e.message}", e)
            throw Exception("Email fetching timed out. Please check your network connection.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch emails: ${e.message}", e)
            throw e
        }
    }
    @WorkerThread
    suspend fun forwardEmail(
        messageId: String,
        destination: String,
        destinationType: String,
        withSummary: Boolean = false,
        telegramHelper: TelegramDeepLinkHelperWork? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting to forward email to $destinationType: $destination${if (withSummary) " with summary" else ""}")
            return@withContext when (destinationType) {
                DestinationType.GMAIL -> forwardToGmail(messageId, destination, withSummary)
// Fixed: Handle DEEPLINK type the same as TELEGRAM
                DestinationType.TELEGRAM, DestinationType.DEEPLINK -> {
                    val actualDestination = if (destination == DestinationType.DEEPLINK) {
                        if (telegramHelper == null) {
                            Log.e(TAG, "No Telegram helper provided. Please set up deep link first.")
                            return@withContext false
                        }
                        val (isConnected, _, chatId) = telegramHelper.checkConnectionStatus()
                        if (!isConnected || chatId == null) {
                            Log.e(TAG, "Telegram not connected. Please set up deep link first.")
                            return@withContext false
                        }
                        chatId
                    } else {
                        destination
                    }
                    forwardToTelegram(messageId, actualDestination, withSummary, telegramHelper)
                }
                else -> {
                    Log.e(TAG, "Unknown destination type: $destinationType")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward email: ${e.message}", e)
            false
        }
    }
    @WorkerThread
    suspend fun sendLastFiveEmailSummaries(
        destination: String,
        destinationType: String,
        senderFilter: String? = null,
        subjectFilter: String? = null,
        telegramHelper: TelegramDeepLinkHelperWork? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting to send summaries of filtered emails to $destinationType: $destination" +
                    (if (senderFilter != null) " with sender filter: $senderFilter" else "") +
                    (if (subjectFilter != null) " with subject filter: $subjectFilter" else ""))
            return@withContext when (destinationType) {
                DestinationType.GMAIL -> sendBatchSummariesToGmail(destination, senderFilter, subjectFilter)
// Fixed: Handle DEEPLINK type the same as TELEGRAM
                DestinationType.TELEGRAM, DestinationType.DEEPLINK -> {
                    val actualDestination = if (destination == DestinationType.DEEPLINK) {
                        if (telegramHelper == null) {
                            Log.e(TAG, "No Telegram helper provided. Please set up deep link first.")
                            return@withContext false
                        }
                        val (isConnected, _, chatId) = telegramHelper.checkConnectionStatus()
                        if (!isConnected || chatId == null) {
                            Log.e(TAG, "Telegram not connected. Please set up deep link first.")
                            return@withContext false
                        }
                        chatId
                    } else {
                        destination
                    }
                    sendBatchSummariesToTelegram(actualDestination, senderFilter, subjectFilter, telegramHelper)
                }
                else -> {
                    Log.e(TAG, "Unknown destination type: $destinationType")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send summaries: ${e.message}", e)
            false
        }
    }
    private fun parseKeywords(filter: String?): List<String> {
        return filter?.split(Regex("[,\\s]+"))?.filter { it.isNotEmpty() } ?: emptyList()
    }
    private fun matchesKeywords(text: String, keywords: List<String>): Boolean {
        return keywords.isEmpty() || keywords.any { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
    }
    @WorkerThread
    private suspend fun forwardToGmail(
        messageId: String,
        destinationEmail: String,
        withSummary: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val user = "me"
            val originalMessage = withTimeoutOrNull(15_000) {
                gmailService.users().messages().get(user, messageId).setFormat("full").execute()
            } ?: throw Exception("Message retrieval timed out")
            val headers = originalMessage.payload.headers
            val subject = decodeEmailHeader(headers.firstOrNull { it.name == "Subject" }?.value)
            val from = decodeEmailHeader(headers.firstOrNull { it.name == "From" }?.value)
            val date = headers.firstOrNull { it.name == "Date" }?.value ?: ""
            val body = getMessageBody(originalMessage)
            val messageBuilder = StringBuilder()
            messageBuilder.append("From: ${account.email ?: ""}\n")
            messageBuilder.append("To: $destinationEmail\n")
            messageBuilder.append("Subject: Fwd: $subject\n")
            messageBuilder.append("Content-Type: text/plain; charset=UTF-8\n")
            messageBuilder.append("MIME-Version: 1.0\n\n")
            if (withSummary && aiModelInstance != null) {
                messageBuilder.append("SUMMARY\n")
                messageBuilder.append("=".repeat(50)).append("\n")
                val summary = summarizeEmail(body)
                messageBuilder.append(summary.trim())
                messageBuilder.append("\n\n")
                messageBuilder.append("ORIGINAL EMAIL\n")
                messageBuilder.append("=".repeat(50)).append("\n")
            } else {
                messageBuilder.append("---------- Forwarded message ----------\n")
            }
            messageBuilder.append("From: $from\n")
            messageBuilder.append("Date: $date\n")
            messageBuilder.append("Subject: $subject\n\n")
            messageBuilder.append(body)
            val encodedMessage = android.util.Base64.encodeToString(
                messageBuilder.toString().toByteArray(),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            val message = Message().apply {
                raw = encodedMessage
            }
            val sentMessage = gmailService.users().messages().send(user, message).execute()
            Log.d(TAG, "Email forwarded to Gmail successfully with ID: ${sentMessage.id}")
            true
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Timeout forwarding email to Gmail: ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward email to Gmail: ${e.message}", e)
            false
        }
    }
    @WorkerThread
    private suspend fun forwardToTelegram(
        messageId: String,
        chatId: String,
        withSummary: Boolean,
        telegramHelper: TelegramDeepLinkHelperWork?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val user = "me"
            val originalMessage = withTimeoutOrNull(15_000) {
                gmailService.users().messages().get(user, messageId).setFormat("full").execute()
            } ?: throw Exception("Message retrieval timed out")
            val headers = originalMessage.payload.headers
            val subject = decodeEmailHeader(headers.firstOrNull { it.name == "Subject" }?.value)
            val from = decodeEmailHeader(headers.firstOrNull { it.name == "From" }?.value)
            val date = headers.firstOrNull { it.name == "Date" }?.value ?: ""
            val body = getMessageBody(originalMessage)
            val messageBuilder = StringBuilder()
            if (withSummary && aiModelInstance != null) {
                val summary = summarizeEmail(body)
                messageBuilder.append("<b>SUMMARY</b>\n")
                messageBuilder.append(escapeHtml(summary.trim()))
                messageBuilder.append("\n\n")
                messageBuilder.append("<b>ORIGINAL EMAIL</b>\n")
            }
            messageBuilder.append("<b>From:</b> ${escapeHtml(from)}\n")
            messageBuilder.append("<b>Date:</b> ${escapeHtml(date)}\n")
            messageBuilder.append("<b>Subject:</b> ${escapeHtml(subject)}\n\n")
            messageBuilder.append(escapeHtml(body))
            val telegramMessage = messageBuilder.toString()
            if (telegramHelper == null) {
                Log.e(TAG, "Telegram helper is null")
                return@withContext false
            }
            return@withContext telegramHelper.sendMessageToChat(chatId, telegramMessage)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Timeout forwarding email to Telegram: ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward email to Telegram: ${e.message}", e)
            false
        }
    }
    @WorkerThread
    private suspend fun sendBatchSummariesToGmail(
        destinationEmail: String,
        senderFilter: String? = null,
        subjectFilter: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (aiModelInstance == null) {
                Log.e(TAG, "AI model not initialized")
                return@withContext false
            }
            val user = "me"
            val senderKeywords = parseKeywords(senderFilter)
            val subjectKeywords = parseKeywords(subjectFilter)
            val query = buildString {
                if (!senderFilter.isNullOrEmpty()) {
                    append("from:$senderFilter ")
                }
                if (!subjectFilter.isNullOrEmpty()) {
                    append("subject:$subjectFilter")
                }
            }.trim()
            val listRequest = gmailService.users().messages().list(user)
                .setLabelIds(listOf("INBOX"))
                .setMaxResults(20)
            if (query.isNotEmpty()) {
                listRequest.setQ(query)
            }
            val messagesResponse = withTimeoutOrNull(30_000) {
                listRequest.execute()
            } ?: throw Exception("Gmail API request timed out")
            val messages = messagesResponse.messages ?: return@withContext false
            Log.d(TAG, "Found ${messages.size} messages matching filters: $query")
            val filteredMessages = mutableListOf<Message>()
            for (msg in messages) {
                if (filteredMessages.size >= 5) break
                try {
                    val message = withTimeoutOrNull(15_000) {
                        gmailService.users().messages().get(user, msg.id).setFormat("full").execute()
                    } ?: throw Exception("Message retrieval timed out")
                    val subject = decodeEmailHeader(message.payload.headers.firstOrNull { it.name == "Subject" }?.value)
                    val from = decodeEmailHeader(message.payload.headers.firstOrNull { it.name == "From" }?.value)
                    val matchesSender = matchesKeywords(from, senderKeywords)
                    val matchesSubject = matchesKeywords(subject, subjectKeywords)
                    if (matchesSender && matchesSubject) {
                        filteredMessages.add(message)
                        Log.d(TAG, "Email matched filters: $subject")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error filtering message ${msg.id}: ${e.message}", e)
                }
            }
            if (filteredMessages.isEmpty()) {
                Log.d(TAG, "No emails matched the keyword filters")
                return@withContext false
            }
            val emailSummaries = mutableListOf<String>()
            for ((index, message) in filteredMessages.withIndex()) {
                try {
                    val subject = decodeEmailHeader(message.payload.headers.firstOrNull { it.name == "Subject" }?.value)
                    val from = decodeEmailHeader(message.payload.headers.firstOrNull { it.name == "From" }?.value)
                    val date = message.payload.headers.firstOrNull { it.name == "Date" }?.value ?: ""
                    val body = getMessageBody(message)
                    Log.d(TAG, "Summarizing email ${index + 1}: $subject")
                    val maxContentLength = if (aiModel?.name?.contains("1B") == true || aiModel?.name?.contains("q4") == true) {
                        1000
                    } else {
                        1500
                    }
                    val shortBody = if (body.length > maxContentLength) {
                        body.take(maxContentLength) + "\n\n[Content truncated for processing...]"
                    } else {
                        body
                    }
                    val summary = try {
                        summarizeEmail(shortBody)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to summarize email ${index + 1}: ${e.message}", e)
                        if (e.message?.contains("Input is too long") == true || e.message?.contains("OUT_OF_RANGE") == true) {
                            "Summary: Email content too long for AI processing. Subject: $subject"
                        } else {
                            "Summary: Could not generate AI summary. Subject: $subject"
                        }
                    }
                    emailSummaries.add("Email ${index + 1}:\nFrom: $from\nSubject: $subject\nDate: $date\n\n$summary\n")
                    if (index < filteredMessages.size - 1) {
                        delay(2500)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process email ${index + 1}: ${e.message}", e)
                    emailSummaries.add("Email ${index + 1}: Failed to process - ${e.message}\n")
                }
            }
            val messageBuilder = StringBuilder()
            messageBuilder.append("From: ${account.email ?: ""}\n")
            messageBuilder.append("To: $destinationEmail\n")
            messageBuilder.append("Subject: Summary of Filtered Emails\n")
            messageBuilder.append("Content-Type: text/plain; charset=UTF-8\n")
            messageBuilder.append("MIME-Version: 1.0\n\n")
            messageBuilder.append("EMAIL SUMMARIES\n")
            messageBuilder.append("=".repeat(50)).append("\n")
            messageBuilder.append("Generated on: ${java.time.LocalDateTime.now()}\n")
            messageBuilder.append("Keyword filters applied: ")
            if (senderKeywords.isNotEmpty()) messageBuilder.append("Sender: ${senderKeywords.joinToString(", ")} ")
            if (subjectKeywords.isNotEmpty()) messageBuilder.append("Subject: ${subjectKeywords.joinToString(", ")}")
            messageBuilder.append("\n\n")
            emailSummaries.forEach { summary ->
                messageBuilder.append(summary)
                messageBuilder.append("-".repeat(30)).append("\n\n")
            }
            val encodedMessage = android.util.Base64.encodeToString(
                messageBuilder.toString().toByteArray(),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            val message = Message().apply {
                raw = encodedMessage
            }
            val sentMessage = gmailService.users().messages().send(user, message).execute()
            Log.d(TAG, "Summaries sent to Gmail successfully with ID: ${sentMessage.id}")
            true
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Timeout sending batch summaries to Gmail: ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send summaries to Gmail: ${e.message}", e)
            false
        }
    }
    @WorkerThread
    private suspend fun sendBatchSummariesToTelegram(
        chatId: String,
        senderFilter: String? = null,
        subjectFilter: String? = null,
        telegramHelper: TelegramDeepLinkHelperWork?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (aiModelInstance == null) {
                Log.e(TAG, "AI model not initialized")
                return@withContext false
            }
            if (telegramHelper == null) {
                Log.e(TAG, "Telegram helper is null")
                return@withContext false
            }
            val user = "me"
            val senderKeywords = parseKeywords(senderFilter)
            val subjectKeywords = parseKeywords(subjectFilter)
            val query = buildString {
                if (!senderFilter.isNullOrEmpty()) {
                    append("from:$senderFilter ")
                }
                if (!subjectFilter.isNullOrEmpty()) {
                    append("subject:$subjectFilter")
                }
            }.trim()
            val listRequest = gmailService.users().messages().list(user)
                .setLabelIds(listOf("INBOX"))
                .setMaxResults(20)
            if (query.isNotEmpty()) {
                listRequest.setQ(query)
            }
            val messagesResponse = withTimeoutOrNull(30_000) {
                listRequest.execute()
            } ?: throw Exception("Gmail API request timed out")
            val messages = messagesResponse.messages ?: return@withContext false
            Log.d(TAG, "Found ${messages.size} messages matching filters: $query")
            val filteredMessages = mutableListOf<Message>()
            for (msg in messages) {
                if (filteredMessages.size >= 5) break
                try {
                    val message = withTimeoutOrNull(15_000) {
                        gmailService.users().messages().get(user, msg.id).setFormat("full").execute()
                    } ?: throw Exception("Message retrieval timed out")
                    val subject = decodeEmailHeader(message.payload.headers.firstOrNull { it.name == "Subject" }?.value)
                    val from = decodeEmailHeader(message.payload.headers.firstOrNull { it.name == "From" }?.value)
                    val matchesSender = matchesKeywords(from, senderKeywords)
                    val matchesSubject = matchesKeywords(subject, subjectKeywords)
                    if (matchesSender && matchesSubject) {
                        filteredMessages.add(message)
                        Log.d(TAG, "Email matched filters: $subject")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error filtering message ${msg.id}: ${e.message}", e)
                }
            }
            if (filteredMessages.isEmpty()) {
                Log.d(TAG, "No emails matched the keyword filters")
                return@withContext false
            }
            val emailSummaries = mutableListOf<String>()
            for ((index, message) in filteredMessages.withIndex()) {
                try {
                    val subject = decodeEmailHeader(message.payload.headers.firstOrNull { it.name == "Subject" }?.value)
                    val from = decodeEmailHeader(message.payload.headers.firstOrNull { it.name == "From" }?.value)
                    val date = message.payload.headers.firstOrNull { it.name == "Date" }?.value ?: ""
                    val body = getMessageBody(message)
                    Log.d(TAG, "Summarizing email ${index + 1}: $subject")
                    val maxContentLength = if (aiModel?.name?.contains("1B") == true || aiModel?.name?.contains("q4") == true) {
                        1000
                    } else {
                        1500
                    }
                    val shortBody = if (body.length > maxContentLength) {
                        body.take(maxContentLength) + "\n\n[Content truncated for processing...]"
                    } else {
                        body
                    }
                    val summary = try {
                        summarizeEmail(shortBody)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to summarize email ${index + 1}: ${e.message}", e)
                        if (e.message?.contains("Input is too long") == true || e.message?.contains("OUT_OF_RANGE") == true) {
                            "Summary: Email content too long for AI processing. Subject: $subject"
                        } else {
                            "Summary: Could not generate AI summary. Subject: $subject"
                        }
                    }
                    emailSummaries.add(
                        "<b>Email ${index + 1}:</b>\n" +
                                "<b>From:</b> ${escapeHtml(from)}\n" +
                                "<b>Subject:</b> ${escapeHtml(subject)}\n" +
                                "<b>Date:</b> ${escapeHtml(date)}\n\n" +
                                "${escapeHtml(summary)}"
                    )
                    if (index < filteredMessages.size - 1) {
                        delay(2500)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process email ${index + 1}: ${e.message}", e)
                    emailSummaries.add(
                        "<b>Email ${index + 1}:</b>\n" +
                                "Failed to process - ${escapeHtml(e.message)}"
                    )
                }
            }
            val telegramMessage = buildString {
                append("<b>EMAIL SUMMARIES</b>\n")
                append("=".repeat(50)).append("\n")
                append("Generated on: ${java.time.LocalDateTime.now()}\n\n")
                append("<b>Keyword filters applied:</b> ")
                if (senderKeywords.isNotEmpty()) append("Sender: ${senderKeywords.joinToString(", ")} ")
                if (subjectKeywords.isNotEmpty()) append("Subject: ${subjectKeywords.joinToString(", ")}")
                append("\n\n")
                emailSummaries.forEach { summary ->
                    append(summary).append("\n\n")
                    append("-".repeat(30)).append("\n\n")
                }
                append("<i>Total: ${emailSummaries.size} emails processed</i>\n")
                append("<i>Sent via Gmail Forwarder App</i>")
            }
            return@withContext telegramHelper.sendMessageToChat(chatId, telegramMessage)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Timeout sending batch summaries to Telegram: ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send summaries to Telegram: ${e.message}", e)
            false
        }
    }
    private fun escapeHtml(text: String?): String {
        if (text == null) return ""
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
    private fun decodeEmailHeader(header: String?): String {
        if (header == null) return ""
        return try {
            if (header.contains("=?") && header.contains("?=")) {
                val regex = Regex("=\\?([^?]+)\\?([BQ])\\?([^?]*)\\?=")
                regex.replace(header) { matchResult ->
                    val charset = matchResult.groupValues[1]
                    val encoding = matchResult.groupValues[2]
                    val encodedText = matchResult.groupValues[3]
                    try {
                        when (encoding.uppercase()) {
                            "B" -> {
                                val bytes = android.util.Base64.decode(
                                    encodedText,
                                    android.util.Base64.DEFAULT
                                )
                                String(bytes, java.nio.charset.Charset.forName(charset))
                            }
                            "Q" -> {
                                encodedText.replace("_", " ")
                                    .replace("=", "")
                                    .replace("%20", " ")
                            }
                            else -> encodedText
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error decoding header: ${e.message}", e)
                        encodedText
                    }
                }
            } else {
                header.replace("Ã¢Â€Â™", "'")
                    .replace("Ã¢Â€Âœ", """)
.replace("Ã¢Â€Â", """)
                    .replace("Ã°ÂŸÂªÂ™", "🪙")
                    .let { cleaned ->
                        cleaned.filter { char ->
                            char.code in 32..126 ||
                                    char.code in 160..255 ||
                                    char.isLetterOrDigit() ||
                                    char.isWhitespace() ||
                                    char in ".,!?;:()[]{}'\"`~@#$%^&*+=|\\/<>"
                        }
                    }
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding email header: ${e.message}", e)
            header
        }
    }
    private fun getMessageBody(message: Message): String {
        return try {
            Log.d(TAG, "Extracting body for message with mimeType: ${message.payload.mimeType}")
            Log.d(TAG, "Message payload has data: ${message.payload.body.data != null}")
            Log.d(TAG, "Message payload has parts: ${message.payload.parts != null}")
            if (message.payload.parts != null) {
                Log.d(TAG, "Number of parts: ${message.payload.parts.size}")
                message.payload.parts.forEachIndexed { index, part ->
                    Log.d(
                        TAG,
                        "Part $index: mimeType=${part.mimeType}, hasData=${part.body.data != null}"
                    )
                }
            }
            fun extractTextFromPart(part: com.google.api.services.gmail.model.MessagePart): String {
                return try {
                    val data = part.body.data
                    if (data != null) {
                        val maxDataSize = 1024 * 1024 // 1MB limit
                        val decodedBytes = android.util.Base64.decode(data, android.util.Base64.URL_SAFE)
                        if (decodedBytes.size > maxDataSize) {
                            Log.w(TAG, "Email part exceeds size limit (${decodedBytes.size} bytes), truncating")
                            String(decodedBytes, 0, maxDataSize, java.nio.charset.Charset.forName("UTF-8")) + "\n\n[Content truncated due to size]"
                        } else {
                            String(decodedBytes, java.nio.charset.Charset.forName("UTF-8"))
                        }
                    } else ""
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting from part: ${e.message}", e)
                    ""
                }
            }
            fun extractFromParts(parts: List<com.google.api.services.gmail.model.MessagePart>): String {
                val contentBuilder = StringBuilder()
                for (part in parts) {
                    when (part.mimeType) {
                        "text/plain" -> {
                            val text = extractTextFromPart(part)
                            if (text.isNotEmpty()) {
                                contentBuilder.append(text).append("\n\n")
                            }
                        }
                        "text/html" -> {
                            val text = extractTextFromPart(part)
                            if (text.isNotEmpty()) {
                                contentBuilder.append(text).append("\n\n")
                            }
                        }
                        "multipart/alternative", "multipart/mixed", "multipart/related" -> {
                            part.parts?.let { nestedParts ->
                                val nestedText = extractFromParts(nestedParts)
                                if (nestedText.isNotEmpty()) {
                                    contentBuilder.append(nestedText).append("\n\n")
                                }
                            }
                        }
                        else -> {
                            if (part.body.data != null) {
                                val text = extractTextFromPart(part)
                                if (text.isNotEmpty()) {
                                    contentBuilder.append(text).append("\n\n")
                                }
                            }
                        }
                    }
                }
                return contentBuilder.toString().trim()
            }
            when (message.payload.mimeType) {
                "text/plain" -> {
                    val result = extractTextFromPart(message.payload)
                    Log.d(TAG, "Extracted plain text: ${result.take(100)}...")
                    result
                }
                "text/html" -> {
                    val result = extractTextFromPart(message.payload)
                    Log.d(TAG, "Extracted HTML text: ${result.take(100)}...")
                    result
                }
                "multipart/alternative", "multipart/mixed", "multipart/related" -> {
                    message.payload.parts?.let { parts ->
                        val result = extractFromParts(parts)
                        Log.d(TAG, "Extracted multipart text: ${result.take(100)}...")
                        result
                    } ?: ""
                }
                else -> {
                    message.payload.parts?.let { parts ->
                        val result = extractFromParts(parts)
                        Log.d(TAG, "Extracted from parts (else case): ${result.take(100)}...")
                        result
                    } ?: {
                        val result = extractTextFromPart(message.payload)
                        Log.d(TAG, "Extracted from payload (else case): ${result.take(100)}...")
                        result
                    }()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting message body: ${e.message}", e)
            message.snippet ?: "Unable to extract message content"
        }
    }
}