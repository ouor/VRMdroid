<div align="center">

<img src="images/icon.png" width="112" alt="VRMdroid icon: a fox face, half tracking mesh and half mask">

# VRMdroid

[한국어](../README.md) · **English** · [日本語](README.ja.md) · [中文](README.zh.md)

**VTuber face tracking with your Android phone, no iPhone needed**

<!-- Demo GIF: making faces in front of the phone while the avatar on the PC copies them -->

### [Latest release (APK)](https://github.com/ouor/VRMdroid/releases/latest)

</div>

> [!NOTE]
> **Your camera feed never leaves your phone.** <br/>
> All face tracking happens on the phone itself, and only expression values are sent to your PC.
> The app doesn't connect to any server on the internet.

## 📸 How it works

1. **Pick an avatar**: Choose a VRM file on your phone. Don't have one yet? You can try the app with the sample avatar first.
2. **Pick your PC program**: Choose the program you use (VSeeFace, VNyan or Warudo), and the app shows you which menus to click on your PC and which address to enter.
3. **Start**: Put your phone in front of your face and tap **Start**. Your avatar on the PC will copy your expressions.

<div align="center">
<img src="images/screen-2.jpg" width="30%" alt="Connect to PC: VSeeFace connection steps and the phone's address">
<img src="images/screen-1.jpg" width="30%" alt="Main screen: the avatar on the phone copying the user's expressions">
<img src="images/screen-3.jpg" width="30%" alt="Expressions: sliders for Blinking, Mouth movement and Smoothing">
</div>
<div align="center"><sub>Connect to PC · Main screen · Expressions</sub></div>

## ✨ What it can do

- **The same expressions as an iPhone**: Sends the same 52 expressions as iPhone tracking, so avatars already set up for iPhone work as they are.
- **Preview on your phone**: The avatar copies your expressions on the phone screen too, so you can check that tracking works well before connecting to your PC.
- **Connection guide**: Pick your PC program and the app walks you through each step, then tells you right away once it's connected.
- **Expressions**: Tune blink and mouth-open sensitivity and how smooth the movement is, while watching your avatar.
- **Recenter**: Look at the screen for 3 seconds and your current pose is set as facing forward.
- **Live debug console**: See the expression values sent to your PC and the send rate right on the main screen. Turn it on in Settings.
- **Clean view**: Hides all the buttons so you can record or show your phone screen as is.
- **Leave it running**: When you step away, the app dims the screen and lowers the brightness to save battery. Tracking and sending to your PC keep going the whole time.

### Great for when

- You don't have an iPhone but want iPhone-quality face tracking in VSeeFace or Warudo
- You don't have a webcam, or webcam tracking can't keep up with your blinks and mouth shapes
- Your PC is already busy just streaming. The phone does the face tracking, and the PC only moves the avatar with the values it receives.
- You want to move your VRM avatar on your phone, without a PC

## 🖥️ PC programs that work with it

| Program | What to pick in the app | What to do in the PC program |
|---|---|---|
| VSeeFace | VSeeFace | In Settings → General settings, turn on the iFacialMocap receiver and enter the phone's address |
| VNyan, Warudo, etc. | VNyan, Warudo, and more | Choose iPhone / iFacialMocap as the tracking method and enter the phone's address |
| VMagicMirror and other programs that receive VMC | Apps that receive VMC | Turn on VMC receiving, set the port to 39539, then enter the PC's address in the app |

Your phone and PC need to be on the **same Wi-Fi**. Menu names may differ slightly depending on the program version.

## ⬇️ Installing

1. Download the `.apk` file from the [latest release](https://github.com/ouor/VRMdroid/releases/latest).
2. Open the downloaded file. If you see an "Install unknown apps" prompt, tap **Settings** and allow this source (your browser or file manager).
3. If a Play Protect warning appears, tap **More details → Install anyway**. It only shows up because the app doesn't come from the Play Store.

**To update**, just install the new APK over the old one. Your settings and avatar stay as they are.

### Requirements

- An Android 12 or later, 64-bit (arm64) phone
- The same Wi-Fi as your PC
- App languages: Korean, English, Japanese and Simplified Chinese. The app follows your phone's language and shows English for any other language. On Android 13 or later, you can also pick it separately under "App languages" in Settings.
- On a Dimensity 8300 phone, it sends expressions 25 to 30 times per second. This varies with your phone's performance and temperature.

## 🔒 Privacy

- Your camera feed is **never saved or sent anywhere.** The app only uses how your face moved.
- The only things sent to your PC are **expression values and head angles**, and they only go to the PC you choose.
- The app uses two permissions:
  - **Camera**: to read your expressions
  - **Internet**: to send expression values to the PC on the same Wi-Fi
- No ads, no analytics, no account sign-in.

## 🙋 FAQ

| If this happens | Try this |
|---|---|
| It won't install | Check that "Install unknown apps" is allowed. The app can't be installed on Android 11 or earlier, or on 32-bit phones. |
| The avatar on my PC doesn't move | Check that your phone and PC are on the same Wi-Fi. Guest and public Wi-Fi networks often block devices from talking to each other. |
| The PC program can't find my phone | Check that you allowed "Private networks" in the Windows Firewall prompt that appeared the first time you opened the PC program. If you already denied it, you can allow it in Windows Security → Firewall & network protection. |
| I want to see the connection status | Tap the status indicator at the top of the app to see what it's waiting for and your phone's address. |
| The avatar isn't facing forward | Put your phone in front of your face and tap **Recenter**. |
| My phone is getting hot | For long streams, we recommend plugging in the charger and taking the phone out of its case. |

If the problem continues, please open an [issue](https://github.com/ouor/VRMdroid/issues) with your phone model and PC program.

---

## 🛠️ Developer notes

This part is for anyone who wants to build or modify the app themselves.

- How to build and how it's structured: [BUILDING.md](BUILDING.md) (Korean)
- Tech used
  - App: Kotlin, CameraX
  - Face tracking: MediaPipe Face Landmarker
  - Avatar preview: Unity 6 (UniVRM)
  - Sending: iFacialMocap, VMC protocol
- License: [MIT](../LICENSE). The sample avatar *Sendagaya Shibu* is a VRoid Studio sample model (CC0).
