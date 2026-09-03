# FaceControl - Android Face Gesture Assistant 🚀

[中文版](./README_CN.md) | English

**FaceControl** is an open-source Android app that lets users control their device entirely through facial gestures — blinking, head tilting, and mouth movements — using computer vision.

It is designed for people with limited mobility, or for any situation where hands-free operation is useful (cooking, washing hands, etc.).

---

## ✨ Key Features

- **Real-time Face Tracking**: Powered by **Google MediaPipe Face Landmarker**, detecting 468 3D facial landmarks in real time.
- **Continuous Background Monitoring**: Combines **CameraX** with a **Foreground Service**, so the app keeps responding even when it is in the background or the screen is locked.
- **Global Gesture Simulation**: Uses the **Accessibility Service API** to simulate swipe, click, and long-press gestures in any app — no Root required.
- **Crosshair Mode (custom click point)**: Enter a crosshair mode with a double blink, move the cursor by tilting your head, and pick the exact spot for click and long-press actions. The app automatically detects interactive elements near the cursor and highlights them.
- **Smart Click Detection**: Click and long-press actions prefer the actual clickable element under the cursor, then fall back to raw coordinate gestures.
- **Anti-Mistouch Logic**: An EAR (Eye Aspect Ratio) state machine with an adaptive baseline distinguishes natural blinks from deliberate commands.

---

## 🛠️ Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Vision**: [MediaPipe Tasks Vision](https://developers.google.com/mediapipe)
- **Camera**: CameraX (Camera2)
- **System Interface**: Android Accessibility Service API
- **Background Architecture**: Lifecycle-aware Foreground Service

---

## 📸 Gesture Mapping

### Portrait Mode (scrolling / browsing)

| Facial Gesture | Simulated Action | Typical Use |
| :--- | :--- | :--- |
| **Head Down** (look down) | Swipe down | Scroll down / refresh |
| **Head Up** (look up) | Swipe up | Scroll up / next page |
| **Shake Left** | Swipe left | Back / previous item |
| **Shake Right** | Swipe right | Forward / next item |
| **Long Blink** (~0.8s) | Click at the selected point | Confirm / select / pause |
| **Mouth Open** | Long-press at the selected point | Long-press menu / speed control |
| **Mouth Close** | Release | Release the long-press |
| **Double Blink** | Toggle crosshair mode | Enter / exit crosshair mode |

### Landscape Mode (video playback / fullscreen)

| Facial Gesture | Simulated Action | Typical Use |
| :--- | :--- | :--- |
| **Head Down** (look down) | Swipe down | Volume / brightness down |
| **Head Up** (look up) | Swipe up | Volume / brightness up |
| **Long Blink** (~0.8s) | Click at the selected point | Play / pause |
| **Mouth Open** | Long-press at the selected point | Player menu / speed control |
| **Mouth Close** | Release | Release the long-press |
| **Double Blink** | Toggle crosshair mode | Enter / exit crosshair mode |

> In landscape mode, head-shake gestures are currently not mapped.

---

## 🎯 Crosshair Mode

Double-blink to enter crosshair mode. A crosshair cursor appears, and you move it by tilting your head in the direction you want it to go (up / down / left / right).

- The cursor stops when you return your head to a neutral position.
- **Green frame** = where a **click** (long blink) will land.
- **Orange frame** = where a **long-press** (mouth open) will land.

The frames highlight the actual interactive element detected under the cursor, so you always see exactly what will be pressed. Double-blink again to confirm the position and exit. After that, click and long-press actions use the position you selected (or the screen center, if you never set one).

---

## 🚀 Quick Start

### Requirements

- Android 8.0+ (API 26+)
- A physical device is recommended (emulators have limited camera support)

### 1. Build

Open the project in Android Studio, sync Gradle, and run.

### 2. Grant Permissions

Manually enable the following in system settings:

1. **Camera** — for real-time face capture
2. **Display over other apps** (system alert window) — for the crosshair overlay
3. **Accessibility Service** — find "FaceControl Service" and enable it, so the app can simulate global gestures and detect interactive elements

> ⚠️ After changing the accessibility service configuration, you may need to toggle the service off and on (or reinstall the app) for the new configuration to take effect.

### 3. Start Using

- Tap "START FACECONTROL" to activate the camera and gesture detection
- A "FaceControl is active" notification indicates the service is running
- Double-blink to enter crosshair mode, tilt your head to position the cursor, and double-blink again to confirm

---

## 📁 Project Structure

```
app/
└── src/main/
    ├── java/org/npu/face_control/
    │   ├── MainActivity.kt                     # Compose UI + permission management
    │   ├── FaceAnalyzer.kt                     # MediaPipe face analysis engine (core algorithm)
    │   ├── FaceMath.kt                         # Pure math utilities (EAR, distance)
    │   ├── FaceControlForegroundService.kt     # Foreground service + CameraX pipeline + gesture dispatch
    │   ├── FaceAccessibilityService.kt         # Gesture execution + interactive-element detection
    │   ├── CrosshairOverlayView.kt             # Crosshair overlay + target highlighting
    │   └── ui/theme/                           # Compose theme (Color, Theme, Typography)
    └── assets/
        └── face_landmarker.task                # MediaPipe face landmarker model
```

---

## ⚙️ Detection Algorithm

### Blink Detection

Uses EAR (Eye Aspect Ratio) computed from 6 landmarks per eye. The eye is considered closed when EAR drops below a fraction of an adaptive baseline. Blink duration determines the action:

- **< 300ms** → natural blink (participates in double-blink detection)
- **300–800ms** → ignored (anti-mistouch zone)
- **≥ 800ms** → deliberate long blink (click)

A double blink is two short blinks with a gap of roughly 80–700ms.

### Head Direction

Head direction is derived from the nose position relative to face width (horizontal) and face height (vertical). In crosshair mode, a narrower dead zone is used so small head tilts are enough to move the cursor.

### Mouth Open / Close

Uses MAR (Mouth Aspect Ratio). The mouth is considered open when the ratio between lip distance and mouth width exceeds a threshold.

---

## 🤝 Contributing

Pull requests and suggestions are welcome! Feel free to open an issue to discuss new features or improvements.

---

## 📄 License

This project is open source and available for learning and research purposes.
