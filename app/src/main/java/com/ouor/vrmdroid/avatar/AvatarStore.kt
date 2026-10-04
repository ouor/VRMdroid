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

    private const val SAMPLE_ASSET = "sample_avatar.vrm"
    private const val SAMPLE_MARKER = "avatar.sample"
    /** The avatar before the last import; Unity moves it back if the new file fails to load. */
    const val PREVIOUS_FILE_NAME = "avatar.prev.vrm"
    const val PREVIOUS_SAMPLE_MARKER = "avatar.prev.sample"

    fun file(context: Context): File = File(context.getExternalFilesDir(null), FILE_NAME)

    /** True while the bundled sample avatar is in place, i.e. the user hasn't picked their own. */
    fun isSample(context: Context): Boolean =
        File(context.getExternalFilesDir(null), SAMPLE_MARKER).exists() && file(context).exists()

    /**
     * Puts the bundled sample avatar in place when there is no avatar yet, so the first launch
     * isn't an empty stage. Returns true if an avatar exists afterwards. Call off the main thread.
     */
    fun ensureAvatar(context: Context): Boolean {
        if (file(context).exists()) return true
        val dir = context.getExternalFilesDir(null) ?: return false
        return runCatching {
            val tmp = File(dir, "$FILE_NAME.tmp")
            context.assets.open(SAMPLE_ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            File(dir, SAMPLE_MARKER).createNewFile()
            moveIntoPlace(tmp, file(context))
        }.isSuccess
    }


    /** Copies the picked document into place atomically. Call off the main thread. */
    fun import(context: Context, uri: Uri): String {
        val name = queryName(context, uri) ?: FILE_NAME
        val dir = context.getExternalFilesDir(null)!!
        val tmp = File(dir, "$FILE_NAME.tmp")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { context.getString(R.string.error_file_open) }
            tmp.outputStream().use { out -> input.copyTo(out) }
        }
        if (!VrmCheck.isVrm(tmp)) {
            tmp.delete()
            throw IllegalArgumentException(context.getString(R.string.error_not_vrm))
        }
        val current = file(context)
        val sampleMarker = File(dir, SAMPLE_MARKER)
        if (current.exists()) {
            val previous = File(dir, PREVIOUS_FILE_NAME)
            previous.delete()
            current.renameTo(previous)
            val previousMarker = File(dir, PREVIOUS_SAMPLE_MARKER)
            previousMarker.delete()
            if (sampleMarker.exists()) sampleMarker.renameTo(previousMarker)
        }
        sampleMarker.delete()
        moveIntoPlace(tmp, current)
        return name
    }

    /** Unity polls for the file, so it must appear complete, never half-written. */
    private fun moveIntoPlace(tmp: File, target: File) {
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun queryName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
