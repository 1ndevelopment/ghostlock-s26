plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "indev.ghostlock.s26"
    compileSdk = 35

    defaultConfig {
        applicationId = "indev.ghostlock.s26"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Exploit is arm64-only (S26 family). x86 emulators can still
            // install the app for UI work; staging will report unsupported.
            abiFilters += listOf("arm64-v8a")
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
        debug {
            applicationIdSuffix = ".debug"
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
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // ksud + prebuilts are large; keep them uncompressed so we can
        // mmap/copy them straight out of the APK if needed.
        jniLibs {
            useLegacyPackaging = false
        }
    }

    buildFeatures {
        viewBinding = true
    }

    // Optional CMake rebuild of preload.so from exploit/src. Enabled only when
    // a runnable SDK cmake + NDK are present (x86_64 workstation). On hosts
    // without them (e.g. on-device builds) the APK falls back to the
    // stage-assets.sh outputs in src/main/assets - no native step needed.
    // Override with -PforceNative=true to force-enable.
    val sdkDirProp = project.findProperty("sdk.dir") as String?
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: ""
    val sdkCmakePresent = file("$sdkDirProp/cmake").exists()
    val forceNative = (project.findProperty("forceNative") as String?) == "true"
    if (sdkCmakePresent || forceNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.lifecycle.runtime)
    implementation(libs.coroutines.android)
    // Shizuku: preferred execution path (uid 2000 shell, same context as
    // the README's `adb shell` flow). Optional at runtime - the app works
    // without it, but the in-app fallback is less likely to succeed.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
