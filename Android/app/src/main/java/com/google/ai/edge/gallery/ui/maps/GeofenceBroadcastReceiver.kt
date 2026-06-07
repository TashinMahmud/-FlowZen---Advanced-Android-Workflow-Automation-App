package com.google.ai.edge.gallery.ui.maps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// Data class for a single geofence
data class GeofenceHistoryItem(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Float,
    val createdAt: Long,
    val expirationTime: Long,
    var isActive: Boolean = false,
    val notifications: List<NotificationHistoryItem> = emptyList()
)

// Data class for notification history
data class NotificationHistoryItem(
    val id: String,
    val timestamp: Long,
    val recipientType: String, // "TELEGRAM" or "EMAIL"
    val recipient: String,     // chat ID or email address
    val status: String,        // "ENTERED", "EXITED", "DWELLING", "INSIDE"
    val success: Boolean
)

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    private val gson = Gson()
    private val geofenceHistoryKey = "geofence_history"

    override fun onReceive(context: Context, intent: Intent) {
        val geoPrefs = context.getSharedPreferences("GEOFENCE_PREFS", Context.MODE_PRIVATE)

        // Manual trigger path
        if (intent.action == "com.google.ai.edge.gallery.ui.maps.MANUAL_GEOFENCE_TRIGGER") {
            val requestId = intent.getStringExtra("geofence_request_id") ?: "Unknown"
            val chatId = intent.getLongExtra("chat_id", -1)
            val isManualTrigger = intent.getBooleanExtra("manual_trigger", false)
            val latitude = intent.getDoubleExtra("geofence_latitude", 0.0)
            val longitude = intent.getDoubleExtra("geofence_longitude", 0.0)
            val radius = intent.getFloatExtra("geofence_radius", 0.0f)

            Log.d("GeofenceReceiver", "🔔 Manual trigger: requestId=$requestId, chatId=$chatId, manual=$isManualTrigger")

            Toast.makeText(context, "📍 Geofence alert: You're inside the monitored area!", Toast.LENGTH_LONG).show()

            // mark that we just fired a manual trigger to avoid echo ENTER soon after
            geoPrefs.edit().putBoolean("manual_trigger_sent", true).apply()

            sendAlerts(context, requestId, chatId, isManualTrigger, "INSIDE", latitude, longitude, radius)
            return
        }

        // Regular geofence events
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent?.hasError() == true) {
            Log.e("GeofenceReceiver", "❌ Geofencing event error: ${geofencingEvent.errorCode}")
            return
        }

        val transitionType = geofencingEvent?.geofenceTransition
        if (transitionType == null) {
            Log.w("GeofenceReceiver", "⚠️ Null transitionType; ignoring")
            return
        }

        val requestId = intent.getStringExtra("geofence_request_id") ?: "Unknown"
        val chatId = intent.getLongExtra("chat_id", -1)

        // Prefer values provided via intent extras (we add them when registering).
        // If absent, fall back to last saved geofence details in GEOFENCE_PREFS.
        val extraLat = intent.getDoubleExtra("geofence_latitude", 0.0)
        val extraLng = intent.getDoubleExtra("geofence_longitude", 0.0)
        val extraRadius = intent.getFloatExtra("geofence_radius", 0.0f)

        val savedLat = geoPrefs.getFloat("latitude", 0f).toDouble()
        val savedLng = geoPrefs.getFloat("longitude", 0f).toDouble()
        val savedRadius = geoPrefs.getFloat("radius", 0f)

        val finalLatitude = if (extraLat != 0.0) extraLat else savedLat
        val finalLongitude = if (extraLng != 0.0) extraLng else savedLng
        val finalRadius = if (extraRadius != 0.0f) extraRadius else savedRadius

        Log.d("GeofenceReceiver", "📡 Event: type=$transitionType, requestId=$requestId, chatId=$chatId, lat=$finalLatitude, lng=$finalLongitude, r=$finalRadius")

        // Expiration check (use the stored expiration_time for the active geofence)
        val expirationTime = geoPrefs.getLong("expiration_time", 0L)
        if (expirationTime > 0 && System.currentTimeMillis() > expirationTime) {
            Log.d("GeofenceReceiver", "⏰ Geofence expired; ignoring event")
            return
        }

        var statusMessage = ""
        var toastMessage = ""

        when (transitionType) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                val lastNotificationTime = geoPrefs.getLong("last_notification_time", 0)
                val currentTime = System.currentTimeMillis()
                val twoMinutes = 2 * 60 * 1000L

                val manualTriggerSent = geoPrefs.getBoolean("manual_trigger_sent", false)
                if (manualTriggerSent) {
                    Log.d("GeofenceReceiver", "⏱️ Skipping ENTER due to recent manual trigger")
                    geoPrefs.edit().putBoolean("manual_trigger_sent", false).apply()
                    return
                }

                if (currentTime - lastNotificationTime < twoMinutes) {
                    Log.d("GeofenceReceiver", "⏱️ Cooldown active; skipping ENTER")
                    return
                }

                geoPrefs.edit().putLong("last_notification_time", currentTime).apply()

                statusMessage = "ENTERED"
                toastMessage = "📍 Entered geofence area"
                Log.d("GeofenceReceiver", "✅ ENTER -> sending alerts")
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                statusMessage = "EXITED"
                toastMessage = "📤 Exited geofence area"
                Log.d("GeofenceReceiver", "📤 EXIT")
            }
            Geofence.GEOFENCE_TRANSITION_DWELL -> {
                statusMessage = "DWELLING"
                toastMessage = "🏠 Dwelling in geofence area"
                Log.d("GeofenceReceiver", "🏠 DWELL")
            }
            else -> {
                Log.w("GeofenceReceiver", "⚠️ Unknown transition $transitionType")
                return
            }
        }

        if (toastMessage.isNotEmpty()) {
            Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
        }

        sendAlerts(context, requestId, chatId, false, statusMessage, finalLatitude, finalLongitude, finalRadius)
    }

    private fun sendAlerts(
        context: Context,
        requestId: String,
        chatId: Long,
        isManualTrigger: Boolean,
        status: String = "INSIDE",
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        radius: Float = 0.0f
    ) {
        // Read email address from TELEGRAM_PREFS and only use if non-blank
        val accountPrefs = context.getSharedPreferences("TELEGRAM_PREFS", Context.MODE_PRIVATE)
        val emailAddress = accountPrefs.getString("email_address", null)?.trim()

        // Build optional location block
        val locationInfo = if (latitude != 0.0 && longitude != 0.0) {
            "\n\nLocation Details:\nLatitude: $latitude\nLongitude: $longitude\nRadius: ${radius.toInt()} meters"
        } else {
            ""
        }

        // Telegram
        if (chatId != -1L) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Log.d("GeofenceReceiver", "📱 Telegram alert...")
                    val messagePrefix = if (isManualTrigger) "🔔" else "📍 "
                    val statusText = when (status) {
                        "ENTERED" -> "has ENTERED the geofence area"
                        "EXITED" -> "has EXITED the geofence area"
                        "DWELLING" -> "is DWELLING in the geofence area"
                        "INSIDE" -> "is INSIDE the geofence area"
                        else -> "triggered geofence event"
                    }

                    val success = TelegramSender.sendMessage(
                        chatId = chatId,
                        text = messagePrefix + "Someone $statusText!$locationInfo\n\n" +
                                "Request ID: $requestId\n" +
                                "Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
                    )

                    addNotificationToHistory(
                        context,
                        "geofence_${latitude}_${longitude}_${radius}",
                        "TELEGRAM",
                        chatId.toString(),
                        status,
                        success,
                        latitude,
                        longitude,
                        radius
                    )

                    if (success) Log.d("GeofenceReceiver", "✅ Telegram alert sent")
                    else Log.e("GeofenceReceiver", "❌ Telegram alert failed")
                } catch (e: Exception) {
                    Log.e("GeofenceReceiver", "❌ Telegram send crash: ${e.message}", e)
                }
            }
        } else {
            Log.w("GeofenceReceiver", "⚠️ No Telegram chat_id available")
        }

        // Email — ONLY if non-blank
        if (!emailAddress.isNullOrBlank()) {
            Log.d("GeofenceReceiver", "📧 Email configured: $emailAddress")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val success = sendGmailApiAlert(
                        toEmail = emailAddress,
                        context = context,
                        isManualTrigger = isManualTrigger,
                        status = status,
                        latitude = latitude,
                        longitude = longitude,
                        radius = radius
                    )

                    addNotificationToHistory(
                        context,
                        "geofence_${latitude}_${longitude}_${radius}",
                        "EMAIL",
                        emailAddress,
                        status,
                        success,
                        latitude,
                        longitude,
                        radius
                    )

                    if (success) Log.d("GeofenceReceiver", "✅ Email alert sent")
                    else Log.e("GeofenceReceiver", "❌ Email alert failed")
                } catch (e: Exception) {
                    Log.e("GeofenceReceiver", "❌ Email send crash: ${e.message}", e)
                }
            }
        } else {
            Log.w("GeofenceReceiver", "⚠️ No email connected; skipping email notification")
        }
    }

    private fun addNotificationToHistory(
        context: Context,
        geofenceId: String,
        recipientType: String,
        recipient: String,
        status: String,
        success: Boolean,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        radius: Float = 0.0f
    ) {
        try {
            val sharedPreferences = context.getSharedPreferences("GEOFENCE_PREFS", Context.MODE_PRIVATE)
            val historyJson = sharedPreferences.getString(geofenceHistoryKey, null)

            val type = object : TypeToken<List<GeofenceHistoryItem>>() {}.type
            val historyList = if (historyJson != null) {
                gson.fromJson<List<GeofenceHistoryItem>>(historyJson, type).toMutableList()
            } else {
                mutableListOf()
            }

            val geofenceIndex = historyList.indexOfFirst {
                it.latitude == latitude && it.longitude == longitude && it.radius == radius
            }

            if (geofenceIndex != -1) {
                val geofence = historyList[geofenceIndex]
                val notification = NotificationHistoryItem(
                    id = "notif_${System.currentTimeMillis()}",
                    timestamp = System.currentTimeMillis(),
                    recipientType = recipientType,
                    recipient = recipient,
                    status = status,
                    success = success
                )
                val updated = geofence.copy(notifications = geofence.notifications + notification)
                historyList[geofenceIndex] = updated
            } else {
                val newGeofence = GeofenceHistoryItem(
                    id = "geofence_${System.currentTimeMillis()}",
                    name = "Unnamed Geofence",
                    latitude = latitude,
                    longitude = longitude,
                    radius = radius,
                    createdAt = System.currentTimeMillis(),
                    expirationTime = 0,
                    isActive = true,
                    notifications = listOf(
                        NotificationHistoryItem(
                            id = "notif_${System.currentTimeMillis()}",
                            timestamp = System.currentTimeMillis(),
                            recipientType = recipientType,
                            recipient = recipient,
                            status = status,
                            success = success
                        )
                    )
                )
                historyList.add(newGeofence)
            }

            sharedPreferences.edit().putString(geofenceHistoryKey, gson.toJson(historyList)).apply()
            Log.d("GeofenceReceiver", "✅ Notification history updated")
        } catch (e: Exception) {
            Log.e("GeofenceReceiver", "❌ Failed to update history: ${e.message}", e)
        }
    }

    private suspend fun sendGmailApiAlert(
        toEmail: String,
        context: Context,
        isManualTrigger: Boolean,
        status: String = "INSIDE",
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        radius: Float = 0.0f
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("GeofenceReceiver", "📧 Creating Gmail API email...")
                val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)
                if (account == null) {
                    Log.e("GeofenceReceiver", "❌ No Google account signed in")
                    return@withContext false
                }

                val credential = com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential.usingOAuth2(
                    context,
                    listOf("https://www.googleapis.com/auth/gmail.send")
                ).setSelectedAccount(account.account)

                val gmailService = com.google.api.services.gmail.Gmail.Builder(
                    com.google.api.client.http.javanet.NetHttpTransport(),
                    JacksonFactory(),
                    credential
                )
                    .setApplicationName("Geofence Alert App")
                    .build()

                val locationInfo = if (latitude != 0.0 && longitude != 0.0) {
                    "\n\nLocation Details:\nLatitude: $latitude\nLongitude: $longitude\nRadius: ${radius.toInt()} meters"
                } else ""

                val subjectPrefix = if (isManualTrigger) "🔔" else "📍 "
                val statusText = when (status) {
                    "ENTERED" -> "has ENTERED the geofence area"
                    "EXITED" -> "has EXITED the geofence area"
                    "DWELLING" -> "is DWELLING in the geofence area"
                    "INSIDE" -> "is INSIDE the geofence area"
                    else -> "triggered geofence event"
                }

                val emailContent = """From: ${account.email}
To: $toEmail
Subject: ${subjectPrefix}Geofence Alert: Someone $statusText
Content-Type: text/plain; charset=UTF-8

Someone $statusText!

This is an automated alert from your Geofence Map app.$locationInfo

Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}

Best regards,
Geofence Map App"""

                val encodedEmail = android.util.Base64.encodeToString(
                    emailContent.toByteArray(),
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )

                val message = com.google.api.services.gmail.model.Message().setRaw(encodedEmail)
                val sentMessage = gmailService.users().messages().send("me", message).execute()
                Log.d("GeofenceReceiver", "✅ Gmail API email sent: ${sentMessage.id}")
                true
            } catch (e: Exception) {
                Log.e("GeofenceReceiver", "❌ Gmail API email failed: ${e.message}", e)
                false
            }
        }
    }

    private fun loadGeofenceHistory(context: Context, gson: Gson): List<GeofenceHistoryItem> {
        return try {
            val prefs = context.getSharedPreferences("GEOFENCE_PREFS", Context.MODE_PRIVATE)
            val historyJson = prefs.getString("geofence_history", "[]")
            val type = object : TypeToken<List<GeofenceHistoryItem>>() {}.type
            gson.fromJson(historyJson, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e("GeofenceReceiver", "Error loading geofence history: ${e.message}", e)
            emptyList()
        }
    }
}
