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
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.core)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.benchmark.junit4)
}
