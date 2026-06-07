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
package com.google.ai.edge.gallery.data
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.CameraEnhance
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Mms
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.Map  // Added for Maps task
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoCameraBack
import androidx.compose.material.icons.outlined.PhotoCameraFront
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import com.google.ai.edge.gallery.R
/** Type of task. */
enum class TaskType(val label: String, val id: String) {
  LLM_CHAT(label = "Chat with an Ai", id = "llm_chat"),
  LLM_PROMPT_LAB(label = "", id = "llm_prompt_lab"),
  LLM_ASK_IMAGE(label = "Text & Image AI", id = "llm_ask_image"),
  LLM_ASK_AUDIO(label = "", id = "llm_ask_audio"),
  TEST_TASK_1(label = "Test task 1", id = "test_task_1"),
  TEST_TASK_2(label = "Test task 2", id = "test_task_2"),
  CREATE_WORKFLOW(label = "Create Workflow", id = "create_workflow"),
  MAPS(label = "Maps", id = "maps"),  // Added Maps task type
  CAMFLOW(label = "CamFlow", id = "cam_flow"),  // Added CamFlow task type
  AI_ASSISTANT(label = "AI Assistant", id = "ai_assistant"),  // Added AI Assistant task type
  GMAIL_FORWARD(label = "Gmail Forwarder", id = "gmail_forward"),  // Added Gmail Forwarder task type
}
/** Data class for a task listed in home screen. */
data class Task(
  /** Type of the task. */
  val type: TaskType,
  /** Icon to be shown in the task tile. */
  val icon: ImageVector? = null,
  /** Vector resource id for the icon. This precedes the icon if both are set. */
  val iconVectorResourceId: Int? = null,
  /** List of models for the task. */
  val models: MutableList<Model>,
  /** Description of the task. */
  val description: String,
  /** Documentation url for the task. */
  val docUrl: String = "",
  /** Source code url for the model-related functions. */
  val sourceCodeUrl: String = "",
  /** Placeholder text for the name of the agent shown above chat messages. */
  @StringRes val agentNameRes: Int = R.string.chat_generic_agent_name,
  /** Placeholder text for the text input field. */
  @StringRes val textInputPlaceHolderRes: Int = R.string.chat_textinput_placeholder,
  // The following fields are managed by the app. Don't need to set manually.
  var index: Int = -1,
  val updateTrigger: MutableState<Long> = mutableLongStateOf(0),
)
val TASK_LLM_CHAT =
  Task(
    type = TaskType.LLM_CHAT,
    icon = Icons.Outlined.Forum,
    models = mutableListOf(),
    description = "Chat with a on-device offline AI",
    textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
  )
val TASK_LLM_PROMPT_LAB =
  Task(
    type = TaskType.LLM_PROMPT_LAB,
    icon = Icons.Outlined.Widgets,
    models = mutableListOf(),
    description = "",
    textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
  )
val TASK_LLM_ASK_IMAGE =
  Task(
    type = TaskType.LLM_ASK_IMAGE,
    icon = Icons.Outlined.Mms,
    models = mutableListOf(),
    description = "Ask questions about images",
    textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
  )
val TASK_LLM_ASK_AUDIO =
  Task(
    type = TaskType.LLM_ASK_AUDIO,
    icon = Icons.Outlined.Mic,
    models = mutableListOf(),
    description = "",
    textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
  )
val TASK_CREATE_WORKFLOW =
  Task(
    type = TaskType.CREATE_WORKFLOW,
    icon = Icons.Outlined.RocketLaunch,
    models = mutableListOf(),
    description = "Build automated workflows using AI agents",
    textInputPlaceHolderRes = R.string.chat_textinput_placeholder,
  )
// New Maps task
val TASK_MAPS =
  Task(
    type = TaskType.MAPS,
    icon = Icons.Outlined.Map,
    models = mutableListOf(),
    description = "Explore locations and Geofencing with maps",
    textInputPlaceHolderRes = R.string.chat_textinput_placeholder,
  )
// New CamFlow task
val TASK_CAMFLOW =
  Task(
    type = TaskType.CAMFLOW,
    icon = Icons.Outlined.CameraEnhance,
    models = mutableListOf(), // CamFlow uses models from other tasks, but we'll add some for display
    description = "Camera workflow automation",
    textInputPlaceHolderRes = R.string.chat_textinput_placeholder,
  )
// AI Assistant task
val TASK_AI_ASSISTANT =
  Task(
    type = TaskType.AI_ASSISTANT,
    icon = Icons.Outlined.AutoAwesome,
    models = mutableListOf(), // AI Assistant will use models from other tasks
    description = "AI Assistant for workflow creation and automation",
    textInputPlaceHolderRes = R.string.chat_textinput_placeholder,
  )
// Gmail Forwarder task
val TASK_GMAIL_FORWARD =
  Task(
    type = TaskType.GMAIL_FORWARD,
    icon = Icons.Outlined.Email,
    models = mutableListOf(),
    description = "Read and forward your Gmail messages",
    textInputPlaceHolderRes = R.string.chat_textinput_placeholder,
  )
/** All tasks. */
val TASKS: List<Task> =
  listOf(
    TASK_LLM_ASK_IMAGE,
    TASK_LLM_CHAT,
    TASK_CREATE_WORKFLOW,
    TASK_MAPS,  // Added Maps task to the list
    TASK_CAMFLOW,  // Added CamFlow task to the list
    TASK_AI_ASSISTANT,  // Added AI Assistant task to the list
    // Added Gmail Forwarder task to the list
  )
fun getModelByName(name: String): Model? {
  for (task in TASKS) {
    for (model in task.models) {
      if (model.name == name) {
        return model
      }
    }
  }
  return null
}
fun processTasks() {
  for ((index, task) in TASKS.withIndex()) {
    task.index = index
    for (model in task.models) {
      model.preProcess()
    }
  }
}