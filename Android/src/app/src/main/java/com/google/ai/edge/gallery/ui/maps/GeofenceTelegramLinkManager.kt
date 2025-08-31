package com.google.ai.edge.gallery.ui.maps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

object GeofenceTelegramLinkManager {

    private const val PREFS = "TELEGRAM_PREFS"
    private const val KEY_LINK_PAYLOAD = "tg_link_payload"
    private const val KEY_LAST_UPDATE_ID = "tg_last_update_id"
    private const val KEY_CHAT_ID = "chat_id"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Old helper: open Telegram directly (kept for compatibility) */
    fun createAndOpenDeepLink(context: Context, botUsername: String): String {
        val payload = UUID.randomUUID().toString().replace("-", "").take(32)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LINK_PAYLOAD, payload).apply()

        val url = "https://t.me/$botUsername?start=${URLEncoder.encode(payload, "UTF-8")}"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't open Telegram app. Is it installed?", Toast.LENGTH_LONG).show()
            Log.e("TGLinkManager", "Failed to open Telegram: ${e.message}", e)
        }
        return payload
    }

    /** NEW: generate a deep-link **for sharing** (does NOT launch Telegram). */
    fun createShareLink(context: Context, botUsername: String): String {
        val payload = UUID.randomUUID().toString().replace("-", "").take(32)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LINK_PAYLOAD, payload).apply()
        return "https://t.me/$botUsername?start=${URLEncoder.encode(payload, "UTF-8")}"
    }

    /** Resolve chat_id after the user taps START in Telegram for the payload we stored. */
    suspend fun tryResolveChatId(context: Context, botToken: String): Long? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val payload = prefs.getString(KEY_LINK_PAYLOAD, null) ?: return@withContext null

        val base = "https://api.telegram.org/bot$botToken/getUpdates"
        val offset = prefs.getLong(KEY_LAST_UPDATE_ID, 0L) + 1
        val url = if (offset > 1) "$base?offset=$offset" else base
        Log.d("TGLinkManager", "getUpdates: $url (expecting /start $payload)")

        val res = client.newCall(Request.Builder().url(url).build()).execute()
        val bodyStr = res.body?.string()
        if (!res.isSuccessful || bodyStr.isNullOrBlank()) return@withContext null

        val json = JSONObject(bodyStr)
        if (!json.optBoolean("ok", false)) return@withContext null

        val results = json.optJSONArray("result") ?: return@withContext null
        var lastUpdateId = prefs.getLong(KEY_LAST_UPDATE_ID, 0L)
        var resolved: Long? = null

        for (i in 0 until results.length()) {
            val upd = results.getJSONObject(i)
            val updateId = upd.optLong("update_id", lastUpdateId)
            if (updateId > lastUpdateId) lastUpdateId = updateId

            fun scan(objName: String) {
                val msg = upd.optJSONObject(objName) ?: return
                val text = msg.optString("text", "")
                val chat = msg.optJSONObject("chat")
                val cid = chat?.optLong("id", -1L) ?: -1L
                if (text.startsWith("/start ") && text.trim() == "/start $payload" && cid != -1L) {
                    resolved = cid
                }
            }
            scan("message")
            if (resolved == null) scan("edited_message")
        }

        prefs.edit().putLong(KEY_LAST_UPDATE_ID, lastUpdateId).apply()

        if (resolved != null && resolved != -1L) {
            prefs.edit().putLong(KEY_CHAT_ID, resolved!!).remove(KEY_LINK_PAYLOAD).apply()
            Log.d("TGLinkManager", "Resolved chat_id=$resolved for payload=$payload")
        }
        resolved
    }

    fun getStoredChatId(context: Context): Long? {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_CHAT_ID, -1L)
        return if (id == -1L) null else id
    }
}
