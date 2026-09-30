package app.slate.tablet.study

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Shares a session as a .zip of Markdown plus its images, through Slate's own content provider. */
object FileShare {
    const val AUTHORITY = "app.slate.tablet.files"

    fun shareMarkdown(activity: Activity, store: StudyStore, session: StudyStore.Session) {
        val safe = session.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Slate study" }
        val out = File(File(activity.cacheDir, "export").apply { mkdirs() }, "$safe.zip")
        ZipOutputStream(out.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("$safe/$safe.md"))
            zip.write(store.exportMarkdown(session.id).toByteArray())
            zip.closeEntry()
            for (e in store.entries(session.id)) {
                val img = File(store.dir(session.id), e.image)
                if (!img.exists()) continue
                zip.putNextEntry(ZipEntry("$safe/${e.image}"))
                img.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val uri: Uri = Uri.parse("content://$AUTHORITY/export/${Uri.encode(out.name)}")
        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(Intent.createChooser(send, "Share ${session.title}"))
        } catch (e: Exception) {
            Toast.makeText(activity, "No app to share with.", Toast.LENGTH_SHORT).show()
        }
    }
}
