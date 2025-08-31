package com.google.ai.edge.gallery.ui.navigation
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

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

class TelegramHelper(private val context: Context) {
    companion object {
        private const val TAG = "TelegramHelper"
        private const val TELEGRAM_API_BASE_URL = "https://api.telegram.org/bot"
        // Added your bot token here
        private const val DEFAULT_BOT_TOKEN = "8123513934:AAHybG4oY02mdAwcr8odWwjtD_X5eoOcpvA"
    }

    val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Send a message to a Telegram chat using bot token and chat ID
     */
    suspend fun sendMessage(
        botToken: String = DEFAULT_BOT_TOKEN, // Using your token by default
        chatId: String,
        message: String,
        preserveHtml: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Sending message to Telegram chat: $chatId")
            Log.d(TAG, "Message length: ${message.length} characters")

            val url = "$TELEGRAM_API_BASE_URL$botToken/sendMessage"

            val jsonBody = JSONObject().apply {
                put("chat_id", chatId)
                put("text", message)
                if (preserveHtml) {
                    put("parse_mode", "HTML") // Allow basic HTML formatting
                }
            }

            val requestBody = jsonBody.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val jsonResponse = JSONObject(responseBody)
                val success = jsonResponse.optBoolean("ok", false)
                if (success) {
                    Log.d(TAG, "Message sent successfully to Telegram")
                    return@withContext true
                } else {
                    val errorDescription = jsonResponse.optString("description", "Unknown error")
                    Log.e(TAG, "Telegram API error: $errorDescription")
                    Log.e(TAG, "Full response: $responseBody")

                    // Check for specific error types
                    when {
                        errorDescription.contains("message is too long") -> {
                            Log.e(TAG, "Message too long for Telegram API")
                        }
                        errorDescription.contains("parse_mode") -> {
                            Log.e(TAG, "HTML parsing error in message")
                            // Try again without HTML parsing
                            return@withContext sendMessage(botToken, chatId, message, false)
                        }
                        errorDescription.contains("chat not found") -> {
                            Log.e(TAG, "Chat ID not found or bot not added to chat")
                        }
                        else -> {
                            Log.e(TAG, "Unknown Telegram API error")
                        }
                    }
                    return@withContext false
                }
            } else {
                Log.e(TAG, "HTTP error: ${response.code} - ${responseBody}")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message to Telegram: ${e.message}", e)
            return@withContext false
        }
    }

    /**
     * Send a large message by splitting it into multiple parts if needed
     */
    suspend fun sendLargeMessage(
        botToken: String = DEFAULT_BOT_TOKEN, // Using your token by default
        chatId: String,
        message: String,
        maxLength: Int = 4000,
        preserveHtml: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val processedMessage = if (preserveHtml) {
            // Keep HTML tags but escape other special characters
            message
                .replace("&", "&amp;") // Must be first to avoid double escaping
                .replace(Regex("<(?!/?[biu])", RegexOption.IGNORE_CASE), "&lt;") // Escape non-formatting HTML tags
                .replace(Regex("(?<![biu]/?)>", RegexOption.IGNORE_CASE), "&gt;") // Escape non-formatting HTML tags
        } else {
            // Remove all HTML tags and escape HTML entities
            val messageWithoutTags = message
                .replace("<b>", "")
                .replace("</b>", "")
                .replace("<i>", "")
                .replace("</i>", "")
                .replace("<u>", "")
                .replace("</u>", "")
                .replace("<code>", "")
                .replace("</code>", "")
                .replace("<pre>", "")
                .replace("</pre>", "")

            messageWithoutTags
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
        }

        Log.d(TAG, "Original message length: ${message.length}")
        Log.d(TAG, "Processed message length: ${processedMessage.length}")

        return@withContext sendLargeMessageInternal(botToken, chatId, processedMessage, maxLength, preserveHtml)
    }

    private suspend fun sendLargeMessageInternal(
        botToken: String,
        chatId: String,
        message: String,
        maxLength: Int = 4000,
        preserveHtml: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Processing large message: ${message.length} characters (max: $maxLength)")
            Log.d(TAG, "Message preview: ${message.take(100)}...")

            if (message.length <= maxLength) {
                // Message is small enough, send as single message
                Log.d(TAG, "Message fits in single part, sending directly")
                return@withContext sendMessage(botToken, chatId, message, preserveHtml)
            }

            // Split message into parts
            Log.d(TAG, "Splitting message into parts...")
            val parts = mutableListOf<String>()
            var currentPart = ""
            val lines = message.split("\n")
            Log.d(TAG, "Total lines to process: ${lines.size}")

            for ((index, line) in lines.withIndex()) {
                val testPart = if (currentPart.isEmpty()) line else "$currentPart\n$line"
                if (testPart.length <= maxLength) {
                    currentPart = testPart
                } else {
                    if (currentPart.isNotEmpty()) {
                        Log.d(TAG, "Adding part ${parts.size + 1} with ${currentPart.length} characters")
                        parts.add(currentPart)
                        currentPart = line
                    } else {
                        // Single line is too long, split it
                        Log.d(TAG, "Line $index is too long (${line.length} chars), chunking...")
                        val chunks = line.chunked(maxLength)
                        Log.d(TAG, "Split into ${chunks.size} chunks")
                        parts.addAll(chunks)
                    }
                }
            }

            if (currentPart.isNotEmpty()) {
                Log.d(TAG, "Adding final part with ${currentPart.length} characters")
                parts.add(currentPart)
            }

            // Send all parts
            Log.d(TAG, "Splitting message into ${parts.size} parts")
            var allSuccess = true

            for ((index, part) in parts.withIndex()) {
                val partNumber = if (parts.size > 1) " (Part ${index + 1}/${parts.size})" else ""
                val messageWithPart = if (parts.size > 1) "$part$partNumber" else part
                Log.d(TAG, "Sending part ${index + 1}/${parts.size} (${messageWithPart.length} characters)")

                // Try to send the part, with fallback for very large parts
                var success = sendMessage(botToken, chatId, messageWithPart, preserveHtml)
                if (!success && messageWithPart.length > 3000) {
                    Log.d(TAG, "Part too large, trying with truncated version...")
                    val truncatedPart = messageWithPart.take(3000) + "\n\n[Content truncated due to size limit]"
                    success = sendMessage(botToken, chatId, truncatedPart, preserveHtml)
                }

                if (!success) {
                    allSuccess = false
                    Log.e(TAG, "Failed to send part ${index + 1} of ${parts.size}")
                } else {
                    Log.d(TAG, "Successfully sent part ${index + 1}/${parts.size}")
                }

                // Small delay between messages to avoid rate limiting
                if (index < parts.size - 1) {
                    kotlinx.coroutines.delay(100)
                }
            }

            return@withContext allSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Error sending large message to Telegram: ${e.message}", e)
            // Fallback: try sending a simplified version
            Log.d(TAG, "Trying fallback with simplified message...")
            val simplifiedMessage = "📧 Large email received\n\nSubject: [Email subject]\n\n[Content too large to display completely]"
            return@withContext sendMessage(botToken, chatId, simplifiedMessage, false)
        }
    }

    /**
     * Send email summary to Telegram
     */
    suspend fun sendEmailSummary(
        botToken: String = DEFAULT_BOT_TOKEN, // Using your token by default
        chatId: String,
        summary: String,
        emailSubject: String = "Email Summary"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedMessage = """
                📧 <b>$emailSubject</b>
                
                $summary
                
                <i>Sent via Gmail Forwarder App</i>
            """.trimIndent()

            return@withContext sendMessage(botToken, chatId, formattedMessage, true)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending email summary to Telegram: ${e.message}", e)
            return@withContext false
        }
    }

    /**
     * Send batch email summaries to Telegram
     */
    suspend fun sendBatchEmailSummaries(
        botToken: String = DEFAULT_BOT_TOKEN, // Using your token by default
        chatId: String,
        summaries: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedMessage = """
                📧 <b>Batch Email Summaries</b>
                
                ${summaries.joinToString("\n\n---\n\n")}
                
                <i>Total: ${summaries.size} emails processed</i>
                <i>Sent via Gmail Forwarder App</i>
            """.trimIndent()

            return@withContext sendLargeMessage(botToken, chatId, formattedMessage, 4000, true)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending batch summaries to Telegram: ${e.message}", e)
            return@withContext false
        }
    }

    /**
     * Test if bot token is valid (without sending message)
     */
    suspend fun testBotToken(botToken: String = DEFAULT_BOT_TOKEN): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$TELEGRAM_API_BASE_URL$botToken/getMe"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val jsonResponse = JSONObject(responseBody)
                val success = jsonResponse.optBoolean("ok", false)
                if (success) {
                    return@withContext true
                } else {
                    val errorDescription = jsonResponse.optString("description", "Unknown error")
                    Log.e(TAG, "Bot token is invalid: $errorDescription")
                    return@withContext false
                }
            } else {
                Log.e(TAG, "HTTP error testing bot: ${response.code}")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error testing bot token: ${e.message}", e)
            return@withContext false
        }
    }

    /**
     * Validate bot token and chat ID
     */
    suspend fun validateCredentials(
        botToken: String = DEFAULT_BOT_TOKEN, // Using your token by default
        chatId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$TELEGRAM_API_BASE_URL$botToken/getMe"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val jsonResponse = JSONObject(responseBody)
                val success = jsonResponse.optBoolean("ok", false)
                if (success) {
                    // Test sending a message to validate chat ID
                    val testMessage = "Test message from Gmail Forwarder App"
                    return@withContext sendMessage(botToken, chatId, testMessage, false)
                } else {
                    return@withContext false
                }
            } else {
                Log.e(TAG, "HTTP error during validation: ${response.code}")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating Telegram credentials: ${e.message}", e)
            return@withContext false
        }
    }
}