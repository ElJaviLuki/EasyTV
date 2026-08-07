plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.eljaviluki.easytv"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.eljaviluki.easytv"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Non-debuggable so parental / kids app pickers treat it like a normal TV app.
            isDebuggable = false
            isMinifyEnabled = false
            // Local installs: reuse the debug keystore (no Play signing required).
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

/** Keep APK live guide in sync with secrets/channels_clean.json. */
tasks.register<Copy>("syncChannelsCleanAsset") {
    from(rootProject.file("secrets/channels_clean.json"))
    into(layout.projectDirectory.dir("src/main/assets"))
    onlyIf { rootProject.file("secrets/channels_clean.json").isFile }
}
tasks.named("preBuild").configure { dependsOn("syncChannelsCleanAsset") }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("androidx.appfunctions:appfunctions:1.0.0-alpha10")
    ksp("androidx.appfunctions:appfunctions-compiler:1.0.0-alpha10")
}

// Ensure KSP-generated AppFunction XML assets are packaged into the APK.
androidComponents {
    onVariants { variant ->
        val kspTaskName = "ksp${variant.name.replaceFirstChar { it.uppercase() }}Kotlin"
        afterEvaluate {
            tasks.findByName("merge${variant.name.replaceFirstChar { it.uppercase() }}Assets")
                ?.dependsOn(kspTaskName)
        }
    }
}
