package com.ouor.vrmdroid.service

import com.ouor.vrmdroid.processing.TrackingResult
import com.ouor.vrmdroid.settings.OutputProtocol
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How far along the PC link is, as the user would describe it. */
enum class PcLink {
    /** Sending is turned off (phone-only use). */
    OFF,
    /** We don't know where the PC is yet. */
    WAITING,
    /** Sending to an address, but the PC hasn't confirmed it's listening. */
    SENDING,
    /** The PC app contacted us (iFacialMocap handshake). */
    CONNECTED,
}

data class TrackingStatus(
    val running: Boolean = false,
    val trackerName: String = "",
    val faceDetected: Boolean = false,
    val fps: Float = 0f,
    val inferenceMs: Long = 0,
    val protocol: OutputProtocol = OutputProtocol.IFACIALMOCAP,
    val pcAddress: String = "",
    val pcLink: PcLink = PcLink.WAITING,
    val error: String? = null,
)

/** In-process state shared between [TrackingService] and the UI. */
object TrackingHub {
    private val _status = MutableStateFlow(TrackingStatus())
    val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    private val _latest = MutableStateFlow<TrackingResult?>(null)
    val latest: StateFlow<TrackingResult?> = _latest.asStateFlow()

    private val _pcFound = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Emits the PC's address when a PC app newly connects; drives the "PC를 찾았어요" card. */
    val pcFound: SharedFlow<String> = _pcFound.asSharedFlow()

    /** Set by the UI; consumed by the service on the next frame. */
    @Volatile var calibrationRequested = false

    internal fun updateStatus(transform: (TrackingStatus) -> TrackingStatus) = _status.update(transform)
    internal fun publish(result: TrackingResult?) { _latest.value = result }
    internal fun notifyPcFound(address: String) { _pcFound.tryEmit(address) }
}
