/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
plugins {
  alias(libs.plugins.android.application)                    // ✅ keep this
  alias(libs.plugins.kotlin.android)                         // ✅
  alias(libs.plugins.kotlin.compose)                         // ✅
  alias(libs.plugins.kotlin.serialization)                   // ✅
  alias(libs.plugins.protobuf)                               // ✅
  id("com.google.gms.google-services")
}
android {
  namespace = "com.google.ai.edge.gallery"
  compileSdk = 35
  defaultConfig {
    applicationId = "com.google.aiedge.gallery"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0.4"
    // Needed for HuggingFace auth workflows.
    manifestPlaceholders["appAuthRedirectScheme"] = "com.google.ai.edge.gallery.oauth"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "META-INF/DEPENDENCIES"
      excludes += "META-INF/LICENSE"
      excludes += "META-INF/LICENSE.txt"
      excludes += "META-INF/license.txt"
      excludes += "META-INF/NOTICE"
      excludes += "META-INF/NOTICE.txt"
      excludes += "META-INF/notice.txt"
      excludes += "META-INF/ASL2.0"
      excludes += "META-INF/*.kotlin_module"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += "-Xcontext-receivers"
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  sourceSets {
    getByName("main") {
      assets {
        srcDirs("src\\main\\assets", "src\\main\\assets")
      }
    }
  }
}
dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.mediapipe.tasks.text)
  implementation(libs.mediapipe.tasks.genai)
  implementation(libs.mediapipe.tasks.imagegen)
  implementation(libs.commonmark)
  implementation(libs.richtext)

  // Use standard TensorFlow Lite for FaceNet model (removed Google Edge TFLite to avoid conflicts)
  implementation("org.tensorflow:tensorflow-lite:2.14.0")
  implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
  implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

  // ML Kit Face Detection
  implementation("com.google.mlkit:face-detection:16.1.6")
  implementation("com.google.code.gson:gson:2.10.1")
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)

  // Location services for geofencing
  implementation("com.google.android.gms:play-services-location:21.0.1")

  // Email sending via SMTP (JavaMail)
  implementation("javax.mail:mail:1.4.7")

  // OSMDroid for maps (free, no billing needed)
  implementation("org.osmdroid:osmdroid-android:6.1.18")

  // Google Maps Compose for maps UI
  implementation("com.google.maps.android:maps-compose:2.11.4")
  implementation("com.google.android.gms:play-services-maps:18.2.0")

  // OkHttp for Telegram API
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("androidx.preference:preference:1.2.1")

  // Google Photos API
  implementation("com.google.api-client:google-api-client-android:2.2.0")
  implementation("com.google.http-client:google-http-client-gson:1.42.2")

  // Firebase dependencies for authentication
  implementation(platform("com.google.firebase:firebase-bom:32.7.2"))
  implementation("com.google.firebase:firebase-auth")
  implementation("com.google.android.gms:play-services-auth:20.7.0")
  implementation("com.google.api-client:google-api-client-android:1.35.0")
  implementation("com.google.apis:google-api-services-gmail:v1-rev110-1.25.0")
  implementation("com.squareup.retrofit2:retrofit:2.9.0")
  implementation("com.squareup.retrofit2:converter-gson:2.9.0")
  implementation("androidx.lifecycle:lifecycle-service:2.9.2")
  implementation("com.google.android.libraries.places:places:2.7.0")

  // For JSON serialization
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")

  implementation("com.google.mlkit:text-recognition:16.0.0")
  implementation("com.google.mlkit:image-labeling:17.0.7")

  // For coroutines
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
  implementation("androidx.exifinterface:exifinterface:1.3.6")
  implementation(libs.digital.ink.recognition)

  // Testing dependencies
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
}
protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks { all().forEach { it.plugins { create("java") { option("lite") } } } }
}