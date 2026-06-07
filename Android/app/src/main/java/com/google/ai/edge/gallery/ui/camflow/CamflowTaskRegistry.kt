package com.google.ai.edge.gallery.ui.camflow

import android.net.Uri
import com.google.ai.edge.gallery.data.Model
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory handoff between UI and the ForegroundService.
 * This avoids serializing Model/Bitmap/etc. We register a task snapshot,
 * then the service pulls it immediately and runs it.
 */
object CamflowTaskRegistry {
    data class TaskSpec(
        val model: Model?,
        val imageUris: List<Uri>,
        val prompt: String,
        val destinationType: String,
        val destination: String,
        val attachImages: Boolean
    )

    private val tasks = ConcurrentHashMap<String, TaskSpec>()

    fun register(spec: TaskSpec): String {
        val id = UUID.randomUUID().toString()
        tasks[id] = spec
        return id
    }

    fun get(id: String): TaskSpec? = tasks[id]
    
    fun take(id: String): TaskSpec? = tasks.remove(id)
}
