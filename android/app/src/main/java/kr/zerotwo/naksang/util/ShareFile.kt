package kr.zerotwo.naksang.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes [body] into the cache and opens the system share sheet.
 * No storage permission. Returns a short Korean error, or null.
 */
fun shareCacheFile(
    context: Context,
    fileName: String,
    mime: String,
    body: String,
    chooserTitle: String,
): String? {
    return try {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(body)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, chooserTitle)
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        null
    } catch (e: Exception) {
        "기록을 보내지 못했어요"
    }
}
