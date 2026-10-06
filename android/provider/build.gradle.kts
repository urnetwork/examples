// The Android provider app (PROVIDER_CONTRACT.md, "Android"): one activity and the foreground
// service that owns the provider device, on the gomobile AAR (package com.bringyour.sdk).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "8.11.1"
    id("org.jetbrains.kotlin.android") version "2.3.21"
}

android {
    // replace with your own application id before you ship
    namespace = "com.example.urnetwork.provider"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.urnetwork.provider"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // The SDK: the published AAR with -PurnetworkSdkVersion=<version>, otherwise a local build of
    // the AAR, libs/URnetworkSdk.aar by default or the path in -PurnetworkSdkAar=<path>.
    val urnetworkSdkVersion = findProperty("urnetworkSdkVersion")
    if (urnetworkSdkVersion != null) {
        implementation("io.ur:urnetwork-sdk-android:$urnetworkSdkVersion")
    } else {
        implementation(files(findProperty("urnetworkSdkAar") ?: "libs/URnetworkSdk.aar"))
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
