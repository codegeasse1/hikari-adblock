import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.codegeasse1.hikariadblock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.codegeasse1.hikariadblock"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val b64 = System.getenv("RELEASE_KEYSTORE_B64")
            if (!b64.isNullOrBlank()) {
                val dir = layout.buildDirectory.dir("keystore").get().asFile
                dir.mkdirs()
                val f = dir.resolve("release.jks")
                f.writeBytes(Base64.getDecoder().decode(b64))
                storeFile = f
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "hikari"
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs["release"].takeIf { it.storeFile != null } ?: signingConfigs.getByName("debug")
        }
        debug {
            // Distinct applicationId so the debug build installs alongside a
            // release install (different signature) without wiping the user's
            // configured app. FileProvider authority is ${applicationId}.fileprovider.
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // Keep native libs unstripped for consistent, reproducible builds.
            keepDebugSymbols.add("**/libdatastore_shared_counter.so")
            keepDebugSymbols.add("**/libgojni.so")
        }
        resources {
            excludes += "**/sentry-debug-meta.properties"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs = listOf("-XXLanguage:+PropertyParamAnnotationDefaultTargetMode")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Ktor HTTP Client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.logging)

    // Go Tunnel backend (prebuilt gomobile AAR - see README for rebuild recipe)
    implementation(files("libs/tunnel.aar"))

    implementation(libs.timber)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // Koin DI
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // Accompanist
    implementation(libs.accompanist.drawablepainter)

    // WorkManager for auto-update
    implementation(libs.androidx.work.runtime.ktx)

    // ComposeCharts
    implementation(libs.compose.charts)

    // libsu - root shell access for iptables mode
    implementation(libs.core)
    implementation(libs.service)

    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
