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

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.google.ai.edge.gallery.auth.AuthViewModel
import com.google.ai.edge.gallery.ui.auth.LoginScreen
import com.google.ai.edge.gallery.ui.navigation.GalleryNavHost
import com.google.ai.edge.gallery.ui.ViewModelProvider

/** Top level composable representing the main screen of the application. */
@Composable
fun GalleryApp(
    navController: NavHostController = rememberNavController(),
    authViewModel: AuthViewModel = viewModel(factory = ViewModelProvider.Factory),
    handleDeepLink: (Uri) -> Unit = {}
) {
    val authState by authViewModel.authState.collectAsStateWithLifecycle()
    var pendingDeepLink by remember { mutableStateOf<Uri?>(null) }

    // Handle deep links after authentication
    DisposableEffect(authState.isLoggedIn) {
        if (authState.isLoggedIn && pendingDeepLink != null) {
            // User just logged in and we have a pending deep link
            pendingDeepLink?.let { deepLink ->
                Log.d("GalleryApp", "Processing pending deep link after login: $deepLink")
                handleDeepLink(deepLink)
                pendingDeepLink = null
            }
        }

        // Return a proper DisposableEffect result
        onDispose {
            // No cleanup needed
        }
    }

    if (authState.isLoggedIn) {
        // User is authenticated - show main app
        GalleryNavHost(
            navController = navController,
            authViewModel = authViewModel,
            onDeepLink = { deepLink ->
                if (authState.isLoggedIn) {
                    // User is already logged in, handle the deep link immediately
                    Log.d("GalleryApp", "Handling deep link immediately: $deepLink")
                    handleDeepLink(deepLink)
                } else {
                    // User is not logged in, save the deep link for after login
                    Log.d("GalleryApp", "Saving deep link for after login: $deepLink")
                    pendingDeepLink = deepLink
                }
            }
        )
    } else {
        // User is not authenticated - show login screen
        LoginScreen(
            onLoginSuccess = {
                // Login successful - the state will automatically update
                // and this composable will re-render to show the main app
            }
        )
    }
}