package com.ouor.vrmdroid.avatar

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Holds the user's VRM file at a path the Unity player can read. Unity's
 * `Application.persistentDataPath` is the app's external files dir, so the model goes there.
 */
object AvatarStore {
    const val FILE_NAME = "avatar.vrm"
    private const val NAME_FILE = "avatar.name"

    fun file(context: Context): File = File(context.getExternalFilesDir(null), FILE_NAME)

    fun displayName(context: Context): String? =
        File(context.getExternalFilesDir(null), NAME_FILE).takeIf { it.exists() }?.readText()

    /** Copies the picked document into place atomically. Call off the main thread. */
    fun import(context: Context, uri: Uri): String {
        val name = queryName(context, uri) ?: FILE_NAME
        val dir = context.getExternalFilesDir(null)!!
        val tmp = File(dir, "$FILE_NAME.tmp")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "파일을 열 수 없어요" }
            val header = ByteArray(4)
            tmp.outputStream().use { out ->
                val n = input.read(header)
                require(n == 4 && String(header, Charsets.US_ASCII) == "glTF") { "VRM 파일이 아니에요. .vrm 파일을 골라 주세요" }
                out.write(header)
                input.copyTo(out)
            }
        }
        val target = file(context)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        File(dir, NAME_FILE).writeText(name)
        return name
    }

    private fun queryName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
