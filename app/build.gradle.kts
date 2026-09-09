plugins {
    id("com.android.application")
}

android {
    namespace = "com.simswitch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.simswitch"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.2-phase1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // The interlock and anti-thrash rules are pure logic and are verified here, because several of
    // their input combinations (Wi-Fi off, mid-call) cannot be produced on the phone from adb.
    testImplementation("junit:junit:4.13.2")
}
