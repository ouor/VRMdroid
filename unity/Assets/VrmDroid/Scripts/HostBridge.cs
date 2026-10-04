using UnityEngine;

namespace VrmDroid
{
    /// <summary>Kept in step with com.ouor.vrmdroid.avatar.AvatarState on the Android side.</summary>
    public enum AvatarState
    {
        None = 0,
        Loading = 1,
        Failed = 2,
        Restored = 3,
    }

    /// <summary>
    /// Calls into the Android host app. All user-facing text lives there, so Unity only reports
    /// state codes and draws no UI of its own.
    /// </summary>
    public static class HostBridge
    {
        const string BridgeClass = "com.ouor.vrmdroid.avatar.UnityBridge";

        public static void ReportAvatarState(AvatarState state)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            using var bridge = new AndroidJavaClass(BridgeClass);
            bridge.CallStatic("onAvatarState", (int)state);
#else
            Debug.Log($"[VrmDroid] avatar state: {state}");
#endif
        }
    }
}
