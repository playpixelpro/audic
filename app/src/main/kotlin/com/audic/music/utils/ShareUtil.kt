package com.audic.music.utils

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object ShareUtil {
    fun shareUrl(context: Context, scope: CoroutineScope, originalUrl: String) {
        scope.launch(Dispatchers.IO) {
            val url = ShortLinkManager.shorten(originalUrl)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            context.startActivity(Intent.createChooser(intent, null))
        }
    }

    fun shareText(context: Context, scope: CoroutineScope, text: String, url: String) {
        scope.launch(Dispatchers.IO) {
            val shortUrl = ShortLinkManager.shorten(url)
            val fullText = "$text\n$shortUrl"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, fullText)
            }
            context.startActivity(Intent.createChooser(intent, null))
        }
    }
}
