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
    /**
     * Sending to an address. UDP has no acknowledgement, so we never claim the PC is
     * "connected": a PC app that said hello once may have quit since.
     */
    SENDING,
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
    /** How much the phone currently holds back to stay cool (see [ThermalGovernor]). */
    val thermalLevel: ThermalLevel = ThermalLevel.NORMAL,
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

    private val _calibrationRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** "Look straight ahead now" from the UI; the running service re-zeroes on its next frame. */
    val calibrationRequests: SharedFlow<Unit> = _calibrationRequests.asSharedFlow()

    fun requestCalibration() { _calibrationRequests.tryEmit(Unit) }

    internal fun updateStatus(transform: (TrackingStatus) -> TrackingStatus) = _status.update(transform)
    internal fun publish(result: TrackingResult?) { _latest.value = result }
    internal fun notifyPcFound(address: String) { _pcFound.tryEmit(address) }
}
