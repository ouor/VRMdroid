using UnityEngine;
using UnityEngine.InputSystem;
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
        GUIStyle _style;

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
            Application.targetFrameRate = 60;
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

        void Update()
        {
            HandleInput();
            // Avatar faces +Z, so "in front" is on the +Z side looking back toward -Z.
            var rotation = Quaternion.Euler(_orbitPitch, 180f + _orbitYaw, 0f);
            viewCamera.transform.SetPositionAndRotation(_focus - rotation * Vector3.forward * distance, rotation);
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

        void OnGUI()
        {
            // The host app shows the empty state and live status; only loading/errors come from here.
            var status = avatar.Status;
            if (string.IsNullOrEmpty(status)) return;
            _style ??= new GUIStyle(GUI.skin.label)
            {
                fontSize = Mathf.RoundToInt(Mathf.Max(Screen.dpi, 160f) / 160f * 15f),
                wordWrap = true,
                alignment = TextAnchor.MiddleCenter,
                normal = { textColor = new Color(0.66f, 0.64f, 0.72f) },
            };
            GUI.Label(new Rect(32, Screen.height * 0.5f - 100, Screen.width - 64, 200), status, _style);
        }
    }
}
