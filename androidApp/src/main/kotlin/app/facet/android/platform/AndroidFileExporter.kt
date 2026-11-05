package app.facet.android.platform

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.facet.platform.FileExporter
import app.facet.platform.SavedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Save → Downloads/Facet (MediaStore on Android 10+, the public Downloads folder before that).
 * Share → the system share sheet, through the FileProvider in the manifest. No network involved.
 */
class AndroidFileExporter(context: Context) : FileExporter {
    private val app = context.applicationContext
    private val authority = "${app.packageName}.files"

    override suspend fun save(sourcePath: String, fileName: String, mimeType: String): SavedFile =
        withContext(Dispatchers.IO) {
            val source = File(sourcePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveToMediaStore(source, fileName, mimeType)
            else saveToPublicDownloads(source, fileName, mimeType)
        }

    override fun share(sourcePath: String, fileName: String, mimeType: String) {
        val uri = FileProvider.getUriForFile(app, authority, File(sourcePath), fileName)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, fileName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Lets the share sheet preview the file name and grants read access to the chosen app.
        send.clipData = ClipData.newRawUri(fileName, uri)
        app.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun open(file: SavedFile): Boolean {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(file.uri), file.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(view)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(source: File, fileName: String, mimeType: String): SavedFile {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, storeMimeType(fileName))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("MediaStore refused $fileName")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("no output stream for $uri")
            out.use { o -> source.inputStream().use { it.copyTo(o) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // MediaStore renames on a clash ("Mug_01 (1).3mf"): report the name it actually used.
        val savedName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: fileName
        return SavedFile(location(savedName), uri.toString(), mimeType)
    }

    /** Android 8–9: needs WRITE_EXTERNAL_STORAGE (the export screen asks for it first). */
    private fun saveToPublicDownloads(source: File, fileName: String, mimeType: String): SavedFile {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("storage permission not granted")
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create $dir")
        val target = uniqueFile(dir, fileName)
        source.copyTo(target)
        MediaScannerConnection.scanFile(app, arrayOf(target.path), arrayOf(mimeType), null)
        val uri = FileProvider.getUriForFile(app, authority, target)
        return SavedFile(location(target.name), uri.toString(), mimeType)
    }

    /** As the Files app names it ("Downloads"; the folder on disk is "Download"). */
    private fun location(name: String) = "Downloads/$FOLDER/$name"

    /**
     * MediaStore changes the extension when it doesn't match the MIME type, so use the type Android
     * itself maps to the extension; unknown extensions (most versions don't know .3mf) are kept as
     * they are with application/octet-stream.
     */
    private fun storeMimeType(fileName: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    companion object {
        /** Folder inside Downloads. */
        const val FOLDER = "Facet"

        /** "Mug_01.3mf" → "Mug_01 (1).3mf" while the name is taken (matches MediaStore's style). */
        fun uniqueFile(dir: File, fileName: String): File {
            var file = File(dir, fileName)
            val stem = fileName.substringBeforeLast('.')
            val ext = fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            var n = 1
            while (file.exists()) {
                file = File(dir, "$stem ($n)$ext")
                n++
            }
            return file
        }
    }
}
