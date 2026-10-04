using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using UnityEngine;
using UniVRM10;

namespace VrmDroid
{
    /// <summary>
    /// Loads the user's VRM (VRM 0.x or 1.0) and drives it from <see cref="TrackingReceiver"/>.
    /// Uses ARKit "perfect sync" expressions when the model has them, VRM presets otherwise.
    /// </summary>
    public sealed class AvatarController : MonoBehaviour
    {
        public const string AvatarFileName = "avatar.vrm";
        // Kept in step with AvatarStore on the Android side.
        const string PreviousFileName = "avatar.prev.vrm";
        const string SampleMarker = "avatar.sample";
        const string PreviousSampleMarker = "avatar.prev.sample";

        [Tooltip("Editor/testing override. Empty = Application.persistentDataPath/avatar.vrm (where the host app stores it).")]
        [SerializeField] string vrmPathOverride = "";
        [SerializeField] TrackingReceiver receiver;
        [Tooltip("Fraction of the head rotation given to the neck.")]
        [Range(0, 1)] [SerializeField] float neckShare = 0.3f;
        [Tooltip("How much head translation moves the whole avatar.")]
        [SerializeField] float positionScale = 0.5f;
        [Tooltip("Apply happy/angry/sad/surprised estimated from the face (preset mode only).")]
        [SerializeField] bool applyEmotions = false;
        [Tooltip("Degrees the upper arms are lowered from the T-pose.")]
        [SerializeField] float armDownAngle = 72f;
        [Tooltip("Slight elbow bend so the arms don't look stiff.")]
        [SerializeField] float elbowBend = 12f;
        [Tooltip("Seconds without a face before the avatar relaxes to a neutral face and pose.")]
        [SerializeField] float lostFaceHold = 1f;
        [Tooltip("How quickly (per second) the avatar relaxes once the face is lost.")]
        [SerializeField] float relaxSpeed = 3f;

        public Vrm10Instance Instance { get; private set; }
        AvatarState _state;
        /// <summary>Loading/failure state; the host app turns it into on-screen text.</summary>
        public AvatarState State
        {
            get => _state;
            private set
            {
                if (_state == value) return;
                _state = value;
                HostBridge.ReportAvatarState(value);
            }
        }
        public bool UsesPerfectSync { get; private set; }

        public event Action<Vrm10Instance> AvatarLoaded;

        readonly float[] _packet = new float[TrackingPacket.FloatCount];
        readonly Dictionary<ExpressionKey, float> _weights = new Dictionary<ExpressionKey, float>();
        ExpressionKey?[] _perfectSyncKeys = Array.Empty<ExpressionKey?>();
        Vector3 _rootBasePosition;
        float _lastFaceTime;
        static readonly HumanBodyBones[] RelaxBones = { HumanBodyBones.Neck, HumanBodyBones.Head };
        bool _hasSideBlinks;
        readonly System.Collections.Generic.List<ExpressionKey> _keyScratch = new System.Collections.Generic.List<ExpressionKey>();
        DateTime _loadedWriteTime = DateTime.MinValue;
        bool _loading;
        bool _restoredNotice;
        float _nextFileCheck;

        string VrmPath => string.IsNullOrEmpty(vrmPathOverride)
            ? Path.Combine(Application.persistentDataPath, AvatarFileName)
            : vrmPathOverride;

        void Awake()
        {
            if (receiver == null) receiver = GetComponent<TrackingReceiver>();
        }

        void Update()
        {
            if (Time.unscaledTime >= _nextFileCheck)
            {
                _nextFileCheck = Time.unscaledTime + 1f;
                CheckForNewAvatar();
            }
            if (Instance == null) return;
            if (receiver != null && receiver.TryGetLatest(_packet)) Apply(_packet);
            // Face gone (looked away, left the desk): don't freeze mid-blink; ease to neutral.
            if (Time.unscaledTime - _lastFaceTime > lostFaceHold) Relax(Time.unscaledDeltaTime);
        }

        void Relax(float dt)
        {
            var k = 1f - Mathf.Exp(-relaxSpeed * dt);
            var rig = Instance.Runtime.ControlRig;
            foreach (var bone in RelaxBones)
            {
                var t = rig?.GetBoneTransform(bone);
                if (t != null) t.localRotation = Quaternion.Slerp(t.localRotation, Quaternion.identity, k);
            }
            Instance.transform.localPosition = Vector3.Lerp(Instance.transform.localPosition, _rootBasePosition, k);
            Instance.Runtime.LookAt.SetYawPitchManually(0f, 0f);
            if (_weights.Count == 0) return;
            _keyScratch.Clear();
            _keyScratch.AddRange(_weights.Keys);
            foreach (var key in _keyScratch) _weights[key] *= 1f - k;
            Instance.Runtime.Expression.SetWeightsNonAlloc(_weights);
        }

        void CheckForNewAvatar()
        {
            if (_loading) return;
            var path = VrmPath;
            if (!File.Exists(path))
            {
                if (Instance == null) State = AvatarState.None; // the host app shows its own "no avatar" screen
                return;
            }
            var writeTime = File.GetLastWriteTimeUtc(path);
            // Skip files we've already tried, whether that load succeeded or failed; otherwise a
            // broken file would be re-read and re-parsed every second.
            if (writeTime == _loadedWriteTime) return;
            _ = LoadAsync(path, writeTime);
        }

        async Task LoadAsync(string path, DateTime writeTime)
        {
            _loading = true;
            State = AvatarState.Loading;
            // Free the current avatar first: holding old and new models (plus the file bytes)
            // at once doubles peak memory, which can get the whole app killed on low-end phones.
            if (Instance != null)
            {
                Destroy(Instance.gameObject);
                Instance = null;
                await Resources.UnloadUnusedAssets();
            }
            try
            {
                var instance = await Vrm10.LoadPathAsync(path, canLoadVrm0X: true, showMeshes: true);
                if (instance == null) throw new InvalidDataException("VRM 파일을 읽지 못했어요");
                Instance = instance;
                _loadedWriteTime = writeTime;
                Setup(instance);
                State = AvatarState.None;
                if (_restoredNotice)
                {
                    _restoredNotice = false;
                    StartCoroutine(ShowRestored());
                }
                AvatarLoaded?.Invoke(instance);
            }
            catch (Exception e)
            {
                Debug.LogException(e);
                _loadedWriteTime = writeTime; // don't retry the same broken file every second
                if (RestorePrevious(path))
                {
                    // The restored file has its own write time, so the next check loads it.
                    _restoredNotice = true;
                    State = AvatarState.None;
                }
                else
                {
                    State = AvatarState.Failed;
                }
            }
            finally
            {
                _loading = false;
            }
        }

        /// <summary>
        /// Puts back the avatar the host app set aside on import (avatar.prev.vrm), so a broken
        /// file doesn't leave the stage empty. Returns false when there is nothing to restore.
        /// </summary>
        static bool RestorePrevious(string path)
        {
            var dir = Path.GetDirectoryName(path);
            var previous = Path.Combine(dir, PreviousFileName);
            if (!File.Exists(previous)) return false;
            try
            {
                File.Delete(path);
                File.Move(previous, path);
                var marker = Path.Combine(dir, PreviousSampleMarker);
                if (File.Exists(marker)) File.Move(marker, Path.Combine(dir, SampleMarker));
                return true;
            }
            catch (Exception e)
            {
                Debug.LogException(e);
                return false;
            }
        }

        System.Collections.IEnumerator ShowRestored()
        {
            State = AvatarState.Restored;
            yield return new WaitForSecondsRealtime(4f);
            if (State == AvatarState.Restored) State = AvatarState.None;
        }

        void Setup(Vrm10Instance instance)
        {
            instance.transform.SetParent(transform, false);
            _rootBasePosition = instance.transform.localPosition;
            instance.LookAtTargetType = VRM10ObjectLookAt.LookAtTargetTypes.YawPitchValue;
            ApplyRestPose(instance);

            _weights.Clear();
            _lastFaceTime = Time.unscaledTime;
            _hasSideBlinks = false;
            foreach (var key in instance.Runtime.Expression.ExpressionKeys)
            {
                if (key.Preset == ExpressionPreset.blinkLeft || key.Preset == ExpressionPreset.blinkRight) _hasSideBlinks = true;
            }

            // Map ARKit names to custom expressions, case-insensitively ("EyeBlinkLeft", "eyeBlinkLeft", ...).
            var custom = new Dictionary<string, ExpressionKey>(StringComparer.OrdinalIgnoreCase);
            foreach (var key in instance.Runtime.Expression.ExpressionKeys)
            {
                if (key.Preset == ExpressionPreset.custom && !string.IsNullOrEmpty(key.Name)) custom[key.Name] = key;
            }
            _perfectSyncKeys = new ExpressionKey?[TrackingPacket.ArkitCount];
            var matched = 0;
            for (var i = 0; i < TrackingPacket.ArkitCount; i++)
            {
                if (custom.TryGetValue(TrackingPacket.ArkitNames[i], out var k)) { _perfectSyncKeys[i] = k; matched++; }
            }
            // Partial matches are usually unrelated custom clips; require most of the set.
            UsesPerfectSync = matched >= 40;
            Debug.Log($"[VrmDroid] Loaded avatar, perfect sync: {UsesPerfectSync} ({matched}/52)");
        }

        /// <summary>
        /// Relaxed standing pose instead of the T-pose: arms down at the sides with a slight
        /// elbow bend. Control-rig bones are normalized (identity in T-pose, world-aligned axes);
        /// the avatar faces +Z with its left arm along -X, so +Z roll lowers the left arm and +Y
        /// yaw swings the left forearm forward.
        /// </summary>
        void ApplyRestPose(Vrm10Instance instance)
        {
            var rig = instance.Runtime.ControlRig;
            if (rig == null) return;
            void Set(HumanBodyBones bone, Quaternion rotation)
            {
                var t = rig.GetBoneTransform(bone);
                if (t != null) t.localRotation = rotation;
            }
            Set(HumanBodyBones.LeftUpperArm, Quaternion.Euler(0f, 0f, armDownAngle));
            Set(HumanBodyBones.RightUpperArm, Quaternion.Euler(0f, 0f, -armDownAngle));
            Set(HumanBodyBones.LeftLowerArm, Quaternion.Euler(0f, elbowBend, 0f));
            Set(HumanBodyBones.RightLowerArm, Quaternion.Euler(0f, -elbowBend, 0f));
        }

        void Apply(float[] p)
        {
            var runtime = Instance.Runtime;
            var rig = runtime.ControlRig;

            if (p[TrackingPacket.Detected] < 0.5f) return; // Update() relaxes after a short hold
            _lastFaceTime = Time.unscaledTime;

            var head = new Quaternion(
                p[TrackingPacket.HeadRotation], p[TrackingPacket.HeadRotation + 1],
                p[TrackingPacket.HeadRotation + 2], p[TrackingPacket.HeadRotation + 3]);
            // A degenerate rotation would poison the bones (and spring bones) for good.
            var length = Mathf.Sqrt(head.x * head.x + head.y * head.y + head.z * head.z + head.w * head.w);
            if (length < 1e-3f) return;
            head = new Quaternion(head.x / length, head.y / length, head.z / length, head.w / length);
            var neckBone = rig?.GetBoneTransform(HumanBodyBones.Neck);
            var headBone = rig?.GetBoneTransform(HumanBodyBones.Head);
            if (neckBone != null)
            {
                neckBone.localRotation = Quaternion.Slerp(Quaternion.identity, head, neckShare);
                if (headBone != null) headBone.localRotation = Quaternion.Slerp(Quaternion.identity, head, 1f - neckShare);
            }
            else if (headBone != null)
            {
                headBone.localRotation = head;
            }

            var offset = new Vector3(
                p[TrackingPacket.HeadPosition], p[TrackingPacket.HeadPosition + 1], p[TrackingPacket.HeadPosition + 2]);
            Instance.transform.localPosition = _rootBasePosition + offset * positionScale;

            // UniVRM: yaw + = right, pitch + = up. The packet's pitch is + = down.
            runtime.LookAt.SetYawPitchManually(p[TrackingPacket.Gaze], -p[TrackingPacket.Gaze + 1]);

            _weights.Clear();
            if (UsesPerfectSync)
            {
                for (var i = 0; i < TrackingPacket.ArkitCount; i++)
                {
                    if (_perfectSyncKeys[i] is ExpressionKey k) _weights[k] = p[TrackingPacket.Blendshapes + i];
                }
            }
            else
            {
                SetPreset(ExpressionKey.Aa, p, TrackingPacket.Preset.Aa);
                SetPreset(ExpressionKey.Ih, p, TrackingPacket.Preset.Ih);
                SetPreset(ExpressionKey.Ou, p, TrackingPacket.Preset.Ou);
                SetPreset(ExpressionKey.Ee, p, TrackingPacket.Preset.Ee);
                SetPreset(ExpressionKey.Oh, p, TrackingPacket.Preset.Oh);
                if (_hasSideBlinks)
                {
                    SetPreset(ExpressionKey.BlinkLeft, p, TrackingPacket.Preset.BlinkLeft);
                    SetPreset(ExpressionKey.BlinkRight, p, TrackingPacket.Preset.BlinkRight);
                }
                else
                {
                    // Some models only define the combined "blink".
                    _weights[ExpressionKey.Blink] = 0.5f * (
                        p[TrackingPacket.Presets + (int)TrackingPacket.Preset.BlinkLeft] +
                        p[TrackingPacket.Presets + (int)TrackingPacket.Preset.BlinkRight]);
                }
                if (applyEmotions)
                {
                    SetPreset(ExpressionKey.Happy, p, TrackingPacket.Preset.Happy);
                    SetPreset(ExpressionKey.Angry, p, TrackingPacket.Preset.Angry);
                    SetPreset(ExpressionKey.Sad, p, TrackingPacket.Preset.Sad);
                    SetPreset(ExpressionKey.Surprised, p, TrackingPacket.Preset.Surprised);
                }
            }
            runtime.Expression.SetWeightsNonAlloc(_weights);
        }

        void SetPreset(ExpressionKey key, float[] p, TrackingPacket.Preset preset)
        {
            _weights[key] = p[TrackingPacket.Presets + (int)preset];
        }
    }
}
