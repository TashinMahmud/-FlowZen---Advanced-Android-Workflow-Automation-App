package com.google.ai.edge.gallery.ui.maps
import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.api.client.json.jackson2.JacksonFactory
object EmailSender {
    suspend fun sendTestEmail(toEmail: String, context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("EmailSender", "📧 Creating test email...")
// Get the current Google account
            val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)
            if (account == null) {
                Log.e("EmailSender", "❌ No Google account signed in")
                return@withContext false
            }
// Create Gmail API service
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
// Create email content
            val emailContent = """
From: ${account.email}
To: $toEmail
Subject: 🧪 Test Email from Geofence Map
Content-Type: text/plain; charset=UTF-8
This is a test email from your Geofence Map app.
If you receive this email, your email configuration is working correctly!
Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}
Best regards,
Geofence Map App
""".trimIndent()
// Encode email content
            val encodedEmail = android.util.Base64.encodeToString(
                emailContent.toByteArray(),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
// Create Gmail message
            val message = com.google.api.services.gmail.model.Message().setRaw(encodedEmail)
// Send email
            val sentMessage = gmailService.users().messages().send("me", message).execute()
            Log.d("EmailSender", "✅ Test email sent successfully: ${sentMessage.id}")
            true
        } catch (e: Exception) {
            Log.e("EmailSender", "❌ Failed to send test email: ${e.message}", e)
            false
        }
    }
}