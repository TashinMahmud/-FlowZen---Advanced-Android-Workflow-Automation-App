package com.google.ai.edge.gallery.ui.maps
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import android.util.Base64

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceHistoryScreen(
    navigateUp: () -> Unit,
    navigateToMap: (LatLng, Float, String?, Boolean) -> Unit = { _, _, _, _ -> } // Modified signature
) {
    val context = LocalContext.current
    val gson = remember { Gson() }
    var geofenceHistory by remember { mutableStateOf<List<GeofenceHistoryItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var retryTrigger by remember { mutableStateOf(false) }

    // Load geofence history
    LaunchedEffect(Unit, retryTrigger) {
        isLoading = true
        errorMessage = null
        try {
            geofenceHistory = loadGeofenceHistory(context, gson)
                .sortedByDescending { it.createdAt } // Sort by newest first
        } catch (e: Exception) {
            Log.e("GeofenceHistory", "Error loading history: ${e.message}", e)
            errorMessage = "Error loading geofence history: ${e.message}"
            Toast.makeText(context, "Error loading geofence history", Toast.LENGTH_SHORT).show()
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("📋 Geofence History") },
                navigationIcon = {
                    IconButton(onClick = navigateUp) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = errorMessage ?: "Unknown error",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = {
                            retryTrigger = !retryTrigger // Toggle to trigger LaunchedEffect
                        }) {
                            Text("Retry")
                        }
                    }
                }
                geofenceHistory.isEmpty() -> {
                    Text(
                        text = "No geofence history yet.\nCreate a geofence to see it here.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(geofenceHistory) { geofence ->
                            GeofenceHistoryItemCard(
                                geofence = geofence,
                                onViewOnMap = {
                                    navigateToMap(LatLng(geofence.latitude, geofence.longitude), geofence.radius, null, false)
                                },
                                onShare = {
                                    shareGeofence(context, geofence)
                                },
                                onDelete = {
                                    try {
                                        geofenceHistory = deleteGeofenceFromHistory(context, gson, geofence.id, geofenceHistory)
                                        Toast.makeText(context, "Geofence deleted", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Log.e("GeofenceHistory", "Error deleting geofence: ${e.message}", e)
                                        Toast.makeText(context, "Error deleting geofence", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceHistoryItemCard(
    geofence: GeofenceHistoryItem,
    onViewOnMap: () -> Unit,
    onShare: () -> Unit, // Added parameter
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
    val expirationFormat = remember { SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = geofence.name.ifEmpty { "Unnamed Geofence" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Created: ${dateFormat.format(Date(geofence.createdAt))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Show expiration time with countdown
                    if (geofence.expirationTime > 0) {
                        val currentTime = System.currentTimeMillis()
                        val isExpired = currentTime > geofence.expirationTime
                        if (isExpired) {
                            Text(
                                text = "Expired: ${expirationFormat.format(Date(geofence.expirationTime))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            // Calculate remaining time
                            val remainingTime = geofence.expirationTime - currentTime
                            val days = TimeUnit.MILLISECONDS.toDays(remainingTime)
                            val hours = TimeUnit.MILLISECONDS.toHours(remainingTime) % 24
                            val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingTime) % 60

                            Text(
                                text = "Expires: ${expirationFormat.format(Date(geofence.expirationTime))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Time remaining: $days days, $hours hours, $minutes minutes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        Text(
                            text = "Never expires",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Location:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Lat: ${geofence.latitude}, Lng: ${geofence.longitude}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Radius: ${geofence.radius.toInt()} meters",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Status: ${if (geofence.isActive) "Active" else "Inactive"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (geofence.isActive)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )

                    if (geofence.notifications.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Notifications: ${geofence.notifications.size}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )

                        // Show last 3 notifications
                        geofence.notifications.takeLast(3).forEach { notification ->
                            Text(
                                text = "• ${notification.recipientType}: ${notification.status} - " +
                                        "${if (notification.success) "✅" else "❌"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (notification.success)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.error
                            )
                        }

                        if (geofence.notifications.size > 3) {
                            Text(
                                text = "... and ${geofence.notifications.size - 3} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Column {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            }

            // View on Maps button at the bottom
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onViewOnMap,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = "View on Map",
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("View on Maps")
            }
        }
    }
}

private fun shareGeofence(context: Context, geofence: GeofenceHistoryItem) {
    try {
        // Create a JSON string with the geofence data
        val geofenceJson = JSONObject().apply {
            put("name", geofence.name)
            put("latitude", geofence.latitude)
            put("longitude", geofence.longitude)
            put("radius", geofence.radius)
            put("expirationTime", geofence.expirationTime)
        }.toString()

        // Base64 encode the JSON string
        val encodedData = Base64.encodeToString(geofenceJson.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)

        // Use the app's existing scheme for the deep link
        val deepLink = "com.google.ai.edge.gallery://geofence/share?data=$encodedData"

        // Create the share intent
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "I've shared a geofence with you. Click this link to add it to your app: $deepLink")
            type = "text/plain"
        }

        // Start the share activity
        context.startActivity(Intent.createChooser(shareIntent, "Share Geofence"))
    } catch (e: Exception) {
        Log.e("GeofenceHistory", "Error sharing geofence: ${e.message}", e)
        Toast.makeText(context, "Error sharing geofence", Toast.LENGTH_SHORT).show()
    }
}

private fun loadGeofenceHistory(context: Context, gson: Gson): List<GeofenceHistoryItem> {
    return try {
        val prefs = context.getSharedPreferences("GEOFENCE_PREFS", Context.MODE_PRIVATE)
        val historyJson = prefs.getString("geofence_history", "[]") ?: "[]"

        // Try to parse as a list of objects
        val jsonArray = org.json.JSONArray(historyJson)
        val historyList = mutableListOf<GeofenceHistoryItem>()

        for (i in 0 until jsonArray.length()) {
            try {
                val item = jsonArray.getJSONObject(i)

                // Extract values with defaults for missing fields
                val id = item.optString("id", "")
                val name = item.optString("name", "Unnamed Geofence")
                val latitude = item.optDouble("latitude", 0.0)
                val longitude = item.optDouble("longitude", 0.0)
                val radius = item.optDouble("radius", 0.0).toFloat()
                val createdAt = item.optLong("createdAt", 0L)
                val expirationTime = item.optLong("expirationTime", 0L)
                val isActive = item.optBoolean("isActive", false)

                // Parse notifications
                val notificationsJson = item.optJSONArray("notifications")
                val notifications = mutableListOf<NotificationHistoryItem>()

                if (notificationsJson != null) {
                    for (j in 0 until notificationsJson.length()) {
                        try {
                            val notifItem = notificationsJson.getJSONObject(j)
                            val notifId = notifItem.optString("id", "")
                            val timestamp = notifItem.optLong("timestamp", 0L)
                            val recipientType = notifItem.optString("recipientType", "")
                            val recipient = notifItem.optString("recipient", "")
                            val status = notifItem.optString("status", "")
                            val success = notifItem.optBoolean("success", false)

                            notifications.add(NotificationHistoryItem(
                                id = notifId,
                                timestamp = timestamp,
                                recipientType = recipientType,
                                recipient = recipient,
                                status = status,
                                success = success
                            ))
                        } catch (e: Exception) {
                            Log.e("GeofenceHistory", "Error parsing notification item: ${e.message}", e)
                        }
                    }
                }

                historyList.add(GeofenceHistoryItem(
                    id = id,
                    name = name,
                    latitude = latitude,
                    longitude = longitude,
                    radius = radius,
                    createdAt = createdAt,
                    expirationTime = expirationTime,
                    isActive = isActive,
                    notifications = notifications
                ))
            } catch (e: Exception) {
                Log.e("GeofenceHistory", "Error parsing geofence item at index $i: ${e.message}", e)
            }
        }

        // Check if we need to migrate old data (without name and expirationTime)
        if (historyList.isNotEmpty()) {
            val needsMigration = historyList.any { it.name == "Unnamed Geofence" && it.expirationTime == 0L }
            if (needsMigration) {
                // Save the migrated data
                val updatedHistoryJson = gson.toJson(historyList)
                prefs.edit().putString("geofence_history", updatedHistoryJson).apply()
                Log.d("GeofenceHistory", "Migrated old geofence history to new format")
            }
        }

        historyList
    } catch (e: Exception) {
        Log.e("GeofenceHistory", "Error loading geofence history: ${e.message}", e)
        emptyList()
    }
}

private fun deleteGeofenceFromHistory(
    context: Context,
    gson: Gson,
    geofenceId: String,
    currentHistory: List<GeofenceHistoryItem>
): List<GeofenceHistoryItem> {
    return try {
        val prefs = context.getSharedPreferences("GEOFENCE_PREFS", Context.MODE_PRIVATE)
        val updatedHistory = currentHistory.filter { it.id != geofenceId }
        val updatedHistoryJson = gson.toJson(updatedHistory)
        prefs.edit().putString("geofence_history", updatedHistoryJson).apply()
        updatedHistory
    } catch (e: Exception) {
        Log.e("GeofenceHistory", "Error deleting geofence: ${e.message}", e)
        currentHistory
    }
}