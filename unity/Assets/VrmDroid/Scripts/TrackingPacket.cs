using System;

namespace VrmDroid
{
    /// <summary>
    /// Wire format sent by the Android host's PreviewSender
    /// (app/src/main/java/com/ouor/vrmdroid/output/PreviewSender.kt). Keep the two in sync.
    ///
    /// "VDF1" followed by little-endian floats. All values are from the avatar's point of view
    /// in Unity coordinates (mirroring already applied by the host).
    /// </summary>
    public static class TrackingPacket
    {
        public const int ArkitCount = 52;
        public const int PresetCount = 12;

        public const int Detected = 0;
        public const int Blendshapes = 1;
        public const int HeadRotation = Blendshapes + ArkitCount; // 53: x, y, z, w
        public const int HeadPosition = HeadRotation + 4; // 57: meters, relative to calibration
        public const int Gaze = HeadPosition + 3; // 60: yaw (+ = avatar's right), pitch (+ = down)
        public const int Presets = Gaze + 2; // 62
        public const int FloatCount = Presets + PresetCount; // 74

        public static readonly byte[] Magic = { (byte)'V', (byte)'D', (byte)'F', (byte)'1' };

        /// <summary>ARKit names in the order used by the packet.</summary>
        public static readonly string[] ArkitNames =
        {
            "browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft", "browOuterUpRight",
            "cheekPuff", "cheekSquintLeft", "cheekSquintRight", "eyeBlinkLeft", "eyeBlinkRight",
            "eyeLookDownLeft", "eyeLookDownRight", "eyeLookInLeft", "eyeLookInRight",
            "eyeLookOutLeft", "eyeLookOutRight", "eyeLookUpLeft", "eyeLookUpRight",
            "eyeSquintLeft", "eyeSquintRight", "eyeWideLeft", "eyeWideRight", "jawForward",
            "jawLeft", "jawOpen", "jawRight", "mouthClose", "mouthDimpleLeft", "mouthDimpleRight",
            "mouthFrownLeft", "mouthFrownRight", "mouthFunnel", "mouthLeft", "mouthLowerDownLeft",
            "mouthLowerDownRight", "mouthPressLeft", "mouthPressRight", "mouthPucker", "mouthRight",
            "mouthRollLower", "mouthRollUpper", "mouthShrugLower", "mouthShrugUpper",
            "mouthSmileLeft", "mouthSmileRight", "mouthStretchLeft", "mouthStretchRight",
            "mouthUpperUpLeft", "mouthUpperUpRight", "noseSneerLeft", "noseSneerRight", "tongueOut",
        };

        /// <summary>VRM 1.0 preset names in packet order (matches VrmPresets.Preset in Kotlin).</summary>
        public enum Preset { Aa, Ih, Ou, Ee, Oh, BlinkLeft, BlinkRight, Happy, Angry, Sad, Relaxed, Surprised }

        public static bool TryParse(byte[] data, int length, float[] into)
        {
            if (length < 4 + FloatCount * 4) return false;
            for (var i = 0; i < 4; i++) if (data[i] != Magic[i]) return false;
            Buffer.BlockCopy(data, 4, into, 0, FloatCount * 4);
            if (!BitConverter.IsLittleEndian)
            {
                for (var i = 0; i < FloatCount; i++)
                {
                    var b = BitConverter.GetBytes(into[i]);
                    Array.Reverse(b);
                    into[i] = BitConverter.ToSingle(b, 0);
                }
            }
            return true;
        }
    }
}
