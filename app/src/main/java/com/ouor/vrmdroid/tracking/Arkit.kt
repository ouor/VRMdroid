package com.ouor.vrmdroid.tracking

// Top-level so it is initialized before the enum entries that read it.
private val DIRECTIONAL_SHAPES = setOf("jawLeft", "jawRight", "mouthLeft", "mouthRight")

/**
 * The 52 ARKit face blendshapes, in Apple's documented order.
 *
 * Every tracker reports values into an array indexed by this enum, so the rest of the
 * pipeline (filters, senders, Unity bridge) never depends on a specific tracking backend.
 * Names use ARKit's subject-relative convention: `Left` means the user's own left.
 */
enum class Arkit(val arkitName: String) {
    BrowDownLeft("browDownLeft"),
    BrowDownRight("browDownRight"),
    BrowInnerUp("browInnerUp"),
    BrowOuterUpLeft("browOuterUpLeft"),
    BrowOuterUpRight("browOuterUpRight"),
    CheekPuff("cheekPuff"),
    CheekSquintLeft("cheekSquintLeft"),
    CheekSquintRight("cheekSquintRight"),
    EyeBlinkLeft("eyeBlinkLeft"),
    EyeBlinkRight("eyeBlinkRight"),
    EyeLookDownLeft("eyeLookDownLeft"),
    EyeLookDownRight("eyeLookDownRight"),
    EyeLookInLeft("eyeLookInLeft"),
    EyeLookInRight("eyeLookInRight"),
    EyeLookOutLeft("eyeLookOutLeft"),
    EyeLookOutRight("eyeLookOutRight"),
    EyeLookUpLeft("eyeLookUpLeft"),
    EyeLookUpRight("eyeLookUpRight"),
    EyeSquintLeft("eyeSquintLeft"),
    EyeSquintRight("eyeSquintRight"),
    EyeWideLeft("eyeWideLeft"),
    EyeWideRight("eyeWideRight"),
    JawForward("jawForward"),
    JawLeft("jawLeft"),
    JawOpen("jawOpen"),
    JawRight("jawRight"),
    MouthClose("mouthClose"),
    MouthDimpleLeft("mouthDimpleLeft"),
    MouthDimpleRight("mouthDimpleRight"),
    MouthFrownLeft("mouthFrownLeft"),
    MouthFrownRight("mouthFrownRight"),
    MouthFunnel("mouthFunnel"),
    MouthLeft("mouthLeft"),
    MouthLowerDownLeft("mouthLowerDownLeft"),
    MouthLowerDownRight("mouthLowerDownRight"),
    MouthPressLeft("mouthPressLeft"),
    MouthPressRight("mouthPressRight"),
    MouthPucker("mouthPucker"),
    MouthRight("mouthRight"),
    MouthRollLower("mouthRollLower"),
    MouthRollUpper("mouthRollUpper"),
    MouthShrugLower("mouthShrugLower"),
    MouthShrugUpper("mouthShrugUpper"),
    MouthSmileLeft("mouthSmileLeft"),
    MouthSmileRight("mouthSmileRight"),
    MouthStretchLeft("mouthStretchLeft"),
    MouthStretchRight("mouthStretchRight"),
    MouthUpperUpLeft("mouthUpperUpLeft"),
    MouthUpperUpRight("mouthUpperUpRight"),
    NoseSneerLeft("noseSneerLeft"),
    NoseSneerRight("noseSneerRight"),
    TongueOut("tongueOut");

    /** VSeeFace "perfect sync" clip name, e.g. `EyeBlinkLeft`. */
    val perfectSyncName: String = arkitName.replaceFirstChar { it.uppercaseChar() }

    /** iFacialMocap wire name, e.g. `eyeBlink_L`. */
    val iFacialMocapName: String = when {
        // jawLeft/mouthLeft describe movement direction and keep their names.
        arkitName in DIRECTIONAL_SHAPES -> arkitName
        arkitName.endsWith("Left") -> arkitName.removeSuffix("Left") + "_L"
        arkitName.endsWith("Right") -> arkitName.removeSuffix("Right") + "_R"
        else -> arkitName
    }

    /** The same blendshape on the other side of the face (itself for symmetric ones). */
    val mirrored: Arkit by lazy {
        val swapped = when {
            arkitName.endsWith("Left") -> arkitName.removeSuffix("Left") + "Right"
            arkitName.endsWith("Right") -> arkitName.removeSuffix("Right") + "Left"
            else -> arkitName
        }
        byName.getValue(swapped)
    }

    companion object {
        const val COUNT = 52
        private val byName: Map<String, Arkit> = entries.associateBy { it.arkitName }
        fun fromName(name: String): Arkit? = byName[name]

        /** Index permutation that swaps left and right blendshapes. */
        val MIRROR_INDEX: IntArray by lazy { IntArray(COUNT) { entries[it].mirrored.ordinal } }
    }
}
