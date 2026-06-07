package com.google.ai.edge.gallery.ui.workflow
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import kotlin.concurrent.thread
class TelegramDeepLinkHelperWork(private val context: Context, private val workflowId: String) {
    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("TelegramPrefs_$workflowId", Context.MODE_PRIVATE)
    val KEY_CHAT_ID = "telegram_chat_id"
    val KEY_USERNAME = "telegram_username"
    val KEY_TOKEN = "telegram_token"
    val KEY_UPDATE_OFFSET = "update_offset"
    fun getWorkflowId(): String = workflowId
    fun getChatIdKey(): String = KEY_CHAT_ID
    fun getUsernameKey(): String = KEY_USERNAME
    private val BOT_TOKEN = "YOUR_TELEGRAM_BOT_TOKEN_HERE"
    private val BOT_USERNAME = "flowzen_aibot"
    private val TELEGRAM_API = "https://api.telegram.org/bot"
    interface ConnectionCallback {
        fun onConnectionStatusChanged(workflowId: String, isConnected: Boolean, status: String?, chatId: String?, username: String?)
    }
    var connectionCallback: ConnectionCallback? = null
    fun startInviteFlow(): String {
        val token = UUID.randomUUID().toString()
        val link = "https://t.me/$BOT_USERNAME?start=$token"
        prefs.edit().putString(KEY_TOKEN, token).apply()
        shareInviteLink(link)
        Log.d("WorkflowDeepLink", "Generated deep link: $link")
        updateStatus("Waiting for recipient to press Start…")
        connectionCallback?.onConnectionStatusChanged(workflowId, false, "Waiting for recipient to press Start…", null, null)
        startPollingForToken(token)
        return link
    }
    fun sendMessageToChat(chatId: String, message: String): Boolean {
        return try {
            val url = "$TELEGRAM_API$BOT_TOKEN/sendMessage"
            val mediaType = "application/x-www-form-urlencoded".toMediaType()
            val requestBody = "chat_id=$chatId&text=${
                java.net.URLEncoder.encode(message, "UTF-8")
            }&parse_mode=HTML".toRequestBody(mediaType)
            val request = okhttp3.Request.Builder().url(url).post(requestBody).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d("WorkflowDeepLink", "Message sent successfully to chat $chatId")
                true
            } else {
                Log.e("WorkflowDeepLink", "Failed to send message: ${response.code} ${response.message}")
                false
            }
        } catch (e: Exception) {
            Log.e("WorkflowDeepLink", "Error sending message: ${e.message}", e)
            false
        }
    }
    private fun shareInviteLink(link: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
        }
        context.startActivity(Intent.createChooser(sendIntent, "Share via"))
    }
    private fun startPollingForToken(token: String) {
        thread {
            var offset = prefs.getInt(KEY_UPDATE_OFFSET, 0)
            Log.d("WorkflowDeepLink", "Starting polling with token: $token, offset: $offset")
            while (true) {
                try {
                    val updates = getUpdates(offset)
                    Log.d("WorkflowDeepLink", "Received updates: $updates")
                    val found = findStartForToken(updates, token)
                    if (found != null) {
                        saveConnectionDetails(found.chatId.toString(), found.username)
                        handler.post {
                            val statusMessage = "Connected to @${found.username ?: "user"} (chat_id=${found.chatId})"
                            updateStatus(statusMessage)
                            toast("Connected!")
                            connectionCallback?.onConnectionStatusChanged(workflowId, true, statusMessage, found.chatId.toString(), found.username)
                        }
                        break
                    }
                    offset = updates.optInt("result", 0)
                    if (offset > 0) {
                        saveUpdateOffset(offset)
                    }
                } catch (e: Exception) {
                    Log.e("WorkflowDeepLink", "Poll error: ${e.message}", e)
                }
                Thread.sleep(1000)
            }
        }
    }
    private fun getUpdates(offset: Int): JSONObject {
        val url = "$TELEGRAM_API$BOT_TOKEN/getUpdates?timeout=20&allowed_updates=%5B%22message%22%5D&offset=$offset"
        Log.d("WorkflowDeepLink", "Requesting updates from: $url")
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            Log.d("WorkflowDeepLink", "Response body: $body")
            return JSONObject(body)
        }
    }
    private fun findStartForToken(updates: JSONObject, token: String): StartEvent? {
        val result = updates.optJSONArray("result") ?: return null
        var maxUpdateId: Int? = null
        var match: StartEvent? = null
        Log.d("WorkflowDeepLink", "Processing ${result.length()} updates")
        for (i in 0 until result.length()) {
            val upd = result.getJSONObject(i)
            val updateId = upd.optInt("update_id")
            maxUpdateId = maxOf(maxUpdateId ?: updateId, updateId)
            val msg = upd.optJSONObject("message") ?: continue
            val text = msg.optString("text", "").trim()
            Log.d("WorkflowDeepLink", "Message text: $text")
            if (text.startsWith("/start")) {
                val arg = text.removePrefix("/start").trim()
                Log.d("WorkflowDeepLink", "Start argument: '$arg', expected: '$token'")
                if (arg == token) {
                    val chat = msg.optJSONObject("chat") ?: continue
                    val from = msg.optJSONObject("from")
                    val chatId = chat.optLong("id")
                    val username = from?.optString("username")
                    Log.d("WorkflowDeepLink", "Found matching token! Chat ID: $chatId, Username: $username")
                    match = StartEvent(chatId, username)
                }
            }
        }
        if (maxUpdateId != null) {
            saveUpdateOffset(maxUpdateId + 1)
            Log.d("WorkflowDeepLink", "Saved update offset: ${maxUpdateId + 1}")
        }
        return match
    }
    private fun saveConnectionDetails(chatId: String, username: String?) {
        prefs.edit()
            .putString(KEY_CHAT_ID, chatId)
            .putString(KEY_USERNAME, username)
            .remove(KEY_TOKEN)
            .apply()
        Log.d("WorkflowDeepLink", "Saved connection details - Chat ID: $chatId, Username: $username")
    }
    private fun updateStatus(status: String) {
        Log.d("WorkflowDeepLink", status)
    }
    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
    private fun saveUpdateOffset(offset: Int) {
        prefs.edit().putInt(KEY_UPDATE_OFFSET, offset).apply()
    }
    fun checkConnectionStatus(): Triple<Boolean, String?, String?> {
        val chatId = prefs.getString(KEY_CHAT_ID, null)
        val username = prefs.getString(KEY_USERNAME, null)
        return if (chatId != null) {
            Triple(true, "Connected to @${username ?: "user"}", chatId)
        } else {
            Triple(false, "Not connected to Telegram", null)
        }
    }
    fun disconnect() {
        prefs.edit()
            .remove(KEY_CHAT_ID)
            .remove(KEY_USERNAME)
            .remove(KEY_TOKEN)
            .apply()
        Log.d("WorkflowDeepLink", "Disconnected from Telegram for workflow: $workflowId")
        connectionCallback?.onConnectionStatusChanged(workflowId, false, "Not connected to Telegram", null, null)
    }
    fun getChatId(): String? {
        return prefs.getString(KEY_CHAT_ID, null)
    }
    // Add this method to set connection state
    fun setConnectionState(chatId: String?, username: String?, status: String?) {
        prefs.edit()
            .putString(KEY_CHAT_ID, chatId)
            .putString(KEY_USERNAME, username)
            .apply()
        Log.d("WorkflowDeepLink", "Set connection state for workflow $workflowId: chatId=$chatId, username=$username")
    }
    private data class StartEvent(val chatId: Long, val username: String?)
}