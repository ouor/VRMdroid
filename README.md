# VRMDroid

안드로이드 폰의 전면 카메라로 얼굴을 추적해서, **아이폰 페이스 트래킹(ARKit 52 블렌드셰이프) 호환 데이터**를
PC의 VSeeFace / VNyan / Warudo 등으로 보내는 앱입니다. 폰에서는 Unity(UniVRM)로 VRM 아바타를 미리 볼 수 있습니다.

```
전면 카메라 ─▶ MediaPipe Face Landmarker ─▶ FaceProcessor ─┬─▶ iFacialMocap (UDP 49983) ─▶ VSeeFace 등
 (CameraX)      52 blendshapes + head pose   스무딩·보정·미러  ├─▶ VMC 프로토콜 (OSC, UDP 39539)
                                                             └─▶ Unity 미리보기 (localhost UDP 39540)
```

## 구성

| 경로 | 내용 |
|---|---|
| `app/.../tracking` | `FaceTracker` 인터페이스와 MediaPipe 구현. ARKit 순서의 52개 값과 머리 자세를 냅니다. 다른 모델은 이 인터페이스를 구현해서 `FaceTrackerFactory`에 추가하면 됩니다. |
| `app/.../processing` | One Euro 필터, 정면 보정, 감도, 미러링, Unity 좌표 변환, ARKit→VRM 프리셋(A/I/U/E/O, 눈 깜빡임) 변환 |
| `app/.../output` | `IFacialMocapSender`, `VmcSender`, `PreviewSender`(Unity용) |
| `app/.../service` | 카메라 포그라운드 서비스. Unity 화면이 앞에 있어도 트래킹이 계속됩니다. |
| `unity/` | Unity 6 (6000.6.3f1, URP) + UniVRM 0.131. `Assets/VrmDroid`에 수신기·아바타 제어·export 스크립트가 있습니다. |

## 빌드

0. **UniVRM 설치** (처음 한 번)
   - UniVRM은 저장소에 포함하지 않습니다. [UniVRM v0.131.2 릴리스](https://github.com/vrm-c/UniVRM/releases/tag/v0.131.2)에서 받아
     `unity/Packages/com.vrmc.gltf`, `unity/Packages/com.vrmc.vrm`에 embedded 패키지로 넣어 주세요
     (`packages-lock.json`이 이 두 폴더를 `file:` 경로로 참조합니다).
1. **Unity 라이브러리 export** (Unity 코드를 바꿨을 때만 필요)
   - Unity 에디터 메뉴 **VRMDroid > Export Android Library**, 또는 에디터를 닫은 상태에서 아래 명령을 실행합니다.
     ```bash
     "C:/Program Files/Unity/Hub/Editor/6000.6.3f1/Editor/Unity.exe" -batchmode -quit -projectPath unity -executeMethod VrmDroid.Editor.AndroidExport.ExportFromCommandLine -logFile unity/Logs/export.log
     ```
   - 결과물은 `unity/Builds/AndroidExport/unityLibrary`에 생기고, `settings.gradle.kts`가 자동으로 포함합니다.
     이 폴더가 없으면 앱은 Unity 없이(랜드마크 화면만) 빌드됩니다.
   - Unity export가 있어도 `-Pvrmdroid.unity=false`를 주면 Unity 없는 빌드를 만들 수 있습니다
     (두 가지 소스셋 `app/src/unity`, `app/src/nounity`가 모두 컴파일되는지 확인할 때 사용).
2. **앱 빌드·설치**
   ```bash
   ./gradlew :app:installDebug
   ```
   MediaPipe 모델(`face_landmarker.task`, 버전 1 고정)은 첫 빌드 때 `app/build/generated/mlmodel`로 내려받고
   SHA-256을 검증합니다. 테스트는 `./gradlew :app:testDebugUnitTest`.

## 사용법

처음 실행하면 단계별 안내가 나옵니다: 환영 → 카메라 허용 → 아바타(VRM) 고르기 → 어디서 쓸지(PC / 폰만) → 준비 완료.
끝나면 메인 화면에서 **정면 맞추기**가 자동으로 진행됩니다.

### 메인 화면
- 아바타가 화면 전체에 보이고, 위쪽 **상태 알약**이 지금 상태를 한 줄로 알려 줍니다 (🟢 PC 연결됨 / 🟡 PC 연결을 기다려요 / 🔴 얼굴이 안 보여요). 누르면 자세한 상태, 폰 주소(복사), **PC와 연결하기**가 나옵니다.
- 아래 큰 버튼 하나로 **시작하기 / 멈추기**. 그 위에 **정면 맞추기**(3초 가이드), **표정 조절**(아바타를 보면서 감도 조절), **아바타 바꾸기**.
- 오른쪽 위 눈 아이콘 = **화면 깨끗하게**(버튼을 모두 숨김, 화면을 탭하면 다시 나옴).
- 아바타는 드래그로 회전, 두 손가락으로 확대/축소.

### PC와 연결하기 (상태 알약 → PC와 연결하기, 또는 설정)
프로그램을 고르면 그에 맞는 순서와 폰 주소(복사 버튼)가 나오고, 연결되는 순간 "연결됐어요"로 바뀝니다.
- **VSeeFace / VNyan / Warudo (아이폰 트래킹 방식, 기본)**: PC 프로그램의 iFacialMocap 받기를 켜고 폰 주소를 입력합니다. PC가 연결하면 앱이 PC 주소를 저절로 알아냅니다.
- **VMC 방식**: PC 프로그램의 VMC 받기를 켜고(포트 39539), 앱에 PC 주소를 입력합니다. 머리·목·눈 회전, 표정(기본 표정 + 퍼펙트 싱크), 머리 위치(root)를 보냅니다.

### 설정
PC 연결 / 내 표정 / 화면 / 고급(접힘) 순서입니다. 고급에는 현재 보내는 방식에 해당하는 항목만 나옵니다
(고개 방향 반대로, 포트, 보내는 횟수, VMC 세부 항목, 그래픽 가속). 맨 아래 **기본값으로 되돌리기**.

## VRoid Studio에 대해
VRoid Studio는 모델을 만드는 도구이고 외부 트래킹 입력을 받지 않습니다.
VRoid로 만든 VRM을 VSeeFace 등에 불러온 뒤 이 앱의 데이터를 받으면 됩니다.

## 알려진 한계
- MediaPipe에는 `tongueOut`이 없어 항상 0입니다.
- 눈동자 방향은 eyeLook* 블렌드셰이프에서 추정합니다(최대 ±25°).
- VMC에서는 머리 이동을 root 위치(`/VMC/Ext/Root/Pos`)로 보냅니다. VSeeFace가 하체 트래킹을 직접 하도록 설정돼 있으면 root 위치는 적용되지 않습니다.
