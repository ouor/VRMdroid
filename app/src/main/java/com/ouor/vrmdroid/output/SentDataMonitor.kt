package com.ouor.vrmdroid.output

import android.os.SystemClock
import com.ouor.vrmdroid.processing.TrackingResult
import com.ouor.vrmdroid.settings.OutputProtocol

/**
 * Records what the PC senders actually put on the wire, for the real-time debug console.
 * Senders call [record] right after sending, with the exact payload text (or a decoded view of
 * binary OSC), so the console shows real traffic rather than a re-creation of it.
 *
 * Only active while the console is visible (and the screen isn't dimmed); recording is skipped
 * otherwise. The console only redraws a few times per second, so senders build the payload text
 * only when [wantsPayload] says a fresh one would be shown.
 */
object SentDataMonitor {

    class Sample(
        val protocol: OutputProtocol,
        val destination: String,
        /** Payload as sent (iFacialMocap text) or decoded (VMC/OSC). */
        val payload: String,
        /** The tracking frame that produced this payload. */
        val result: TrackingResult,
        val bytes: Int,
        val atMs: Long,
    )

    @Volatile var enabled = false
        set(value) {
            field = value
            if (!value) synchronized(window) { window.clear(); last = null }
        }

    @Volatile var last: Sample? = null
        private set

    /** True when the next [record] should carry the payload text. */
    fun wantsPayload(): Boolean {
        if (!enabled) return false
        val sample = last ?: return true
        return SystemClock.uptimeMillis() - sample.atMs >= PAYLOAD_INTERVAL_MS
    }

    /** (timestamp, bytes) of sends in the last second, for the rate line. */
    private val window = ArrayDeque<Pair<Long, Int>>()

    /** [payload] is null when [wantsPayload] was false; the packet then only counts toward [rate]. */
    fun record(protocol: OutputProtocol, destination: String, payload: String?, result: TrackingResult, bytes: Int) {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        if (payload != null) last = Sample(protocol, destination, payload, result, bytes, now)
        synchronized(window) {
            window.addLast(now to bytes)
            while (window.isNotEmpty() && now - window.first().first > 1000) window.removeFirst()
        }
    }

    /** Packets per second and bytes per second over the last second. */
    fun rate(): Pair<Int, Int> = synchronized(window) {
        val now = SystemClock.uptimeMillis()
        while (window.isNotEmpty() && now - window.first().first > 1000) window.removeFirst()
        window.size to window.sumOf { it.second }
    }

    /** A bit faster than the console's refresh, so it always has a recent sample. */
    private const val PAYLOAD_INTERVAL_MS = 200L
}
