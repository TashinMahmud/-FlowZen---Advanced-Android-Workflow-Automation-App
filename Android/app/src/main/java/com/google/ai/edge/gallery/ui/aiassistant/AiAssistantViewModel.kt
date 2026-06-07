package com.google.ai.edge.gallery.ui.aiassistant

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TASK_AI_ASSISTANT
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.aiassistant.AiAssistantFunctions
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class AiAssistantViewModel : LlmChatViewModel(curTask = TASK_AI_ASSISTANT) {
    private var aiAssistantFunctions: AiAssistantFunctions? = null

    init {
        Log.d("AiAssistantViewModel", "AiAssistantViewModel initialized with task: ${TASK_AI_ASSISTANT.type}")
    }

    fun initializeAiAssistantFunctions(context: Context) {
        Log.d("AiAssistantViewModel", "Initializing AiAssistantFunctions")
        try {
            aiAssistantFunctions = AiAssistantFunctions(context)
            Log.d("AiAssistantViewModel", "AiAssistantFunctions initialized successfully")
        } catch (e: Exception) {
            Log.e("AiAssistantViewModel", "Error initializing AiAssistantFunctions", e)
        }
    }

    fun processAiAssistantInput(
        model: Model,
        input: String,
        images: List<Bitmap> = emptyList(),
        audioMessages: List<ChatMessageAudioClip> = emptyList(),
        onError: () -> Unit,
    ) {
        Log.d("AiAssistantViewModel", "🔍 DEBUG: processAiAssistantInput called")
        Log.d("AiAssistantViewModel", "🔍 DEBUG: Input: '$input'")
        Log.d("AiAssistantViewModel", "🔍 DEBUG: Model: ${model.name}")
        Log.d("AiAssistantViewModel", "🔍 DEBUG: Model instance null: ${model.instance == null}")
        Log.d("AiAssistantViewModel", "🔍 DEBUG: aiAssistantFunctions is null: ${aiAssistantFunctions == null}")

        try {
            // Check if AiAssistantFunctions is initialized
            if (aiAssistantFunctions == null) {
                Log.e("AiAssistantViewModel", "❌ ERROR: AiAssistantFunctions is null! Initializing now...")
                addMessage(
                    model = model,
                    message = ChatMessageText(
                        content = "⚠️ AI Assistant Functions not initialized. Please try again.",
                        side = ChatSide.AGENT,
                    ),
                )
                return
            }

            // FIRST: Check if we're in the middle of workflow creation or have an active intent
            Log.d("AiAssistantViewModel", "🔍 DEBUG: Checking for workflow continuation...")
            val workflowResponse = aiAssistantFunctions?.processUserInputWithIntent(input, "workflow_continue")
            Log.d("AiAssistantViewModel", "🔍 DEBUG: Workflow response: $workflowResponse")
            if (workflowResponse != null) {
                Log.d("AiAssistantViewModel", "✅ DEBUG: Workflow conversation active, continuing workflow")
                addMessage(
                    model = model,
                    message = ChatMessageText(
                        content = workflowResponse,
                        side = ChatSide.AGENT,
                    ),
                )
                return
            }
            Log.d("AiAssistantViewModel", "🔍 DEBUG: No active workflow, proceeding to intent analysis")

            // SECOND: Only do intent analysis if we're not in an active workflow
            Log.d("AiAssistantViewModel", "🔍 DEBUG: Starting intent analysis...")
            val intentAnalysis = analyzeIntentWithLLM(model, input)
            Log.d("AiAssistantViewModel", "🔍 DEBUG: Intent analysis result: $intentAnalysis")

            // Only use workflow functions for very specific, clear intents
            // For general chat, questions, casual conversation, use normal LLM
            val shouldUseWorkflowFunctions = when (intentAnalysis) {
                "email_summarize" -> {
                    // More flexible email summarization detection
                    val lowerInput = input.lowercase()
                    lowerInput.contains("summarize") && lowerInput.contains("email") ||
                            lowerInput.contains("send") && lowerInput.contains("email") && lowerInput.contains("summar") ||
                            lowerInput.contains("email") && (lowerInput.contains("digest") || lowerInput.contains("summary") || lowerInput.contains("summar")) ||
                            lowerInput.contains("create") && lowerInput.contains("email") && lowerInput.contains("workflow") ||
                            lowerInput.contains("email") && lowerInput.contains("summaries")
                }
                "set_alarm" -> {
                    // Only trigger for clear alarm requests
                    val lowerInput = input.lowercase()
                    lowerInput.contains("alarm") ||
                            (lowerInput.contains("wake") && lowerInput.contains("up")) ||
                            (lowerInput.contains("set") && lowerInput.contains("alarm"))
                }
                "set_reminder" -> {
                    // Only trigger for clear reminder requests
                    val lowerInput = input.lowercase()
                    lowerInput.contains("remind") ||
                            (lowerInput.contains("set") && lowerInput.contains("reminder")) ||
                            (lowerInput.contains("notify") && lowerInput.contains("me"))
                }

                "camflow" -> {
                    // More flexible CamFlow detection
                    val lowerInput = input.lowercase()
                    lowerInput.contains("camera") ||
                            lowerInput.contains("image") ||
                            lowerInput.contains("photo") ||
                            lowerInput.contains("camflow") ||
                            (lowerInput.contains("open") && lowerInput.contains("camflow")) ||
                            (lowerInput.contains("create") && lowerInput.contains("camflow")) ||
                            (lowerInput.contains("start") && lowerInput.contains("camflow")) ||
                            (lowerInput.contains("use") && lowerInput.contains("camera")) ||
                            (lowerInput.contains("camera") && lowerInput.contains("analysis")) ||
                            (lowerInput.contains("image") && lowerInput.contains("analysis")) ||
                            (lowerInput.contains("photo") && lowerInput.contains("analysis"))
                }
                "geofencing" -> {
                    // More flexible geofencing detection
                    val lowerInput = input.lowercase()
                    lowerInput.contains("location") ||
                            lowerInput.contains("gps") ||
                            lowerInput.contains("geofence") ||
                            lowerInput.contains("geofencing") ||
                            (lowerInput.contains("when") && lowerInput.contains("at")) ||
                            (lowerInput.contains("open") && lowerInput.contains("geofencing")) ||
                            (lowerInput.contains("set") && lowerInput.contains("up") && lowerInput.contains("geofence")) ||
                            (lowerInput.contains("create") && lowerInput.contains("geofence"))
                }
                else -> false
            }

            Log.d("AiAssistantViewModel", "🔍 DEBUG: shouldUseWorkflowFunctions: $shouldUseWorkflowFunctions")
            if (shouldUseWorkflowFunctions) {
                Log.d("AiAssistantViewModel", "✅ DEBUG: Clear workflow intent detected, using AiAssistantFunctions")
                val functionResponse = aiAssistantFunctions?.processUserInputWithIntent(input, intentAnalysis)
                Log.d("AiAssistantViewModel", "🔍 DEBUG: Function response: $functionResponse")

                if (functionResponse != null) {
                    Log.d("AiAssistantViewModel", "✅ DEBUG: Adding function response to chat")
                    addMessage(
                        model = model,
                        message = ChatMessageText(
                            content = functionResponse,
                            side = ChatSide.AGENT,
                        ),
                    )
                    return
                } else {
                    Log.d("AiAssistantViewModel", "⚠️ DEBUG: Function response was null, falling back to LLM")
                }
            } else {
                Log.d("AiAssistantViewModel", "🔍 DEBUG: No clear workflow intent, using normal LLM chat")
            }

            // Use normal LLM chat for general conversation, questions, or unclear requests
            Log.d("AiAssistantViewModel", "🔍 DEBUG: Using normal AI response (generateResponse)")
            super.generateResponse(model, input, images, audioMessages, onError)
        } catch (e: Exception) {
            Log.e("AiAssistantViewModel", "❌ EXCEPTION: Error in processAiAssistantInput", e)
            // If there's an error, still try to use normal LLM chat as fallback
            Log.d("AiAssistantViewModel", "🔍 DEBUG: Falling back to normal LLM chat due to error")
            super.generateResponse(model, input, images, audioMessages, onError)
        }
    }

    private fun analyzeIntentWithLLM(model: Model, input: String): String {
        // Use reliable semantic analysis for intent detection
        Log.d("AiAssistantViewModel", "Analyzing intent for: '$input'")

        val semanticIntent = fallbackIntentAnalysis(input)
        Log.d("AiAssistantViewModel", "Semantic analysis result: $semanticIntent")

        // For general chat cases, don't try LLM to avoid timeouts
        // Just return general_chat and let normal LLM handle it
        if (semanticIntent == "general_chat") {
            Log.d("AiAssistantViewModel", "General chat detected, skipping LLM intent analysis")
            return "general_chat"
        }

        // Only use LLM for complex cases where semantic analysis might be ambiguous
        // For now, just use semantic analysis to avoid timeouts
        Log.d("AiAssistantViewModel", "Using semantic analysis result: $semanticIntent")
        return semanticIntent
    }

    private fun fallbackIntentAnalysis(input: String): String {
        // Conservative semantic analysis - only detect very clear workflow intents
        val lowerInput = input.lowercase()

        Log.d("AiAssistantViewModel", "🔍 DEBUG: Analyzing input: '$input'")
        Log.d("AiAssistantViewModel", "🔍 DEBUG: Lowercase: '$lowerInput'")

        return when {
            // Email summarization - more flexible patterns
            (lowerInput.contains("summarize") && lowerInput.contains("email")) ||
                    (lowerInput.contains("send") && lowerInput.contains("email") && lowerInput.contains("summar")) ||
                    (lowerInput.contains("email") && lowerInput.contains("summar")) ||
                    (lowerInput.contains("create") && lowerInput.contains("email") && lowerInput.contains("workflow")) ||
                    (lowerInput.contains("email") && lowerInput.contains("digest") && lowerInput.contains("workflow")) ||
                    (lowerInput.contains("email") && lowerInput.contains("summary") && lowerInput.contains("to")) ||
                    (lowerInput.contains("summarize") && lowerInput.contains("emails")) ||
                    (lowerInput.contains("email") && lowerInput.contains("summaries")) -> {
                Log.d("AiAssistantViewModel", "Fallback: detected clear email summarization intent")
                "email_summarize"
            }

            // Alarm - only for very clear alarm requests
            (lowerInput.contains("set") && lowerInput.contains("alarm")) ||
                    (lowerInput.contains("wake") && lowerInput.contains("up") && lowerInput.contains("alarm")) ||
                    (lowerInput.contains("create") && lowerInput.contains("alarm")) -> {
                Log.d("AiAssistantViewModel", "Fallback: detected clear alarm intent")
                "set_alarm"
            }

            // Reminder - only for very clear reminder requests
            (lowerInput.contains("set") && lowerInput.contains("reminder")) ||
                    (lowerInput.contains("create") && lowerInput.contains("reminder")) ||
                    (lowerInput.contains("remind") && lowerInput.contains("me") && lowerInput.contains("to")) -> {
                Log.d("AiAssistantViewModel", "Fallback: detected clear reminder intent")
                "set_reminder"
            }



            // CamFlow - more flexible patterns for camera-based requests
            (lowerInput.contains("open") && lowerInput.contains("camflow")) ||
                    (lowerInput.contains("create") && lowerInput.contains("camflow")) ||
                    (lowerInput.contains("start") && lowerInput.contains("camflow")) ||
                    (lowerInput.contains("use") && lowerInput.contains("camera")) ||
                    (lowerInput.contains("camera") && lowerInput.contains("flow")) ||
                    (lowerInput.contains("camflow")) ||
                    (lowerInput.contains("camera") && lowerInput.contains("analysis")) ||
                    (lowerInput.contains("image") && lowerInput.contains("analysis")) ||
                    (lowerInput.contains("photo") && lowerInput.contains("analysis")) -> {
                Log.d("AiAssistantViewModel", "Fallback: detected clear CamFlow intent")
                "camflow"
            }

            // Geofencing - more flexible patterns for location-based requests
            (lowerInput.contains("create") && lowerInput.contains("location") && lowerInput.contains("reminder")) ||
                    (lowerInput.contains("geofence") && lowerInput.contains("reminder")) ||
                    (lowerInput.contains("when") && lowerInput.contains("at") && lowerInput.contains("location")) ||
                    (lowerInput.contains("open") && lowerInput.contains("geofencing")) ||
                    (lowerInput.contains("set") && lowerInput.contains("up") && lowerInput.contains("geofence")) ||
                    (lowerInput.contains("create") && lowerInput.contains("geofence")) ||
                    (lowerInput.contains("location") && lowerInput.contains("reminder")) ||
                    (lowerInput.contains("geofencing")) ||
                    (lowerInput.contains("geofence")) -> {
                Log.d("AiAssistantViewModel", "Fallback: detected clear geofencing intent")
                "geofencing"
            }

            else -> {
                Log.d("AiAssistantViewModel", "Fallback: detected general chat intent")
                "general_chat"
            }
        }
    }

    fun resetAiAssistantState() {
        Log.d("AiAssistantViewModel", "Resetting AI Assistant state")
        aiAssistantFunctions?.resetIntentState()
    }

    override fun onCleared() {
        Log.d("AiAssistantViewModel", "AiAssistantViewModel being cleared")
        super.onCleared()
        aiAssistantFunctions = null
    }
}