# 🚀 FlowZen Setup Guide

This guide will help you set up your own API keys and bot tokens for FlowZen to work properly.

## ⚠️ Security Notice

**Never commit API keys or bot tokens to Git!** The app currently uses placeholder values that you need to replace with your own credentials.

## 🔑 Required API Keys & Tokens

### 1. Google Maps API Key
**What it's for**: Maps functionality, geofencing, and location services

**How to get it**:
1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select existing one
3. Enable the following APIs:
   - Maps SDK for Android
   - Places API
   - Geocoding API
4. Go to "Credentials" → "Create Credentials" → "API Key"
5. Copy the generated API key

**Where to put it**:
- `app/src/main/AndroidManifest.xml` (line 71)
- `app/src/main/java/com/google/ai/edge/gallery/ui/maps/CreateMapScreen.kt` (line 96)

### 2. Telegram Bot Token
**What it's for**: Sending notifications, workflow results, and CamFlow analysis results

**How to get it**:
1. Open Telegram and search for [@BotFather](https://t.me/botfather)
2. Send `/newbot` command
3. Follow the instructions to create your bot
4. Copy the bot token (format: `123456789:ABCdefGHIjklMNOpqrsTUVwxyz`)

**Where to put it**:
- `app/src/main/java/com/google/ai/edge/gallery/ui/workflow/TelegramDeepLinkHelperWork.kt` (line 26)
- `app/src/main/java/com/google/ai/edge/gallery/ui/camflow/camManager.kt` (line 87)
- `app/src/main/java/com/google/ai/edge/gallery/ui/camflow/CamFlowViewModel.kt` (line 64)
- `app/src/main/java/com/google/ai/edge/gallery/ui/maps/TelegramSender.kt` (line 13)
- `app/src/main/java/com/google/ai/edge/gallery/ui/navigation/TelegramHelper.kt` (line 34)

## 📝 Step-by-Step Setup

### Step 1: Get Your API Keys
Follow the instructions above to obtain:
- Google Maps API Key
- Telegram Bot Token

### Step 2: Replace Placeholder Values
In your code, find all instances of these placeholder values and replace them:

```kotlin
// Replace this:
"YOUR_GOOGLE_MAPS_API_KEY_HERE"
"YOUR_GOOGLE_PLACES_API_KEY_HERE"
"YOUR_TELEGRAM_BOT_TOKEN_HERE"
```

**With your actual values**:
```kotlin
// Example:
"AIzaSyC...your_actual_key_here"
"123456789:ABCdefGHIjklMNOpqrsTUVwxyz"
```

### Step 3: Update Bot Username (Optional)
If you want to use a different bot username, update:
- `app/src/main/java/com/google/ai/edge/gallery/ui/workflow/TelegramDeepLinkHelperWork.kt` (line 32)
- `app/src/main/java/com/google/ai/edge/gallery/ui/camflow/CamFlowViewModel.kt` (line 65)

## 🔧 Advanced Configuration

### Using Environment Variables (Recommended)
Instead of hardcoding keys, you can use environment variables:

1. Create a `local.properties` file in your project root (if it doesn't exist)
2. Add your keys:
   ```properties
   MAPS_API_KEY=your_google_maps_api_key_here
   PLACES_API_KEY=your_google_places_api_key_here
   TELEGRAM_BOT_TOKEN=your_telegram_bot_token_here
   ```

3. Update your `build.gradle.kts` to read these values:
   ```kotlin
   android {
       defaultConfig {
           manifestPlaceholders["MAPS_API_KEY"] = project.findProperty("MAPS_API_KEY") ?: ""
           manifestPlaceholders["PLACES_API_KEY"] = project.findProperty("PLACES_API_KEY") ?: ""
       }
   }
   ```

4. Update your code to use BuildConfig:
   ```kotlin
   BuildConfig.MAPS_API_KEY
   BuildConfig.PLACES_API_KEY
   BuildConfig.TELEGRAM_BOT_TOKEN
   ```

### Using BuildConfig
You can also inject keys at build time using BuildConfig:

```kotlin
android {
    buildTypes {
        debug {
            buildConfigField("String", "MAPS_API_KEY", "\"$MAPS_API_KEY\"")
            buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"$TELEGRAM_BOT_TOKEN\"")
        }
        release {
            buildConfigField("String", "MAPS_API_KEY", "\"$MAPS_API_KEY\"")
            buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"$TELEGRAM_BOT_TOKEN\"")
        }
    }
}
```

## 🧪 Testing Your Setup

### Test Google Maps
1. Run the app
2. Navigate to Maps section
3. Check if maps load properly
4. Try creating a geofence

### Test Telegram Bot
1. Start a conversation with your bot
2. Try the deep link setup in Workflow or CamFlow
3. Check if messages are sent successfully

## 🚨 Troubleshooting

### Common Issues

**"Invalid API Key" Error**
- Double-check your Google Maps API key
- Ensure the API is enabled in Google Cloud Console
- Check if billing is enabled for your project

**"Bot Token Invalid" Error**
- Verify your Telegram bot token
- Make sure the bot hasn't been deleted
- Check if the bot is active

**Maps Not Loading**
- Verify internet connection
- Check if Google Play Services is up to date
- Ensure the device has location permissions

**Telegram Messages Not Sending**
- Check if the bot is blocked
- Verify chat ID is correct
- Ensure the bot has permission to send messages

### Debug Tips
1. Check Logcat for error messages
2. Verify all placeholder values are replaced
3. Test with a simple message first
4. Check network connectivity

## 🔒 Security Best Practices

1. **Never commit API keys to Git**
2. **Use environment variables** when possible
3. **Rotate keys regularly**
4. **Limit API key permissions** to only what's needed
5. **Monitor API usage** for unusual activity
6. **Use different keys** for development and production

## 📱 App Permissions

Make sure your app has these permissions in `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
```

## 🎯 Next Steps

After setting up your API keys:
1. Test all major features
2. Configure your workflows
3. Set up geofencing
4. Test CamFlow functionality
5. Configure AI Assistant

## 📞 Need Help?

If you encounter issues:
1. Check the troubleshooting section above
2. Review the error logs in Logcat
3. Verify your API keys are correct
4. Ensure all placeholder values are replaced

---

**Happy automating with FlowZen! 🚀✨**
