package com.google.ai.edge.gallery.ui.maps

import android.content.Context
import android.content.Intent

object TelegramDeepLinkHelperGeo {
    fun shareText(context: Context, text: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(sendIntent, "Share Telegram Invite")
        context.startActivity(chooser)
    }
}
