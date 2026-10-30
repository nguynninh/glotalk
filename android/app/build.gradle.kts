plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "vn.onplay.vdubbing"
    ndkVersion = libs.versions.ndk.get()
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "vn.onplay.glotalk"
        // AccessibilityService.takeScreenshot() requires API 30.
        minSdk = 30
        targetSdk = 36
        versionCode = 7
        versionName = "1.0.6"

        testInstrumentationRunner = providers.gradleProperty("vdubbing.testRunner")
            .getOrElse("vn.onplay.vdubbing.HiltTestRunner")
        externalNativeBuild {
            cmake {
                arguments += "-DVDUBBING_WHISPER_VERSION=${libs.versions.whisperCpp.get()}"
            }
        }

        buildConfigField(
            "String",
            "GEMINI_ENDPOINT",
            "\"https://generativelanguage.googleapis.com/v1beta/interactions\"",
        )
        buildConfigField("String", "GEMINI_MODEL", "\"gemini-3.8-flash\"")
    }

    flavorDimensions += "environment"
    productFlavors {
        create("development") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
        }
        create("production") {
            dimension = "environment"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Every locale file must carry the same keys as values/, and plurals must
        // have the quantities each language needs; a lagging file is a bug, not a warning.
        error += listOf("MissingTranslation", "MissingQuantity")
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.compose)
    ksp(libs.hilt.compiler)
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.compiler)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.mlkit.text.recognition.korean)
    implementation(libs.mlkit.text.recognition.devanagari)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
