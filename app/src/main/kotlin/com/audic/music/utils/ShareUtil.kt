package com.audic.music.utils

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ShareUtil {
    // Application-level scope that outlives any composable — never cancelled by menu dismissal
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun shareUrl(
        context: Context,
        originalUrl: String,
        title: String? = null,
    ) {
        appScope.launch(Dispatchers.IO) {
            val url = ShortLinkManager.shorten(originalUrl, title = title)
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

    fun shareText(
        context: Context,
        text: String,
        url: String,
        title: String? = null,
    ) {
        appScope.launch(Dispatchers.IO) {
            val shortUrl = ShortLinkManager.shorten(url, title = title)
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
