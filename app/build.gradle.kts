import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.nathanhanapps.appdual"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nathanhanapps.appdual"
        minSdk = 31
        targetSdk = 36
        versionCode = 5
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing key lives outside the repo (sibling AppDualZukuKey/ folder) and is
    // never committed. Builds without it (e.g. a fresh clone) just skip release signing.
    val keystorePropertiesFile = rootProject.file("../AppDualZukuKey/keystore.properties")
    val hasReleaseSigning = keystorePropertiesFile.exists()
    val keystoreProperties = Properties().apply {
        if (hasReleaseSigning) load(keystorePropertiesFile.inputStream())
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            aidl.directories.add("src/main/aidl")
        }
    }

    buildTypes {
        debug {
            // v2 physical-test package coexists with both AppDual v1.5 and the first
            // one-off dhizukutest build, whose ephemeral CI debug key cannot be reused.
            applicationIdSuffix = ".dhizukutest2"
            versionNameSuffix = "-dhizukutest2"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.shimmer)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.dhizuku.api)
    implementation(libs.hidden.api.bypass)
}
