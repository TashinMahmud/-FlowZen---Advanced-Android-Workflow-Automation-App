package com.google.ai.edge.gallery.ui.maps

import android.content.Context
import android.util.Log
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object TelegramSender {
    // Dedicated to geofencing; keep token here so we don't depend on other Telegram helpers.
    private const val BOT_TOKEN = "8123513934:AAHybG4oY02mdAwcr8odWwjtD_X5eoOcpvA"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Send message to a specific chat id.
     */
    suspend fun sendMessage(chatId: Long, text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "https://api.telegram.org/bot$BOT_TOKEN/sendMessage?chat_id=$chatId&text=$encodedText"
            Log.d("TelegramSender", "Sending Telegram message to $chatId")
            client.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (res.isSuccessful) {
                    Log.d("TelegramSender", "Message sent.")
                    return@use true
                } else {
                    Log.e("TelegramSender", "Failed: HTTP ${res.code}")
                    return@use false
                }
            }
        } catch (e: Exception) {
            Log.e("TelegramSender", "Error: ${e.message}", e)
            false
        }
    }

    /**
     * Convenience: send to stored chat id (if connected).
     */
    suspend fun sendMessage(context: Context, text: String): Boolean {
        val chatId = GeofenceTelegramLinkManager.getStoredChatId(context)
        if (chatId == null) {
            Log.e("TelegramSender", "chat_id is null. Connect Telegram first.")
            return false
        }
        return sendMessage(chatId, text)
    }
}
