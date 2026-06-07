# 🌀 FlowZen — Advanced Android Workflow Automation App

[![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-0095D5?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=android)](https://developer.android.com/jetpack/compose)
[![TensorFlow Lite](https://img.shields.io/badge/TensorFlow%20Lite-FF6F00?style=for-the-badge&logo=tensorflow&logoColor=white)](https://www.tensorflow.org/lite)
[![Room DB](https://img.shields.io/badge/Room%20DB-3DDC84?style=for-the-badge&logo=sqlite&logoColor=white)](https://developer.android.com/training/data-storage/room)

A premium, production-ready, on-device AI-powered workflow automation and smart automation application built for the Android platform. **FlowZen** integrates multiple background processes, geofencing capabilities, real-time computer vision, local machine learning models, and messaging integrations to help users automate day-to-day tasks directly from their mobile device.

* **On-Device LLM Integration**: Uses local 2B parameters model execution for privacy-first intent analysis.
* **Complex Multi-Step Automations**: Runs background schedules with Google Maps, Gmail, and Telegram.

---

## 🏗️ Architecture & Component Routing

FlowZen is engineered utilizing clean architecture principles adhering to the **MVVM (Model-View-ViewModel)** design pattern. It decouples UI layouts, state management, local storage, and background tasks.

```
                      [ User Input / Intent ]
                                 │
                        [ Jetpack Compose UI ]
                                 │
                     [ ViewModels (MVVM Layer) ]
                                 │
                ┌────────────────┴────────────────┐
                ▼                                 ▼
      [ On-Device AI Engine ]           [ Repositories / Data Layer ]
       (TensorFlow Lite / LLM)           ┌───────┼────────┬───────┐
                                         │       │        │       │
                                     [ Room ] [ Maps ] [ Gmail ] [ Telegram ]
```

### Component Architecture
* **UI Layer**: Fully declarative screens, dynamically built using Jetpack Compose and structured navigation.
* **Background Layer**: Managed via Android Jetpack WorkManager to execute robust tasks (e.g. Email summarization) with reliability across device reboots and low-battery states.
* **On-Device AI Layer**: Runs TensorFlow Lite and local model files allowing low-latency processing without cloud-based API dependencies.

---

## ⚡ Tech Stack & Core Libraries

* **Language**: [Kotlin](https://kotlinlang.org/) — fully asynchronous programming model using Coroutines & StateFlow.
* **UI Engine**: [Jetpack Compose](https://developer.android.com/jetpack/compose) — modern, declarative UI layout system.
* **Local Persistence**: [Room DB](https://developer.android.com/training/data-storage/room) — reactive SQLite database layer.
* **Background Tasks**: [Jetpack WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) — robust scheduler with execution constraints.
* **Computer Vision & ML**: ML Kit, FaceNet for face recognition/clustering, and TensorFlow Lite for local classification.
* **Integrations**: Google Sign-In, Gmail API for message synchronization, Google Maps SDK, and Telegram Bot API.

---

## 🌟 Key Features & Components

### 1. Intelligent AI Assistant
An on-device chat interface utilizing a local LLM to interpret user intents in natural language. For example, telling the assistant *"Forward my urgent emails to Telegram every hour"* automatically generates, schedules, and registers the workflow in the background.

### 2. CamFlow (Real-time Vision Pipeline)
A CameraX-based processing pipeline that runs computer vision models locally. Features:
* **Face Identification & Clustering**: Utilizes FaceNet embedding comparisons to detect faces and group them into automatic smart albums.
* **Dynamic OCR**: Google ML Kit-powered text recognition to extract document contents.
* **Object Tagging & Barcodes**: Instantly categorizes camera feeds and scans barcodes/QRs for fast data extraction.

### 3. Maps & Geofencing Automation
Integrates Google Maps to let users specify physical geofences. The app tracks entry and exit transitions, automatically dispatching webhook notifications, Telegram alerts, or custom Gmail logs.

### 4. Background Summarizer
A background automation service that pulls emails from a Gmail inbox under user authorization, uses local NLP summarization pipelines to condense them, and forwards the results to designated Telegram accounts or email addresses.

---

## 🚀 Quick Start Guide

### 1. Prerequisites
Ensure you have the following configurations set up:
* Android Studio (Ladybug or newer recommended)
* Android SDK 26+ (Android 8.0+)
* A physical Android device (recommended for camera and geofencing testing)
* Google Play Services configured on the device

### 2. Configuration Setup
Create a `local.properties` file in the root directory and add your Google Maps SDK key:
```properties
MAPS_API_KEY=your_google_maps_sdk_key_here
```

Configure Telegram Bot credentials inside the respective helper classes:
* `TelegramDeepLinkHelperWork.kt`
* `TelegramDeepLinkHelper.kt`

### 3. Build & Run
Compile and deploy the debug APK via Gradle:
```bash
# Clean project
./gradlew clean

# Build debug APK
./gradlew assembleDebug
```

---

## 🧭 Project Directory Layout

```
app/src/main/java/com/google/ai/edge/gallery/
├── data/                    # Data models, DB Entities, Room DAOs
├── ui/                      # Jetpack Compose Screens and UI Component files
│   ├── aiassistant/         # AI Chat Screen & Local LLM Helpers
│   ├── camflow/             # Vision Pipeline UI & Camera ViewModels
│   ├── maps/                # Google Maps integration and Geofencing UI
│   ├── workflow/            # Active automations and Scheduling view list
│   ├── modelmanager/        # Model configuration download & allowlist UI
│   └── navigation/          # Type-safe routing controls
├── services/                # Background WorkflowExecutionService & WorkManager classes
└── utils/                   # TensorFlow Lite utilities, ML Kit wrappers, OCR utils
```

---

## 📝 Configuration Allowlist

The application manages local models dynamically through `model_allowlist.json` located in the main asset directory. Models can be downloaded from external edges or loaded locally based on user configuration requirements.

---

## 📜 License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for complete details.
