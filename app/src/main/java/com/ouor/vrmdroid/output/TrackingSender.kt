package com.ouor.vrmdroid.output

import com.ouor.vrmdroid.processing.TrackingResult
import com.ouor.vrmdroid.settings.AppSettings

/** Sends processed tracking data to a PC application. Called from a single network thread. */
interface TrackingSender : AutoCloseable {
    /** Short description of where data is going, for the status line. */
    val destination: String

    fun send(result: TrackingResult, settings: AppSettings)
}
