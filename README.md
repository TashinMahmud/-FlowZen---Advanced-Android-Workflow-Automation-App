# 🌀 FlowZen — On-Device AI Android Workflow Automation

<div align="center">

[![Android SDK](https://img.shields.io/badge/Android-26%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#prerequisites)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-0095D5?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Compose-UI-4285F4?style=for-the-badge&logo=android)](#technical-architecture)
[![TensorFlow Lite](https://img.shields.io/badge/TFLite-Edge_AI-FF6F00?style=for-the-badge&logo=tensorflow&logoColor=white)](#on-device-ai-engine)
[![Download APK](https://img.shields.io/badge/Download-APK-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/TashinMahmud/-FlowZen---Advanced-Android-Workflow-Automation-App/releases)

---

**FlowZen** is a premium, privacy-first Android application that automates complex local workflows on-device. By pairing local machine learning models (including a 2B parameter Large Language Model) with OS-level sensors and background execution layers, FlowZen converts natural language intents into automated tasks—without dispatching personal data to external clouds.

</div>

## 🛠️ Technical Architecture

FlowZen is structured around clean Architecture principles utilizing the **MVVM (Model-View-ViewModel)** design pattern. State flows reactively from Room databases and repositories through ViewModels to declarative Jetpack Compose UI trees.

```
+-------------------------------------------------------------+
|                      JETPACK COMPOSE UI                     |
|  [ AI Assistant ]   [ CamFlow ]   [ Maps/Geofence ]  ...   |
+------------------------------+------------------------------+
                               | (UiState flow)
                               v
+-------------------------------------------------------------+
|                      VIEWMODELS (MVVM)                      |
|   Exposes StateFlows and handles UI action triggers         |
+------------------------------+------------------------------+
                               | (Data Operations)
                               v
+-------------------------------------------------------------+
|                     REPOSITORIES LAYER                      |
|  [ DownloadRepository ]  [ DataStoreRepository ]            |
+--------------+-----------------------+----------------------+
               |                       |
               v                       v
+--------------+-------+       +-------+----------------------+
|    ON-DEVICE AI      |       |     LOCAL DATA/SERVICES      |
|  - TensorFlow Lite   |       |  - Jetpack WorkManager       |
|  - Local 2B LLM      |       |  - Google Maps & Geofences   |
|  - ML Kit (OCR/Face) |       |  - Gmail & Telegram API      |
+----------------------+       +------------------------------+
```

### Core Code Modules & Responsibilities
*   `data/` Layer:
    *   [`Tasks.kt`](Android/app/src/main/java/com/google/ai/edge/gallery/data/Tasks.kt): Defs for available workflows, parsing rules, and task parameter mappings.
    *   [`ModelAllowlist.kt`](Android/app/src/main/java/com/google/ai/edge/gallery/data/ModelAllowlist.kt): Dynamic loading configuration metadata mapping local/remote task models.
    *   [`DownloadRepository.kt`](Android/app/src/main/java/com/google/ai/edge/gallery/data/DownloadRepository.kt): Handles multi-threaded file downloads and models checksum validation.
*   `ui/` Components:
    *   `aiassistant/`: Intent translator chat screen which converts natural speech phrases into background workflows.
    *   `camflow/`: Real-time vision analysis view, running FaceNet-based clustering and ML Kit OCR.
    *   `maps/`: Location-based geofence anchors configuration and historical mapping data feeds.

---

## ⚡ Core Integration Interfaces

<details>
<summary><b>🤖 Telegram Deep Link & Notification Hub</b></summary>

FlowZen coordinates notifications using the Telegram Bot API. It implements a deep-link handshaking script (`TelegramDeepLinkHelper.kt`) to resolve client `chat_id` keys securely without requiring manual developer portal values from players.
*   **Workflow trigger**: Sends status summaries or image captures directly to your Telegram chat.
</details>

<details>
<summary><b>📧 Gmail API Summarizer Flow</b></summary>

The app uses Google Sign-In and local OAuth handles to read recent inbox headers, filters messages according to custom rules, translates content through the local NLP model, and triggers background forwards.
</details>

<details>
<summary><b>📂 On-Device AI Models & Allowlist</b></summary>

Dynamic inference models are managed via `model_allowlist.json`. The application validates models locally before load:
*   **FaceNet Classifier**: Generates 128-dimensional embedding vectors for real-time face matching and grouping.
*   **Edge LLM**: Local model engine executing reasoning directly inside the Android Sandbox environment.
</details>

---

## 🚀 Getting Started

### 1. Requirements
*   Android Studio Ladybug or newer
*   Android SDK 26+ (API Level 26 or above)
*   Physical Android device configured with Google Play Services (required for Maps and Geofencing coordinates)

### 2. Configurations Setup
Create a `local.properties` file in your `Android/` project root:
```properties
MAPS_API_KEY=your_google_maps_sdk_key_here
```

Configure your Telegram Bot token inside the helper configurations:
-   `TelegramDeepLinkHelperWork.kt`
-   `TelegramDeepLinkHelper.kt`

### 3. Compilation
Build the production-ready Release APK via the Gradle CLI:
```bash
cd Android/
./gradlew assembleRelease
```
The compiled APK will be output to: `Android/app/build/outputs/apk/release/app-release.apk`.

---

## 📜 License

Licensed under the [Apache License 2.0](LICENSE).
