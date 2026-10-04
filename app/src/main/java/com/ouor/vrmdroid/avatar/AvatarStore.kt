package com.ouor.vrmdroid.avatar

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ouor.vrmdroid.R
import java.io.File

/**
 * Holds the user's VRM file at a path the Unity player can read. Unity's
 * `Application.persistentDataPath` is the app's external files dir, so the model goes there.
 */
object AvatarStore {
    const val FILE_NAME = "avatar.vrm"

    fun file(context: Context): File = File(context.getExternalFilesDir(null), FILE_NAME)


    /** Copies the picked document into place atomically. Call off the main thread. */
    fun import(context: Context, uri: Uri): String {
        val name = queryName(context, uri) ?: FILE_NAME
        val dir = context.getExternalFilesDir(null)!!
        val tmp = File(dir, "$FILE_NAME.tmp")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { context.getString(R.string.error_file_open) }
            val header = ByteArray(4)
            tmp.outputStream().use { out ->
                val n = input.readNBytes(header, 0, header.size)
                require(n == 4 && String(header, Charsets.US_ASCII) == "glTF") { context.getString(R.string.error_not_vrm) }
                out.write(header)
                input.copyTo(out)
            }
        }
        val target = file(context)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return name
    }

    private fun queryName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
