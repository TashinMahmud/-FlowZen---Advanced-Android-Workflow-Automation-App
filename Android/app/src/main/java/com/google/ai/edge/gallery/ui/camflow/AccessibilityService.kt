/*
 * Copyright 2025 Google LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 */
package com.google.ai.edge.gallery.ui.camflow

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import android.os.Handler
import android.os.Looper

class SamsungGalleryAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GalleryAccessibility"
        private const val GALLERY_PACKAGE = "com.sec.android.gallery3d"
        private val TARGET_CONTENT_DESCRIPTIONS = listOf(
            "Create album", "Create", "New album", "+", "Add", "Create new"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName != GALLERY_PACKAGE) return

        Log.d(TAG, "Samsung Gallery window state changed")

        // Wait a moment for the UI to fully render
        rootInActiveWindow?.let { rootNode ->
            Handler(Looper.getMainLooper()).postDelayed({
                findAndClickCreateButton(rootNode)
            }, 500)
        }
    }

    private fun findAndClickCreateButton(node: AccessibilityNodeInfo) {
        // First try to find by content description
        TARGET_CONTENT_DESCRIPTIONS.forEach { desc ->
            val nodes = node.findAccessibilityNodeInfosByText(desc)
            if (nodes.isNotEmpty()) {
                val targetNode = nodes.firstOrNull {
                    it.contentDescription?.toString()?.equals(desc, ignoreCase = true) == true ||
                            it.text?.toString()?.equals(desc, ignoreCase = true) == true
                }
                if (targetNode != null && targetNode.isClickable) {
                    Log.d(TAG, "Found target node by content description: $desc")
                    performClick(targetNode)
                    return
                }
            }
        }

        // Fallback: Search by view ID resource name
        val nodesByViewId = node.findAccessibilityNodeInfosByViewId("$GALLERY_PACKAGE:id/create_button")
        if (nodesByViewId.isNotEmpty()) {
            Log.d(TAG, "Found target node by view ID")
            performClick(nodesByViewId[0])
            return
        }

        // Another fallback: Look for any node with "+" in content description
        val allNodes = ArrayList<AccessibilityNodeInfo>()
        node.getChildNodes(allNodes)

        for (child in allNodes) {
            val contentDesc = child.contentDescription?.toString() ?: ""
            if (contentDesc.contains("+") || contentDesc.contains("create", ignoreCase = true)) {
                if (child.isClickable) {
                    Log.d(TAG, "Found target node by content description pattern: $contentDesc")
                    performClick(child)
                    return
                }
            }
        }

        Log.w(TAG, "Could not find the create album button")
        Toast.makeText(this, "Please tap the + button to create album", Toast.LENGTH_SHORT).show()
    }

    private fun performClick(node: AccessibilityNodeInfo) {
        try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "Successfully clicked the create album button")
                Toast.makeText(this, "Album creation started", Toast.LENGTH_SHORT).show()
            } else {
                Log.w(TAG, "Failed to click the create album button")
                Toast.makeText(this, "Please tap the + button to create album", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking create album button", e)
            Toast.makeText(this, "Please tap the + button to create album", Toast.LENGTH_SHORT).show()
        }
    }

    private fun AccessibilityNodeInfo.getChildNodes(result: MutableList<AccessibilityNodeInfo>) {
        for (i in 0 until childCount) {
            getChild(i)?.let { child ->
                result.add(child)
                child.getChildNodes(result)
            }
        }
    }

    override fun onInterrupt() {
        // Not used
    }
}