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

        public Vrm10Instance Instance { get; private set; }
        public string Status { get; private set; } = "";
        public bool UsesPerfectSync { get; private set; }

        public event Action<Vrm10Instance> AvatarLoaded;

        readonly float[] _packet = new float[TrackingPacket.FloatCount];
        readonly Dictionary<ExpressionKey, float> _weights = new Dictionary<ExpressionKey, float>();
        ExpressionKey?[] _perfectSyncKeys = Array.Empty<ExpressionKey?>();
        Vector3 _rootBasePosition;
        DateTime _loadedWriteTime = DateTime.MinValue;
        bool _loading;
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
            if (Instance != null && receiver != null && receiver.TryGetLatest(_packet)) Apply(_packet);
        }

        void CheckForNewAvatar()
        {
            if (_loading) return;
            var path = VrmPath;
            if (!File.Exists(path))
            {
                if (Instance == null) Status = ""; // the host app shows its own "no avatar" screen
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
            Status = "아바타를 불러오고 있어요…";
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
                Status = "";
                AvatarLoaded?.Invoke(instance);
            }
            catch (Exception e)
            {
                Debug.LogException(e);
                Status = "아바타를 불러오지 못했어요.\n다른 VRM 파일로 다시 시도해 주세요.";
                _loadedWriteTime = writeTime; // don't retry the same broken file every second
            }
            finally
            {
                _loading = false;
            }
        }

        void Setup(Vrm10Instance instance)
        {
            instance.transform.SetParent(transform, false);
            _rootBasePosition = instance.transform.localPosition;
            instance.LookAtTargetType = VRM10ObjectLookAt.LookAtTargetTypes.YawPitchValue;
            ApplyRestPose(instance);

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

            if (p[TrackingPacket.Detected] < 0.5f) return; // hold last pose while the face is lost

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
                SetPreset(ExpressionKey.BlinkLeft, p, TrackingPacket.Preset.BlinkLeft);
                SetPreset(ExpressionKey.BlinkRight, p, TrackingPacket.Preset.BlinkRight);
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
