plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.k7closedlens"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.k7closedlens"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Android 15+ 16 KB page size support
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)

    // Android 15 16KB Page Alignment용 최신 네이티브 이미지 처리 라이브러리 오버라이드
    implementation("androidx.camera:camera-core:1.4.2")

    // Google ML Kit Text Recognition V2 (완전 번들형 오프라인 OCR: 80+개국어)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // NLLB-200 번역 엔진용 ONNX Runtime (Android 15 16KB Page Alignment 완벽 지원 버전)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
