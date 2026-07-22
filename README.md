# FaceControl - Android Face Gesture Assistant 🚀

[中文版](./README_CN.md) | English

**FaceControl** is an innovative Android open-source application that leverages computer vision to enable users to control their devices through simple facial gestures such as blinking, nodding, and shaking.

This project aims to provide a new way of interaction for people with disabilities, or to make device operation accessible when manual handling is inconvenient (e.g., washing hands, cooking).

---

## ✨ Key Features

- **Real-time Face Tracking**: Powered by **Google MediaPipe Face Mesh**, supporting 468 3D facial landmarks detection in real-time.
- **Continuous Background Monitoring**: Combines **CameraX** with a **Foreground Service**, ensuring the app responds even when in the background or when the screen is locked.
- **Global Gesture Simulation**: Utilizes the **Accessibility Service API** to simulate swipe and click gestures across any third-party apps (e.g., TikTok, Browser) without requiring Root access.
- **Anti-Mistouch Logic**: Built-in EAR (Eye Aspect Ratio) state machine with support for "Double Blink" triggering to distinguish between natural blinks and control commands.

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
| **Double Blink** | Swipe Up (bottom to top) | Scrolling TikTok / Next page |
| **Head Shake Left** | Swipe Left (right to left) | Back / Exit / Previous item |
| **Head Shake Right** | Swipe Right (left to right) | Forward / Next item |
| **Mouth Open** | Long press at screen center | Trigger long-press menu / Speed up |
| **Mouth Close** | Release long press | Release long-press action |
| **Head Nod** | Single click at screen center | Confirm / Select / Pause |

### Landscape Mode (Video Playback / Fullscreen)

| Facial Gesture | Simulated Action | Use Case |
| :--- | :--- | :--- |
| **Double Blink** | Swipe Right (fast forward) | Video fast forward |
| **Head Nod** | Swipe Left (rewind) | Video rewind |
| **Mouth Open** | Long press at screen center | Trigger player menu / Speed control |
| **Mouth Close** | Release long press | Release long-press action |

---

## 🚀 Quick Start

### Requirements
- Android Device (Running Android 8.0+ / API 26+)
- Physical device for testing (Emulators do not support hardware camera acceleration well)

### 1. Clone and Build

```bash
git clone https://github.com/your-id/FaceControl-Android.git