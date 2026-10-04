using System;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using UnityEditor;
using UnityEditor.Android;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEngine;
using UnityEngine.Rendering.Universal;

namespace VrmDroid.Editor
{
    /// <summary>
    /// Exports this project as the `unityLibrary` Gradle module consumed by the host app.
    ///
    /// Menu: VRMDroid > Export Android Library
    /// CLI:  Unity.exe -batchmode -quit -projectPath unity -executeMethod VrmDroid.Editor.AndroidExport.ExportFromCommandLine
    /// </summary>
    public static class AndroidExport
    {
        /// <summary>Relative to the Unity project; settings.gradle.kts points here.</summary>
        public const string ExportDir = "Builds/AndroidExport";

        static readonly string[] RuntimeShaders =
        {
            "VRM10/Universal Render Pipeline/MToon10",
            "UniGLTF/UniUnlit",
            "Universal Render Pipeline/Lit",
            "Universal Render Pipeline/Unlit",
        };

        [MenuItem("VRMDroid/Configure Player Settings")]
        public static void Configure()
        {
            if (EditorUserBuildSettings.activeBuildTarget != BuildTarget.Android)
                EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.Android, BuildTarget.Android);

            PlayerSettings.companyName = "ouor";
            PlayerSettings.productName = "VRMDroid Preview";
            PlayerSettings.SetApplicationIdentifier(NamedBuildTarget.Android, "com.ouor.vrmdroid");
            PlayerSettings.SetScriptingBackend(NamedBuildTarget.Android, ScriptingImplementation.IL2CPP);
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
            PlayerSettings.Android.minSdkVersion = (AndroidSdkVersions)31;
            PlayerSettings.Android.applicationEntry = AndroidApplicationEntry.Activity;
            // VRMs load their normal maps at runtime as plain RGB(A) textures. DXT5nm-style decoding
            // would read alpha as the X component and bend every normal sideways.
            PlayerSettings.SetNormalMapEncoding(NamedBuildTarget.Android, NormalMapEncoding.XYZ);
            // The host activity owns orientation and system bars. AutoRotation leaves the
            // activity's requested orientation alone (stays UNSPECIFIED, so rotation lock works).
            // The player applies its inset request when it starts; match what MainActivity asks
            // for (status bar hidden, navigation bar shown) so the two never fight.
            PlayerSettings.defaultInterfaceOrientation = UIOrientation.AutoRotation;
            PlayerSettings.Android.requestedVisibleInsets = AndroidWindowInsetsType.NavigationBars;
            PlayerSettings.runInBackground = true;
            // Even frame pacing, and lets the panel drop from 120 Hz to the rate we render at.
            PlayerSettings.Android.optimizedFramePacing = true;

            TrimRuntimeCost();
            IncludeRuntimeShaders();
            AddOutlineFeatureToRenderers();
            AssetDatabase.SaveAssets();
            Debug.Log("[VrmDroid] Player settings configured for Android library export.");
        }

        [MenuItem("VRMDroid/Export Android Library")]
        public static void Export()
        {
            Configure();
            EditorUserBuildSettings.exportAsGoogleAndroidProject = true;
            EditorUserBuildSettings.buildAppBundle = false;

            var scenes = EditorBuildSettings.scenes.Where(s => s.enabled).Select(s => s.path).ToArray();
            var report = BuildPipeline.BuildPlayer(new BuildPlayerOptions
            {
                scenes = scenes,
                locationPathName = ExportDir,
                target = BuildTarget.Android,
                targetGroup = BuildTargetGroup.Android,
                options = BuildOptions.None,
            });
            if (report.summary.result != BuildResult.Succeeded)
                throw new BuildFailedException($"Android export failed: {report.summary.result}");
            Debug.Log($"[VrmDroid] Exported to {Path.GetFullPath(ExportDir)}");
        }

        public static void ExportFromCommandLine()
        {
            try
            {
                Export();
                EditorApplication.Exit(0);
            }
            catch (Exception e)
            {
                Debug.LogException(e);
                EditorApplication.Exit(1);
            }
        }

        /// <summary>
        /// The player shares the phone with the camera and face tracking, so it skips everything the
        /// preview doesn't show: sound, physics (no rigidbodies; spring bones run their own solver),
        /// shadows and per-frame volume updates (post-processing is off).
        /// </summary>
        static void TrimRuntimeCost()
        {
            var audio = new SerializedObject(AssetDatabase.LoadAssetAtPath<UnityEngine.Object>("ProjectSettings/AudioManager.asset"));
            audio.FindProperty("m_DisableAudio").boolValue = true;
            audio.ApplyModifiedPropertiesWithoutUndo();

            Physics.simulationMode = SimulationMode.Script;
            Physics2D.simulationMode = SimulationMode2D.Script;

            foreach (var guid in AssetDatabase.FindAssets("t:UniversalRenderPipelineAsset"))
            {
                var path = AssetDatabase.GUIDToAssetPath(guid);
                if (!path.StartsWith("Assets/")) continue;
                var so = new SerializedObject(AssetDatabase.LoadAssetAtPath<UniversalRenderPipelineAsset>(path));
                so.FindProperty("m_MainLightShadowsSupported").boolValue = false;
                so.FindProperty("m_AdditionalLightShadowsSupported").boolValue = false;
                // 1 = via scripting: volumes are only evaluated when asked to, which is never.
                so.FindProperty("m_VolumeFrameworkUpdateMode").intValue = 1;
                so.ApplyModifiedPropertiesWithoutUndo();
            }
        }

        static void IncludeRuntimeShaders()
        {
            var graphics = AssetDatabase.LoadAssetAtPath<UnityEngine.Object>("ProjectSettings/GraphicsSettings.asset");
            var so = new SerializedObject(graphics);
            var list = so.FindProperty("m_AlwaysIncludedShaders");
            foreach (var name in RuntimeShaders)
            {
                var shader = Shader.Find(name);
                if (shader == null)
                {
                    Debug.LogWarning($"[VrmDroid] Shader not found: {name}");
                    continue;
                }
                var present = Enumerable.Range(0, list.arraySize)
                    .Any(i => list.GetArrayElementAtIndex(i).objectReferenceValue == shader);
                if (present) continue;
                list.InsertArrayElementAtIndex(list.arraySize);
                list.GetArrayElementAtIndex(list.arraySize - 1).objectReferenceValue = shader;
            }
            so.ApplyModifiedPropertiesWithoutUndo();
        }

        /// <summary>MToon outlines in URP need this renderer feature.</summary>
        static void AddOutlineFeatureToRenderers()
        {
            // VRM10.MToon10 is not auto-referenced, so resolve the feature type by name.
            var featureType = Type.GetType("VRM10.MToon10.MToonOutlineRenderFeature, VRM10.MToon10");
            if (featureType == null)
            {
                Debug.LogWarning("[VrmDroid] MToonOutlineRenderFeature not found; outlines disabled.");
                return;
            }
            foreach (var guid in AssetDatabase.FindAssets("t:UniversalRendererData"))
            {
                var path = AssetDatabase.GUIDToAssetPath(guid);
                if (!path.StartsWith("Assets/")) continue;
                var data = AssetDatabase.LoadAssetAtPath<UniversalRendererData>(path);
                if (data == null || data.rendererFeatures.Any(f => f != null && f.GetType() == featureType)) continue;

                var feature = (ScriptableRendererFeature)ScriptableObject.CreateInstance(featureType);
                feature.name = featureType.Name;
                AssetDatabase.AddObjectToAsset(feature, data);
                data.rendererFeatures.Add(feature);

                // rendererFeatureMap mirrors rendererFeatures with local file IDs.
                var so = new SerializedObject(data);
                var map = so.FindProperty("m_RendererFeatureMap");
                if (map != null && AssetDatabase.TryGetGUIDAndLocalFileIdentifier(feature, out _, out long localId))
                {
                    map.InsertArrayElementAtIndex(map.arraySize);
                    map.GetArrayElementAtIndex(map.arraySize - 1).longValue = localId;
                    so.ApplyModifiedPropertiesWithoutUndo();
                }
                data.SetDirty();
                EditorUtility.SetDirty(data);
                Debug.Log($"[VrmDroid] Added MToon outline feature to {path}");
            }
        }
    }

    /// <summary>
    /// Adapts the generated unityLibrary for embedding: the host app shows the player inside its
    /// own activity, so the library must not add a second launcher icon.
    /// </summary>
    public sealed class UnityLibraryPostprocessor : IPostGenerateGradleAndroidProject
    {
        public int callbackOrder => 100;

        public void OnPostGenerateGradleAndroidProject(string path)
        {
            var manifestPath = Path.Combine(path, "src", "main", "AndroidManifest.xml");
            if (!File.Exists(manifestPath)) return;
            var xml = File.ReadAllText(manifestPath);

            xml = Regex.Replace(xml,
                @"<intent-filter>\s*<category android:name=""android.intent.category.LAUNCHER""\s*/>\s*<action android:name=""android.intent.action.MAIN""\s*/>\s*</intent-filter>|" +
                @"<intent-filter>\s*<action android:name=""android.intent.action.MAIN""\s*/>\s*<category android:name=""android.intent.category.LAUNCHER""\s*/>\s*</intent-filter>",
                "");
            File.WriteAllText(manifestPath, xml);
        }
    }
}
