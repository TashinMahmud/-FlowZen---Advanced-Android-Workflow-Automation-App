/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.ai.edge.gallery.ui.workflow.WorkflowExecutionReceiver

class MainActivity : ComponentActivity() {
  private lateinit var navController: NavHostController
  private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001

  // Define CamFlow constants here to avoid import issues
  companion object {
    const val ACTION_OPEN_HISTORY = "OPEN_CAMFLOW_HISTORY"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // Fix for three-button nav not properly going edge-to-edge.
      // See: https://issuetracker.google.com/issues/298296168
      window.isNavigationBarContrastEnforced = false
    }

    // Request notification permission for Android 13+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (ContextCompat.checkSelfPermission(
          this,
          Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
      ) {
        // Request the permission
        ActivityCompat.requestPermissions(
          this,
          arrayOf(Manifest.permission.POST_NOTIFICATIONS),
          NOTIFICATION_PERMISSION_REQUEST_CODE
        )
      }
    }

    setContent {
      GalleryTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          // Initialize the navController with the correct type
          navController = rememberNavController()
          GalleryApp(
            navController = navController,
            handleDeepLink = { deepLink ->
              handleDeepLink(deepLink)
            }
          )
        }
      }
    }

    // Keep the screen on while the app is running for better demo experience.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    // Handle workflow execution intent
    handleWorkflowExecutionIntent(intent)

    // Handle deep links when the app is opened from a link
    handleDeepLinkFromIntent(intent)

    // Handle CamFlow notification tap
    handleCamFlowNotification(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    // Set the new intent as the current intent
    setIntent(intent)
    handleWorkflowExecutionIntent(intent)
    // Handle deep links when the app is already running
    handleDeepLinkFromIntent(intent)
    // Handle CamFlow notification tap when app is already running
    handleCamFlowNotification(intent)
  }

  // Handle permission request result
  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
      if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        // Permission granted
        Log.d("MainActivity", "Notification permission granted")
      } else {
        // Permission denied
        Log.d("MainActivity", "Notification permission denied")
        // You might want to show a message to the user explaining why the permission is needed
      }
    }
  }

  private fun handleWorkflowExecutionIntent(intent: Intent) {
    if (intent.action == WorkflowExecutionReceiver.ACTION_EXECUTE_WORKFLOW) {
      val workflowId = intent.getStringExtra(WorkflowExecutionReceiver.EXTRA_WORKFLOW_ID)
      if (workflowId != null) {
        Log.d("MainActivity", "Received workflow execution intent for workflow: $workflowId")
        // The workflow execution will be handled by the WorkflowViewModel
        // when the app is launched or when the workflow screen is accessed
      }
    }
  }

  private fun handleDeepLinkFromIntent(intent: Intent) {
    val uri = intent.data
    if (uri != null) {
      Log.d("MainActivity", "Deep link received from intent: $uri")
      handleDeepLink(uri)
    }
  }

  private fun handleDeepLink(uri: Uri) {
    Log.d("MainActivity", "Processing deep link: $uri")

    if (uri.toString().startsWith("com.google.ai.edge.gallery://geofence/share")) {
      val sharedData = uri.getQueryParameter("data")
      if (sharedData != null) {
        Log.d("MainActivity", "Shared data found: $sharedData")

        // Navigate to the map screen with the shared data
        val encodedData = Uri.encode(sharedData)
        val deepLinkRoute = "maps?sharedData=$encodedData&autoActivate=true"

        Log.d("MainActivity", "Navigating to route: $deepLinkRoute")

        // Use a post action to ensure navigation happens after the navController is initialized
        try {
          navController.navigate(deepLinkRoute) {
            // Clear the back stack so the user can't go back to an empty screen
            popUpTo("maps") { inclusive = true }
          }
        } catch (e: Exception) {
          Log.e("MainActivity", "Error navigating to deep link: ${e.message}", e)
        }
      } else {
        Log.w("MainActivity", "No shared data found in deep link")
      }
    } else if (uri.toString().startsWith("com.google.ai.edge.gallery://camflow/history")) {
      // Handle CamFlow history deep link from notification
      Log.d("MainActivity", "CamFlow history deep link received")
      try {
        navController.navigate("cam_flow_history") {
          // Clear the back stack to home
          popUpTo("home") { inclusive = false }
        }
      } catch (e: Exception) {
        Log.e("MainActivity", "Error navigating to CamFlow history: ${e.message}", e)
      }
    } else {
      Log.w("MainActivity", "Unknown deep link format: $uri")
    }
  }

  private fun handleCamFlowNotification(intent: Intent) {
    if (intent.action == ACTION_OPEN_HISTORY) {
      Log.d("MainActivity", "CamFlow history notification tapped")
      try {
        navController.navigate("cam_flow_history") {
          // Clear the back stack to home
          popUpTo("home") { inclusive = false }
        }
      } catch (e: Exception) {
        Log.e("MainActivity", "Error navigating to CamFlow history: ${e.message}", e)
      }
    }
  }
}