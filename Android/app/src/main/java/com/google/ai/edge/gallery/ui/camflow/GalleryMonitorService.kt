/*
 * Copyright 2025 Google LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 */
package com.google.ai.edge.gallery.ui.camflow

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class GalleryMonitorService : Service() {
    companion object {
        private const val TAG = "GalleryMonitorService"
        private const val CHANNEL_ID = "gallery_monitor_channel"
        private const val NOTIF_ID = 7462
        private const val CONTENT_OBSERVER_DELAY = 2000L // 2 seconds delay
        private const val PREFS_NAME = "gallery_monitor_prefs"
        private const val KEY_IS_MONITORING = "is_monitoring"
        private const val DAILY_STATS_PREFS = "camflow_daily_stats"
        private const val KEY_COUNT_DATE = "count_date"
        private const val KEY_TODAY_COUNT = "today_count"
        private const val KEY_MONITORING_START_TIME = "monitoring_start_time"
        private const val KEY_LAST_SCAN_TIME = "last_scan_time"
        private const val KEY_PROCESSED_URIS = "processed_uris"
        private const val MAX_PROCESSED_URIS = 1000
        private const val SCAN_INTERVAL = 60000L // 1 minute in milliseconds (reduced for better responsiveness)

        fun start(context: Context) {
            val intent = Intent(context, GalleryMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            setMonitoringState(context, true)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GalleryMonitorService::class.java))
            setMonitoringState(context, false)
        }

        fun isMonitoring(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_IS_MONITORING, false)
        }

        private fun setMonitoringState(context: Context, isMonitoring: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_IS_MONITORING, isMonitoring)
                .apply()
        }

        fun getTodayImageCount(context: Context): Int {
            val prefs = context.getSharedPreferences(DAILY_STATS_PREFS, Context.MODE_PRIVATE)
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val storedDate = prefs.getString(KEY_COUNT_DATE, "")
            return if (storedDate == today) {
                prefs.getInt(KEY_TODAY_COUNT, 0)
            } else {
                0
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var contentObserver: ContentObserver? = null
    private val processedUris = ConcurrentHashMap<String, Long>()
    private var lastScanTime = 0L
    private var monitoringStartTime = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Monitoring gallery for new images"))
        Log.d(TAG, "GalleryMonitorService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        initializeDailyCount()
        setupContentObserver()
        startPeriodicScan()
        return START_STICKY
    }

    private fun initializeDailyCount() {
        val prefs = getSharedPreferences(DAILY_STATS_PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val storedDate = prefs.getString(KEY_COUNT_DATE, "")
        if (storedDate != today) {
            // New day, reset counters
            prefs.edit()
                .putString(KEY_COUNT_DATE, today)
                .putInt(KEY_TODAY_COUNT, 0)
                .remove(KEY_PROCESSED_URIS)
                .apply()
            processedUris.clear()
            Log.d(TAG, "Reset daily counters for new day: $today")
        } else {
            // Load processed URIs from preferences
            val storedUris = prefs.getStringSet(KEY_PROCESSED_URIS, emptySet()) ?: emptySet()
            storedUris.forEach { uriString ->
                processedUris[uriString] = System.currentTimeMillis()
            }
            Log.d(TAG, "Loaded ${storedUris.size} processed URIs from preferences")
        }
        // Set monitoring start time
        monitoringStartTime = System.currentTimeMillis()
        prefs.edit().putLong(KEY_MONITORING_START_TIME, monitoringStartTime).apply()
        Log.d(TAG, "Set monitoring start time to: $monitoringStartTime")
        // Load last scan time if available
        lastScanTime = prefs.getLong(KEY_LAST_SCAN_TIME, 0L)
        Log.d(TAG, "Loaded last scan time: $lastScanTime")
    }

    private fun setupContentObserver() {
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uri?.let {
                    scope.launch {
                        delay(CONTENT_OBSERVER_DELAY)
                        Log.d(TAG, "ContentObserver triggered with URI: $uri")
                        scanForNewImages()
                    }
                }
            }
        }
        // Register observer for all possible storage locations
        val urisToObserve = getAllImageUris()
        urisToObserve.forEach { uri ->
            try {
                contentResolver.registerContentObserver(
                    uri,
                    true,
                    contentObserver!!
                )
                Log.d(TAG, "Registered ContentObserver for URI: $uri")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register ContentObserver for URI: $uri", e)
            }
        }
        Log.d(TAG, "ContentObserver registered for ${urisToObserve.size} image URIs")
    }

    private fun getAllImageUris(): List<Uri> {
        val uris = mutableListOf<Uri>()
        // Standard locations
        uris.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        uris.add(MediaStore.Images.Media.INTERNAL_CONTENT_URI)
        // Check for additional storage volumes (API 21+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val volumes = contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    arrayOf(MediaStore.Files.FileColumns.VOLUME_NAME),
                    null, null, null
                )
                volumes?.use { cursor ->
                    val volumeNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.VOLUME_NAME)
                    while (cursor.moveToNext()) {
                        val volumeName = cursor.getString(volumeNameColumn)
                        if (volumeName != "internal" && volumeName != "external_primary") {
                            val volumeUri = MediaStore.Images.Media.getContentUri(volumeName)
                            uris.add(volumeUri)
                            Log.d(TAG, "Added volume URI: $volumeUri for volume: $volumeName")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying storage volumes: ${e.message}", e)
            }
        }
        return uris.distinct()
    }

    private fun startPeriodicScan() {
        scope.launch {
            while (true) {
                delay(SCAN_INTERVAL)
                scanForNewImages()
            }
        }
    }

    private suspend fun scanForNewImages() {
        try {
            val currentTime = System.currentTimeMillis()
            // Skip if we scanned recently (within 30 seconds)
            if (currentTime - lastScanTime < 30000) {
                return
            }
            lastScanTime = currentTime
            // Save last scan time
            getSharedPreferences(DAILY_STATS_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_SCAN_TIME, lastScanTime)
                .apply()
            Log.d(TAG, "Starting scan for new images")
            // Check for appropriate permission based on Android version
            val permissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            } else {
                ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
            if (!permissionGranted) {
                Log.e(TAG, "Required storage permission not granted")
                return
            }
            val prefs = getSharedPreferences(DAILY_STATS_PREFS, Context.MODE_PRIVATE)
            val monitoringStartTimeSec = monitoringStartTime / 1000
            var newImagesFound = false
            val allUris = getAllImageUris()
            Log.d(TAG, "Scanning ${allUris.size} URIs for new images")
            for (uri in allUris) {
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH
                )
                // Query for images added after monitoring started
                val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
                val selectionArgs = arrayOf(monitoringStartTimeSec.toString())
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"
                try {
                    val cursor = contentResolver.query(
                        uri,
                        projection,
                        selection,
                        selectionArgs,
                        sortOrder
                    )
                    cursor?.use {
                        val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val dateAddedColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                        val dateTakenColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                        val displayNameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                        val dataColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                        val bucketColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                        val pathColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                        val count = it.count
                        Log.d(TAG, "Found $count images in $uri")
                        while (it.moveToNext()) {
                            val id = it.getLong(idColumn)
                            val dateAdded = it.getLong(dateAddedColumn)
                            val dateTaken = it.getLong(dateTakenColumn)
                            val displayName = it.getString(displayNameColumn)
                            val dataPath = it.getString(dataColumn)
                            val bucketName = it.getString(bucketColumn)
                            val relativePath = it.getString(pathColumn)
                            val imageUri = ContentUris.withAppendedId(uri, id)
                            val uriString = imageUri.toString()
                            // Skip if already processed
                            if (processedUris.containsKey(uriString)) {
                                continue
                            }
                            Log.d(TAG, "Found new image: $displayName, URI: $imageUri, dateAdded: $dateAdded, dateTaken: $dateTaken, bucket: $bucketName, path: $relativePath")
                            // Mark as processed
                            processedUris[uriString] = currentTime
                            newImagesFound = true
                            // Increment daily count
                            incrementDailyImageCount()
                            // Save the last image URI to preferences
                            saveLastImageUri(imageUri)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error querying URI: $uri", e)
                }
            }
            if (newImagesFound) {
                // Save processed URIs to preferences
                saveProcessedUris()
                updateNotification()
                Log.d(TAG, "Scan completed. Found ${processedUris.size} new images")
            } else {
                Log.d(TAG, "Scan completed. No new images found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning for new images: ${e.message}", e)
        }
    }

    // NEW FUNCTION: Save last image URI to preferences
    private fun saveLastImageUri(uri: Uri) {
        try {
            Log.d(TAG, "🔍 DEBUG: Saving last image URI: $uri")
            val prefs = getSharedPreferences("last_image_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("last_image_uri", uri.toString()).apply()
            Log.d(TAG, "✅ DEBUG: Last image URI saved successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ DEBUG: Error saving last image URI: ${e.message}", e)
        }
    }

    private fun saveProcessedUris() {
        val prefs = getSharedPreferences(DAILY_STATS_PREFS, Context.MODE_PRIVATE)
        // Limit the number of URIs we save to avoid hitting SharedPreferences size limits
        val urisToSave = processedUris.keys.take(MAX_PROCESSED_URIS)
        prefs.edit()
            .putStringSet(KEY_PROCESSED_URIS, urisToSave.toSet())
            .apply()
        Log.d(TAG, "Saved ${urisToSave.size} processed URIs to preferences")
    }

    private fun incrementDailyImageCount() {
        val prefs = getSharedPreferences(DAILY_STATS_PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val storedDate = prefs.getString(KEY_COUNT_DATE, "")
        if (storedDate == today) {
            val currentCount = prefs.getInt(KEY_TODAY_COUNT, 0)
            prefs.edit().putInt(KEY_TODAY_COUNT, currentCount + 1).apply()
            Log.d(TAG, "Incremented daily image count for $today to ${currentCount + 1}")
        } else {
            // This should not happen if we reset at service start, but just in case
            prefs.edit()
                .putString(KEY_COUNT_DATE, today)
                .putInt(KEY_TODAY_COUNT, 1)
                .apply()
            Log.d(TAG, "Reset daily image count for $today to 1")
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun updateNotification() {
        val count = getTodayImageCount(this)
        val notification = createNotification("Monitoring gallery. Today: $count images")
        NotificationManagerCompat.from(this).notify(NOTIF_ID, notification)
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CamFlow Gallery Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Gallery Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        contentObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
        scope.cancel()
        saveProcessedUris()
        setMonitoringState(this, false)
        Log.d(TAG, "GalleryMonitorService destroyed")
    }

    data class AutomationSettings(
        val prompt: String,
        val destinationType: String,
        val destination: String,
        val attachImages: Boolean,
        val model: com.google.ai.edge.gallery.data.Model?
    )
}