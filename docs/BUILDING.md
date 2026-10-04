# VRMdroid 개발자 안내

앱 사용법은 [README](../README.md)를 보세요. 이 문서는 소스에서 직접 빌드하거나 코드를 고치려는 분을 위한 것입니다.

VRMdroid는 안드로이드 폰의 전면 카메라로 얼굴을 추적해서, **아이폰 페이스 트래킹(ARKit 52 블렌드셰이프) 호환 데이터**를
PC의 VSeeFace / VNyan / Warudo 등으로 보내는 앱입니다. 폰에서는 Unity(UniVRM)로 VRM 아바타를 미리 볼 수 있습니다.

```
전면 카메라 ─▶ MediaPipe Face Landmarker ─▶ FaceProcessor ─┬─▶ iFacialMocap (UDP 49983) ─▶ VSeeFace 등
 (CameraX)      52 blendshapes + head pose   스무딩·보정·미러  ├─▶ VMC 프로토콜 (OSC, UDP 39539)
                                                             └─▶ Unity 미리보기 (localhost UDP 39540)
```

## 구성

| 경로 | 내용 |
|---|---|
| `app/.../tracking` | `FaceTracker` 인터페이스와 MediaPipe 구현. ARKit 순서의 52개 값과 머리 자세를 냅니다. 카메라 프레임은 변환 없이 RGBA 버퍼 그대로 넘깁니다(`FrameConverter`). 다른 모델은 이 인터페이스를 구현해서 `FaceTrackerFactory`에 추가하면 됩니다. |
| `app/.../processing` | One Euro 필터, 정면 보정, 감도, 미러링, Unity 좌표 변환, ARKit→VRM 프리셋(A/I/U/E/O, 눈 깜빡임) 변환 |
| `app/.../output` | `IFacialMocapSender`, `VmcSender`, `PreviewSender`(Unity용), 디버그 콘솔용 `SentDataMonitor` |
| `app/.../service` | 카메라 포그라운드 서비스(`TrackingService`). Unity 화면이 앞에 있어도 트래킹이 계속됩니다. 발열 단계(`ThermalGovernor`)와 성능 로그(`PerfStats`)도 여기 있습니다. |
| `app/.../avatar` | VRM 파일 저장·검사, Unity와 주고받는 `UnityBridge` |
| `app/.../ui`, `settings` | 온보딩, PC 연결 안내, 상태·표정 조절 시트, 절전 화면, 설정 |
| `app/src/unity`, `app/src/stub` | flavor별 `UnityHost` 구현(실제 Unity 플레이어 / 빈 구현) |
| `unity/` | Unity 6 (6000.6.3f1, URP) + UniVRM 0.131.2. `Assets/VrmDroid/Scripts`에 수신기(`TrackingReceiver`), 아바타 제어(`AvatarController`), 렌더 예산(`PreviewApp`), 텍스처 축소(`TextureBudget`)가 있고, `Assets/VrmDroid/Editor`에 export 스크립트가 있습니다. |

## 준비물

- Android Studio(또는 JDK 17 이상)와 Android SDK. compileSdk는 37입니다.
- Unity 6과 **Android Build Support** 모듈(OpenJDK, Android SDK & NDK 포함). 아바타 미리보기 없이 `stub` 빌드만 할 거라면 Unity는 없어도 됩니다.
- 64비트(arm64) Android 12 이상 기기. 앱은 arm64-v8a만 빌드합니다.

## 빌드

1. **Unity 라이브러리 export** (처음 한 번, 그리고 Unity 쪽을 바꿨을 때)
   - export 결과물은 저장소에 없으므로 처음 받은 뒤에는 꼭 한 번 해야 `unity` flavor가 생깁니다.
   - Unity 에디터 메뉴 **VRMDroid > Export Android Library**, 또는 에디터를 닫은 상태에서 아래 명령을 실행합니다.
     ```bash
     "C:/Program Files/.../Unity.exe" -batchmode -quit -projectPath unity -executeMethod VrmDroid.Editor.AndroidExport.ExportFromCommandLine -logFile unity/Logs/export.log
     ```
   - export는 Player Settings(IL2CPP, ARM64, 그림자·오디오·물리 끄기 등)를 먼저 맞춘 뒤 진행합니다. 이 설정은 `AndroidExport.Configure`에 있으니 에디터에서 직접 바꾸지 마세요.
   - 결과물은 `unity/Builds/AndroidExport/unityLibrary`에 생기고, `settings.gradle.kts`가 자동으로 포함합니다.
2. **앱 빌드·설치**
   ```bash
   ./gradlew :app:installUnityDebug
   ```
   - 앱에는 두 가지 빌드 변형(flavor)이 있습니다.
     - `unity`: 아바타 미리보기 포함. Unity export가 있을 때만 생깁니다.
     - `stub`: Unity 없이 랜드마크 화면만 있는 빌드. `com.ouor.vrmdroid.stub`으로 따로 설치되며 `./gradlew :app:installStubDebug`로 빌드합니다.
   - MediaPipe 모델(`face_landmarker.task`, 버전 1 고정)과 샘플 아바타는 첫 빌드 때 `app/build/generated/mlmodel`로 내려받고 SHA-256을 검증합니다.
   - 테스트는 `./gradlew :app:testUnityDebugUnitTest`(Unity export가 없으면 `testStubDebugUnitTest`).

## 릴리스 APK 만들기

릴리스 빌드에는 서명 설정이 없어서, 빌드한 뒤 직접 정렬하고 서명합니다. 지금까지의 릴리스는 Android 디버그 키(`~/.android/debug.keystore`)로 서명했습니다. 다른 키로 서명하면 기존 설치 위에 업데이트할 수 없습니다.

1. `app/build.gradle.kts`의 `versionCode`와 `versionName`을 올립니다.
2. 빌드한 뒤 정렬하고 서명합니다. `zipalign`과 `apksigner`는 Android SDK의 `build-tools/<버전>/`에 있습니다.
   ```bash
   ./gradlew :app:assembleUnityRelease
   ```
   ```bash
   zipalign -f -p 4 app/build/outputs/apk/unity/release/app-unity-release-unsigned.apk VRMdroid-aligned.apk
   ```
   ```bash
   apksigner sign --ks ~/.android/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --out VRMdroid-vX.Y-arm64-v8a.apk VRMdroid-aligned.apk
   ```
3. `apksigner verify`로 확인한 뒤 GitHub Releases에 올립니다.

## 성능 확인 (디버그 빌드)

- `adb logcat -s VrmPerf Unity`로 5초마다 추적 속도, 변환·추론 시간(p50/p95), 발열 상태, Unity fps·GPU 시간을 볼 수 있습니다.
- 앱을 다시 띄우면서 바로 트래킹을 시작하려면(디버그 빌드만):
  ```bash
  adb shell am start -S -n com.ouor.vrmdroid/.MainActivity --ez start_tracking true
  ```
- 발열 단계를 강제로 정하려면 디버그 빌드의 SharedPreferences에 `debug_thermal_level`을 `WARM` 또는 `HOT`으로 넣습니다.

## 샘플 아바타
처음 실행할 때 내 VRM이 없으면 샘플 아바타 *Sendagaya Shibu*(VRoid Studio 샘플, CC0)를 보여 줍니다.
파일은 저장소에 넣지 않고 빌드할 때 [madjin/vrm-samples](https://github.com/madjin/vrm-samples)에서 내려받아 SHA-256으로 확인합니다.
메인 화면의 "내 아바타로 바꾸기"로 언제든 바꿀 수 있습니다.

## 알려진 한계
- MediaPipe에는 `tongueOut`이 없어 항상 0입니다.
- 눈동자 방향은 eyeLook* 블렌드셰이프에서 추정합니다(최대 ±25°).
- VMC에서는 머리 이동을 root 위치(`/VMC/Ext/Root/Pos`)로 보냅니다. VSeeFace가 하체 트래킹을 직접 하도록 설정돼 있으면 root 위치는 적용되지 않습니다.

## 라이선스
[MIT](../LICENSE)
