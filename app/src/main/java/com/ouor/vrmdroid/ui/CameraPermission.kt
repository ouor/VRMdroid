package com.ouor.vrmdroid.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ouor.vrmdroid.R

/**
 * Camera permission handling shared by onboarding and the main screen. Once the system stops
 * showing the prompt ("don't ask again"), asking again silently does nothing, so we explain and
 * offer the app's settings page instead of leaving the user at a dead end.
 */
object CameraPermission {
    private const val KEY_ASKED = "camera_permission_asked"

    fun isGranted(activity: Activity) =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Call right before launching the permission request. */
    fun markAsked(activity: Activity) {
        PreferenceManager.getDefaultSharedPreferences(activity).edit().putBoolean(KEY_ASKED, true).apply()
    }

    /** True when the system will no longer show the camera prompt. */
    fun isPermanentlyDenied(activity: Activity): Boolean =
        !isGranted(activity) &&
            PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(KEY_ASKED, false) &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)

    /** After a denial: point to settings if the prompt is gone for good, else a short hint. */
    fun onDenied(activity: Activity) {
        if (isPermanentlyDenied(activity)) showSettingsDialog(activity)
        else Toast.makeText(activity, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
    }

    fun showSettingsDialog(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.camera_permission_off_title)
            .setMessage(R.string.camera_permission_off_body)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
