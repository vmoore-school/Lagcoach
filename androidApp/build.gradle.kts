import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Version comes from the git tag in CI (e.g. v1.2.3 -> 1.2.3 / 10203).
val versionNameProp = (findProperty("lagcoach.versionName") as String?) ?: "0.0.0-dev"
val versionCodeProp = (findProperty("lagcoach.versionCode") as String?)?.toInt() ?: 1

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "app.lagcoach"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.lagcoach"
        minSdk = 29
        targetSdk = 36
        versionCode = versionCodeProp
        versionName = versionNameProp
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "LAGCOACH_KEYSTORE_PATH")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "LAGCOACH_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "LAGCOACH_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "LAGCOACH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The map's native code is per-ABI; phones are all arm64 now.
            ndk { abiFilters += "arm64-v8a" }
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") } // x86_64 for the emulator
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    splits {
        abi {
            isEnable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    dependenciesInfo {
        // Obtainium / F-Droid style: don't embed the Google-encrypted dependency blob.
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.coroutines.android)
}
