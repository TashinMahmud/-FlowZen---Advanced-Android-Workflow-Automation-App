package com.google.ai.edge.gallery.ui.navigation

import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedContentTransitionScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.tasks.Tasks
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.ListMessagesResponse
import com.google.api.services.gmail.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TASK_LLM_ASK_AUDIO
import com.google.ai.edge.gallery.data.TASK_LLM_ASK_IMAGE
import com.google.ai.edge.gallery.data.TASK_LLM_CHAT
import com.google.ai.edge.gallery.data.TASK_LLM_PROMPT_LAB
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.TaskType
import com.google.ai.edge.gallery.data.getModelByName
import com.google.ai.edge.gallery.ui.ViewModelProvider
import com.google.ai.edge.gallery.ui.home.HomeScreen
import com.google.ai.edge.gallery.ui.llmchat.LlmAskAudioDestination
import com.google.ai.edge.gallery.ui.llmchat.LlmAskAudioScreen
import com.google.ai.edge.gallery.ui.llmchat.LlmAskImageDestination
import com.google.ai.edge.gallery.ui.llmchat.LlmAskImageScreen
import com.google.ai.edge.gallery.ui.llmchat.LlmChatDestination
import com.google.ai.edge.gallery.ui.llmchat.LlmChatScreen
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.gallery.ui.llmsingleturn.LlmSingleTurnDestination
import com.google.ai.edge.gallery.ui.llmsingleturn.LlmSingleTurnScreen
import com.google.ai.edge.gallery.ui.modelmanager.ModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.auth.AuthViewModel
import com.google.ai.edge.gallery.data.TASK_CREATE_WORKFLOW
import com.google.ai.edge.gallery.ui.common.humanReadableSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Environment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume



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

    fun saveEmailsToFile(emails: List<EmailData>, accountEmail: String) {
        try {
            val fileName = "gmail_emails_${accountEmail.replace("@", "_at_").replace(".", "_")}.txt"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val currentTime = dateFormat.format(Date())

            val newEmails = buildList {
                add("=== GMAIL EMAILS FOR ${accountEmail.uppercase()} ===")
                add("File updated: $currentTime")
                add("Latest 10 emails from inbox")
                add("")
                emails.forEach { email ->
                    add("EMAIL ID: ${email.id}")
                    add("SUBJECT: ${email.subject}")
                    add("FROM: ${email.from}")
                    add("TO: ${email.to}")
                    add("DATE: ${email.date}")
                    add("TIMESTAMP: ${email.timestamp}")
                    add("SNIPPET: ${email.snippet}")
                    add("BODY:")
                    add(email.body)
                    add("---")
                    add("")
                }
            }

            file.writeText(newEmails.joinToString("\n"))
            Log.d(TAG, "Saved ${emails.size} emails to file: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving emails to file: ${e.message}", e)
        }
    }

    fun loadExistingEmailIds(accountEmail: String): Set<String> {
        return try {
            val fileName = "gmail_emails_${accountEmail.replace("@", "_at_").replace(".", "_")}.txt"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)

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
            val fileName = "gmail_emails_${accountEmail.replace("@", "_at_").replace(".", "_")}.txt"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            if (!file.exists()) "No saved emails found." else file.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading saved emails: ${e.message}", e)
            "Error reading saved emails: ${e.message}"
        }
    }
    fun getGoogleAccountAndToken(context: Context): Pair<com.google.android.gms.auth.api.signin.GoogleSignInAccount?, String?> {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        val token = account?.idToken
        return Pair(account, token)
    }
    class GmailApiHelper(private val context: Context, private val account: com.google.android.gms.auth.api.signin.GoogleSignInAccount) {
        private val telegramHelper = TelegramHelper(context)
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
    }

}
