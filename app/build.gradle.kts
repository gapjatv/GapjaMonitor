plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tv.gapja.monitor"
    compileSdk = 35

    defaultConfig {
        // 설치되는 앱 이름표 (스토어·설정 화면에 보이는 값). 코드 폴더 이름(namespace)과는 따로다.
        applicationId = "com.unbbal.bossalarm"
        // 화면 캡처(MediaProjection)와 포그라운드 서비스 타입이 필요하므로 최소 29.
        minSdk = 29
        targetSdk = 35
        versionCode = 9
        versionName = "1.8"

        // 어떤 ABI 를 담을지는 아래 splits 에서 정한다 (기기 종류별 APK)
    }

    // 기기 종류별로 APK 를 따로 만든다 (하나로 합치면 100MB 가 넘는다)
    //   arm64-v8a   → 요즘 폰·태블릿 (대부분 이것)
    //   armeabi-v7a → 오래된 32비트 폰
    //   x86_64      → 뮤뮤플레이어 같은 PC 앱플레이어
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    // ⚠️ 서명 키를 고정한다.
    //    GitHub 서버는 빌드할 때마다 디버그 키를 새로 만들기 때문에, 키를 고정하지 않으면
    //    새 버전을 깔 때 '기존 앱과 충돌' 오류가 나서 지우고 다시 깔아야 했다.
    //    debug.keystore 는 안드로이드 표준 디버그 키 형식(비밀번호 android)이다.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
