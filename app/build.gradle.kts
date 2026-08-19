plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hilight.studio"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hilight.studio"
        // HiLight is a Pixel 11 / Android 17 feature. Keeping this floor aligned with the
        // supported hardware prevents installation on devices the renderer cannot support.
        minSdk = 37
        targetSdk = 37
        versionCode = 3
        versionName = "1.0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    sourceSets {
        // The renderer core is shared with the adb host, which is compiled separately into a dex by
        // scripts/build-helper.sh. Including it here also means AdbHelper ships inside the APK, so
        // the adb command can run straight out of the installed app with nothing to push.
        getByName("main").java.srcDir("../core/src")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
