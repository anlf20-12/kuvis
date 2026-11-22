plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.t.kuvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.t.kuvis"
        minSdk = 26
        targetSdk = 34
        // GitHub Actions에서 빌드하면 100+실행번호 (앱의 업데이트 확인과 맞춤)
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = if (run != null) 100 + run else 4
        versionName = if (run != null) "3.1.$run" else "3.1"
    }

    // 고정 서명 키: 새로 빌드해도 지우지 않고 덮어 설치(설정·API 키 유지)
    signingConfigs {
        getByName("debug") {
            storeFile = file("kuvis.keystore")
            storePassword = "jarvis123"
            keyAlias = "jarvis"
            keyPassword = "jarvis123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
}
