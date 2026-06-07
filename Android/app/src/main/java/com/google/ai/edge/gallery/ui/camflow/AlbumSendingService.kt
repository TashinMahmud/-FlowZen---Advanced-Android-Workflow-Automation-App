/*
 * Copyright 2025 Google LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 */
package com.google.ai.edge.gallery.ui.camflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.google.ai.edge.gallery.data.Model
import android.util.Log

class AlbumSendingService : Service() {
    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "album_sending_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "album_sending_prefs"
        private const val KEY_LAST_SEND_TIME = "last_send_time"
        private const val AUTOMATION_PREFS_NAME = "camflow_automation"
        private const val TAG = "AlbumSendingService"

        fun start(context: Context) {
            Log.d(TAG, "Starting AlbumSendingService")
            val intent = Intent(context, AlbumSendingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            Log.d(TAG, "Stopping AlbumSendingService")
            context.stopService(Intent(context, AlbumSendingService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var camManager: CamflowImageManager
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Creating AlbumSendingService")
        camManager = CamflowImageManager(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Image sending service running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        // This service is now simplified and doesn't do scheduled sending
        // It's kept for compatibility but can be removed if not needed
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "Creating notification channel")
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Image Sending Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("CamFlow Image Sender")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        Log.d(TAG, "Updating notification: $text")
        val notification = createNotification(text)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destroying AlbumSendingService")
        serviceScope.cancel()
        updateNotification("Image sending service stopped")
    }
}