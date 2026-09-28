plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.localcharacter.chat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.localcharacter.chat"
        minSdk = 24
        targetSdk = 35
        versionCode = 50
        versionName = "5.0.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("local") {
            storeFile = file("local-chat.keystore")
            storePassword = "localchat2026"
            keyAlias = "localchat"
            keyPassword = "localchat2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("local")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("local")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    sourceSets.getByName("test").java.srcDirs("../tests/core", "../tests/junit")

    testOptions {
        unitTests.isReturnDefaultValues = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
