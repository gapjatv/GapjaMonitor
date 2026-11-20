plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.gapja.monitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "tv.gapja.monitor"
        // 화면 캡처(MediaProjection)와 포그라운드 서비스 타입이 필요하므로 최소 29.
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        // OpenCV 네이티브 라이브러리를 필요한 ABI만 담아 APK 크기를 줄인다.
        // (요즘 안드로이드 폰은 전부 arm64-v8a 다. 구형 32비트 폰도 쓰려면 armeabi-v7a 를 남겨 둔다)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            // 난독화는 OpenCV 리플렉션과 충돌할 수 있어 기본적으로 끈다.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // ───────────────────────────────────────────────────────────────
    // OpenCV — 템플릿 매칭(matchTemplate)과 Canny 에 쓴다.
    //
    // OpenCV 4.9.0 부터 Maven Central 에 안드로이드용 AAR 이 올라와 있어
    // 아래 한 줄로 끝난다. 버전을 바꾸려면 숫자만 고치면 된다.
    //
    // ⚠️ 만약 이 좌표를 찾지 못한다는 오류가 나면 (사내망 등에서 접근이 막힌 경우)
    //    README 의 '② OpenCV 를 직접 넣는 방법' 을 따르세요.
    // ───────────────────────────────────────────────────────────────
    implementation("org.opencv:opencv:4.10.0")
}
