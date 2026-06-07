package com.google.ai.edge.gallery.ui.maps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController

object DeepLinkHandler {
    fun handleDeepLink(context: Context, intent: Intent, navController: NavController): Boolean {
        val data = intent.data
        if (data != null && data.scheme == "flowzen" && data.host == "geofence") {
            val path = data.path
            if (path == "/share") {
                val sharedData = data.getQueryParameter("data")
                if (sharedData != null) {
                    // Navigate to GeofenceMapScreen with shared data and auto-activate
                    navController.navigate("maps?sharedData=${Uri.encode(sharedData)}&autoActivate=true")
                    return true
                }
            }
        }
        return false
    }
}