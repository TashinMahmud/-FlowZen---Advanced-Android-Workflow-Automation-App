/*
 * Telegram deep-link helper for /start <token> flow.
 */
package com.google.ai.edge.gallery.ui.camflow

import android.content.Context
import android.content.Intent
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

object TelegramDeepLinkHelper {
    private const val TAG = "TelegramDeepLink"
    private const val TELEGRAM_API = "https://api.telegram.org"

    // SharedPreferences keys
    private const val PREFS = "tg_prefs"
    private const val KEY_CHAT_ID = "tg_receiver_chat_id"
    private const val KEY_USERNAME = "tg_receiver_username"
    private const val KEY_UPDATE_OFFSET = "tg_update_offset"
    private const val KEY_WEBHOOK_DELETED = "tg_webhook_deleted"

    data class StartEvent(val chatId: Long, val username: String?)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ---- Public storage helpers ----
    fun saveChatId(context: Context, chatId: Long?, username: String?) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        with(sp.edit()) {
            if (chatId == null) remove(KEY_CHAT_ID) else putLong(KEY_CHAT_ID, chatId)
            if (username == null) remove(KEY_USERNAME) else putString(KEY_USERNAME, username)
            apply()
        }
    }

    fun loadChatId(context: Context): Long? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = sp.getLong(KEY_CHAT_ID, -1L)
        return if (v > 0) v else null
    }

    fun loadUsername(context: Context): String? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getString(KEY_USERNAME, null)
    }

    fun clearReceiver(context: Context) {
        saveChatId(context, null, null)
    }

    // ---- Invite construction & share ----
    fun newToken(): String = UUID.randomUUID().toString()
    fun buildInviteLink(botUsername: String, token: String): String =
        "https://t.me/$botUsername?start=$token"

    fun shareText(context: Context, text: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(sendIntent, "Share via"))
    }

    // ---- Webhook off so polling works ----
    fun deleteWebhookIfNeeded(context: Context, botToken: String) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_WEBHOOK_DELETED, false)) return
        runCatching {
            val url = "$TELEGRAM_API/bot$botToken/deleteWebhook"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { /* ignore */ }
            sp.edit().putBoolean(KEY_WEBHOOK_DELETED, true).apply()
        }
    }

    // ---- Update offset helpers ----
    private fun saveUpdateOffset(context: Context, offset: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_UPDATE_OFFSET, offset).apply()
    }

    fun clearUpdateOffset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_UPDATE_OFFSET).apply()
    }

    private fun loadUpdateOffset(context: Context): Int? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (sp.contains(KEY_UPDATE_OFFSET)) sp.getInt(KEY_UPDATE_OFFSET, -1) else null
    }

    // ---- Polling for /start <token> ----
    fun getUpdates(context: Context, botToken: String): JSONObject {
        val offset = loadUpdateOffset(context)
        val url = buildString {
            append("$TELEGRAM_API/bot$botToken/getUpdates?timeout=20&allowed_updates=%5B%22message%22%5D")
            if (offset != null) append("&offset=$offset")
        }
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            Log.d(TAG, "getUpdates: $body")
            return JSONObject(body)
        }
    }

    fun findStartForToken(context: Context, updates: JSONObject, token: String): StartEvent? {
        if (!updates.optBoolean("ok", false)) return null
        val result: JSONArray = updates.optJSONArray("result") ?: return null
        var maxUpdateId: Int? = null
        var match: StartEvent? = null

        for (i in 0 until result.length()) {
            val upd = result.getJSONObject(i)
            val updateId = upd.optInt("update_id")
            maxUpdateId = maxOf(maxUpdateId ?: updateId, updateId)

            val msg = upd.optJSONObject("message") ?: continue
            val text = msg.optString("text", "").trim()

            if (text.startsWith("/start")) {
                val arg = text.removePrefix("/start").trim()
                if (arg == token) {
                    val chat = msg.optJSONObject("chat") ?: continue
                    val from = msg.optJSONObject("from")
                    val chatId = chat.optLong("id")
                    val username = from?.optString("username")
                    match = StartEvent(chatId, username)
                }
            }
        }

        if (maxUpdateId != null) saveUpdateOffset(context, maxUpdateId + 1)
        return match
    }
}
