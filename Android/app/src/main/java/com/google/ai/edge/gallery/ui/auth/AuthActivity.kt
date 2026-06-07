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

package com.google.ai.edge.gallery.ui.auth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.auth.AuthViewModel
import com.google.ai.edge.gallery.ui.ViewModelProvider
import com.google.ai.edge.gallery.ui.theme.GalleryTheme

/**
 * Example activity demonstrating how to integrate the authentication system.
 * This shows how to handle login state and navigate between authenticated and unauthenticated states.
 */
class AuthActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels { ViewModelProvider.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GalleryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AuthApp(authViewModel = authViewModel)
                }
            }
        }
    }
}

/**
 * Main app composable that handles authentication state.
 */
@Composable
fun AuthApp(authViewModel: AuthViewModel) {
    val authState by authViewModel.authState.collectAsStateWithLifecycle()

    if (authState.isLoggedIn) {
        // User is authenticated - show main app content
        AuthenticatedContent(
            user = authState.user,
            onLogout = { authViewModel.signOut() }
        )
    } else {
        // User is not authenticated - show login screen
        LoginScreen(
            onLoginSuccess = {
                // Login successful - the state will automatically update
                // and this composable will re-render to show authenticated content
            }
        )
    }
}

/**
 * Example authenticated content screen.
 */
@Composable
fun AuthenticatedContent(
    user: com.google.firebase.auth.FirebaseUser?,
    onLogout: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Top bar with logout button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Welcome to FlowZen",
                style = MaterialTheme.typography.headlineSmall
            )
            TextButton(onClick = onLogout) {
                Text("Logout")
            }
        }
        
        // Main content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .padding(top = 80.dp), // Add top padding to avoid overlap with top bar
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Authentication Successful!",
                style = MaterialTheme.typography.headlineMedium
            )
            
            Spacer(
                modifier = Modifier.height(16.dp)
            )
            
            user?.email?.let { email ->
                Text(
                    text = "Logged in as: $email",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            
            Spacer(
                modifier = Modifier.height(8.dp)
            )
            
            user?.uid?.let { uid ->
                Text(
                    text = "User ID: ${uid.take(20)}...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(
                modifier = Modifier.height(32.dp)
            )
            
            Text(
                text = "You can now access protected resources and features.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
} 