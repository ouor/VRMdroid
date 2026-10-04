package com.ouor.vrmdroid.service

/**
 * Rolling per-stage timings of the tracking pipeline, summarized into one log line per window so
 * performance changes can be compared run to run (`adb logcat -s VrmPerf`).
 *
 * Stages: `conv` (camera image to tracker input), `infer` (submit to result) and `age` (camera
 * sensor timestamp to result, i.e. what the avatar lags behind the face). Pure JVM; safe to call
 * from the analysis and result threads at once.
 */
class PerfStats(private val clock: () -> Long, private val windowMs: Long = WINDOW_MS) {
    private val convert = Samples()
    private val inference = Samples()
    private val age = Samples()
    private var results = 0
    private var detected = 0
    private var windowStart = clock()

    @Synchronized fun onConvert(ms: Float) = convert.add(ms)

    @Synchronized fun onResult(detected: Boolean, inferenceMs: Float, ageMs: Float?) {
        results++
        if (detected) this.detected++
        inference.add(inferenceMs)
        ageMs?.let(age::add)
    }

    /**
     * The summary of the window that just ended, or null while it is still running. [extra] is
     * appended as is (device state such as temperature) and only evaluated when a window ends.
     */
    @Synchronized fun poll(extra: () -> String = { "" }): String? {
        val now = clock()
        val elapsed = now - windowStart
        if (elapsed < windowMs) return null
        val line = buildString {
            append("hz=").append(fmt(results * 1000f / elapsed))
            append(" face=").append(if (results == 0) 0 else detected * 100 / results).append('%')
            append(" conv=").append(convert.summary())
            append(" infer=").append(inference.summary())
            append(" age=").append(age.summary())
            extra().takeIf { it.isNotEmpty() }?.let { append(' ').append(it) }
        }
        results = 0; detected = 0; windowStart = now
        convert.clear(); inference.clear(); age.clear()
        return line
    }

    /** A growable sample buffer; p50/p95 by sorting a copy once per window. */
    private class Samples {
        private var values = FloatArray(256)
        private var size = 0

        fun add(v: Float) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = v
        }

        fun clear() { size = 0 }

        /** "p50/p95" in milliseconds, or "-" without samples. */
        fun summary(): String {
            if (size == 0) return "-"
            val sorted = values.copyOf(size).also { it.sort() }
            return fmt(percentile(sorted, 0.50f)) + "/" + fmt(percentile(sorted, 0.95f))
        }
    }

    companion object {
        const val WINDOW_MS = 5_000L

        /** Nearest-rank percentile of an ascending array. */
        internal fun percentile(sorted: FloatArray, p: Float): Float =
            sorted[(Math.ceil(p * sorted.size.toDouble()).toInt() - 1).coerceIn(0, sorted.size - 1)]

        private fun fmt(v: Float) = (Math.round(v * 10) / 10f).toString()
    }
}
