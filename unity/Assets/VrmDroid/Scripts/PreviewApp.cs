using System.Globalization;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.Rendering;
using UnityEngine.Rendering.Universal;
using UniVRM10;

namespace VrmDroid
{
    /// <summary>
    /// Bootstraps the preview: creates the receiver/avatar objects if the scene lacks them,
    /// frames the camera on the avatar's face, and handles orbit/zoom and the back button.
    /// </summary>
    public sealed class PreviewApp : MonoBehaviour
    {
        [SerializeField] Camera viewCamera;
        [SerializeField] AvatarController avatar;
        [SerializeField] float distance = DefaultDistance;
        [Tooltip("Matches the host app's stage color so the avatar sits on the app, not on a 3D sky.")]
        [SerializeField] Color background = new Color32(0x1E, 0x1A, 0x2E, 0xFF);
        [SerializeField] float minDistance = 0.3f;
        [SerializeField] float maxDistance = 4f;

        const float DefaultDistance = 2.1f;
        // The head bone sits at the base of the skull; the face's center is a little above it.
        const float FaceAboveHeadBone = 0.08f;

        // Level camera: any tilt would move the face away from the 1/3 line.
        const float DefaultPitch = 0f;
        [Tooltip("How far (meters) a two-finger pan may move the view away from the avatar.")]
        [SerializeField] float maxPan = 1.2f;

        Vector3 _focus = new Vector3(0f, 1.25f, 0f);
        Vector3 _homeFocus = new Vector3(0f, 1.25f, 0f);
        float _orbitYaw; // degrees around the avatar, 0 = in front
        float _orbitPitch = DefaultPitch;
        float _lastPinch;
        Vector2 _lastMid;
        int _pinchIdA = -1, _pinchIdB = -1;
        // After a two-finger gesture, ignore the remaining finger until all are lifted,
        // so lifting one finger doesn't make the view jump into an orbit.
        bool _multiTouchUntilRelease;
        bool _lowPower;

        // The avatar only changes when a tracking packet arrives (no interpolation), so idle
        // frames are paced to the tracking rate, rounded up to a rate the 60 Hz panel divides
        // evenly. A hot phone tracking at 18 Hz then renders 20 fps instead of 30, easing the GPU
        // exactly when it matters. Touch gestures get 60 fps so orbiting stays smooth; the dimmed
        // screen (nobody looking) gets a trickle that keeps the player alive for an instant wake-up.
        const int MaxIdleFps = 30;
        const int InteractiveFps = 60;
        const int LowPowerFps = 5;
        // Tracking rate (Hz) above which each paced step is needed; stepping back down waits until
        // the rate is PacingHysteresis below, so a rate hovering at a boundary doesn't flap.
        static readonly (float aboveHz, int fps)[] PacingSteps = { (21f, 30), (16f, 20), (0f, 15) };
        const float PacingHysteresis = 2f;

        TrackingReceiver _receiver;
        int _pacedFps = MaxIdleFps;
        float _trackingHz = -1f;
        long _lastPacketCount;
        float _rateWindowStart;

        // Fraction of the screen resolution the avatar is drawn at, then upscaled. Per-pixel MToon
        // work dominates the GPU cost, and on a phone screen the difference is hard to see.
        // The host lowers it further when the phone runs hot (SetRenderBudget).
        const float DefaultRenderScale = 0.7f;
        float _renderScale = DefaultRenderScale;
        int _maxIdleFps = MaxIdleFps;
        bool _outlines = true;

        // Rendered frame rate and GPU time, logged next to the host's VrmPerf line
        // (adb logcat -s Unity).
        const float PerfLogSeconds = 5f;
        float _perfWindowStart;
        int _perfFrames;
        readonly FrameTiming[] _timing = new FrameTiming[1];
        double _gpuMs, _cpuMs;
        int _timedFrames;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Bootstrap()
        {
            if (FindAnyObjectByType<PreviewApp>() != null) return;
            var go = new GameObject("VrmDroid");
            go.AddComponent<TrackingReceiver>();
            go.AddComponent<AvatarController>();
            go.AddComponent<PreviewApp>();
        }

        void Awake()
        {
            Application.targetFrameRate = MaxIdleFps;
            _receiver = GetComponent<TrackingReceiver>();
            ApplyRenderScale();
            Screen.sleepTimeout = SleepTimeout.NeverSleep;
            if (avatar == null) avatar = GetComponent<AvatarController>();
            if (viewCamera == null) viewCamera = Camera.main;
            if (viewCamera == null)
            {
                viewCamera = new GameObject("Main Camera").AddComponent<Camera>();
                viewCamera.tag = "MainCamera";
            }
            viewCamera.fieldOfView = 30f;
            viewCamera.nearClipPlane = 0.05f;
            viewCamera.clearFlags = CameraClearFlags.SolidColor;
            viewCamera.backgroundColor = background;
            avatar.AvatarLoaded += Frame;
            avatar.AvatarLoaded += _ => ApplyOutlines();
        }

        void Frame(Vrm10Instance instance)
        {
            // Place the face one third down from the top of the screen. With a level camera the
            // visible half-height at distance D is D·tan(fov/2), so aiming h/3 below the face puts
            // it at 1/2 − 1/6 = 1/3 from the top, whatever the screen's aspect ratio.
            if (instance.TryGetBoneTransform(HumanBodyBones.Head, out var head))
            {
                var face = head.position + Vector3.up * FaceAboveHeadBone;
                var halfHeight = DefaultDistance * Mathf.Tan(viewCamera.fieldOfView * 0.5f * Mathf.Deg2Rad);
                _homeFocus = face - Vector3.up * (halfHeight / 3f);
            }
            ResetView();
        }

        /// <summary>Back to the default upper-body shot (also on double tap).</summary>
        void ResetView()
        {
            _focus = _homeFocus;
            _orbitYaw = 0f;
            _orbitPitch = DefaultPitch;
            distance = DefaultDistance;
        }

        /// <summary>Called by the host app (UnitySendMessage) when the screen dims or wakes.</summary>
        public void SetLowPower(string on) => _lowPower = on == "1";

        /// <summary>
        /// Called by the host app (UnitySendMessage) with "maxFps;renderScale;outlines(0/1)" as the
        /// phone heats up or cools down, so the preview gives way to face tracking.
        /// </summary>
        public void SetRenderBudget(string budget)
        {
            var parts = budget.Split(';');
            if (parts.Length != 3 ||
                !int.TryParse(parts[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out var fps) ||
                !float.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out var scale))
            {
                Debug.LogWarning($"[VrmDroid] Bad render budget: {budget}");
                return;
            }
            _maxIdleFps = Mathf.Clamp(fps, LowPowerFps, MaxIdleFps);
            _renderScale = Mathf.Clamp(scale, 0.3f, 1f);
            _outlines = parts[2] == "1";
            ApplyRenderScale();
            ApplyOutlines();
            Debug.Log($"[VrmDroid] Render budget: {_maxIdleFps} fps, scale {_renderScale}, outlines {_outlines}");
        }

        void ApplyRenderScale()
        {
            if (GraphicsSettings.currentRenderPipeline is UniversalRenderPipelineAsset urp) urp.renderScale = _renderScale;
        }

        /// <summary>MToon draws outlines in an extra pass per material; switching it off skips those draws.</summary>
        void ApplyOutlines()
        {
            var instance = avatar != null ? avatar.Instance : null;
            if (instance == null) return;
            foreach (var renderer in instance.GetComponentsInChildren<Renderer>(true))
            {
                foreach (var material in renderer.sharedMaterials)
                {
                    if (material != null) material.SetShaderPassEnabled(OutlinePass, _outlines);
                }
            }
        }

        const string OutlinePass = "MToonOutline";

        void Update()
        {
            var touching = Touchscreen.current != null && Touchscreen.current.primaryTouch.press.isPressed;
            UpdatePacing();
            var fps = _lowPower ? LowPowerFps : touching ? InteractiveFps : Mathf.Min(_pacedFps, _maxIdleFps);
            if (Application.targetFrameRate != fps) Application.targetFrameRate = fps;

            HandleInput();
            // Avatar faces +Z, so "in front" is on the +Z side looking back toward -Z.
            var rotation = Quaternion.Euler(_orbitPitch, 180f + _orbitYaw, 0f);
            viewCamera.transform.SetPositionAndRotation(_focus - rotation * Vector3.forward * distance, rotation);
            LogFrameRate();
        }

        /// <summary>Measures the tracking packet rate once a second and picks the idle frame rate.</summary>
        void UpdatePacing()
        {
            if (_receiver == null) return;
            var elapsed = Time.unscaledTime - _rateWindowStart;
            if (elapsed < 1f) return;
            var count = _receiver.ReceivedCount;
            var rate = (count - _lastPacketCount) / elapsed;
            _lastPacketCount = count;
            _rateWindowStart = Time.unscaledTime;
            _trackingHz = _trackingHz < 0f ? rate : Mathf.Lerp(_trackingHz, rate, 0.5f);

            var want = 15;
            foreach (var (aboveHz, fps) in PacingSteps)
            {
                if (_trackingHz > aboveHz) { want = fps; break; }
            }
            if (want < _pacedFps)
            {
                // Only step down once clearly below the current step's threshold.
                foreach (var (aboveHz, fps) in PacingSteps)
                {
                    if (fps == _pacedFps && _trackingHz > aboveHz - PacingHysteresis) want = _pacedFps;
                }
            }
            _pacedFps = want;
        }

        void LogFrameRate()
        {
            _perfFrames++;
            FrameTimingManager.CaptureFrameTimings();
            if (FrameTimingManager.GetLatestTimings(1, _timing) == 1 && _timing[0].gpuFrameTime > 0)
            {
                _gpuMs += _timing[0].gpuFrameTime;
                _cpuMs += _timing[0].cpuMainThreadFrameTime;
                _timedFrames++;
            }
            var elapsed = Time.unscaledTime - _perfWindowStart;
            if (elapsed < PerfLogSeconds) return;
            var gpu = _timedFrames > 0 ? $"{_gpuMs / _timedFrames:F1}" : "-";
            var cpu = _timedFrames > 0 ? $"{_cpuMs / _timedFrames:F1}" : "-";
            Debug.Log($"[VrmPerf] unity fps={_perfFrames / elapsed:F1} target={Application.targetFrameRate} tracking={_trackingHz:F1}Hz gpu={gpu}ms main={cpu}ms scale={_renderScale} outlines={_outlines}");
            _perfFrames = 0;
            _perfWindowStart = Time.unscaledTime;
            _gpuMs = _cpuMs = 0;
            _timedFrames = 0;
        }

        void HandleInput()
        {
            // No Application.Quit() here: the player is embedded in the host app's activity, and
            // quitting would end the whole process. Back navigation is handled by Android.

            var touch = Touchscreen.current;
            if (touch != null)
            {
                // Use the first two fingers actually down (slot order isn't finger order).
                int active = 0;
                UnityEngine.InputSystem.Controls.TouchControl first = null, second = null;
                foreach (var t in touch.touches)
                {
                    if (!t.isInProgress) continue;
                    active++;
                    if (first == null) first = t; else if (second == null) second = t;
                }

                if (active >= 2)
                {
                    // Two fingers: spread = zoom, moving together = pan, both at once.
                    var a = first.position.ReadValue();
                    var b = second.position.ReadValue();
                    var d = Vector2.Distance(a, b);
                    var mid = (a + b) * 0.5f;
                    // A different pair of fingers: restart from here instead of jumping.
                    var idA = first.touchId.ReadValue();
                    var idB = second.touchId.ReadValue();
                    if (idA != _pinchIdA || idB != _pinchIdB) { _lastPinch = 0f; _pinchIdA = idA; _pinchIdB = idB; }
                    if (_lastPinch > 0f)
                    {
                        Zoom((_lastPinch - d) / Mathf.Max(Screen.dpi, 100f) * 0.5f);
                        Pan(mid - _lastMid);
                    }
                    _lastPinch = d;
                    _lastMid = mid;
                    _multiTouchUntilRelease = true;
                    return;
                }
                _lastPinch = 0f;
                if (active == 0) _multiTouchUntilRelease = false;
                if (_multiTouchUntilRelease) return;

                if (touch.primaryTouch.tap.wasPressedThisFrame && touch.primaryTouch.tapCount.ReadValue() >= 2)
                {
                    ResetView();
                    return;
                }
            }

            var pointer = Pointer.current;
            if (pointer != null && pointer.press.isPressed)
            {
                var delta = pointer.delta.ReadValue() / Mathf.Max(Screen.dpi, 100f) * 60f;
                _orbitYaw = Mathf.Clamp(_orbitYaw + delta.x, -170f, 170f);
                _orbitPitch = Mathf.Clamp(_orbitPitch - delta.y, -40f, 60f);
            }
            var mouse = Mouse.current;
            if (mouse != null)
            {
                Zoom(-mouse.scroll.ReadValue().y * 0.001f);
                if (mouse.middleButton.isPressed) Pan(mouse.delta.ReadValue()); // editor testing
            }
        }

        /// <summary>
        /// Moves the view in the camera's plane so the avatar follows the fingers. Scaled by the
        /// visible height at the current distance, so a swipe feels the same at any zoom.
        /// </summary>
        void Pan(Vector2 screenDelta)
        {
            var worldPerPixel = 2f * distance * Mathf.Tan(viewCamera.fieldOfView * 0.5f * Mathf.Deg2Rad) / Screen.height;
            var t = viewCamera.transform;
            var move = (t.right * screenDelta.x + t.up * screenDelta.y) * worldPerPixel;
            _focus -= move;
            var offset = _focus - _homeFocus;
            if (offset.magnitude > maxPan) _focus = _homeFocus + offset.normalized * maxPan;
        }

        void Zoom(float amount) => distance = Mathf.Clamp(distance + amount, minDistance, maxDistance);

    }
}
