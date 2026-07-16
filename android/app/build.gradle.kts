import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun getGitHash(): String {
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--short", "HEAD"))
        process.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        "unknown"
    }
}

fun getGitCommitCount(): Int {
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("git", "rev-list", "--count", "HEAD"))
        process.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 1
    } catch (e: Exception) {
        1
    }
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.thegreatequalizer.app"
    compileSdk = 35

    signingConfigs {
        getByName("debug") {
            // uses default debug keystore
        }
        create("release") {
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile", ""))
            storePassword = keystoreProperties.getProperty("storePassword", "")
            keyAlias = keystoreProperties.getProperty("keyAlias", "")
            keyPassword = keystoreProperties.getProperty("keyPassword", "")
        }
    }

    defaultConfig {
        applicationId = "com.thegreatequalizer.app"
        minSdk = 28
        targetSdk = 35
        versionCode = getGitCommitCount()
        versionName = "1.4.1"

        buildConfigField("String", "GIT_HASH", "\"${getGitHash()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "the_great_equalizer.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260522")
}
