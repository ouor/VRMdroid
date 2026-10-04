package com.ouor.vrmdroid.ui

import android.content.Context
import androidx.annotation.ColorRes
import com.ouor.vrmdroid.R
import com.ouor.vrmdroid.service.PcLink
import com.ouor.vrmdroid.service.TrackingStatus
import java.net.Inet4Address
import java.net.NetworkInterface

/** Turns raw tracking status into the few plain words and colors the user sees. */
object StatusText {

    data class Pill(val text: String, @ColorRes val color: Int)

    /** The single most important thing right now, for the pill at the top of the screen. */
    fun pill(context: Context, s: TrackingStatus): Pill =
        pillRes(s).let { (text, color) -> Pill(context.getString(text), color) }

    /** String and color resources behind [pill]; free of Context so it can be unit tested. */
    fun pillRes(s: TrackingStatus): Pair<Int, Int> = when {
        // A real failure must never hide behind "no face".
        s.error != null -> R.string.pill_error to R.color.status_error
        !s.running -> R.string.pill_off to R.color.status_idle
        !s.faceDetected -> R.string.pill_no_face to R.color.status_wait
        s.pcLink == PcLink.SENDING -> R.string.pill_pc_sending to R.color.status_ok
        s.pcLink == PcLink.WAITING -> R.string.pill_pc_waiting to R.color.status_wait
        else -> R.string.pill_face to R.color.status_ok
    }

    fun tracking(context: Context, s: TrackingStatus): Pill = when {
        s.error != null -> Pill(s.error, R.color.status_error)
        !s.running -> Pill(context.getString(R.string.status_off), R.color.status_idle)
        !s.faceDetected -> Pill(context.getString(R.string.status_no_face), R.color.status_wait)
        s.fps >= 24f -> Pill(context.getString(R.string.status_quality_good), R.color.status_ok)
        s.fps >= 15f -> Pill(context.getString(R.string.status_quality_ok), R.color.status_ok)
        else -> Pill(context.getString(R.string.status_quality_slow), R.color.status_wait)
    }

    fun pc(context: Context, s: TrackingStatus): Pill = when (s.pcLink) {
        PcLink.OFF -> Pill(context.getString(R.string.status_pc_off), R.color.status_idle)
        PcLink.WAITING -> Pill(context.getString(R.string.status_pc_waiting), R.color.status_wait)
        PcLink.SENDING -> Pill(context.getString(R.string.status_pc_sending, s.pcAddress), R.color.status_ok)
    }

    /** This phone's Wi-Fi IPv4 address, which the PC app needs. */
    fun phoneAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address }?.hostAddress
    }.getOrNull()
}
