plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.actionalarm.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.actionalarm.app"
        minSdk = 27          // Android 8.1 이상
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // 개인용 고정 서명 키: 새 버전을 기존 앱 위에 덮어 설치할 수 있게 함
    signingConfigs {
        create("personal") {
            storeFile = file("actionalarm.keystore")
            storePassword = "actionalarm"
            keyAlias = "actionalarm"
            keyPassword = "actionalarm"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// 외부 라이브러리 없이 안드로이드 기본 API만 사용합니다.
dependencies {
}
