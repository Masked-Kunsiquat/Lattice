plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.github.maskedkunisquat.lattice.core.logic"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        // AndroidBenchmarkRunner is a drop-in replacement for AndroidJUnitRunner that adds
        // IsolationActivity support. Required to avoid ACTIVITY-MISSING benchmark errors.
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        // Library modules cannot set isDebuggable=false on their test APK in AGP 9.x.
        // Suppress the DEBUGGABLE error so benchmarks run; numbers are valid for relative
        // comparisons but will be slower than a release-signed app module benchmark.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "DEBUGGABLE"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("benchmark") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    sourceSets {
        // Make the Llama-3.2-3B shards (gitignored, in :app assets) available to
        // CognitiveLoopBenchmark without duplicating the 3.5 GB files.
        // The benchmark's assumeTrue gate skips gracefully if shards are absent.
        getByName("androidTest") {
            assets.srcDirs("../app/src/main/assets")
        }
    }
    testOptions {
        // Log.w (and other Android stubs) throw RuntimeException by default on the desktop JVM.
        // returnDefaultValues silences them so unit tests can exercise EmbeddingProvider's
        // zero-vector fallback path without a full Android environment.
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core-data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.room.ktx)
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // org.json is bundled in the Android SDK but not on the desktop JVM.
    // The standalone artifact mirrors the Android API exactly, enabling unit tests
    // that exercise JSONObject-based serialisation without an emulator.
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.core)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.benchmark.junit4)
    androidTestImplementation(libs.androidx.work.testing)
}
