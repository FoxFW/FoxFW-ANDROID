package com.foxfw.companion

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Share text/.sub content out to any app via the system share sheet (FileProvider-backed). */
object Sharing {
    fun share(ctx: Context, filename: String, content: String, mime: String = "text/plain") {
        runCatching {
            val dir = File(ctx.getExternalFilesDir(null), "share").apply { mkdirs() }
            val f = File(dir, filename)
            f.writeText(content)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TITLE, filename)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, "Share $filename").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
