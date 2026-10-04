package com.ouor.vrmdroid.processing

import com.ouor.vrmdroid.tracking.Arkit

/**
 * Derives standard VRM expression presets from ARKit blendshapes, for avatars without
 * perfect-sync clips. Input and output are both from the avatar's point of view.
 */
object VrmPresets {
    /** Order of [compute]'s output. VRM 0.x clip name to VRM 1.0 preset name. */
    enum class Preset(val vrm0: String, val vrm1: String) {
        A("A", "aa"), I("I", "ih"), U("U", "ou"), E("E", "ee"), O("O", "oh"),
        BlinkL("Blink_L", "blinkLeft"), BlinkR("Blink_R", "blinkRight"),
        Joy("Joy", "happy"), Angry("Angry", "angry"), Sorrow("Sorrow", "sad"),
        Fun("Fun", "relaxed"), Surprised("Surprised", "surprised"),
    }

    val COUNT = Preset.entries.size

    fun compute(s: FloatArray, out: FloatArray = FloatArray(COUNT)): FloatArray {
        fun v(a: Arkit) = s[a.ordinal]
        fun avg(a: Arkit, b: Arkit) = (v(a) + v(b)) * 0.5f

        val jaw = v(Arkit.JawOpen)
        val funnel = v(Arkit.MouthFunnel)
        val pucker = v(Arkit.MouthPucker)
        val stretch = avg(Arkit.MouthStretchLeft, Arkit.MouthStretchRight)
        val smile = avg(Arkit.MouthSmileLeft, Arkit.MouthSmileRight)
        val lowerDown = avg(Arkit.MouthLowerDownLeft, Arkit.MouthLowerDownRight)

        // Vowels: rounded shapes take priority over the open "A".
        val o = funnel * 1.4f
        val u = pucker * 1.4f * (1f - jaw)
        val i = (stretch * 1.5f + smile * 0.4f) * (1f - funnel)
        val e = lowerDown * 0.8f * (1f - pucker)
        val a = (jaw * 1.3f - v(Arkit.MouthClose)) * (1f - 0.6f * (o + u).coerceAtMost(1f))

        out[Preset.A.ordinal] = a
        out[Preset.I.ordinal] = i
        out[Preset.U.ordinal] = u
        out[Preset.E.ordinal] = e
        out[Preset.O.ordinal] = o
        out[Preset.BlinkL.ordinal] = v(Arkit.EyeBlinkLeft)
        out[Preset.BlinkR.ordinal] = v(Arkit.EyeBlinkRight)
        out[Preset.Joy.ordinal] = smile * 1.2f
        out[Preset.Angry.ordinal] = avg(Arkit.BrowDownLeft, Arkit.BrowDownRight)
        out[Preset.Sorrow.ordinal] = avg(Arkit.MouthFrownLeft, Arkit.MouthFrownRight) * 1.5f
        out[Preset.Fun.ordinal] = 0f
        out[Preset.Surprised.ordinal] =
            v(Arkit.BrowInnerUp) * avg(Arkit.EyeWideLeft, Arkit.EyeWideRight) * 2f
        for (k in out.indices) out[k] = out[k].coerceIn(0f, 1f)
        return out
    }

    val EMOTIONS = setOf(Preset.Joy, Preset.Angry, Preset.Sorrow, Preset.Fun, Preset.Surprised)
}
