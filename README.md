# FaceControl - Android Face Gesture Assistant 🚀

[中文版](./README_CN.md) | English

**FaceControl** is an innovative Android open-source application that leverages computer vision to enable users to control their devices through simple facial gestures such as blinking, head tilting, and mouth movements.

This project aims to provide a new way of interaction for people with disabilities, or to make device operation accessible when manual handling is inconvenient (e.g., washing hands, cooking).

---

## ✨ Key Features

- **Real-time Face Tracking**: Powered by **Google MediaPipe Face Landmarker**, supporting 468 3D facial landmarks detection in real-time.
- **Continuous Background Monitoring**: Combines **CameraX** with a **Foreground Service**, ensuring the app responds even when in the background or when the screen is locked.
- **Global Gesture Simulation**: Utilizes the **Accessibility Service API** to simulate swipe, click, and long-press gestures across any third-party apps (e.g., TikTok, Browser) without requiring Root access.
- **Anti-Mistouch Logic**: Built-in EAR (Eye Aspect Ratio) state machine with support for multiple blink detection modes to distinguish between natural blinks and control commands.

---

## 🛠️ Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Vision Computing**: [MediaPipe Tasks Vision](https://developers.google.com/mediapipe)
- **Camera Support**: CameraX (Camera2)
- **System Interface**: Android Accessibility Service API
- **Background Architecture**: Lifecycle-aware Foreground Service

---

## 📸 Gesture Mapping

### Portrait Mode (Scrolling / Browsing)

| Facial Gesture | Simulated Action | Use Case |
| :--- | :--- | :--- |
| **Head Down** (look down) | Swipe Down (top to bottom) | Scroll down / Previous page |
| **Head Up** (look up) | Swipe Up (bottom to top) | Scrolling TikTok / Next page |
| **Long Blink** (~1.5s eye closure) | Single click at screen center | Confirm / Select / Pause |
| **Double Blink** | Swipe Up (bottom to top) | Scrolling TikTok / Next page |
| **Head Shake Left** | Swipe Left (right to left) | Back / Exit / Previous item |
| **Head Shake Right** | Swipe Right (left to right) | Forward / Next item |
| **Mouth Open** | Long press at screen center | Trigger long-press menu / Speed up |
| **Mouth Close** | Release long press | Release long-press action |

### Landscape Mode (Video Playback / Fullscreen)

| Facial Gesture | Simulated Action | Use Case |
| :--- | :--- | :--- |
| **Head Down** (look down) | Swipe Down | Volume / Brightness down |
| **Head Up** (look up) | Swipe Up | Volume / Brightness up |
| **Long Blink** (~1.5s eye closure) | Single click at screen center | Play / Pause |
| **Double Blink** | Swipe Right (fast forward) | Video fast forward |
| **Mouth Open** | Long press at screen center | Trigger player menu / Speed control |
| **Mouth Close** | Release long press | Release long-press action |

---

## 🚀 Quick Start

### Requirements
- Android Device (Running Android 8.0+ / API 26+)
- Physical device recommended for testing (Emulators have limited camera support)

### 1. Clone and Build

```bash
git clone https://github.com/your-id/FaceControl-Android.git
cd FaceControl-Android
# Open with Android Studio, sync Gradle, then Run
```

### 2. Grant Permissions

After installation, manually grant the following permissions in system settings:

1. **Camera** – for real-time facial capture
2. **Display over other apps** (System Alert Window) – for foreground service overlay
3. **Accessibility Service** – search for "FaceControl Service" and enable it, allowing the app to simulate global gestures

### 3. Start Using

- Tap "Start Face Control" in the app to activate the camera and gesture detection
- The notification bar will show "FaceControl is active" when running
- Perform facial gestures to control your device hands-free

---

## 📁 Project Structure

```
app/
├── src/main/java/org/npu/face_control/
│   ├── MainActivity.kt                     # Compose main UI and permission management
│   ├── FaceAnalyzer.kt                     # MediaPipe face analysis engine (core algorithm)
│   ├── FaceMath.kt                         # Pure math utilities (EAR, distance calculation)
│   ├── FaceControlForegroundService.kt     # Foreground service + CameraX pipeline
│   ├── FaceAccessibilityService.kt         # Gesture execution via Accessibility API
│   └── ui/theme/                           # Compose theme (Color, Theme, Typography)
└── src/main/assets/
    └── face_landmarker.task                # MediaPipe face landmarker model file
```

---

## ⚙️ Face Gesture Detection Algorithm

### Eye Blink Detection
Uses EAR (Eye Aspect Ratio) computed from 6 eye landmarks per eye. When the ratio drops below `0.2`, the eye is considered closed. Blink duration determines the action:
- **< 180ms** → Physiological blink (participates in double-blink detection)
- **180–350ms** → Ignored (anti-mistouch zone)
- **1200–2000ms** (~1.5s) → Triggered as a deliberate long blink

### Head Up/Down Detection
Calculates the ratio of nose position relative to total face height (forehead to chin):
- Ratio > `0.6` → Head Down
- Ratio < `0.35` → Head Up

### Head Shake Detection
Computes the horizontal ratio of nose position relative to face width:
- Ratio > `0.75` → Head turned left
- Ratio < `0.25` → Head turned right

### Mouth Open/Close Detection
Uses MAR (Mouth Aspect Ratio) calculated from upper/lower lip distance and mouth width. When ratio exceeds `0.5`, the mouth is considered open.

---

## 🤝 Contributing

Pull requests and suggestions are welcome! Feel free to open an issue to discuss new features or improvements.

---

## 📄 License

This project is open source and available for learning and research purposes.
