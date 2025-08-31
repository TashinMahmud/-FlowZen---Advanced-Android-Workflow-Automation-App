# 🚀 FlowZen - Advanced Android Workflow Automation App

A comprehensive Android application that integrates multiple automation capabilities, workflow management, and smart features using on-device AI models.

## 🚀 Features

### 🤖 **AI Assistant**
- **Intelligent Chat Interface**: Advanced AI assistant with natural language processing
- **Workflow Creation**: Automatically creates workflows based on user intent
- **Smart Intent Analysis**: Understands user requests and converts them to automated workflows
- **Multi-Model Support**: Uses optimized 2B models for fast, on-device processing

### 🔄 **Workflow Automation**
- **Email Summarization**: Automatically fetch, summarize, and forward Gmail emails
- **Multi-Destination Support**: Send to Gmail, Telegram, or Deep Link recipients
- **Advanced Filtering**: Filter emails by sender and subject keywords
- **Scheduling**: Set up recurring workflows with custom intervals
- **Real-time Progress**: Live progress tracking during workflow execution
- **Background Execution**: Runs workflows in the background with WorkManager

### 📱 **Telegram Integration**
- **Deep Link Setup**: Easy one-click Telegram bot connection
- **Automatic Chat ID Detection**: No manual chat ID entry required
- **Workflow Notifications**: Send workflow results directly to Telegram
- **CamFlow Integration**: Share camera analysis results via Telegram

### 📷 **CamFlow (Camera + Workflow)**
- **AI Image Analysis**: Analyze images using on-device AI models
- **Smart Prompts**: Custom prompts for specific image analysis tasks
- **Multi-Destination**: Send results to Gmail or Telegram
- **Session Management**: Save and manage analysis sessions
- **Background Processing**: Process images in background service

### 🗺️ **Maps & Geofencing**
- **Interactive Maps**: Google Maps integration with custom markers
- **Geofence Creation**: Set up location-based triggers
- **Location Sharing**: Share geofences via deep links
- **Telegram Alerts**: Get notified when entering/exiting geofences
- **History Tracking**: View past geofence activities

### 💬 **AI Chat Features**
- **Text & Image AI**: Ask questions about images using AI
- **Multi-Model Support**: Choose from various AI models
- **Conversation History**: Maintain chat history across sessions
- **Model Management**: Download, manage, and switch between models

### 📧 **Gmail Integration**
- **Email Fetching**: Retrieve emails from Gmail inbox
- **AI Summarization**: Use AI to summarize email content
- **Smart Forwarding**: Forward emails with AI-generated summaries
- **Filter Support**: Filter emails by sender and subject

## 🛠️ Technical Stack

### **Core Technologies**
- **Android Jetpack Compose**: Modern UI toolkit
- **Kotlin Coroutines**: Asynchronous programming
- **WorkManager**: Background task scheduling
- **Room Database**: Local data persistence
- **SharedPreferences**: Lightweight data storage

### **AI & ML**
- **On-Device AI Models**: Local inference for privacy
- **TensorFlow Lite**: Model execution engine
- **Model Management**: Dynamic model loading and switching
- **Multi-Task Support**: Different models for different tasks

### **APIs & Services**
- **Google Maps API**: Maps and geofencing
- **Gmail API**: Email integration
- **Telegram Bot API**: Messaging and notifications
- **Google Sign-In**: Authentication

### **Architecture**
- **MVVM Pattern**: Clean architecture with ViewModels
- **Repository Pattern**: Data layer abstraction
- **Dependency Injection**: Modular component design
- **Navigation Component**: Type-safe navigation

## 📱 Screenshots

*[Screenshots will be added here]*

## 🚀 Getting Started

### Prerequisites
- Android Studio Arctic Fox or later
- Android SDK 26+ (API level 26)
- Google Play Services
- Internet connection for model downloads

### Installation

1. **Clone the repository**
   ```bash
   git clone [repository-url]
   cd gallery-main/Android/src
   ```

2. **Configure API Keys**
   - Add your Google Maps API key to `local.properties`:
     ```properties
     MAPS_API_KEY=your_google_maps_api_key_here
     ```
   - Configure Telegram bot credentials in the respective helper classes

3. **Build and Run**
   ```bash
   ./gradlew assembleDebug
   ```

### Configuration

#### **Telegram Bot Setup**
1. Create a Telegram bot via @BotFather
2. Get your bot token and username
3. Update the bot credentials in:
   - `TelegramDeepLinkHelperWork.kt`
   - `TelegramDeepLinkHelper.kt`
   - `CamFlowViewModel.kt`

#### **Gmail API Setup**
1. Enable Gmail API in Google Cloud Console
2. Configure OAuth 2.0 credentials
3. Add required scopes for email access

## 📖 Usage Guide

### **AI Assistant**
1. Open the AI Assistant from the main screen
2. Type natural language requests like:
   - "Summarize emails and send to my Gmail every hour"
   - "Create a workflow to forward urgent emails to Telegram"
3. The AI will automatically create and configure workflows

### **Workflow Management**
1. Go to Workflow Manager
2. Create new workflows or edit existing ones
3. Configure:
   - **Destination**: Gmail, Telegram, or Deep Link
   - **Filters**: Sender and subject filters
   - **Schedule**: Execution frequency and timing
   - **Expiration**: When to stop the workflow

### **CamFlow**
1. Open CamFlow from the main screen
2. Take or select images
3. Enter analysis prompts
4. Choose destination (Gmail/Telegram)
5. Process and send results

### **Maps & Geofencing**
1. Open Maps from the main screen
2. Set location and radius for geofence
3. Configure Telegram notifications
4. Share geofences with others via deep links

## 🔧 Development

### **Project Structure**
```
app/src/main/java/com/google/ai/edge/gallery/
├── data/                    # Data models and tasks
├── ui/                      # UI components
│   ├── aiassistant/         # AI Assistant screens
│   ├── camflow/            # CamFlow functionality
│   ├── maps/               # Maps and geofencing
│   ├── workflow/           # Workflow management
│   ├── modelmanager/       # Model management
│   └── navigation/         # Navigation components
├── services/               # Background services
└── utils/                  # Utility classes
```

### **Key Components**

#### **Workflow System**
- `WorkflowViewModel`: Manages workflow state and execution
- `WorkflowExecutionService`: Background workflow execution
- `CreateWorkflowScreen`: Workflow creation and editing UI

#### **AI Integration**
- `AiAssistantFunctions`: Core AI logic and intent analysis
- `LlmChatModelHelper`: Model management and inference
- `ModelManagerViewModel`: Model downloading and initialization

#### **Telegram Integration**
- `TelegramDeepLinkHelperWork`: Workflow deep link management
- `TelegramDeepLinkHelper`: CamFlow deep link management
- `TelegramSender`: Message sending utilities

### **Adding New Features**

1. **New Task Type**
   - Add to `TaskType` enum in `Tasks.kt`
   - Create task definition
   - Add to `TASKS` list
   - Update `ModelManagerViewModel`

2. **New UI Screen**
   - Create Composable function
   - Add navigation route
   - Update `GalleryNavGraph`

3. **New AI Model**
   - Add model to `model_allowlist.json`
   - Configure task compatibility
   - Update model initialization logic

## 📚 Documentation

Additional documentation is available in the `docs/` folder:
- `CHANGES_SUMMARY.md`: Detailed change history
- `README_summerizer.md`: Email summarization guide
- `TELEGRAM_SETUP.md`: Telegram bot setup instructions

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Google AI Edge team for the base framework
- Telegram Bot API for messaging integration
- Google Maps API for location services
- TensorFlow Lite for on-device AI inference

## 📞 Support

For support and questions:
- Create an issue in the repository
- Check the documentation in the `docs/` folder
- Review the code comments for implementation details

---
   
 