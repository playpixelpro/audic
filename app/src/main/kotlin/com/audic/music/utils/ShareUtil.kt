package com.audic.music.utils

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ShareUtil {
    fun shareUrl(context: Context, scope: CoroutineScope, originalUrl: String) {
        scope.launch(Dispatchers.IO) {
            val url = ShortLinkManager.shorten(originalUrl)
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, null))
            }
        }
    }

    fun shareText(context: Context, scope: CoroutineScope, text: String, url: String) {
        scope.launch(Dispatchers.IO) {
            val shortUrl = ShortLinkManager.shorten(url)
            withContext(Dispatchers.Main) {
                val fullText = "$text\n$shortUrl"
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, fullText)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, null))
            }
        }
    }
}
